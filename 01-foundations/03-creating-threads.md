# Creating Threads

## Overview

Java provides three primary ways to define the work a thread should perform: subclassing `Thread`, implementing `Runnable`, and implementing `Callable`. Each approach has different trade-offs around flexibility, return values, exception handling, and composability with the `java.util.concurrent` framework. Choosing the right mechanism from the start avoids awkward refactoring later when requirements change.

Subclassing `Thread` was the earliest idiom but is rarely the right choice today. It conflates two concerns: the thread's execution machinery and the task the thread performs. A class that extends `Thread` cannot extend any other class, which forecloses normal inheritance hierarchies. Implementing `Runnable` separates the task definition from the thread, allows the same `Runnable` to be submitted to a thread pool without modification, and allows the implementing class to extend something else. `Callable` adds a return value and the ability to declare checked exceptions, which `Runnable.run()` cannot do.

Beyond how you define the task, there are several `Thread` methods that every concurrent Java developer must understand: `start()`, `run()`, `join()`, `sleep()`, `interrupt()`, `isInterrupted()`, and `currentThread()`. Misusing any of these is a reliable source of bugs — calling `run()` instead of `start()`, catching `InterruptedException` silently, or using `sleep()` as a synchronization mechanism are among the most common mistakes in beginner concurrent code.

The `java.util.concurrent` framework, introduced in Java 5, builds on top of `Runnable` and `Callable` to provide `ExecutorService`, `Future`, `FutureTask`, and a suite of higher-level abstractions. Understanding the raw thread mechanisms covered in this note is the prerequisite for using those abstractions correctly.

## Key Concepts

### Extending Thread

The simplest way to define a thread is to subclass `Thread` and override `run()`. The work of the thread goes in `run()`. To start the thread, call `start()` on the instance.

```java
class CounterThread extends Thread {
    CounterThread(String name) {
        super(name);
    }

    @Override
    public void run() {
        for (int i = 0; i < 5; i++) {
            System.out.println(getName() + " count=" + i);
        }
    }
}

// Usage:
Thread t = new CounterThread("counter");
t.start();
```

The problem with this approach is that `CounterThread` can extend nothing else in Java's single-inheritance model. The task (counting) and the execution vehicle (thread) are merged into one class. If you later want to run the same counting logic on a thread pool, you cannot submit a `CounterThread` instance directly to an `ExecutorService` without adaptation.

### Implementing Runnable

`Runnable` is a functional interface with one method: `void run()`. A class that implements `Runnable` defines a task without binding it to a thread. You pass the `Runnable` to a `Thread` constructor, or to an `ExecutorService.submit()`, or wrap it in a `FutureTask`.

```java
class CounterTask implements Runnable {
    private final String label;

    CounterTask(String label) {
        this.label = label;
    }

    @Override
    public void run() {
        for (int i = 0; i < 5; i++) {
            System.out.println(label + " count=" + i);
        }
    }
}

// Usage:
Runnable task = new CounterTask("counter");
Thread t = new Thread(task, "counter-thread");
t.start();
```

Because `Runnable.run()` is declared `void` and has no `throws` clause, it cannot return a value or throw a checked exception. Any checked exception must be caught inside `run()` and either handled or re-thrown as an unchecked exception.

### Implementing Callable

`Callable<V>` is similar to `Runnable` but its single method, `V call() throws Exception`, returns a value and can throw a checked exception. `Callable` is designed to be submitted to an `ExecutorService`, which wraps it in a `Future<V>`. You can also use it with `FutureTask<V>` to run it on a plain `Thread`.

```java
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

Callable<Integer> sumTask = () -> {
    int sum = 0;
    for (int i = 1; i <= 100; i++) sum += i;
    return sum;
};

FutureTask<Integer> future = new FutureTask<>(sumTask);
Thread t = new Thread(future, "sum-thread");
t.start();

Integer result = future.get(); // blocks until done
System.out.println("Sum = " + result);
```

`FutureTask<V>` implements both `Runnable` and `Future<V>`. Passing it to a `Thread` constructor satisfies the `Runnable` requirement; calling `future.get()` satisfies the `Future<V>` requirement. This dual interface is what allows `FutureTask` to bridge plain threads and the `ExecutorService` framework.

### Comparison of Mechanisms

| Mechanism | Returns value | Throws checked exception | Extends another class | Pool-compatible |
|-----------|:---:|:---:|:---:|:---:|
| `extends Thread` | No | No | No | No (without wrapping) |
| `implements Runnable` | No | No | Yes | Yes |
| `implements Callable` | Yes | Yes | Yes | Yes |

### Thread.start vs Thread.run

`start()` creates a new OS thread, allocates a stack, registers the thread with the scheduler, and arranges for `run()` to be called on the new thread. Execution of `start()` returns immediately to the caller — the new thread runs concurrently.

