/*
 * ReentrantLockDemo — Main
 *
 * Three demonstrations of ReentrantLock features:
 *
 *   1. Basic lock/unlock: ten threads deposit concurrently into one account.
 *      The lock prevents read-modify-write races; the final balance confirms
 *      no increments were lost.
 *
 *   2. tryLock deadlock prevention: two threads transfer between two accounts
 *      in opposite directions. Plain lock() would deadlock; tryLock with a
 *      timeout backs off and retries, so both transfers complete.
 *
 *   3. lockInterruptibly: a thread blocked waiting for a lock is cancelled
 *      via Thread.interrupt(). lock() ignores interruption; lockInterruptibly
 *      throws InterruptedException so the thread can exit cleanly.
 */
package examples.reentrantlockdemo;

import java.util.concurrent.TimeUnit;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        demonstrateBasicLock();
        demonstrateTryLock();
        demonstrateLockInterruptibly();
    }

    // -------------------------------------------------------------------------
    // Demo 1: basic lock/unlock
    // -------------------------------------------------------------------------

    static void demonstrateBasicLock() throws InterruptedException {
        BankAccount account = new BankAccount("savings", 500.0);

        // Ten threads each deposit 100 concurrently. Without the lock, the
        // read-modify-write in deposit() would race and lose increments.
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final int id = i;
            threads[i] = new Thread(() -> account.deposit(100.0), "depositor-" + id);
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        System.out.printf("basic lock — balance: %.0f (expected 1500)%n", account.getBalance());
    }

    // -------------------------------------------------------------------------
    // Demo 2: tryLock deadlock prevention
    // -------------------------------------------------------------------------

    static void demonstrateTryLock() throws InterruptedException {
        BankAccount accountA = new BankAccount("account-A", 1000.0);
        BankAccount accountB = new BankAccount("account-B", 1000.0);

        // Thread 1 transfers A→B while thread 2 transfers B→A simultaneously.
        // With plain lock(), each thread could hold one lock and wait forever
        // for the other. tryLock with a timeout backs off and retries instead.
        Thread t1 = new Thread(() -> transfer(accountA, accountB, 300.0), "transfer-A-to-B");
        Thread t2 = new Thread(() -> transfer(accountB, accountA, 200.0), "transfer-B-to-A");

        t1.start();
        t2.start();
        t1.join();
        t2.join();
    }

    static void transfer(BankAccount from, BankAccount to, double amount) {
        while (true) {
            boolean fromLocked = false;
            boolean toLocked   = false;
            try {
                fromLocked = from.lock.tryLock(50, TimeUnit.MILLISECONDS);
                toLocked   = fromLocked && to.lock.tryLock(50, TimeUnit.MILLISECONDS);

                if (fromLocked && toLocked) {
                    // Both locks held — safe to modify both balances atomically
                    from.balance -= amount;
                    to.balance   += amount;
                    System.out.printf("[%s] transferred %.0f: %s → %s%n",
                        Thread.currentThread().getName(), amount,
                        from.getName(), to.getName());
                    return;
                }
                // Could not acquire both locks — release what we hold and retry
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                // Unlock in reverse acquisition order
                if (toLocked)   to.lock.unlock();
                if (fromLocked) from.lock.unlock();
            }
            // Brief back-off reduces contention on the next attempt
            try { Thread.sleep(10); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Demo 3: lockInterruptibly
    // -------------------------------------------------------------------------

    static void demonstrateLockInterruptibly() throws InterruptedException {
        BankAccount account = new BankAccount("checking", 200.0);

        // holder acquires the lock and holds it for a long time
        Thread holder = new Thread(() -> {
            account.lock.lock();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                account.lock.unlock();
            }
        }, "lock-holder");

        // waiter blocks on lockInterruptibly — unlike lock(), this wakes up
        // immediately when the thread is interrupted
        Thread waiter = new Thread(() -> {
            try {
                account.lock.lockInterruptibly();
                try {
                    account.deposit(50.0);
                } finally {
                    account.lock.unlock();
                }
            } catch (InterruptedException e) {
                System.out.println("[lock-waiter] interrupted while waiting — exited without acquiring lock");
            }
        }, "lock-waiter");

        holder.start();
        Thread.sleep(100); // ensure holder acquires first
        waiter.start();
        Thread.sleep(100); // let waiter block
        waiter.interrupt();
        waiter.join();
        holder.interrupt();
        holder.join();
    }
}
