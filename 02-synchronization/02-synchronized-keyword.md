# The synchronized Keyword

## Overview

The `synchronized` keyword is Java's built-in mechanism for mutual exclusion and memory visibility. When a thread enters a synchronized method or block, it acquires a monitor lock on a specific object. Only one thread can hold a given monitor lock at a time. Any other thread that attempts to acquire the same lock will block — that is, it will be suspended by the JVM and will not execute until the lock becomes available. When the synchronized region is exited, either by reaching its end or by an exception propagating out, the lock is released and one of the waiting threads is permitted to acquire it.

The monitor is the synchronization primitive underlying `synchronized`. Every Java object has an associated monitor. A monitor provides two guarantees: mutual exclusion, meaning at most one thread executes inside the guarded region at any time, and memory visibility, meaning that all writes performed inside the guarded region are flushed to main memory when the lock is released, and all reads inside the guarded region see the values written by the most recent release of the same lock. This second guarantee is what makes `synchronized` a complete synchronization mechanism: it addresses both the ordering hazard and the visibility hazard described in the race conditions note.

The `synchronized` keyword can appear in three forms: on an instance method, on a static method, or on an explicit block. Each form specifies a different monitor object. Understanding which object is used as the monitor is critical, because two synchronized regions protect the same shared state only if they synchronize on the same monitor object. Two methods that each use `synchronized` but lock on different objects do not protect against each other.

## Key Concepts

### Instance Method Synchronization

When `synchronized` is placed on an instance method, the monitor object is `this` — the instance on which the method is called:

```java
public class Counter {
    private int count = 0;

    public synchronized void increment() {
        count++;
    }

    public synchronized int getCount() {
        return count;
    }
}
```

Both `increment` and `getCount` lock on the same `Counter` instance. If thread A is executing `increment` on a particular `Counter` object, thread B attempting `getCount` on the same object will block until thread A releases the lock. If thread B is calling `getCount` on a different `Counter` instance, it will not block — the locks are on different objects.

### Static Method Synchronization

When `synchronized` is placed on a static method, the monitor object is the `Class` object for the declaring class — `Counter.class` in the example below:

```java
public class IdGenerator {
    private static int nextId = 0;

    public static synchronized int generate() {
        return ++nextId;
    }
}
```

There is exactly one `Class` object per class per classloader, so `IdGenerator.class` is a single shared lock for all threads in the same classloader context. A static synchronized method and an instance synchronized method on the same class do not contend with each other, because one locks on `Class` and the other locks on `this`.

### Synchronized Block

A synchronized block names the monitor object explicitly:

```java
public class Cache {
    private final Object lock = new Object();
    private final Map<String, String> map = new HashMap<>();

    public void put(String key, String value) {
        synchronized (lock) {
            map.put(key, value);
        }
    }

    public String get(String key) {
        synchronized (lock) {
            return map.get(key);
        }
    }
}
```

Synchronized blocks offer finer granularity than synchronized methods. A method can do some work before acquiring the lock, hold the lock for only the critical section, and do more work after releasing it. This reduces contention by shortening the time the lock is held. The monitor object can be any non-null Java object reference; using a dedicated private final object as shown above is the recommended pattern, for reasons explained below.

### Reentrancy

Java's monitor locks are reentrant: a thread that already holds a lock can acquire it again without blocking. Each acquisition increments an internal counter; each release decrements it. The lock is actually released only when the counter returns to zero. This is essential for inheritance hierarchies and for synchronized methods that call other synchronized methods on the same object:

```java
public class RecursiveSum {
    public synchronized int sum(int n) {
        if (n <= 0) {
            return 0;
        }
        return n + sum(n - 1); // recursive call re-acquires the same lock
    }
}
```

Without reentrancy, the recursive call to `sum` would attempt to acquire a lock already held by the same thread, causing that thread to block waiting for itself — an immediate deadlock. Reentrancy eliminates this problem. A subclass method that calls `super.method()` where both are synchronized on `this` also relies on reentrancy.

### Private Lock Object

Using `this` as the monitor exposes the lock to outside code. Any class that holds a reference to the object can synchronize on it, potentially interfering with the internal synchronization strategy or creating unexpected contention:

```java
// Caller can disrupt the internal synchronization:
Widget w = new Widget();
synchronized (w) {
    // Holds the same lock Widget uses internally.
    // Long operation here stalls all Widget synchronized methods.
    Thread.sleep(10_000);
}
```

The solution is to use a dedicated private final object as the lock:

```java
public class Widget {
    private final Object lock = new Object(); // private: not accessible to callers

    public void doWork() {
        synchronized (lock) {
            // ...
        }
    }
}
```

Because `lock` is private, no external code can synchronize on it. The lock must be `final` to ensure the reference never changes — synchronizing on a field whose value can be reassigned is a common bug, because two threads can each lock on a different object and not actually achieve mutual exclusion.

## Code Snippet

