/*
 * CASDemo — Main
 *
 * Demonstrates two properties of CAS-based concurrency:
 *   1. CAS loop correctness: ManualCasCounter uses an explicit read-compute-CAS-retry
 *      loop. Under full concurrent contention, the final count must still equal
 *      THREADS * INCREMENTS — no update is lost because each failed CAS retries.
 *   2. The ABA problem and its fix:
 *      - With plain AtomicReference, CAS succeeds when the reference is back to its
 *        original object after cycling A -> B -> A. The intermediate change is invisible.
 *      - With AtomicStampedReference, each transition also increments a stamp.
 *        Thread 1's CAS expects the original stamp and fails when the stamp has advanced,
 *        correctly detecting that A -> B -> A occurred during the pause.
 */
package examples.casdemo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        demonstrateCasLoop();
        demonstrateABAProblem();
        demonstrateStampedFix();
    }

    // -------------------------------------------------------------------------
    // Demo 1: CAS loop correctness under contention
    // -------------------------------------------------------------------------

    static void demonstrateCasLoop() throws InterruptedException {
        ManualCasCounter counter    = new ManualCasCounter();
        final int        threads    = 6;
        final int        increments = 50_000;

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
        // The CAS loop retries on every contended write, so no increment is dropped.
        System.out.println("CAS loop correct: " + (actual == expected)
                + " (" + actual + " / " + expected + ")");
    }

    // -------------------------------------------------------------------------
    // Demo 2: ABA problem with plain AtomicReference
    // -------------------------------------------------------------------------

    static void demonstrateABAProblem() throws InterruptedException {
        Slot slotA = new Slot("A");
        Slot slotB = new Slot("B");
        AtomicReference<Slot> abaRef = new AtomicReference<>(slotA);

        CountDownLatch thread1Paused   = new CountDownLatch(1);
        CountDownLatch thread2Finished = new CountDownLatch(1);

        // Thread 1 reads A, signals that it has paused, then waits.
        // It resumes after Thread 2 has cycled the reference A -> B -> A.
        Thread thread1 = new Thread(() -> {
            Slot seen = abaRef.get();
            thread1Paused.countDown();

            try { thread2Finished.await(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // The reference is back to slotA (the same object), so CAS succeeds.
            // Thread 1 cannot detect that the reference went through A -> B -> A.
            Slot slotC = new Slot("C");
            boolean ok = abaRef.compareAndSet(seen, slotC);
            System.out.println("[ABA]     Thread-1 CAS(A->C) succeeded: " + ok
                    + "  (A->B->A transition went undetected)");
        }, "aba-thread-1");

        // Thread 2 performs A -> B -> A while Thread 1 is waiting.
        // It puts the same slotA object back, so == comparison in CAS still holds.
        Thread thread2 = new Thread(() -> {
            try { thread1Paused.await(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            abaRef.compareAndSet(slotA, slotB);   // A -> B
            abaRef.compareAndSet(slotB, slotA);   // B -> A  (same slotA object)
            thread2Finished.countDown();
        }, "aba-thread-2");

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
    }

    // -------------------------------------------------------------------------
    // Demo 3: AtomicStampedReference detects ABA
    // -------------------------------------------------------------------------

    static void demonstrateStampedFix() throws InterruptedException {
        Slot slotA = new Slot("A");
        Slot slotB = new Slot("B");
        // Pair the reference with an integer stamp; both must match for CAS to succeed.
        AtomicStampedReference<Slot> stampedRef = new AtomicStampedReference<>(slotA, 0);

        CountDownLatch thread1Paused   = new CountDownLatch(1);
        CountDownLatch thread2Finished = new CountDownLatch(1);

        Thread thread1 = new Thread(() -> {
            int[] stampHolder = new int[1];
            Slot seen  = stampedRef.get(stampHolder);
            int  stamp = stampHolder[0];   // stamp = 0
            thread1Paused.countDown();

            try { thread2Finished.await(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Thread 2 advanced the stamp to 2. Thread 1's CAS expects stamp 0,
            // so it fails even though the reference is back to slotA.
            Slot slotC = new Slot("C");
            boolean ok = stampedRef.compareAndSet(seen, slotC, stamp, stamp + 1);
            System.out.println("[Stamped] Thread-1 CAS(A->C, stamp " + stamp
                    + "->" + (stamp + 1) + ") succeeded: " + ok
                    + "  (ABA correctly detected via stamp mismatch)");
        }, "stamped-thread-1");

        Thread thread2 = new Thread(() -> {
            try { thread1Paused.await(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            stampedRef.compareAndSet(slotA, slotB, 0, 1);   // A -> B, stamp 0 -> 1
            stampedRef.compareAndSet(slotB, slotA, 1, 2);   // B -> A, stamp 1 -> 2
            thread2Finished.countDown();
        }, "stamped-thread-2");

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
    }
}
