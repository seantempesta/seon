---
type: issue
status: open
severity: blocker
tags: [issue, operator, program-graph]
---

# Priming indexes with the live JVM's loaded code and records a digest that lies

## Problem

`bin/seon index <cluster>` reads source FILES from disk but interprets them with
the `seon.sci.reader` and `seon.fn` the target JVM happened to load at boot. A
long-lived cluster therefore primes its corpus with whatever indexer that JVM
started with, while `index!` records `:seon.ancestor/digest` computed from the
files on disk. The recorded digest then claims "this corpus was synchronized
from source digest X" when the rows were in fact produced by older code. That is
a lying fact in the one place downstream work trusts, and it is silent.

## Evidence

`script/seon/fresh_operator.clj:1212-1224` — `index!` for a named cluster sends
`seon.cluster/index!` over that cluster's prepl (`instance-form`), so
`seon.fn/rows` runs inside the live JVM against its loaded namespaces.

`script/seon/fresh_operator.clj:1199-1210` — `refresh-baseline!` PREFERS a live
anchor JVM over the fresh `clojure -M:dev` subprocess that
`source-process-value!` already implements, so even the baseline can be built by
stale loaded code.

`script/seon/fresh_operator.clj:1036-1067` — `bin/seon start NEW-CLUSTER`
also prefers a live anchor JVM and invokes its loaded `seon.cluster/start!`.
When the current disk-digest ancestor is absent,
`src/seon/cluster.clj:359-414` creates it through the loaded
`populate-ancestor!`, which reads the disk source with that JVM's loaded reader
and indexer and names the result with the current disk digest. The issue affects
new-cluster start as well as explicit indexing.

Observed cost, 2026-07-29: while the indexer defect archived at
`archive/indexer-namespace-allowlist-dropped-two-thirds-of-the-program-graph.md`
was being fixed in the working tree, an agent probing the owner's long-lived
cluster measured "47 arglists, 0 syms" for `src/seon/flow.clj` and concluded the
attribution bug was still live. The disk code was already fixed. A
`(require 'seon.sci.reader :reload)` in that JVM changed the same probe to 47
syms, and re-priming took the corpus from 121 rows to 379. An hour was spent on
archaeology against code that no longer existed, and the operator gave no signal
that its interpreter was older than its input.

## Recommendation

Make the indexer publish its own readiness rather than adding a reload the
caller must remember.

1. The JVM should record, at load time, the digest of the source files its
   indexing namespaces were loaded from — `seon.fn/source-roots` already feeds
   `seon.cluster/ancestor-roots`, and `seon.cluster.ancestor/digest` already
   computes a content digest, so no new mechanism is needed. `index!` compares
   that recorded digest with the current one and REFUSES when they differ,
   naming both. Staleness becomes an observed fact, not a clock or a guess.
2. `refresh-baseline!` should stop preferring the anchor JVM. The baseline store
   is not flock-held by a live cluster, and `source-process-value!` already runs
   a fresh `clojure -M:dev` for exactly this case.

A blanket `:reload` inside the index form is the tempting one-liner and is
rejected: reloading `seon.cluster` in a live cluster re-runs its top-level
schema load and activation against a running system, which is a larger blast
radius than the problem. Per-cluster indexing cannot move to a subprocess at
all, because the live JVM holds the store's `flock`.

## Owner

`script/seon/fresh_operator.clj` start/index selection and `seon.cluster` /
`seon.fn` loaded-indexer readiness.

## Acceptance

`bin/seon index <cluster>` refuses, with both digests named, when the target
JVM's loaded indexing code does not match the tree it is about to index;
`bin/seon index` with no cluster always builds the baseline in a fresh JVM; and
a recorded `:seon.ancestor/digest` therefore implies the rows were produced by
that same source.

Start a JVM with indexer version N, change the on-disk reader/indexer to N+1
without reloading, ensure the N+1 ancestor branch is absent, then run
`bin/seon start <new-name>`. The command refuses before ancestor publication or
cluster fork, naming loaded-code and disk-source digests. It does not publish
an ancestor whose digest names N+1 input but whose rows were interpreted by N.
A genuinely source-current JVM starts the cluster with rows equal to an
independently computed N+1 census.
