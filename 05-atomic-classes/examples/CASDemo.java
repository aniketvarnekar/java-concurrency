/**
 * CASDemo
 *
 * Demonstrates:
 *   - A manual CAS loop implementing a lock-free counter
 *   - The ABA problem using plain AtomicReference
 *   - How AtomicStampedReference detects and prevents ABA
 *
 * Run:
 *   javac CASDemo.java && java CASDemo
 */

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;

public class CASDemo {

    // -------------------------------------------------------------------------
    // Part 1: Manual CAS loop
    // -------------------------------------------------------------------------

    /**
     * A lock-free counter implemented with an explicit CAS retry loop.
     * This is equivalent to AtomicInteger.incrementAndGet() but shows
     * the loop structure explicitly so the reader can see exactly what happens.
     */
    static class ManualCasCounter {
        private final AtomicInteger value = new AtomicInteger(0);

        /** Increment and return the new value. May retry many times under contention. */
        public int incrementAndGet() {
            int current;
            int next;
            int retries = 0;
            do {
                current = value.get();   // 1. Read current value
                next    = current + 1;   // 2. Compute desired new value
                retries++;
                // 3. Only write if value hasn't changed since step 1.
                //    If another thread changed it, the CAS returns false and we loop.
            } while (!value.compareAndSet(current, next));

            if (retries > 1) {
                // This line fires under contention, showing the retry in action.
                System.out.printf("  [CAS] %s needed %d attempts to increment%n",
                        Thread.currentThread().getName(), retries);
            }
            return next;
        }

        public int get() { return value.get(); }
    }

    // -------------------------------------------------------------------------
    // Part 2: ABA problem with plain AtomicReference
    // -------------------------------------------------------------------------

    /**
     * A simple wrapper representing a named value, used to make the ABA
     * problem visible in output.
     */
    static class Slot {
        final String name;
        Slot(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }

    // Shared reference used to demonstrate ABA
    static final AtomicReference<Slot> abaRef = new AtomicReference<>();

