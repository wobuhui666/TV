package com.fongmi.android.tv.ui.custom;

/** A repeated/released seek key belongs to the original device and key-down event. */
final class SeekKeySequence {

    private int keyCode = -1;
    private int deviceId;
    private long downTime;

    boolean down(int keyCode, int deviceId, long downTime, int repeatCount) {
        if (repeatCount == 0) {
            this.keyCode = keyCode;
            this.deviceId = deviceId;
            this.downTime = downTime;
        }
        return owns(keyCode, deviceId, downTime);
    }

    boolean up(int keyCode, int deviceId, long downTime) {
        if (!owns(keyCode, deviceId, downTime)) return false;
        clear();
        return true;
    }

    private boolean owns(int keyCode, int deviceId, long downTime) {
        return this.keyCode == keyCode && this.deviceId == deviceId && this.downTime == downTime;
    }

    void clear() {
        keyCode = -1;
    }
}
