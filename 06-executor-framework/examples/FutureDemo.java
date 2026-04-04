/**
 * Demonstrates Callable, Future.get() with timeout, ExecutionException, and invokeAll.
 *
 * Run: javac FutureDemo.java && java FutureDemo
 */

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class FutureDemo {

    // Named ThreadFactory for clear thread-dump attribution
    static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger(1);
        private final String prefix;

        NamedThreadFactory(String prefix) { this.prefix = prefix; }

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, prefix + "-" + count.getAndIncrement());
        }
    }

    // A checked exception to demonstrate ExecutionException wrapping
    static class ServiceException extends Exception {
        ServiceException(String msg) { super(msg); }
    }

    public static void main(String[] args) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(4,
                new NamedThreadFactory("future-worker"));

        // --- Task 1: completes in 200ms, returns a String ---
        Callable<String> task1 = () -> {
            System.out.printf("[%s] task-1 running%n", Thread.currentThread().getName());
            Thread.sleep(200);
            return "result-from-task-1";
        };

        // --- Task 2: throws a checked exception after 100ms ---
        Callable<String> task2 = () -> {
            System.out.printf("[%s] task-2 running%n", Thread.currentThread().getName());
            Thread.sleep(100);
            throw new ServiceException("upstream database connection refused");
        };

        // --- Task 3: sleeps 2000ms (will timeout on get(500ms)) ---
        Callable<String> task3 = () -> {
            System.out.printf("[%s] task-3 running (long-running, 2s)%n",
                    Thread.currentThread().getName());
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                System.out.printf("[%s] task-3 received interrupt%n",
                        Thread.currentThread().getName());
                Thread.currentThread().interrupt();
                return "task-3-interrupted";
            }
            return "task-3-result";
        };

        // --- Task 4: sleeps 5000ms, will be cancelled before it finishes ---
        Callable<String> task4 = () -> {
            System.out.printf("[%s] task-4 running (will be cancelled)%n",
                    Thread.currentThread().getName());
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                System.out.printf("[%s] task-4 received interrupt (cancelled)%n",
                        Thread.currentThread().getName());
                Thread.currentThread().interrupt();
                return "task-4-interrupted";
            }
            return "task-4-result";
        };

        System.out.println("=== Submitting all 4 tasks ===\n");
        Future<String> future1 = executor.submit(task1);
        Future<String> future2 = executor.submit(task2);
        Future<String> future3 = executor.submit(task3);
        Future<String> future4 = executor.submit(task4);

        // ---- Task 1: get with 500ms timeout — should succeed ----
        System.out.println("--- Task 1: get(500ms) ---");
        try {
            String result = future1.get(500, TimeUnit.MILLISECONDS);
            System.out.println("[main] task-1 result: " + result);
        } catch (ExecutionException e) {
            System.out.println("[main] task-1 failed: " + e.getCause().getMessage());
        } catch (TimeoutException e) {
            System.out.println("[main] task-1 timed out");
        }

        // ---- Task 2: ExecutionException — unwrap the cause ----
        System.out.println("\n--- Task 2: ExecutionException unwrapping ---");
        try {
            future2.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            System.out.println("[main] caught ExecutionException");
            System.out.println("[main] e.getMessage():         " + e.getMessage());
            System.out.println("[main] e.getCause().getClass(): " + e.getCause().getClass().getSimpleName());
            System.out.println("[main] e.getCause().getMessage(): " + e.getCause().getMessage());
        } catch (TimeoutException e) {
            System.out.println("[main] task-2 timed out unexpectedly");
        }

        // ---- Task 3: TimeoutException, then cancel ----
        System.out.println("\n--- Task 3: TimeoutException → cancel(true) ---");
        try {
            future3.get(500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.out.println("[main] task-3 timed out after 500ms");
            boolean cancelled = future3.cancel(true);
            System.out.println("[main] cancel(true) returned: " + cancelled);
            System.out.println("[main] future3.isDone(): " + future3.isDone());
            System.out.println("[main] future3.isCancelled(): " + future3.isCancelled());
        } catch (ExecutionException e) {
            System.out.println("[main] task-3 execution exception: " + e.getCause().getMessage());
        }

        // Let task-3 react to the interrupt
        Thread.sleep(200);

        // ---- Task 4: cancel before completion, show CancellationException ----
        System.out.println("\n--- Task 4: cancel before completion → CancellationException ---");
        boolean cancelled = future4.cancel(true);
        System.out.println("[main] cancel(true) on task-4 returned: " + cancelled);
        System.out.println("[main] future4.isCancelled(): " + future4.isCancelled());
        System.out.println("[main] future4.isDone(): " + future4.isDone());
        try {
            future4.get();
            System.out.println("[main] task-4 result (unexpected)");
        } catch (CancellationException e) {
            System.out.println("[main] task-4 get() threw CancellationException as expected");
        } catch (ExecutionException e) {
            System.out.println("[main] unexpected ExecutionException: " + e.getCause().getMessage());
        }

        // ---- invokeAll with 3 tasks ----
        System.out.println("\n--- invokeAll: 3 tasks, collect all results ---");
        List<Callable<String>> batchTasks = List.of(
                () -> { Thread.sleep(100); return "batch-result-A"; },
                () -> { Thread.sleep(200); return "batch-result-B"; },
                () -> { Thread.sleep(50);  return "batch-result-C"; }
        );
        List<Future<String>> batchFutures = executor.invokeAll(batchTasks, 5, TimeUnit.SECONDS);
        System.out.println("[main] invokeAll returned " + batchFutures.size() + " futures:");
        for (int i = 0; i < batchFutures.size(); i++) {
            Future<String> f = batchFutures.get(i);
            try {
                String val = f.get(); // already done — returns immediately
                System.out.println("[main] batch-" + (char)('A' + i) + " result: " + val);
            } catch (ExecutionException e) {
                System.out.println("[main] batch-" + (char)('A' + i) + " failed: " + e.getCause().getMessage());
            } catch (CancellationException e) {
                System.out.println("[main] batch-" + (char)('A' + i) + " was cancelled (timeout)");
            }
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        System.out.println("\nAll demos complete.");
    }
}
