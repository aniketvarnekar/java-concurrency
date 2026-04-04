/**
 * Demonstrates the producer-consumer pattern with ArrayBlockingQueue and poison pill shutdown.
 *
 * Run: javac ProducerConsumerDemo.java && java ProducerConsumerDemo
 */
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class ProducerConsumerDemo {

    static final String POISON = "POISON";
    static final int QUEUE_CAPACITY = 10;
    static final long START_TIME = System.currentTimeMillis();

    static long elapsed() {
        return System.currentTimeMillis() - START_TIME;
    }

    // ---------------------------------------------------------------
    // Producer: generates items and places them on the queue
    // ---------------------------------------------------------------
    static class Producer implements Runnable {
        private final int producerNum;
        private final BlockingQueue<String> queue;
        private final int itemCount;

        Producer(int producerNum, BlockingQueue<String> queue, int itemCount) {
            this.producerNum = producerNum;
            this.queue = queue;
            this.itemCount = itemCount;
        }

        @Override
        public void run() {
            try {
                for (int m = 1; m <= itemCount; m++) {
                    String item = "P" + producerNum + "-item-" + m;
                    queue.put(item); // blocks if queue is full
                    System.out.printf("[Producer-%d] produced: %s at %d ms%n",
                            producerNum, item, elapsed());
                    Thread.sleep(80);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.printf("[Producer-%d] interrupted%n", producerNum);
            }
            System.out.printf("[Producer-%d] finished producing%n", producerNum);
        }
    }

    // ---------------------------------------------------------------
    // Consumer: takes items from the queue; stops on poison pill
    // ---------------------------------------------------------------
    static class Consumer implements Runnable {
        private final int consumerNum;
        private final BlockingQueue<String> queue;

        Consumer(int consumerNum, BlockingQueue<String> queue) {
            this.consumerNum = consumerNum;
            this.queue = queue;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    String item = queue.take(); // blocks if queue is empty

                    if (POISON.equals(item)) {
                        System.out.printf("[Consumer-%d] received shutdown signal%n",
                                consumerNum);
                        break;
                    }

                    System.out.printf("[Consumer-%d] consumed: %s at %d ms%n",
                            consumerNum, item, elapsed());
                    Thread.sleep(200); // simulate processing time
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.printf("[Consumer-%d] interrupted%n", consumerNum);
            }
            System.out.printf("[Consumer-%d] shut down%n", consumerNum);
        }
    }

    // ---------------------------------------------------------------
    // Main
    // ---------------------------------------------------------------
    public static void main(String[] args) throws InterruptedException {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        int numProducers = 2;
        int numConsumers = 3;
        int itemsPerProducer = 10;

        List<Thread> producers = new ArrayList<>();
        List<Thread> consumers = new ArrayList<>();

        // Start consumers
        for (int c = 1; c <= numConsumers; c++) {
            Thread t = new Thread(new Consumer(c, queue), "Consumer-" + c);
            consumers.add(t);
            t.start();
        }

        // Start producers
        for (int p = 1; p <= numProducers; p++) {
            Thread t = new Thread(new Producer(p, queue, itemsPerProducer), "Producer-" + p);
            producers.add(t);
            t.start();
        }

        // Wait for all producers to finish
        for (Thread p : producers) {
            p.join();
        }
        System.out.println("\nAll producers done. Inserting " + numConsumers + " poison pills...");

        // Insert one poison pill per consumer
        for (int c = 0; c < numConsumers; c++) {
            queue.put(POISON);
        }

        // Wait for all consumers to shut down
        for (Thread c : consumers) {
            c.join();
        }

        System.out.println("\nAll consumers shut down. Total time: " + elapsed() + " ms");
    }
}
