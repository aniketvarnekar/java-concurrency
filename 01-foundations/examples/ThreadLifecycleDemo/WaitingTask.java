/*
 * WaitingTask — demonstrates the WAITING state.
 *
 * Calls Object.wait() on the provided monitor, suspending indefinitely until
 * Main calls notify(). The while loop around wait() guards against spurious
 * wakeups, which the Java spec explicitly permits.
 */
package examples.threadlifecycledemo;

class WaitingTask implements Runnable {

    private final Object lock;
    // Condition predicate written by Main before notify(); volatile ensures visibility.
    volatile boolean notified = false;

    WaitingTask(Object lock) {
        this.lock = lock;
    }

    @Override
    public void run() {
        synchronized (lock) {
            while (!notified) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
