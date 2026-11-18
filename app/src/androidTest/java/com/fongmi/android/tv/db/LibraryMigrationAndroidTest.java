package com.fongmi.android.tv.db;

import android.content.Context;

import androidx.room.Room;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LibraryMigrationAndroidTest {
    private Context context;
    private String name;
    private AppDatabase database;

    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "library-migration-" + UUID.randomUUID();
    }
    @After public void cleanup() {
        if (database != null) database.close();
        context.deleteDatabase(name);
    }
    private AppDatabase open() {
        return Room.databaseBuilder(context, AppDatabase.class, name).addMigrations(Migrations.MIGRATION_38_39).allowMainThreadQueries().build();
    }

    @Test public void shouldUpgradeVersion38WithoutDeletingRecordsOrInventingMatches() {
        database = open();
        SupportSQLiteDatabase sqlite = database.getOpenHelper().getWritableDatabase();
        sqlite.execSQL("DROP TABLE History");
        sqlite.execSQL("CREATE TABLE History (`key` TEXT NOT NULL, vodPic TEXT, vodName TEXT, vodFlag TEXT, vodRemarks TEXT, episodeUrl TEXT, episodeLabelGenerated INTEGER NOT NULL DEFAULT 0, revSort INTEGER NOT NULL, revPlay INTEGER NOT NULL, createTime INTEGER NOT NULL, opening INTEGER NOT NULL, ending INTEGER NOT NULL, openingSource TEXT DEFAULT 'unknown', endingSource TEXT DEFAULT 'unknown', position INTEGER NOT NULL, duration INTEGER NOT NULL, speed REAL NOT NULL, scale INTEGER NOT NULL, cid INTEGER NOT NULL, PRIMARY KEY(`key`))");
        sqlite.execSQL("CREATE INDEX index_History_cid_createTime ON History(cid,createTime)");
        sqlite.execSQL("INSERT INTO History (`key`,vodName,vodRemarks,episodeUrl,revSort,revPlay,createTime,opening,ending,position,duration,speed,scale,cid) VALUES ('a@@@b@@@1','Show','第4集','four',0,0,123,-1,-1,90000,1800000,1,-1,1)");
        sqlite.execSQL("DROP TABLE Keep");
        sqlite.execSQL("CREATE TABLE Keep (`key` TEXT NOT NULL,siteName TEXT,vodName TEXT,vodPic TEXT,createTime INTEGER NOT NULL,type INTEGER NOT NULL,cid INTEGER NOT NULL,PRIMARY KEY(`key`))");
        sqlite.execSQL("INSERT INTO Keep (`key`,vodName,createTime,type,cid) VALUES ('a@@@b','Show',123,0,1)");
        sqlite.execSQL("DELETE FROM room_master_table");
        sqlite.setVersion(38);
        database.close();
        database = open();
        History history = database.getHistoryDao().find(1, "a@@@b@@@1");
        assertNotNull(history);
        assertEquals(90_000, history.getPosition());
        assertEquals("four", history.getEpisodeUrl());
        assertEquals("", history.getMediaRef());
        assertNull(history.getMediaSeason());
        assertNull(history.getMediaEpisode());
        assertEquals(1, database.getKeepDao().findAll().size());
        assertEquals(AppDatabase.VERSION, database.getOpenHelper().getReadableDatabase().getVersion());
    }

    @Test public void shouldRoundTripWorkAndEpisodeReferencesThroughRoomAndBackup() {
        database = open();
        History history = new History(); history.setKey("a@@@b@@@1"); history.setCid(1); history.setVodName("Show");
        history.setMediaRef("tmdb:tv:32"); history.setMediaSeason(2); history.setMediaEpisode(4); history.setPosition(90_000); history.setDuration(1_800_000);
        Keep keep = new Keep(); keep.setKey("tmdb:tv:32"); keep.setType(Keep.TYPE_DISCOVER); keep.setMediaRef("tmdb:tv:32");
        database.getHistoryDao().insertOrUpdate(history); database.getKeepDao().insertOrUpdate(keep);
        database.close(); database = open();
        History stored = database.getHistoryDao().findByMedia(1, "tmdb:tv:32");
        assertEquals(Integer.valueOf(2), stored.getMediaSeason());
        assertEquals(Integer.valueOf(4), stored.getMediaEpisode());
        Backup backup = new Backup(); backup.setHistory(List.of(stored)); backup.setKeep(database.getKeepDao().findAll());
        Backup restored = Backup.objectFrom(backup.toString());
        assertEquals("tmdb:tv:32", restored.getHistory().get(0).getMediaRef());
        assertEquals(Integer.valueOf(4), restored.getHistory().get(0).getMediaEpisode());
        assertEquals("tmdb:tv:32", restored.getKeep().get(0).getMediaRef());
        History old = Backup.objectFrom("{\"history\":[{\"key\":\"a@@@b@@@1\",\"position\":90000}]}").getHistory().get(0);
        assertEquals("", old.getMediaRef());
        assertNull(old.getMediaEpisode());
    }

    @Test public void shouldFindTheSameWorkAcrossTitlesWithoutMergingAnotherEpisodeOrConfiguration() {
        database = open();
        for (int i = 0; i < 4; i++) {
            History history = new History(); history.setKey("site-" + i + "@@@work@@@1"); history.setCid(i == 3 ? 2 : 1);
            history.setVodName(i == 0 ? "作品" : "Original Title"); history.setMediaRef("tmdb:tv:40"); history.setMediaSeason(1); history.setMediaEpisode(i == 2 ? 3 : 2);
            database.getHistoryDao().insertOrUpdate(history);
        }
        assertEquals(2, database.getHistoryDao().findByMediaContext(1, "tmdb:tv:40", 1, 2).size());
        assertEquals(1, database.getHistoryDao().findByMediaContext(1, "tmdb:tv:40", 1, 3).size());
        assertTrue(database.getHistoryDao().findByMediaContext(1, "tmdb:tv:40", null, null).isEmpty());
        History movie = new History(); movie.setKey("movie@@@work@@@1"); movie.setCid(1); movie.setMediaRef("tmdb:movie:40");
        database.getHistoryDao().insertOrUpdate(movie);
        assertEquals(1, database.getHistoryDao().findByMediaContext(1, "tmdb:movie:40", null, null).size());
    }
}
