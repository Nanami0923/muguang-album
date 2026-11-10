package com.muguang.album.analysis.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.muguang.album.core.AiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials never leave this store through the observable settings model. */
class AiConfiguration(context: Context) {
    private val prefs = context.getSharedPreferences("ai_recognition", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    private fun load() = AiSettings(
        endpoint=prefs.getString("endpoint", "")!!, model=prefs.getString("model", "")!!,
        hasKey=prefs.contains("secret"), enabled=prefs.getBoolean("enabled",false),
        paused=prefs.getBoolean("paused",false), maxImagesPerRun=prefs.getInt("limit",50),
        sent=prefs.getInt("sent",0), tokenParameter=prefs.getString("tokenParameter","max_tokens")!!,
        status=prefs.getString("status","尚未配置")!!,
    )
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("album-ai-v1",null) as? SecretKey) ?: KeyGenerator.getInstance("AES","AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("album-ai-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized fun secret(): String {
        val encoded = prefs.getString("secret",null) ?: error("请填写 API 密钥")
        val pieces = encoded.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(pieces[0],Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(pieces[1],Base64.NO_WRAP)), Charsets.UTF_8)
    }
    @Synchronized fun save(settings: AiSettings, newKey: String?) {
        val endpoint=settings.endpoint.trim(); val model=settings.model.trim()
        if (endpoint.isNotEmpty()) {
            val uri = runCatching { URI(endpoint) }.getOrNull()
            require(uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "请输入完整的 HTTPS 接口地址" }
        }
        require(settings.maxImagesPerRun in 1..10000) { "每轮数量应为 1～10000 张" }
        require(settings.tokenParameter in setOf("max_tokens","max_completion_tokens","none"))
        val edit=prefs.edit()
        if (!newKey.isNullOrBlank()) {
            require(!newKey.contains('\n') && !newKey.contains('\r')) { "密钥不能包含换行" }
            val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key())
            val encrypted=cipher.doFinal(newKey.trim().toByteArray(Charsets.UTF_8))
            edit.putString("secret",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP))
        }
        require(!settings.enabled || (endpoint.isNotBlank() && model.isNotBlank() && (prefs.contains("secret") || !newKey.isNullOrBlank()))) { "启用前请填写地址、密钥和视觉模型名称" }
        edit.putString("endpoint",endpoint).putString("model",model).putBoolean("enabled",settings.enabled)
            .putInt("limit",settings.maxImagesPerRun).putString("tokenParameter",settings.tokenParameter)
            .putString("status",if(settings.enabled) "已启用，等待争议图片" else "AI 已关闭").commit()
        mutable.value=load()
    }
    @Synchronized fun status(message: String, pause: Boolean? = null) {
        val edit=prefs.edit().putString("status",message)
        pause?.let { edit.putBoolean("paused",it) }; edit.commit(); mutable.value=load()
    }
    @Synchronized fun reserve(): Boolean {
        val latest=load()
        if (!latest.enabled || latest.paused || latest.sent >= latest.maxImagesPerRun) return false
        prefs.edit().putInt("sent",latest.sent+1).commit(); mutable.value=load(); return true
    }
    @Synchronized fun newBatch() {
        prefs.edit().putInt("sent",0).putBoolean("paused",false).putString("status","新一轮 AI 识别已开始").commit()
        mutable.value=load()
    }
}
