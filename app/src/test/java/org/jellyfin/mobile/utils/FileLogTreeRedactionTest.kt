package org.jellyfin.mobile.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FileLogTreeRedactionTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    @DisplayName("the web client's stored credentials lose their access token")
    fun storedCredentials() {
        val line = "I/WebView: Stored JSON credentials: {\"Servers\":[{\"ManualAddress\":\"http://10.0.0.2:8096\"," +
            "\"AccessToken\":\"$token\",\"UserId\":\"f00d\"}]}, http://10.0.0.2:8096/web/main.js (1)"

        val redacted = FileLogTree.redact(line)

        assertFalse(token in redacted)
        assertTrue("\"AccessToken\":\"<redacted>\"" in redacted)
        assertTrue("\"UserId\":\"f00d\"" in redacted, "unrelated members must survive")
        assertTrue("\"ManualAddress\":\"http://10.0.0.2:8096\"" in redacted)
    }

    @Test
    @DisplayName("JSON that was itself escaped into a string is covered too")
    fun escapedJson() {
        val redacted = FileLogTree.redact("""{\"AccessToken\":\"$token\"}""")

        assertEquals("""{\"AccessToken\":\"<redacted>\"}""", redacted)
    }

    @Test
    @DisplayName("query parameters carrying a key are redacted, the rest of the URL is kept")
    fun queryParameters() {
        val redacted = FileLogTree.redact(
            "GET http://host/Videos/1/master.m3u8?MediaSourceId=abc&api_key=$token&PlaySessionId=xyz " +
                "and http://host/Audio/1/stream?ApiKey=$token",
        )

        assertFalse(token in redacted)
        assertTrue("MediaSourceId=abc&api_key=<redacted>&PlaySessionId=xyz" in redacted)
        assertTrue("ApiKey=<redacted>" in redacted)
    }

    @Test
    @DisplayName("authorization headers are redacted")
    fun headers() {
        val redacted = FileLogTree.redact(
            "Authorization: MediaBrowser Client=\"Jellyfin\", DeviceId=\"dev\", Token=\"$token\"\n" +
                "X-Emby-Token: $token",
        )

        assertFalse(token in redacted)
        assertTrue("DeviceId=\"dev\", Token=\"<redacted>\"" in redacted)
        assertTrue("X-Emby-Token: <redacted>" in redacted)
    }

    @Test
    @DisplayName("passwords in an authentication body are redacted")
    fun passwords() {
        assertEquals(
            "{\"Username\":\"me\",\"Pw\":\"<redacted>\"}",
            FileLogTree.redact("{\"Username\":\"me\",\"Pw\":\"hunter2\"}"),
        )
    }

    @Test
    @DisplayName("ordinary log lines are left untouched")
    fun ordinaryLines() {
        val line = "Playback error, attempting fallback\n  errorCode: ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT\n" +
            "    uri: http://host/videos/1/hls1/main/0.mp4"

        assertEquals(line, FileLogTree.redact(line))
    }
}
