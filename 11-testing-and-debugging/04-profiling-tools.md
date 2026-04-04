# Profiling Tools

## Overview

Profiling concurrent Java applications requires tools that accurately capture where threads spend time — whether executing on CPU, waiting for locks, or blocked on IO. Standard sampling profilers that only sample at JVM safepoints give a systematically biased picture, underrepresenting code in tight loops between safepoints. For concurrent systems, two tools are the current standard: async-profiler for accurate, low-overhead sampling and Java Flight Recorder (JFR) for built-in event recording.

Both tools can be used in production. async-profiler uses OS-level kernel events (perf_events on Linux, DTrace on macOS) or the AsyncGetCallTrace API to sample without safepoint restriction. JFR is embedded in HotSpot JVM and records structured events with overhead below 2%, making it suitable for always-on monitoring.

The output formats differ: async-profiler generates flame graphs (HTML or SVG) that show the proportion of time spent in each call path, while JFR produces `.jfr` binary files that are analyzed with JDK Mission Control (JMC) and expose timeline views, lock contention tables, and GC event correlation.

## Key Concepts

### The Safepoint Sampling Problem

Traditional JVM profilers (VisualVM CPU sampler, older commercial profilers) can only inspect thread stacks at safepoints — checkpoints inserted by the JVM for GC, deoptimization, and other VM operations. Code in tight computational loops that have no safepoints between iterations is invisible to such profilers. The sampled profile shows inflated time in methods that happen to be near safepoints and completely omits the actual CPU consumers. async-profiler avoids this by using OS-level signals (SIGPROF) to interrupt threads at arbitrary points, independent of the JVM's safepoint mechanism.

### async-profiler

async-profiler is an open-source low-overhead profiler for HotSpot JVM that supports CPU, wall-clock, lock, and allocation profiling modes.

Key modes:

| Flag | Mode | Captures |
|---|---|---|
| `-e cpu` | CPU time | Time spent executing on CPU |
| `-e wall` | Wall-clock | All threads including IO-waiting |
| `-e lock` | Monitor contention | Time waiting to enter `synchronized` |
| `-e alloc` | Allocation | Object allocation call paths |

Usage pattern:

```bash
# Attach to a running JVM (pid 12345) and profile locks for 30 seconds
./profiler.sh -e lock -d 30 -f lock-flame.html 12345

# CPU flame graph
./profiler.sh -e cpu -d 30 -f cpu-flame.svg 12345

# Wall-clock (useful for latency analysis including IO waits)
./profiler.sh -e wall -d 30 -f wall-flame.html 12345
```

### Flame Graphs

A flame graph visualizes sampled stack traces. The x-axis represents the proportion of total samples (wider = more time). The y-axis represents call stack depth (bottom = thread entry point, top = the method where time is spent). To identify a bottleneck, find the widest bars at the highest y position — those are the leaf methods consuming the most time.

For lock contention profiling, the flame graph shows which code paths spend time blocked waiting for monitors. Wide bars in `Object.wait()` or `AbstractQueuedSynchronizer.parkAndCheckInterrupt()` indicate threads spending significant time waiting for locks or conditions.

```
                     [wide bar here = bottleneck]
               doWork()
          processItem()
     handleRequest()
  run()
```

### Java Flight Recorder (JFR)

JFR is embedded in HotSpot JVM since Java 11 (free to use; previously commercial). It records a stream of typed events to a memory ring buffer and flushes to disk on demand or at interval.

Starting a recording:

```bash
# At JVM startup
java -XX:StartFlightRecording=duration=60s,filename=recording.jfr MyApp

# Attach to running JVM
jcmd 12345 JFR.start duration=60s filename=recording.jfr

# Dump the current recording buffer (for continuous recording)
jcmd 12345 JFR.dump filename=snapshot.jfr
```

Lock-relevant JFR events:

| Event | Meaning |
|---|---|
| `jdk.JavaMonitorEnter` | Thread blocked entering a `synchronized` block |
| `jdk.JavaMonitorWait` | Thread in `Object.wait()` |
| `jdk.ThreadPark` | Thread blocked in `LockSupport.park()` (ReentrantLock, Condition) |
| `jdk.JavaMonitorInflate` | Monitor inflated to heavyweight (contention detected) |

By default, JFR records only events exceeding a threshold (10ms for lock events). Tune with:

