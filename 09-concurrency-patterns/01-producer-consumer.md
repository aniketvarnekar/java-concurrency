# Producer-Consumer Pattern

## Overview

The producer-consumer pattern separates two classes of threads: those that generate data (producers) and those that process it (consumers). Without a buffer between them, producers would need to block until a consumer was ready and vice versa, coupling their execution rates. The pattern places a shared buffer between the two sides so that each runs at its natural pace.

Java's `BlockingQueue` interface is the standard buffer for this pattern. It handles the blocking semantics automatically: a producer calling `put()` blocks when the queue is full, and a consumer calling `take()` blocks when the queue is empty. This eliminates the need to write `wait()`/`notify()` synchronization by hand, which is error-prone and difficult to get right.

The pattern is applicable whenever you have a pipeline stage: HTTP request handler threads producing work items, worker threads consuming them; log event generators producing messages, a file-writer thread consuming them; sensor threads producing readings, an analytics thread consuming them. The queue absorbs rate mismatches between the two sides, acting as a cushion.

Choosing the right `BlockingQueue` implementation determines the queue's ordering and bounding behavior. `ArrayBlockingQueue` is bounded and FIFO. `LinkedBlockingQueue` is optionally bounded and FIFO. `PriorityBlockingQueue` is unbounded with priority ordering. `SynchronousQueue` has no capacity — each `put()` must pair with a `take()` directly, which creates a rendezvous rather than a buffer.

## Key Concepts

### The Pattern

Producers call `queue.put(item)`, which blocks when the queue is at capacity. Consumers call `queue.take()`, which blocks when the queue is empty. The two sides never call methods on each other directly. The queue is the only shared object between them, and `BlockingQueue` implementations are thread-safe, so no additional synchronization is needed around `put()` and `take()`.

```java
// Producer side
queue.put(item);   // blocks if queue is full

// Consumer side
Item item = queue.take();  // blocks if queue is empty
```

The thread that calls `put()` does not know which consumer will process the item, and the thread that calls `take()` does not know which producer generated it. This is the decoupling the pattern provides.

### Decoupling

When the producer runs faster than the consumer, items accumulate in the queue up to its capacity, at which point `put()` blocks the producer. This is called backpressure: the slow consumer applies pressure backward through the queue to slow down the producer. Without backpressure (unbounded queue), a fast producer can fill available heap memory with queued items, eventually causing `OutOfMemoryError`.

When the consumer runs faster than the producer, `take()` blocks the consumer until work arrives. The consumer wastes no CPU while waiting.

This decoupling is the key architectural benefit. Producers and consumers can be scaled independently. Adding a second consumer thread requires no changes to producers; it just calls `take()` on the same queue.

### Bounded Buffer

An `ArrayBlockingQueue` constructed with a fixed capacity is called a bounded buffer. The capacity is a design parameter that trades latency for throughput. A small capacity creates tight backpressure: producers are throttled quickly when consumers fall behind. A large capacity absorbs larger bursts before backpressure engages, but uses more memory and allows producers to run further ahead of consumers.

```java
// Capacity 10: producer blocks after generating 10 unprocessed items
BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);
```

### Poison Pill Shutdown

Because consumers call `take()` in a loop, they need a signal to stop. A poison pill is a sentinel object placed in the queue by producers after all real work is done. When a consumer dequeues the poison pill, it recognizes it as a stop signal and exits its loop.

The critical rule: there must be exactly one poison pill per consumer thread. If three consumer threads are running, three poison pills must be inserted. If fewer are inserted, some consumers will block forever on `take()`. If more are inserted, consumers may attempt to process a poison pill after another consumer already handled it, which can cause errors depending on the item type.

```java
static final String POISON = "__DONE__";

// Producer inserts one pill per consumer after real work
for (int i = 0; i < NUM_CONSUMERS; i++) {
    queue.put(POISON);
}

// Consumer checks for the pill
while (true) {
    String item = queue.take();
    if (POISON.equals(item)) break;
    process(item);
}
```

An alternative to poison pills is using an `ExecutorService` for consumers and calling `shutdown()`, but the poison pill approach works well when consumers are plain threads managing their own lifecycle.

### Multiple Producers and Consumers

All `BlockingQueue` implementations are fully thread-safe. Multiple producer threads calling `put()` concurrently and multiple consumer threads calling `take()` concurrently require no additional synchronization. The queue handles arbitration internally.

With multiple consumers, any one of them may receive any given item. If the consumer side requires that certain items are always processed by the same consumer (affinity), a router layer must distribute items before they enter per-consumer queues — the single shared queue pattern does not provide this.

### Ordering

