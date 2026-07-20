---
type: prd
status: active
tags: [prd, database, agent]
---

# Async-facade completion PRD

## Problem

`seon.db` is now the asynchronous authority session: `db`, `query`, `entity`,
`pull` return Promises. Callers written against the removed synchronous
facade still treat returned Promises as values. Consequences range from
always-true predicates to Promise objects rendered into agent context —
the transcript already renders `[<Promise>]` labels because
`handlers/message.cljs:43`'s `(or (db/pull ...) ref)` is always the truthy
Promise. Evidence:
[[../database-authority-mesh/research/cleanup-audit-duplicate-interfaces-2026-07-20]].

Closed already: `seon.runtime.recovery` (commit `a2b0c815`; `later-run?` was
`(boolean <Promise>)`, unconditionally true).

## Two idioms, not one

The blanket "`^:async` fns + `await`" recipe is wrong for half the
inventory. There are two planes with opposite fixes:

- **Async plane** — ordinary async computation owners: convert to
  `^:async` with `await` per the recovery template (`a2b0c815`), errors as
  `:seon/error` values, one acquired database value threaded through
  related reads.
- **Sync render plane** — no `^:async` may escape into `seon.render/render`,
  `seon.warn/run-checks`, or any schema-property renderer. `run-checks`
  (warn.cljs:1069-1080) maps sync fns and filters with
  `(comp seq :seon.warn/affected)`; a `^:async` check returns a Promise and
  `(seq Promise)` throws OUTSIDE the per-check try/catch, killing the whole
  WARNINGS block. Schema-property renderers are invoked synchronously by
  `seon.render/render`'s `(unwrap-response view (f in))` (render.cljs:777),
  and render.cljs:180-182 documents renderers as pure projections with
  acquisition in the invoking operation. Fix these acquisition-side:
  the invoking operation pre-acquires the data; the render-plane fn stays
  pure over it.

Sync-render-plane fixes, concretely:

1. **`seon.warn`** — collapse the dual acquisition path to the pre-acquired
   `::warn/data` branch. Consequences stated explicitly: checks become pure
   over pre-acquired `::warn/data`, the ~15 direct-query fallbacks are
   deleted, `:seon.db/db` is dropped from `::check-request` (warn.cljs:75),
   and warn.cljs:1064's dead `seon.db/*conn*` guidance is rewritten to a
   pure-data repro form. `src/seon/agent/ctx/warnings.cljs:121-216` already
   supplies `::warn/data` and never passes `:seon.db/db`, so no behavior
   changes.
2. **`seon.handlers.message:43`** — nest identity attrs in the acquiring
   pull pattern feeding those renderers, mirroring the transcript's existing
   precedent `{:seon.agent.message/from [:db/id :seon.user/id
   :seon.agent/id]}` (src/seon/agent/ctx/transcript.cljs:887); `resolve-ref`
   then reduces to its pure already-carries-identity branch (or is deleted).
3. **`seon.render:684` (`renderable-inst`)** — same defect class: delete the
   per-node async `db/query` fallback and require the invoking events/list
   acquisition to join `:db/txInstant` into each node (the docstring already
   says the events query joins it once); a node without an inst sorts by
   absence, never by a mid-render read. See the reachability gate below —
   this fn is caller-less and is deleted, not migrated.

## Reachability gate — verify-then-delete before asyncify

