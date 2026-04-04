/**
 * Demonstrates StampedLock: optimistic reads with validate() fallback, write lock, concurrent access.
 *
 * Run: javac StampedLockDemo.java && java StampedLockDemo
 */

import java.util.concurrent.locks.StampedLock;
import java.util.concurrent.atomic.AtomicInteger;

public class StampedLockDemo {

    static class Point {
        private double x;
        private double y;
        private final StampedLock lock = new StampedLock();

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

        /**
         * Computes the distance from the origin using an optimistic read.
         * Falls back to a pessimistic read lock if a write occurred during the read.
         */
        double distanceFromOrigin(AtomicInteger optimisticCount, AtomicInteger fallbackCount) {
            long stamp = lock.tryOptimisticRead();
            // Read shared state without any lock
            double curX = x;
            double curY = y;

            if (lock.validate(stamp)) {
                optimisticCount.incrementAndGet();
                System.out.printf("[%s] optimistic read validated — x=%.2f y=%.2f distance=%.4f%n",
                        Thread.currentThread().getName(), curX, curY,
                        Math.sqrt(curX * curX + curY * curY));
                return Math.sqrt(curX * curX + curY * curY);
            }

            // Optimistic read failed — a write occurred between tryOptimisticRead and validate
            fallbackCount.incrementAndGet();
            System.out.printf("[%s] optimistic read INVALIDATED — falling back to read lock%n",
                    Thread.currentThread().getName());
            stamp = lock.readLock();
            try {
                curX = x;
                curY = y;
                double dist = Math.sqrt(curX * curX + curY * curY);
                System.out.printf("[%s] pessimistic read lock — x=%.2f y=%.2f distance=%.4f%n",
                        Thread.currentThread().getName(), curX, curY, dist);
                return dist;
            } finally {
                lock.unlockRead(stamp);
            }
        }

        @Override
        public String toString() {
            long stamp = lock.tryOptimisticRead();
            double curX = x;
            double curY = y;
            if (!lock.validate(stamp)) {
                stamp = lock.readLock();
                try {
                    curX = x;
                    curY = y;
                } finally {
                    lock.unlockRead(stamp);
                }
            }
            return String.format("Point(%.2f, %.2f)", curX, curY);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Point point = new Point(0.0, 0.0);
        AtomicInteger optimisticSuccess = new AtomicInteger(0);
        AtomicInteger fallbackCount     = new AtomicInteger(0);

        final int READER_ITERATIONS = 10;
        final int WRITER_ITERATIONS = 8;

        // Writer thread: moves the point repeatedly with deliberate overlap with readers
        Thread writerThread = new Thread(() -> {
            for (int i = 0; i < WRITER_ITERATIONS; i++) {
                point.move(1.5, 2.0);
                System.out.printf("[%s] moved point → %s%n",
                        Thread.currentThread().getName(), point);
                try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }, "writer-thread");

        // Reader thread 1: reads distance frequently, intentionally interleaved with writes
        Thread readerThread1 = new Thread(() -> {
            for (int i = 0; i < READER_ITERATIONS; i++) {
                point.distanceFromOrigin(optimisticSuccess, fallbackCount);
                try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }, "reader-thread-1");

        // Reader thread 2: reads at a different pace to increase stamp invalidation chances
        Thread readerThread2 = new Thread(() -> {
            for (int i = 0; i < READER_ITERATIONS; i++) {
                point.distanceFromOrigin(optimisticSuccess, fallbackCount);
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }, "reader-thread-2");

        // Reader thread 3: very frequent reads — most will be validated, some will fall back
        Thread readerThread3 = new Thread(() -> {
            for (int i = 0; i < READER_ITERATIONS; i++) {
                point.distanceFromOrigin(optimisticSuccess, fallbackCount);
                try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }, "reader-thread-3");

        System.out.println("Starting concurrent readers and writer...");
        System.out.println("=========================================");

        readerThread1.start();
        readerThread2.start();
        readerThread3.start();
        writerThread.start();

        readerThread1.join();
        readerThread2.join();
        readerThread3.join();
        writerThread.join();

        System.out.println("=========================================");
        System.out.printf("Final point: %s%n", point);
        System.out.printf("Optimistic reads validated: %d%n", optimisticSuccess.get());
        System.out.printf("Pessimistic fallbacks used:  %d%n", fallbackCount.get());
        System.out.println("Demo complete.");
    }
}
