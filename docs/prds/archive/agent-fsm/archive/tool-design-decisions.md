---
type: prd
status: draft
tags: [prd, agent]
---

# my.* toolkit — consolidated design decisions

This is the design-phase output that feeds implementation of the `my.*` agent
toolkit. It synthesizes eight per-tool research notes (each in
`docs/prds/agent-fsm/research/tool-*-research.md`) into ONE decision surface: the
wrap-or-build verdict per tool, the chosen library/pattern, the agent-facing API,
the `seon.*` floor it backs onto, a dependency-ordered build plan, and the
cross-cutting decisions every tool shares.

The headline finding across all eight: **net-zero new npm dependencies.** Every
capability is either already built in a protected `seon.*` floor (wrap it) or is
covered by a Node builtin / Clojure-core + Malli pattern (no library earns its
place). The work is overwhelmingly RESHAPE onto a shared composability backbone +
thin editable `:toolkit-seed` wrappers — not new engines.

## The two-tier model (the frame all eight obey)

- **Protected floor** (`seon.*`, `:core-seed`-guarded, indexed + grep-able but
  NOT rendered every turn, un-clobberable): owns syscalls, the allowlist, the
  wire, the cron engine, the test runner, the embed client. The agent cannot
  `forget!`/override it.
- **Owned wrapper** (`my.*`, seeded `:toolkit-seed` → `:agent` on first edit,
  full source rendered every turn): a lean map-in/map-out facade that reshapes the
  floor's output onto the backbone shapes. The agent CAN redefine or `forget!` it.

Each tool below is a `my.*` wrapper; the "floor it backs onto" column is the
`:core-seed` substrate it wraps.

## Decision table

