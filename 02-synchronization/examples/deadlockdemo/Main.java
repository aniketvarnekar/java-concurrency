/*
 * DeadlockDemo — Main
 *
 * Part 1: produces a deadlock by having Thread-Alpha and Thread-Beta acquire
 * two locks in opposite orders. DeadlockDetector polls ThreadMXBean and prints
 * the diagnostic when the cycle is confirmed.
 *
 * Part 2: fixes the deadlock by having both threads acquire locks in the same
 * order (FIXED_A then FIXED_B), eliminating the circular wait condition.
 *
 * The deadlocked threads in Part 1 are daemons so the JVM does not wait for
 * them; they are abandoned when Part 2 completes and main() returns.
 *
 * Separate lock objects are used for Part 1 and Part 2 so the daemon threads
 * holding Part 1's locks do not interfere with Part 2.
 */
package examples.deadlockdemo;

public class Main {

    private static final Object LOCK_A  = new Object(); // Part 1
    private static final Object LOCK_B  = new Object(); // Part 1
    private static final Object FIXED_A = new Object(); // Part 2
    private static final Object FIXED_B = new Object(); // Part 2

    public static void main(String[] args) throws InterruptedException {

        // --- Part 1: deadlock ---
        System.out.println("=== Part 1: deadlock ===");

        // Alpha acquires A then B; Beta acquires B then A — opposite orders create a cycle.
        Thread alpha    = new Thread(new LockAcquiringTask(LOCK_A, LOCK_B), "Thread-Alpha");
        Thread beta     = new Thread(new LockAcquiringTask(LOCK_B, LOCK_A), "Thread-Beta");
        Thread detector = new Thread(new DeadlockDetector(), "deadlock-detector");

        alpha.setDaemon(true);
        beta.setDaemon(true);

        alpha.start();
        beta.start();
        detector.start();
        detector.join(); // block until detector reports the deadlock and exits

        // --- Part 2: consistent lock ordering ---
        System.out.println("\n=== Part 2: consistent lock ordering (always A then B) ===");

        // Both threads acquire FIXED_A before FIXED_B — no circular wait is possible.
        Thread fixedAlpha = new Thread(new LockAcquiringTask(FIXED_A, FIXED_B), "Fixed-Alpha");
        Thread fixedBeta  = new Thread(new LockAcquiringTask(FIXED_A, FIXED_B), "Fixed-Beta");

        fixedAlpha.start();
        fixedBeta.start();
        fixedAlpha.join();
        fixedBeta.join();

        System.out.println("Part 2 completed — no deadlock.");
    }
}