```java
// Demonstrates synchronized instance method, synchronized block on a private lock,
// and reentrancy. Two threads perform concurrent transfers between bank accounts.
//
// Run: javac BankAccountDemo.java && java BankAccountDemo

public class BankAccountDemo {

    // Version 1: synchronized methods lock on `this`
    static class BankAccount {
        private final String name;
        private int balance;

        BankAccount(String name, int initialBalance) {
            this.name = name;
            this.balance = initialBalance;
        }

        public synchronized void deposit(int amount) {
            System.out.printf("[%s] deposit(%d) on %s, balance before: %d%n",
                    Thread.currentThread().getName(), amount, name, balance);
            balance += amount;
        }

        public synchronized void withdraw(int amount) {
            System.out.printf("[%s] withdraw(%d) on %s, balance before: %d%n",
                    Thread.currentThread().getName(), amount, name, balance);
            balance -= amount;
        }

        // Synchronized method calling another synchronized method on the same instance
        // -- reentrancy allows this without deadlock.
        public synchronized void transfer(BankAccount target, int amount) {
            System.out.printf("[%s] transfer(%d) from %s to %s%n",
                    Thread.currentThread().getName(), amount, name, target.name);
            withdraw(amount);   // re-acquires lock on `this`
            target.deposit(amount); // acquires lock on `target`
        }

        public synchronized int getBalance() {
            return balance;
        }

        @Override
        public String toString() {
            return name + "=" + balance;
        }
    }

    // Version 2: private lock object
    static class SafeAccount {
        private final Object lock = new Object();
        private final String name;
        private int balance;

        SafeAccount(String name, int initialBalance) {
            this.name = name;
            this.balance = initialBalance;
        }

        public void deposit(int amount) {
            synchronized (lock) {
                System.out.printf("[%s] safe-deposit(%d) on %s%n",
                        Thread.currentThread().getName(), amount, name);
                balance += amount;
            }
        }

        public int getBalance() {
            synchronized (lock) {
                return balance;
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Reentrant synchronized transfer ===");
        BankAccount alice = new BankAccount("Alice", 1000);
        BankAccount bob   = new BankAccount("Bob",   1000);

        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                alice.transfer(bob, 100);
            }
        }, "bank-thread-1");

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                bob.transfer(alice, 100);
            }
        }, "bank-thread-2");

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        System.out.println("Final: " + alice + ", " + bob);
        System.out.println("Sum (should be 2000): " + (alice.getBalance() + bob.getBalance()));

        System.out.println("\n=== Private lock object ===");
        SafeAccount sa = new SafeAccount("Safe", 500);

        Thread t3 = new Thread(() -> sa.deposit(200), "safe-thread-1");
        Thread t4 = new Thread(() -> sa.deposit(300), "safe-thread-2");

        t3.start();
        t4.start();
        t3.join();
        t4.join();

        System.out.println("Safe balance (should be 1000): " + sa.getBalance());
    }
}
```

## Gotchas

**Synchronizing on a non-final field is almost always wrong.** If the field can be reassigned, two threads can each call `synchronized (this.lock)` and lock on different objects — the old value and the new value — giving no mutual exclusion at all. The lock field must be `final` and must never be reassigned after construction.

**Synchronizing on string literals creates invisible shared locks.** String literals are interned by the JVM, meaning all string literals with the same value refer to the same object in the string pool. Two completely unrelated classes that both write `synchronized ("lock") { ... }` are competing for the same monitor. The symptom is mysterious contention or deadlock between code that appears to have nothing to do with each other. Use `new Object()` for explicit lock objects.

**Synchronizing on boxed integers or cached autoboxed values produces subtle races.** The JVM caches `Integer` objects for values in the range -128 to 127. Code that writes `synchronized (Integer.valueOf(id))` will use the same cached object for every `id` in that range, causing threads with different IDs to contend for the same lock. Outside the cached range, a new `Integer` is created each time, so the lock is on a different object every call and provides no protection at all.

**Two synchronized methods that protect the same state must lock on the same monitor.** If `increment` is a synchronized instance method (locks on `this`) and `reset` is a synchronized static method (locks on `Class`), they do not protect against each other. A thread calling `increment` and a thread calling `reset` can execute simultaneously. This is a common error when a class mixes instance and static state.

**Holding a lock while doing blocking I/O or calling external code causes contention.** The lock is held for the entire duration of the synchronized method or block. If that duration includes a network call, a file read, or a call into third-party code of unknown duration, all threads waiting on the same lock are blocked for the same duration. Keep synchronized regions short and free of I/O. Move the I/O outside the lock; hold the lock only for the actual read or write of shared state.

**`synchronized` does not protect against visibility issues for fields accessed outside a synchronized block.** A field that is read inside a synchronized method but also read directly (without synchronization) elsewhere is not fully protected. The happens-before guarantee that `synchronized` provides applies only to threads that actually acquire the same lock. A thread that reads the field without acquiring the lock gets no visibility guarantee. Every access to shared mutable state — reads and writes — must be guarded.
