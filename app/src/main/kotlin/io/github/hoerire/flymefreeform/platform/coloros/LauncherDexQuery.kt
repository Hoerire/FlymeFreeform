package io.github.hoerire.flymefreeform.platform.coloros

import java.io.InterruptedIOException
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType

/** 桌面输入适配的字节码证据；查询结果不持有 native bridge 或宿主对象。 */
internal object LauncherDexQuery {
    const val SERVICE_CLASS = "com.android.quickstep.OplusBaseTouchInteractionService"
    /** ColorOS 17 起厂商基类被移除，输入入口回到 AOSP 的 TouchInteractionService。 */
    const val AOSP_SERVICE_CLASS = "com.android.quickstep.TouchInteractionService"
    const val INPUT_MONITOR_CLASS = "android.view.InputMonitor"
    const val RULE_VERSION = "launcher-input-v3-dexkit-2.3.0"

    /**
     * 解析结果。
     *
     * [pilferMethod] 为空表示走"按返回值消费"的通道（17 的混淆构建）：
     * 输入入口变成一个 (TouchInteractionService, InputEvent) 的 boolean 方法，
     * 返回 true 即表示这次事件被消费，桌面的上滑不会开始。
     */
    data class Symbols(
        val inputMethod: String,
        val monitorField: String?,
        val wrapperClass: String?,
        val pilferMethod: String?,
        val serviceClass: String = SERVICE_CLASS,
        /** 输入入口所在类；为空表示就是 [serviceClass] 本身。 */
        val inputOwnerClass: String? = null,
    )

    fun find(bridge: DexKitBridge): Symbols {
        val vendor = bridge.getClassData(SERVICE_CLASS)
        if (vendor == null) return findOnAospService(bridge)
        val service = vendor
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
            Symbols(input.name, owner.name, owner.typeName, pilfer.name)
        }
        return candidates.singleOrNull()
            ?: throw NoSuchFieldException("LAUNCHER_DEX_MONITOR_NOT_UNIQUE")
    }

    /**
     * ColorOS 17：厂商基类消失，输入入口被挪进混淆类，签名为
     * (TouchInteractionService, InputEvent) -> boolean，返回 true 即消费。
     * 按参数类型全局找唯一匹配，不依赖类名。
     */
    private fun findOnAospService(bridge: DexKitBridge): Symbols {
        // 首选 InputEventReceiver 的回调：桌面刚拿到触摸的位置，
        // 在这里返回 true 桌面整条手势链都不会启动（16 的 onInputEvent 等价物）。
        val receiver = bridge.findMethod {
            matcher {
                name = "onInputEvent"
                paramTypes("android.view.InputEvent")
                returnType = "boolean"
            }
        }.singleOrNull()
        if (receiver != null) {
            return Symbols(
                inputMethod = receiver.name,
                monitorField = null,
                wrapperClass = null,
                pilferMethod = null,
                serviceClass = AOSP_SERVICE_CLASS,
                inputOwnerClass = receiver.className,
            )
        }
        // 退而求其次：InputConsumerProxy 形态——已在上滑接管之后，只能"事后"抑制。
        val hits = bridge.findMethod {
            matcher {
                paramTypes(AOSP_SERVICE_CLASS, "android.view.InputEvent")
                returnType = "boolean"
            }
        }
        val single = hits.singleOrNull()
            ?: throw NoSuchMethodException("LAUNCHER_DEX_INPUT_NOT_UNIQUE")
        return Symbols(
            inputMethod = single.name,
            monitorField = null,
            wrapperClass = null,
            pilferMethod = null,
            serviceClass = AOSP_SERVICE_CLASS,
            inputOwnerClass = single.className,
        )
    }
}
