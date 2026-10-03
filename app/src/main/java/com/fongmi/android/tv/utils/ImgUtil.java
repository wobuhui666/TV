package com.fongmi.android.tv.utils;

import static android.widget.ImageView.ScaleType.CENTER_CROP;
import static android.widget.ImageView.ScaleType.FIT_CENTER;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.impl.CustomTarget;
import com.github.catvod.utils.Json;
import com.google.common.net.HttpHeaders;

import java.util.Map;

import jahirfiquitiva.libs.textdrawable.TextDrawable;

public class ImgUtil {

    private static final ImageRetryPolicy failed = new ImageRetryPolicy();

    private static final DrawableCrossFadeFactory CROSS_FADE = new DrawableCrossFadeFactory.Builder(260).setCrossFadeEnabled(true).build();

    public static void logo(ImageView view) {
        try {
            // Custom site logo: circle crop to match round home disc.
            // Default error mark is the square-ish brand vector — fitCenter (not circleCrop)
            // so leanback/mobile toolbars keep a balanced mark; view may still oval-mask.
            String logo = UrlUtil.convert(VodConfig.get().getConfig().getLogo());
            Glide.with(view)
                    .load(logo)
                    .circleCrop()
                    .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                    .error(Glide.with(view).load(R.drawable.ic_logo).fitCenter())
                    .into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String url, CustomTarget<Bitmap> target) {
        try {
            Glide.with(App.get()).asBitmap().load(getUrl(url)).override(ResUtil.dp2px(96), ResUtil.dp2px(96)).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(Context context, String url, CustomTarget<Drawable> target) {
        try {
            Glide.with(context).load(getUrl(url)).override(ResUtil.getScreenWidth(), ResUtil.getScreenHeight()).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String text, String url, ImageView view) {
        load(text, url, view, true, false, false, null);
    }

    public static void load(String text, String url, ImageView view, LoadCallback callback) {
        load(text, url, view, true, false, false, callback);
    }

    public static void loadBlurred(String text, String url, ImageView view, LoadCallback callback) {
        load(text, url, view, true, true, false, callback);
    }

    public static void loadForTransition(String text, String url, ImageView view, LoadCallback callback) {
        load(text, url, view, true, false, true, callback);
    }

    /** Preserve the source aspect ratio; the artwork view owns its final composition. */
    public static void loadArtwork(String text, String url, ImageView view, @Nullable LoadCallback callback) {
        load(text, url, view, true, false, false, true, callback);
    }

    public static void clear(ImageView view) {
        Glide.with(App.get()).clear(view);
    }

    public static void load(String text, String url, ImageView view, boolean vod) {
        load(text, url, view, vod, false, false, null);
    }

    private static void load(String text, String url, ImageView view, boolean vod, boolean blurred, boolean keepCurrentOnError, @Nullable LoadCallback callback) {
        load(text, url, view, vod, blurred, keepCurrentOnError, false, callback);
    }

    private static void load(String text, String url, ImageView view, boolean vod, boolean blurred, boolean keepCurrentOnError, boolean artwork, @Nullable LoadCallback callback) {
        if (!artwork) view.setScaleType(vod ? CENTER_CROP : FIT_CENTER);
        if (!vod) view.setVisibility(TextUtils.isEmpty(url) ? View.GONE : View.VISIBLE);
        try {
            if (TextUtils.isEmpty(url) || !failed.canLoad(url)) {
                // A recycled view may still have a request which would overwrite this placeholder.
                clear(view);
                view.setImageDrawable(getTextDrawable(text, vod));
                notifyLoad(callback, false);
                return;
            }
            RequestBuilder<Drawable> builder = Glide.with(view).load(getUrl(url)).transition(DrawableTransitionOptions.withCrossFade(CROSS_FADE)).listener(getListener(text, url, view, vod, keepCurrentOnError, callback));
            if (artwork) builder.dontTransform().dontAnimate().into(view);
            else if (blurred) builder.transform(new CenterCrop(), new GaussianBlurTransformation()).into(view);
            else if (vod) builder.centerCrop().into(view);
            else builder.fitCenter().into(view);
        } catch (Throwable e) {
            e.printStackTrace();
            notifyLoad(callback, false);
        }
    }

    public static Object getUrl(String url) {
        String param = null;
        url = UrlUtil.convert(url);
        if (url.startsWith("data:")) return url;
        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        if (url.contains("@Headers=")) addHeader(builder, param = url.split("@Headers=")[1].split("@")[0]);
        if (url.contains("@Cookie=")) builder.addHeader(HttpHeaders.COOKIE, param = url.split("@Cookie=")[1].split("@")[0]);
        if (url.contains("@Referer=")) builder.addHeader(HttpHeaders.REFERER, param = url.split("@Referer=")[1].split("@")[0]);
        if (url.contains("@User-Agent=")) builder.addHeader(HttpHeaders.USER_AGENT, param = url.split("@User-Agent=")[1].split("@")[0]);
        url = param == null ? url : url.split("@")[0];
        return TextUtils.isEmpty(url) ? null : new GlideUrl(url, builder.build());
    }

    private static void addHeader(LazyHeaders.Builder builder, String header) {
        Map<String, String> map = Json.toMap(Json.parse(header));
        for (Map.Entry<String, String> entry : map.entrySet()) builder.addHeader(UrlUtil.fixHeader(entry.getKey()), entry.getValue());
    }

    private static Drawable getTextDrawable(String text, boolean vod) {
        TextDrawable.Builder builder = new TextDrawable.Builder();
        text = TextUtils.isEmpty(text) ? "！" : text.substring(0, 1);
        if (vod) builder.buildRect(text, ColorGenerator.get400(text));
        return builder.buildRoundRect(text, ColorGenerator.get400(text), ResUtil.dp2px(4));
    }

    private static RequestListener<Drawable> getListener(String text, String url, ImageView view, boolean vod, boolean keepCurrentOnError, @Nullable LoadCallback callback) {
        return new RequestListener<>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                if (!keepCurrentOnError) view.setImageDrawable(getTextDrawable(text, vod));
                failed.onFailure(url);
                notifyLoad(callback, false);
                return true;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                failed.onSuccess(url);
                notifyLoad(callback, true);
                return false;
            }
        };
    }

    private static void notifyLoad(@Nullable LoadCallback callback, boolean success) {
        if (callback != null) callback.onComplete(success);
    }

    public interface LoadCallback {

        void onComplete(boolean success);
    }
}
