# jcstress: Concurrency Stress Testing

## Overview

jcstress (Java Concurrency Stress tests) is a harness from the OpenJDK project specifically designed for writing and running concurrency correctness tests. It addresses the fundamental problem with standard unit tests for concurrent code: they cannot exhaustively explore thread interleavings. A standard JUnit test that creates two threads and runs them cannot control or enumerate the possible interleavings between those threads, so most tests pass even for code with genuine race conditions. jcstress solves this by running tests under controlled conditions on multiple CPU cores, repeating each test scenario millions of times to maximize the probability of exposing atomicity violations and visibility bugs.

The harness uses a custom execution engine that creates many fresh instances of the test state, assigns actor methods to dedicated OS threads, and runs them with carefully engineered synchronization to maximize interleaving diversity. Results are categorized using the @Outcome annotations the test author provides, distinguishing between outcomes that are acceptable under the Java Memory Model, outcomes that are forbidden (and indicate a bug), and outcomes that are theoretically acceptable but surprising enough to warrant investigation. This classification makes jcstress tests self-documenting: the annotations encode the JMM guarantees the test is verifying.

jcstress is not a replacement for logic tests or integration tests. It is a targeted tool for verifying that a specific piece of shared-state code obeys the JMM guarantees the programmer intends. It is most valuable when writing new concurrent data structures, verifying that a lock-free algorithm is correct, or auditing code that uses volatile or atomic operations in subtle ways. The results are written to an HTML report, not a standard pass/fail stream, so CI integration requires custom scripting to parse the report for any FORBIDDEN outcomes.

## Key Concepts

### @JCStressTest

The `@JCStressTest` annotation marks a class as a concurrency stress test. The harness discovers all classes annotated with it and runs them as part of the test suite. The harness creates many instances of the test class (or the @State class) and runs actor methods concurrently across a large number of iterations, typically millions. The class itself does not extend any base class or implement any interface — the annotation is sufficient for discovery.

### @State

The `@State` annotation marks the class (or a nested class) that holds the shared mutable state under test. The harness creates a fresh `@State` instance for each test iteration, ensuring no state leaks between iterations. If `@State` is placed on the same class as `@JCStressTest`, the test class itself holds the state. If `@State` is placed on a nested class, that class is instantiated separately and passed to the actor methods. Fields in the `@State` class must be initialized only in the field declaration or a no-arg constructor — jcstress creates instances reflectively.

### @Actor

The `@Actor` annotation marks a method that is run by exactly one thread per iteration. A test class with two `@Actor` methods runs those two methods concurrently in two threads for each iteration. Actor methods may receive `Result` parameters (such as `IntResult1`, `IntResult2`, `LLResult2`) where they record their observations. The harness ensures that all actor threads start their respective methods as close to simultaneously as possible, maximizing contention. Each actor method is called exactly once per iteration.

### @Arbiter

The `@Arbiter` annotation marks a method run by the harness after all `@Actor` methods have completed for one iteration. It is used to observe the final state and record the outcome. The arbiter runs sequentially after all actors, so it sees the fully settled state. It receives the same `Result` parameter type as the actors. If the test only needs to observe the final value (not what each actor saw during execution), an arbiter alone may be sufficient, without any actors recording to the result.

### @Outcome

The `@Outcome` annotation is a class-level annotation that describes one possible result for the test. Multiple `@Outcome` annotations are stacked on the test class. Each has three parameters:

- `id`: a string matching the formatted output of the Result object (e.g., `"1, 0"` or `"2"`).
- `expect`: one of `ACCEPTABLE`, `FORBIDDEN`, `INTERESTING`, or `ACCEPTABLE_INTERESTING`.
- `desc`: a human-readable label explaining what this outcome means.

The harness formats the Result fields as a comma-separated string and matches it against the `id` patterns. Any outcome not matched by any `@Outcome` annotation is treated as `ACCEPTABLE_INTERESTING` by default.

```
Outcome expect values:

ACCEPTABLE          - Expected under the JMM; not highlighted in report
FORBIDDEN           - Must never occur; red in report; indicates a bug
INTERESTING         - Legal but surprising; yellow; warrants investigation
ACCEPTABLE_INTERESTING - Both acceptable and interesting; yellow
```

### Result Types

jcstress provides result types that actors and arbiters write their observations into. Common types:

