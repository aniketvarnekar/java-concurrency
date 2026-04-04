/**
 * Demonstrates ReentrantLock: basic lock/unlock, tryLock for deadlock prevention,
 * and lockInterruptibly for cancellable waiting.
 *
 * Run: javac ReentrantLockDemo.java && java ReentrantLockDemo
 */

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class ReentrantLockDemo {

    // -------------------------------------------------------------------------
    // Demo 1: Basic lock/unlock with try/finally
    // -------------------------------------------------------------------------
    static class BankAccount {
        private final String name;
        private double balance;
        private final ReentrantLock lock = new ReentrantLock();

        BankAccount(String name, double initialBalance) {
            this.name = name;
            this.balance = initialBalance;
        }

        void deposit(double amount) {
            lock.lock();
            try {
                System.out.printf("[%s] depositing %.2f into %s (before: %.2f)%n",
                        Thread.currentThread().getName(), amount, name, balance);
                balance += amount;
                System.out.printf("[%s] deposit complete for %s (after: %.2f)%n",
                        Thread.currentThread().getName(), name, balance);
            } finally {
                lock.unlock();
            }
        }

        void withdraw(double amount) {
            lock.lock();
            try {
                System.out.printf("[%s] withdrawing %.2f from %s (before: %.2f)%n",
                        Thread.currentThread().getName(), amount, name, balance);
                if (balance >= amount) {
                    balance -= amount;
                    System.out.printf("[%s] withdrawal complete from %s (after: %.2f)%n",
                            Thread.currentThread().getName(), name, balance);
                } else {
                    System.out.printf("[%s] insufficient funds in %s%n",
                            Thread.currentThread().getName(), name);
                }
            } finally {
                lock.unlock();
            }
        }

        double getBalance() {
            lock.lock();
            try { return balance; } finally { lock.unlock(); }
        }

        ReentrantLock getLock() { return lock; }
        String getName() { return name; }
    }

    // -------------------------------------------------------------------------
    // Demo 2: tryLock deadlock prevention — two accounts, bidirectional transfers
    // -------------------------------------------------------------------------
    static boolean transferWithTryLock(BankAccount from, BankAccount to, double amount)
            throws InterruptedException {
        int attempts = 0;
        while (attempts < 5) {
            if (from.getLock().tryLock(100, TimeUnit.MILLISECONDS)) {
                try {
                    if (to.getLock().tryLock(100, TimeUnit.MILLISECONDS)) {
                        try {
                            if (from.getBalance() < amount) {
                                System.out.printf("[%s] insufficient funds in %s for transfer%n",
                                        Thread.currentThread().getName(), from.getName());
                                return false;
                            }
                            // Simulate work inside the critical section
                            Thread.sleep(10);
                            // Directly update without re-locking (already holding both locks)
                            System.out.printf("[%s] transferring %.2f from %s to %s%n",
                                    Thread.currentThread().getName(), amount,
                                    from.getName(), to.getName());
                            return true;
                        } finally {
                            to.getLock().unlock();
                        }
                    } else {
                        System.out.printf("[%s] could not lock %s, releasing %s and retrying (attempt %d)%n",
                                Thread.currentThread().getName(), to.getName(), from.getName(), attempts + 1);
                    }
                } finally {
                    from.getLock().unlock();
                }
            } else {
                System.out.printf("[%s] could not lock %s, retrying (attempt %d)%n",
                        Thread.currentThread().getName(), from.getName(), attempts + 1);
            }
            attempts++;
            Thread.sleep(20 + (long)(Math.random() * 30)); // randomised back-off
        }
        System.out.printf("[%s] gave up transferring from %s to %s after %d attempts%n",
                Thread.currentThread().getName(), from.getName(), to.getName(), attempts);
        return false;
    }

    // -------------------------------------------------------------------------
    // Demo 3: lockInterruptibly — waiting thread is interrupted by main
    // -------------------------------------------------------------------------
    static final ReentrantLock sharedLock = new ReentrantLock();

    static void runLockInterruptiblyDemo() throws InterruptedException {
        // holder thread acquires and holds the lock for 3 seconds
        Thread holder = new Thread(() -> {
            sharedLock.lock();
            try {
                System.out.printf("[%s] acquired shared lock, holding for 3 seconds%n",
                        Thread.currentThread().getName());
                Thread.sleep(3000);
                System.out.printf("[%s] releasing shared lock%n",
                        Thread.currentThread().getName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.printf("[%s] interrupted while holding lock%n",
                        Thread.currentThread().getName());
            } finally {
                sharedLock.unlock();
            }
        }, "lock-holder-thread");

        // waiter thread tries to acquire with lockInterruptibly
        Thread waiter = new Thread(() -> {
            try {
                System.out.printf("[%s] attempting lockInterruptibly...%n",
                        Thread.currentThread().getName());
                sharedLock.lockInterruptibly();
                try {
                    System.out.printf("[%s] acquired lock (this should not print if interrupted)%n",
                            Thread.currentThread().getName());
                } finally {
                    sharedLock.unlock();
                }
            } catch (InterruptedException e) {
                System.out.printf("[%s] was interrupted while waiting for lock — exiting gracefully%n",
                        Thread.currentThread().getName());
            }
        }, "interruptible-waiter-thread");

        holder.start();
        Thread.sleep(200); // ensure holder acquires first
        waiter.start();
        Thread.sleep(1000); // let waiter block for a moment
        System.out.printf("[main] interrupting %s%n", waiter.getName());
        waiter.interrupt();

        holder.join();
        waiter.join();
    }

    public static void main(String[] args) throws InterruptedException {
        // --- Demo 1: Basic lock/unlock ---
        System.out.println("==============================");
        System.out.println("Demo 1: Basic lock/unlock");
        System.out.println("==============================");
        BankAccount account = new BankAccount("Savings", 500.0);

        Thread depositor = new Thread(() -> {
            for (int i = 0; i < 3; i++) {
                account.deposit(100.0);
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, "depositor-thread");

        Thread withdrawer = new Thread(() -> {
            for (int i = 0; i < 3; i++) {
                account.withdraw(80.0);
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, "withdrawer-thread");

        depositor.start();
        withdrawer.start();
        depositor.join();
        withdrawer.join();
        System.out.printf("Final balance: %.2f%n%n", account.getBalance());

        // --- Demo 2: tryLock deadlock prevention ---
        System.out.println("==============================");
        System.out.println("Demo 2: tryLock deadlock prevention");
        System.out.println("==============================");
        BankAccount accountA = new BankAccount("Account-A", 1000.0);
        BankAccount accountB = new BankAccount("Account-B", 1000.0);

        // Thread 1: A→B, Thread 2: B→A — classic deadlock setup, prevented by tryLock
        Thread transfer1 = new Thread(() -> {
            try { transferWithTryLock(accountA, accountB, 300.0); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "transfer-thread-AtoB");

        Thread transfer2 = new Thread(() -> {
            try { transferWithTryLock(accountB, accountA, 200.0); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "transfer-thread-BtoA");

        transfer1.start();
        transfer2.start();
        transfer1.join();
        transfer2.join();
        System.out.println();

        // --- Demo 3: lockInterruptibly ---
        System.out.println("==============================");
        System.out.println("Demo 3: lockInterruptibly");
        System.out.println("==============================");
        runLockInterruptiblyDemo();

        System.out.println("\nAll demos complete.");
    }
}
