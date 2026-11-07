package com.fongmi.android.tv.ui.dialog;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.MediaMetadata;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.AssrtApi;
import com.fongmi.android.tv.bean.AssrtSubtitle;
import com.fongmi.android.tv.bean.AssrtSubtitleFile;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.databinding.DialogSubtitleSearchBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.source.EpisodeLabel;
import com.fongmi.android.tv.source.EpisodeLabelParser;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.SubtitleDownload;
import com.fongmi.android.tv.utils.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Search, select and load one subtitle while keeping resources tied to the current media. */
public final class SubtitleSearchDialog extends BasePlaybackSelectionDialog {

    /** Per-dialog services also let the disposable preview use a local fixture server. */
    public interface Services {
        boolean isConfigured();
        void search(String query, int position, Object tag, AssrtApi.SearchListener listener);
        void detail(long id, Object tag, AssrtApi.DetailListener listener);
        void download(AssrtSubtitleFile file, Object tag, SubtitleDownload.Listener listener);
        void cancel(Object tag);
    }

    private static final Services DEFAULT_SERVICES = new Services() {
        @Override public boolean isConfigured() { return AssrtApi.isConfigured(); }
        @Override public void search(String query, int position, Object tag, AssrtApi.SearchListener listener) {
            AssrtApi.search(query, position, tag, listener);
        }
        @Override public void detail(long id, Object tag, AssrtApi.DetailListener listener) {
            AssrtApi.detail(id, tag, listener);
        }
        @Override public void download(AssrtSubtitleFile file, Object tag, SubtitleDownload.Listener listener) {
            SubtitleDownload.download(file, tag, listener);
        }
        @Override public void cancel(Object tag) {
            AssrtApi.cancel(tag);
            SubtitleDownload.cancel(tag);
        }
    };

    private enum Page { RESULTS, FILES, EXTRACTED }

    private Object requestTag = new Object();
    private final Rows adapter = new Rows();
    private Services services = DEFAULT_SERVICES;
    private PlayerManager player;
    private DialogSubtitleSearchBinding binding;
    private List<AssrtSubtitle> results = List.of();
    private List<Sub> downloaded = List.of();
    private AssrtSubtitle selectedSubtitle;
    private String mediaKey;
    private String mediaUrl;
    private String mediaTitle;
    private String mediaEpisode;
    private String lastQuery = "";
    private int position;
    private int generation;
    private boolean hasMore;
    private boolean busy;
    private Page page = Page.RESULTS;

    public static void show(FragmentActivity activity, PlayerManager player) {
        show(activity, player, null);
    }

