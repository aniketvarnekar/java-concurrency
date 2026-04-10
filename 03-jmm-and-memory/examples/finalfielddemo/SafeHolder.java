/*
 * SafeHolder — an immutable value object whose fields are safely published via
 * the final-field guarantee.
 *
 * The JMM guarantees that any thread which reads a non-null reference to a
 * SafeHolder will also see the fully initialized values of x, y, and label,
 * without any additional synchronization. The JVM inserts a StoreStore barrier
 * (the "freeze" action) at the end of the constructor to enforce this ordering.
 */
package examples.finalfielddemo;

final class SafeHolder {

    final int x;
    final int y;
    final String label;

    SafeHolder(int x, int y, String label) {
        this.x = x;
        this.y = y;
        this.label = label;
        // JVM inserts StoreStore barrier here (the "freeze" action).
        // Writes to x, y, label are committed to memory before this
        // constructor returns and the reference becomes shareable.
    }

    @Override
    public String toString() {
        return "SafeHolder{x=" + x + ", y=" + y + ", label=" + label + "}";
    }
}
