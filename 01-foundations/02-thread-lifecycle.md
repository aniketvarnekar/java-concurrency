# Thread Lifecycle

## Overview

Every Java thread progresses through a defined set of states from the moment it is created until it finishes execution. The JVM tracks these states through the `Thread.State` enum, which was introduced in Java 5 and contains six values: `NEW`, `RUNNABLE`, `BLOCKED`, `WAITING`, `TIMED_WAITING`, and `TERMINATED`. Understanding these states is essential for diagnosing thread dumps, reasoning about synchronization behavior, and using monitoring tools correctly.

The relationship between `Thread.State` and the underlying OS scheduler state is not one-to-one. The JVM's `RUNNABLE` state covers both threads that are actively executing on a CPU core and threads that are ready to run but waiting for the scheduler to assign them a core. From the JVM's perspective, a thread is `RUNNABLE` once it has been handed off to the OS; the JVM does not distinguish between "executing" and "waiting for a time slice." This distinction matters when interpreting thread dumps: a thread in `RUNNABLE` is not necessarily consuming CPU.

State transitions are triggered by specific method calls or by the JVM's internal synchronization machinery. A thread cannot jump arbitrarily between states — for example, a `TERMINATED` thread can never be restarted. Attempting to call `start()` on a terminated thread throws `IllegalThreadStateException`. Understanding which methods cause which transitions, and which transitions are reversible, prevents a class of concurrency bugs where a developer mistakenly assumes a thread can be recycled or re-entered.

Thread states are also the primary information surface in a thread dump. When you capture a thread dump with `jstack`, `jcmd`, or a profiler, every thread is reported with its current state and — for `BLOCKED`, `WAITING`, and `TIMED_WAITING` — the monitor or lock object it is waiting on. Recognizing state patterns (many threads `BLOCKED` on the same monitor, or a thread stuck in `WAITING` indefinitely) is a fundamental skill for diagnosing deadlocks and throughput bottlenecks.

## Key Concepts

### State: NEW

A thread enters the `NEW` state the instant it is constructed with `new Thread(...)` or a subclass constructor. The thread object exists in the JVM heap, but no OS-level thread has been created yet. No stack has been allocated. The thread is simply a Java object waiting to be started.

The thread leaves `NEW` exactly once: when `start()` is called. After that, it moves to `RUNNABLE` and can never return to `NEW`. Calling `start()` twice on the same thread throws `IllegalThreadStateException`.

### State: RUNNABLE

`RUNNABLE` is the broadest state. A thread enters `RUNNABLE` when `start()` is called and the JVM has registered the thread with the OS scheduler. From this point, the thread may be actively executing bytecodes on a CPU core, or it may be waiting for the OS scheduler to grant it a time slice. The JVM makes no distinction between these two sub-states.

A thread also re-enters `RUNNABLE` from `BLOCKED` when it acquires the monitor it was waiting for, from `WAITING` when it is notified via `notify()` or `notifyAll()`, from `TIMED_WAITING` when its timeout expires or it is notified, and after returning from a blocking native I/O call. The `RUNNABLE` state is exited when the thread blocks on a monitor (`BLOCKED`), calls `wait()`/`join()`/`LockSupport.park()` (`WAITING`), calls a timed variant (`TIMED_WAITING`), or its `run()` method returns (`TERMINATED`).

### State: BLOCKED

A thread enters `BLOCKED` when it attempts to acquire an intrinsic monitor lock (a `synchronized` block or method) that is currently held by another thread. The thread is removed from the run queue and parked by the JVM until the lock becomes available. When the holding thread exits the `synchronized` block, the JVM picks one of the waiting threads (the selection is not guaranteed to be fair) and moves it to `RUNNABLE` so it can attempt to acquire the lock.

`BLOCKED` applies only to intrinsic monitors (`synchronized`). Threads waiting to acquire a `java.util.concurrent.locks.Lock` (such as `ReentrantLock`) are placed in `WAITING` or `TIMED_WAITING` via `LockSupport.park()`, not in `BLOCKED`. This distinction is visible in thread dumps and is a common source of confusion.

### State: WAITING

A thread enters `WAITING` when it calls one of three methods without a timeout: `Object.wait()` (while holding the monitor of that object), `Thread.join()` (waiting for another thread to terminate), or `LockSupport.park()` (used internally by `java.util.concurrent` locks and conditions).

