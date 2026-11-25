package io.lightknight.omniread

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import io.lightknight.omniread.engine.sync.DriveAuthManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAuthHelperTest {
    @Test
    fun generatesValidPkcePairAndParsesLoopbackCallback() {
        val (verifier1, challenge1) = DriveAuthManager.generatePkcePair()
        val (verifier2, _) = DriveAuthManager.generatePkcePair()

        assertTrue(verifier1.length >= 43)
        assertTrue(challenge1.length >= 43)
        assertNotEquals(verifier1, challenge1)
        assertNotEquals(verifier1, verifier2)

        val params = DriveAuthManager.parseLoopbackQueryParams("/oauth2callback?code=abc%2F123&state=xyz&scope=drive")
        assertEquals("abc/123", params["code"])
        assertEquals("xyz", params["state"])
        assertEquals("drive", params["scope"])
    }

    @Test
    fun formatsCommonPlayServicesAuthErrorsInVietnamese() {
        val sha = "E1:6D:4D:18"
        val err10 = DriveAuthManager.formatPlayServicesAuthError(ApiException(Status(10, "Developer error")), sha)
        assertTrue(err10.contains("10"))
        assertTrue(err10.contains(sha))
        assertTrue(err10.contains("OAuth PKCE"))

        val err16 = DriveAuthManager.formatPlayServicesAuthError(ApiException(Status(16, "Canceled/Not allowed")), sha)
        assertTrue(err16.contains("16"))
        assertTrue(err16.contains("Test users"))

        val err7 = DriveAuthManager.formatPlayServicesAuthError(ApiException(Status(7, "Network error")), sha)
        assertTrue(err7.contains("7"))
        assertTrue(err7.contains("mạng", ignoreCase = true))
    }
}