| Type | Fields | Usage |
|---|---|---|
| IntResult1 | r1 (int) | One actor or arbiter records one int |
| IntResult2 | r1, r2 (int) | Two actors each record one int |
| LLResult2 | r1, r2 (long) | Two actors each record one long |
| JJResult2 | r1, r2 (long) | Variant for two longs |
| ZZResult2 | r1, r2 (boolean) | Two actors each record one boolean |

The harness formats the result as `"r1, r2"` (for two-field types) or simply `"r1"` (for one-field types), and this string is matched against `@Outcome` `id` values.

### Full Example: Unsynchronized Increment

```java
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.IntResult1;

import static org.openjdk.jcstress.annotations.Expect.*;

// jcstress test for unsynchronized int increment
@JCStressTest
@Outcome(id = "1", expect = ACCEPTABLE,  desc = "one increment visible — lost update, allowed by JMM")
@Outcome(id = "2", expect = ACCEPTABLE,  desc = "both increments visible — ideal outcome")
@Outcome(id = "0", expect = FORBIDDEN,   desc = "initial value — no increment visible at all")
@State
public class IncrementTest {
    int x;

    @Actor
    public void actor1() {
        x++;   // read x, add 1, write x — not atomic
    }

    @Actor
    public void actor2() {
        x++;   // same non-atomic sequence, concurrent with actor1
    }

    @Arbiter
    public void arbiter(IntResult1 r) {
        r.r1 = x;   // observe final value after both actors complete
    }
}
```

What each part does:

- `@State` on the class means `x` is the shared mutable state; each iteration gets a fresh instance with `x = 0`.
- `actor1` and `actor2` both perform `x++` concurrently. Since `x++` is three operations (read, increment, write), the two threads can interleave: both read 0, both add 1, both write 1, resulting in the lost update (outcome `"1"`). If one completes before the other reads, the result is `"2"`.
- The arbiter reads the final value of `x` after both actors have finished and records it in `r.r1`.
- The outcome `"0"` is `FORBIDDEN` because after two increments on a value starting at 0, seeing 0 would mean both writes were lost — which is not a valid JMM outcome.

### Second Example: Volatile Flag Publication

```java
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.IntResult1;

import static org.openjdk.jcstress.annotations.Expect.*;

@JCStressTest
@Outcome(id = "0", expect = ACCEPTABLE,         desc = "flag not yet visible to reader")
@Outcome(id = "42", expect = ACCEPTABLE,        desc = "flag and data both visible — correct publication")
@Outcome(id = "-1", expect = FORBIDDEN,         desc = "flag visible but data not — volatile ordering violated")
@State
public class VolatilePublicationTest {
    int data = 0;
    volatile boolean flag = false;

    @Actor
    public void writer() {
        data = 42;       // write data before the volatile write
        flag = true;     // volatile write; happens-before any subsequent volatile read of flag
    }

    @Actor
    public void reader(IntResult1 r) {
        if (flag) {      // volatile read
            r.r1 = data; // if flag is true, data must be 42 per JMM happens-before
        } else {
            r.r1 = 0;    // flag not yet visible
        }
        // sentinel: if flag is true but data is 0, record -1 to distinguish forbidden case
        if (flag && data == 0) {
            r.r1 = -1;
        }
    }
}
```

The JMM guarantees that the volatile write to `flag` happens-before any subsequent volatile read of `flag` that observes `true`. This means `data = 42` (which precedes the volatile write in program order) must also be visible. The outcome `-1` (flag visible, data not) is `FORBIDDEN` because it would require the volatile ordering guarantee to be violated.

### Running jcstress

jcstress requires a Maven project with the jcstress dependency. Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.openjdk.jcstress</groupId>
    <artifactId>jcstress-core</artifactId>
    <version>0.16</version>
</dependency>
```

Run with the jcstress Maven profile (typically configured in the archetype-generated pom):

```
mvn verify -pl . -Pjcstress
```

Or use the standalone jar:

```
java -jar jcstress.jar -t IncrementTest
```

Common options:

| Option | Description |
|---|---|
| `-t <regex>` | Run only tests matching the regex |
| `-time <ms>` | Time to spend per test (default 1000ms) |
| `-c <n>` | Number of CPU pairs to use |
| `-r <dir>` | Output directory for HTML report |
| `-v` | Verbose output |

### Reading Results

The HTML report shows a table per test. Each row is one observed outcome combination. Column meanings:

```
Observed      Expect        Description
---------     ----------    ---------------------------
           2  ACCEPTABLE    both increments visible
       99998  ACCEPTABLE    one increment visible — lost update
           0  FORBIDDEN     ** BUG: no increment seen **
