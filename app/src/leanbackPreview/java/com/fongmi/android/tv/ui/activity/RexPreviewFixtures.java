package com.fongmi.android.tv.ui.activity;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.Base64;

import com.fongmi.android.tv.bean.RexSection;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.ui.rex.RexFeedResult;
import com.fongmi.android.tv.ui.rex.RexSections;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Deterministic offline feeds; the production presenters load these through ImgUtil/Glide. */
final class RexPreviewFixtures {
    private static final String[] TITLES = {"星际旅途", "山海之间", "追光的人", "夏日来信", "城市之声", "云端之上"};
    private static final int[] SKY = {0xFF172B63, 0xFF35655D, 0xFF704254, 0xFF59718A, 0xFF4F3979, 0xFF986C43};
    private static List<RexFeedResult> feeds;

    private RexPreviewFixtures() { }

    static List<RexFeedResult> feeds() {
        if (feeds != null) return feeds;
        feeds = new ArrayList<>();
        List<RexSection> sections = RexSections.home();
        int[] selected = {0, 1, 3, 8, 11};
        String[] posters = new String[TITLES.length];
        String[] backdrops = new String[TITLES.length];
        for (int i = 0; i < TITLES.length; i++) {
            posters[i] = artwork(i, false);
            backdrops[i] = artwork(i, true);
        }
        for (int sectionIndex = 0; sectionIndex < selected.length; sectionIndex++) {
            List<Vod> items = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int style = (sectionIndex + i) % TITLES.length;
                Vod item = new Vod();
                item.setId("tmdb:movie:" + (1000 + sectionIndex * 100 + i));
                item.setName(TITLES[style] + " " + (sectionIndex * 12 + i + 1));
                item.setPic(posters[style]);
                item.setBackdrop(backdrops[style]);
                item.setYear("2026");
                item.setGenreText(style % 2 == 0 ? "科幻、冒险" : "剧情、家庭");
                item.setRemarks("8." + (i % 9));
                item.setContent("离线固定影片资料。生产 Rex 首页控件、栏目顺序和副题格式验证。");
                items.add(item);
            }
            feeds.add(new RexFeedResult(sections.get(selected[sectionIndex]), items));
        }
        return feeds;
    }

    private static String artwork(int index, boolean landscape) {
        int width = landscape ? 960 : 432;
        int height = landscape ? 540 : 648;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, width, height, SKY[index], 0xFF101B2D, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        paint.setColor(Color.argb(205, 244, 214, 162));
        canvas.drawCircle(width * .73f, height * .24f, height * .12f, paint);
        for (int layer = 0; layer < 3; layer++) {
            Path mountains = new Path();
            float floor = height * (.57f + layer * .14f);
            mountains.moveTo(0, height);
            mountains.lineTo(0, floor);
            for (int step = 0; step <= 8; step++) {
                float x = width * step / 8f;
                float y = floor - height * (.08f + .14f * (float) Math.sin(step * 1.6 + index + layer));
                mountains.lineTo(x, y);
            }
            mountains.lineTo(width, height);
            mountains.close();
            paint.setColor(new int[] {0xFF30435B, 0xFF22344B, 0xFF132339}[layer]);
            canvas.drawPath(mountains, paint);
        }
        if (index % 2 == 0) {
            paint.setColor(0xBBF5F2E7);
            for (int i = 0; i < 36; i++) canvas.drawCircle((i * 83 + 29) % width, (i * 47 + 13) % (height / 2), i % 3 + 1, paint);
        }
        if (!landscape) {
            paint.setColor(0xFFFAF7F0);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(38);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            canvas.drawText(TITLES[index], width / 2f, height * .79f, paint);
            paint.setTextSize(16);
            canvas.drawText("2026  /  OFFLINE PREVIEW", width / 2f, height * .85f, paint);
        }
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        bitmap.recycle();
        return "data:image/png;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP);
    }
}
