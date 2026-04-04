/**
 * Demonstrates ThreadPoolExecutor with custom configuration: bounded queue,
 * CallerRunsPolicy rejection handler, and named ThreadFactory.
 *
 * Run: javac ThreadPoolDemo.java && java ThreadPoolDemo
 */

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPoolDemo {

    // Named ThreadFactory: threads are named "pool-worker-N"
    static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            String name = "pool-worker-" + count.getAndIncrement();
            Thread t = new Thread(r, name);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    System.err.printf("[UncaughtException] %s: %s%n", thread.getName(), ex.getMessage()));
            return t;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        /*
         * Pool configuration:
         *   corePoolSize    = 2  → 2 threads kept alive always
         *   maximumPoolSize = 4  → up to 4 threads under load
         *   keepAlive       = 5s → extra threads expire after 5s idle
         *   queue           = ArrayBlockingQueue(3) → holds at most 3 waiting tasks
         *   rejection       = CallerRunsPolicy → when pool(4) + queue(3) full, caller runs task
         *
         * Total capacity before CallerRunsPolicy fires: 4 threads + 3 queued = 7 tasks in-flight.
         * Tasks 8-12 will be run by the main (caller) thread.
         */
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2,
                4,
                5L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(3),
                new NamedThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        System.out.println("Pool created: core=2, max=4, queueCapacity=3, policy=CallerRunsPolicy");
        System.out.println("Submitting 12 tasks (each sleeps 500ms)...");
        System.out.println("Expect: tasks 1-4 run on pool threads, tasks 5-7 queued,");
        System.out.println("        tasks 8-12 run on main thread (CallerRunsPolicy)\n");

        for (int i = 1; i <= 12; i++) {
            final int taskId = i;
            System.out.printf("[main] submitting task-%02d | poolSize=%d active=%d queued=%d%n",
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

        System.out.println("\n[main] all tasks submitted, shutting down pool");
        pool.shutdown();
        boolean terminated = pool.awaitTermination(30, TimeUnit.SECONDS);
        System.out.printf("%n[main] pool terminated=%s, completedTasks=%d%n",
                terminated, pool.getCompletedTaskCount());
        System.out.println("Demo complete.");
    }
}
