# Structured Concurrency

## Overview

Structured concurrency (`java.util.concurrent.StructuredTaskScope`, Java 21) imposes a tree-shaped lifetime structure on concurrent tasks. A scope is opened, subtasks are forked inside it, and the scope closes only after all subtasks have either completed or been cancelled. No subtask can outlive the scope that created it. This prevents the most common form of task leak in unstructured async code — where a task submitted to an `ExecutorService` or a `CompletableFuture` chain continues running after the caller that submitted it has returned, failed, or been cancelled.

The problem with unstructured concurrency is that it breaks the relationship between task lifetimes and code structure. A method that submits three tasks to an `ExecutorService` and returns a result has no guaranteed way to ensure those tasks are cancelled if an exception is thrown. `CompletableFuture` chains that outlive their callers can hold resources, retain thread pool workers, and produce results that no one is waiting for. Error propagation across independent async tasks requires explicit coordination that is easy to omit. Structured concurrency makes these properties automatic: the scope boundary is the lifetime boundary.

Structured concurrency is designed primarily for virtual threads. A scope forks subtasks as virtual threads, and the scope's own thread waits at `join()` until the scope's completion policy is satisfied. Because virtual threads are cheap, one virtual thread per subtask is the natural model. The two built-in policies — `ShutdownOnFailure` and `ShutdownOnSuccess` — cover the two most common fan-out patterns: all-or-nothing (fail if any subtask fails) and first-wins (succeed as soon as any subtask succeeds).

## Key Concepts

### The Problem with Unstructured Concurrency

A `CompletableFuture` chain can outlive its caller. If `fetchUser()` and `fetchOrders()` are submitted as `CompletableFuture` tasks and one throws an exception, the other continues running in `ForkJoinPool.commonPool()` indefinitely unless explicitly cancelled. The caller's exception handler must know about both tasks and cancel them explicitly — this coupling is error-prone and frequently omitted. Structured concurrency makes cancellation automatic: when the scope shuts down, all forked subtasks receive an interrupt and the scope does not close until they stop.

### `StructuredTaskScope`

`StructuredTaskScope` is a `try-with-resources` resource. It is opened at the top of a block and closed automatically at the end. The `fork(Callable<V>)` method submits a subtask as a new virtual thread and returns a `Subtask<V>` handle. `join()` blocks until the scope's completion policy declares the scope done (either a timeout is reached or the policy's condition is satisfied). `close()` — called by try-with-resources — ensures that all forked subtasks are cancelled (via `Thread.interrupt()`) and awaited before the scope is fully closed.

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<String> user   = scope.fork(() -> fetchUser(userId));
    Subtask<List<Order>> orders = scope.fork(() -> fetchOrders(userId));
    scope.join().throwIfFailed(); // blocks; propagates first failure
    return new UserProfile(user.get(), orders.get());
}
// Both subtasks are guaranteed to have finished by here
```

### `ShutdownOnFailure`

`ShutdownOnFailure` implements the all-or-nothing fan-out policy. When any subtask throws an exception, the scope shuts down: all remaining subtasks receive an `InterruptedException` (via `Thread.interrupt()`), and `join()` — after returning — throws the first subtask's exception when `throwIfFailed()` is called on the returned `Outcome`. This is appropriate when all results are required: if any dependency fails, there is no point in waiting for the others.

### `ShutdownOnSuccess`

`ShutdownOnSuccess` implements the first-wins (hedging) policy. When any subtask completes successfully, the scope shuts down and cancels remaining subtasks. After `join()` returns, `result()` returns the first successful result. This is appropriate for hedged requests: send the same request to multiple redundant services and accept the fastest response, discarding the rest.

```java
try (var scope = new StructuredTaskScope.ShutdownOnSuccess<String>()) {
    scope.fork(() -> callServiceA());
    scope.fork(() -> callServiceB());
    scope.join(); // returns when first succeeds
    return scope.result(); // the winning result
}
```

### `Subtask<V>.get()`

`Subtask<V>.get()` retrieves the subtask's result. It must be called after `join()` returns. The subtask is guaranteed to be complete at that point. If the subtask failed, `get()` throws the subtask's exception. If the subtask was cancelled (interrupted), `get()` throws `CancellationException`.

### Cancellation Propagation

When a scope shuts down (due to a policy condition being met), it sends `Thread.interrupt()` to all running subtasks' virtual threads. Subtasks must not swallow `InterruptedException` — they must either re-throw it or check `Thread.currentThread().isInterrupted()` and exit. A subtask that catches and swallows `InterruptedException` without stopping will continue running, and the scope's `close()` will block until it finally terminates on its own.

### Custom Policies

Extend `StructuredTaskScope<T>` directly and override `handleComplete(Subtask<? extends T> subtask)` to implement custom completion policies: for example, wait until at least two subtasks succeed, or collect all failures before shutting down. The `shutdown()` method is protected and can be called from `handleComplete()` to trigger scope shutdown.

## Code Snippet

```java
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;

/**
 * Demonstrates StructuredTaskScope.ShutdownOnFailure and ShutdownOnSuccess.
 *
 * Run: javac --enable-preview --release 21 StructuredConcurrencyDemo.java
 *   && java --enable-preview StructuredConcurrencyDemo
 *
 * Note: Requires Java 21+ with preview features enabled.
 */
public class StructuredConcurrencyDemo {

    // ---------------------------------------------------------------
    // Simulated service calls
    // ---------------------------------------------------------------
    static String fetchUser(String userId) throws InterruptedException {
        System.out.printf("  [%s] fetchUser(%s) started%n",
                Thread.currentThread().getName(), userId);
        Thread.sleep(200);
        System.out.printf("  [%s] fetchUser(%s) completed%n",
                Thread.currentThread().getName(), userId);
        return "user-" + userId;
    }

