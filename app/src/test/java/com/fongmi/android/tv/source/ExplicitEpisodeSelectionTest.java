package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ExplicitEpisodeSelectionTest {

    @Test
    public void shouldMatchAnIdOnAnyLineBeforeMatchingThePreferredLinesName() {
        Flag preferred = flag("preferred", Episode.create("episode-4", "different-id"));
        Episode selected = Episode.create("第4集", "episode-4");
        Flag other = flag("other", selected);

        assertSame(selected, select(vod(preferred, other), "episode-4", "preferred").episode());
    }

    @Test
    public void shouldPreferTheHistoryLineWhenAnExactIdIsShared() {
        Flag first = flag("first", Episode.create("第4集", "shared"));
        Flag preferred = flag("preferred", Episode.create("EP04", "shared"));

        assertSame(preferred, select(vod(first, preferred), "shared", "preferred").flag());
    }

    @Test
    public void shouldTreatEpisodeNumbersAsCoordinatesRatherThanPositions() {
        Episode selected = Episode.create("第4集", "fourth");
        Vod source = vod(flag("line", Episode.create("第1集", "first"), selected));
        for (String value : List.of("4", "EP04", "第04集", "第四集")) {
            assertSame(value, selected, select(source, value, "line").episode());
        }
    }

    @Test
    public void shouldRejectEqualMatchesOnDifferentLinesRegardlessOfTheHistoryLine() {
        Vod source = vod(flag("preferred", Episode.create("EP04", "release-a")),
                flag("other", Episode.create("第4集", "release-b")));

        assertNull(select(source, "4", "preferred"));
    }

    @Test
    public void shouldKeepAmbiguityWithinOneLineWhenAnotherLineAlsoMatches() {
        Vod source = vod(flag("mixed", Episode.create("EP04", "release-a"), Episode.create("EP04", "release-b")),
                flag("other", Episode.create("第4集", "release-c")));

        assertNull(select(source, "4", "other"));
    }

    @Test
    public void shouldSelectAnExactReleaseNameBeforeEqualNumberedAlternatives() {
        Episode selected = Episode.create("[A] Show - 04 [1080p].mkv", "release-a");
        Vod source = vod(flag("other", Episode.create("[B] Show - 04 [1080p].mkv", "release-b")), flag("selected", selected));

        assertSame(selected, select(source, selected.getName(), "other").episode());
        assertNull(select(source, "4", "other"));
    }

    @Test
    public void shouldKeepSeasonsPartsAndPreviewsSeparate() {
        Episode selected = Episode.create("S02E04", "season-two");
        Vod source = vod(flag("line", Episode.create("S01E04", "season-one"), selected,
                Episode.create("第4集上", "upper"), Episode.create("第4集预告", "preview")));

        assertSame(selected, select(source, "S02E04", "line").episode());
        assertNull(select(vod(flag("line", Episode.create("第4集上", "upper"),
                Episode.create("第4集预告", "preview"), Episode.create("第4-5集", "merged"))), "4", "line"));
    }

    @Test
    public void shouldNotUseGeneratedNumbersOrUnrelatedNumbers() {
        Episode generated = Episode.create("04", "fourth-file");
        generated.setGeneratedLabel(true);
        Vod source = vod(flag("line", generated, Episode.create("Movie 2026 [1080p]", "movie")));

        for (String value : List.of("4", "2026", "1080")) assertNull(select(source, value, "line"));
        assertSame(generated, select(source, "fourth-file", "line").episode());
    }

    @Test
    public void shouldRejectMissingSelectionsEvenWithOneEpisodeOrASavedPosition() {
        Flag line = flag("line", Episode.create("第1集", "first"));
        line.setPosition(0);

        assertNull(select(vod(line), "4", "line"));
        assertNull(select(vod(line), "missing-id", "line"));
        assertNull(select(vod(line), "", "line"));
    }

    private SmartSourceSelector.FlagSelection select(Vod source, String value, String preferred) {
        return SmartSourceSelector.selectExplicitEpisode(source, value, preferred);
    }

    private Flag flag(String name, Episode... episodes) {
        Flag flag = new Flag(name);
        flag.getEpisodes().addAll(List.of(episodes));
        return flag;
    }

    private Vod vod(Flag... flags) {
        Vod vod = new Vod();
        vod.setName("Show");
        vod.setFlags(List.of(flags));
        return vod;
    }
}