| Tool | Wrap or build | Chosen library / pattern | API surface (agent-facing) | `seon.*` floor it backs onto |
|---|---|---|---|---|
| **my.files** | thin-wrap-existing-seon | Node `node:fs` SYNC builtins (already in the floor). NO npm fs lib (fs-extra / graceful-fs / globby / fast-glob all rejected); babashka.fs = naming muse only (JVM-only) | sync, never-throws, default-deny; every request keyed on shared `:seon.path/abs`. `grants` · `configure!` · `read-file` (paged) · `write-file` · `list-dir` → ITEMS of `:seon.path/located` · `walk-dir` (match-ext/skip-hidden/max-results) · `stat` · `file-exists?` (bare bool) · `home-dir` (bare abs) | `seon.agent.fs` (+ `seon.agent.fs.internal`) — allowlist, `..`-resolution, paging, walk |
| **my.search** | thin-wrap-existing-seon | `@vscode/ripgrep` (bundled `rgPath`) + `node:child_process/execFile --json`, JSON-lines parsed (the floor already does exactly this). No new engine | ONE verb `^:async grep`; errors-as-values, no-match = success-empty. Req `:my.search/{pattern, paths?, glob?, max-results?(100), case-insensitive??, fixed??(-F, kills the regex-escape footgun), hidden??}` → success `{ok? true :seon.items/items [located…] count truncated?}` \| `{ok? false :seon.error/*}`. Each located = `{:seon.path/abs :seon.path/line :seon.path/col? :seon.path/preview}` | `seon.agent.search` (+ `.internal`) — bundled rg, gate via `seon.agent.fs/stat` |
| **my.shell** | hybrid (new floor, but clones an existing wrapper) | Node `node:child_process.execFile`, Promisified to ALWAYS-resolve — clone `seon.agent.search.internal`'s exec wrapper byte-for-shape, reuse `seon.agent.fs/stat` as the cwd gate. NO execa (ESM-only since v6; throws-by-default — both fight the contracts) | ONE verb `^:async run`. Req `:seon.shell/{cmd(argv[0]), args?, cwd?(gated), stdin?, timeout-ms?(30000)}` → RAN `{ok? true exit(any) out err timed-out? truncated??}` \| COULD-NOT-RUN `{ok? false :seon.error/*}`. **`ok?` = "the process RAN", NOT exit 0** (non-zero exit is a legitimate answer — `(zero? exit)` is the success check) | NEW `seon.agent.shell` floor (clones search.internal; reuses `seon.agent.fs` gate). Default-deny via `SEON_SHELL` |
| **my.test** | hybrid (thin-wrap + ~15 lines new) | `cljs.test`-as-DATA via the existing runner's per-call `:reporter` (the ONLY runtime CLJS test path in self-host). kaocha (vendored, JVM-only) = the namespaced-summary design oracle, not a dep | `^:async check {:my.test/sym \| :my.test/ns}` → `{:seon.test/pass? summary{tests pass fail error} failures[{var message}] run-id}` (can't-run → `{pass? false :seon.error/*}`). `^:async check-edit {:my.test/fail-to-pass[] :my.test/pass-to-pass[]}` → `{resolved? fixed still-failing regressed fail-summary pass-summary run-id}` (SWE-bench post-edit predicate). `details {run-id}` → full events | `seon.test.runner` (capture-as-data, async, fixtures, malli-unwrap, stash/projection). Namespace its bare summary AT the facade |
| **my.code** (`forget!`) | hybrid (thin-wrap + 1 generalized floor primitive) | Generalize the private `seon.eval/unbind-result-var!` → public `seon.eval/undef-sym!` (analyzer `:defs` dissoc + munged-globalThis delete) over any FQ sym. NO library (JVM `ns-unmap` = design model only; `cljs.analyzer.api/remove-ns` is whole-ns; replumb undoes requires not defs) | `^:async forget!` — bare-sym sugar OR `{:my.code/sym [:or symbol keyword]}`. Success `{ok? true :my.code/{sym kind prior-source}}`; fail `{ok? false :seon.error/* (:kind :user-input \| :core-protected)}`. Pipeline: resolve identity attr → core-origin guard → return prior source → retractEntity → drop live binding (fn/test: `undef-sym!`; schema: NEW `seon.schema/unregister!`). `prior-source` = one-eval undo. `rename!` DEFERRED (= read-source → eval-new → forget!-old) | `seon.eval` (undef) + `seon.db` (retractEntity, bitemporal undo) + `seon.schema` (NEW `unregister!`) |
| **my.schedule** (`remind!`) | hybrid (thin-wrap; KEEP the in-repo cron engine) | KEEP the hand-rolled pure cron engine (`parse`/`due?`/`next-fire-at`). NO npm cron lib (schedulers own timers + in-memory state → fight the ONE-ticker + DB-derived-due-ness + crash-recovery model; croner is the only correctly-shaped one but still rejected). Timezone gap closes in-place with native `Intl.DateTimeFormat` | `^:async add! {:seon.agent.schedule/{cron, say?, fn?, timezone?}}` → `{ok? true id :my.schedule/next-fire-at}` \| `{ok? false :seon.error/*}` (cron validated via floor `parse`). `list {}` → ITEMS (each item carries `id` → threads to cancel!). `^:async cancel! {id}` (retract, idempotent). `^:async remind! {say, cron \| at}` = add! sugar (recurring or one-shot) | `seon.agent.schedule` (entity schema, pure cron logic, `fire-due-schedules!`, the ONE ticker) |
| **my.recall** | thin-wrap-existing-seon | Wrap `seon.embed/search-pull` (pod = read-only client; JVM wire-server embeds via Gemini `gemini-embedding-2` 1536-dim + Proximum HNSW cosine). NO in-pod embedder/index — transformers.js (384-dim MiniLM) is vector-space-INCOMPATIBLE with the authoritative 1536-dim index; hnswlib-node/Voy/LanceDB rejected | `^:async recall {:my.recall/{query, k?(default 10, suggest 5), within?(datalog :where), eids?, min-similarity?, pull?([*])}}` → on ok `{ok? true :seon.items/items count truncated?}` distance-ascending; on fail `{ok? false :seon.error/* (:kind :feature-off \| :user-input)}`. Each item = pulled entity lifted to top + `:seon.db/ref` + `:my.recall/distance` (raw cosine) + `:my.recall/similarity` (1-distance). Sibling `recall-refs` = ids+scores, no pull. SEON_EMBED-off → graceful ok?-false fallback (never throws) | `seon.embed` (pod client) → `wire-node/knn-search` (UDS) → JVM `seon.embed.clj` (key + index) |
| **my.canvas** | hybrid (thin-wrap + small build-fresh core) | Reuse the DB-as-bus write path (`:seon.render.live-canvas/content`) + `seon.ui.html` renderer; build-fresh ONLY a data registry `{view → component-symbol}` resolved through existing `seon.eval/lookup-value` + a Malli `:multi` (dispatch `:seon.canvas/view`) validating `:seon.canvas/data`. Replicant (`replicant.string`/aliases) VALIDATES the design but is inspire-don't-adopt (we already have a renderer + an alias-equivalent) | All `^:async` (transact forwards to wire-server); default target = caller's own agent by `:seon.agent/id`. `preview {view, data?}` → `{ok? true :seon.render.live-canvas/content(hiccup\|sym) :seon.render/ai}` (PURE, no effect). `show! {view, data?}` → same, transacts (kw view = eager literal hiccup snapshot; sym view = late-resolved each render). `card!`/`pros-cons!`/`recommend!` = show! sugar. `views {}` → ITEMS of view+data-schema | `seon.render.live-tile` (write path + `::content` shape) + `seon.ui.components`/`seon.ui.html` (hiccup floor). BLOCKED on U-lane Layer-1 components + `:seon.ui/*` vocabulary |

