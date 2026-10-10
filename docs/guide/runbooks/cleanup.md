# Runbook: cleanup

Removes data a node no longer owns (`nodetool cleanup`). Panel: **Operations → Maintenance**,
operation *Cleanup*.

## When to use

After adding nodes (or reducing replication), on the nodes that existed before, once the new nodes
have joined and streaming has finished. Until then, the old nodes keep copies of the ranges they
handed over.

## Before you start

- Rewrites all SSTables of the keyspace: needs free disk space and adds I/O. Run it **one node at a
  time** (Studio does this) and outside peak hours.
- Make sure every node is UN (**Overview** or **Monitoring → Nodes**) and no node is joining,
  leaving or moving.
- *Jobs (-j)*: how many SSTables are cleaned in parallel; 0 = Cassandra's default.

## Steps in Studio

1. **Operations**, select the nodes that existed before the expansion, **Maintenance**.
2. Choose **Cleanup**, pick the keyspace and tables (none = all), set *Jobs* if needed.
3. Click **Run on N nodes…**.

## What the confirmation shows

```text
nodetool -h 10.0.0.11 cleanup -j 2 -- shop
```

Warning: "Cleanup rewrites all SSTables of the keyspace to drop data the node no longer owns; it
needs free disk space and adds I/O. Run it after adding nodes, one node at a time."

## Verify

- The job ends SUCCEEDED for every node. When Cassandra reports that cleanup was aborted, could not
  be cancelled or failed, that node is marked failed with the reason.
- **Monitoring → Nodes**: *Load* drops on the old nodes. **Views → status** shows the new ownership.

## Stop or roll back

- **Cancel** calls `stopCompaction(CLEANUP)` on the running node and skips the remaining nodes.
  SSTables already cleaned stay cleaned; run cleanup again later to finish.
- Removed data cannot be brought back on this node, but it is still on the replicas that own it.
