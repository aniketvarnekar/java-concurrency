/**
 * Simulates the jcstress testing approach without requiring the jcstress harness.
 *
 * This file demonstrates the CONCEPTS behind jcstress-style concurrency testing:
 *   - Two "actor" threads operate concurrently on shared state
 *   - An "arbiter" observes the final state after both actors finish
 *   - Results are classified as ACCEPTABLE or FORBIDDEN
 *   - The test is repeated many times to detect rare interleavings
 *
 * The real jcstress tool (https://github.com/openjdk/jcstress) provides:
 *   @JCStressTest, @State, @Actor, @Arbiter, @Outcome annotations
 *   and a proper test harness that runs on multi-core hardware to
 *   exhaustively stress concurrent interleavings.
 *
 * To use the real tool, add the Maven dependency:
 *   <dependency>
 *     <groupId>org.openjdk.jcstress</groupId>
 *     <artifactId>jcstress-core</artifactId>
 *     <version>0.16</version>
 *   </dependency>
 * and run: mvn verify -Pjcstress
 *
 * Run (this simulation): javac JCStressExamples.java && java JCStressExamples
 */
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

public class JCStressExamples {

    // ----------------------------------------------------------------
    // What a real jcstress test looks like (shown as commented pseudocode):
    //
    // @JCStressTest
    // @Outcome(id = "1", expect = ACCEPTABLE, desc = "one increment visible")
    // @Outcome(id = "2", expect = ACCEPTABLE, desc = "both increments visible")
    // @Outcome(id = "0", expect = FORBIDDEN,  desc = "no increment visible")
    // @State
    // public class IncrementTest {
    //     int x;
    //
    //     @Actor
    //     public void actor1() { x++; }
    //
    //     @Actor
    //     public void actor2() { x++; }
    //
    //     @Arbiter
    //     public void arbiter(IntResult1 r) { r.r1 = x; }
    // }
    // ----------------------------------------------------------------

    static final int ITERATIONS = 100_000;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Test 1: Unsynchronized Increment ===");
        runIncrementTest();

