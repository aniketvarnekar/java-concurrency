# Lock-Free Algorithms

## Overview

Lock-free algorithms guarantee that at least one thread in the system makes progress in a finite number of steps, regardless of what other threads do — including indefinite preemption, delays, or failures. This is a strictly stronger guarantee than mutex-based (blocking) algorithms, which guarantee no forward progress at all if the thread holding the lock is preempted indefinitely. Lock-free algorithms achieve their guarantee using atomic compare-and-swap (CAS) instructions rather than mutual exclusion: instead of excluding other threads from a critical section, they detect interference and retry.

The fundamental building block of lock-free algorithms is the CAS loop. A thread reads the current value of a shared variable, computes a new value based on it, and then attempts a CAS: an atomic operation that writes the new value only if the variable still holds the previously read value. If the CAS fails — meaning another thread has modified the variable since it was read — the entire read-compute-CAS sequence is retried from the beginning. Progress is guaranteed because a CAS failure implies that another thread succeeded, satisfying the lock-free progress property.

Lock-free data structures are most valuable in scenarios where lock contention is a demonstrated bottleneck and the overhead of lock acquisition, context switching, and priority inversion are unacceptable. Java's `java.util.concurrent` package provides production-quality lock-free structures: `ConcurrentLinkedQueue` (Michael-Scott queue), `ConcurrentSkipListMap`, and all `java.util.concurrent.atomic` classes. In most applications, these library implementations are preferable to custom lock-free code, which is notoriously difficult to implement correctly.

## Key Concepts

### Progress Guarantees

Progress guarantees form a hierarchy from strongest to weakest:

| Guarantee | Definition | Example |
|---|---|---|
| Wait-free | Every thread completes in bounded steps, regardless of others | Reading a `volatile` field |
| Lock-free | At least one thread makes progress; others may retry indefinitely | CAS loop counter |
| Obstruction-free | A thread makes progress in isolation (only with no contention) | Some STM implementations |
| Blocking | No guarantee if the lock-holder is preempted | `synchronized` |

Wait-free is the strongest guarantee: every individual thread is guaranteed to complete its operation in a bounded number of steps, even if all other threads are running concurrently and taking as long as they want. This is difficult to achieve for complex data structures. Lock-free is more practical: it guarantees system-wide progress but allows individual threads to retry indefinitely under high contention (theoretically, though rarely in practice).

### CAS Loop Pattern

The CAS loop is the foundational pattern:

```java
// Generic CAS loop for a lock-free counter
public int increment(AtomicInteger counter) {
    int current;
    int next;
    do {
        current = counter.get();       // (1) read current value
        next = current + 1;            // (2) compute new value
    } while (!counter.compareAndSet(current, next)); // (3) CAS: retry if value changed
    return next;
}
```

The loop exits only when the CAS succeeds — that is, when no other thread modified the variable between the read (step 1) and the CAS (step 3). Because a CAS failure means another thread's CAS succeeded, the lock-free progress property holds: at least one thread makes progress on each iteration.

### Treiber Lock-Free Stack

The Treiber stack is a classic lock-free stack using a single `AtomicReference<Node>` for the head pointer.

```
Stack state:        head → [Node-C] → [Node-B] → [Node-A] → null

push(D):
  1. newNode = new Node(D)
  2. oldHead = head.get()           // read: [Node-C]
  3. newNode.next = oldHead         // link: D → [Node-C]
  4. CAS(head, oldHead, newNode)    // write: head → [Node-D]
     → success if head is still [Node-C]
     → retry if another thread pushed/popped

pop():
  1. oldHead = head.get()           // read: [Node-D]
  2. if (oldHead == null) return empty
  3. newHead = oldHead.next         // next: [Node-C]
  4. CAS(head, oldHead, newHead)    // write: head → [Node-C]
     → success if head is still [Node-D]
     → retry if another thread pushed/popped
```

### ABA Problem