```

- `FORBIDDEN` rows highlighted in red: a safety violation was observed. The count shows how many times.
- `INTERESTING` rows in yellow: theoretically legal but surprising.
- `ACCEPTABLE` rows: expected outcomes; no action needed.
- Any observed outcome not covered by an `@Outcome` annotation appears as `ACCEPTABLE_INTERESTING` (yellow).

## Code Snippet

This standalone simulation captures the spirit of jcstress without requiring the harness. Two actor threads run concurrently on shared state, an arbiter observes the final state, and results are classified across many iterations.

```java
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates jcstress-style stress testing without the jcstress harness.
 *
 * Run: javac JCStressSimulation.java && java JCStressSimulation
 */
public class JCStressSimulation {

    // -----------------------------------------------------------------------
    // Test 1: Unsynchronized increment — mirrors IncrementTest above
    // -----------------------------------------------------------------------
    static class IncrementState {
        int x = 0;

        void reset() { x = 0; }
    }

    static void runIncrementTest(int iterations) throws InterruptedException {
        System.out.println("=== Unsynchronized Increment Test ===");
        Map<Integer, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        outcomes.put(0, new AtomicInteger());
        outcomes.put(1, new AtomicInteger());
        outcomes.put(2, new AtomicInteger());

        IncrementState state = new IncrementState();

        for (int i = 0; i < iterations; i++) {
            state.reset();
            CyclicBarrier startBarrier = new CyclicBarrier(2);

            Thread actor1 = new Thread(() -> {
                try { startBarrier.await(); } catch (Exception e) { Thread.currentThread().interrupt(); }
                state.x++;
            }, "actor-1");

            Thread actor2 = new Thread(() -> {
                try { startBarrier.await(); } catch (Exception e) { Thread.currentThread().interrupt(); }
                state.x++;
            }, "actor-2");

            actor1.start();
            actor2.start();
            actor1.join();
            actor2.join();

            // Arbiter: observe final state
            int result = state.x;
            outcomes.computeIfAbsent(result, k -> new AtomicInteger()).incrementAndGet();
        }

        System.out.printf("  Outcome 0 (FORBIDDEN  - no increment seen):     %7d%n",
                outcomes.getOrDefault(0, new AtomicInteger()).get());
        System.out.printf("  Outcome 1 (ACCEPTABLE - one increment, lost):   %7d%n",
                outcomes.getOrDefault(1, new AtomicInteger()).get());
        System.out.printf("  Outcome 2 (ACCEPTABLE - both increments):       %7d%n",
                outcomes.getOrDefault(2, new AtomicInteger()).get());

        int forbidden = outcomes.getOrDefault(0, new AtomicInteger()).get();
        if (forbidden > 0) {
            System.out.println("  *** FORBIDDEN outcome observed " + forbidden + " time(s) — CONCURRENCY BUG ***");
        } else {
            System.out.println("  No FORBIDDEN outcomes observed in this run.");
            System.out.println("  (Note: absence of FORBIDDEN does not prove correctness — increase iterations)");
        }
        System.out.println();
    }

    // -----------------------------------------------------------------------
    // Test 2: Volatile flag visibility — mirrors VolatilePublicationTest
    // -----------------------------------------------------------------------
    static class VolatileState {
        int data = 0;
        volatile boolean flag = false;
        // What actor2 observed
        volatile int observedData = -99;
        volatile boolean observedFlag = false;

        void reset() {
            data = 0;
            flag = false;
            observedData = -99;
            observedFlag = false;
        }
    }

