/*
 * Factorial — demonstrates reentrancy via a recursive synchronized method.
 *
 * Each recursive call to compute() attempts to acquire the monitor on `this`.
 * Without reentrancy, the second acquisition would block waiting for the
 * first to release, and the thread would deadlock with itself. Java monitors
 * maintain a per-thread hold count: each synchronized entry increments it,
 * each exit decrements it, and the lock is truly released only when the count
 * reaches zero.
 */
package examples.synchronizeddemo;

class Factorial {

    public synchronized long compute(int n) {
        if (n <= 1) return 1;
        return n * compute(n - 1); // recursive re-acquisition of the same monitor
    }
}
