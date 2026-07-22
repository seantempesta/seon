---
type: spec
status: active
tags: [spec, agent, architecture]
---

# WP-S2 — lazy crash recovery for the supervised sci host

The missing physics-containment half (containment thesis: the supervisor
contains PHYSICS — the process is disposable, durable truth is datoms,
respawn + corpus replay + honest interrupted receipts). Today the pod
lazily reconnects SESSIONS after host death but nothing relaunches the
JVM PROCESS (grounding: research/wps-supervision-grounding-2026-07-22.md
§3; its risks 3/4 are since CLOSED by WP-S1a — dead-only socket
deletion in seon.host, birth-path coordinate publisher in agent.cljs).

STOPPING EARLY IS FREE: read the real source first; report better seams
and the owners' exact terms; a stop with file:line evidence beats an
improvised mechanism.

## Accepted design (orchestrator, under decision 6 + the grounding's recommended ruling)

The pod is the host's CLIENT; process ownership stays with the
OPERATOR. One new narrow operator verb:

    bin/seon ensure host

reconciles exactly the host member of the managed graph under the
standard operator lock — same spec schema (process.clj:51), containment
launch (process.clj:957), record publication, raw-UDS readiness probe,
and reap semantics as `up`, scoped to one kind. Concurrent ensures are
safe BECAUSE the existing lock owns that; do not add a second lock.
No resident babysitter; no CLJS process ownership.

The pod TRIGGERS it: when a host invocation fails with the
retired-process/connection-refused class (the ensure-entry!/session
path, execution/host.cljs:260,668), the pod spawns ONE bounded
`bin/seon ensure host` subprocess for the cluster, under a
manifest-declarable backoff config fact (new key, register with the
resolve-time floor family per W1.7 — suggest
`:seon.config.execution/host-respawn-backoff-ms`, floor >= 1000; follow
the one config owner in config/resolve.cljc). While ensure runs,
further invocations fail fast with the existing honest error (no
queueing mechanism — the next demand retries the session).

## Contracts (each becomes a regression or drive assertion)

1. An invocation interrupted by host death settles as the existing
   retired-process error VALUE (no invocation hangs; B8 staged-response
   semantics unaffected).
2. The interrupted form is NOT auto-replayed (effect-classified replay
   ruling is binding: death mid-form = attempted-outcome-unknown; its
   receipt stays honest — verify what the current receipt terminal
   records for a dead-host invocation and report if it lies).
3. The next demand after death launches a NEW recorded generation with
   no manual operator command; `bin/seon status` shows the new pid;
   the old generation's record is reaped by the ensure reconcile.
4. Session reconnects; a PRE-KILL corpus definition evaluates on the
   fresh host (startup replay already proven — host_registry_writer_test
   :626 — reuse, don't duplicate).
5. The ensure verb is idempotent: with a live ready host it is a no-op
   (changed:false-style report), and it never touches watcher/writer/pod.
6. Backoff honored: two rapid failures produce one launch attempt
   inside the window (regression with a fake clock or injected
   now-fn — no sleeps in tests where avoidable).
7. q27 rides along IF the cause is in reach: teardown classifies
   forced/incomplete-application despite clean absence proofs — the
   same containment surfaces are being touched; fix if the cause is the
   host workload's exit classification, otherwise report evidence and
   leave the row open.
8. q24 rides along: the ensure reconcile sweeps ORPHANED containment
   sockets for the host kind only, guarded against the grounding's
   risk-5 race (a live-but-unpublished socket must survive — use the
   record-then-bind order evidence or the lock, whichever the source
   supports; report which).

## Owned paths

script/seon/dev/cli.clj, script/seon/dev/process.clj,
script/seon/dev/config.clj (verb + host-member reconcile only),
src/seon/execution/host.cljs (trigger + backoff),
src/seon/config/resolve.cljc (the one new config key),
config schema tests + test/seon/dev/* + test/seon/execution/host_test.cljs
+ writer-gate host tests as needed. PROTECTED: config/*.edn (report the
manifest line; orchestrator applies), seon.host startup semantics
beyond what the contracts above require, everything else.

## Gates

bin/test-writer, bin/test-cljs, bin/seon test operator (operator verb
changed) — full runs, honest counts, full logs captured to files. The
kill-host-mid-invocation LIVE drill is the ORCHESTRATOR'S at
acceptance (grounding falsifier 1); do not run bin/seon against the
live default cluster yourself. Commit nothing; leave the diff for
review.
