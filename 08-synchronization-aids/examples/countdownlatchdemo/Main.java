/*
 * CountDownLatchDemo — Main
 *
 * Demonstrates two CountDownLatch coordination patterns:
 *   1. Start-gate (count=1): a single countDown() releases all RUNNER_COUNT
 *      threads simultaneously, so their start timestamps are nearly identical.
 *   2. End-gate (count=RUNNER_COUNT): each runner calls countDown() once on
 *      completion; the main thread blocks in await() until all runners finish.
 *
 * The two-latch pattern is the standard way to measure elapsed time for a group
 * of threads: open the start gate, record the time, then wait on the end gate.
 */
package examples.countdownlatchdemo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;

public class Main {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void main(String[] args) throws InterruptedException {
        // Start-gate: count=1 — one countDown() releases all runners at once
        CountDownLatch startGate = new CountDownLatch(1);

        // End-gate: count=N — each runner decrements once when done
        CountDownLatch endGate = new CountDownLatch(Runner.RUNNER_COUNT);

        for (int i = 1; i <= Runner.RUNNER_COUNT; i++) {
            new Thread(new Runner(i, startGate, endGate), "runner-" + i).start();
        }

        // Give all runners a moment to reach the start gate before opening it.
        // In a real benchmark this sleep is replaced by verifying all threads
        // are in await(), but for a demo a short pause is sufficient.
        Thread.sleep(200);

        System.out.printf("[%s] [Main] opening start gate — releasing all runners...%n%n",
                LocalTime.now().format(FMT));

        // Open the start gate: all threads blocked in startGate.await() are
        // released simultaneously on the next scheduler round.
        startGate.countDown();

        // Block until every runner has called endGate.countDown()
        endGate.await();

        System.out.printf("%n[%s] [Main] all runners finished%n",
                LocalTime.now().format(FMT));
    }
}
