package com.securevault.app

import android.app.Application
import com.securevault.app.data.db.SecureVaultDatabase
import com.securevault.app.util.AutoLockManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SecureVaultApplication : Application() {

	@Inject
	lateinit var autoLockManager: AutoLockManager

	@Inject
	lateinit var database: SecureVaultDatabase

	override fun onCreate() {
		super.onCreate()
		System.loadLibrary("sqlcipher")
		autoLockManager.start()
		// Hilt の Singleton 初期化を早めるために DB 参照を保持する。
		database
	}
}
