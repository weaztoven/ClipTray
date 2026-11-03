package com.cliptray.app;

import com.cliptray.clipboard.ClipboardMonitor;
import com.cliptray.domain.ClipboardRepository;
import com.cliptray.hotkey.GlobalHotkeyManager;
import com.cliptray.persistence.DatabaseManager;
import com.cliptray.persistence.SqliteClipboardRepository;
import com.cliptray.tray.SystemTrayManager;
import com.cliptray.ui.HistoryWindow;
import com.cliptray.ui.UiTheme;
import com.github.kwhat.jnativehook.NativeHookException;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ClipTrayApp implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(ClipTrayApp.class.getName());

    private final SingleInstanceLock instanceLock;
    private final ClipboardRepository repository;
    private final ClipboardMonitor clipboardMonitor;
    private final HistoryWindow historyWindow;
    private final GlobalHotkeyManager hotkeyManager;
    private final SystemTrayManager trayManager;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ClipTrayApp(SingleInstanceLock instanceLock, ClipboardRepository repository,
                        ClipboardMonitor clipboardMonitor,
                        HistoryWindow historyWindow) {
        this.instanceLock = instanceLock;
        this.repository = repository;
        this.clipboardMonitor = clipboardMonitor;
        this.historyWindow = historyWindow;
        this.hotkeyManager = new GlobalHotkeyManager(historyWindow::toggle);
        this.trayManager = new SystemTrayManager(
                historyWindow::show, this::showSettings, this::exit);
    }

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            LOGGER.warning("ClipTray requires a graphical desktop session and cannot run headlessly.");
            return;
        }

        UiTheme.initialize();

        SingleInstanceLock instanceLock = null;
        DatabaseManager databaseManager = null;
        ClipTrayApp app = null;
        try {
            Path dataDirectory = getDataDirectory();
            Files.createDirectories(dataDirectory);
            Optional<SingleInstanceLock> lock = SingleInstanceLock.tryAcquire(
                    dataDirectory.resolve("cliptray.lock"));
            if (lock.isEmpty()) {
                LOGGER.info("ClipTray is already running.");
                return;
            }
            instanceLock = lock.get();

            databaseManager = new DatabaseManager(dataDirectory.resolve("cliptray.db"));
            ClipboardRepository repository = new SqliteClipboardRepository(databaseManager);
            ClipboardMonitor clipboardMonitor = new ClipboardMonitor(repository);
            HistoryWindow historyWindow = createHistoryWindow(repository);
            app = new ClipTrayApp(instanceLock, repository,
                    clipboardMonitor, historyWindow);
            app.start();

            ClipTrayApp runningApp = app;
            Runtime.getRuntime().addShutdownHook(new Thread(runningApp::close, "cliptray-shutdown"));
        } catch (IOException | SQLException | InterruptedException | InvocationTargetException exception) {
            LOGGER.log(Level.SEVERE, "ClipTray could not start", exception);
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (app != null) {
                app.close();
            } else {
                closeQuietly(databaseManager, instanceLock);
            }
        }
    }

    private void start() {
        clipboardMonitor.start();
        try {
            hotkeyManager.start();
        } catch (NativeHookException | UnsatisfiedLinkError exception) {
            LOGGER.log(Level.WARNING,
                    "Global hotkey is unavailable; ClipTray may still be opened from the system tray",
                    exception);
        }
        trayManager.install();
    }

    private void showSettings() {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                null,
                "Clipboard polling interval: 500 ms\nHistory limit: 50 items\n"
                        + "Data directory: " + getDataDirectory(),
                "ClipTray Settings",
                JOptionPane.INFORMATION_MESSAGE));
    }

    private void exit() {
        close();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        clipboardMonitor.close();
        try {
            hotkeyManager.close();
        } catch (IllegalStateException exception) {
            LOGGER.log(Level.WARNING, "Unable to cleanly stop the global hotkey listener", exception);
        }
        trayManager.close();
        historyWindow.dispose();
        try {
            repository.close();
        } catch (SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to close the clipboard database", exception);
        }
        try {
            instanceLock.close();
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Unable to release the single-instance lock", exception);
        }
    }

    private static HistoryWindow createHistoryWindow(ClipboardRepository repository)
            throws InterruptedException, InvocationTargetException {
        HistoryWindow[] window = new HistoryWindow[1];
        SwingUtilities.invokeAndWait(() -> window[0] = new HistoryWindow(repository));
        return window[0];
    }

    private static Path getDataDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "ClipTray");
        }
        return Path.of(System.getProperty("user.home"), ".cliptray");
    }

    private static void closeQuietly(DatabaseManager databaseManager, SingleInstanceLock instanceLock) {
        if (databaseManager != null) {
            try {
                databaseManager.close();
            } catch (SQLException exception) {
                LOGGER.log(Level.WARNING, "Unable to close the clipboard database", exception);
            }
        }
        if (instanceLock != null) {
            try {
                instanceLock.close();
            } catch (IOException exception) {
                LOGGER.log(Level.WARNING, "Unable to release the single-instance lock", exception);
            }
        }
    }
}
