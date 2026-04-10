# 05 — Atomic Classes

The `java.util.concurrent.atomic` package provides classes that perform thread-safe operations on single variables without using locks. These classes are the building blocks for lock-free algorithms and are frequently used in high-performance concurrent code where the overhead of `synchronized` or `ReentrantLock` is undesirable.

Understanding atomic classes requires understanding Compare-And-Swap (CAS), the hardware primitive that powers them. This section covers the full API of each atomic class, the internals of CAS, the ABA problem it introduces, and how `LongAdder` improves on `AtomicLong` under high contention.

## Contents — Notes

| File | Topic |
|---|---|
| [01-atomic-primitives.md](01-atomic-primitives.md) | Full API of AtomicInteger, AtomicLong, AtomicBoolean, and atomic arrays |
| [02-atomic-reference.md](02-atomic-reference.md) | AtomicReference, the ABA problem, AtomicStampedReference, and AtomicMarkableReference |
| [03-cas-internals.md](03-cas-internals.md) | How CAS works at the hardware level, the CAS loop pattern, and VarHandle |
| [04-longadder-vs-atomiclong.md](04-longadder-vs-atomiclong.md) | LongAdder cell striping under contention and when to prefer each |

## Contents — Examples

| Folder | Description |
|---|---|
| [examples/atomiccounterdemo/](examples/atomiccounterdemo/) | Multi-threaded counter using AtomicInteger, one-shot flag with AtomicBoolean, and per-index counters with AtomicIntegerArray |
| [examples/casdemo/](examples/casdemo/) | Manual CAS loop correctness, ABA problem with AtomicReference, and fix with AtomicStampedReference |
