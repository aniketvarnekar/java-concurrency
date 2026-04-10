/*
 * BasicAccount — synchronized instance methods.
 *
 * The monitor is `this`. Every method that reads or writes balance is
 * synchronized on the same instance, so only one thread at a time can
 * execute any of them on a given account. Two threads operating on
 * different BasicAccount instances do not contend.
 *
 * transfer() demonstrates reentrancy: it is synchronized on `this` and
 * calls withdraw() and deposit(), also synchronized on `this`. Java monitors
 * are reentrant — a thread already holding the lock can re-acquire it without
 * blocking, incrementing an internal hold count that is decremented on each exit.
 */
package examples.synchronizeddemo;

class BasicAccount {

    private final String name;
    private int balance;

    BasicAccount(String name, int initialBalance) {
        this.name = name;
        this.balance = initialBalance;
    }

    public synchronized void deposit(int amount) {
        balance += amount;
    }

    public synchronized void withdraw(int amount) {
        balance -= amount;
    }

    // Reentrant: synchronized on `this`, calls withdraw() and deposit() also on `this`.
    public synchronized void transfer(BasicAccount target, int amount) {
        withdraw(amount);       // re-acquires lock on `this`
        target.deposit(amount); // acquires lock on `target` — a different monitor
    }

    public synchronized int balance() { return balance; }

    @Override
    public String toString() { return name + "($" + balance + ")"; }
}
