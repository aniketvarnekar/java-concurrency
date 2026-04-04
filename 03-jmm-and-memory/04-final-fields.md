# Final Fields and Safe Publication

## Overview

An object is said to be safely published when both its reference and the state of its fields are consistently visible to any thread that obtains the reference. Publication sounds simple — just assign the reference to a shared variable — but without explicit synchronization, a writing thread's stores to the object's fields may be reordered past the store of the reference itself. A reading thread can then obtain the reference and see the object in a partially initialized state.

The JMM gives `final` fields a special treatment that eliminates this problem for immutable state. After a constructor that writes `final` fields completes, the JMM inserts a "freeze" action for every `final` field written during construction. This freeze creates a happens-before edge to every subsequent read of those fields by any thread that obtains the object's reference through any path other than a data race on the reference itself. In practical terms: if an object's fields are all `final` and the constructor does not allow `this` to escape, any thread that can read the object's reference is guaranteed to see fully initialized values for those fields without any additional synchronization.

This guarantee is powerful enough to make immutable objects inherently thread-safe for publication. A class like `String`, `Integer`, or any well-designed value type whose fields are all `final` can be freely shared across threads by simply making its reference reachable — no `volatile`, no `synchronized`, no atomic reference required for the publication itself (though the reference must still be published safely for mutable objects or non-final fields).

The caveat, and the source of some of the most confusing bugs in concurrent Java, is that these guarantees hinge on the constructor running to completion without `this` escaping. Any pattern that allows another thread to observe the object before the constructor returns voids the JMM's final-field guarantees and leaves the object in an indeterminate state.

## Key Concepts

### Safe Publication

Safe publication means that when one thread makes an object's reference visible to another thread, the other thread sees a fully constructed, consistent object. There are several mechanisms for safe publication:

1. Storing the reference in a `static` field initialized by the class initializer (class loading is thread-safe).
2. Storing the reference in a `volatile` field.
3. Storing the reference in a field guarded by a lock and reading it under the same lock.
4. Using an `AtomicReference` or a concurrent collection.
5. Constructing an object with only `final` fields and publishing the reference through any of the above, or even through a plain field — the `final` freeze action covers the publication.

Option 5 is unique. For all-`final` objects, the reference can be published without `volatile` because the freeze action that follows construction establishes a happens-before relationship between the constructor writes and any subsequent read of a `final` field, regardless of how the reference was shared. This is the only case where a non-volatile, non-synchronized publication of a reference does not require additional synchronization for field visibility.

```
Thread 1 (constructor)                Thread 2 (reader)
------------------------------        --------------------------------
allocate object
write final field x = 10     ---\
write final field y = 20     ----> [freeze action]
return from constructor           |
publish reference via field       |
                                  |
                                  \--> read reference
                                       read x  (sees 10 — guaranteed)
                                       read y  (sees 20 — guaranteed)
```

### Final Field Write Freeze

The JMM defines a "freeze" action that occurs at the end of a constructor for each `final` field written during that constructor's execution. The freeze is a happens-before edge: every write to a `final` field in the constructor happens-before the freeze, and the freeze happens-before any read of those `final` fields by a thread that obtained the reference after construction.

Mechanically, the JVM implements the freeze by inserting a StoreStore barrier before the constructor returns. This barrier prevents the JIT or CPU from reordering the writes to `final` fields so that they appear to occur after the constructor exits. Without this barrier, the store of the object's reference could be observed by another thread before the stores to the object's fields had propagated — producing the partially initialized object scenario.

### Prohibition on Reordering

The JMM explicitly prohibits the JVM from reordering writes to `final` fields so that they appear to happen after the constructor returns, or from reordering the initial read of a `final` field from a given reference so that it appears to happen before the constructor completed. These are the two ordering guarantees that make final fields special compared to ordinary fields.

