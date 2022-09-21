package com.method.canary.core

import android.os.Handler
import android.os.SystemClock
import com.method.canary.config.MethodCanaryConfig.DEFAULT_RELEASE_BUFFER_DELAY
import com.method.canary.config.MethodCanaryConfig.METHOD_BUFFER_SIZE
import com.method.canary.config.MethodCanaryConfig.TIME_UPDATE_CYCLE_MS
import com.method.canary.hacker.ActivityThreadHacker
import com.method.canary.monitor.LooperDispatchListener
import com.method.canary.monitor.LooperMonitor
import com.method.canary.util.MatrixHandlerThread
import com.method.canary.util.MatrixLog
import com.method.canary.util.Utils


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

    ///////////////////////////start 状态相关///////////////////////////
    private const val STATUS_DEFAULT = Int.MAX_VALUE
    private const val STATUS_STARTED = 2
    private const val STATUS_READY = 1
    private const val STATUS_STOPPED = -1
    private const val STATUS_EXPIRED_START = -2
    private const val STATUS_OUT_RELEASE = -3

    @Volatile
    private var status = STATUS_DEFAULT
    private val statusLock = Object()
    ///////////////////////////end 状态相关///////////////////////////

    ///////////////////////////start 更新时间戳相关///////////////////////////
    @Volatile
    private var currentDiffTime = SystemClock.uptimeMillis()//当前时间戳，监控方法进入和退出的时间点直接拿它赋值

    @Volatile
    private var diffTime = currentDiffTime

    @Volatile
    private var isPauseUpdateTime = false
    private val updateTimeLock = Object()

    private val timerUpdateThread = MatrixHandlerThread.getNewHandlerThread(
        "matrix_time_update_thread",
        Thread.MIN_PRIORITY + 2
    )
    private val timerUpdateHandler = Handler(timerUpdateThread.looper)
    ///////////////////////////end 更新时间戳相关///////////////////////////

    ///////////////////////////start 储存方法信息相关///////////////////////////
    private const val METHOD_ID_MAX: Int = 0xFFFFF
    private const val METHOD_ID_DISPATCH = METHOD_ID_MAX - 1

    private var methodBufferArray: LongArray? = LongArray(METHOD_BUFFER_SIZE)//储存方法的调用信息
    private var currentMethodBufferIndex = 0//当前的方法Buffer数组下标
    private var lastMethodBufferIndex = -1//上一个方法Buffer数组下标

    var sIndexRecordHead: IndexRecord? = null
    ///////////////////////////end 储存方法信息相关///////////////////////////
    private var checkStartExpiredRunnable: Runnable? = null//检查AppMethodBeat启动是否超时

    init {
        timerUpdateHandler.postDelayed({
            realRelease()
        }, DEFAULT_RELEASE_BUFFER_DELAY)
    }

    ///////////////////////////start 生命周期相关///////////////////////////
    //启动
    fun start() {
        synchronized(statusLock) {
            if (status in STATUS_EXPIRED_START until STATUS_STARTED) {
                checkStartExpiredRunnable?.let { runnable ->
                    timerUpdateHandler.removeCallbacks(runnable)
                }

                if (methodBufferArray == null) {
                    throw RuntimeException("$TAG bufferArray == null")
                }
                MatrixLog.i(
                    TAG,
                    "[onStart] preStatus:%s",
                    status,
                    Utils.getStack()
                )
                status = STATUS_STARTED
            } else {
                MatrixLog.w(TAG, "[onStart] current status:%s", status)
            }
        }
    }

    //停止
    fun stop() {
        synchronized(statusLock) {
            if (status == STATUS_STARTED) {
                MatrixLog.i(TAG, "[onStop] %s", Utils.getStack())
                status = STATUS_STOPPED
            } else {
                MatrixLog.w(TAG, "[onStop] current status:%s", status)
            }
        }
    }

    fun forceStop() {
        synchronized(statusLock) { status = STATUS_STOPPED }
    }

    fun isAlive(): Boolean {
        return status >= STATUS_STARTED
    }

    fun isRealTrace(): Boolean {
        return status >= STATUS_READY
    }
    ///////////////////////////end 生命周期相关///////////////////////////

    ///////////////////////////start 启动和终止逻辑///////////////////////////
    // 开始执行，这里才是AppMethodBeat真正的启动逻辑:
    // 大致是先手动更新时间戳，再启动定时更新时间戳的任务
    // 注册消息打印监听器来控制时间戳更新的时机:
    // 如果当前没有消息要处理，时间戳无须更新，定时更新时间戳的任务进入休眠，等待新的消息到来再唤醒，防止无脑定时更新浪费cpu资源
    private fun realExecute() {
        MatrixLog.i(TAG, "[realExecute] timestamp:%s", System.currentTimeMillis())
        currentDiffTime = SystemClock.uptimeMillis() - diffTime
        timerUpdateHandler.removeCallbacksAndMessages(null)
        timerUpdateHandler.postDelayed(
            updateDiffTimeRunnable,
            TIME_UPDATE_CYCLE_MS
        )
        timerUpdateHandler.postDelayed(Runnable {
            synchronized(statusLock) {
                MatrixLog.i(
                    TAG,
                    "[startExpired] timestamp:%s status:%s",
                    System.currentTimeMillis(),
                    status
                )
                if (status == STATUS_DEFAULT || status == STATUS_READY) {
                    status = STATUS_EXPIRED_START
                }
            }
        }.also { checkStartExpiredRunnable = it }, DEFAULT_RELEASE_BUFFER_DELAY)
        ActivityThreadHacker.hackSysHandlerCallback()
        LooperMonitor.register(looperMonitorListener)
    }

    private fun realRelease() {
        synchronized(statusLock) {
            if (status == STATUS_DEFAULT) {
                MatrixLog.i(
                    TAG,
                    "[realRelease] timestamp:%s",
                    System.currentTimeMillis()
                )
                timerUpdateHandler.removeCallbacksAndMessages(null)
                LooperMonitor.unregister(looperMonitorListener)
                timerUpdateThread.quit()
                methodBufferArray = null
                status = STATUS_OUT_RELEASE
            }
        }
    }
    ///////////////////////////end 启动和终止逻辑///////////////////////////

    ///////////////////////////start 更新时间戳相关///////////////////////////

    private val looperMonitorListener: LooperDispatchListener = object : LooperDispatchListener() {

        override fun isValid() = status >= STATUS_READY

        override fun dispatchStart() {
            super.dispatchStart()
            dispatchBegin()
        }

        override fun dispatchEnd() {
            super.dispatchEnd()
            AppMethodBeat.dispatchEnd()
        }
    }

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
            MatrixLog.e(TAG, e.toString())
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
    ///////////////////////////end 更新时间戳相关///////////////////////////

    //创建一个方法记录
    fun maskIndex(source: String?): IndexRecord? {
        return if (sIndexRecordHead == null) {
            sIndexRecordHead = IndexRecord(currentMethodBufferIndex - 1)
            sIndexRecordHead?.source = source
            sIndexRecordHead
        } else {
            val indexRecord = IndexRecord(currentMethodBufferIndex - 1)
            indexRecord.source = source
            var record = sIndexRecordHead
            var last: IndexRecord? = null
            while (record != null) {
                if (indexRecord.index <= record.index) {
                    if (null == last) {
                        val tmp = sIndexRecordHead
                        sIndexRecordHead = indexRecord
                        indexRecord.next = tmp
                    } else {
                        val tmp = last.next
                        last.next = indexRecord
                        indexRecord.next = tmp
                    }
                    return indexRecord
                }
                last = record
                record = record.next
            }
            last!!.next = indexRecord
            indexRecord
        }
    }

}