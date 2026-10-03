package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Word;
import com.fongmi.android.tv.databinding.ActivitySearchBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.RecordAdapter;
import com.fongmi.android.tv.ui.adapter.WordAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomKeyboard;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.utils.ZhuToPin;
import com.github.catvod.net.OkHttp;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayoutManager;

import java.io.IOException;
import java.net.URLEncoder;

import okhttp3.Call;
import okhttp3.Response;

public class SearchActivity extends BaseActivity implements WordAdapter.OnClickListener, RecordAdapter.OnClickListener, CustomKeyboard.Callback {

    private ActivitySearchBinding mBinding;
    private RecordAdapter mRecordAdapter;
    private WordAdapter mWordAdapter;
    private SearchResultsController results;
    private Call suggestionCall;
    private Runnable suggestionRequest;
    private long suggestionGeneration;
    private View savedFocus;
    private final SearchFocusGuard focus = new SearchFocusGuard(this);
    private long resumeFocusGeneration;
    private boolean restoreAfterResume;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SearchActivity.class));
    }

    public static void start(Activity activity, String keyword) {
        Intent intent = new Intent(activity, SearchActivity.class);
        intent.putExtra("keyword", keyword);
        activity.startActivity(intent);
    }

    private String getKeyword() {
        String keyword = getIntent().getStringExtra("keyword");
        return keyword != null ? keyword : "";
    }

    private boolean empty() {
        return mBinding.keyword.getText().toString().trim().isEmpty();
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySearchBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        CustomKeyboard.init(this, mBinding);
        setRecyclerView();
        results = new SearchResultsController(this, mBinding.results, true, focus);
        checkKeyword();
        onSearch();
    }

    @Override
    protected void initEvent() {
        mBinding.keyword.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onSearch();
            return true;
        });
        mBinding.keyword.addTextChangedListener(new CustomTextListener() {
            @Override
            public void afterTextChanged(Editable s) {
                getWord(s.toString());
            }
        });
        mBinding.mic.setOnClickListener(v -> mBinding.mic.start());
        mBinding.mic.setListener(this, new CustomTextListener() {
            @Override
            public void onResults(String result) {
                if (!result.isEmpty()) setKeyword(result);
                requestKeywordFocus();
            }
        });
    }

    private void setRecyclerView() {
        mBinding.wordRecycler.setItemAnimator(null);
        mBinding.wordRecycler.setHasFixedSize(false);
        mBinding.wordRecycler.setLayoutManager(new FlexboxLayoutManager(this, FlexDirection.ROW));
        mBinding.wordRecycler.setAdapter(mWordAdapter = new WordAdapter(this));
        mBinding.recordRecycler.setHasFixedSize(false);
        mBinding.recordRecycler.setLayoutManager(new FlexboxLayoutManager(this, FlexDirection.ROW));
        mBinding.recordRecycler.setAdapter(mRecordAdapter = new RecordAdapter(this));
    }

    private void checkKeyword() {
        setKeyword(getKeyword());
        getWord(getKeyword());
    }

    private void setKeyword(String text) {
        mBinding.keyword.setText(text);
        mBinding.keyword.setSelection(text.length());
    }

    private void getWord(String text) {
        focus.invalidate();
        if (text.isEmpty()) showResults(false);
        long generation = ++suggestionGeneration;
        if (suggestionRequest != null) App.removeCallbacks(suggestionRequest);
        if (suggestionCall != null) suggestionCall.cancel();
        mBinding.word.setText(text.isEmpty() ? R.string.search_hot : R.string.search_suggest);
        if (text.isEmpty()) mWordAdapter.setItems(Word.objectFrom(Setting.getHot()).getData());
        else mWordAdapter.setItems(java.util.List.of());
        suggestionRequest = () -> {
            if (isFinishing() || isDestroyed() || generation != suggestionGeneration) return;
            String url = text.isEmpty() ? "https://hot.api.coolmarket.eu.org/api/douban-hot-mixed"
                    : "https://suggest.video.iqiyi.com/?if=mobile&key=" + URLEncoder.encode(ZhuToPin.get(text));
            suggestionCall = OkHttp.newCall(url);
            suggestionCall.enqueue(getCallback(text, generation));
        };
        App.post(suggestionRequest, text.isEmpty() ? 0 : 250);
    }

    private Callback getCallback(String query, long generation) {
        return new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String result = response.body().string();
                    if (TextUtils.isEmpty(result) || call.isCanceled()) return;
                    App.post(() -> {
                        if (isFinishing() || isDestroyed() || generation != suggestionGeneration) return;
                        if (!query.equals(mBinding.keyword.getText().toString())) return;
                        setAdapter(result, query.isEmpty());
                    });
                }
            }
        };
    }

    private void setAdapter(String result, boolean save) {
        if (!save && empty()) return;
        if (save) Setting.putHot(result);
        View current = getCurrentFocus();
        View item = current == null ? null : mBinding.wordRecycler.findContainingItemView(current);
        boolean restoreFocus = current == mBinding.wordRecycler || item != null;
        int position = item == null ? 0 : mBinding.wordRecycler.getChildAdapterPosition(item);
        long generation = focus.snapshot();
        mWordAdapter.setItems(Word.objectFrom(result).getData(), () -> restoreWordFocus(restoreFocus, position, generation));
    }

    @Override
    public void onItemClick(String text) {
        setKeyword(text);
        onSearch();
    }

    @Override
    public void onDataChanged(int size, int deletedPosition) {
        mBinding.recordLayout.setVisibility(size == 0 || mBinding.results.getRoot().getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        if (deletedPosition == RecyclerView.NO_POSITION && getCurrentFocus() != null) return;
        if (deletedPosition == RecyclerView.NO_POSITION) {
            if (size == 0 && !focusFirst(mBinding.wordRecycler)) requestKeywordFocus();
            return;
        }
        if (size == 0) {
            if (!focusFirst(mBinding.wordRecycler)) requestKeywordFocus();
            return;
        }
        restoreRecordFocus(Math.max(0, Math.min(deletedPosition, size - 1)));
    }

    @Override
    public void onSearch() {
        focus.invalidate();
        if (empty()) return;
        String keyword = mBinding.keyword.getText().toString().trim();
        mRecordAdapter.add(keyword);
        Util.hideKeyboard(mBinding.keyword);
        showResults(true);
        results.search(keyword);
        results.requestFocus();
    }

    private void showResults(boolean visible) {
        mBinding.results.getRoot().setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.recordLayout.setVisibility(!visible && mRecordAdapter != null && mRecordAdapter.getItemCount() > 0 ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams suggestions = (LinearLayout.LayoutParams) mBinding.scroll.getLayoutParams();
        suggestions.height = visible ? com.fongmi.android.tv.utils.ResUtil.dp2px(128) : 0;
        suggestions.weight = visible ? 0 : 1;
        mBinding.scroll.setLayoutParams(suggestions);
    }

    @Override
    public void showDialog() {
        SiteDialog.create().classic().search().show(this);
    }

    @Override
    public void onRemote() {
        PushActivity.start(this, 1);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isActionDown(event)) focus.invalidate();
        if (KeyUtil.isMenuKey(event)) showDialog();
        if (KeyUtil.isActionDown(event) && findFocus(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean findFocus(KeyEvent event) {
        View current = getCurrentFocus();
        if (current == null) return false;
        if (current == mBinding.keyword) return handleKeywordKey(event);
        View inKeyboard = mBinding.keyboard.findContainingItemView(current);
        View inWord = mBinding.wordRecycler.findContainingItemView(current);
        View inRecord = mBinding.recordRecycler.findContainingItemView(current);
        if (inKeyboard != null) return handleKeyboardKey(event, inKeyboard);
        if (inRecord != null) return handleRecordKey(event, inRecord);
        if (inWord != null) return handleWordKey(event, inWord);
        return false;
    }

    private View findNearestInLastRow(RecyclerView rv, int targetLeft) {
        if (rv.getChildCount() == 0) return null;
        int lastTop = rv.getChildAt(rv.getChildCount() - 1).getTop();
        View nearest = null;
        int minDist = Integer.MAX_VALUE;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child.getTop() == lastTop) {
                int dist = Math.abs(child.getLeft() - targetLeft);
                if (dist < minDist) {
                    minDist = dist;
                    nearest = child;
                }
            }
        }
        return nearest;
    }

    private boolean isFirstRow(RecyclerView rv, View item) {
        View first = rv.getChildAt(0);
        return first != null && item.getTop() == first.getTop();
    }

    private boolean isLastRow(RecyclerView rv, View item) {
        View last = rv.getChildAt(rv.getChildCount() - 1);
        return last != null && item.getTop() == last.getTop();
    }

    private boolean isFirstInRow(RecyclerView rv, View focused) {
        int top = focused.getTop();
        int left = focused.getLeft();
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child.getTop() == top && child.getLeft() < left) return false;
        }
        return true;
    }

    private boolean isLastInRow(RecyclerView rv, View focused) {
        int top = focused.getTop();
        int right = focused.getRight();
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child.getTop() == top && child.getRight() > right) return false;
        }
        return true;
    }

    private boolean handleKeywordKey(KeyEvent event) {
        if (!KeyUtil.isRightKey(event)) return false;
        if (mBinding.keyword.getSelectionEnd() < mBinding.keyword.getText().length()) return false;
        if (!empty()) {
            if (focusFirst(mBinding.wordRecycler)) return true;
            if (results.hasResults()) { results.requestFocus(); return true; }
            return false;
        }
        boolean hasRecord = mBinding.recordLayout.getVisibility() == View.VISIBLE;
        return focusFirst(hasRecord ? mBinding.recordRecycler : mBinding.wordRecycler);
    }

    private boolean handleKeyboardKey(KeyEvent event, View item) {
        if (KeyUtil.isUpKey(event) && isFirstRow(mBinding.keyboard, item)) {
            requestKeywordFocus();
            return true;
        }
        if (KeyUtil.isLeftKey(event) && isFirstInRow(mBinding.keyboard, item)) return true;
        return KeyUtil.isDownKey(event) && isLastRow(mBinding.keyboard, item);
    }

    private boolean handleWordKey(KeyEvent event, View item) {
        if (KeyUtil.isRightKey(event)) return isLastInRow(mBinding.wordRecycler, item);
        if (KeyUtil.isDownKey(event) && isLastRow(mBinding.wordRecycler, item)) {
            if (results.hasResults()) results.requestFocus();
            return true;
        }
        if (KeyUtil.isUpKey(event) && isFirstRow(mBinding.wordRecycler, item)) {
            if (mBinding.recordLayout.getVisibility() == View.VISIBLE) {
                View child = findNearestInLastRow(mBinding.recordRecycler, item.getLeft());
                if (child != null) {
                    mBinding.scroll.smoothScrollTo(0, 0);
                    if (!canRequestFocus(child) || !child.requestFocus()) focusFirst(mBinding.recordRecycler);
                    return true;
                }
                if (focusFirst(mBinding.recordRecycler)) return true;
            }
            return true;
        }
        return false;
    }

    private boolean handleRecordKey(KeyEvent event, View item) {
        if (KeyUtil.isRightKey(event)) return isLastInRow(mBinding.recordRecycler, item);
        if (KeyUtil.isUpKey(event)) return isFirstRow(mBinding.recordRecycler, item);
        if (KeyUtil.isDownKey(event) && isLastRow(mBinding.recordRecycler, item)) return focusFirst(mBinding.wordRecycler);
        return false;
    }

    private boolean focusFirst(RecyclerView rv) {
        return focusFirst(rv, focus.snapshot());
    }

    private boolean focusFirst(RecyclerView rv, long generation) {
        if (!focus.canFocus(generation, rv)) return false;
        View child = rv.getChildAt(0);
        if (focus.canFocus(generation, child) && child.requestFocus()) return true;
        RecyclerView.Adapter<?> adapter = rv.getAdapter();
        if (adapter == null || adapter.getItemCount() == 0) return false;
        rv.scrollToPosition(0);
        focus.post(rv, generation, 0, () -> {
            View target = rv.getChildAt(0);
            if (focus.canFocus(generation, target) && target.requestFocus()) return;
            if (focus.canFocus(generation, rv)) rv.requestFocus();
        });
        return true;
    }

    private void restoreWordFocus(boolean restore, int position, long generation) {
        if (!restore || !focus.canFocus(generation, mBinding.wordRecycler)) return;
        if (mWordAdapter.getItemCount() == 0) {
            requestKeywordFocus(generation);
            return;
        }
        int target = position == RecyclerView.NO_POSITION ? 0 : Math.max(0, Math.min(position, mWordAdapter.getItemCount() - 1));
        mBinding.wordRecycler.scrollToPosition(target);
        focus.post(mBinding.wordRecycler, generation, 50, () -> {
            RecyclerView.ViewHolder holder = mBinding.wordRecycler.findViewHolderForAdapterPosition(target);
            if (holder != null && focus.canFocus(generation, holder.itemView) && holder.itemView.requestFocus()) return;
            if (!focusFirst(mBinding.wordRecycler, generation)) requestKeywordFocus(generation);
        });
    }

    private void restoreRecordFocus(int position) {
        long generation = focus.snapshot();
        if (mRecordAdapter.getItemCount() == 0) {
            if (!focusFirst(mBinding.wordRecycler, generation)) requestKeywordFocus(generation);
            return;
        }
        if (!focus.canFocus(generation, mBinding.recordRecycler)) {
            if (!focusFirst(mBinding.wordRecycler, generation)) requestKeywordFocus(generation);
            return;
        }
        int target = Math.max(0, Math.min(position, mRecordAdapter.getItemCount() - 1));
        mBinding.recordRecycler.scrollToPosition(target);
        focus.post(mBinding.recordRecycler, generation, 50, () -> {
            RecyclerView.ViewHolder holder = mBinding.recordRecycler.findViewHolderForAdapterPosition(target);
            if (holder != null && focus.canFocus(generation, holder.itemView) && holder.itemView.requestFocus()) return;
            if (!focusFirst(mBinding.recordRecycler, generation) && !focusFirst(mBinding.wordRecycler, generation)) requestKeywordFocus(generation);
        });
    }

    private void requestKeywordFocus() {
        requestKeywordFocus(focus.snapshot());
    }

    private void requestKeywordFocus(long generation) {
        focus.post(mBinding.keyword, generation, 0, () -> mBinding.keyword.requestFocus());
    }

    private boolean canRequestFocus(View view) {
        return view != null && view.isShown() && view.isEnabled();
    }

    @Override
    protected void onPause() {
        savedFocus = getCurrentFocus();
        restoreAfterResume = false;
        focus.invalidate();
        super.onPause();
        mBinding.mic.setFocusable(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        focus.invalidate();
        resumeFocusGeneration = focus.snapshot();
        restoreAfterResume = true;
        mBinding.mic.setFocusable(true);
        if (hasWindowFocus()) restoreResumedFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && restoreAfterResume) restoreResumedFocus();
    }

    private void restoreResumedFocus() {
        restoreAfterResume = false;
        View target = savedFocus;
        if (canRequestFocus(target)) focus.post(target, resumeFocusGeneration, 0, target::requestFocus);
        else if (mBinding.results.getRoot().isShown() && results.hasResults()) results.requestFocus(resumeFocusGeneration);
        else if (getCurrentFocus() == null) requestKeywordFocus(resumeFocusGeneration);
    }

    @Override
    protected void onBackInvoked() {
        focus.invalidate();
        if (mBinding.results.getRoot().hasFocus()) requestKeywordFocus();
        else super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        focus.close();
        suggestionGeneration++;
        if (suggestionRequest != null) App.removeCallbacks(suggestionRequest);
        if (suggestionCall != null) suggestionCall.cancel();
        if (results != null) results.dispose();
        mBinding.mic.destroy();
        super.onDestroy();
    }
}
