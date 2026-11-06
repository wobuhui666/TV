package com.fongmi.android.tv.bean;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class VodContentRatingParcelTest {

    @Test
    public void shouldPreserveCertificationAndFollowingFieldsAcrossParcel() {
        Vod vod = new Vod();
        vod.setId("title-id");
        vod.setContent("description");
        vod.setContentRating(" PG-13 ");
        vod.setBackdrop("https://example.com/backdrop.jpg");
        vod.setGenreText("Drama");
        vod.setPlayFrom("source");
        vod.setPlayUrl("Episode$https://example.com/video.mp4");
        vod.setPlayId("episode-04?token=a&x=1");
        Vod candidate = new Vod();
        candidate.setId("alternate");
        candidate.setContentRating("TV-14");
        candidate.setPlayId("alternate-episode");
        vod.setSourceCandidates(Collections.singletonList(candidate));

        Vod restored = roundTrip(vod);

        assertEquals("title-id", restored.getId());
        assertEquals("description", restored.getContent());
        assertEquals("PG-13", restored.getContentRating());
        assertEquals("https://example.com/backdrop.jpg", restored.getBackdrop());
        assertEquals("Drama", restored.getGenreText());
        assertEquals("source", restored.getPlayFrom());
        assertEquals("Episode$https://example.com/video.mp4", restored.getPlayUrl());
        assertEquals("episode-04?token=a&x=1", restored.getPlayId());
        assertEquals(1, restored.getSourceCandidates().size());
        assertEquals("alternate", restored.getSourceCandidates().get(0).getId());
        assertEquals("TV-14", restored.getSourceCandidates().get(0).getContentRating());
        assertEquals("alternate-episode", restored.getSourceCandidates().get(0).getPlayId());
    }

    @Test
    public void shouldPreserveAbsentCertificationAcrossParcel() {
        assertEquals("", roundTrip(new Vod()).getContentRating());
        assertEquals("", roundTrip(new Vod()).getPlayId());
    }

    private Vod roundTrip(Vod vod) {
        Parcel parcel = Parcel.obtain();
        try {
            vod.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            Vod restored = Vod.CREATOR.createFromParcel(parcel);
            assertEquals("Parcel layout must be consumed symmetrically", parcel.dataSize(), parcel.dataPosition());
            return restored;
        } finally {
            parcel.recycle();
        }
    }
}
