---
type: research
status: complete
tags: [database, pod, research, agent]
---

# Duplicate-interface and removed-API caller audit — src/ CLJS (2026-07-20)

Read-only audit on `codex/runtime-reliability-refactor`. Scope: duplicate or
parallel mechanisms inside `src/` ClojureScript, the exhaustive inventory of
callers still treating the asynchronous `seon.db` facade synchronously, direct
`datahike.api` use outside `src/seon/db/`, and dead namespaces. Config/startup,
logging, and `.clj` JVM-residue classification belong to other lanes.

## Dependency ledger

- `src/seon/db.cljs` at current HEAD — self-described "The pod's asynchronous
  database API"; `db`, `query`, `pull`, `pull-many`, `entity`,
  `installed-schema`, `execute-many`, `transact!`, `index-page` are all
  `^:async` and therefore always return a `js/Promise`, including when handed
  an explicit already-acquired `:seon.db/db` value.
- `docs/prds/runtime-reliability/roadmap.md` — the tree is intentionally broken
  where old synchronous consumers still name removed local database APIs; that
  is a dependency inventory, not compatibility work.
- Evidence method: `rg`/`sed` over `src/**/*.cljs`; every `(db/...)` call site
  was classified by whether its returned Promise is awaited (`await`/`.then`/
  `js/Promise.all`) or consumed synchronously (`ffirst`, `seq`, `->>`,
  `into`, `count`, `map`, destructuring).

## 1. Duplicate/parallel mechanism findings

### Confirmed drift/duplication

1. **Stale agent-facing guidance names the removed `seon.db/*conn*` var** —
   `src/seon/warn.cljs:1064`. `check-error-cluster` renders the repair example
   `"(" nm " {:seon.db/db (deref seon.db/*conn*)})"` into agent context.
   `seon.db/*conn*` no longer exists anywhere in `src/`; an agent following
   this guidance fails. Note `test/seon/ctx_test.cljs:87` asserts the *system
   text* contains no `db/*conn*`, but this warn-generated string escapes that
   gate. Owner: `seon.warn`. Fix: rewrite the example to the current async
   idiom (acquire via `(await (db/db))` upstream, pass the value); no new test
   path.
2. **`seon.warn` carries a dual acquisition path** — every check does
   `(or (::data pre-acquired) (db/query {...}))`. The `::data` injection path
   is the current-architecture path (results acquired upstream, passed in);
   the direct-query fallback is the broken synchronous remnant (section 2).
   One mechanism should survive: mandatory pre-acquired `::data` (or fully
   async checks); the sync fallback branch is the deletion.

### Checked and judged healthy decomposition (not duplication)

- **`seon.execution` / `seon.execution.host` / `seon.execution.runtime`** —
  one mechanism split by role: protocol + child entry point and operation
  dispatch (`execution`), Bun child supervision/spawn/correlation/shutdown
  (`execution.host`), child-side service composition (`execution.runtime`).
  Docstrings explicitly disclaim each other's ownership; no overlapping
  function surface found.
- **`seon.runtime.lifecycle`** does not exist. `seon.runtime.admission`
  (process-local admission of one verified committed generation),
  `seon.runtime.recovery` (unexpected-exit fenced database transition), and
  `seon.agent.lifecycle` (agent-facing scoped run operations: wait/complete/
  pause/resume/terminate) own disjoint concerns; `seon.agent.internal` is only
  shared error values and the parent-graph authorization selector for
  `agent.lifecycle` — a support namespace, not a second lifecycle.
- **`seon.agent.runtime` / `seon.agent.loop` / `seon.agent.turn` /
  `seon.agent.run`** — host process resources, run-driving FSM/ticker, one
  turn's execution, and run schemas/fencing respectively. Each docstring
  delegates the others' concerns; cross-checked function inventories do not
  overlap. `seon.derive` exists precisely to hold the once-quintuplicated
  derived-state projections below the require cycle — the anti-duplication
  mechanism, already consolidated.
- **`seon.render` + `seon.render.*` + `seon.ui.*` + `seon.web.*`** — a layered
  single render path: `render` (engine/twin resolution), `render.value`
  (raw eval values), `render.surface`/`ui.agent-view`/`ui.header` (pure
  eager-data → hiccup leaves), `web.router`/`web.datastar`/`web.serve`
  (routes, feed, HTTP host), `web.view-unit` (morph identity tokens only).
  No second renderer or second feed found. `seon.ui.markdown` and
  `seon.ui.clojure` are single hand-rolled formatters with no competing
  implementation (markdown's "Tonight's demo" docstring is stale prose, not a
  parallel path).
