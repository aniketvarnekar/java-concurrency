/*
 * PhaseCompleteAction — the barrier action executed at the end of each phase.
 *
 * The JVM guarantees that this action runs on the last thread to call barrier.await()
 * in a given phase, and that it completes before any thread is released to the next
 * phase. This means all workers have written their partial results to
 * Worker.partialResults by the time this code runs, making the read safe.
 */
package examples.cyclicbarrierdemo;

import java.util.concurrent.atomic.AtomicInteger;

class PhaseCompleteAction implements Runnable {

    private final AtomicInteger currentPhase;

    PhaseCompleteAction(AtomicInteger currentPhase) {
        this.currentPhase = currentPhase;
    }

    @Override
    public void run() {
        int phase = currentPhase.getAndIncrement();
        int total = 0;
        for (int r : Worker.partialResults) total += r;

        System.out.printf("%n=== Phase %d complete (barrier action) — "
                + "aggregated work time: %dms ===%n%n", phase, total);
    }
}
