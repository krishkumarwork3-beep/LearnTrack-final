package com.learntrack;

/**
 * Plain main-class launcher for the fat jar.
 *
 * JavaFX requires that the main class does NOT extend Application when loading
 * from a fat jar that does not have the JavaFX modules on the module path.
 * This thin wrapper calls Application.launch(MainApp.class, ...) from a class
 * that has no JavaFX superclass, satisfying the classloader requirement.
 *
 * Plan §7, Fix 7.
 */
public class Launcher {
    public static void main(String[] args) {
        javafx.application.Application.launch(MainApp.class, args);
    }
}
