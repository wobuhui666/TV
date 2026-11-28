#!/usr/bin/env python3
"""Exercise the bundled calendar phone page with controlled account responses."""
import json
import mimetypes
import os
from pathlib import Path
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright, expect

ASSETS = Path(__file__).resolve().parents[2] / "app/src/main/assets"
OUTPUT = Path(os.environ.get("WEB_VERIFY_OUTPUT", "artifacts/calendar-account-web"))
CODE = "a1" * 24
STATE = {}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, body, mime="application/json; charset=utf-8"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/account/session":
            STATE["gets"].append(self.path)
            if self.headers.get("X-Calendar-Pairing") != CODE:
                return self.reply(403, {})
            return self.reply(STATE["status"], {"state": STATE["state"], "remainingMillis": 500000,
                "provider": STATE["provider"], "verificationUrl": STATE["verificationUrl"], "userCode": "TV123456"})
        target = ASSETS / path.lstrip("/")
        if not target.resolve().is_relative_to(ASSETS) or not target.is_file():
            return self.reply(404, {})
        self.reply(200, target.read_bytes(), mimetypes.guess_type(target.name)[0])

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        STATE["posts"].append({"path": self.path, "data": data, "code": self.headers.get("X-Calendar-Pairing"),
                               "type": self.headers.get("Content-Type")})
        STATE["state"] = "pending"
        self.reply(200, {"state": "pending", "remainingMillis": 500000, "provider": STATE["provider"]})


class CalendarAccountWebTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        OUTPUT.mkdir(parents=True, exist_ok=True)
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(headless=True, args=["--no-sandbox"])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        STATE.update(state="ready", status=200, gets=[], posts=[], provider="bangumi", verificationUrl="https://auth.trakt.tv/activate")
        self.context = self.browser.new_context(viewport={"width": 375, "height": 812})
        self.page = self.context.new_page()
        self.errors = []
        self.page.on("pageerror", lambda error: self.errors.append(str(error)))

    def tearDown(self):
        self.context.close()
        self.assertEqual([], self.errors)

    def open(self):
        self.page.goto("about:blank")
        self.page.goto(self.base + "/account.html#" + CODE)
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "ready")

    def test_submission_polls_until_saved_and_keeps_token_out_of_storage_and_urls(self):
        self.open()
        expect(self.page.locator("a.account-token-link")).to_have_attribute("href", "https://next.bgm.tv/demo/access-token")
        expect(self.page.locator("a.account-token-link")).to_have_attribute("rel", "noopener noreferrer")
        self.page.locator("#account-token").fill("fixture-personal-token")
        self.page.locator("#account-submit").click()
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "pending")
        expect(self.page.locator("#account-submit")).to_be_disabled()
        expect(self.page.locator("#account-token")).to_have_value("")
        self.assertEqual(1, len(STATE["posts"]))
        self.assertEqual({"token": "fixture-personal-token"}, STATE["posts"][0]["data"])
        self.assertEqual("application/json", STATE["posts"][0]["type"])
        self.assertEqual(CODE, STATE["posts"][0]["code"])
        self.assertNotIn("fixture-personal-token", self.page.evaluate("JSON.stringify([localStorage, sessionStorage, location.href])"))
        self.assertTrue(all("?" not in path for path in STATE["gets"]))
        STATE["state"] = "connected"
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "connected")
        expect(self.page.locator("#account-form")).to_be_hidden()
        self.assertEqual(0, self.page.evaluate("sessionStorage.length"))

    def test_invalid_token_can_retry_without_replaying_previous_submission(self):
        self.open()
        self.page.locator("#account-token").fill("invalid-fixture")
        self.page.locator("#account-submit").click()
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "pending")
        STATE["state"] = "invalid_token"
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "invalid_token")
        expect(self.page.locator("#account-submit")).to_be_enabled()
        self.page.locator("#account-token").fill("retry-fixture")
        self.page.locator("#account-submit").click()
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "pending")
        self.assertEqual(2, len(STATE["posts"]))

    def test_refresh_restores_pairing_but_never_restores_token(self):
        self.open()
        self.assertNotIn("#", self.page.url)
        self.page.locator("#account-token").fill("unsent-fixture")
        self.page.reload()
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "ready")
        expect(self.page.locator("#account-token")).to_have_value("")
        self.assertEqual([], STATE["posts"])

    def test_expired_pairing_and_unpaired_entry_cannot_submit(self):
        self.page.goto(self.base + "/account.html")
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "missing")
        self.assertEqual([], STATE["gets"])
        STATE["status"] = 410
        self.page.goto(self.base + "/account.html#" + CODE)
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "ended")
        expect(self.page.locator("#account-form")).to_be_hidden()
        self.assertEqual(0, self.page.evaluate("sessionStorage.length"))

    def test_network_failure_recovers_with_read_before_resubmission(self):
        STATE["status"] = 503
        self.page.goto(self.base + "/account.html#" + CODE)
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "offline")
        STATE["status"] = 200
        self.page.locator("#account-retry").click()
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "ready")
        self.assertEqual([], STATE["posts"])

    def test_languages_themes_and_phone_widths(self):
        for language in ["zh-CN", "zh-TW", "en"]:
            for theme in ["light", "dark"]:
                self.page.goto(self.base + "/account.html")
                self.page.evaluate("([language, theme]) => { localStorage.setItem('tv-web-language', language); localStorage.setItem('tv-web-theme', theme); }", [language, theme])
                for width in [320, 375, 768]:
                    self.page.set_viewport_size({"width": width, "height": 812})
                    self.open()
                    self.assertTrue(self.page.evaluate("document.documentElement.scrollWidth <= innerWidth"))
                    self.assertEqual(language, self.page.locator("html").get_attribute("lang"))
                if language == "zh-CN":
                    self.page.set_viewport_size({"width": 375, "height": 812})
                    self.page.screenshot(path=str(OUTPUT / f"bangumi-phone-{theme}.png"))

    def test_trakt_client_id_leads_to_official_authorization_then_connected(self):
        STATE["provider"] = "trakt"
        self.open()
        expect(self.page.locator("#account-provider")).to_have_text("Trakt")
        expect(self.page.locator("#account-input-label")).to_have_text("Trakt Client ID")
        expect(self.page.locator("a.account-token-link")).to_have_attribute("href", "https://developer.trakt.tv/apps")
        self.page.locator("#account-token").fill("trakt-client-fixture")
        self.page.locator("#account-submit").click()
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "pending")
        self.assertEqual({"clientId": "trakt-client-fixture"}, STATE["posts"][0]["data"])
        STATE["state"] = "authorizing"
        expect(self.page.locator("#account-authorization")).to_be_visible()
        expect(self.page.locator("#account-form")).to_be_hidden()
        expect(self.page.locator("#account-authorize")).to_have_attribute("href", "https://auth.trakt.tv/activate")
        expect(self.page.locator("#account-authorize")).to_have_attribute("rel", "noopener noreferrer")
        expect(self.page.locator("#account-user-code")).to_have_value("TV123456")
        self.page.screenshot(path=str(OUTPUT / "trakt-phone-authorization.png"))
        STATE["state"] = "connected"
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "connected")
        self.assertEqual(0, self.page.evaluate("sessionStorage.length"))

    def test_trakt_rejects_unsafe_authorization_links(self):
        STATE.update(provider="trakt", state="authorizing", verificationUrl="https://example.invalid/steal")
        self.page.goto(self.base + "/account.html#" + CODE)
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "offline")
        expect(self.page.locator("#account-authorization")).to_be_hidden()

    def test_trakt_failed_client_id_can_retry_and_expired_code_cannot_authorize(self):
        STATE["provider"] = "trakt"
        self.open()
        self.page.locator("#account-token").fill("wrong-client")
        self.page.locator("#account-submit").click()
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "pending")
        STATE["state"] = "failed"
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "failed")
        self.page.locator("#account-token").fill("valid-client")
        self.page.locator("#account-submit").click()
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "pending")
        STATE["state"] = "authorizing"
        expect(self.page.locator("#account-authorization")).to_be_visible()
        STATE["status"] = 410
        expect(self.page.locator("#account-status")).to_have_attribute("data-state", "ended")
        expect(self.page.locator("#account-authorization")).to_be_hidden()

    def test_trakt_languages_and_narrow_screens(self):
        STATE["provider"] = "trakt"
        for language in ["zh-CN", "zh-TW", "en"]:
            self.open()
            self.page.evaluate("language => localStorage.setItem('tv-web-language', language)", language)
            for phase in ["ready", "authorizing"]:
                STATE["state"] = phase
                self.page.set_viewport_size({"width": 320, "height": 812})
                self.page.reload()
                expect(self.page.locator("#account-status")).to_have_attribute("data-state", phase)
                self.assertTrue(self.page.evaluate("document.documentElement.scrollWidth <= innerWidth"))
            STATE["state"] = "ready"


if __name__ == "__main__":
    unittest.main()
