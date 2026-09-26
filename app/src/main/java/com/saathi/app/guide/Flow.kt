package com.saathi.app.guide

import android.content.Context
import android.content.Intent

/** One thing to tap. The guide picks the LATEST step whose target is visible, so flows self-correct. */
data class Step(
    val key: String,
    val targets: List<Regex>,
    val say: Say,
    val role: String? = null,
    val fill: String? = null, // what "Do it for me" types into an input
    val tip: Say? = null,     // teach mode: why this matters
    /** Only valid when the screen shows this text too (e.g. a slider step only on the font screen). */
    val screenHas: Regex? = null,
    /** Not valid while one of these is still tappable (we haven't gone that far yet). */
    val unlessVisible: List<Regex> = emptyList(),
)

/** A task: how to start it, its steps, and how we know it's finished. */
class Flow(
    val id: String,
    val launch: ((Context) -> Intent?)?,
    val steps: List<Step>,
    val isDone: ((Screen) -> Boolean)?,
    val doneSay: Say,
    val start: Say,
    val teach: Boolean = false,
    /** Goal handed to the planner when no scripted step matches (OEM screens differ). */
    val llmGoal: String? = null,
    /** Instant skills (torch, volume): do it, then say what was done. */
    val action: ((Context) -> Say)? = null,
    /** Saved to memory on completion, e.g. "Take BP medicine · 8:00 AM daily". */
    val memo: String? = null,
    /** Instant skills that open one of Saathi's own screens: just speak, no card on top of it. */
    val quiet: Boolean = false,
)

fun rx(vararg p: String) = p.map { Regex(it, RegexOption.IGNORE_CASE) }
