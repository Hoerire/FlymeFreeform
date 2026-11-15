package io.github.hoerire.flymefreeform.hook

import io.github.hoerire.flymefreeform.log.ModuleLog
import io.github.libxposed.api.XposedModule

/**
 * 内容传送门面板接管入口。
 *
 * 一个系统版本一份实现与一份配置，混淆名不跨版本复用：
 *
 * | 版本 | 实现 | 面板窗口 | 面板/列表/截图 | 状态回调 | 显示/收起 |
 * |---|---|---|---|---|---|
 * | 17.3.18 | [PortalTapLegacy] | `d6.x0` | `m` / `n` / `p` | `DragEventReceiver.l` | `H(1)` / `U0()` |
 * | 17.5.4 | [PortalTapModern] | `u61` | `k` / `l` / `n` | 窗口 `c` → `zd.i`（`ml0`），回调在热区 `sz.b` | `s(String)`、`g()` / `r(Z)` |
 *
 * 旧版的完整做法（[PortalTapLegacy]，17.3.18 实测名）：
 *   · 绑定：`ContentPortalService.onBind` 建立会话，窗口取服务字段 `c`；
 *   · 长按即弹：钩 `DragEventReceiver.g(4)` 拿到拖动坐标，再调 `l(4)` 派发 ENTER，
 *     原厂收到 ENTER 才会把面板升到手指位置；
 *   · 接管呈现：钩窗口 `H(1)`，自己造一个 DROP 交给 `B(1)`（记录落点），
 *     再调 `O()` 显示列表，然后一次性设窗口参数与五列网格；
 *   · 防回弹：钩窗口 `U0()`（入场动画）跑完复位参数；
 *   · 生命周期：钩 `onDragEvent` 吞掉拖动事件，关闭只走点窗外与点条目。
 *
 * 新版按上表换成同义的类与字段，流程保持一致。
 */
internal class PortalTapHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    fun install(loader: ClassLoader, apkPath: String?) {
        if (PortalTapLegacy(module, configuration).install(loader)) {
            ModuleLog.install("PORTAL_MODE_LEGACY")
            return
        }
        val profile = PROFILES.firstOrNull {
            runCatching { loader.loadClass(it.windowClass) }.isSuccess
        }
        if (profile == null) {
            ModuleLog.install("PORTAL_MODE_UNSUPPORTED")
            return
        }
        if (PortalTapModern(module, configuration).install(loader, profile)) {
            ModuleLog.install("PORTAL_MODE_MODERN_" + profile.portalVersion)
        } else {
            ModuleLog.install("PORTAL_MODE_MODERN_FAILED_" + profile.portalVersion)
        }
    }

    companion object {
        const val PACKAGE_NAME = "com.oplus.contentportal"

        /** 版本 → 混淆名映射。字段含义见 [PortalProfile]。 */
        private val PROFILES = listOf(
            PortalProfile(
                portalVersion = "17.5.4",
                serviceClass = "com.oplus.contentportal.service.ContentPortalService",
                windowClass = "u61",
                receiverClass = "ml0",
                stateClass = "com.oplus.contentportal.drag.DragEventReceiver\$DragState",
                enterStateField = "d",
                dragEntry = "onDragEvent",
                panelHideMethod = "r",
                animMethod = "g",
                controllerField = "c",
                receiverField = "i",
                hotViewField = "c",
                listenerField = "mOnDragListener",
                callbackField = "b",
                serviceInfoClass = "com.oplus.contentportal.data.ServiceInfo",
                launchMethod = "b",
                panelField = "k",
                listField = "l",
                screenshotField = "n",
                listDataField = "q",
                eventField = "o",
                clipField = "p",
            ),
        )
    }
}
