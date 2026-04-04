/**
 * Demonstrates producer-consumer pattern with ArrayBlockingQueue and poison pill shutdown.
 *
 * Run: javac BlockingQueueProducerConsumer.java && java BlockingQueueProducerConsumer
 */

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;

public class BlockingQueueProducerConsumer {

    private static final String POISON = "POISON";
    private static final int QUEUE_CAPACITY = 5;
    private static final int PRODUCER_COUNT = 2;
    private static final int CONSUMER_COUNT = 3;
    private static final int ITEMS_PER_PRODUCER = 5;

    // Shared queue
    private static final ArrayBlockingQueue<String> queue =
        new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static String now() {
        return LocalTime.now().format(TIME_FMT);
    }

    // -----------------------------------------------------------------------
    // Producer
    // -----------------------------------------------------------------------
    static class Producer implements Runnable {
        private final int id;
        private final CountDownLatch doneLatch;

        Producer(int id, CountDownLatch doneLatch) {
            this.id = id;
            this.doneLatch = doneLatch;
        }

        @Override
        public void run() {
            String name = "Producer-" + id;
            try {
                for (int m = 1; m <= ITEMS_PER_PRODUCER; m++) {
                    String item = "producer-" + id + "-item-" + m;
                    queue.put(item);  // blocks if queue is full
                    System.out.printf("[%s] [%s] produced: %s%n",
                        now(), name, item);
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.printf("[%s] [%s] interrupted%n", now(), name);
            } finally {
                doneLatch.countDown();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Consumer
    // -----------------------------------------------------------------------
    static class Consumer implements Runnable {
        private final int id;

        Consumer(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            String name = "Consumer-" + id;
            try {
                while (true) {
                    String item = queue.take();  // blocks if queue is empty
                    if (POISON.equals(item)) {
                        System.out.printf("[%s] [%s] shutting down%n", now(), name);
                        break;
                    }
                    System.out.printf("[%s] [%s] consumed: %s%n",
                        now(), name, item);
                    Thread.sleep(200);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.printf("[%s] [%s] interrupted%n", now(), name);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== BlockingQueue Producer-Consumer Demo ===");
        System.out.printf("Queue capacity: %d | Producers: %d | Consumers: %d%n",
            QUEUE_CAPACITY, PRODUCER_COUNT, CONSUMER_COUNT);
        System.out.println();

        CountDownLatch producersDone = new CountDownLatch(PRODUCER_COUNT);

        // Start producers
        Thread[] producers = new Thread[PRODUCER_COUNT];
        for (int i = 1; i <= PRODUCER_COUNT; i++) {
            producers[i - 1] = new Thread(new Producer(i, producersDone),
                "producer-thread-" + i);
            producers[i - 1].start();
        }

        // Start consumers
        Thread[] consumers = new Thread[CONSUMER_COUNT];
        for (int i = 1; i <= CONSUMER_COUNT; i++) {
            consumers[i - 1] = new Thread(new Consumer(i),
                "consumer-thread-" + i);
            consumers[i - 1].start();
        }

        // Wait for all producers to finish, then inject one POISON per consumer
        producersDone.await();
        System.out.printf("%n[%s] [Main] all producers done — injecting %d poison pills%n%n",
            now(), CONSUMER_COUNT);
        for (int i = 0; i < CONSUMER_COUNT; i++) {
            queue.put(POISON);
        }

        // Wait for all consumers to finish
        for (Thread c : consumers) {
            c.join();
        }
        for (Thread p : producers) {
            p.join();
        }

        System.out.printf("%n[%s] [Main] all threads finished%n", now());
    }
}
