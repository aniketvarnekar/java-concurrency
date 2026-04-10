/*
 * CounterThread — Thread subclass used in CreatingThreadsDemo.
 *
 * Extending Thread is the oldest mechanism. It conflates the execution
 * vehicle with the task, preventing this class from extending anything else
 * and making it incompatible with ExecutorService without adaptation.
 */
package examples.creatingthreadsdemo;

class CounterThread extends Thread {

    private final int limit;

    CounterThread(String name, int limit) {
        super(name); // thread name set at construction via Thread constructor
        this.limit = limit;
    }

    @Override
    public void run() {
        for (int i = 1; i <= limit; i++) {
            // Honor interruption at each step rather than running to completion.
            if (isInterrupted()) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                // sleep() clears the flag on throw — restore it before returning
                // so that callers checking isInterrupted() still see it set.
                interrupt();
                return;
            }
        }
    }
}
