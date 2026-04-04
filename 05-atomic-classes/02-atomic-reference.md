# 02 — AtomicReference

## Overview

`AtomicReference<V>` provides the same compare-and-swap semantics as `AtomicInteger`, but for object references instead of primitive values. This makes it possible to atomically publish a new version of any immutable object — a configuration record, a snapshot of application state, or a node in a linked data structure — without using `synchronized`. Any thread that calls `get()` is guaranteed to see either the old reference or the new reference, never a partially constructed intermediate state.

While `AtomicReference` solves many problems cleanly, reference-based CAS introduces a subtle correctness hazard called the ABA problem. A thread reads a reference (call it A), and later performs a CAS expecting A. If in the meantime another thread changed the reference to B and then back to A, the CAS succeeds even though the state represented by that reference may have changed in a semantically meaningful way. For primitive counters this is often harmless, but for pointer-based data structures it can cause corruption.

`AtomicStampedReference<V>` and `AtomicMarkableReference<V>` address the ABA problem by pairing the reference with a second piece of metadata. `AtomicStampedReference` pairs the reference with an integer stamp (typically a version counter). `AtomicMarkableReference` pairs the reference with a boolean mark. Both require that the CAS check both the reference and the metadata, making it impossible for an ABA transition to go undetected.

These three classes are the foundation from which lock-free data structures — stacks, queues, linked lists — are built. Using them correctly requires careful thinking about what invariants a CAS is protecting, which thread is allowed to succeed, and what retry logic should look like.

## Key Concepts

### AtomicReference

The core operations on `AtomicReference<V>` mirror those on `AtomicInteger`:

```java
AtomicReference<String> ref = new AtomicReference<>("initial");

String current = ref.get();                          // read the current reference
ref.set("updated");                                  // plain volatile write
String old = ref.getAndSet("swapped");               // atomic swap, returns old

// CAS: set to "new-value" only if current value is "swapped"
boolean changed = ref.compareAndSet("swapped", "new-value");

// Functional update: apply a function to the current value, return the new value
String next = ref.updateAndGet(s -> s.toUpperCase());

// accumulateAndGet: combine current reference with a second value using a BinaryOperator
String combined = ref.accumulateAndGet("suffix", (a, b) -> a + "-" + b);
```

The primary use case is publishing immutable objects. Define an immutable value class, hold it in an `AtomicReference`, and perform updates by creating a new instance and CAS-swapping it in. All readers see a consistent snapshot without any locking.

```java
// Immutable config snapshot
record Config(String host, int port) {}

AtomicReference<Config> config = new AtomicReference<>(new Config("localhost", 8080));

// Any thread can update by replacing the whole record
config.set(new Config("prod-host", 443));

// Readers always get a consistent snapshot
Config current = config.get(); // all fields are consistent
```

### The ABA Problem

Consider a lock-free stack backed by a linked list. Thread 1 reads the head pointer (node A). Thread 1 is then preempted. Thread 2 pops A, pops B, then pushes A back. The stack now contains only A. Thread 1 resumes and performs `compareAndSet(A, A.next)`. A.next still points to B, but B has already been removed (and possibly recycled). Thread 1's CAS succeeds, corrupting the stack.

The sequence is:

```
Initial stack:   A -> B -> C
Thread 1 reads:  head = A, prepares to CAS(A, B)

Thread 2 does:   pop A  => stack: B -> C
                 pop B  => stack: C
                 push A => stack: A -> C  (but A.next was not updated!)

Thread 1 CAS(A, A.next):  A.next is still B (stale pointer)
Result:  head = B, but B is no longer in the stack => corruption
```

The CAS in thread 1 succeeded because the reference at head was still A. The CAS had no way to detect that A had been through a complete remove-and-reinsert cycle.

### AtomicStampedReference

`AtomicStampedReference<V>` pairs a reference with an `int` stamp. All four values — old reference, old stamp, new reference, new stamp — must match for a CAS to succeed.

```java
AtomicStampedReference<String> ref =
        new AtomicStampedReference<>("A", 0); // initial value "A", stamp 0

// Read both reference and stamp atomically
int[] stampHolder = new int[1];
String value = ref.getReference();             // just the reference
int   stamp  = ref.getStamp();                 // just the stamp
String valueAndStamp = ref.get(stampHolder);   // reference; stamp written into stampHolder[0]

// CAS: succeed only if both the reference and the stamp match
boolean ok = ref.compareAndSet(
        "A", "B",   // expected reference, new reference
        0, 1        // expected stamp,     new stamp
);
```

A thread that wants to modify the reference increments the stamp. Even if another thread changes the reference from A to B and back to A, the stamp will have advanced (e.g., 0 → 1 → 2), so a CAS expecting stamp 0 will fail.

```java
// ABA scenario with AtomicStampedReference:
// Thread 2: compareAndSet(A, B, 0, 1) -- stamp goes to 1
//           compareAndSet(B, A, 1, 2) -- stamp goes to 2
// Thread 1: compareAndSet(A, X, 0, 1) -- FAILS because current stamp is 2, not 0
```

### AtomicMarkableReference

`AtomicMarkableReference<V>` pairs a reference with a single `boolean` mark. It has lower overhead than `AtomicStampedReference` because it stores only one bit of metadata, but it can only distinguish "marked" from "unmarked" — it cannot track how many transitions have occurred.