## Cross-cutting decisions

### A. npm dependencies: add NONE

No tool adds an npm dependency. The full rejected-library ledger, with the
one-line reason each loses to a Node builtin or an existing `seon.*` floor:

| Considered library | For | Verdict | Why rejected |
|---|---|---|---|
| `execa` | my.shell | NO | ESM-only since v6 (pod is CJS shadow bundle); throws on non-zero exit by default — both fight the contracts. Builtin `execFile` is already wrapped in `search.internal`. |
| `fs-extra` / `graceful-fs` | my.files | NO | copy/move/ensure verbs we don't use + a promise API we CAN'T use (sync is a hard eval-await constraint); graceful-fs solves FD-exhaustion a single agent never hits. |
| `globby` / `fast-glob` | my.files | NO | async-first; glob/content reach is ripgrep's job — would duplicate the my.search floor. |
| pure-JS ripgrep wrappers (`ripgrep-js`, `node-ripgrep`) | my.search | NO | shell out to the same rg with worse parse control; sparsely maintained. `@vscode/ripgrep` IS the standard (VS Code uses it). |
| kaocha / kaocha-cljs / cljs-test-runner / shadow `:node-test` | my.test | NO | all JVM-driven build-time tools; none runs in-process in self-host CLJS. `cljs.test` is the only runtime framework, already wrapped. (kaocha = design oracle only.) |
| JVM `ns-unmap` / `cljs.analyzer.api/remove-ns` / replumb | my.code | NO | JVM-only / whole-ns granularity / undoes requires-not-defs. The best single-sym CLJS undefine already lives in `seon.eval`. |
| `node-cron` / `node-schedule` / `croner` / `cron-parser` | my.schedule | NO | schedulers own timers + in-memory job state → fight the ONE-ticker + DB-derived-due-ness + crash-recovery model. The in-repo pure engine is code-as-data; an opaque dep is not. |
| transformers.js / hnswlib-node / Voy / LanceDB | my.recall | NO | vector-space INCOMPATIBLE (384-dim MiniLM vs the authoritative 1536-dim Gemini index) → would force a second drifting index or pod-side embedding (forbidden). |
| Replicant (`replicant.string`) | my.canvas | NO (inspire) | on-point and validating, but seon already has an XSS-safe hiccup renderer + an alias-equivalent (late symbol resolution). Two renderers = "don't be a dumbass." |

### B. Small `:core-seed` floor additions required (NOT new deps)

Wrapping is "net-zero npm," but five tools need a tiny generalization or gap-close
on the floor — each is a one-time, in-place change, never a `*-v2`:

