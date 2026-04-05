/**
 * Demonstrates all three forms of the synchronized keyword and reentrancy.
 *
 *   1. Synchronized instance method  — locks on `this`
 *   2. Synchronized static method    — locks on the Class object
 *   3. Synchronized block            — locks on an explicit private Object lock
 *   4. Reentrancy                    — a synchronized method calling another
 *                                      synchronized method on the same instance
 *                                      without deadlocking
 *
 * The example uses a BankAccount-style scenario to make the locking
 * semantics concrete: deposits, withdrawals, and inter-account transfers.
 * All threads are named so every print statement identifies its origin.
 *
 * Run:
 *   javac SynchronizedDemo.java && java SynchronizedDemo
 */
public class SynchronizedDemo {

    // -----------------------------------------------------------------------
    // Form 1: Synchronized instance methods (lock = this)
    // -----------------------------------------------------------------------

    static class BasicAccount {
        private final String owner;
        private int balance;

        BasicAccount(String owner, int initialBalance) {
            this.owner = owner;
            this.balance = initialBalance;
        }

        // Lock: this (the BasicAccount instance)
        public synchronized void deposit(int amount) {
            System.out.printf("  [%s] deposit %d onto %s.balance=%d%n",
                    Thread.currentThread().getName(), amount, owner, balance);
            balance += amount;
        }

        // Lock: this
        public synchronized void withdraw(int amount) {
            System.out.printf("  [%s] withdraw %d from %s.balance=%d%n",
                    Thread.currentThread().getName(), amount, owner, balance);
            balance -= amount;
        }

        // Lock: this — reentrant: calls withdraw() and deposit(), both synchronized on this
        public synchronized void transfer(BasicAccount target, int amount) {
            System.out.printf("  [%s] transfer %d from %s -> %s%n",
                    Thread.currentThread().getName(), amount, owner, target.owner);
            withdraw(amount);          // re-acquires lock on `this` -- reentrancy allows this
            target.deposit(amount);    // acquires lock on `target`  -- different monitor
        }

        public synchronized int balance() { return balance; }

        @Override
        public String toString() { return owner + "($" + balance + ")"; }
    }

    // -----------------------------------------------------------------------
    // Form 2: Synchronized static method (lock = BasicAccount.class object)
    // -----------------------------------------------------------------------

    static class AccountRegistry {
        private static int totalAccounts = 0;

        // Lock: AccountRegistry.class
        public static synchronized int registerNewAccount() {
            totalAccounts++;
            System.out.printf("  [%s] registered account #%d%n",
                    Thread.currentThread().getName(), totalAccounts);
            return totalAccounts;
        }

        public static synchronized int getTotalAccounts() {
            return totalAccounts;
        }
    }

    // -----------------------------------------------------------------------
    // Form 3: Synchronized block on a private lock object
    //         Also demonstrates why private lock is safer than locking on `this`
    // -----------------------------------------------------------------------

    static class SafeAccount {
        // Private lock: callers cannot synchronize on it from outside the class
        private final Object lock = new Object();

        private final String owner;
        private int balance;

        SafeAccount(String owner, int initialBalance) {
            this.owner = owner;
            this.balance = initialBalance;
        }

        public void deposit(int amount) {
            synchronized (lock) {   // synchronized block on private object
                System.out.printf("  [%s] safe-deposit %d onto %s.balance=%d%n",
                        Thread.currentThread().getName(), amount, owner, balance);
                balance += amount;
            }
            // Code here runs outside the lock -- reduces contention
        }

        public void withdraw(int amount) {
            synchronized (lock) {
                System.out.printf("  [%s] safe-withdraw %d from %s.balance=%d%n",
                        Thread.currentThread().getName(), amount, owner, balance);
                balance -= amount;
            }
        }

        public int balance() {
            synchronized (lock) { return balance; }
        }

        @Override
        public String toString() { return owner + "($" + balance + ")"; }
    }

    // -----------------------------------------------------------------------
    // Form 4: Reentrancy with a recursive synchronized method
    // -----------------------------------------------------------------------

    static class Factorial {
        // The same thread re-acquires the lock on each recursive call.
        // Java's monitor locks are reentrant: an internal counter tracks
        // the depth, and the lock is only released when the count returns to 0.
        public synchronized long compute(int n) {
            System.out.printf("  [%s] compute(%d)%n", Thread.currentThread().getName(), n);
            if (n <= 1) return 1;
            return n * compute(n - 1); // recursive -- re-acquires lock on `this`
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static void join(Thread... threads) {
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {

        // --- Demo 1: synchronized instance methods + reentrancy via transfer ---
        System.out.println("=== 1. Synchronized instance methods + reentrant transfer ===");

        BasicAccount alice = new BasicAccount("Alice", 1000);
        BasicAccount bob   = new BasicAccount("Bob",   1000);

        Thread t1 = new Thread(() -> {
            alice.transfer(bob, 200);
            alice.transfer(bob, 100);
        }, "bank-worker-1");

        Thread t2 = new Thread(() -> {
            alice.transfer(bob, 200);
            alice.transfer(bob, 100);
        }, "bank-worker-2");

        t1.start(); t2.start();
        join(t1, t2);

        System.out.printf("After transfers: %s, %s%n", alice, bob);
        System.out.printf("Sum must be 2000: %d%n%n", alice.balance() + bob.balance());

        // --- Demo 2: synchronized static method ---
        System.out.println("=== 2. Synchronized static method (locks on Class object) ===");

        Thread r1 = new Thread(() -> {
            AccountRegistry.registerNewAccount();
            AccountRegistry.registerNewAccount();
        }, "registrar-1");

        Thread r2 = new Thread(() -> {
            AccountRegistry.registerNewAccount();
            AccountRegistry.registerNewAccount();
        }, "registrar-2");

        r1.start(); r2.start();
        join(r1, r2);

        System.out.printf("Total accounts registered (expected 4): %d%n%n",
                AccountRegistry.getTotalAccounts());

        // --- Demo 3: synchronized block on private lock ---
        System.out.println("=== 3. Synchronized block on private lock object ===");

        SafeAccount safe = new SafeAccount("SafeAlice", 500);

        Thread s1 = new Thread(() -> {
            safe.deposit(300);
            safe.withdraw(100);
        }, "safe-worker-1");

        Thread s2 = new Thread(() -> {
            safe.deposit(200);
            safe.withdraw(150);
        }, "safe-worker-2");

        s1.start(); s2.start();
        join(s1, s2);

        System.out.printf("Safe balance (expected 750): %d%n%n", safe.balance());

        // --- Demo 4: reentrancy via recursive method ---
        System.out.println("=== 4. Reentrancy — recursive synchronized method ===");

        Factorial fact = new Factorial();
        Thread factThread = new Thread(() -> {
            long result = fact.compute(6);
            System.out.printf("  [%s] 6! = %d (expected 720)%n",
                    Thread.currentThread().getName(), result);
        }, "factorial-worker");

        factThread.start();
        factThread.join();

        System.out.println("\nAll demos complete.");
    }
}
