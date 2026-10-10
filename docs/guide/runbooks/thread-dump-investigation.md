# Runbook: thread-dump investigation

Finds what a node's threads are doing: stuck requests, lock contention, deadlocks and threads
burning CPU. Panel: **Diagnostics → Threads** (JVM-1, JVM-2).

## When to use

- A node is slow or unresponsive while others are fine; thread pools show pending or blocked tasks
  (**Monitoring → Nodes**, `threadpool.blocked`).
- High CPU on one node.
- Requests time out on one node, or a compaction, repair or streaming seems stuck.

## Before you start

- Read-only, but a thread dump briefly pauses the JVM (like `jstack`); a series repeats that. On a
  node that is already struggling, take a few dumps, not dozens.
- Needs JMX (SSH tunnel or direct). Works on Java 8 to 17 nodes (Cassandra 3.11 to 5.0).

## Steps in Studio

1. Open **Diagnostics → Threads** and pick the **Node**.
2. For a slow or stuck node: set the number of dumps and the seconds between them (for example 3
   dumps, 5 s apart) and click **Take series**. For a quick look: **Take thread dump**.
3. Open a dump:
   - Deadlocks are shown first, in red, as chains ("A waits for X held by B").
   - Blocked threads are listed with the lock they wait for and the chain of owners.
   - The state chips (RUNNABLE, BLOCKED, WAITING, TIMED_WAITING) filter the threads.
   - **Groups** puts threads with an identical stack together, largest group first: a large group
     of request threads on the same frame points at the bottleneck. **Threads** lists each thread;
     filter by name or by a frame (for example `ReadStage` or a class name).
4. **Compare** two dumps of the series: new and gone threads, threads that changed state, and
   threads with the same stack in both dumps. Tick *Hide threads idle in native code* to drop
   threads that just wait in epoll or accept. A thread RUNNABLE or BLOCKED on the same stack across
   dumps is the one to look at.
5. For CPU problems switch to **Top threads (live)**, click **Start live view**: threads sorted by
   CPU (% of one core over the interval), user time, allocation rate and total CPU. Tick *Group
   thread pools* to add up pools such as `CompactionExecutor` or `Native-Transport-Requests`.
6. **Export .txt** saves a dump in jstack format for a ticket or another tool.

## What to look for

| Pattern | Usually means |
|---|---|
| Many `Native-Transport-Requests` or `ReadStage` threads on the same frame | A slow read path: large partitions, tombstones (check Diagnostics → Partitions) or a slow disk |
| `CompactionExecutor` threads busy in top threads | Compaction backlog; check pending compactions |
| Threads BLOCKED on the same lock owned by one thread | Lock contention; the owner's stack shows what holds it |
| A deadlock | A Cassandra bug: collect the dump (Export .txt) for a bug report; the node usually needs a restart |
| High CPU in GC threads | Heap pressure: see the [GC log runbook](gc-log-investigation.md) |

## What the confirmation shows

Nothing to confirm: taking dumps only reads.

## Verify

After acting (for example restarting the node, or fixing a hot partition), take a new dump and
compare: the large stuck group or the blocked chain should be gone, and **Monitoring** should show
thread pools without blocked tasks.

## Stop or roll back

- **Cancel** stops a series between dumps. **Stop** ends the live top-threads view.
- Studio keeps the newest 40 dumps per cluster in memory until you disconnect; **Delete** removes a
  dump.
