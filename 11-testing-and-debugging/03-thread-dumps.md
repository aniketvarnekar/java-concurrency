# Thread Dumps

## Overview

A thread dump is a snapshot of every thread's state in a running JVM at a specific moment in time. It shows each thread's name, current state, stack trace from bottom (entry point) to top (current location), and the monitors (locks) it holds and is waiting to acquire. Thread dumps are the primary diagnostic tool for investigating deadlocks, livelocks, thread pool exhaustion, high CPU usage on individual cores, and threads stuck in blocking calls that never return.

Taking multiple thread dumps at intervals — typically three dumps ten seconds apart — is more informative than a single snapshot. A thread that appears at the same stack frame in all three dumps is genuinely stuck, not merely passing through that code path at the moment of the snapshot. This simple technique distinguishes threads that are blocked indefinitely from threads that are momentarily visible at a location due to scheduling timing.

Thread dumps require no application code changes and impose negligible overhead on the JVM. They are safe to take in production. The `jstack` and `jcmd` tools are the standard way to capture them on modern JDK installations. Programmatic capture via `ThreadMXBean` is available when the dump must be embedded in application diagnostics, structured logging, or automated alerting systems.

## Key Concepts

### Taking a Thread Dump

Several methods are available depending on the environment:

**jstack** — the traditional tool, part of the JDK since early versions:

```
# Find the JVM's PID
jps -l

# Take a thread dump (prints to stdout)
jstack <pid>

# Include locked ownable synchronizers (ReentrantLock, etc.)
jstack -l <pid>
```

**jcmd** — the modern replacement for jstack, available since Java 7:

```
# Basic thread dump
jcmd <pid> Thread.print

# Include locked synchronizers (equivalent to jstack -l)
jcmd <pid> Thread.print -l

# List all running JVMs
jcmd
```

**kill -3** — sends SIGQUIT to the JVM process on POSIX systems; the JVM prints the thread dump to its standard error output (the same log file where the JVM writes startup messages):

```
kill -3 <pid>
```

**Programmatic via ThreadMXBean:**

```java
ThreadMXBean bean = ManagementFactory.getThreadMXBean();
long[] ids = bean.getAllThreadIds();
ThreadInfo[] infos = bean.getThreadInfo(ids, 50); // 50 = max stack depth
for (ThreadInfo info : infos) {
    System.out.println(info);
}
```

**GUI tools:** VisualVM and JConsole can connect to a running JVM (local or remote via JMX) and produce thread dumps through a graphical button. Useful for one-off investigations; not suitable for automation.

### Thread Entry Format

A single thread entry in a dump looks like this:

```
"worker-thread-1" #22 prio=5 os_prio=0 tid=0x00007f1a2b3c4d00 nid=0x1a2b waiting for monitor entry [0x00007f1a2a3b4000]
   java.lang.Thread.State: BLOCKED (on object monitor)
        at com.example.OrderService.process(OrderService.java:45)
        - waiting to lock <0x00000007b7e35e58> (a com.example.OrderService)
        - locked <0x00000007b7e35e40> (a com.example.Cache)
        at com.example.RequestHandler.handle(RequestHandler.java:88)
        at java.lang.Thread.run(Thread.java:833)
```

Field annotations:

```
"worker-thread-1"     — thread name (set via Thread.setName or ThreadFactory)
#22                   — JVM-internal thread number (sequential)
prio=5                — Java thread priority (1-10; 5 is NORM_PRIORITY)
os_prio=0             — OS-level priority (platform-specific)
tid=0x00007f...       — JVM thread ID (address in JVM heap; not OS PID)
nid=0x1a2b            — native (OS) thread ID in hexadecimal
                          convert to decimal for use with top -H or perf
waiting for monitor entry — current activity description
[0x00007f...]         — stack base address

java.lang.Thread.State: BLOCKED (on object monitor)
                      — Java thread state

at com.example.OrderService.process(OrderService.java:45)
                      — top of stack (most recent frame) = current location

- waiting to lock <0x00000007b7e35e58> (a com.example.OrderService)
                      — the monitor this thread is blocked trying to acquire
                        address is the object's identity hash in JVM heap

- locked <0x00000007b7e35e40> (a com.example.Cache)
                      — a monitor this thread currently holds

at com.example.RequestHandler.handle(RequestHandler.java:88)
                      — caller frame

at java.lang.Thread.run(Thread.java:833)
                      — bottom of stack (thread entry point)
```

