package io.github.hoerire.flymefreeform.hook

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 系统进程内访问厂商私有成员的统一入口。
 *
 * 各 Hook 分别实现字段/方法查找会导致行为差异（有的沿继承链找、有的只找本类，
 * 有的按参数个数匹配、有的按参数类型匹配），排查问题时很难判断某一处到底怎么取的值。
 * 这里把语义固定下来：
 *
 * - 查找一律沿继承链向上，命中即返回；厂商常把访问器放在父类。
 * - [readField] / [findMethod] 找不到时返回 null，由调用方决定是否降级；
 *   [requireMethod] 找不到时抛 [NoSuchMethodException]，用于「缺了就没法继续」的场景。
 * - [call] 按参数个数定位方法，[findMethod] 按精确参数类型定位，二者不可互换：
 *   同名重载较多时（例如 `Task.resize`）必须用后者。
 */
internal object OemReflection {

    fun loadOrNull(loader: ClassLoader, name: String): Class<*>? =
        runCatching { loader.loadClass(name) }.getOrNull()

    fun readField(instance: Any?, name: String): Any? {
        if (instance == null) return null
        val field = findField(instance.javaClass, name) ?: return null
        return runCatching { field.get(instance) }.getOrNull()
    }

    fun readStaticField(type: Class<*>, name: String): Any? {
        val field = findField(type, name) ?: return null
        return runCatching { field.get(null) }.getOrNull()
    }

    fun writeField(instance: Any?, name: String, value: Any?): Boolean {
        if (instance == null) return false
        val field = findField(instance.javaClass, name) ?: return false
        return runCatching { field.set(instance, value) }.isSuccess
    }

    fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current!!.getDeclaredField(name) }
                .getOrNull()
                ?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    fun findMethod(type: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current!!.getDeclaredMethod(name, *parameterTypes) }
                .getOrNull()
                ?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        // 部分厂商类把访问器声明在接口上，只查 declared 方法会漏掉。
        return runCatching { type.getMethod(name, *parameterTypes) }
            .getOrNull()
            ?.apply { isAccessible = true }
    }

    fun findMethodByArity(type: Class<*>, name: String, parameterCount: Int): Method? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                current!!.declaredMethods.firstOrNull {
                    it.name == name && it.parameterCount == parameterCount
                }
            }.getOrNull()?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    fun requireMethod(type: Class<*>, name: String, parameterCount: Int): Method =
        findMethodByArity(type, name, parameterCount) ?: throw NoSuchMethodException(name)

    /** 按参数个数调用；返回值取不到（方法缺失或抛异常）时为 null。 */
    fun call(instance: Any?, name: String, vararg args: Any?): Any? {
        if (instance == null) return null
        val method = findMethodByArity(instance.javaClass, name, args.size) ?: return null
        return runCatching { method.invoke(instance, *args) }.getOrNull()
    }

    fun callNoArg(instance: Any?, name: String): Any? {
        if (instance == null) return null
        val method = findMethod(instance.javaClass, name) ?: return null
        return runCatching { method.invoke(instance) }.getOrNull()
    }

    /** 只关心调用是否成功，例如无返回值的状态更新函数。 */
    fun callBoolean(instance: Any?, name: String, vararg args: Any?): Boolean {
        if (instance == null) return false
        val method = findMethodByArity(instance.javaClass, name, args.size) ?: return false
        return runCatching { method.invoke(instance, *args) }.isSuccess
    }
}
