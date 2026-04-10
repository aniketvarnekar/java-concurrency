/*
 * SafeAccount — synchronized block on a private lock object.
 *
 * Using `this` as the monitor exposes the lock: any caller holding a reference
 * to the account can synchronize on it, potentially causing unexpected contention
 * or locking out the account's own methods for an arbitrary duration. A private
 * final Object eliminates that risk.
 *
 * The lock must be final. If it were reassignable, two threads could each call
 * synchronized(this.lock) and lock on different objects — the old and new values —
 * achieving no mutual exclusion at all.
 */
package examples.synchronizeddemo;

class SafeAccount {

    // private: inaccessible to callers, so no external code can synchronize on it
    private final Object lock = new Object();
    private final String name;
    private int balance;

    SafeAccount(String name, int initialBalance) {
        this.name = name;
        this.balance = initialBalance;
    }

    public void deposit(int amount) {
        synchronized (lock) {
            balance += amount;
        }
    }

    public void withdraw(int amount) {
        synchronized (lock) {
            balance -= amount;
        }
    }

    public int balance() {
        synchronized (lock) { return balance; }
    }

    @Override
    public String toString() { return name + "($" + balance + ")"; }
}
