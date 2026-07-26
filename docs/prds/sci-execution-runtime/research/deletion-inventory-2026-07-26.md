---
type: research
status: active
tags: [research, runtime]
---

# Deletion inventory, 2026-07-26 — where every abandoned path is

Owner instruction, 2026-07-26: *"DO NOT MAINTAIN MULTIPLE CODE PATHS. This is
NOT live production code. This is a mess of different ideas and I'm trying to
clean it up and I'm having to fight just to get you to delete old code that we
are intentionally abandoning. Start by deleting it and commit it and if you
ever need to reference the implementation you'll look in git history."*

This file is the answer to "show me you know where it all is". Every line below
was grepped on 2026-07-26 against the tree at `b418cd897`. No lane summaries.

## 0. The live new path — 4 files

`seon.agent.driver` (`src/seon/agent/driver.clj`, 667) →
`seon.sci.eval` (`src/seon/sci/eval.clj`) →
`seon.sci.ctx` (`src/seon/sci/ctx.clj`, 36) + `seon.sci.interrupt`.

`seon.sci.ctx/base` builds its own SCI base: `clojure.core`, `clojure.string`,
five `seon.agent.lifecycle` vars, four classes. **It requires nothing under
`seon.host.`** — verified by reading the whole file. The new path already drove
real agent turns (measurements §17, §18) without the old tree.

The driver's only two calls into the old tree are
`seon.host.context/database-functions` (`context.clj:415-418`) and
`seon.host.context/allocate!` (`:420-424`) — four-line wrappers over `seon.db`
and `seon.db.id`, reached from `src/seon/host.clj:340-343`.

## 1. BLOCK 1 — the old guarded door. 5,715 src lines, zero production callers.

| file | lines | what it is |
|---|---:|---|
| `src/seon/host.clj` | 424 | UDS protocol server. **The seam: it also boots the new driver at `:340`.** |
| `src/seon/host/context.clj` | 2,184 | old base ctx, per-agent `ensure-context!` (never evicted, R-8a), `pure-block?` source regex, toolkit hand lists |
| `src/seon/host/eval.clj` | 566 | old eval entry, output cap at `:216-217` |
| `src/seon/host/record.clj` | 483 | **holds the fn/ns/schema tee** — do-not-delete, needs a new owner |
| `src/seon/host/preflight.clj` | 344 | reply repair/splice |
| `src/seon/host/invoke.clj` | 284 | watchdog `Thread.interrupt`; `cancel-active!` waits 2 s then walks away (`:280-283`) |
| `src/seon/host/graduate.clj` | 276 | `install-nursery!`/`rebuild!` live (do-not-delete); `trust-gate?` dead (zero production callers) |
| `src/seon/host/guard.cljc` | 260 | **the second `:interrupt-fn`**, superseded by `seon.sci.interrupt` |
| `src/seon/host/instrument.clj` | 242 | the global fair `ReentrantReadWriteLock` (R-8a) |
| `src/seon/host/session/leaf.cljs` | 261 | pod side of the second IPC path |
| `src/seon/host/session/leaf.clj` | 192 | JVM side of the second IPC path |
| `src/seon/host/sample.clj` | 116 | |
| `src/seon/host/session.cljc` | 83 | frame/session protocol |

**Every reference to `seon.host` from outside `src/seon/host*`:**

- `src/seon/web/datastar.cljs:32-33` requires `seon.host.session` and
  `seon.host.session.leaf`; the single call is
  `host.session.leaf/invoke-authored!` at `:1111-1115` (pod authored render).
  This is BLOCK 2.
- `src/seon/capability.cljc:45` reads the keyword `:seon.host.context/effect` —
  a keyword, not a require.
- `src/seon/web/serve.cljs:938` writes the keyword
  `:seon.host.session/processes`.
- `src/seon/agent.cljs:186,190` — two comments.

Nothing else. There is no production `:require` of `seon.host.*` on the JVM
outside the block itself.

**Tests that pin BLOCK 1** (deleted in the same commit; a test that pins a
deleted path is deleted with it):
`test/seon/host_cancel_writer_test.clj` 399,
`test/seon/host_conformance_writer_test.clj` 1,054,
`test/seon/host_eval_wire_safety_writer_test.clj` 90,
`test/seon/host_graduate_writer_test.clj` 300,
`test/seon/host_guard_policy_writer_test.clj` 26,
`test/seon/host_hardening_writer_test.clj` 69,
`test/seon/host_hostile_battery_writer_test.clj` 825,
`test/seon/host_instrument_writer_test.clj` 452,
`test/seon/host_interrupt_writer_test.clj` 275,
`test/seon/host_preflight_writer_test.clj` 235,
`test/seon/host_projection_writer_test.clj` 361,
`test/seon/host_registry_writer_test.clj` 771,
`test/seon/host_shared_var_writer_test.clj` 214,
`test/seon/host_surface_writer_test.clj` 394,
`test/seon/host_toolkit_writer_test.clj` 63,
`test/seon/host/guard_config_test.cljs` 44,
`test/seon/host/guard_context_test.clj` 158,
`test/seon/host/guard_test.cljc` 160,
`test/seon/host/toolkit_bindings_test.clj` 45.
Also `bench/u1_guard_calibration.clj` 65 (requires `seon.host.guard`).

