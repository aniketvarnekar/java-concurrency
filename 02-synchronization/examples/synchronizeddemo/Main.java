/*
 * SynchronizedDemo — Main
 *
 * Exercises the three forms of synchronized and demonstrates reentrancy.
 * Correctness is verified by checking final values after all concurrent
 * operations complete — an incorrect value would mean synchronization failed
 * to prevent a race condition.
 */
package examples.synchronizeddemo;

public class Main {

    static void joinAll(Thread... threads) {
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public static void main(String[] args) throws InterruptedException {

        // --- Synchronized instance methods + reentrancy via transfer ---
        // Both threads transfer in the same direction; transfer() calls withdraw()
        // and deposit() on `this`, relying on reentrancy to avoid self-deadlock.
        BasicAccount alice = new BasicAccount("Alice", 1000);
        BasicAccount bob   = new BasicAccount("Bob",   1000);

        Thread t1 = new Thread(() -> { for (int i = 0; i < 5; i++) alice.transfer(bob, 100); }, "bank-worker-1");
        Thread t2 = new Thread(() -> { for (int i = 0; i < 5; i++) alice.transfer(bob, 100); }, "bank-worker-2");

        t1.start(); t2.start();
        joinAll(t1, t2);

        // sum must remain 2000 regardless of scheduling; any other value indicates a race
        System.out.println("Synchronized instance methods — sum (expected 2000): "
                + (alice.balance() + bob.balance()));

        // --- Synchronized static method ---
        // All registrations go through AccountRegistry.class as the monitor.
        Thread r1 = new Thread(() -> { AccountRegistry.register(); AccountRegistry.register(); }, "registrar-1");
        Thread r2 = new Thread(() -> { AccountRegistry.register(); AccountRegistry.register(); }, "registrar-2");

        r1.start(); r2.start();
        joinAll(r1, r2);

        System.out.println("Synchronized static method   — total (expected 4): "
                + AccountRegistry.total());

        // --- Synchronized block on private lock ---
        SafeAccount safe = new SafeAccount("Safe", 500);

        Thread s1 = new Thread(() -> { safe.deposit(300); safe.withdraw(100); }, "safe-worker-1");
        Thread s2 = new Thread(() -> { safe.deposit(200); safe.withdraw(150); }, "safe-worker-2");

        s1.start(); s2.start();
        joinAll(s1, s2);

        System.out.println("Private lock (block)         — balance (expected 750): "
                + safe.balance());

        // --- Reentrant recursive synchronized method ---
        Factorial fact = new Factorial();
        Thread factThread = new Thread(
                () -> System.out.println("Reentrant recursive          — 6! (expected 720): "
                        + fact.compute(6)),
                "factorial-worker");

        factThread.start();
        factThread.join();
    }
}
