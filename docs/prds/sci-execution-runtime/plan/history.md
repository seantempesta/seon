---
type: prd
status: active
tags: [prd, agent, architecture]
---

# What was tried before

Owner instruction, 2026-07-26: keep the historical record available for
reference, with commits, so an agent researching a problem can look up what was
already attempted.

**Git is the archive.** Nothing below is a live document — every row is a commit
you can read. Use `git show <hash>` or `git show <hash>^:<path>` to read a file
as it was before deletion. This file exists so you know *what to ask for*.

The rule that makes this safe: **read the old implementation for inspiration,
never restore it wholesale.** A function that runs on the new tier while keeping
its old-model shape is a ported defect, not a conversion.

## 1. The big deletions — what is gone and how to read it

| what | lines | commit | read it |
|---|---:|---|---|
| The old guarded door: `src/seon/host.clj` + all of `src/seon/host/` | 5,715 src + ~7,000 test | `8dc8623ad` | `git show 8dc8623ad^:src/seon/host/context.clj` |
| The documentation archive: 83 files including two competing plans | 25,632 | `24053c64e` | `git show 24053c64e --stat` |
| `seon.eval` whole, taking `result_var_test.cljs` | 5,362 | `fbc6b28b5` | `git show fbc6b28b5 --stat` |
| The pod turn phase stack | — | `f6f6673b6` | `git show f6f6673b6 --stat` |
| Placement derivation, the pod agent loop, `src-flow-prototype/` (the O12 cut) | — | `574ac70ed` | `git show 574ac70ed --stat` |
| The Integrant era: `system.clj`, `system/config.clj`, `db/datahike/system.clj`, `resources/integrant/hierarchy.edn` | 274 + 83 + 82 + 20 | `6c1079c8d` | `git show 6c1079c8d^:src/seon/system.clj` |
| An orphaned Integrant-era nREPL entry point | — | `314e3cafd` | |
| The sandbox dial in `bin/codex-agent` | — | `42a9faf2e` | |

**The richest one to read is `8dc8623ad^:src/seon/host/context.clj`** (2,184
lines). It is where every capability family was actually installed, so it is the
reference for the door in step 1 — including the two hand-maintained toolkit
lists that must *not* come back.

## 2. Things that were tried and rejected, with the reason

Read these before re-proposing them. Each cost real time.

| tried | outcome |
|---|---|
| A step/interpreter budget ("fuel") as a limit | SCI has **zero counters** — the interpreter has no step concept. The name asserted a mechanism the dependency does not have, which is why the default sat at 100,000,000 and was never calibrated. `time-limit` is the only limit. |
| An allocated-bytes budget as a limit | The metric is *cumulative* allocation, anti-correlated with live footprint: it killed a harmless `(reduce + (range 500000))` at 20 ms and **missed** a retained 1 GB that OOM'd the JVM. |
| A turn-level transform `(db, agent, message) -> [tx-data, messages, effects]` | Ported from `core.async.flow` and falsified by measurement: the identical form answered **0** against the turn's opening basis and **9** against the step's, so read-your-own-writes is inexpressible at turn granularity. |
| Tuples, then component refs, to store ordered collections | Homogeneous tuples **throw** above 8 values; component refs pull back in ascending entity ID, not insertion order. The cause was wrong *declarations*, not a wrong bridge (`5a37489c6`). |
| AOT for the writer uber-jar | Removed once at `be30f420` because two clean builds from identical inputs produced different digests. Reintroduced later; the determinism re-check is still owed. |
| A JVM SCI JIT | No substrate: on `:clj`, `->Node` is a bare `reify` that discards its ast and `attach-ast` is identity. And it would optimize 0.15% of a turn. |
| Re-enabling native accretion (`graduate!`) | Compiling agent code to a native JVM fn **deletes the `:interrupt-fn`**. Refused deliberately; the refusal was correct. |
| `trust-gate?` as the accretion gate | Zero production callers — the live gate nobody had named was `my.plan.internal/green-tested?`. |
| Keeping the old path beside the new one during conversion | Owner ruling O12: *"keeping the old one and the new one at the same time is how the design gets broken."* Every hour both exist is an hour someone reconciles the new design against the old shape. |
| A read-only sandbox for audit lanes | The audit finished a 63-file inventory then had its own `apply_patch` rejected, losing all its per-file evidence. A sandbox does not make an audit safer; it makes its output unrecordable (`42a9faf2e`). |

