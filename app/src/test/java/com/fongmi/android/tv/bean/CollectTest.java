package com.fongmi.android.tv.bean;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CollectTest {

    @Test
    public void contentDiffIncludesStateSelectionAndItems() {
        Site site = Site.get("site", "站点");
        Collect loading = new Collect(site, new ArrayList<>()).state("LOADING");
        Collect failed = new Collect(site, new ArrayList<>()).state("FAILURE");
        assertFalse(loading.isSameContent(failed));

        Collect selected = new Collect(site, new ArrayList<>()).state("LOADING");
        selected.setSelected(true);
        assertFalse(loading.isSameContent(selected));

        Vod vod = new Vod();
        vod.setId("vod");
        Collect withItem = new Collect(site, List.of(vod)).state("LOADING");
        assertFalse(loading.isSameContent(withItem));
        assertTrue(loading.isSameContent(new Collect(site, new ArrayList<>()).state("LOADING")));
    }
}
