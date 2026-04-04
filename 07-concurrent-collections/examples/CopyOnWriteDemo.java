/**
 * Demonstrates CopyOnWriteArrayList: safe iteration during concurrent modification.
 *
 * Run: javac CopyOnWriteDemo.java && java CopyOnWriteDemo
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

public class CopyOnWriteDemo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== CopyOnWriteArrayList Demo ===");
        System.out.println();
        partOne();
        System.out.println();
        partTwo();
    }

    // -----------------------------------------------------------------------
    // Part 1: CopyOnWriteArrayList — readers iterate safely while writer adds
    // -----------------------------------------------------------------------
    private static void partOne() throws InterruptedException {
        System.out.println("--- Part 1: CopyOnWriteArrayList (no ConcurrentModificationException) ---");

        CopyOnWriteArrayList<String> list =
            new CopyOnWriteArrayList<>(Arrays.asList("A", "B", "C"));

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(4); // 3 readers + 1 writer

        // 3 reader threads: iterate the list 5 times with 50ms between iterations
        for (int r = 1; r <= 3; r++) {
            final int readerId = r;
            Thread reader = new Thread(() -> {
                String name = Thread.currentThread().getName();
                try {
                    startGate.await();
                    for (int iter = 1; iter <= 5; iter++) {
                        // Capture the snapshot-based iterator
                        StringBuilder sb = new StringBuilder();
                        for (String elem : list) {
                            sb.append(elem).append(" ");
                            // Simulate slow iteration to widen the window for writes
                            Thread.sleep(10);
                        }
                        System.out.printf("[%s] iteration %d snapshot: [%s]%n",
                            name, iter, sb.toString().trim());
                        Thread.sleep(50);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "reader-" + readerId);
            reader.setDaemon(false);
            reader.start();
        }

        // 1 writer thread: adds elements every 150ms for ~1 second (6 additions)
        Thread writer = new Thread(() -> {
            String name = Thread.currentThread().getName();
            try {
                startGate.await();
                String[] newElements = {"D", "E", "F", "G", "H", "I"};
                for (String elem : newElements) {
                    Thread.sleep(150);
                    list.add(elem);
                    System.out.printf("[%s] added '%s' — list size now %d%n",
                        name, elem, list.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        }, "writer-1");
        writer.start();

        // Release all threads simultaneously
        startGate.countDown();
        doneLatch.await();

        System.out.println("Final list: " + list);
        System.out.println("No ConcurrentModificationException was thrown.");
        System.out.println();
        System.out.println("Key observation: each reader iterates a snapshot taken at");
        System.out.println("the moment its for-each loop began. Writes are not visible");
        System.out.println("mid-iteration — only in subsequent iterations that start");
        System.out.println("after the write has completed.");
    }

    // -----------------------------------------------------------------------
    // Part 2: ArrayList + synchronized block still throws
    //         ConcurrentModificationException if a write occurs during iteration
    //         without holding the lock across the entire iteration.
    //
    // This section is intentionally left as a demonstration. The synchronized
    // block wraps the modification but NOT the iteration in the reader threads,
    // which mimics the common mistake of only synchronizing individual operations
    // rather than the full iteration.
    // -----------------------------------------------------------------------
    private static void partTwo() {
        System.out.println("--- Part 2: ArrayList without full iteration locking ---");
        System.out.println("(Demonstrating ConcurrentModificationException)");

        List<String> unsafeList = new ArrayList<>(Arrays.asList("A", "B", "C"));

        Thread writerThread = new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    Thread.sleep(20);
                    // Synchronizing on the list for the add() call alone is not enough
                    // if the reader does not synchronize across the full iteration.
                    synchronized (unsafeList) {
                        unsafeList.add("X" + i);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "unsafe-writer");

        Thread readerThread = new Thread(() -> {
            try {
                for (int iter = 0; iter < 20; iter++) {
                    Thread.sleep(15);
                    // NOT synchronizing across the full iteration — this is the bug.
                    // The iterator's modCount check will detect the concurrent modification.
                    Iterator<String> it = unsafeList.iterator();
                    while (it.hasNext()) {
                        String elem = it.next(); // may throw CME
                        // simulate slow processing
                        Thread.sleep(5);
                    }
                }
            } catch (java.util.ConcurrentModificationException e) {
                System.out.println("[unsafe-reader] caught ConcurrentModificationException: "
                    + e.getClass().getSimpleName());
                System.out.println("  This is the hazard CopyOnWriteArrayList eliminates.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "unsafe-reader");

        writerThread.start();
        readerThread.start();

        try {
            writerThread.join();
            readerThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println();
        System.out.println("Summary:");
        System.out.println("  CopyOnWriteArrayList: readers iterate a stable snapshot.");
        System.out.println("  ArrayList (unsynchronized iteration): ConcurrentModificationException.");
        System.out.println("  Trade-off: CopyOnWriteArrayList writes are O(n) due to full copy.");
        System.out.println("  Best suited for read-heavy, write-rare workloads (e.g., listener lists).");
    }
}
