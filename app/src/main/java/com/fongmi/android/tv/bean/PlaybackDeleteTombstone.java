package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(indices = {@Index("deletedAt")})
public class PlaybackDeleteTombstone {
    @NonNull
    @PrimaryKey
    public String id = "";
    @NonNull
    public String configKey = "";
    @NonNull
    public String scope = "item";
    @NonNull
    public String historyKey = "";
    @NonNull
    public String siteKey = "";
    @NonNull
    public String vodId = "";
    public long deletedAt;
}
