package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.History;

import java.util.List;

@Dao
public abstract class HistoryDao extends BaseDao<History> {

    @Query("SELECT * FROM History")
    public abstract List<History> findAll();

    @Query("SELECT * FROM History WHERE cid = :cid AND createTime >= :createTime ORDER BY createTime DESC LIMIT 60")
    public abstract List<History> find(int cid, long createTime);

    @Query("SELECT * FROM History WHERE cid = :cid AND `key` = :key")
    public abstract History find(int cid, String key);

    @Query("SELECT * FROM History WHERE cid = :cid AND vodName = :vodName ORDER BY createTime DESC")
    public abstract List<History> findByName(int cid, String vodName);

    @Query("DELETE FROM History WHERE cid = :cid AND `key` = :key")
    public abstract void delete(int cid, String key);

    @Query("DELETE FROM History WHERE cid = :cid")
    public abstract void delete(int cid);

    @Query("DELETE FROM History")
    public abstract void delete();

    @Query("UPDATE History SET opening = :opening, openingSource = :openingSource, ending = :ending, endingSource = :endingSource " +
            "WHERE cid = :cid AND `key` = :key " +
            "AND IFNULL(vodRemarks, '') = IFNULL(:vodRemarks, '') AND IFNULL(episodeUrl, '') = IFNULL(:episodeUrl, '') " +
            "AND opening = :previousOpening AND IFNULL(openingSource, 'unknown') = IFNULL(:previousOpeningSource, 'unknown') " +
            "AND ending = :previousEnding AND IFNULL(endingSource, 'unknown') = IFNULL(:previousEndingSource, 'unknown')")
    public abstract int updateBoundariesIfUnchanged(int cid, String key, String vodRemarks, String episodeUrl,
                                                    long previousOpening, String previousOpeningSource,
                                                    long previousEnding, String previousEndingSource,
                                                    long opening, String openingSource, long ending, String endingSource);
}
