/*
 * Producer — puts items into the shared queue until ITEMS_PER_PRODUCER items are produced.
 *
 * put() blocks when the queue is full, providing natural backpressure: a producer that
 * outpaces the consumers will park here rather than accumulating unbounded work. When the
 * producer finishes its batch it decrements doneLatch so the main thread knows when to
 * send the poison pills that shut down the consumers.
 */
package examples.blockingqueuedemo;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;

class Producer implements Runnable {
    static final int ITEMS_PER_PRODUCER = 5;

    private final int id;
    private final ArrayBlockingQueue<String> queue;
    private final CountDownLatch doneLatch;

    Producer(int id, ArrayBlockingQueue<String> queue, CountDownLatch doneLatch) {
        this.id        = id;
        this.queue     = queue;
        this.doneLatch = doneLatch;
    }

    @Override
    public void run() {
        try {
            for (int m = 1; m <= ITEMS_PER_PRODUCER; m++) {
                String item = "producer-" + id + "-item-" + m;
                // Blocks if the queue is full — consumers must drain before production resumes.
                queue.put(item);
                System.out.printf("[%s] produced: %s%n",
                        Thread.currentThread().getName(), item);
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.printf("[%s] interrupted%n", Thread.currentThread().getName());
        } finally {
            doneLatch.countDown();
        }
    }
}
