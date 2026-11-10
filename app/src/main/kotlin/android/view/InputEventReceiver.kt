package android.view

/** 编译期声明，见 [InputChannel]。 */
abstract class InputEventReceiver(
    channel: InputChannel,
    looper: android.os.Looper,
) {
    open fun onInputEvent(event: InputEvent) {}
    fun finishInputEvent(event: InputEvent, handled: Boolean) {}
    fun dispose() {}
}
