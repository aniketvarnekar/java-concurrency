# Daemon Threads

## Overview

Java threads are categorized as either user threads or daemon threads. A user thread (also called a non-daemon thread) keeps the JVM alive. The JVM will not exit as long as at least one user thread is still running, regardless of how many daemon threads exist. A daemon thread, by contrast, does not prevent JVM shutdown. When all user threads have terminated, the JVM initiates shutdown even if daemon threads are still in the middle of executing.

The distinction exists to support background housekeeping work that has no reason to keep the application alive. Examples include the JVM's own garbage collector threads, the finalizer thread, and background monitoring or logging threads that an application might create. These threads exist to serve the application, and when the application's user threads have finished, there is no reason to keep the process alive just for the housekeeping work.

From a practical standpoint, daemon threads are a tool for background tasks that are safe to abandon mid-execution. The JVM does not wait for them to finish their current work, does not run their `finally` blocks, and does not flush their I/O buffers on shutdown. Any thread that holds a resource requiring cleanup — an open file, a database connection, a lock — should be a user thread or should register a JVM shutdown hook to perform cleanup.

The garbage collector is the canonical daemon thread. It runs continuously in the background, collecting unreachable objects. When the last user thread exits, the JVM doesn't wait for a GC cycle to complete — it simply exits. This is the intended behavior: GC is a servant of the application, not a reason to keep the process alive.

## Key Concepts

### setDaemon()

`Thread.setDaemon(boolean on)` marks a thread as a daemon thread when called with `true`, or as a user thread when called with `false`. The default for a new thread is inherited from the thread that creates it (covered below). The main thread is always a user thread.

```java
Thread background = new Thread(() -> {
    while (true) {
        performHousekeeping();
    }
}, "housekeeping");

background.setDaemon(true); // must be called before start()
background.start();
```

`setDaemon()` must be called before `start()`. Calling it after `start()` throws `IllegalThreadStateException`. This restriction exists because once a thread is running, the JVM has already made scheduling decisions based on its daemon status.

### isDaemon()

`Thread.isDaemon()` returns the current daemon status of the thread. It can be called at any point in the thread's lifecycle.

```java
Thread t = new Thread(() -> {}, "example");
System.out.println(t.isDaemon()); // false — default is non-daemon

t.setDaemon(true);
System.out.println(t.isDaemon()); // true
```

The daemon status of a running thread can be read but not changed. Attempting to change it after `start()` throws `IllegalThreadStateException`.

### Inheritance of Daemon Status

When a thread creates a new thread, the new thread inherits the daemon status of its creator. If Thread A is a daemon thread and Thread A creates Thread B, Thread B is also a daemon thread by default (unless `setDaemon(false)` is explicitly called before `B.start()`).

```
main thread (user thread)
  |
  +-- spawns Thread A (daemon=true)
        |
        +-- spawns Thread B (inherits daemon=true from Thread A)
        +-- spawns Thread C, then calls C.setDaemon(false)  -->  user thread
```

This inheritance behavior is a common source of bugs in thread pool libraries. A custom `ThreadFactory` that creates daemon threads will produce an executor where all worker threads are daemons — tasks submitted to that executor may be abandoned mid-execution if the main thread exits.

### JVM Shutdown Behavior

When the last user thread terminates, the JVM begins its shutdown sequence:

1. Registered shutdown hooks are started (as user threads). These run concurrently.
2. If `System.runFinalizersOnExit(true)` was called (deprecated and not recommended), finalizers run.
3. The JVM halts, abandoning all daemon threads at whatever point they are executing.

Daemon threads do not get a chance to finish their current task, run `finally` blocks, or flush buffers. A daemon thread writing to a file may leave a partial, corrupted file. A daemon thread holding a lock will release it abruptly when the JVM exits, but any resource it was protecting may be in an inconsistent state.

### JVM Shutdown Hooks

