package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;

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
import com.fongmi.android.tv.ui.search.SearchInputParser;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.utils.ZhuToPin;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayoutManager;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.Call;
import okhttp3.Response;

public class SearchActivity extends BaseActivity implements WordAdapter.OnClickListener, RecordAdapter.OnClickListener, CustomKeyboard.Callback {

    private ActivitySearchBinding mBinding;
    private RecordAdapter mRecordAdapter;
    private WordAdapter mWordAdapter;
    private Call suggestionCall;
    private Runnable suggestionRequest;
    private volatile long suggestionGeneration;
    private final ExecutorService wordWorker = Executors.newSingleThreadExecutor();
    private Future<?> cachedWordTask, suggestionPreparation;
    private Runnable wordUiUpdate;
    private String pendingWordQuery;
    private long pendingWordGeneration;
    private int shownWordHeader = R.string.search_hot;
    private boolean wordListCleared = true;
    private static final int WORDS_PER_FRAME = 6;
    private Runnable wordChunk;
    private long wordPublicationGeneration;
    private View savedFocus;
    private boolean returnToRecord;
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
        // Search keywords use the app's suggestions, not Android's prose spell checker.
        mBinding.keyword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        CustomKeyboard.init(this, mBinding);
        setRecyclerView();
        checkKeyword();
        mBinding.keyword.setShowSoftInputOnFocus(false);
        mBinding.keyword.requestFocus();
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
        mBinding.keyword.beginBatchEdit();
        try {
            Editable editable = mBinding.keyword.getText();
            if (editable == null) mBinding.keyword.setText(text);
            else if (!editable.toString().equals(text)) editable.replace(0, editable.length(), text);
            mBinding.keyword.setSelection(mBinding.keyword.length());
        } finally {
            mBinding.keyword.endBatchEdit();
        }
    }

    private void getWord(String text) {
        focus.invalidate();
        pendingWordGeneration = ++suggestionGeneration;
        pendingWordQuery = text;
        if (suggestionRequest != null) App.removeCallbacks(suggestionRequest);
        if (suggestionCall != null) { suggestionCall.cancel(); suggestionCall = null; }
        cancelWordWork();
        // Several edits in one frame share one header/list update. TextWatcher remains cheap.
        if (wordUiUpdate != null) return;
        wordUiUpdate = () -> {
            wordUiUpdate = null;
            String query = pendingWordQuery;
            long generation = pendingWordGeneration;
            if (currentSuggestion(query, generation)) updateWords(query, generation);
        };
        mBinding.keyword.postOnAnimation(wordUiUpdate);
    }

    private void updateWords(String text, long generation) {
        int header = text.isEmpty() ? R.string.search_hot : R.string.search_suggest;
        if (header != shownWordHeader) { shownWordHeader = header; mBinding.word.setText(header); }
        if (!wordListCleared) { wordListCleared = true; mWordAdapter.setItems(List.of()); }
        if (text.isEmpty()) {
            cachedWordTask = wordWorker.submit(() -> {
                List<Word.Data> words = wordItems(SearchInputParser.words(Setting.getHot()));
                if (Thread.currentThread().isInterrupted()) return;
                App.post(() -> {
                    if (!currentSuggestion(text, generation)) return;
                    publishWords(words, text, generation, () -> { });
                });
            });
        }
        suggestionRequest = () -> {
            if (isFinishing() || isDestroyed() || generation != suggestionGeneration) return;
            suggestionPreparation = wordWorker.submit(() -> {
                String url = text.isEmpty() ? "https://hot.api.coolmarket.eu.org/api/douban-hot-mixed"
                        : "https://suggest.video.iqiyi.com/?if=mobile&key=" + URLEncoder.encode(ZhuToPin.get(text));
                if (Thread.currentThread().isInterrupted()) return;
                App.post(() -> {
                    if (!currentSuggestion(text, generation)) return;
                    suggestionCall = OkHttp.newCall(url);
                    suggestionCall.enqueue(getCallback(text, generation));
                });
            });
        };
        App.post(suggestionRequest, text.isEmpty() ? 0 : 250);
    }

    private Callback getCallback(String query, long generation) {
        return new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    if (response.body() == null || call.isCanceled()) return;
                    List<Word.Data> words = wordItems(SearchInputParser.words(response.body().charStream()));
                    if (call.isCanceled()) return;
                    String cache = query.isEmpty() ? App.gson().toJson(Map.of("data", words)) : "";
                    App.post(() -> {
                        if (!currentSuggestion(query, generation)) return;
                        if (query.isEmpty()) Setting.putHot(cache);
                        setAdapter(words, query.isEmpty());
                    });
                }
            }
        };
    }

    private boolean currentSuggestion(String query, long generation) {
        return !isFinishing() && !isDestroyed() && generation == suggestionGeneration
                && query.equals(mBinding.keyword.getText().toString());
    }

    private List<Word.Data> wordItems(List<String> labels) {
        return labels.stream().map(label -> Word.Data.fromTitle(Trans.s2t(label))).toList();
    }

    private void cancelWordWork() {
        if (cachedWordTask != null && !cachedWordTask.isDone()) cachedWordTask.cancel(true);
        if (suggestionPreparation != null && !suggestionPreparation.isDone()) suggestionPreparation.cancel(true);
        cachedWordTask = suggestionPreparation = null;
        cancelWordPublication();
    }

    private void cancelWordUiUpdate() {
        if (wordUiUpdate != null) mBinding.keyword.removeCallbacks(wordUiUpdate);
        wordUiUpdate = null;
    }

    private void cancelWordPublication() {
        wordPublicationGeneration++;
        if (wordChunk != null) mBinding.wordRecycler.removeCallbacks(wordChunk);
        wordChunk = null;
    }

    private void publishWords(List<Word.Data> items, String query, long generation, Runnable complete) {
        cancelWordPublication();
        wordListCleared = items.isEmpty();
        submitWordChunk(items, query, generation, wordPublicationGeneration, Math.min(WORDS_PER_FRAME, items.size()), complete);
    }

    private boolean currentWordPublication(String query, long generation, long publication) {
        return publication == wordPublicationGeneration && currentSuggestion(query, generation);
    }

    private void submitWordChunk(List<Word.Data> items, String query, long generation, long publication, int end, Runnable complete) {
        if (!currentWordPublication(query, generation, publication)) return;
        mWordAdapter.setItems(new ArrayList<>(items.subList(0, end)), () -> {
            if (!currentWordPublication(query, generation, publication)) return;
            if (end == items.size()) { complete.run(); return; }
            scheduleWordChunk(items, query, generation, publication, end, complete);
        });
    }

    private void scheduleWordChunk(List<Word.Data> items, String query, long generation, long publication, int end, Runnable complete) {
        wordChunk = () -> {
            if (!currentWordPublication(query, generation, publication)) return;
            wordChunk = null;
            // Let the preceding six chips measure and draw before adding the next six.
            if (mBinding.wordRecycler.isLayoutRequested() || mBinding.wordRecycler.isComputingLayout()) {
                scheduleWordChunk(items, query, generation, publication, end, complete);
                return;
            }
            submitWordChunk(items, query, generation, publication, Math.min(end + WORDS_PER_FRAME, items.size()), complete);
        };
        mBinding.wordRecycler.postOnAnimation(wordChunk);
    }

    private void setAdapter(List<Word.Data> items, boolean save) {
        if (!save && empty()) return;
        View current = getCurrentFocus();
        View item = current == null ? null : mBinding.wordRecycler.findContainingItemView(current);
        boolean restoreFocus = current == mBinding.wordRecycler || item != null;
        int position = item == null ? 0 : mBinding.wordRecycler.getChildAdapterPosition(item);
        long generation = focus.snapshot();
        publishWords(items, mBinding.keyword.getText().toString(), suggestionGeneration,
                () -> restoreWordFocus(restoreFocus, position, generation));
    }

    @Override
    public void onItemClick(String text) {
        search(text);
    }

    @Override
    public void onDataChanged(int size, int deletedPosition) {
        mBinding.recordLayout.setVisibility(size == 0 ? View.GONE : View.VISIBLE);
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
        search(mBinding.keyword.getText().toString().trim());
    }

    private void search(String keyword) {
        if (keyword.trim().isEmpty()) return;
        focus.invalidate();
        View current = getCurrentFocus();
        returnToRecord = current != null && mBinding.recordRecycler.findContainingItemView(current) != null;
        // Freeze this suggestion set until the user edits again; late network results
        // must not replace the selected item behind the fullscreen result page.
        suggestionGeneration++;
        if (suggestionRequest != null) App.removeCallbacks(suggestionRequest);
        if (suggestionCall != null) suggestionCall.cancel();
        cancelWordWork();
        cancelWordUiUpdate();
        mRecordAdapter.add(keyword);
        Util.hideKeyboard(mBinding.keyword);
        // Keep the input and suggestion list intact so Back restores the chosen item.
        CollectActivity.start(this, keyword);
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
        if (returnToRecord) {
            returnToRecord = false;
            restoreRecordFocus(0);
            return;
        }
        View target = savedFocus;
        if (canRequestFocus(target)) focus.post(target, resumeFocusGeneration, 0, target::requestFocus);
        else if (getCurrentFocus() == null) requestKeywordFocus(resumeFocusGeneration);
    }

    @Override
    protected void onBackInvoked() {
        focus.invalidate();
        super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        focus.close();
        suggestionGeneration++;
        if (suggestionRequest != null) App.removeCallbacks(suggestionRequest);
        if (suggestionCall != null) suggestionCall.cancel();
        cancelWordWork();
        cancelWordUiUpdate();
        wordWorker.shutdownNow();
        if (mRecordAdapter != null) mRecordAdapter.close();
        mBinding.mic.destroy();
        super.onDestroy();
    }
}
