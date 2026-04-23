# AGENTS.md

This file is read automatically at the start of every session.
It describes the project structure, conventions, and workflows for this repository.

---

## What this repository is

A structured reference for Java concurrency, covering everything from thread
fundamentals to advanced topics like virtual threads and lock-free algorithms.
Each section contains topic notes in Markdown and runnable Java examples.
The target reader is a developer who is comfortable with Java but new to concurrency.

---

## Repository layout

```
java-concurrency/
  README.md
  AGENTS.md
  NN-section-name/
    README.md
    NN-topic-name.md
    examples/
      ConceptNameDemo/
        Main.java
        SupportingType.java
```

### Sections

| # | Directory | Topics |
|---|-----------|--------|
| 01 | 01-foundations | Threads, lifecycle, daemon threads |
| 02 | 02-synchronization | Race conditions, synchronized, volatile, deadlock |
| 03 | 03-jmm-and-memory | Java Memory Model, visibility, reordering |
| 04 | 04-locks-and-conditions | ReentrantLock, ReadWriteLock, StampedLock |
| 05 | 05-atomic-classes | AtomicInteger, CAS, LongAdder |
| 06 | 06-executor-framework | Thread pools, Future, CompletableFuture |
| 07 | 07-concurrent-collections | ConcurrentHashMap, BlockingQueue, CopyOnWrite |
| 08 | 08-synchronization-aids | CountDownLatch, CyclicBarrier, Semaphore, Phaser |
| 09 | 09-concurrency-patterns | Producer-Consumer, Thread-local, Active Object |
| 10 | 10-advanced-topics | Fork/Join, Virtual Threads, Lock-free algorithms |
| 11 | 11-testing-and-debugging | jcstress, thread dumps, profiling |
| 12 | 12-interview-prep | Common questions, tricky scenarios, cheatsheet |

---

## Java version

Target: **Java 25**.

---

## Target reader

A developer who is new to Java concurrency but comfortable with Java in general.
Write precisely and technically — do not dumb things down — but build concepts
from the ground up within each file. Every term should be defined on first use.

---

## Note file format

Every topic `.md` file must follow this structure in this exact order:

```
# <Title>

## Overview
2–4 paragraphs of plain prose. No bullet points.

## Key Concepts
Named subsections using ### headings.
Use prose, tables, ASCII diagrams, and code blocks where they help.
Code blocks are not mandatory — include them only when they materially
aid understanding. Omit them when a diagram or table does the job better.

## Gotchas
4–6 specific pitfalls written as plain prose paragraphs. No sub-bullets.
Each pitfall is 2–4 sentences.
```

---

## Section README format

Each section `README.md` must contain:

1. A prose intro of 2–3 sentences describing what the section covers.
2. A contents table linking every `.md` file with a one-line description.
3. A contents table linking every example folder with a one-line description.

---

## Example conventions

### Folder structure

Each runnable example lives in its own folder under `examples/`, named in
lowercase (e.g. `examples/deadlockdemo/`). The folder may contain as many
`.java` files as the concept requires. Split types across files the same way
you would in a normal IntelliJ IDEA project. Use a package name that mirrors
the folder path, e.g. `package examples.deadlockdemo;`.

Every example folder must have a `Main.java` with a `main` method as its
entry point. The project must open and run in IntelliJ IDEA as a plain Java
project with the `examples/` directory on the source root — no build tool
configuration required.

### Comments

Every file must open with a block comment explaining what the file represents
within the example.

Inline comments must explain the concurrency reasoning — the *why* behind a
design decision, a potential hazard, or a guarantee being relied upon. They
must not restate what the code literally does. A comment above a block of
related lines is preferred over a comment on every individual line.

`System.out.println` is allowed only when the printed output is itself the
observable result of the demonstration, such as thread state transitions,
timing measurements, or detected deadlocks. Do not use print statements as
a substitute for comments.

The code and its comments together must make the example self-explanatory
without requiring the reader to open the accompanying `.md` file.

### Thread naming

Every thread must have a descriptive name assigned at construction time via
the `Thread` constructor, `Thread.ofVirtual().name(...)`, or a `ThreadFactory`.
Names must reflect the thread's role (e.g. `"producer-1"`, `"db-worker-2"`).
Generic names like `"t1"` or `"thread1"` are not acceptable.

---

## Writing rules

- No emojis anywhere in the repository.
- No `## References` section in any file.
- No bullet points in prose — use `###` subsections instead.
- No filler phrases: "it's worth noting", "importantly", "keep in mind",
  "note that", "it should be noted".
- ASCII diagrams are encouraged where they clarify structure or flow.
- Comparison tables are encouraged.
- All code blocks in `.md` files must use triple-backtick `java` syntax
  highlighting unless the block contains shell output, YAML, or plain text.
- When editing an existing file, do not reformat, rewrite, or add to sections
  that were not part of the request.
- Do not add features, refactor, or make improvements beyond what was
  explicitly asked.

---

## Workflows

### Adding a new topic to an existing section

1. Read the section's existing `.md` files to match tone and depth.
2. Use the next available number prefix for the filename.
3. Write the file following the note file format above.
4. Add a row to the section `README.md` contents table.
5. If a new example folder is also created, update the examples table too.

### Adding a new example

1. Create a new PascalCase folder under the relevant section's `examples/`.
2. Add `Main.java` and any supporting files following the conventions above.
3. Update the section `README.md` examples table.

### Adding a new section

1. Use the next two-digit prefix (e.g. `13-new-section`).
2. Create `README.md`, all topic `.md` files, and `examples/` with at least
   one example folder.
3. Update the root `README.md` contents table.
4. Add the new section to the **Sections** table in this file.

### Editing an existing file

1. Read the full file before making changes.
2. Match the existing structure and tone exactly.
3. Change only what was explicitly requested.

### Updating the Java version

1. Update the **Java version** section in this file.
