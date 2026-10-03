package com.fongmi.android.tv.utils;

/** Image geometry only: a portrait must stay readable instead of filling a wide hero. */
public final class HomeArtworkPolicy {

    private HomeArtworkPolicy() {
    }

    public static boolean isLandscape(int width, int height) {
        return width > 0 && height > 0 && (float) width / height >= 1.3f;
    }

    public static Placement place(int viewWidth, int viewHeight, int imageWidth, int imageHeight) {
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return new Placement(1, 0, 0);
        if (isLandscape(imageWidth, imageHeight)) {
            float scale = Math.max((float) viewWidth / imageWidth, (float) viewHeight / imageHeight);
            return new Placement(scale, (viewWidth - imageWidth * scale) / 2, (viewHeight - imageHeight * scale) / 2);
        }
        float scale = Math.min(viewWidth * 0.31f / imageWidth, viewHeight * 0.70f / imageHeight);
        return new Placement(scale, viewWidth * 0.77f - imageWidth * scale / 2, viewHeight * 0.60f - imageHeight * scale / 2);
    }

    public record Placement(float scale, float x, float y) {
    }
}
