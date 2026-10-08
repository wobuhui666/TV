package com.fongmi.android.tv.ui.activity;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.custom.TouchFocus;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterPosterSourceBinding;
import com.fongmi.android.tv.databinding.ViewPosterSourcesBinding;
import com.fongmi.android.tv.model.DiscoverSourceSearch;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.setting.PosterSourcePrioritySetting;
import com.fongmi.android.tv.ui.dialog.PosterSourcePriorityDialog;
import com.fongmi.android.tv.source.PosterSourceResults;
import com.fongmi.android.tv.source.PosterSourceResults.Candidate;
import com.fongmi.android.tv.source.SearchRelevance;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native source rail. Streaming results never move the row currently under the remote focus. */
final class PosterSourcesController {
    private final DiscoverDetailActivity activity;
    private final ViewPosterSourcesBinding binding;
    private final DiscoverSourceSearch search = new DiscoverSourceSearch();
    private final SourceAdapter adapter = new SourceAdapter();
    private PosterSourceResults results;
    private String title = "", originalTitle = "", year = "";
    private boolean movie;
    private int seasons, selectedSeason;
    private boolean running;
    private List<String> priorities = List.of();

    PosterSourcesController(DiscoverDetailActivity activity, ViewPosterSourcesBinding binding, Runnable close) {
        this.activity = activity;
        this.binding = binding;
        binding.results.setLayoutManager(new LinearLayoutManager(activity));
        binding.results.setAdapter(adapter);
        binding.results.setItemAnimator(null);
        TouchFocus.bind(binding.smart, binding.retry, binding.priority, binding.season, binding.fullSearch, binding.close);
        binding.retry.setOnClickListener(view -> start());
        binding.smart.setOnClickListener(view -> {
            BrowseExperienceSettings.putSmartSourceEnabled(!BrowseExperienceSettings.isSmartSourceEnabled());
            start();
        });
        binding.priority.setOnClickListener(view -> PosterSourcePriorityDialog.show(activity, () -> {
            start();
            binding.priority.requestFocus();
        }));
        binding.season.setOnClickListener(view -> chooseSeason());
        binding.fullSearch.setOnClickListener(view -> { stop(); SearchActivity.start(activity, title); });
        binding.close.setOnClickListener(view -> close.run());
        for (View control : new View[]{binding.smart, binding.retry, binding.priority, binding.season, binding.fullSearch, binding.close}) {
            control.setOnFocusChangeListener((view, focused) -> {
                if (focused && results != null) render();
            });
        }
        updateControls();
    }

    void metadata(String title, String originalTitle, String year, boolean movie, int seasons) {
        this.title = title;
        this.originalTitle = originalTitle;
        this.year = year;
        this.movie = movie;
        this.seasons = Math.min(100, Math.max(0, seasons));
        updateControls();
    }

    private void updateControls() {
        int priorityCount = PosterSourcePrioritySetting.getOrderedKeys().size();
        binding.priority.setText(priorityCount == 0 ? activity.getString(R.string.poster_source_priority_button_default)
                : activity.getString(R.string.poster_source_priority_button_count, priorityCount));
        binding.smart.setText(BrowseExperienceSettings.isSmartSourceEnabled() ? R.string.poster_sources_ranked : R.string.poster_sources_order);
        binding.season.setVisibility(!movie && seasons > 1 ? View.VISIBLE : View.GONE);
        binding.season.setText(selectedSeason == 0 ? activity.getString(R.string.poster_sources_all_seasons)
                : activity.getString(R.string.poster_sources_season_number, selectedSeason));
    }

    private void chooseSeason() {
        String[] options = new String[seasons + 1];
        options[0] = activity.getString(R.string.poster_sources_all_seasons);
        for (int i = 1; i <= seasons; i++) options[i] = activity.getString(R.string.poster_sources_season_number, i);
        new MaterialAlertDialogBuilder(activity).setTitle(R.string.poster_sources_season)
                .setSingleChoiceItems(options, selectedSeason, (dialog, which) -> {
                    selectedSeason = which;
                    dialog.dismiss();
                    start();
                }).show();
    }

    void open() {
        updateControls();
        if (results == null) start();
        binding.smart.requestFocus();
    }

