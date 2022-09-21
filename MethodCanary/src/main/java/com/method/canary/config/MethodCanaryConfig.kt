package com.method.canary.config

/**
 * 方法耗时监控配置类
 */
object MethodCanaryConfig {

    const val DEFAULT_EVIL_METHOD_THRESHOLD_MS = 700//方法耗时阈值，超过此阈值,判定为异常耗时方法
    const val TIME_UPDATE_CYCLE_MS = 5L//每间隔5ms刷新当前时间戳(开新线程去刷新，避免在每个方法里前后插入SystemClock.uptimeMillis(),造成额外的耗时)
    const val TAG = " MethodCanary"
}