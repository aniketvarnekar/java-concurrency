/*
 * SharedCache — an in-memory key-value store guarded by a ReentrantReadWriteLock.
 *
 * Multiple threads may hold the read lock simultaneously, so concurrent get()
 * calls run in parallel. The write lock is exclusive: put() blocks until all
 * current readers release the read lock, and no new readers can start until
 * the write completes.
 *
 * putAndGet() demonstrates lock downgrading: it acquires the read lock before
 * releasing the write lock so that no other writer can intervene between the
 * write and the subsequent read of the same key.
 */
package examples.readwritelockdemo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

class SharedCache {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Map<String, String> store = new HashMap<>();
    private final ReentrantReadWriteLock rwLock    = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock  readLock  = rwLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();

    String get(String key) {
        readLock.lock();
        try {
            // getReadLockCount() > 1 confirms multiple readers are running
            // concurrently under the shared read lock.
            System.out.printf("[%s] %s reading '%s' — concurrent readers: %d%n",
                LocalTime.now().format(FMT), Thread.currentThread().getName(),
                key, rwLock.getReadLockCount());
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return store.get(key);
        } finally {
            readLock.unlock();
        }
    }

    void put(String key, String value) {
        writeLock.lock();
        try {
            // Exclusive access: no reader or other writer can proceed until
            // this write lock is released.
            System.out.printf("[%s] %s writing '%s'='%s'%n",
                LocalTime.now().format(FMT), Thread.currentThread().getName(), key, value);
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            store.put(key, value);
        } finally {
            writeLock.unlock();
        }
    }

    String putAndGet(String key, String value) {
        writeLock.lock();
        try {
            store.put(key, value);
            System.out.printf("[%s] %s downgrade: wrote '%s'='%s', acquiring read lock before releasing write%n",
                LocalTime.now().format(FMT), Thread.currentThread().getName(), key, value);
            // Acquire read lock while still holding the write lock (downgrade).
            // No other writer can slip in between the write above and the read below.
            readLock.lock();
        } finally {
            writeLock.unlock(); // write lock released; read lock still held
        }
        try {
            return store.get(key);
        } finally {
            readLock.unlock();
        }
    }
}
