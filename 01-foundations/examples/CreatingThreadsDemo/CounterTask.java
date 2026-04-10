/*
 * CounterTask — Runnable implementation used in CreatingThreadsDemo.
 *
 * Implementing Runnable decouples the task from the thread. The same instance
 * can be passed to a Thread constructor or submitted directly to an
 * ExecutorService without modification — unlike a Thread subclass.
 */
package examples.creatingthreadsdemo;

class CounterTask implements Runnable {

    private final int limit;

    CounterTask(int limit) {
        this.limit = limit;
    }

    @Override
    public void run() {
        for (int i = 1; i <= limit; i++) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
