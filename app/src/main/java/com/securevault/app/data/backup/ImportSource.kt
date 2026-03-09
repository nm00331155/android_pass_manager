package com.securevault.app.data.backup

/**
 * CSV インポート元サービスの定義。
 *
 * 各サービスごとに、CSV ヘッダー名のマッピング情報を保持する。
 */
enum class ImportSource(
    val displayName: String,
    val fileExtension: String,
    val columnMapping: CsvColumnMapping
) {
    BRAVE(
        displayName = "Brave",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "name",
            serviceUrl = "url",
            username = "username",
            password = "password",
            notes = "note"
        )
    ),
    CHROME(
        displayName = "Google Chrome",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "name",
            serviceUrl = "url",
            username = "username",
            password = "password",
            notes = "note"
        )
    ),
    EDGE(
        displayName = "Microsoft Edge",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "name",
            serviceUrl = "url",
            username = "username",
            password = "password",
            notes = "note"
        )
    ),
    FIREFOX(
        displayName = "Firefox",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "url",
            serviceUrl = "url",
            username = "username",
            password = "password",
            notes = null
        )
    ),
    ONE_PASSWORD(
        displayName = "1Password",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "Title",
            serviceUrl = "Website",
            username = "Username",
            password = "Password",
            notes = "Notes"
        )
    ),
    BITWARDEN(
        displayName = "Bitwarden",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "name",
            serviceUrl = "login_uri",
            username = "login_username",
            password = "login_password",
            notes = "notes"
        )
    ),
    LASTPASS(
        displayName = "LastPass",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "name",
            serviceUrl = "url",
            username = "username",
            password = "password",
            notes = "extra"
        )
    ),
    DASHLANE(
        displayName = "Dashlane",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "title",
            serviceUrl = "url",
            username = "username",
            password = "password",
            notes = "note"
        )
    ),
    APPLE_PASSWORDS(
        displayName = "Apple パスワード（iCloud キーチェーン）",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "Title",
            serviceUrl = "URL",
            username = "Username",
            password = "Password",
            notes = "Notes"
        )
    ),
    KEEPASS(
        displayName = "KeePass / KeePassXC",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "Title",
            serviceUrl = "URL",
            username = "Username",
            password = "Password",
            notes = "Notes"
        )
    ),
    SECUREVAULT(
        displayName = "KeyPass CSV",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "serviceName",
            serviceUrl = "serviceUrl",
            username = "username",
            password = "password",
            notes = "notes",
            category = "category",
            credentialType = "credentialType",
            cardholderName = "cardholderName",
            cardNumber = "cardNumber",
            expirationMonth = "expirationMonth",
            expirationYear = "expirationYear",
            securityCode = "securityCode"
        )
    )
}

/**
 * CSV ヘッダー名と SecureVault の項目の対応を表す。
 */
data class CsvColumnMapping(
    val serviceName: String,
    val serviceUrl: String?,
    val username: String,
    val password: String,
    val notes: String?,
    val category: String? = null,
    val credentialType: String? = null,
    val cardholderName: String? = null,
    val cardNumber: String? = null,
    val expirationMonth: String? = null,
    val expirationYear: String? = null,
    val securityCode: String? = null
)
