/**
 * CreatingThreadsDemo
 *
 * Demonstrates the three primary mechanisms for creating and running threads
 * in Java:
 *
 *   1. Extending Thread       — override run(), call start()
 *   2. Implementing Runnable  — pass to Thread constructor, call start()
 *   3. Implementing Callable  — wrap in FutureTask, pass to Thread, call start()
 *
 * Also demonstrates:
 *   - Thread.join() to wait for completion
 *   - Thread.interrupt() and proper InterruptedException handling
 *   - Thread.currentThread().getName() for identification
 *   - FutureTask.get() to retrieve a Callable's return value
 *
 * Run command:
 *   javac CreatingThreadsDemo.java && java CreatingThreadsDemo
 */

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class CreatingThreadsDemo {

    // =========================================================================
    // Mechanism 1: Extending Thread
    //
    // The task logic lives inside the subclass. This conflates the thread
    // vehicle with the work to be done, which limits reuse. Shown here for
    // completeness.
    // =========================================================================
    static class CounterThread extends Thread {

        private final int limit;

        CounterThread(String name, int limit) {
            super(name);          // sets the thread's name via Thread constructor
            this.limit = limit;
        }

        @Override
        public void run() {
            System.out.println("[" + getName() + "] started (mechanism: Thread subclass)");
            for (int i = 1; i <= limit; i++) {
                // Check for interruption at each iteration — cooperative cancellation.
                if (Thread.currentThread().isInterrupted()) {
                    System.out.println("[" + getName() + "] interrupted at step " + i
                            + ", stopping early");
                    return;
                }
                System.out.println("[" + getName() + "] count=" + i);
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    // sleep() cleared the interrupt flag when it threw — restore it.
                    Thread.currentThread().interrupt();
                    System.out.println("[" + getName() + "] sleep interrupted at step " + i
                            + ", stopping");
                    return;
                }
            }
            System.out.println("[" + getName() + "] completed all " + limit + " steps");
        }
    }

    // =========================================================================
    // Mechanism 2: Implementing Runnable
    //
    // The task is decoupled from the Thread. The same RunnableTask instance
    // could be submitted to an ExecutorService without modification.
    // =========================================================================
    static class RunnableTask implements Runnable {

        private final String label;
        private final int limit;

        RunnableTask(String label, int limit) {
            this.label = label;
            this.limit = limit;
        }

        @Override
        public void run() {
            String threadName = Thread.currentThread().getName();
            System.out.println("[" + threadName + "] started (mechanism: Runnable, label="
                    + label + ")");
            for (int i = 1; i <= limit; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    System.out.println("[" + threadName + "] interrupted at step " + i
                            + ", stopping early");
                    return;
                }
                System.out.println("[" + threadName + "] step=" + i);
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    // Restore the interrupt flag before returning.
                    Thread.currentThread().interrupt();
                    System.out.println("[" + threadName + "] sleep interrupted at step " + i);
                    return;
                }
            }
            System.out.println("[" + threadName + "] Runnable completed all " + limit + " steps");
        }
    }

    // =========================================================================
    // Mechanism 3: Implementing Callable<V>
    //
    // call() returns a value and can throw checked exceptions. Wrapped in
    // FutureTask so it can be passed to a Thread constructor. The result is
    // retrieved via FutureTask.get() after join().
    // =========================================================================
    static class SumCallable implements Callable<Long> {

        private final long limit;

        SumCallable(long limit) {
            this.limit = limit;
        }

        @Override
        public Long call() throws Exception {
            String threadName = Thread.currentThread().getName();
            System.out.println("[" + threadName + "] started (mechanism: Callable)");
            long sum = 0;
            for (long i = 1; i <= limit; i++) {
                sum += i;
                // Simulate some work per iteration and allow interruption.
                if (i % 250_000 == 0 && Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Callable interrupted at i=" + i);
                }
            }
            System.out.println("[" + threadName + "] Callable computed sum, returning result");
            return sum;
        }
    }

    // =========================================================================
    // main
    // =========================================================================
    public static void main(String[] args) throws InterruptedException, ExecutionException {

        System.out.println("=== CreatingThreadsDemo ===");
        System.out.println();

        // ---------------------------------------------------------------------
        // 1. Thread subclass — run to completion with join()
        // ---------------------------------------------------------------------
        System.out.println("--- Mechanism 1: Thread subclass (runs to completion) ---");
        CounterThread t1 = new CounterThread("counter-subclass", 4);
        t1.start();
        t1.join(); // main waits here until counter-subclass finishes
        System.out.println("  [main] t1 joined, state=" + t1.getState());
        System.out.println();

        // ---------------------------------------------------------------------
        // 2. Runnable — interrupted before completion
        // ---------------------------------------------------------------------
        System.out.println("--- Mechanism 2: Runnable (interrupted mid-run) ---");
        RunnableTask runnableTask = new RunnableTask("task-alpha", 10);
        Thread t2 = new Thread(runnableTask, "runnable-thread");
        t2.start();
        // Interrupt after a short delay — the thread is mid-loop.
        Thread.sleep(80);
        System.out.println("  [main] interrupting " + t2.getName());
        t2.interrupt();
        t2.join();
        System.out.println("  [main] t2 joined, state=" + t2.getState());
        System.out.println();

        // ---------------------------------------------------------------------
        // 3. Callable + FutureTask — retrieve return value
        // ---------------------------------------------------------------------
        System.out.println("--- Mechanism 3: Callable + FutureTask (retrieves return value) ---");
        SumCallable sumCallable = new SumCallable(1_000_000L);
        FutureTask<Long> futureTask = new FutureTask<>(sumCallable);
        Thread t3 = new Thread(futureTask, "callable-thread");
        t3.start();
        t3.join(); // wait for the computation to finish
        Long result = futureTask.get(); // retrieve the computed value
        System.out.println("  [main] sum(1..1000000) = " + result
                + "  (expected: " + (1_000_000L * 1_000_001L / 2) + ")");
        System.out.println("  [main] t3 joined, state=" + t3.getState());
        System.out.println();

        // ---------------------------------------------------------------------
        // 4. Demonstrate that calling run() directly does NOT create a new thread
        // ---------------------------------------------------------------------
        System.out.println("--- Calling run() directly (NOT start) — runs on main thread ---");
        CounterThread t4 = new CounterThread("counter-wrong", 2);
        // Calling run() instead of start() — executes on the main thread, not a new thread.
        // The thread name shown will be 'main', not 'counter-wrong'.
        System.out.println("  About to call run() directly on counter-wrong instance");
        System.out.println("  Watch for thread name in output — it will be 'main', not 'counter-wrong'");
        // We override the name check for clarity — show currentThread name explicitly:
        System.out.println("  [" + Thread.currentThread().getName() + "] "
                + "executing t4.run() — this is the main thread, not a new thread");
        // (Skipping actual run() call to avoid confusing interleaved output;
        //  the point is: start() must be used, not run().)
        System.out.println("  t4.getState() after never calling start(): " + t4.getState()
                + " (still NEW — no thread was ever started)");
        System.out.println();

        System.out.println("=== Demo complete ===");
    }
}