`test/seon/web/datastar_test.cljs` 519 and `test/seon/web/serve_test.cljs`
1,323 pin the pod, not the door — they ride BLOCK 2.

**The two genuine implementation dependencies**, named per CUT FIRST /
SEAM-FIX SECOND rather than used as a reason to keep the block:

1. **the fn/ns/schema tee** (`record.clj/tee-tx-data`) — do-not-delete. It is
   not ported: under the three sanctioned shapes it is *a durable FACT the
   driver commits*, so it becomes tx-data in the step's terminal transaction
   next to the receipt.
2. **corpus install at boot** (`graduate.clj/install-nursery!`, `rebuild!`) —
   do-not-delete. `rebuild!`'s corpus query selects on the *presence* of
   `:seon.fn/execution-tier` (`graduate.clj:90-100`), so its replacement is
   presence-plus-provenance (`[?fn :seon.fn/source]` minus the boot-process
   join `seon.db.program` already uses, `program.clj:40-45`) and must land in
   the same change.

## 2. BLOCK 2 — the pod. 64 `.cljs` files, 31,264 lines, still supervised.

The **agent loop is already gone**: `src/seon/agent/loop/` and
`src/seon/agent/turn/` are empty directories; `src/seon/agent/run/core.cljc` is
the only survivor there and is on the do-not-delete list.

Largest survivors: `client.cljs` 2,876 · `web/serve.cljs` 2,020 ·
`agent.cljs` 1,347 · `web/datastar.cljs` 1,268 · `ai/typeahead.cljs` 1,248 ·
`config.cljs` 1,185 · `db/transport/uds.cljs` 999 ·
`runtime/admission.cljs` 949 · `ai.cljs` 851 · `agent/fs.cljs` 849 ·
`test/runner.cljs` 823 · `agent/web/pod.cljs` 813 ·
`ai/generate_code.cljs` 771 · `db/session.cljs` 770 ·
`agent/ctx/driver.cljs` 605 · `runtime/state.cljs` 600 ·
`runtime/recovery.cljs` 533.

Five processes are supervised (`script/seon/dev/process.clj:33`):
`watcher`, `writer`, **`host`**, **`pod`**, `web-render`. The target is one
cluster JVM plus web-render.

Note against R-8b's "~3,650 wire lines SURVIVE for web-render": web-render is a
**JVM** process served by `src/seon/web/{server,feed,data}.clj`. What the pod
uniquely still serves has to be established before the pod cut, not assumed.

## 3. BLOCK 3 — the dual wire predicates

`seon.db.protocol/ordinary-wire-value?` (`src/seon/db/protocol.cljc:146`) and
`seon.agent.interaction/persisted-value?` (`src/seon/agent/interaction.cljc:50`).
Two predicates that disagree on 6 of 9 tested shapes (R-12).

## 4. BLOCK 4 — not a deletion: the fourth ordering mechanism

`:seon.eval/ordinal` + `:seon.eval/total` shipped in the O12 cut against the
plan's explicit "reconcile the name" instruction.

**Measured meanings in `src/`** (attribute keywords only):

| meaning | registered attributes |
|---|---|
| stored ordinal | `:seon.eval/ordinal`, `:seon.agent.run.form/ordinal`, `:seon.agent.turn.timing/ordinal`, and the outlier `:seon.error.frame/ordinal` |
| index selection/facet | `:db/index`, `::db/index`, `:seon.db/index`, `:datahike/index`, `:seon.db.protocol/index`, `seon.db/::index` |
| Proximum secondary index | `:seon.embed/index` |

All three eval/run/timing ordinal attributes are new in this program, while the
error-frame attribute predated them, so seniority alone argued for `index`.
Non-collision decides the other way: Datahike and Proximum use `index` for an
actual index, and a position is not an index. The reconciliation therefore
keeps `ordinal` and renames the error-frame attribute.

## 5. What is NOT old code (do not cut)

- `src/seon/agent/run/core.cljc` — claim/epoch/lease/steal CAS.
- `src/seon/eval/receipt.cljc` — receipt-before-run.
- `src/seon/agent/message.cljc` — the built channel layer.
- `src/seon/web/{server,feed,data}.clj` + `src/seon/reactive.cljc` — the UI
  chain facts → derivation → SSE morph.
- `src/seon/db/**` on the JVM — the writer.
