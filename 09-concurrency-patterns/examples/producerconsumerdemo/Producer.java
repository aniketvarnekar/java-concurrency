/*
 * Producer — generates items and places them on a shared BlockingQueue.
 *
 * put() blocks when the queue is full, applying natural back-pressure:
 * the producer cannot race ahead of the consumers by more than QUEUE_CAPACITY items.
 * InterruptedException from put() is handled by restoring the interrupt flag
 * so the thread's interrupted status is not silently swallowed.
 */
package examples.producerconsumerdemo;

import java.util.concurrent.BlockingQueue;

class Producer implements Runnable {

    private final int producerNum;
    private final BlockingQueue<String> queue;
    private final int itemCount;

    Producer(int producerNum, BlockingQueue<String> queue, int itemCount) {
        this.producerNum = producerNum;
        this.queue       = queue;
        this.itemCount   = itemCount;
    }

    @Override
    public void run() {
        try {
            for (int m = 1; m <= itemCount; m++) {
                String item = "P" + producerNum + "-item-" + m;
                // Blocks if the queue is full — back-pressure from slow consumers
                queue.put(item);
                System.out.printf("[Producer-%d] produced: %s at %dms%n",
                        producerNum, item, Main.elapsed());
                Thread.sleep(80);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.printf("[Producer-%d] interrupted%n", producerNum);
        }
    }
}
