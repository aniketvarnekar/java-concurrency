# Disruptor Pattern

## Overview

The LMAX Disruptor is a high-performance inter-thread messaging library developed for the LMAX electronic trading platform and open-sourced in 2011. It replaces a blocking queue with a pre-allocated ring buffer and achieves throughput several orders of magnitude higher than java.util.concurrent queues under comparable workloads.

Three design decisions account for the performance difference. First, the ring buffer is pre-allocated at startup: event objects are created once and their fields are updated in place. No garbage is generated per message, which eliminates GC pauses during message passing. Second, coordination between producers and consumers uses memory barriers and CAS rather than locks. Third, each sequence number — the cursor tracking how far a producer or consumer has advanced — is padded to occupy its own CPU cache line, eliminating false sharing between threads updating different sequences.

The Disruptor is a pattern as much as a library. The core ideas — ring buffer, sequence numbers, sequence barriers, and cache line padding — can be applied independently of the LMAX library itself, and understanding them gives insight into the performance limits of concurrent queues in general.

## Key Concepts

### Ring Buffer

The ring buffer is a fixed-size circular array of pre-allocated event objects. Its size must be a power of 2 so that the slot index can be computed as `sequence & (size - 1)` using a bitwise AND, which is faster than modulo division. Events are never allocated or discarded at runtime; their fields are overwritten by the producer for each new message.

```java
class Event {
    long value;
    String data;
}

Event[] ringBuffer = new Event[1024]; // size = power of 2
for (int i = 0; i < ringBuffer.length; i++) {
    ringBuffer[i] = new Event();       // pre-allocate
}
```

### Sequence Numbers

Every slot in the ring buffer is addressed by a monotonically increasing long sequence number. The producer holds a cursor — the highest sequence number claimed. Each consumer holds its own sequence — the last sequence it processed. The available range for a consumer is `consumerSequence + 1` through the producer cursor. When the consumer's sequence falls more than `bufferSize` behind the producer, the ring buffer is logically full and the producer stalls.

```
Producer cursor: 1023
Consumer sequence: 995
Buffer size: 1024
Available to consume: 996..1023  (28 slots)
Wrap-around guard: producer cannot exceed consumer + bufferSize
```

### Producer Claiming

A single producer increments its sequence counter without synchronization. A multi-producer setup uses CAS to claim the next sequence atomically:

```java
// Single producer (no CAS needed)
long nextSeq = ++producerSequence;
int slot = (int)(nextSeq & MASK);
ringBuffer[slot].value = newValue;
// publish: make visible to consumers via volatile write or fence
cursor = nextSeq;

// Multi-producer
long current, next;
do {
    current = cursor.get();
    next = current + 1;
} while (!cursor.compareAndSet(current, next));
```

### Sequence Barriers and Consumer Waiting

A consumer waits on a SequenceBarrier — a polling loop that checks whether the producer cursor has advanced past the consumer's next expected sequence. The wait strategy is configurable: busy-spin (lowest latency, highest CPU), yielding (medium), or blocking (lowest CPU, higher latency).

```java
// Busy-spin wait strategy
while (producerCursor < expectedSequence) {
    Thread.onSpinWait(); // JVM hint for spin loops (Java 9+)
}
```

### Cache Line Padding

A CPU cache line is typically 64 bytes. If two frequently written `long` fields share a cache line, writes to one field invalidate the other thread's cached copy of the entire line, causing cache coherence traffic even though neither thread touches the other's field. This is false sharing.

The Disruptor pads each sequence with 7 `long` padding fields so it occupies its own 64-byte cache line:

```java
class Sequence {
    // 7 longs before (56 bytes)
    long p1, p2, p3, p4, p5, p6, p7;
    // the actual value (8 bytes) — total so far: 64 bytes
    volatile long value;
    // 7 longs after — ensures no other field shares this cache line
    long q1, q2, q3, q4, q5, q6, q7;
}
```

Java 8+ provides `@Contended` (in `jdk.internal.vm.annotation`, or `sun.misc.Contended` in older JDKs) as an alternative, but it requires `-XX:-RestrictContended` to take effect outside the JDK.

### Single vs Multi-Producer

A single-producer Disruptor is simpler and faster: the producer's sequence is a plain long (or a volatile long for visibility) incremented without CAS. A multi-producer Disruptor requires CAS to claim sequences, and consumers must additionally check per-slot availability flags because a later sequence might be published before an earlier one if the earlier producer is preempted between claiming and publishing.

