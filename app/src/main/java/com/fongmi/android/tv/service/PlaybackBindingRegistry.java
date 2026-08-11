package com.fongmi.android.tv.service;

final class PlaybackBindingRegistry<T> {

    private Runnable onReplaced;
    private T owner;

    public void claim(T owner, Runnable onReplaced) {
        if (owns(owner)) return;
        Runnable previous = this.onReplaced;
        this.owner = owner;
        this.onReplaced = onReplaced;
        if (previous != null) previous.run();
    }

    public boolean owns(T owner) {
        return this.owner == owner;
    }

    public boolean release(T owner) {
        if (!owns(owner)) return false;
        this.owner = null;
        this.onReplaced = null;
        return true;
    }
}
