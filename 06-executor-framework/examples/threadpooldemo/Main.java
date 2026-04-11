/*
 * ThreadPoolDemo — Main
 *
 * Demonstrates ThreadPoolExecutor with a bounded queue and CallerRunsPolicy:
 *   - core=2, max=4, queue capacity=3 → total capacity of 7 tasks before CallerRuns fires.
 *   - Submitting 12 tasks shows three phases: pool grows to 4 threads, queue fills to 3,
 *     then tasks 8–12 execute on the main (caller) thread instead of being rejected.
 *   - The per-submission pool stats ([main] submitting task-XX | pool=N active=N queued=N)
 *     make the thread-growth algorithm visible in real time.
 *   - CallerRunsPolicy provides implicit backpressure: the task-submitting thread blocks
 *     while running a task, slowing submission to match the pool's consumption rate.
 */
package examples.threadpooldemo;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        /*
         * Pool configuration:
         *   corePoolSize    = 2  → 2 threads kept alive always
         *   maximumPoolSize = 4  → up to 4 threads under load
         *   keepAlive       = 5s → extra threads (beyond core) expire after 5s idle
         *   queue           = ArrayBlockingQueue(3) → holds at most 3 waiting tasks
         *   rejection       = CallerRunsPolicy → when pool(4) + queue(3) all full,
         *                                        the submitting thread runs the task
         */
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2,
                4,
                5L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(3),
                new NamedThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        for (int i = 1; i <= 12; i++) {
            final int taskId = i;
            // Print pool state before each submit to show growth phases.
            System.out.printf("[main] submitting task-%02d | pool=%d active=%d queued=%d%n",
                    taskId,
                    pool.getPoolSize(),
                    pool.getActiveCount(),
                    pool.getQueue().size());
            pool.execute(() -> {
                String threadName = Thread.currentThread().getName();
                System.out.printf("[%s] task-%02d STARTED%n", threadName, taskId);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.printf("[%s] task-%02d INTERRUPTED%n", threadName, taskId);
                    return;
                }
                System.out.printf("[%s] task-%02d FINISHED%n", threadName, taskId);
            });
        }

        pool.shutdown();
        boolean terminated = pool.awaitTermination(30, TimeUnit.SECONDS);
        System.out.printf("[main] terminated=%s completedTasks=%d%n",
                terminated, pool.getCompletedTaskCount());
    }
}
