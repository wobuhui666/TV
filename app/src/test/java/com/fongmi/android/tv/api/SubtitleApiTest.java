package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.SubtitleSearchItem;
import com.fongmi.android.tv.bean.SubtitleSearchPage;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SubtitleApiTest {

    @Test
    public void parsesObjectShapedAssrtLists() throws Exception {
        String json = "{\"status\":0,\"sub\":{\"subs\":{\"id\":7,\"native_name\":\"Movie\",\"lang\":{\"desc\":\"简体\"}}}}";

        SubtitleSearchPage page = SubtitleApi.parseSearch(json);

        assertEquals(1, page.resultCount());
        assertEquals("Movie - 简体", page.items().get(0).getText());
    }

    @Test
    public void filtersUnsupportedDetailFiles() throws Exception {
        String json = "{\"status\":0,\"sub\":{\"subs\":[{\"id\":7,\"filelist\":[{\"f\":\"movie.srt\",\"url\":\"https://example.com/movie.srt\"},{\"f\":\"readme.txt\",\"url\":\"https://example.com/readme.txt\"}]}]}}";

        List<SubtitleSearchItem> items = SubtitleApi.parseDetail(json);

        assertEquals(1, items.size());
        assertTrue(items.get(0).getUrl().endsWith("movie.srt"));
    }
}
