package com.fongmi.android.tv.bean;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.impl.Diffable;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Collect implements Parcelable, Diffable<Collect> {

    private boolean selected;
    private List<Vod> list;
    private Site site;
    private int page;
    private String state;

    public Collect(Site site, List<Vod> list) {
        this.site = site;
        this.list = list;
    }

    protected Collect(Parcel in) {
        this.selected = in.readByte() != 0;
        this.list = in.createTypedArrayList(Vod.CREATOR);
        this.site = in.readParcelable(Site.class.getClassLoader());
        this.page = in.readInt();
        this.state = in.readString();
    }

    public static Collect all() {
        Collect item = new Collect(Site.get("all", ResUtil.getString(R.string.all)), new ArrayList<>());
        item.setSelected(true);
        return item;
    }

    public static Collect create(List<Vod> list) {
        return new Collect(list.get(0).getSite(), list);
    }

    public Site getSite() {
        return site == null ? new Site() : site;
    }

    public List<Vod> getList() {
        return list == null ? new ArrayList<>() : list;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public int getPage() {
        return Math.max(1, page);
    }

    public String getDisplayName() {
        String name = getSite().getName();
        if ("LOADING".equals(state)) return name + " · 加载中";
        if ("EMPTY".equals(state)) return name + " · 无结果";
        if ("FAILURE".equals(state)) return name + " · 失败";
        return name;
    }

    public Collect state(String state) {
        this.state = state;
        return this;
    }

    public void setPage(int page) {
        this.page = page;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Collect it)) return false;
        return Objects.equals(getSite(), it.getSite());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getSite());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte(this.selected ? (byte) 1 : (byte) 0);
        dest.writeTypedList(this.list);
        dest.writeParcelable(this.site, flags);
        dest.writeInt(this.page);
        dest.writeString(this.state);
    }

    @Override
    public boolean isSameItem(Collect other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(Collect other) {
        return other != null
                && isSelected() == other.isSelected()
                && getPage() == other.getPage()
                && getDisplayName().equals(other.getDisplayName())
                && getList().equals(other.getList());
    }

    public static final Creator<Collect> CREATOR = new Creator<>() {
        @Override
        public Collect createFromParcel(Parcel source) {
            return new Collect(source);
        }

        @Override
        public Collect[] newArray(int size) {
            return new Collect[size];
        }
    };
}
