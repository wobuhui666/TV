package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.PlaybackDeleteTombstone;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.setting.Setting;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class PlaybackMerge {

    private PlaybackMerge() {
    }

    public static synchronized boolean apply(PlaybackProgress remote) {
        if (remote == null || Setting.isIncognito()) return false;
        int cid = TextUtils.isEmpty(remote.configKey) ? VodConfig.getCid() : PlaybackConfigIdentity.cidForKey(remote.configKey);
        if (cid <= 0 || TextUtils.isEmpty(remote.siteKey) || TextUtils.isEmpty(remote.vodId) || remote.updatedAt <= latestTombstone(remote, cid)) return false;
        History local = find(cid, remote);
        if (local != null && remote.updatedAt <= local.getCreateTime()) return false;
        History history = local == null ? new History() : local.copy();
        history.setKey(local == null ? historyKey(remote.siteKey, remote.vodId, cid) : local.getKey());
        history.setCid(cid);
        history.setVodName(remote.vodName);
        history.setVodPic(remote.vodPic);
        history.setVodFlag(remote.flag);
        history.setVodRemarks(remote.episodeName);
        history.setEpisodeUrl(remote.episodeUrl);
        history.setPosition(remote.positionMs);
        history.setDuration(remote.durationMs);
        history.setCreateTime(remote.updatedAt);
        history.setSpeed(remote.speed <= 0 ? 1 : remote.speed);
        AppDatabase.get().getHistoryDao().insertOrUpdate(history);
        return true;
    }

    public static synchronized void delete(String scope, String siteKey, String vodId, long deletedAt) {
        delete(scope, siteKey, vodId, deletedAt, "");
    }

    public static synchronized void delete(String scope, String siteKey, String vodId, long deletedAt, String configKey) {
        writeTombstone(scope, siteKey, vodId, deletedAt, configKey);
        int cid = TextUtils.isEmpty(configKey) ? VodConfig.getCid() : PlaybackConfigIdentity.cidForKey(configKey);
        if (cid > 0) applyDelete(cid, scope, siteKey, vodId, deletedAt);
    }

    public static synchronized void writeTombstone(String scope, String siteKey, String vodId, long deletedAt) {
        writeTombstone(scope, siteKey, vodId, deletedAt, "");
    }

    private static void writeTombstone(String scope, String siteKey, String vodId, long deletedAt, String configKey) {
        PlaybackDeleteTombstone tombstone = new PlaybackDeleteTombstone();
        tombstone.id = UUID.randomUUID().toString();
        tombstone.configKey = TextUtils.isEmpty(configKey) ? PlaybackConfigIdentity.currentKey() : configKey;
        tombstone.scope = TextUtils.isEmpty(scope) ? "item" : scope;
        tombstone.siteKey = safe(siteKey);
        tombstone.vodId = safe(vodId);
        tombstone.historyKey = tombstone.siteKey + AppDatabase.SYMBOL + tombstone.vodId;
        tombstone.deletedAt = deletedAt <= 0 ? System.currentTimeMillis() : deletedAt;
        AppDatabase.get().getPlaybackDeleteTombstoneDao().insertOrUpdate(tombstone);
        AppDatabase.get().getPlaybackDeleteTombstoneDao().deleteBefore(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90));
    }

    private static void applyDelete(int cid, String scope, String siteKey, String vodId, long deletedAt) {
        long time = deletedAt <= 0 ? System.currentTimeMillis() : deletedAt;
        for (History history : AppDatabase.get().getHistoryDao().findAll(cid)) {
            boolean match = "all".equals(scope)
                    || "site".equals(scope) && safe(siteKey).equals(history.getSiteKey())
                    || "item".equals(scope) && safe(siteKey).equals(history.getSiteKey()) && safe(vodId).equals(history.getVodId());
            if (match && history.getCreateTime() <= time) AppDatabase.get().getHistoryDao().delete(cid, history.getKey());
        }
    }

    private static History find(int cid, PlaybackProgress remote) {
        String base = remote.siteKey + AppDatabase.SYMBOL + remote.vodId;
        History exact = AppDatabase.get().getHistoryDao().find(cid, base);
        if (exact == null) exact = AppDatabase.get().getHistoryDao().find(cid, historyKey(remote.siteKey, remote.vodId, cid));
        if (exact != null) return exact;
        List<History> candidates = AppDatabase.get().getHistoryDao().findByKeyPrefix(cid, base);
        for (History item : candidates) if (!remote.episodeUrl.isEmpty() && remote.episodeUrl.equals(item.getEpisodeUrl())) return item;
        for (History item : candidates) if (!remote.flag.isEmpty() && remote.flag.equals(item.getVodFlag()) && remote.episodeName.equals(item.getVodRemarks())) return item;
        for (History item : candidates) if (remote.episodeName.equals(item.getVodRemarks())) return item;
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static long latestTombstone(PlaybackProgress remote, int cid) {
        long latest = 0;
        String configKey = PlaybackConfigIdentity.keyForCid(cid);
        for (PlaybackDeleteTombstone item : AppDatabase.get().getPlaybackDeleteTombstoneDao().findAll()) {
            if (!item.configKey.equals(configKey)) continue;
            boolean match = "all".equals(item.scope) || "site".equals(item.scope) && item.siteKey.equals(remote.siteKey)
                    || "item".equals(item.scope) && item.siteKey.equals(remote.siteKey) && item.vodId.equals(remote.vodId);
            if (match) latest = Math.max(latest, item.deletedAt);
        }
        return latest;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String historyKey(String siteKey, String vodId, int cid) {
        return siteKey + AppDatabase.SYMBOL + vodId + AppDatabase.SYMBOL + cid;
    }
}