| Floor change | For | What | Size |
|---|---|---|---|
| NEW `seon.agent.shell` ns | my.shell | execFile-always-resolve wrapper (clone of search.internal) + `SEON_SHELL` default-deny gate; cwd via `seon.agent.fs/stat` | new floor ns |
| `seon.agent.fs` touch-ups | my.files | `list-dir` → `readdirSync(…,{withFileTypes:true})` (type in one syscall); errors through `seon.error/->map` with `:seon.error/kind` | 2 small edits |
| facade-side summary translation | my.test | namespace cljs.test's bare `{:test :pass :fail :error}` → `:seon.test/*` AT the facade (kaocha's pattern); `check-edit` = ~15 lines new orchestration | facade-only |
| generalize `unbind-result-var!` → public `seon.eval/undef-sym!`; NEW `seon.schema/unregister!` (`swap! *schemas dissoc`); extract ONE `core-origin?` predicate by identity-attr | my.code | the schema "live binding" is the registry entry, NOT a globalThis var — without `unregister!` a forgotten schema stays validation-live until restart (highest-value finding) | 2 small floor verbs |
| native-`Intl` tz matching in `parse`/`next-fire-at` (in-place, gated by `:timezone`); optional one-shot `:seon.agent.schedule/at` + self-retract branch in `fire-due-schedules!` (~10 lines) | my.schedule | host-local matching is CORRECT for single-user-on-own-machine; only bites cloud dual-track. DEFERRABLE | deferrable |

### C. The four shared THREADING shapes (register ONCE on the floor)

The whole catalog is held to: **the output of one verb is a valid input to the
next, with no reshape at the arrow.** Tool-specific payload keys are `my.<tool>/*`;
the shapes you THREAD are `seon.*`. Exactly four, registered on the `:core-seed`
floor (step 1, before any wrapper):

- **PATH** — `:seon.path/{abs, line, col?, preview, located}`. The files ↔ search ↔
  shell hinge. A grep match IS a `:seon.path/located`; a dir entry IS one;
  `read-file`/`stat`/`shell cwd` ACCEPT one. Deletes the current manual rekey
  (`:seon.agent.search/path` → `:seon.agent.fs/path`).
