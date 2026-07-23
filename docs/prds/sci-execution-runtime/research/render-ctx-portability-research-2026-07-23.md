---
type: research
status: active
tags: [research, agent, ui, architecture]
---

# Render/ctx portability + purity inventory — 2026-07-23

Grounds ruling 21 (context is DERIVED from the database value; renders are
pure functions of it), ruling 20(d) (in-pod render move first, full .cljc
port as its own later unit), and triage #1
([[../../../seon/issues/ai-context-is-not-pure-over-database-value]]).
Every claim below is from current source on
`codex/runtime-reliability-refactor` (read-only lane; `wc -l` measured
2026-07-23).

## 0. Headline

- **Family size: 13,477 LOC measured** (ctx 7,729 + render 4,817 +
  debug 622 + my/canvas 309) — larger than the prior ~8.5k estimate.
  Of that, **2,216 LOC are already `.cljc`** (`render/value.cljc` 1,883,
  `my/canvas.cljc` 309, `ctx/ns_name.cljc` 24) and **~1,600 LOC are pure
  `.cljs` with zero JS interop** (rename-only). The genuinely hard port
  core is **~7k LOC**.
- **I1–I4 of the frozen-turn-inputs PRD HAVE LANDED in current source**
  (commit `6032f0b5` + follow-ups `cd3c2d6e`, `07c998a5`). The triage's
  "REAL (L)" sizing of triage #1 is stale on the input-impurity side; what
  remains is I5 (file blocks), one NEW timezone impurity, the ambient-db
  fallback doors, and vestigial-dial deletion. Detail in §2.
- The deepest coupling is not `seon.eval` data helpers (those are pure and
  relocatable) but **symbol→fn resolution**: `seon.render` resolves every
  render symbol — core and authored alike — through
  `seon.eval/lookup-value`'s `js/globalThis` walk (§3), and the prompt
  driver lives inside the dying execution child
  (`seon.execution.runtime/render-prompt!`, §5).
- Structural good news: every block already splits into *acquisition as
  data* (protocol member maps executed by `db/execute-many` at one passed
  database value) plus *pure formatting over acquired data*. The .cljc port
  is therefore mostly file moves + a date/format shim + one portable
  acquisition executor, not a rewrite (§5).

## 1. Per-namespace inventory

Classification: **(a)** pure data/hiccup transform over the db value —
portable as-is; **(b)** impure input (named ambient read); **(c)** platform
residue (js interop, mechanically convertible); **(d)** web-tier or
non-render (belongs elsewhere). Functions not named under (b)/(c)/(d) are
(a).

### 1.1 `src/seon/agent/ctx.cljs` — 2,161 LOC

| Class | Functions | Evidence |
|---|---|---|
| (a) | `quote-lines` `truncate-edn` `message-label` `read-error-envelope` `cap-result` `cap-result-body` `error-lines` `format-eval-row` glyph defs (`result-marker`…`prompt`) `render-system-text` + the system-text string family; `fn-block-ai`; the whole referenced-schema family (`schema-refs` `normalize-schema-form` `referenced-schema-rows-block` `schema-block-ai` `test-block-ai`) — deliberately registry-inert via a composite registry that never touches Malli's mutable process registry (ctx.cljs:1243-1258); `ns-demarc` `render-one-ns-ai` `render-namespace-ai` `split-context` `initial-agent-context` `decode-block` `agent-blocks` `block-bracket-ai` `cap-block-text` `rendered-context-from-entity` `selected-agent-blocks` `block-chain-keys` | e.g. `format-eval-row`'s escape-clipping override defaults to a constant `true`, not a live read (ctx.cljs:604-606; the ":live read" comment at :585 is stale) |
| (b) | `file-path->abs` (:118 `js/globalThis -process .cwd`), `file-exists?` (:125), `read-file-text` (:133 `js/require "fs"` readFileSync) → `file-block-ai/html/file-block` (:188-208), `identity-files-text` (:262-276) — **I5, open**. `soul-file-path` (:248-256) — load-time `config/env-string "SEON_SOUL"/"SEON_SOUL_FILE"`. `host-timezone` (:297-303) — `js/Intl.DateTimeFormat` resolvedOptions — **NEW ledger row L7** | |
| (c) | `js/require`, `Intl`, `cljs.reader` use — reader-conditional conversions | |
| (d) | `acquire-context-blocks` (:1652), `migrate-plan-surface-default!` (:1678), `install!` (:1726), `remove!` (:1763) — context ADMINISTRATION (db writes/config-apply), not render; port as ordinary ops outside the pure-render surface | |

