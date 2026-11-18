package com.fongmi.android.tv.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.security.MessageDigest;

public final class GaussianBlurTransformation extends BitmapTransformation {

    private static final String ID = "com.fongmi.android.tv.utils.GaussianBlurTransformation.v1";
    private static final byte[] ID_BYTES = ID.getBytes(Key.CHARSET);
    private static final float SCALE = 0.08f;
    private static final int RADIUS = 3;

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap source, int outWidth, int outHeight) {
        int width = Math.max(1, Math.round(source.getWidth() * SCALE));
        int height = Math.max(1, Math.round(source.getHeight() * SCALE));
        Bitmap result = pool.get(width, height, Bitmap.Config.ARGB_8888);
        result.setHasAlpha(source.hasAlpha());
        result.setDensity(source.getDensity());

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(source, null, new Rect(0, 0, width, height), paint);
        canvas.setBitmap(null);
        blurBitmap(result);
        return result;
    }

    /** Blurs an owned, mutable software bitmap in place; run on a worker after downsampling. */
    @NonNull
    public static Bitmap blurBitmap(@NonNull Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        int[] output = new int[pixels.length];
        int[] scratch = new int[pixels.length];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        boxBlur(pixels, output, scratch, width, height);
        boxBlur(output, pixels, scratch, width, height);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private static void boxBlur(int[] input, int[] output, int[] scratch, int width, int height) {
        int diameter = RADIUS * 2 + 1;
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            int alpha = 0;
            int red = 0;
            int green = 0;
            int blue = 0;
            for (int i = -RADIUS; i <= RADIUS; i++) {
                int color = input[offset + clamp(i, 0, width - 1)];
                alpha += color >>> 24;
                red += color >> 16 & 0xff;
                green += color >> 8 & 0xff;
                blue += color & 0xff;
            }
            for (int x = 0; x < width; x++) {
                scratch[offset + x] = color(alpha / diameter, red / diameter, green / diameter, blue / diameter);
                int remove = input[offset + clamp(x - RADIUS, 0, width - 1)];
                int add = input[offset + clamp(x + RADIUS + 1, 0, width - 1)];
                alpha += (add >>> 24) - (remove >>> 24);
                red += (add >> 16 & 0xff) - (remove >> 16 & 0xff);
                green += (add >> 8 & 0xff) - (remove >> 8 & 0xff);
                blue += (add & 0xff) - (remove & 0xff);
            }
        }
        for (int x = 0; x < width; x++) {
            int alpha = 0;
            int red = 0;
            int green = 0;
            int blue = 0;
            for (int i = -RADIUS; i <= RADIUS; i++) {
                int color = scratch[clamp(i, 0, height - 1) * width + x];
                alpha += color >>> 24;
                red += color >> 16 & 0xff;
                green += color >> 8 & 0xff;
                blue += color & 0xff;
            }
            for (int y = 0; y < height; y++) {
                output[y * width + x] = color(alpha / diameter, red / diameter, green / diameter, blue / diameter);
                int remove = scratch[clamp(y - RADIUS, 0, height - 1) * width + x];
                int add = scratch[clamp(y + RADIUS + 1, 0, height - 1) * width + x];
                alpha += (add >>> 24) - (remove >>> 24);
                red += (add >> 16 & 0xff) - (remove >> 16 & 0xff);
                green += (add >> 8 & 0xff) - (remove >> 8 & 0xff);
                blue += (add & 0xff) - (remove & 0xff);
            }
        }
    }

    private static int color(int alpha, int red, int green, int blue) {
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        messageDigest.update(ID_BYTES);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof GaussianBlurTransformation;
    }

    @Override
    public int hashCode() {
        return ID.hashCode();
    }
}
