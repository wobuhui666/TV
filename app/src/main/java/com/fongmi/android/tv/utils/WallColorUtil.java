package com.fongmi.android.tv.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.palette.graphics.Palette;

import java.io.File;

/** Shared wallpaper color rules; callers choose the worker/lifecycle. */
public final class WallColorUtil {

    private static final int[] BUILT_IN_COLORS = {0, 0xFF40C090, 0xFF4870E0, 0xFF48B0C0, 0xFF404040};

    private WallColorUtil() {
    }

    public static int getColor(int wall, int type, File cache) {
        if (type == 0 && wall > 0 && wall < BUILT_IN_COLORS.length) return BUILT_IN_COLORS[wall];
        if (cache == null || !cache.exists()) return BUILT_IN_COLORS[1];
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 8;
        Bitmap bitmap = BitmapFactory.decodeFile(cache.getAbsolutePath(), options);
        if (bitmap == null) return BUILT_IN_COLORS[1];
        try {
            Palette palette = Palette.from(bitmap).maximumColorCount(8).generate();
            Palette.Swatch swatch = palette.getVibrantSwatch();
            if (swatch == null) swatch = palette.getDominantSwatch();
            return swatch != null ? swatch.getRgb() : BUILT_IN_COLORS[1];
        } finally {
            bitmap.recycle();
        }
    }
}
