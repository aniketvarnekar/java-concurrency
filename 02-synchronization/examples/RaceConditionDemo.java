/**
 * Demonstrates two classic race condition patterns:
 *
 *   1. Read-modify-write race: two threads each increment a shared counter
 *      100,000 times without synchronization. The final value is almost always
 *      less than the expected 200,000 because concurrent increments overwrite
 *      each other (lost update).
 *
 *   2. Check-then-act race: unsafe lazy initialization where two threads can
 *      both observe a null reference and both construct the "singleton", so the
 *      instance is created twice.
 *
 * Run:
 *   javac RaceConditionDemo.java && java RaceConditionDemo
 */
public class RaceConditionDemo {

    // -----------------------------------------------------------------------
    // Part 1: Read-modify-write — unsynchronized counter
    // -----------------------------------------------------------------------

    static int counter = 0; // shared mutable state -- no synchronization

    static void runUnsafeCounterDemo() throws InterruptedException {
        counter = 0;
        final int INCREMENTS = 100_000;

        Thread incrementer1 = new Thread(() -> {
            for (int i = 0; i < INCREMENTS; i++) {
                counter++; // NOT atomic: load, add 1, store
            }
        }, "incrementer-1");

        Thread incrementer2 = new Thread(() -> {
            for (int i = 0; i < INCREMENTS; i++) {
                counter++; // races with incrementer-1
            }
        }, "incrementer-2");

        incrementer1.start();
        incrementer2.start();
        incrementer1.join();
        incrementer2.join();

        System.out.println("--- Read-Modify-Write Race ---");
        System.out.println("  Expected : " + (2 * INCREMENTS));
        System.out.println("  Actual   : " + counter);
        System.out.println("  Lost updates: " + (2 * INCREMENTS - counter));
        System.out.println("  (actual < expected almost every run)");
    }

    // -----------------------------------------------------------------------
    // Part 2: Check-then-act — unsafe lazy initialization
    // -----------------------------------------------------------------------

    /**
     * A resource whose construction is observable: it records which thread
     * constructed it and a sequence number so we can tell if more than one
     * instance was created.
     */
    static class ExpensiveResource {
        private static final java.util.concurrent.atomic.AtomicInteger constructionCount
                = new java.util.concurrent.atomic.AtomicInteger(0);

        final int instanceNumber;
        final String constructedBy;

        ExpensiveResource() {
            this.instanceNumber = constructionCount.incrementAndGet();
            this.constructedBy  = Thread.currentThread().getName();
            // Simulate some initialization work that widens the race window
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        @Override
        public String toString() {
            return "ExpensiveResource#" + instanceNumber + "(by=" + constructedBy + ")";
        }
    }

    static ExpensiveResource sharedResource = null; // guarded by... nothing

    static ExpensiveResource getResourceUnsafe() {
        if (sharedResource == null) {              // (1) CHECK  -- race window starts here
            sharedResource = new ExpensiveResource(); // (2) ACT -- another thread may also be here
        }                                          //     both threads create a new instance
        return sharedResource;
    }

    static void runCheckThenActDemo() throws InterruptedException {
        sharedResource = null;
        ExpensiveResource.constructionCount.set(0);

        // We run multiple trials because the race is timing-dependent
        int racesDetected = 0;
        final int TRIALS = 20;

        for (int trial = 0; trial < TRIALS; trial++) {
            sharedResource = null;

            // Hold both threads at a barrier so they enter getResourceUnsafe together
            java.util.concurrent.CyclicBarrier barrier =
                    new java.util.concurrent.CyclicBarrier(2);

            ExpensiveResource[] results = new ExpensiveResource[2];

            Thread checker1 = new Thread(() -> {
                try { barrier.await(); } catch (Exception e) { Thread.currentThread().interrupt(); }
                results[0] = getResourceUnsafe();
            }, "checker-1");

            Thread checker2 = new Thread(() -> {
                try { barrier.await(); } catch (Exception e) { Thread.currentThread().interrupt(); }
                results[1] = getResourceUnsafe();
            }, "checker-2");

            checker1.start();
            checker2.start();
            checker1.join();
            checker2.join();

            // If the two threads got different instances, the race manifested
            if (results[0] != results[1]) {
                racesDetected++;
            }
        }

        System.out.println("\n--- Check-Then-Act Race ---");
        System.out.println("  Trials run   : " + TRIALS);
        System.out.println("  Races detected (two threads got different instances): " + racesDetected);
        System.out.println("  Total constructions across all trials: "
                + ExpensiveResource.constructionCount.get());
        System.out.println("  (if races > 0, multiple instances were created per trial)");
    }

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Race Condition Demo ===\n");

        runUnsafeCounterDemo();
        runCheckThenActDemo();

        System.out.println("\nFix for Part 1: use AtomicInteger.incrementAndGet() or synchronized.");
        System.out.println("Fix for Part 2: use synchronized, volatile + double-checked locking,");
        System.out.println("               or the initialization-on-demand holder idiom.");
    }
}
