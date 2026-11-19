package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class MovieSelectionTest {
    @Test public void shouldIgnorePreviewsAndPreferTheSavedLine() {
        Flag first = flag("first", Episode.create("预告片", "preview"), Episode.create("正片", "first-film"));
        Flag preferred = flag("preferred", Episode.create("正片", "second-film"));
        assertSame(preferred, SmartSourceSelector.selectMovie(vod(first, preferred), "", "preferred").flag());
    }

    @Test public void shouldLeaveMultipleCutsWithinALineForManualSelection() {
        Vod source = vod(flag("line", Episode.create("影院版", "theatrical"), Episode.create("导演剪辑版", "director")));
        assertNull(SmartSourceSelector.selectMovie(source, "", "line"));
    }

    @Test public void shouldLeaveDifferentCutsAcrossLinesForManualSelection() {
        Vod source = vod(flag("first", Episode.create("正片", "theatrical")), flag("second", Episode.create("Extended Cut", "extended")));
        assertNull(SmartSourceSelector.selectMovie(source, "", "first"));
    }

    @Test public void shouldResumeTheExactSavedCutAndLineEvenWithAlternatives() {
        Flag first = flag("first", Episode.create("导演剪辑版", "shared"));
        Episode selected = Episode.create("导演剪辑版", "shared");
        Flag preferred = flag("preferred", Episode.create("影院版", "theatrical"), selected);
        var choice = SmartSourceSelector.selectMovie(vod(first, preferred), "shared", "preferred");
        assertSame(preferred, choice.flag());
        assertSame(selected, choice.episode());
    }

    @Test public void shouldRejectPreviewOnlyOrEmptyLines() {
        assertNull(SmartSourceSelector.selectMovie(vod(flag("line", Episode.create("Trailer", "preview"))), "preview", "line"));
        assertNull(SmartSourceSelector.selectMovie(vod(flag("empty")), "", ""));
    }

    @Test public void shouldTreatDuplicatedUrlsAsOneFilm() {
        Episode film = Episode.create("正片", "film");
        assertSame(film, SmartSourceSelector.selectMovie(vod(flag("line", film, Episode.create("1080P", "film"))), "", "").episode());
    }

    private Flag flag(String name, Episode... episodes) {
        Flag flag = new Flag(name); flag.getEpisodes().addAll(List.of(episodes)); return flag;
    }
    private Vod vod(Flag... flags) {
        Vod source = new Vod(); source.setFlags(List.of(flags)); return source;
    }
}
