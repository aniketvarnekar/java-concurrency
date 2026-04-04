/**
 * Demonstrates StructuredTaskScope.ShutdownOnFailure and ShutdownOnSuccess.
 *
 * Shows:
 *   - ShutdownOnFailure: parallel fetch of user + orders; one fails, both cancelled,
 *     exception propagated to caller
 *   - ShutdownOnSuccess: two redundant service calls; fastest wins, other cancelled
 *
 * Run:
 *   javac --enable-preview --release 21 StructuredConcurrencyDemo.java
 *   java  --enable-preview StructuredConcurrencyDemo
 *
 * Requires Java 21+ with preview features enabled.
 */
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;

public class StructuredConcurrencyDemo {

    public static void main(String[] args) {
        System.out.println("=== Demo 1: ShutdownOnFailure ===");
        try {
            String result = fetchUserAndOrders();
            System.out.println("Result: " + result);
        } catch (Exception e) {
            System.out.println("Caught: " + e.getClass().getSimpleName()
                               + " — " + e.getMessage());
        }

        System.out.println();
        System.out.println("=== Demo 2: ShutdownOnSuccess ===");
        try {
            String winner = queryRedundantServices();
            System.out.println("Winner: " + winner);
        } catch (Exception e) {
            System.out.println("Caught: " + e.getClass().getSimpleName()
                               + " — " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Demo 1: ShutdownOnFailure
    // Fetch user data and order data in parallel.
    // If either subtask fails, the scope shuts down and the exception
    // propagates to the caller.
    // ------------------------------------------------------------------
    static String fetchUserAndOrders() throws InterruptedException, ExecutionException {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {

            StructuredTaskScope.Subtask<String> userTask =
                scope.fork(() -> fetchUser());

            StructuredTaskScope.Subtask<String> ordersTask =
                scope.fork(() -> fetchOrders());

            scope.join()           // wait for completion policy
                 .throwIfFailed(); // rethrow first failure as ExecutionException

            // Both succeeded — safe to call .get()
            return userTask.get() + " with " + ordersTask.get();
        }
    }

    static String fetchUser() throws InterruptedException {
        System.out.println("  [user-service]   starting  on " + Thread.currentThread().getName());
        Thread.sleep(200);
        System.out.println("  [user-service]   completed on " + Thread.currentThread().getName());
        return "user-42";
    }

    static String fetchOrders() throws InterruptedException {
        System.out.println("  [order-service]  starting  on " + Thread.currentThread().getName());
        Thread.sleep(300);
        // Simulate a service failure
        System.out.println("  [order-service]  FAILING   on " + Thread.currentThread().getName());
        throw new RuntimeException("orders service unavailable");
    }

    // ------------------------------------------------------------------
    // Demo 2: ShutdownOnSuccess
    // Query two redundant services; return the first successful result
    // and cancel the other.
    // ------------------------------------------------------------------
    static String queryRedundantServices() throws InterruptedException {
        try (var scope = new StructuredTaskScope.ShutdownOnSuccess<String>()) {

            scope.fork(() -> callServiceA());
            scope.fork(() -> callServiceB());

            scope.join(); // waits until one succeeds (or all fail)

            return scope.result(); // returns first successful result
        }
    }

    static String callServiceA() throws InterruptedException {
        System.out.println("  [service-A] starting  on " + Thread.currentThread().getName());
        try {
            Thread.sleep(400); // slower
            System.out.println("  [service-A] completed on " + Thread.currentThread().getName());
            return "result-from-A";
        } catch (InterruptedException e) {
            System.out.println("  [service-A] cancelled on " + Thread.currentThread().getName());
            throw e;
        }
    }

    static String callServiceB() throws InterruptedException {
        System.out.println("  [service-B] starting  on " + Thread.currentThread().getName());
        try {
            Thread.sleep(150); // faster
            System.out.println("  [service-B] completed on " + Thread.currentThread().getName());
            return "result-from-B";
        } catch (InterruptedException e) {
            System.out.println("  [service-B] cancelled on " + Thread.currentThread().getName());
            throw e;
        }
    }
}
