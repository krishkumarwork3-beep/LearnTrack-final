package com.learntrack.ui;

import com.learntrack.service.SemesterReplanService;
import com.learntrack.util.SerialExecutor;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.time.LocalDate;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Coordinates asynchronous semester replans on a dedicated serial worker thread.
 * Guarantees replans run strictly sequentially in submission order, never concurrently,
 * and notifies callbacks on the JavaFX Application Thread.
 */
public class ReplanRunner {

    private final SemesterReplanService replanService;
    private final SerialExecutor executor;
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private int pendingCount = 0;

    public ReplanRunner(SemesterReplanService replanService) {
        this(replanService, new SerialExecutor());
    }

    public ReplanRunner(SemesterReplanService replanService, SerialExecutor executor) {
        this.replanService = Objects.requireNonNull(replanService, "replanService must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    public ReadOnlyBooleanProperty busyProperty() {
        return busy;
    }

    public boolean isBusy() {
        return busy.get();
    }

    /**
     * Submits a replan for background execution.
     * Must be called from the JavaFX Application Thread.
     */
    /**
     * Convenience: submits a replan from today for the given semester.
     * Calls run() with null success/error callbacks (errors are shown in a default dialog).
     */
    public void runReplan(int semesterId) {
        run(semesterId, LocalDate.now(), null, null);
    }

    public void run(int semesterId, LocalDate fromDate, Runnable onSuccess, Consumer<Throwable> onError) {
        pendingCount++;
        busy.set(true);

        executor.execute(() -> {
            Throwable error = null;
            try {
                replanService.regenerateSemesterPlan(semesterId, fromDate);
            } catch (Throwable t) {
                error = t;
            }

            final Throwable finalError = error;
            try {
                Platform.runLater(() -> {
                    pendingCount--;
                    if (pendingCount <= 0) {
                        pendingCount = 0;
                        busy.set(false);
                    }

                    if (executor.isShutdown()) {
                        return;
                    }

                    if (finalError != null) {
                        finalError.printStackTrace();
                        if (onError != null) {
                            onError.accept(finalError);
                        } else {
                            showDefaultError(finalError);
                        }
                    } else {
                        if (onSuccess != null) {
                            try {
                                onSuccess.run();
                            } catch (Exception ex) {
                                ex.printStackTrace();
                                showDefaultError(ex);
                            }
                        }
                    }
                });
            } catch (IllegalStateException ignored) {
                // JavaFX toolkit may have shut down
            }
        });
    }

    private void showDefaultError(Throwable t) {
        UiKit.error(null, "Replanning Failed", t);
    }

    public void shutdown() {
        executor.shutdownGracefully(10_000);
    }
}
