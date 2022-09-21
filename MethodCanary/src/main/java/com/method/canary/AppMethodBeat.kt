package com.method.canary

import android.os.SystemClock
import com.method.canary.config.MethodCanaryConfig.TIME_UPDATE_CYCLE_MS
import com.method.canary.monitor.LooperDispatchListener
import com.method.canary.util.MatrixLog


/**
 * 创建时间：2022/9/21
 * 编写人：ningtukun
 * 功能描述：方法耗时打点，输出调用堆栈
 */
object AppMethodBeat {

    interface MethodEnterListener {
        fun enter(method: Int, threadId: Long)
    }

    private const val TAG = "AppMethodBeat"
    var isDev = false

    private const val STATUS_DEFAULT = Int.MAX_VALUE
    private const val STATUS_STARTED = 2
    private const val STATUS_READY = 1
    private const val STATUS_STOPPED = -1
    private const val STATUS_EXPIRED_START = -2
    private const val STATUS_OUT_RELEASE = -3

    @Volatile
    private var status = STATUS_DEFAULT
    @Volatile
    private var currentDiffTime = SystemClock.uptimeMillis()
    @Volatile
    private var diffTime = currentDiffTime
    @Volatile
    private var isPauseUpdateTime = false
    private val updateTimeLock = Object()

    private val looperMonitorListener: LooperDispatchListener = object : LooperDispatchListener() {

        override fun isValid() = status >= STATUS_READY

        override fun dispatchStart() {
            super.dispatchStart()
            AppMethodBeat.dispatchBegin()
        }

        override fun dispatchEnd() {
            super.dispatchEnd()
            AppMethodBeat.dispatchEnd()
        }
    }

    private fun dispatchBegin() {
        currentDiffTime = SystemClock.uptimeMillis() - diffTime
        isPauseUpdateTime = false
        synchronized(updateTimeLock) {
            updateTimeLock.notify()
        }
    }

    private fun dispatchEnd() {
        isPauseUpdateTime = true
    }

    /**
     * update time runnable
     */
    private val updateDiffTimeRunnable = Runnable {
        try {
            while (true) {
                while (!isPauseUpdateTime && status > STATUS_STOPPED) {
                    currentDiffTime = SystemClock.uptimeMillis() - diffTime
                    SystemClock.sleep(TIME_UPDATE_CYCLE_MS)
                }
                synchronized(updateTimeLock) { updateTimeLock.wait() }
            }
        } catch (e: Exception) {
            MatrixLog.e(TAG, "" + e.toString())
        }
    }

}