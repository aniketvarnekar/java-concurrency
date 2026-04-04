# SynchronousQueue

## Overview

`SynchronousQueue<E>` is a blocking queue with zero capacity. It stores no elements. Every `put` operation blocks until another thread calls `take`, and every `take` blocks until another thread calls `put`. The two threads meet at the queue and hand the item directly from one to the other — no buffering, no intermediate storage. The queue is a synchronization point, not a container.

This direct-handoff semantics means that the producing thread and the consuming thread always rendezvous. The producer cannot race ahead of the consumer, and the consumer cannot race ahead of the producer. Both threads are guaranteed to be present at the moment of transfer. This tight coupling is the distinguishing characteristic of `SynchronousQueue` and is what makes it fundamentally different from all other queue implementations.

`SynchronousQueue` plays a central role in the standard library: it is the queue used by `Executors.newCachedThreadPool()`. When a task is submitted to a cached pool, the pool tries to hand the task directly to an idle thread. If no idle thread is available, a new thread is created immediately (or a `RejectedExecutionException` is thrown if the pool is at its limit). There is no buffer to absorb bursts of tasks; each task either finds a waiting thread or triggers thread creation.

The practical uses for `SynchronousQueue` in application code are narrower than the other queue types: tightly coupled pipeline stages where the producer must wait for the consumer to acknowledge receipt, single-item transfer channels, and any design where buffering would mask overload rather than surface it.

## Key Concepts

### Direct Handoff

When thread A calls `put(item)`, it inserts the item and then blocks. The item is not placed in any storage structure. Thread A parks itself and waits. When thread B calls `take()`, it sees thread A's waiting item, takes it, unparks thread A, and returns. The transfer is complete. Both threads proceed only after the handoff has occurred.

```
Thread A (producer)          Thread B (consumer)
     |                              |
   put(X)                           |
   [blocks, waiting for consumer]   |
     |                            take()
     |                         <-- X received
   [unblocked, continues]          |
     |                           [processes X]
```

This diagram shows that the producer is held at `put` until the consumer is ready. Neither thread can proceed past the handoff independently.

### Fairness Mode

`SynchronousQueue` has two internal implementations, selected by the constructor:

`new SynchronousQueue<>()` (or `new SynchronousQueue<>(false)`) — unfair mode. Internally uses a stack (LIFO) of waiting threads. The most recently arrived waiter is served first. This can improve throughput under high contention because the most recently cached thread state tends to be warmer in CPU caches.

`new SynchronousQueue<>(true)` — fair mode. Internally uses a queue (FIFO) of waiting threads. The longest-waiting producer is matched with the longest-waiting consumer first. This prevents starvation and provides predictable ordering of handoffs at the cost of some throughput.

### Use in newCachedThreadPool

`Executors.newCachedThreadPool()` constructs a `ThreadPoolExecutor` with `corePoolSize=0`, `maximumPoolSize=Integer.MAX_VALUE`, `keepAliveTime=60s`, and a `SynchronousQueue` as the work queue. Because the queue has zero capacity, every submitted task that cannot be immediately handed to an idle thread causes a new thread to be spawned (up to `Integer.MAX_VALUE`). This means a cached thread pool can create a very large number of threads under burst load, which can exhaust system resources. The `SynchronousQueue` makes this behavior explicit and unavoidable: there is nowhere for tasks to wait except in a thread.

### Producer-Consumer Without Buffering

In a standard buffered producer-consumer system, a slow consumer causes the buffer to fill, which eventually blocks the producer. The back-pressure is indirect and delayed by the buffer size. With `SynchronousQueue`, back-pressure is immediate: the producer blocks after every single item until the consumer is ready. This makes overload immediately visible and forces the production rate to match the consumption rate precisely.

### offer() and poll() Without Timeout

`offer(e)` (no timeout) inserts the element only if a consumer is already waiting. It returns `true` if the handoff occurred, `false` immediately if no consumer was present. `poll()` (no timeout) retrieves an element only if a producer is already waiting. This non-blocking form is useful for probing availability without committing to a wait.

## Code Snippet

