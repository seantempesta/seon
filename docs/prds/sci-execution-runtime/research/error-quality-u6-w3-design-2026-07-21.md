---
type: research
status: active
tags: [research, agent]
---

# Error-quality convergence design: U6 instrumentation + W3 preflight/steering

Complete implementable design for the owner's goal: agents get great,
token-conscious errors that immediately say what's wrong, where in the
data, and where in their code. Grounded in the landed W0.2 code
(`82a0c4b4`/`3346e54f`), the abridged-first/addressable-full ruling
([[../program-synthesis-2026-07-21]] design addenda), and the sci
internals read ([[sci-internals-opportunities-2026-07-21]]). Ends with
the dependency-ordered Codex work-package cut.

## 0. Dependency ledger

| Dependency | Where read | What it settles |
|---|---|---|
| sci pinned checkout | `reference-code/sci` HEAD `be4021d`; remotes: `origin` babashka/sci, `fork` seantempesta/sci. The resolution-failure patch is NOT yet on the fork (verified: no `:sci.impl/symbol` in `src/sci/impl/resolve.cljc`) | patch site `resolve.cljc:322-332` + wrapper `resolve.cljc:11-12`; `utils/throw-error-with-location` merges data (`utils.cljc:62-70`); IRef watches (`lang.cljc:194-202`); `bindRoot` notify (`lang.cljc:97-103`); interrupt marker structural test (`utils.cljc:47-56`); `sci.interrupt/interrupt!` data arg (`interrupt.cljc:32-42`); analysis-only recipe (`interpreter.cljc:29-62`); `sci/fork` = one `(atom @env)` (`core.cljc:318-323`); `rewrite-ex-msg` arity prose (`utils.cljc:88-119`); `sci/stacktrace` + `f-meta` frames (`core.cljc:402-410`, `utils.cljc:296-305`) |
| malli | `reference-code/malli`: `m/explain`, `malli.error/humanize` (`error.cljc:374`), `m/-function-info`, `m/function-schema` | the host wrapper needs NONE of `malli.instrument`'s CLJS accessor surgery — sci vars are plain objects; validate via `-function-info` input/output validators exactly as `seon.instrument/injecting-fschema` does inside its wrapper closure |
| pod instrumentation authority | `src/seon/instrument.cljc` (whole file), `src/seon/runtime/admission.cljs:257` | the ONE derivation: `seon.schema/projection-from-rows` over committed `:seon.schema/form` + `:seon.fn/spec` rows → projection → wrap. Kill-switch `enabled?` reads `SEON_INSTRUMENT` (`instrument.cljc:27-39`). Rejection classes `::no-var` (non-fatal) vs fatal (`instrument.cljc:833`) |
| host projection acquisition | `src/seon/host/context.clj:1093-1246` (`acquire-committed-projection!`, `refresh-committed-projection!`), `src/seon/host.clj:686-700` (refresh on `::projection-changed?`) | the host ALREADY holds the same committed projection, refreshed after every projection-changing eval — U6 consumes it, adds no second derivation |
| corpus recording | `src/seon/host/record.clj` (`fn-row` writes `:seon.fn/spec` from `:malli/schema` meta, `:seon.fn/schema-error` on parse failure; `tee-tx-data`) | schema-error rows exist as facts; instrumentation skips them and a derived render surfaces them |
| error envelope owner | `src/seon/error/instrument.cljc` (ALREADY `.cljc`: `explain-payload`, `report-fn`, `render-malli-error`, `instrument-error?`, `caller-fault-kinds`) | the malli failure classes need zero new code for shape or ai rendering — the host reuses the file as-is |
| landed W0.2 steering | `src/seon/host.clj:407-435` (`built-in-var-refusal?` structural-then-regex, `eval-error-value` home-ns steering); `src/seon/host/context.clj:433-451` (stamping) | refusal class shipped; W3 upgrades its message-regex fallback and the two remaining regexes (`host.clj:543` interrupt regex, `context.clj:921-923` resolve regex) |
| render slots | `src/seon/render/schema.cljs:29` (`:seon.render/ai`/`:seon.render/html` slots); `src/seon/handlers/eval.cljs` (`render-ai` budgets: source 200 / result 20 / error 30 tokens via `seon.ai.tokens/clip-str`); `src/seon/render.cljs:302-304` (html branch already dispatches on `einstrument/instrument-error?` → `render-malli-error`) | abridged/full is the EXISTING two-view dispatch, not a new shape |
| addressable full detail | `src/seon/host.clj` `retain-live-value!`/`serve-value-sample!` + `src/seon/render/value.cljc` `drill-value` (get-in/path browser, `result/{id}` binding in `src/seon/eval.cljs:1028-1140`) | the full-detail path exists for ok values; U6/W3 extends retention to error detail |
| repair candidates | `src/seon/repair/candidates.cljc` (`rank-candidates`, `pick-winner`) — already registered as a host wrapper (`context.clj:631-636`); pod loop `src/seon/eval.cljs:3962-4160` (`preflight-repair-run!`, skip-heads, budget, phantom-def hygiene) | the detect→candidates→trial→apply-or-hint loop is the parity model |
| tokens | `seon.ai.tokens/estimate`/`clip-str`/`bounded-pr-str` | the ONE budget mechanism; every human/agent-visible size in tokens |

