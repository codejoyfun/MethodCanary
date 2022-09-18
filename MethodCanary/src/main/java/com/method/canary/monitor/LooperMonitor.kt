package com.method.canary.monitor

import android.os.Looper
import android.os.MessageQueue
import android.util.Printer
import com.method.canary.config.MethodCanaryConfig
import com.method.canary.config.MethodCanaryConfig.TAG
import com.method.canary.util.MatrixLog
import com.method.canary.util.ReflectUtils
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 监听Handler消息的前后处理
 */
class LooperMonitor : MessageQueue.IdleHandler {

    companion object {
        private val LOOPER_MONITOR_MAP = ConcurrentHashMap<Looper, LooperMonitor>()
        private val MAIN_MONITOR = of(Looper.getMainLooper())

        private const val isReflectLoggingError = false//标识反射获取消息打印器是否失败

        //根据当前的looper构造对应的LooperMonitor
        fun of(looper: Looper): LooperMonitor {
            return LOOPER_MONITOR_MAP[looper] ?: run {
                val looperMonitor = LooperMonitor(looper)
                LOOPER_MONITOR_MAP[looper] = looperMonitor
                looperMonitor
            }
        }
    }

    private var looper: Looper
    private lateinit var printer: LooperPrinter

    private constructor(looper: Looper) {
        Objects.requireNonNull(looper)
        this.looper = looper
        resetPrinter()
        addIdleHandler(looper)
    }

    @Synchronized
    private fun resetPrinter() {
        var originPrinter: Printer? = null
        try {
            if (!isReflectLoggingError) {
                originPrinter = ReflectUtils.get(looper.javaClass, "mLogging", looper)
                if (originPrinter == printer){

                }
            }
        }catch (e:Exception){

        }
    }


    override fun queueIdle(): Boolean {
        return true
    }

    //监听消息的分发
    private fun dispatch(isBegin:Boolean, log:String?){

    }

    inner class LooperPrinter(private val originPrinter: Printer?) : Printer {
        var isHasChecked = false
        var isValid = false

        override fun println(x: String?) {
            originPrinter?.println(x)//业务方或其他sdk设置的消息打印器
            if (originPrinter == this) {
                throw RuntimeException("${MethodCanaryConfig.TAG} origin == this")
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



}