```bash
java -XX:+UnlockDiagnosticVMOptions \
     -XX:FlightRecorderOptions=stackdepth=128 \
     -XX:StartFlightRecording=duration=60s,filename=rec.jfr MyApp
```

### JDK Mission Control (JMC)

JMC is the GUI for analyzing `.jfr` files. Key views:
- Thread activity timeline: shows each thread's state (running, IO, lock) over time
- Lock Instances view: lists monitored objects ranked by contention duration
- Hot Methods: sampling-based CPU hotspot identification
- Allocation Pressure: top allocating call paths

### Correlating async-profiler with Thread Dumps

The `nid` (native thread ID) in a thread dump is the OS thread ID in hexadecimal. To correlate with CPU usage:

```bash
# Find Java PIDs
jps

# See per-thread CPU (Linux)
top -H -p 12345

# Convert top's TID (decimal) to hex to match nid in thread dump
printf '%x\n' 6699    # → 1a2b
```

## Code Snippet

```java
/**
 * Creates artificial lock contention for profiling demonstration.
 * Run for 30 seconds, then profile with async-profiler or JFR.
 *
 * async-profiler lock profile:
 *   ./profiler.sh -e lock -d 30 -f lock-flame.html <pid>
 *
 * JFR recording:
 *   jcmd <pid> JFR.start duration=30s filename=lock-contention.jfr
 *   jcmd <pid> JFR.dump filename=lock-contention.jfr
 *   # Open lock-contention.jfr in JDK Mission Control
 *
 * Run: javac LockContentionDemo.java && java LockContentionDemo
 */
import java.util.concurrent.locks.ReentrantLock;

public class LockContentionDemo {

    static final ReentrantLock LOCK  = new ReentrantLock();
    static volatile long       count = 0;

    public static void main(String[] args) throws InterruptedException {
        int threadCount = 8;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int id = i;
            threads[i] = new Thread(() -> {
                long endTime = System.currentTimeMillis() + 30_000L;
                while (System.currentTimeMillis() < endTime) {
                    LOCK.lock();
                    try {
                        // Hold lock for 10ms — creates contention
                        Thread.sleep(10);
                        count++;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } finally {
                        LOCK.unlock();
                    }
                }
                System.out.println("Thread-" + id + " finished, count=" + count);
            }, "contention-thread-" + i);
        }

        System.out.println("Starting " + threadCount + " threads. PID: " +
            ProcessHandle.current().pid());
        System.out.println("Profile now with async-profiler or JFR.");
        System.out.println("Running for 30 seconds...");

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        System.out.println("Done. Total count: " + count);
    }
}
```

## Gotchas

### async-profiler requires OS-level permissions
On Linux, perf_events profiling requires `kernel.perf_event_paranoid` to be 1 or lower (`sudo sysctl kernel.perf_event_paranoid=1`). On macOS, SIP restrictions may prevent DTrace-based profiling without root. Document the setup requirement before deploying async-profiler in CI or production.

### Wall-clock mode shows IO-waiting threads as "hot"
The `-e wall` mode samples all threads including those blocked on IO. A method spending 90% of its time in a socket read will appear as 90% of the flame graph width, even if it uses zero CPU. This is useful for latency analysis but can be mistaken for a CPU bottleneck — confirm with `-e cpu` mode before optimizing.

### JFR's default lock threshold is 10ms
Events shorter than the threshold are not recorded. For low-latency applications with sub-millisecond lock contention, the default threshold produces an empty lock report. Lower the threshold with `FlightRecorderOptions`, accepting higher recording overhead and larger output files.

### Profiling in debug mode inflates all timings
Running with assertions enabled (`-ea`), extra logging, or debug JVM flags changes the hot spots significantly. Always profile with production-equivalent JVM flags and a production-representative workload. A profile taken in debug mode can mislead optimization efforts entirely.

### Flame graph width is proportional, not absolute
A flame graph bar representing 5% of samples in a 30-second recording corresponds to 1.5 seconds of total thread time. Without knowing the sample rate and duration, the graph shows relative proportions only. When comparing two profiles, ensure they were captured under the same load and for the same duration.

### JFR continuous mode is safe for production
JFR with continuous recording and a 1-minute ring buffer adds less than 1% overhead and can be left on permanently. Running `jcmd <pid> JFR.dump` takes a snapshot of the last minute on demand. This is far less invasive than attaching async-profiler or enabling verbose GC logging after an incident is already in progress.