## 1. U6 — host instrumentation over sci vars

### 1.1 One derivation authority

The pod derives instrumentation targets from
`seon.schema/projection-from-rows` (schema.cljc:485) over committed
`:seon.schema` + `:seon.fn/spec` rows and applies them via
`seon.instrument/reconcile-projection!` from `seon.runtime.admission`.
The host acquires the SAME projection through
`context/acquire-committed-projection!` (same two queries) into
`::projection-state`, and already refreshes it after every eval whose
tee changed `:seon.schema/form` or `:seon.fn/spec`
(`host.clj:686-700`). U6 adds exactly one consumer of that state — a
new `src/seon/host/instrument.clj` — and NO second query, registry, or
scan. `seon.instrument` (the pod `.cljc`) is not promoted: its body is
CLJS accessor surgery (goog.object, arity bridges, Malli meta-fn
repair) that has no JVM meaning. The two files share the projection
vocabulary (`:seon.schema.projection/function-contracts`,
`.../registry`) — that map IS the shared contract.

### 1.2 Wrapping mechanism

A sci var is a plain mutable object; `sci.core/alter-var-root` is the
privileged host write (binds `:unrestricted`, `core.cljc:249-257`), so
wrapping never fights the `:sci/built-in` stamp — probe step 7 proved
host-side alter-var-root upgrades stamped vars. The wrapper:

```clojure
(defn- wrap-fn [fn-sym function-schema f]
  (let [{:keys [min max input output]} (m/-function-info function-schema)
        vin (m/-validator input) vout (m/-validator output)]
    (with-meta
      (fn [& args]
        (let [n (count args)]
          (when-not (<= min n (or max Long/MAX_VALUE))
            (einstrument/report-fn :malli.core/invalid-arity
              {:arity n :arities #{{:min min :max max}}
               :args (vec args) :input input :schema function-schema
               :fn-name fn-sym}))
          (when-not (vin (vec args))
            (einstrument/report-fn :malli.core/invalid-input
              {:input input :args (vec args) :schema function-schema
               :fn-name fn-sym}))
          (let [ret (apply f args)]
            (when-not (vout ret)
              (einstrument/report-fn :malli.core/invalid-output
                {:output output :value ret :args (vec args)
                 :schema function-schema :fn-name fn-sym}))
            ret)))
      {::original f ::contract-fingerprint (content-hash/sha-256 spec-str)})))

```

Synchronous only — the host tier has no Promise arm, no injectables
(the pod's `injectables` registry resolves pod eval-context; host
capability wrappers already close over their writer), no accessor
shapes. Multi-arity `:function` schemas validate through per-arity
`-function-info` selection (same data `injecting-fschema` reads). The
`::original` meta link is the see-through marker (the JVM analog of
`malli$instrument$original`): re-instrumentation and the coverage
census read through it, never wrap a wrapper.

`einstrument/report-fn` is `seon.error.instrument/report-fn` — already
`.cljc`, already throws the `ex-info` whose ex-data is the full
`:seon.error.malli/*` envelope. Nothing new is invented for the
violation shape.

### 1.3 Install points and re-instrumentation

Three moments put a specced fn behind a wrapper, all driven by the one
projection:

