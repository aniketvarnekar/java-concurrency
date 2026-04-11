/*
 * StructuredConcurrencyDemo — Main
 *
 * Demonstrates two StructuredTaskScope shutdown policies:
 *
 * ShutdownOnFailure (all-or-nothing):
 *   fetchUser and fetchOrders run concurrently. fetchOrders throws after 300ms.
 *   The scope cancels fetchUser, and throwIfFailed() re-throws the exception
 *   to the caller. Neither subtask can outlive the scope block.
 *
 * ShutdownOnSuccess (hedging / first-wins):
 *   callServiceA (400ms) and callServiceB (150ms) race. ServiceB wins;
 *   the scope cancels ServiceA by interrupting it. scope.result() returns
 *   the winning value after scope.join() returns.
 *
 * The thread names in each print confirm that subtasks run on virtual threads
 * managed by the scope, not on the caller's thread.
 *
 * Requires Java 21+.
 */
package examples.structuredconcurrencydemo;

import java.util.concurrent.ExecutionException;
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
    // ShutdownOnFailure: both subtasks must succeed; one failure cancels all.
    // -------------------------------------------------------------------------
    static String fetchUserAndOrders() throws InterruptedException, ExecutionException {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {

            StructuredTaskScope.Subtask<String> userTask =
                    scope.fork(Main::fetchUser);

            StructuredTaskScope.Subtask<String> ordersTask =
                    scope.fork(Main::fetchOrders);

            scope.join()           // block until policy is satisfied
                 .throwIfFailed(); // re-throw first failure as ExecutionException

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
    // ShutdownOnSuccess: first successful subtask wins; the other is cancelled.
    // -------------------------------------------------------------------------
    static String queryRedundantServices() throws InterruptedException {
        try (var scope = new StructuredTaskScope.ShutdownOnSuccess<String>()) {

            scope.fork(Main::callServiceA);
            scope.fork(Main::callServiceB);

            scope.join(); // returns when the first subtask succeeds

            return scope.result(); // returns the winning value
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
