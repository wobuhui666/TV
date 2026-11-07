package com.fongmi.android.tv.bean;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.impl.Diffable;
import com.fongmi.android.tv.source.EpisodeLabel;
import com.fongmi.android.tv.source.EpisodeLabelParser;
import com.fongmi.android.tv.source.EpisodeTarget;
import com.github.catvod.utils.Trans;
import com.google.gson.annotations.SerializedName;

import java.util.Objects;
import java.util.Locale;

public class Episode implements Parcelable, Diffable<Episode> {

    @SerializedName("name")
    private String name;
    @SerializedName("desc")
    private String desc;
    @SerializedName("url")
    private String url;

    private int index;
    private int number;
    private boolean selected;
    private boolean generatedLabel;
    private transient EpisodeLabel label;

    private Episode(String name, String desc, String url) {
        this.name = name;
        this.desc = desc;
        this.url = url;
        this.number = getNumber();
    }

    public Episode() {
    }

    protected Episode(Parcel in) {
        this.name = in.readString();
        this.desc = in.readString();
        this.url = in.readString();
        this.number = in.readInt();
        this.selected = in.readByte() != 0;
        this.generatedLabel = in.readByte() != 0;
    }

    public static Episode create(String name, String url) {
        return new Episode(name, "", url).trans();
    }

    public static Episode create(String name, String desc, String url) {
        return new Episode(name, desc, url).trans();
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
        this.label = null;
        this.number = getNumber();
    }

    public String getDesc() {
        return TextUtils.isEmpty(desc) ? "" : desc;
    }

    public String getUrl() {
        return TextUtils.isEmpty(url) ? "" : url;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public int getNumber() {
        EpisodeLabel parsed = getLabel();
        number = !generatedLabel && parsed.isSingleEpisode() && !parsed.isPreview() ? parsed.number() : -1;
        return number;
    }

    public EpisodeLabel getLabel() {
        if (label == null) label = EpisodeLabelParser.parse(getName());
        return label;
    }

    public boolean isGeneratedLabel() {
        return generatedLabel;
    }

    public void setGeneratedLabel(boolean generatedLabel) {
        this.generatedLabel = generatedLabel;
        this.number = getNumber();
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public void deselect() {
        setSelected(false);
    }

    public int getScore(String name, int number) {
        if (getName().equalsIgnoreCase(name) && !getLabel().isAmbiguous()) return 100;
        EpisodeTarget target = new EpisodeTarget(null, name);
        if (target.matches(this)) return 80;
        if (target.isUnnumbered() && !getLabel().isAmbiguous() && getLabel().number() == null
                && !getLabel().isVariety() && getLabel().part().isEmpty()) {
            if (name.length() >= 2 && getName().toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) return 70;
            if (getName().length() >= 2 && name.toLowerCase(Locale.ROOT).contains(getName().toLowerCase(Locale.ROOT))) return 60;
        }
        return 0;
    }

    public boolean matchesName(Episode other) {
        if (other == null) return false;
        return getName().equalsIgnoreCase(other.getName());
    }

    public Episode trans() {
        if (Trans.pass()) return this;
        this.name = Trans.s2t(name);
        this.desc = Trans.s2t(desc);
        this.label = null;
        return this;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Episode it)) return false;
        return Objects.equals(getName(), it.getName()) && Objects.equals(getUrl(), it.getUrl());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), getUrl());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.name);
        dest.writeString(this.desc);
        dest.writeString(this.url);
        dest.writeInt(getNumber());
        dest.writeByte(this.selected ? (byte) 1 : (byte) 0);
        dest.writeByte(this.generatedLabel ? (byte) 1 : (byte) 0);
    }

    @Override
    public boolean isSameItem(Episode other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(Episode other) {
        return getUrl().equals(other.getUrl()) && getDesc().equals(other.getDesc());
    }

    public record Rule(Episode episode, int score) {

        public boolean find() {
            return score > 0;
        }
    }

    public static final Creator<Episode> CREATOR = new Creator<>() {
        @Override
        public Episode createFromParcel(Parcel source) {
            return new Episode(source);
        }

        @Override
        public Episode[] newArray(int size) {
            return new Episode[size];
        }
    };
}
