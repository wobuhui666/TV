package com.fongmi.android.tv.db;

import android.content.Context;
import android.database.Cursor;

import androidx.room.Room;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.playback.vod.VodHistoryPolicy;
import com.fongmi.android.tv.source.EpisodeTarget;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises the production migration and DAO against an isolated real Room database. */
@RunWith(AndroidJUnit4.class)
public final class HistoryGeneratedLabelAndroidTest {

    private static final String LEGACY_IDENTITY = "5fbafa85b4abd1a7cbde5d102966811f";
    private static final String LEGACY_HISTORY = "CREATE TABLE History (`key` TEXT NOT NULL, `vodPic` TEXT, `vodName` TEXT, `vodFlag` TEXT, `vodRemarks` TEXT, `episodeUrl` TEXT, `revSort` INTEGER NOT NULL, `revPlay` INTEGER NOT NULL, `createTime` INTEGER NOT NULL, `opening` INTEGER NOT NULL, `ending` INTEGER NOT NULL, `openingSource` TEXT, `endingSource` TEXT, `position` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `speed` REAL NOT NULL, `scale` INTEGER NOT NULL, `cid` INTEGER NOT NULL, PRIMARY KEY(`key`))";
    private static final String KEY = "fixture@@@movie";
    private static final int CID = 61;
    private Context context;
    private String databaseName;
    private AppDatabase database;

    @Before
    public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "history-label-test-" + UUID.randomUUID();
    }

    @After
    public void cleanup() {
        if (database != null) database.close();
        if (context != null && databaseName != null) context.deleteDatabase(databaseName);
    }

    @Test
    public void shouldMigrateVersion37WithoutLosingHistoryOrItsIndex() {
        createVersion37();
        database = openDatabase();
        History history = database.getHistoryDao().find(CID, KEY);
        assertNotNull(history);
        assertEquals("Show", history.getVodName());
        assertEquals("03", history.getVodRemarks());
        assertEquals("https://example.invalid/third", history.getEpisodeUrl());
        assertEquals(120_000, history.getPosition());
        assertEquals(1_800_000, history.getDuration());
        assertEquals(45_000, history.getOpening());
        assertEquals("manual", history.getOpeningSource());
        assertFalse(history.isEpisodeLabelGenerated());
        assertEquals(AppDatabase.VERSION, database.getOpenHelper().getReadableDatabase().getVersion());

        boolean column = false;
        try (Cursor cursor = database.getOpenHelper().getReadableDatabase().query("PRAGMA table_info(`History`)")) {
            while (cursor.moveToNext()) {
                if (!"episodeLabelGenerated".equals(cursor.getString(cursor.getColumnIndexOrThrow("name")))) continue;
                column = true;
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")));
                assertEquals("0", cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")));
            }
        }
        assertTrue(column);
        try (Cursor cursor = database.getOpenHelper().getReadableDatabase().query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_History_cid_createTime'")) {
            assertTrue(cursor.moveToFirst());
        }
    }

    @Test
    public void shouldKeepGeneratedProvenanceAfterDatabaseReopenAndBackupRoundTrip() {
        database = openDatabase();
        History history = new History();
        history.setKey(KEY);
        history.setCid(CID);
        history.setVodName("Show");
        Episode generated = Episode.create("03", "https://example.invalid/third");
        generated.setGeneratedLabel(true);
        new VodHistoryPolicy().updateEpisode(history, Flag.create("line"), generated);
        history.setPosition(120_000);
        history.setDuration(1_800_000);
        database.getHistoryDao().insertOrUpdate(history);
        database.close();
        database = openDatabase();

        History reloaded = database.getHistoryDao().find(CID, KEY);
        assertNotNull(reloaded);
        assertTrue(reloaded.isEpisodeLabelGenerated());
        assertTrue(reloaded.getEpisode().isGeneratedLabel());
        assertEquals(-1, reloaded.getEpisode().getNumber());
        assertFalse(EpisodeTarget.of(reloaded.getEpisode()).matches(Episode.create("第3集", "another-url")));
        assertEquals(120_000, reloaded.getPosition());

        Backup backup = new Backup();
        backup.setHistory(List.of(reloaded));
        History restored = Backup.objectFrom(backup.toString()).getHistory().get(0);
        assertTrue(restored.getEpisode().isGeneratedLabel());
        assertEquals("https://example.invalid/third", restored.getEpisodeUrl());
        assertEquals(120_000, restored.getPosition());

        new VodHistoryPolicy().updateEpisode(reloaded, Flag.create("line"), Episode.create("第3集", "real-episode"));
        database.getHistoryDao().insertOrUpdate(reloaded);
        assertFalse(database.getHistoryDao().find(CID, KEY).isEpisodeLabelGenerated());
    }

    private AppDatabase openDatabase() {
        return Room.databaseBuilder(context, AppDatabase.class, databaseName)
                .addMigrations(Migrations.MIGRATION_37_38)
                .allowMainThreadQueries().build();
    }

    private void createVersion37() {
        // Other entities are unchanged in version 38; create their real tables first.
        database = openDatabase();
        SupportSQLiteDatabase sqlite = database.getOpenHelper().getWritableDatabase();
        sqlite.beginTransaction();
        try {
            sqlite.execSQL("DROP TABLE History");
            sqlite.execSQL(LEGACY_HISTORY);
            sqlite.execSQL("CREATE INDEX index_History_cid_createTime ON History (`cid`, `createTime`)");
            sqlite.execSQL("INSERT INTO History (`key`,`vodName`,`vodFlag`,`vodRemarks`,`episodeUrl`,`revSort`,`revPlay`,`createTime`,`opening`,`ending`,`openingSource`,`endingSource`,`position`,`duration`,`speed`,`scale`,`cid`) VALUES (?,?,?,?,?,0,0,123,45000,60000,'manual','unknown',120000,1800000,1.0,-1,?)",
                    new Object[]{KEY, "Show", "line", "03", "https://example.invalid/third", CID});
            sqlite.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42", new Object[]{LEGACY_IDENTITY});
            sqlite.setVersion(37);
            sqlite.setTransactionSuccessful();
        } finally {
            sqlite.endTransaction();
            database.close();
            database = null;
        }
    }
}
