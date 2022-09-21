package com.method.canary.monitor

import android.os.Build
import android.os.Looper
import android.os.MessageQueue
import android.os.SystemClock
import android.util.Log
import android.util.Printer
import androidx.annotation.CallSuper
import com.method.canary.util.MatrixLog
import com.method.canary.util.ReflectUtils
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 监听Handler消息的前后处理
 */
class LooperMonitor private constructor(private var looper: Looper?) : MessageQueue.IdleHandler {

    companion object {

        private const val TAG = " MethodCanary"
        private var isReflectLoggingError = false//标识反射获取消息打印器是否失败
        private const val FIELD_LOGGING = "mLogging"
        private const val CHECK_TIME = 60 * 1000L

        private val LOOPER_MONITOR_MAP = ConcurrentHashMap<Looper, LooperMonitor>()
        private val MAIN_MONITOR = of(Looper.getMainLooper())

        //根据当前的looper构造对应的LooperMonitor
        fun of(looper: Looper): LooperMonitor {
            return LOOPER_MONITOR_MAP[looper] ?: run {
                val looperMonitor = LooperMonitor(looper)
                LOOPER_MONITOR_MAP[looper] = looperMonitor
                looperMonitor
            }
        }

        fun register(listener: LooperDispatchListener) {
            MAIN_MONITOR.addListener(listener)
        }

        fun unregister(listener: LooperDispatchListener) {
            MAIN_MONITOR.removeListener(listener)
        }
    }

    private var currentPrinter: LooperPrinter? = null
    private val dispatchListenerList = hashSetOf<LooperDispatchListener>()//Looper消息分发器集合
    private var lastCheckPrinterTime = 0L

    init {
        Objects.requireNonNull(looper)
        resetPrinter()
        addIdleHandler(looper)
    }

    override fun queueIdle(): Boolean {
        //消息循环空闲时，每隔60秒检查一下自己的消息打印监听器有没有被覆盖掉，如果有，重新设置一下
        if (SystemClock.uptimeMillis() - lastCheckPrinterTime >= CHECK_TIME) {
            resetPrinter()
            lastCheckPrinterTime = SystemClock.uptimeMillis()
        }
        return true
    }

    /////////////////////////start 消息分发监听器相关/////////////////////////
    fun addListener(listener: LooperDispatchListener) {
        synchronized(dispatchListenerList) {
            dispatchListenerList.add(listener)
        }
    }

    fun removeListener(listener: LooperDispatchListener) {
        synchronized(dispatchListenerList) {
            dispatchListenerList.remove(listener)
        }
    }

    //监听消息的分发,遍历向LooperMonitor注册的监听器
    private fun dispatch(isBegin: Boolean, log: String?) {
        synchronized(dispatchListenerList) {
            dispatchListenerList.forEach { listener ->
                if (listener.isValid()) {
                    if (isBegin) {
                        if (!listener.isDispatchStarted) {
                            listener.onDispatchStart(log)
                        }
                    } else {
                        if (listener.isDispatchStarted) {
                            listener.onDispatchEnd(log)
                        }
                    }
                } else if (!isBegin && listener.isDispatchStarted) {
                    listener.dispatchEnd()
                }
            }
        }
    }
    /////////////////////////end 消息分发监听器相关/////////////////////////

