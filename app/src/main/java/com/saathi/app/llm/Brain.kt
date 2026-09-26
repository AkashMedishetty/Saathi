package com.saathi.app.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The app's connection to the ":brain" process (BrainService). Every call is guarded: if the brain process was killed
 * (vivo, low memory, a native crash), calls return the default and Android restarts it; the models reload on next use.
 */
object Brain {
    @Volatile private var remote: IBrain? = null
    @Volatile private var app: Context? = null
    @Volatile private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            remote = IBrain.Stub.asInterface(b)
            com.saathi.app.DebugLog.i("brain", "connected to the brain process")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            com.saathi.app.DebugLog.i("brain", "brain process gone; Android restarts it and the models reload on next use")
        }
        override fun onBindingDied(name: ComponentName?) {
            remote = null; bound = false
            app?.let { runCatching { it.unbindService(this) }; bind(it) }
        }
    }

    fun connect(ctx: Context) { if (!bound) bind(ctx.applicationContext) }

    private fun bind(c: Context) {
        app = c
        bound = runCatching { c.bindService(Intent(c, BrainService::class.java), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
    }

    /** Quick calls (state checks): never block on a missing brain. */
    fun <T> sync(default: T, f: (IBrain) -> T): T = remote?.let { r -> runCatching { f(r) }.getOrDefault(default) } ?: default

    /** Model calls, off the main thread; waits briefly for the brain process to (re)connect. */
    suspend fun <T> call(default: T, f: (IBrain) -> T): T = withContext(Dispatchers.IO) {
        app?.let { connect(it) }
        val until = SystemClock.elapsedRealtime() + 4000
        while (remote == null && app != null && SystemClock.elapsedRealtime() < until) delay(50)
        remote?.let { r -> runCatching { f(r) }.getOrDefault(default) } ?: default
    }
}
