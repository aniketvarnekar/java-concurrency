# Processes vs Threads

## Overview

A process is the operating system's unit of isolation. When the OS launches a program, it allocates an independent address space for that program: virtual memory that no other process can read or write without explicit inter-process communication. Each process owns its own set of file descriptors, its own heap, its own code and data segments, and at least one thread of execution. Because each process lives in its own address space, a bug in one process — a segmentation fault, an uncaught exception that kills the JVM — cannot corrupt the memory of any other process. This isolation is powerful but expensive: forking a process on Linux copies the page table, creates kernel data structures, and typically costs tens of milliseconds even with copy-on-write optimizations.

A thread is a unit of execution that lives inside a process. Every process starts with one thread (the main thread in Java) and can create additional threads that share the same heap memory and the same static (class-level) fields. Each thread does, however, maintain its own private execution context: its own program counter (PC), which tracks the next instruction to execute, and its own call stack, which holds local variables and method call frames. Because threads share the heap, one thread can write a value to a field and another thread can immediately see that value — for better or worse.

The cost of creating a thread is far lower than the cost of creating a process. On the JVM, `new Thread()` followed by `start()` allocates a stack (typically 256 KB to 1 MB depending on platform and JVM flags), creates kernel scheduling structures, and registers the thread with the OS scheduler. This costs on the order of microseconds to low milliseconds. Still, thread creation is not free, which is why production systems use thread pools rather than spawning a new thread per task.

Understanding the memory boundary between processes and threads is the single most important prerequisite for reasoning about concurrency bugs. Race conditions, visibility failures, and data corruption all arise because threads share heap memory without coordination. A program that stores all mutable state in local variables (on the stack) and never shares references between threads is automatically thread-safe. As soon as a reference escapes to the heap — passed to another thread, stored in a static field, placed in a shared collection — the programmer takes on the responsibility of ensuring safe access.

## Key Concepts

### Process Memory Layout

A running process is divided into several segments by the operating system. The text segment holds the compiled machine code. The data segment holds static and global variables that are initialized at program start. The BSS segment holds uninitialized static variables. The heap grows upward from a base address and is where dynamic allocations (Java object instances) live. The stack grows downward from the top of the address space and holds the call frames of the main thread.

```
High address
+---------------------------+
|         Stack             |  <-- grows downward (main thread)
+---------------------------+
|           ...             |
+---------------------------+
|          Heap             |  <-- grows upward (all Java objects)
+---------------------------+
|    BSS / Data / Text      |  <-- static fields, compiled code
+---------------------------+
Low address
```

In the JVM, the heap is where all object instances live. Static fields are stored in the method area (part of the non-heap memory in older JVMs, represented by the metaspace in HotSpot since Java 8). The key point is that everything stored on the heap is visible to every thread within the same process.

### Thread Memory Layout

When a second thread is created inside a process, the OS allocates a new stack for it and a new set of CPU registers (saved when the thread is not scheduled). The new thread does not get its own heap. Both threads share the same heap, the same static field storage, and the same open file descriptors.

```
+-------------------------------------------------------+
|                     JVM Process                       |
|                                                       |
|  +------------------+    +------------------+         |
|  |    Thread A      |    |    Thread B      |         |
|  |  PC: 0x4a21f0   |    |  PC: 0x4b33c8   |         |
|  |  Stack:          |    |  Stack:          |         |
|  |    local int x=1 |    |    local int x=9 |         |
|  |    frame ...     |    |    frame ...     |         |
|  +--------+---------+    +---------+--------+         |
|           |                        |                  |
|           v                        v                  |
|  +-----------------------------------------------+   |
|  |              Shared Heap                      |   |
|  |   Object A { field = 42 }                     |   |
|  |   Object B { field = "hello" }                |   |
|  |   Static fields of all classes                |   |
|  +-----------------------------------------------+   |
+-------------------------------------------------------+
```

The local variable `x` in Thread A and the local variable `x` in Thread B are completely independent — they occupy different memory locations on different stacks. But if both threads hold a reference to the same object on the heap, they are looking at the same memory.

### Cost of Creation

Process creation on Linux uses the `fork()` system call, which duplicates the parent's page table and creates new kernel scheduling structures. Even with copy-on-write (pages are not physically copied until written), fork is expensive enough that high-performance servers avoid it on the critical path.

Thread creation uses `clone()` on Linux with flags that tell the kernel to share the address space, file descriptor table, and signal handlers. This avoids duplicating the page table entirely. The main costs are allocating the new thread's stack and registering with the scheduler.

| Operation | Typical cost | Isolation |
|-----------|-------------|-----------|
| `fork()` new process | ~1–10 ms | Full address space isolation |
| `new Thread().start()` | ~0.01–1 ms | Shared heap, private stack |
| Submit to thread pool | ~1–10 µs | Shared heap, reused stack |

