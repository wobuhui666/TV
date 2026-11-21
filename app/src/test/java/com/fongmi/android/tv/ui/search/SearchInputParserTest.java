package com.fongmi.android.tv.ui.search;

import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;

import static org.junit.Assert.*;

public class SearchInputParserTest {
    @Test public void shouldKeepOnlyUsableUniqueHistoryLabels() {
        assertEquals(List.of("one", "two"), SearchInputParser.records("[null,42,{},\" one \",\"\",\"one\",\"two\"]"));
    }

    @Test public void shouldAcceptMissingOrMalformedCachesWithoutThrowing() {
        for (String cache : List.of("", "null", "{}", "[", "not json")) assertTrue(SearchInputParser.records(cache).isEmpty());
        for (String cache : List.of("", "null", "[]", "{\"data\":null}", "not json")) assertTrue(SearchInputParser.words(cache).isEmpty());
    }

    @Test public void shouldBoundHistoryBeforeMeasuringAnOldImportedList() {
        StringBuilder cache = new StringBuilder("[");
        for (int i = 0; i < 5000; i++) {
            if (i > 0) cache.append(',');
            cache.append('"').append("record-").append(i).append('"');
        }
        cache.append(']');
        List<String> records = SearchInputParser.records(cache.toString());
        assertEquals(9, records.size());
        assertEquals("record-0", records.get(0));
        assertEquals("record-8", records.get(8));
    }

    @Test public void shouldReadOnlyTheNeededPrefixOfALargeHotList() {
        StringBuilder cache = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 5000; i++) {
            if (i > 0) cache.append(',');
            cache.append("{\"title\":\"word-").append(i).append("\"}");
        }
        cache.append("]}");
        CountingReader reader = new CountingReader(cache.toString());
        List<String> words = SearchInputParser.words(reader);
        assertEquals(30, words.size());
        assertEquals("word-0", words.get(0));
        assertEquals("word-29", words.get(29));
        assertTrue("Unneeded cache entries are not parsed", reader.characters < cache.length() / 10);
        assertTrue("Network/cache readers are closed after the bounded prefix", reader.closed);
    }

    @Test public void shouldUseTitleAndNameWithoutBlankOrRepeatedSuggestions() {
        assertEquals(List.of("title", "alias", "direct"), SearchInputParser.words(
                "{\"data\":[null,{}, {\"title\":\"title\",\"name\":\"ignored alias\"},"
                        + "{\"title\":\" \",\"name\":\" alias \"},{\"name\":\"alias\"},\"direct\"]}"));
    }

    @Test public void shouldFitLabelsWithinTheSearchInputLimit() {
        String label = "x".repeat(5000);
        assertEquals(255, SearchInputParser.records("[\"" + label + "\"]").get(0).length());
        assertEquals(255, SearchInputParser.words("{\"data\":[{\"title\":\"" + label + "\"}]}").get(0).length());
    }

    @Test public void shouldStopReadingWhenTheRequestIsCancelled() {
        Thread.currentThread().interrupt();
        try {
            assertTrue(SearchInputParser.records("[\"one\",\"two\"]").isEmpty());
            assertTrue(SearchInputParser.words("{\"data\":[{\"title\":\"one\"}]}").isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void shouldPreserveOldHistoryBehindEditsMadeDuringLoading() {
        assertEquals(List.of("new", "first", "second"), SearchInputParser.mergeRecords(
                List.of("new", "first"), List.of("first", "second"), List.of()));
    }

    @Test public void shouldNeverRestoreAHistoryLabelDeletedDuringLoading() {
        assertEquals(List.of("new", "second"), SearchInputParser.mergeRecords(
                List.of("new"), List.of("deleted", "second"), List.of("deleted")));
    }

    @Test public void shouldKeepRecentEditsWithinTheHistoryLimit() {
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"), SearchInputParser.mergeRecords(
                List.of("1", "2", "3", "4", "5", "6", "7", "8"), List.of("9", "10", "11"), List.of()));
    }

    private static final class CountingReader extends Reader {
        private final StringReader reader;
        private int characters;
        private boolean closed;

        CountingReader(String value) { reader = new StringReader(value); }

        @Override public int read(char[] buffer, int offset, int length) throws IOException {
            int count = reader.read(buffer, offset, length);
            if (count > 0) characters += count;
            return count;
        }

        @Override public void close() { closed = true; reader.close(); }
    }
}