A thread in `WAITING` will remain there indefinitely until another thread takes a specific action: calling `Object.notify()` or `Object.notifyAll()` on the same monitor (for `wait()`), the joined thread terminating (for `join()`), or `LockSupport.unpark(thread)` being called (for `park()`). A thread in `WAITING` can also be moved to `RUNNABLE` by an interrupt — `Thread.interrupt()` causes `wait()` and `join()` to throw `InterruptedException`.

### State: TIMED_WAITING

`TIMED_WAITING` is identical in concept to `WAITING` except that the thread specifies a maximum duration after which it will automatically return to `RUNNABLE` if not woken earlier. Methods that cause this transition include `Thread.sleep(millis)`, `Object.wait(millis)`, `Thread.join(millis)`, `LockSupport.parkNanos(nanos)`, and `LockSupport.parkUntil(deadline)`.

The timer is a best-effort mechanism. The OS scheduler does not guarantee that a sleeping thread will wake at exactly the specified time — it will wake no earlier than the timeout, but may wake later if the scheduler is busy or the system clock resolution is coarse.

### State: TERMINATED

A thread enters `TERMINATED` (also called "dead") when its `run()` method returns normally, when it throws an uncaught exception that propagates out of `run()`, or, in rare cases, when the JVM exits. A `TERMINATED` thread cannot be restarted. The `Thread` object still exists in the heap and its fields (name, ID, daemon status) can still be read, but calling `start()` again throws `IllegalThreadStateException`.

### State Transition Diagram

```
                    start()
        NEW ──────────────────────> RUNNABLE
                                    |     ^
                    acquire monitor |     | monitor acquired
                    not available   v     |
                                  BLOCKED

        RUNNABLE ─── wait() ──────> WAITING ─── notify()/interrupt() ──> RUNNABLE
                 ─── join() ──────> WAITING ─── target terminates ──────> RUNNABLE
                 ─── park() ──────> WAITING ─── unpark()/interrupt() ───> RUNNABLE

        RUNNABLE ─── sleep(t) ────> TIMED_WAITING ─── timeout/notify ──> RUNNABLE
                 ─── wait(t) ─────> TIMED_WAITING
                 ─── join(t) ─────> TIMED_WAITING
                 ─── parkNanos ───> TIMED_WAITING

        RUNNABLE ─── run() returns/throws ──────────────────────────────> TERMINATED
```

### Transition Table

| From state | Trigger | To state |
|------------|---------|----------|
| `NEW` | `thread.start()` | `RUNNABLE` |
| `RUNNABLE` | Attempts `synchronized` on held monitor | `BLOCKED` |
| `RUNNABLE` | `Object.wait()` | `WAITING` |
| `RUNNABLE` | `Thread.join()` | `WAITING` |
| `RUNNABLE` | `LockSupport.park()` | `WAITING` |
| `RUNNABLE` | `Thread.sleep(t)` | `TIMED_WAITING` |
| `RUNNABLE` | `Object.wait(t)` | `TIMED_WAITING` |
| `RUNNABLE` | `Thread.join(t)` | `TIMED_WAITING` |
| `RUNNABLE` | `run()` returns or throws | `TERMINATED` |
| `BLOCKED` | Monitor becomes available and thread wins it | `RUNNABLE` |
| `WAITING` | `notify()` / `notifyAll()` / `unpark()` | `RUNNABLE` |
| `WAITING` | `Thread.interrupt()` | `RUNNABLE` (throws `InterruptedException`) |
| `TIMED_WAITING` | Timeout expires | `RUNNABLE` |
| `TIMED_WAITING` | `notify()` / `interrupt()` before timeout | `RUNNABLE` |

## Code Snippet

This program demonstrates four distinct states — `NEW`, `RUNNABLE`, `TIMED_WAITING`, and `TERMINATED` — and prints each state as it occurs. `BLOCKED` and `WAITING` are demonstrated with brief explanations in comments since capturing them requires precise timing.