The ABA problem occurs when a CAS reads a value A, another thread changes A → B → A, and the first thread's CAS succeeds even though the state has logically changed. In pointer-based structures without GC, this can corrupt the data structure because the A pointer may now point to a recycled object. In Java, the garbage collector prevents address reuse while live references exist, mitigating physical ABA. However, logical ABA — where the same value represents a different semantic state — can still occur and requires `AtomicStampedReference` (pairing the value with an integer version counter that increments on every change).

```java
AtomicStampedReference<Node> head = new AtomicStampedReference<>(null, 0);

// CAS with stamp:
int[] stamp = new int[1];
Node current = head.get(stamp);
head.compareAndSet(current, newHead, stamp[0], stamp[0] + 1);
```

### Michael-Scott Lock-Free Queue

The Michael-Scott queue (underlying `ConcurrentLinkedQueue`) uses two pointers: `head` and `tail`. Enqueue CAS's on `tail.next` to append a new node, then advances `tail`. Dequeue CAS's on `head` to advance it. The two-pointer design allows enqueue and dequeue to operate concurrently on different ends of the queue without interfering, and includes a "helping" mechanism where a thread that finds `tail` lagging behind advances it before proceeding.

## Code Snippet

```java
import java.util.concurrent.atomic.AtomicReference;

/**
 * Treiber lock-free stack: push/pop with explicit CAS retry loop.
 * Multiple threads push and pop concurrently; verifies no values are lost or duplicated.
 *
 * Run: javac LockFreeStackDemo.java && java LockFreeStackDemo
 */
public class LockFreeStackDemo {

    // ---------------------------------------------------------------
    // Treiber Stack
    // ---------------------------------------------------------------
    static class LockFreeStack<T> {
        // Node: immutable after construction
        static class Node<T> {
            final T value;
            final Node<T> next;

            Node(T value, Node<T> next) {
                this.value = value;
                this.next  = next;
            }
        }

        private final AtomicReference<Node<T>> head = new AtomicReference<>(null);
        private volatile int pushRetries = 0;
        private volatile int popRetries  = 0;

        public void push(T value) {
            Node<T> newNode;
            Node<T> currentHead;
            do {
                currentHead = head.get();        // (1) read current head
                newNode = new Node<>(value, currentHead); // (2) build new node
                // (3) CAS: write only if head hasn't changed
            } while (!head.compareAndSet(currentHead, newNode));
            // If CAS fails, another thread pushed or popped; retry from step 1
        }

        public T pop() {
            Node<T> currentHead;
            Node<T> newHead;
            do {
                currentHead = head.get();        // (1) read current head
                if (currentHead == null) {
                    return null;                 // stack is empty
                }
                newHead = currentHead.next;      // (2) compute new head
                // (3) CAS: write only if head hasn't changed
            } while (!head.compareAndSet(currentHead, newHead));
            return currentHead.value;
        }

        public boolean isEmpty() {
            return head.get() == null;
        }
    }

    // ---------------------------------------------------------------
    // Main: concurrent push/pop, verify correctness
    // ---------------------------------------------------------------
    public static void main(String[] args) throws InterruptedException {
        LockFreeStack<Integer> stack = new LockFreeStack<>();
        int itemsPerThread = 1000;
        int numThreads     = 4;

        // Track pushed and popped values
        java.util.concurrent.ConcurrentLinkedQueue<Integer> pushed = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.ConcurrentLinkedQueue<Integer> popped = new java.util.concurrent.ConcurrentLinkedQueue<>();

        // Push threads
        Thread[] pushers = new Thread[numThreads];
        for (int t = 0; t < numThreads; t++) {
            final int base = t * itemsPerThread;
            pushers[t] = new Thread(() -> {
                for (int i = base; i < base + itemsPerThread; i++) {
                    stack.push(i);
                    pushed.add(i);
                }
            }, "pusher-" + t);
        }

        // Pop threads — run concurrently with pushers
        Thread[] poppers = new Thread[numThreads];
        java.util.concurrent.atomic.AtomicBoolean allPushed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        for (int t = 0; t < numThreads; t++) {
            poppers[t] = new Thread(() -> {
                // Keep popping until all pushed and stack is empty
                while (!allPushed.get() || !stack.isEmpty()) {
                    Integer val = stack.pop();
                    if (val != null) {
                        popped.add(val);
                    }
                }
            }, "popper-" + t);
        }

        // Start all threads
        for (Thread p : poppers) p.start();
        for (Thread p : pushers) p.start();

        // Wait for all pushers to finish
        for (Thread p : pushers) p.join();
        allPushed.set(true);

        // Drain remaining items with poppers
        for (Thread p : poppers) p.join();

        // Drain anything still in stack (edge case: poppers may have exited early)
        Integer leftover;
        while ((leftover = stack.pop()) != null) {
            popped.add(leftover);
        }

        // Verify
        int totalPushed = pushed.size();
        int totalPopped = popped.size();

        java.util.TreeSet<Integer> pushedSet = new java.util.TreeSet<>(pushed);
        java.util.TreeSet<Integer> poppedSet = new java.util.TreeSet<>(popped);

        System.out.printf("Pushed: %,d items | Popped: %,d items%n", totalPushed, totalPopped);
        System.out.printf("All pushed values popped: %b%n", pushedSet.equals(poppedSet));
        System.out.printf("No duplicates in popped: %b%n", popped.size() == poppedSet.size());
        System.out.printf("Stack empty after drain: %b%n", stack.isEmpty());
    }
}
```

