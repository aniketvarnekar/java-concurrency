/*
 * CopyOnWriteDemo — Main
 *
 * Demonstrates two contrasting behaviors:
 *
 * Part 1 — CopyOnWriteArrayList: readers iterate a snapshot taken at the moment
 * their for-each loop began. The writer adds elements concurrently but those additions
 * are only visible to iterations that start AFTER the add completes. No
 * ConcurrentModificationException is thrown regardless of how writes and reads interleave.
 *
 * Part 2 — ArrayList without full-iteration synchronization: the reader holds no lock
 * across the full iteration while the writer holds the lock only during individual add()
 * calls. The ArrayList's internal modCount check detects the concurrent modification and
 * throws ConcurrentModificationException.
 */
package examples.copyonwritedemo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        demonstrateCopyOnWrite();
        demonstrateUnsafeIteration();
    }

    // -------------------------------------------------------------------------
    // Part 1: CopyOnWriteArrayList — snapshot-based iteration is always safe
    // -------------------------------------------------------------------------

    private static void demonstrateCopyOnWrite() throws InterruptedException {
        CopyOnWriteArrayList<String> list =
                new CopyOnWriteArrayList<>(Arrays.asList("A", "B", "C"));

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(4); // 3 readers + 1 writer

        for (int r = 1; r <= 3; r++) {
            final int readerId = r;
            new Thread(() -> {
                try {
                    startGate.await();
                    for (int iter = 1; iter <= 5; iter++) {
                        // The iterator captures the current backing array as a snapshot.
                        // Writes after this point do not affect this iteration.
                        StringBuilder sb = new StringBuilder();
                        for (String elem : list) {
                            sb.append(elem).append(" ");
                            Thread.sleep(10); // widen the window for concurrent writes
                        }
                        System.out.printf("[%s] iteration %d snapshot: [%s]%n",
                                Thread.currentThread().getName(), iter,
                                sb.toString().trim());
                        Thread.sleep(50);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "reader-" + readerId).start();
        }

        new Thread(() -> {
            try {
                startGate.await();
                for (String elem : new String[]{"D", "E", "F", "G", "H", "I"}) {
                    Thread.sleep(150);
                    list.add(elem);
                    // Each add() atomically replaces the backing array with a new copy.
                    System.out.printf("[%s] added '%s' — list size now %d%n",
                            Thread.currentThread().getName(), elem, list.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        }, "writer-1").start();

        startGate.countDown();
        doneLatch.await();

        System.out.println("final list: " + list);
        System.out.println("no ConcurrentModificationException thrown");
    }

    // -------------------------------------------------------------------------
    // Part 2: ArrayList — synchronizing only individual writes, not full iteration
    // -------------------------------------------------------------------------

    private static void demonstrateUnsafeIteration() throws InterruptedException {
        List<String> unsafeList = new ArrayList<>(Arrays.asList("A", "B", "C"));

        // Writer synchronizes on the list for each individual add() — but not across
        // the full write-then-wait cycle, so the lock is released between add() calls.
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    Thread.sleep(20);
                    synchronized (unsafeList) {
                        unsafeList.add("X" + i);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "unsafe-writer");

        // Reader never holds the lock across the full iteration — the modCount check
        // inside ArrayList's iterator detects writes that happened mid-iteration.
        Thread reader = new Thread(() -> {
            try {
                for (int iter = 0; iter < 20; iter++) {
                    Thread.sleep(15);
                    Iterator<String> it = unsafeList.iterator();
                    while (it.hasNext()) {
                        it.next(); // may throw CME if writer added an element
                        Thread.sleep(5);
                    }
                }
            } catch (java.util.ConcurrentModificationException e) {
                System.out.println("[unsafe-reader] caught ConcurrentModificationException");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "unsafe-reader");

        writer.start();
        reader.start();
        writer.join();
        reader.join();
    }
}
