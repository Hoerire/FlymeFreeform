package io.github.hoerire.free.hook

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.hoerire.free.bubble.ClipboardClassifier
import io.github.hoerire.free.config.ModulePreferences
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * system_server 侧的剪贴板分类。
 *
 * Android 10+ 的剪贴板读取被焦点限制，SystemUI 里直接监听 ClipboardManager 拿不到内容；
 * 因此在实际写剪贴板的进程里 hook `ClipboardService.setPrimaryClip`，把分类结果
 * 定向广播给 SystemUI 的气泡子系统。只传分类后的短文本，不传整段剪贴板。
 */
internal class ClipboardSuggestionHook(
    private val module: XposedModule,
    private val enabled: () -> Boolean,
    private val logger: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) {
    private var lastSignature: String? = null
    private var lastAt = 0L

    fun install(classLoader: ClassLoader) {
        val outer =
            SERVICE_CLASSES.firstNotNullOfOrNull { name ->
                runCatching { Class.forName(name, false, classLoader) }.getOrNull()
            }
        if (outer == null) {
            logger(Log.INFO, "CLIPBOARD_SUGGESTION_CLASS_MISSING", null)
            return
        }
        // 写入剪贴板的方法不一定声明在服务类本体上：ColorOS 把 AIDL 实现挪进了
        // `ClipboardService$ClipboardImpl`，本体只剩 Internal 系列。把嵌套类一并纳入候选，
        // 再按「第一个参数是 ClipData + 名字是写入语义」挑方法。
        val allClasses =
            (listOf(outer) + outer.declaredClasses.filterNot { it.name.contains("$$") })
                .distinct()
        val allMethods = allClasses.flatMap { it.declaredMethods.toList() }
        val named = allMethods.filter { isClipboardWrite(it, requireNameHint = true) }
        val candidates = named.ifEmpty { allMethods.filter { isClipboardWrite(it, requireNameHint = false) } }
        var count = 0
        for (method in candidates) {
            method.isAccessible = true
            runCatching {
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("free.clipboard." + method.name + "." + method.parameterCount)
                    .intercept { chain ->
                        val result = chain.proceed()
                        (chain.args?.firstOrNull() as? ClipData)?.let { clip ->
                            runCatching { onClipboardChanged(clip, chain.thisObject) }
                        }
                        result
                    }
                count++
            }.onFailure {
                logger(Log.WARN, "CLIPBOARD_SUGGESTION_HOOK_FAILED_" + method.name, it)
            }
        }
        logger(
            Log.INFO,
            if (count > 0) "CLIPBOARD_SUGGESTION_HOOKS_$count" else "CLIPBOARD_SUGGESTION_HOOKS_NONE",
            null,
        )
        // 一个都没挂上时把候选签名打出来，避免只能靠猜：类名 + 方法名 + 参数类型足够定位版本差异。
        if (count == 0) {
            allClasses.flatMap { it.declaredMethods.toList() }
                .take(MAX_DIAGNOSTIC_METHODS)
                .forEachIndexed { index, m ->
                    logger(
                        Log.INFO,
                        "CLIPBOARD_SUGGESTION_CAND_" + index + "_" + m.declaringClass.simpleName +
                            "_" + m.name + "_" +
                            m.parameterTypes.joinToString(",") { it.simpleName },
                        null,
                    )
                }
        }
    }

    /** 第一个参数是剪贴板、且名字像「写入」的方法。 */
    private fun isClipboardWrite(method: java.lang.reflect.Method, requireNameHint: Boolean): Boolean {
        val parameters = method.parameterTypes
        if (parameters.isEmpty()) return false
        if (!ClipData::class.java.isAssignableFrom(parameters[0])) return false
        val lower = method.name.lowercase()
        return if (requireNameHint) {
            lower.contains("setprimary")
        } else {
            lower.contains("set") || lower.contains("write")
        }
    }

    /**
     * 服务的 Context：方法可能声明在嵌套类上，此时 Context 在外部类里，
     * 顺着 `this$0` 往外找；`ClipboardService` 本身不带 `mContext`，
     * 最后退回 `ActivityThread` 的系统上下文。
     */
    private fun contextOf(service: Any?): Context? {
        var current: Any? = service
        repeat(MAX_CONTEXT_DEPTH) {
            val target = current ?: return@repeat
            (OemReflection.readField(target, "mContext") as? Context)?.let { return it }
            current = OemReflection.readField(target, "this$0")
        }
        return systemContext()
    }

    private var cachedSystemContext: Context? = null

    /** system_server 里没有现成 Context，`ActivityThread#getSystemContext` 是唯一稳定来源。 */
    private fun systemContext(): Context? {
        cachedSystemContext?.let { return it }
        val context =
            runCatching {
                val threadClass = Class.forName("android.app.ActivityThread")
                val thread =
                    threadClass.getDeclaredMethod("currentActivityThread").invoke(null)
                        ?: return null
                threadClass.getDeclaredMethod("getSystemContext").invoke(thread) as? Context
            }.getOrNull()
        cachedSystemContext = context
        return context
    }

    private fun onClipboardChanged(clip: ClipData, service: Any?) {
        if (!enabled()) return
        if (clip.itemCount == 0) return
        val context = contextOf(service) ?: return
        val text =
            runCatching { clip.getItemAt(0).coerceToText(context)?.toString() }
                .getOrNull()
                ?.trim()
                .orEmpty()
        if (text.isEmpty()) return
        val results = ClipboardClassifier.classifyAll(text)
        if (results.isEmpty()) return
        val signature =
            results.joinToString(separator = ";") { "${it.kind}=${it.value}" }
        val now = SystemClock.elapsedRealtime()
        if (signature == lastSignature && now - lastAt < 3_000L) return
        lastSignature = signature
        lastAt = now
        val intent =
            Intent(ModulePreferences.ACTION_CLIPBOARD_SUGGESTION)
                .setPackage("com.android.systemui")
                .putExtra(
                    ModulePreferences.EXTRA_CLIP_KINDS,
                    results.map { it.kind }.toIntArray(),
                )
                .putExtra(
                    ModulePreferences.EXTRA_CLIP_VALUES,
                    results.map { it.value }.toTypedArray(),
                )
                .putExtra(
                    ModulePreferences.EXTRA_CLIP_DISPLAYS,
                    results.map { it.display }.toTypedArray(),
                )
                .putExtra(
                    ModulePreferences.EXTRA_CLIP_APPS,
                    results.map { it.appPackage.orEmpty() }.toTypedArray(),
                )
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        context.sendBroadcast(intent)
        logger(Log.INFO, "CLIPBOARD_SUGGESTION_SENT_${results.size}", null)
    }

    private companion object {
        /**
         * 写入剪贴板的服务类名候选。
         *
         * AOSP 一直是 `com.android.server.clipboard.ClipboardService`；ColorOS 把 AIDL 实现
         * 挪进了它的内部类 `ClipboardService$ClipboardImpl`，本体会被自动展开成候选，
         * 所以这里只列服务本体及其厂商变体。
         */
        val SERVICE_CLASSES =
            listOf(
                "com.android.server.clipboard.ClipboardService",
                "com.oplus.server.clipboard.ClipboardService",
            )

        /** 没挂上 hook 时最多打印多少条方法签名用于定位版本差异。 */
        const val MAX_DIAGNOSTIC_METHODS = 40

        /** 顺着 `this$0` 找 Context 的最大层数。 */
        const val MAX_CONTEXT_DEPTH = 3
    }
}
