import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import urllib.error


SPEC = importlib.util.spec_from_file_location(
    "preview_artifacts", Path(__file__).resolve().parents[2] / ".github/scripts/prepare-quickjs-preview.py")
PREVIEW = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREVIEW)


class PinnedPreviewArtifactTest(unittest.TestCase):
    GROUP = "fixture.preview"
    ARTIFACT = "native-library"
    VERSION = "1.2.3"
    CONTENT = b"exact pinned fixture AAR bytes"
    CHECKSUM = hashlib.sha256(CONTENT).hexdigest()

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.home = Path(temporary.name)

    def cache(self, content, *, group=GROUP, artifact=ARTIFACT, version=VERSION):
        path = self.home / "caches/modules-2/files-2.1" / group / artifact / version / "cache-entry" / f"{artifact}-{version}.aar"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    def load(self):
        return PREVIEW.load_pinned_aar(self.GROUP, self.ARTIFACT, self.VERSION, self.CHECKSUM,
                                       "Fixture", gradle_home=self.home)

    def http_error(self, code, headers=None):
        return urllib.error.HTTPError("https://repo.maven.apache.org/fixture.aar", code,
                                      "fixture response", headers or {}, None)

    def test_verified_gradle_cache_avoids_network(self):
        self.cache(self.CONTENT)
        with mock.patch.object(PREVIEW.urllib.request, "urlopen") as download:
            self.assertEqual(self.CONTENT, self.load())
        download.assert_not_called()

    def test_matching_bytes_from_other_coordinates_are_not_accepted(self):
        self.cache(self.CONTENT, group="other.preview")
        self.cache(self.CONTENT, artifact="other-library")
        self.cache(self.CONTENT, version="1.2.2")
        with mock.patch.object(PREVIEW.urllib.request, "urlopen", return_value=io.BytesIO(self.CONTENT)) as download:
            self.assertEqual(self.CONTENT, self.load())
        download.assert_called_once_with(
            "https://repo.maven.apache.org/maven2/fixture/preview/native-library/1.2.3/native-library-1.2.3.aar", timeout=60)

    def test_rate_limit_retries_and_verifies_download_with_capped_retry_after(self):
        responses = [self.http_error(429, {"Retry-After": "120"}), io.BytesIO(self.CONTENT)]
        with mock.patch.object(PREVIEW.urllib.request, "urlopen", side_effect=responses) as download, \
                mock.patch.object(PREVIEW.time, "sleep") as sleep:
            self.assertEqual(self.CONTENT, self.load())
        self.assertEqual(2, download.call_count)
        sleep.assert_called_once_with(15)

    def test_corrupt_cache_falls_back_to_verified_download(self):
        self.cache(b"corrupt cached bytes")
        with mock.patch.object(PREVIEW.urllib.request, "urlopen", return_value=io.BytesIO(self.CONTENT)) as download:
            self.assertEqual(self.CONTENT, self.load())
        self.assertEqual(1, download.call_count)

    def test_corrupt_cache_and_wrong_download_checksum_fail_without_retry(self):
        self.cache(b"corrupt cached bytes")
        with mock.patch.object(PREVIEW.urllib.request, "urlopen", return_value=io.BytesIO(b"wrong repository bytes")) as download, \
                mock.patch.object(PREVIEW.time, "sleep") as sleep:
            with self.assertRaisesRegex(SystemExit, "checksum mismatch"):
                self.load()
        self.assertEqual(1, download.call_count)
        sleep.assert_not_called()

    def test_transient_server_and_network_errors_stop_after_four_attempts(self):
        final_error = self.http_error(503)
        responses = [urllib.error.URLError("temporary network failure"), self.http_error(500),
                     self.http_error(502), final_error]
        with mock.patch.object(PREVIEW.urllib.request, "urlopen", side_effect=responses) as download, \
                mock.patch.object(PREVIEW.time, "sleep") as sleep:
            with self.assertRaises(urllib.error.HTTPError) as failure:
                self.load()
        self.assertIs(final_error, failure.exception)
        self.assertEqual(4, download.call_count)
        self.assertEqual([mock.call(1), mock.call(2), mock.call(4)], sleep.call_args_list)

    def test_permanent_http_errors_do_not_retry(self):
        for code in (404, 501):
            with self.subTest(code=code), \
                    mock.patch.object(PREVIEW.urllib.request, "urlopen", side_effect=self.http_error(code)) as download, \
                    mock.patch.object(PREVIEW.time, "sleep") as sleep:
                with self.assertRaises(urllib.error.HTTPError):
                    self.load()
                self.assertEqual(1, download.call_count)
                sleep.assert_not_called()

    def test_oversized_download_is_bounded_and_not_retried(self):
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = b"x" * (PREVIEW.MAX_AAR_BYTES + 1)
        with mock.patch.object(PREVIEW.urllib.request, "urlopen", return_value=response) as download, \
                mock.patch.object(PREVIEW.time, "sleep") as sleep:
            with self.assertRaisesRegex(SystemExit, "16 MiB limit"):
                self.load()
        response.read.assert_called_once_with(16 * 1024 * 1024 + 1)
        self.assertEqual(1, download.call_count)
        sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
