package com.saathi.app.maps

import com.saathi.app.guide.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Consistency of the whole registry: typos in a screen id or a missing translation fail here, not on stage. */
class RegistryTest {
    private val routes = AppMaps.all.flatMap { m -> m.routes.map { m to it } }

    @Test fun idsAreUnique() {
        val ids = routes.map { it.second.id }
        assertEquals(ids.groupBy { it }.filterValues { it.size > 1 }.keys.toString(), ids.size, ids.toSet().size)
        AppMaps.all.forEach { m ->
            val s = m.screens.map { it.id }
            assertEquals("${m.name} screens", s.size, s.toSet().size)
        }
        val pkgs = AppMaps.all.flatMap { listOf(it.pkg) + it.alsoPkgs }
        assertEquals("a package is claimed by two maps", pkgs.size, pkgs.toSet().size)
    }

    @Test fun everyStepPointsAtARealScreenOfItsApp() {
        val bad = mutableListOf<String>()
        for ((m, r) in routes) {
            assertTrue("${r.id} has steps", r.steps.isNotEmpty())
            for ((i, st) in r.steps.withIndex()) {
                val app = st.pkg?.let { AppMaps.mapFor(it) } ?: m
                val known = app.screens.map { it.id }.toSet()
                (listOf(st.on) + st.alsoOn).filter { it !in known }.forEach { bad += "${r.id}[$i] → $it" }
                assertTrue("${r.id}[$i] has a target", st.target.isNotEmpty())
                st.needsReached?.let { assertTrue("${r.id}[$i] needsReached < its index", it < i) }
                st.fill?.let { assertTrue("${r.id}[$i] fills an undeclared slot $it", it in r.slots) }
            }
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }

    @Test fun everySentenceExistsInAllThreeLanguages() {
        val bad = mutableListOf<String>()
        fun chk(where: String, s: Map<Lang, String>?) {
            s ?: return
            Lang.entries.forEach { l -> if (s[l].isNullOrBlank()) bad += "$where $l" }
            // Hindi must be Devanagari and Telugu must be Telugu script (no transliteration)
            if (s[Lang.HI]?.none { it in '\u0900'..'\u097F' } == true) bad += "$where HI not Devanagari"
            if (s[Lang.TE]?.none { it in '\u0C00'..'\u0C7F' } == true && s[Lang.TE] != s[Lang.EN]) bad += "$where TE not Telugu"
        }
        for ((m, r) in routes) {
            chk("${r.id}.doneSay", r.doneSay)
            r.next.forEach { chk("${r.id}.next", it) }
            r.steps.forEachIndexed { i, st -> chk("${r.id}[$i].say", st.say); chk("${r.id}[$i].why", st.why); chk("${r.id}[$i].scroll", st.scrollHint) }
            chk("${m.name}.back", m.backHint)
        }
        // "Done." / "CVV"-style words can be the same in all scripts; allow exact-English Telugu only for those.
        assertEquals(bad.joinToString("\n"), 0, bad.filterNot { it.endsWith("TE not Telugu") && it.contains("doneSay") }.size)
    }

    @Test fun speechPlaceholdersAreDeclaredSlots() {
        val slot = Regex("\\{(\\w+)\\}")
        val bad = mutableListOf<String>()
        for ((_, r) in routes) {
            val says = r.steps.flatMap { listOfNotNull(it.say, it.why, it.scrollHint) } + r.doneSay + r.next
            says.flatMap { it.values }.flatMap { v -> slot.findAll(v).map { it.groupValues[1] } }.toSet()
                .filter { it !in r.slots && it !in r.presets }.forEach { bad += "${r.id} uses {$it}" }
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }

    @Test fun riskyStepsAreNeverFillSteps() {
        for ((_, r) in routes) r.steps.forEach { assertTrue("${r.id}: a risky step can't also type", !(it.risky && it.fill != null)) }
    }
}
