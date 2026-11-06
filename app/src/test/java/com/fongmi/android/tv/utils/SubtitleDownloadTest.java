package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.bean.Sub;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SubtitleDownloadTest {

    private static final String SRT = "1\n00:00:01,000 --> 00:00:02,000\n字幕测试\n";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void shouldKeepDisplayNameAndSaveRawSubtitleUnderASafeLocalName() throws Exception {
        File directory = temporary.newFolder();
        List<Sub> result = SubtitleDownload.save(stream(SRT), "字幕#1.简体.srt", directory);

        assertEquals(1, result.size());
        assertEquals("字幕#1.简体.srt", result.get(0).getName());
        File file = new File(result.get(0).getUrl());
        assertEquals(directory, file.getParentFile());
        assertEquals(SRT, Files.readString(file.toPath()));
        assertTrue(result.get(0).isForced());
    }

    @Test
    public void shouldAcceptUtf16SubtitleWithoutChangingItsEncoding() throws Exception {
        byte[] text = ("\ufeff" + SRT).getBytes(StandardCharsets.UTF_16LE);
        List<Sub> result = SubtitleDownload.save(new ByteArrayInputStream(text), "bilingual.srt", temporary.newFolder());

        assertEquals(text.length, new File(result.get(0).getUrl()).length());
        assertEquals("\ufeff" + SRT, new String(Files.readAllBytes(new File(result.get(0).getUrl()).toPath()), StandardCharsets.UTF_16LE));
    }

    @Test
    public void shouldReturnAllSupportedArchiveFilesForUserSelection() throws Exception {
        byte[] archive = zip(new String[]{"Season 2/Show.S02E03.srt", "Season 2/Show.S02E03.ass", "readme.txt"},
                new String[]{SRT, "[Script Info]\nTitle: Fixture\n[Events]\n", "Credits"});
        File directory = temporary.newFolder();
        List<Sub> result = SubtitleDownload.save(new ByteArrayInputStream(archive), "season.zip", directory);

        assertEquals(2, result.size());
        assertEquals("Show.S02E03.srt", result.get(0).getName());
        assertEquals("Show.S02E03.ass", result.get(1).getName());
        for (Sub sub : result) assertEquals(directory, new File(sub.getUrl()).getParentFile());
        assertFalse(new File(directory, "download.part").exists());
    }

    @Test
    public void shouldRejectArchiveTraversalAndRemovePartialDownloads() throws Exception {
        for (String name : new String[]{"../outside.srt", "Season/../../outside.srt", "C:\\outside.srt", "/outside.srt"}) {
            byte[] archive = zip(new String[]{name}, new String[]{SRT});
            File directory = temporary.newFolder();
            assertThrows(IOException.class, () -> SubtitleDownload.save(new ByteArrayInputStream(archive), "invalid.zip", directory));
            assertFalse(directory.exists());
            assertFalse(new File(temporary.getRoot(), "outside.srt").exists());
        }
    }

    @Test
    public void shouldRejectHtmlAndEmptyDownloadsInsteadOfLoadingThemAsSubtitles() throws Exception {
        for (String text : new String[]{"<html>Download expired</html>", "", "not a subtitle"}) {
            File directory = temporary.newFolder();
            assertThrows(IOException.class, () -> SubtitleDownload.save(stream(text), "download.srt", directory));
            assertFalse(directory.exists());
        }
    }

    @Test
    public void shouldAcceptVttAndTtmlButRejectUnsupportedBinaryFormats() throws Exception {
        assertEquals(1, SubtitleDownload.save(stream("WEBVTT\n\n00:01.000 --> 00:02.000\nFixture\n"), "fixture.vtt", temporary.newFolder()).size());
        assertEquals(1, SubtitleDownload.save(stream("<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body/></tt>"), "fixture.ttml", temporary.newFolder()).size());
        assertTrue(SubtitleDownload.isSupported("fixture.rar"));
        assertFalse(SubtitleDownload.isSupported("fixture.apk"));
        assertFalse(SubtitleDownload.isSupported("fixture.idx"));
    }

    @Test
    public void shouldBoundExpandedBytesEvenForIgnoredArchiveEntries() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
            archive.putNextEntry(new ZipEntry("large-readme.txt"));
            byte[] block = new byte[32_768];
            for (long size = 0; size <= SubtitleDownload.MAX_EXPANDED_BYTES; size += block.length) archive.write(block);
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry("actual.srt"));
            archive.write(SRT.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        File directory = temporary.newFolder();
        assertThrows(IOException.class, () -> SubtitleDownload.save(new ByteArrayInputStream(bytes.toByteArray()), "bundle.zip", directory));
        assertFalse(directory.exists());
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] zip(String[] names, String[] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
            for (int index = 0; index < names.length; index++) {
                archive.putNextEntry(new ZipEntry(names[index]));
                archive.write(contents[index].getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
