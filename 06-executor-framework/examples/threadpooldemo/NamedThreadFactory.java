/*
 * NamedThreadFactory — assigns descriptive names to pool threads and registers an
 * UncaughtExceptionHandler so that runtime exceptions thrown by execute()-submitted
 * tasks surface in the error log rather than being silently swallowed.
 *
 * Tasks submitted via submit() wrap exceptions inside the Future instead; they only
 * appear when get() is called. Tasks submitted via execute() bypass that wrapper, so
 * UncaughtExceptionHandler is the only place to observe those failures.
 */
package examples.threadpooldemo;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

class NamedThreadFactory implements ThreadFactory {
    private final AtomicInteger count = new AtomicInteger(1);

    @Override
    public Thread newThread(Runnable r) {
        String name = "pool-worker-" + count.getAndIncrement();
        Thread t = new Thread(r, name);
        t.setUncaughtExceptionHandler((thread, ex) ->
                System.err.printf("[UncaughtException] %s: %s%n",
                        thread.getName(), ex.getMessage()));
        return t;
    }
}
