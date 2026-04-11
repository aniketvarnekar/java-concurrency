/*
 * CyclicBarrierDemo — Main
 *
 * WORKER_COUNT worker threads each perform PHASE_COUNT phases of simulated work.
 * After each phase every worker calls barrier.await(). When the last worker
 * arrives, PhaseCompleteAction runs on that thread and aggregates the partial
 * results. The barrier then auto-resets, releasing all workers into the next phase.
 *
 * Key properties shown:
 *   - The barrier action runs exactly once per phase, always after all workers finish.
 *   - The barrier is reusable across phases with no manual reset.
 *   - All workers start each new phase simultaneously after the barrier trips.
 */
package examples.cyclicbarrierdemo;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        AtomicInteger currentPhase = new AtomicInteger(1);

        CyclicBarrier barrier = new CyclicBarrier(
                Worker.WORKER_COUNT, new PhaseCompleteAction(currentPhase));

        Thread[] workers = new Thread[Worker.WORKER_COUNT];
        for (int i = 1; i <= Worker.WORKER_COUNT; i++) {
            workers[i - 1] = new Thread(new Worker(i, barrier), "worker-" + i);
        }

        for (Thread w : workers) w.start();
        for (Thread w : workers) w.join();
    }
}
