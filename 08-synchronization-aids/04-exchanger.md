# Exchanger

## Overview

`Exchanger<V>` is a synchronization point at which two threads can exchange objects. Each thread presents an object and blocks until the other thread arrives at the same exchanger; then each receives the object presented by the other. This is a two-party rendezvous with data transfer. Neither thread can proceed past the exchange until both have arrived, and the swap is atomic from both threads' perspectives.

The canonical use case is double-buffering: one thread fills a buffer while the other processes the previous buffer. When the filler is done, it swaps the full buffer for the empty one that the drainer just finished processing. No additional locking is needed for the buffers themselves — ownership transfers atomically at the exchange point. Adjacent pipeline stages can use the same pattern to hand off work items without a shared queue.

## Key Concepts

### exchange(V object)

Presents `object` and blocks until the partner thread calls `exchange()` with its own object. Returns the partner's object. Throws `InterruptedException` if interrupted while waiting. The happens-before guarantee means all actions performed by thread A before `exchange()` are visible to thread B after `exchange()` returns, and vice versa.

```java
Exchanger<List<Integer>> exchanger = new Exchanger<>();

// Thread A presents its full buffer and receives the empty one
List<Integer> emptyBuffer = exchanger.exchange(fullBuffer);

// Thread B presents its empty buffer and receives the full one
List<Integer> fullBuffer = exchanger.exchange(emptyBuffer);
```

### exchange(V object, long timeout, TimeUnit unit)

Timed version. Throws `TimeoutException` if the partner does not arrive within the timeout. This is the production-safe form — always prefer it over the unbounded `exchange()` to detect a stuck or crashed partner.

```java
try {
    result = exchanger.exchange(data, 5, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    // partner did not arrive — decide whether to retry or abort
}
```

### Two-Party Only

`Exchanger` is strictly for exactly two threads. If a third thread calls `exchange()`, it waits for a fourth thread to form a second pair. Threads form arbitrary pairs based on arrival order, not logical identity. Any odd-numbered thread blocks indefinitely unless another thread arrives to pair with it.

```
Thread-A ──> exchange() ─┐
                          │ paired — swap occurs
Thread-B ──> exchange() ─┘

Thread-C ──> exchange() ─┐  blocks waiting for Thread-D
Thread-D ──> exchange() ─┘  paired — separate swap
```

### Happens-Before

The exchange establishes a symmetric happens-before relationship. Everything thread A does before calling `exchange()` is visible to thread B after `exchange()` returns. Everything thread B does before calling `exchange()` is visible to thread A after `exchange()` returns. This is stronger than passing the data through a volatile field and requires no additional synchronization.

### Double-Buffering Use Case

```
Filler:   [fill buffer B1] ──> exchange(B1) ──> [fill buffer B2] ──> exchange(B2)
                                      |                                     |
                              receives empty B2                    receives empty B1
                                      |                                     |
Drainer:  [drain buffer B1] <─────── │ ────────────────────────────────────┘
          exchange(empty B1) ──> receives B2 ──> [drain B2] ──> exchange(empty B2)
```

### Pipeline Handoff

Adjacent stages in a processing pipeline can use an `Exchanger` to hand off work items without a queue. Stage 1 produces an item, stage 2 presents its previously received item (now processed) and receives the new one. This creates zero-copy handoff with natural backpressure: stage 1 blocks until stage 2 is ready to receive.

## Code Snippet

A filler thread fills a list, swaps with a drainer thread, and the drainer processes the list. The exchange repeats four times, with each thread handing back an empty list in exchange for a full one.

```java
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Exchanger;

public class DoubleBufferDemo {
    private static final int ROUNDS  = 4;
    private static final int ITEMS   = 5;

    public static void main(String[] args) throws InterruptedException {
        Exchanger<List<Integer>> exchanger = new Exchanger<>();

        Thread filler = new Thread(() -> {
            String name = Thread.currentThread().getName();
            List<Integer> buffer = new ArrayList<>();
            try {
                for (int round = 1; round <= ROUNDS; round++) {
                    buffer.clear();
                    for (int i = 1; i <= ITEMS; i++) buffer.add(round * 10 + i);
                    System.out.println("[" + name + "] filled: " + buffer);
                    buffer = exchanger.exchange(buffer);
                    System.out.println("[" + name + "] received back: " + buffer
                        + " (size=" + buffer.size() + ", should be empty)");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "filler");

        Thread drainer = new Thread(() -> {
            String name = Thread.currentThread().getName();
            List<Integer> buffer = new ArrayList<>();
            try {
                for (int round = 1; round <= ROUNDS; round++) {
                    buffer = exchanger.exchange(buffer);
                    int sum = buffer.stream().mapToInt(Integer::intValue).sum();
                    System.out.println("[" + name + "] drained: " + buffer
                        + " sum=" + sum);
                    buffer = new ArrayList<>();  // hand back a fresh empty list
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "drainer");

        filler.start();
        drainer.start();
        filler.join();
        drainer.join();
        System.out.println("Exchange complete");
    }
}
```

## Gotchas

`Exchanger` is strictly two-party. Using one `Exchanger` for more than two threads does not distribute work evenly; it creates arbitrary pairings based on arrival order and leaves any unpaired thread blocked indefinitely if there is an odd number of callers. For fan-out or broadcast patterns, use `CountDownLatch` or `CyclicBarrier` instead.

If one partner never arrives — because it crashed, threw an exception before calling `exchange()`, or was slow — the other thread blocks forever in the unbounded `exchange()`. Always use the timed form `exchange(V, timeout, unit)` in production code. Set the timeout generously enough to accommodate normal slowdowns but short enough to detect genuine failures.

The object reference handed to `exchange()` is shared with the partner thread the moment the exchange completes. Modifying the exchanged object after handing it off — while the partner may be reading it — is a data race. Treat the exchanged object as transferred: after calling `exchange()`, do not read from or write to the object you passed in. Only access the object you received back.

`TimeoutException` from `exchange(V, timeout, unit)` leaves the state of both threads ambiguous. The thread that timed out should not assume the exchange did not occur — there is a narrow race window in which the partner arrived at the exchanger at the same moment the timeout was measured. The safest recovery is to treat the timed-out thread's data as potentially lost and restart the protocol from a known good state.

`Exchanger` does not support one-to-many or broadcast patterns. Each exchange involves exactly one pair of threads and one object swap. If you need to distribute data from one producer to multiple consumers, or collect results from multiple producers, use a `BlockingQueue`, `CyclicBarrier` with a barrier action, or `Phaser` instead.

The order in which the two threads arrive at `exchange()` is irrelevant to the outcome. Whichever thread arrives first simply blocks and waits. There is no notion of "first" or "second" that changes the returned value or the happens-before semantics — both threads present their object and receive the partner's object symmetrically.
