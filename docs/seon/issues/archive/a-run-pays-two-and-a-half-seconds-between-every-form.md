---
type: issue
status: resolved
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

Per-effect execution cost over the same run, largely independent of the work:

```text
my.fs/stat   on one file          131 ms
my.fs/glob   over 2 entries       120 ms
my.fs/read   refusing after 16MiB 125 ms
```

So of ~2.4 s between forms, ~0.12 s is the capability request. The finder
attributes the effect handler's own 110–130 ms to two transactions (open + settle), a
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

### 2026-09-05 turn-batch resolution

The run loop now freezes the complete reply in one intent transaction,
evaluates its ordered forms in one SCI fork, analyzes only generation-stamped
definitions in one kondo batch, and writes one settlement transaction. Ordinary
forms no longer invoke kondo, transact, install, or render independently.

The regression
`seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent`
drove one six-form reply containing a repaired `defn` plus five ordinary forms.
Its clock starts when the completed reply enters the reader and stops when the
turn returns; the assertion subtracts nanoseconds spent inside SCI evaluation,
leaving read/repair, both durable transactions, the one definition-analysis
batch, program installation, and the single post-settlement render.

| Six-form reply | Before | After |
|---|---:|---:|
| Wall/bookkeeping shape | ~9.9 s wall | 196.353334 ms bookkeeping |
| Durable transactions | 2 per form | 2 per turn |
| Kondo analysis | every form, 228--311 ms each | defining forms only, one batch |
| Derive/render | after every form | once after settlement |

The after sample ran in a fresh test JVM after canonical fixture construction;
it did not pre-warm `seon.fn/analyze-forms`. The definition batch resolves only
the call-target symbols kondo actually reports instead of pulling the complete
function population before analysis.

## Resolution

A form that does 130 ms of work no longer pays a 2.4-second per-form loop. The
algorithmic unit is the turn: two transactions, one definition-only analysis
batch, and one derive/render pass.

## Owner

`seon.cluster.run` owns settlement transaction construction;
`seon.fn/analyze-forms` owns the one per-turn definition analysis batch.

## Acceptance

- The original per-form phases are attributed above.
- The dominant repeated phases are removed at their owners.
- A six-form reply's complete non-evaluation bookkeeping is measured below
  300 ms and enforced by the turn regression.

### Left behind by the batched turn (orchestrator, 2026-09-05 night)

- `seon.cluster.work/form-settlement` still derives the retired E3 routing
  states (`:routed`, `:unrouted-red`, `:owner-fixed`, assignment and
  declination messages) that the batched loop no longer produces: a red form
  is a flat error on its own eval and the turn settles. Per the owner
  (2026-09-05) automatic routing of problems to a namespace owner is a LATER
  capability, so this derivation is dead code awaiting deletion with its
  schema enum; `turn-test/a-red-form-routes-to-its-namespace-owner-and-the-fold-continues`
  now asserts the ruled behavior directly from eval facts.
- The lane's brief asked for the reader recovery corpus; the evaluator also
  had to refuse a recovered error event instead of running its placeholder
  form (`seon.sci.eval/one-event`), found by
  `an-unreadable-reply-is-a-settled-form-with-paid-attempt-evidence`.
