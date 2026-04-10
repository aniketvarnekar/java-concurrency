/*
 * RaceConditionDemo — Main
 *
 * Demonstrates two classic race condition patterns:
 *   1. Read-modify-write: two threads increment a shared counter without
 *      synchronization, producing lost updates.
 *   2. Check-then-act: unsafe lazy initialization where both threads observe
 *      null and each construct the object, violating the singleton contract.
 *
 * The printed expected vs actual values and construction counts are the
 * observable evidence of each race.
 */
package examples.raceconditiondemo;

import java.util.concurrent.CyclicBarrier;

public class Main {

    // --- Part 1: read-modify-write race ---

    static void demonstrateReadModifyWrite() throws InterruptedException {
        final int INCREMENTS = 100_000;
        int[] count = {0}; // shared mutable state — no synchronization

        Thread t1 = new Thread(new UnsafeCounter(count, INCREMENTS), "incrementer-1");
        Thread t2 = new Thread(new UnsafeCounter(count, INCREMENTS), "incrementer-2");
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // actual is almost always less than expected because concurrent increments
        // overwrite each other; the difference is the number of lost updates.
        System.out.println("Read-modify-write race:");
        System.out.println("  expected : " + (2 * INCREMENTS));
        System.out.println("  actual   : " + count[0]);
        System.out.println("  lost     : " + (2 * INCREMENTS - count[0]));
    }

    // --- Part 2: check-then-act race ---

    static ExpensiveResource shared = null; // guarded by nothing

    static ExpensiveResource getUnsafe() {
        if (shared == null) {                 // CHECK — another thread may also see null here
            shared = new ExpensiveResource(); // ACT  — both threads may reach this line
        }
        return shared;
    }

    static void demonstrateCheckThenAct() throws InterruptedException {
        final int TRIALS = 20;
        int racesDetected = 0;

        for (int trial = 0; trial < TRIALS; trial++) {
            shared = null;
            ExpensiveResource.resetConstructionCount();

            // CyclicBarrier releases both threads simultaneously to maximize
            // the probability they both observe null before either writes.
            CyclicBarrier barrier = new CyclicBarrier(2);
            ExpensiveResource[] results = new ExpensiveResource[2];

            Thread c1 = new Thread(() -> {
                try { barrier.await(); } catch (Exception e) { Thread.currentThread().interrupt(); }
                results[0] = getUnsafe();
            }, "checker-1");

            Thread c2 = new Thread(() -> {
                try { barrier.await(); } catch (Exception e) { Thread.currentThread().interrupt(); }
                results[1] = getUnsafe();
            }, "checker-2");

            c1.start();
            c2.start();
            c1.join();
            c2.join();

            // Different references mean both threads constructed their own instance.
            if (results[0] != results[1]) racesDetected++;
        }

        System.out.println("\nCheck-then-act race (" + TRIALS + " trials):");
        System.out.println("  races detected (two different instances returned): " + racesDetected);
        System.out.println("  total constructions: " + ExpensiveResource.getConstructionCount());
    }

    public static void main(String[] args) throws InterruptedException {
        demonstrateReadModifyWrite();
        demonstrateCheckThenAct();
    }
}
