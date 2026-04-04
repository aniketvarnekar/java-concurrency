/**
 * Demonstrates CountDownLatch: start-gate pattern and end-gate pattern.
 *
 * Run: javac CountDownLatchDemo.java && java CountDownLatchDemo
 */

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

public class CountDownLatchDemo {

    private static final int RUNNER_COUNT = 5;
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Random RNG = new Random();

    private static String now() {
        return LocalTime.now().format(FMT);
    }

    // -----------------------------------------------------------------------
    // Runner: waits on startGate, does work, counts down on endGate.
    // -----------------------------------------------------------------------
    static class Runner implements Runnable {
        private final int id;
        private final CountDownLatch startGate;
        private final CountDownLatch endGate;

        Runner(int id, CountDownLatch startGate, CountDownLatch endGate) {
            this.id        = id;
            this.startGate = startGate;
            this.endGate   = endGate;
        }

        @Override
        public void run() {
            String name = Thread.currentThread().getName();
            try {
                System.out.printf("[%s] [%s] waiting to start%n", now(), name);

                // Block until the start gate is opened
                startGate.await();

                System.out.printf("[%s] [%s] started%n", now(), name);

                // Simulate race — random duration between 100ms and 500ms
                long duration = 100 + RNG.nextInt(400);
                Thread.sleep(duration);

                System.out.printf("[%s] [%s] finished (ran for %dms)%n",
                    now(), name, duration);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.printf("[%s] [%s] interrupted%n", now(), name);
            } finally {
                // Always count down on the end gate, even if interrupted or thrown
                endGate.countDown();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== CountDownLatch Demo ===");
        System.out.println("Runners: " + RUNNER_COUNT);
        System.out.println();

        // Start-gate: count=1. One call to countDown() releases all runners.
        CountDownLatch startGate = new CountDownLatch(1);

        // End-gate: count=N. Each runner calls countDown() once when done.
        CountDownLatch endGate = new CountDownLatch(RUNNER_COUNT);

        // Create and start all runner threads
        for (int i = 1; i <= RUNNER_COUNT; i++) {
            Thread t = new Thread(
                new Runner(i, startGate, endGate),
                "runner-" + i
            );
            t.start();
        }

        // Give all runners a moment to reach the start gate before opening it.
        // In a real benchmark this sleep is replaced by verifying all threads
        // are in await(), but for a demo a short pause is sufficient.
        Thread.sleep(200);

        System.out.printf("[%s] [Main] opening start gate — releasing all runners...%n%n",
            now());

        // Open the start gate: all runners blocked in await() are released simultaneously.
        startGate.countDown();

        // Wait for all runners to finish via the end gate.
        endGate.await();

        System.out.printf("%n[%s] [Main] all runners finished%n", now());
        System.out.println();
        System.out.println("Key observations:");
        System.out.println("  - All runners printed 'started' timestamps within a few");
        System.out.println("    milliseconds of each other (simultaneous release).");
        System.out.println("  - 'finished' timestamps are spread out (random work duration).");
        System.out.println("  - Main thread unblocked only after the last runner finished.");
        System.out.println("  - countDown() was in a finally block — endGate always decrements.");
    }
}
