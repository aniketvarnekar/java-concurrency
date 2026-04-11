# Reactor Pattern

## Overview

The Reactor pattern handles concurrent service requests using a single-threaded event loop that demultiplexes and dispatches incoming I/O events to their registered handlers. The central insight is that most server connections spend the vast majority of their time idle — waiting for the next packet to arrive — rather than actively transferring data. Thread-per-connection designs waste one OS thread per idle connection. The Reactor monitors all connections simultaneously through a `Selector` and wakes only when a connection has work to do, keeping all other connections idle at zero CPU cost.

Instead of creating one thread per connection — which does not scale past a few thousand connections due to OS thread limits and context-switching overhead — the reactor registers each connection's channel with a `java.nio.channels.Selector`. The `Selector` tracks which channels are ready for I/O, allowing the event loop to service whichever connection is ready without spawning additional threads. This model underlies most modern high-performance network servers and frameworks including Netty, Node.js, and the Linux epoll event loop.

The pattern has a strict operational requirement: event handlers must never block. A blocking handler — one that performs a synchronous database query, calls `Thread.sleep()`, or waits on a lock — stalls the entire event loop and blocks all other connections for the duration of the wait. Long-running work must be offloaded to a separate thread pool; the handler's only job is to initiate the work and return immediately.

## Key Concepts

### Event Loop

The event loop is the heart of the Reactor. It runs continuously in a single thread, executing four steps in sequence: (1) call `Selector.select()`, which blocks until at least one registered channel is ready for an I/O operation; (2) retrieve the set of ready `SelectionKey` objects from `selector.selectedKeys()`; (3) iterate over each key and dispatch it to its registered handler; (4) clear the selected key set and repeat. The loop never terminates while the reactor is running.

```
Connections → [Selector] → Event Loop → Dispatch → [Handler A]
                                                 → [Handler B]
                                                 → [Handler C]
                    ↑
             select() blocks until
             at least one channel ready
```

### Selector (`java.nio.channels.Selector`)

A `Selector` monitors multiple non-blocking channels for I/O readiness. Channels must be configured non-blocking (`channel.configureBlocking(false)`) before registration. The selector tracks four readiness operations: `OP_ACCEPT` (a server channel is ready to accept a new connection), `OP_READ` (a channel has data available to read), `OP_WRITE` (a channel's output buffer has space for writing), and `OP_CONNECT` (a client channel has finished its connection attempt). A single `Selector` can monitor thousands of channels simultaneously.

### Non-Blocking Requirement

Handlers must not block. A handler that blocks — whether on synchronous I/O, a database call, a lock acquisition, or `Thread.sleep()` — stalls the entire event loop and prevents any other connection from being serviced for the duration of the block. Long-running work must be dispatched to a separate `ExecutorService`; the handler submits the task and returns immediately. When the task completes, it can write results back through the channel by scheduling a write on the event loop (after calling `Selector.wakeup()`).

### SelectionKey

A `SelectionKey` is a token representing the registration of a specific channel with a specific selector. It carries the channel reference, the selector reference, the interest set (which operations to monitor), and the ready set (which operations are currently ready). Crucially, a `SelectionKey` also holds an **attachment**: an arbitrary object that can be set via `key.attach(handler)` and retrieved via `key.attachment()`. The Reactor pattern stores the handler object as the attachment so that the event loop can call `handler.handle()` without a separate dispatch table.

### Handler Pattern

Each `SelectionKey` carries an attachment that implements a common dispatch interface — typically `Runnable` or a custom `Handler` interface with a `handle()` method. The event loop iterates over selected keys and calls `((Runnable) key.attachment()).run()` for each. This keeps the event loop itself simple and allows handlers to be registered and replaced independently.

### Acceptor vs IO Handler

The lifecycle of a connection involves two types of handlers. The **Acceptor** registers with the server channel for `OP_ACCEPT`. When a new connection arrives, it calls `serverChannel.accept()` to obtain the `SocketChannel`, configures it non-blocking, and registers it with the selector for `OP_READ` with a new `IOHandler` as its attachment. The **IOHandler** handles `OP_READ` events (data from the client) and optionally `OP_WRITE` events (when output buffer space becomes available). When reading is done and a response is ready, the handler can write it directly or register for `OP_WRITE` if the write would block.

## Gotchas

**Blocking inside a handler freezes the entire event loop.** Any synchronous I/O, database query, lock acquisition, or long computation in a handler prevents the reactor from processing any other connection for its entire duration. Handlers must submit blocking work to a dedicated thread pool, capture the channel reference, and schedule a write-back when the work completes. The handler itself must return immediately.

**`SelectionKey.cancel()` must be called before closing the channel.** Closing a channel without cancelling its key leaves the selector's internal registration table in an inconsistent state. The selector continues tracking the closed channel, and subsequent calls to `select()` may return keys for the closed channel, causing `IOException` on every iteration. Always cancel the key first, then close the channel.

**`ByteBuffer` position and limit must be managed with `flip()` and `clear()`.** After reading data from a channel into a buffer (`channel.read(buffer)`), the buffer is in write mode: position is at the end of the written data, limit is at capacity. To read the data back out, call `buffer.flip()` first — this sets limit to the current position and resets position to zero. Before writing into the buffer again, call `buffer.clear()`. Forgetting `flip()` results in reading zero bytes; forgetting `clear()` causes stale data from previous reads to be processed.

**`Selector.select()` can return 0 on a spurious wakeup.** The contract of `select()` does not guarantee that a return means channels are ready. A return value of 0 means no keys are ready; the event loop must check the return value and skip key processing when it is zero, otherwise `selectedKeys()` returns an empty set and the loop continues harmlessly but wastes CPU.

**Registering a new channel from a different thread while `select()` is blocked may deadlock.** `Selector.register()` and `select()` contend on an internal lock inside the JVM's `Selector` implementation. If the reactor thread is blocked in `select()` and another thread attempts to register a channel, the registration may block until `select()` returns. The fix is to call `selector.wakeup()` before registering from any thread other than the reactor thread, which causes `select()` to return immediately.

**The Reactor pattern is single-threaded — shared state on the event loop thread needs no synchronization, but off-loop state does.** Any data structure read and written only from the event loop thread (such as per-connection state stored in handler objects) requires no synchronization. However, if handlers submit work to a thread pool that then writes results back to shared state (such as a response cache), that state must be protected with appropriate synchronization or passed back through a thread-safe channel.
