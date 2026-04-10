/*
 * StampedLockDemo — Main
 *
 * One writer moves a 2D point repeatedly while two readers continuously
 * compute the distance from the origin. Readers attempt the optimistic path
 * first; when a write races with a read, validate() fails and the reader
 * falls back to a full read lock.
 *
 * The final counts show how often each path was taken, confirming that most
 * reads succeed without any lock and only a few require the fallback.
 */
package examples.stampedlockdemo;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        Point point = new Point(0.0, 0.0);

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                point.move(1.0, 1.0);
                try { Thread.sleep(60); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); return;
                }
            }
        }, "writer");

        Thread reader1 = new Thread(() -> {
            for (int i = 0; i < 8; i++) {
                point.distanceFromOrigin();
                try { Thread.sleep(40); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); return;
                }
            }
        }, "reader-1");

        Thread reader2 = new Thread(() -> {
            for (int i = 0; i < 8; i++) {
                point.distanceFromOrigin();
                try { Thread.sleep(55); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); return;
                }
            }
        }, "reader-2");

        writer.start();
        reader1.start();
        reader2.start();

        writer.join();
        reader1.join();
        reader2.join();

        System.out.println("final position  : " + point);
        System.out.println("optimistic reads: " + point.getOptimisticCount());
        System.out.println("fallback reads  : " + point.getFallbackCount());
    }
}
