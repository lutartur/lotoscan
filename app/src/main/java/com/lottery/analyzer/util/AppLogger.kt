package com.lottery.analyzer.util

import android.util.Log

/**
 * Утилита для логирования в приложении
 */
object AppLogger {
    private const val TAG = "LotteryAnalyzer"
    private var enabled = true

    fun enable() {
        enabled = true
    }

    fun disable() {
        enabled = false
    }

    fun d(message: String) {
        if (enabled) {
            Log.d(TAG, message)
        }
    }

    fun i(message: String) {
        if (enabled) {
            Log.i(TAG, message)
        }
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (enabled) {
            if (throwable != null) {
                Log.w(TAG, message, throwable)
            } else {
                Log.w(TAG, message)
            }
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (enabled) {
            if (throwable != null) {
                Log.e(TAG, message, throwable)
            } else {
                Log.e(TAG, message)
            }
        }
    }

    fun performance(tag: String, startTime: Long, operation: String) {
        if (enabled) {
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "[$tag] $operation: ${duration}ms")
        }
    }
}
