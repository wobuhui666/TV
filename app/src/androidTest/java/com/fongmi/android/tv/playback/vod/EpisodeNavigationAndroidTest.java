package com.fongmi.android.tv.playback.vod;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.player.skip.CommunitySkipRuntime;
import com.fongmi.android.tv.source.EpisodeTarget;
import com.fongmi.android.tv.source.SmartSourceSelector;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Exercises real controller requests and Android Parcel without a source or metadata network. */
@RunWith(AndroidJUnit4.class)
public class EpisodeNavigationAndroidTest {

    private static final Map<String, Object> SETTINGS = Map.of(
            "incognito", true,
            "community_skip_enabled", false,
            "preload_next", false,
            "source_selection_mode", "SMART",
            "source_selection_cross_site", false);

    private final List<VodPlayRequest> requests = new ArrayList<>();
    private final Map<String, Object> saved = new HashMap<>();
    private VodPlaybackController controller;
    private VodPlaybackState state;
    private History history;
    private Flag flag;
    private int noNext;
    private int noPrevious;
    private boolean allowDetailRequest;
    private int detailRequests;

    @Before
    public void setup() {
        String applicationId = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        assertTrue("Use a disposable validation application", applicationId.equals("com.fongmi.android.tv.preview")
                || applicationId.equals("com.fongmi.android.tv.sourceprobe"));
        Map<String, ?> preferences = Prefers.getPrefers().getAll();
        for (String key : SETTINGS.keySet()) if (preferences.containsKey(key)) saved.put(key, preferences.get(key));
        SETTINGS.forEach(Prefers::put);
    }

    @After
    public void cleanup() {
        if (controller != null) controller.reset();
        for (String key : SETTINGS.keySet()) {
            if (saved.containsKey(key)) Prefers.put(key, saved.get(key));
            else Prefers.getPrefers().edit().remove(key).apply();
        }
    }

    @Test
    public void sourceLabelsShouldIgnoreFileSizeAndKeepCanonicalSeasonEpisode() {
        Episode anime = Episode.create(ani(4), "fixture-anime-4");
        Episode alternate = Episode.create(toaru(1), "fixture-alternate-1");
        assertEquals(4, anime.getNumber());
        assertEquals(1, alternate.getNumber());
        assertEquals(Integer.valueOf(1), alternate.getLabel().season());
        assertFalse(anime.getLabel().edition().equals(alternate.getLabel().edition()));
    }

    @Test
    public void nextAndPreviousShouldStayWithTheSelectedInterleavedRelease() {
        initialize(0, ani(1), toaru(1), ani(2), toaru(2));
        Episode next = flag.getEpisodes().get(2);
        controller.nextEpisode(true);
        assertRequested(next);
        controller.prevEpisode(true);
        assertRequested(flag.getEpisodes().get(0));
        controller.prevEpisode(true);
        assertEquals(2, requests.size());
        assertEquals(1, noPrevious);
    }

    @Test
    public void endingShouldStopAtReleaseBoundaryAndManualSelectionShouldStillWork() {
        initialize(1, ani(1), ani(2), toaru(1), toaru(2));
        Episode last = state.getEpisode();
        controller.playbackEnded();
        assertSame(last, state.getEpisode());
        assertTrue(requests.isEmpty());
        assertEquals(1, noNext);
        controller.selectEpisode(flag.getEpisodes().get(2));
        assertRequested(flag.getEpisodes().get(2));
        controller.nextEpisode(true);
        assertRequested(flag.getEpisodes().get(3));
    }

    @Test
    public void distinctReleasesWithoutOverlappingEpisodesShouldStillHaveBoundaries() {
        initialize(1, ani(1), ani(2), toaru(3), toaru(4));
        Episode last = state.getEpisode();
        controller.playbackEnded();
        assertSame(last, state.getEpisode());
        assertTrue(requests.isEmpty());
        assertEquals(1, noNext);
    }