### 1.2 `src/seon/agent/ctx/transcript.cljs` — 1,551 LOC

| Class | Functions | Evidence |
|---|---|---|
| (a) | `schema-default` cap fns (`decay-cap-for-offset` `tier-cap-for-turn` `clip-events-by-tiers` `turn-window-cutoff` `clip-events-by-turn-window` `clip-rendered-events-by-settled-budget` — `vswap!`/volatile budgets are invocation-local accumulators, pure by contract), `mode-fragment` `masthead` `message->renderable` `eval->renderable` `coalesced->renderable` `coalesce-events` `message->event` `eval->event` `with-ns-markers` `format-bytes` `ordered-events`, all query/member builders, `transcript-block`'s assembly logic | |
| (b) | `node-os` top-level def (:24 `js/require "os"`); `host-telemetry` (:593-609, loadavg + `js/process.memoryUsage`) and `readline` (:611-680, `js/Date.` now + `ctx/host-timezone`) — the DELIBERATE policy-fenced free dynamic tail, root-only via `readline-block` (:681-685), telemetry clipped ≤50 tokens (I4 landed as a separate terminal block); `clock` (:331) and eval timestamps (:348 `.toLocaleTimeString` with `ctx/host-timezone`) — formats STORED db instants but through the AMBIENT host TZ → **L7**: two pods in different zones render different cacheable bytes at one coordinate; `transcript-block` 3-tier db door (:1085-1087) — **L8** | |
| (c) | `js/Date` instance checks/`.getTime`/`toLocale*`/`#js` throughout — mechanical `java.time` shim | |
| — | **I1 vestige**: `::result-live?` is now ALWAYS false — `eval->event` is only ever called with `false` (:557 arity default, :719 the sole event-path caller); `::result-handles?` registered default false (:104) but the node-dial read still defaults true (:1319-1320) and is dead weight. No `seon.eval/result-live?` call remains in the namespace. Delete the dial + `::result-live?` plumbing with eval.cljs's writers (matches the deletion audit's named residue) | |

### 1.3 `src/seon/agent/ctx/namespaces.cljs` — 1,280 LOC

(a) all selection predicates (`my-ns-name?` `full-source-ns?` …),
`effective-selections`, `render-one`, `cur-ns-workspace-stub`, header,
formatting; the `acquire-*` family (:310-684) is pure over its PASSED
`database` (paged pull/query members). (b) the one 3-tier db door in
`acquire-namespace-rows!` (:505-507) — **L8**. (c)
`js/Number.MAX_SAFE_INTEGER` (:949). No clocks, no atoms.

### 1.4 `src/seon/agent/ctx/menu.cljs` — 553 LOC

(a) everything except: (b) `acquire-prompt-menu` 3-tier door (:368-370) —
**L8**. seon.eval coupling: `seval/edges->require-info` (:271) — a PURE
fold (eval.cljs:2636), relocation candidate (§3). No clocks.

### 1.5 `src/seon/agent/ctx/subagents.cljs` — 386 LOC

(a) `age-str` `child-line` `latest-closed-runs` `format-subagents-block` +
queries. **I3 LANDED**: `now` derives from `database-instant-query`
(max `:db/txInstant`, :151-154, consumed :258-280) — the breaker `since`
(:280) is `js/Date.` ARITHMETIC on the db instant, not a clock read = (c).
(b) 3-tier doors in `subagents-block` (:248) and `orphaned-agents-block`
(:345) — **L8**.

### 1.6 `src/seon/agent/ctx/warnings.cljs` — 546 LOC

Same shape. **I2 LANDED**: `database-instant-query` (:23-26) supplies
`now` (:301); the one-hour window math (:143-144) is `.getTime` arithmetic
on db instants = (c). (a) all page-data/format fns; paged `acquire-*` pure
over passed database. (b) 3-tier doors ×3: `warnings-block` (:370),
`core-faults-block` (:462), `instrumentation-gaps-block` (:515) — **L8**.

### 1.7 `src/seon/agent/ctx/canvas.cljs` — 384 LOC

