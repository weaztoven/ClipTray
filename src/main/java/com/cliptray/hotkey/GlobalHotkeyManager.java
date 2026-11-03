package com.cliptray.hotkey;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GlobalHotkeyManager implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(GlobalHotkeyManager.class.getName());

    private final Runnable onHotkey;
    private final AtomicBoolean shortcutKeyDown = new AtomicBoolean();
    private final AtomicBoolean dispatchPending = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final NativeKeyListener listener = new NativeKeyListener() {
        @Override
        public void nativeKeyPressed(NativeKeyEvent event) {
            int modifiers = event.getModifiers();
            boolean shortcutPressed = event.getKeyCode() == NativeKeyEvent.VC_V
                    && (modifiers & NativeInputEvent.CTRL_MASK) != 0
                    && (modifiers & NativeInputEvent.ALT_MASK) != 0;
            if (shortcutPressed && !closed.get()
                    && shortcutKeyDown.compareAndSet(false, true)
                    && dispatchPending.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(() -> {
                    dispatchPending.set(false);
                    if (!closed.get()) {
                        try {
                            onHotkey.run();
                        } catch (RuntimeException exception) {
                            LOGGER.log(Level.SEVERE, "Unable to handle the global hotkey", exception);
                        }
                    }
                });
            }
        }

        @Override
        public void nativeKeyReleased(NativeKeyEvent event) {
            if (event.getKeyCode() == NativeKeyEvent.VC_V) {
                shortcutKeyDown.set(false);
            }
        }

        @Override
        public void nativeKeyTyped(NativeKeyEvent event) {
        }
    };

    private boolean hookRegistered;
    private boolean listenerRegistered;

    public GlobalHotkeyManager(Runnable onHotkey) {
        this.onHotkey = onHotkey;
    }

    public synchronized void start() throws NativeHookException {
        if (closed.get()) {
            throw new IllegalStateException("Global hotkey manager is already closed");
        }
        if (listenerRegistered) {
            return;
        }
        GlobalScreen.registerNativeHook();
        hookRegistered = true;
        try {
            GlobalScreen.addNativeKeyListener(listener);
            listenerRegistered = true;
        } catch (RuntimeException exception) {
            try {
                GlobalScreen.unregisterNativeHook();
                hookRegistered = false;
            } catch (NativeHookException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        shortcutKeyDown.set(false);
        if (listenerRegistered) {
            GlobalScreen.removeNativeKeyListener(listener);
            listenerRegistered = false;
        }
        if (!hookRegistered) {
            return;
        }
        try {
            GlobalScreen.unregisterNativeHook();
            hookRegistered = false;
        } catch (NativeHookException exception) {
            throw new IllegalStateException("Unable to unregister the global keyboard hook", exception);
        }
    }
}
