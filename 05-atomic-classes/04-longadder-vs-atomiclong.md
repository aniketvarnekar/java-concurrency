# 04 — LongAdder vs AtomicLong

## Overview

`AtomicLong` stores its value in a single `volatile long` field. Every increment, decrement, or add operation performs a CAS on that one memory location. Under low or moderate contention this is fast because each thread's CAS usually succeeds on the first try. Under high contention — dozens of threads all trying to update the same counter simultaneously — most CAS attempts fail, threads spin and retry, and each retry causes a cache coherence message that invalidates the cache line on every other core. Throughput degrades roughly as the square of the number of contending threads.

`LongAdder`, introduced in Java 8, solves this by distributing the counter across an array of independently updatable cells. The class extends `Striped64`, an internal base class that manages a `base` field and a `cells` array. When a thread finds low contention, it updates `base` directly. When it detects contention (a CAS failure), it is assigned to a cell in the array and updates that cell instead. Because different threads write to different cells, cache invalidation traffic is dramatically reduced.

The tradeoff is that reading the current value requires summing `base` and all cells. This sum is not a snapshot of a single instant in time — threads may be modifying cells during the sum computation. `LongAdder.sum()` is therefore eventually consistent: it returns an approximation that reflects some valid state of the counter, but not necessarily the exact current state. This makes `LongAdder` ideal for high-throughput counters and metrics collectors where the exact current value is not needed at every moment.

`LongAccumulator` generalizes `LongAdder` by allowing an arbitrary accumulator function. Instead of summing cells, it combines them using whatever `LongBinaryOperator` you supply — such as `Math::max`, `Math::min`, or a bitwise OR. The same cell-striping strategy applies, and the same limitation on `get()` consistency applies.

## Key Concepts

### AtomicLong Under Contention

All threads compete for one cache line. Each CAS failure means the thread must re-read the value, which fetches the cache line that is now owned by another core, then retry. The retry rate rises with thread count. At 16 threads, a typical increment workload shows saturation: additional threads add latency without adding throughput.

```
Thread count vs throughput for increment-only workload (illustrative):

Threads:    1     2     4     8    16    32
AtomicLong: 100%  95%   80%   55%  35%   20%
LongAdder:  100%  105%  115%  130% 140%  145%   (approximate relative values)
```

The degradation in `AtomicLong` is caused by the "hot cache line" problem: the single cell containing the counter is invalidated and retransferred between cores on every successful write.

### LongAdder Cell Striping

`Striped64` (the parent of `LongAdder`) maintains:

- A `volatile long base` field used under zero contention.
- A `volatile Cell[] cells` array, lazily initialized. Each `Cell` holds a `volatile long` value and is padded to occupy a full cache line (64 bytes on most hardware) to prevent false sharing between cells.

When a thread calls `add(x)`:

