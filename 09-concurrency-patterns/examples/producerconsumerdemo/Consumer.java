/*
 * Consumer — takes items from the shared BlockingQueue and processes them.
 *
 * take() blocks when the queue is empty — no busy-waiting required.
 * The poison pill pattern is used for shutdown: main inserts one POISON
 * sentinel per consumer after all producers finish, causing each consumer
 * to break out of its loop cleanly.
 */
package examples.producerconsumerdemo;

import java.util.concurrent.BlockingQueue;

class Consumer implements Runnable {

    static final String POISON = "POISON";

    private final int consumerNum;
    private final BlockingQueue<String> queue;

    Consumer(int consumerNum, BlockingQueue<String> queue) {
        this.consumerNum = consumerNum;
        this.queue       = queue;
    }

    @Override
    public void run() {
        try {
            while (true) {
                // Blocks if the queue is empty — thread parks until an item arrives
                String item = queue.take();

                if (POISON.equals(item)) {
                    System.out.printf("[Consumer-%d] received poison pill — shutting down%n",
                            consumerNum);
                    break;
                }

                System.out.printf("[Consumer-%d] consumed: %s at %dms%n",
                        consumerNum, item, Main.elapsed());
                Thread.sleep(200); // simulate processing time
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.printf("[Consumer-%d] interrupted%n", consumerNum);
        }
    }
}
