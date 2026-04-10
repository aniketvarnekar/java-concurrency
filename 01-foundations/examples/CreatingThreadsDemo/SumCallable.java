/*
 * SumCallable — Callable<Long> implementation used in CreatingThreadsDemo.
 *
 * Callable differs from Runnable in two ways: call() returns a typed value,
 * and it can declare checked exceptions. It must be wrapped in a FutureTask
 * to run on a plain Thread; the result is retrieved via FutureTask.get()
 * after the thread terminates.
 */
package examples.creatingthreadsdemo;

import java.util.concurrent.Callable;

class SumCallable implements Callable<Long> {

    private final long limit;

    SumCallable(long limit) {
        this.limit = limit;
    }

    @Override
    public Long call() {
        long sum = 0;
        for (long i = 1; i <= limit; i++) {
            sum += i;
        }
        return sum;
    }
}
