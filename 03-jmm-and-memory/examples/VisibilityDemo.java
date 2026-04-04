/**
 * VisibilityDemo — demonstrates a visibility bug caused by missing volatile
 * and shows the correct fix.
 *
 * What it demonstrates:
 *   Without volatile, the JIT compiler may hoist the read of the stop flag
 *   out of the worker loop and cache it in a register, causing the worker
 *   thread to spin forever even after the main thread sets the flag to true.
 *   With volatile, the JMM requires every read to go through main memory,
 *   so the worker sees the update and exits cleanly.
 *
 * Run command:
 *   javac VisibilityDemo.java && java VisibilityDemo
 *
 * Note:
 *   The non-volatile version may not hang on every JVM or every run. The JIT
 *   compiler decides when to hoist the flag read, and this decision depends on
 *   how many times the loop executes before the JIT kicks in (typically after
 *   ~10,000 iterations). On a slow machine or with -Xint (interpreted mode),
 *   the loop may exit even without volatile because the JIT never optimizes it.
 *   The bug is real and specified by the JMM — the behavior is just non-deterministic.
 *   Use -server and -XX:+TieredCompilation for the best chance of reproducing it.
 */
public class VisibilityDemo {

    // -----------------------------------------------------------------------
    // Part 1: Without volatile (the bug)
    // -----------------------------------------------------------------------

    // The JIT is permitted to keep this field in a CPU register for the
    // duration of the worker loop. The main thread's write is not guaranteed
    // to be visible to the worker thread.
    private static boolean nonVolatileFlag = false;

    // -----------------------------------------------------------------------
    // Part 2: With volatile (the fix)
    // -----------------------------------------------------------------------

    // volatile forces every read to go to main memory and every write to be
    // flushed immediately. The JMM guarantees visibility across threads.
    private static volatile boolean volatileFlag = false;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== VisibilityDemo ===");
        System.out.println();

        demonstrateNonVolatile();
        demonstrateVolatile();
    }

    // -------------------------------------------------------------------------

    static void demonstrateNonVolatile() throws InterruptedException {
        nonVolatileFlag = false;
        System.out.println("[main] Starting non-volatile demo...");
        System.out.println("[main] WARNING: worker may spin forever without volatile.");
        System.out.println("[main] This demo will force-exit the worker after 500ms.");

        Thread worker = new Thread(() -> {
            System.out.println("[non-volatile-worker] loop started");
            long iters = 0;
            // The JIT may transform this into: while (true) { iters++; }
            // because it never sees nonVolatileFlag change within this method.
            while (!nonVolatileFlag) {
                iters++;
            }
            // May never reach this line
            System.out.println("[non-volatile-worker] stopped after " + iters + " iterations");
        }, "non-volatile-worker");

        worker.setDaemon(true); // daemon so it doesn't block JVM exit
        worker.start();

        Thread.sleep(200); // Let the worker run and the JIT optimize the loop

        System.out.println("[main] setting nonVolatileFlag = true");
        nonVolatileFlag = true;

        worker.join(300); // Wait up to 300ms
        if (worker.isAlive()) {
            System.out.println("[main] non-volatile-worker is STILL RUNNING after 300ms");
            System.out.println("[main] This confirms the visibility bug: the worker");
            System.out.println("[main] never saw the write to nonVolatileFlag.");
        } else {
            System.out.println("[main] non-volatile-worker exited (JIT did not hoist the read this run)");
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------

    static void demonstrateVolatile() throws InterruptedException {
        volatileFlag = false;
        System.out.println("[main] Starting volatile demo...");

        Thread worker = new Thread(() -> {
            System.out.println("[volatile-worker] loop started");
            long iters = 0;
            // volatile guarantees: every read of volatileFlag goes to main memory.
            // The JIT is NOT allowed to hoist this read out of the loop.
            while (!volatileFlag) {
                iters++;
            }
            System.out.println("[volatile-worker] stopped cleanly after " + iters + " iterations");
        }, "volatile-worker");

        worker.start();

        Thread.sleep(200); // Let the worker run

        System.out.println("[main] setting volatileFlag = true");
        volatileFlag = true;

        worker.join(2000); // Should exit well within 2 seconds
        if (worker.isAlive()) {
            System.out.println("[main] ERROR: volatile-worker did not stop — unexpected!");
        } else {
            System.out.println("[main] volatile-worker exited as expected");
        }

        System.out.println();
        System.out.println("=== Demo complete ===");
        System.out.println("Summary:");
        System.out.println("  volatile flag  -> worker always stops (JMM guaranteed)");
        System.out.println("  plain flag     -> worker may spin forever (JMM undefined)");
    }
}