    /**
     * Simulates the ABA scenario:
     *   Thread 1 reads reference A and is then paused.
     *   Thread 2 swaps A -> B -> A.
     *   Thread 1 resumes and its CAS(A -> C) succeeds, unaware of the change.
     */
    static void demonstrateABAProblem() throws InterruptedException {
        System.out.println("\n--- ABA Problem (plain AtomicReference) ---");

        Slot slotA = new Slot("A");
        Slot slotB = new Slot("B");
        abaRef.set(slotA);

        CountDownLatch thread1Paused     = new CountDownLatch(1);
        CountDownLatch thread2Finished   = new CountDownLatch(1);

        // Thread 1: reads A, pauses, then tries CAS(A -> C)
        Thread thread1 = new Thread(() -> {
            Slot seen = abaRef.get(); // reads "A"
            System.out.println("[Thread-1] read reference: " + seen);

            // Signal that we've read; now pause so Thread 2 can run
            thread1Paused.countDown();

            // Wait for Thread 2 to do A -> B -> A
            try { thread2Finished.await(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Thread 1 tries CAS. The reference is back to A (same object),
            // so this CAS succeeds -- but Thread 1 is unaware that the reference
            // went through A -> B -> A during its pause.
            Slot slotC = new Slot("C");
            boolean ok = abaRef.compareAndSet(seen, slotC);
            System.out.println("[Thread-1] CAS(" + seen + " -> C) succeeded: " + ok
                    + " (ABA went undetected! reference was A->B->A while we slept)");
            System.out.println("[Thread-1] current ref: " + abaRef.get());
        }, "Thread-1");

        // Thread 2: after Thread 1 has read, does A -> B -> A
        Thread thread2 = new Thread(() -> {
            try { thread1Paused.await(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // A -> B
            boolean ok1 = abaRef.compareAndSet(slotA, slotB);
            System.out.println("[Thread-2] CAS(A -> B): " + ok1
                    + ", ref=" + abaRef.get());

            // B -> A (putting the SAME object A back)
            boolean ok2 = abaRef.compareAndSet(slotB, slotA);
            System.out.println("[Thread-2] CAS(B -> A): " + ok2
                    + ", ref=" + abaRef.get());

            thread2Finished.countDown();
        }, "Thread-2");

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
    }

    // -------------------------------------------------------------------------
    // Part 3: AtomicStampedReference prevents ABA
    // -------------------------------------------------------------------------

    static final AtomicStampedReference<Slot> stampedRef =
            new AtomicStampedReference<>(null, 0);

    /**
     * Repeats the ABA scenario but using AtomicStampedReference.
     * Thread 2's A -> B -> A transitions increment the stamp each time.
     * Thread 1's CAS expects the original stamp, so it correctly fails.
     */
    static void demonstrateStampedFix() throws InterruptedException {
        System.out.println("\n--- ABA Fix (AtomicStampedReference) ---");

        Slot slotA = new Slot("A");
        Slot slotB = new Slot("B");
        stampedRef.set(slotA, 0); // initial: ref=A, stamp=0

        CountDownLatch thread1Paused   = new CountDownLatch(1);
        CountDownLatch thread2Finished = new CountDownLatch(1);

        Thread thread1 = new Thread(() -> {
            int[] stampHolder = new int[1];
            Slot seen  = stampedRef.get(stampHolder);
            int  stamp = stampHolder[0];
            System.out.println("[Thread-1] read: ref=" + seen + ", stamp=" + stamp);

            thread1Paused.countDown();

            try { thread2Finished.await(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // CAS now also checks the stamp. Thread 2 advanced the stamp to 2,
            // so this CAS with expected stamp=0 will fail.
            Slot slotC = new Slot("C");
            boolean ok = stampedRef.compareAndSet(seen, slotC, stamp, stamp + 1);
            System.out.println("[Thread-1] CAS(" + seen + " -> C, stamp "
                    + stamp + " -> " + (stamp + 1) + ") succeeded: " + ok
                    + " (correctly rejected! stamp mismatch)");
            System.out.println("[Thread-1] current ref=" + stampedRef.getReference()
                    + ", stamp=" + stampedRef.getStamp());
        }, "Thread-1");

        Thread thread2 = new Thread(() -> {
            try { thread1Paused.await(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // A -> B, stamp 0 -> 1
            boolean ok1 = stampedRef.compareAndSet(slotA, slotB, 0, 1);
            System.out.println("[Thread-2] CAS(A->B, stamp 0->1): " + ok1
                    + ", stamp=" + stampedRef.getStamp());

            // B -> A, stamp 1 -> 2
            boolean ok2 = stampedRef.compareAndSet(slotB, slotA, 1, 2);
            System.out.println("[Thread-2] CAS(B->A, stamp 1->2): " + ok2
                    + ", stamp=" + stampedRef.getStamp());

            thread2Finished.countDown();
        }, "Thread-2");

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
    }

    // -------------------------------------------------------------------------
    // Part 4: Manual CAS loop stress test
    // -------------------------------------------------------------------------

    static void stressTestCasLoop() throws InterruptedException {
        System.out.println("\n--- Manual CAS Loop Stress Test ---");

        ManualCasCounter counter = new ManualCasCounter();
        final int threads    = 6;
        final int increments = 50_000;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < increments; i++) {
                    counter.incrementAndGet();
                }
                done.countDown();
            }, "cas-worker-" + t).start();
        }

        start.countDown();
        done.await();

        int expected = threads * increments;
        int actual   = counter.get();
        System.out.printf("Expected: %,d | Actual: %,d | Correct: %b%n",
                expected, actual, expected == actual);
    }

    // -------------------------------------------------------------------------
    // main
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== CASDemo ===");

        stressTestCasLoop();
        demonstrateABAProblem();
        demonstrateStampedFix();

        System.out.println("\nDone.");
    }
}
