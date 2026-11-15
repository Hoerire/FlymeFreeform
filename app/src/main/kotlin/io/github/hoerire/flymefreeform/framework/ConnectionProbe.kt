package io.github.hoerire.flymefreeform.framework

import android.util.Log

/**
 * 设置页连接链路的探针。
 *
 * 只做记录：每个阶段发生什么、看到了几个服务、失败原因是什么，统一打一条带前缀的日志。
 * 页面连不上时先看日志，不靠猜测定位。
 */
internal object ConnectionProbe {
    private const val TAG = "FlymeFreeformProbe"
    private val events = ArrayDeque<String>()

    @Synchronized
    fun record(stage: String, detail: String? = null) {
        val line = if (detail.isNullOrBlank()) stage else stage + " " + detail
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast(line)
        Log.i(TAG, line)
    }

    /** 框架服务缓存里当前有几个服务：由 XposedServiceHelper 的静态字段读取，用于判断投递是否发生。 */
    @Synchronized
    fun recordServiceCache(stage: String) {
        val size =
            runCatching {
                val type = Class.forName("io.github.libxposed.service.XposedServiceHelper")
                val field = type.getDeclaredField("mCache").apply { isAccessible = true }
                (field.get(null) as? Collection<*>)?.size ?: -1
            }.getOrElse { -1 }
        record(stage, "cache=" + size)
    }

    @Synchronized
    fun snapshot(): List<String> = events.toList()

    private const val MAX_EVENTS = 64
}
