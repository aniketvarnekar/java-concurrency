/*
 * BoundedBuffer — a fixed-capacity queue guarded by a ReentrantLock with two
 * Condition objects: notFull (waited on by producers) and notEmpty (waited on
 * by consumers).
 *
 * Using two distinct Conditions avoids the need for signalAll(). A producer
 * that adds an item signals only notEmpty, waking one waiting consumer without
 * disturbing other producers that are blocked on notFull. A consumer that
 * removes an item signals only notFull, waking one waiting producer without
 * disturbing other consumers.
 *
 * With a single wait set (Object.wait / notifyAll), all threads share the same
 * queue, so every state change must wake every waiter — many of whom will find
 * the condition still unmet and immediately wait again, burning CPU on
 * unnecessary context switches.
 */
package examples.conditiondemo;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

class BoundedBuffer<T> {

    private final Object[] items;
    private int head, tail, count;

    private final ReentrantLock lock = new ReentrantLock();

    // Producers await here when the buffer is full.
    private final Condition notFull = lock.newCondition();

    // Consumers await here when the buffer is empty.
    private final Condition notEmpty = lock.newCondition();

    BoundedBuffer(int capacity) {
        items = new Object[capacity];
    }

    /**
     * Inserts an item, blocking until space is available.
     */
    void put(T item) throws InterruptedException {
        lock.lock();
        try {
            // Guard in a while loop: spurious wakeups must recheck the predicate.
            while (count == items.length) {
                notFull.await();
            }
            items[tail] = item;
            tail = (tail + 1) % items.length;
            count++;

            // Signal only consumers — producers on notFull are unaffected.
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes and returns an item, blocking until one is available.
     */
    @SuppressWarnings("unchecked")
    T take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) {
                notEmpty.await();
            }
            T item = (T) items[head];
            items[head] = null; // allow GC of removed reference
            head = (head + 1) % items.length;
            count--;

            // Signal only producers — consumers on notEmpty are unaffected.
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    int capacity() {
        return items.length;
    }
}
