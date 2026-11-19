package com.fongmi.android.tv.ui.activity;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic synthetic artwork, with visible composition and no external files or rights. */
final class CalendarArtworkFixtures {
    private static final Map<String, byte[]> IMAGES = new ConcurrentHashMap<>();
    private static final Pattern ID = Pattern.compile("(\\d+)");

    private CalendarArtworkFixtures() { }

    static byte[] image(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return IMAGES.computeIfAbsent(name, CalendarArtworkFixtures::draw);
    }

    private static byte[] draw(String name) {
        Matcher matcher = ID.matcher(name);
        int seed = matcher.find() ? Integer.parseInt(matcher.group(1)) : 11801;
        boolean logo = name.contains("logo");
        boolean portrait = name.contains("poster");
        int width = logo ? 640 : portrait ? 480 : 1280;
        int height = logo ? 160 : portrait ? 720 : 720;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (logo) {
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            paint.setTextSize(67);
            paint.setShadowLayer(2, 0, 2, Color.argb(100, 0, 0, 0));
            canvas.drawText("HORIZON " + (seed % 100), 16, 91, paint);
            paint.clearShadowLayer();
            paint.setTextSize(22);
            canvas.drawText("SYNTHETIC TITLE ARTWORK", 20, 135, paint);
        } else {
            int palette = Math.floorMod(seed, 3);
            int top = palette == 0 ? Color.rgb(27, 55, 89) : palette == 1 ? Color.rgb(115, 42, 64) : Color.rgb(22, 78, 80);
            int horizon = palette == 0 ? Color.rgb(225, 150, 114) : palette == 1 ? Color.rgb(240, 174, 116) : Color.rgb(204, 200, 120);
            paint.setShader(new LinearGradient(0, 0, 0, height, top, horizon, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
            paint.setColor(Color.argb(160, 252, 211, 144));
            float sunX = width * (.58f + palette * .1f);
            canvas.drawCircle(sunX, height * .31f, height * .09f, paint);
            paint.setColor(Color.argb(95, 255, 227, 185));
            canvas.drawRect(width * .05f, height * .25f, width * .49f, height * .26f, paint);
            canvas.drawRect(width * .48f, height * .37f, width * .94f, height * .38f, paint);
            mountain(canvas, paint, width, height, .54f, Color.rgb(44, 56, 73), palette);
            mountain(canvas, paint, width, height, .68f, Color.rgb(25, 34, 47), palette + 2);
            paint.setColor(Color.rgb(14, 23, 34));
            canvas.drawRect(0, height * .84f, width, height, paint);
            float figureX = width * .42f;
            float figureY = height * .84f;
            paint.setColor(Color.rgb(13, 18, 28));
            canvas.drawCircle(figureX, figureY - height * .19f, height * .026f, paint);
            canvas.drawRoundRect(figureX - height * .035f, figureY - height * .16f,
                    figureX + height * .035f, figureY - height * .035f, 10, 10, paint);
            paint.setStrokeWidth(height * .014f);
            canvas.drawLine(figureX - height * .015f, figureY - height * .05f, figureX - height * .027f, figureY, paint);
            canvas.drawLine(figureX + height * .015f, figureY - height * .05f, figureX + height * .036f, figureY, paint);
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            paint.setTextSize(portrait ? 54 : 65);
            canvas.drawText("FAR HORIZON", width * .055f, height * .12f, paint);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(portrait ? 18 : 22);
            canvas.drawText("SYNTHETIC TEST ARTWORK · " + seed, width * .055f, height * .96f, paint);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw new AssertionError("Encode synthetic artwork");
        bitmap.recycle();
        return bytes.toByteArray();
    }

    private static void mountain(Canvas canvas, Paint paint, int width, int height, float base, int color, int variation) {
        Path mountain = new Path();
        mountain.moveTo(0, height);
        mountain.lineTo(0, height * base);
        for (int i = 1; i <= 8; i++) {
            float peak = base + (Math.floorMod(i + variation, 3) - 1) * .08f;
            mountain.lineTo(width * i / 8f, height * peak);
        }
        mountain.lineTo(width, height);
        mountain.close();
        paint.setColor(color);
        canvas.drawPath(mountain, paint);
    }
}
