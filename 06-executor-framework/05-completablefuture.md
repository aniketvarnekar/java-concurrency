# CompletableFuture

## Overview

CompletableFuture<T> (Java 8+) is a Future that can be explicitly completed, combined with other futures, and used to build non-blocking asynchronous pipelines through a rich set of composition methods. Where Future requires a blocking get() call to retrieve the result, CompletableFuture allows chaining callbacks that execute when the result becomes available, enabling event-driven computation without blocking threads while waiting for results.

The composition API falls into three categories: transformation (thenApply, thenApplyAsync), chaining (thenCompose, thenComposeAsync), and combination (thenCombine, allOf, anyOf). Error handling methods (exceptionally, handle, whenComplete) can be attached anywhere in the chain and act as recovery points or side-effect hooks. Each composition method returns a new CompletableFuture, allowing arbitrarily long pipelines to be expressed as a sequence of readable method calls.

CompletableFuture also supports explicit completion: complete(value) and completeExceptionally(throwable) allow a thread to provide the result from outside the computation. This enables bridging legacy callback-based APIs into the CompletableFuture model without spawning a background thread.

## Key Concepts

### Creating

supplyAsync(Supplier<T>) runs the supplier on ForkJoinPool.commonPool() and returns a CompletableFuture<T>. supplyAsync(Supplier<T>, Executor) uses the provided executor. runAsync(Runnable) produces CompletableFuture<Void>. completedFuture(value) returns an already-completed future, useful for testing and conditional pipelines.

```java
CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> fetchUser(), executor);
CompletableFuture<Void>   vf = CompletableFuture.runAsync(() -> fireAndForget(), executor);
CompletableFuture<String> done = CompletableFuture.completedFuture("cached-value");
```

### thenApply(Function<T,U>)

Transforms the result of a stage synchronously on the thread that completed the previous stage. If the previous stage completed before thenApply was registered, the function runs on the calling thread. Use thenApplyAsync(Function, Executor) to guarantee the transformation runs on a controlled executor thread.

```java
CompletableFuture<Integer> lengthFuture = cf.thenApply(String::length);
CompletableFuture<Integer> asyncLength  = cf.thenApplyAsync(String::length, executor);
```

### thenCompose(Function<T, CompletableFuture<U>>)

Flat-maps one async stage into another. The function takes the result of the first stage and returns a new CompletableFuture. The overall result is that inner future's value, not a nested CompletableFuture<CompletableFuture<U>>. Use thenCompose when the next step is itself an async operation; use thenApply when the transformation is synchronous.

```java
CompletableFuture<List<Order>> ordersFuture =
    userFuture.thenCompose(user -> CompletableFuture.supplyAsync(
            () -> fetchOrders(user), executor));
```

### thenCombine, allOf, anyOf

thenCombine(CompletableFuture<U>, BiFunction<T,U,V>) combines two independent futures when both complete. allOf(futures...) completes when all provided futures complete; its result type is Void, so individual results must be retrieved from the original futures. anyOf(futures...) completes when the first future completes, returning an Object.

```java
CompletableFuture<String> combined = userFuture.thenCombine(prefsFuture,
        (user, prefs) -> user + " prefs=" + prefs);

CompletableFuture<Void> all = CompletableFuture.allOf(f1, f2, f3);
all.join(); // then call f1.join(), f2.join() etc. to get values
```

### exceptionally(Function<Throwable, T>)

Runs only if the pipeline threw an exception. The function receives the Throwable and returns a fallback value of type T. Does not execute if no exception occurred. Useful for providing default values or cached fallbacks when a service call fails.

```java
CompletableFuture<String> safe = risky
        .exceptionally(t -> "default-value");
```

### handle(BiFunction<T, Throwable, U>)

Always runs, regardless of success or failure. The first parameter is the result (null on failure) and the second is the throwable (null on success). Unlike exceptionally, handle can transform both the success value and the error into a unified response type.

```java
CompletableFuture<Response> handled = future.handle((result, err) -> {
    if (err != null) return Response.error(err.getMessage());
    return Response.ok(result);
});
```

### whenComplete(BiConsumer<T, Throwable>)

Side-effect hook that runs on both success and failure. It does not transform the result — the original result or exception passes through unchanged to the next stage. Use for logging, metrics, or cleanup.

```java
future.whenComplete((result, err) -> {
    if (err != null) log.error("pipeline failed", err);
    else log.info("pipeline completed: {}", result);
});
```

### join() vs get()

Both block until the future completes. join() throws CompletionException (unchecked) on failure; get() throws ExecutionException (checked) and InterruptedException. In lambda pipelines where checked exceptions would require try/catch blocks, join() is more ergonomic.

### Pipeline Diagram

```
supplyAsync(fetchUser)
  → thenCompose(user → supplyAsync(fetchOrders(user)))
  → thenCombine(supplyAsync(fetchPrefs(user)), merge)
  → exceptionally(t -> defaultResponse)
  → whenComplete((result, err) -> log)
```

## Code Snippet

