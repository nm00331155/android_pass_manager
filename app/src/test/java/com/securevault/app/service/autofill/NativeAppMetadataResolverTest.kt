package com.securevault.app.service.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAppMetadataResolverTest {

    @Test
    fun `activity package wins over android framework package`() {
        val resolved = NativeAppMetadataResolver.chooseBestPackageName(
            activityPackageName = "jp.co.mcdonalds.android",
            observedPackages = listOf("android", "android", "jp.co.mcdonalds.android"),
            ownPackageName = "com.securevault.app"
        )

        assertEquals("jp.co.mcdonalds.android", resolved)
    }

    @Test
    fun `most frequent non generic observed package is selected when activity is missing`() {
        val resolved = NativeAppMetadataResolver.chooseBestPackageName(
            activityPackageName = null,
            observedPackages = listOf(
                "android",
                "jp.co.mcdonalds.android",
                "jp.co.mcdonalds.android",
                "android"
            ),
            ownPackageName = "com.securevault.app"
        )

        assertEquals("jp.co.mcdonalds.android", resolved)
    }

    @Test
    fun `app label is preferred for native app service name`() {
        val serviceName = NativeAppMetadataResolver.chooseServiceName(
            webDomain = null,
            appLabel = "マクドナルド",
            packageName = "jp.co.mcdonalds.android",
            defaultServiceName = "Saved Login"
        )

        assertEquals("マクドナルド", serviceName)
    }

    @Test
    fun `generic package and service name are replaceable`() {
        assertTrue(
            NativeAppMetadataResolver.shouldReplacePackageName(
                currentPackageName = "android",
                candidatePackageName = "jp.co.mcdonalds.android",
                ownPackageName = "com.securevault.app"
            )
        )
        assertTrue(
            NativeAppMetadataResolver.shouldReplaceServiceName(
                currentServiceName = "android",
                candidateServiceName = "マクドナルド",
                currentPackageName = "android"
            )
        )
        assertFalse(
            NativeAppMetadataResolver.isGenericPackageName(
                packageName = "jp.co.mcdonalds.android",
                ownPackageName = "com.securevault.app"
            )
        )
    }
}