(a) `discovery-state` `candidate-rows` `selected-surface`
`rendered-canvas-text` `selected-canvas-response`. `acquire-canvas!`
(:122) is pure over its passed database (history via
`(assoc database :history true)` — value-level, good). (b) `canvas-block`
3-tier door (:342) — **L8**. Guarded-door consumer:
`selected-canvas-call`/`invoke-selected!` for the wired render fn (§4).

### 1.8 `src/seon/agent/ctx/render_fns.cljs` — 131 LOC

(a) `output-twin-keys` (Malli parse, portable), `derived-blocks`,
`selected-call`. `render-fn-block-ai/html` take `invoke-selected!` as an
EXPLICIT leaf-provided input — already the ruling-21 shape; zero ambient
reads. The cleanest exemplar in the family.

### 1.9 `src/seon/agent/ctx/typeahead_steps.cljs` — 539 LOC

(a) the large surface-formatting family. (b) 3-tier doors ×2 (:55, :163)
— **L8**. (c) `js/Math.round` ×2 (:331, :424). Experimental
(repl-autosuggest lane, owner-preserved) — port LAST or leave as a leaf.

### 1.10 `src/seon/agent/ctx/usage.cljs` — 174 LOC / `ns_name.cljc` — 24 LOC

`usage`: 100% (a), zero js interop — immediate `.cljc` rename.
`ns-name`: already `.cljc`.

### 1.11 `src/seon/render.cljs` — 1,044 LOC

| Class | Functions | Evidence |
|---|---|---|
| (a) | all schemas, `unwrap-response` `loud-explain` `value-leaf` `map-node` `seqish-node` `value-node` `pruned-marker` `schema-statuses` `data-panel` `hiccup-text` `code-fenced` `block` `generic-*` `renderable-id` `missing-render`, the `render` walker's dispatch/guard logic | |
| (b) | `eval/lookup-value` ×4 — `render-entity-html` (:353), `invoke-custom-render` (:703), `render-entity-ai` (:884), `resolve-render` (:996): resolution against the process-local compiled-fn population (`js/globalThis` + munge walk, per the ns docstring :20-22) — **L9, the re-seam** (§3). `strict-fail!` (:313) reads the zero-arg ambient `config/render-strict?` dial; `err/record!` at catch sites writes a fault datom — both SANCTIONED mechanisms (errors-as-data; the strict dial), but the dial read should take the threaded `:seon.config/configuration` input when ported | |
| (c)/(d) | `value-url`/`drill-control` (:467-505) `js/URLSearchParams` + `JSON.stringify` — html-view drill controls, web tier | |

### 1.12 `src/seon/render/value.cljc` — 1,883 LOC — DONE

Already portable with `#?` conditionals (`js/WeakSet` vs
`java.util.IdentityHashMap` :357 etc.). Residue: a `:cljs`-only
`seon.config` require (:68) feeding `verbatim-probe-options` — the config
accessor must become input-threaded or the require conditional-balanced
when the JVM consumer arrives.

### 1.13 `src/seon/render/canvas.cljs` — 615 LOC

(a) `valid-hiccup?` `hiccup-structure-error` `wired-content` `wired-label`
`wiring-source` `default-error-card` `error-response` — the portable
canvas contract. (b)/(d) `welcome` greeting card `js/Date.` + `Intl`
(:451-462) — the default HTML greeting (web view; clock acceptable there,
or derive from the request instant). **Defect found:** `my.canvas/field-signal`'s
`:clj` branch (my/canvas.cljc:42-43) calls `seon.render.canvas/field-signal`,
but `seon.render.canvas` is `.cljs`-only AND defines no `field-signal` —
`my.canvas` cannot load on the JVM today; its "already .cljc" status is
nominal. Issue filed:
[[../../../seon/issues/my-canvas-clj-branch-references-missing-render-canvas-fn]].

### 1.14 Small render namespaces

- `render/chat.cljs` 136 — (a) except `js/Date` check (:64) = (c).
- `render/surface.cljs` 87, `render/view_unit.cljs` 42,
  `render/schema.cljs` 30 — 100% (a), rename-only.
- `render/system.cljs` 130 — (a) formatting; (b) `system-view` (:127)
  queries with NO `::db/db` (ambient latest) — **L11**; web-tier root view.

