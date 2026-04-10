/*
 * DeadlockDetector — polls ThreadMXBean until a deadlock cycle is found or the
 * thread is interrupted.
 *
 * findDeadlockedThreads() detects cycles through both intrinsic monitors
 * (synchronized) and java.util.concurrent locks. It does not detect threads
 * waiting on Semaphores, BlockingQueues, or external resources like database
 * row locks — those require inspecting stack traces and external tooling.
 */
package examples.deadlockdemo;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

class DeadlockDetector implements Runnable {

    @Override
    public void run() {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        while (!Thread.currentThread().isInterrupted()) {
            try { Thread.sleep(200); } catch (InterruptedException e) { return; }

            long[] ids = tmx.findDeadlockedThreads();
            if (ids == null) continue;

            // A non-null result means at least one deadlock cycle was found.
            System.out.println("\n*** DEADLOCK DETECTED involving " + ids.length + " thread(s) ***");
            ThreadInfo[] infos = tmx.getThreadInfo(ids, true, true);
            for (ThreadInfo info : infos) {
                System.out.println("  " + info.getThreadName()
                        + " [" + info.getThreadState() + "]"
                        + "  waiting for: " + info.getLockName()
                        + "  held by: " + info.getLockOwnerName());
            }
            return;
        }
    }
}
