package com.fongmi.android.tv.ai.skip;

import com.google.gson.annotations.SerializedName;

public final class AiSkipResult {
    @SerializedName("jobId")
    private String jobId;
    @SerializedName("status")
    private String status;
    @SerializedName("openingMs")
    private long openingMs;
    @SerializedName("endingMs")
    private long endingMs;
    @SerializedName("durationMs")
    private long durationMs;
    @SerializedName("confidence")
    private Confidence confidence;
    @SerializedName("error")
    private String error;

    public String getJobId() { return jobId == null ? "" : jobId; }
    public String getStatus() { return status == null ? "" : status; }
    public long getOpeningMs() { return Math.max(0, openingMs); }
    public long getEndingMs() { return Math.max(0, endingMs); }
    public long getDurationMs() { return Math.max(0, durationMs); }
    public float getOpeningConfidence() { return confidence == null ? 0 : confidence.opening; }
    public float getEndingConfidence() { return confidence == null ? 0 : confidence.ending; }
    public String getError() { return error == null ? "" : error; }

    public boolean isCompleted() { return "completed".equalsIgnoreCase(getStatus()); }
    public boolean isPending() { return "pending".equalsIgnoreCase(getStatus()) || "processing".equalsIgnoreCase(getStatus()); }
    public boolean hasUsableBoundary() {
        return (getOpeningMs() > 0 && getOpeningConfidence() >= 0.8f)
                || (getEndingMs() > 0 && getEndingConfidence() >= 0.8f);
    }

    private static final class Confidence {
        private float opening;
        private float ending;
    }
}