```java
import java.time.Instant;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates SynchronousQueue direct-handoff semantics.
 *
 * Part 1: A producer and consumer rendezvous via put/take, showing
 *         that both threads must be present for the transfer to complete.
 *
 * Part 2: Shows that offer() returns false immediately when no consumer waits.
 *
 * Run: javac SynchronousQueueDemo.java && java SynchronousQueueDemo
 */
public class SynchronousQueueDemo {

    static String timestamp() {
        return String.format("%tT.%tL", Instant.now(), Instant.now());
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Part 1: Synchronized handoff ===");
        demonstrateHandoff();

        Thread.sleep(500);

        System.out.println("\n=== Part 2: offer() with no consumer present ===");
        demonstrateOfferNoConsumer();
    }

    static void demonstrateHandoff() throws InterruptedException {
        SynchronousQueue<String> queue = new SynchronousQueue<>();

        // Consumer starts first but will block waiting for producer
        Thread consumer = new Thread(() -> {
            try {
                System.out.printf("[%s][%s] calling take(), waiting for producer...%n",
                    timestamp(), Thread.currentThread().getName());
                String item = queue.take();
                System.out.printf("[%s][%s] received: %s%n",
                    timestamp(), Thread.currentThread().getName(), item);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "consumer");
        consumer.start();

        // Producer waits 400ms to show consumer blocks during that time
        Thread producer = new Thread(() -> {
            try {
                Thread.sleep(400);
                System.out.printf("[%s][%s] calling put('hello')...%n",
                    timestamp(), Thread.currentThread().getName());
                queue.put("hello");
                System.out.printf("[%s][%s] put() returned — consumer has the item%n",
                    timestamp(), Thread.currentThread().getName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "producer");
        producer.start();

        producer.join();
        consumer.join();
    }

    static void demonstrateOfferNoConsumer() throws InterruptedException {
        SynchronousQueue<String> queue = new SynchronousQueue<>();

        // offer() with no waiting consumer — returns false immediately
        boolean accepted = queue.offer("nobody-home");
        System.out.printf("[%s] offer() with no consumer: accepted=%b%n",
            timestamp(), accepted);

        // offer() with timeout — start a delayed consumer
        Thread delayedConsumer = new Thread(() -> {
            try {
                Thread.sleep(200);
                System.out.printf("[%s][%s] calling take() now%n",
                    timestamp(), Thread.currentThread().getName());
                String item = queue.take();
                System.out.printf("[%s][%s] received: %s%n",
                    timestamp(), Thread.currentThread().getName(), item);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "delayed-consumer");
        delayedConsumer.start();

        System.out.printf("[%s][main] calling offer('timed', 1s timeout)...%n", timestamp());
        boolean timedAccepted = queue.offer("timed", 1, TimeUnit.SECONDS);
        System.out.printf("[%s][main] timed offer result: accepted=%b%n",
            timestamp(), timedAccepted);

        delayedConsumer.join();
    }
}
```

## Gotchas

### peek(), isEmpty(), and Iteration Are Meaningless

`SynchronousQueue` has no elements to peek at, iterate over, or measure. `peek()` always returns `null`. `isEmpty()` always returns `true`. `size()` always returns 0. `iterator()` returns an empty iterator. These methods exist to satisfy the `Collection` contract but provide no useful information. Any code that checks `queue.size() > 0` or `queue.isEmpty()` before deciding to call `take` is simply wrong.

### offer() Returns False When No Consumer Waits

`offer(e)` without a timeout returns `false` immediately if no consumer thread is currently blocked in `take`. This is appropriate when you want to check availability and skip if no consumer is ready, but it also means that a producer that uses `offer` without understanding this semantics will silently drop items under any load where consumers are momentarily busy. Always use `put` when item delivery is required, or use the timed `offer` and handle a `false` return explicitly.

### newCachedThreadPool Can Exhaust Threads

`Executors.newCachedThreadPool()` uses `SynchronousQueue` with `maximumPoolSize=Integer.MAX_VALUE`. Under a burst of task submissions, no task queues — each one either finds an idle thread or creates a new one. A burst of 10,000 tasks in a second will attempt to create up to 10,000 threads, which can exhaust file descriptors, stack space, or OS thread limits. Use `newCachedThreadPool` only for short-lived, bursty workloads with well-understood peak load. For sustained high load, use a `ThreadPoolExecutor` with a bounded queue and a defined rejection policy.

### One Thread Not Calling exchange Leaves the Other Blocked Forever

If a producer calls `put` but the corresponding consumer thread dies, is interrupted without re-entering `take`, or simply never calls `take`, the producer blocks indefinitely. Always pair a timed `offer(e, timeout, unit)` or `put` within a thread that handles `InterruptedException` with a shutdown mechanism, so that a missing partner does not permanently wedge the producer. For robustness, prefer `offer` with a timeout over `put` in production code where consumer availability is not guaranteed.

### SynchronousQueue Cannot Buffer Bursts

Any workload where producers run faster than consumers, even briefly, will cause producer threads to pile up waiting in `put`. With a regular `BlockingQueue`, bursts are absorbed by the buffer. With `SynchronousQueue`, every unmatched producer parks immediately. If your design assumes a queue that smooths out production spikes, `SynchronousQueue` is the wrong choice and will turn production spikes into thread stalls.
