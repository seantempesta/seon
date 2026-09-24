---
type: landing
status: items 1 and 3 landed and proven on default; item 2 (errors in Seon agents' evaluation responses) stopped — needs seon.repl
lane: errors-on-their-branch-2
created: 2026-09-24
requirements: docs/research/agent-platform/language-requirements-2026-09-24.md §8.2, §8.3, §8.4, §8.6
---

# Lane errors-on-their-branch-2 (2026-09-24)

What exists: `seon.fault/record!` is the one writer of core faults. Every Flow fault reaches it
through the one per-cluster fan-out, which passes the cluster connection
(`cluster.clj:3512-3521`). The change: `record!` now decides where it writes. It writes once on
the branch where the fault happened, and once more on the cluster branch, naming the origin.
Each write runs under that target's own custody. So the fan-out needs no change.

## Broken or unproven, first

- **Item 2 is not landed. It needs a file I do not own: `src/seon/repl.clj`.**
  - `receipt-entry` (`transcript.clj:254-294`) takes only the receipt. It has neither the
    database nor the previous evaluation's basis.
  - The response grammar is `seon.repl/response-entries` (`repl.clj:168-211`). It has a
    closed key list (`response-order`), and `text` is its single generator. Appending text in
    `transcript.clj` would be a second grammar.
  - Exact change (~25 lines across three files):
    1. `transcript.clj` `history` (`:338-365`) and the in-memory path of `candidate-history`
       (`:600-653`) put `::branch-errors` on each eval entry. The value is
       `(error/occurred-since (db/as-of db this) previous)`.
       - `previous` is the greatest of the agent's own
         `:seon.cluster.eval/read-basis-transaction` values below `this`. One query per render
         collects them: turn → agent, eval → turn.
    2. `emission` (`:419-454`) passes it on as `:seon.repl/errors`.
    3. `repl.clj` `response-entries` renders `:seon.repl/errors` when the vector is non-empty.
       It also needs a `response-order` slot and the optional key on
       `resources/seon/schemas/seon.repl.edn` `:emission`.
  - **Window question for root.** The ruled window, (previous read basis, this read basis],
    shows the errors that the *previous* evaluation caused in *this* response. A read basis is
    taken before the evaluation runs.
    - A smaller alternative that is also exact: (previous evaluation's settlement tx, this
      evaluation's settlement tx]. The settlement tx is `entry-basis`, the entity's greatest
      datom tx (`transcript.clj:745-750`).
    - That window shows each evaluation's own errors in its own response. It also covers the
      gap between two evaluations. Recommended.
- **Default proof: done.** Default was restarted from the checkout at about 16:55Z with hook
  publication on, and it loads both commits: `seon.db/branch-connection`, `seon.cluster.branch`,
  and the new `record!`.
  - `bin/test-check default --test seon.fault-test/a-core-fault-is-recorded-on-its-branch-and-copied-to-the-cluster-branch`:
    run `9f18f783fc98`, 5 pass / 0 fail, 4,616 ms.
  - `--test seon.cluster.boot-test/an-uncaught-throwable-without-its-world-is-printed-and-never-stored-elsewhere`:
    run `54446b824cb0`, 2 pass / 0 fail, 2,127 ms.
  - `--ns seon.fault-test`: run `437d5cf86493`, 32 pass / 1 fail / 1 error. Neither red is from
    this change:
    - The fail is the pre-existing 1000 ms assertion below, at 1,140 ms here.
    - The error is `an-unknown-write-outcome-…`: "Worker-global state changed … drift-added
      seon.turn/preview-sources". That is another lane's `turn.clj` adoption arming a Var
      during the run.
  - The default roster holds no `fault-branches-*` or `eotb2-*` branch afterwards.
  - On root's instruction, the snapshot JVM (pid 59241) was stopped with `bin/seon down` and
    its root deleted, after its symlinks were unlinked first.
- **`registry/cluster-branch` is a retiring alias.** It is `(def cluster-branch
  #'seon.cluster.branch/cluster-branch)`, so calls go through the armed leaf Var.
  - Its callers live in files other owners hold: `cluster.clj`, `boot.clj`, `agent.clj`,
    `eval/drive.clj`, `my/agent.clj`, and about 12 test files.
  - Retirement is one scripted sweep, `registry/cluster-branch` → `cluster.branch/cluster-branch`,
    followed by deleting the alias. It needs those files' owners.
- **Pre-existing, not from this change: `seon.fault-test/record-mode-…` exceeds its own
  1000 ms assertion.** Snapshot runs measured 1964, 2215, 2306 and 2912 ms.
  - The test's cluster "fault-record" is not open in the process, so its path is a single write.
  - Same-branch probe, HEAD versus new `record!`: see numbers. No regression.
  - The time is spent in fixture-branch `db/transact!`. The previous lane measured 2,369 ms for
    one branch write on live default. The owner is `db.clj` / the Datahike writer.
- **A fault with an unreadable agent-branch read records on the connection's branch.**
  - Where: `(if (keyword? agent-branch) agent-branch here)`.
  - This keeps the fault. The read failure itself is not surfaced. It is a deliberate choice,
    named here.
- **Origin branch not open in the process** (for example, retired before the fault was
  recorded):
  - the cluster copy is still written;
  - `outcome` is a flat error naming the branch, so the policy panics;
  - `:seon.fault/recording` types its outcome as `:map`, which admits this; no named schema
    was added.
- **No copy when the process holds no cluster branch** for the name (fixture cluster names). A
  running cluster always holds its branch open.
- **Foreign, seen live.** The snapshot's default recorded a recurring
  `seon.error/prepare-result refused … :seon.render.profile/id` from `sci/eval.clj:3037`,
  count 4 and rising, with no agent action. That is HEAD code, not this change.

## What landed

| commit | change | source lines |
|---|---|---|
| `9ce7e0992` | `record!` has one writer and two targets. The origin is the tagged agent's `:seon.agent/branch`, else the connection's own branch. Each write runs under `db/call-with-custody` for its target. The cluster copy carries `:seon.error.occurrence/branch origin`. `seon.db/branch-connection database branch` borrows the process's open connection from Datahike's registry, keyed from the value's own config (`datahike/store.cljc:44-55`, `connections.cljc:5-9`). New leaf `seon.cluster.branch` holds `cluster-branch`, which breaks the fault → registry → store → fault cycle. Regression: `seon.fault-test/a-core-fault-is-recorded-on-its-branch-and-copied-to-the-cluster-branch`. | +80/−16 (fault +48/−9, db +12, leaf +14, registry +6/−7) |
| `f1c876882` | `record-uncaught!` uses `(carried-environment throwable)` only. An uncarried throwable takes the existing `uncaught-emergency!` print, with every cause. Its docstring is corrected. Regression: `seon.cluster.boot-test/an-uncaught-throwable-without-its-world-is-printed-and-never-stored-elsewhere`. | +5/−7 |

Tests: +65 lines. Net source: +62.

**Committer deletion is now mechanical.** The later lane that deletes the per-cluster fan-out
committer (`cluster.clj`) can hand any connection to `seon.fault/record!` or `fault!`. Where
the fault is written is decided inside `record!` from the tagged agent, not from the
connection it was given.

## Evidence

All snapshot evidence comes from a `git archive` of HEAD `056cdbe0e` plus this diff, with
`reference-code` and `.cpcache` linked. It booted ready in 122 s. It was deleted on root's
order.

- **Regression, green.** `--gate` run `03a239530f09`: 5 pass, 0 fail.
  - The fault from the cluster connection, tagged with agent `worker` (whose branch is the
    fixture branch), shows `[#{origin}]` in the origin branch's window.
  - The same fault shows `[#{origin}]` in the cluster branch's window.
  - After the fixture releases and unlinks the origin, the origin is absent from the roster,
    and the cluster copy is still `[#{origin}]`.
- **`--ns seon.fault-test`.** Run `49f5188fdf08`: 32 pass, 1 fail. The fail is the
  pre-existing 1000 ms assertion above.
- **Boot regression.** Run `35dcb0f3a91e`: 2 pass. The printed text carries "SEON CORE FAULT
  (uncaught, not stored)" and the throwable's message.
- **Live copy seen through the MCP window.** After the branch probe, the snapshot bridge's
  cluster window showed `{:seon.error.occurrence/branch ["eotb2-cost"] … "branch cost probe 4"}`.
- **Before/after cost.** Probe: `tmp/errors-on-their-branch-2/cost-probe.clj`. HEAD's
  `record!` is loaded as the scratch namespace `errors-on-their-branch-2.fault-head`.

  | case | HEAD `record!` (ms) | new `record!` (ms) |
  |---|---|---|
  | same branch, cluster connection | 133, 110, 159, 161 | 138, 123, 134, 123 |
  | isolated branch; new also writes the cluster copy, batch 1 | 858, 229, 125, 182 | 378, 804, 1737, 2123 |
  | isolated branch; new also writes the cluster copy, batch 2 | 904, 472, 387, 236, 219 | 375, 429, 708, 421, 353 |

  - Same branch: no regression.
  - Batch 1: the first copy delivered the opening wake to root. Root's turn then ran
    concurrently on the cluster writer, so the later copies waited behind it.
  - Batch 2: no new opening; medians are 387 (HEAD) and 421 (new).
  - The second write is Datahike-writer-bound.
  - Allocation not measured.

## Released paths

- `src/seon/fault.clj`
- `src/seon/db.clj`
- `src/seon/cluster/registry.clj`
- `src/seon/cluster/branch.clj`
- `src/seon/cluster/boot.clj`
- `test/seon/fault_test.clj`
- `test/seon/cluster/boot_test.clj`
- `src/seon/render/transcript.clj`: untouched
- `src/seon/cluster/store.clj`: untouched, since no require change was needed
- this note
