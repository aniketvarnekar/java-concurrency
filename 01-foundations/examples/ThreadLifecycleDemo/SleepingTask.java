/*
 * SleepingTask — demonstrates the TIMED_WAITING state.
 *
 * Sleeps for a long duration so Main has time to observe the state before the
 * timeout expires. Main interrupts this thread after observation; sleep()
 * responds to interruption by throwing InterruptedException and clearing the
 * flag, so the flag is restored before returning.
 */
package examples.threadlifecycledemo;

class SleepingTask implements Runnable {

    @Override
    public void run() {
        try {
            Thread.sleep(30_000);
        } catch (InterruptedException e) {
            // Restore the flag; callers checking isInterrupted() will see it set.
            Thread.currentThread().interrupt();
        }
    }
}
