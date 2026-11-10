package android.view;

/**
 * 编译期占位类。
 *
 * android.view.InputChannel 是 framework 的 @hide 类，公开 android.jar 里不存在，
 * 但模块里要把它当类型用（getInputChannel() 的返回值和 InputEventReceiver 的构造参数）。
 * 运行时 PathClassLoader 会先委托 BootClassLoader 命中系统里的真实类，这个定义永远不会被加载。
 */
public final class InputChannel {
    private InputChannel() {}
}
