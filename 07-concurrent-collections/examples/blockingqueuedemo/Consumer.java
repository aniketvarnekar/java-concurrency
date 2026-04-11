/*
 * Consumer — takes items from the shared queue and processes them until it dequeues a
 * poison pill, at which point it shuts down.
 *
 * take() blocks when the queue is empty — the consumer parks rather than spinning.
 * The poison pill is a sentinel value agreed upon by producers and consumers. One pill
 * per consumer is required because a pill absorbed by one consumer does not reach others.
 */
package examples.blockingqueuedemo;

import java.util.concurrent.ArrayBlockingQueue;

class Consumer implements Runnable {
    static final String POISON = "POISON";

    private final int id;
    private final ArrayBlockingQueue<String> queue;

    Consumer(int id, ArrayBlockingQueue<String> queue) {
        this.id    = id;
        this.queue = queue;
    }

    @Override
    public void run() {
        try {
            while (true) {
                // Blocks if the queue is empty — no busy-waiting.
                String item = queue.take();
                if (POISON.equals(item)) {
                    System.out.printf("[%s] received poison pill — shutting down%n",
                            Thread.currentThread().getName());
                    break;
                }
                System.out.printf("[%s] consumed: %s%n",
                        Thread.currentThread().getName(), item);
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.printf("[%s] interrupted%n", Thread.currentThread().getName());
        }
    }
}
