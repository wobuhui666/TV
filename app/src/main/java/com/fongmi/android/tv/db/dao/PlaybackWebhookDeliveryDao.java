package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.fongmi.android.tv.bean.PlaybackWebhookDelivery;

import java.util.List;

@Dao
public interface PlaybackWebhookDeliveryDao {
    @Query("SELECT * FROM PlaybackWebhookDelivery WHERE failedAt = 0 AND nextAttemptAt <= :now ORDER BY nextAttemptAt LIMIT :limit")
    List<PlaybackWebhookDelivery> ready(long now, int limit);

    @Query("SELECT * FROM PlaybackWebhookDelivery WHERE failedAt > 0 ORDER BY failedAt DESC")
    List<PlaybackWebhookDelivery> failed();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrUpdate(PlaybackWebhookDelivery item);

    @Query("DELETE FROM PlaybackWebhookDelivery WHERE deliveryId = :deliveryId")
    void delete(String deliveryId);

    @Query("DELETE FROM PlaybackWebhookDelivery WHERE failedAt > 0 AND failedAt < :cutoff")
    void deleteDeadLettersBefore(long cutoff);
}