This prohibition is specific to the constructor. Once an object is constructed, the `final` guarantee only covers the values written during construction. If a `final` field points to a mutable object, the contents of that mutable object are not covered — only the reference stored in the `final` field is guaranteed to be visible.

```java
public final class Config {
    final int maxRetries;         // guaranteed visible after construction
    final List<String> hosts;     // the reference is guaranteed visible;
                                  // the List contents are NOT — they are mutable

    public Config(int maxRetries, List<String> hosts) {
        this.maxRetries = maxRetries;
        this.hosts = Collections.unmodifiableList(new ArrayList<>(hosts));
        // Copying and wrapping in unmodifiableList makes the contents effectively
        // immutable, which is the correct pattern.
    }
}
```

### This Escape

A constructor allows `this` to escape if it makes the partially constructed object reachable by another thread before the constructor returns. The most common forms of this-escape are:

- Registering `this` as a listener or callback in the constructor body.
- Starting a thread in the constructor and passing `this` to it.
- Assigning `this` to a public static field from within the constructor.
- Calling a non-private, overridable method from the constructor that passes `this` to external code.

When `this` escapes, the JMM's final-field freeze guarantee is voided. Another thread can observe the object through the escaped reference before the constructor has finished, seeing default values (0, null, false) for fields that the constructor had not yet written, even if those fields are `final`.

```java
// BROKEN — this escapes before constructor completes
public class EventListener {
    private final int id;
    private static EventListener lastCreated;

    public EventListener(int id) {
        lastCreated = this;   // this escapes — final field id not yet written
        this.id = id;
    }
}
```

A thread reading `lastCreated.id` after seeing the assignment could observe `id == 0` even though it is `final`, because the store of `lastCreated = this` may have been observed before the store of `this.id = id`.

### Immutable Objects

An object is immutable if all of its fields are `final` (or effectively final — never written after construction) and none of the objects reachable through those fields are mutable. Immutable objects combine two properties that make them inherently thread-safe:

First, they cannot be modified after construction, so there are no write-write or write-read races on their state. Second, because their fields are `final`, the JMM's freeze action ensures that any thread obtaining the reference sees the full, correct initial state.

This means immutable objects can be freely shared without defensive copying, locking, or volatile references. The pattern of making value types immutable — records in Java 16+, or manually by using `final` fields and providing no mutators — is one of the most effective tools for eliminating concurrency bugs.

## Code Snippet

This example shows a `SafeHolder` with `final` fields that is safely published, and contrasts it with an `UnsafeHolder` using non-final fields published without synchronization. Because the timing of unsafely published object visibility is non-deterministic, the unsafe version's bugs may not manifest on every run, but the comments explain the risk precisely.

