/*
 * Runner — one participant in the CountDownLatch start-gate / end-gate demo.
 *
 * Waits on a start-gate latch (count=1) so all runners are released simultaneously,
 * simulates work for a random duration, then counts down the end-gate latch so
 * the main thread knows every runner has finished.
 *
 * countDown() is in a finally block so the end-gate decrements even if the
 * thread is interrupted mid-run — a missed decrement would stall the main thread forever.
 */
package examples.countdownlatchdemo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

class Runner implements Runnable {

    static final int RUNNER_COUNT = 5;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Random RNG = new Random();

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
            System.out.printf("[%s] [%s] waiting to start%n",
                    LocalTime.now().format(FMT), name);

            // Block until the start gate is opened by main
            startGate.await();

            System.out.printf("[%s] [%s] started%n",
                    LocalTime.now().format(FMT), name);

            long duration = 100 + RNG.nextInt(400);
            Thread.sleep(duration);

            System.out.printf("[%s] [%s] finished (ran for %dms)%n",
                    LocalTime.now().format(FMT), name, duration);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.printf("[%s] [%s] interrupted%n",
                    LocalTime.now().format(FMT), name);
        } finally {
            // Always count down — guarantees the end-gate reaches zero
            // even when this thread is interrupted before completing work.
            endGate.countDown();
        }
    }
}
