package com.learntrack.ui;

import javafx.scene.Scene;

/**
 * Applies the design system to a scene. The light theme carries the tokens; the dark theme
 * only overrides them, so switching is a stylesheet swap.
 */
public final class Theme {

    /** SettingsDAO key holding the persisted choice; light is the default. */
    public static final String SETTING_KEY = "ui_theme";

    private static final String LIGHT = "/css/learntrack.css";
    private static final String DARK = "/css/learntrack-dark.css";

    private static boolean darkActive;

    private Theme() {
    }

    public static boolean isDark(String settingValue) {
        return "dark".equalsIgnoreCase(settingValue);
    }

    /** True when the dark stylesheet is currently active; dialogs use this to match. */
    public static boolean isDarkActive() {
        return darkActive;
    }

    public static void apply(Scene scene, boolean dark) {
        if (scene == null) {
            return;
        }
        darkActive = dark;
        scene.getStylesheets().clear();
        addStylesheet(scene, LIGHT);
        if (dark) {
            addStylesheet(scene, DARK);
        }
    }

    private static void addStylesheet(Scene scene, String resource) {
        java.net.URL url = Theme.class.getResource(resource);
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        }
    }
}
