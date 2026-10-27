package com.learntrack.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class SerialExecutorTest {

    @Test
    void testTasksExecuteSequentiallyAndInOrder() throws InterruptedException {
        SerialExecutor executor = new SerialExecutor();
        List<Integer> executedOrder = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger concurrentRunning = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        int taskCount = 10;
        CountDownLatch latch = new CountDownLatch(taskCount);

        for (int i = 0; i < taskCount; i++) {
            final int index = i;
            executor.execute(() -> {
                int running = concurrentRunning.incrementAndGet();
                maxConcurrent.accumulateAndGet(running, Math::max);
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executedOrder.add(index);
                concurrentRunning.decrementAndGet();
                latch.countDown();
            });
        }

        boolean finished = latch.await(5, TimeUnit.SECONDS);
        assertTrue(finished, "All tasks should finish within timeout");
        assertEquals(1, maxConcurrent.get(), "Tasks must never execute concurrently");
        assertEquals(taskCount, executedOrder.size());
        for (int i = 0; i < taskCount; i++) {
            assertEquals(i, executedOrder.get(i), "Tasks must execute in FIFO order");
        }

        executor.shutdown();
        assertTrue(executor.isShutdown());
    }

    @Test
    void testShutdownTerminatesCleanly() {
        SerialExecutor executor = new SerialExecutor();
        executor.execute(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
        });
        executor.shutdown();
        assertTrue(executor.isShutdown());
    }

    @Test
    void testShutdownGracefullyLetsRunningTaskFinishAndDropsQueuedTasks() throws InterruptedException {
        SerialExecutor executor = new SerialExecutor();
        java.util.concurrent.atomic.AtomicBoolean runningTaskFinished = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean queuedTaskRan = new java.util.concurrent.atomic.AtomicBoolean(false);
        CountDownLatch startedLatch = new CountDownLatch(1);

        executor.execute(() -> {
            startedLatch.countDown();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            runningTaskFinished.set(true);
        });

        // Ensure task 1 has started executing on the single worker thread
        assertTrue(startedLatch.await(2, TimeUnit.SECONDS), "Task 1 should have started");

        // Queue task 2 (which should not start while worker is busy executing task 1)
        executor.execute(() -> {
            queuedTaskRan.set(true);
        });

        long start = System.currentTimeMillis();
        executor.shutdownGracefully(2000);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(runningTaskFinished.get(), "Running task should finish to completion");
        assertFalse(queuedTaskRan.get(), "Queued task should have been dropped and never run");
        assertTrue(elapsed < 2000, "shutdownGracefully should return immediately once the running task completes");
        assertTrue(executor.isShutdown(), "Executor should report isShutdown() true");
    }
}

