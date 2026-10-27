package com.learntrack.util;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Plain-Java serial executor running tasks strictly sequentially in FIFO order on a daemon thread.
 * Layer rules: util layer has no JavaFX dependencies.
 */
public class SerialExecutor {

    private final ThreadPoolExecutor executor;
    private final BlockingQueue<Runnable> workQueue;

    public SerialExecutor() {
        this.workQueue = new LinkedBlockingQueue<>();
        this.executor = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                this.workQueue,
                r -> {
                    Thread t = new Thread(r, "LearnTrack-SerialWorker");
                    t.setDaemon(true);
                    return t;
                }
        );
    }

    public Future<?> submit(Runnable task) {
        return executor.submit(task);
    }

    public void execute(Runnable task) {
        executor.execute(task);
    }

    /**
     * Standard shutdown: stops accepting new tasks, waits up to 2 seconds for tasks to complete,
     * and forces termination via {@code shutdownNow()} if timeout is exceeded.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Shuts down the executor gracefully without interrupting an actively running task prematurely:
     * <ol>
     *   <li>Stops accepting any new tasks via {@code shutdown()}.</li>
     *   <li>Drops all tasks currently queued that have not yet started execution by clearing the work queue.</li>
     *   <li>Allows the task currently running on the worker thread (if any) to run to completion without interruption.</li>
     *   <li>Waits up to {@code timeoutMillis} milliseconds for that active task to finish.</li>
     *   <li>Only if the running task does not complete within {@code timeoutMillis} (or if the waiting thread
     *       is interrupted) is {@code shutdownNow()} called to forcefully interrupt the worker thread.</li>
     * </ol>
     *
     * @param timeoutMillis maximum time in milliseconds to wait for the actively executing task to finish
     */
    public void shutdownGracefully(long timeoutMillis) {
        executor.shutdown();
        workQueue.clear();
        try {
            if (!executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isShutdown() {
        return executor.isShutdown();
    }
}

