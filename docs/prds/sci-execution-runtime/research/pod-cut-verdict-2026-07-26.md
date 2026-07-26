---
type: research
status: active
tags: [research, runtime]
---

# Pod cut verdict, 2026-07-26 — a verdict for every remaining `.cljs`

Owner ruling O13, 2026-07-26: the pod dies **unconditionally**. Every remaining
`.cljs` is deleted and `:seon.dev.process/pod` leaves the supervised set
(`script/seon/dev/process.clj:33`), taking five supervised processes to three —
watcher, cluster JVM, web-render. Bun returns **only** as a disposable
on-demand leaf runtime for the packages work, which the owner scheduled LAST.

## 0. Provenance of this document — read this first

The audit was performed by lane `pod-verdict` under a **read-only** sandbox,
which then refused its own `apply_patch`, so the lane's per-file evidence
sentences were lost. This document was reconstructed by the orchestrator from
the lane's summary plus independent re-verification. Provenance is marked
throughout:

- **verified** — the orchestrator re-derived the number in this tree today.
- **lane** — the lane's audit conclusion, evidence sentence lost, not re-derived.

Anything marked *lane* is a claim to check before acting, not a settled fact.
The per-file evidence column is the one thing a write-enabled rerun would add.

## 1. Inventory — verified

| | count | lines |
|---|---:|---:|
| all `.cljs` under `src/` | **63** | **30,957** |
| DELETE-NOW | **48** | **24,037** |
| DELETE-WITH-RENDER | **15** | **6,920** |
| PORT-TO-CLJC | 0 | 0 |
| SURVIVES-AS-IS | 0 | 0 |

**The plan's 64 files / 31,264 lines is stale.** The 307-line delta is exactly
`host/session/leaf.cljs` (261) plus 44 removed from `web/datastar.cljs` and 2
from `web/serve.cljs` by `8dc8623ad` (*lane*, arithmetic checks out).

**PORT-TO-CLJC is zero, and that is the headline.** No surviving mechanism needs
any of this behaviour relocated. O14 requires the JVM render path to be designed
fresh rather than ported, so even the render-held set is a deletion once its
replacement exists — not a migration.

## 2. DELETE-WITH-RENDER — 15 files, 6,920 lines

Held only because they serve the UI the pod currently owns. They go when the
JVM render path lands, and they are **replaced by fresh design, not ported**
(O14).

| file | lines |
|---|---:|
| `src/seon/web/serve.cljs` | 2,018 |
| `src/seon/web/datastar.cljs` | 1,224 |
| `src/seon/agent/ctx/driver.cljs` | 605 |
| `src/seon/agent/debug.cljs` | 529 |
| `src/seon/derive.cljs` | 505 |
| `src/seon/web/router.cljs` | 486 |
| `src/seon/web/debug.cljs` | 304 |
| `src/seon/web/reactive/call.cljs` | 301 |
| `src/seon/web/reactive/transform.cljs` | 267 |
| `src/seon/web/brand.cljs` | 238 |
| `src/seon/render/system.cljs` | 130 |
| `src/seon/route.cljs` | 115 |
| `src/seon/ui/agent_view.cljs` | 93 |
| `src/seon/web/value.cljs` | 58 |
| `src/seon/ui/header.cljs` | 47 |

## 3. DELETE-NOW — 48 files, 24,037 lines

Nothing on a surviving path needs these. They can go before render lands.

| file | lines |
|---|---:|
| `src/seon/client.cljs` | 2,876 |
| `src/seon/agent.cljs` | 1,347 |
| `src/seon/ai/typeahead.cljs` | 1,248 |
| `src/seon/config.cljs` | 1,185 |
| `src/seon/db/transport/uds.cljs` | 999 |
| `src/seon/runtime/admission.cljs` | 949 |
| `src/seon/ai.cljs` | 851 |
| `src/seon/agent/fs.cljs` | 849 |
| `src/seon/test/runner.cljs` | 823 |
| `src/seon/agent/web/pod.cljs` | 813 |
| `src/seon/ai/generate_code.cljs` | 771 |
| `src/seon/db/session.cljs` | 770 |
| `src/seon/diffusion/worker/eval.cljs` | 762 |
| `src/seon/diffusion/gemma.cljs` | 707 |
| `src/seon/diffusion/retrieval.cljs` | 678 |
| `src/seon/runtime/state.cljs` | 600 |
| `src/seon/ai/openai_compat.cljs` | 555 |
| `src/seon/agent/ctx/typeahead_steps.cljs` | 540 |
| `src/seon/runtime/recovery.cljs` | 533 |
| `src/seon/agent/search/internal.cljs` | 530 |
| `src/seon/agent/shell.cljs` | 510 |
| `src/my/blob/leaf.cljs` | 500 |
| `src/seon/log.cljs` | 459 |
| `src/seon/agent/schedule.cljs` | 430 |
| `src/seon/ai/anthropic.cljs` | 373 |
| `src/seon/agent/search.cljs` | 366 |
| `src/seon/agent/fs/internal.cljs` | 314 |
| `src/seon/agent/shell/internal.cljs` | 302 |
| `src/seon/subprocess.cljs` | 269 |
| `src/seon/diffusion/oracle.cljs` | 233 |
| `src/my/ns.cljs` | 232 |
| `src/seon/agent/testrun.cljs` | 214 |
| `src/seon/embed.cljs` | 208 |
| `src/seon/diffusion/worker/parse.cljs` | 196 |
| `src/seon/diffusion/scaffold.cljs` | 180 |
| `src/my/kb/shared.cljs` | 131 |
| `src/seon/agent/ctx/admin.cljs` | 129 |
| `src/my/data.cljs` | 129 |
| `src/seon/ai/dispatch.cljs` | 113 |
| `src/seon/db/fiber.cljs` | 69 |
| `src/seon/diffusion/bootstrap_cache.cljs` | 63 |
| `src/seon/platform.cljs` | 62 |
| `src/seon/agent/authorization.cljs` | 55 |
| `src/seon/repl/autocomplete.cljs` | 54 |
| `src/seon/items.cljs` | 20 |
| `src/seon/agent/message/pod.cljs` | 16 |
| `src/seon/demo.cljs` | 14 |
| `src/seon/result.cljs` | 10 |