```java
import java.util.List;
import java.util.concurrent.*;

public class CompletableFutureDemo {

    static ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setName("cf-worker-" + t.getId());
        return t;
    });

    // Simulated async service calls
    static String fetchUser() throws InterruptedException {
        System.out.println("[" + Thread.currentThread().getName() + "] fetchUser started");
        Thread.sleep(200);
        System.out.println("[" + Thread.currentThread().getName() + "] fetchUser done");
        return "user-42";
    }

    static String enrichUser(String userId) throws InterruptedException {
        System.out.println("[" + Thread.currentThread().getName() + "] enrichUser(" + userId + ")");
        Thread.sleep(100);
        return "User{id=42, name=Alice}";
    }

    static List<String> fetchOrders(String userInfo) throws InterruptedException {
        System.out.println("[" + Thread.currentThread().getName() + "] fetchOrders for " + userInfo);
        Thread.sleep(150);
        return List.of("order-1", "order-2", "order-3");
    }

    static double fetchDiscount() throws InterruptedException {
        System.out.println("[" + Thread.currentThread().getName() + "] fetchDiscount started");
        Thread.sleep(100);
        return 0.1;
    }

    static String mergeResult(List<String> orders, double discount) {
        System.out.println("[" + Thread.currentThread().getName() + "] mergeResult");
        return "Orders=" + orders + " discount=" + (int)(discount * 100) + "%";
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Pipeline: user → enrich → orders + discount → merge ===\n");

        // Independent discount fetch starts immediately in parallel with the user pipeline
        CompletableFuture<Double> discountFuture = CompletableFuture
                .supplyAsync(() -> {
                    try { return fetchDiscount(); }
                    catch (InterruptedException e) { throw new CompletionException(e); }
                }, executor);

        CompletableFuture<String> pipeline = CompletableFuture
                // Stage 1: fetch user
                .supplyAsync(() -> {
                    try { return fetchUser(); }
                    catch (InterruptedException e) { throw new CompletionException(e); }
                }, executor)

                // Stage 2: enrich user (synchronous transform)
                .thenApplyAsync(userId -> {
                    try { return enrichUser(userId); }
                    catch (InterruptedException e) { throw new CompletionException(e); }
                }, executor)

                // Stage 3: fetch orders (async, flat-mapped)
                .thenCompose(userInfo -> CompletableFuture.supplyAsync(() -> {
                    try { return fetchOrders(userInfo); }
                    catch (InterruptedException e) { throw new CompletionException(e); }
                }, executor))

                // Stage 4: combine with discount from parallel fetch
                .thenCombine(discountFuture, CompletableFutureDemo::mergeResult)

                // Stage 5: recovery if any stage threw
                .exceptionally(t -> {
                    System.out.println("[" + Thread.currentThread().getName()
                            + "] pipeline failed: " + t.getMessage() + " → returning default");
                    return "default-response";
                })

                // Stage 6: log completion (side effect, does not transform result)
                .whenComplete((result, err) -> {
                    if (err != null)
                        System.out.println("[" + Thread.currentThread().getName()
                                + "] whenComplete: error=" + err.getMessage());
                    else
                        System.out.println("[" + Thread.currentThread().getName()
                                + "] whenComplete: result=" + result);
                });

        String result = pipeline.join(); // block main thread until pipeline completes
        System.out.println("\nFinal result: " + result);

        // --- Failure path ---
        System.out.println("\n=== Failure path: exceptionally recovers ===\n");

        CompletableFuture<String> failingPipeline = CompletableFuture
                .supplyAsync(() -> {
                    System.out.println("[" + Thread.currentThread().getName()
                            + "] throwing simulated exception");
                    throw new CompletionException(new RuntimeException("service-timeout"));
                }, executor)
                .thenApply(s -> s.toUpperCase()) // skipped on exception
                .exceptionally(t -> {
                    System.out.println("[" + Thread.currentThread().getName()
                            + "] exceptionally caught: " + t.getCause().getMessage());
                    return "fallback-value";
                })
                .whenComplete((r, e) -> System.out.println("[" + Thread.currentThread().getName()
                        + "] failure path whenComplete: result=" + r));

        System.out.println("Failure path result: " + failingPipeline.join());

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        System.out.println("\nAll demos complete.");
    }
}
```

## Gotchas

**thenApply runs on whichever thread completed the previous stage, which may be the main thread if the future was already done when thenApply was called.** If a transformation is CPU-intensive or should run on a specific thread pool, always use thenApplyAsync with an explicit executor. Relying on implicit thread assignment makes the execution model unpredictable and can accidentally run heavy work on threads that should not be blocked.

**If no exceptionally or handle is attached and a stage throws, the exception is silently stored in the CompletableFuture.** It will only surface when get() or join() is called. If no code ever calls get() or join() — which is common in fire-and-forget pipelines — the exception is lost entirely. Always attach a whenComplete or handle at the end of every pipeline to at least log failures.

**allOf() returns CompletableFuture<Void> and there is no way to get the individual results from it directly.** After calling allOf(f1, f2, f3).join(), each future must be accessed by calling f1.join(), f2.join(), f3.join() individually. A common mistake is to try to extract results from the allOf future itself rather than from the original futures.

**thenCompose must return a CompletableFuture — using thenApply where thenCompose is needed produces CompletableFuture<CompletableFuture<T>>.** The inner future is treated as an opaque value rather than being unwrapped. The type checker will flag this in most cases, but suppressed warnings or raw types can mask the problem. Use thenCompose whenever the mapping function itself performs an async operation.

**Supplying no executor to supplyAsync means all async stages run on ForkJoinPool.commonPool().** Long-running or blocking tasks on commonPool starve parallel streams, ForkJoinPool-based recursive computations, and other CompletableFutures in the same JVM that depend on commonPool. Always provide a dedicated executor for blocking IO or long-running CPU work.

**CompletableFuture.cancel() completes the future with CancellationException but does not interrupt the underlying computation thread.** The thread running the supplyAsync supplier continues running until it finishes normally. This is different from Future.cancel(true) on a ThreadPoolExecutor-submitted Callable, which sends an interrupt. If cancellation of the actual computation is needed, the task itself must poll a shared flag or use a different cancellation mechanism.
