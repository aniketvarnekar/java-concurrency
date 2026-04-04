# Common Interview Questions

## Overview

This file covers the most frequently asked Java concurrency interview questions, ranging from foundational thread concepts through modern features like virtual threads and structured concurrency. Each answer is written to demonstrate technical precision rather than surface familiarity. The questions are ordered roughly from foundational to advanced.

## Questions and Answers

### Q1: What is a race condition? What are the two main patterns?

A race condition occurs when the correctness of a program depends on the relative timing or interleaving of two or more threads accessing shared mutable state. The two canonical patterns are check-then-act and read-modify-write. In check-then-act, a thread checks a condition and then acts on it, but another thread modifies the state between the check and the act — the classic example is lazy initialization where two threads both observe `instance == null` and both proceed to initialize. In read-modify-write, a thread reads a shared value, computes a new value, and writes it back, but another thread does the same concurrently, causing one thread's update to overwrite the other's — the classic example is `i++`, which is three operations (read, add 1, write) that can be interleaved with a concurrent `i++`, causing one increment to be silently lost.

### Q2: What does synchronized guarantee?

`synchronized` provides two guarantees: mutual exclusion and visibility. Mutual exclusion means only one thread can hold a given monitor at a time; all other threads attempting to acquire the same monitor block until it is released. Visibility means that when a thread exits a synchronized block, all writes made while holding the monitor are flushed to main memory, and when another thread enters a synchronized block on the same monitor, it reads fresh values from main memory — establishing a happens-before edge between the unlock and the subsequent lock. `synchronized` is also reentrant: a thread that already holds a monitor can re-acquire it without blocking, which prevents self-deadlock when one synchronized method calls another on the same instance.

### Q3: What does volatile guarantee, and what does it NOT guarantee?

`volatile` guarantees visibility for a single variable: a write to a volatile field is immediately visible to all threads that subsequently read that same field, without requiring any lock. It also prevents certain compiler and CPU instruction reorderings around the volatile read and write. What `volatile` does not guarantee is atomicity: `volatile int x; x++` is still a non-atomic read-modify-write — the read, increment, and write are three separate steps that can be interleaved with another thread. It also does not protect compound actions such as check-then-act on the volatile variable itself. For atomic updates, use `AtomicInteger` or `synchronized`.

### Q4: What are the four conditions for deadlock, and how do you prevent it?

The four necessary conditions (Coffman conditions) are: mutual exclusion (threads hold resources exclusively), hold-and-wait (a thread holds one resource while waiting for another), no preemption (held resources cannot be forcibly taken), and circular wait (a cycle of threads each waiting for the next). Eliminating any one condition prevents deadlock. The most practical strategies are lock ordering (always acquire multiple locks in the same global order, breaking circular wait — see `02-synchronization/05-deadlock-livelock-starvation.md`), timed tryLock (use `ReentrantLock.tryLock(timeout, unit)` to detect and back off from contention), and lock scope reduction (hold locks for the minimum time and avoid acquiring a second lock while holding the first).

### Q5: What is the difference between Object.wait() and Thread.sleep()?

`Object.wait()` releases the monitor lock held by the calling thread and suspends it until another thread calls `notify()` or `notifyAll()` on the same object — the thread must already hold the object's monitor or `IllegalMonitorStateException` is thrown. `Thread.sleep(ms)` suspends the thread for a fixed duration without releasing any locks it holds. `wait()` is a coordination mechanism that allows threads to signal each other; `sleep()` is a pure timing delay. Both throw `InterruptedException` when the thread is interrupted while waiting.

### Q6: What is the Java Memory Model and why does it exist?

The JMM (Java Language Specification §17) is a formal specification that defines which values a read of a shared variable is permitted to return. It exists because modern CPUs and compilers reorder instructions and buffer memory writes for performance — without a specification, the same Java program could produce different (and incorrect) results on different hardware. The JMM provides a contract: if a developer follows the synchronization rules (use `synchronized`, `volatile`, or explicit locks), the JVM and CPU must ensure that the resulting memory visibility behavior conforms to the model. The happens-before relation is the JMM's central concept — if action A happens-before action B, all effects of A are guaranteed visible to B.

### Q7: What is happens-before? Name three rules.

