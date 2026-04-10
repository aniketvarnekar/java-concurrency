/*
 * UnsafeCounter — Runnable that increments a shared counter without synchronization.
 *
 * count[0]++ compiles to three separate JVM operations: load the value, add 1,
 * store the result. A context switch between any two of them lets another thread
 * overwrite the intermediate result, producing a lost update.
 */
package examples.raceconditiondemo;

class UnsafeCounter implements Runnable {

    // Single-element array used as a mutable shared container.
    // Both threads access the same count[0] location with no coordination.
    final int[] count;
    final int increments;

    UnsafeCounter(int[] count, int increments) {
        this.count = count;
        this.increments = increments;
    }

    @Override
    public void run() {
        for (int i = 0; i < increments; i++) {
            count[0]++; // NOT atomic: load, add 1, store
        }
    }
}
