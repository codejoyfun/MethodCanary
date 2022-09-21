package com.method.canary.config

/**
 * 方法耗时监控配置类
 */
object MethodCanaryConfig {

    const val TAG = " MethodCanary"
    const val DEFAULT_EVIL_METHOD_THRESHOLD_MS = 700//方法耗时阈值，超过此阈值,判定为异常耗时方法
    //每间隔5ms刷新当前时间戳(开新线程去刷新，避免在每个方法里前后插入SystemClock.uptimeMillis(),造成额外的耗时)
    const val TIME_UPDATE_CYCLE_MS = 5L
    //AppMethodBeat如果在15秒内还没监控到有方法调用，关闭AppMethodBeat，释放相关资源
    const val DEFAULT_RELEASE_BUFFER_DELAY = 15 * 1000L
    // 大小为7.6M 储存方法的调用信息，每个buffer包含方法的enter和exit表示，方法id，方法的执行时间戳
    const val METHOD_BUFFER_SIZE = 100 * 10000

}