    /////////////////////////start 空闲消息处理器相关/////////////////////////
    @Synchronized
    private fun addIdleHandler(looper: Looper?) {
        looper ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            looper.queue.addIdleHandler(this)
        } else {
            try {
                val queue = ReflectUtils.get<MessageQueue>(looper.javaClass, "mQueue", looper)
                queue.addIdleHandler(this)
            } catch (e: Exception) {
                Log.e(TAG, "[removeIdleHandler] %s", e)
            }
        }
    }

    @Synchronized
    private fun removeIdleHandler(looper: Looper?) {
        looper ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            looper.queue.removeIdleHandler(this)
        } else {
            try {
                val queue = ReflectUtils.get<MessageQueue>(looper.javaClass, "mQueue", looper)
                queue.removeIdleHandler(this)
            } catch (e: java.lang.Exception) {
                Log.e(TAG, "[removeIdleHandler] %s", e)
            }
        }
    }
    /////////////////////////end 空闲消息处理器相关/////////////////////////

    /////////////////////////start 设置自定义消息打印器相关/////////////////////////
    @Synchronized
    private fun resetPrinter() {
        var originPrinter: Printer? = null
        try {
            if (!isReflectLoggingError) {//之前的反射没有出错

                originPrinter = ReflectUtils.get(looper?.javaClass, FIELD_LOGGING, looper)
                //在设置Printer之前做一些校验
                currentPrinter?.let { current ->
                    if (originPrinter == current) {//发现原来的打印监听器跟当前的监听器一样，则不需再处理
                        return
                    }

                    originPrinter?.let { origin ->
                        //防止不同的类加载器加载相同的监听类
                        if (origin.javaClass.name.equals(current.javaClass.name)) {
                            MatrixLog.w(
                                TAG,
                                "LooperPrinter可能被不同的类加载器加载, my = ${current.javaClass.classLoader}, other = ${origin.javaClass.classLoader}"
                            )
                            return
                        }
                    }// end of originPrinter?.let
                }//end of currentPrinter?.let
            }
        } catch (e: Exception) {
            isReflectLoggingError = true
            Log.e(TAG, "[resetPrinter] %s", e)
        }

        currentPrinter?.let { current ->
            MatrixLog.w(
                TAG, "maybe thread:%s printer[%s] was replace other[%s]!",
                looper?.thread?.name, current
            )
        }

        currentPrinter = LooperPrinter(originPrinter)
        looper?.setMessageLogging(currentPrinter)
        originPrinter?.let { origin ->
            MatrixLog.i(TAG, "reset printer, originPrinter[%s] in %s", origin, looper?.thread?.name)
        }
    }

    /**
     * 自定义消息打印监听器
     */
    inner class LooperPrinter(val originPrinter: Printer?) : Printer {
        var isHasChecked = false
        var isValid = false

        override fun println(x: String?) {
            originPrinter?.println(x)//业务方或其他sdk设置的消息打印器
            if (originPrinter == this) {
                throw RuntimeException("$TAG origin == this")
            }

            //检查消息打印机制的规则是否有效，有效才去做监听，因为有可能android版本更新，这套机制可能会失效
            if (!isHasChecked) {
                val firstChar = x?.firstOrNull()
                isValid = firstChar == '>' || firstChar == '<'
                if (!isValid) {//消息打印规则跟我们的预设不一样
                    MatrixLog.e(TAG, "[println] Printer is inValid! x:%s", x)
                }
            }

            if (isValid) {//消息打印规则跟我们的预设匹配，监听消息的开始和结束
                dispatch(x?.firstOrNull() == '>', x)
            }
        }
    }

    /////////////////////////end 设置自定义消息打印器相关/////////////////////////

    //释放自身资源
    @Synchronized
    fun onRelease() {
        if (currentPrinter != null) {
            synchronized(dispatchListenerList) {
                dispatchListenerList.clear()
            }
            MatrixLog.v(TAG, "[onRelease] %s, origin printer:%s", looper?.thread?.name, currentPrinter?.originPrinter)
            looper?.setMessageLogging(currentPrinter?.originPrinter)
            removeIdleHandler(looper)
            looper = null
            currentPrinter = null
        }
    }

}

/**
 * 自定义Looper消息分发器
 */
abstract class LooperDispatchListener {

    var isDispatchStarted = false//消息分发是否已经开始

    open fun isValid() = false

    open fun dispatchStart() {}

    open fun dispatchEnd() {}

    @CallSuper
    fun onDispatchStart(x: String?) {
        isDispatchStarted = true
        dispatchStart()
    }

    @CallSuper
    fun onDispatchEnd(x: String?) {
        isDispatchStarted = false
        dispatchEnd()
    }

}