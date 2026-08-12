package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(indices = {@Index("eventId"), @Index("nextAttemptAt"), @Index("failedAt")})
public class PlaybackWebhookDelivery {
    @NonNull
    @PrimaryKey
    public String deliveryId = "";
    @NonNull
    public String eventId = "";
    @NonNull
    public String endpointId = "";
    @NonNull
    public String endpointUrl = "";
    @NonNull
    public String payload = "";
    public int attempts;
    public int maxRetries;
    public long nextAttemptAt;
    public long failedAt;
    @NonNull
    public String lastError = "";
}
