/**
 * Demonstrates CyclicBarrier: multi-phase computation with barrier action.
 *
 * Run: javac CyclicBarrierDemo.java && java CyclicBarrierDemo
 */

import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

public class CyclicBarrierDemo {

    private static final int WORKER_COUNT = 4;
    private static final int PHASE_COUNT  = 3;
    private static final Random RNG       = new Random();

    // Shared per-phase accumulator — workers write their partial results here,
    // the barrier action reads the total.
    private static final int[] partialResults = new int[WORKER_COUNT];
    private static final AtomicInteger currentPhase = new AtomicInteger(1);

    // -----------------------------------------------------------------------
    // Worker: performs PHASE_COUNT phases, awaiting the barrier after each one.
    // -----------------------------------------------------------------------
    static class Worker implements Runnable {
        private final int id;
        private final CyclicBarrier barrier;

        Worker(int id, CyclicBarrier barrier) {
            this.id      = id;
            this.barrier = barrier;
        }

        @Override
        public void run() {
            String name = Thread.currentThread().getName();
            try {
                for (int phase = 1; phase <= PHASE_COUNT; phase++) {
                    // Simulate phase work with a random duration
                    int workMs = 100 + RNG.nextInt(300);
                    Thread.sleep(workMs);

                    // Write a partial result for this phase
                    partialResults[id - 1] = workMs;

                    System.out.printf("[%s] phase %d done (work=%dms)%n",
                        name, phase, workMs);

                    // Wait for all workers to finish this phase.
                    // The barrier action will run on the last thread to arrive.
                    barrier.await();

                    // All workers are released simultaneously here.
                    // The barrier has already auto-reset for the next phase.
                }

                System.out.println("[" + name + "] all " + PHASE_COUNT
                    + " phases complete — work done");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[" + name + "] interrupted");
            } catch (BrokenBarrierException e) {
                // Thrown if another waiting thread was interrupted, the barrier
                // was reset, or the barrier action threw an exception.
                System.err.println("[" + name + "] barrier broken — aborting");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Barrier action: runs on the last thread to arrive at each phase.
    // All workers have written their partial results by the time this runs.
    // -----------------------------------------------------------------------
    static class PhaseCompleteAction implements Runnable {
        @Override
        public void run() {
            int phase = currentPhase.getAndIncrement();
            int total = 0;
            for (int r : partialResults) total += r;

            System.out.printf("%n=== Phase %d complete (barrier action) — "
                + "aggregated work time: %dms ===%n%n", phase, total);
        }
    }

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== CyclicBarrier Demo ===");
        System.out.printf("Workers: %d | Phases: %d%n%n", WORKER_COUNT, PHASE_COUNT);

        // Create the barrier with the barrier action
        CyclicBarrier barrier = new CyclicBarrier(WORKER_COUNT, new PhaseCompleteAction());

        Thread[] workers = new Thread[WORKER_COUNT];
        for (int i = 1; i <= WORKER_COUNT; i++) {
            workers[i - 1] = new Thread(new Worker(i, barrier), "worker-" + i);
        }

        for (Thread w : workers) w.start();
        for (Thread w : workers) w.join();

        System.out.println();
        System.out.println("Key observations:");
        System.out.println("  - The barrier action printed 'Phase N complete' exactly "
            + PHASE_COUNT + " times.");
        System.out.println("  - The barrier auto-reset after each phase — no manual reset needed.");
        System.out.println("  - All workers started the next phase simultaneously after each barrier trip.");
        System.out.println("  - The barrier action ran on whichever thread arrived last in each phase.");
    }
}