Happens-before is the JMM's formal ordering relation. If action A happens-before action B, all writes performed by A are guaranteed visible to B. Happens-before is not a temporal ordering — two actions can have a happens-before relationship without A occurring earlier in wall time. Three rules: (1) Program Order Rule — within a single thread, each action happens-before the next action in program order; (2) Monitor Lock Rule — an unlock of a monitor happens-before every subsequent lock on the same monitor; (3) Volatile Variable Rule — a write to a volatile field happens-before every subsequent read of that same field. Transitivity applies: if A happens-before B and B happens-before C, then A happens-before C.

### Q8: What is the difference between Callable and Runnable?

`Runnable` has a single method `void run()` that returns no value and cannot declare checked exceptions. `Callable<V>` has a single method `V call() throws Exception` that returns a typed value and can throw any checked exception. `Runnable` can be used with `Thread`, `Executor.execute()`, and `ExecutorService.submit()`. `Callable` is used only with `ExecutorService.submit()`, which returns a `Future<V>`. When a `Callable` throws, the exception is captured, wrapped in an `ExecutionException`, and re-thrown by `Future.get()`, allowing checked exceptions to propagate cleanly from asynchronous tasks.

### Q9: What does Future.get() do and what are its failure modes?

`Future.get()` blocks the calling thread indefinitely until the computation completes, then returns the result. Its four failure modes are: `ExecutionException` — the task's `call()` method threw an exception, which is wrapped as the cause; `CancellationException` — the task was cancelled via `future.cancel()` before or during execution; `InterruptedException` — the calling thread was interrupted while waiting in `get()`; and `TimeoutException` (only from the timed variant `get(timeout, unit)`) — the computation did not finish within the specified time. `ExecutionException` is the most commonly mishandled: always call `getCause()` to retrieve the original exception, not `getMessage()` on the wrapper.

### Q10: What is CompletableFuture and how does thenCompose differ from thenApply?

`CompletableFuture<T>` is a `Future` that can be explicitly completed and supports a rich set of composition methods for building non-blocking asynchronous pipelines. `thenApply(Function<T,U>)` applies a synchronous transformation to the result and returns a `CompletableFuture<U>` holding the transformed value directly. `thenCompose(Function<T, CompletableFuture<U>>)` is the flat-map equivalent: the function itself returns a `CompletableFuture<U>`, and `thenCompose` unwraps the nesting, returning `CompletableFuture<U>` rather than `CompletableFuture<CompletableFuture<U>>`. Use `thenApply` for synchronous transformations and `thenCompose` when the next step is itself asynchronous — for example, chaining a database fetch after an HTTP response.

### Q11: What is the difference between newFixedThreadPool and newCachedThreadPool?

`newFixedThreadPool(n)` creates a pool with exactly `n` threads, a zero keepAlive time, and an unbounded `LinkedBlockingQueue`. The pool never rejects tasks, but under sustained overload the queue grows without bound, eventually causing `OutOfMemoryError`. `newCachedThreadPool()` creates a pool with 0 core threads, `Integer.MAX_VALUE` maximum threads, a `SynchronousQueue` (zero capacity), and a 60-second keepAlive. Every submitted task either uses an idle thread or creates a new one. Under burst load it can spawn thousands of OS threads, causing OS-level resource exhaustion. Fixed pools are appropriate when concurrency must be bounded; cached pools are appropriate for workloads with many short-lived tasks and variable arrival rates.

### Q12: What happens in ThreadPoolExecutor when the task queue is full?

When all core threads are busy and the work queue is full, `ThreadPoolExecutor` attempts to create a new thread up to `maximumPoolSize`. If the thread count is already at the maximum, the pool invokes its `RejectedExecutionHandler`. The four built-in policies are: `AbortPolicy` (default — throw `RejectedExecutionException`), `CallerRunsPolicy` (the submitting thread executes the task inline, providing natural backpressure that slows the producer), `DiscardPolicy` (silently discard the task), and `DiscardOldestPolicy` (discard the oldest queued task and retry submitting the new one). Note that with an unbounded queue (`LinkedBlockingQueue`), the queue never fills and `maximumPoolSize` is never reached regardless of its value.

### Q13: What is ConcurrentHashMap's advantage over Hashtable?

`Hashtable` synchronizes every method on the instance, meaning only one thread can perform any operation at any time — all readers contend with all writers. `ConcurrentHashMap` (Java 8+) uses node-level locking: write operations `synchronized` only the affected hash bucket, and read operations are lock-free because `Node` values are stored as `volatile` fields. Reads are fully concurrent, and writes to different buckets proceed simultaneously. `ConcurrentHashMap` also provides atomic compound operations — `computeIfAbsent`, `compute`, `merge` — that cannot be safely replicated with `Hashtable` even with external synchronization. Additionally, `ConcurrentHashMap` prohibits null keys and values to avoid ambiguity in concurrent access, while `Hashtable` allows neither but for different historical reasons.

