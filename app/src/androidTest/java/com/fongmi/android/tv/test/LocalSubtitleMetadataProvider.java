package com.fongmi.android.tv.test;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.FileNotFoundException;
import java.util.concurrent.atomic.AtomicInteger;

/** Metadata-only document fixture in the instrumentation APK, never the production app. */
public final class LocalSubtitleMetadataProvider extends ContentProvider {

    private final AtomicInteger queries = new AtomicInteger();
    private final AtomicInteger opens = new AtomicInteger();

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String sortOrder) {
        queries.incrementAndGet();
        if ("opaque".equals(uri.getLastPathSegment())) {
            MatrixCursor cursor = new MatrixCursor(new String[]{MediaStore.MediaColumns.DISPLAY_NAME});
            cursor.addRow(new Object[]{"cloud-video.mp4"});
            return cursor;
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{MediaStore.MediaColumns.DATA});
        cursor.addRow(new Object[]{uri.getQueryParameter("path")});
        return cursor;
    }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        if ("reset".equals(method)) {
            queries.set(0);
            opens.set(0);
        }
        Bundle result = new Bundle();
        result.putInt("queries", queries.get());
        result.putInt("opens", opens.get());
        return result;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        opens.incrementAndGet();
        throw new FileNotFoundException("This fixture permits metadata lookup, never content copying");
    }

    @Override public String getType(Uri uri) { return "video/mp4"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
}
