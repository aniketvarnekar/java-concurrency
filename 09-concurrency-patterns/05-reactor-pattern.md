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

## Code Snippet

```java
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;

/**
 * NIO echo server demonstrating the Reactor pattern.
 * Opens a ServerSocketChannel on an ephemeral port, registers it with a
 * Selector for OP_ACCEPT. The event loop accepts connections, registers
 * them for OP_READ, and echoes data back to the client.
 *
 * Run: javac ReactorPatternDemo.java && java ReactorPatternDemo
 */
public class ReactorPatternDemo {

    // ---------------------------------------------------------------
    // Handler interface — the event loop calls handle() for each ready key
    // ---------------------------------------------------------------
    interface Handler {
        void handle(SelectionKey key) throws IOException;
    }

    // ---------------------------------------------------------------
    // Acceptor: handles OP_ACCEPT, registers new connections for OP_READ
    // ---------------------------------------------------------------
    static class Acceptor implements Handler {
        private final Selector selector;

        Acceptor(Selector selector) {
            this.selector = selector;
        }

        @Override
        public void handle(SelectionKey key) throws IOException {
            ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
            SocketChannel client = serverChannel.accept();
            if (client == null) return; // spurious wakeup guard

            client.configureBlocking(false);
            System.out.printf("[%s] Accepted connection from %s%n",
                    Thread.currentThread().getName(), client.getRemoteAddress());

            // Register the new client channel for OP_READ with an IOHandler attachment
            SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ);
            clientKey.attach(new IOHandler());
        }
    }

    // ---------------------------------------------------------------
    // IOHandler: handles OP_READ, echoes data back
    // ---------------------------------------------------------------
    static class IOHandler implements Handler {
        private final ByteBuffer buffer = ByteBuffer.allocate(256);

        @Override
        public void handle(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();
            buffer.clear(); // prepare buffer for reading from channel

            int bytesRead;
            try {
                bytesRead = channel.read(buffer);
            } catch (IOException e) {
                closeKey(key);
                return;
            }

            if (bytesRead == -1) {
                // Client closed the connection
                System.out.printf("[%s] Client disconnected%n",
                        Thread.currentThread().getName());
                closeKey(key);
                return;
            }

            if (bytesRead > 0) {
                buffer.flip(); // switch buffer from write-mode to read-mode
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                String received = new String(data).trim();
                System.out.printf("[%s] Read %d bytes: \"%s\" — echoing back%n",
                        Thread.currentThread().getName(), bytesRead, received);

                // Echo: write back to client
                buffer.rewind();
                channel.write(buffer);
            }
        }

        private void closeKey(SelectionKey key) throws IOException {
            key.cancel(); // cancel before closing the channel
            key.channel().close();
        }
    }

    // ---------------------------------------------------------------
    // Reactor: the event loop
    // ---------------------------------------------------------------
    static class Reactor implements Runnable {
        private final Selector selector;
        private volatile boolean running = true;

        Reactor(Selector selector) {
            this.selector = selector;
        }

        public void stop() {
            running = false;
            selector.wakeup(); // unblock select() so the loop can check running
        }

        @Override
        public void run() {
            System.out.printf("[%s] Reactor event loop started%n",
                    Thread.currentThread().getName());
            while (running) {
                try {
                    // Blocks until at least one channel is ready
                    int ready = selector.select(500); // 500ms timeout

                    // select() can return 0 on spurious wakeup — must guard
                    if (ready == 0) continue;

                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> iter = selectedKeys.iterator();

                    while (iter.hasNext()) {
                        SelectionKey key = iter.next();
                        iter.remove(); // must remove from selected set manually

                        if (!key.isValid()) continue;

                        Handler handler = (Handler) key.attachment();
                        try {
                            handler.handle(key);
                        } catch (IOException e) {
                            System.err.printf("[%s] Handler error: %s%n",
                                    Thread.currentThread().getName(), e.getMessage());
                            key.cancel();
                            key.channel().close();
                        }
                    }
                } catch (IOException e) {
                    if (running) {
                        System.err.printf("[%s] Selector error: %s%n",
                                Thread.currentThread().getName(), e.getMessage());
                    }
                }
            }
            System.out.printf("[%s] Reactor event loop stopped%n",
                    Thread.currentThread().getName());
        }
    }

    // ---------------------------------------------------------------
    // Main: start reactor, connect a client, send data
    // ---------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        Selector selector = Selector.open();

        // Set up server channel
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress("localhost", 0)); // ephemeral port
        int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        System.out.println("Server listening on port " + port);

        // Register server channel for OP_ACCEPT with an Acceptor handler
        SelectionKey serverKey = serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        serverKey.attach(new Acceptor(selector));

        // Start the reactor in a named thread
        Reactor reactor = new Reactor(selector);
        Thread reactorThread = new Thread(reactor, "reactor-event-loop");
        reactorThread.setDaemon(false);
        reactorThread.start();

        // Client thread: connect and send data
        Thread clientThread = new Thread(() -> {
            try (SocketChannel client = SocketChannel.open(
                    new InetSocketAddress("localhost", port))) {

                String[] messages = {"hello", "world", "reactor pattern"};
                for (String msg : messages) {
                    ByteBuffer buf = ByteBuffer.wrap((msg + "\n").getBytes());
                    client.write(buf);
                    System.out.printf("[%s] Sent: \"%s\"%n",
                            Thread.currentThread().getName(), msg);

                    // Read echo
                    ByteBuffer readBuf = ByteBuffer.allocate(256);
                    Thread.sleep(50); // give reactor time to echo
                    readBuf.clear();
                    int n = client.read(readBuf);
                    if (n > 0) {
                        readBuf.flip();
                        byte[] data = new byte[readBuf.remaining()];
                        readBuf.get(data);
                        System.out.printf("[%s] Received echo: \"%s\"%n",
                                Thread.currentThread().getName(),
                                new String(data).trim());
                    }
                }
            } catch (Exception e) {
                System.err.println("[client-thread] Error: " + e.getMessage());
            }
        }, "client-thread");

        clientThread.start();
        clientThread.join();

        // Stop the reactor cleanly
        Thread.sleep(200);
        reactor.stop();
        reactorThread.join();
        serverChannel.close();
        selector.close();
        System.out.println("Done.");
    }
}
```