A shutdown hook is a thread that is pre-registered with the JVM and started during shutdown. Hooks run after the last user thread finishes, concurrently with other hooks, but before daemon threads are abandoned.

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    System.out.println("Shutdown hook running — flushing buffers...");
    flushPendingWrites();
}, "shutdown-hook"));
```

Shutdown hooks are the correct mechanism for daemon-thread-adjacent cleanup. Register a hook to flush, close, or clean up resources that a daemon thread might be using, rather than trying to coordinate cleanup in the daemon thread itself.

### Threads Created by Daemon Threads

Any thread created by a daemon thread is itself a daemon thread by default due to status inheritance. This cascades: an application that makes its initial worker thread a daemon effectively makes all threads spawned from that worker daemons. The entire subtree of threads created from a daemon root will be daemon threads unless explicitly overridden.

This matters in practice when using thread pools. If the `ThreadFactory` used to construct a `ThreadPoolExecutor` creates daemon threads, all tasks submitted to that pool run on daemon threads. If those tasks spawn additional threads, those are daemon threads too.

## Code Snippet

This program runs two scenarios. In the first, a daemon thread is running when the main user thread exits — the JVM exits without waiting for the daemon thread to finish. In the second, a user thread keeps the JVM alive until it completes its work.

```java
public class DaemonDemo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Scenario 1: Daemon thread abandoned on exit ===");
        scenarioDaemon();

        // Brief pause so output from scenario 1 settles before scenario 2.
        Thread.sleep(300);

        System.out.println("\n=== Scenario 2: User thread keeps JVM alive ===");
        scenarioUser();
    }

    static void scenarioDaemon() throws InterruptedException {
        Thread daemon = new Thread(() -> {
            for (int i = 1; i <= 20; i++) {
                System.out.println("  [daemon] step " + i);
                try { Thread.sleep(80); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            // This line may never print if the JVM exits first.
            System.out.println("  [daemon] finished all steps");
        }, "daemon-worker");

        daemon.setDaemon(true);
        daemon.start();

        // Main thread (user) sleeps briefly, then returns from this method.
        // After main() returns, the JVM exits, abandoning the daemon thread.
        Thread.sleep(200); // daemon will only complete ~2-3 steps
        System.out.println("  [main] returning from scenarioDaemon()");
        // The daemon thread is still running but will be abandoned when
        // scenario 2 finishes and main() returns.
    }

    static void scenarioUser() throws InterruptedException {
        Thread user = new Thread(() -> {
            for (int i = 1; i <= 5; i++) {
                System.out.println("  [user-worker] step " + i);
                try { Thread.sleep(80); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            System.out.println("  [user-worker] finished all steps — JVM may now exit");
        }, "user-worker");

        user.setDaemon(false); // explicit, though false is already the default
        user.start();

        System.out.println("  [main] started user-worker, now returning from scenarioUser()");
        // Even though main returns from scenarioUser(), the JVM waits for
        // user-worker to finish because it is a user thread.
        user.join(); // wait here so we can print the final message cleanly
        System.out.println("  [main] user-worker is done");
    }
}
```

Run: `javac DaemonDemo.java && java DaemonDemo`

Expected behavior: the daemon-worker thread will be cut short when the JVM exits. The user-worker thread will run to completion because the JVM waits for all user threads before exiting.

## Gotchas

### setDaemon() must be called before start()

Calling `setDaemon()` after `start()` throws `IllegalThreadStateException`. There is no way to change a running thread's daemon status. The daemon flag must be set up front, before the thread is handed to the OS scheduler. If you are using a thread pool and want daemon threads, configure the `ThreadFactory` when constructing the pool.

### Daemon threads leave resources uncleaned

When the JVM exits, daemon threads are stopped without running their `finally` blocks. A daemon thread that opened a file, began writing to a database, or acquired an external lock will abandon those resources in whatever state they were in. Use shutdown hooks (`Runtime.getRuntime().addShutdownHook(...)`) to perform cleanup for resources that daemon threads manage.

### ThreadPoolExecutor uses non-daemon threads by default

`Executors.newFixedThreadPool()` and similar factory methods use `Executors.defaultThreadFactory()`, which creates non-daemon threads. This means that if you create a thread pool and never shut it down explicitly (via `executor.shutdown()`), the application will not exit even after `main()` returns, because the pool's idle worker threads are still alive. Either call `shutdown()` when the pool is no longer needed, or configure the pool with a daemon-thread factory.

### Inheriting daemon status from a parent daemon thread

A daemon thread that spawns additional threads produces more daemon threads by default. A framework or library that marks its root thread as a daemon, then spawns worker threads from it, may inadvertently make all of those workers daemons — causing them to be abandoned mid-task on application exit. When writing a framework, use an explicit `ThreadFactory` and call `setDaemon(false)` on each thread it creates to avoid unintended daemon inheritance.

### Shutdown hooks run concurrently and have a time limit

Multiple shutdown hooks run concurrently. If one hook blocks indefinitely, the JVM may not finish shutdown cleanly. On a forced shutdown (`kill -9`, `Runtime.halt()`), hooks do not run at all. Hooks should be fast, defensive, and idempotent. Do not rely on shutdown hooks for critical data integrity — use proper transaction handling or write-ahead logging instead.
