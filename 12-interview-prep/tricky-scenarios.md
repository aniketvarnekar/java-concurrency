# Tricky Scenarios

## Overview

Each scenario presents code that appears correct but contains a concurrency bug or exhibits surprising behavior. For each scenario, the problematic code is shown, the root cause is explained, a fixed version is provided, and the reason the fix works is stated.

---

### Scenario 1: Double-Checked Locking Without volatile

A common singleton implementation uses double-checked locking to avoid synchronizing on every call after initialization. The two null checks appear to ensure only one instance is created.

```java
public class Singleton {
    private static Singleton instance;   // NOT volatile

    private Singleton() { }

    public static Singleton getInstance() {
        if (instance == null) {               // first check (unsynchronized)
            synchronized (Singleton.class) {
                if (instance == null) {       // second check (synchronized)
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}
```

**What actually happens:** Object creation consists of three steps: (1) allocate memory, (2) initialize fields by running the constructor, (3) assign the reference to `instance`. The JIT compiler and CPU are permitted to reorder steps 2 and 3. A second thread executing the first null check may see a non-null `instance` reference (step 3 has occurred) but an incompletely initialized object (step 2 has not yet completed). The thread proceeds to use a partially constructed object.

**Fix:**

```java
public class Singleton {
    private static volatile Singleton instance;  // volatile prevents reordering

    private Singleton() { }

    public static Singleton getInstance() {
        if (instance == null) {
            synchronized (Singleton.class) {
                if (instance == null) {
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}
```

**Why the fix works:** A `volatile` write to `instance` establishes a happens-before edge: all writes performed by the constructor (step 2) happen-before the volatile write of the reference (step 3). Any thread that reads `instance` via a volatile read is guaranteed to see all constructor writes, so the object is fully initialized before the reference is observable.

---

### Scenario 2: Volatile Array

A developer uses a `volatile` array reference expecting that individual element reads will always see the latest values.

```java
volatile int[] counters = new int[4];

// Thread 1
counters[0]++;

// Thread 2
int val = counters[0];  // expects to see Thread 1's write
```

**What actually happens:** The `volatile` keyword applies to the array reference itself — it guarantees that if `counters` is reassigned to a different array, other threads will see the new reference. It does not apply to the array's elements. `counters[0]++` is a non-volatile, non-atomic read-modify-write on an ordinary heap location. Thread 2 may see a stale cached copy of `counters[0]`.

**Fix:**

```java
AtomicIntegerArray counters = new AtomicIntegerArray(4);

// Thread 1
counters.incrementAndGet(0);    // atomic, volatile element semantics

// Thread 2
int val = counters.get(0);      // guaranteed to see Thread 1's write
```

**Why the fix works:** `AtomicIntegerArray` stores elements with volatile semantics and provides CAS-based atomic operations per element. `get(i)` is a volatile read; `incrementAndGet(i)` is a CAS-loop that both increments atomically and establishes visibility.

---

### Scenario 3: ThreadLocal Leak in a Thread Pool

A web framework uses a `ThreadLocal` to store the current user's identity for the duration of each request.

```java
static final ThreadLocal<String> currentUser = new ThreadLocal<>();

// Called by a pool thread for each incoming request
void handleRequest(String userId) {
    currentUser.set(userId);
    processRequest();    // internally calls currentUser.get()
    // forgot: currentUser.remove()
}
```

**What actually happens:** Thread pool threads are reused across requests. After the first request completes, the `ThreadLocal` entry for `currentUser` remains in the pool thread's `ThreadLocalMap`. When the same thread handles a second request, `processRequest()` calls `currentUser.get()` and finds the first request's userId — not the second request's. If the second request never calls `set()` before `get()` (e.g., in a code path that assumes the value is set upstream), it sees stale data. Over time, `ThreadLocal` entries also accumulate, and if the values are large, prevent GC.

**Fix:**

```java
void handleRequest(String userId) {
    currentUser.set(userId);
    try {
        processRequest();
    } finally {
        currentUser.remove();    // always remove, even on exception
    }
}
```

**Why the fix works:** `remove()` deletes the entry from the thread's `ThreadLocalMap`, ensuring each request starts with a clean state regardless of which pool thread handles it. The `finally` block guarantees cleanup even if `processRequest()` throws.

---

### Scenario 4: False Sharing

Two threads update distinct counters in a shared object. The counters are independent — no thread touches the other's field — yet throughput is far below expectations.

```java
public class Counters {
    volatile long counter1 = 0;   // Thread 1 updates this
    volatile long counter2 = 0;   // Thread 2 updates this
}

// Thread 1: counters.counter1++ in a tight loop
// Thread 2: counters.counter2++ in a tight loop
```

**What actually happens:** `counter1` and `counter2` are adjacent in memory. On a typical 64-byte cache line (8 longs), they share a single cache line. Every time Thread 1 writes `counter1`, it invalidates Thread 2's cached copy of the entire cache line (which includes `counter2`). Thread 2 must reload from main memory before updating `counter2`, then Thread 1 must reload, and so on. This cache coherence ping-pong serializes the two otherwise independent writes, degrading throughput to roughly the speed of a single-threaded loop.