### Q14: How does CopyOnWriteArrayList achieve thread safety?

Every mutation — `add`, `set`, `remove` — creates a complete copy of the backing array, applies the change to the copy, and then atomically publishes the new array via a `volatile` reference. Readers always hold a reference to the current array snapshot, which is never modified after publication, so no locking is required for reads. Iterators capture the array reference at creation time and traverse that snapshot; they never see subsequent writes and never throw `ConcurrentModificationException`. The trade-off is that writes are O(n) due to the full array copy, making `CopyOnWriteArrayList` suitable only for collections that are read frequently and modified rarely, such as event listener registries or configuration snapshots.

### Q15: What is the difference between CountDownLatch and CyclicBarrier?

`CountDownLatch` is initialized with a count N; any number of threads can call `countDown()` (decrementing N) and any number can call `await()` (blocking until N reaches zero). Once at zero it cannot be reset — it is one-shot. `CyclicBarrier` requires exactly N parties to all call `await()`; when the Nth party arrives, all are released simultaneously, an optional barrier action runs, and the barrier resets automatically for the next cycle. Key differences: `CountDownLatch` supports asymmetric usage (M threads counting down, K threads waiting, where M ≠ K); `CyclicBarrier` requires exactly N of the same parties. `CyclicBarrier` is reusable across phases; `CountDownLatch` is not. For repeated multi-phase parallel algorithms, `CyclicBarrier` or `Phaser` is appropriate; for one-shot coordination, `CountDownLatch`.

### Q16: What is a Semaphore and what is a binary semaphore?

A `Semaphore` maintains a count of permits. `acquire()` blocks until a permit is available and decrements the count; `release()` increments the count. Multiple threads can hold permits simultaneously up to the initial count, making it suitable for throttling concurrent access to a bounded resource pool (for example, a pool of 5 database connections). A binary semaphore (permits = 1) behaves like a mutual exclusion lock but with one important distinction from `synchronized`: the permit can be released by a different thread than the one that acquired it. This makes a binary semaphore suitable for signaling — one thread acquires (closes the gate), another releases (opens it) — which `synchronized` cannot express.

### Q17: What is the ABA problem and how do you solve it?

The ABA problem occurs in lock-free algorithms using CAS. Thread 1 reads a value A. Thread 2 changes A → B → A (the memory location holds the same value again). Thread 1's `CAS(expected=A, new=X)` succeeds because the value appears unchanged, even though intermediate changes occurred. In pointer-based lock-free structures, this can cause corruption — the node previously at address A may have been freed and reallocated with different content. In Java, `AtomicStampedReference<V>` solves this by pairing the reference with an integer stamp (version counter); `compareAndSet` checks both the reference and the stamp, so A-with-stamp-1 is distinct from A-with-stamp-3 even if the reference is identical. `AtomicMarkableReference` provides a boolean flag instead of a version counter when only a one-bit distinction is needed.

### Q18: When would you prefer LongAdder over AtomicLong?

Prefer `LongAdder` for high-contention counters that are read infrequently, such as request counters, metrics accumulators, or rate counters. `LongAdder` maintains a base value and a dynamically expanded array of cells; updates are distributed across cells based on thread identity, drastically reducing CAS contention compared to a single shared `AtomicLong`. Under high contention, `LongAdder` throughput can be several times higher. The trade-off is that `sum()` adds base plus all cells and is not consistent across concurrent updates — it reflects an approximate value at the moment of the call. Prefer `AtomicLong` when you need `compareAndSet`, when you need a precise snapshot of the current value, or when contention is low to moderate (where the overhead difference is negligible).

### Q19: What is a virtual thread and how does it differ from a platform thread?

A virtual thread (Java 21+, Project Loom) is a lightweight thread managed by the JVM rather than the OS. Platform threads map 1:1 to OS threads, have a 1MB default OS stack, cost approximately 1ms to create, and are limited to roughly 10,000 per JVM before degrading. Virtual threads are M:N mapped to a small pool of platform carrier threads; they use heap-allocated stacks starting at a few hundred bytes and grow dynamically; they cost approximately 1μs to create; and millions can exist simultaneously. When a virtual thread blocks on IO, sleep, or a lock (via `LockSupport.park()`), it unmounts from its carrier thread, which is freed to execute another virtual thread. Virtual threads are designed for high-concurrency IO-bound workloads.

