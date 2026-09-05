---
type: issue
status: open
severity: blocker
tags: [issue, runtime, class/n9, wave/run-loop-velocity]
---

# A run pays ~2.4 s BETWEEN every form, and almost nothing in the work

## Problem

Driving a system run costs roughly 2.4 seconds per form, and that cost is
not in the forms. A five-form run took 11.7 s wall and a six-form run 9.9 s
while the work inside each form cost 110–130 ms. The gap is the loop's own
per-form path: derive, submit, settle, transact, re-derive.

Every fix cycle that drives a real run pays this, and every live proof, every
curation proof, and every agent turn is a run. Under the standing rule that
anything extremely slow taxing every fix cycle is a development-velocity
incident, this is one; it is filed rather than fixed here because the owner
is the run loop, not the capability surface the tool-repairs lane held.

## Evidence

Tool-exercise lane, 2026-08-07, cluster `tools` in an isolated operator root,
measured from committed effect receipts on a loaded machine (several sibling
lanes on the same box — treat as an upper band). Report:
[tool-exercise-2026-08-08.md](../../prds/sci-execution-runtime/research/tool-exercise-2026-08-08.md).
Raw receipts:
[probes/tool-exercise/](../../prds/sci-execution-runtime/research/probes/tool-exercise/).

Successive effect `opened-at` instants within ONE five-form run:

```text
02:41:46.233
02:41:48.660   +2.43 s
02:41:51.143   +2.48 s
02:41:54.490   +3.35 s
02:41:55.848   +1.36 s
```

Per-effect door cost over the same run, largely independent of the work:

```text
my.fs/stat   on one file          131 ms
my.fs/glob   over 2 entries       120 ms
my.fs/read   refusing after 16MiB 125 ms
```

So of ~2.4 s between forms, ~0.12 s is the capability request. The finder
attributes the door's own 110–130 ms to two transactions (open + settle), a
`config/effective`, and two admissions per request; the remaining ~2.3 s per
form is unattributed and is the number worth chasing first.

### 2026-09-05 attribution and partial repair

An isolated `velocity` cluster drove one scripted six-form reply. The terminal
`my.run/complete` form hit a separate admission refusal, so the comparable
sample is the five preceding ordinary forms. Nanotime measurements around the
settlement path attributed their cost as follows (ranges are the observed five
forms on a loaded, concurrently used development machine):

| Phase | Before `4e68150f5` | After / disposition |
|---|---:|---|
| SCI evaluation | 47--60 ms | unchanged |
| cached-prelude lookup | 2--3 ms | unchanged; the cache is reached |
| `seon.fn/analyze-form` | 228--311 ms | unchanged; clj-kondo still reparses the synthesized source |
| receipt transaction's second schema-projection build | 360--550 ms | removed |
| complete settle call | 414--629 ms | live post-fix remeasurement still required |
| following render | 0.9--1.9 ms | not dominant |
| `config/effective` | 0.5--1.4 ms | not dominant |

The redundant projection was at
`src/seon/cluster/run.clj:1596`: `receipt-read-evidence-tx` rebuilt the
complete projection inside Datahike's transaction function even though the
outer database transaction codec already carries that projection and encodes
transaction-function output at `src/seon/schema/datahike.clj:467-502`.
Commit `4e68150f5` now returns ordinary transaction data to that one codec.

The recurring regression
`seon.cluster.loop-test/read-evidence-settlement-reuses-the-outer-transaction-codec`
fails if settlement re-enters `projection-from-database`; on the clean focused
gate it projected 1,024 evidence rows in 1 ms. The same gate also exposed and
repaired a stale crash-walk fixture whose planned forms omitted their required
namespace ref and identity.

Proof at current HEAD:

```text
bin/test seon.cluster.loop-test seon.cluster.turn-test
Ran 80 tests containing 500 assertions.
0 failures, 0 errors.
```

A same-drive post-fix measurement remains unavailable in the relaunch session:
the Seon MCP status/eval tools were not exposed, and the repository rules
forbid substituting a hand-rolled prepl client. The issue therefore remains
open; removing one 360--550 ms duplicate phase is material but does not prove
the under-200-ms per-form target.

## Expected

A form that does 130 ms of work does not cost 2.4 s of run. The first task
is attribution, not optimisation: instrument one run's per-form path so the
2.3 s is assigned to named phases (work derivation, submission, settlement
transaction, re-derivation, listener wake) rather than inferred from receipt
gaps.

## Owner

`seon.cluster.run` owns settlement transaction construction;
`seon.fn/analyze-form` owns the remaining measured per-form analysis floor.

## Acceptance

- One run's per-form cost is attributed to named phases with measurements,
  recorded in the owning PRD's `research/`.
- The dominant phase is fixed at its owner, and the same five-form drive is
  re-measured to show the change.
- Per-form loop overhead is below 200 ms, or the remaining irreducible floor is
  measured and written here with its dependency boundary.
