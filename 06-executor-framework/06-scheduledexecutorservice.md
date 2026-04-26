# ScheduledExecutorService

## Overview

ScheduledExecutorService extends ExecutorService with three scheduling methods: schedule() for one-time delayed execution, scheduleAtFixedRate() for periodic execution measured from the start of each task, and scheduleWithFixedDelay() for periodic execution measured from the end of each task. Choosing between the two periodic modes depends on whether a fixed throughput rate or a fixed cool-down between runs is the desired behavior.

scheduleAtFixedRate maintains a consistent execution cadence regardless of task duration, making it appropriate for fixed-interval operations such as polling, metrics collection, or heartbeat signals. scheduleWithFixedDelay ensures a consistent gap between the end of one run and the start of the next, making it appropriate for retry loops or maintenance tasks that should not pile up if the task takes variable time.

Both periodic methods share a critical failure behavior: if the task throws an unchecked exception, all future scheduled executions are silently cancelled. The ScheduledFuture transitions to the done state and the exception is stored inside, but no log entry appears and no further executions occur unless the application explicitly catches the exception inside the task body.

## Key Concepts

### schedule(Callable/Runnable, delay, unit)

Runs the task once after the specified delay. Returns a ScheduledFuture whose get() blocks until the task completes. Suitable for one-time deferred actions such as session expiry, delayed retry, or timeout enforcement.

```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
ScheduledFuture<String> future = scheduler.schedule(
        () -> "hello", 5, TimeUnit.SECONDS);
String result = future.get(); // blocks up to 5s + task duration
```

### scheduleAtFixedRate

Fires every period measured from the scheduled start time of the previous execution. If the task takes less than the period, the next execution starts at the next scheduled time. If the task takes longer than the period, the next execution starts immediately after the previous one finishes — there is no concurrent overlap and no catch-up burst.

```java
scheduler.scheduleAtFixedRate(task, initialDelay, period, TimeUnit.SECONDS);
```

### scheduleWithFixedDelay

Waits for delay time after the previous task completes before starting the next one. The gap between the end of one run and the start of the next is always at least delay. If the task takes variable time, the inter-execution intervals vary proportionally.

```java
scheduler.scheduleWithFixedDelay(task, initialDelay, delay, TimeUnit.SECONDS);
```

### Timing Comparison

```
scheduleAtFixedRate(period=5s):
Task 1:  |==2s==|
Task 2:                 |==2s==|
Task 3:                                  |==2s==|
         0              5              10             15

If task takes longer than period:
Task 1:  |===========7s===========|
Task 2:                            |====2s====|  (starts immediately after task 1)
         0                         7

scheduleWithFixedDelay(delay=3s):
Task 1:  |====2s====|
                    |---3s---|
Task 2:                      |====2s====|
                                        |---3s---|
Task 3:                                          |====2s====|
         0          2        5          7        10
```

### Task Failure Behavior

An unchecked exception escaping the task body causes the ScheduledFuture to complete exceptionally and cancels all future scheduled executions. No further invocations occur. The ScheduledFuture.get() will throw ExecutionException for that specific run if called. The correct pattern is to wrap the entire task body in try/catch(Exception e).

```java
scheduler.scheduleAtFixedRate(() -> {
    try {
        performWork();
    } catch (Exception e) {
        log.error("Scheduled task failed, next run will still occur", e);
    }
}, 0, 10, TimeUnit.SECONDS);
```

### Shutdown

ScheduledExecutorService threads are non-daemon by default. The JVM will not exit if the scheduler is not shut down. Always call shutdown() (or shutdownNow()) in a finally block or register a shutdown hook.

### Delay Accuracy

scheduleAtFixedRate guarantees that executions are not more frequent than the specified period, but can be delayed by GC pauses, OS scheduling latency, or thread pool saturation. It is not a real-time scheduler. For tasks that need sub-millisecond precision, a dedicated real-time framework is required.

## Gotchas

**An unchecked exception escaping the task body silently cancels all future executions.** The ScheduledFuture is marked done with the exception as cause, but no log or error appears unless someone explicitly calls get() on the future. The task simply stops executing with no notice. Always wrap the entire scheduled task body in try/catch(Exception e) to preserve future executions and log the error.

**scheduleAtFixedRate does not start a new task while the previous one is still running — it is not a fixed-rate concurrent burst.** If a task takes longer than the period, the next execution is delayed until the current one completes. The overall execution rate is capped at one concurrent execution per scheduled task. If concurrent executions are needed, submit independent tasks manually.

**scheduleAtFixedRate's period is measured from the scheduled start time, not the actual start time.** If a GC pause or thread pool saturation delays a scheduled execution by 2 seconds, the following execution is still scheduled relative to the original time — it may fire almost immediately after the delayed execution finishes. This can create the impression of a burst when the system recovers from a pause.

**The JVM will not exit if a ScheduledExecutorService is not shut down.** Its worker threads are non-daemon threads. An application that creates a ScheduledExecutorService in a component and never calls shutdown() will prevent the JVM from terminating after the main method returns. Always call shutdown() in a finally block or register a JVM shutdown hook.

**ScheduledFuture.cancel(false) prevents future scheduled executions but does not interrupt a currently running execution.** A long-running iteration will complete its current run even after cancel(false) returns true. Use cancel(true) if the current execution should be interrupted, and ensure the task responds to Thread.isInterrupted().

**Using java.util.Timer instead of ScheduledExecutorService is a common legacy mistake.** Timer runs all scheduled tasks on a single thread — one slow task delays all others. An unchecked exception in any Timer task permanently kills the Timer thread, cancelling all remaining tasks with no recovery. ScheduledExecutorService uses a configurable thread pool and isolates task failures, making it strictly superior to Timer for new code.