1. If `cells` is null, attempt a CAS on `base`. If it succeeds, done.
2. If the CAS on `base` fails (contention detected), or if `cells` is already initialized, identify the cell for this thread using a thread-local probe (a hash of the thread's ID, rehashed on collision).
3. CAS on the identified cell. If the cell's CAS fails, the probe is rehashed (the thread is reassigned to a different cell) to spread load more evenly.
4. If no suitable cell exists yet, expand the `cells` array (up to the next power of two that is >= the number of CPUs).

```
Low contention:
  base CAS succeeds -> done

High contention:
  thread-0 -> cells[0]
  thread-1 -> cells[1]
  thread-2 -> cells[2]
  thread-3 -> cells[3]

sum() = base + cells[0] + cells[1] + cells[2] + cells[3]
```

### sum()

`sum()` iterates over `base` and all cells and adds them. The iteration is not synchronized, so a cell may be incremented between reads. The result is consistent with some state of the counter, but it is not guaranteed to reflect the exact count at any single point in time.

```java
LongAdder counter = new LongAdder();

// Increment from multiple threads...

long total = counter.sum();   // approximate snapshot
counter.reset();              // reset all cells and base to 0 (not atomic with sum())
long snapshot = counter.sumThenReset(); // sum() then reset(), also not atomic
```

### LongAccumulator

`LongAccumulator` takes two constructor arguments: a `LongBinaryOperator` and an identity value. The identity value is the neutral element for the operator (0 for sum, `Long.MIN_VALUE` for max, `Long.MAX_VALUE` for min). Each cell is initialized to the identity, and the `get()` result combines all cells using the operator.

```java
// Track the maximum value seen across all threads
LongAccumulator maxSeen = new LongAccumulator(Math::max, Long.MIN_VALUE);

maxSeen.accumulate(42L);
maxSeen.accumulate(100L);
maxSeen.accumulate(7L);

long max = maxSeen.get(); // 100
```

The operator must be associative and commutative because cells are combined in arbitrary order.

### When to Use Each

| Criterion | AtomicLong | LongAdder |
|---|---|---|
| Precise current value via `get()` | Yes, always exact | No, `sum()` is approximate |
| `compareAndSet` support | Yes | No |
| Throughput under high contention | Degrades | Scales |
| Memory footprint | 1 long + object header | 1 long + N cells (N <= CPU count) |
| Decrement / subtract | Yes, `decrementAndGet`, `addAndGet(-n)` | Yes, `add(-n)` |
| Reset to zero | Yes, `set(0)` | Yes, but `reset()` is not atomic |
| Suitable for | Sequence numbers, precise flags, CAS protocols | Metrics, event counters, rate tracking |

Use `AtomicLong` when you need to read the current value with precision, need `compareAndSet`, or when contention is low. Use `LongAdder` when throughput matters and you can tolerate an approximate sum.

## Gotchas

### sum() is not a consistent snapshot

If threads are actively incrementing while `sum()` is running, the returned value may be higher or lower than the true count at any single instant. Do not use `LongAdder.sum()` as a gate for synchronization decisions (such as "stop when count reaches N"). Use `AtomicLong` for any logic that depends on an exact value.

### reset() is not atomic with respect to increment()

Calling `reset()` sets `base` and each cell to zero, but it does so in separate CAS operations. An increment arriving between two cell resets will be lost from the counter's perspective after the reset completes. `sumThenReset()` has the same issue. If you need a consistent snapshot followed by a reset, you need external synchronization.

### LongAdder has no compareAndSet

There is no `compareAndSet`, `getAndIncrement`, or any operation that returns the old value. The only read method is `sum()`, which is approximate. If your algorithm needs to CAS on the counter value — for example, to implement a semaphore or a rate limiter — `LongAdder` is the wrong tool.

### LongAdder uses more memory than AtomicLong

Each `Cell` in the `cells` array is padded to a full cache line (typically 64 bytes) to prevent false sharing. On a machine with 32 hardware threads, the `cells` array can grow to 32 cells, consuming 32 x 64 = 2048 bytes plus overhead. For most applications this is negligible, but if you are creating large numbers of `LongAdder` instances (for example, a per-key counter map with millions of keys), the memory overhead is significant compared to storing a plain `long` or using `AtomicLong`.

### Cell initialization and resizing are not free

The first time contention is detected, `Striped64` allocates and initializes the `cells` array. This is a one-time cost, but it involves a `synchronized` block. Similarly, when the cells array needs to grow, it is resized under a lock. In a workload that goes through bursts of high contention, this initialization overhead may appear as occasional latency spikes. Warming up the counter before the critical path can mitigate this.

### LongAccumulator's operator must be associative and commutative

`LongAccumulator` combines cells in an arbitrary order determined by the internal striping. If your accumulator function is not associative (meaning `f(a, f(b, c)) != f(f(a, b), c)`) or not commutative (meaning `f(a, b) != f(b, a)`), the result is undefined. Subtraction, for example, is neither associative nor commutative and must not be used as the accumulator function.
