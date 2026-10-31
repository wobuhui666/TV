package com.fongmi.android.tv.player.media;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class LocalSubtitleScannerTest {

    @Rule public final TemporaryFolder directory = new TemporaryFolder();

    @Test public void shouldSelectOnlyTheVideoSidecarsAndPutExactNamesBeforeLanguages() throws Exception {
        File video = directory.newFile("My Movie.mkv");
        directory.newFile("My Movie.zh-Hans.ass");
        directory.newFile("My Movie.en.srt");
        directory.newFile("My Movie.VTT");
        directory.newFile("my movie.fr.SRT");
        directory.newFile("My Movie 2.srt");
        directory.newFile("My Movies.srt");
        directory.newFile("Another Movie.srt");
        directory.newFile("My Movie.jpg");
        directory.newFolder("My Movie.it.srt");

        List<String> names = LocalSubtitleScanner.findFiles(video).stream().map(File::getName).toList();

        assertEquals("My Movie.VTT", names.get(0));
        assertEquals(List.of("My Movie.VTT", "My Movie.en.srt", "my movie.fr.SRT", "My Movie.zh-Hans.ass"), names);
    }

    @Test public void shouldNotFallBackToUnrelatedSubtitlesWhenTheVideoHasNoMatch() throws Exception {
        File video = directory.newFile("Episode 2.mp4");
        directory.newFile("Episode 1.srt");
        directory.newFile("Unrelated.vtt");

        assertTrue(LocalSubtitleScanner.findFiles(video).isEmpty());
    }

    @Test public void shouldFindAnExactSidecarEvenInADirectoryLargerThanTheEnumerationBudget() throws Exception {
        File video = directory.newFile("Large directory.mp4");
        for (int i = 0; i < LocalSubtitleScanner.MAX_DIRECTORY_ENTRIES + 32; i++) directory.newFile("unrelated-" + i + ".txt");
        File subtitle = directory.newFile("Large directory.srt");

        assertEquals(List.of(subtitle), LocalSubtitleScanner.findFiles(video));
    }

    @Test public void shouldBoundLanguageVariantsWithoutDroppingTheExactSubtitle() throws Exception {
        File video = directory.newFile("Episode.mp4");
        for (int i = 0; i < 80; i++) directory.newFile("Episode.lang-" + i + ".srt");
        File exact = directory.newFile("Episode.srt");

        List<File> found = LocalSubtitleScanner.findFiles(video);

        assertEquals(LocalSubtitleScanner.MAX_SUBTITLES, found.size());
        assertEquals(exact, found.get(0));
    }

    @Test public void shouldDiscardCancelledDiscoveryWithoutClearingTheInterrupt() throws Exception {
        File video = directory.newFile("Cancelled.mp4");
        directory.newFile("Cancelled.vtt");
        AtomicBoolean empty = new AtomicBoolean();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            empty.set(LocalSubtitleScanner.findFiles(video).isEmpty());
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        worker.join(2000);

        assertFalse(worker.isAlive());
        assertTrue(empty.get());
        assertTrue(stillInterrupted.get());
    }

    @Test public void shouldNotDiscoverSubtitlesForAMissingVideo() throws Exception {
        directory.newFile("Missing.srt");

        assertTrue(LocalSubtitleScanner.findFiles(new File(directory.getRoot(), "Missing.mp4")).isEmpty());
    }
}
