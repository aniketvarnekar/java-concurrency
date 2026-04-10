/*
 * VisibilityDemo — Main
 *
 * Demonstrates the visibility hazard caused by missing volatile on a stop flag.
 * A worker thread spins on a shared flag. Without volatile, the JIT may cache
 * the flag in a CPU register and never re-read it from memory, so the worker
 * never observes the main thread's write. With volatile, every read of the
 * flag goes to main memory and the write is always observed.
 *
 * Note: the non-volatile version may not hang on every JVM or every run.
 * Whether the JIT hoists the read depends on how many iterations execute
 * before tier-2 compilation kicks in (typically ~10,000). Use -server and
 * -XX:+TieredCompilation for the best chance of reproducing the bug.
 */
package examples.visibilitydemo;

public class Main {

    // Without volatile, the JIT may keep this in a register for the entire
    // duration of the worker loop and never reload it from main memory.
    static boolean plainStopped = false;

    // volatile forces every read to go to main memory. The JMM guarantees
    // the worker will observe the main thread's write.
    static volatile boolean volatileStopped = false;

    public static void main(String[] args) throws InterruptedException {
        demonstrateNonVolatile();
        demonstrateVolatile();
    }

    // -------------------------------------------------------------------------

    static void demonstrateNonVolatile() throws InterruptedException {
        plainStopped = false;

        Thread worker = new Thread(() -> {
            long iters = 0;
            // The JIT may rewrite this as: while (true) { iters++; }
            // because plainStopped is never written inside the loop, so the
            // JIT treats it as an unmodifiable invariant for this method.
            while (!plainStopped) {
                iters++;
            }
            // May never reach this line if the JIT hoisted the read
            System.out.println("[non-volatile-worker] stopped after " + iters + " iterations");
        }, "non-volatile-worker");

        worker.setDaemon(true); // daemon so it does not block JVM exit if it spins forever
        worker.start();

        Thread.sleep(200); // let the loop run long enough for the JIT to optimize it

        plainStopped = true;

        worker.join(300);
        if (worker.isAlive()) {
            System.out.println("[main] non-volatile-worker still running — write not visible to worker");
        } else {
            System.out.println("[main] non-volatile-worker exited — JIT did not hoist read this run");
        }
    }

    // -------------------------------------------------------------------------

    static void demonstrateVolatile() throws InterruptedException {
        volatileStopped = false;

        Thread worker = new Thread(() -> {
            long iters = 0;
            // volatile prohibits hoisting: the JIT must emit a fresh memory
            // read on every iteration. The worker always sees the write.
            while (!volatileStopped) {
                iters++;
            }
            System.out.println("[volatile-worker] stopped after " + iters + " iterations");
        }, "volatile-worker");

        worker.start();

        Thread.sleep(200);

        volatileStopped = true;

        worker.join(2000);
        if (worker.isAlive()) {
            System.out.println("[main] ERROR: volatile-worker did not stop");
        } else {
            System.out.println("[main] volatile-worker exited as expected");
        }
    }
}
