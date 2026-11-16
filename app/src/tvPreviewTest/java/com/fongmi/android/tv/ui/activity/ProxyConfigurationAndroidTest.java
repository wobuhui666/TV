package com.fongmi.android.tv.ui.activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.github.catvod.bean.Proxy;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Real Android Uri parsing verifies that runtime caches are rebuilt only by init. */
@RunWith(AndroidJUnit4.class)
public class ProxyConfigurationAndroidTest {
    @Test public void initRebuildsProxiesAndWildcardFromTheDocumentedFields() {
        assertEquals("Only the isolated preview", "com.fongmi.android.tv.preview",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName());
        List<Proxy> rules = Proxy.arrayFrom(JsonParser.parseString("["
                + "{\"name\":\"wild\",\"hosts\":[\"*.example.invalid\"],"
                + "\"urls\":[\"http://proxy.example.invalid:8080\",\"socks://sample:sample@proxy.example.invalid:1080\",\"invalid\"],"
                + "\"proxies\":[{}],\"uris\":[{}],\"wildcard\":false},"
                + "{\"name\":\"exact\",\"hosts\":[\"media.example.invalid\"],\"urls\":[],\"wildcard\":true}]"));
        assertEquals(2, rules.size());
        Proxy wildcard = rules.get(0), exact = rules.get(1);
        assertTrue(wildcard.getProxies().isEmpty());
        wildcard.init(); exact.init();
        assertEquals("wild", wildcard.getName());
        assertEquals(2, wildcard.getProxies().size());
        assertEquals(java.net.Proxy.Type.HTTP, wildcard.getProxies().get(0).type());
        assertEquals(java.net.Proxy.Type.SOCKS, wildcard.getProxies().get(1).type());
        InetSocketAddress address = (InetSocketAddress) wildcard.getProxies().get(1).address();
        assertTrue("Proxy initialization does not resolve the endpoint on the UI thread", address.isUnresolved());
        assertEquals(1080, address.getPort());
        assertEquals("sample:sample", wildcard.getUserInfo("proxy.example.invalid"));
        assertTrue("Wildcard priority is computed from hosts rather than input cache flags", wildcard.compareTo(exact) > 0);
        assertTrue(exact.getProxies().isEmpty());
        JsonObject serialized = new Gson().toJsonTree(wildcard).getAsJsonObject();
        assertEquals(Set.of("name", "hosts", "urls"), serialized.keySet());
    }
}
