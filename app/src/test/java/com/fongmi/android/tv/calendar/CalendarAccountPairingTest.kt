package com.fongmi.android.tv.calendar

import org.junit.Assert.*
import org.junit.Test

class CalendarAccountPairingTest {
    @Test fun shouldRejectUnknownCodesAndExpireWithoutSaving() {
        var now = 0L
        val pairing = CalendarAccountPairing { now }
        val code = pairing.start { }
        assertEquals(48, code.length)
        assertThrows(SecurityException::class.java) { pairing.status("wrong") }
        pairing.submit(code, "test-token")
        now = CalendarAccountPairing.DURATION
        var saved = false
        assertFalse(pairing.complete(code) { saved = true })
        assertFalse(saved)
        assertThrows(IllegalStateException::class.java) { pairing.status(code) }
    }

    @Test fun shouldAcceptOnlyOneSubmissionWhileCheckingAndAfterSuccess() {
        val tokens = mutableListOf<String>()
        val pairing = CalendarAccountPairing()
        val code = pairing.start { tokens.add(it) }
        assertEquals("pending", pairing.submit(code, "first-token").state)
        pairing.submit(code, "second-token")
        var saves = 0
        assertTrue(pairing.complete(code) { saves++ })
        pairing.cancel(code)
        assertEquals("connected", pairing.status(code).state)
        pairing.submit(code, "third-token")
        assertFalse(pairing.complete(code) { saves++ })
        assertEquals(listOf("first-token"), tokens)
        assertEquals(1, saves)
    }

    @Test fun shouldAllowRetryAfterInvalidTokenOrNetworkFailure() {
        var calls = 0
        val pairing = CalendarAccountPairing()
        val code = pairing.start { calls++ }
        pairing.submit(code, "invalid")
        pairing.failed(code, true)
        assertEquals("invalid_token", pairing.status(code).state)
        pairing.submit(code, "retry")
        pairing.failed(code, false)
        assertEquals("failed", pairing.status(code).state)
        pairing.submit(code, "valid")
        assertTrue(pairing.complete(code) {})
        assertEquals(3, calls)
    }

    @Test fun shouldIgnoreLateResultsAfterCancellationOrReplacement() {
        val pairing = CalendarAccountPairing()
        val old = pairing.start { }
        pairing.submit(old, "old-token")
        pairing.cancel(old)
        assertFalse(pairing.complete(old) { fail("Canceled account saved") })
        val next = pairing.start { }
        assertNotEquals(old, next)
        pairing.cancel(old)
        pairing.failed(old, true)
        assertFalse(pairing.complete(old) { fail("Old account replaced new account") })
        assertEquals("ready", pairing.status(next).state)
    }

    @Test fun shouldNotReportSuccessWhenCredentialStorageFails() {
        val pairing = CalendarAccountPairing()
        val code = pairing.start { }
        pairing.submit(code, "test-token")
        assertThrows(IllegalArgumentException::class.java) {
            pairing.complete(code) { throw IllegalArgumentException("Storage failed") }
        }
        pairing.failed(code, false)
        assertEquals("failed", pairing.status(code).state)
    }

    @Test fun shouldRejectEmptyOversizedOrHeaderInjectionTokens() {
        val pairing = CalendarAccountPairing()
        val code = pairing.start { fail("Invalid token accepted") }
        for (token in listOf("", "a".repeat(2049), "hello\r\nworld", "a b", "令牌")) {
            assertThrows(IllegalArgumentException::class.java) { pairing.submit(code, token) }
        }
        assertEquals("ready", pairing.status(code).state)
    }

    @Test fun shouldInvalidateAllCodesWhenServerStops() {
        val pairing = CalendarAccountPairing()
        val code = pairing.start { }
        pairing.submit(code, "test-token")
        pairing.clear()
        assertFalse(pairing.isPending(code))
        assertThrows(IllegalStateException::class.java) { pairing.status(code) }
    }

    @Test fun shouldExposeOnlyPublicTraktCodesAndPreventAnotherClientIdDuringAuthorization() {
        val submitted = mutableListOf<String>()
        val pairing = CalendarAccountPairing()
        val code = pairing.start(CalendarProvider.TRAKT) { submitted.add(it) }
        pairing.submit(code, "trakt-client")
        assertTrue(pairing.authorize(code, CalendarDeviceCode("private-device-code", "PUBLIC12", "https://auth.trakt.tv/activate", 300, 5)))
        val snapshot = pairing.status(code)
        assertEquals("trakt", snapshot.provider)
        assertEquals("authorizing", snapshot.state)
        assertEquals("PUBLIC12", snapshot.userCode)
        assertFalse(snapshot.toString().contains("private-device-code"))
        pairing.submit(code, "different-client")
        assertEquals(listOf("trakt-client"), submitted)
        assertTrue(pairing.complete(code) {})
        assertEquals("", pairing.status(code).userCode)
    }

    @Test fun shouldApplyTheEarlierTraktExpiryAndRejectLateAuthorizationResults() {
        var now = 0L
        val pairing = CalendarAccountPairing { now }
        val code = pairing.start(CalendarProvider.TRAKT) {}
        pairing.submit(code, "client")
        pairing.authorize(code, CalendarDeviceCode("private", "PUBLIC", "https://auth.trakt.tv/activate", 5, 1))
        now = 5000
        assertFalse(pairing.complete(code) { fail("Expired token saved") })
        assertThrows(IllegalStateException::class.java) { pairing.status(code) }
    }

    @Test fun shouldRejectCrossProviderStateChangesAndMalformedTraktClientIds() {
        val pairing = CalendarAccountPairing()
        val bangumi = pairing.start {}
        pairing.submit(bangumi, "token")
        assertFalse(pairing.authorize(bangumi, CalendarDeviceCode("private", "PUBLIC", "https://auth.trakt.tv/activate", 5, 1)))
        val trakt = pairing.start(CalendarProvider.TRAKT) { fail("Invalid client accepted") }
        for (value in listOf("https://example.com", "a".repeat(257), "client id")) {
            assertThrows(IllegalArgumentException::class.java) { pairing.submit(trakt, value) }
        }
    }
}
