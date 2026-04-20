/*
 * ConditionDemo — Main
 *
 * Two demonstrations of Condition variable semantics using BoundedBuffer:
 *
 *   1. Producer-consumer with two Conditions: three producers and two consumers
 *      share a buffer of capacity 4. Producers block on notFull when the buffer
 *      is full; consumers block on notEmpty when the buffer is empty. Each side
 *      signals only the opposite Condition, so no unnecessary wakeups occur.
 *
 *   2. Timed await: a single consumer calls await(timeout) on an empty buffer.
 *      No producer ever signals, so the await times out and returns false,
 *      demonstrating how callers can react to a missed signal or slow producer.
 */
package examples.conditiondemo;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        demonstrateProducerConsumer();
        demonstrateTimedAwait();
    }

    // -------------------------------------------------------------------------
    // Demo 1: producer-consumer with two Conditions
    // -------------------------------------------------------------------------

    static void demonstrateProducerConsumer() throws InterruptedException {
        BoundedBuffer<Integer> buffer = new BoundedBuffer<>(4);

        // Three producers each publish 5 items — 15 items total.
        Thread[] producers = new Thread[3];
        for (int i = 0; i < producers.length; i++) {
            final int producerId = i;
            producers[i] = new Thread(() -> {
                for (int item = 0; item < 5; item++) {
                    try {
                        int value = producerId * 10 + item;
                        buffer.put(value);
                        System.out.printf("[producer-%d] put %2d  (buffer size: %d/%d)%n",
                                producerId, value, buffer.size(), buffer.capacity());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "producer-" + i);
        }

        // Two consumers each take items until they have consumed 7 and 8 items
        // respectively, covering all 15 produced items between them.
        int[] consumeCounts = {8, 7};
        Thread[] consumers = new Thread[2];
        for (int i = 0; i < consumers.length; i++) {
            final int consumerId = i;
            final int toConsume = consumeCounts[i];
            consumers[i] = new Thread(() -> {
                for (int n = 0; n < toConsume; n++) {
                    try {
                        int value = buffer.take();
                        System.out.printf("[consumer-%d] took %2d  (buffer size: %d/%d)%n",
                                consumerId, value, buffer.size(), buffer.capacity());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "consumer-" + i);
        }

        for (Thread t : producers) t.start();
        for (Thread t : consumers) t.start();
        for (Thread t : producers) t.join();
        for (Thread t : consumers) t.join();

        System.out.printf("producer-consumer complete — buffer empty: %b%n%n", buffer.size() == 0);
    }

    // -------------------------------------------------------------------------
    // Demo 2: timed await
    // -------------------------------------------------------------------------

    static void demonstrateTimedAwait() throws InterruptedException {
        // A standalone lock and Condition — no BoundedBuffer wrapper needed here
        // because the point is to show the await(time, unit) API directly.
        ReentrantLock lock = new ReentrantLock();
        Condition available = lock.newCondition();

        Thread consumer = new Thread(() -> {
            lock.lock();
            try {
                System.out.println("[timed-consumer] waiting up to 300 ms for signal...");

                // await returns false when the timeout expires without a signal.
                boolean signalled = available.await(300, TimeUnit.MILLISECONDS);

                if (signalled) {
                    System.out.println("[timed-consumer] woke up via signal — item is ready");
                } else {
                    // No producer signalled within the window. The thread can
                    // decide to retry, log a warning, or return an empty result.
                    System.out.println("[timed-consumer] timed out — no item arrived within 300 ms");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "timed-consumer");

        consumer.start();
        consumer.join();
    }
}
