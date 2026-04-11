/*
 * ProducerConsumerDemo — Main
 *
 * Demonstrates the producer-consumer pattern using ArrayBlockingQueue and
 * the poison pill shutdown idiom:
 *   - 2 producer threads, each producing ITEMS_PER_PRODUCER items.
 *   - 3 consumer threads, each consuming until it receives a poison pill.
 *   - Bounded queue (capacity 10) limits how far producers can outpace consumers.
 *   - Main inserts one POISON sentinel per consumer after all producers finish.
 *
 * The elapsed() timestamps show interleaving: consumers process while producers
 * are still generating, demonstrating concurrent pipeline execution.
 */
package examples.producerconsumerdemo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class Main {

    static final long START_TIME = System.currentTimeMillis();

    static long elapsed() {
        return System.currentTimeMillis() - START_TIME;
    }

    private static final int QUEUE_CAPACITY    = 10;
    private static final int NUM_PRODUCERS     = 2;
    private static final int NUM_CONSUMERS     = 3;
    private static final int ITEMS_PER_PRODUCER = 10;

    public static void main(String[] args) throws InterruptedException {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        List<Thread> producers = new ArrayList<>();
        List<Thread> consumers = new ArrayList<>();

        // Start consumers first so they are ready to drain immediately
        for (int c = 1; c <= NUM_CONSUMERS; c++) {
            Thread t = new Thread(new Consumer(c, queue), "consumer-" + c);
            consumers.add(t);
            t.start();
        }

        for (int p = 1; p <= NUM_PRODUCERS; p++) {
            Thread t = new Thread(
                    new Producer(p, queue, ITEMS_PER_PRODUCER), "producer-" + p);
            producers.add(t);
            t.start();
        }

        for (Thread p : producers) p.join();

        // One poison pill per consumer — each consumer exits after receiving it
        System.out.printf("%n[main] all producers done — inserting %d poison pills%n",
                NUM_CONSUMERS);
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            queue.put(Consumer.POISON);
        }

        for (Thread c : consumers) c.join();
    }
}
