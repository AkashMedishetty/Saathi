package com.saathi.app.guide

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RecipesTest {
    private val pkg = "com.example.notes"
    @Before fun before() { Recipes.cancelRecording() }
    @After fun after() { Recipes.cancelRecording() }
    private fun recipe(name: String, label: String = "New note") =
        Recipes.Recipe(name, pkg, listOf(Recipes.Tap(label, "button", pkg)), 1L)
    private fun recorded() = Recipes.finishRecording(123L)

    @Test fun debouncedTitleAndBodyKeepOnlyLastValuesInOrder() {
        Recipes.startRecording("take notes")
        Recipes.onClick("New note", "button", pkg)
        Recipes.onClick("Title", "input", pkg)
        Recipes.onText("Title", "Shop", pkg)
        Recipes.onText("Title", "Shopping", pkg)
        Recipes.onClick("Body", "input", pkg)
        Recipes.onText("Body", "Milk", pkg)
        Recipes.onText("Body", "Milk\nBread", pkg)
        Recipes.onClick("Title", "input", pkg)
        Recipes.onText(" title ", "Shopping list", pkg)
        Recipes.onClick("Save", "button", pkg)
        val r = recorded()!!
        assertEquals(listOf("New note", "title", "Body", "Save"), r.taps.map { it.label })
        assertEquals(listOf(null, "Shopping list", "Milk\nBread", null), r.taps.map { it.fill })
        assertEquals(listOf("button", "input", "input", "button"), r.taps.map { it.role })
        assertEquals(123L, r.at)
        val flow = Recipes.toFlow(r)
        assertEquals("Shopping list", flow.steps[1].fill)
        assertEquals("Milk\nBread", flow.steps[2].fill)
        assertEquals("input", flow.steps[1].role)
        assertNull(flow.steps[0].fill)
        assertTrue(flow.teach)
        assertNull(recorded())
    }

    @Test fun sameLabelInDifferentAppsIsNotMerged() {
        Recipes.startRecording("take notes")
        Recipes.onText("Title", "First app", pkg)
        Recipes.onText("Title", "Second app", "com.other.notes")
        assertEquals(listOf("First app", "Second app"), recorded()!!.taps.map { it.fill })
    }

    @Test fun nothingIsRecordedOutsideSessionOrAfterCancel() {
        Recipes.onText("Title", "outside", pkg)
        assertNull(recorded())
        Recipes.startRecording("notes")
        Recipes.onText("Title", "before cancel", pkg)
        Recipes.cancelRecording()
        Recipes.onText("Title", "after cancel", pkg)
        assertNull(recorded())
        Recipes.startRecording("fresh")
        assertNull(recorded())
    }

    @Test fun clearingAFieldRemovesItsPreviousFill() {
        Recipes.startRecording("notes")
        Recipes.onText("Title", "remove this", pkg)
        Recipes.onText("Title", "", pkg)
        assertNull(recorded())
    }

    @Test fun passwordFlagBlocksTextEvenWithInnocentLabelAndPurgesPreviousValue() {
        Recipes.startRecording("notes")
        Recipes.onText("Details", "safe draft", pkg)
        Recipes.onText("Details", "NeverStoreThisSecret!", pkg, isPassword = true)
        assertNull(recorded())
    }

    @Test fun sensitiveFieldLabelsNeverStoreTextInAnySupportedLanguage() {
        for (label in listOf("Password", "PIN", "OTP", "Card number", "Aadhaar", "PAN", "Bank account number",
            "पासवर्ड", "ओटीपी", "पिन", "आधार", "పాస్‌వర్డ్", "ఓటీపీ", "పిన్", "ఆధార్")) {
            Recipes.startRecording("notes")
            Recipes.onClick(label, "input", pkg)
            Recipes.onText(label, "NeverStoreThisSecret!", pkg)
            assertNull(label, recorded())
        }
    }

    @Test fun sensitiveValuesNeverStoreAndRemoveAnyEarlierPartialValue() {
        for (secret in listOf("1", "12", "1234", "123456", "1234 5678 9012", "4111-1111-1111-1111",
            "ABCDE1234F", "code 123456", "code 1 2 3 4", "१२३४५६", "౧౨౩౪౫౬", "My password is hunter2",
            "OTP: 123456", "ओटीपी १२३४५६", "ఓటీపీ ౧౨౩౪౫౬")) {
            Recipes.startRecording("notes")
            Recipes.onText("Body", "prior partial draft", pkg)
            Recipes.onText("Body", secret, pkg)
            assertNull(secret, recorded())
        }
    }

    @Test fun ordinaryNoteTextSurvivesInEnglishHindiAndTelugu() {
        for (text in listOf("Milk and bread", "दूध और रोटी", "పాలు మరియు రొట్టె", "Buy 2 apples", "Call at 6 pm")) {
            Recipes.startRecording("notes")
            Recipes.onText("Body", text, pkg)
            assertEquals(text, recorded()!!.taps.single().fill)
        }
    }

    @Test fun unknownLabelsAndSystemAppsDoNotRecordInput() {
        Recipes.startRecording("notes")
        for (label in listOf(null, "", " ", "x".repeat(61))) Recipes.onText(label, "text", pkg)
        for (app in listOf("", "com.saathi.app", "com.android.systemui", "com.bbk.launcher", "com.phonepe.app"))
            Recipes.onText("Title", "text", app)
        assertNull(recorded())
    }

    @Test fun unsafeStoredFillsAreAlsoRejectedBeforeReplay() {
        val r = recipe("notes").copy(taps = listOf(
            Recipes.Tap("Body", "input", pkg, "123456"),
            Recipes.Tap("Password", "input", pkg, "secret"),
            Recipes.Tap("Save", "button", pkg, "Milk"),
            Recipes.Tap("Body", "input", pkg, "Milk and bread")))
        assertEquals(listOf("Milk and bread"), Recipes.toFlow(r).steps.map { it.fill })
    }

    @Test fun oldTapOnlyRecipesStillReplayWithoutFill() {
        val r = recipe("notes")
        assertEquals(r.taps.single(), Recipes.safeStoredTap(r.taps.single()))
        assertNull(Recipes.toFlow(r).steps.single().fill)
    }

    @Test fun exactThenBadgeFreeThenSignificantWordsStayOrdered() {
        val targets = Recipes.toFlow(recipe("chats", "Chats, 3 unread")).steps.single().targets
        assertEquals(3, targets.size)
        assertTrue(targets[0].matches("Chats, 3 unread"))
        assertFalse(targets[0].containsMatchIn("Chats, 4 unread"))
        assertTrue(targets[1].containsMatchIn("Chats"))
        assertTrue(targets[1].containsMatchIn("CHATS, 4 unread"))
        assertTrue(targets[2].containsMatchIn("Open Chats now"))
        assertFalse(targets.any { it.containsMatchIn("Chatsworth") })
    }

    @Test fun countersBracketsAndBadgeSeparatorsCanChange() {
        for (label in listOf("Chats (3)", "Chats [3]", "Chats · 3 unread", "Chats • 3 notifications", "Chats 3 unread",
            "Chats, ३ unread", "Chats, ౩ unread")) {
            val targets = Recipes.toFlow(recipe("chats", label)).steps.single().targets
            assertTrue(label, targets[1].containsMatchIn("Chats"))
        }
    }

    @Test fun significantWordsAreCaseInsensitiveAndNotSubstrings() {
        val targets = Recipes.toFlow(recipe("notes", "New shopping list template")).steps.single().targets
        assertTrue(targets[2].containsMatchIn("Open NEW shopping list today"))
        assertFalse(targets[2].containsMatchIn("renew shopping list"))
        assertFalse(targets[2].containsMatchIn("new shopping listings"))
    }

    @Test fun exactLabelsEscapeRegexAndAreNotTruncated() {
        val label = "C++ [draft] (new). " + "a".repeat(35)
        val exact = Recipes.toFlow(recipe("notes", label)).steps.single().targets.first()
        assertTrue(exact.matches(label))
        assertFalse(exact.matches(label + " other"))
        assertFalse(exact.matches("C draft new"))
    }

    @Test fun unicodeLabelsKeepWholeWordBoundaries() {
        for (label in listOf("नई सूची", "కొత్త నోటు")) {
            val targets = Recipes.toFlow(recipe("notes", label)).steps.single().targets
            assertTrue(targets.last().containsMatchIn("$label today"))
            assertFalse(targets.last().containsMatchIn("x${label}x"))
        }
    }

    @Test fun strippingNumbersDoesNotCreateMatchEverythingTarget() {
        val targets = Recipes.toFlow(recipe("numeric", "123")).steps.single().targets
        assertEquals(1, targets.size)
        assertFalse(targets.any { it.containsMatchIn("anything") })
    }

    @Test fun notesAcceptSmallWordingChanges() {
        for (name in listOf("take notes", "make a note", "notes")) {
            val r = recipe(name)
            for (goal in listOf("take notes", "make a note", "notes", "please write a note", "taking notes"))
                assertEquals("$name / $goal", r, Recipes.find(listOf(r), goal))
            assertNull(Recipes.find(listOf(r), "open notebook"))
            assertNull(Recipes.find(listOf(r), "take photos"))
        }
    }

    @Test fun stemsSupportInflectedMultiwordNames() {
        val r = recipe("open shopping lists")
        assertEquals(r, Recipes.find(listOf(r), "please opening my shopping list"))
        assertNull(Recipes.find(listOf(r), "open list")) // 2/3 < .75
    }

    @Test fun multiwordThresholdIsStillThreeQuarters() {
        val r = recipe("open family shopping lists")
        assertEquals(r, Recipes.find(listOf(r), "open shopping list")) // 3/4
        assertNull(Recipes.find(listOf(r), "family lists")) // 2/4
        assertNull(Recipes.find(listOf(recipe("shopping notes")), "notes"))
    }

    @Test fun mostSpecificMatchingNameWinsTies() {
        val generic = recipe("open youtube")
        val specific = recipe("open youtube subscriptions")
        assertEquals(specific, Recipes.find(listOf(generic, specific), "open my youtube subscriptions"))
        assertNull(Recipes.find(listOf(recipe("the my please")), "please"))
        assertNull(Recipes.find(listOf(specific), ""))
    }
}
