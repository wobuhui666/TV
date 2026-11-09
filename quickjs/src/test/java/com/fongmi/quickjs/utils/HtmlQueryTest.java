package com.fongmi.quickjs.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class HtmlQueryTest {
    @Test public void selectsTextAttributesAndMissingFieldsWithoutChangingMarkup() throws Exception {
        String result = HtmlQuery.query("<ul><li><a href='/one'>A <b>B</b></a></li><li>C</li></ul>",
                "{\"queries\":{\"rows\":{\"selector\":\"li\",\"fields\":{\"text\":{},\"href\":{\"selector\":\"a\",\"attribute\":\"href\"}}}}}");
        assertEquals("{\"rows\":[{\"text\":\"A B\",\"href\":\"/one\"},{\"text\":\"C\",\"href\":null}]}", result);
    }
    @Test public void rejectsUnknownPlanOptionsAndExcessRows() {
        assertThrows(IllegalArgumentException.class, () -> HtmlQuery.query("<p>one</p>","{\"queries\":{},\"ignore\":true}"));
        assertThrows(IllegalArgumentException.class, () -> HtmlQuery.query("<p>one</p><p>two</p>",
                "{\"queries\":{\"rows\":{\"selector\":\"p\",\"maxRows\":1,\"fields\":{\"text\":{}}}}}"));
    }
}
