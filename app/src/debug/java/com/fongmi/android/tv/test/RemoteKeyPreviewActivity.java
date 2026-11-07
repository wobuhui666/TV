package com.fongmi.android.tv.test;

import android.app.Dialog;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

/** Small real-window fixture for the local browser remote. Included only in debug/preview. */
public final class RemoteKeyPreviewActivity extends FragmentActivity {

    public Button first;
    public Button second;
    public int clicks;
    public int dialogClicks;
    public int backs;
    public int numericTaps;
    public int menuTaps;
    public int lastNumeric;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = content();
        first = button("First");
        second = button("Second");
        first.setNextFocusDownId(second.getId());
        second.setNextFocusUpId(first.getId());
        first.setOnClickListener(view -> clicks++);
        second.setOnClickListener(view -> clicks++);
        content.addView(first);
        content.addView(second);
        setContentView(content);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { backs++; }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                numericTaps++;
                lastNumeric = code - KeyEvent.KEYCODE_0;
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP) menuTaps++;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    public Sheet showNestedDialog() {
        Fragment host = new Fragment();
        getSupportFragmentManager().beginTransaction().add(host, "remote-host").commitNow();
        Sheet sheet = new Sheet();
        sheet.showNow(host.getChildFragmentManager(), "remote-sheet");
        return sheet;
    }

    private LinearLayout content() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(48, 48, 48, 48);
        return content;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setId(View.generateViewId());
        button.setText(text);
        button.setFocusable(true);
        button.setLayoutParams(new LinearLayout.LayoutParams(560, 100));
        return button;
    }

    public static final class Sheet extends DialogFragment {

        public Button first;
        public Button second;

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle state) {
            RemoteKeyPreviewActivity owner = (RemoteKeyPreviewActivity) requireActivity();
            Dialog dialog = new Dialog(owner);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout content = owner.content();
            first = owner.button("Dialog first");
            second = owner.button("Dialog second");
            first.setNextFocusDownId(second.getId());
            second.setNextFocusUpId(first.getId());
            first.setOnClickListener(view -> owner.dialogClicks++);
            second.setOnClickListener(view -> owner.dialogClicks++);
            content.addView(first);
            content.addView(second);
            dialog.setContentView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return dialog;
        }
    }
}
