# Java Concurrency Cheatsheet

## Overview

Quick-reference tables for synchronization primitives, atomic classes, thread pool configurations, synchronization aids, concurrent collection characteristics, key thread method behaviors, and common jstack diagnostic signatures.

---

## Synchronization Primitives

| Primitive | Mutual Exclusion | Visibility | Reentrant | Multiple Wait Sets | Virtual Thread Safe | Notes |
|---|---|---|---|---|---|---|
| `synchronized` | Yes | Yes (both sides) | Yes | No (one per monitor) | No (pins carrier) | Simplest; lexical scope |
| `volatile` | No | Yes (single field) | N/A | N/A | Yes | No compound atomicity |
| `ReentrantLock` | Yes | Yes | Yes | Yes (`newCondition()`) | Yes | tryLock, lockInterruptibly |
| `ReentrantReadWriteLock` | Write: exclusive; Read: shared | Yes | Yes | Yes | Yes | Read-heavy optimization |
| `StampedLock` | Write: exclusive; Read: shared + optimistic | Yes | No | No | Yes | Not reentrant; stamp-based |

---

## Atomic Classes

| Class | Operation | Atomic Per Call | CAS Support | Notes |
|---|---|---|---|---|
| `AtomicInteger` / `AtomicLong` | get, set, getAndIncrement, addAndGet, updateAndGet | Yes | Yes (`compareAndSet`) | Lock-free |
| `AtomicBoolean` | get, set, compareAndSet | Yes | Yes | One-bit flag guard |
| `AtomicReference<V>` | get, set, compareAndSet, getAndUpdate | Yes (reference) | Yes | ABA risk |
| `AtomicStampedReference<V>` | compareAndSet(ref, stamp, newRef, newStamp) | Yes | Yes | Fixes ABA |
| `AtomicMarkableReference<V>` | compareAndSet(ref, mark, newRef, newMark) | Yes | Yes | Boolean flag + ref |
| `LongAdder` | add, sum, reset | add: yes; sum: approximate | No CAS | High-contention counter |
| `LongAccumulator` | accumulate(x), get | accumulate: yes | No CAS | Custom binary operator |
| `AtomicIntegerArray` | get(i), set(i,v), compareAndSet(i,e,u) | Yes (per element) | Yes | Volatile element access |

---

## Thread Pool Types

| Factory Method | Core | Max | Queue | KeepAlive | Primary Risk |
|---|---|---|---|---|---|
| `newFixedThreadPool(n)` | n | n | `LinkedBlockingQueue` (unbounded) | 0 | OOM from queue buildup |
| `newCachedThreadPool()` | 0 | `MAX_INT` | `SynchronousQueue` | 60 s | Thread explosion under burst |
| `newSingleThreadExecutor()` | 1 | 1 | `LinkedBlockingQueue` (unbounded) | 0 | Single point of slowness |
| `newWorkStealingPool(p)` | 0 | p | ForkJoinPool internal | adaptive | Daemon threads; CPU tasks only |
| `newScheduledThreadPool(n)` | n | `MAX_INT` | `DelayedWorkQueue` | 0 | Exception suppresses future runs |
| `newVirtualThreadPerTaskExecutor()` | N/A | N/A | N/A | N/A | Pinning with `synchronized` |

### ThreadPoolExecutor Thread Growth Algorithm

```
New task submitted
├── running threads < corePoolSize?
│   YES → create new thread (even if idle threads exist)
├── queue not full?
│   YES → enqueue task
├── running threads < maximumPoolSize?
│   YES → create new thread
└── invoke RejectedExecutionHandler
```

| Rejection Policy | Behavior |
|---|---|
| `AbortPolicy` (default) | Throw `RejectedExecutionException` |
| `CallerRunsPolicy` | Submitting thread executes task inline (backpressure) |
| `DiscardPolicy` | Silently discard new task |
| `DiscardOldestPolicy` | Discard oldest queued task; retry new task |

---

## Synchronization Aids

| Aid | Parties | Reusable | Data Exchange | Barrier Action | Dynamic Parties | Primary Use |
|---|---|---|---|---|---|---|
| `CountDownLatch` | N waiters, M counters (M can ≠ N) | No | No | No | No | One-shot start/end gate |
| `CyclicBarrier` | Fixed N | Yes (auto-reset) | No | Yes (last arriver) | No | Multi-phase parallel algorithm |
| `Semaphore` | N concurrent (permits) | Yes | No | No | Via release/acquire | Resource pool throttle |
| `Exchanger` | Exactly 2 | Yes | Yes (symmetric swap) | No | No | Pipeline buffer handoff |
| `Phaser` | Dynamic | Yes | No | Yes (`onAdvance`) | Yes | Dynamic multi-phase barrier |

---

## Concurrent Collection Characteristics

