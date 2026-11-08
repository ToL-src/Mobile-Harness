package com.jarves.mh.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitLabClientTest {
    @Test
    fun normalizeHost_defaultsAndStripsScheme() {
        assertEquals("gitlab.com", GitLabClient.normalizeHost("gitlab.com"))
        assertEquals("gitlab.com", GitLabClient.normalizeHost("https://gitlab.com/"))
        assertEquals("gitlab.example.com", GitLabClient.normalizeHost("https://gitlab.example.com"))
        assertEquals("gitlab.example.com:8443", GitLabClient.normalizeHost("https://gitlab.example.com:8443/"))
    }

    @Test
    fun normalizeHost_rejectsHttpAndCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            GitLabClient.normalizeHost("http://gitlab.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitLabClient.normalizeHost("https://user:pass@gitlab.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitLabClient.normalizeHost("https://localhost")
        }
    }

    @Test
    fun apiBase_usesV4() {
        assertEquals("https://gitlab.com/api/v4", GitLabClient.apiBase("gitlab.com"))
        assertEquals(
            "https://gitlab.example.com/api/v4",
            GitLabClient.apiBase("gitlab.example.com"),
        )
    }

    @Test
    fun normalizeHost_stripsPastedApiRoot() {
        assertEquals("gitlab.com", GitLabClient.normalizeHost("https://gitlab.com/api/v4"))
        assertEquals("gitlab.com", GitLabClient.normalizeHost("https://gitlab.com/api/v4/"))
        assertEquals(
            "gitlab.example.com/gitlab",
            GitLabClient.normalizeHost("https://gitlab.example.com/gitlab/api/v4"),
        )
    }
}
