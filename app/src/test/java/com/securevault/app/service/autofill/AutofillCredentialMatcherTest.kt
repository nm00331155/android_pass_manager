package com.securevault.app.service.autofill

import com.securevault.app.data.repository.model.Credential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutofillCredentialMatcher] のユニットテスト。
 */
class AutofillCredentialMatcherTest {

    @Test
    fun `domain match ranks matching service name first`() {
        val credentials = listOf(
            credential(serviceName = "GitHub", updatedAt = 100L),
            credential(serviceName = "Example", updatedAt = 200L)
        )

        val ranked = AutofillCredentialMatcher.rank(
            credentials = credentials,
            packageName = "com.android.chrome",
            webDomain = "github.com",
            appLabel = null,
            maxItems = 8
        )

        assertEquals("GitHub", ranked.first().serviceName)
        assertEquals(1, ranked.size)
    }

    @Test
    fun `app label exact match returns relevant app credential`() {
        val credentials = listOf(
            credential(serviceName = "Netflix", updatedAt = 100L),
            credential(serviceName = "GitHub", updatedAt = 300L)
        )

        val ranked = AutofillCredentialMatcher.rank(
            credentials = credentials,
            packageName = "com.netflix.mediaclient",
            webDomain = null,
            appLabel = "Netflix",
            maxItems = 8
        )

        assertEquals(listOf("Netflix"), ranked.map { it.serviceName })
    }

    @Test
    fun `no confident match returns no credential`() {
        val credentials = listOf(
            credential(serviceName = "楽天銀行", isFavorite = false, updatedAt = 100L),
            credential(serviceName = "社内ポータル", isFavorite = true, updatedAt = 200L)
        )

        val ranked = AutofillCredentialMatcher.rank(
            credentials = credentials,
            packageName = "jp.co.unknown.nativeapp",
            webDomain = null,
            appLabel = "Unknown App",
            maxItems = 8
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `maxItems limits same service credentials`() {
        val credentials = listOf(
            credential(serviceName = "GitHub", serviceUrl = "https://github.com", updatedAt = 1L),
            credential(serviceName = "GitHub Work", serviceUrl = "https://github.com/login", updatedAt = 2L),
            credential(serviceName = "GitHub Secondary", serviceUrl = "https://github.com/sessions", updatedAt = 3L),
            credential(serviceName = "Example", serviceUrl = "https://example.com", updatedAt = 4L)
        )

        val ranked = AutofillCredentialMatcher.rank(
            credentials = credentials,
            packageName = "com.android.chrome",
            webDomain = "github.com",
            appLabel = null,
            maxItems = 3
        )

        assertEquals(3, ranked.size)
        assertTrue(ranked.all { it.serviceName.contains("GitHub") })
    }

    private fun credential(
        serviceName: String,
        serviceUrl: String? = null,
        packageName: String? = null,
        isFavorite: Boolean = false,
        updatedAt: Long = 0L
    ): Credential {
        return Credential(
            id = updatedAt,
            serviceName = serviceName,
            serviceUrl = serviceUrl,
            packageName = packageName,
            username = "user",
            password = "pass",
            isFavorite = isFavorite,
            updatedAt = updatedAt
        )
    }
}
