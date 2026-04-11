/*
 * BlockingQueueDemo — Main
 *
 * Demonstrates the producer-consumer pattern with ArrayBlockingQueue:
 *   - 2 producers each produce 5 items with 100ms between puts
 *   - 3 consumers each consume with 200ms between takes
 *   - The queue capacity of 5 limits how far ahead producers can run
 *   - put() blocks producers when the queue is full (backpressure)
 *   - take() blocks consumers when the queue is empty
 *
 * Shutdown uses the poison pill pattern: after all producers finish,
 * one poison pill per consumer is put into the queue. Each consumer stops
 * when it dequeues its pill. Because each pill is consumed by exactly one
 * consumer thread, CONSUMER_COUNT pills are needed.
 */
package examples.blockingqueuedemo;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;

public class Main {

    private static final int QUEUE_CAPACITY = 5;
    private static final int PRODUCER_COUNT = 2;
    private static final int CONSUMER_COUNT = 3;

    public static void main(String[] args) throws InterruptedException {
        ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        CountDownLatch producersDone = new CountDownLatch(PRODUCER_COUNT);

        Thread[] producers = new Thread[PRODUCER_COUNT];
        for (int i = 1; i <= PRODUCER_COUNT; i++) {
            producers[i - 1] = new Thread(
                    new Producer(i, queue, producersDone), "producer-" + i);
            producers[i - 1].start();
        }

        Thread[] consumers = new Thread[CONSUMER_COUNT];
        for (int i = 1; i <= CONSUMER_COUNT; i++) {
            consumers[i - 1] = new Thread(
                    new Consumer(i, queue), "consumer-" + i);
            consumers[i - 1].start();
        }

        // Wait for all producers to finish, then send one pill per consumer.
        // Sending fewer pills would leave some consumers blocked on take() forever.
        producersDone.await();
        System.out.printf("[main] all producers done — sending %d poison pills%n",
                CONSUMER_COUNT);
        for (int i = 0; i < CONSUMER_COUNT; i++) {
            queue.put(Consumer.POISON);
        }

        for (Thread c : consumers) c.join();
        for (Thread p : producers) p.join();
    }
}