### Thread States in Dumps

| State in Dump | Java Thread.State | Meaning |
|---|---|---|
| runnable | RUNNABLE | Executing or OS-runnable; may include blocking native IO |
| waiting for monitor entry | BLOCKED | Blocked waiting to enter a synchronized block |
| in Object.wait() | WAITING | Thread called Object.wait(); released its monitor |
| sleeping | TIMED_WAITING | Thread.sleep() in progress |
| parked | WAITING | LockSupport.park(); used by ReentrantLock internals |
| parked, waiting for notification | TIMED_WAITING | LockSupport.parkNanos() or parkUntil() |
| waiting on condition | WAITING | Condition.await() on a Lock's Condition |

### Deadlock Report

When `jstack` or `jcmd Thread.print` detects a Java-level deadlock, it appends a report after the thread entries. The report identifies the cycle:

```
Found one Java-level deadlock:
=============================
"thread-A":
  waiting to lock monitor 0x00000007b7e35e58 (object 0x00000007b7e35e40, a com.example.Lock),
  which is held by "thread-B"
"thread-B":
  waiting to lock monitor 0x00000007b7e35e40 (object 0x00000007b7e35e58, a com.example.OtherLock),
  which is held by "thread-A"

Java stack information for the threads listed above:
===================================================
"thread-A":
        at com.example.Foo.methodA(Foo.java:30)
        - waiting to lock <0x00000007b7e35e58> (a com.example.Lock)
        - locked <0x00000007b7e35e40> (a com.example.OtherLock)
"thread-B":
        at com.example.Foo.methodB(Foo.java:55)
        - waiting to lock <0x00000007b7e35e40> (a com.example.OtherLock)
        - locked <0x00000007b7e35e58> (a com.example.Lock)

Found 1 deadlock.
```

Note: this automatic detection works only for `synchronized` monitor locks. Deadlocks through `ReentrantLock` are only detected when using `jstack -l` or `jcmd Thread.print -l`, which includes locked ownable synchronizers.

### Common Diagnostic Patterns

```
Pattern                             What it means
----------------------------------  -----------------------------------------------
All threads BLOCKED on same         Single hot lock; reduce scope, split lock,
<0xABCD> object address             or use ReadWriteLock / ConcurrentHashMap

All pool threads WAITING in         Thread pool is idle OR upstream is not
LinkedBlockingQueue.take()          submitting tasks; check producer side

Thread RUNNABLE at socketRead0()    Normal for IO threads; blocking IO appears
or FileInputStream.read0()          RUNNABLE in Java; use timeout to avoid hangs

Thread RUNNABLE at a non-IO         CPU-bound loop or infinite loop without
method in a tight loop              yield/sleep; add termination check

Thread stuck at same frame          Genuinely stuck; compare 2-3 dumps 10s apart;
across 3 dumps, not a lock          check for infinite loop or blocked native call

Hundreds of threads BLOCKED         Thread pool sized too small or single lock
on the same address                 serializing all work; horizontal bottleneck
```

### Annotated Sample: Deadlock Plus WAITING Thread

