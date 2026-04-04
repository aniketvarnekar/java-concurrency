/**
 * FinalFieldDemo — demonstrates safe publication via final fields.
 *
 * What it demonstrates:
 *   An object with final fields is safely published to another thread via a
 *   volatile reference. The final-field JMM guarantee ensures the reading
 *   thread sees fully initialized values without any additional locking.
 *   A contrasting UnsafeHolder with non-final fields published without
 *   synchronization shows where the guarantee breaks down — the unsafe version
 *   is formally a data race, though it may appear to work on x86 hardware.
 *
 * Run command:
 *   javac FinalFieldDemo.java && java FinalFieldDemo
 *
 * Key JMM rule illustrated:
 *   After a constructor that writes final fields completes, the JVM inserts a
 *   StoreStore barrier ("freeze" action) before returning. This prevents the
 *   JIT or CPU from reordering the writes to final fields so that they appear
 *   to occur after the reference becomes visible to other threads.
 */
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FinalFieldDemo {

    // -----------------------------------------------------------------------
    // SafeHolder: all fields are final.
    // The JMM guarantees that any thread reading a non-null safeRef will
    // see x and y with their constructor-assigned values.
    // -----------------------------------------------------------------------
    static final class SafeHolder {
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

    // -----------------------------------------------------------------------
    // UnsafeHolder: fields are NOT final.
    // Without synchronization on the publishing path, writes to x, y, label
    // may be reordered past the publication of the reference. A reading thread
    // may observe x=0, y=0, or label=null.
    // -----------------------------------------------------------------------
    static final class UnsafeHolder {
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

    // Published via volatile: reading the reference is a volatile read, which
    // establishes happens-before with the volatile write. This makes the
    // unsafe holder "accidentally" safe in this specific setup — to see the
    // unsafe version in its natural habitat, you would need to use a plain
    // (non-volatile) field for the reference. See the comments below.
    static volatile SafeHolder   safeRef   = null;
    static volatile UnsafeHolder unsafeRef = null;

    // Plain (non-volatile) reference: this is where UnsafeHolder is truly unsafe.
    // A reading thread that sees plainUnsafeRef != null is not guaranteed to see
    // the constructed values of x, y, label.
    static UnsafeHolder plainUnsafeRef = null;

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== FinalFieldDemo ===");
        System.out.println();

        demonstrateSafePublication();
        demonstrateUnsafePublicationPlainRef();
        demonstrateSafePublicationManyReaders();
    }

    // -----------------------------------------------------------------------
    // Demo 1: Safe publication — final fields + volatile reference
    // -----------------------------------------------------------------------

    static void demonstrateSafePublication() throws InterruptedException {
        System.out.println("--- Demo 1: Safe publication via final fields ---");
        safeRef = null;

        CountDownLatch readyToRead = new CountDownLatch(1);
        CountDownLatch readerDone  = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            try {
                readyToRead.await(); // wait for publisher to signal
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // By the time we read safeRef, the volatile read establishes
            // happens-before with the volatile write in the publisher.
            // Additionally, the final-field freeze ensures x, y, label are visible.
            SafeHolder h = safeRef;
            if (h == null) {
                System.out.println("[safe-reader] safeRef is null — unexpected");
            } else {
                System.out.println("[safe-reader] read: " + h);
                System.out.println("[safe-reader] x=" + h.x + " (expected 42)");
                System.out.println("[safe-reader] y=" + h.y + " (expected 99)");
                System.out.println("[safe-reader] label=" + h.label + " (expected 'alpha')");
            }
            readerDone.countDown();
        }, "safe-reader");

        Thread publisher = new Thread(() -> {
            // Construct and publish. The final freeze action in SafeHolder's
            // constructor ensures x, y, label are committed before returning.
            safeRef = new SafeHolder(42, 99, "alpha"); // volatile write
            System.out.println("[safe-publisher] published SafeHolder");
            readyToRead.countDown();
        }, "safe-publisher");

        reader.start();
        publisher.start();

        readerDone.await();
        reader.join();
        publisher.join();
        System.out.println();
    }

    // -----------------------------------------------------------------------
    // Demo 2: Unsafe publication — non-final fields + plain (non-volatile) ref
    // This demonstrates the risk. On x86 hardware, the bug rarely manifests
    // because x86's TSO model is strong, but the JMM does not guarantee safety.
    // -----------------------------------------------------------------------

    static void demonstrateUnsafePublicationPlainRef() throws InterruptedException {
        System.out.println("--- Demo 2: Unsafe publication (plain ref, non-final fields) ---");
        System.out.println("[main] NOTE: On x86 hardware this may appear correct,");
        System.out.println("[main] but it is formally a data race under the JMM.");
        System.out.println("[main] On ARM or with aggressive JIT optimization, it can fail.");

        AtomicInteger corruptedCount = new AtomicInteger(0);
        int iterations = 10_000;
        CountDownLatch done = new CountDownLatch(iterations);

        ExecutorService pool = Executors.newFixedThreadPool(4);

        for (int i = 0; i < iterations; i++) {
            final int iter = i;
            pool.submit(() -> {
                // Publisher: write to a plain (non-volatile) reference
                plainUnsafeRef = new UnsafeHolder(iter + 1, iter + 2, "iter-" + iter);

                // Immediately read it back from a different context.
                // A different CPU core may not yet see the updated field values.
                UnsafeHolder h = plainUnsafeRef;
                if (h != null && (h.x == 0 || h.y == 0 || h.label == null)) {
                    // Observed a partially constructed object
                    corruptedCount.incrementAndGet();
                }
                done.countDown();
            });
        }

        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.println("[main] Iterations: " + iterations);
        System.out.println("[main] Observed partially constructed objects: " + corruptedCount.get());
        System.out.println("[main] (Any non-zero count confirms the unsafe publication bug)");
        System.out.println();
    }

    // -----------------------------------------------------------------------
    // Demo 3: Safe publication with many readers — shows final fields
    // guarantee holds even with concurrent readers
    // -----------------------------------------------------------------------

    static void demonstrateSafePublicationManyReaders() throws InterruptedException {
        System.out.println("--- Demo 3: Safe publication with 8 concurrent readers ---");
        safeRef = null;

        int readerCount = 8;
        CountDownLatch publishedLatch = new CountDownLatch(1);
        CountDownLatch allDone        = new CountDownLatch(readerCount);
        AtomicInteger correctReads    = new AtomicInteger(0);

        // Start all readers; they wait for publication
        for (int i = 0; i < readerCount; i++) {
            Thread reader = new Thread(() -> {
                try {
                    publishedLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                SafeHolder h = safeRef;
                if (h != null && h.x == 7 && h.y == 13 && "beta".equals(h.label)) {
                    correctReads.incrementAndGet();
                }
                allDone.countDown();
            }, "reader-" + i);
            reader.start();
        }

        // Publisher constructs and signals all readers simultaneously
        Thread publisher = new Thread(() -> {
            safeRef = new SafeHolder(7, 13, "beta");
            System.out.println("[batch-publisher] published SafeHolder{x=7, y=13, label=beta}");
            publishedLatch.countDown();
        }, "batch-publisher");

        publisher.start();
        allDone.await();
        publisher.join();

        System.out.println("[main] Readers that saw correct values: "
            + correctReads.get() + "/" + readerCount);
        System.out.println("[main] (Should be " + readerCount + "/" + readerCount + ")");
        System.out.println();
        System.out.println("=== Demo complete ===");
    }
}