```java
import java.util.concurrent.CountDownLatch;

public class FinalFieldDemo {

    // SafeHolder: all fields are final.
    // The JMM freeze action after the constructor guarantees that any thread
    // that sees a non-null reference will also see x=10 and y=20.
    static final class SafeHolder {
        final int x;
        final int y;

        SafeHolder(int x, int y) {
            this.x = x;
            this.y = y;
            // JVM inserts StoreStore barrier here before returning,
            // preventing reordering of x/y writes past the reference publication.
        }
    }

    // UnsafeHolder: fields are NOT final.
    // Without synchronization, x and y writes can be reordered past the
    // reference publication. A reading thread may see x=0 or y=0.
    static final class UnsafeHolder {
        int x;
        int y;

        UnsafeHolder(int x, int y) {
            this.x = x;
            this.y = y;
            // No barrier here — writes to x and y may not be visible to
            // threads that read the reference.
        }
    }

    // The reference is published via a plain (non-volatile) field.
    // For SafeHolder this is safe due to final-field freeze.
    // For UnsafeHolder this is a data race.
    static SafeHolder   safeRef   = null;
    static UnsafeHolder unsafeRef = null;

    public static void main(String[] args) throws InterruptedException {
        demonstrateSafePublication();
        demonstrateUnsafePublicationRisk();
    }

    static void demonstrateSafePublication() throws InterruptedException {
        safeRef = null;
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch done      = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            try {
                published.await(); // wait until publisher has set safeRef
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            SafeHolder h = safeRef;
            if (h != null) {
                // Guaranteed by JMM: if h != null, h.x == 10 and h.y == 20.
                System.out.println("[safe-reader] x=" + h.x + " y=" + h.y
                    + " (expected: x=10 y=20)");
            }
            done.countDown();
        }, "safe-reader");

        Thread publisher = new Thread(() -> {
            safeRef = new SafeHolder(10, 20); // construction + freeze + publication
            published.countDown();
        }, "safe-publisher");

        reader.start();
        publisher.start();
        done.await();
        publisher.join();
        System.out.println("[main] safe publication complete");
    }

    static void demonstrateUnsafePublicationRisk() throws InterruptedException {
        unsafeRef = null;
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch done      = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            try {
                published.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            UnsafeHolder h = unsafeRef;
            if (h != null) {
                // NOT guaranteed: on some JVMs/CPUs, h.x or h.y could be 0
                // because the writes to x and y may be reordered past the
                // write to unsafeRef. This is a data race.
                System.out.println("[unsafe-reader] x=" + h.x + " y=" + h.y
                    + " (expected: x=10 y=20, but not guaranteed by JMM)");
            }
            done.countDown();
        }, "unsafe-reader");

        Thread publisher = new Thread(() -> {
            unsafeRef = new UnsafeHolder(10, 20); // no freeze action
            published.countDown();
        }, "unsafe-publisher");

        reader.start();
        publisher.start();
        done.await();
        publisher.join();
        System.out.println("[main] unsafe publication complete (may show wrong values)");
    }
}
```

## Gotchas

### this-Escape Voids the Final Field Guarantee

Registering a listener, starting a thread, or assigning a static variable inside a constructor allows another thread to observe the object before its constructor finishes. When `this` escapes, the JMM freeze action for `final` fields has not yet occurred, so the reading thread is not guaranteed to see the final fields' values. The constructor must run to completion before the reference becomes reachable by any other thread.

### A final Reference to a Mutable Object Is Not Fully Thread-Safe

The `final` guarantee covers only the value of the `final` field itself — the reference. If that reference points to a mutable object (an `ArrayList`, an array, a mutable bean), the contents of that object are not protected. Another thread reading the `final` field will see the correct reference, but concurrent modifications to the referenced object still require synchronization. For true thread safety through publication, the referenced objects must themselves be immutable or separately synchronized.

### Arrays Declared final Have Mutable Contents

A `final int[] data` field guarantees that `data` always refers to the same array, and that the array reference is visible to all threads after construction. The array elements themselves — `data[0]`, `data[1]`, etc. — are not covered by the `final` field guarantee. They require their own synchronization if modified after publication.

### Effectively Final Is Not the Same as final for JMM Purposes

The Java compiler uses the term "effectively final" for local variables that are never assigned after their initial assignment, allowing them to be used in lambdas and anonymous classes. This is a language-level concept for the compiler's type rules, not a JMM concept. "Effectively final" fields do not receive the JMM's freeze action; only fields declared with the `final` keyword do.

### Subclassing Can Expose Partially Constructed Objects

A `final` field guarantee applies to the constructor in which the field is written. In an inheritance hierarchy, the subclass constructor runs after the superclass constructor. If the superclass constructor allows `this` to escape and the subclass relies on `final` fields written in the subclass constructor, those fields are not yet initialized at the time of escape. This is an argument for either keeping constructors minimal or making classes `final` (preventing subclassing).

### Safe Publication Via final Does Not Protect Subsequent Mutations

The `final` field guarantee covers the state established during construction. If an object with `final` fields is mutable (perhaps through methods that modify objects reachable through the final references), those mutations are not covered by the initial safe publication. Every subsequent write to shared mutable state still requires explicit synchronization, regardless of how the owning object was published.