```java
public class StateDemo {

    public static void main(String[] args) throws InterruptedException {
        // --- NEW state ---
        Thread worker = new Thread(() -> {
            System.out.println("[worker] running — state from inside: "
                    + Thread.currentThread().getState());
            try {
                // Causes TIMED_WAITING
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "worker");

        System.out.println("After new, before start: " + worker.getState()); // NEW

        worker.start();

        // Give the worker thread a moment to begin executing.
        Thread.sleep(20);
        System.out.println("Shortly after start:     " + worker.getState()); // RUNNABLE

        // The worker is now sleeping for 200 ms.
        Thread.sleep(30);
        System.out.println("While sleeping:          " + worker.getState()); // TIMED_WAITING

        worker.join(); // wait for worker to finish
        System.out.println("After join:              " + worker.getState()); // TERMINATED

        // --- BLOCKED state demonstration ---
        // Two threads competing for the same monitor.
        Object lock = new Object();

        Thread blocker = new Thread(() -> {
            synchronized (lock) {
                try { Thread.sleep(500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "blocker");

        Thread blocked = new Thread(() -> {
            synchronized (lock) { /* just acquires and releases */ }
        }, "blocked-thread");

        blocker.start();
        Thread.sleep(50); // let blocker acquire the lock
        blocked.start();
        Thread.sleep(50); // let blocked-thread attempt to acquire
        System.out.println("Waiting for monitor:     " + blocked.getState()); // BLOCKED

        blocker.join();
        blocked.join();
        System.out.println("After both finish:       " + blocked.getState()); // TERMINATED

        // --- WAITING state demonstration ---
        Object waitLock = new Object();
        Thread waiter = new Thread(() -> {
            synchronized (waitLock) {
                try {
                    waitLock.wait(); // WAITING until notified
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "waiter");

        waiter.start();
        Thread.sleep(50);
        System.out.println("Inside wait():           " + waiter.getState()); // WAITING

        synchronized (waitLock) {
            waitLock.notify();
        }
        waiter.join();
        System.out.println("After notify + join:     " + waiter.getState()); // TERMINATED
    }
}
```

Run: `javac StateDemo.java && java StateDemo`

## Gotchas

### RUNNABLE does not mean the thread is using CPU

A thread in `RUNNABLE` is simply eligible to run from the JVM's perspective. The OS scheduler may not have given it a CPU core yet. A thread performing a blocking system call (reading from a socket, waiting for disk I/O) may also appear as `RUNNABLE` in a thread dump because the JVM classifies native I/O waits as `RUNNABLE`. Do not interpret `RUNNABLE` as "consuming CPU" when reading a thread dump.

### Spurious wakeups require a loop around wait()

The Java specification explicitly permits `Object.wait()` to return even if no `notify()` or `notifyAll()` was called and the thread was not interrupted. This is called a spurious wakeup and originates from the behavior of POSIX condition variables on some operating systems. Always wrap `wait()` in a `while` loop that re-checks the condition predicate:

```java
synchronized (lock) {
    while (!conditionMet()) {
        lock.wait();
    }
}
```

Using an `if` instead of `while` will cause incorrect behavior when a spurious wakeup occurs.

### Thread.interrupt() does not stop a BLOCKED thread

Calling `interrupt()` on a thread that is `BLOCKED` waiting for a monitor lock has no immediate effect. The thread remains `BLOCKED`. `InterruptedException` is only thrown from methods that are specifically documented to respond to interruption: `wait()`, `sleep()`, `join()`, and certain `java.util.concurrent` blocking methods. A thread stuck on a `synchronized` lock cannot be interrupted out of that wait.

### Joining a thread that never terminates causes deadlock

`Thread.join()` with no timeout waits indefinitely. If the joined thread is stuck in `WAITING` or `BLOCKED` and nothing will ever unblock it, the calling thread waits forever. Use `join(timeout)` and handle the case where the thread did not finish within the timeout, or ensure the target thread has a well-defined termination condition.

### The TERMINATED state is permanent

Once a thread reaches `TERMINATED`, it cannot be moved back to any other state. Calling `start()` again on the same `Thread` object throws `IllegalThreadStateException`. If you need to run the same task again, create a new `Thread` instance or submit the task to a thread pool, which manages worker thread reuse internally.

### TIMED_WAITING timeouts are not precise

`Thread.sleep(1000)` does not guarantee the thread wakes after exactly 1000 ms. It guarantees the thread will not wake before 1000 ms. On a loaded system, the actual sleep duration may be significantly longer. Code that relies on precise timing (for example, treating sleep as a clock for periodic tasks) will drift. Use `ScheduledExecutorService` for periodic work, which compensates for execution time.
