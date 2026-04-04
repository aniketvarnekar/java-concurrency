# 02 — Synchronization

This section covers the fundamental mechanisms Java provides for coordinating access to shared mutable state between threads. Starting from the root problem of race conditions, it builds through the synchronized keyword and volatile variable, through the Java Memory Model's happens-before rules, and culminates in the three classic failure modes: deadlock, livelock, and starvation.

Each note is self-contained but they are ordered to build on each other. Reading them in sequence is recommended for newcomers.

## Contents — Notes

| File | Topic |
|------|-------|
| [01-race-conditions.md](01-race-conditions.md) | What race conditions are, the two primary patterns (check-then-act, read-modify-write), and lost update anatomy |
| [02-synchronized-keyword.md](02-synchronized-keyword.md) | All three synchronized forms, reentrancy, and why private lock objects matter |
| [03-volatile-keyword.md](03-volatile-keyword.md) | Volatile visibility guarantee, its atomicity limits, and the correct double-checked locking idiom |
| [04-happens-before.md](04-happens-before.md) | All eight JMM happens-before rules with code illustrations for each |
| [05-deadlock-livelock-starvation.md](05-deadlock-livelock-starvation.md) | Deadlock conditions and prevention strategies, livelock with randomized backoff, starvation with fair locks, and runtime detection via ThreadMXBean |

## Contents — Examples

| File | Description |
|------|-------------|
| [examples/RaceConditionDemo.java](examples/RaceConditionDemo.java) | Two threads increment a shared counter 100,000 times with no synchronization, exposing lost updates; also demonstrates a check-then-act race in lazy initialization |
| [examples/SynchronizedDemo.java](examples/SynchronizedDemo.java) | BankAccount-style example covering synchronized instance methods, static methods, synchronized blocks on a private lock, and reentrant locking via a recursive method |
| [examples/DeadlockDemo.java](examples/DeadlockDemo.java) | Two threads acquire two locks in opposite order to produce a deadlock, detects it with ThreadMXBean, then shows the fixed version using consistent lock ordering |
