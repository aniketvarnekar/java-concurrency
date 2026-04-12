/*
 * ThreadLifecycleDemo — Main
 *
 * Places named threads into each of the six Thread.State values and prints
 * the observed state. The printed states are the observable result of the
 * demonstration; all concurrency reasoning is in the supporting task files.
 */
package examples.threadlifecycledemo;

public class Main {

    public static void main(String[] args) throws InterruptedException {

        // NEW: the Thread object exists but start() has not been called.
        Thread newThread = new Thread(() -> {
        }, "lifecycle-new");
        System.out.println(newThread.getName() + ":           " + newThread.getState());

        // RUNNABLE: thread is registered with the scheduler and eligible to run.
        BusyTask busyTask = new BusyTask();
        Thread runnableThread = new Thread(busyTask, "lifecycle-runnable");
        runnableThread.start();
        Thread.sleep(50); // observation window — not a synchronization guarantee
        System.out.println(runnableThread.getName() + ":      " + runnableThread.getState());
        busyTask.done = true;
        runnableThread.join();

        // BLOCKED: thread is waiting to acquire an intrinsic monitor held by this thread.
        Object blockLock = new Object();
        Thread blockedThread = new Thread(new BlockedTask(blockLock), "lifecycle-blocked");
        synchronized (blockLock) {
            blockedThread.start();
            Thread.sleep(50);
            System.out.println(blockedThread.getName() + ":       " + blockedThread.getState());
            // exiting this block releases the lock; blockedThread will proceed
        }
        blockedThread.join();

        // WAITING: thread is suspended on Object.wait() until notify() is called.
        Object waitLock = new Object();
        WaitingTask waitingTask = new WaitingTask(waitLock);
        Thread waitingThread = new Thread(waitingTask, "lifecycle-waiting");
        waitingThread.start();
        Thread.sleep(50);
        System.out.println(waitingThread.getName() + ":       " + waitingThread.getState());
        synchronized (waitLock) {
            waitingTask.notified = true;
            waitLock.notify();
        }
        waitingThread.join();

        // TIMED_WAITING: thread is suspended for a bounded duration via sleep().
        Thread sleepingThread = new Thread(new SleepingTask(), "lifecycle-timed-waiting");
        sleepingThread.start();
        Thread.sleep(50);
        System.out.println(sleepingThread.getName() + ": " + sleepingThread.getState());
        sleepingThread.interrupt(); // SleepingTask restores the flag and exits cleanly
        sleepingThread.join();

        // TERMINATED: run() has returned; the thread cannot be restarted.
        Thread termThread = new Thread(() -> {
        }, "lifecycle-terminated");
        termThread.start();
        termThread.join();
        System.out.println(termThread.getName() + ":    " + termThread.getState());
    }
}
