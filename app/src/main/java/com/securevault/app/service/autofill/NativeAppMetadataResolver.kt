package com.securevault.app.service.autofill

import java.util.Locale

/**
 * ネイティブアプリ向けの package/service 名推定ルールを共通化する。
 */
object NativeAppMetadataResolver {

    private val genericPackageNames = setOf(
        "android",
        "unknown"
    )

    private val genericServiceNames = setOf(
        "android",
        "unknown",
        "savedlogin",
        "login"
    )

    private val frameworkPackagePrefixes = listOf(
        "androidx.",
        "java.",
        "kotlin.",
        "com.android.internal"
    )

    private val ignoredPackageSegments = setOf(
        "com",
        "app",
        "android",
        "mobile",
        "client",
        "jp",
        "co",
        "ne",
        "or",
        "ac",
        "go",
        "www"
    )

    fun chooseBestPackageName(
        activityPackageName: String?,
        observedPackages: List<String>,
        ownPackageName: String
    ): String? {
        val normalizedActivityPackage = normalizePackage(activityPackageName)
        val scores = linkedMapOf<String, Int>()

        fun add(raw: String?, weight: Int) {
            val candidate = normalizePackage(raw)
            if (candidate.isBlank()) {
                return
            }
            scores[candidate] = (scores[candidate] ?: 0) + weight
        }

        add(normalizedActivityPackage, 1_000)
        observedPackages.forEach { add(it, 10) }

        return scores.entries
            .asSequence()
            .filter { !isGenericPackageName(it.key, ownPackageName) }
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { if (it.key == normalizedActivityPackage) 1 else 0 }
            )
            .map { it.key }
            .firstOrNull()
    }

    fun chooseServiceName(
        webDomain: String?,
        appLabel: String?,
        packageName: String?,
        defaultServiceName: String
    ): String {
        val normalizedWebDomain = webDomain?.trim().orEmpty()
        if (normalizedWebDomain.isNotBlank()) {
            return normalizedWebDomain
        }

        val sanitizedLabel = appLabel?.trim().orEmpty()
        if (sanitizedLabel.isNotBlank() && !isWeakServiceName(sanitizedLabel, packageName)) {
            return sanitizedLabel
        }

        val fallbackSegment = extractMeaningfulPackageSegment(packageName)
        if (fallbackSegment.isNotBlank()) {
            return fallbackSegment
        }

        val normalizedPackage = normalizePackage(packageName)
        if (normalizedPackage.isNotBlank()) {
            return normalizedPackage
        }

        return defaultServiceName
    }

    fun shouldReplacePackageName(
        currentPackageName: String?,
        candidatePackageName: String?,
        ownPackageName: String
    ): Boolean {
        val candidate = normalizePackage(candidatePackageName)
        if (isGenericPackageName(candidate, ownPackageName)) {
            return false
        }

        val current = normalizePackage(currentPackageName)
        return current.isBlank() || isGenericPackageName(current, ownPackageName)
    }

    fun shouldReplaceServiceName(
        currentServiceName: String?,
        candidateServiceName: String?,
        currentPackageName: String?
    ): Boolean {
        val candidate = candidateServiceName?.trim().orEmpty()
        if (candidate.isBlank()) {
            return false
        }

        val normalizedCurrent = normalizeLookupLabel(currentServiceName)
        val normalizedCandidate = normalizeLookupLabel(candidate)
        if (normalizedCurrent == normalizedCandidate) {
            return false
        }

        return normalizedCurrent.isBlank() || isWeakServiceName(currentServiceName, currentPackageName)
    }

    fun isGenericPackageName(packageName: String?, ownPackageName: String): Boolean {
        val normalized = normalizePackage(packageName)
        return normalized.isBlank() ||
            normalized in genericPackageNames ||
            normalized == normalizePackage(ownPackageName) ||
            frameworkPackagePrefixes.any { normalized.startsWith(it) }
    }

    fun isWeakServiceName(serviceName: String?, packageName: String?): Boolean {
        val normalizedServiceName = normalizeLookupLabel(serviceName)
        if (normalizedServiceName.isBlank() || normalizedServiceName in genericServiceNames) {
            return true
        }

        val normalizedPackageTail = normalizeLookupLabel(extractMeaningfulPackageSegment(packageName))
        return normalizedPackageTail.isNotBlank() && normalizedPackageTail == normalizedServiceName
    }

    fun normalizeLookupLabel(value: String?): String {
        return value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("[^a-z0-9\u3040-\u30ff\u3400-\u9fff]"), "")
            .orEmpty()
    }

    private fun normalizePackage(value: String?): String {
        return value?.trim().orEmpty()
    }

    private fun extractMeaningfulPackageSegment(packageName: String?): String {
        val parts = normalizePackage(packageName)
            .split('.')
            .filter { part ->
                part.isNotBlank() && part.lowercase(Locale.ROOT) !in ignoredPackageSegments
            }

        return parts.lastOrNull().orEmpty()
    }
}