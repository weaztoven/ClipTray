package com.cliptray.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.UIManager;
import java.util.logging.Logger;

public final class UiTheme {
    private static final Logger LOGGER = Logger.getLogger(UiTheme.class.getName());

    private UiTheme() {
    }

    public static void initialize() {
        FlatLaf.registerCustomDefaultsSource("com.cliptray.ui");
        UIManager.put("Component.arc", 12);
        UIManager.put("Button.arc", 12);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        if (!FlatDarkLaf.setup()) {
            LOGGER.warning("FlatLaf could not be installed; falling back to the platform look and feel.");
        }
    }
}
