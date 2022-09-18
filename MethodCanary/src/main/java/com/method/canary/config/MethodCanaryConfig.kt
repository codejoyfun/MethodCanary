package com.method.canary.config

/**
 * 方法耗时监控配置类
 */
object MethodCanaryConfig {

    const val DEFAULT_EVIL_METHOD_THRESHOLD_MS = 700//方法耗时阈值，超过此阈值,判定为异常耗时方法

}