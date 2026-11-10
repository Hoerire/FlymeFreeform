package io.github.hoerire.flymefreeform.hook

import android.util.Log
import io.github.hoerire.flymefreeform.config.ModulePreferences
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 在小窗菜单里追加一项「扇形菜单」，并接管它的点击。
 *
 * ColorOS 16/17 的菜单由 FlexibleMenuManager 构建：mPopupListItems 是菜单项列表，
 * 四个 append*ItemIfApplicable 方法负责逐项追加；点击统一由 onItemClick 按 position 分发，
 * 菜单项本身没有点击回调。因此做法是：追加一项，并在 position 命中它时自行处理。
 */
internal class FlexibleMenuEntryInstaller(
    private val module: XposedModule,
) {
    @Volatile private var entryIndex = -1

    fun install(loader: ClassLoader) {
        val managerClass = runCatching {
            loader.loadClass(MANAGER_CLASS)
        }.getOrNull() ?: run {
            module.log(Log.WARN, TAG, "FLEXIBLE_MENU_CLASS_UNAVAILABLE", null)
            return
        }
        hookAppend(managerClass)
        hookItemClick(managerClass)
    }

    /**
     * 菜单构建入口：16 是 initPopupList()，17 是 initPopupList(Z)；两者都存在。
     * 构建完成后补一项（用标题去重，重复弹出不叠加）。
     */
    private fun hookAppend(managerClass: Class<*>) {
        val append = managerClass.declaredMethods.firstOrNull { method ->
            method.name == "initPopupList"
        } ?: managerClass.declaredMethods.firstOrNull { method ->
            method.name.startsWith("append") && method.parameterTypes.size == 3
        } ?: run {
            module.log(Log.WARN, TAG, "FLEXIBLE_MENU_APPEND_UNAVAILABLE", null)
            return
        }
        append.isAccessible = true
        module
            .hook(append)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.systemui.flexible_menu_entry")
            .intercept { chain ->
                val result = chain.proceed()
                runCatching { appendEntry(chain.thisObject) }
                    .onFailure { module.log(Log.WARN, TAG, "FLEXIBLE_MENU_ENTRY_FAILED", it) }
                result
            }
        module.log(Log.INFO, TAG, "FLEXIBLE_MENU_APPEND_HOOKED_" + append.name, null)
    }

    private fun appendEntry(manager: Any?) {
        manager ?: return
        val field = manager.javaClass.getDeclaredField("mPopupListItems").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val items = field.get(manager) as? ArrayList<Any> ?: return
        val loader = manager.javaClass.classLoader
        val builderClass = loader.loadClass(BUILDER_CLASS)
        val already = items.any { item ->
            runCatching {
                item.javaClass.getMethod("getTitle").invoke(item) as? String
            }.getOrNull() == ENTRY_TITLE
        }
        if (already) {
            entryIndex = items.indexOfFirst { item ->
                runCatching {
                    item.javaClass.getMethod("getTitle").invoke(item) as? String
                }.getOrNull() == ENTRY_TITLE
            }
            return
        }
        val builder = builderClass.getDeclaredConstructor().newInstance()
        builderClass.getMethod("reset").invoke(builder)
        builderClass.getMethod("setTitle", String::class.java).invoke(builder, ENTRY_TITLE)
        builderClass.getMethod("setIsEnable", Boolean::class.javaPrimitiveType).invoke(builder, true)
        val item = builderClass.getMethod("build").invoke(builder)
        items.add(item)
        entryIndex = items.size - 1
        module.log(Log.INFO, TAG, "FLEXIBLE_MENU_ENTRY_ADDED_" + entryIndex, null)
    }

    /** 点击分发：position 命中我们那一项时自己处理。 */
    private fun hookItemClick(managerClass: Class<*>) {
        val click = managerClass.declaredMethods.firstOrNull { method ->
            method.name.contains("lambda") && method.parameterTypes.size == 5 &&
                method.parameterTypes[3] == Int::class.javaPrimitiveType
        } ?: run {
            module.log(Log.WARN, TAG, "FLEXIBLE_MENU_CLICK_UNAVAILABLE", null)
            return
        }
        click.isAccessible = true
        module
            .hook(click)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.systemui.flexible_menu_click")
            .intercept { chain ->
                val position = chain.args.getOrNull(3) as? Int ?: -1
                if (entryIndex >= 0 && position == entryIndex) {
                    module.log(Log.INFO, TAG, "FLEXIBLE_MENU_ENTRY_CLICKED", null)
                    val manager = chain.args.getOrNull(0)
                    // 先收起菜单，再请 system_server 画扇形（菜单是 SystemUI 的弹窗，留着会挡住扇形）。
                    runCatching {
                        manager?.javaClass
                            ?.getMethod("dismissPopupWindowMenu")
                            ?.invoke(manager)
                    }
                    runCatching {
                        val context = (manager?.javaClass?.getMethod("getDisplayContext")?.invoke(manager)
                            as? android.content.Context)
                        val intent = android.content.Intent(ModulePreferences.ACTION_SHOW_RADIAL)
                        intent.setPackage("android")
                        context?.sendBroadcast(intent)
                        module.log(Log.INFO, TAG, "FLEXIBLE_MENU_ENTRY_BROADCAST", null)
                    }.onFailure { module.log(Log.WARN, TAG, "FLEXIBLE_MENU_ENTRY_BROADCAST_FAILED", it) }
                    null
                } else {
                    chain.proceed()
                }
            }
        module.log(Log.INFO, TAG, "FLEXIBLE_MENU_CLICK_HOOKED", null)
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val MANAGER_CLASS = "com.oplus.flexibletask.menu.FlexibleMenuManager"
        const val BUILDER_CLASS = "com.coui.appcompat.poplist.PopupListItem\$Builder"
        const val ENTRY_TITLE = "扇形菜单"
    }
}