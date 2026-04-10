/*
 * BlockedTask — demonstrates the BLOCKED state.
 *
 * Attempts to enter a synchronized block on a monitor that Main already holds.
 * The JVM parks this thread in BLOCKED until the monitor is released.
 * Note: BLOCKED applies only to intrinsic (synchronized) monitors, not to
 * java.util.concurrent locks, which park threads in WAITING via LockSupport.
 */
package examples.threadlifecycledemo;

class BlockedTask implements Runnable {

    private final Object lock;

    BlockedTask(Object lock) {
        this.lock = lock;
    }

    @Override
    public void run() {
        synchronized (lock) {
            // acquired — nothing to do; BLOCKED was the state worth observing
        }
    }
}