```java
AtomicMarkableReference<String> ref =
        new AtomicMarkableReference<>("node", false);

boolean[] markHolder = new boolean[1];
String value = ref.get(markHolder);    // value; mark written into markHolder[0]
boolean mark = ref.isMarked();

// CAS with mark: used in lock-free lists to logically delete a node
boolean ok = ref.compareAndSet(
        "node", "node",  // expected ref, new ref (same, we're just marking)
        false, true      // expected mark, new mark
);
```

The classic use of `AtomicMarkableReference` is in lock-free linked list deletion. When a thread wants to remove a node, it first marks it (logical deletion) and then physically unlinks it. Other threads that see a marked node know it is being removed and can assist or skip it.

## Code Snippet

```java
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;

/**
 * Demonstrates:
 *  1. AtomicReference for atomic config swap
 *  2. The ABA problem with plain AtomicReference
 *  3. AtomicStampedReference preventing ABA
 *
 * Run: javac AtomicReferenceSnippet.java && java AtomicReferenceSnippet
 * (See CASDemo.java for the full runnable example)
 */
public class AtomicReferenceSnippet {

    // --- Demo 1: Atomic config publish ---

    record Config(String host, int port) {}

    static final AtomicReference<Config> configRef =
            new AtomicReference<>(new Config("localhost", 8080));

    // --- Demo 2 & 3: ABA with a simple string reference ---

    static final AtomicReference<String> abaRef =
            new AtomicReference<>("A");

    static final AtomicStampedReference<String> stampedRef =
            new AtomicStampedReference<>("A", 0);

    public static void main(String[] args) throws InterruptedException {

        // -- Demo 1 --
        Thread updater = new Thread(() -> {
            Config old = configRef.get();
            Config next = new Config("prod-host", 443);
            boolean ok = configRef.compareAndSet(old, next);
            System.out.println("[config] CAS succeeded: " + ok
                    + " => " + configRef.get());
        }, "config-updater");
        updater.start();
        updater.join();

        // -- Demo 2: ABA problem --
        String seen = abaRef.get(); // Thread 1 reads "A"
        System.out.println("[ABA] Thread 1 sees: " + seen);

        // Simulate thread 2 doing A -> B -> A
        abaRef.set("B");
        abaRef.set("A");
        System.out.println("[ABA] Thread 2 did A->B->A, current: " + abaRef.get());

        // Thread 1 CAS succeeds even though state changed
        boolean abaOk = abaRef.compareAndSet(seen, "C");
        System.out.println("[ABA] Thread 1 CAS(A->C) succeeded: " + abaOk
                + " (ABA went undetected)");

        // -- Demo 3: AtomicStampedReference prevents ABA --
        int[] stamp = new int[1];
        String initial = stampedRef.get(stamp);
        System.out.println("\n[Stamped] Thread 1 sees: " + initial
                + ", stamp=" + stamp[0]);

        // Thread 2: A->B (stamp 0->1) then B->A (stamp 1->2)
        stampedRef.compareAndSet("A", "B", 0, 1);
        stampedRef.compareAndSet("B", "A", 1, 2);
        System.out.println("[Stamped] Thread 2 did A->B->A, stamp now: "
                + stampedRef.getStamp());

        // Thread 1 tries CAS with old stamp (0) -- should FAIL
        boolean stampedOk = stampedRef.compareAndSet("A", "C", 0, 1);
        System.out.println("[Stamped] Thread 1 CAS(A->C, stamp 0->1) succeeded: "
                + stampedOk + " (correctly detected ABA)");
    }
}
```

## Gotchas

### Equality in compareAndSet uses reference equality, not equals()

`AtomicReference.compareAndSet` compares references with `==`, not `.equals()`. Two distinct `String` objects with the same content are not the same reference. If you create a new object to represent the "expected" value and pass it to `compareAndSet`, the comparison will fail even though the content matches. Always retain the reference returned by `get()` and pass that exact reference as the expected argument.

### AtomicStampedReference has no atomic get-reference-and-stamp method that returns both cleanly

To read both the reference and the stamp without two separate calls, you must use `get(int[] stampHolder)`. This API is awkward: it writes the stamp into a single-element array you must allocate. Forgetting to allocate the array or reusing a stale array are common mistakes. Always allocate a fresh `int[1]` per read when you need both values.

### Incrementing the stamp on every CAS does not eliminate ABA, it only makes it very unlikely

A stamp implemented as an `int` wraps around after 2,147,483,647 increments. In a long-running system with extremely high update rates, stamp wraparound is theoretically possible. In practice this is not a concern for most applications, but for systems with strict correctness requirements, you should document the assumption that stamp wraparound will not occur within the expected operational lifetime.

### AtomicMarkableReference cannot distinguish multiple mark transitions

The boolean mark can only ever hold two states. If your algorithm needs to track more than one kind of logical deletion or tagging, `AtomicMarkableReference` is insufficient. You either need `AtomicStampedReference` with a stamp encoding the state machine, or a different design altogether.

### Garbage collection and ABA

In Java, the garbage collector ensures that a reference to a live object is always valid, and a collected object's memory will not be reused for a new object at the same address. This means the C/C++ form of ABA — where a freed pointer is reused for a new allocation — cannot happen with `AtomicReference`. The ABA problem in Java is about semantic state changes: the object that the reference points to may have undergone meaningful transitions, not about address reuse.

### Using AtomicReference for mutable objects defeats the purpose

`AtomicReference` atomically swaps a reference, but if the object that the reference points to is itself mutable, readers can observe partially constructed or inconsistent state through the reference. The correct pattern is to store only immutable objects (or effectively immutable records) in an `AtomicReference`. If you need to atomically publish mutable state, you need a different synchronization mechanism.