    void start() {
        search.stop();
        updateControls();
        priorities = PosterSourcePrioritySetting.getOrderedKeys();
        List<Site> sites = PosterSourcePrioritySetting.orderSites(VodConfig.get().getSites());
        List<String> aliases = originalTitle.isEmpty() || originalTitle.equals(title) ? List.of() : List.of(originalTitle);
        // A series premiere year is not the release year of every later season.
        SearchRelevance.Query query = new SearchRelevance.Query(title, aliases, movie ? year : "", selectedSeason == 0 ? null : selectedSeason);
        results = new PosterSourceResults(query, VodConfig.get().getHome().getKey(), sites.stream().map(Site::getKey).toList(), movie ? "movie" : "tv", priorities);
        adapter.update(List.of());
        if (sites.isEmpty()) {
            running = false;
            binding.status.setText(R.string.poster_sources_no_sites);
            return;
        }
        List<String> queries = new ArrayList<>();
        if (selectedSeason > 0) queries.add(title + " 第" + selectedSeason + "季");
        queries.add(title);
        if (BrowseExperienceSettings.isSmartSourceEnabled()) queries.addAll(aliases);
        running = true;
        binding.status.setText(activity.getString(R.string.poster_sources_progress, 0, 0, sites.size() * queries.size()));
        search.start(sites, queries, priorities, new DiscoverSourceSearch.Listener() {
            @Override public void onResult(Result result, int returned, int total) {
                if (result != null) results.add(result.getList());
                render();
                binding.status.setText(activity.getString(R.string.poster_sources_progress, adapter.getItemCount(), returned, total));
            }
            @Override public void onComplete(boolean deadline) {
                running = false;
                render();
                if (adapter.getItemCount() == 0) binding.status.setText(R.string.poster_sources_empty);
                else if (deadline) binding.status.setText(activity.getString(R.string.poster_sources_deadline, adapter.getItemCount()));
                else binding.status.setText(activity.getString(R.string.poster_sources_complete, adapter.getItemCount(), results.hiddenCount()));
            }
        });
    }

    private void render() {
        List<Candidate> snapshot = results.snapshot(BrowseExperienceSettings.isSmartSourceEnabled());
        if (binding.results.hasFocus()) {
            Map<String, Candidate> pending = new LinkedHashMap<>();
            for (Candidate item : snapshot) pending.put(PosterSourceResults.key(item.vod()), item);
            List<Candidate> fixed = new ArrayList<>();
            for (Candidate item : adapter.items) {
                Candidate next = pending.remove(PosterSourceResults.key(item.vod()));
                // Do not remove a focused card when the bounded result set improves in the background.
                fixed.add(next == null ? item : next);
            }
            for (Candidate item : pending.values()) if (fixed.size() < 120) fixed.add(item);
            snapshot = fixed;
        }
        adapter.update(snapshot);
    }

    void stop() {
        search.stop();
        if (running && results != null) {
            binding.status.setText(activity.getString(R.string.poster_sources_deadline, adapter.getItemCount()));
        }
        running = false;
    }

    private void play(Candidate item) {
        stop();
        Vod vod = item.vod();
        // Explicit source choice hands off to the existing player, episode selector and history.
        VideoActivity.start(activity, vod.getSiteKey(), vod.getId(), vod.getName(), vod.getPic());
    }

    private final class SourceAdapter extends RecyclerView.Adapter<SourceHolder> {
        private List<Candidate> items = List.of();
        void update(List<Candidate> values) {
            List<Candidate> before = items;
            List<Candidate> after = new ArrayList<>(values);
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return before.size(); }
                @Override public int getNewListSize() { return after.size(); }
                @Override public boolean areItemsTheSame(int a, int b) {
                    return PosterSourceResults.key(before.get(a).vod()).equals(PosterSourceResults.key(after.get(b).vod()));
                }
                @Override public boolean areContentsTheSame(int a, int b) { return before.get(a).equals(after.get(b)); }
            });
            items = after;
            diff.dispatchUpdatesTo(this);
        }
        @NonNull @Override public SourceHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new SourceHolder(AdapterPosterSourceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }
        @Override public void onBindViewHolder(@NonNull SourceHolder holder, int position) {
            Candidate item = items.get(position);
            Vod vod = item.vod();
            int priority = priorities.indexOf(vod.getSiteKey());
            holder.row.site.setText(priority < 0 ? vod.getSiteName()
                    : activity.getString(R.string.poster_source_priority_site, priority + 1, vod.getSiteName()));
            holder.row.name.setText(vod.getName());
            List<String> parts = new ArrayList<>();
            if (!vod.getYear().isEmpty()) parts.add(vod.getYear());
            if (!vod.getRemarks().isEmpty()) parts.add(vod.getRemarks());
            parts.add(activity.getString(item.match().confident() ? R.string.poster_sources_ready : R.string.poster_sources_check));
            holder.row.meta.setText(android.text.TextUtils.join(" · ", parts));
            holder.row.getRoot().setOnClickListener(view -> {
                int at = holder.getBindingAdapterPosition();
                if (at != RecyclerView.NO_POSITION) play(items.get(at));
            });
        }
        @Override public int getItemCount() { return items.size(); }
    }

    private static final class SourceHolder extends RecyclerView.ViewHolder {
        final AdapterPosterSourceBinding row;
        SourceHolder(AdapterPosterSourceBinding row) {
            super(row.getRoot());
            this.row = row;
            TouchFocus.bind(row.getRoot());
        }
    }
}
