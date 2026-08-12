package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.fongmi.android.tv.bean.PlaybackDeleteTombstone;

import java.util.List;

@Dao
public interface PlaybackDeleteTombstoneDao {
    @Query("SELECT * FROM PlaybackDeleteTombstone")
    List<PlaybackDeleteTombstone> findAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrUpdate(PlaybackDeleteTombstone item);

    @Query("DELETE FROM PlaybackDeleteTombstone WHERE deletedAt < :cutoff")
    void deleteBefore(long cutoff);
}
