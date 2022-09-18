package com.method.canary.monitor

import android.os.Looper
import android.os.MessageQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * 监听Handler消息的前后处理
 */
class LooperMonitor : MessageQueue.IdleHandler {

    private val sLooperMonitorMap = ConcurrentHashMap<Looper, LooperMonitor>()
    private val sMainMonitor: LooperMonitor = LooperMonitor.of(Looper.getMainLooper())

    override fun queueIdle(): Boolean {
        return true
    }

    fun of(looper: Looper): LooperMonitor? {
        var looperMonitor = LooperMonitor.sLooperMonitorMap.get(looper)
        if (looperMonitor == null) {
            looperMonitor = LooperMonitor(looper)
            LooperMonitor.sLooperMonitorMap.put(looper, looperMonitor)
        }
        return looperMonitor
    }

}