package io.github.hoerire.free.platform.coloros

import java.io.InterruptedIOException
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType

/** 桌面输入适配的字节码证据；查询结果不持有 native bridge 或宿主对象。 */
internal object LauncherDexQuery {
    const val SERVICE_CLASS = "com.android.quickstep.OplusBaseTouchInteractionService"
    const val INPUT_MONITOR_CLASS = "android.view.InputMonitor"
    const val RULE_VERSION = "launcher-input-v1-dexkit-2.3.0"

    data class Symbols(
        val className: String = SERVICE_CLASS,
        val inputMethod: String,
        val monitorField: String,
        val wrapperClass: String,
        val pilferMethod: String,
    )

    fun find(bridge: DexKitBridge): Symbols {
        // 先按上游的固定类名找；找不到再按行为特征反查（ColorOS 17 的 OplusLauncher 被 R8 混淆，
        // 当前 ROM 上对应类是 Lq97;，类内仍保留 OplusBaseTouchInteractionService#onOverviewToggle 这样的日志字符串）。
        var serviceName = SERVICE_CLASS
        val service = bridge.getClassData(SERVICE_CLASS) ?: run {
            val probe = bridge.findMethod {
                matcher {
                    paramTypes("android.view.InputEvent")
                    returnType = "void"
                    usingEqStrings("Unknown event ", "onInputEvent(), mDeviceState user is not unlocked")
                }
            }.singleOrNull() ?: throw NoSuchMethodException("LAUNCHER_DEX_SERVICE_MISSING")
            (probe.declaredClass ?: throw NoSuchMethodException("LAUNCHER_DEX_SERVICE_MISSING")).also { serviceName = it.name }
        }
        val input = service.methods.findMethod {
            matcher {
                paramTypes("android.view.InputEvent")
                returnType = "void"
                usingEqStrings("Unknown event ", "onInputEvent(), mDeviceState user is not unlocked")
                addInvoke("Landroid/view/MotionEvent;->getActionMasked()I")
            }
        }.filter { !Modifier.isStatic(it.modifiers) }
            .singleOrNull() ?: throw NoSuchMethodException("LAUNCHER_DEX_INPUT_NOT_UNIQUE")

        // 从宿主字段类型向下定位真实抢占调用，排除同签名的空 pilferPointers 方法。
        val candidates = service.fields.filter { !Modifier.isStatic(it.modifiers) }.mapNotNull { owner ->
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("LAUNCHER_DEX_CANCELLED")
            val wrapper = owner.type
            val delegates = wrapper.fields.filter {
                it.typeName == INPUT_MONITOR_CLASS && !Modifier.isStatic(it.modifiers)
            }
            if (delegates.size != 1) return@mapNotNull null
            val delegate = delegates.single()
            val pilfer = wrapper.methods.findMethod {
                matcher {
                    paramTypes()
                    returnType = "void"
                    addInvoke("Landroid/view/InputMonitor;->pilferPointers()V")
                }
            }.filter { method ->
                !Modifier.isStatic(method.modifiers) && method.usingFields.any {
                    it.usingType == FieldUsingType.Read && it.field.descriptor == delegate.descriptor
                }
            }.singleOrNull() ?: return@mapNotNull null
            Symbols(serviceName, input.name, owner.name, owner.typeName, pilfer.name)
        }
        return candidates.singleOrNull()
            ?: throw NoSuchFieldException("LAUNCHER_DEX_MONITOR_NOT_UNIQUE")
    }
}
