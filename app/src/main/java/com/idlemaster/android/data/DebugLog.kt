package com.idlemaster.android.data

import android.util.Log
import `in`.dragonbra.javasteam.util.log.LogListener
import `in`.dragonbra.javasteam.util.log.LogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app log (the desktop app had Logger.cs writing to a file). Collects JavaSteam's own log
 * and the app's messages so a failed sign-in or idle problem can be copied from the "Журнал" screen.
 */
object DebugLog {
    private const val MAX_LINES = 800
    private val lines = ArrayDeque<String>()
    private val _version = MutableStateFlow(0)
    /** Bumps on every new line; read the content with [snapshot]. */
    val version: StateFlow<Int> = _version.asStateFlow()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun install() {
        LogManager.addListener(object : LogListener {
            override fun onLog(clazz: Class<*>, message: String?, throwable: Throwable?) =
                add("D", clazz.simpleName, message, throwable)

            override fun onError(clazz: Class<*>, message: String?, throwable: Throwable?) =
                add("E", clazz.simpleName, message, throwable)
        })
    }

    fun i(tag: String, message: String) = add("I", tag, message, null)
    fun w(tag: String, message: String, t: Throwable? = null) = add("W", tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = add("E", tag, message, t)

    @Synchronized
    private fun add(level: String, tag: String, message: String?, t: Throwable?) {
        val sb = StringBuilder()
        sb.append(time.format(Date())).append(' ').append(level).append(' ').append(tag).append(": ").append(message ?: "")
        var cause = t
        var depth = 0
        while (cause != null && depth < 4) {
            sb.append("\n    ").append(cause.javaClass.name).append(": ").append(cause.message ?: "")
            cause.stackTrace.take(3).forEach { sb.append("\n      at ").append(it) }
            cause = cause.cause.takeIf { it !== cause }
            depth++
        }
        val line = sb.toString()
        if (level == "E" || level == "W") Log.w("IdleMaster", line) else Log.d("IdleMaster", line)
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        _version.value++
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")
}
