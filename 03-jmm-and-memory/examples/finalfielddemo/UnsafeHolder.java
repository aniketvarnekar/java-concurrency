/*
 * UnsafeHolder — a value object with non-final fields, published without
 * synchronization on the reference.
 *
 * Without a StoreStore barrier after construction, the JIT or CPU may reorder
 * the writes to x, y, and label so that they appear to occur after the
 * reference is published. A reading thread may observe x=0, y=0, or
 * label=null even after confirming the reference is non-null.
 */
package examples.finalfielddemo;

final class UnsafeHolder {

    int x;
    int y;
    String label;

    UnsafeHolder(int x, int y, String label) {
        this.x = x;
        this.y = y;
        this.label = label;
        // No barrier here. The JIT or CPU may reorder the stores to x, y,
        // and label past the point where the reference is published.
    }

    @Override
    public String toString() {
        return "UnsafeHolder{x=" + x + ", y=" + y + ", label=" + label + "}";
    }
}