    @Test
    public void mixedReleaseNavigationShouldIgnorePerFileRevisionDifferences() {
        initialize(0, "[ANi] Show - 01v2 [1080p].mkv", "[ANi] Show - 01v1 [1080p].mkv",
                toaru(1), "[ANi] Show - 02v1 [1080p].mkv");
        controller.nextEpisode(true);
        assertRequested(flag.getEpisodes().get(3));
    }

    @Test
    public void automaticSourceFallbackShouldPreserveThePendingNextSeasonAndRelease() {
        initialize(0, "[ANi] Show S02E03 [1080p].mkv");
        EpisodeTarget pending = EpisodeTarget.of(state.getEpisode()).offset(1);
        state.setEpisodeTarget(pending);
        allowDetailRequest = true;
        Vod alternative = new Vod();
        alternative.setName("Show 第二季");
        controller.fallbackSource(alternative);

        assertSame(pending, state.getEpisodeTarget());
        assertEquals(Integer.valueOf(4), state.getEpisodeTarget().getNumber());
        assertEquals(1, detailRequests);
        assertTrue(requests.isEmpty());
        Episode matching = Episode.create("[ANi] Show S02E04 [1080p].mkv", "matching-next");
        assertTrue(state.getEpisodeTarget().matches(matching));
        assertFalse(state.getEpisodeTarget().matches(Episode.create("[ANi] Show S01E04 [1080p].mkv", "wrong-season")));
        Flag candidates = new Flag("fallback");
        candidates.getEpisodes().add(Episode.create("[Other] Show S02E04 [1080p].mkv", "other-release"));
        candidates.getEpisodes().add(matching);
        assertSame(matching, SmartSourceSelector.findEpisode(candidates, state.getEpisodeTarget()));
    }

    @Test
    public void missingEpisodeShouldNotUseAnotherReleaseOrSkipAhead() {
        initialize(0, ani(1), ani(3), toaru(1), toaru(2));
        Episode current = state.getEpisode();
        controller.nextEpisode(true);
        assertSame(current, state.getEpisode());
        assertTrue(requests.isEmpty());
        assertEquals(1, noNext);
    }

    @Test
    public void reversingDisplayOrderShouldKeepCurrentObjectAndRelease() {
        initialize(2, ani(1), toaru(1), ani(2), toaru(2));
        Episode current = state.getEpisode();
        Episode previous = flag.getEpisodes().get(0);
        controller.reverseEpisode(true);
        assertSame(current, state.getEpisode());
        controller.nextEpisode(true);
        assertRequested(previous);
    }

    @Test
    public void reversedPlaybackAndPreloadSelectionShouldUseTheSameTarget() throws Exception {
        initialize(2, ani(1), toaru(1), ani(2), toaru(2));
        history.setRevPlay(true);
        Episode preloadTarget = relative(-1);
        assertSame(flag.getEpisodes().get(0), preloadTarget);
        controller.nextEpisode(true);
        assertRequested(preloadTarget);
    }

    @Test
    public void preloadSelectorAndActualNextRequestShouldAgree() throws Exception {
        initialize(0, ani(1), toaru(1), ani(2), toaru(2));
        // preloadNext() uses this production selector before requesting speculative source data.
        Episode preloadTarget = relative(1);
        assertSame(flag.getEpisodes().get(2), preloadTarget);
        controller.nextEpisode(false);
        assertRequested(preloadTarget);
    }

    @Test
    public void mergingMoreEpisodesShouldKeepCurrentUrlAndExposeTheSameReleaseNext() {
        initialize(0, ani(1), toaru(1));
        Episode current = state.getEpisode();
        controller.nextEpisode(false);
        assertTrue(requests.isEmpty());
        Flag additional = new Flag("fixture");
        additional.getEpisodes().add(Episode.create(toaru(2), "appended-other-2"));
        Episode next = Episode.create(ani(2), "appended-anime-2");
        additional.getEpisodes().add(next);
        controller.mergeFlags(List.of(additional));
        assertSame(current, state.getEpisode());
        assertEquals(current.getUrl(), history.getEpisodeUrl());
        controller.nextEpisode(false);
        assertRequested(next);
    }

