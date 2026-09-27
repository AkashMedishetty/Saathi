package com.saathi.app.llm

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.saathi.app.guide.Lang
import com.saathi.app.guide.Planner
import com.saathi.app.guide.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class ProBrainTest {
    // Gson already exists in the app's transitive test runtime; no dependency/build changes.
    // It substitutes only for Android's stubbed org.json, never for privacy or reply parsing.
    private val gson = Gson()
    private val json = object : ProBrain.JsonCodec {
        override fun encode(value: Map<String, Any?>) = gson.toJson(value)
        override fun decode(text: String): Map<String, Any?> = gson.fromJson(text,
            object : TypeToken<Map<String, Any?>>() {}.type)
    }
    private val cfg = ProConfig("https://openrouter.ai/api/v1", "test-only-key", "provider/model")
    private fun request(c: ProConfig = cfg, app: String = "com.example.editor", screen: String = "[3] button \"Export\"") =
        ProBrain.buildPlanRequest(c, "Edit a video", listOf("Opened project"), listOf("Landscape"), app, screen, "EN", json)
    private fun envelope(text: String, finish: String = "stop") = json.encode(mapOf("choices" to listOf(
        mapOf("finish_reason" to finish, "message" to mapOf("role" to "assistant", "content" to text)))))
    private fun parse(text: String) = ProBrain.parsePlanResponse(envelope(text), json)
    private fun user(r: ProBrain.Request): String {
        val messages = json.decode(r.body)["messages"] as List<*>
        return (messages[1] as Map<*, *>)["content"] as String
    }

    @Test fun providerUrlsHeadersAndPayloadFollowChatContract() {
        for (base in listOf("https://openrouter.ai/api/v1", "https://api.groq.com/openai/v1/",
            "https://api.deepseek.com", "https://api.together.xyz/v1")) {
            val r = request(cfg.copy(baseUrl = base))!!
            assertEquals(base.trimEnd('/') + "/chat/completions", r.url)
            assertEquals("application/json; charset=utf-8", r.headers["Content-Type"])
            assertEquals("application/json", r.headers["Accept"])
            assertFalse(r.headers.containsKey("Authorization"))
            assertFalse(r.toString().contains(cfg.apiKey))
            val body = json.decode(r.body)
            assertEquals("provider/model", body["model"])
            assertEquals(0.2, body["temperature"])
            assertEquals(200.0, body["max_tokens"])
            assertEquals(15000, r.timeoutMs)
            assertTrue(user(r).contains("Edit a video"))
            assertTrue(user(r).contains("Opened project"))
            assertTrue(user(r).contains("Landscape"))
            assertTrue(user(r).contains("[3] button \"Export\""))
            val system = ((body["messages"] as List<*>)[0] as Map<*, *>)["content"] as String
            assertTrue(system.contains("TAP <n> | TYPE <n> <text> | SCROLL | BACK | DONE | ASK"))
            assertTrue(system.contains("<think>...</think>"))
        }
    }

    @Test fun jsonEscapesQuotesBackslashesAndUnicode() {
        val goal = "Edit \"demo\" at C:\\clips\nకొత్త వీడియో"
        val r = ProBrain.buildPlanRequest(cfg, goal, emptyList(), emptyList(), "Editor", "[1] input \"Title\"", "TE", json)!!
        assertTrue(user(r).contains(goal))
        assertTrue(user(r).contains("Language: TE"))
    }

    @Test fun everyFreeTextInputIsRedactedAndNumberedRowsSurvive() {
        val r = ProBrain.buildPlanRequest(cfg, "Call +91 98765 43210", listOf("Email me at person@example.com"),
            listOf("ABCDE1234F"), "Editor", "[3] input \"OTP 123456\"\n[4] text \"1234 5678 9012\"\n[5] button \"Export\"",
            "EN", json)!!
        val text = user(r)
        listOf("98765", "person@example.com", "ABCDE1234F", "123456", "1234 5678 9012").forEach { assertFalse(it, text.contains(it)) }
        assertTrue(text.contains("[3] input \"[PRIVATE]\""))
        assertTrue(text.contains("[4] text"))
        assertTrue(text.contains("[5] button \"Export\""))
    }

    @Test fun masksSecretsPhonesEmailsAndIndicDigits() {
        for (secret in listOf("OTP 123456", "PIN 4321", "4111-1111-1111-1111", "1234 5678 9012", "ABCDE1234F",
            "+1 (415) 555-2671", "9876543210", "person+tag@example.co.in", "१२३४५६", "౧౨౩౪౫౬")) {
            val masked = ProBrain.redact(secret)
            assertFalse(secret, masked.contains(secret))
            assertTrue(secret, masked.contains("[PRIVATE]"))
        }
        assertEquals("Open clip 3 at 4K resolution", ProBrain.redact("Open clip 3 at 4K resolution"))
    }

    @Test fun sshPrivateKeyBlocksNeverLeaveEvenAcrossNumberedRows() {
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----\nprivateBase64Payload\n-----END OPENSSH PRIVATE KEY-----"
        assertEquals("[PRIVATE]", ProBrain.redact(key))
        val screen = "[1] text \"-----BEGIN PRIVATE KEY-----\"\n[2] text \"privateBase64Payload\""
        assertFalse(ProBrain.redactScreen(screen).contains("privateBase64Payload"))
    }

    @Test fun refusesMoneyAppsAndPaymentScreensBeforeSerialization() {
        var calls = 0
        val spy = object : ProBrain.JsonCodec {
            override fun encode(value: Map<String, Any?>): String { calls++; return json.encode(value) }
            override fun decode(text: String) = json.decode(text)
        }
        for (app in listOf("com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user",
            "com.whatsapp.pay", "com.axis.mobile", "com.msf.kbank.mobile", "Google Pay", "BHIM", "PhonePe",
            "com.sbi.SBIFreedomPlus", "com.dreamplug.androidapp", "cris.org.in.prs.ima")) {
            assertNull(app, ProBrain.buildPlanRequest(cfg, "help", emptyList(), emptyList(), app, "[1] button OK", "EN", spy))
        }
        assertEquals(0, calls)
        assertNull(request(screen = "[1] text \"UPI payment gateway\""))
        assertNotNull(request(app = "com.termux"))
        assertNotNull(request(app = "com.example.videoeditor"))
    }

    @Test fun invalidConfigurationsFailWithoutNetwork() = runBlocking {
        for (c in listOf(cfg.copy(apiKey = ""), cfg.copy(apiKey = " \t"), cfg.copy(baseUrl = ""),
            cfg.copy(baseUrl = "not a url"), cfg.copy(baseUrl = "http://example.com"),
            cfg.copy(baseUrl = "https://user:secret@example.com/v1"), cfg.copy(baseUrl = "https://example.com/v1?token=x"),
            cfg.copy(baseUrl = "https://example.com/#fragment"), cfg.copy(apiKey = "key\r\nInjected: true"),
            cfg.copy(model = " "), cfg.copy(timeoutMs = 0))) {
            assertNull(request(c))
            assertNull(ProBrain.plan(c, "goal", emptyList(), emptyList(), "Editor", "[1] button OK", "EN"))
            assertNull(ProBrain.explain(c, "What next?", "App: Editor"))
        }
        assertFalse(cfg.toString().contains("test-only-key"))
    }

    @Test fun stripsThinkingFencesAndLinePrefixes() {
        val raw = "<think>private reasoning\n<think>nested</think>more</think>\n```text\nLine 1: TAP 3\nLine 2: SAY Tap Export.\n```"
        assertEquals("TAP 3\nSAY Tap Export.", parse(raw))
        assertNull(parse("<think>unfinished TAP 3\nSAY Secret."))
        assertNull(parse("</think>TAP 3\nSAY Tap Export."))
        assertEquals("TAP 3\nSAY Tap Export.", parse("```TAP 3\nSAY Tap Export.```"))
        assertNull(parse("TAP 3\nSAY <think unfinished"))
    }

    @Test fun allPlannerActionsHaveExactlyTwoCanonicalLines() {
        for (action in listOf("TAP 3", "TYPE 2 hello world", "SCROLL", "BACK", "DONE", "ASK Which workspace?"))
            assertEquals("$action\nSAY Continue here.", parse("$action\nSAY Continue here."))
        assertEquals("TAP 3\nSAY Tap Export.", parse("tap 3\nsay Tap Export."))
        val screen = Screen("com.example.editor", emptyList(), "")
        for (action in listOf("SCROLL", "BACK", "DONE", "ASK Which workspace?")) {
            val canonical = parse("$action\nSAY Continue here.")!!
            assertNotNull(Planner.parse(canonical, screen, Lang.EN))
        }
    }

    @Test fun invalidPlansAreRejectedInsteadOfGuessing() {
        for (text in listOf("TAP 0\nSAY Tap it.", "TAP +3\nSAY Tap it.", "TAP -1\nSAY Scroll.", "TYPE 2\nSAY Type here.",
            "TYPE 2 [PRIVATE]\nSAY Type here.", "TAP 2 extra\nSAY Tap it.", "BACK now\nSAY Back.",
            "TAP 99999999999999999\nSAY Tap it.", "CLICK 2\nSAY Click.", "TAP 3", "TAP 3\nSAY ",
            "TAP 3\nSAY Tap Export.\nextra", "ASK ?\nSAY Choose.")) assertNull(text, parse(text))
    }

    @Test fun malformedErrorRefusalToolAndTruncatedEnvelopesReturnNull() {
        for (raw in listOf("<html>error</html>", "not json", "{}", "[]", "{\"choices\":[]}",
            "{\"choices\":[{\"message\":{\"content\":null}}]}",
            "{\"choices\":[{\"message\":{\"content\":12}}]}",
            "{\"error\":{\"message\":\"denied\"}}", envelope("TAP 3\nSAY Tap Export.", "length"),
            "{\"choices\":[{\"message\":{\"refusal\":\"no\",\"content\":\"DONE\\nSAY Done.\"}}]}",
            "{\"choices\":[{\"message\":{\"tool_calls\":[],\"content\":\"DONE\\nSAY Done.\"}}]}"))
            assertNull(raw, ProBrain.parsePlanResponse(raw, json))
    }

    @Test fun explanationHasPrivacyAndAppGate() {
        val r = ProBrain.buildExplainRequest(cfg, "What does person@example.com mean?", "App: com.termux\nOTP 123456", json = json)!!
        assertFalse(user(r).contains("person@example.com"))
        assertFalse(user(r).contains("123456"))
        assertNull(ProBrain.buildExplainRequest(cfg, "What next?", "App: PhonePe", json = json))
        assertNull(ProBrain.buildExplainRequest(cfg, "What next?", "Unknown app context", json = json))
        assertNotNull(ProBrain.buildExplainRequest(cfg, "What next?", "A terminal", "com.termux", json))
        val answer = ProBrain.parseExplainResponse(envelope("<think>secret</think>One. Two. Three. Four."), json)
        assertEquals("One. Two. Three.", answer)
        assertEquals("One.Two.Three.", ProBrain.parseExplainResponse(envelope("One.Two.Three.Four."), json))
        assertEquals("एक।दो।तीन।", ProBrain.parseExplainResponse(envelope("एक।दो।तीन।चार।"), json))
    }

    private class FakeConnection(url: URL, val status: Int = 200, val response: String = "{}", val timeout: Boolean = false) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream() = sent
        override fun getResponseCode(): Int { if (timeout) throw SocketTimeoutException("local test"); return status }
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
    }

    @Test fun transportUsesPostTimeoutsUtf8AndDoesNotFollowRedirects() {
        val r = request()!!
        val fake = FakeConnection(URL(r.url), response = envelope("DONE\nSAY Done."))
        assertNotNull(ProBrain.post(r, cfg.apiKey) { fake })
        assertEquals("POST", fake.requestMethod)
        assertEquals(15000, fake.connectTimeout)
        assertEquals(15000, fake.readTimeout)
        assertFalse(fake.instanceFollowRedirects)
        assertTrue(fake.disconnected)
        assertEquals(r.body, fake.sent.toString("UTF-8"))
        assertEquals("Bearer test-only-key", fake.getRequestProperty("Authorization"))
    }

    @Test fun httpErrorsRedirectsTimeoutsAndOversizeResponsesReturnNull() {
        val r = request()!!
        for (code in listOf(301, 302, 400, 401, 429, 500)) {
            val fake = FakeConnection(URL(r.url), status = code)
            assertNull(ProBrain.post(r, cfg.apiKey) { fake })
            assertTrue(fake.disconnected)
        }
        val timeout = FakeConnection(URL(r.url), timeout = true)
        assertNull(ProBrain.post(r, cfg.apiKey) { timeout })
        assertTrue(timeout.disconnected)
        assertNull(ProBrain.post(r, cfg.apiKey) { throw java.io.IOException("local test") })
        assertNull(ProBrain.post(r, cfg.apiKey) { FakeConnection(it, response = "x".repeat(1_048_577)) })
        var called = false
        assertNull(ProBrain.post(r, "") { called = true; FakeConnection(it) })
        assertFalse(called)
    }
}
