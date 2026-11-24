package com.fongmi.android.tv.ui.activity;

import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.calendar.CalendarProvider;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.calendar.AiringCalendarView;
import com.fongmi.android.tv.ui.calendar.CalendarAccountDialogs;

import kotlin.Unit;

/** Preview-only calendar surface that receives the shipping theme refresh signal. */
public final class CalendarThemePreviewActivity extends BaseActivity {
    private AiringCalendarView calendar;
    private CalendarAccountDialogs accounts;

    @Override protected ViewBinding getBinding() {
        if (calendar == null) calendar = new AiringCalendarView(this);
        return () -> calendar;
    }

    @Override protected void initView(Bundle savedInstanceState) {
        accounts = new CalendarAccountDialogs(this, sync -> Unit.INSTANCE, busy -> Unit.INSTANCE);
    }

    @Override protected void onThemeChanged() { calendar.refreshTheme(); }

    public AiringCalendarView calendar() { return calendar; }

    public void showAccount(CalendarProvider provider) { accounts.show(provider); }

    @Override protected void onDestroy() {
        accounts.close();
        super.onDestroy();
    }
}
