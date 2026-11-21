package com.fongmi.android.tv.test

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.bean.Config
import com.fongmi.android.tv.impl.Callback
import com.github.catvod.net.OkHttp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** Explicitly requested APK transfer inside the disposable app UID; signed URLs remain private. */
@RunWith(AndroidJUnit4::class)
class ArtifactDownloadAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    @Test(timeout = 900_000)
    fun downloadRequestedArtifactApks() {
        assertEquals("Only the disposable sourceprobe package may download artifacts",
            "com.fongmi.android.tv.sourceprobe", context.packageName)
        assertEquals("Artifact transfer requires explicit opt-in", "true", arguments.getString("artifact_download_opt_in"))
        val appName = arguments.getString("artifact_app_name", "")!!
        val testName = arguments.getString("artifact_test_name", "")!!
        val names = setOf(appName, testName)
        assertTrue("Provide two distinct, exact APK basenames", names.size == 2 && names.all {
            it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}\\.apk"))
        })
        val directory = File(context.filesDir, "artifact-download")
        assertTrue("Private artifact directory is required", directory.isDirectory || directory.mkdirs())
        val reportFile = File(context.getExternalFilesDir("artifact-download"), "report.json")
        reportFile.parentFile!!.mkdirs()
        val report = JSONObject().put("schema", "tv.artifact-download.v1")
            .put("status", "starting").put("requestedApks", JSONArray(names.toList()))
            .put("maximumBytes", MAX_BYTES).put("zipBytes", 0).put("apks", JSONArray())
        val began = SystemClock.elapsedRealtime()
        val zipFile = File(directory, "artifact.zip.partial")
        var succeeded = false
        fun save() {
            report.put("elapsedMs", SystemClock.elapsedRealtime() - began)
            reportFile.writeText(report.toString(2))
        }
        try {
            save()
            val configUrl = arguments.getString("artifact_config_url", "")!!
            if (configUrl.isNotEmpty()) {
                assertEquals("Only the explicitly requested source configuration may initialize networking", CONFIG_URL, configUrl)
                loadConfiguration(report, ::save)
            }
            val urlFile = File(context.filesDir, "artifact-url.txt")
            assertTrue("Provide the artifact URL through the private URL file", urlFile.isFile && urlFile.length() in 1..16_384)
            val url = urlFile.bufferedReader().use { it.readLine()?.trim() ?: "" }
            val uri = URI(url)
            assertTrue("The artifact URL must use HTTPS", uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null)
            // Preserve the app's DNS and proxy while validating the artifact server's TLS certificate.
            val client = OkHttp.trustedClient().newBuilder().followRedirects(true).followSslRedirects(false).build()
            val call = OkHttp.newCall(client, url)
            call.timeout().timeout(600, TimeUnit.SECONDS)
            report.put("status", "downloading")
            save()
            call.execute().use { response ->
                report.put("httpStatus", response.code)
                assertTrue("Artifact download must return a successful HTTPS response",
                    response.isSuccessful && response.request.url.isHttps)
                val body = response.body ?: throw IllegalStateException("Artifact body missing")
                assertTrue("The artifact exceeds the byte limit", body.contentLength() <= MAX_BYTES)
                body.byteStream().use { input ->
                    FileOutputStream(zipFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var reported = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            assertTrue("The artifact exceeds the byte limit", downloaded <= MAX_BYTES)
                            output.write(buffer, 0, count)
                            if (downloaded - reported >= 8 * 1024 * 1024) {
                                report.put("zipBytes", downloaded)
                                save()
                                reported = downloaded
                            }
                        }
                        report.put("zipBytes", downloaded)
                    }
                }
            }
            report.put("status", "extracting")
            save()
            val found = mutableSetOf<String>()
            var expanded = 0L
            var entries = 0
            ZipInputStream(FileInputStream(zipFile)).use { zip ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    assertTrue("The ZIP has too many entries", entries <= 4096)
                    assertTrue("Unsafe ZIP path", !entry.name.startsWith('/') && !entry.name.contains('\\')
                        && !entry.name.contains('\u0000') && entry.name.split('/').none { it == ".." })
                    val name = entry.name.substringAfterLast('/')
                    val wanted = !entry.isDirectory && name in names
                    if (wanted) assertTrue("The ZIP contains a duplicate requested APK", found.add(name))
                    val partial = if (wanted) File(directory, "$name.partial") else null
                    if (partial != null) assertEquals("APK output must stay in the private directory",
                        directory.canonicalFile, partial.canonicalFile.parentFile)
                    val output = partial?.let { FileOutputStream(it) }
                    try {
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            expanded += count
                            assertTrue("Expanded ZIP exceeds the byte limit", expanded <= MAX_BYTES)
                            output?.write(buffer, 0, count)
                        }
                    } finally { output?.close() }
                    zip.closeEntry()
                }
            }
            assertEquals("The ZIP must contain both exact requested APKs", names, found)
            report.put("uncompressedBytes", expanded)
            for (name in names) {
                val partial = File(directory, "$name.partial")
                assertTrue("The requested APK is empty", partial.length() > 0)
                val target = File(directory, name)
                assertTrue("Cannot replace the private APK output", !target.exists() || target.delete())
                assertTrue("Cannot finish the private APK output", partial.renameTo(target))
                report.getJSONArray("apks").put(JSONObject().put("name", name)
                    .put("bytes", target.length()).put("sha256", sha256(target)))
            }
            succeeded = true
            report.put("status", "complete")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
            // Exceptions from the HTTP client can include the signed URL; never export the cause.
            throw AssertionError("Artifact download failed (${failure.javaClass.simpleName}); inspect the sanitized report")
        } finally {
            zipFile.delete()
            names.forEach { name ->
                File(directory, "$name.partial").delete()
                if (!succeeded) File(directory, name).delete()
            }
            save()
        }
    }

    private fun loadConfiguration(report: JSONObject, save: () -> Unit) {
        if (VodConfig.getUrl() == CONFIG_URL && VodConfig.get().sites.isNotEmpty()) {
            report.put("configStatus", "already-loaded")
            return
        }
        val worker = Executors.newSingleThreadExecutor()
        val ready = CountDownLatch(1)
        val loaded = AtomicBoolean()
        try {
            worker.submit {
                try {
                    VodConfig.load(Config.find(CONFIG_URL, 0), object : Callback() {
                        override fun success() { loaded.set(true); ready.countDown() }
                        override fun error(message: String?) { ready.countDown() }
                    })
                } catch (_: Throwable) { ready.countDown() }
            }
            val completed = ready.await(90, TimeUnit.SECONDS)
            report.put("configStatus", if (!completed) "timeout" else if (loaded.get()) "loaded" else "failed")
            save()
            assertTrue("The requested configuration must load before downloading", completed && loaded.get())
        } finally { worker.shutdownNow() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val MAX_BYTES = 350L * 1024 * 1024
        private const val CONFIG_URL = "https://9280.kstore.vip/aiwex.json"
    }
}
