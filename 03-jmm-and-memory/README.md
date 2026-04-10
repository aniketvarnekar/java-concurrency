# 03 — JMM and Memory

The Java Memory Model (JMM) is the formal specification that governs how threads interact through memory. Understanding it is essential for writing correct concurrent programs, because without it the behavior of multi-threaded code is simply undefined. This section covers the model itself, the distinction between visibility and atomicity, instruction reordering, and the special guarantees provided by final fields.

## Contents — Notes

| File | Topic |
|------|-------|
| [01-java-memory-model.md](01-java-memory-model.md) | What the JMM is, main memory vs working memory, the three memory properties |
| [02-visibility-atomicity.md](02-visibility-atomicity.md) | Visibility and atomicity as separate concerns; volatile, synchronized, atomic classes |
| [03-reordering-and-fences.md](03-reordering-and-fences.md) | Compiler and CPU reordering; memory barriers inserted by volatile and synchronized |
| [04-final-fields.md](04-final-fields.md) | Safe publication via final fields; freeze actions; this-escape pitfall |

## Contents — Examples

| Folder | Description |
|--------|-------------|
| [examples/visibilitydemo/](examples/visibilitydemo/) | Demonstrates a visibility bug with a stop flag and its fix using volatile |
| [examples/finalfielddemo/](examples/finalfielddemo/) | Demonstrates safe publication of an object via final fields compared to non-final |
