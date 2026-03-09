package com.securevault.app.service.autofill

import com.securevault.app.data.repository.model.Credential
import java.util.Locale

/**
 * Autofill 候補の照合と優先順位付けを担当する。
 *
 * 手入力で保存された認証情報は packageName を持たないケースが多いため、
 * URL・サービス名・パッケージ名の断片を横断的に使って候補を絞り込む。
 * それでも一致が取れない場合は上位件数をフォールバック表示する。
 */
object AutofillCredentialMatcher {

    private const val EXACT_PACKAGE_SCORE = 10_000
    private const val EXACT_HOST_SCORE = 9_000
    private const val EXACT_LABEL_SCORE = 7_000
    private const val CONTAINS_LABEL_SCORE = 4_000
    private const val EXACT_TOKEN_SCORE = 1_500
    private const val CONTAINS_TOKEN_SCORE = 700
    private const val DOMAIN_CONFIDENCE_THRESHOLD = 4_000
    private const val APP_CONFIDENCE_THRESHOLD = 3_000
    private const val SCORE_SPREAD_LIMIT = 5_000

    private val ignoredPackageParts = setOf(
        "com",
        "android",
        "app",
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

    /**
     * 対象パッケージ/ドメインに対して候補を優先順位付きで返す。
     */
    fun rank(
        credentials: List<Credential>,
        packageName: String,
        webDomain: String?,
        appLabel: String? = null,
        maxItems: Int
    ): List<Credential> {
        if (credentials.isEmpty()) {
            return emptyList()
        }

        val host = webDomain?.let(::extractHost).orEmpty()
        val mainDomain = extractMainDomain(host)
        val tokens = buildCandidateTokens(packageName, webDomain, appLabel)
        val normalizedTargetPackage = normalize(packageName)
        val normalizedHost = normalize(host)
        val normalizedMainDomain = normalize(mainDomain)
        val normalizedAppLabel = normalize(appLabel.orEmpty())

        val ranked = credentials
            .map { credential ->
                credential to score(
                    credential = credential,
                    normalizedTargetPackage = normalizedTargetPackage,
                    normalizedHost = normalizedHost,
                    normalizedMainDomain = normalizedMainDomain,
                    normalizedAppLabel = normalizedAppLabel,
                    tokens = tokens
                )
            }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Credential, Int>> { it.second }
                    .thenByDescending { it.first.isFavorite }
                    .thenByDescending { it.first.updatedAt }
            )

        if (ranked.isEmpty()) {
            return emptyList()
        }

        val bestScore = ranked.first().second
        val threshold = if (normalizedHost.isNotBlank()) {
            DOMAIN_CONFIDENCE_THRESHOLD
        } else {
            APP_CONFIDENCE_THRESHOLD
        }
        if (bestScore < threshold) {
            return emptyList()
        }

        val scoreFloor = maxOf(threshold, bestScore - SCORE_SPREAD_LIMIT)
        return ranked
            .filter { it.second >= scoreFloor }
            .map { it.first }
            .take(maxItems)
    }

    private fun score(
        credential: Credential,
        normalizedTargetPackage: String,
        normalizedHost: String,
        normalizedMainDomain: String,
        normalizedAppLabel: String,
        tokens: Set<String>
    ): Int {
        val normalizedServiceName = normalize(credential.serviceName)
        val normalizedServiceUrl = normalize(credential.serviceUrl.orEmpty())
        val normalizedServiceHost = extractHost(credential.serviceUrl.orEmpty()).let(::normalize)
        val normalizedCredentialPackage = normalize(credential.packageName.orEmpty())
        val haystacks = listOf(
            normalizedServiceName,
            normalizedServiceUrl,
            normalizedServiceHost,
            normalizedCredentialPackage
        )

        var score = 0

        if (normalizedTargetPackage.isNotBlank() && normalizedCredentialPackage == normalizedTargetPackage) {
            score += EXACT_PACKAGE_SCORE
        }

        if (normalizedHost.isNotBlank()) {
            if (normalizedServiceHost == normalizedHost) {
                score += EXACT_HOST_SCORE
            } else if (normalizedServiceUrl.contains(normalizedHost)) {
                score += EXACT_HOST_SCORE / 2
            }

            if (normalizedMainDomain.isNotBlank()) {
                score += scoreLabelMatch(normalizedServiceName, normalizedMainDomain)
                score += scoreLabelMatch(normalizedServiceHost, normalizedMainDomain)
            }
        }

        if (normalizedAppLabel.isNotBlank()) {
            score += scoreLabelMatch(normalizedServiceName, normalizedAppLabel)
            score += scoreLabelMatch(normalizedServiceHost, normalizedAppLabel)
            score += scoreLabelMatch(normalizedServiceUrl, normalizedAppLabel) / 2
        }

        tokens.forEach { token ->
            if (token.isBlank()) {
                return@forEach
            }
            if (haystacks.any { it == token }) {
                score += EXACT_TOKEN_SCORE
            } else if (haystacks.any { it.contains(token) }) {
                score += CONTAINS_TOKEN_SCORE
            }
        }

        return score
    }

    private fun scoreLabelMatch(source: String, label: String): Int {
        if (source.isBlank() || label.isBlank()) {
            return 0
        }
        return when {
            source == label -> EXACT_LABEL_SCORE
            source.contains(label) || label.contains(source) -> CONTAINS_LABEL_SCORE
            else -> 0
        }
    }

    private fun buildCandidateTokens(packageName: String, webDomain: String?, appLabel: String?): Set<String> {
        val tokens = linkedSetOf<String>()

        fun addToken(raw: String?) {
            val normalized = raw?.let(::normalize).orEmpty()
            if (normalized.length >= 3) {
                tokens += normalized
            }
        }

        val host = webDomain?.let(::extractHost).orEmpty()
        val mainDomain = extractMainDomain(host)

        addToken(host)
        addToken(mainDomain)
        addToken(appLabel)

        host.split('.')
            .filter { it.length >= 3 && it !in ignoredPackageParts }
            .forEach(::addToken)

        packageName.split('.')
            .filter { it.length >= 3 && it !in ignoredPackageParts }
            .forEach(::addToken)

        addToken(packageName.substringAfterLast('.', ""))

        return tokens
    }

    private fun extractHost(input: String): String {
        val trimmed = input.trim().lowercase(Locale.ROOT)
        if (trimmed.isBlank()) {
            return ""
        }
        val withoutScheme = trimmed.substringAfter("://", trimmed)
        return withoutScheme.substringBefore('/').substringBefore(':')
    }

    private fun extractMainDomain(host: String): String {
        val parts = host.split('.').filter { it.isNotBlank() }
        if (parts.size < 2) {
            return host
        }

        val twoLevelTlds = setOf(
            "co.jp",
            "or.jp",
            "ne.jp",
            "ac.jp",
            "go.jp",
            "co.uk",
            "org.uk",
            "com.au",
            "co.kr",
            "com.br",
            "com.cn"
        )
        val suffix = "${parts[parts.size - 2]}.${parts.last()}"
        return if (suffix in twoLevelTlds && parts.size >= 3) {
            parts[parts.size - 3]
        } else {
            parts[parts.size - 2]
        }
    }

    private fun normalize(value: String): String {
        return value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("https?://"), "")
            .replace(Regex("[^a-z0-9\u3040-\u30ff\u3400-\u9fff]"), "")
    }
}
