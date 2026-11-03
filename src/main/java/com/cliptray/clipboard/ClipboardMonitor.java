package com.cliptray.clipboard;

import com.cliptray.domain.ClipboardRepository;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ClipboardMonitor implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(ClipboardMonitor.class.getName());
    private static final long POLL_INTERVAL_MILLIS = 500;

    private final ClipboardRepository repository;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String previousContent;
    private volatile int previousHash;

    public ClipboardMonitor(ClipboardRepository repository) {
        this.repository = repository;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "cliptray-clipboard-monitor");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.scheduleWithFixedDelay(
                    this::pollClipboard, 0, POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            started.set(false);
            if (!closed.get()) {
                throw exception;
            }
        }
    }

    private void pollClipboard() {
        if (closed.get()) {
            return;
        }
        try {
            Transferable transferable = Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .getContents(null);
            if (transferable == null || !transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return;
            }

            Object value = transferable.getTransferData(DataFlavor.stringFlavor);
            if (!(value instanceof String content) || content.isBlank()) {
                return;
            }

            int contentHash = content.hashCode();
            if (contentHash == previousHash && content.equals(previousContent)) {
                return;
            }

            repository.saveOrTouchLatest(content, System.currentTimeMillis());
            previousHash = contentHash;
            previousContent = content;
        } catch (HeadlessException | IllegalStateException | SecurityException exception) {
            LOGGER.log(Level.WARNING, "Unable to access the system clipboard", exception);
        } catch (java.awt.datatransfer.UnsupportedFlavorException | IOException exception) {
            LOGGER.log(Level.FINE, "Clipboard contents changed while being read", exception);
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to save clipboard contents", exception);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    LOGGER.warning("Clipboard monitor is still stopping; clipboard access may be blocked");
                }
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
