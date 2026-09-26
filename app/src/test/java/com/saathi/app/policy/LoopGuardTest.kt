package com.saathi.app.policy

import org.junit.Assert.*
import org.junit.Test

class LoopGuardTest {
    @Test fun threeSameActionsStopEvenWithOtherActionsBetween() {
        val g = LoopGuard()
        g.onScreen("a", "pkg")
        g.onAction("tap")
        g.onAction("tap")
        assertEquals(LoopVerdict.Ok, g.verdict())
        g.onAction("scroll")
        g.onAction("tap")
        assertTrue(g.verdict() is LoopVerdict.Repeating)
    }
    @Test fun changedScreenResetsActionCounts() {
        val g = LoopGuard()
        g.onScreen("a", "pkg"); repeat(2) { g.onAction("tap") }
        g.onScreen("b", "pkg"); g.onAction("tap")
        assertEquals(LoopVerdict.Ok, g.verdict())
    }
    @Test fun sameFingerprintDifferentPackageIsProgress() {
        val g = LoopGuard()
        g.onScreen("a", "one"); repeat(2) { g.onAction("tap") }
        g.onScreen("a", "two"); g.onAction("tap")
        assertEquals(LoopVerdict.Ok, g.verdict())
    }
    @Test fun pingPongStopsOnFourthTransitionAndLatches() {
        val g = LoopGuard()
        listOf("a", "b", "a").forEach { g.onScreen(it, "pkg") }
        assertEquals(LoopVerdict.Ok, g.verdict())
        g.onScreen("b", "pkg")
        assertTrue(g.verdict() is LoopVerdict.Repeating)
        g.onScreen("c", "pkg")
        assertTrue(g.verdict() is LoopVerdict.Repeating)
    }
    @Test fun duplicatesDoNotHidePingPong() {
        val g = LoopGuard()
        listOf("a", "a", "b", "b", "a", "b").forEach { g.onScreen(it, "pkg") }
        assertTrue(g.verdict() is LoopVerdict.Repeating)
    }
    @Test fun fortySecondsBoundaryAndNoProgressOnActionOrDuplicate() {
        var t = 0L
        val g = LoopGuard(nowMs = { t })
        g.onScreen("a", "pkg")
        t = 39_999; g.onScreen("a", "pkg"); g.onAction("tap")
        assertEquals(LoopVerdict.Ok, g.verdict())
        t = 40_000
        assertTrue(g.verdict() is LoopVerdict.Stuck)
    }
    @Test fun progressResetsTimer() {
        var t = 0L
        val g = LoopGuard(nowMs = { t })
        g.onScreen("a", "pkg"); t = 39_999; g.onScreen("b", "pkg")
        t = 40_000
        assertEquals(LoopVerdict.Ok, g.verdict())
        t = 79_999
        assertTrue(g.verdict() is LoopVerdict.Stuck)
    }
    @Test fun budgetBoundaryAndPrecedence() {
        val g = LoopGuard(3)
        repeat(2) { g.onAction("tap") }
        assertEquals(LoopVerdict.Ok, g.verdict())
        g.onAction("tap")
        assertTrue(g.verdict() is LoopVerdict.OutOfSteps)
        g.onScreen("b", "p")
        assertTrue(g.verdict() is LoopVerdict.OutOfSteps)
    }
    @Test fun independentTasksStartFresh() {
        val old = LoopGuard(1); old.onAction("tap")
        assertTrue(old.verdict() is LoopVerdict.OutOfSteps)
        assertEquals(LoopVerdict.Ok, LoopGuard().verdict())
    }
    @Test(expected = IllegalArgumentException::class) fun invalidBudget() { LoopGuard(0) }
    @Test fun allMessagesHaveThreeLanguages() {
        val g = LoopGuard(1); g.onAction("tap")
        assertEquals(3, (g.verdict() as LoopVerdict.OutOfSteps).say.size)
        var t = 0L; val s = LoopGuard(nowMs = { t }); t = 40_000
        assertEquals(3, (s.verdict() as LoopVerdict.Stuck).say.size)
        val r = LoopGuard(); repeat(3) { r.onAction("tap") }
        assertEquals(3, (r.verdict() as LoopVerdict.Repeating).say.size)
    }
}
