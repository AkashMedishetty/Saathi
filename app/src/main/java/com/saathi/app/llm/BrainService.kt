package com.saathi.app.llm

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.runBlocking

/**
 * The models' own process (":brain"). Gemma 4 on the GPU + Gemma 3 1B and FastVLM on the NPU together take ~4.7 GB;
 * when the accessibility service's process was that big, vivo removed Saathi from the enabled accessibility services
 * the moment another app came to the front (trap #44). Here, the worst case is that the brain is killed and reloads
 * (~5 s), while Saathi keeps guiding. A native crash in a model also can't take the accessibility service down.
 */
class BrainService : Service() {
    override fun onCreate() { super.onCreate(); com.saathi.app.DebugLog.init(this) }

    private val binder = object : IBrain.Stub() {
        override fun state() = LlmManager.State.encode(LocalLlm.state.value)
        override fun load() = LocalLlm.loadAsync(applicationContext)
        override fun unload() = LocalLlm.unload()
        override fun generate(system: String, user: String): String? = runBlocking { LocalLlm.generate(system, user) }
        override fun chat(key: String, system: String, user: String): String? = runBlocking { LocalLlm.chat(key, system, user) }
        override fun turns() = LocalLlm.turns()
        override fun endChat() = LocalLlm.endChat()
        override fun lastGenMs() = LocalLlm.lastGenMs
        override fun fastReady() = LocalFast.isReady
        override fun fast(system: String, user: String): String? = runBlocking { LocalFast.generate(applicationContext, system, user) }
        override fun vision(jpeg: ByteArray, prompt: String): String? = runBlocking { LocalVision.describe(applicationContext, jpeg, prompt) }
        override fun visionInfo() = LocalVision.label?.let { "$it\u001f${LocalVision.lastMs}" } ?: ""
        override fun unloadVision() = LocalVision.unload()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE) { LocalVision.unload(); LocalFast.unload() }
    }
}
