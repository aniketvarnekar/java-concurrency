/*
 * JCStressDemo — Main
 *
 * Simulates the jcstress concurrency testing approach without requiring the
 * jcstress harness. Two "actor" threads run concurrently on shared state;
 * an "arbiter" observes the final state; outcomes are classified across
 * many iterations to surface rare interleavings.
 *
 * Test 1 — Unsynchronized increment:
 *   Two actors each execute x++ (a non-atomic read-modify-write) on a shared int.
 *   ACCEPTABLE: x=1 (lost update) or x=2 (both increments visible)
 *   FORBIDDEN:  x=0 (no increment visible at all)
 *
 * Test 2 — Volatile flag visibility:
 *   actor1 writes: data=42 then flag=true (volatile write).
 *   actor2 reads:  flag (volatile read), then data.
 *   ACCEPTABLE: (flag=false, data=0) or (flag=true, data=42)
 *   FORBIDDEN:  (flag=true, data=0) — would mean volatile happens-before failed.
 *
 * The real jcstress harness (https://github.com/openjdk/jcstress) provides
 * @JCStressTest, @Actor, @Arbiter, @Outcome and a proper engine that runs on
 * multi-core hardware to exhaust concurrent interleavings far more thoroughly.
 */
package examples.jcstressdemo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    static final int ITERATIONS = 100_000;

    public static void main(String[] args) throws Exception {
        runIncrementTest();
        System.out.println();
        runVolatileVisibilityTest();
    }

    // -------------------------------------------------------------------------
    // Test 1: Unsynchronized int increment
    // -------------------------------------------------------------------------
    static void runIncrementTest() throws Exception {
        Map<Integer, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        outcomes.put(0, new AtomicInteger()); // FORBIDDEN
        outcomes.put(1, new AtomicInteger()); // ACCEPTABLE — lost update
        outcomes.put(2, new AtomicInteger()); // ACCEPTABLE — both increments visible

        for (int iter = 0; iter < ITERATIONS; iter++) {
            int[] state = {0};
            CyclicBarrier startBarrier = new CyclicBarrier(2);

            Thread actor1 = new Thread(() -> {
                try { startBarrier.await(); } catch (Exception e) { return; }
                state[0]++; // non-atomic read-modify-write
            }, "actor-1");

            Thread actor2 = new Thread(() -> {
                try { startBarrier.await(); } catch (Exception e) { return; }
                state[0]++; // non-atomic read-modify-write
            }, "actor-2");

            actor1.start();
            actor2.start();
            actor1.join();
            actor2.join();

            // Arbiter: observe final state
            outcomes.computeIfAbsent(state[0], k -> new AtomicInteger()).incrementAndGet();
        }

        System.out.println("Test 1 — unsynchronized increment (" + ITERATIONS + " iterations):");
        System.out.println("  x=0 (FORBIDDEN ):  " + outcomes.getOrDefault(0, new AtomicInteger()).get());
        System.out.println("  x=1 (ACCEPTABLE):  " + outcomes.getOrDefault(1, new AtomicInteger()).get()
                           + "  <- lost update (race condition)");
        System.out.println("  x=2 (ACCEPTABLE):  " + outcomes.getOrDefault(2, new AtomicInteger()).get()
                           + "  <- both increments visible");

        int forbidden = outcomes.getOrDefault(0, new AtomicInteger()).get();
        if (forbidden > 0) {
            System.out.println("  *** FORBIDDEN observed " + forbidden + " times ***");
        } else {
            System.out.println("  FORBIDDEN not observed (race IS present — x=1 entries confirm it)");
        }
    }

    // -------------------------------------------------------------------------
    // Test 2: Volatile flag visibility
    // -------------------------------------------------------------------------
    static void runVolatileVisibilityTest() throws Exception {
        AtomicInteger seenFlagFalseData0 = new AtomicInteger(); // ACCEPTABLE
        AtomicInteger seenFlagTrueData42 = new AtomicInteger(); // ACCEPTABLE
        AtomicInteger seenFlagTrueData0  = new AtomicInteger(); // FORBIDDEN
        AtomicInteger seenOther          = new AtomicInteger();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            VolatileState vs = new VolatileState();
            int[] result = {-1, -1}; // [0]=observedFlag (0/1), [1]=observedData

            CyclicBarrier startBarrier = new CyclicBarrier(2);

            Thread actor1 = new Thread(() -> {
                try { startBarrier.await(); } catch (Exception e) { return; }
                vs.data = 42;    // write data before the volatile publish
                vs.flag = true;  // volatile write — establishes happens-before
            }, "actor-1");

            Thread actor2 = new Thread(() -> {
                try { startBarrier.await(); } catch (Exception e) { return; }
                boolean f = vs.flag; // volatile read
                int d     = vs.data; // plain read (must see 42 if f is true)
                result[0] = f ? 1 : 0;
                result[1] = d;
            }, "actor-2");

            actor1.start();
            actor2.start();
            actor1.join();
            actor2.join();

            boolean observedFlag = result[0] == 1;
            int observedData = result[1];

            if (!observedFlag && observedData == 0) {
                seenFlagFalseData0.incrementAndGet();
            } else if (observedFlag && observedData == 42) {
                seenFlagTrueData42.incrementAndGet();
            } else if (observedFlag && observedData == 0) {
                seenFlagTrueData0.incrementAndGet(); // FORBIDDEN
            } else {
                seenOther.incrementAndGet();
            }
        }

        System.out.println("Test 2 — volatile flag visibility (" + ITERATIONS + " iterations):");
        System.out.println("  (flag=false, data=0 ) ACCEPTABLE: " + seenFlagFalseData0.get());
        System.out.println("  (flag=true,  data=42) ACCEPTABLE: " + seenFlagTrueData42.get());
        System.out.println("  (flag=true,  data=0 ) FORBIDDEN:  " + seenFlagTrueData0.get());
        System.out.println("  other:                             " + seenOther.get());

        if (seenFlagTrueData0.get() > 0) {
            System.out.println("  *** FORBIDDEN: volatile happens-before violated ***");
        } else {
            System.out.println("  No FORBIDDEN outcomes — volatile guarantee holds.");
        }
    }
}