```
2024-01-15 14:32:07
Full thread dump OpenJDK 64-Bit Server VM (21+35 mixed mode, sharing):

"transfer-thread-A" #15 prio=5 os_prio=0 tid=0x00007f... nid=0x6d3 waiting for monitor entry [0x...]
   java.lang.Thread.State: BLOCKED (on object monitor)
        at com.example.Bank.transfer(Bank.java:42)
        - waiting to lock <0x000000076c3d8a00> (a com.example.Account)   <-- wants account-B
        - locked <0x000000076c3d8a50> (a com.example.Account)             <-- holds account-A
        at com.example.TransferTask.run(TransferTask.java:18)
        at java.lang.Thread.run(Thread.java:833)

"transfer-thread-B" #16 prio=5 os_prio=0 tid=0x00007f... nid=0x6d4 waiting for monitor entry [0x...]
   java.lang.Thread.State: BLOCKED (on object monitor)
        at com.example.Bank.transfer(Bank.java:42)
        - waiting to lock <0x000000076c3d8a50> (a com.example.Account)   <-- wants account-A
        - locked <0x000000076c3d8a00> (a com.example.Account)             <-- holds account-B
        at com.example.TransferTask.run(TransferTask.java:18)
        at java.lang.Thread.run(Thread.java:833)

"notification-dispatcher" #20 prio=5 os_prio=0 tid=0x00007f... nid=0x6d8 in Object.wait() [0x...]
   java.lang.Thread.State: WAITING (on object monitor)
        at java.lang.Object.wait(Native Method)
        - waiting on <0x000000076c3d9000> (a com.example.EventQueue)     <-- waiting for notify()
        - locked <0x000000076c3d9000> (a com.example.EventQueue)          <-- released during wait
        at com.example.EventQueue.take(EventQueue.java:77)
        at com.example.NotificationDispatcher.run(NotificationDispatcher.java:33)
        at java.lang.Thread.run(Thread.java:833)

Found one Java-level deadlock:
=============================
"transfer-thread-A":
  waiting to lock monitor 0x000000076c3d8a00 (object 0x000000076c3d8a00, a com.example.Account),
  which is held by "transfer-thread-B"
"transfer-thread-B":
  waiting to lock monitor 0x000000076c3d8a50 (object 0x000000076c3d8a50, a com.example.Account),
  which is held by "transfer-thread-A"

Found 1 deadlock.
```

The `notification-dispatcher` thread shows a healthy pattern: it called `Object.wait()`, released the monitor on `EventQueue`, and is waiting for `notify()` from a producer. This is expected for a queue consumer.

## Code Snippet

```java
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * Creates a deadlock between two threads, prints instructions for taking a
 * thread dump externally, and also demonstrates programmatic thread dump
 * capture via ThreadMXBean including deadlock detection.
 *
 * Run: javac DeadlockDemo.java && java DeadlockDemo
 *
 * Expected jstack output structure (after running: jstack -l <pid>):
 *
 *   "deadlock-thread-B" #XX ... waiting for monitor entry
 *      java.lang.Thread.State: BLOCKED (on object monitor)
 *           at DeadlockDemo$DeadlockTask.run(DeadlockDemo.java:XX)
 *           - waiting to lock <0x...> (a java.lang.Object)   <- wants lockA
 *           - locked <0x...> (a java.lang.Object)             <- holds lockB
 *
 *   "deadlock-thread-A" #XX ... waiting for monitor entry
 *      java.lang.Thread.State: BLOCKED (on object monitor)
 *           at DeadlockDemo$DeadlockTask.run(DeadlockDemo.java:XX)
 *           - waiting to lock <0x...> (a java.lang.Object)   <- wants lockB
 *           - locked <0x...> (a java.lang.Object)             <- holds lockA
 *
 *   Found one Java-level deadlock:
 *   "deadlock-thread-A" is waiting for "deadlock-thread-B"
 *   "deadlock-thread-B" is waiting for "deadlock-thread-A"
 */
public class DeadlockDemo {

    static final Object lockA = new Object();
    static final Object lockB = new Object();

    static class DeadlockTask implements Runnable {
        private final Object first;
        private final Object second;
        private final String name;

        DeadlockTask(String name, Object first, Object second) {
            this.name = name;
            this.first = first;
            this.second = second;
        }

        @Override
        public void run() {
            synchronized (first) {
                System.out.println(name + " acquired first lock");
                try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                System.out.println(name + " waiting for second lock...");
                synchronized (second) {
                    System.out.println(name + " acquired second lock (will not print in deadlock)");
                }
            }
        }
    }

    static void printProgrammaticDump() {
        ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();

        // Detect deadlocked threads
        long[] deadlocked = mxBean.findDeadlockedThreads();
        if (deadlocked != null) {
            System.out.println("\n=== Programmatic Deadlock Detection ===");
            System.out.println("Deadlocked thread IDs: " + java.util.Arrays.toString(deadlocked));
            ThreadInfo[] infos = mxBean.getThreadInfo(deadlocked, true, true);
            for (ThreadInfo info : infos) {
                System.out.println("  Thread: " + info.getThreadName()
                        + " | State: " + info.getThreadState()
                        + " | BlockedOn: " + info.getLockName()
                        + " | LockOwner: " + info.getLockOwnerName());
            }
        } else {
            System.out.println("No deadlock detected programmatically.");
        }

        // Full thread dump
        System.out.println("\n=== Programmatic Thread Dump (all threads, depth 10) ===");
        long[] allIds = mxBean.getAllThreadIds();
        ThreadInfo[] allInfos = mxBean.getThreadInfo(allIds, 10);
        for (ThreadInfo info : allInfos) {
            if (info == null) continue;
            System.out.printf("Thread: %-30s  State: %s%n",
                    info.getThreadName(), info.getThreadState());
            StackTraceElement[] stack = info.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, 3); i++) {
                System.out.println("    at " + stack[i]);
            }
            if (stack.length > 3) {
                System.out.println("    ... " + (stack.length - 3) + " more frames");
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Starting deadlock demo...");
        System.out.println("PID: " + ProcessHandle.current().pid());
        System.out.println("To take an external thread dump, run one of:");
        System.out.println("  jstack -l " + ProcessHandle.current().pid());
        System.out.println("  jcmd " + ProcessHandle.current().pid() + " Thread.print -l");
        System.out.println("  kill -3 " + ProcessHandle.current().pid());
        System.out.println();

        // Thread A: locks lockA then lockB
        Thread threadA = new Thread(
                new DeadlockTask("deadlock-thread-A", lockA, lockB),
                "deadlock-thread-A");

        // Thread B: locks lockB then lockA — opposite order = deadlock
        Thread threadB = new Thread(
                new DeadlockTask("deadlock-thread-B", lockB, lockA),
                "deadlock-thread-B");

        threadA.start();
        threadB.start();

        // Give the threads time to enter the deadlock
        Thread.sleep(500);

        // Programmatic detection via ThreadMXBean
        printProgrammaticDump();

        System.out.println("\nProgram will now wait. Take a jstack dump to see the deadlock report.");
        System.out.println("Press Ctrl+C to exit.");

        threadA.join();  // will wait forever unless interrupted
        threadB.join();
    }
}
```