    static String fetchOrders(String userId) throws InterruptedException {
        System.out.printf("  [%s] fetchOrders(%s) started%n",
                Thread.currentThread().getName(), userId);
        Thread.sleep(300);
        // Simulate failure after 300ms
        throw new RuntimeException("orders service unavailable");
    }

    static String callServiceA() throws InterruptedException {
        System.out.printf("  [%s] ServiceA started%n", Thread.currentThread().getName());
        try {
            Thread.sleep(400);
            System.out.printf("  [%s] ServiceA completed%n", Thread.currentThread().getName());
            return "result-from-A";
        } catch (InterruptedException e) {
            System.out.printf("  [%s] ServiceA cancelled (interrupted)%n",
                    Thread.currentThread().getName());
            throw e; // must re-throw — do not swallow InterruptedException
        }
    }

    static String callServiceB() throws InterruptedException {
        System.out.printf("  [%s] ServiceB started%n", Thread.currentThread().getName());
        Thread.sleep(150);
        System.out.printf("  [%s] ServiceB completed%n", Thread.currentThread().getName());
        return "result-from-B";
    }

    // ---------------------------------------------------------------
    // Part 1: ShutdownOnFailure — all-or-nothing
    // ---------------------------------------------------------------
    static void demonstrateShutdownOnFailure() {
        System.out.println("=== Part 1: ShutdownOnFailure ===");
        System.out.println("fetchUser (200ms) + fetchOrders (300ms, then throws)");
        System.out.println("Expected: user fetch cancelled when orders fails\n");

        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            Subtask<String> userTask   = scope.fork(() -> fetchUser("42"));
            Subtask<String> orderTask  = scope.fork(() -> fetchOrders("42"));

            scope.join()           // wait for scope policy to be satisfied
                 .throwIfFailed(); // propagate first exception

            // This line is only reached if both tasks succeed
            System.out.printf("User: %s, Orders: %s%n", userTask.get(), orderTask.get());

        } catch (Exception e) {
            System.out.printf("\nScope failed with: %s%n", e.getMessage());
            // Both subtasks are guaranteed complete at this point
            System.out.println("All subtasks have stopped (guaranteed by scope close)");
        }
    }

    // ---------------------------------------------------------------
    // Part 2: ShutdownOnSuccess — first-wins (hedging)
    // ---------------------------------------------------------------
    static void demonstrateShutdownOnSuccess() throws Exception {
        System.out.println("\n=== Part 2: ShutdownOnSuccess ===");
        System.out.println("ServiceA (400ms) vs ServiceB (150ms)");
        System.out.println("Expected: ServiceB wins, ServiceA is cancelled\n");

        try (var scope = new StructuredTaskScope.ShutdownOnSuccess<String>()) {
            scope.fork(() -> callServiceA());
            scope.fork(() -> callServiceB());

            scope.join(); // returns when first subtask succeeds

            String winner = scope.result();
            System.out.printf("%nWinner: %s%n", winner);
            System.out.println("Losing service was cancelled (interrupted)");
        }
    }

    // ---------------------------------------------------------------
    // Main
    // ---------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        demonstrateShutdownOnFailure();
        demonstrateShutdownOnSuccess();
        System.out.println("\nDone.");
    }
}
```

## Gotchas

**`Subtask<V>.get()` must be called after `scope.join()` returns.** If `get()` is called before `join()`, the subtask may not yet be complete and `get()` throws `IllegalStateException`. The correct sequence is: fork all subtasks, call `join()` (which blocks until the policy is satisfied), then call `get()` on each subtask to retrieve results. `get()` is guaranteed to return immediately after `join()` has returned.

**Swallowing `InterruptedException` in a subtask prevents timely scope shutdown.** When a scope shuts down, it calls `Thread.interrupt()` on each running subtask's virtual thread. If the subtask catches `InterruptedException` and does not re-throw it or stop execution, the interrupt is effectively ignored. The scope's `close()` will block waiting for that subtask to finish, hanging the scope for as long as the subtask continues to run. Always re-throw `InterruptedException` or honor the interrupt by returning promptly.

**`StructuredTaskScope` is a preview API in Java 21 and requires `--enable-preview` at both compile time and runtime.** Compiling without `--enable-preview --release 21` produces a compile error because the `StructuredTaskScope` class is in a preview package. Running without `--enable-preview` produces a runtime error. Both flags are required in the `javac` and `java` invocations. This requirement may change in future Java releases as structured concurrency moves out of preview.

**Mixing `StructuredTaskScope` with unstructured `CompletableFuture` chains breaks the scope's lifetime guarantee.** If a forked subtask internally submits additional tasks to `ForkJoinPool.commonPool()` via `CompletableFuture.supplyAsync()` without joining them, those tasks escape the scope's control. They run beyond the scope's close, holding resources and potentially modifying state after the caller has already returned or failed. All work spawned within a scope must be forked through the scope, not submitted to external executors.

**The scope must be used with try-with-resources to guarantee closure.** Using `StructuredTaskScope` without try-with-resources — for example, by calling `scope.fork()` and `scope.join()` without a corresponding `scope.close()` — leaves the scope open and all forked subtasks running indefinitely. Close is the point at which the scope sends interrupts to outstanding tasks and waits for them to finish. Omitting it leaks threads.

**`ShutdownOnSuccess` throws when no subtask succeeds.** If all subtasks fail or are cancelled, `scope.result()` throws `ExecutionException` (wrapping the first failure) and `join()` does not itself throw. The caller must handle the case of no successful result explicitly, either by calling `scope.result()` inside a try/catch or by checking the subtask states before calling `result()`. Assuming `result()` is always available after `join()` returns leads to an unexpected exception.
