package io.github.hoerire.flymefreeform.platform.coloros

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.github.hoerire.flymefreeform.log.ModuleLog

/** 校验系统包身份与组件权限；功能兼容性由宿主接口和 DexKit 查询另行确认。 */
internal object ColorOsSidebarTarget {
    const val PACKAGE_NAME = "com.coloros.smartsidebar"
    const val PROCESS_NAME = "$PACKAGE_NAME:ui"
    const val SERVICE_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.UIService"
    const val BIND_ACTION = "io.github.hoerire.flymefreeform.action.BIND_ALL_APPS_V1"
    const val REQUIRED_PERMISSION = "oppo.permission.OPPO_COMPONENT_SAFE"
    const val PROVIDER_AUTHORITY = "com.coloros.sidebar"
    const val PREPARE_METHOD = "bindServiceForTransferDock"

    /**
     * 侧边栏应用的 uid；不可用时返回 null。
     *
     * 这里只校验"是不是那个应用、有没有那套权限"，**不再要求
     * provider.applicationInfo.uid == service.applicationInfo.uid**。
     * 原因：ColorOS 侧边栏可能同时存在系统预装版（/product/app/SmartSideBar）与
     * 更新版（/data/app/...）两条 Package 记录，getServiceInfo 与 resolveContentProvider
     * 会各自命中不同实例，uid 必然对不上，于是整条链路被判成"不可用"，
     * 表现为重启后侧边栏目录失效。
     *
     * 同包名的 Provider 只能由该包声明，去掉 uid 比较不会放宽到"别的应用能冒充"。
     */
    fun supportedUid(context: Context): Int? {
        val manager = context.packageManager
        return try {
            val service =
                manager.getServiceInfo(
                    ComponentName(PACKAGE_NAME, SERVICE_CLASS),
                    PackageManager.ComponentInfoFlags.of(0),
                )
            val app = service.applicationInfo
            val provider =
                manager.resolveContentProvider(
                    PROVIDER_AUTHORITY,
                    PackageManager.ComponentInfoFlags.of(0),
                )
            if (provider != null &&
                (
                    provider.packageName != PACKAGE_NAME ||
                        provider.name != "com.coloros.edgepanel.api.SideBarProvider" ||
                        !provider.enabled ||
                        !provider.exported ||
                        provider.readPermission != "com.coloros.sidebar.permission.data" ||
                        provider.writePermission != "com.coloros.sidebar.permission.data"
                )
            ) {
                ModuleLog.warn("SIDEBAR_PROVIDER_REJECT_" + provider.packageName + "_" + provider.name)
                return null
            }
            val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            if (app.flags and systemFlags == 0) {
                ModuleLog.warn("SIDEBAR_NOT_SYSTEM_APP_" + app.flags)
                return null
            }
            if (!app.enabled || !service.enabled || !service.exported) {
                ModuleLog.warn("SIDEBAR_DISABLED_APP" + app.enabled + "_SVC" + service.enabled)
                return null
            }
            if (app.flags and ApplicationInfo.FLAG_SUSPENDED != 0) {
                ModuleLog.warn("SIDEBAR_SUSPENDED")
                return null
            }
            if (service.permission != REQUIRED_PERMISSION || service.processName != PROCESS_NAME) {
                ModuleLog.warn(
                    "SIDEBAR_SERVICE_MISMATCH_" + service.permission + "_" + service.processName,
                )
                return null
            }
            app.uid
        } catch (exception: PackageManager.NameNotFoundException) {
            ModuleLog.warn("SIDEBAR_PACKAGE_MISSING", exception)
            null
        } catch (exception: RuntimeException) {
            ModuleLog.warn("SIDEBAR_TARGET_FAILED", exception)
            null
        }
    }
}
