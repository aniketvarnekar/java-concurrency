/*
 * Worker — one participant in the CyclicBarrier multi-phase demo.
 *
 * Performs PHASE_COUNT phases of simulated work. After each phase it calls
 * barrier.await(), which blocks until all workers have finished that phase.
 * The barrier auto-resets for the next phase after the barrier action runs,
 * so no manual reset is needed between phases.
 *
 * BrokenBarrierException is thrown to all waiting threads if any thread is
 * interrupted while waiting, the barrier is reset externally, or the barrier
 * action throws. Each worker catches it and aborts cleanly.
 */
package examples.cyclicbarrierdemo;

import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

class Worker implements Runnable {

    static final int WORKER_COUNT = 4;
    static final int PHASE_COUNT  = 3;

    private static final Random RNG = new Random();

    // Shared per-phase accumulator — written by each worker after its phase work,
    // read by PhaseCompleteAction once all workers have arrived at the barrier.
    // Safe without additional locking: the barrier arrival guarantees all writes
    // happen-before the barrier action reads them.
    static final int[] partialResults = new int[WORKER_COUNT];

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
                int workMs = 100 + RNG.nextInt(300);
                Thread.sleep(workMs);

                // Record this worker's contribution for the barrier action to aggregate
                partialResults[id - 1] = workMs;

                System.out.printf("[%s] phase %d done (work=%dms)%n",
                        name, phase, workMs);

                // Block until all WORKER_COUNT threads have arrived.
                // The last to arrive runs PhaseCompleteAction, then all are released.
                barrier.await();
            }

            System.out.println("[" + name + "] all " + PHASE_COUNT
                    + " phases complete — work done");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[" + name + "] interrupted");
        } catch (BrokenBarrierException e) {
            // Another waiting thread was interrupted, the barrier was reset,
            // or the barrier action threw — this worker cannot continue safely.
            System.err.println("[" + name + "] barrier broken — aborting");
        }
    }
}
