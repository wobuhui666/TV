package com.fongmi.android.tv.server.process;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalFileMutationTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final OkHttpClient client = new OkHttpClient();
    private NanoHTTPD server;

    @Before
    public void startServer() throws Exception {
        Local local = new Local(temporary.getRoot());
        server = new NanoHTTPD("127.0.0.1", 0) {
            @Override
            public Response serve(IHTTPSession session) {
                try {
                    session.parseBody(new HashMap<>());
                    return local.doResponse(session, session.getUri(), Map.of());
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        };
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
    }

    @After
    public void stopServer() {
        if (server != null) server.stop();
        client.dispatcher().executorService().shutdownNow();
        client.connectionPool().evictAll();
    }

    @Test
    public void shouldRenameChineseAndQuotedFileNamesWithoutChangingContent() throws Exception {
        File source = temporary.newFile("原字幕.srt");
        Files.writeString(source.toPath(), "subtitle content");
        String name = "第三集 \"彩蛋\" #.srt";

        assertEquals(200, post("/rename", "path", "/原字幕.srt", "name", name));

        assertFalse(source.exists());
        assertEquals("subtitle content", Files.readString(new File(temporary.getRoot(), name).toPath()));
    }

    @Test
    public void shouldMoveADirectoryAndItsContentIntoAnExistingFolder() throws Exception {
        File source = temporary.newFolder("字幕");
        Files.writeString(new File(source, "中文.srt").toPath(), "caption");
        File destination = temporary.newFolder("收藏");

        assertEquals(200, post("/move", "path", "/字幕", "target", "/收藏"));

        assertFalse(source.exists());
        assertEquals("caption", Files.readString(new File(destination, "字幕/中文.srt").toPath()));
    }

    @Test
    public void shouldRejectCollisionsWithoutOverwritingEitherFile() throws Exception {
        File source = temporary.newFile("source.srt");
        File existing = temporary.newFile("existing.srt");
        Files.writeString(source.toPath(), "source");
        Files.writeString(existing.toPath(), "existing");

        assertEquals(409, post("/rename", "path", "/source.srt", "name", "existing.srt"));

        assertEquals("source", Files.readString(source.toPath()));
        assertEquals("existing", Files.readString(existing.toPath()));

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int index = 0; index < 20; index++) {
                String first = "first-" + index + ".srt";
                String second = "second-" + index + ".srt";
                String target = "target-" + index + ".srt";
                Files.writeString(new File(temporary.getRoot(), first).toPath(), "first");
                Files.writeString(new File(temporary.getRoot(), second).toPath(), "second");
                CountDownLatch start = new CountDownLatch(1);
                Future<Integer> one = workers.submit(() -> { start.await(); return post("/rename", "path", "/" + first, "name", target); });
                Future<Integer> two = workers.submit(() -> { start.await(); return post("/rename", "path", "/" + second, "name", target); });
                start.countDown();
                int a = one.get();
                int b = two.get();
                assertTrue("Exactly one rename wins", a == 200 && b == 409 || a == 409 && b == 200);
                assertEquals(a == 200 ? "first" : "second", Files.readString(new File(temporary.getRoot(), target).toPath()));
                assertTrue(new File(temporary.getRoot(), a == 200 ? second : first).exists());
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void shouldRejectRootAndSelfDescendantMoves() throws Exception {
        File source = temporary.newFolder("parent", "child");

        assertEquals(400, post("/rename", "path", "/", "name", "renamed-root"));
        assertEquals(400, post("/move", "path", "/parent", "target", "/parent/child"));

        assertTrue(source.isDirectory());
    }

    @Test
    public void shouldRejectPathTraversalAndInvalidNames() throws Exception {
        File source = temporary.newFile("subtitle.srt");
        for (String name : new String[]{"", "..", "../outside.srt", "nested/file.srt", "nested\\file.srt"}) {
            assertEquals(name, 400, post("/rename", "path", "/subtitle.srt", "name", name));
        }
        assertEquals(400, post("/move", "path", "/subtitle.srt", "target", "../"));
        assertEquals(400, post("/rename", "path", "../outside", "name", "renamed"));

        assertTrue(source.isFile());
    }

    @Test
    public void shouldRejectMissingFilesAndMissingDestinationDirectories() throws Exception {
        temporary.newFile("source.srt");

        assertEquals(404, post("/rename", "path", "/missing", "name", "renamed"));
        assertEquals(404, post("/move", "path", "/source.srt", "target", "/missing-folder"));
        assertEquals(400, post("/rename", "name", "renamed"));
    }

    @Test
    public void shouldRequirePostAndAllowAnUnchangedName() throws Exception {
        temporary.newFile("source.srt");
        try (Response response = client.newCall(new Request.Builder().url(base() + "/rename?path=/source.srt&name=other.srt").build()).execute()) {
            assertEquals(405, response.code());
        }

        assertEquals(200, post("/rename", "path", "/source.srt", "name", "source.srt"));
    }

    @Test
    public void shouldRejectCrossOriginMutationsAndAcceptTheCurrentWebpage() throws Exception {
        File source = temporary.newFile("source.srt");
        FormBody body = new FormBody.Builder().add("path", "/source.srt").add("name", "renamed.srt").build();
        try (Response response = client.newCall(new Request.Builder().url(base() + "/rename")
                .header("Origin", "https://other.example.invalid").post(body).build()).execute()) {
            assertEquals(403, response.code());
        }
        assertTrue(source.exists());
        try (Response response = client.newCall(new Request.Builder().url(base() + "/rename")
                .header("Origin", base()).post(body).build()).execute()) {
            assertEquals(200, response.code());
        }
        assertFalse(source.exists());
    }

    private int post(String path, String... fields) throws Exception {
        FormBody.Builder body = new FormBody.Builder();
        for (int index = 0; index < fields.length; index += 2) body.add(fields[index], fields[index + 1]);
        try (Response response = client.newCall(new Request.Builder().url(base() + path).post(body.build()).build()).execute()) {
            return response.code();
        }
    }

    private String base() {
        return "http://127.0.0.1:" + server.getListeningPort();
    }
}
