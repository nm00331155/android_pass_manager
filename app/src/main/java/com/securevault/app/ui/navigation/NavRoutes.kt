package com.securevault.app.ui.navigation

object NavRoutes {
    const val Auth = "auth"
    const val Home = "home"
    const val Settings = "settings"
    const val Generator = "generator"
    const val Backup = "backup"

    const val AddEditPattern = "add_edit/{credentialId}"
    const val DetailPattern = "detail/{credentialId}"

    fun addEdit(credentialId: Long = -1L): String = "add_edit/$credentialId"
    fun detail(credentialId: Long): String = "detail/$credentialId"
}