In Java, thread pool reuse is preferred for short-lived tasks precisely because the per-task overhead drops from milliseconds to microseconds.

### Isolation and Sharing

Because processes have separate address spaces, a crash in one process cannot corrupt another process's memory. Inter-process communication (IPC) requires explicit mechanisms: pipes, sockets, shared memory segments, or files. Each of these has a defined API and usually involves a copy of data from one address space to another.

Threads within a process share memory directly. There is no copy — one thread writes to a field and another thread can read that same memory location. This is fast but requires explicit synchronization to be correct. Without synchronization, the Java Memory Model (JMM) does not guarantee that a write by one thread will ever be visible to another thread, nor does it guarantee that reads and writes will not be reordered by the compiler or CPU.

### Context Switching

When the OS scheduler preempts a running thread and schedules a different thread, it performs a context switch. The scheduler saves the current thread's CPU registers (including the PC and stack pointer) to a kernel data structure, then restores the saved registers of the next thread to run. Because threads within the same process share an address space, switching between threads of the same process does not require changing the page table — this is cheaper than switching between processes.

Context switching is not free. A switch involves at minimum hundreds of nanoseconds of kernel time, plus cache pollution: the newly scheduled thread's data may not be in the L1/L2 cache, causing cache misses that cost tens to hundreds of nanoseconds each. Systems that create far more threads than CPU cores can degrade significantly due to context switch overhead and cache thrashing.

## Code Snippet

This program starts two threads and demonstrates that they share a static field on the heap while maintaining independent local variables on their respective stacks.

```java
public class SharedHeapDemo {

    // Stored in the method area / heap — visible to all threads.
    static int sharedCounter = 0;

    public static void main(String[] args) throws InterruptedException {
        Thread incrementer = new Thread(() -> {
            // 'localValue' lives on THIS thread's stack only.
            int localValue = 100;
            sharedCounter = localValue + 1;
            System.out.println("Incrementer: localValue=" + localValue
                    + ", sharedCounter=" + sharedCounter);
        }, "incrementer");

        Thread reader = new Thread(() -> {
            int localValue = 999; // independent from incrementer's localValue
            // Small sleep to let incrementer run first (not a sync guarantee).
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("Reader:      localValue=" + localValue
                    + ", sharedCounter=" + sharedCounter);
        }, "reader");

        incrementer.start();
        reader.start();
        incrementer.join();
        reader.join();

        System.out.println("Main: final sharedCounter=" + sharedCounter);
    }
}
```

Run: `javac SharedHeapDemo.java && java SharedHeapDemo`

## Gotchas

### Shared heap without synchronization is undefined behavior

The Java Memory Model does not guarantee that a write by one thread to a non-volatile, non-synchronized field is visible to any other thread. The CPU may keep the value in a register, the JIT compiler may reorder stores, and the hardware may reorder memory operations. The example above uses `Thread.sleep` as a rough ordering aid, which is not a correctness guarantee. Always use `volatile`, `synchronized`, `java.util.concurrent` primitives, or `java.util.concurrent.atomic` classes to coordinate shared state.

### Stack overflow is per-thread, not per-process

Each thread has its own stack with a fixed size (configurable via `-Xss`). Deep recursion in one thread will throw `StackOverflowError` in that thread only — it does not affect other threads' stacks. However, if many threads each allocate a large stack, total committed memory (threads × stack size) can exhaust the process's virtual address space or OS limits before the heap is full.

### Native thread limits are an OS resource

Each Java thread corresponds to one native OS thread. Operating systems impose limits on the number of threads per process (`ulimit -u` on Linux) and system-wide. Creating thousands of threads without a pool will exhaust these limits and cause `OutOfMemoryError: unable to create native thread`. Thread pools bound concurrency to a sensible level.

### File descriptors are shared and not thread-safe by default

Because threads share the process's file descriptor table, closing a file or socket in one thread immediately affects all other threads that hold the same descriptor. Concurrent reads or writes to an unbuffered `FileInputStream` from multiple threads without external synchronization will produce interleaved, corrupted data.

### Local variables are not automatically safe across thread boundaries

A local variable is private to its stack frame, but the object it refers to may be shared. If Thread A creates an `ArrayList`, stores it in a local variable, and then passes the reference to Thread B via a shared field, both threads now share the same heap object through potentially different stack variables. The stack privacy provides no protection once the reference escapes.

### Context switches can hide race conditions during testing

A race condition that depends on a specific interleaving of two threads may never manifest on a lightly loaded development machine where the scheduler rarely preempts threads mid-operation. The same code run on a heavily loaded production server or a machine with many cores may expose the race immediately. Never conclude that an unsynchronized program is correct because tests pass.