`run()` is an ordinary method call. Calling `thread.run()` directly executes the task on the calling thread, with no new thread created. The calling thread blocks until `run()` returns. This compiles and runs without error, which makes the mistake hard to spot. Every thread must be started with `start()`, never with `run()`.

### Thread.join

`join()` causes the calling thread to wait until the target thread reaches `TERMINATED`. It is the standard way to wait for a thread's work to complete before proceeding.

```java
Thread t = new Thread(() -> doWork(), "worker");
t.start();
t.join(); // main thread waits here until worker finishes
System.out.println("Worker done");
```

`join(long millis)` accepts a timeout. If the thread has not terminated within `millis` milliseconds, `join()` returns normally (without throwing). The caller should check `t.isAlive()` afterward to determine whether the thread finished or the timeout elapsed.

### Thread.sleep

`Thread.sleep(long millis)` causes the current thread to enter `TIMED_WAITING` for at least the specified duration. It throws `InterruptedException` if another thread interrupts the sleeping thread, which must be handled or declared.

`sleep()` does not release any intrinsic monitor locks the thread holds. A thread that sleeps inside a `synchronized` block retains the lock for the entire sleep duration, blocking other threads that need the same monitor.

```java
synchronized (lock) {
    Thread.sleep(1000); // holds lock for 1 second — other threads cannot enter
}
```

This is almost always a bug. Prefer `lock.wait(timeout)` if you need to pause execution while allowing other threads to enter the synchronized region.

### Thread.interrupt and isInterrupted

`interrupt()` sets the interrupt flag on the target thread. It does not forcibly stop the thread. If the thread is in `WAITING` or `TIMED_WAITING` (blocked in `sleep()`, `wait()`, or `join()`), it wakes immediately and an `InterruptedException` is thrown; the interrupt flag is cleared when the exception is thrown. If the thread is `RUNNABLE`, the flag is set but nothing happens until the thread explicitly checks it via `Thread.interrupted()` (clears the flag and returns its value) or `Thread.currentThread().isInterrupted()` (returns the flag value without clearing it).

The cooperative interruption pattern is:

```java
while (!Thread.currentThread().isInterrupted()) {
    doUnitOfWork();
}
```

When `InterruptedException` is caught, the interrupt status has already been cleared. If you catch it and do not re-throw it, you must restore the flag so callers can detect the interruption:

```java
try {
    Thread.sleep(1000);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt(); // restore the flag
    return; // or otherwise stop the work
}
```

### Thread.currentThread

`Thread.currentThread()` is a static method that returns a reference to the `Thread` object representing the currently executing thread. It is useful for reading the thread's name, checking its interrupt status, or adjusting its priority when you do not have a direct reference to the thread object.

```java
System.out.println("Running on: " + Thread.currentThread().getName());
```

## Gotchas

### Calling run() instead of start() creates no new thread

`thread.run()` is a plain method invocation. The task executes on the calling thread, sequentially, with no concurrency. The mistake compiles without warning and the program produces correct output on a single-threaded basis, so tests may pass while the concurrency requirement is silently unmet. Always call `start()`.

### Thread.stop() is deprecated and dangerous

`Thread.stop()` was deprecated in Java 1.1 because it forcibly releases all monitors the target thread holds by throwing `ThreadDeath` (a `Throwable`) at an arbitrary point. This can leave objects in a partially updated, inconsistent state, corrupting data visible to other threads. Use cooperative interruption: set the interrupt flag with `interrupt()` and write the thread's loop to check `isInterrupted()` at safe points.

### Swallowing InterruptedException breaks cooperative cancellation

`InterruptedException` is a signal from another thread requesting cancellation. Catching it and doing nothing — `catch (InterruptedException e) {}` — discards that signal. The thread continues running as if interruption never happened. Any caller that attempted to cancel the operation via `interrupt()` will never see its request honored. Always either re-throw the exception or restore the interrupt flag with `Thread.currentThread().interrupt()`.

### sleep() does not release locks

`Thread.sleep()` pauses the current thread without releasing any intrinsic monitor locks it holds. A thread sleeping inside a `synchronized` block starves all other threads waiting for that monitor. If you need to pause execution and allow other threads to proceed inside a synchronized region, use `wait()` on the monitor object, which atomically releases the lock and suspends the thread.

### join() with no timeout can block forever

`thread.join()` waits indefinitely. If the target thread hangs due to an infinite loop, a deadlock, or an external resource that never responds, the joining thread waits forever, potentially hanging the application. Use `join(long millis)` with a sensible timeout and verify `thread.isAlive()` afterward to distinguish a clean finish from a timeout.

### FutureTask exceptions are wrapped, not re-thrown directly

If the `Callable` passed to a `FutureTask` throws an exception, `futureTask.get()` wraps the original exception in an `ExecutionException`. The actual cause is retrieved with `e.getCause()`. Catching only `RuntimeException` or the specific checked exception in a `try/catch` around `get()` will miss the error. Always handle `ExecutionException` and inspect its cause.
