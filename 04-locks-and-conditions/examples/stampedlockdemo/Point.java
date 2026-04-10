/*
 * Point — a 2D coordinate guarded by a StampedLock.
 *
 * move() uses the exclusive write lock. distanceFromOrigin() tries the
 * optimistic read path first: it reads x and y without acquiring any lock,
 * then calls validate() to confirm no writer intervened. If validation fails,
 * it falls back to a full read lock and re-reads consistent values.
 *
 * The optimistic path eliminates read-write contention entirely in the common
 * case where writes are infrequent. The fallback count in the output shows
 * how many reads were forced to take the slower path due to concurrent writes.
 */
package examples.stampedlockdemo;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

class Point {

    private double x;
    private double y;
    private final StampedLock lock = new StampedLock();

    // Counters show how often each read path is taken
    private final AtomicInteger optimisticCount = new AtomicInteger(0);
    private final AtomicInteger fallbackCount   = new AtomicInteger(0);

    Point(double x, double y) {
        this.x = x;
        this.y = y;
    }

    void move(double deltaX, double deltaY) {
        long stamp = lock.writeLock();
        try {
            x += deltaX;
            y += deltaY;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    double distanceFromOrigin() {
        // Attempt an optimistic read: records the current write state as a stamp
        // without blocking. No lock is held during the reads below.
        long stamp = lock.tryOptimisticRead();
        double curX = x;
        double curY = y;

        if (lock.validate(stamp)) {
            // No write occurred between tryOptimisticRead and validate — the
            // values of curX and curY are consistent.
            optimisticCount.incrementAndGet();
            return Math.sqrt(curX * curX + curY * curY);
        }

        // validate() returned false: a write lock was acquired after our stamp.
        // Fall back to a full pessimistic read lock for a consistent re-read.
        fallbackCount.incrementAndGet();
        stamp = lock.readLock();
        try {
            curX = x;
            curY = y;
            return Math.sqrt(curX * curX + curY * curY);
        } finally {
            lock.unlockRead(stamp);
        }
    }

    int getOptimisticCount() { return optimisticCount.get(); }
    int getFallbackCount()   { return fallbackCount.get(); }

    @Override
    public String toString() {
        long stamp = lock.tryOptimisticRead();
        double curX = x, curY = y;
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try { curX = x; curY = y; } finally { lock.unlockRead(stamp); }
        }
        return String.format("(%.1f, %.1f)", curX, curY);
    }
}
