package com.reyfxck.radiofmx.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DebugLogEntry(
    val id: Long,
    val time: String,
    val level: String,
    val tag: String,
    val message: String,
) {
    fun asLine(): String = "$time $level/$tag: $message"
}

/**
 * In-app diagnostic stream for RadioFMX. This deliberately records the app's own
 * events and errors; it is not a privileged reader for the complete Android logcat.
 */
object DebugLog {
    private const val MAX_ENTRIES = 600
    private var nextId = 0L
    private val mutableEntries = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val entries: StateFlow<List<DebugLogEntry>> = mutableEntries.asStateFlow()

    @Synchronized
    private fun record(level: String, tag: String, message: String, error: Throwable? = null) {
        val detail = if (error == null) message else {
            val summary = "$message | ${error.javaClass.simpleName}: ${error.message ?: "sem mensagem"}"
            val stack = error.stackTraceToString().lineSequence().take(28).joinToString("\n")
            "$summary\n$stack"
        }
        val entry = DebugLogEntry(
            id = ++nextId,
            time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()),
            level = level,
            tag = tag,
            message = detail,
        )
        val updated = mutableEntries.value.toMutableList()
        updated.add(entry)
        if (updated.size > MAX_ENTRIES) {
            updated.subList(0, updated.size - MAX_ENTRIES).clear()
        }
        mutableEntries.value = updated

        when (level) {
            "D" -> Log.d(tag, detail)
            "I" -> Log.i(tag, detail)
            "W" -> Log.w(tag, detail)
            else -> if (error != null) Log.e(tag, detail, error) else Log.e(tag, detail)
        }
    }

    fun d(tag: String, message: String) = record("D", tag, message)
    fun i(tag: String, message: String) = record("I", tag, message)
    fun w(tag: String, message: String) = record("W", tag, message)
    fun e(tag: String, message: String, error: Throwable? = null) =
        record("E", tag, message, error)

    @Synchronized
    fun clear() {
        mutableEntries.value = emptyList()
    }

    fun dump(): String = mutableEntries.value.joinToString(separator = "\n") { it.asLine() }
}