## 3. The session that produced this plan — 2026-07-26

In order. The audit trail the owner asked to keep.

| commit | what |
|---|---|
| `dc62c30b5` | Inventoried every abandoned path before cutting anything |
| `63099a523` | Recorded owner rulings O13/O14 in the plan authority |
| `2953a3b2f` | Vendored http-kit as a submodule — the only loose dir of 99 |
| `8dc8623ad` | **Deleted the 5,715-line guarded door.** 12,110 deletions; `host.clj` 424 → 58 lines |
| `ef1f815a5` | Filed the two seams that deletion created |
| `7d435fbb2` | Corrected my own ordinal/index evidence after a lane refused to act on it |
| `71f3cb0e0` | Stopped duplicate JVM run admission |
| `ee000a4e7` | Reconciled stored-ordering vocabulary to one spelling |
| `ce5e061f2` | Closed the SCI catch-class containment gap |
| `1832764de`, `3946b7192` | Closed the wake-path loop; lease readiness published from the claim |
| `c060a20ca` | Recorded O15/O16/O17 — compile-time indexing, no runtime derive, one ledger |
| `40ea7e29c` | Filed the lazy-value containment escape |
| `b6ba7ca67` | The per-file pod cut verdict |
| `6019d0862`, `2372d9efa` | Indexed every deleted capability; indexed dangling code |
| `b1c70a141` | Collapsed seven orderings into one |
| `24053c64e` | **Deleted the documentation archive.** 44,781 → ~18,500 lines |
| `42a9faf2e` | Deleted the sandbox dial from codex lanes |
| `f07c0e3aa`, `434423628` | The capability-ordered plan on seven base constructs |
| `d29138d1d`, `3564882a3` | Workload-tagged scheduling; then revised so channels return as bounded submission queues |
| `6b5f37bf2` | Filed two driver blockers found by verifying a lane's claim |
| `05451d5c5` | Collapsed the **eighth** ordering; created `plan/` |
| `bd8038419` | Decided Integrant boot ownership |

## 4. Mistakes this session made, recorded so they are not repeated

Not decoration — each is a live failure mode.

- **I created an eighth ordering** two hours after diagnosing seven orderings as
  the reason "follow the plan" had no referent (`05451d5c5` fixes it). Producing
  a document is not the same as reducing confusion.
- **I gave a design lane a candidate list the architecture had already
  excluded.** `ui.md:23-27` already specified the render design; I had to stop
  and re-aim the lane. Read the authority before designing.
- **I supplied a lane three wrong grep counts** for a naming decision. It
  refused to act and re-grepped, which was correct behaviour and is why the
  decision survived.
- **I sandboxed an audit lane**, which then could not write its own report.
- **I wrote a plan row from a one-day-old document.** Four of its six defects
  were already fixed at HEAD.

The pattern: **verify against the tree, not against a document — including a
document written yesterday by me.** That is what `bin/plan-state` is for.

## 5. Earlier sessions

- `b418cd897` — the previous session's own accounting of what it did correctly
  and what it did not. Worth reading: it names the sequencing failure (working
  from "what is on fire" rather than the plan) that this session's structure is
  designed to prevent.
- The full research corpus for 2026-07-20 → 2026-07-25 is in `24053c64e^` —
  ~100 dated audits. Recover any one with
  `git show 24053c64e^:docs/prds/sci-execution-runtime/research/<name>.md`.
  Notable: `wtf-review-2026-07-24.md` (a fresh-eyes trace of one turn),
  `flow-prototype-2026-07-25.md` (the D1–D16 adversarial measurements),
  `boot-time-design-2026-07-23.md` (the 271 s reset breakdown).
