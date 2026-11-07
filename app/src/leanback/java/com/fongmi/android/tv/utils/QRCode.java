package com.fongmi.android.tv.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.core.graphics.ColorUtils;

import com.fongmi.android.tv.ui.theme.JetStreamPalette;
import com.google.android.material.color.MaterialColors;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.util.EnumMap;
import java.util.Map;

public class QRCode {

    private static final int FINDER_MODULES = 7;
    private static final float MODULE_RADIUS_RATIO = 0.45f;
    private static final int MIN_QUIET_ZONE = 4;

    public static Bitmap getBitmap(String content, int size, int margin) {
        return getJetStreamBitmap(content, size, margin);
    }

    public static Bitmap getLightBitmap(String content, int size, int margin) {
        return getBitmap(content, size, margin, Color.BLACK, Color.WHITE);
    }

    public static Bitmap getJetStreamBitmap(String content, int size, int margin) {
        return getBitmap(content, size, margin, JetStreamPalette.onSurfaceInt(), JetStreamPalette.surfaceInt());
    }

    public static Bitmap getThemedBitmap(Context context, String content, int size, int margin) {
        int background = ColorUtils.setAlphaComponent(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, JetStreamPalette.surfaceInt()), 255);
        int foreground = ColorUtils.setAlphaComponent(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, JetStreamPalette.onSurfaceInt()), 255);
        if (ColorUtils.calculateContrast(foreground, background) < 7) {
            foreground = ColorUtils.calculateLuminance(background) > 0.5 ? Color.BLACK : Color.WHITE;
        }
        return getBitmap(content, size, margin, foreground, background);
    }

    private static Bitmap getBitmap(String content, int size, int margin, int foreground, int background) {
        try {
            BitMatrix matrix = encode(content, size, margin);
            int quietZone = detectQuietZone(matrix);
            int finderSize = detectFinderPx(matrix, quietZone);
            return render(matrix, quietZone, finderSize, foreground, background);
        } catch (Exception e) {
            return null;
        }
    }

    private static BitMatrix encode(String content, int size, int margin) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, Math.max(MIN_QUIET_ZONE, margin));
        return new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, ResUtil.dp2px(size), ResUtil.dp2px(size), hints);
    }

    private static int detectQuietZone(BitMatrix matrix) {
        for (int y = 0; y < matrix.getHeight(); y++) for (int x = 0; x < matrix.getWidth(); x++) if (matrix.get(x, y)) return Math.min(x, y);
        return 0;
    }

    private static int detectFinderPx(BitMatrix matrix, int quietZone) {
        for (int x = quietZone; x < matrix.getWidth(); x++) if (!matrix.get(x, quietZone)) return x - quietZone;
        return 0;
    }

    private static Bitmap render(BitMatrix matrix, int quietZone, int finderSize, int foreground, int background) {
        int width = matrix.getWidth(), height = matrix.getHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawBackground(canvas, width, height, background);
        float moduleSize = finderSize > 0 ? (float) finderSize / FINDER_MODULES : 1;
        int numModules = finderSize > 0 ? Math.round((width - 2f * quietZone) / moduleSize) : 0;
        drawDataModules(canvas, matrix, quietZone, moduleSize, numModules, width, height, foreground);
        if (finderSize <= 0) return bitmap;
        drawFinderPattern(canvas, quietZone, quietZone, finderSize, foreground, background);
        drawFinderPattern(canvas, width - quietZone - finderSize, quietZone, finderSize, foreground, background);
        drawFinderPattern(canvas, quietZone, height - quietZone - finderSize, finderSize, foreground, background);
        return bitmap;
    }

    private static void drawBackground(Canvas canvas, int width, int height, int color) {
        // Keep the full quiet zone opaque; the surrounding view supplies the rounded frame.
        canvas.drawColor(color);
    }

    private static void drawDataModules(Canvas canvas, BitMatrix matrix, int quietZone, float moduleSize, int numModules, int width, int height, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        float radius = moduleSize * MODULE_RADIUS_RATIO;
        for (int my = 0; my < numModules; my++) {
            for (int mx = 0; mx < numModules; mx++) {
                if (isFinderRegion(mx, my, numModules)) continue;
                float px = quietZone + (mx + 0.5f) * moduleSize;
                float py = quietZone + (my + 0.5f) * moduleSize;
                if (matrix.get(Math.min((int) px, width - 1), Math.min((int) py, height - 1))) canvas.drawCircle(px, py, radius, paint);
            }
        }
    }

    private static boolean isFinderRegion(int moduleX, int moduleY, int numModules) {
        boolean topLeft = moduleX < FINDER_MODULES && moduleY < FINDER_MODULES;
        boolean topRight = moduleX >= numModules - FINDER_MODULES && moduleY < FINDER_MODULES;
        boolean bottomLeft = moduleX < FINDER_MODULES && moduleY >= numModules - FINDER_MODULES;
        return topLeft || topRight || bottomLeft;
    }

    private static void drawFinderPattern(Canvas canvas, int left, int top, int finderSize, int foreground, int background) {
        float module = finderSize / (float) FINDER_MODULES;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(foreground);
        canvas.drawRoundRect(new RectF(left, top, left + finderSize, top + finderSize), module * 0.6f, module * 0.6f, paint);
        paint.setColor(background);
        canvas.drawRoundRect(new RectF(left + module, top + module, left + finderSize - module, top + finderSize - module), module * 0.4f, module * 0.4f, paint);
        paint.setColor(foreground);
        canvas.drawRoundRect(new RectF(left + 2 * module, top + 2 * module, left + finderSize - 2 * module, top + finderSize - 2 * module), module * 0.3f, module * 0.3f, paint);
    }
}