## Gotchas

**CAS failure under high contention causes spinning that can be worse than a mutex.** When many threads simultaneously attempt to CAS the same variable, all but one fail and retry. Under sustained high contention, the retry loop resembles a spinlock, consuming CPU while making slow progress. Exponential backoff (adding a `Thread.sleep()` or `Thread.onSpinWait()` after each failed CAS) reduces contention at the cost of increased latency. Under extreme contention, a well-implemented mutex with blocking is more efficient than a CAS loop.

**The ABA problem in Java is partially mitigated by the GC but not eliminated for logical ABA.** Because the GC does not reclaim objects while strong references to them exist, the same object address cannot be reused while another thread holds a reference to it. This prevents physical ABA in pointer-based structures. However, if the algorithm uses primitive values (integers, counts) or reuses object instances from a pool, the ABA problem in its logical form still applies. Use `AtomicStampedReference` or `AtomicMarkableReference` when the value can legitimately cycle back to a previously seen state with different semantics.

**Lock-free does not mean fast.** The constant factors for lock-free algorithms are often higher than their locked equivalents. Each CAS is a full memory barrier, which is significantly more expensive than a regular store on modern CPUs. Retry loops add branch misprediction overhead. For low-contention access patterns, a simple `synchronized` block may outperform a CAS loop due to JIT lock elision and biased locking optimizations. Always benchmark the actual workload.

**Progress guarantees address liveness, not fairness.** A lock-free algorithm guarantees that at least one thread makes progress, but it does not guarantee that all threads make progress at similar rates. A thread that consistently loses the CAS race to faster or more fortunate threads can spin for an extended period before succeeding — this is a form of starvation, even in a lock-free algorithm. Lock-free is not starvation-free; for fairness guarantees, a wait-free or fair-queued algorithm is required.

**Memory reclamation in Java lock-free structures is simpler than in native languages.** In C++ or Rust, freeing a node that is being read by another thread (a memory reclamation hazard) requires hazard pointers or epoch-based reclamation. In Java, the GC handles this: a node is not reclaimed while any thread holds a reference to it, even if that reference was read from a data structure that has since been logically removed. This is a significant advantage of Java for lock-free programming and one reason Java's `java.util.concurrent` structures are simpler than equivalent C++ implementations.

**Composing two lock-free operations into one atomic compound operation requires algorithm redesign.** Two separate CAS operations cannot be made collectively atomic without a fundamentally different approach. For example, atomically moving an item from one lock-free stack to another requires either a combining algorithm, a transactional approach, or redesigning both structures to share a single point of synchronization. The apparent simplicity of individual CAS operations does not compose freely into larger atomic actions.
