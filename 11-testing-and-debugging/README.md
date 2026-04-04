# 11 — Testing and Debugging Concurrent Code

Testing concurrent code requires a different mindset than testing sequential code. Non-deterministic thread scheduling means a bug may appear only under specific interleavings that are impossible to predict or reproduce reliably. This section covers practical strategies for testing concurrent code, specialized tools like jcstress, and the diagnostic techniques — thread dumps and profilers — that help identify problems in running systems.

## Contents — Notes

| File | Description |
|---|---|
| [01-testing-concurrent-code.md](01-testing-concurrent-code.md) | Strategies for testing thread-safe code: single-threaded units, stress tests, structured concurrency tests |
| [02-jcstress.md](02-jcstress.md) | The jcstress harness: @JCStressTest anatomy, @Outcome, running and reading results |
| [03-thread-dumps.md](03-thread-dumps.md) | How to capture and read thread dumps; diagnosing deadlocks and stuck threads |
| [04-profiling-tools.md](04-profiling-tools.md) | async-profiler flame graphs and JFR lock events for identifying lock contention |

## Contents — Examples

| File | Description |
|---|---|
| [examples/JCStressExamples.java](examples/JCStressExamples.java) | Simulates jcstress-style actor/outcome testing without the harness; demonstrates the design pattern |
