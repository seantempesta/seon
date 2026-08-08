---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, testing]
---

# `next-agent-work` requires a `now` it never reads, and `curate/prove!` omits it

## Problem

Two defects, one declaration.

**One — a required key nothing uses.** `:seon.cluster.work/agent-request`
(`resources/seon/schemas/seon.cluster.work.edn:1-6`) requires three keys:

```clojure
[:seon.cluster.agent/id :seon.cluster.agent/id]
[:seon.cluster.run/process :seon.cluster.run/process]
[:seon.cluster.work/now :inst]
```

`seon.cluster.work/next-agent-work` destructures only the first two
(`src/seon/cluster/work.clj:598`), and `:seon.cluster.work/now` appears
NOWHERE in `src/seon/cluster/work.clj`. The function is documented as pure
and derives everything from committed facts, so there is nothing for a clock
to do. A required argument that no code reads is the inverse of the standing
rule that an argument which does not exist cannot be passed wrongly: it can
only ever be forgotten, and forgetting it is fatal.

**Two — a live caller forgets it.** `seon.cluster.curate/execute-revision!`
(`src/seon/cluster/curate.clj:131-141`) calls it with exactly two keys:

```clojure
(work/next-agent-work
 @connection
 {:seon.cluster.agent/id agent-id
  :seon.cluster.run/process process})
```

Under instrumentation that throws:

```text
seon.cluster.work/next-agent-work violated its contract (invalid-input):
[nil #:seon.cluster.work{:now [{:value nil, :message "missing required key"}]}]
```

`prove!` wraps its body in `catch Throwable`, so the session-curation proof
does not surface this as "you forgot a key" — it reports
`:seon.cluster.curate/proof-fault` with a stack frame. Every proof
therefore fails as a fault rather than proving or disproving a revision.

## Evidence

Tool-exercise lane, 2026-08-07. Reproduced by driving a system run against a
live cluster with the same two-key request curate uses; the contract report
above is the verbatim value. `seon.cluster.curate` is the only first-party
caller with this shape — `seon.cluster.agent`, `seon.cluster.loop`, and
`seon.eval.drive` all pass `now`, which is why the gate has not caught it.

## Expected

The key stops being required, because nothing consumes it: remove
`:seon.cluster.work/now` from `:seon.cluster.work/agent-request` so the
request expresses exactly its real dependencies. (Under the accretion rule
removing a REQUIRED input key is accretion — it requires no more — and every
existing caller that still passes it is ignored as extra data.) If some
future arm genuinely needs a clock, it is a new key added then, with a
reader.

`curate/prove!` then works with the call it already makes. Whichever
direction is chosen, the two must agree.

## Acceptance

- `seon.cluster.curate/prove!` executes a revision end to end on a fresh
  proof branch without a `::proof-fault`, proven by a test that drives the
  real `prove!` (not `execute-revision!` in isolation).
- No required key in `resources/seon/schemas/seon.cluster.work.edn` is
  unread by `src/seon/cluster/work.clj`.

## Resolution (tool-repairs lane, 2026-08-08, `e9a49f8f4`)

`:seon.cluster.work/now` is gone from `:seon.cluster.work/agent-request`. The
derivation is pure over committed facts and reads no clock, so the request now
expresses exactly its real dependencies and there is no unread required key
left to forget. Removing a required input is accretion — it requires no more —
so the three callers that still passed a clock were simply accreted; they have
been updated to pass what the derivation actually reads.

Recurring regression:
`the-request-declares-exactly-the-dependencies-the-derivation-reads` in
`test/seon/cluster/work_test.clj` — the two-key request validates, a request
still carrying a clock validates, and dropping either real key does not. An
unread required key cannot come back without failing there.

Rotation note, recorded because it changes what this note claimed:
`seon.cluster.curate-test` — which drives the real `prove!` end to end —
passes at HEAD both BEFORE and AFTER the change. The gate does not instrument
this call, so the contract violation the finder saw is reachable in a live
instrumented cluster but not from the suite. The acceptance item "prove!
executes end to end without a ::proof-fault" is therefore satisfied by an
existing recurring test that was already green; the declaration mismatch is
what this commit makes unrepresentable.