1. **Full apply** at host `start!` and after every projection refresh:
   `instrument-projection!` walks
   `:seon.schema.projection/function-contracts`, resolves each sym to
   its live sci var (registry vars via `@registry` lookup; corpus vars
   via the base env's ns map under `sci.ctx-store/with-ctx`), and
   `sci/alter-var-root`s in the wrapper when the current root is not
   already wrapped for the same contract fingerprint (idempotent —
   refresh after an unrelated schema change re-walks cheaply and
   touches nothing).
2. **Redefinition** — the IRef watch. Every instrumented corpus var
   gets one `add-watch` (privileged path: watches go through
   `with-writeable-var`, so install under
   `sci.ctx-store/with-ctx {:unrestricted true}`). `bindRoot` fires the
   watch on every eval-side `defn` re-def (`lang.cljc:97-103`); the
   watch re-wraps the NEW root against the CURRENT projection's
   contract for that sym. Re-entrancy guard: the watch compares the
   incoming new root's meta `::original` — a root that is already our
   wrapper (its own `bindRoot` from step re-wrap) is ignored. Caveat
   accepted from the internals read: bare `(def x)` re-declaration
   mutates without notify — irrelevant for the defn-shaped corpus.
   NOTE the ordering hole the watch closes: the agent's `defn` commits
   its tee and only THEN does the projection refresh — between
   `bindRoot` and refresh the new fn runs unwrapped for its OLD
   contract. The watch wraps immediately with the old contract; the
   refresh re-wraps with the new one. No window where a specced fn is
   bare.
3. **Graduation / registry upgrade** — `graduate/install` and
   `register-wrappers!` both terminate in `sci/alter-var-root`
   (`context.clj:467`), which is `bindRoot`, which fires the same
   watch. Compiled graduated roots are ordinary JVM fns placed as the
   sci var's root; the wrapper wraps whatever the root is — nursery
   closure or compiled fn — one mechanism, no tier branch. (The real
   Clojure var `graduate/compiled-var` also interns is host-internal
   test machinery, not an agent call path; it is NOT wrapped.)

Host-authored capability wrappers (`seon.db/transact!` etc.) are
`:sci/built-in` AND specced-in-source with `:malli/schema` on their
compiled implementations — but those implementations are already
pod/JVM Seon functions; wrapping the sci var adds a second layer only
when the projection carries a contract row for that sym. Today the
projection contains only corpus `:seon.fn/spec` rows, so registry
wrappers are untouched; if capability contracts are later committed as
rows, the same walk covers them with no code change.

### 1.4 Kill-switch and schema-error rows

- **Kill-switch parity:** one predicate, same posture as
  `seon.instrument/enabled?` — default ON, `SEON_INSTRUMENT=0|false|off|no`
  disables every wrapper. Per ruling 7 the durable home is a named
  config fact; W1 owns the sweep. Proposed key:
  `:seon.config.instrument/enabled?` (host reads it from the committed
  config singleton at `start!`; the env var remains the emergency
  override until W1 lands, exactly matching the pod).
- **Schema-error rows:** a `:seon.fn` row with `:seon.fn/schema-error`
  never reaches the projection's contracts (record.clj only writes
  `:seon.fn/spec` when the schema compiles), so its var is simply not
  wrapped. Surfacing is a DERIVED render — a context/warnings query
  over `:seon.fn/schema-error` presence (the pod's warnings block
  already owns this class) — never a stored census. The host-side
  parallel of the pod's fatal-rejection rule: a contract row whose
  form no longer compiles against the projection registry is a
  `::unresolvable-schema` rejection; the full-apply walk skips it,
  reports it in its returned ledger, and the ledger lands in the host
  ready log line (parity with `base-failed=` reporting) — instrumenting
  the REST is correct on the host because sci vars are wrapped
  one-at-a-time with no Malli data-walk corruption hazard (the pod's
  all-or-nothing rule exists because `mi/instrument!` mutates as it
  walks; the host has no such shared walk).

### 1.5 Wrapper-fault classification

The pod's `wrapper-fault` refinement (agent-authored sym → `:agent`)
collapses on the host: every host eval IS an agent turn, the wrapped
syms are corpus fns, and the throw is caught by `eval-form!` which
already classifies to `:agent`. The envelope's fine class comes from
the ex-data (`instrument-error?` + `caller-fault-kinds` unchanged).
Heat: the wrapper bumps one in-memory per-sym counter (same wrapper,
one mechanism — the U3 candidate-selection input flagged in the
internals read §2).

## 2. Error value taxonomy

One shape everywhere: `{:seon/error {:seon.error/message …
:seon.error/kind … :seon.error/data …}}` (`seon.error` owner). The
fine class lives in `:seon.error/data` under its owning namespace.
New namespace: **`seon.error.sci`** (`src/seon/error/sci.clj`) — owns
the structural classifier over sci throwables and therefore owns the
`:seon.error.sci/*` keys (key-namespace ruling: the functions
operating on the data live where the keys point). Its public fns:

