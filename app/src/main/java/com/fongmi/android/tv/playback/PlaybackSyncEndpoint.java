package com.fongmi.android.tv.playback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlaybackSyncEndpoint {
    public String kind = "remote";
    public String id = "";
    public String name = "";
    public String url = "";
    public String token = "";
    public List<String> siteKeys = new ArrayList<>();
    public boolean enabled = true;
    public boolean syncOnStart;
    public int periodMinutes;
    public int limit = 100;
    public long cursor;
    public Map<String, Long> cursors = new HashMap<>();
    public long lastSuccessAt;
    public Map<String, Long> lastSuccessByConfig = new HashMap<>();
    public String lastError = "";
    public Map<String, String> lastErrorByConfig = new HashMap<>();
    public String fieldPreset = "standard";
    public List<String> customFields = new ArrayList<>();
    public List<String> events = new ArrayList<>(List.of("playback.progress", "playback.ended", "playback.deleted"));
    public int progressIntervalSeconds = 30;
    public int retries = 3;
}
