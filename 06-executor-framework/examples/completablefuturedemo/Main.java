/*
 * CompletableFutureDemo — Main
 *
 * Demonstrates a multi-stage async pipeline using CompletableFuture composition:
 *   - supplyAsync: starts computation on an executor thread
 *   - thenApplyAsync: transforms result on an executor thread (vs thenApply which runs on
 *     whichever thread completed the previous stage)
 *   - thenCompose: flat-maps one async stage into another (avoids CompletableFuture<CF<T>>)
 *   - thenCombine: merges two independent futures when both complete
 *   - exceptionally: recovery point — runs only if the chain threw; provides a fallback value
 *   - whenComplete: side-effect hook — runs on success and failure, does not transform result
 *
 * The log() calls include the current thread name to show which executor thread runs each
 * stage. The discount fetch runs in parallel with the user pipeline — the elapsed time
 * printed at the end confirms this overlap.
 *
 * The second pipeline demonstrates the failure propagation path: thenApplyAsync is skipped
 * entirely when the upstream stage threw, and exceptionally intercepts the exception.
 */
package examples.completablefuturedemo;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    static final AtomicInteger THREAD_COUNT = new AtomicInteger(1);
    static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "cf-worker-" + THREAD_COUNT.getAndIncrement());
        return t;
    });

    // -------------------------------------------------------------------------
    // Simulated async service calls — each sleeps to represent IO latency
    // -------------------------------------------------------------------------

    static String fetchUser() {
        log("fetchUser START");
        sleep(200);
        log("fetchUser DONE");
        return "user-42";
    }

    static String enrichUser(String userId) {
        log("enrichUser START for " + userId);
        sleep(100);
        log("enrichUser DONE");
        return "User{id=42, name=Alice}";
    }

    static List<String> fetchOrders(String userInfo) {
        log("fetchOrders START for " + userInfo);
        sleep(150);
        log("fetchOrders DONE");
        return Arrays.asList("order-101", "order-202", "order-303");
    }

    static double fetchDiscount() {
        log("fetchDiscount START");
        sleep(100);
        log("fetchDiscount DONE");
        return 0.10;
    }

    static String mergeOrdersAndDiscount(List<String> orders, double discount) {
        log("merge START");
        String result = "orders=" + orders + " discount=" + (int)(discount * 100) + "%";
        log("merge DONE → " + result);
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static void log(String msg) {
        System.out.printf("[%s] %s%n", Thread.currentThread().getName(), msg);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // Wraps a checked-exception-throwing supplier for use inside supplyAsync lambdas,
    // which require an unchecked Supplier. CompletionException is the correct wrapper.
    static <T> T wrap(CheckedSupplier<T> supplier) {
        try { return supplier.get(); }
        catch (Exception e) { throw new CompletionException(e); }
    }

    @FunctionalInterface
    interface CheckedSupplier<T> { T get() throws Exception; }

    // -------------------------------------------------------------------------
    // Main
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        runSuccessPipeline();
        runFailurePipeline();

        EXECUTOR.shutdown();
        EXECUTOR.awaitTermination(10, TimeUnit.SECONDS);
    }

    // Pipeline: fetchUser → enrichUser → fetchOrders + fetchDiscount (parallel) → merge
    static void runSuccessPipeline() throws InterruptedException {
        long start = System.currentTimeMillis();

        // Discount runs in parallel with the user pipeline — no dependency between them.
        CompletableFuture<Double> discountFuture = CompletableFuture
                .supplyAsync(() -> wrap(Main::fetchDiscount), EXECUTOR);

        CompletableFuture<String> pipeline = CompletableFuture
                .supplyAsync(() -> wrap(Main::fetchUser), EXECUTOR)
                .thenApplyAsync(userId -> wrap(() -> enrichUser(userId)), EXECUTOR)
                .thenCompose(userInfo -> CompletableFuture.supplyAsync(
                        () -> wrap(() -> fetchOrders(userInfo)), EXECUTOR))
                .thenCombine(discountFuture, Main::mergeOrdersAndDiscount)
                .exceptionally(t -> {
                    log("exceptionally: caught " + t.getCause().getMessage()
                            + " → returning default-response");
                    return "default-response";
                })
                .whenComplete((result, err) -> {
                    if (err != null) log("whenComplete: FAILED — " + err.getMessage());
                    else             log("whenComplete: SUCCESS — " + result);
                });

        String result  = pipeline.join();
        long   elapsed = System.currentTimeMillis() - start;
        System.out.printf("final result: %s  (elapsed: %dms)%n", result, elapsed);
    }

    // Failure path: upstream stage throws; thenApplyAsync is skipped; exceptionally recovers.
    static void runFailurePipeline() {
        CompletableFuture<String> failing = CompletableFuture
                .<String>supplyAsync(() -> {
                    log("failingStage: throwing service-unavailable");
                    throw new CompletionException(new RuntimeException("service-unavailable"));
                }, EXECUTOR)

                // This stage is skipped because the previous stage threw.
                .thenApplyAsync(s -> {
                    log("thenApply: skipped (upstream threw)");
                    return s.toUpperCase();
                }, EXECUTOR)

                .exceptionally(t -> {
                    log("exceptionally: recovering from " + t.getCause().getMessage());
                    return "fallback-value";
                })

                // Runs after recovery — the pipeline continues with "fallback-value".
                .thenApplyAsync(s -> {
                    log("thenApply after recovery: result = " + s);
                    return s + "-enriched";
                }, EXECUTOR)

                .whenComplete((result, err) -> {
                    if (err != null) log("whenComplete: FAILED — " + err.getMessage());
                    else             log("whenComplete: result after recovery = " + result);
                });

        System.out.println("failure path result: " + failing.join());
    }
}