## Gotchas

**Blocking inside a handler freezes the entire event loop.** Any synchronous I/O, database query, lock acquisition, or long computation in a handler prevents the reactor from processing any other connection for its entire duration. Handlers must submit blocking work to a dedicated thread pool, capture the channel reference, and schedule a write-back when the work completes. The handler itself must return immediately.

**`SelectionKey.cancel()` must be called before closing the channel.** Closing a channel without cancelling its key leaves the selector's internal registration table in an inconsistent state. The selector continues tracking the closed channel, and subsequent calls to `select()` may return keys for the closed channel, causing `IOException` on every iteration. Always cancel the key first, then close the channel.

**`ByteBuffer` position and limit must be managed with `flip()` and `clear()`.** After reading data from a channel into a buffer (`channel.read(buffer)`), the buffer is in write mode: position is at the end of the written data, limit is at capacity. To read the data back out, call `buffer.flip()` first — this sets limit to the current position and resets position to zero. Before writing into the buffer again, call `buffer.clear()`. Forgetting `flip()` results in reading zero bytes; forgetting `clear()` causes stale data from previous reads to be processed.

**`Selector.select()` can return 0 on a spurious wakeup.** The contract of `select()` does not guarantee that a return means channels are ready. A return value of 0 means no keys are ready; the event loop must check the return value and skip key processing when it is zero, otherwise `selectedKeys()` returns an empty set and the loop continues harmlessly but wastes CPU.

**Registering a new channel from a different thread while `select()` is blocked may deadlock.** `Selector.register()` and `select()` contend on an internal lock inside the JVM's `Selector` implementation. If the reactor thread is blocked in `select()` and another thread attempts to register a channel, the registration may block until `select()` returns. The fix is to call `selector.wakeup()` before registering from any thread other than the reactor thread, which causes `select()` to return immediately.

**The Reactor pattern is single-threaded — shared state on the event loop thread needs no synchronization, but off-loop state does.** Any data structure read and written only from the event loop thread (such as per-connection state stored in handler objects) requires no synchronization. However, if handlers submit work to a thread pool that then writes results back to shared state (such as a response cache), that state must be protected with appropriate synchronization or passed back through a thread-safe channel.
