/*
 * FutureDemo — Main
 *
 * Demonstrates the full Future lifecycle through four tasks and invokeAll:
 *   1. Successful Callable: Future.get(timeout) returns the result.
 *   2. Failing Callable: Future.get() throws ExecutionException; getCause() unwraps
 *      the original exception — getMessage() on ExecutionException itself is not useful.
 *   3. Slow Callable: get(500ms) throws TimeoutException; cancel(true) sends an interrupt
 *      to the running thread; future transitions to CANCELLED state.
 *   4. Cancellation: cancel(true) before completion; get() throws CancellationException.
 *   5. invokeAll: submits a batch and blocks until all futures are done, then collects results.
 */
package examples.futuredemo;

import java.util.List;
import java.util.concurrent.*;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(4,
                new NamedThreadFactory("future-worker"));

        // Task 1: completes in 200ms, returns a result.
        Callable<String> task1 = () -> {
            System.out.printf("[%s] task-1 running%n", Thread.currentThread().getName());
            Thread.sleep(200);
            return "result-from-task-1";
        };

        // Task 2: throws a checked exception — wrapped in ExecutionException by the Future.
        Callable<String> task2 = () -> {
            System.out.printf("[%s] task-2 running%n", Thread.currentThread().getName());
            Thread.sleep(100);
            throw new ServiceException("upstream database connection refused");
        };

        // Task 3: sleeps 2s — deliberately longer than the 500ms get() timeout.
        Callable<String> task3 = () -> {
            System.out.printf("[%s] task-3 running%n", Thread.currentThread().getName());
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                // cancel(true) sends an interrupt; respond by restoring the flag and exiting.
                System.out.printf("[%s] task-3 received interrupt%n",
                        Thread.currentThread().getName());
                Thread.currentThread().interrupt();
                return "task-3-interrupted";
            }
            return "task-3-result";
        };

        // Task 4: sleeps 5s — will be cancelled before it finishes.
        Callable<String> task4 = () -> {
            System.out.printf("[%s] task-4 running%n", Thread.currentThread().getName());
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                System.out.printf("[%s] task-4 received interrupt%n",
                        Thread.currentThread().getName());
                Thread.currentThread().interrupt();
                return "task-4-interrupted";
            }
            return "task-4-result";
        };

        Future<String> future1 = executor.submit(task1);
        Future<String> future2 = executor.submit(task2);
        Future<String> future3 = executor.submit(task3);
        Future<String> future4 = executor.submit(task4);

        // Task 1: should complete well within the 500ms window.
        try {
            String result = future1.get(500, TimeUnit.MILLISECONDS);
            System.out.println("[main] task-1 result: " + result);
        } catch (ExecutionException e) {
            System.out.println("[main] task-1 failed: " + e.getCause().getMessage());
        } catch (TimeoutException e) {
            System.out.println("[main] task-1 timed out");
        }

        // Task 2: ExecutionException wraps the original ServiceException.
        // e.getCause() gives the actual exception; e.getMessage() is just a wrapper string.
        try {
            future2.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            System.out.println("[main] task-2 cause type:    " + e.getCause().getClass().getSimpleName());
            System.out.println("[main] task-2 cause message: " + e.getCause().getMessage());
        } catch (TimeoutException e) {
            System.out.println("[main] task-2 timed out unexpectedly");
        }

        // Task 3: TimeoutException fires because the task needs 2s but we wait only 500ms.
        // cancel(true) sends Thread.interrupt() to the running thread.
        try {
            future3.get(500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.out.println("[main] task-3 timed out after 500ms");
            boolean cancelled = future3.cancel(true);
            System.out.println("[main] cancel(true) returned: " + cancelled);
            System.out.println("[main] future3.isDone():      " + future3.isDone());
            System.out.println("[main] future3.isCancelled(): " + future3.isCancelled());
        } catch (ExecutionException e) {
            System.out.println("[main] task-3 execution exception: " + e.getCause().getMessage());
        }

        Thread.sleep(200); // let task-3 finish reacting to the interrupt

        // Task 4: cancel before natural completion; get() throws CancellationException.
        boolean cancelled = future4.cancel(true);
        System.out.println("[main] cancel(true) on task-4 returned: " + cancelled);
        System.out.println("[main] future4.isCancelled(): " + future4.isCancelled());
        System.out.println("[main] future4.isDone():      " + future4.isDone());
        try {
            future4.get();
        } catch (CancellationException e) {
            System.out.println("[main] task-4 get() threw CancellationException");
        } catch (ExecutionException e) {
            System.out.println("[main] unexpected ExecutionException: " + e.getCause().getMessage());
        }

        // invokeAll: submits all tasks and blocks until every future is done.
        // Returned futures are guaranteed to be in the done state — get() returns immediately.
        List<Callable<String>> batchTasks = List.of(
                () -> { Thread.sleep(100); return "batch-result-A"; },
                () -> { Thread.sleep(200); return "batch-result-B"; },
                () -> { Thread.sleep(50);  return "batch-result-C"; }
        );
        List<Future<String>> batchFutures = executor.invokeAll(batchTasks, 5, TimeUnit.SECONDS);
        for (int i = 0; i < batchFutures.size(); i++) {
            Future<String> f = batchFutures.get(i);
            try {
                System.out.println("[main] batch-" + (char)('A' + i) + " result: " + f.get());
            } catch (ExecutionException e) {
                System.out.println("[main] batch-" + (char)('A' + i) + " failed: "
                        + e.getCause().getMessage());
            } catch (CancellationException e) {
                System.out.println("[main] batch-" + (char)('A' + i) + " cancelled (timeout)");
            }
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
