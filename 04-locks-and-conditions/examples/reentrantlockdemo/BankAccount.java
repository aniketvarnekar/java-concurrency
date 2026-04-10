/*
 * BankAccount — a simple account guarded by a ReentrantLock.
 *
 * The lock field is package-private so that Main's transfer() method can
 * acquire both account locks with tryLock before touching either balance,
 * without re-entering the lock through deposit() or withdraw().
 */
package examples.reentrantlockdemo;

import java.util.concurrent.locks.ReentrantLock;

class BankAccount {

    private final String name;

    // package-private: Main's transfer() accesses balance directly while
    // holding both locks, avoiding a redundant reentrant lock acquisition
    // through deposit() or withdraw().
    double balance;

    // guards all reads and writes to balance
    final ReentrantLock lock = new ReentrantLock();

    BankAccount(String name, double initialBalance) {
        this.name = name;
        this.balance = initialBalance;
    }

    void deposit(double amount) {
        lock.lock();
        try {
            balance += amount;
        } finally {
            lock.unlock();
        }
    }

    double getBalance() {
        lock.lock();
        try {
            return balance;
        } finally {
            lock.unlock();
        }
    }

    String getName() { return name; }
}