        System.out.println();
        System.out.println("=== Test 2: Volatile Visibility ===");
        runVolatileVisibilityTest();
    }

    // ----------------------------------------------------------------
    // Test 1: Unsynchronized int increment
    //
    // Two actor threads each execute x++ on a shared int.
    // Acceptable outcomes: 1 (one increment lost), 2 (both visible)
    // Forbidden outcome:   0 (no increment visible at all)
    // ----------------------------------------------------------------
    static void runIncrementTest() throws Exception {
        Map<Integer, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        outcomes.put(0, new AtomicInteger());  // forbidden
        outcomes.put(1, new AtomicInteger());  // acceptable (lost update)
        outcomes.put(2, new AtomicInteger());  // acceptable (both visible)

        for (int iter = 0; iter < ITERATIONS; iter++) {
            // Shared state — reset each iteration
            int[] state = {0};

            CyclicBarrier startBarrier = new CyclicBarrier(2);

            Thread actor1 = new Thread(() -> {
                try { startBarrier.await(); } catch (Exception e) { return; }
                state[0]++;  // non-atomic read-modify-write
            }, "actor-1");

            Thread actor2 = new Thread(() -> {
                try { startBarrier.await(); } catch (Exception e) { return; }
                state[0]++;  // non-atomic read-modify-write
            }, "actor-2");

            actor1.start();
            actor2.start();
            actor1.join();
            actor2.join();

            // Arbiter: observe final state
            int result = state[0];
            outcomes.computeIfAbsent(result, k -> new AtomicInteger()).incrementAndGet();
        }

        System.out.println("Outcomes after " + ITERATIONS + " iterations:");
        System.out.println("  x=0 (FORBIDDEN):   " + outcomes.getOrDefault(0, new AtomicInteger()).get());
        System.out.println("  x=1 (ACCEPTABLE):  " + outcomes.getOrDefault(1, new AtomicInteger()).get()
                           + "  <- lost update (race condition observed)");
        System.out.println("  x=2 (ACCEPTABLE):  " + outcomes.getOrDefault(2, new AtomicInteger()).get()
                           + "  <- both increments visible");

        int forbidden = outcomes.getOrDefault(0, new AtomicInteger()).get();
        if (forbidden > 0) {
            System.out.println("  *** FORBIDDEN outcome observed " + forbidden + " times ***");
        } else {
            System.out.println("  FORBIDDEN outcome not observed (but race condition IS present — see x=1)");
        }
    }

    // ----------------------------------------------------------------
    // Test 2: Volatile visibility
    //
    // Actor1 writes: data = 42; flag = true  (in that order)
    // Actor2 reads:  if (flag) observedData = data
    //
    // If volatile is correct, whenever actor2 sees flag=true, it must
    // also see data=42 (due to volatile happens-before).
    //
    // Acceptable:  (flag=false, data=0)  actor2 read before actor1 wrote
    //              (flag=true,  data=42) actor2 read after actor1 wrote
    // Forbidden:   (flag=true,  data=0)  actor2 saw flag=true but data=0
    //              — would indicate a visibility failure
    // ----------------------------------------------------------------
    static void runVolatileVisibilityTest() throws Exception {
        AtomicInteger seenFlagFalseData0   = new AtomicInteger(); // acceptable
        AtomicInteger seenFlagTrueData42   = new AtomicInteger(); // acceptable
        AtomicInteger seenFlagTrueData0    = new AtomicInteger(); // FORBIDDEN
        AtomicInteger seenOther            = new AtomicInteger();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            // Shared state
            VolatileState vs = new VolatileState();
            int[] result = {-1, -1}; // [0]=observedFlag, [1]=observedData

            CyclicBarrier startBarrier = new CyclicBarrier(2);

            Thread actor1 = new Thread(() -> {
                try { startBarrier.await(); } catch (Exception e) { return; }
                vs.data = 42;      // write data first
                vs.flag = true;    // then publish flag (volatile write)
            }, "actor-1");

            Thread actor2 = new Thread(() -> {
                try { startBarrier.await(); } catch (Exception e) { return; }
                boolean f = vs.flag;  // volatile read
                int d     = vs.data;  // plain read
                result[0] = f ? 1 : 0;
                result[1] = d;
            }, "actor-2");

            actor1.start();
            actor2.start();
            actor1.join();
            actor2.join();

            // Arbiter: classify outcome
            boolean observedFlag = result[0] == 1;
            int observedData = result[1];

            if (!observedFlag && observedData == 0) {
                seenFlagFalseData0.incrementAndGet(); // acceptable
            } else if (observedFlag && observedData == 42) {
                seenFlagTrueData42.incrementAndGet();  // acceptable
            } else if (observedFlag && observedData == 0) {
                seenFlagTrueData0.incrementAndGet();   // FORBIDDEN
            } else {
                seenOther.incrementAndGet();
            }
        }

        System.out.println("Outcomes after " + ITERATIONS + " iterations:");
        System.out.println("  (flag=false, data=0 ) ACCEPTABLE: " + seenFlagFalseData0.get());
        System.out.println("  (flag=true,  data=42) ACCEPTABLE: " + seenFlagTrueData42.get());
        System.out.println("  (flag=true,  data=0 ) FORBIDDEN:  " + seenFlagTrueData0.get());
        System.out.println("  other:                             " + seenOther.get());

        if (seenFlagTrueData0.get() > 0) {
            System.out.println("  *** FORBIDDEN: volatile happens-before violated! ***");
        } else {
            System.out.println("  No FORBIDDEN outcomes — volatile guarantee appears to hold.");
        }
    }

    // Shared state for Test 2
    static class VolatileState {
        int           data = 0;
        volatile boolean flag = false;
    }
}
