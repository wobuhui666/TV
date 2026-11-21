package com.fongmi.android.tv.ui.custom;

import android.text.Editable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySearchBinding;
import com.fongmi.android.tv.ui.adapter.KeyboardAdapter;

public class CustomKeyboard implements KeyboardAdapter.OnClickListener {

    private final ActivitySearchBinding binding;
    private final Callback callback;
    private KeyboardAdapter adapter;

    public static void init(Callback callback, ActivitySearchBinding binding) {
        new CustomKeyboard(callback, binding).initView();
    }

    public CustomKeyboard(Callback callback, ActivitySearchBinding binding) {
        this.callback = callback;
        this.binding = binding;
    }

    private void initView() {
        binding.keyboard.setItemAnimator(null);
        binding.keyboard.setHasFixedSize(false);
        binding.keyboard.addItemDecoration(new SpaceItemDecoration(7, 8));
        binding.keyboard.setAdapter(adapter = new KeyboardAdapter(this));
    }

    @Override
    public void onTextClick(String text) {
        Editable editable = binding.keyword.getText();
        if (editable == null || text == null || text.isEmpty() || editable.length() > 19) return;
        int cursor = cursor(editable);
        String addition = text.substring(0, Math.min(text.length(), 20 - editable.length()));
        int previousLength = editable.length();
        binding.keyword.beginBatchEdit();
        try {
            editable.insert(cursor, addition);
            binding.keyword.setSelection(Math.min(editable.length(), cursor + editable.length() - previousLength));
        } finally {
            binding.keyword.endBatchEdit();
        }
    }

    @Override
    public void onIconClick(int resId) {
        int cursor = binding.keyword.getSelectionStart();
        if (resId == R.drawable.ic_setting_home) callback.showDialog();
        else if (resId == R.drawable.ic_keyboard_remote) callback.onRemote();
        else if (resId == R.drawable.ic_keyboard_search) callback.onSearch();
        else if (resId == R.drawable.ic_keyboard_left) onMoveLeft(cursor);
        else if (resId == R.drawable.ic_keyboard_right) onMoveRight(cursor);
        else if (resId == R.drawable.ic_keyboard_back) onBackspace();
        else if (resId == R.drawable.ic_keyboard) adapter.toggle();
    }

    private void onMoveLeft(int cursor) {
        binding.keyword.setSelection(--cursor < 0 ? 0 : cursor);
    }

    private void onMoveRight(int cursor) {
        binding.keyword.setSelection(++cursor > binding.keyword.length() ? binding.keyword.length() : cursor);
    }

    private int cursor(Editable editable) {
        int selected = binding.keyword.getSelectionStart();
        return selected < 0 ? editable.length() : Math.min(selected, editable.length());
    }

    private void onBackspace() {
        Editable editable = binding.keyword.getText();
        if (editable == null) return;
        int cursor = cursor(editable);
        if (cursor <= 0) return;
        int previous = Character.offsetByCodePoints(editable, cursor, -1);
        binding.keyword.beginBatchEdit();
        try {
            editable.delete(previous, cursor);
            binding.keyword.setSelection(Math.min(previous, editable.length()));
        } finally {
            binding.keyword.endBatchEdit();
        }
    }

    @Override
    public boolean onLongClick(int resId) {
        if (resId != R.drawable.ic_keyboard_back) return false;
        Editable editable = binding.keyword.getText();
        if (editable != null && editable.length() > 0) {
            binding.keyword.beginBatchEdit();
            try {
                editable.clear();
                binding.keyword.setSelection(0);
            } finally {
                binding.keyword.endBatchEdit();
            }
        }
        return true;
    }

    public interface Callback {

        void showDialog();

        void onRemote();

        void onSearch();
    }
}