**Fix:**

```java
public class PaddedCounters {
    volatile long counter1;
    long p1, p2, p3, p4, p5, p6, p7;  // 7 × 8 bytes = 56 bytes padding
    volatile long counter2;            // now on its own 64-byte cache line
    long q1, q2, q3, q4, q5, q6, q7;
}
```

Alternatively, use `@jdk.internal.vm.annotation.Contended` (requires `-XX:-RestrictContended`).

**Why the fix works:** Each counter now occupies its own 64-byte cache line. Writes to `counter1` do not touch the cache line containing `counter2`, eliminating the coherence traffic and allowing both threads to update their counters at full memory bandwidth.

---

### Scenario 5: Parallel Stream with Shared Mutable State

A developer uses a parallel stream to process a large list and accumulates results into a shared `ArrayList` for speed.

```java
List<Integer> results = new ArrayList<>();
List<Integer> input   = IntStream.rangeClosed(1, 10_000)
                                 .boxed().collect(Collectors.toList());

input.parallelStream().forEach(n -> {
    results.add(n * 2);   // concurrent add to non-thread-safe ArrayList
});

System.out.println(results.size());   // often < 10_000, sometimes throws AIOOBE
```

**What actually happens:** `ArrayList.add()` is not thread-safe. Multiple threads simultaneously detect that the backing array needs expanding and copy it; one thread's copy overwrites another's. Elements are lost silently. In some interleavings, the internal size counter and the backing array are inconsistent, causing an `ArrayIndexOutOfBoundsException` from the array write at the wrong index.

**Fix:**

```java
List<Integer> results = input.parallelStream()
    .map(n -> n * 2)
    .collect(Collectors.toList());
```

**Why the fix works:** `Collectors.toList()` accumulates elements using thread-local intermediate collections (one per stream worker), combining them only at the end — no concurrent writes to a shared list occur. The result is always exactly 10,000 elements.

---

### Scenario 6: Virtual Thread Pinning with synchronized

A service uses `Executors.newVirtualThreadPerTaskExecutor()` for high-concurrency processing. A helper method acquires a `synchronized` lock and performs a blocking call. Throughput is unexpectedly limited.

```java
private final Object lock = new Object();

public void processRequest() throws InterruptedException {
    synchronized (lock) {
        // Simulates blocking IO (network call, DB query)
        Thread.sleep(100);
        // process result...
    }
}
```

**What actually happens:** When the virtual thread reaches `Thread.sleep(100)` inside the `synchronized` block, it cannot unmount from its carrier thread because it holds a monitor lock. The carrier (a platform thread) is pinned for the full 100ms. With the default carrier pool size equal to the number of CPUs (e.g., 8), throughput is capped at 80 requests per second — the same as using 8 platform threads. The virtual thread benefit is entirely negated for this code path.

**Fix:**

```java
private final ReentrantLock lock = new ReentrantLock();

public void processRequest() throws InterruptedException {
    lock.lock();
    try {
        Thread.sleep(100);   // virtual thread unmounts here; carrier is freed
        // process result...
    } finally {
        lock.unlock();
    }
}
```

**Why the fix works:** `ReentrantLock.lock()` uses `LockSupport.park()` internally when blocking. `park()` is the JVM signal for a virtual thread to unmount. The carrier thread is released to execute other virtual threads during the sleep, allowing thousands of virtual threads to process concurrently despite the lock.

---

### Scenario 7: Livelock

Two "polite" threads each try to yield when they detect a conflict with the other, with the intention of letting the other proceed first. Neither thread makes progress.

```java
// Thread 1: trying to acquire resourceA then resourceB
while (!resourceA.tryAcquire()) {
    Thread.yield();
}
while (!resourceB.tryAcquire()) {
    Thread.yield();
    resourceA.release();   // yield back resourceA too
    while (!resourceA.tryAcquire()) Thread.yield();
}

// Thread 2: trying to acquire resourceB then resourceA (reversed)
while (!resourceB.tryAcquire()) {
    Thread.yield();
}
while (!resourceA.tryAcquire()) {
    Thread.yield();
    resourceB.release();
    while (!resourceB.tryAcquire()) Thread.yield();
}
```

**What actually happens:** Both threads detect a conflict simultaneously, both yield and release their held resource simultaneously, both retry simultaneously, detect the conflict again, and repeat indefinitely. Neither thread is blocked — they are both RUNNABLE — but neither makes progress. Unlike deadlock, livelock threads are active and consuming CPU while accomplishing nothing.

**Fix:** Add randomized backoff so the two threads' retry times are statistically unlikely to coincide:

```java
Random rng = new Random();

while (!resourceA.tryAcquire()) {
    Thread.sleep(rng.nextInt(10));   // randomized delay: 0–9 ms
}
```

**Why the fix works:** With different random delays, one thread typically succeeds in acquiring the resource while the other is sleeping. The symmetry that causes both threads to retry at the same time is broken probabilistically. Exponential backoff (doubling the max delay with each consecutive failure) provides stronger guarantees under sustained contention.