- `classify` — Throwable → the classified `:seon/error` value (walks
  the cause chain once; consumes producers' own terms — sci's
  `:sci.impl/symbol`, `:sci.impl/interrupt`, `:phase`, malli's
  envelope keys, `ArityException` fields — and translates at this one
  boundary);
- `steering-head` — error value + token budget → the abridged head
  string (the ONE composer of `:seon.eval/error` text);
- `detail` — Throwable → the full addressable detail map
  (`sci/stacktrace` frames with `f-meta`, complete ex-data chain,
  candidates) for live retention.

Registered class enum:
`(schema/register! :seon.error.sci/class [:enum :schema-input
:schema-output :schema-arity :resolution :arity :interrupt :refusal
:preflight :runtime])`.

Per class — exact data shape, ai render, html render, full-detail path.
Common to ALL rows: the persisted `:seon.eval/error` string IS the
abridged head, composed by `steering-head` within
`:seon.config.render/error-head-token-cap` (proposed config fact,
default 120 tokens, measured by `seon.ai.tokens/estimate` via
`clip-str`); `seon.handlers.eval/render-ai` then shows its first line
at its existing 30-token error budget in the transcript, and the
transcript's expanded per-eval card shows the whole head. Full detail
is NEVER inlined: the head ends with
`full: (get-in result/<eval-id> [:frames])`-style references (§2.9).

### 2.1 Schema violation on call (`:schema-input`)

- **Value:** kind `:agent`; data = the existing
  `seon.error.instrument` envelope untouched —
  `:seon.error/kind :seon.error.kind/malli-instrument-input`,
  `:seon.error.malli/fn-sym`, `/schema`, `/path`, `/leaf-type`,
  `/expected` (bounded 50), `/got-edn` (bounded 50), `/got-type`,
  `/humanized`, `/hint`, `/arg-index`, `:seon.error/args-edn`
  (clip 200). Plus `:seon.error.sci/class :schema-input`.
- **render-ai:** `seon.error.instrument/render-malli-error` verbatim —
  the `;; ERROR malli/instrument-input seon.db/transact! arg 0` block
  with expected/got/reason/hint columns; already per-field
  token-bounded. `steering-head` = that block + the detail reference.
- **render-html:** the existing `seon.render.cljs:302` branch
  (`instrument-error?` → `render-malli-error` in a card) — extend the
  same branch table, no new dispatch.
- **Full:** complete unbounded args, full explain `:errors` vector,
  schema form — retained under the eval id (§2.9).

### 2.2 Schema violation on return (`:schema-output`)

