# 04 — Locks and Conditions

The `java.util.concurrent.locks` package provides explicit locking primitives that go beyond what `synchronized` offers, including timed and interruptible lock acquisition, read-write separation, optimistic reads, and multiple condition queues per lock. This section covers each major lock type in depth and ends with a direct comparison to help you choose between explicit locks and `synchronized`.

## Contents — Notes

| File | Topic |
|------|-------|
| [01-reentrant-lock.md](01-reentrant-lock.md) | ReentrantLock basics, fairness, tryLock, and lockInterruptibly |
| [02-read-write-lock.md](02-read-write-lock.md) | ReadWriteLock read/write semantics, lock downgrading, use cases |
| [03-stamped-lock.md](03-stamped-lock.md) | StampedLock optimistic reads, stamp-based API, lock conversion |
| [04-condition-variables.md](04-condition-variables.md) | Condition.await/signal, multiple conditions, producer-consumer pattern |
| [05-lock-vs-synchronized.md](05-lock-vs-synchronized.md) | Feature comparison table, when to use each, virtual thread considerations |

## Contents — Examples

| Folder | Description |
|--------|-------------|
| [examples/reentrantlockdemo/](examples/reentrantlockdemo/) | Shows basic lock/unlock, tryLock deadlock avoidance, and lockInterruptibly |
| [examples/readwritelockdemo/](examples/readwritelockdemo/) | Demonstrates concurrent readers, exclusive writer, and lock downgrading |
| [examples/stampedlockdemo/](examples/stampedlockdemo/) | Shows optimistic reads with validate() fallback and write locking on a 2D point |
| [examples/conditiondemo/](examples/conditiondemo/) | Bounded buffer with two Conditions showing targeted producer/consumer signaling, plus timed await |