## Code Snippet

```java
/**
 * Simulates the Disruptor pattern: pre-allocated ring buffer, padded sequences,
 * single producer, single consumer.
 *
 * Run: javac DisruptorPatternDemo.java && java DisruptorPatternDemo
 */
public class DisruptorPatternDemo {

    // Cache-line padded sequence
    static final class Sequence {
        long p1, p2, p3, p4, p5, p6, p7;
        volatile long value;
        long q1, q2, q3, q4, q5, q6, q7;

        Sequence(long initial) { this.value = initial; }
    }

    // Pre-allocated event
    static final class Event {
        volatile long sequence;
        volatile long data;
    }

    static final int BUFFER_SIZE = 1024;   // must be power of 2
    static final int MASK        = BUFFER_SIZE - 1;
    static final int MESSAGES    = 100_000;

    static final Event[]   ring     = new Event[BUFFER_SIZE];
    static final Sequence  producer = new Sequence(-1);
    static final Sequence  consumer = new Sequence(-1);

    static { for (int i = 0; i < BUFFER_SIZE; i++) ring[i] = new Event(); }

    public static void main(String[] args) throws InterruptedException {
        Thread producerThread = new Thread(() -> {
            for (long i = 0; i < MESSAGES; i++) {
                // Wait if ring buffer is full (consumer too slow)
                while (i - consumer.value > BUFFER_SIZE - 1) {
                    Thread.onSpinWait();
                }
                int slot = (int)(i & MASK);
                ring[slot].data     = i * 2;
                ring[slot].sequence = i;          // publish
                producer.value = i;               // advance cursor
            }
        }, "disruptor-producer");

        Thread consumerThread = new Thread(() -> {
            long nextExpected = 0;
            long sum = 0;
            while (nextExpected < MESSAGES) {
                // Wait for next sequence to be published
                while (producer.value < nextExpected) {
                    Thread.onSpinWait();
                }
                int slot = (int)(nextExpected & MASK);
                sum += ring[slot].data;
                consumer.value = nextExpected;    // advance consumer
                nextExpected++;
            }
            System.out.println("Consumer processed " + MESSAGES + " messages, sum=" + sum);
        }, "disruptor-consumer");

        long start = System.currentTimeMillis();
        producerThread.start();
        consumerThread.start();
        producerThread.join();
        consumerThread.join();
        System.out.println("Time: " + (System.currentTimeMillis() - start) + " ms");
    }
}
```

## Gotchas

### Ring buffer size must be a power of 2
The index formula `sequence & (size - 1)` is only equivalent to `sequence % size` when size is a power of 2. Using a non-power-of-2 size produces wrong slot indices, causing producers to overwrite slots the consumer has not yet processed and corrupting messages silently.

### Slow consumers stall producers
When a consumer falls more than `bufferSize` sequences behind the producer, the producer must spin until the consumer catches up. Unlike an unbounded queue, the ring buffer has inherent backpressure — the producer stalls. Choosing the buffer size is a trade-off: too small and slow consumers frequently stall producers; too large and the pre-allocated memory footprint is proportionally larger.

### Padding is cache-line-size specific
The padding of 7 longs (56 bytes) is correct for a 64-byte cache line with 8-byte longs. Some architectures use 128-byte cache lines. The `@Contended` annotation is more portable but requires a JVM flag outside the JDK.

### Single-producer assumption
If two threads call the producer's publish method without synchronization in a single-producer Disruptor, both may claim the same slot, overwriting each other's data. The Disruptor library distinguishes `SingleProducerSequencer` from `MultiProducerSequencer` explicitly; mixing them silently corrupts messages.

### Busy-wait burns CPU continuously
The spin-wait strategy minimizes latency because the consumer never sleeps and notices new events immediately. However, a spinning consumer thread burns 100% of one CPU core even when no messages are arriving. For bursty or low-volume workloads, a yielding or sleeping wait strategy reduces CPU waste at the cost of slightly higher latency.

### False sharing without padding is severe
Benchmarking a ring buffer implementation without cache line padding on a multi-core machine often shows throughput degrading below that of a simple synchronized queue. The false-sharing penalty between producer and consumer sequences can negate all other optimizations. Always measure with and without padding when evaluating Disruptor-style implementations.