- **REF** — `:seon.db/ref` (already exists). The DB address an item carries so it
  threads into `db/pull`/`db/entity`/`my.canvas` (my.recall items, my.test failure
  vars via `[:seon.test/sym (str var)]`, my.code's sym-as-address).
- **ITEMS** — `:seon.items/{items, count, truncated?}` (NEW). A self-describing
  collection mixin; each item is itself a valid next input. Adopted by
  my.search/my.files (located), my.recall (entities), my.schedule/my.canvas (rows).
  Counts stay scalars (aggregates, not items).
- **RESULT** — `:seon.result/ok?` (NEW discriminator) + the existing `:seon.error/*`
  map (`{:seon.error/message :seon.error/data{:seon.error/kind …} :seon.error/raw}`).
  Each tool's `:my.<tool>/ok?` REFERENCES `:seon.result/ok?`; failures return the
  shared error MAP, never a bare string, so an agent branches "fix my args"
  (`:user-input`) vs "report it" (`:core-bug`/`:core-protected`/`:feature-off`).

Two SPECIALIZED RESULT shapes are sanctioned divergences where the value IS the
answer: **my.test** returns `{pass? summary failures run-id}` (pass? is the
discriminator) and **my.shell** redefines `ok?` as "RAN" with a top-level
`exit` (non-zero exit is success-with-data, mirroring rg-exit-1-is-no-matches).
Both still return the shared `:seon.error/*` map on the can't-run path.

### D. sync vs async (a real, load-bearing split)

- **my.files is SYNC** and must stay so — the eval loop's auto-await fires only on
  a form's OUTERMOST value, so a Promise inside a `let` silently mis-branches.
  Never "modernize" it to `node:fs/promises`.
- **All other verbs are `^:async`** (they await execFile / transact!-to-wire /
  run-vars / knn-search). The pod is core.async-free — native CLJS `await`. (my.test
  `check`/`check-edit` async; my.schedule `list` is sync — pure DB read.)

## Build order (dependencies first, core-four first)

Each unit is independently evaluable against the live pod — define, eval, read the
value back. The ordering principle: the backbone shapes come first (every wrapper
references them), then the **core four** file-and-loop primitives (the SWE/coding
spine: find → read → run → verify), then the lifecycle/assistant/UI verbs that
build on top.

0. **Backbone shapes + `:toolkit-seed` origin** (floor) — register `:seon.path/*`,
   `:seon.result/ok?`, `:seon.items/*`; confirm `:seon.error/*` + `:seon.db/ref`
   referenceable; tag `my.*` nses `:toolkit-seed` at boot index so the
   `core-origin?` guard leaves them editable. Gate for everything below.

**Core four (the coding spine — build these first):**

1. **my.files** — rename wrapper over `seon.agent.fs`; the floor touch-ups in B.
   Proves PATH + ITEMS on the read side.
2. **my.search** — wrapper over `seon.agent.search`; match → `:seon.path/located`.
   Test `(->> (grep …) :seon.items/items (map files/read-file))` runs with no
   rekey — the headline composability fix.
3. **my.shell** + the NEW `seon.agent.shell` floor — execFile, fs cwd gate, the
   `ok?`=RAN refinement, `SEON_SHELL` default-deny.
4. **my.test** — facade over `seon.test.runner`; namespace the summary in the same
   patch; `check` then `check-edit` (the SWE-bench dual-set).

**Then (depend on the spine + backbone):**

5. **my.code / forget!** — the generalized `undef-sym!` + `schema/unregister!` +
   one `core-origin?` predicate. (Lifecycle's third verb; define/redefine already
   exist.)
6. **my.schedule / remind!** — verb over the existing cron engine + ticker; tz fix
   deferred.
7. **my.recall** — over `seon.embed`; SEON_EMBED-off → graceful fallback.
8. **my.canvas** — facade; BLOCKED on the U-lane Layer-1 `seon.ui.components` +
   `:seon.ui/*` vocabulary, so it lands last of the eight.

(Interleaves with the catalog's full plan — `my.todos`, the `message` floor smell,
`lifecycle` docstring, `db.examples`, the deferred `my.blob`, `my.kb` — which are
out of scope for these eight researched tools but share step 0's backbone.)

## Open flags carried into implementation

- **my.code schema-forget gap** (highest-value): a schema's live binding is the
  `seon.schema/*schemas` registry, NOT a globalThis var — `forget!` of a schema
  MUST call the new `seon.schema/unregister!` or the attr stays validation-live
  until restart. The catalog glosses this.
- **my.code naming**: the catalog sketch used `:seon.code/*` for the wrapper's OWN
  payload — should be `:my.code/*` (only the threaded `:seon.error/*` /
  `:seon.result/ok?` stay `seon.*`).
- **my.schedule `:say` surfacing**: firing opens a `:schedule` run but nothing
  injects the `:say` into the woken run's context yet — wire it reactively (set
  `:seon.agent.run/cause` to the schedule entity, add a derive-the-cause's-say
  render section). Owner = render/run-open. Without it `remind!` wakes silently.
- **my.schedule `:fn` exec** is stored but DEFERRED to the one-exec-service routing;
  `say`/wake ships today, the API shape is forward-compatible.
- **my.recall k default**: floor `default-k` is 10; each `[*]`-pulled entity hits
  the token-budgeted context — suggest the wrapper default to 5 (reuse
  `seon.embed/default-k`, don't mint a second constant). Owner judgment call.
- **my.canvas is BLOCKED**: needs Layer-1 `md-card`/`pros-cons`/`decision-summary`
  components + the unregistered `:seon.ui/*` vocabulary. Build order: shapes →
  components → my.canvas. Also: do NOT ship both `seon.agent.ui` (the older
  `ux-toolkit-proposal` framing) and `my.canvas` — `my.canvas` is the one to build.
- **my.test render** (separate owner): the per-ns `:seon.test` block must render
  only for the agent's CURRENT ns, coordinated with the GI-1 double-render fix —
  not a my.test API concern but the verify-loop UX depends on it.

## Sources

The eight per-tool research notes (full options-compared, gotchas, and verbatim
external/source grounding):
`docs/prds/agent-fsm/research/tool-my-{shell,files,search,test,code,schedule,recall,tile}-research.md`.
Spec being synthesized: `docs/prds/agent-fsm/toolkit-catalog.md` (the four shared
shapes, the two-tier model, the per-tool verdicts, the build/REPL-test order).
</content>
</invoke>