### 1.15 `src/seon/render/handlers/` — 850 LOC

- `fn.cljs` 137, `ns.cljs` 110, `schema.cljs` 71, `test.cljs` 156 —
  100% (a), rename-only.
- `message.cljs` 110 — (a) except `js/Date` checks (:34-36, :106) = (c).
- `eval.cljs` 266 — `render-ai` side (a); html side
  (`live-result-address` :134-150 `js/URLSearchParams`/`encodeURIComponent`,
  `live-result-disclosure` :150-168 `JSON.stringify` + Datastar attrs) =
  (d) web tier.

### 1.16 `src/seon/agent/debug.cljs` — 622 LOC

Observability projections (`ctx-preview` `turn` `turn-diff` `errors`
`error` `repro`): all `^:async`, each with an explicit `database` param
defaulting `(or database (await (db/db)))` — the latest-value default is
ACCEPTABLE for a human debug tool (explicit pin exists for reproduction) —
**L12, document not fix**. Couples to `turn/render-prompt` (re-render
path) and `my.blob` (platform behind a capability). Ports with the
loop/observability work, NOT with this render unit.

### 1.17 `src/my/canvas.cljc` — 309 LOC

Render side (`view` `button` `input` `select` `toggle` `form`) pure (a)
except the `field-signal` `:clj` defect above. `show!`/`state`/`save!`
`(or database (await (db/db)))` doors are toolkit capability fns (their
row-level porting blocks on host bindings, per the census), not context
renders.

### 1.18 The prompt driver — `src/seon/execution/runtime.cljs` (:1-420 of 715)

Not in the family count but the family's DRIVER: `render-prompt!` (:280),
`resolve-blocks!` (:140), `derived-blocks` (:173), `resolve-whole-prompt!`
(:190), acquisition members (:205-251), plus `render-agent-view!` (:451,
web view) and `interactive-hiccup` (:85, `seon.web.reactive.transform` —
web tier). Every block render — core and authored — is invoked through
`invoke-selected!` in the child. Notable: the child's own
`db/execute-many` (:290) passes NO `::db/db`; pinning is by PROCESS state
(the invocation context installed by `execution.host/invoke-compiled!`)
and validated post-hoc by turn.cljs:414-421 (`not= database
(:seon.db/db response)`). Dies at cutover; §5 stage 1 relocates it.

### 1.19 Dependency portability

Already `.cljc`: `seon.schema`, `seon.ai.tokens`, `seon.db.id`,
`seon.error` (+ `error/instrument`), `seon.instrument`, `seon.ui.html`.
Pure `.cljs`, zero js (rename-only): `seon.ui.markdown` (226),
`seon.ui.clojure` (192), `seon.agent.home` (232), `seon.warn` (962),
`my/ui.cljs` (272). Near-pure: `seon.derive` (547, 4 js sites).
Platform-bound by design: `seon.db` (pod client — the JVM side has its
own `seon.db` surface; the .cljc port targets whichever `execute-many`
shape the all-JVM design settles), `seon.config` (accessors must be
input-threaded, see L13).

## 2. The impurity ledger — every ambient read on the agent-context render path

Reconciliation of frozen-turn-inputs I1–I5
([[../../archive/frozen-turn-inputs/roadmap]]) against current source, plus
new rows found by this audit.