## 4. Commit grouping — *lane*

Six coherent commits rather than one unreviewable one. Groups 1–5 are
DELETE-NOW; group 6 is the render-held set and waits on O14.

| # | group | lines |
|---:|---|---:|
| 1 | diffusion / typeahead | 4,607 |
| 2 | JVM-owned capability leaves | 5,113 |
| 3 | provider / generation / embedding | 2,871 |
| 4 | pod toolkit / agent orchestration | 2,640 |
| 5 | pod substrate and supervised entry | 8,806 |
| 6 | render replacement cleanup | 6,920 |

Groups 1–5 sum to 24,037, matching DELETE-NOW.

## 5. The five decision questions

**1. `bin/test-cljs` — what coverage dies.** *lane*: it compiles Shadow's
`:node-test` build and runs Bun; it does **not** use `seon.test.runner`.
Lost: **98 CLJS test namespaces / 1,080 `deftest`s**, plus the CLJS branches of
**24 `.cljc` namespaces / 191 `deftest`s**. `bin/test-writer` must claim the JVM
coverage and durable test evidence; operator coverage stays with
`bin/seon test operator`. **No fourth runner** (AGENTS.md forbids it). This is
the largest single consequence of O13 and it needs its own row in the ledger.

**2. `src/seon/config.cljs` (1,185).** *lane*: not a thin leaf — it mixes Node
observation with **duplicated** resolution and accessors.
`src/seon/config/resolve.cljc` is already the surviving JVM owner. So this is a
parallel implementation of config resolution, deleted rather than ported.

**3. The surviving wire — R-8b's "~3,650 lines" is wrong.** **verified**:
web-render resolves `uds.cljc`, never `uds.cljs`, and its direct JVM wire stack
is **4,946 lines** — `db/transport/uds.cljc` 1,703 + `db/host.clj` 979 +
`db/protocol.cljc` 2,264. Correct R-8b wherever it is quoted.

**4. The optional subsystem is the largest free deletion.** *lane*: diffusion
CLJS is 2,819 lines; with `ai/typeahead.cljs` the unreachable opt-in subsystem
is **4,067 lines**. DiffusionGemma is opt-in only through explicit provider
configuration and is never activated as a side effect (AGENTS.md), and none of
it is reachable from a surviving path.

**5. `src/seon/client.cljs` (2,876).** *lane*: now has **zero**
`recovery/recover!` calls — the plan's Wave 0 "six production calls" is stale
after `8dc8623ad`. Only an unused require, a dead helper, and stale
documentation remain. Note this closes the CORRECTION in
`redesign-ledger-2026-07-25.md` that refused the deletion on those grounds.

## 6. Reader conditionals

*lane*: **428 of 453** real reader conditionals become unnecessary once the JS
tier is gone; 25 Babashka/JVM conditionals remain. The plan's **468 of 493 is
stale.** (Orchestrator's independent count of `#?(` occurrences across `src/`
was 448, which brackets the lane's 453 — the difference is `#?@(` handling.
Treat 428/453 as the lane's figure pending a rerun.)

Per AGENTS.md the owner MAXIMIZES portable `.cljc` — "a `.cljc` is wrong only if
it CONTAINS unconditional platform code" — so a clean `.cljc` does **not**
collapse to `.clj` just because the pod died. Only conditionals that exist
purely for the dying tier come out.

## 7. Dangling attribute references — verified, and worse than known

**The entire `:seon.ai.attempt/*` family is unregistered.** Verified: **25
distinct attributes** used across `src/`, **0** `schema/register!` calls for any
of them. The previously-known `:seon.ai.attempt/ordinal`
(registration deleted by `f6f6673b6`, consumers at
`src/seon/web/serve.cljs:976, :999, :1010, :1175, :1181`) was one of 25.

Also dangling, *lane*: `:seon.agent.turn/llm-attempts`,
`:seon.agent.turn/phase`, `:seon.ai.attempt/partial-text`.

All of these are read by `web/serve.cljs`, which is in the render-held set — so
they ride the render cut and need no separate fix. Filed:
`docs/seon/issues/pod-attempt-ordinal-consumers-reference-an-unregistered-attribute.md`
(broaden it to the whole family).

## 8. What this audit did NOT settle

- **The per-file evidence column**, lost to the sandbox error. The verdicts are
  the lane's; only the counts are re-verified.
- Remaining determinations need the **O14 render ruling** and a **reset
  database's installed-schema comparison** (*lane*).
- Nothing here is a live proof. The pod cut has not been executed and
  `bin/test-writer` currently discovers **0 tests**, so no suite can presently
  verify any of it.
