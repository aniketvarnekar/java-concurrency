/*
 * ReadWriteLockDemo — Main
 *
 * Two demonstrations:
 *
 *   1. Concurrent readers vs exclusive writer: three reader threads and one
 *      writer start nearly simultaneously. The timestamps and reader counts in
 *      the output confirm readers overlap each other, while the writer runs in
 *      isolation after all readers finish.
 *
 *   2. Lock downgrading: a thread updates a key and reads it back without
 *      releasing the write lock first, ensuring no other writer can intervene
 *      between the write and the subsequent read.
 */
package examples.readwritelockdemo;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        SharedCache cache = new SharedCache();
        cache.put("host", "localhost");
        cache.put("port", "8080");

        System.out.println();
        demonstrateConcurrentReaders(cache);
        System.out.println();
        demonstrateLockDowngrade(cache);
    }

    // -------------------------------------------------------------------------

    static void demonstrateConcurrentReaders(SharedCache cache) throws InterruptedException {
        Thread reader1 = new Thread(() -> cache.get("host"), "reader-1");
        Thread reader2 = new Thread(() -> cache.get("host"), "reader-2");
        Thread reader3 = new Thread(() -> cache.get("port"), "reader-3");

        // Writer starts 50ms after readers so it arrives while they are active.
        // It must wait for all three readers to release the read lock before
        // it can acquire the exclusive write lock.
        Thread writer = new Thread(() -> cache.put("host", "prod-server-01"), "writer");

        reader1.start();
        reader2.start();
        reader3.start();
        Thread.sleep(50);
        writer.start();

        reader1.join();
        reader2.join();
        reader3.join();
        writer.join();
    }

    // -------------------------------------------------------------------------

    static void demonstrateLockDowngrade(SharedCache cache) throws InterruptedException {
        Thread downgrader = new Thread(() -> {
            String result = cache.putAndGet("timeout", "30s");
            System.out.printf("[%s] putAndGet returned: '%s'%n",
                Thread.currentThread().getName(), result);
        }, "downgrader");

        downgrader.start();
        downgrader.join();
    }
}
