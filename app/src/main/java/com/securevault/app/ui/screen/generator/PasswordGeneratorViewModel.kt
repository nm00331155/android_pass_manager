package com.securevault.app.ui.screen.generator

import androidx.lifecycle.ViewModel
import com.securevault.app.util.PasswordStrength
import com.securevault.app.util.PasswordStrengthChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import java.security.SecureRandom
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * パスワード生成画面の状態を管理する ViewModel。
 */
@HiltViewModel
class PasswordGeneratorViewModel @Inject constructor() : ViewModel() {

    private val random = SecureRandom()

    private val _length = MutableStateFlow(16)
    val length: StateFlow<Int> = _length.asStateFlow()

    private val _useLowercase = MutableStateFlow(true)
    val useLowercase: StateFlow<Boolean> = _useLowercase.asStateFlow()

    private val _useUppercase = MutableStateFlow(true)
    val useUppercase: StateFlow<Boolean> = _useUppercase.asStateFlow()

    private val _useDigits = MutableStateFlow(true)
    val useDigits: StateFlow<Boolean> = _useDigits.asStateFlow()

    private val _useSymbols = MutableStateFlow(true)
    val useSymbols: StateFlow<Boolean> = _useSymbols.asStateFlow()

    private val _excludeAmbiguous = MutableStateFlow(false)
    val excludeAmbiguous: StateFlow<Boolean> = _excludeAmbiguous.asStateFlow()

    private val _generatedPassword = MutableStateFlow("")
    val generatedPassword: StateFlow<String> = _generatedPassword.asStateFlow()

    private val _strength = MutableStateFlow(PasswordStrength.VERY_WEAK)
    val strength: StateFlow<PasswordStrength> = _strength.asStateFlow()

    init {
        generate()
    }

    /**
     * パスワード長を更新する。
     */
    fun updateLength(value: Int) {
        _length.value = value.coerceIn(8, 64)
        generate()
    }

    /**
     * 小文字利用を更新する。
     */
    fun updateUseLowercase(value: Boolean) {
        _useLowercase.value = value
        generate()
    }

    /**
     * 大文字利用を更新する。
     */
    fun updateUseUppercase(value: Boolean) {
        _useUppercase.value = value
        generate()
    }

    /**
     * 数字利用を更新する。
     */
    fun updateUseDigits(value: Boolean) {
        _useDigits.value = value
        generate()
    }

    /**
     * 記号利用を更新する。
     */
    fun updateUseSymbols(value: Boolean) {
        _useSymbols.value = value
        generate()
    }

    /**
     * 紛らわしい文字除外を更新する。
     */
    fun updateExcludeAmbiguous(value: Boolean) {
        _excludeAmbiguous.value = value
        generate()
    }

    /**
     * 現在設定でパスワードを再生成する。
     */
    fun generate() {
        val pool = buildCharacterPool()
        val generated = buildString {
            repeat(_length.value) {
                append(pool[random.nextInt(pool.length)])
            }
        }

        _generatedPassword.value = generated
        _strength.value = PasswordStrengthChecker.check(generated)
    }

    private fun buildCharacterPool(): String {
        val lowercase = "abcdefghijklmnopqrstuvwxyz"
        val uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val digits = "0123456789"
        val symbols = "!@#$%^&*()-_=+[]{}:;,.?"

        val pool = buildString {
            if (_useLowercase.value) append(lowercase)
            if (_useUppercase.value) append(uppercase)
            if (_useDigits.value) append(digits)
            if (_useSymbols.value) append(symbols)
        }.ifBlank {
            lowercase + uppercase + digits
        }

        if (!_excludeAmbiguous.value) {
            return pool
        }

        val ambiguousChars = setOf('0', 'O', 'l', '1', 'I')
        val filtered = pool.filterNot { it in ambiguousChars }
        return filtered.ifBlank { lowercase + uppercase + digits }
    }
}