- **`seon.reactive` vs `seon.web.reactive.*`** — unrelated concerns despite
  the shared word: `seon.reactive` is registered reactive reads over writer
  interests; `web.reactive.call`/`web.reactive.transform` are the third
  sandboxed-execution door (hiccup-authored calls) and the Datastar
  event-handler rewrite. No shared responsibility.
- **`seon.repl` vs `seon.eval` vs `seon.worker-eval`** — `seon.eval` is the
  one agent eval boundary; `seon.repl` is the dev iteration surface
  (`dev-init!`, required only by `seon.web.serve` and eval tests);
  `seon.worker-eval` is a separate `shadow-cljs.edn` build entry
  (`:worker-eval`, `-main`) for the frozen diffusion GPU worker, not loaded by
  the pod. `seon.repair.candidates` is explicitly the ONE shared symbol-fix
  mechanism between `seon.eval` and `seon.worker-eval` (owner ruling
  2026-07-05) — consolidation, not duplication.
- **`seon.retry` vs `seon.repair.candidates`** — different concerns entirely
  (async backoff policy vs symbol-distance candidates); the names only rhyme.

## 2. Removed/synchronous `seon.db` caller inventory (the breakage inventory)

`seon.db` returns Promises unconditionally. Every site below consumes the
return value synchronously and is therefore broken (or silently truthy —
a Promise is truthy and `(seq promise)`/`ffirst` misbehave). The correct
idiom in each case is either (a) make the enclosing function `^:async` and
`await`, or (b) acquire the rows once at the async boundary upstream and pass
ordinary values down (the pattern `seon.repl.autocomplete`, `my.plan`,
`seon.agent.debug`, and `seon.web.serve`'s handlers already use).

### seon.warn (largest cluster — the whole check registry)

All checks receive `{:seon.db/keys [db]}` and fall back to direct queries:

- `src/seon/warn.cljs:110` — `fn-rows` `(db/query ...)` result `map`ped.
- `src/seon/warn.cljs:345,354` — seed-tx/schema-key queries into `into #{}`.
- `src/seon/warn.cljs:382,475` — `(db/installed-schema db)` consumed as a map.
- `src/seon/warn.cljs:416` — `(count (db/query ...))`.
- `src/seon/warn.cljs:624` — `latest-user-at`: `(ffirst (db/query ...))`.
- `src/seon/warn.cljs:640,652` — `failed-eval-rows` branches return raw
  query Promises to sync consumers.
- `src/seon/warn.cljs:760,821,873,912,942,975` — same pattern in the fs,
  storm, schema, and canvas checks (`->> rows filter`, `into #{} ... (fn-rows ...)`).
- Plus `src/seon/warn.cljs:1064` stale `seon.db/*conn*` guidance (section 1).

Correct owner: keep the `::data` pre-acquired injection as the only path;
the context acquisition layer (execution child) awaits once and passes values.

### seon.runtime.recovery (read/notice derivation half)

- `src/seon/runtime/recovery.cljs:553` — `anchor-rows` returns the raw query
  Promise; consumers treat it as rows.
- `src/seon/runtime/recovery.cljs:563,578` — `repaired-agent-runs`,
  `interrupted-run-turns`: `(->> (db/query ...) (sort-by ...) vec)`.
- `src/seon/runtime/recovery.cljs:595` — `later-run?`: `(boolean (db/query ...))`
  — always true (Promise is truthy): a live correctness bug, not just breakage.
- `src/seon/runtime/recovery.cljs:640` — `(db/entity ...)` result read with
  keyword access.

### seon.eval

- `src/seon/eval.cljs:752` — `(boolean (seq (db/query ...)))` (ns-exists
  probe) — always true.
- `src/seon/eval.cljs:875,883` — ns/member source acquisition:
  `(-> (db/query ...) first first)` and `(->> (db/query ...) (map first))`
  inside the `*load-fn*` string builder.
- `src/seon/eval.cljs:1640` — `(db/entity {:seon.db/ref [:seon.eval/id ...]})`
  row read synchronously (result-symbol miss path).
- `src/seon/eval.cljs:2832–2852` — persisted-require-edges:
  `(ffirst (db/query ...))`, `(contains? (db/installed-schema db) ...)`,
  and `(:seon.ns/require-edges (db/pull db ...))` all synchronous.
- `src/seon/eval.cljs:2960` — boot-fn filter: `(into #{} ... (db/query ...))`.

### Other src/seon consumers

- `src/seon/render.cljs:684` — node `:db/txInstant` sort key:
  `(ffirst (db/query ...))`.
- `src/seon/agent/testrun.cljs:192,205` — `latest`: `(->> (db/query ...)
  (map first) (reduce max 0))` then destructuring `(db/pull ...)`.
- `src/seon/agent/web/internal.cljs:528–536` — `fresh-projection`:
  `(seq rows)`/`max-key` over the query Promise and returns `(db/entity e)`
  unawaited.
- `src/seon/handlers/message.cljs:43` — `(or (db/pull ...) ref)` — the `or`
  always takes the Promise branch.

### src/my (agent-facing toolkit)

- `src/my/skills.cljs:324–331` — skill-block render: `db/query` eid then
  `db/pull` row, both synchronous.
- `src/my/canvas.cljs:149–153` — `pinned`: `(contains? (db/installed-schema
  dbv) ...)` and `(some-> (db/pull ...) :seon.render.canvas/content ...)`
  synchronous; note `my.canvas/state` was already migrated to `^:async`
  (roadmap 2026-07-18), `pinned` was not.

### Verified healthy (already migrated, listed to bound the inventory)

`seon.repl.autocomplete`, `my.plan`, `my.data`, `seon.agent.debug`,
`seon.agent.lifecycle`, `seon.agent.ctx.*`, `seon.web.serve` request handlers
(`js/Promise.all` + `await`), `seon.web.debug`, `seon.client`,
`seon.ai.generate-code`, `seon.reactive`, and `seon.agent.ctx.transcript`
all await or `.then` every facade call. No caller anywhere still requires a
removed namespace (`seon.db.replica`, feed, or local constructors are gone
with zero remaining requires).

## 3. Direct `datahike.api` outside `src/seon/db/`

- **CLJS `src/`: none.** Zero `datahike.api` requires in any `.cljs` under
  `src/` outside `src/seon/db/`.
- JVM `.clj` (classification owned by another lane, recorded for the ledger):
  `src/seon/embed.clj:76` and `src/seon/embed/preflight.clj:18` require
  `datahike.api` directly outside `src/seon/db/`.
- Test residue: `test/seon/agent/ctx/canvas_test.cljs:6,67` requires
  `datahike.api` and calls `d/create-database` from a pod test — a rule
  violation in the test tree.

## 4. Dead namespaces

- **`src/seon/agent/ctx/usage.cljs`** — required by nothing in `src/`,
  `test/`, or `config/` (no `usage` context block registered, no
  `usage_test.cljs`). 70 lines, genuinely dead. Deletion candidate.
- Not dead despite zero `src/` requirers (build/preload entries in
  `shadow-cljs.edn`): `seon.demo` (`:preloads`), `seon.worker-validator`
  (`:worker-validator` build `-main`), `seon.worker-eval` (`:worker-eval`
  build `-main`), `seon.diffusion.oracle`/`seon.diffusion.scaffold`
  (diffusion worker builds + own tests). These belong to the frozen diffusion
  lane; whether that lane's builds stay in `shadow-cljs.edn` is an owner
  decision, not this audit's.
- Single-requirer namespaces (`*.internal`, `seon.db.transport.uds`,
  `seon.web.view-unit`, `seon.ai.generate-code`) are cohesive private halves
  of their one public owner — healthy.

## Ordered collapse plan

1. Fix `seon.runtime.recovery` sync reads first — `later-run?` returning a
   truthy Promise corrupts recovery-notice derivation, the current spine's
   proof surface.
2. Migrate `seon.eval`'s five clusters (load-fn/require-edges/result-miss) —
   these sit under self-host evaluation and silently poison agent evals.
3. Collapse `seon.warn` to the single pre-acquired `::data` path (delete the
   direct-query fallbacks) and rewrite the `seon.db/*conn*` guidance string
   in the same change.
4. `my.canvas/pinned` and `my.skills` block → `^:async` + await (agent-facing;
   match the already-migrated `my.canvas/state`).
5. `seon.render:684`, `seon.agent.testrun/latest`,
   `seon.agent.web.internal/fresh-projection`,
   `seon.handlers.message:43` — mechanical await/acquire-upstream fixes.
6. Delete `src/seon/agent/ctx/usage.cljs`.
7. Move `test/seon/agent/ctx/canvas_test.cljs` off direct `datahike.api`
   (test-tree follow-up; coordinate with the test-gate owner).