## Gotchas

**RUNNABLE in a thread dump does not mean the thread is burning CPU.** A thread performing blocking native IO — socket reads, file reads, DNS lookups — appears RUNNABLE in the Java thread dump because the JVM cannot distinguish between executing native code and being blocked in a native system call. A flame graph (async-profiler with `-e wall`) or OS-level IO profiler is needed to confirm whether a RUNNABLE thread is doing CPU work or waiting on IO.

**Default thread pool names convey no diagnostic information.** Names like `pool-1-thread-1` are generated by the default `ThreadPoolExecutor` thread factory. When you have five different thread pools in an application all using default names, a thread dump becomes nearly impossible to interpret. Always supply a custom `ThreadFactory` that encodes the pool's purpose in the thread name, such as `order-processor-thread-1` or `http-client-io-2`.

**A single thread dump is a point-in-time snapshot.** A thread that appears stuck in one dump might have been scheduled there by chance. Take at least two or three dumps spaced ten seconds apart. If the same thread appears at the same stack frame in all three dumps, it is genuinely stuck. A thread that appears at different frames across dumps is making progress and is not the problem.

**"locked" in a thread dump means the thread currently holds that monitor.** It does not mean the thread is the only one that ever acquires it or that there is a problem. The significant combination to look for is one thread entry showing `- locked <0xABCD>` and another thread entry showing `- waiting to lock <0xABCD>` for the same address. That pair is the signature of lock contention or deadlock — one thread holds what the other is waiting for.

**jstack without -l misses ReentrantLock deadlocks.** The automatic deadlock detection in the thread dump footer works only for `synchronized` monitor locks. Deadlocks through `ReentrantLock`, `ReadWriteLock`, and other `java.util.concurrent` locks are only detected when locked ownable synchronizers are included, which requires `jstack -l` or `jcmd Thread.print -l`. Without `-l`, a ReentrantLock deadlock produces threads that appear WAITING at `sun.misc.Unsafe.park()` with no deadlock report — a much harder diagnosis.

**The nid (native ID) in a thread dump is in hexadecimal.** To correlate thread dump entries with CPU usage from `top -H` or Linux `perf`, convert the nid to decimal. For example, `nid=0x1a2b` is decimal 6699. Run `top -H -p <jvm-pid>` and find TID 6699 in the output to see that thread's CPU usage. This is the standard technique for identifying which specific thread is responsible for a CPU spike.
