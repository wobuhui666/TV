package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.databinding.ViewWallBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.GlideWarmup;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.WallColorUtil;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.IOException;

import pl.droidsonroids.gif.GifDrawable;

public class CustomWallView extends FrameLayout implements DefaultLifecycleObserver {

    private static final int[] WALL_PAPERS = {0, R.drawable.wallpaper_1, R.drawable.wallpaper_2, R.drawable.wallpaper_3, R.drawable.wallpaper_4};
    private static final int TYPE_RES = 0;
    private static final int TYPE_GIF = 1;
    private static final int TYPE_VIDEO = 2;
    private ViewWallBinding binding;
    private GifDrawable drawable;
    private PlayerView video;
    private ExoPlayer player;

    public CustomWallView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isInEditMode()) return;
        binding = ViewWallBinding.inflate(LayoutInflater.from(getContext()), this, true);
        ((ComponentActivity) getContext()).getLifecycle().addObserver(this);
        refresh();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.WALL) refresh();
    }

    private void refresh() {
        stop();
        ImgUtil.clear(binding.image);
        load();
        theme();
    }

    private void stop() {
        if (player != null && player.isPlaying()) {
            player.stop();
            player.clearMediaItems();
        }
        if (video != null) {
            video.setPlayer(null);
            video.setVisibility(GONE);
        }
        if (drawable != null) {
            drawable.stop();
            drawable.recycle();
            drawable = null;
        }
    }

    private void load() {
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        if (isBuiltIn(wall, type)) loadWallpaper(WALL_PAPERS[wall], false);
        else if (type == TYPE_VIDEO) loadVideo(FileUtil.getWall(wall));
        else if (type == TYPE_GIF) loadGif(FileUtil.getWall(wall));
        else loadWallpaper(FileUtil.getWallCache(), true);
    }

    protected void theme() {
        int newColor = WallColorUtil.getColor(Setting.getWall(), Setting.getWallType(), FileUtil.getWallCache());
        int oldColor = Setting.getWallColor();
        if (newColor == oldColor) return;
        Setting.putWallColor(newColor);
        if (Setting.getThemeColor() == Setting.THEME_FOLLOW_WALLPAPER) RefreshEvent.theme();
    }

    private void loadVideo(File file) {
        ensurePlayer();
        ensureVideoView();
        video.setPlayer(player);
        video.setVisibility(VISIBLE);
        loadWallpaper(FileUtil.getWallCache(), false);
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        player.prepare();
    }

    private void loadGif(File file) {
        drawable = gif(file);
        if (drawable != null) binding.image.setImageDrawable(drawable);
        else loadWallpaper(FileUtil.getWallCache(), true);
    }

    private void loadWallpaper(Object model, boolean fallback) {
        if (GlideWarmup.defer(binding.image, () -> loadWallpaper(model, fallback), () -> {
            if (fallback) binding.image.setImageResource(R.drawable.wallpaper_1);
            else if (model instanceof Integer resource) binding.image.setImageResource(resource);
        })) return;
        RequestBuilder<Bitmap> request = wallpaper(model);
        if (model instanceof File) request = request.signature(new ObjectKey(WallConfig.get().snapshotVersion()));
        if (fallback) request = request.error(wallpaper(R.drawable.wallpaper_1));
        request.into(binding.image);
    }

    private RequestBuilder<Bitmap> wallpaper(Object model) {
        // One decode for concurrent full-screen views. ImageView applies centerCrop at draw time.
        return Glide.with(getContext().getApplicationContext()).asBitmap().load(model)
                .override(Target.SIZE_ORIGINAL).dontTransform().dontAnimate().diskCacheStrategy(DiskCacheStrategy.NONE);
    }

    private GifDrawable gif(File file) {
        try {
            return new GifDrawable(file);
        } catch (IOException e) {
            return null;
        }
    }

    private void ensurePlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(getContext()).build();
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.setPlayWhenReady(true);
        player.mute();
    }

    private void ensureVideoView() {
        if (video != null) return;
        video = (PlayerView) LayoutInflater.from(getContext()).inflate(R.layout.view_wall_video, this, false);
        addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private boolean hasVideo() {
        return player != null && video != null && video.getVisibility() == VISIBLE && player.getMediaItemCount() > 0;
    }

    private boolean isBuiltIn(int wall, int type) {
        return type == TYPE_RES && wall > 0 && wall < WALL_PAPERS.length;
    }

    @Override
    public void onCreate(@NonNull LifecycleOwner owner) {
        EventBus.getDefault().register(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        if (drawable != null) drawable.start();
        if (!hasVideo()) return;
        video.setPlayer(player);
        player.play();
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        if (drawable != null) drawable.pause();
        if (!hasVideo()) return;
        video.setPlayer(null);
        player.pause();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        EventBus.getDefault().unregister(this);
        if (drawable != null) drawable.recycle();
        if (binding != null) ImgUtil.clear(binding.image);
        if (video != null) removeView(video);
        if (player != null) player.release();
        drawable = null;
        binding = null;
        player = null;
        video = null;
    }
}
