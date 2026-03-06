package com.securevault.app

import android.app.Application
import com.securevault.app.util.AutoLockManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SecureVaultApplication : Application() {

	@Inject
	lateinit var autoLockManager: AutoLockManager

	override fun onCreate() {
		super.onCreate()
		autoLockManager.start()
	}
}
