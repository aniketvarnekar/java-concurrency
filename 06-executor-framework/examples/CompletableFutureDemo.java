/**
 * Demonstrates CompletableFuture pipeline: supplyAsync, thenApplyAsync, thenCompose,
 * thenCombine, exceptionally, whenComplete.
 *
 * Run: javac CompletableFutureDemo.java && java CompletableFutureDemo
 */

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CompletableFutureDemo {

    // Custom named thread pool — explicit executor for all async stages
    static final AtomicInteger THREAD_COUNT = new AtomicInteger(1);
    static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "cf-worker-" + THREAD_COUNT.getAndIncrement());
        return t;
    });

    // -------------------------------------------------------------------------
    // Simulated async service calls
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
        log("mergeResult START");
        String result = "orders=" + orders + " discount=" + (int)(discount * 100) + "%";
        log("mergeResult DONE → " + result);
        return result;
    }

    // -------------------------------------------------------------------------
    // Helper utilities
    // -------------------------------------------------------------------------

    static void log(String msg) {
        System.out.printf("[%s] %s%n", Thread.currentThread().getName(), msg);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // Wrap an InterruptedException-throwing supplier for use in supplyAsync lambdas
    static <T> T wrap(CheckedSupplier<T> supplier) {
        try { return supplier.get(); }
        catch (Exception e) { throw new CompletionException(e); }
    }

    @FunctionalInterface
    interface CheckedSupplier<T> { T get() throws Exception; }

    // -------------------------------------------------------------------------
    // Main demos
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Demo 1: Full async pipeline ===\n");
        runSuccessPipeline();

        System.out.println("\n=== Demo 2: Failure path — exceptionally recovers ===\n");
        runFailurePipeline();

        EXECUTOR.shutdown();
        EXECUTOR.awaitTermination(10, TimeUnit.SECONDS);
        System.out.println("\nAll demos complete.");
    }

    static void runSuccessPipeline() throws InterruptedException {
        long start = System.currentTimeMillis();

        // Stage: fetch discount runs independently in parallel with the user pipeline
        CompletableFuture<Double> discountFuture = CompletableFuture
                .supplyAsync(() -> wrap(CompletableFutureDemo::fetchDiscount), EXECUTOR);

        CompletableFuture<String> pipeline = CompletableFuture
                // Stage 1: supplyAsync — fetch user (runs on EXECUTOR thread)
                .supplyAsync(() -> wrap(CompletableFutureDemo::fetchUser), EXECUTOR)

                // Stage 2: thenApplyAsync — enrich user (transform on EXECUTOR thread)
                .thenApplyAsync(userId -> wrap(() -> enrichUser(userId)), EXECUTOR)

                // Stage 3: thenCompose — fetch orders (another async stage, flat-mapped)
                .thenCompose(userInfo -> CompletableFuture.supplyAsync(
                        () -> wrap(() -> fetchOrders(userInfo)), EXECUTOR))

                // Stage 4: thenCombine — merge orders with discount (both must complete)
                .thenCombine(discountFuture,
                        CompletableFutureDemo::mergeOrdersAndDiscount)

                // Stage 5: exceptionally — fallback value on any pipeline exception
                .exceptionally(t -> {
                    log("exceptionally: caught " + t.getCause().getMessage()
                            + " → returning default-response");
                    return "default-response";
                })

                // Stage 6: whenComplete — side-effect log (does not transform result)
                .whenComplete((result, err) -> {
                    if (err != null)
                        log("whenComplete: FAILED — " + err.getMessage());
                    else
                        log("whenComplete: SUCCESS — " + result);
                });

        String result = pipeline.join(); // block until pipeline completes
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("%nFinal result: %s  (elapsed: %dms)%n", result, elapsed);
        System.out.println("Note: parallel stages (user pipeline + discount) overlap in time.");
    }

    static void runFailurePipeline() throws InterruptedException {
        CompletableFuture<String> failing = CompletableFuture
                // Stage 1: throws immediately
                .supplyAsync(() -> {
                    log("failingStage: about to throw");
                    throw new CompletionException(new RuntimeException("service-unavailable"));
                }, EXECUTOR)

                // Stage 2: skipped because previous stage threw
                .thenApplyAsync(s -> {
                    log("thenApply: this should NOT run (upstream threw)");
                    return s.toUpperCase();
                }, EXECUTOR)

                // Stage 3: exceptionally — recovery point
                .exceptionally(t -> {
                    log("exceptionally: recovering from " + t.getCause().getMessage());
                    return "fallback-value";
                })

                // Stage 4: runs after recovery — result is now "fallback-value"
                .thenApplyAsync(s -> {
                    log("thenApply after recovery: result = " + s);
                    return s + "-enriched";
                }, EXECUTOR)

                // Side-effect log
                .whenComplete((result, err) -> {
                    if (err != null)
                        log("whenComplete: FAILED — " + err.getMessage());
                    else
                        log("whenComplete: result after recovery = " + result);
                });

        String result = failing.join();
        System.out.println("Failure path final result: " + result);
    }
}
