package com.cliptray.tray;

import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SystemTrayManager implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(SystemTrayManager.class.getName());
    private static final Color MENU_BACKGROUND = new Color(32, 32, 32);
    private static final Color MENU_TEXT = Color.WHITE;

    private final Runnable openHistory;
    private final Runnable openSettings;
    private final Runnable exit;
    private final List<JMenuItem> menuItems = new ArrayList<>();
    private final List<ActionListener> menuListeners = new ArrayList<>();
    private TrayIcon trayIcon;
    private MouseAdapter trayMouseListener;
    private JPopupMenu popupMenu;
    private PopupMenuListener popupMenuListener;
    private JWindow popupAnchor;
    private volatile boolean closed;

    public SystemTrayManager(Runnable openHistory, Runnable openSettings, Runnable exit) {
        this.openHistory = openHistory;
        this.openSettings = openSettings;
        this.exit = exit;
    }

    public boolean install() {
        if (trayIcon != null) {
            return true;
        }
        if (!SystemTray.isSupported()) {
            LOGGER.warning("System tray is not supported; use Ctrl+Alt+V to open ClipTray.");
            return false;
        }

        closed = false;
        createPopupMenu();
        trayIcon = new TrayIcon(createIcon(), "ClipTray");
        trayIcon.setImageAutoSize(true);
        trayMouseListener = new MouseAdapter() {
            private boolean popupGestureHandled;

            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON3 && !popupGestureHandled) {
                    showPopupAt(event.getXOnScreen(), event.getYOnScreen());
                }
                if (event.getButton() == MouseEvent.BUTTON3) {
                    popupGestureHandled = false;
                } else if (event.getButton() == MouseEvent.BUTTON1) {
                    onEventThread(openHistory);
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    showPopupAt(event.getXOnScreen(), event.getYOnScreen());
                    popupGestureHandled = true;
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (event.isPopupTrigger() && !popupGestureHandled) {
                    showPopupAt(event.getXOnScreen(), event.getYOnScreen());
                    popupGestureHandled = true;
                }
            }
        };
        trayIcon.addMouseListener(trayMouseListener);
        try {
            SystemTray.getSystemTray().add(trayIcon);
            return true;
        } catch (AWTException | SecurityException exception) {
            LOGGER.log(Level.WARNING, "Unable to add ClipTray to the system tray", exception);
            close();
            return false;
        }
    }

    @Override
    public void close() {
        closed = true;
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::cleanup);
            return;
        }
        cleanup();
    }

    private void cleanup() {
        try {
            if (trayIcon != null && SystemTray.isSupported()) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
        } catch (SecurityException exception) {
            LOGGER.log(Level.WARNING, "Unable to remove ClipTray from the system tray", exception);
        } finally {
            if (trayIcon != null && trayMouseListener != null) {
                trayIcon.removeMouseListener(trayMouseListener);
            }
            if (popupMenu != null) {
                popupMenu.setVisible(false);
                if (popupMenuListener != null) {
                    popupMenu.removePopupMenuListener(popupMenuListener);
                }
                for (int index = 0; index < menuItems.size(); index++) {
                    menuItems.get(index).removeActionListener(menuListeners.get(index));
                }
                popupMenu.removeAll();
            }
            disposePopupAnchor();
            menuItems.clear();
            menuListeners.clear();
            trayIcon = null;
            trayMouseListener = null;
            popupMenu = null;
            popupMenuListener = null;
        }
    }

    private void createPopupMenu() {
        popupMenu = new JPopupMenu();
        popupMenu.setLightWeightPopupEnabled(false);
        popupMenu.putClientProperty("JPopupMenu.borderCornerRadius", 10);
        popupMenu.setBackground(MENU_BACKGROUND);

        addMenuItem("Open clipboard history", openHistory);
        addMenuItem("Settings", openSettings);
        popupMenu.addSeparator();
        addMenuItem("Exit", exit);
        popupMenuListener = new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
                disposePopupAnchor();
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent event) {
                disposePopupAnchor();
            }
        };
        popupMenu.addPopupMenuListener(popupMenuListener);
    }

    private void addMenuItem(String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.setOpaque(true);
        item.setBackground(MENU_BACKGROUND);
        item.setForeground(MENU_TEXT);
        item.setBorder(javax.swing.BorderFactory.createEmptyBorder(9, 14, 9, 24));
        item.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        ActionListener listener = event -> {
            if (!closed) {
                action.run();
            }
        };
        item.addActionListener(listener);
        menuItems.add(item);
        menuListeners.add(listener);
        popupMenu.add(item);
    }

    private void showPopupAt(int screenX, int screenY) {
        onEventThread(() -> {
            if (closed || popupMenu == null || !popupMenu.isEnabled()) {
                return;
            }
            if (popupMenu.isVisible()) {
                popupMenu.setVisible(false);
                return;
            }

            Point location = clampToScreen(screenX, screenY, popupMenu.getPreferredSize().width,
                    popupMenu.getPreferredSize().height);
            popupAnchor = new JWindow();
            popupAnchor.setFocusableWindowState(true);
            popupAnchor.setBackground(new Color(0, 0, 0, 0));
            popupAnchor.setSize(1, 1);
            popupAnchor.setLocation(location);
            popupAnchor.setVisible(true);
            popupMenu.show(popupAnchor.getContentPane(), 0, 0);
        });
    }

    private static Point clampToScreen(int x, int y, int popupWidth, int popupHeight) {
        GraphicsConfiguration configuration = configurationAt(x, y);
        Rectangle bounds = configuration.getBounds();
        Insets insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int minX = bounds.x + insets.left;
        int minY = bounds.y + insets.top;
        int maxX = bounds.x + bounds.width - insets.right - popupWidth;
        int maxY = bounds.y + bounds.height - insets.bottom - popupHeight;
        int adjustedY = y;
        if (adjustedY > maxY) {
            adjustedY -= popupHeight;
        }
        return new Point(Math.max(minX, Math.min(x, maxX)),
                Math.max(minY, Math.min(adjustedY, maxY)));
    }

    private static GraphicsConfiguration configurationAt(int x, int y) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            if (configuration.getBounds().contains(x, y)) {
                return configuration;
            }
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
    }

    private void disposePopupAnchor() {
        if (popupAnchor != null) {
            popupAnchor.setVisible(false);
            popupAnchor.dispose();
            popupAnchor = null;
        }
    }

    private static void onEventThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private static BufferedImage createIcon() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(32, 32, 32));
            graphics.fillRoundRect(1, 1, 14, 14, 4, 4);
            graphics.setColor(Color.WHITE);
            graphics.fillRoundRect(4, 3, 8, 10, 2, 2);
            graphics.setColor(new Color(90, 90, 90));
            graphics.drawLine(6, 6, 10, 6);
            graphics.drawLine(6, 8, 10, 8);
            graphics.drawLine(6, 10, 9, 10);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
