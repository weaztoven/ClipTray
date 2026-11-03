package com.cliptray.ui;

import com.cliptray.domain.ClipboardEntry;
import com.cliptray.domain.ClipboardRepository;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class HistoryWindow {
    private static final Logger LOGGER = Logger.getLogger(HistoryWindow.class.getName());
    private static final int HISTORY_LIMIT = 50;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a").withZone(ZoneId.systemDefault());
    private static final Color SURFACE = new Color(0x20, 0x20, 0x20);
    private static final Color SEARCH_SURFACE = new Color(0x25, 0x25, 0x25);
    private static final Color CARD = new Color(0x25, 0x25, 0x25);
    private static final Color CARD_HOVER = new Color(0x2c, 0x2c, 0x2c);
    private static final Color OUTLINE = new Color(0x33, 0x33, 0x33);
    private static final Color OUTLINE_HOVER = new Color(0x3d, 0x3d, 0x3d);
    private static final Color MUTED = new Color(0xa0, 0xa0, 0xa0);
    private static final Color TEXT = Color.WHITE;

    private final ClipboardRepository repository;
    private final JFrame frame = new JFrame("ClipTray");
    private final JTextField searchField = new JTextField();
    private final JPanel cardList = new JPanel();
    private final JLabel statusLabel = new JLabel(" ");
    private final AtomicLong searchSequence = new AtomicLong();
    private final Timer searchTimer;
    private SwingWorker<List<ClipboardEntry>, Void> activeLoad;
    private ClipboardEntry selectedEntry;
    private boolean closing;

    public HistoryWindow(ClipboardRepository repository) {
        this.repository = repository;
        searchTimer = new Timer(180, event -> reload(searchField.getText()));
        searchTimer.setRepeats(false);
        buildWindow();
        reload("");
    }

    public void toggle() {
        onEventThread(() -> {
            if (frame.isVisible()) {
                frame.setVisible(false);
            } else {
                showAndFocus();
            }
        });
    }

    public void show() {
        onEventThread(this::showAndFocus);
    }

    public void dispose() {
        onEventThread(() -> {
            closing = true;
            searchTimer.stop();
            searchSequence.incrementAndGet();
            if (activeLoad != null) {
                activeLoad.cancel(true);
                activeLoad = null;
            }
            frame.dispose();
        });
    }

    private void buildWindow() {
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(620, 480));
        frame.setSize(760, 660);
        frame.setLocationByPlatform(true);

        JPanel root = new JPanel(new BorderLayout(0, 20));
        root.setBorder(BorderFactory.createEmptyBorder(26, 28, 22, 28));
        root.setBackground(SURFACE);

        JLabel titleLabel = new JLabel("Clipboard history");
        titleLabel.putClientProperty("FlatLaf.style", "font: bold +5");
        titleLabel.setForeground(TEXT);

        JLabel subtitleLabel = new JLabel("Your recent snippets, ready when you are");
        subtitleLabel.setForeground(MUTED);

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(titleLabel);
        heading.add(Box.createVerticalStrut(5));
        heading.add(subtitleLabel);

        JPanel searchShell = new RoundedPanel(26);
        searchShell.setLayout(new BorderLayout(10, 0));
        searchShell.setBackground(SEARCH_SURFACE);
        searchShell.setBorder(BorderFactory.createEmptyBorder(3, 16, 3, 16));
        searchShell.setPreferredSize(new Dimension(100, 50));
        searchShell.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        JLabel searchIcon = new JLabel(new SearchIcon(MUTED));
        searchIcon.setBorder(BorderFactory.createEmptyBorder(0, 1, 0, 0));
        searchField.setToolTipText("Search clipboard history");
        searchField.putClientProperty("JTextField.placeholderText", "Search your clipboard");
        searchField.putClientProperty("JTextField.showClearButton", true);
        searchField.setOpaque(false);
        searchField.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        searchShell.add(searchIcon, BorderLayout.WEST);
        searchShell.add(searchField, BorderLayout.CENTER);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(heading);
        top.add(Box.createVerticalStrut(20));
        top.add(searchShell);
        root.add(top, BorderLayout.NORTH);

        cardList.setOpaque(false);
        cardList.setLayout(new BoxLayout(cardList, BoxLayout.Y_AXIS));
        JScrollPane scrollPane = new JScrollPane(cardList);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        scrollPane.getVerticalScrollBar().putClientProperty("JScrollBar.showButtons", false);
        root.add(scrollPane, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setOpaque(false);
        statusLabel.setForeground(MUTED);
        footer.add(statusLabel, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        JButton copyButton = createActionButton("Copy", new ActionIcon(ActionIcon.Kind.COPY), true);
        copyButton.addActionListener(event -> copySelected());
        JButton deleteButton = createActionButton("Delete", new ActionIcon(ActionIcon.Kind.DELETE), false);
        deleteButton.addActionListener(event -> deleteSelected());
        JButton refreshButton = createActionButton("Refresh", new ActionIcon(ActionIcon.Kind.REFRESH), false);
        refreshButton.addActionListener(event -> reload(searchField.getText()));
        actions.add(copyButton);
        actions.add(deleteButton);
        actions.add(refreshButton);
        footer.add(actions, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        frame.setContentPane(root);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                scheduleSearch();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                scheduleSearch();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                scheduleSearch();
            }
        });

        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "hide-history");
        frame.getRootPane().getActionMap().put("hide-history", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                frame.setVisible(false);
            }
        });
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent event) {
                SwingUtilities.invokeLater(() -> {
                    if (frame.isDisplayable() && frame.isVisible() && !frame.isFocused()) {
                        frame.setVisible(false);
                    }
                });
            }
        });
    }

    private void reload(String keyword) {
        if (!SwingUtilities.isEventDispatchThread()) {
            onEventThread(() -> reload(keyword));
            return;
        }
        if (closing) {
            return;
        }

        long sequence = searchSequence.incrementAndGet();
        if (activeLoad != null) {
            activeLoad.cancel(true);
        }
        statusLabel.setText("Loading history…");
        activeLoad = new SwingWorker<>() {
            @Override
            protected List<ClipboardEntry> doInBackground() throws SQLException {
                if (keyword == null || keyword.isBlank()) {
                    return repository.findRecent(HISTORY_LIMIT);
                }
                return repository.search(keyword, HISTORY_LIMIT);
            }

            @Override
            protected void done() {
                if (closing || sequence != searchSequence.get() || isCancelled()) {
                    return;
                }
                try {
                    List<ClipboardEntry> entries = get();
                    renderEntries(entries);
                    statusLabel.setText(entries.size() + " items");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Loading history was interrupted");
                } catch (ExecutionException | CancellationException exception) {
                    LOGGER.log(Level.SEVERE, "Unable to load clipboard history",
                            exception instanceof ExecutionException ? exception.getCause() : exception);
                    statusLabel.setText("Could not load history");
                } finally {
                    if (sequence == searchSequence.get()) {
                        activeLoad = null;
                    }
                }
            }
        };
        activeLoad.execute();
    }

    private void renderEntries(List<ClipboardEntry> entries) {
        cardList.removeAll();
        selectedEntry = null;
        for (ClipboardEntry entry : entries) {
            ClipboardCard card = new ClipboardCard(entry);
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            cardList.add(card);
            cardList.add(Box.createVerticalStrut(10));
        }
        if (entries.isEmpty()) {
            JLabel emptyLabel = new JLabel("No clipboard items found");
            emptyLabel.setForeground(MUTED);
            emptyLabel.setBorder(BorderFactory.createEmptyBorder(20, 4, 20, 4));
            emptyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            cardList.add(emptyLabel);
        }
        cardList.revalidate();
        cardList.repaint();
    }

    private void scheduleSearch() {
        searchTimer.restart();
    }

    private void showAndFocus() {
        reload(searchField.getText());
        if (!frame.isVisible()) {
            frame.setLocationRelativeTo(null);
        }
        frame.setState(JFrame.NORMAL);
        frame.setAlwaysOnTop(true);
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
        searchField.requestFocusInWindow();
        Timer releaseTopmost = new Timer(350, event -> frame.setAlwaysOnTop(false));
        releaseTopmost.setRepeats(false);
        releaseTopmost.start();
    }

    private void select(ClipboardEntry entry) {
        selectedEntry = entry;
        for (Component component : cardList.getComponents()) {
            if (component instanceof ClipboardCard card) {
                card.setSelected(card.entry.id() == entry.id());
            }
        }
        statusLabel.setText("Selected · " + TIMESTAMP_FORMAT.format(
                Instant.ofEpochMilli(entry.timestamp())));
    }

    private void copySelected() {
        if (selectedEntry == null) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(selectedEntry.content()), null);
            statusLabel.setText("Copied to clipboard");
        } catch (java.awt.HeadlessException | IllegalStateException | SecurityException exception) {
            LOGGER.log(Level.WARNING, "Unable to copy the selected history item", exception);
            JOptionPane.showMessageDialog(frame, "Unable to access the system clipboard.",
                    "ClipTray", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteSelected() {
        if (selectedEntry == null) {
            return;
        }
        long id = selectedEntry.id();
        statusLabel.setText("Deleting item…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws SQLException {
                repository.delete(id);
                return null;
            }

            @Override
            protected void done() {
                if (closing) {
                    return;
                }
                try {
                    get();
                    selectedEntry = null;
                    reload(searchField.getText());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Deletion was interrupted");
                } catch (ExecutionException | CancellationException exception) {
                    LOGGER.log(Level.SEVERE, "Unable to delete clipboard history item",
                            exception instanceof ExecutionException ? exception.getCause() : exception);
                    statusLabel.setText("Could not delete the selected item");
                    JOptionPane.showMessageDialog(frame, "Unable to delete the selected item.",
                            "ClipTray", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private JButton createActionButton(String label, javax.swing.Icon icon, boolean primary) {
        JButton button = new FluentButton(label, icon, primary);
        button.setIconTextGap(8);
        button.setFocusable(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMargin(new Insets(9, 15, 9, 15));
        button.setForeground(TEXT);
        return button;
    }

    private final class ClipboardCard extends RoundedPanel {
        private final ClipboardEntry entry;
        private final Timer colorTimer;
        private Color displayedColor = CARD;
        private Color targetColor = CARD;
        private boolean selected;
        private boolean hovered;

        private ClipboardCard(ClipboardEntry entry) {
            super(28);
            this.entry = entry;
            colorTimer = new Timer(15, event -> animateColor());
            colorTimer.setCoalesce(true);
            setLayout(new BorderLayout(0, 10));
            setBorder(BorderFactory.createEmptyBorder(16, 18, 15, 18));
            setBackground(CARD);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 132));
            setPreferredSize(new Dimension(560, 120));
            setMinimumSize(new Dimension(320, 120));

            JTextArea preview = new JTextArea(previewText(entry.content()));
            preview.setEditable(false);
            preview.setFocusable(false);
            preview.setLineWrap(true);
            preview.setWrapStyleWord(true);
            preview.setRows(2);
            preview.setOpaque(false);
            preview.setBorder(BorderFactory.createEmptyBorder());
            preview.setForeground(TEXT);
            preview.setFont(preview.getFont().deriveFont(Font.PLAIN, 14f));
            preview.setToolTipText(entry.content());

            JPanel metadata = new JPanel(new BorderLayout());
            metadata.setOpaque(false);
            JLabel timestamp = new JLabel(TIMESTAMP_FORMAT.format(
                    Instant.ofEpochMilli(entry.timestamp())));
            timestamp.setForeground(MUTED);
            timestamp.setFont(timestamp.getFont().deriveFont(Font.PLAIN, 11f));

            String type = entry.content().contains("\n") || entry.content().contains("\r")
                    ? "MULTILINE TEXT" : "TEXT";
            JPanel badge = new RoundedPanel(18);
            badge.setLayout(new BorderLayout());
            badge.setBackground(new Color(0x33, 0x33, 0x33));
            badge.setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 9));
            JLabel badgeText = new JLabel(type, SwingConstants.CENTER);
            badgeText.setForeground(new Color(0xd0, 0xd0, 0xd0));
            badgeText.setFont(badgeText.getFont().deriveFont(Font.BOLD, 9.5f));
            badge.add(badgeText, BorderLayout.CENTER);
            metadata.add(timestamp, BorderLayout.WEST);
            metadata.add(badge, BorderLayout.EAST);

            add(preview, BorderLayout.CENTER);
            add(metadata, BorderLayout.SOUTH);
            MouseAdapter cardMouse = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hovered = true;
                    updateTargetColor();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    SwingUtilities.invokeLater(ClipboardCard.this::updateHoverFromPointer);
                }

                @Override
                public void mouseClicked(MouseEvent event) {
                    select(entry);
                    if (event.getClickCount() == 2) {
                        copySelected();
                    }
                }
            };
            addMouseListener(cardMouse);
            preview.addMouseListener(cardMouse);
            timestamp.addMouseListener(cardMouse);
            badge.addMouseListener(cardMouse);
            badgeText.addMouseListener(cardMouse);
            setToolTipText(entry.content());
        }

        private void setSelected(boolean selected) {
            this.selected = selected;
            updateTargetColor();
        }

        private void updateHoverFromPointer() {
            if (!isShowing()) {
                hovered = false;
            } else {
                java.awt.PointerInfo pointer = java.awt.MouseInfo.getPointerInfo();
                if (pointer == null) {
                    hovered = false;
                } else {
                    java.awt.Point point = pointer.getLocation();
                    SwingUtilities.convertPointFromScreen(point, this);
                    hovered = contains(point);
                }
            }
            updateTargetColor();
        }

        private void updateTargetColor() {
            targetColor = selected || hovered ? CARD_HOVER : CARD;
            if (!colorTimer.isRunning()) {
                colorTimer.start();
            }
        }

        private void animateColor() {
            displayedColor = blend(displayedColor, targetColor, 0.3f);
            if (colorDistance(displayedColor, targetColor) < 3) {
                displayedColor = targetColor;
                colorTimer.stop();
            }
            repaint();
        }

        @Override
        public void removeNotify() {
            colorTimer.stop();
            super.removeNotify();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(displayedColor);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 28, 28);
                g.setColor(hovered || selected ? OUTLINE_HOVER : OUTLINE);
                g.setStroke(new BasicStroke(1f));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 28, 28);
            } finally {
                g.dispose();
            }
        }
    }

    private static Color blend(Color from, Color to, float amount) {
        return new Color(
                Math.round(from.getRed() + (to.getRed() - from.getRed()) * amount),
                Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * amount),
                Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * amount));
    }

    private static int colorDistance(Color first, Color second) {
        return Math.abs(first.getRed() - second.getRed())
                + Math.abs(first.getGreen() - second.getGreen())
                + Math.abs(first.getBlue() - second.getBlue());
    }

    private static String previewText(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').strip();
        String[] lines = normalized.split("\n", -1);
        StringBuilder preview = new StringBuilder();
        int maxLines = 2;
        int maxCharacters = 220;
        for (int index = 0; index < lines.length && index < maxLines; index++) {
            if (preview.length() > 0) {
                preview.append('\n');
            }
            int remaining = maxCharacters - preview.length();
            if (remaining <= 0) {
                break;
            }
            String line = lines[index];
            if (line.length() > remaining) {
                preview.append(line, 0, Math.max(0, remaining - 1)).append('…');
                return preview.toString();
            }
            preview.append(line);
        }
        if (lines.length > maxLines || normalized.length() > preview.length()) {
            preview.append('…');
        }
        return preview.toString();
    }

    private static void onEventThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private static class RoundedPanel extends JPanel {
        private final int arc;

        private RoundedPanel(int arc) {
            this.arc = arc;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(getBackground());
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                g.setColor(OUTLINE);
                g.setStroke(new BasicStroke(1f));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private static final class FluentButton extends JButton {
        private static final Color NORMAL = new Color(0x32, 0x32, 0x32);
        private static final Color HOVER = new Color(0x40, 0x40, 0x40);
        private static final Color PRESSED = new Color(0x29, 0x29, 0x29);

        private final Timer colorTimer;
        private Color displayedColor = NORMAL;

        private FluentButton(String text, javax.swing.Icon icon, boolean primary) {
            super(text, icon);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setBorder(BorderFactory.createEmptyBorder());
            putClientProperty("JButton.buttonType", "borderless");
            putClientProperty("JButton.arc", 14);
            if (primary) {
                setFont(getFont().deriveFont(Font.BOLD));
            }
            colorTimer = new Timer(15, event -> animateButtonColor());
            colorTimer.setCoalesce(true);
            getModel().addChangeListener(event -> {
                if (!colorTimer.isRunning()) {
                    colorTimer.start();
                }
            });
        }

        private void animateButtonColor() {
            Color target = getModel().isPressed() ? PRESSED
                    : getModel().isRollover() ? HOVER : NORMAL;
            displayedColor = blend(displayedColor, target, 0.32f);
            if (colorDistance(displayedColor, target) < 3) {
                displayedColor = target;
                colorTimer.stop();
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(displayedColor);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 28, 28);
                g.setColor(OUTLINE);
                g.setStroke(new BasicStroke(1f));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 28, 28);
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }

        @Override
        public void removeNotify() {
            colorTimer.stop();
            super.removeNotify();
        }
    }

    private static final class SearchIcon implements javax.swing.Icon {
        private final Color color;

        private SearchIcon(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawOval(x + 2, y + 2, 10, 10);
                g.drawLine(x + 11, y + 11, x + 16, y + 16);
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }
    }

    private static final class ActionIcon implements javax.swing.Icon {
        private enum Kind {
            COPY, DELETE, REFRESH
        }

        private final Kind kind;

        private ActionIcon(Kind kind) {
            this.kind = kind;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(component.isEnabled() ? component.getForeground() : MUTED);
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                switch (kind) {
                    case COPY -> {
                        g.drawRoundRect(x + 5, y + 2, 10, 12, 2, 2);
                        g.drawRoundRect(x + 2, y + 5, 10, 12, 2, 2);
                    }
                    case DELETE -> {
                        g.drawLine(x + 3, y + 5, x + 17, y + 5);
                        g.drawRoundRect(x + 5, y + 6, 10, 11, 2, 2);
                        g.drawLine(x + 8, y + 3, x + 12, y + 3);
                        g.drawLine(x + 8, y + 9, x + 8, y + 14);
                        g.drawLine(x + 12, y + 9, x + 12, y + 14);
                    }
                    case REFRESH -> {
                        g.drawArc(x + 3, y + 3, 13, 13, 35, 285);
                        g.drawLine(x + 14, y + 3, x + 17, y + 7);
                        g.drawLine(x + 14, y + 3, x + 10, y + 4);
                    }
                }
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 20;
        }

        @Override
        public int getIconHeight() {
            return 20;
        }
    }
}
