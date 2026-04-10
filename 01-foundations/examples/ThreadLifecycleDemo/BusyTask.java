/*
 * BusyTask — demonstrates the RUNNABLE state.
 *
 * Spins in a tight loop so the thread remains schedulable without blocking
 * on a monitor or I/O. The volatile flag lets Main signal termination across
 * the cache-line boundary without a full memory fence.
 */
package examples.threadlifecycledemo;

class BusyTask implements Runnable {

    // volatile guarantees the write by Main is visible to this thread.
    volatile boolean done = false;

    @Override
    public void run() {
        while (!done) {
            // intentional spin — keeps the thread in RUNNABLE
        }
    }
}