`ArrayBlockingQueue` and `LinkedBlockingQueue` are FIFO: items are dequeued in the order they were enqueued. `PriorityBlockingQueue` accepts a `Comparator` and always dequeues the highest-priority item, regardless of insertion order. If both ordering and capacity bounds are needed, a bounded priority queue requires extra work because `PriorityBlockingQueue` is unbounded.

```
ASCII: Producer-Consumer with Bounded Buffer

Producer-1 ---\
               |---> [ Queue: capacity N ] ---> Consumer-1
Producer-2 ---/      [ _ | _ | X | X | X ]     Consumer-2
                                                Consumer-3
                     ^-- backpressure --^
                     When full, producers block.
                     When empty, consumers block.
```

## Code Snippet

```java
import java.util.concurrent.*;

public class ProducerConsumerExample {

    static final String POISON = "__POISON__";
    static final int NUM_CONSUMERS = 3;

    public static void main(String[] args) throws InterruptedException {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);

        // Start 2 producers
        Thread p1 = new Thread(new Producer(queue, "P1", 5), "producer-1");
        Thread p2 = new Thread(new Producer(queue, "P2", 5), "producer-2");

        // Start 3 consumers
        Thread c1 = new Thread(new Consumer(queue), "consumer-1");
        Thread c2 = new Thread(new Consumer(queue), "consumer-2");
        Thread c3 = new Thread(new Consumer(queue), "consumer-3");

        c1.start(); c2.start(); c3.start();
        p1.start(); p2.start();

        p1.join(); p2.join();

        // Insert one poison pill per consumer
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            queue.put(POISON);
        }

        c1.join(); c2.join(); c3.join();
        System.out.println("All done.");
    }

    static class Producer implements Runnable {
        private final BlockingQueue<String> queue;
        private final String id;
        private final int count;

        Producer(BlockingQueue<String> queue, String id, int count) {
            this.queue = queue; this.id = id; this.count = count;
        }

        public void run() {
            try {
                for (int i = 1; i <= count; i++) {
                    String item = id + "-item-" + i;
                    queue.put(item);
                    System.out.printf("[%s] produced: %s%n",
                        Thread.currentThread().getName(), item);
                    Thread.sleep((long)(Math.random() * 100));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static class Consumer implements Runnable {
        private final BlockingQueue<String> queue;

        Consumer(BlockingQueue<String> queue) { this.queue = queue; }

        public void run() {
            try {
                while (true) {
                    String item = queue.take();
                    if (POISON.equals(item)) break;
                    System.out.printf("[%s] consumed: %s%n",
                        Thread.currentThread().getName(), item);
                    Thread.sleep((long)(Math.random() * 150));
                }
                System.out.printf("[%s] exiting.%n", Thread.currentThread().getName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
```

## Gotchas

### Wrong Number of Poison Pills

If fewer poison pills than consumer threads are inserted, some consumers will block indefinitely on `take()`. The program will not terminate. The number of pills must equal the number of consumer threads exactly. If consumers are added or removed dynamically, the shutdown logic must be updated accordingly.

### Not Handling InterruptedException in put() and take()

Both `put()` and `take()` throw `InterruptedException`. Swallowing this exception (catching it without restoring the interrupt flag or retrying) can cause threads to silently ignore shutdown signals. Always either propagate the exception or call `Thread.currentThread().interrupt()` after catching it to restore the interrupt status.

### Unbounded Queue and OutOfMemoryError

Using `new LinkedBlockingQueue<>()` without a capacity argument creates an unbounded queue. If the producer consistently outpaces the consumer, items accumulate without limit. Under load this will exhaust heap memory. Always specify a capacity that matches the expected working set and the memory budget.

### Exception in Consumer Leaves Queue Items and Producers Blocked

If a consumer thread throws an unchecked exception and terminates, it stops calling `take()`. If the queue becomes full as a result, producers block on `put()` indefinitely with no indication of what went wrong. Use a `try/finally` or an `UncaughtExceptionHandler` to ensure consumer threads either restart or signal producers to stop on failure.

### Poison Pill with Multiple Item Types

If the item type is a class, a common mistake is using `null` as the poison pill. `LinkedBlockingQueue` does not permit `null` elements — inserting `null` throws `NullPointerException`. Use a sentinel instance of the item class, or a wrapper type that can distinguish sentinel from real items.

### Sharing a Queue Between Unrelated Pipelines

A `BlockingQueue` should be private to one producer-consumer pipeline. If multiple unrelated pipelines share a queue, a poison pill from one pipeline can be consumed by a consumer from a different pipeline, causing premature shutdown or corrupted state. Each pipeline should have its own queue instance.
