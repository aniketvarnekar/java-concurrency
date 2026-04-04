# 08 — Synchronization Aids

This section covers the higher-level synchronization utilities in `java.util.concurrent` that coordinate the timing of multiple threads without low-level `wait`/`notify`. Each aid solves a specific coordination pattern: one-shot barriers, reusable barriers, resource throttling, data exchange, and flexible multi-phase coordination.

Rather than replacing locks, these utilities sit above them. They express intent at a higher level of abstraction — a `CountDownLatch` communicates "wait until N events have occurred" far more clearly than an equivalent `wait`/`notifyAll` loop, and the built-in implementations are correct, tested, and optimized for the JVM.

## Contents

### Concept Files

| File | Description |
|---|---|
| [01-countdownlatch.md](./01-countdownlatch.md) | One-shot countdown barrier for start-gate and end-gate patterns |
| [02-cyclicbarrier.md](./02-cyclicbarrier.md) | Reusable barrier with optional barrier action for multi-phase algorithms |
| [03-semaphore.md](./03-semaphore.md) | Permit-based concurrency limiter for resource pool guarding |
| [04-exchanger.md](./04-exchanger.md) | Two-party synchronous object swap for pipeline handoff |
| [05-phaser.md](./05-phaser.md) | Dynamic multi-phase barrier generalizing CountDownLatch and CyclicBarrier |

### Example Files

| File | Description |
|---|---|
| [CountDownLatchDemo.java](./examples/CountDownLatchDemo.java) | Start-gate and end-gate patterns with CountDownLatch |
| [CyclicBarrierDemo.java](./examples/CyclicBarrierDemo.java) | Multi-phase simulation with barrier action |
| [SemaphoreDemo.java](./examples/SemaphoreDemo.java) | Connection pool guarded by Semaphore |