| Collection | Thread Safety Mechanism | Null Keys | Null Values | Iterator Behavior | Best For |
|---|---|---|---|---|---|
| `HashMap` | None | Yes | Yes | Fail-fast | Single-threaded |
| `Hashtable` | Full method lock (this) | No | No | Fail-fast | Legacy only |
| `Collections.synchronizedMap(m)` | Full map lock | Depends on wrapped map | Depends | Must lock externally | Wrapping existing maps |
| `ConcurrentHashMap` | Node-level lock + volatile reads | No | No | Weakly consistent | General concurrent map |
| `CopyOnWriteArrayList` | Full array copy on write | Yes | Yes | Snapshot (no CME) | Read-heavy, rarely modified |
| `ConcurrentLinkedQueue` | CAS (Michael-Scott algorithm) | No | No | Weakly consistent | Non-blocking FIFO queue |
| `LinkedBlockingQueue` | Two separate locks (head/tail) | No | No | Weakly consistent | Producer-consumer |
| `ArrayBlockingQueue` | Single lock | No | No | Weakly consistent | Bounded producer-consumer |
| `PriorityBlockingQueue` | Single lock | No | No | Weakly consistent | Priority-ordered consumption |

---

## Key Thread Methods and Lock Behavior

| Method | Releases Lock? | Interruptible? | Notes |
|---|---|---|---|
| `Object.wait()` | Yes (releases monitor) | Yes | Must hold monitor; check predicate in `while` loop |
| `Object.wait(timeout)` | Yes | Yes | Timed; check predicate after return |
| `Thread.sleep(ms)` | No | Yes | Does not release any lock |
| `LockSupport.park()` | No (holds no lock to release) | Yes | Used by `ReentrantLock` / `Condition` internally |
| `synchronized` block entry (waiting) | N/A — acquiring | No | Cannot interrupt wait for monitor |
| `ReentrantLock.lock()` | N/A — acquiring | No | Blocks indefinitely |
| `ReentrantLock.tryLock()` | N/A | No | Returns `false` immediately if unavailable |
| `ReentrantLock.tryLock(t, u)` | N/A | Yes | Timed; returns `false` on timeout |
| `ReentrantLock.lockInterruptibly()` | N/A — acquiring | Yes | Throws `InterruptedException` if interrupted |
| `Condition.await()` | Yes (releases associated Lock) | Yes | Spurious wakeups; use `while` loop |
| `Condition.awaitUninterruptibly()` | Yes | No | Does not respond to interrupt |
| `Thread.join()` | No | Yes | Waits for target thread to terminate |
| `Thread.join(timeout)` | No | Yes | Timed; returns when timeout expires or thread dies |

---

## Common jstack Signatures

| Symptom | jstack Pattern | Diagnostic Action |
|---|---|---|
| Deadlock | `"Found one Java-level deadlock:"` section with circular `"waiting to lock"` | Fix lock ordering; use `tryLock` |
| Lock contention | Many threads `BLOCKED (on object monitor)` waiting on same `<0xABCD>` | Reduce lock scope; use `ConcurrentHashMap`, `ReadWriteLock` |
| Thread pool exhausted | All pool threads `WAITING` at `LinkedBlockingQueue.take()` or `poll()` | Check task submission rate; increase pool size or add backpressure |
| CPU burn / infinite loop | Thread `RUNNABLE` in a tight loop with no IO or lock methods in stack | Add termination condition; check `isInterrupted()` |
| IO wait | Thread `RUNNABLE` at `socketRead`, `socketWrite`, `FileInputStream.read` | Expected for IO threads; check timeout configuration |
| `Object.wait()` | Thread `WAITING` at `Object.wait()` with `"on object monitor"` | Normal if condition signalling is correct; abnormal if `notify` is never called |
| `LockSupport.park()` | Thread `WAITING` at `sun.misc.Unsafe.park()` or `jdk.internal...park` | Normal for ReentrantLock/Condition; abnormal if no unparker will arrive |
| Virtual thread pinned | `"virtual"` thread `RUNNABLE` inside `synchronized` block with no IO mount | Replace `synchronized` with `ReentrantLock` |
| GC pause causing stall | All threads `WAITING` or `BLOCKED` while one thread runs GC | Reduce allocation rate; tune GC settings |

---

## Progress Guarantees in Lock-Free Programming

| Guarantee | Strength | Definition | Java Example |
|---|---|---|---|
| Wait-free | Strongest | Every thread completes in a bounded number of steps regardless of others | Read `volatile` field |
| Lock-free | Strong | At least one thread always makes progress; others may retry | `AtomicInteger.incrementAndGet()` (CAS loop) |
| Obstruction-free | Weak | A thread makes progress when running in isolation | Some software transactional memory implementations |
| Blocking | None | No progress guarantee if lock holder is preempted | `synchronized`, `ReentrantLock` |

---

## Happens-Before Quick Reference

| Rule | Establishes HB From | To |
|---|---|---|
| Program Order | Each action in a thread | Next action in the same thread |
| Monitor Lock | `unlock` on a monitor | Any subsequent `lock` on the same monitor |
| Volatile Write | Write to a `volatile` field | Any subsequent read of that field |
| Thread Start | `Thread.start()` | Any action in the started thread |
| Thread Join | Any action in a thread | `Thread.join()` returning in the joining thread |
| Interrupt | `Thread.interrupt()` | The interrupted thread detecting the interrupt |
| Finalizer | Object constructor completion | Start of the object's `finalize()` method |
| Transitivity | A hb B, B hb C | A hb C |
