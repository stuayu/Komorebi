package com.beeregg2001.komorebi.data.api.interceptor

import com.beeregg2001.komorebi.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloudflare Zero Trust (Cloudflare Access) のサービストークンを
 * リクエストヘッダーに付与するインターセプター。
 *
 * トークンが未設定の場合は何もしない。
 * 認証情報の漏洩を防ぐため、設定された KonomiTV サーバーのホスト宛の
 * リクエストにのみヘッダーを付与する。
 */
@Singleton
class CloudflareAccessInterceptor @Inject constructor(
    private val settingsRepository: SettingsRepository
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val headers = runBlocking { settingsRepository.getCfAccessHeaders() }
        if (headers.isEmpty()) return chain.proceed(request)

        val baseHost = runBlocking {
            settingsRepository.getBaseUrl().toHttpUrlOrNull()?.host
        }
        if (baseHost == null || request.url.host != baseHost) {
            return chain.proceed(request)
        }

        val newRequest = request.newBuilder().apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        return chain.proceed(newRequest)
    }
}