### Q20: What is virtual thread pinning and what causes it?

A virtual thread is pinned when the JVM cannot unmount it from its carrier thread despite the thread being in a blocking state. Pinning wastes a carrier thread for the duration of the block, defeating the scalability benefit of virtual threads. The two causes are: (1) the virtual thread is inside a `synchronized` block or `synchronized` method when it blocks — the JVM cannot unmount a thread holding a monitor lock; (2) the virtual thread is executing native code (JNI). The fix for `synchronized` is to replace it with `ReentrantLock`, whose blocking uses `LockSupport.park()` which the JVM recognizes as an unmount point. Pinning can be detected with the JVM flag `-Djdk.tracePinnedThreads=short` or via JFR's `jdk.VirtualThreadPinned` event.

### Q21: What is StructuredTaskScope.ShutdownOnFailure?

`StructuredTaskScope.ShutdownOnFailure` is a completion policy for structured concurrency (Java 21+, `java.util.concurrent`). Multiple subtasks are forked within the scope using `scope.fork(Callable)`. If any subtask throws an exception, the scope shuts down: it sends an interrupt to all remaining subtasks and waits for them to finish. After `scope.join()`, calling `scope.throwIfFailed()` rethrows the first subtask failure wrapped in an `ExecutionException`. This models the all-or-nothing fan-out pattern: if fetching user data and order data in parallel, a failure in either should cancel both and propagate the error to the caller without requiring manual future tracking or explicit cancellation logic.

### Q22: What is the Fork/Join work-stealing algorithm?

In a `ForkJoinPool`, each worker thread maintains a double-ended deque (deque) of tasks. The worker pushes newly forked subtasks onto the front of its own deque and pops from the front when it needs work (LIFO order). When a worker's deque is empty, it steals tasks from the back of another worker's deque (FIFO order). This design minimizes contention: the owner accesses the front exclusively, and thieves access the back — they rarely meet in the middle. LIFO local execution favors recently forked (smaller) subtasks, exploiting cache locality. FIFO stealing favors older (larger, not-yet-split) tasks, maximizing the amount of work stolen per steal operation and keeping all workers productive.

### Q23: What is a ThreadLocal memory leak and when does it occur?

A ThreadLocal memory leak occurs in thread pools when a thread sets a `ThreadLocal` value during a request and does not call `remove()` before the request ends. Pool threads live for the lifetime of the pool, so the `ThreadLocal` entry persists in the thread's `ThreadLocalMap` across all future requests processed by that thread. If the value references a large object (a classloader, a cache, a byte array), that object is not eligible for GC for as long as the thread lives. In application servers, this can cause classloader leaks on redeployment — the old deployment's classloader is retained by a pool thread's `ThreadLocal` entry, preventing GC of all classes from the old deployment. The fix is to always call `ThreadLocal.remove()` in a `finally` block at the end of request processing.

### Q24: What is false sharing and how does cache line padding address it?

False sharing occurs when two threads write to different variables that occupy the same CPU cache line (typically 64 bytes). When Thread 1 writes to its variable, the CPU must invalidate Thread 2's cached copy of the entire 64-byte cache line — even though Thread 2's variable is different and was not touched. This causes constant cache coherence traffic between CPU cores, effectively serializing what should be independent writes. Cache line padding places dummy fields around a hot variable so that it occupies its own cache line. For a `long` field, 7 additional `long` padding fields (56 bytes) plus the 8-byte `long` itself = 64 bytes fills one cache line. The LMAX Disruptor and Java's `Striped64` (base class of `LongAdder`) both use this technique. Java 8+ provides `@Contended` (requires `-XX:-RestrictContended`) as a cleaner alternative.

### Q25: What are the three progress guarantees in lock-free programming, from strongest to weakest?

Wait-free is the strongest guarantee: every thread completes its operation in a bounded number of steps regardless of what other threads do — no thread can be delayed indefinitely by others. Practical examples include reading a `volatile` field. Lock-free is weaker: the system as a whole always makes progress — at least one thread completes in a finite number of steps — but individual threads may retry (spin on CAS failure) for an unbounded number of iterations. `AtomicInteger.incrementAndGet()` is lock-free because a CAS failure means another thread succeeded. Obstruction-free is the weakest useful guarantee: a thread makes progress only when it runs in isolation with no conflicting concurrent operations. Blocking algorithms (using mutexes) provide no progress guarantee — a thread preempted while holding a lock can stall all dependent threads indefinitely.
