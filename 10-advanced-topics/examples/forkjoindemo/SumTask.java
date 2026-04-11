/*
 * SumTask — RecursiveTask that computes the sum of a subrange of Main.DATA.
 *
 * When the range is larger than THRESHOLD, it forks the left half and
 * computes the right half inline on the current worker thread. This is the
 * canonical fork-one-compute-one pattern: the current thread stays productive
 * while the pool handles the left half asynchronously via work-stealing.
 */
package examples.forkjoindemo;

import java.util.concurrent.RecursiveTask;

class SumTask extends RecursiveTask<Long> {

    private final int lo;
    private final int hi;

    SumTask(int lo, int hi) {
        this.lo = lo;
        this.hi = hi;
    }

    @Override
    protected Long compute() {
        if (hi - lo <= Main.THRESHOLD) {
            // Base case: below threshold, sum sequentially in this thread
            long sum = 0;
            for (int i = lo; i < hi; i++) {
                sum += Main.DATA[i];
            }
            return sum;
        }

        // Recursive case: split the range in half
        int mid = lo + (hi - lo) / 2;
        SumTask left  = new SumTask(lo, mid);
        SumTask right = new SumTask(mid, hi);

        left.fork();                         // submit left to pool asynchronously
        long rightResult = right.compute();  // compute right in this thread
        long leftResult  = left.join();      // wait for left result

        return leftResult + rightResult;
    }
}
