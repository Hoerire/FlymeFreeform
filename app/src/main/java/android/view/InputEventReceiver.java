package android.view;

/**
 * 编译期占位类，理由同 InputChannel。
 *
 * 只保留本模块用到的两个签名：构造 (InputChannel, Looper)、回调 onInputEvent、收尾 finishInputEvent。
 */
public abstract class InputEventReceiver {
    public InputEventReceiver(InputChannel inputChannel, android.os.Looper looper) {}

    public void onInputEvent(InputEvent event) {}

    public final void finishInputEvent(InputEvent event, boolean handled) {}
}
