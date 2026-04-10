/*
 * CreatingThreadsDemo — Main
 *
 * Demonstrates the three mechanisms for defining thread work in Java:
 *   1. Extending Thread      (CounterThread)
 *   2. Implementing Runnable (CounterTask)
 *   3. Implementing Callable (SumCallable)
 *
 * Observable outputs are thread states after join() and the Callable's return
 * value. The final section shows that calling run() directly never starts a
 * thread — the object stays in NEW.
 */
package examples.creatingthreadsdemo;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class Main {

    public static void main(String[] args) throws InterruptedException, ExecutionException {

        // --- Mechanism 1: Thread subclass ---
        // start() creates the OS thread and calls run() on it.
        // Calling run() directly instead would execute on the main thread — no new thread.
        CounterThread t1 = new CounterThread("counter-subclass", 5);
        t1.start();
        t1.join();
        System.out.println("Thread subclass  — state after join:            " + t1.getState());

        // --- Mechanism 2: Runnable, interrupted mid-run ---
        // interrupt() on a sleeping thread causes InterruptedException; CounterTask
        // restores the flag so the thread's interrupt status is visible after it exits.
        Thread t2 = new Thread(new CounterTask(20), "runnable-counter");
        t2.start();
        Thread.sleep(60); // let a few iterations complete before interrupting
        t2.interrupt();
        t2.join();
        System.out.println("Runnable         — state after interrupt + join: " + t2.getState());

        // --- Mechanism 3: Callable wrapped in FutureTask ---
        // FutureTask implements Runnable (accepted by Thread) and Future<V>
        // (exposes the return value). get() blocks until the result is ready,
        // but join() already ensures the thread is done before we call it here.
        FutureTask<Long> future = new FutureTask<>(new SumCallable(1_000_000L));
        Thread t3 = new Thread(future, "sum-callable");
        t3.start();
        t3.join();
        System.out.println("Callable         — sum(1..1_000_000) = " + future.get());
        System.out.println("Callable         — state after join:            " + t3.getState());

        // --- run() vs start() ---
        // run() is a plain method call; it executes on the calling thread and never
        // advances the Thread object's state beyond NEW.
        CounterThread t4 = new CounterThread("never-started", 3);
        t4.run(); // runs on main thread; t4 remains in NEW throughout
        System.out.println("run() not start() — state (never started):      " + t4.getState());
    }
}
