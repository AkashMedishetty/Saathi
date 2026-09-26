package com.saathi.app.llm

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The shared text brain (Gemma 4 on the GPU), as the app sees it. The model itself runs in the ":brain" process
 * ([LocalLlm] inside [BrainService]); this is a thin, crash-proof client with the same API as before.
 */
object LlmManager {
    sealed interface State {
        data object Idle : State
        data object NoModel : State
        data class Loading(val what: String) : State
        data class Ready(val label: String, val backend: String, val loadMs: Long) : State
        data class Failed(val msg: String) : State

        companion object {
            private const val SEP = "\u001f"
            fun encode(s: State): String = when (s) {
                Idle -> "idle"
                NoModel -> "nomodel"
                is Loading -> "loading$SEP${s.what}"
                is Ready -> "ready$SEP${s.label}$SEP${s.backend}$SEP${s.loadMs}"
                is Failed -> "failed$SEP${s.msg}"
            }
            fun decode(raw: String): State {
                val p = raw.split(SEP)
                return when (p[0]) {
                    "nomodel" -> NoModel
                    "loading" -> Loading(p.getOrElse(1) { "" })
                    "ready" -> Ready(p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, p.getOrNull(3)?.toLongOrNull() ?: 0)
                    "failed" -> Failed(p.getOrElse(1) { "" })
                    else -> Idle
                }
            }
        }
    }

    /** `state.value` asks the brain process (cheap); Idle when it isn't connected. */
    class StateView internal constructor() { val value: State get() = State.decode(Brain.sync("idle") { it.state() }) }
    val state = StateView()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isReady get() = state.value is State.Ready
    val label get() = (state.value as? State.Ready)?.label
    val lastGenMs get() = Brain.sync(0L) { it.lastGenMs() }

    fun loadAsync(ctx: Context) { Brain.connect(ctx); scope.launch { Brain.call(Unit) { it.load() } } }

    /** Starts loading and waits (up to 40 s) until it's ready or has failed. */
    suspend fun load(ctx: Context) {
        Brain.connect(ctx)
        Brain.call(Unit) { it.load() }
        var waited = 0
        while (waited < 40_000) {
            val s = state.value
            if (s is State.Ready || s is State.Failed || s is State.NoModel) return
            delay(200); waited += 200
        }
    }

    /** Cleaned model text, or null when no model is loaded / the brain isn't there. Never throws. */
    suspend fun generate(system: String, user: String): String? = Brain.call(null) { it.generate(system, user) }

    /** A turn in the task's ongoing conversation (the brain keeps the conversation's memory). */
    suspend fun chat(key: String, system: String, user: String): String? = Brain.call(null) { it.chat(key, system, user) }

    /** Is [key] the conversation currently alive (so we only send what's new)? */
    fun inChat(key: String) = lastChatKey == key && Brain.sync(0) { it.turns() } in 1..9
    @Volatile var lastChatKey: String? = null

    fun endChat() { lastChatKey = null; Brain.sync(Unit) { it.endChat() } }

    /** Frees the GPU brain (off the caller's thread: closing takes ~1 s). */
    fun unload() { scope.launch { Brain.sync(Unit) { it.unload() } } }
}
