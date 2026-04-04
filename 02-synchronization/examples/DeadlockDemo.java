/**
 * Demonstrates deadlock, runtime detection via ThreadMXBean, and prevention
 * via consistent lock ordering.
 *
 * Part 1 — Deadlock:
 *   Thread-Alpha acquires LOCK_A then tries to acquire LOCK_B.
 *   Thread-Beta  acquires LOCK_B then tries to acquire LOCK_A.
 *   Both block forever. A background detector thread uses
 *   ThreadMXBean.findDeadlockedThreads() to identify the cycle and
 *   prints the diagnostic information.
 *
 * Part 2 — Fixed:
 *   Both threads acquire locks in the same global order (LOCK_A then LOCK_B).
 *   The circular wait condition is eliminated; no deadlock occurs.
 *
 * Run:
 *   javac DeadlockDemo.java && java DeadlockDemo
 *
 * Note: Part 1 threads are daemon threads so the JVM exits after Part 2
 * completes even though Part 1 threads remain blocked.
 */
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

public class DeadlockDemo {

    // Two shared lock objects. The deadlock arises from acquiring these
    // in different orders in different threads.
    private static final Object LOCK_A = new Object();
    private static final Object LOCK_B = new Object();

    // -----------------------------------------------------------------------
    // Part 1: Induce a deadlock
    // -----------------------------------------------------------------------

    static Thread buildAlpha() {
        return new Thread(() -> {
            System.out.println("[Thread-Alpha] acquiring LOCK_A...");
            synchronized (LOCK_A) {
                System.out.println("[Thread-Alpha] acquired  LOCK_A");
                safeSleep(150); // hold LOCK_A long enough for Beta to grab LOCK_B

                System.out.println("[Thread-Alpha] waiting for LOCK_B...");
                synchronized (LOCK_B) {
                    // This line is never reached in the deadlock scenario
                    System.out.println("[Thread-Alpha] acquired LOCK_B -- doing work");
                }
            }
        }, "Thread-Alpha");
    }

    static Thread buildBeta() {
        return new Thread(() -> {
            System.out.println("[Thread-Beta]  acquiring LOCK_B...");
            synchronized (LOCK_B) {
                System.out.println("[Thread-Beta]  acquired  LOCK_B");
                safeSleep(150); // hold LOCK_B long enough for Alpha to grab LOCK_A

                System.out.println("[Thread-Beta]  waiting for LOCK_A...");
                synchronized (LOCK_A) {
                    // This line is never reached in the deadlock scenario
                    System.out.println("[Thread-Beta]  acquired LOCK_A -- doing work");
                }
            }
        }, "Thread-Beta");
    }

    // -----------------------------------------------------------------------
    // Deadlock detector: polls ThreadMXBean until deadlock is found
    // -----------------------------------------------------------------------

    static Thread buildDetector() {
        return new Thread(() -> {
            ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
            System.out.println("[Detector] watching for deadlock...");

            while (true) {
                safeSleep(200);
                long[] deadlockedIds = tmx.findDeadlockedThreads();
                if (deadlockedIds == null) {
                    System.out.println("[Detector] no deadlock yet, continuing to watch...");
                    continue;
                }

                System.out.println("\n*** DEADLOCK DETECTED ***");
                System.out.println("Threads involved: " + deadlockedIds.length);

                // getThreadInfo with stack traces and lock info
                ThreadInfo[] infos = tmx.getThreadInfo(deadlockedIds, true, true);
                for (ThreadInfo info : infos) {
                    System.out.println("  --------------------------------------------------");
                    System.out.printf("  Thread      : %s (id=%d)%n",
                            info.getThreadName(), info.getThreadId());
                    System.out.printf("  State       : %s%n", info.getThreadState());
                    System.out.printf("  Waiting on  : %s%n", info.getLockName());
                    System.out.printf("  Held by     : %s%n", info.getLockOwnerName());
                    System.out.println("  Stack trace (top 5 frames):");
                    StackTraceElement[] stack = info.getStackTrace();
                    int limit = Math.min(5, stack.length);
                    for (int i = 0; i < limit; i++) {
                        System.out.println("    at " + stack[i]);
                    }
                }

                System.out.println("\n[Detector] Diagnosis complete. Deadlocked threads are daemon");
                System.out.println("[Detector] threads and will be abandoned when main exits.");
                return; // stop the detector
            }
        }, "Deadlock-Detector");
    }

    // -----------------------------------------------------------------------
    // Part 2: Fixed version using consistent lock ordering
    // -----------------------------------------------------------------------

    static void runFixed() throws InterruptedException {
        System.out.println("\n=== Part 2: Fixed — consistent lock ordering (always A then B) ===\n");

        // Both tasks acquire locks in the same order: LOCK_A then LOCK_B.
        // The circular wait condition cannot form.
        Runnable orderedTask = () -> {
            String name = Thread.currentThread().getName();
            System.out.printf("[%s] acquiring LOCK_A...%n", name);
            synchronized (LOCK_A) {
                System.out.printf("[%s] acquired  LOCK_A%n", name);
                safeSleep(50);
                System.out.printf("[%s] acquiring LOCK_B...%n", name);
                synchronized (LOCK_B) {
                    System.out.printf("[%s] acquired  LOCK_B -- doing work%n", name);
                    safeSleep(30); // simulate work inside both locks
                }
                System.out.printf("[%s] released LOCK_B%n", name);
            }
            System.out.printf("[%s] released LOCK_A%n", name);
        };

        Thread fixedAlpha = new Thread(orderedTask, "Fixed-Alpha");
        Thread fixedBeta  = new Thread(orderedTask, "Fixed-Beta");

        fixedAlpha.start();
        fixedBeta.start();
        fixedAlpha.join();
        fixedBeta.join();

        System.out.println("\nFixed version completed successfully. No deadlock.");
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    static void safeSleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Part 1: Inducing deadlock ===\n");

        // Daemon threads: JVM will not wait for them to finish
        Thread alpha    = buildAlpha();
        Thread beta     = buildBeta();
        Thread detector = buildDetector();

        alpha.setDaemon(true);
        beta.setDaemon(true);
        detector.setDaemon(false); // keep JVM alive until detector finishes

        alpha.start();
        beta.start();
        detector.start();

        detector.join(); // wait for detector to finish its diagnosis

        runFixed();
    }
}
