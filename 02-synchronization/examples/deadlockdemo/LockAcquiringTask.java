/*
 * LockAcquiringTask — acquires two locks in a caller-specified order.
 *
 * When Thread-Alpha uses (lockA, lockB) and Thread-Beta uses (lockB, lockA),
 * the opposite orders produce a circular wait: Alpha holds lockA waiting for
 * lockB, while Beta holds lockB waiting for lockA. Neither can proceed.
 *
 * When both threads use the same order, one blocks on the first lock until
 * the other releases it. No cycle forms and both complete.
 *
 * The prints showing lock acquisition progress are the observable record of
 * how far each thread advanced before the deadlock formed.
 */
package examples.deadlockdemo;

class LockAcquiringTask implements Runnable {

    private final Object firstLock;
    private final Object secondLock;

    LockAcquiringTask(Object firstLock, Object secondLock) {
        this.firstLock = firstLock;
        this.secondLock = secondLock;
    }

    @Override
    public void run() {
        String name = Thread.currentThread().getName();
        synchronized (firstLock) {
            System.out.println("[" + name + "] acquired first lock");
            // Hold first lock long enough for the other thread to acquire its first lock,
            // ensuring both threads are committed before either tries for the second.
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            System.out.println("[" + name + "] waiting for second lock...");
            synchronized (secondLock) {
                // Reachable only in the fixed (consistent-order) scenario.
                System.out.println("[" + name + "] acquired second lock — completed");
            }
        }
    }
}
