package app.woom

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.platform.unix.X11
import java.awt.Window

/**
 * Keeps a window out of the taskbar and pager on Linux (X11) by setting _NET_WM_STATE_SKIP_TASKBAR.
 * Java's Window.Type.UTILITY is not passed on to every window manager (Cinnamon ignores it).
 * Must be called after each time the window is shown: the window manager drops the state on hiding.
 */
fun skipTaskbar(window: Window) {
    if (isWindows || System.getenv("WAYLAND_DISPLAY") != null && System.getenv("DISPLAY") == null) return
    runCatching {
        val x = X11.INSTANCE
        val display = x.XOpenDisplay(null) ?: return
        try {
            val event = X11.XEvent()
            event.type = X11.ClientMessage
            event.setType(X11.XClientMessageEvent::class.java)
            event.xclient.type = X11.ClientMessage
            event.xclient.send_event = 1
            event.xclient.display = display
            event.xclient.window = X11.Window(Native.getWindowID(window))
            event.xclient.message_type = x.XInternAtom(display, "_NET_WM_STATE", false)
            event.xclient.format = 32
            event.xclient.data.setType(Array<NativeLong>::class.java)
            event.xclient.data.l[0] = NativeLong(1) // _NET_WM_STATE_ADD
            event.xclient.data.l[1] = NativeLong(x.XInternAtom(display, "_NET_WM_STATE_SKIP_TASKBAR", false).toLong())
            event.xclient.data.l[2] = NativeLong(x.XInternAtom(display, "_NET_WM_STATE_SKIP_PAGER", false).toLong())
            event.xclient.data.l[3] = NativeLong(1) // source: application
            val mask = NativeLong(X11.SubstructureRedirectMask.toLong() or X11.SubstructureNotifyMask.toLong())
            x.XSendEvent(display, x.XDefaultRootWindow(display), 0, mask, event)
            x.XFlush(display)
        } finally {
            x.XCloseDisplay(display)
        }
    }.onFailure { System.err.println("Woom: could not hide the window from the taskbar: $it") }
}
