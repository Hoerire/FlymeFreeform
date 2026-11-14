package io.github.hoerire.flymefreeform.hook

import android.graphics.Rect
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.log.ModuleLog
import io.github.hoerire.flymefreeform.log.VerboseLog

/**
 * 小窗摆放：掐掉 ColorOS 17 新加的"避让已有小窗"，让新窗永远落在系统自己的默认位置上。
 *
 * 反编译结论（oplus-services.jar → com.android.server.wm.OplusFlexibleTaskLayoutPolicy）：
 *   FlexibleTaskController.calcDefaultLaunchBounds
 *     → OplusFlexibleTaskLayoutPolicy.calcLaunchBounds
 *       → offsetBounds(...)
 *         · 列表为空（第一个窗）  → offsetSingleFlexibleBounds(rect, ...)
 *         · 列表非空（已有小窗）  → adjustDisplayBoundsList + offsetSingleFlexibleBounds
 *                                   + avoidOverlappingAreas(rect, list, ...)   ← 把 rect 推走
 *
 *   两条路里的居中计算是同一段，差别只有最后这句避让：
 *   竖屏分支里 left = mWindowBounds.centerX() - scaledW/2（系统自己就是居中）。
 *   实证：先把旧窗关掉再开新窗（列表为空）时，落点就是居中的。
 *
 * 因此这里保留系统原本的摆放，只在避让之后把 Rect 还原成避让前的值。
 * 不改缩放、不碰动画、不逐帧改写。
 */
internal class CenterZoomWindowHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    fun install(loader: ClassLoader) {
        // ColorOS 17 才引入"避让已有窗口"这一步；16 及更早不需要也不允许改写摆放。
        if (!io.github.hoerire.flymefreeform.platform.coloros.RomFlavor.isColorOs17OrNewer) {
            ModuleLog.info("ZOOM_CENTER_SKIP_ROM_" + android.os.Build.VERSION.SDK_INT)
            return
        }
        val target = runCatching { loader.loadClass(POLICY_CLASS) }.getOrNull() ?: run {
            ModuleLog.warn("ZOOM_CENTER_NO_POLICY")
            return
        }
        var installed = 0
        for (method in target.declaredMethods) {
            if (method.name != AVOID_METHOD) continue
            val rectIndex = method.parameterTypes.indexOfFirst { it == Rect::class.java }
            if (rectIndex < 0) continue
            method.isAccessible = true
            runCatching {
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.center." + installed)
                    .intercept { chain ->
                        // 只在"同时只允许一个悬浮小窗"时接管摆放：
                        // 允许多窗口时堆叠会让后面的窗口完全看不见，那种情况交回系统原生排序。
                        val settings = configuration.snapshot
                        if (!settings.zoomInteractionActive || settings.zoomMaxWindowCountEffective != 1) {
                            return@intercept chain.proceed()
                        }
                        val rect = chain.getArg(rectIndex) as? Rect
                        if (rect == null || rect.width() <= 0 || rect.height() <= 0) {
                            return@intercept chain.proceed()
                        }
                        val before = Rect(rect)
                        val result = chain.proceed()
                        if (rect.left != before.left || rect.top != before.top ||
                            rect.width() != before.width() || rect.height() != before.height()
                        ) {
                            rect.set(before)
                            if (VerboseLog.enabled) {
                                ModuleLog.debug(TAG) {
                                    "ZOOM_AVOID_SKIPPED_" + before.flattenToString()
                                }
                            }
                        }
                        result
                    }
                installed++
            }
        }
        ModuleLog.info("ZOOM_CENTER_INSTALLED_" + installed)
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val POLICY_CLASS = "com.android.server.wm.OplusFlexibleTaskLayoutPolicy"
        const val AVOID_METHOD = "avoidOverlappingAreas"
    }
}
