package kr.taeuk.pleos.rentalcheck.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kr.taeuk.pleos.rentalcheck.BuildConfig

/** Provision the vehicle app once. The credential never appears in the phone upload link. */
class ServerSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("cloud_settings", Context.MODE_PRIVATE)
    private val alias = "rental-check-server-token"

    fun load(): ServerConfig {
        val url = preferences.getString("url", "").orEmpty()
        val encrypted = preferences.getString("token", null) ?: return ServerConfig(url)
        return runCatching {
            val parts = encrypted.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            ServerConfig(url, String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
        }.getOrElse { ServerConfig(url) }
    }

    fun save(rawUrl: String, token: String): ServerConfig {
        val url = rawUrl.trim().trimEnd('/')
        val parsed = URI(url)
        require(parsed.host != null && parsed.userInfo == null && parsed.query == null && parsed.fragment == null &&
            parsed.path.orEmpty().isEmpty() && (parsed.scheme == "https" || (BuildConfig.DEBUG && parsed.scheme == "http"))) {
            "HTTPS 서버 주소를 입력해 주세요. 개발 빌드에서는 로컬 HTTP도 사용할 수 있어요."
        }
        require(token.length >= 32 && token.all { it.code in 33..126 }) { "서버 발급 접근 키(32자 이상)를 입력해 주세요." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(preferences.edit().putString("url", url).putString("token", encrypted).commit()) { "서버 설정을 저장하지 못했어요." }
        return ServerConfig(url, token)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
