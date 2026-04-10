/*
 * ManualCasCounter — a lock-free counter implemented with an explicit CAS retry loop.
 *
 * This is functionally equivalent to AtomicInteger.incrementAndGet() but exposes
 * the loop structure that the JDK hides internally: read the current value, compute
 * the desired new value, attempt a CAS, and retry if another thread changed the value
 * first. The loop guarantees that no increment is lost regardless of contention.
 */
package examples.casdemo;

import java.util.concurrent.atomic.AtomicInteger;

class ManualCasCounter {
    private final AtomicInteger value = new AtomicInteger(0);

    /** Increment and return the new value. Retries until the CAS succeeds. */
    int incrementAndGet() {
        int current;
        int next;
        do {
            current = value.get();   // 1. Read current value
            next    = current + 1;   // 2. Compute desired new value
            // 3. Only write if value hasn't changed since step 1.
            //    compareAndSet returns false if another thread changed it; loop retries.
        } while (!value.compareAndSet(current, next));
        return next;
    }

    int get() {
        return value.get();
    }
}