| # | Site | Ambient read | I-row status | Ruling-21 fix shape |
|---|---|---|---|---|
| L1 | transcript.cljs:557,:719 (`::result-live?` always false), :104 vs :1319 (dial default mismatch) | none remaining — the `seon.eval/result-live?` runtime-cache read is GONE | **I1 LANDED** (`6032f0b5`); vestigial dial | delete `::result-handles?`/`::result-live?` plumbing with eval.cljs's writers (deletion-audit residue "transcript result-handles dial after its writers") |
| L2 | warnings.cljs:23-26,:301 | none — `now` = max `:db/txInstant` of the pinned value | **I2 LANDED** | — |
| L3 | subagents.cljs:151-154,:258-280 | none — same database-instant derivation | **I3 LANDED** | — |
| L4 | transcript.cljs:24 (`node-os`), :593-609 (`host-telemetry` loadavg/rss/heap), :611-680 (`readline` live `js/Date.`), :681 (`readline-block` root-only) | live process state BY POLICY | **I4 LANDED** as a separate root-only terminal block; telemetry hard-clipped ≤50 tokens; prompt blob = byte history | keep policy-fenced; at port, telemetry becomes ONE platform leaf fn (`#?` os residue) or a leaf-provided input value; the stage-5 byte-identity gate should assert the tail sits after every cache boundary |
| L5 | ctx.cljs:118-145 (`read-file-text` fresh disk read per render), :188-208 (file blocks), :262-276 (`identity-files-text` SOUL.md/AGENTS.md) | filesystem | **I5 OPEN** (unchanged) | owner-ruled 2026-07-20: content stays file/blob; the FINGERPRINT (content hash) becomes the database fact transacted at an operation boundary; render pure over (db value + fingerprint) |
| L6 | ctx.cljs:248-256 (`soul-file-path`) | load-time env `SEON_SOUL`/`SEON_SOUL_FILE` | I5 sibling, OPEN | config→db manifest fact (the config singleton already exists) |
| L7 | ctx.cljs:297-303 (`host-timezone` Intl) consumed INSIDE the cacheable body by transcript timestamps (:348 `clock`, message/eval `hh:mm:ss`) | host timezone | **NEW** — not an I-row; cross-POD divergence (two pods in different zones render different bytes at one coordinate), not time drift | tz becomes a `:seon.config` database fact or an explicit render input; the free-tail readline may keep the live Intl read |
| L8 | 3-tier fallback door `(or (::db/db input) (::db/db (db/current-tx-context)) (await (db/db)))` ×11: subagents:248,:345 · canvas:342 · menu:370 · namespaces:507 · transcript:1087 · warnings:370,:462,:515 · typeahead:55,:163 | ambient LATEST value (tier 3) | **NEW class** — pinned in practice (the child's tx-context carries the invocation value; the driver never passes `::db/db` in `block-call`, runtime.cljs:129-138), falls to latest only for direct callers | the render port makes `:seon.db/db` a REQUIRED block input (driver injects it in `block-call`); tiers 2-3 deleted for a loud `:core-bug` error — the exact I8 precedent already applied at the turn spine |
| L9 | render.cljs:353,:703,:884,:996 `eval/lookup-value` | process-local compiled-fn population via `js/globalThis` munge walk | the re-seam (§3) | authored syms resolve through the guarded eval door (sci); core converter syms through a static trusted table |
| L10 | render/canvas.cljs:451-462 (`welcome` greeting `js/Date.` + Intl) | wall clock | web-tier html default | acceptable in the web view; derive from the request instant if the byte gate ever covers html |
| L11 | render/system.cljs:127 (`system-view` query with no `::db/db`) | ambient latest | web tier | pass the page's database value |
| L12 | debug.cljs:99,:177,:283,:399,:494 `(or database (await (db/db)))` | ambient latest DEFAULT | debug tool; explicit pin exists | document; not part of the cacheable-context gate |
| L13 | render.cljs:313 `config/render-strict?` (zero-arg); config accessors generally | process-installed config singleton | sanctioned config mechanism | thread the already-present `:seon.config/configuration` input (the pattern `(config/eval-render-cap configuration)` at ctx.cljs:590 is the correct shape; make the zero-arg dial take it too) |

Count: 13 rows — 4 landed (L1-L4 = I1-I4), 2 open from the PRD (L5, L6),
3 new fix-required (L7, L8, L9), 3 web/debug-tier acknowledgements
(L10-L12), 1 config-threading cleanup (L13). The frozen-turn-inputs
stage-5 byte-identity gate (render every default block twice at one
coordinate, diff bytes) is still UNBUILT and remains the acceptance
instrument for L5-L8.

## 3. The seon.eval re-seam

`seon.eval` dies at cutover (self-host compiler). Current coupling points
in the family and their replacements:

| Caller | seon.eval fn | What it actually is | Replacement |
|---|---|---|---|
| render.cljs :353/:703/:884/:996 | `lookup-value` (eval.cljs:502) | munged `js/globalThis` walk over the compiled-fn population | THE structural seam: split `resolve-render` into (i) core/trusted syms → a static symbol→fn table (ordinary compiled requires — the handlers, block fns, defaults) and (ii) authored syms (`err/agent-authored-sym?`, error.cljc:211-225) → the guarded eval door's sci resolution (`sci.core/eval-form`/env lookup over the corpus-loaded context). No corpus reparse: the sci host already loads `:seon.ns` source |
| ctx.cljs :671 | `sanitize-result-edn` (eval.cljs:2864) | PURE read-side re-projection (cljs.reader + `seon.render.value/project-plain`) | relocate to `seon.render.value` (its docstring already names it as that projection's net); needs a portable tagged-reader table |
| ctx.cljs :724 | `scratch-def-note` (eval.cljs:2213) | PURE source-string predicate | relocate to a portable source-analysis owner (`seon.repl.parse` / the ns-source owner) |
| menu.cljs :271 | `edges->require-info` (eval.cljs:2636) | PURE fold over `:seon.ns/require-edges` component rows | relocate beside the `:seon.ns.require` schema owner |
| transcript (docstrings only) | `render-error-string` naming | already write-side: `:seon.eval/error` is stored PRE-RENDERED (ctx.cljs:366-370) | no runtime coupling — nothing to do |
| transcript (removed) | `result-live?` (eval.cljs:1101) | the I1 cache read | already severed (L1); delete the dial |

Conclusion: ctx needs NO receipts or new facts from the eval replacement —
its three imports are pure helpers to relocate. The render walker needs
exactly one thing: a symbol-resolution seam with a trusted/authored split,
which is the same guarded-eval-door contract the scoping-hold research is
already designing.

## 4. The guarded-door boundary

Render entry points that MUST pass through the one guarded eval door
(authored, untrusted — deadline/fuel, output caps, `:agent` fault):

1. Stored block `:seon.render/ai` / `:seon.render/html` SYMBOLS that are
   agent-authored — today via `block-call`→`invoke-selected!`
   (runtime.cljs:129-165).
2. The whole-prompt symbol on the agent entity
   (`resolve-whole-prompt!`, runtime.cljs:190-203).
3. Derived render-fn blocks — every `::fn-sym` from indexed `:seon.fn`
   rows (render_fns.cljs:81-88 `selected-call`); authored by definition.
4. The wired canvas render fn + its AI twin
   (ctx/canvas.cljs `selected-canvas-call` :294; my.canvas twins — note
   the open render-twin-runs-function-twice issue).
5. Agent-authored entity converters and custom schema-property renderers
   reached through `render-entity-ai/html`, `invoke-custom-render`, and
   `resolve-render` (render.cljs:353/:703/:884/:996) — the authored side
   of the L9 split.

Core renders that run as TRUSTED direct calls (no door):

- The core block fns: `transcript-block`, `readline-block`,
  `warnings-block`, `core-faults-block`, `instrumentation-gaps-block`,
  `subagents-block`, `orphaned-agents-block`, `namespaces-block`,
  `function-menu-block`, `canvas-block`, `steps-ai`/`steps-surface-html`,
  file/identity blocks.
- Core converters `seon.render.handlers.*` (schema-stamped syms) and the
  generic/schema default renderers, `render.value` drill, masthead,
  system-text.

The boundary PREDICATE already exists and is the right one:
`seon.error/agent-authored-sym?` (error.cljc:211-225 — ns not
`seon|clojure|cljs|sci|goog`.*). Honest gaps today: (i) the child invokes
CORE block fns through the same `invoke-selected!` door as authored ones —
uniform but wasteful; post-move, core fns become direct calls and only
authored syms pay door overhead; (ii) `resolve-render` performs NO
authored/trusted split at resolution time — the classification exists only
post-hoc in fault attribution; the port must make the split structural
(trusted table vs door), or a hostile stored symbol naming a core-ish fn
runs unguarded.

## 5. The port cut

### Stage R0 — ruling 20(d) in-pod render move (at W5) — **M, ~600 LOC touched**

Move the prompt driver out of the dying child into the pod:
`render-prompt!` + `resolve-blocks!` + `derived-blocks` +
`resolve-whole-prompt!` + acquisition members (~420 LOC of
runtime.cljs:1-420) become pod-owned (natural owner: `seon.agent.ctx`
side, since blocks/`selected-agent-blocks`/`rendered-context-from-entity`
already live there); `invoke-selected!` is replaced by (i) direct calls
for core block fns and (ii) the guarded eval door for §4 items 1-5. No
`.cljc` conversion; the ctx/render namespaces are already loaded in the
pod. **Fold the purity fixes here per ruling 21 + triage #1**: L8
(required `:seon.db/db` block input; delete fallback tiers loudly), L7
(tz as config fact), L1 (dial deletion), L13 (thread configuration), and
the stage-5 byte-identity regression as the gate. L5/L6 (file
fingerprint) can land here or as its own S follow-up at the
config/install boundary — it is the only fix requiring a new transacted
fact.

### Stage R1 — pure renames — **S, ~1,600 LOC mechanical**

`ctx/usage`, `handlers/{fn,ns,schema,test}`,
`render/{surface,view_unit,schema,chat}`, `seon.ui.markdown`,
`seon.ui.clojure`, `my/ui` → `.cljc`. Zero semantic change; census: the
**my.ui 7 rows** move to platform-done.

### Stage R2 — the date/platform shim — **S**

One portable instant/format helper (js/Date ↔ `java.time.Instant` +
`DateTimeFormatter` for the sv-SE-style renders, safe-int predicates,
Math.round) consumed by transcript/chat/message/subagents/warnings.
Without this every later stage bleeds `#?` at ~40 sites.

### Stage R3 — render core + canvas — **M, ~1,700 LOC**

`seon.render` (walker, with L9 resolution seam from R0), `render/canvas`
(including a real `field-signal` so `my.canvas`'s `:clj` branch stops
dangling — fixes the filed defect; `java.util.Base64` per the c2 audit),
`handlers/eval` ai side. Html/url residue stays behind `#?` or in the web
tier. Census: unblocks the **my.canvas 11 rows** from platform-pending
(their remaining blocker is host bindings, per the reconciled census).

### Stage R4 — the ctx port — **L, ~6,400 LOC**

`ctx.cljs` (minus admin fns (d) and with L5-L7 already resolved),
`transcript`, `subagents`, `warnings`, `menu`, `namespaces`, `ctx/canvas`,
`render_fns` → `.cljc`. The structural cut that makes this tractable:
acquisition is ALREADY data (protocol member maps built by pure fns,
executed by exactly one `db/execute-many` call per stage) — confine the
platform/async ceremony to ONE portable acquisition executor (CLJS awaits;
CLJ plain calls against the JVM `seon.db` surface), and every block
becomes acquire-members → execute → pure-format. The `^:async` markers on
block fns exist ONLY for that executor call. Depends on: the all-JVM
design's `seon.db` execute-many shape (scoping-hold) and R0's door.
`typeahead_steps` ports last or stays a `.cljs` leaf (experimental,
owner-preserved).

### Out of this unit

`render/system` + html sides + `interactive-hiccup`/reactive-transform +
`render-agent-view!` (web tier — moves with the JVM web/SSE research);
`agent/debug.cljs` (observability — moves with the loop port);
`my.canvas` toolkit `show!`/`state`/`save!` rows (host bindings).

### Sequencing verdict

**Purity first, inside R0** — the fixes are small, they are the triage-#1
fold the anchor already ordered, and the stage-5 byte gate then protects
every later mechanical stage. R1/R2 can run as portfolio fillers any
time after R0; R3 before R4; R4 waits for the all-JVM `seon.db`/door
contracts. Expected census delta from R1+R3: my.ui 7 + my.canvas 11 rows
change state; the ctx/render "pod-legit" tier shrinks from ~11.3k
unported LOC to the web-tier residue (~1.5k).

## 6. Defects found by this audit (issues filed/updated)

- `my.canvas/field-signal` `:clj` branch calls a nonexistent
  `seon.render.canvas/field-signal` (my/canvas.cljc:42-43;
  `seon.render.canvas` is `.cljs`-only) — new issue
  [[../../../seon/issues/my-canvas-clj-branch-references-missing-render-canvas-fn]].
- Stale comments claiming live reads that no longer exist:
  ctx.cljs:585 ("the live escape-clipping read" — now a constant),
  transcript.cljs:1315 ("`::result-handles?` false, node first" — code
  defaults absent→true at :1319 while the schema default is false); both
  are cleanup rows for R0, noted in the triage-#1 issue's fold.
- `ai-context-is-not-pure-over-database-value` triage sizing is stale:
  I1-I4 landed; the remaining L is really L5-L9 (this ledger) — updated
  in the issue note.
