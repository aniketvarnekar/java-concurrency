/*
 * StructuredConcurrencyDemo — Main
 *
 * Demonstrates two StructuredTaskScope join policies (Java 25 API):
 *
 * Joiner.awaitAllSuccessfulOrThrow() (all-or-nothing):
 *   fetchUser and fetchOrders run concurrently. fetchOrders throws after 300ms.
 *   scope.join() throws FailedException (unchecked), which cancels fetchUser.
 *   Neither subtask can outlive the scope block.
 *
 * Joiner.anySuccessfulResultOrThrow() (hedging / first-wins):
 *   callServiceA (400ms) and callServiceB (150ms) race. ServiceB wins;
 *   the scope cancels ServiceA by interrupting it. scope.join() returns
 *   the winning value directly.
 *
 * The thread names in each print confirm that subtasks run on virtual threads
 * managed by the scope, not on the caller's thread.
 *
 * Requires Java 25+ (StructuredTaskScope is a preview feature).
 */
package examples.structuredconcurrencydemo;

import java.util.concurrent.StructuredTaskScope;

public class Main {

    public static void main(String[] args) {
        try {
            String result = fetchUserAndOrders();
            System.out.println("Result: " + result);
        } catch (Exception e) {
            System.out.println("Caught: " + e.getClass().getSimpleName()
                    + " — " + e.getMessage());
        }

        System.out.println();

        try {
            String winner = queryRedundantServices();
            System.out.println("Winner: " + winner);
        } catch (Exception e) {
            System.out.println("Caught: " + e.getClass().getSimpleName()
                    + " — " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // awaitAllSuccessfulOrThrow: both subtasks must succeed; one failure cancels all.
    // -------------------------------------------------------------------------
    static String fetchUserAndOrders() throws InterruptedException {
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<String>awaitAllSuccessfulOrThrow())) {

            StructuredTaskScope.Subtask<String> userTask =
                    scope.fork(Main::fetchUser);

            StructuredTaskScope.Subtask<String> ordersTask =
                    scope.fork(Main::fetchOrders);

            // Blocks until all subtasks complete; throws FailedException if any failed.
            scope.join();

            // Only reached if both subtasks succeeded
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
        // Simulate a downstream service failure
        System.out.println("  [order-service]  FAILING   on " + Thread.currentThread().getName());
        throw new RuntimeException("orders service unavailable");
    }

    // -------------------------------------------------------------------------
    // anySuccessfulResultOrThrow: first successful subtask wins; the other is cancelled.
    // -------------------------------------------------------------------------
    static String queryRedundantServices() throws InterruptedException {
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<String>anySuccessfulResultOrThrow())) {

            scope.fork(Main::callServiceA);
            scope.fork(Main::callServiceB);

            // Blocks until the first subtask succeeds; returns its result directly.
            return scope.join();
        }
    }

    static String callServiceA() throws InterruptedException {
        System.out.println("  [service-A] starting  on " + Thread.currentThread().getName());
        try {
            Thread.sleep(400); // slower path
            System.out.println("  [service-A] completed on " + Thread.currentThread().getName());
            return "result-from-A";
        } catch (InterruptedException e) {
            // Scope cancelled this subtask because another succeeded first
            System.out.println("  [service-A] cancelled on " + Thread.currentThread().getName());
            throw e;
        }
    }

    static String callServiceB() throws InterruptedException {
        System.out.println("  [service-B] starting  on " + Thread.currentThread().getName());
        try {
            Thread.sleep(150); // faster path
            System.out.println("  [service-B] completed on " + Thread.currentThread().getName());
            return "result-from-B";
        } catch (InterruptedException e) {
            System.out.println("  [service-B] cancelled on " + Thread.currentThread().getName());
            throw e;
        }
    }
}
