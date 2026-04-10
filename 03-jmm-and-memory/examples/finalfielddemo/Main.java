/*
 * FinalFieldDemo — Main
 *
 * Demonstrates safe publication via final fields by contrasting SafeHolder
 * (all fields final) with UnsafeHolder (plain fields).
 *
 * The key difference is the "freeze" action the JVM inserts at the end of a
 * constructor that writes final fields. This StoreStore barrier ensures all
 * final field writes are committed to memory before the constructor returns,
 * so any thread that obtains the reference is guaranteed to see fully
 * initialized values — without any additional synchronization on the fields.
 *
 * UnsafeHolder has no freeze action. Without synchronization on the
 * publication path, the JIT or CPU may reorder the field writes past the
 * reference publication. A reader may see x=0, y=0, or label=null.
 */
package examples.finalfielddemo;

import java.util.concurrent.CountDownLatch;

public class Main {

    // volatile reference: the volatile write establishes happens-before with
    // any subsequent volatile read of the same field.  SafeHolder's final
    // fields carry an additional freeze-action guarantee independent of that.
    static volatile SafeHolder safeRef = null;

    // Plain reference: no happens-before is established between the writes
    // inside the constructor and a reader's subsequent field accesses.
    // On ARM or with aggressive JIT optimization, x, y, or label may appear
    // as their default values (0, 0, null) even after the reference is non-null.
    static UnsafeHolder unsafeRef = null;

    public static void main(String[] args) throws InterruptedException {
        CountDownLatch published = new CountDownLatch(1);

        Thread publisher = new Thread(() -> {
            safeRef   = new SafeHolder(42, 99, "alpha");   // volatile write; freeze committed
            unsafeRef = new UnsafeHolder(42, 99, "alpha"); // plain write; no freeze
            published.countDown();
        }, "publisher");

        Thread reader = new Thread(() -> {
            try {
                published.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // The volatile read of safeRef happens-before the publisher's volatile
            // write. The final-field freeze additionally ensures x, y, and label
            // are the values assigned in the constructor — never 0 or null.
            SafeHolder safe = safeRef;
            System.out.println("[reader] safe   x=" + safe.x + " y=" + safe.y + " label=" + safe.label);

            // unsafeRef is read through a plain field. The latch happens-before
            // incidentally makes it visible in this demo, but any code that
            // publishes an UnsafeHolder without synchronization risks seeing
            // partially initialized fields.
            UnsafeHolder unsafe = unsafeRef;
            System.out.println("[reader] unsafe x=" + unsafe.x + " y=" + unsafe.y + " label=" + unsafe.label);
        }, "reader");

        publisher.start();
        reader.start();
        publisher.join();
        reader.join();
    }
}