    static void runVolatileTest(int iterations) throws InterruptedException {
        System.out.println("=== Volatile Flag Publication Test ===");
        // Outcome encoding: "flag=false" -> 0, "flag=true,data=42" -> 1, "flag=true,data=0" -> -1 (FORBIDDEN)
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();

        VolatileState state = new VolatileState();

        for (int i = 0; i < iterations; i++) {
            state.reset();
            CyclicBarrier startBarrier = new CyclicBarrier(2);

            Thread writer = new Thread(() -> {
                try { startBarrier.await(); } catch (Exception e) { Thread.currentThread().interrupt(); }
                state.data = 42;
                state.flag = true;
            }, "writer-thread");

            Thread reader = new Thread(() -> {
                try { startBarrier.await(); } catch (Exception e) { Thread.currentThread().interrupt(); }
                state.observedFlag = state.flag;
                if (state.observedFlag) {
                    state.observedData = state.data;
                } else {
                    state.observedData = 0;
                }
            }, "reader-thread");

            writer.start();
            reader.start();
            writer.join();
            reader.join();

            // Arbiter: classify outcome
            String outcome;
            if (!state.observedFlag) {
                outcome = "flag=false, data=0 (ACCEPTABLE: flag not yet visible)";
            } else if (state.observedData == 42) {
                outcome = "flag=true, data=42 (ACCEPTABLE: correct publication)";
            } else {
                outcome = "flag=true, data=" + state.observedData + " (FORBIDDEN: data not visible despite flag)";
            }
            outcomes.computeIfAbsent(outcome, k -> new AtomicInteger()).incrementAndGet();
        }

        System.out.println("  Results:");
        outcomes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.printf("    [%7d] %s%n", e.getValue().get(), e.getKey()));

        boolean forbiddenSeen = outcomes.keySet().stream().anyMatch(k -> k.contains("FORBIDDEN"));
        if (forbiddenSeen) {
            System.out.println("  *** FORBIDDEN outcome observed — volatile ordering violated ***");
        } else {
            System.out.println("  No FORBIDDEN outcomes observed.");
        }
        System.out.println();
    }

    public static void main(String[] args) throws InterruptedException {
        int iterations = 100_000;
        System.out.println("Running jcstress-style stress tests (" + iterations + " iterations each)");
        System.out.println("Note: The real jcstress harness uses a more sophisticated engine.");
        System.out.println("      See 02-jcstress.md for the real @JCStressTest / @Actor / @Arbiter approach.");
        System.out.println();

        runIncrementTest(iterations);
        runVolatileTest(iterations);
    }
}
```

## Gotchas

**ACCEPTABLE_INTERESTING outcomes warrant investigation.** They appear in yellow in the HTML report and are legal under the JMM, but the test author did not anticipate them. An ACCEPTABLE_INTERESTING result often indicates a surprising compiler or CPU optimization, or a case the test author failed to enumerate in their @Outcome annotations. Treat yellow rows as a prompt to verify that the outcome is genuinely acceptable, not an oversight.

**A test showing only ACCEPTABLE outcomes does not prove correctness.** jcstress may simply not have observed the racing interleaving within the available run time. To increase coverage, use the `-time` flag to extend per-test duration, run on multi-core hardware (more cores increase interleaving diversity), and run multiple times. The absence of a FORBIDDEN outcome is weak evidence; only the presence of one is definitive.

**@State class fields must be initialized only in the field declaration or a no-arg constructor.** The jcstress harness creates State instances reflectively using the no-arg constructor. Custom constructor arguments, factory methods, or `@Before`-style setup are not supported. Any initialization that requires parameters must be encoded as a fixed constant in the field declaration or computed in the no-arg constructor.

**The harness allocates and discards many State objects rapidly.** Any `@State` class that holds expensive resources — file handles, database connections, large off-heap buffers — will cause resource exhaustion because the harness creates thousands of instances without calling any teardown method. The `@State` class should hold only the minimal shared state being tested, not resources with lifecycle requirements.

**FORBIDDEN with `expect=FORBIDDEN` means the outcome was observed at least once.** This is not a statistical warning — it is confirmation of a verified concurrency bug. Once a FORBIDDEN result appears in the report, it cannot be dismissed as a fluke or a probabilistic anomaly. The bug exists in the code and the harness confirmed it. Fix the code and re-run until the FORBIDDEN row disappears from the results entirely.

**jcstress tests are not unit tests and do not fit standard test runners.** They cannot be run with JUnit or TestNG and do not produce pass/fail signals in the standard Surefire plugin output. They must be run as a separate Maven build phase and their results are HTML reports. Integrating jcstress into CI requires either parsing the HTML for FORBIDDEN outcomes or using the jcstress `results.xml` output with a custom script to fail the build when a FORBIDDEN outcome is observed.
