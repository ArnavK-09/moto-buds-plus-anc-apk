package com.arnavk09.motoanc

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object BudsLog {
    private const val TAG = "MotoBuds"
    private const val MAX_LINES = 500
    private val lines = ArrayDeque<String>()
    private val listeners = ArrayList<Runnable>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun d(message: String) {
        Log.d(TAG, message)
        val line = stamp.format(Date()) + "  " + message
        lines.addLast(line)
        while (lines.size > MAX_LINES) {
            lines.removeFirst()
        }
        if (listeners.isEmpty()) return
        val snapshot = ArrayList(listeners)
        for (listener in snapshot) {
            dispatch(listener)
        }
    }

    private fun dispatch(listener: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runSafely(listener)
        } else {
            main.post { runSafely(listener) }
        }
    }

    private fun runSafely(listener: Runnable) {
        try {
            listener.run()
        } catch (ignored: Throwable) {
        }
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
    }

    @Synchronized
    fun addListener(listener: Runnable) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }
}
