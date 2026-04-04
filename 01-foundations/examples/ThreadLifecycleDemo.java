/**
 * ThreadLifecycleDemo
 *
 * Demonstrates all six Thread.State values defined in the Thread.State enum:
 *   NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED
 *
 * Each state is triggered deliberately and printed to stdout so the
 * progression is clearly visible. Named threads are used throughout
 * so that thread dumps and stack traces are easy to read.
 *
 * Run command:
 *   javac ThreadLifecycleDemo.java && java ThreadLifecycleDemo
 */
public class ThreadLifecycleDemo {

    // ---------------------------------------------------------------------------
    // Shared monitor objects used to park threads in specific states.
    // ---------------------------------------------------------------------------
    private static final Object BLOCKED_LOCK  = new Object();
    private static final Object WAITING_LOCK  = new Object();

    // A volatile flag used to signal the WAITING thread to stop.
    private static volatile boolean notifyWaiter = false;

    // ---------------------------------------------------------------------------
    // Helper: print a labelled state reading with consistent formatting.
    // ---------------------------------------------------------------------------
    static void printState(String label, Thread t) {
        System.out.printf("  %-28s -> %s%n", label, t.getState());
    }

    // ---------------------------------------------------------------------------
    // Inner task: demonstrates BLOCKED state.
    // The monitor on BLOCKED_LOCK is held by main before this thread starts,
    // so this thread will immediately enter BLOCKED when it tries to enter
    // the synchronized block.
    // ---------------------------------------------------------------------------
    static class BlockedTask implements Runnable {
        @Override
        public void run() {
            // This synchronized attempt will block because main holds the lock.
            synchronized (BLOCKED_LOCK) {
                // Once unblocked, just return immediately.
                System.out.println("  [blocked-thread] acquired BLOCKED_LOCK, returning");
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Inner task: demonstrates WAITING state via Object.wait().
    // ---------------------------------------------------------------------------
    static class WaitingTask implements Runnable {
        @Override
        public void run() {
            synchronized (WAITING_LOCK) {
                while (!notifyWaiter) {
                    try {
                        WAITING_LOCK.wait(); // enters WAITING until notify()
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            System.out.println("  [waiting-thread] was notified, returning");
        }
    }

    // ---------------------------------------------------------------------------
    // Inner task: demonstrates TIMED_WAITING via Thread.sleep().
    // ---------------------------------------------------------------------------
    static class SleepingTask implements Runnable {
        @Override
        public void run() {
            try {
                System.out.println("  [sleeping-thread] going to sleep for 5 seconds");
                Thread.sleep(5_000); // long sleep so main can observe TIMED_WAITING
            } catch (InterruptedException e) {
                // Interruption is expected — used to wake this thread after observation.
                Thread.currentThread().interrupt();
                System.out.println("  [sleeping-thread] interrupted, returning");
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Inner task: demonstrates RUNNABLE state — tight CPU loop.
    // ---------------------------------------------------------------------------
    static class RunnableTask implements Runnable {
        // volatile so the loop termination flag is visible across threads.
        volatile boolean done = false;

        @Override
        public void run() {
            long count = 0;
            while (!done) {
                count++; // spin to stay RUNNABLE
            }
            System.out.println("  [runnable-thread] loop ended, count=" + count);
        }
    }

    // ---------------------------------------------------------------------------
    // main
    // ---------------------------------------------------------------------------
    public static void main(String[] args) throws InterruptedException {

        System.out.println("=== Thread.State demonstration ===");
        System.out.println();

        // ------------------------------------------------------------------
        // 1. NEW
        // ------------------------------------------------------------------
        System.out.println("--- State: NEW ---");
        Thread newThread = new Thread(() -> {}, "demo-new");
        printState("before start()", newThread); // NEW
        System.out.println();

        // ------------------------------------------------------------------
        // 2. RUNNABLE
        // ------------------------------------------------------------------
        System.out.println("--- State: RUNNABLE ---");
        RunnableTask runnableTask = new RunnableTask();
        Thread runnableThread = new Thread(runnableTask, "demo-runnable");
        runnableThread.start();
        Thread.sleep(50); // give the thread time to enter the spin loop
        printState("while spinning", runnableThread); // RUNNABLE
        runnableTask.done = true;
        runnableThread.join();
        printState("after join", runnableThread); // TERMINATED (preview)
        System.out.println();

        // ------------------------------------------------------------------
        // 3. BLOCKED
        // ------------------------------------------------------------------
        System.out.println("--- State: BLOCKED ---");
        Thread blockedThread = new Thread(new BlockedTask(), "demo-blocked");

        // Acquire the lock on the main thread so that blockedThread will block.
        synchronized (BLOCKED_LOCK) {
            blockedThread.start();
            Thread.sleep(100); // give blockedThread time to attempt the synchronized block
            printState("waiting for monitor", blockedThread); // BLOCKED
            // Exiting synchronized block releases the lock; blockedThread can proceed.
        }
        blockedThread.join();
        printState("after join", blockedThread); // TERMINATED
        System.out.println();

        // ------------------------------------------------------------------
        // 4. WAITING
        // ------------------------------------------------------------------
        System.out.println("--- State: WAITING ---");
        Thread waitingThread = new Thread(new WaitingTask(), "demo-waiting");
        waitingThread.start();
        Thread.sleep(100); // let waitingThread enter wait()
        printState("inside wait()", waitingThread); // WAITING

        // Wake the waiting thread.
        synchronized (WAITING_LOCK) {
            notifyWaiter = true;
            WAITING_LOCK.notify();
        }
        waitingThread.join();
        printState("after notify + join", waitingThread); // TERMINATED
        System.out.println();

        // ------------------------------------------------------------------
        // 5. TIMED_WAITING
        // ------------------------------------------------------------------
        System.out.println("--- State: TIMED_WAITING ---");
        Thread sleepingThread = new Thread(new SleepingTask(), "demo-timed-waiting");
        sleepingThread.start();
        Thread.sleep(100); // let sleepingThread enter sleep()
        printState("inside sleep()", sleepingThread); // TIMED_WAITING

        // Interrupt to wake it early (sleep() throws InterruptedException).
        sleepingThread.interrupt();
        sleepingThread.join();
        printState("after interrupt + join", sleepingThread); // TERMINATED
        System.out.println();

        // ------------------------------------------------------------------
        // 6. TERMINATED
        // ------------------------------------------------------------------
        System.out.println("--- State: TERMINATED ---");
        Thread termThread = new Thread(() ->
            System.out.println("  [term-thread] running briefly then returning"),
            "demo-terminated");
        termThread.start();
        termThread.join();
        printState("after run() returned", termThread); // TERMINATED
        System.out.println();

        System.out.println("=== All states demonstrated ===");
    }
}
