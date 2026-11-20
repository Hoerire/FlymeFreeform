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
        val serviceClass =
            runCatching {
                Class.forName("com.android.server.clipboard.ClipboardService", false, classLoader)
            }.getOrNull() ?: return
        var count = 0
        for (method in serviceClass.declaredMethods) {
            if (method.name != "setPrimaryClip" && method.name != "setPrimaryClipAsPackage") continue
            val parameters = method.parameterTypes
            if (parameters.isEmpty() || !ClipData::class.java.isAssignableFrom(parameters[0])) {
                continue
            }
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.clipboard." + method.name)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.args?.firstOrNull() as? ClipData)?.let { clip ->
                        runCatching { onClipboardChanged(clip, chain.thisObject) }
                    }
                    result
                }
            count++
        }
        logger(
            Log.INFO,
            if (count > 0) "CLIPBOARD_SUGGESTION_HOOKS_$count"
            else "CLIPBOARD_SUGGESTION_HOOKS_NONE",
            null,
        )
    }

    private fun onClipboardChanged(clip: ClipData, service: Any?) {
        if (!enabled()) return
        if (clip.itemCount == 0) return
        val context =
            runCatching { OemReflection.readField(service, "mContext") as? Context }.getOrNull()
                ?: return
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
}
