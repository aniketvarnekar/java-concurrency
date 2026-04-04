/**
 * Demonstrates ReentrantReadWriteLock: concurrent readers, exclusive writer, lock downgrading.
 *
 * Run: javac ReadWriteLockDemo.java && java ReadWriteLockDemo
 */

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ReadWriteLockDemo {

    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    static String now() {
        return LocalTime.now().format(FMT);
    }

    // -------------------------------------------------------------------------
    // Shared cache protected by a ReentrantReadWriteLock
    // -------------------------------------------------------------------------
    static class SharedCache {
        private final Map<String, String> store = new HashMap<>();
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
        private final ReentrantReadWriteLock.ReadLock  readLock  = rwLock.readLock();
        private final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();

        String get(String key) {
            readLock.lock();
            try {
                System.out.printf("[%s] [%s] get('%s') — read lock acquired, readers=%d%n",
                        now(), Thread.currentThread().getName(), key, rwLock.getReadLockCount());
                // Simulate some read work
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                String value = store.get(key);
                System.out.printf("[%s] [%s] get('%s') = '%s' — releasing read lock%n",
                        now(), Thread.currentThread().getName(), key, value);
                return value;
            } finally {
                readLock.unlock();
            }
        }

        void put(String key, String value) {
            writeLock.lock();
            try {
                System.out.printf("[%s] [%s] put('%s','%s') — WRITE LOCK acquired%n",
                        now(), Thread.currentThread().getName(), key, value);
                // Simulate some write work
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                store.put(key, value);
                System.out.printf("[%s] [%s] put('%s','%s') — done, releasing write lock%n",
                        now(), Thread.currentThread().getName(), key, value);
            } finally {
                writeLock.unlock();
            }
        }

        /**
         * Lock downgrading: acquire write lock, update data, then acquire read lock
         * before releasing the write lock — guarantees we see our own write during the read.
         */
        String refreshAndRead(String key, String newValue) {
            writeLock.lock();
            String result;
            try {
                System.out.printf("[%s] [%s] refresh: write lock acquired for '%s'%n",
                        now(), Thread.currentThread().getName(), key);
                store.put(key, newValue);
                System.out.printf("[%s] [%s] refresh: data updated, downgrading to read lock%n",
                        now(), Thread.currentThread().getName());

                // Acquire read lock BEFORE releasing write lock (downgrade)
                readLock.lock();
            } finally {
                writeLock.unlock(); // release write lock while still holding read lock
            }
            try {
                System.out.printf("[%s] [%s] refresh: holding read lock after downgrade%n",
                        now(), Thread.currentThread().getName());
                // Simulate reading the just-written data
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                result = store.get(key);
                System.out.printf("[%s] [%s] refresh: read result='%s', releasing read lock%n",
                        now(), Thread.currentThread().getName(), result);
            } finally {
                readLock.unlock();
            }
            return result;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        SharedCache cache = new SharedCache();

        // Pre-populate the cache
        cache.put("config.host", "localhost");
        cache.put("config.port", "8080");
        System.out.println();

        // ---- Demo 1: Concurrent readers and exclusive writer ----
        System.out.println("=== Demo 1: Concurrent readers + exclusive writer ===");

        Thread reader1 = new Thread(() -> {
            cache.get("config.host");
        }, "reader-thread-1");

        Thread reader2 = new Thread(() -> {
            cache.get("config.host");
        }, "reader-thread-2");

        Thread reader3 = new Thread(() -> {
            cache.get("config.port");
        }, "reader-thread-3");

        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(100); // let readers start first
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            cache.put("config.host", "prod-server-01");
        }, "writer-thread");

        // Start all readers and the writer nearly simultaneously
        reader1.start();
        reader2.start();
        reader3.start();
        writer.start();

        reader1.join();
        reader2.join();
        reader3.join();
        writer.join();

        System.out.println("\n--- Readers ran concurrently; writer blocked until all readers finished ---\n");

        // ---- Demo 2: Lock downgrading ----
        System.out.println("=== Demo 2: Lock downgrading (write → read) ===");

        Thread downgrader = new Thread(() -> {
            String result = cache.refreshAndRead("config.host", "downgraded-server");
            System.out.printf("[%s] [%s] refreshAndRead returned: '%s'%n",
                    now(), Thread.currentThread().getName(), result);
        }, "downgrade-thread");

        // Concurrent reader that will be blocked while downgrader holds write lock
        // but will proceed once downgrader downgrades to read lock
        Thread concurrentReader = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            System.out.printf("[%s] [%s] attempting concurrent read during downgrade...%n",
                    now(), Thread.currentThread().getName());
            String val = cache.get("config.host");
            System.out.printf("[%s] [%s] concurrent read completed, got: '%s'%n",
                    now(), Thread.currentThread().getName(), val);
        }, "concurrent-reader-thread");

        downgrader.start();
        concurrentReader.start();

        downgrader.join();
        concurrentReader.join();

        System.out.println("\nAll demos complete.");
    }
}
