# Parallel Streams

## Overview

Parallel streams (Java 8+) distribute stream element processing across `ForkJoinPool.commonPool()` threads automatically, requiring no explicit thread management from the caller. The framework handles task splitting, work-stealing, and result combining transparently. For CPU-intensive, stateless, unordered operations on large datasets with good source splittability, parallel streams can provide near-linear speedup proportional to the number of available cores.

However, parallel streams introduce correctness and performance pitfalls that make them incorrect or slower in a surprising number of common scenarios. The most dangerous pitfall is shared mutable state: lambdas that write to a non-thread-safe collection from multiple threads simultaneously cause data races, resulting in silently dropped elements or runtime exceptions. The most common performance pitfall is using parallel streams on small datasets or IO-bound operations, where the overhead of task splitting and coordination exceeds any benefit from parallelism.

The decision to use a parallel stream should be driven by measurement, not assumption. Amdahl's law limits the achievable speedup to the fraction of the work that is actually parallelizable. Sequential overhead — boxing, source splitting, result combining — must be subtracted from the parallel portion. For the typical enterprise application processing database result sets, HTTP responses, or collections of a few thousand elements, sequential streams are almost always faster and simpler.

## Key Concepts

### Enabling Parallelism

Call `collection.parallelStream()` to obtain a parallel stream from a collection, or call `.parallel()` on any existing stream to switch it to parallel mode. Call `.sequential()` to switch back. The last call to `parallel()` or `sequential()` in a pipeline wins and applies to the entire pipeline — intermediate stages do not independently choose parallel or sequential execution.

```java
List<Integer> list = List.of(1, 2, 3, 4, 5);
list.parallelStream().map(x -> x * 2).collect(Collectors.toList());
list.stream().parallel().mapToInt(x -> x).sum();
```

### When Parallel Streams Help

Four conditions must hold simultaneously for a parallel stream to outperform a sequential one. The operation must be CPU-intensive: IO-bound operations (network, disk) do not benefit from more threads because the bottleneck is the IO subsystem, not the CPU. The operation must be stateless: each element must be processable independently without reading or writing shared mutable state. The source must be efficiently splittable: `ArrayList`, arrays, and `IntStream.range()` split in O(1); `LinkedList` and IO-based sources split poorly. The dataset must be large enough to overcome the parallelism overhead, which is typically at least tens of thousands of elements for simple operations.

### Shared Mutable State

The most critical correctness rule for parallel streams: lambdas in a parallel pipeline must not write to shared mutable state. Accumulating results into a non-thread-safe `ArrayList` from a `forEach` call runs on multiple threads simultaneously, causing races: elements are dropped, indices are overwritten, or an `ArrayIndexOutOfBoundsException` is thrown. The correct patterns are terminal operations that handle aggregation internally: `collect(Collectors.toList())`, `reduce()`, `mapToLong().sum()`, or `collect(Collectors.groupingByConcurrent())`.

```
WRONG:                               CORRECT:
List<X> result = new ArrayList<>();  List<X> result =
stream.parallel()                      stream.parallel()
      .forEach(result::add); // race   .collect(Collectors.toList());
```

### Stateful Intermediate Operations

`sorted()`, `distinct()`, and `limit()` are stateful: they require knowledge of multiple elements to produce their output. On a parallel stream, these operations must gather elements from all parallel threads, coordinate across them, and then re-establish order for downstream operations. This coordination is expensive and often negates the benefit of parallelism. On unordered parallel streams (e.g., after calling `.unordered()`), `distinct()` is cheaper because it does not need to preserve encounter order.

### Encounter Order

For ordered sources (lists, arrays), a sequential stream preserves encounter order — elements are processed and emitted in their original sequence. A parallel stream may process elements out of order internally but re-establishes order at ordered terminal operations like `collect(Collectors.toList())`. `forEachOrdered()` forces ordered output, which serializes the output phase and reduces throughput. `forEach()` makes no ordering guarantee and is faster for parallel streams. `findFirst()` on an ordered parallel stream is expensive because the framework must find the first element in encounter order, potentially discarding earlier-completing results from later positions; `findAny()` is faster.

### Overhead

Parallel stream overhead consists of: source splitting (dividing the data into sub-ranges), task scheduling in `ForkJoinPool.commonPool()` (queue operations, work-stealing), and result combining (merging partial results from each thread). For a simple `sum()` of a list of 100 integers, this overhead dominates: sequential execution is faster by an order of magnitude. The crossover point where parallel exceeds sequential depends on the cost of the operation per element and the number of available cores.

### `ForkJoinPool` Isolation

By default, parallel streams use `ForkJoinPool.commonPool()`. To use a custom pool — for example, to limit the parallelism used by one stream or to isolate it from other commonPool users — wrap the stream operation in a task submitted to the custom pool:

```java
ForkJoinPool pool = new ForkJoinPool(2);
long result = pool.submit(() ->
    list.parallelStream().mapToLong(x -> x).sum()
).get();
pool.shutdown();
```

This is a workaround that relies on the implementation detail that parallel streams use the pool of the thread that initiates the terminal operation. It is not officially documented behavior.

## Gotchas

**Using a shared non-thread-safe collection as the target of `forEach` in a parallel stream causes a data race.** Multiple threads call `ArrayList.add()` simultaneously, which overwrites elements, leaves null gaps in the backing array, or throws `ArrayIndexOutOfBoundsException`. Elements are silently dropped with no exception in many cases. Always use `collect()` with a `Collector` that handles thread safety internally, such as `Collectors.toList()`, `Collectors.toUnmodifiableList()`, or `Collectors.toConcurrentMap()`.

**Parallel streams on small datasets are slower than sequential.** The overhead of splitting the source, scheduling tasks in the `ForkJoinPool`, and combining results is not free. For a list of fewer than a few thousand elements with a simple transformation, sequential streams are consistently faster. Benchmark with the actual data size and operation before enabling parallelism.

**`sorted()` on a parallel stream buffers all elements before sorting.** The stream must collect all elements from parallel threads into an array, sort it using `Arrays.sort()` (sequentially), and then emit elements in order to downstream operations. The sort itself is not parallel. This adds memory allocation and synchronization overhead that rarely pays off. If the final result does not require ordering (e.g., it is collected into a set), remove the `.sorted()` call entirely.

**Stateful lambdas that capture an `AtomicInteger` produce correct results but serialize execution at the counter.** A pattern like `stream.parallel().map(x -> counter.incrementAndGet() + ":" + x)` is functionally correct but causes all threads to contend on the same `AtomicInteger`. The contention serializes the map operation, eliminating the benefit of parallelism. The result is correct but no faster — and potentially slower — than sequential.

**Parallel streams on IO-bound operations do not benefit from parallelism and harm the `commonPool`.** If each element processing involves a network call or database query, the parallel threads spend most of their time blocked waiting for IO, not computing. The ForkJoinPool workers are occupied but idle, starving other parallel streams or `CompletableFuture` operations that share `commonPool`. For IO-bound fan-out, use an `ExecutorService` with a thread pool sized to the IO concurrency ceiling.

**`limit(n)` on a parallel stream is correct but expensive.** Enforcing an element count across multiple parallel threads requires coordination: all threads must check a shared counter before emitting each element, and all threads must be stopped once `n` is reached. This coordination overhead frequently makes `parallel().limit(n)` slower than `sequential().limit(n)`. For the common pattern of taking the first `n` elements of a large stream, prefer `stream().limit(n)` (sequential) unless profiling confirms a parallel benefit.