Before migrating any inventoried site, `rg` the defn name across `src/`,
`test/`, `src-needle/`, and the active PRDs for callers. Most of the
`seon.eval` inventory is caller-less superseded code: `guarded-load*`
(eval.cljs:956-981) reads the pre-acquired authored-sources map ("The
loader never opens or reads a Datahike value"), and `compile-eval-tee`
consumes the acquired `::core-boot-function-symbols` member
(eval.cljs:3771/3803). Asyncifying these would entrench a dead async
subtree with stale docstrings and violate the one-mechanism rule ("delete
the superseded path in the same refactor").

**DELETE in the same commit series** (one issue note each per the house
rule):

- `seon.eval` `ns-rows-in-db?` (747), `synthesized-ns-head` (831),
  `reconstitute-ns-source` (847), `persisted-require-edges` (2820),
  `persisted-require-targets` (2863), `core-boot-fn-syms` (2946), plus the
  now-dead private helpers reachable only from them (`require-specs`,
  `merge-requires-into-ns-source` if caller-less after the cut, and the
  stale docstring cross-reference in `reject-core-overrides`);
- `render.cljs` `renderable-inst` (674; the 2026-07-17 web-render audit
  already found it caller-less);
- `testrun.cljs` `latest-run` (182-205; superseded by `lifecycle.cljc`'s
  `:seon.agent.testrun/_agent` pull).

**MIGRATE** per the idiom split:

| Consumer | Sites | Plane / disposition |
|---|---|---|
| `seon.warn` | ~15 across the check registry | sync render plane; acquisition-side collapse (item 1 above) |
| `seon.eval` `lookup-result` | 1618 | async plane; keep — it backs `result/<id>`, has behavioral tests, and is the reader named by [[data-browser]]; fix its stale `seon.agent.message` docstring reference in the same commit |
| `seon.agent.testrun` | remaining live sites, if any after the delete | async plane |
| `seon.agent.web.internal` | 528-536 | async plane; capability fn |
| `seon.handlers.message` | 43 | sync render plane; acquisition-side (item 2 above) |
| `my.skills` | 324-331 | async plane; toolkit |
| `my.canvas` (pinned) | 149-153 | async plane; `state` already migrated, `pinned` missed |

This collapses the eval-lane coordination surface from 7 clusters to
essentially one live fn.

## Agent-facing guidance strings

The guidance-string inventory is wider than warn.cljs:1064. `check-bad-ref`'s
own fix example (warn.cljs:720) teaches
`(def eid (ffirst (seon.db/query {...})))` — `ffirst` applies BEFORE the
runtime's whole-form await, so `eid` binds garbage and the agent loops on the
very warning that taught it the broken idiom. `bin/test-cljs` cannot catch
these; they are string literals rendered into agent context.

1. In the same `seon.warn` stage-1 commit series, rewrite warn.cljs:718-722
   to the documented whole-form-await idiom — two top-level forms: first
   `(seon.db/query {:seon.db/query '[:find ?e :where [?e :kb.doc/path
   "a.md"]]})` (the runtime returns data next turn), then use the returned
   eid directly in the transact map, dropping the nested
   `(def eid (ffirst ...))` composition entirely.
2. The check-error-cluster repro string (~warn.cljs:1066) also names
   `*conn*` and needs a `::data`-based repro after the dual-path collapse;
   rewrite it in the same series.
3. Audit, do not regex-gate: top-level `(seon.db/query …)` /
   `(seon.db/pull …)` in examples are CORRECT under whole-form auto-await,
   so a blanket rg over example strings would flag correct guidance.
   Instead `rg ':seon.warn/example'` (14 sites) and manually audit each
   example string against one invariant — every db read taught in an
   example is either its own top-level form or inside an explicit `^:async`
   fn with `await`, never composed inside another call. Record the audited
   list in the warn commit message. The `agent/ctx.cljs` guidance strings
   (819/961) were verified correct; no sweep there.

## Recommended solution

Order: `seon.warn` (worst, self-contained, sync-plane collapse) → the
reachability-gated `seon.eval` delete + `lookup-result` migration → the
remaining one-site fixes (mechanical, one commit) → re-run the audit scan
to zero.

## Acceptance

Full `bin/test-cljs`; audit inventory re-scan returns zero sync reads AND
additionally asserts zero `^:async` fns reachable from `seon.render/render`
or `seon.warn/run-checks`; reachability-gated: no caller-less superseded fn
was asyncified (the DELETE list landed as deletions); the
`:seon.warn/example` audit recorded; live cluster proof that a warn check, a
render, and an eval round-trip through the authority (recovery-style `.then`
probe or post-B7 `await`).

## Open questions

None — the idiom split is settled; this is execution.
