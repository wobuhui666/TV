package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.MediaMetadata;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SubtitleApi;
import com.fongmi.android.tv.bean.SubtitleSearchItem;
import com.fongmi.android.tv.bean.SubtitleSearchPage;
import com.fongmi.android.tv.databinding.DialogSubtitleSearchBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.SubtitleSearchSetting;
import com.fongmi.android.tv.ui.adapter.SubtitleSearchAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

public final class SubtitleSearchDialog extends BaseBottomSheetDialog implements SubtitleSearchAdapter.OnClickListener {

    private final SubtitleSearchAdapter adapter = new SubtitleSearchAdapter(this);
    private DialogSubtitleSearchBinding binding;
    private PlayerManager player;
    private String keyword;
    private int nextPos;
    private boolean hasMore;
    private boolean loading;

    public static SubtitleSearchDialog create() {
        return new SubtitleSearchDialog();
    }

    public SubtitleSearchDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) if (fragment instanceof SubtitleSearchDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogSubtitleSearchBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.recycler.setAdapter(adapter);
        binding.recycler.setItemAnimator(null);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 12));
        binding.keyword.setText(getTitle());
        if (binding.keyword.getText() != null) binding.keyword.setSelection(binding.keyword.length());
        Util.showKeyboard(binding.keyword);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (Util.isLeanback()) binding.keyword.requestFocus();
    }

    @Override
    protected void initEvent() {
        binding.setting.setOnClickListener(view -> showTokenDialog());
        binding.keyword.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) startSearch();
            return true;
        });
        binding.recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (recyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE && dy > 0 && !recyclerView.canScrollVertically(1)) loadMore();
            }
        });
    }

    private CharSequence getTitle() {
        MediaMetadata metadata = player == null ? null : player.getMetadata();
        return metadata == null || TextUtils.isEmpty(metadata.title) ? "" : metadata.title;
    }

    private void startSearch() {
        String text = binding.keyword.getText() == null ? "" : binding.keyword.getText().toString().trim();
        if (text.isEmpty()) return;
        if (!SubtitleSearchSetting.hasToken()) {
            showTokenDialog();
            return;
        }
        Util.hideKeyboard(binding.keyword);
        adapter.clear();
        keyword = text;
        nextPos = 0;
        hasMore = true;
        requestSearch();
    }

    private void loadMore() {
        if (!loading && hasMore) requestSearch();
    }

    private void requestSearch() {
        if (loading) return;
        setLoading(true);
        SubtitleApi.search(keyword, nextPos, this::onSearch, this::onError);
    }

    private void onSearch(SubtitleSearchPage page) {
        nextPos += page.resultCount();
        hasMore = page.resultCount() >= SubtitleApi.SEARCH_COUNT;
        adapter.addAll(page.items());
        setLoading(false);
        if (adapter.getItemCount() == 0 && !hasMore) Notify.show(R.string.error_empty);
    }

    private void setLoading(boolean loading) {
        this.loading = loading;
        if (binding == null) return;
        binding.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.recycler.setVisibility(adapter.getItemCount() == 0 && loading ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onItemClick(SubtitleSearchItem item) {
        if (!item.hasUrl()) {
            setLoading(true);
            SubtitleApi.detail(item.getId(), this::showDetail, this::onError);
        } else if (item.isZip()) {
            setLoading(true);
            SubtitleApi.loadArchive(item, this::showArchive, this::onError);
        } else {
            apply(item);
        }
    }

    private void showDetail(List<SubtitleSearchItem> items) {
        setLoading(false);
        if (items.size() == 1) apply(items.get(0));
        else {
            hasMore = false;
            adapter.setItems(items);
        }
    }

    private void showArchive(List<SubtitleSearchItem> items) {
        setLoading(false);
        if (items.isEmpty()) Notify.show(R.string.error_empty);
        else if (items.size() == 1) apply(items.get(0));
        else {
            hasMore = false;
            adapter.setItems(items);
        }
    }

    private void apply(SubtitleSearchItem item) {
        if (player != null) player.setSub(item.toSub());
        dismiss();
    }

    private void onError(Exception error) {
        setLoading(false);
        Notify.show(TextUtils.isEmpty(error.getMessage()) ? ResUtil.getString(R.string.error_empty) : error.getMessage());
    }

    private void showTokenDialog() {
        TextInputEditText input = new TextInputEditText(requireContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(R.string.subtitle_search_token_hint);
        input.setText(SubtitleSearchSetting.getToken());
        int padding = ResUtil.dp2px(24);
        ViewGroup container = new androidx.appcompat.widget.LinearLayoutCompat(requireContext());
        container.setPadding(padding, 0, padding, 0);
        container.addView(input, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.subtitle_search_api_title).setView(container).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
            String token = input.getText() == null ? "" : input.getText().toString();
            SubtitleSearchSetting.putToken(token);
            if (SubtitleSearchSetting.hasToken() && binding != null && binding.keyword.length() > 0) startSearch();
        }).show();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        SubtitleApi.cancel();
        super.onDismiss(dialog);
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
