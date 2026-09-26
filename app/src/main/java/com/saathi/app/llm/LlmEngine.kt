package com.saathi.app.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One loaded on-device model. [label] is what the UI shows, e.g. "LiteRT-LM · NPU · FastVLM-0.5B". */
interface LlmEngine {
    val label: String
    val backend: String
    fun generate(system: String, user: String): String
    /** Multi-turn: same [key] = same conversation (the model keeps its memory of earlier turns). Default: stateless. */
    fun chat(key: String, system: String, user: String): String = generate(system, user)
    fun endChat() {}
    fun close()
}

/** LiteRT-LM (.litertlm). The only runtime that reaches the Hexagon NPU. */
class LiteRtEngine(ctx: Context, file: File, override val backend: String, vision: Boolean = false) : LlmEngine {
    override val label = "LiteRT-LM · $backend · ${file.nameWithoutExtension.substringBefore('.').take(24)}"
    private val engine: Engine

    init {
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        fun make(b: String) = when (b) {
            "NPU" -> Backend.NPU(nativeDir)
            "GPU" -> Backend.GPU()
            else -> Backend.CPU()
        }
        engine = Engine(
            EngineConfig(
                modelPath = file.absolutePath,
                backend = make(backend),
                visionBackend = if (vision) make(backend) else null,
                maxNumImages = if (vision) 1 else null,
                cacheDir = ctx.cacheDir.absolutePath,
            )
        )
        engine.initialize()
    }

    override fun generate(system: String, user: String): String = ask(Contents.of(user), system)

    /** Image + prompt, for vision models (FastVLM). */
    fun describe(jpeg: ByteArray, prompt: String): String =
        ask(Contents.of(Content.ImageBytes(jpeg), Content.Text(prompt)), null)

    private fun ask(msg: Contents, system: String?): String {
        endChat() // one conversation at a time on this runtime
        val cfg = ConversationConfig(
            systemInstruction = system?.let { Contents.of(it) },
            samplerConfig = SamplerConfig(topK = 1, topP = 0.95, temperature = 0.1, seed = 7),
        )
        engine.createConversation(cfg).use { conv ->
            val reply = conv.sendMessage(msg)
            return reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
    }

    // ── One live conversation per task: the KV cache keeps the goal and every earlier step. ──
    private var chatKey: String? = null
    private var chatConv: com.google.ai.edge.litertlm.Conversation? = null
    private var chatTurns = 0

    override fun chat(key: String, system: String, user: String): String {
        if (key != chatKey || chatConv == null || chatTurns >= 10) {
            endChat()
            chatConv = engine.createConversation(ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 1, topP = 0.95, temperature = 0.1, seed = 7),
            ))
            chatKey = key; chatTurns = 0
        }
        chatTurns++
        val reply = chatConv!!.sendMessage(Contents.of(user))
        return reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
    }

    val turns get() = chatTurns

    override fun endChat() { runCatching { chatConv?.close() }; chatConv = null; chatKey = null; chatTurns = 0 }

    override fun close() { endChat(); engine.close() }
}

/** MediaPipe LLM Inference (.task). Guaranteed GPU/CPU path; applies chat templates itself (trap #3). */
class MediaPipeEngine(ctx: Context, private val file: File, gpu: Boolean) : LlmEngine {
    override val backend = if (gpu) "GPU" else "CPU"
    override val label = "MediaPipe · $backend · ${file.nameWithoutExtension.take(24)}"
    private val llm = LlmInference.createFromOptions(
        ctx,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(1280)
            .setMaxTopK(40)
            .setPreferredBackend(if (gpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU)
            .build(),
    )

    /** Greedy, streamed, cut off as soon as the answer is complete (small models ramble, trap #4). */
    override fun generate(system: String, user: String): String {
        val opts = LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(1).setTemperature(0f).build()
        val session = LlmInferenceSession.createFromOptions(llm, opts)
        try {
            session.addQueryChunk(Templates.wrap(file.name, system, user))
            val sb = StringBuilder()
            val stop = CountDownLatch(1)
            val finished = AtomicBoolean(false)
            val future = session.generateResponseAsync { partial, done ->
                val text = synchronized(sb) { sb.append(partial); sb.toString() }
                if (done) finished.set(true)
                if (done || Templates.enough(text)) stop.countDown()
            }
            stop.await(60, TimeUnit.SECONDS)
            if (!finished.get()) runCatching { session.cancelGenerateResponseAsync() }
            runCatching { future.get(3, TimeUnit.SECONDS) }
            return synchronized(sb) { sb.toString() }
        } finally {
            runCatching { session.close() }
        }
    }

    override fun close() = llm.close()
}

object Templates {
    fun wrap(fileName: String, system: String, user: String): String {
        val n = fileName.lowercase()
        return when {
            "qwen" in n -> "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
            "gemma" in n -> "<start_of_turn>user\n$system\n\n$user<end_of_turn>\n<start_of_turn>model\n"
            else -> "$system\n\n$user\n\nAnswer:\n"
        }
    }

    /** Enough to act on: a finished SAY line, 3 non-blank lines, or ~300 chars. */
    fun enough(s: String): Boolean {
        if (s.length > 300) return true
        val afterSay = s.substringAfter("SAY:", "")
        if (afterSay.isNotBlank() && afterSay.trimStart().contains('\n')) return true
        return s.lines().count { it.isNotBlank() } >= 3
    }

    /** Byte-level BPE leaking through means the runtime mangled non-ASCII text (trap #5). */
    fun garbled(s: String) = Regex("Ġ|Ċ|à[°±¤¥]").containsMatchIn(s)

    fun clean(raw: String) = raw
        .replace(Regex("<\\|im_end\\|>|<\\|im_start\\|>|<end_of_turn>|<start_of_turn>|</?think>"), "")
        .trim()
}

/** Where models live. Push with scripts/push-model.sh. */
object ModelLocator {
    fun dirs(ctx: Context): List<File> = listOfNotNull(
        ctx.getExternalFilesDir("models"),
        File("/data/local/tmp/llm"),
        File(ctx.filesDir, "models"),
    )

    /** Text brains, best first. Vision models are excluded. */
    fun text(ctx: Context): List<File> = all(ctx).filterNot(::isVision).sortedByDescending(::score)

    /** The vision model (FastVLM compiled for this chip's NPU). */
    fun vision(ctx: Context): File? = all(ctx).filter(::isVision).maxByOrNull { if ("sm8850" in it.name.lowercase()) 1 else 0 }

    private fun all(ctx: Context) = dirs(ctx)
        .flatMap { it.listFiles()?.toList().orEmpty() }
        .filter { it.isFile && it.canRead() && (it.name.endsWith(".litertlm") || it.name.endsWith(".task")) }
        .distinctBy { it.name }

    private fun isVision(f: File) = Regex("vlm|vision|paligemma", RegexOption.IGNORE_CASE).containsMatchIn(f.name)

    private fun score(f: File): Long {
        val n = f.name.lowercase()
        var s = 0L
        if (n.endsWith(".litertlm")) s += 1_000
        if ("sm8850" in n) s += 5_000
        if ("gemma-4" in n || "gemma4" in n) s += 600
        if ("gemma" in n) s += 300
        if ("1.5b" in n || "1b" in n) s += 100
        return s * 100 + f.length() / 100_000_000
    }
}