    @Test
    public void upperAndLowerPartsShouldRemainSeparateDuringPlayback() {
        initialize(0, "[ANi] Show 第1集上.mkv", "[Other] Show 第1集上.mkv",
                "[ANi] Show 第1集下.mkv", "[ANi] Show 第2集上.mkv");
        controller.nextEpisode(true);
        assertRequested(flag.getEpisodes().get(2));
        assertEquals("lower", state.getEpisode().getLabel().part());
        controller.prevEpisode(true);
        assertRequested(flag.getEpisodes().get(0));
        assertEquals("upper", state.getEpisode().getLabel().part());
    }

    @Test
    public void parcelShouldPreserveSeasonEpisodeAndGeneratedLabelTrust() {
        Episode real = Episode.create("S02E03", "fixture-real");
        real.setSelected(true);
        Episode generated = Episode.create("01", "fixture-generated");
        generated.setGeneratedLabel(true);
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeTypedList(List.of(real, generated));
            parcel.setDataPosition(0);
            List<Episode> copied = parcel.createTypedArrayList(Episode.CREATOR);
            assertEquals(2, copied.size());
            assertEquals("fixture-real", copied.get(0).getUrl());
            assertEquals(3, copied.get(0).getNumber());
            assertEquals(Integer.valueOf(2), copied.get(0).getLabel().season());
            assertTrue(copied.get(0).isSelected());
            assertFalse(copied.get(0).isGeneratedLabel());
            assertTrue(copied.get(1).isGeneratedLabel());
            assertEquals(-1, copied.get(1).getNumber());
            assertNull(EpisodeTarget.of(copied.get(1)).getNumber());
            assertNull(EpisodeTarget.of(copied.get(1)).offset(1));
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void generatedSourceLabelsShouldNeverStartAutomaticIdentityRequests() throws Exception {
        Flag generated = new Flag("generated");
        generated.setEpisodes("memory://one#memory://two");
        Episode first = generated.getEpisodes().get(0);
        assertTrue(first.isGeneratedLabel());
        assertEquals(-1, first.getNumber());
        assertNull(EpisodeTarget.of(first).getNumber());
        assertFalse(EpisodeTarget.of(first).matches(Episode.create("01", "other-source-one")));
        Prefers.put("community_skip_enabled", true);
        CommunitySkipRuntime runtime = new CommunitySkipRuntime();
        try {
            runtime.start(new Vod(), new History(), first);
            Field active = CommunitySkipRuntime.class.getDeclaredField("active");
            active.setAccessible(true);
            assertNull("No session or metadata work for a positional label", active.get(runtime));
        } finally {
            runtime.stop();
            Prefers.put("community_skip_enabled", false);
        }
    }

    @Test
    public void parcelShouldReparseVarietyDateIssuePartAndVariantEvidence() {
        Episode member = Episode.create("第二季 [20260301] 第三期 上 会员版", "fixture-member");
        Episode dated = Episode.create("2026-03-01", "fixture-date-only");
        member.setSelected(true);
        assertEquals("2026-03-01", member.getLabel().airDate());
        assertTrue(member.getLabel().isIssue());
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeTypedList(List.of(member, dated));
            parcel.setDataPosition(0);
            List<Episode> copied = parcel.createTypedArrayList(Episode.CREATOR);
            Episode restored = copied.get(0);
            assertEquals("fixture-member", restored.getUrl());
            assertTrue(restored.isSelected());
            assertEquals(Integer.valueOf(2), restored.getLabel().season());
            assertEquals(3, restored.getNumber());
            assertTrue(restored.getLabel().isIssue());
            assertEquals("2026-03-01", restored.getLabel().airDate());
            assertEquals("upper", restored.getLabel().part());
            assertEquals("member", restored.getLabel().variant());
            assertTrue(EpisodeTarget.of(restored).matches(Episode.create(
                    "第二季 2026年3月1日 第3期 上 會員加長版", "other-member")));
            assertFalse(restored.getLabel().isApiSafe());
            assertEquals(-1, copied.get(1).getNumber());
            assertNull(EpisodeTarget.of(copied.get(1)).getNumber());
            assertNull(EpisodeTarget.of(copied.get(1)).offset(1));
            assertFalse(EpisodeTarget.of(copied.get(1)).matches(Episode.create("第1期", "undated")));
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void datedPlaybackAndPreloadShouldFollowPartsThenTheNextActualDate() throws Exception {
        initialize(0, "20260301 第3期 上", "20260301 第3期 上 加更", "20260301 第3期 下",
                "20260301 第3期 下 预告", "20260322 第9期 上", "20260322 第9期 下");
        Episode lower = flag.getEpisodes().get(2);
        assertSame(lower, relative(1));
        controller.playbackEnded();
        assertRequested(lower);
        Episode nextDate = flag.getEpisodes().get(4);
        assertSame(nextDate, relative(1));
        controller.nextEpisode(false);
        assertRequested(nextDate);
        assertEquals("2026-03-22", state.getEpisode().getLabel().airDate());
        assertEquals(2, requests.size());
    }

    @Test
    public void numberedVarietyPlaybackShouldSkipMemberAndPureTracksBetweenParts() throws Exception {
        initialize(0, "第1期 上", "第1期 上 会员版", "第1期 下", "第1期 下 纯享",
                "第2期 上", "第2期 下");
        Episode lower = flag.getEpisodes().get(2);
        assertSame(lower, relative(1));
        controller.nextEpisode(true);
        assertRequested(lower);
        Episode nextIssue = flag.getEpisodes().get(4);
        assertSame(nextIssue, relative(1));
        controller.nextEpisode(true);
        assertRequested(nextIssue);
        controller.prevEpisode(true);
        assertRequested(lower);
        assertEquals(3, requests.size());
    }

    @Test
    public void datedControllerNavigationShouldHonorDescendingAndReversedSourceOrder() throws Exception {
        initialize(0, "20260322", "20260322 加更", "20260308", "20260301");
        Episode newest = flag.getEpisodes().get(0);
        Episode middle = flag.getEpisodes().get(2);
        assertSame(middle, relative(1));
        controller.nextEpisode(true);
        assertRequested(middle);
        controller.reverseEpisode(true);
        assertSame(middle, state.getEpisode());
        assertSame(newest, relative(1));
        controller.nextEpisode(true);
        assertRequested(newest);
    }

    @Test
    public void missingDatedNextShouldStopUntilTheUserChoosesAnUndatedIssue() throws Exception {
        initialize(0, "20260301 第3期", "第4期", "20260301 第3期 加更");
        Episode selected = state.getEpisode();
        assertSame(selected, relative(1));
        controller.playbackEnded();
        assertSame(selected, state.getEpisode());
        assertEquals(selected.getUrl(), history.getEpisodeUrl());
        assertTrue(requests.isEmpty());
        assertEquals(1, noNext);
        controller.selectEpisode(flag.getEpisodes().get(1));
        assertRequested(flag.getEpisodes().get(1));
    }

    @Test
    public void duplicateDatedNextShouldRequireManualSelectionBeforeContinuing() throws Exception {
        initialize(0, "20260301", "20260308", "2026-03-08", "20260315");
        Episode selected = state.getEpisode();
        assertSame(selected, relative(1));
        controller.playbackEnded();
        assertSame(selected, state.getEpisode());
        assertTrue(requests.isEmpty());
        assertEquals(1, noNext);
        controller.selectEpisode(flag.getEpisodes().get(2));
        assertRequested(flag.getEpisodes().get(2));
        controller.nextEpisode(true);
        assertRequested(flag.getEpisodes().get(3));
        assertEquals(2, requests.size());
    }

    @Test
    public void datedFlagFindShouldRejectSingletonAndSavedPositionFallbacks() {
        initialize(1, "20260308 第3期", "20260315 第4期");
        String requested = "20260301 第3期";
        Flag singleton = new Flag("singleton");
        singleton.getEpisodes().add(Episode.create("20260308 第3期", "wrong-date"));
        assertNull(singleton.find(requested, true));
        assertNull(singleton.find(requested, false));
        assertNull(flag.find(requested, true));
        assertNull(flag.find(requested, false));
        Episode sameDate = Episode.create("2026年3月1日 第三期", "same-date");
        flag.getEpisodes().add(sameDate);
        assertSame(sameDate, flag.find(requested, true));
        assertSame(sameDate, flag.find(requested, false));
        assertTrue(requests.isEmpty());
    }

    @Test
    public void seamlessFlagsShouldRejectTheSameIssueOnAnotherDateAndAcceptDateAliases() {
        initialize(0, "20260301 第3期", "20260301 第3期 加更");
        Episode selected = state.getEpisode();
        state.setEpisodeTarget(EpisodeTarget.of(selected));
        Flag wrong = new Flag("wrong-date");
        wrong.getEpisodes().add(Episode.create("20260308 第3期", "wrong-date-issue-three"));
        Flag aliases = new Flag("same-date");
        aliases.getEpisodes().add(Episode.create("2026年3月1日 第3期 加更", "bonus-same-date"));
        Episode sameDate = Episode.create("2026.3.1 第三期 完整版", "regular-same-date");
        aliases.getEpisodes().add(sameDate);
        state.getFlags().addAll(List.of(wrong, aliases));
        controller.selectFlag(wrong);
        assertTrue(requests.isEmpty());
        assertEquals(selected.getUrl(), history.getEpisodeUrl());
        controller.selectFlag(aliases);
        assertRequested(sameDate);
        assertEquals(1, requests.size());
    }

    private void initialize(int current, String... labels) {
        flag = new Flag("fixture");
        for (int index = 0; index < labels.length; index++) {
            flag.getEpisodes().add(Episode.create(labels[index], "fixture-url-" + index));
        }
        flag.toggle(true, flag.getEpisodes().get(current));
        history = new History();
        history.setKey("episode-navigation-fixture");
        history.setVodName("Episode navigation fixture");
        history.setVodFlag(flag.getFlag());
        history.setVodRemarks(flag.getEpisodes().get(current).getName());
        history.setEpisodeUrl(flag.getEpisodes().get(current).getUrl());
        state = new VodPlaybackState();
        state.setFlags(new ArrayList<>(List.of(flag)));
        state.setHistory(history);
        VodPlaybackHost host = (VodPlaybackHost) Proxy.newProxyInstance(VodPlaybackHost.class.getClassLoader(),
                new Class<?>[]{VodPlaybackHost.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getVodPlayId")) return "";
                    if (method.getName().equals("requestPlayer")) requests.add((VodPlayRequest) args[0]);
                    else if (method.getName().equals("showNoNext")) noNext++;
                    else if (method.getName().equals("showNoPrev")) noPrevious++;
                    else if (method.getName().equals("requestDetail") && allowDetailRequest) detailRequests++;
                    else if (method.getName().equals("requestSearch") || method.getName().equals("requestDetail")) {
                        throw new AssertionError("Fixture navigation must not request a source or metadata service");
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == String.class) return "episode-navigation-fixture";
                    if (method.getReturnType() == List.class) return List.of();
                    return null;
                });
        controller = new VodPlaybackController(host, state);
    }

    private Episode relative(int offset) throws Exception {
        Method method = VodPlaybackController.class.getDeclaredMethod("getRelativeEpisode", int.class);
        method.setAccessible(true);
        return (Episode) method.invoke(controller, offset);
    }

    private void assertRequested(Episode episode) {
        assertSame(episode, state.getEpisode());
        assertFalse(requests.isEmpty());
        assertEquals(episode.getUrl(), requests.get(requests.size() - 1).getId());
        assertEquals(episode.getUrl(), history.getEpisodeUrl());
    }

    private String ani(int number) {
        return String.format("[494.7MB][ANi] 暗部共生少女 - %02d [1080P][Baha][WEB-DL][AAC AVC].mp4", number);
    }

    private String toaru(int number) {
        return String.format("[1.4GB]Toaru.Anbu.no.Item.S01E%02d.1080p.WEB-DL.AAC.H264.mkv", number);
    }
}