    public static void show(FragmentActivity activity, PlayerManager player, @Nullable Services services) {
        if (activity.getSupportFragmentManager().findFragmentByTag("subtitle-search") != null) return;
        SubtitleSearchDialog dialog = new SubtitleSearchDialog();
        dialog.player = player;
        if (services != null) dialog.services = services;
        dialog.show(activity.getSupportFragmentManager(), "subtitle-search");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogSubtitleSearchBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.recycler.setAdapter(adapter);
        binding.recycler.setItemAnimator(null);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 8));
        binding.source.setTextSize(12);
        if (player == null || player.isReleased() || player.isEmpty()) {
            showMediaChanged();
            return;
        }
        mediaKey = player.getKey();
        mediaUrl = player.getUrl();
        MediaMetadata metadata = player.getMetadata();
        mediaTitle = text(metadata == null ? null : metadata.title);
        mediaEpisode = text(metadata == null ? null : metadata.artist);
        String query = initialQuery(mediaTitle, mediaEpisode);
        binding.keyword.setText(query);
        binding.keyword.setSelection(binding.keyword.length());
        binding.status.setText(services.isConfigured() ? R.string.assrt_search_hint : R.string.assrt_unavailable);
        binding.search.setEnabled(services.isConfigured());
        updateControls();
        binding.search.post(() -> {
            if (binding != null && binding.search.isEnabled()) binding.search.requestFocus();
        });
    }

    @Override
    protected void initEvent() {
        binding.search.setOnClickListener(view -> search(0));
        binding.search.setOnKeyListener((view, keyCode, event) -> {
            if (!KeyUtil.isActionDown(event) || !KeyUtil.isDownKey(event)) return false;
            if (page != Page.RESULTS) return binding.back.requestFocus();
            if (adapter.getItemCount() == 0) return false;
            DialogFocus.requestRecyclerFocus(binding.recycler, 0, adapter.getItemCount());
            return true;
        });
        binding.keyword.setOnClickListener(Util::showKeyboard);
        binding.keyword.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            search(0);
            return true;
        });
        binding.keyword.setOnKeyListener((view, keyCode, event) -> {
            if (!KeyUtil.isActionDown(event) || !KeyUtil.isDownKey(event)) return false;
            binding.search.requestFocus();
            return true;
        });
        binding.previous.setOnClickListener(view -> search(keyword().equals(lastQuery) ? Math.max(0, position - 15) : 0));
        binding.next.setOnClickListener(view -> search(keyword().equals(lastQuery) ? position + 15 : 0));
        binding.back.setOnClickListener(view -> showResults());
        binding.source.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://assrt.net")));
            } catch (ActivityNotFoundException ignored) {
                Notify.show(R.string.assrt_browser_unavailable);
            }
        });
    }

    private void search(int nextPosition) {
        if (!sameMedia()) { showMediaChanged(); return; }
        if (!services.isConfigured()) { showStatus(R.string.assrt_unavailable); return; }
        String query = keyword();
        if (query.isEmpty()) { showStatus(R.string.assrt_query_empty); return; }
        int ticket = begin(R.string.assrt_searching);
        page = Page.RESULTS;
        selectedSubtitle = null;
        position = nextPosition;
        lastQuery = query;
        results = List.of();
        hasMore = false;
        updateControls();
        Util.hideKeyboard(binding.keyword);
        services.search(query, position, requestTag, new AssrtApi.SearchListener() {
            @Override public void onSuccess(List<AssrtSubtitle> items) {
                if (!accept(ticket)) return;
                results = List.copyOf(items);
                hasMore = items.size() >= 15;
                renderResults();
            }
            @Override public void onError(Exception error) {
                if (accept(ticket)) fail(errorMessage(error, R.string.assrt_search_error));
            }
        });
    }

    private void detail(AssrtSubtitle item) {
        if (!sameMedia()) { showMediaChanged(); return; }
        int ticket = begin(R.string.assrt_loading_files);
        page = Page.FILES;
        selectedSubtitle = item;
        updateControls();
        services.detail(item.getId(), requestTag, new AssrtApi.DetailListener() {
            @Override public void onSuccess(AssrtSubtitle item) {
                if (!accept(ticket)) return;
                selectedSubtitle = item;
                renderFiles();
            }
            @Override public void onError(Exception error) {
                if (accept(ticket)) fail(errorMessage(error, R.string.assrt_files_error));
            }
        });
    }

    private void download(AssrtSubtitleFile file) {
        if (!sameMedia()) { showMediaChanged(); return; }
        int ticket = begin(R.string.assrt_downloading);
        services.download(file, requestTag, new SubtitleDownload.Listener() {
            @Override public void onSuccess(List<Sub> files) {
                if (!accept(ticket)) { SubtitleDownload.discard(files); return; }
                downloaded = List.copyOf(files);
                if (files.isEmpty()) { fail(R.string.assrt_files_empty); return; }
                if (files.size() == 1) { apply(files.get(0)); return; }
                page = Page.EXTRACTED;
                List<Row> rows = new ArrayList<>();
                for (Sub sub : files) rows.add(new Row(sub.getName(), format(sub.getName()), () -> apply(sub)));
                finish(rows, R.string.assrt_choose_extracted);
            }
            @Override public void onError(Exception error) {
                if (!accept(ticket)) return;
                renderFiles();
                binding.status.setText(R.string.assrt_download_error);
            }
        });
    }

    private void apply(Sub subtitle) {
        if (!sameMedia()) { showMediaChanged(); return; }
        player.setSub(subtitle, true);
        downloaded = List.of();
        Notify.show(R.string.assrt_loaded);
        dismiss();
    }

    private void showResults() {
        if (binding == null) return;
        discardDownload();
        services.cancel(requestTag);
        generation++;
        page = Page.RESULTS;
        selectedSubtitle = null;
        renderResults();
    }

    private void renderResults() {
        List<Row> rows = new ArrayList<>();
        for (AssrtSubtitle item : results) {
            String description = join(item.getLanguageText(), item.getFormat(), item.getReleaseSite(), item.getReleaseName());
            rows.add(new Row(item.getTitle(), description, () -> detail(item)));
        }
        finish(rows, rows.isEmpty() ? R.string.assrt_empty : R.string.assrt_choose_result);
    }

    private void renderFiles() {
        page = Page.FILES;
        List<Row> rows = new ArrayList<>();
        if (selectedSubtitle != null) for (AssrtSubtitleFile file : selectedSubtitle.getFiles()) {
            rows.add(new Row(file.getName(), join(format(file.getName()), file.getSizeText()), () -> download(file)));
        }
        finish(rows, rows.isEmpty() ? R.string.assrt_files_empty : R.string.assrt_choose_file);
    }

    private int begin(int message) {
        discardDownload();
        services.cancel(requestTag);
        generation++;
        requestTag = new Object();
        busy = true;
        adapter.set(List.of());
        binding.recycler.setVisibility(View.GONE);
        binding.progress.setVisibility(View.VISIBLE);
        binding.status.setText(message);
        updateControls();
        return generation;
    }

    private void finish(List<Row> rows, int message) {
        busy = false;
        binding.progress.setVisibility(View.GONE);
        adapter.set(rows);
        binding.recycler.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        binding.status.setText(message);
        updateControls();
        if (!rows.isEmpty()) DialogFocus.requestRecyclerFocus(binding.recycler, 0, rows.size());
        else binding.search.requestFocus();
    }

    private void fail(int message) { finish(List.of(), message); }

    private static int errorMessage(Exception error, int fallback) {
        if (!(error instanceof AssrtApi.ApiException api)) return fallback;
        if (api.isRateLimited()) return R.string.assrt_rate_limit;
        return api.getCode() == 20001 ? R.string.assrt_unavailable : fallback;
    }

    private void showStatus(int message) { if (binding != null) binding.status.setText(message); }

    private void updateControls() {
        if (binding == null) return;
        boolean resultsPage = page == Page.RESULTS;
        boolean paging = resultsPage && (!results.isEmpty() || position > 0);
        binding.previous.setVisibility(paging ? View.VISIBLE : View.GONE);
        binding.next.setVisibility(paging ? View.VISIBLE : View.GONE);
        binding.previous.setEnabled(!busy && position > 0);
        binding.next.setEnabled(!busy && hasMore);
        binding.back.setVisibility(resultsPage ? View.GONE : View.VISIBLE);
        binding.keyword.setNextFocusDownId(binding.search.getId());
        binding.search.setNextFocusDownId(!resultsPage ? binding.back.getId()
                : adapter.getItemCount() > 0 ? binding.recycler.getId() : binding.source.getId());
        binding.back.setNextFocusDownId(adapter.getItemCount() > 0 ? binding.recycler.getId() : binding.source.getId());
    }

    private boolean accept(int ticket) {
        if (binding == null || !isAdded() || ticket != generation) return false;
        if (!sameMedia()) { showMediaChanged(); return false; }
        return true;
    }

    private boolean sameMedia() {
        if (player == null || player.isReleased() || player.isEmpty()) return false;
        MediaMetadata metadata = player.getMetadata();
        return Objects.equals(mediaKey, player.getKey()) && Objects.equals(mediaUrl, player.getUrl())
                && Objects.equals(mediaTitle, text(metadata == null ? null : metadata.title))
                && Objects.equals(mediaEpisode, text(metadata == null ? null : metadata.artist));
    }

    private void showMediaChanged() {
        discardDownload();
        services.cancel(requestTag);
        generation++;
        busy = false;
        if (binding == null) return;
        adapter.set(List.of());
        binding.progress.setVisibility(View.GONE);
        binding.recycler.setVisibility(View.GONE);
        binding.search.setEnabled(false);
        binding.previous.setVisibility(View.GONE);
        binding.next.setVisibility(View.GONE);
        binding.back.setVisibility(View.GONE);
        binding.status.setText(R.string.assrt_media_changed);
    }

    private String keyword() {
        return binding.keyword.getText() == null ? "" : binding.keyword.getText().toString().trim();
    }

    static String initialQuery(String title, String episode) {
        EpisodeLabel label = EpisodeLabelParser.parse(episode);
        if (label.isVariety()) return (text(title) + " " + text(episode)).trim();
        if (!label.isApiSafe()) return title;
        Integer season = label.season() == null ? EpisodeLabelParser.parse(title).season() : label.season();
        return season == null ? title + String.format(Locale.ROOT, " %02d", label.number())
                : title + String.format(Locale.ROOT, " S%02dE%02d", season, label.number());
    }

    private static String text(CharSequence value) { return value == null ? "" : value.toString().trim(); }

    private static String format(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    private static String join(String... values) {
        List<String> present = new ArrayList<>();
        for (String value : values) if (value != null && !value.trim().isEmpty() && !present.contains(value.trim())) present.add(value.trim());
        return String.join(" · ", present);
    }

    @Override
    public void onDestroyView() {
        discardDownload();
        services.cancel(requestTag);
        generation++;
        if (binding != null) Util.hideKeyboard(binding.keyword);
        binding = null;
        super.onDestroyView();
    }

    private void discardDownload() {
        SubtitleDownload.discard(downloaded);
        downloaded = List.of();
    }

    private record Row(String title, String description, Runnable action) { }

    private static final class Rows extends RecyclerView.Adapter<Rows.Holder> {
        private List<Row> items = List.of();
        void set(List<Row> rows) { items = List.copyOf(rows); notifyDataSetChanged(); }
        @Override public int getItemCount() { return items.size(); }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_subtitle_search, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            Row row = items.get(position);
            holder.text.setSingleLine(false);
            holder.text.setMaxLines(3);
            holder.text.setText(row.description().isEmpty() ? row.title() : row.title() + "\n" + row.description());
            holder.itemView.setOnClickListener(view -> {
                int index = holder.getBindingAdapterPosition();
                if (index != RecyclerView.NO_POSITION && index < items.size()) items.get(index).action().run();
            });
        }
        static final class Holder extends RecyclerView.ViewHolder {
            final TextView text;
            Holder(View view) { super(view); text = (TextView) view; }
        }
    }
}
