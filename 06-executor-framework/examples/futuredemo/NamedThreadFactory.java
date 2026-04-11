/*
 * NamedThreadFactory — assigns descriptive names to pool threads so that thread dumps
 * and log output identify which pool thread is running each task.
 */
package examples.futuredemo;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

class NamedThreadFactory implements ThreadFactory {
    private final AtomicInteger count = new AtomicInteger(1);
    private final String prefix;

    NamedThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable r) {
        return new Thread(r, prefix + "-" + count.getAndIncrement());
    }
}
