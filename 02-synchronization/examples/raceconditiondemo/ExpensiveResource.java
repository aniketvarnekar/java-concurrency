/*
 * ExpensiveResource — tracks how many times it has been constructed across all threads.
 *
 * The sleep in the constructor widens the check-then-act race window: both
 * checker threads can observe sharedResource == null, pass the null check,
 * and each call new ExpensiveResource(). When constructionCount > 1 after a
 * trial, the race manifested and the singleton guarantee was violated.
 */
package examples.raceconditiondemo;

import java.util.concurrent.atomic.AtomicInteger;

class ExpensiveResource {

    // AtomicInteger ensures the count itself is updated safely even when
    // ExpensiveResource is being constructed concurrently by two threads.
    private static final AtomicInteger constructionCount = new AtomicInteger(0);

    final int instanceNumber;

    ExpensiveResource() {
        this.instanceNumber = constructionCount.incrementAndGet();
        // Slow construction widens the gap between the null-check and the assignment,
        // making it likely both threads observe null before either writes.
        try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static int getConstructionCount() { return constructionCount.get(); }

    static void resetConstructionCount() { constructionCount.set(0); }
}