Same envelope with `/return-value-edn`; head names the VIOLATED fn as
the culprit ("`my.tools/parse-row` returned a value violating its own
`:seon.fn/spec`") — output violations are the callee's fault
(`caller-fault-kinds` excludes them), and the steering points at the
fn's source (`:seon.fn/sym` → "fix the fn or its spec"), not the call.

### 2.3 Resolution failure (`:resolution`)

- **Producer:** the sci fork patch (§4) puts `:sci.impl/symbol` in the
  analysis throw's ex-data alongside `:phase "analysis"`,
  `:line`/`:column`/`:file`.
- **Value:** kind `:agent`; data
  `{:seon.error.sci/class :resolution, :seon.error.sci/symbol foo/bar,
  :seon.error.sci/line 3, :seon.error.sci/column 9,
  :seon.repair/suggestions [{:seon.repair/to "…"} …]}`. Candidates:
  on catch, the classifier queries the SAME ctx with public API
  (`sci/resolve`, ns-publics/interns, `all-ns`, the wrapper registry
  libs) and ranks via `seon.repair.candidates/rank-candidates`
  (already a host wrapper). The `:seon.repair/*` keys keep their
  existing owner — repair operates on them.
- **render-ai head:** `Unable to resolve foo/bar (line 3). Did you
  mean my.tools/bar? Your fns live in my.agent.<id>.` — cause,
  suggestion, place, in the agent's own terms.
- **render-html:** error card with clickable candidate chips (each
  chip = the corpus fn's page link when the candidate is a corpus
  sym).
- **Kills:** the `re-matches #"Unable to resolve symbol: (.+)"` in
  `load-portable-slice!` (`context.clj:921-923`) and the symbol-regex
  extraction the preflight loop would otherwise need.

### 2.4 Arity (uninstrumented) (`:arity`)

- **Producer:** JVM interpreted sci fns throw
  `clojure.lang.ArityException` (message already rewritten to the real
  var by sci's `rewrite-ex-msg`). Structural: `(instance?
  clojure.lang.ArityException cause)`; fields `.actual` and `.name`.
- **Value:** data `{:seon.error.sci/class :arity,
  :seon.error.sci/symbol <resolved sym>, :seon.error.malli/arity n,
  :seon.error.malli/arities #{…}}` — arities read from the var's
  `:arglists` meta when resolvable (the registry and corpus vars carry
  real arglists). Instrumented fns never reach here (the wrapper's
  arity check reports `:schema-arity` first with the full expected
  set).
- **render-ai head:** `my.tools/fmt takes ([row] [row opts]) — called
  with 3 args.`

### 2.5 Interrupt / deadline (`:interrupt`)

- **Producer:** change `build-base!`'s `:interrupt-fn`
  (`context.clj:965-968`) to
  `(interrupt/interrupt! "eval deadline exceeded" {:seon.error/kind :timeout})`
  — `sci.interrupt/interrupt!` carries the data map into ex-data next
  to the structural marker.
- **Classifier:** `(identical? sci.impl.utils/interrupt-marker
  (:sci.impl/interrupt (ex-data e)))` end-to-end — kills the
  `#"deadline exceeded|interrupt"` regex at `host.clj:543`
  (the named regex-classified-interrupts WEAK). Cancel-vs-timeout
  stays where it is: `run-invocation!` consults
  `::cancel-requested?` after the interrupted batch — that is control
  state, not message parsing.
- **Value:** data `{:seon.error.sci/class :interrupt,
  :seon.error/kind :timeout, :seon.execution/deadline-ms …,
  :seon.eval/duration-ms …}`.
- **render-ai head:** `Interrupted at the 100ms eval deadline. The
  form was stopped mid-run; nothing after the interrupt executed.
  Partial writes before it ARE committed — check with db/query before
  re-running, or raise the deadline.` (The transact! op-id idempotency
  note belongs here when the form contained a transact — the head
  composer checks the source for `transact!` and adds the replay
  sentence. This is derived steering from facts, not a stored
  warning.)

### 2.6 Refusal (`:refusal`)

Landed in W0.2. Convergence changes only: fold
`built-in-var-refusal?` + `eval-error-value` (`host.clj:407-435`) into
`seon.error.sci/classify`, drop the message-regex FALLBACK arm once
the structural `:var` ex-data path is proven complete against the
hostile battery, and add data
`{:seon.error.sci/class :refusal, :seon.error.sci/refused-var
'my.shared/f}`. Head text stays the shipped steering (own fn in own
home ns; last-version-wins).

### 2.7 Preflight finding (`:preflight`)

Produced BEFORE execution by §3; shape is the pod's repair vocabulary
riding the standard error value: data
`{:seon.error.sci/class :preflight, :seon.error.sci/symbol …,
:seon.repair/from "tok", :seon.repair/suggestions […],
:seon.repair/ambiguous? bool}` — or, when a fix applied, NOT an error
at all: the envelope carries `:seon.repair/fixes` +
`:seon.repair/applied-class` beside the normal ok result (pod parity:
fixes annotate success; refusals steer failure).

### 2.8 Runtime throw (everything else) (`:runtime`)

The residual class: first line of the message (current behavior), plus
`:seon.error.sci/callstack-head` — the top 3 `sci/stacktrace` frames
as `{:seon.error.sci/fn-sym :seon.error.sci/line :seon.error.sci/ns}`
maps built from the `StackFrame` chain's `f-meta` (the warning→
catch-site input; no message parsing). Head:
`Divide by zero — in my.tools/avg (line 2), called from my.agent.a1/report (line 7).`
"Where in their code" for free from data sci already keeps.

### 2.9 Addressable full detail — result/{id} + get-in

Today `retain-live-value!` retains only ok values (`host.clj:704-706`).
Change: on a FAILED eval, retain `(seon.error.sci/detail throwable)` —
a plain map `{:seon.error.sci/class …, :seon.error.sci/frames
[{…full stacktrace…}], :seon.error.sci/ex-chain [{:message :data} …],
:seon.error.malli/errors […], :seon.error.sci/args …}` — under the
same eval id through the same `admit-retained-value` bounding. The
existing value-sample path (`serve-value-sample!` →
`render.value/drill-value`) then serves it: the agent follows the
head's reference with `result/<id>` and pages with the get-in/path
browser exactly as for any large value; eviction and process-restart
already answer honestly ("re-run the form to recompute it"). The
20-page stacktrace is a reference, never a payload. Durable
persistence stays the cap-edn'd `:seon.eval/error` head — full detail
is runtime state (owner decision 1 below if post-restart forensics
should blob it).

## 3. W3 preflight — analyze-without-eval on the batch path

### 3.1 Hook point

Inside `eval-batch-result` (`host.clj:596-719`), per form, between
`start-eval-receipt!` and `eval-form!`. The receipt still commits
first (no receipt, no run — and a preflight-refused form still records
its terminal error honestly, exactly like a pod failed eval). Skip
rules are pod parity (`eval.cljs:4078-4119`): blank, `result/<id>`
reads, loader heads (`ns require use import in-ns` …) — analysis of
loader forms is not side-effect-free enough to trial and they are
never typo-fix targets. Macro heads need no exclusion on this tier:
sci macro expansion at analysis is the same work the real eval would
do first, and the trial ctx is disposable.

### 3.2 Mechanism

Per eligible form:

1. `fork` the AGENT'S context (`sci/fork` = one atom copy; the fork
   sees the agent's own defs and the shared registry vars).
2. Under `sci.ctx-store/with-ctx fork`, `(sci.impl.analyzer/analyze
   fork form)` per the `eval-form*` recipe — analysis without the
   `types/eval` call. Impl-namespace stability caveat: pinned
   `:local/root` checkout; re-verify the call on version bumps
   (recorded in this ledger). The two side effects (analysis-time
   `init-var!` interning; `set-namespace!`) land in the disposable
   fork — nothing to roll back (the pod needs
   `remove-phantom-defs!`; the host gets hygiene from the fork).
3. Clean analysis → run the ORIGINAL source in the REAL ctx
   (byte-identical to a run without preflight).
4. Analysis throw with `:phase "analysis"` + `:sci.impl/symbol` (§4)
   → the detect→candidates→trial loop, pod-shaped
   (`preflight-repair-run!` parity): rank via
   `candidates/rank-candidates` over (agent-ns interns ∪ registry libs
   ∪ `clojure.core` publics for bare tokens; resolved-ns interns for
   qualified tokens), trial = substitute + re-analyze on a FRESH fork,
   unique winner under the class dial applies (the fixed source then
   really evals and records with `:seon.repair/fixes` on the
   envelope), ambiguity/no-winner → the `:preflight` error value with
   suggestions; the form does not run (it could not have compiled
   anyway — pod semantics preserved).
5. Missing-require synthesis: an unresolved ALIAS-qualified symbol
   whose alias matches a registry lib or a committed `:seon.ns/name`
   fact → the suggestion is a merged require, and the head teaches the
   merge rule sci already implements (internals read §5): `Alias plan/
   is not required here. Add (ns my.agent.a1 (:require [my.plan :as
   plan])) — re-declaring your ns MERGES requires, it never drops
   existing ones.`

Arity-vs-arglists advisory: after clean analysis, walk the form's call
heads that resolve to vars carrying `:arglists` meta and compare
literal arg counts. Mismatch is ADVISORY only (apply/variadic/HOF
false-positive risk): it does not block the run; when the run then
fails, the finding is already attached to the classifier's input.
Instrumented fns make this near-redundant — keep it cheap and
head-only.

### 3.3 Findings surface

- Fatal (analysis cannot succeed): the form's envelope is the
  `:preflight` error value; eval skipped; terminal-tx-data records it;
  the batch continues to the next form (pod parity: one bad form does
  not kill the batch — but a form DEFINING a symbol later forms use
  will cascade honest resolution errors, each with its own steering).
- Fix applied: normal ok envelope + `:seon.repair/fixes`.
- Advisory: `:seon.repair/suggestions` beside the envelope.

### 3.4 Cost bound

sci eval = analyze + run (`interpreter.cljc:29-62` does both);
preflight adds ≤1 analysis pass + 1 env-atom copy per form, so the
worst case (pure-def forms, where run ≈ 0) is <2× and typical agent
forms (db round-trips ≈ 2ms each, LLM-adjacent work) are dominated by
run cost — well under the "must not double eval latency" line. Two
bounds enforce it: the existing `repair-budget-ms` config accessor
(reuse the SAME key — one budget dial for both tiers) caps the trial
loop; trials are capped by `repair-max-fixes` parity. The W3 gate
measures analyze-vs-eval on the live host (one timed batch of
representative corpus forms) and records the ratio in the PRD — a
falsifiable number, not this estimate.

## 4. The sci fork patch — structured resolution-failure ex-data

- **Site:** `reference-code/sci/src/sci/impl/resolve.cljc`,
  `resolve-symbol` (`resolve.cljc:322-332`): the failure arm calls the
  local `throw-error-with-location` (`resolve.cljc:11-12`), which
  passes `{:phase "analysis"}` to `utils/throw-error-with-location`
  (`utils.cljc:62-70`, merges data into the ex-info).
- **Patch (~2 lines):** widen the local wrapper to take data and pass
  `{:phase "analysis" :sci.impl/symbol sym}` from the failure arm.
  Resulting ex-data:
  `{:type :sci/error, :line L, :column C, :file F, :phase "analysis",
  :sci.impl/symbol the-sym}` — pure-additive.
- **Consumer:** `seon.error.sci/classify` keys on
  `(:sci.impl/symbol (ex-data cause))` + `:phase "analysis"` →
  `:resolution`; the preflight loop reads the symbol directly. The
  boundary translation to `:seon.error.sci/symbol` happens in
  `classify` only.
- **Fork state:** `reference-code/sci` remote `fork` =
  `seantempesta/sci`; HEAD `be4021d` does NOT yet carry the patch —
  WP-A commits it on a `seon` branch of the fork, and W9's pushed
  mirror publishes that coordinate.
- **Upstream PR framing:** "Add the unresolved symbol to the
  analysis-failure ex-data (`:sci.impl/symbol`), mirroring the
  existing `:phase` key. Tooling (REPLs, editors, agents) can then
  build did-you-mean and auto-require suggestions without parsing the
  exception message. Zero behavior change; message untouched;
  one additive key." Near-zero rebase risk carried locally either way.

## 5. Feeding generate-code fix-up workers (Stage 6 bundle)

The Stage 6 worker bundle (generate-code roadmap Stage 6 exit:
"exact original plan, accepted prefix, errors, target source,
`.internal` source, neighbor contracts, sibling status"; W7:
"planner reply, accepted prefix, failed eval ids, sibling status").
The "errors" entry is NOT a new shape: it is the failed evals'
`:seon.eval` entities rendered through the ONE
`seon.handlers.eval/render-ai` slot — which now shows the
`steering-head` text because that is what `:seon.eval/error` stores.
The bundle names the failed eval ids; each renders as its transcript
row; the worker follows `result/<id>` for depth. Three worked
examples, agent form → worker-visible steering:

**A. Resolution failure.** Planner-generated unit evals
`(defn total [rows] (reduce + (map :acme.order/amount rows)))` then
`(totl [{...}])`. sci analysis throws with
`:sci.impl/symbol totl`; preflight trial proves `total` is the unique
compiling candidate under the dial → fix applies, form runs, envelope
carries fixes — OR with the dial off: classify → `:resolution` value;
terminal row `:seon.eval/error`:
`Unable to resolve totl (line 1). Did you mean my.gen.orders/total? Your fns live in my.gen.orders. full: result/auC-2607211412`.
Worker bundle renders:
`[eval auC-2607211412 3ms :error]` / `(totl [{...}])` /
`:error Unable to resolve totl (line 1). Did you mean my.gen.orders/total?…`
— the worker re-emits the corrected call without opening the detail.

**B. Schema violation on call.** Worker's earlier unit registered
`(schema/register! :acme.order/amount :int)`; the failing unit calls
`(my.gen.orders/record! {:acme.order/amount "12.50"})`. The U6 wrapper
validates input, `report-fn` throws the malli envelope, classify
passes it through; `:seon.eval/error` head (render-malli-error block):

```
;; ERROR  malli/instrument-input  my.gen.orders/record!  arg 0
;; expected  :int    at  [:acme.order/amount]
;; got       "12.50"    (string)
;; reason    should be an integer
;; hint      use (js/parseInt x 10) to convert string→int
full: result/auC-2607211418

```
(what's wrong, where in the data — the path — and where in the code —
the fn and arg index; the coercion-hints table gains the JVM-tier
`parse-long` wording in WP-D). The worker fixes the producing form,
not the symptom site, because `fn-sym` + `path` name the contract.

**C. Deadline.** A generated unit's test form loops:
`(my.gen.orders/reconcile-all! db)` runs past the invocation deadline.
Watchdog interrupts the worker thread; `:interrupt-fn` throws the
data-carrying interrupt; classify → `:interrupt`/`:timeout`;
`:seon.eval/error`:
`Interrupted at the 60000ms eval deadline. Nothing after the interrupt ran; writes committed before it are durable — check with db/query before re-running. full: result/auC-2607211425`.
Stage 6 bundle shows the row plus sibling status (other units green),
so the fix-up worker treats it as a performance defect in its own
unit — bound the loop or batch the writes — rather than re-running
blind. The steering's replay sentence prevents the classic
double-transact bug.

## 6. Work-package cut (Codex-implementable, dependency order)

Every spec carries the ruling-10 preamble (read the `reference-code/`
source you interface with; report better seams; use upstream terms),
shared-tree path-limited-commit rules, and behavior-not-strings tests.

**WP-A — sci patch + structural classification (`seon.error.sci`).**
Owned paths: `reference-code/sci` (fork branch commit: the
`resolve.cljc`/local-wrapper patch + one test), NEW
`src/seon/error/sci.clj`, `src/seon/host.clj` (replace
`built-in-var-refusal?`/`eval-error-value`/interrupt regex call sites
with `classify`/`steering-head`), `src/seon/host/context.clj`
(interrupt-fn data map; resolve-regex removal in
`load-portable-slice!`), tests under the writer gate. Gate: one
hostile classification suite — each class (§2.1-2.8) produced live,
asserts the `:seon.error.sci/class`, structural keys, and zero
message-regex (`rg -n 're-(find|matches)' src/seon/host* src/seon/error/sci.clj`
clean of error-classification regexes); full writer gate green.

**WP-B — U6 host instrumentation.** Depends on WP-A (violations flow
through classify). Owned paths: NEW `src/seon/host/instrument.clj`,
`src/seon/host.clj` (apply at `start!` + after projection refresh),
`src/seon/host/graduate.clj` (no change expected — alter-var-root
already fires the watch; verify), tests. Gate: bad call/bad return/bad
arity on a specced corpus fn produce the malli envelope; agent
re-`defn` re-wraps via the watch (call immediately after re-def is
validated); graduated swap stays wrapped; `SEON_INSTRUMENT=0` host
runs zero wrappers; schema-error row skipped + derived warning
renders; full writer gate green.

**WP-C — W3 preflight.** Depends on WP-A (`:sci.impl/symbol`).
Owned paths: `src/seon/host.clj` (`eval-batch-result` hook), NEW
preflight fns in `src/seon/host/context.clj` or a
`src/seon/host/preflight.clj` (implementer's seam call per ruling 10),
tests. Gate: typo'd symbol with unique candidate auto-fixes and runs
(dial on) / steers without running (dial off); missing-require
suggestion names the merged ns form; skip-heads honored; measured
analyze/eval ratio recorded in the PRD and < 2×; batch after a fatal
finding continues; full writer gate green.

**WP-D — abridged-first render + addressable detail.** Depends on
WP-A+B. Owned paths: `src/seon/host.clj` (retain error detail;
`terminal-tx-data` head via `steering-head`), `src/seon/error/sci.clj`
(`detail`), `src/seon/handlers/eval.cljs` +
`src/seon/render.cljs` (html branch rows for the new classes),
`src/seon/config.cljs` (+ host read) for
`:seon.config.render/error-head-token-cap`, `src/seon/error/instrument.cljc`
(JVM coercion-hint wording), tests. Gate: a deliberately deep
stacktrace failure renders a head ≤ cap tokens
(`seon.ai.tokens/estimate` asserted) ending in a live `result/<id>`
reference; the value-sample drill pages `[:seon.error.sci/frames]`;
eviction answers the honest recompute message; CLJS + writer gates
green.

Adjacent W3 items NOT in this design's scope (separate packages on the
W3 punch list): host-side run-fence CAS, output capture parity (sci
print vars → the `::output` seam), authored function invocation on the
host tier.

## 7. Open owner decisions

1. **Durable full-detail persistence:** live-only retention (this
   design; restart loses detail honestly) vs. also blobbing the detail
   map via `my.blob` with a `:seon.eval/error-detail` ref for
   post-restart forensics. Recommendation: live-only now; blob only if
   U12 forensics proves the need — the abridged head persists either
   way.
2. **Preflight auto-apply on the host:** pod parity applies proven
   unique-winner fixes; host proof is analyze-only (same strength as
   the pod's compile-only proof, weaker than running). Recommendation:
   apply, same class dials (`repair-class-on?` keys), because parity
   and because the fixed source still really evals and records.
   Decide only if the owner wants generated-code lanes to see raw
   failures instead of silent fixes (the `:seon.repair/fixes`
   annotation keeps it visible either way).
3. **Config key names** `:seon.config.instrument/enabled?` and
   `:seon.config.render/error-head-token-cap` — W1 owns the aero→fact
   sweep; these names land now as accessors so W1 sweeps them with the
   rest.
