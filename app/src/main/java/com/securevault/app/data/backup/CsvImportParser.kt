package com.securevault.app.data.backup

import android.util.Log
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 複数サービスの CSV を [BackupCredential] へ変換する汎用パーサー。
 */
@Singleton
class CsvImportParser @Inject constructor() {

    /**
     * CSV 形式が不正な場合に UI 側で判定するためのエラー識別子。
     */
    companion object {
        const val INVALID_FORMAT_ERROR = "INVALID_IMPORT_FORMAT"
        private const val TAG = "CsvImportParser"
    }

    /**
     * CSV 文字列を指定された [ImportSource] のマッピングでパースする。
     */
    fun parse(csvContent: String, source: ImportSource): List<BackupCredential> {
        val rows = splitCsvRows(csvContent)
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
        if (rows.isEmpty()) {
            return emptyList()
        }

        val headerRow = rows.first().removePrefix("\uFEFF")
        val headerFields = parseCsvLine(headerRow)
            .map { it.trim().removePrefix("\uFEFF") }
        val normalizedHeaders = headerFields.map(::normalizeColumnName)

        debugLog("Header fields: $headerFields")
        debugLog("Normalized headers: $normalizedHeaders")
        debugLog("Row count (excluding header): ${rows.size - 1}")

        if (normalizedHeaders.none { it.contains("password") }) {
            throw IllegalArgumentException(INVALID_FORMAT_ERROR)
        }

        val columns = resolveColumnIndices(headerFields, source.columnMapping)
        val now = System.currentTimeMillis()

        return rows.drop(1).mapNotNull { row ->
            if (row.isBlank()) {
                return@mapNotNull null
            }
            val fields = parseCsvLine(row)

            val password = fields.getOrNull(columns.passwordIndex).orEmpty().trim()
            if (password.isBlank()) {
                return@mapNotNull null
            }

            val rawServiceName = fields.getOrNull(columns.serviceNameIndex).orEmpty().trim()
            val serviceUrl = columns.serviceUrlIndex
                ?.let { fields.getOrNull(it).orEmpty().trim() }
                ?.ifBlank { null }
            val username = fields.getOrNull(columns.usernameIndex).orEmpty().trim()
            val notes = columns.notesIndex
                ?.let { fields.getOrNull(it).orEmpty().trim() }
                ?.ifBlank { null }

            val serviceName = resolveServiceName(rawServiceName, serviceUrl, source)
            BackupCredential(
                serviceName = serviceName,
                serviceUrl = serviceUrl,
                username = username,
                password = password,
                notes = notes,
                category = "other",
                createdAt = now,
                updatedAt = now
            )
        }
    }

    /**
     * ヘッダー行を解析し、各カラムのインデックスを返す。
     *
     * カラム名の比較は case-insensitive + スペース/アンダースコア無視で行う。
     */
    internal fun resolveColumnIndices(
        headerFields: List<String>,
        mapping: CsvColumnMapping
    ): ResolvedColumns {
        val normalizedHeaders = headerFields.map(::normalizeColumnName)

        fun findIndex(columnName: String?): Int? {
            if (columnName == null) {
                return null
            }
            val normalized = normalizeColumnName(columnName)
            return normalizedHeaders.indexOfFirst { it == normalized }
                .takeIf { it >= 0 }
        }

        fun requireIndex(columnName: String): Int {
            return findIndex(columnName)
                ?: throw IllegalArgumentException(INVALID_FORMAT_ERROR)
        }

        return ResolvedColumns(
            serviceNameIndex = requireIndex(mapping.serviceName),
            serviceUrlIndex = findIndex(mapping.serviceUrl),
            usernameIndex = requireIndex(mapping.username),
            passwordIndex = requireIndex(mapping.password),
            notesIndex = findIndex(mapping.notes)
        )
    }

    /**
     * CSV 1 行をフィールドリストへパースする。
     */
    internal fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !inQuotes -> {
                    inQuotes = true
                }

                ch == '"' && inQuotes -> {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                }

                ch == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }

                else -> current.append(ch)
            }
            i++
        }
        fields.add(current.toString())

        return fields
    }

    private fun splitCsvRows(csvContent: String): List<String> {
        val rows = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < csvContent.length) {
            val ch = csvContent[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < csvContent.length && csvContent[i + 1] == '"') {
                        current.append('"')
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                        current.append(ch)
                    }
                }

                (ch == '\n' || ch == '\r') && !inQuotes -> {
                    rows.add(current.toString())
                    current.clear()
                    if (ch == '\r' && i + 1 < csvContent.length && csvContent[i + 1] == '\n') {
                        i++
                    }
                }

                else -> current.append(ch)
            }
            i++
        }

        if (current.isNotEmpty()) {
            rows.add(current.toString())
        }

        return rows
    }

    private fun normalizeColumnName(columnName: String): String {
        return columnName
            .trim()
            .removePrefix("\uFEFF")
            .removeSurrounding("\"")
            .lowercase()
            .replace(" ", "")
            .replace("_", "")
    }

    private fun debugLog(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun resolveServiceName(
        rawServiceName: String,
        serviceUrl: String?,
        source: ImportSource
    ): String {
        if (source == ImportSource.FIREFOX) {
            val candidate = serviceUrl ?: rawServiceName
            return extractHostOrFallback(candidate)
        }

        if (rawServiceName.isNotBlank()) {
            return rawServiceName
        }

        if (!serviceUrl.isNullOrBlank()) {
            return extractHostOrFallback(serviceUrl)
        }

        return "unknown"
    }

    private fun extractHostOrFallback(url: String): String {
        return runCatching {
            URI(url).host?.takeIf { it.isNotBlank() } ?: url
        }.getOrDefault(url)
    }
}

/**
 * CSV ヘッダーから解決した各項目の列インデックス。
 */
internal data class ResolvedColumns(
    val serviceNameIndex: Int,
    val serviceUrlIndex: Int?,
    val usernameIndex: Int,
    val passwordIndex: Int,
    val notesIndex: Int?
)
