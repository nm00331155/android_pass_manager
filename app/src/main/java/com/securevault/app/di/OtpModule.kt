package com.securevault.app.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * OTP 関連コンポーネントの DI モジュール。
 * @Inject constructor による自動提供を利用するため、明示バインディングは不要。
 */
@Module
@InstallIn(SingletonComponent::class)
object OtpModule
