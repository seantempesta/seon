---
type: research
status: proposed (lane clojure-way-opus, 2026-10-08)
tags: [vocabulary, architecture]
---
# The Clojure way: vocabulary audit, re-implementations, atom census, research agenda

Owner, 2026-10-08: "make sure we are doing things the clojure way and using proper vocab and names for other
libraries and references like git. do not pretend you are writing ruby/python/whatever and reuse those concepts."
Later the same day: "make sure we aren't using atoms everywhere ... Atoms are about managing access atomically and we
should be storing fully map specced data that is validated on every write or rejected." The owner then added that
"one per cluster" is not a hard rule. An atom may stay separate only with a seriously good justification.

Scope: `src/` (90,339 lines in `src/**/*.clj*`, against the 10,000-line goal), `resources/seon/schemas/` (225
files), `docs/seon/specs/`, `docs/seon/architecture/vocabulary.md`. Git vocabulary is already mapped in
`docs/research/agent-platform/2026-10-08/git-vocab/git-vocab.md` (owner approved). This note does not repeat it; it
only cites it. Reference revisions are the submodules pinned at HEAD `f159bf145`. A `file:line` into
`reference-code/` was read in this pass. Anything marked **UNVERIFIED** was not.

Blunt summary:
- Seon's core loop is clojure.main's `repl` with renamed hooks.
- Its reply is prepl's `:ret` message with renamed keys, printed by string concatenation.
- Its error map is `ex-triage` with `:clojure.error/phase` renamed to `:seon.error/layer`.
- Its "arm" is Malli's `instrument`, and its "projection" is a Malli registry.
- Its turn work is a hand-written state machine stored as a type tag (`:seon.turn.work/situation`).
- About 40 per-call atoms collect what a fold or a returned value would carry. No atom anywhere has a validator: `set-validator!` occurs zero times in `src/`.

## 1. Vocabulary audit

Legend:
- **KEEP**: already the standard name.
- **RENAME**: an established term names the same concept; the replacement is given.
- **JUSTIFIED**: genuinely ours; the reason is given.

Counts are `grep -rIiow` over `src` plus `resources/seon/schemas`, then over `docs/seon/specs`.

### 1.1 The REPL path

| Seon term | Count (src / specs) | Established term and source | Verdict |
|---|---|---|---|
| **pass**, **batch** (`seon.batch/evaluate-batch` src/seon/batch.clj:687) | 257 / 80 | One nREPL **`eval` message**: a code string whose forms are all evaluated, one `:value` per form, then `:status #{:done}` (reference-code/nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj:156-158, :202). clojure.main calls one iteration `read-eval-print` (reference-code/clojure/src/clj/clojure/main.clj:430). | **RENAME**: say "an eval request". Rename `seon.batch` to `seon.repl` (the stages are the REPL's hooks, §2.1), `evaluate-batch` to `eval-request`, and `:seon.batch/*` keys to `:seon.repl/*`. "Pass" disappears. |
| **stages** in the request: `:read-next`, `:evaluate`, `:show`, `:fault`, `:around` (batch.clj:5-25) | — | `clojure.main/repl` options `:init :read :eval :print :caught` (main.clj:368-410). nREPL's `eval` op also takes an `eval` var that replaces `clojure.core/eval` (interruptible_eval.clj:216). | **RENAME**: use `:read`, `:eval`, `:print` and `:caught`; `:around` becomes `:init` plus a binding frame. Agents know these names from `clojure.main`. |
| **turn** | 2115 / many | No Clojure term. In the LLM field it is standard: a provider exchange plus its evaluation. | **KEEP**. turn.clj still calls the turn entity "run" internally (`run-id`, `:seon.agent/run-id` turn.clj:5740, `episode-runs` in seon.turn.work.edn), which contradicts vocabulary.md's ruled meaning of "run". Retire those names. |
| **run** | many | vocabulary.md: a budget of turns, derived, never stored. | **KEEP** that meaning, and delete the internal "run means turn" spelling. |
| **call** (`:seon.turn/call`, provider call) | — | An HTTP request or a provider "message"; Clojure has no term. | **JUSTIFIED** as "provider call". In code it collides with "function call" (`:seon.fn/calls`), so in prose always write "provider call". |
| **settlement**, **settle** (batch.clj:735, the `:seon.batch/settle` stage) | 234 / 23 | Datahike `transact` returns a **tx-report**: `TxReport [db-before db-after tx-data tempids tx-meta]` (reference-code/datahike/src/datahike/db.cljc:130). | **RENAME**: "the recording transaction" and its tx-report. `:seon.batch/settle` becomes `:seon.repl/record`; `settled-into` becomes `with-tx-report`. |
| **receipt** | 533 hits; 195 in turn.clj alone | Banned by vocabulary.md line 12, yet still used: `:seon.turn.loop/receipt`, `:seon.maintenance.receipt`, and `seon.fault`'s docstring ("gets a receipt", src/seon/fault.clj:3). | **RENAME** to "tx-report" for a transaction's answer and "result" elsewhere. This is a standing-order violation and should be swept. |
| **envelope** | 3 / 4 | prepl's message map `{:tag :ret :val :ns :ms :form :exception}` (reference-code/clojure/src/clj/clojure/core/server.clj:199-212). | **RENAME** to "reply" (already the CLAUDE.md word) and shape it as prepl (§2.2). |
| **shown text**, `:seon.eval/shown`, `:seon.repl/value` | 52 / 8 | nREPL's `:value` is already the **printed** string (interruptible_eval.clj:157 with `::print/keys #{:value}`). prepl's `:val` is the value itself. | **RENAME** to "printed value", or just `:value` as in nREPL. "Shown" is a third word for one concept. |
| **handle** `result/e…` | 441 (mixed with "handler") | clojure.main `*1 *2 *3 *e` (main.clj:96, :438-447). SCI exports the same vars (reference-code/sci/src/sci/core.cljc:178 `*1`). Seon's SCI contexts do not bind them: no `*1` or `*e` appears in src/seon/sci or repl.clj. | **KEEP** `result/e<id>`, because it survives later forms and agents cite it in later turns. **Also bind `*1 *2 *3 *e`** in the agent's SCI context. Agents are trained on them, and their absence is a needless surprise. |
| **elision**, elision form (owner renamed "dig form" on 2026-10-08) | 82 / 5 | `*print-length*`/`*print-level*` in clojure.core, and nREPL's `*quota*` (reference-code/nrepl/src/clojure/nrepl/middleware/print.clj:33). Clerk names it **elision** too: `elision-viewer` with `:nextjournal/budget` (reference-code/clerk/src/nextjournal/clerk/viewer.cljc:952, :1043). | **KEEP** "elision" (Clerk precedent). `seon.render.value`'s docstring still says "dig form" (src/seon/render/value.clj:16, :28); fix it. The budget dial `seon.print/*budget*` matches nREPL's `*quota*` idea. Document that link. |
| **render function**, `:seon.render/ai` / `:html` | — | Clerk viewers `{:name :pred :transform-fn :render-fn :page-size}` (viewer.cljc:203-204, :916-933). Portal `:portal.viewer/default` metadata (reference-code/portal/src/portal/runtime.cljc:373). Also `clojure.datafy/datafy` and `nav` (reference-code/clojure/src/clj/clojure/datafy.clj:15, :30). | **KEEP** "render function"; the owner ruled it. The *selection* is Clerk's viewer model: the contract fit is `:pred`, the AI face is `:transform-fn`, the HTML face is `:render-fn`. Cite Clerk in the render spec. An elision form on `result/e…` is `nav` in practice; see §2.9. |
| **value printer**, print node | — | fipp's document and engine (reference-code/fipp/src/fipp/engine.cljc:247). Seon already uses `fipp.engine` (src/seon/print.cljc:5, :639). | **KEEP** "value printer". "print node" is fipp's **document** node; use fipp's word. |
| **finding**, `:seon.repl/findings` | 224 / 5 | clj-kondo `{:findings [...]}` with `:type :level :message :row :col` (reference-code/clj-kondo/src/clj_kondo/core.clj:49, :103). | **KEEP** the word. **Shape it as clj-kondo's**: Seon's finding is `{:seon.repl/finding kw :seon.repl/fix …}` (seon.repl.edn). Add `:level` and `:type` so agents and tooling read one finding grammar. |
| **diagnostic**, `:seon.batch/diagnostics` | 457 / 24 | prepl reports an exception as `{:tag :ret :exception true :val (ex->data ex phase)}` (server.clj:252-254). nREPL uses `:status #{:eval-error}` with `:ex`/`:root-ex` (interruptible_eval.clj:122-124). | **RENAME**: a diagnostic is an error map with a phase. Use "error" (`:seon.repl/errors`) and drop the extra word. |
| **fault** (`seon.fault`, 548 hits) | 548 / 30 | Clojure says "exception"/"error"; CLAUDE.md already says "the errors cut retires the separate fault concept". | **RENAME** to "recorded error". Delete `seon.fault` into `seon.error`. Already ruled and still undone. |
| **recorded error** | — | — | **JUSTIFIED**: an error stored once per signature with a count. Sentry calls it an "issue", but that word is taken here. |
| **opening** | 125 / 19 | No Clojure term. The owner ruled that context = static prefix ((help)) + history + current-state block, and that "opening-once ... likely disappear". | **DELETE** with the static-prefix cut. The prefix is `(help)`'s printed value. |
| **current-state block**, "status tail" | 0 / 10 | No Clojure term. | **JUSTIFIED** as one name only. Pick "current-state block" and retire "status tail". |

### 1.2 World, custody, supply, arming

| Seon term | Count | Established term and source | Verdict |
|---|---|---|---|
| **world**, `seon.batch/*world*`, `world-members` (batch.clj:39-63) | 472 / 19 | It *is* a dynamic Var bound per evaluation and conveyed to other threads. Clojure calls that **thread bindings**: `binding`, `get-thread-bindings`, `bound-fn*`, `binding-conveyor-fn` (reference-code/clojure/src/clj/clojure/core.clj:1960, :1968, :2015, :2043). | **RENAME**: "the evaluation's bindings". `with-world` becomes `bound-fn*` or `with-bindings`, the Clojure functions themselves. Today four overlapping words exist: world, environment, scope and context. Make it one: SCI ctx = context, and the dynamic frame = bindings. |
| **environment** (`seon.env`, `(defrecord Environment [])` src/seon/env.clj:36) | — | SCI's `:env` is the context's own atom (reference-code/sci/src/sci/impl/opts.cljc:306). "Environment" therefore already means SCI's internal state. | **RENAME** to "cluster state" (the one validated state map, §3.3). The SCI word must not be reused for a second thing. |
| **custody**, `call-with-custody` (src/seon/db.clj) | 187 / 6 | git-vocab.md: "checked-out branch". | **RENAME** in agent-facing text (git-vocab already did). Internally, `call-with-custody` becomes `with-branch`. |
| **supply**, supplied defaults (seon.call-preparation, 1130 lines) | 94 / 0 | Malli **`:default`** and `default-value-transformer`, which fills missing keys from declared defaults (reference-code/malli/src/malli/transform.cljc:484; `default-fn` at :486). Supplying by schema name is a `default-fn` keyed on the schema's name. | **RENAME** to "defaults", and research §4 item 3: Malli's transformer may replace most of call-preparation. |
| **arm**, **armed**, arming | 709 / 9 | Malli **instrument**: `malli.instrument/instrument!` (reference-code/malli/src/malli/instrument.clj:152) and `m/-instrument` with `:scope #{:input :output :guard}` and a `:report` hook (reference-code/malli/src/malli/core.cljc:3166-3186). | **RENAME**: "instrument" and "instrumented". Seon already requires `malli.instrument` (src/seon/instrument.clj:15) and calls `mi/-f->original`; then it builds 1,335 lines of its own wrappers. |
| **contract** | — | Malli **function schema** `:=>` / `:function` (`m/function-schema` core.cljc:3130). | **KEEP** "contract" in prose (CLAUDE.md law), and say "function schema" wherever Malli's API is meant. |
| **projection** (2,815 bare uses; `projection-from-database` src/seon/schema.clj:3257; `projection-registry` :589 returns `malli.registry/registry?`) | 3,331 | **Malli registry** (`malli.registry`), plus the derived Datahike schema. | **RENAME**: "registry" for the Malli part and "database schema" for the Datahike part. The most-used invented noun in the codebase names a Malli registry. |
| **admission**, admit | 1,500+ | Malli `m/validate`/`m/explain` against the registry. "Admission" also names schema-entry policy (`:gate`/`:warn`). | **RENAME** value admission to "validation". **KEEP** "admission" only for the entry policy (`:gate`/`:warn`), which is a decision rather than a validation. |
| **gate**, gate set (`seon.fn/gate-set`) | 116 / 4 | clojure.test has no word. Kaocha calls the selected tests a **test plan** (reference-code/kaocha/src/kaocha/testable.clj:233). | **RENAME** "gate set" to "the tests reaching the change" (owner prose) and "gate" (entry) to "entry policy". "Gate" means three things today. |
| **reach**, reaching test | 321 / 4 | No library term; a call-graph closure. | **KEEP** "reaching tests" (owner-used). |
| **proof** | 54 / 25 | No Clojure term; the owner's word for a task's success tests. | **KEEP** (owner ruling). In Clojure terms, the deftests naming the task in `:seon/tasks`. |
| **member** (`:seon.test.member/*`, test run member) | 1,085 | clojure.test: **test var** (`test-var`, `:begin-test-var` report type). Kaocha: **testable** (testable.clj:142). | **RENAME** `:seon.test.member/*` to `:seon.test.run/var`, or say "test var". |
| **accretion** test (`:seon.test.accretion/*`) | — | Malli **`malli.generator/check`** and `function-checker` (reference-code/malli/src/malli/generator.cljc:540, :572). Spec's `stest/check`. | **RENAME** to "generative check" and reuse `mg/check`. "Accretion" is Rich Hickey's word for the *change*, not the test of it. |

### 1.3 Program, branches, reload

| Seon term | Established term and source | Verdict |
|---|---|---|
| **base context**, agent context, **fork** | SCI `init` and `fork` (reference-code/sci/src/sci/core.cljc:320, :334). vocabulary.md cites :331 and :345, which are **stale lines**. | **KEEP**. Fix the stale line numbers. |
| **overlay** (sparse config, `agent-overlay`) | No Clojure noun; it is `merge` over config maps. | **RENAME** to "config overrides" (merged with `merge`), or **JUSTIFIED** if the owner keeps it. Low priority. |
| **fold** (batch, "per-call accumulation is a fold") | Clojure `reduce` and transducers. | **KEEP** in prose (CLAUDE.md law). Code should *be* a `reduce`: today `evaluate-batch` is a `loop` with a `volatile!` result (batch.clj:713-722). |
| **adoption** ("entities → load → arm → record") | Clojure `require :reload` (core.clj:6070); clj-reload `reload` (reference-code/clj-reload/src/clj_reload/core.clj:334); tools.namespace `refresh` (**UNVERIFIED**: not vendored). | **RENAME** to "reload" (vocabulary.md already rules reload as `require :reload`). |
| **publication** (archive of HEAD → digested inputs) | Build output, a classpath "artifact" (tools.build; **UNVERIFIED**, not vendored). | **JUSTIFIED** only as the operator-scope build step. Never use it for a program change. |
| **write-back** | git-vocab.md: no exact git term. | **KEEP** (git-vocab ruled). |
| **wake**, inside wake | Datahike `listen` delivers a **tx-report** to a callback (datahike/api/specification.cljc:1141; Seon calls it at src/seon/cluster/wake.clj:527). A wake is "a tx-report touching a listened attribute that the agent has not answered". | **JUSTIFIED** as a derived notion, but define it in Datahike terms: a listened datom whose tx `t` is greater than the agent's answered `t`. |
| **answered basis**, `:seon.agent/answered-t` | Datomic `basis-t` (**UNVERIFIED** here: Datomic is not vendored). Datahike exposes `:max-tx` (datahike/src/datahike/db.cljc:999). | **KEEP** `answered-t`. In prose say "basis t", Datomic's word. |
| **disposition** (`:seon.turn/disposition :completed/:wait`, seon.turn.edn:44) | It is the agent's **return value** from `my.turn/wait` / `complete!`. | **RENAME**: store the call (`:seon.turn/ended-by 'my.turn/wait`), or derive it. A disposition enum is a kind. |
| **situation** (`:seon.turn.work/situation` `:open :generate :call :resume :close`, seon.turn.work.edn `:next`; stored on the turn entity at src/seon/turn.clj:415, :854) | core.async.flow's `:transform` step-fn returns `[state outputs]` (reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:291-302). Lifecycle transitions are flow's `::flow/resume`/`::flow/pause` (flow.clj:237). | **DELETE**: a stored state-machine tag (§2.4). |
| **roster** (agent roster, profile roster) | `registry` (Malli and Datahike both say registry); vocabulary.md itself lists "roster" as a legacy word for candidates. | **RENAME** to "registry" where it is a keyed map of live handles. |
| **request** (execution request, agent request) | Ring and nREPL say **message**/**request** for the same thing. | **KEEP**. |
| **norms**, merge request, merge branch, ours/theirs | git-vocab.md (approved). | **KEEP** as approved. |

### 1.4 Type tags ("no kinds", owner ruling)

These discriminator enums are counted in `src` plus `resources`. Each is a kind or a stored state tag. Under the owner's ruling, "positive data recorded in the database not a type system", each must justify itself or become presence-of-data plus a specific Malli schema:
- `:seon.error/layer` (304); duplicates `:clojure.error/phase` (main.clj:211).
- `:seon.error/type` (91); kept: it is the `:multi` dispatch CLAUDE.md rules.
- `:seon.turn.work/situation` (45)
- `:seon.turn.loop/outcome` (23)
- `:seon.health/severity` (23); clj-kondo `:level` is the precedent.
- `:seon.task/kind` (21); git-vocab already deletes it.
- `:seon.instrument.lookup/status` (18)
- `:seon.step/status` (17); the owner ruled steps are replaced by tasks.
- `:seon.test.failure/type` (14); this one is clojure.test's own `:fail`/`:error`. **KEEP**.
- `:seon.turn.loop/phase` (13)
- `:seon.test/class` (13)
- `:seon.task/outcome` (13)
- `:seon.program/kind` (13)
- `:seon.health/state` (12)
- `:seon.eval/outcome` (11)
- `:seon.batch/stage` (11)
- `:seon.batch/mode` (11)
- `:seon.cache/kind` (9); a cache region name, which is fine.
- `:seon.agent/mode` (9); git-vocab: derive it as `(= branch main)`.
- `:seon.cluster.boot/disposition` (9)

## 2. Where Seon re-implements, non-Clojure-style, what Clojure or a library already does

Ranked by agent-loop impact.

1. **The REPL loop is `clojure.main/repl` with renamed hooks.**
   - `seon.batch` (761 lines) takes a request map of stages: read-next, evaluate, observe, derive, facts, apply, fold, settle, show, fault, around (src/seon/batch.clj:5-25).
   - `clojure.main/repl` takes `:init :read :eval :print :caught` (main.clj:368-410). nREPL lets a message name its own `eval` var (interruptible_eval.clj:216).
   - The Seon-specific additions (derive, facts, apply, record) belong in `:eval` and one `:caught`-shaped error path.
   - The loop is a `loop` over a `volatile!` (batch.clj:713) rather than a `reduce` over forms.
   - Reuse: keep Clojure's hook names and the `*1`/`*e` contract, and make the fold a `reduce`.
2. **The reply is prepl's `:ret` message, built by string concatenation.**
   - `seon.repl/reply-map` builds `"#:seon.repl{" … ":" (name k) " " text … "}"` with `str` (src/seon/repl.clj:275-296). That is a second printer, which CLAUDE.md forbids ("Writing a new printer ... is refused in review").
   - prepl already defines the data: `{:tag :ret :val :ns :ms :form :exception}`, plus `:out`, `:err` and `:tap` messages (server.clj:199-212).
   - nREPL defines `:value :ns :out :err :ex :root-ex :status` (interruptible_eval.clj:122-158).
   - Reuse: the reply is a map with prepl's keys, namespaced if you like: `:val :ns :ms :form :out :err`, plus Seon's `:result` handle and `:findings`. It goes through the one value printer as data.
3. **The error map is `ex-triage` renamed.**
   - `:seon.error/layer` (304 uses) is `:clojure.error/phase`, whose phases are `:read-source :compile-syntax-check :macroexpansion :execution :print-eval-result` (main.clj:207-266).
   - The cause chain is `Throwable->map`'s `:via`/`:trace`/`:cause` (reference-code/clojure/src/clj/clojure/core_print.clj:473).
   - seon.error is 2,810 lines. It has its own `explain-problem`, `problem-sentence` and `edit-distance` (src/seon/error.clj:915, :990, :1920).
   - Malli has `me/humanize`, `me/with-spell-checking` (reference-code/malli/src/malli/error.cljc:374, :339) and `malli.dev.virhe` / `malli.dev.pretty` for printing.
   - The code also has **197 `catch Throwable`/`catch Exception` sites** against CLAUDE.md's three catches, and 54 local `refuse!`/`refused` helpers.
   - Reuse: `Throwable->map` → `ex-triage`, with `:clojure.error/phase` kept verbatim. Malli's humanize and spell-checking replace the hand-written problem text.
4. **The turn loop is a stored state machine.**
   - `:seon.turn.work/situation` ∈ `#{:open :generate :call :resume :close}` is written onto the turn entity (src/seon/turn.clj:415, :854). It is read back with `(= :generate (:seon.turn.work/situation run))` (turn.clj:684, :818, :2398, :2875, :5055). Form progress is tracked by a seven-state enum `:unevaluated … :owner-declared-cant` (seon.turn.work.edn).
   - turn.clj is 6,136 lines with 189 top-level defs.
   - core.async.flow already owns a proc's state: `:transform (fn [state in msg] [state' outputs])` (flow.clj:291-302).
   - Datahike already says what happened: an open turn is one with no `:closed-tx`; a turn with a provider attempt but no evaluations is generating.
   - Reuse: derive "what next" as a pure function of the db value, keep in-flight bookkeeping in the proc's state, and delete the stored situation.
5. **Instrumentation is a parallel Malli.**
   - seon.instrument is 1,335 lines and seon.call-preparation 1,130. Together they build wrappers, arglist plans and a "caller frame" by stack walking (instrument.clj:130).
   - Malli's `-instrument` takes `:scope` and a `:report` hook (core.cljc:3166-3186). `mi/instrument!` collects from `:malli/schema` metadata (instrument.clj:152).
   - Defaults can be filled by `mt/default-value-transformer` (transform.cljc:484) before validation.
   - Reuse: `m/-instrument` with a `:report` that builds the Seon error map, plus a transformer for defaults. Research how much of the two namespaces that leaves (§4 item 3).
6. **"Projection" is a Malli registry with its own caches.**
   - schema.clj is 4,257 lines. It has `projection-registry` (:589) and `structural-registry` (a `reify mr/Registry`, :1308). It also has `projection-cache-value` over `clojure.core.cache` (:432).
   - db.clj carries a second LRU tier (`projection-cache`, `value-projection-cache`, src/seon/db.clj:1451, :1457).
   - Malli: `mr/composite-registry`, `mr/fast-registry` and `mr/lazy-registry` (malli/registry.cljc). The compiled schema caches its own validator: `m/validator` caches via `-cached` (core.cljc:2672-2678).
   - Reuse: one Malli registry value per program commit, held in `seon.cache` by commit id.
7. **Two cache mechanisms where CLAUDE.md allows one.**
   - `clojure.core.cache.wrapped` is required in src/seon/test.clj:4, call_preparation.clj:37, db.clj:6, schema.clj:24 and sci/eval.clj:99.
   - It runs as process-global LRUs: db.clj:1451 and :1457, sci/eval.clj:3095 and :3338, call_preparation.clj:402, test.clj:416, schema.clj:420 and :2052.
   - `memoize` appears at fn.clj:3485 and :3660, db.clj:4177 and :5241, test.clj:1389, test/runner.clj:1742 and fn/analyzer.clj:258. That is all beside `seon.cache` (Caffeine, src/seon/cache.clj).
   - core.cache is the Clojure library and Caffeine is the JVM one. Pick **one**. CLAUDE.md picks `seon.cache`, so the core.cache uses are the defect. core.cache's source is not vendored (**UNVERIFIED** line numbers).
8. **Reload ordering is hand-rolled.**
   - `reload-order` is a hand-written Kahn topological sort over stored `:seon.ns/requires` (src/seon/cluster.clj:1860-1881). `load-development-definitions!` then calls `require :reload` per namespace (cluster.clj:1907-1919).
   - clj-reload has `topo-sort` (reference-code/clj-reload/src/clj_reload/parse.clj:163), `unload` and `reload` (core.clj:304, :334), and `:unload-hook` (core.clj:154).
   - It is vendored but **not on deps.edn**.
   - Reuse: clj-reload's `topo-sort`, or the whole `reload` with its `:only` option. Research whether its file-scan model fits "reload from program entities" (§4 item 6).
9. **Value printing invents "print nodes" over fipp.**
   - `seon.print` (856 lines) plus `seon.render.value` (1,821) walk into fipp ops by hand (print.cljc:403-405, :619).
   - fipp's visitor protocol and `pretty-coll` honour `*print-length*`/`*print-level*` (reference-code/fipp/src/fipp/edn.cljc:9, :95).
   - puget adds `:print-handlers` by type (reference-code/puget/src/puget/printer.clj:744).
   - Clerk solved budgeted, breadth-first elision with `:page-size`, `:nextjournal/budget` and `elision-viewer` (viewer.cljc:888-952, :1043).
   - This one is defensible because the owner's breadth-first budget is not fipp's. Still, research Clerk's `present` before growing seon.render.value further.
10. **Flow internals are reached into.**
    - `seon.flow` requires `clojure.core.async.flow.impl.graph`, `flow.spi`, `core.async.impl.channels`, `impl.dispatch` and `impl.protocols` (src/seon/flow.clj:11-15). It reifies `WritePort` (:314) and a `ProcLauncher` (:179).
    - A `CountedSlidingBuffer` deftype sits in cluster/agent.clj:107.
    - These are impl namespaces with no compatibility promise. The public API is `create-flow`, `start`, `process`, `inject`, `ping` and `map->step` (flow.clj:76-302).
11. **Test reporting.** This is mostly done right: `seon.test.runner` rebinds `clojure.test/report` and `*report-counters*` (src/seon/test/runner.clj:405-410). The defects are what it wraps around that:
    - "member", "accretion", "selection disposition" and claim/worker facts (`:seon.test.member/worker`, `claim-tx`);
    - it builds a parallel run ledger, where kaocha's testable and result model (`:kaocha.result/pass`, `:fail`, `:error`; reference-code/kaocha/src/kaocha/result.clj:42) is the established data shape.
12. **Circular namespaces broken by `requiring-resolve` delays.**
    - 114 `requiring-resolve` calls and 38 `defonce … (delay (requiring-resolve …))` cycle-breakers, e.g. src/seon/db.clj:52-66, schema.clj:42-58, repl.clj:27-32 and render/value.clj:65-73.
    - Clojure's way is a namespace DAG: pass the function as an argument, or move the shared function down.
    - Every one of these is a hidden dependency that clj-kondo, the reload order and agents cannot see.
13. **Messages built with `str`.** There are 582 `(str "` sites and 28 `(ex-info (str …))`. The owner ruled "errors through the value printer ... no pr-str/str-built messages". batch.clj:735-751 builds `"settle: the recording was not committed: " …` this way.

## 3. Atom census

Method: grep `src/` for `atom`, `volatile!`, `ref`, `agent`, `memoize`, `ConcurrentHashMap`, `Atomic*`, `LongAdder`, `IdentityHashMap`, `WeakHashMap`, `ArrayList.` and `set-validator!`. The raw list is 244 hits plus 3 multi-line `memoize`s.

**`set-validator!` appears zero times in `src/`. No atom in Seon is validated on write.** The one partial exception is `seon.env/replace-environment!`, which checks `environment?` before `reset!` (src/seon/env.clj:106-119). Anyone may still call `reset!`/`swap!` directly on that atom, so it is not enforced.

### 3.1 Process-global holders (`defonce`), all unvalidated

| Site | State | Writer | Right owner |
|---|---|---|---|
| src/seon/sci/eval.clj:1017 `loaded-identity (atom {})` | the loaded program commit per root (adoption detection) | adoption (`call-with-program-change`, cluster.clj:1923) | **Process state map**, key `:seon.process/loaded-commit`. The value is per-JVM by definition (one set of compiled Vars), so it cannot be a database fact. |
| src/seon/schema.clj:1026 `!fallback-counts (atom {})` | counts of projection fallbacks | schema | **Delete**: a counter for diagnosis. Use `seon.profile` or record a finding. |
| src/seon/sci/kernel.clj:115 `memory-pressure-state (volatile! nil)` | last GC-notification state | JMX listener thread | **Process state map**. A `volatile!` shared across threads is a **correctness defect**: volatile only guarantees visibility, and it gives no atomicity. |
| src/seon/sci/kernel.clj:644 `pending-work` (ConcurrentHashMap) | evaluation id → owned in-flight futures count | kernel `own-pending!` | **Process state map**, or the agent's Flow proc state. It is keyed by evaluation, which the proc owns. |
| src/seon/fault.clj:89 `folded` (ConcurrentHashMap) | per-(branch, occurrence) repeat counts awaiting commit | `seon.fault` | **Database facts** (the occurrence count is already an attribute, `:seon.error/occurrence-count`) and the errors cut. Delete it with `seon.fault`. |
| src/seon/profile.clj:127 `roster` (ConcurrentHashMap of LongAdder histograms) | per-function call counters | every instrumented call | **KEEP** (see the justification in §3.4). |
| src/seon/cache.clj:44-60 stats (ConcurrentHashMap of LongAdder) | hit/miss/eviction counters per cache region | `seon.cache` | **KEEP** inside `seon.cache` (Caffeine's own counters are the alternative: `recordStats`). |
| src/seon/db.clj:1451, :1457; sci/eval.clj:3095, :3338 (core.cache LRUs) | registry/projection and program-identity memos | readers | **`seon.cache`** (§2 item 7). |
| src/seon/cluster.clj:2152 `launched-source-digests`; cluster/source.clj:157 `launched-dependencies` | immutable launch-time facts (delays) | once at load | **KEEP** as `delay`s: they are values, not places. |
| src/seon/render/web.clj:138 `generator-mult` | a test generator's shared mult | once | **KEEP** (test-only value). |
| src/seon/cluster/store.clj:669 `branch-open-monitor (Object.)` | a lock object | `locking` | **Process state map** carries no lock. Datahike's writer serializes; verify whether this lock is needed at all (**UNVERIFIED**). |
| 38 `defonce (delay (requiring-resolve …))` | function handles that break namespace cycles | — | **Delete**: fix the namespace DAG (§2 item 12). |

### 3.2 Per-cluster and per-agent holders created at runtime

| Site | State | Writer | Right owner |
|---|---|---|---|
| src/seon/env.clj:71, :104 `environment-state` → `(atom environment)` | the cluster's environment, including its current projection | `replace-environment!`, `advance-projection!` | **This becomes THE cluster state atom** (§3.3), validated with `set-validator!`. |
| src/seon/cluster/agent.clj:621 `(atom {:seon.agent/armed {} :seon.agent/channels {} :seon.agent/contexts {}})` (agent routing) | live handles per branch and agent: armed graphs, channels, SCI contexts | agent acquisition and retirement | **Cluster state map**, key `:seon.cluster/agents`. This is CLAUDE.md's "one roster per scope". |
| src/seon/cluster.clj:3426-3431 `latest-packages`, `render-interest`, `:seon.render.web/registration` (3 atoms) | web UI's latest package per tab, interest filter, SSE registrations | web proc, SSE handlers | **The web Flow proc's state** for packages and interest. Registrations go in the **cluster state map** under `:seon.cluster/web`. |
| src/seon/flow.clj:673-675 `active-work`, `io-submissions`, `accepting?`; :792-794 `::status`, `::active?`, `::task` (3 atoms per submission) | the I/O launcher's queue and per-task lifecycle | flow launcher | **The Flow proc's state**: this hand-built executor is what `flow/process` with `:workload :io` already does (flow.clj:269). |
| src/seon/cluster/agent.clj:1304 `turn-backstop-state`; :1101 `allocated`; :1334, :536 AtomicReference `turn-thread` | the turn proc's backstop, an allocation sample, and the running thread | agent proc | **The agent's Flow proc state**. The thread reference is the one justified place: interrupt needs the live Thread. |
| src/seon/turn.clj:5740 `:seon.agent/run-id (atom run-id)` | current turn id for the fault path | turn proc | **The Flow proc state**, or pass the value. The comment beside it (turn.clj:5736-5739) documents a race this atom caused. |
| src/seon/cluster/agent.clj:134, :596 `(atom 0)` in CountedSlidingBuffer | dropped-message count | buffer | **Delete** with the deftype; count drops in the proc. |
| src/seon/sci/eval.clj:3052-3059 (5 atoms per agent fork: `::base-bindings`, `::result-objects`, `::kernel/installed-functions`, `::kernel/program-snapshot`, `::print-session`); :2961-2967, :3223-3225, :3567-3569 (copies); :419-420 (2 on the base) | per-agent private state beside SCI's own `:env` atom | evaluation | **The SCI context.** SCI's ctx already *is* one atom (`:env`, reference-code/sci/src/sci/impl/opts.cljc:306; `fork` makes a new one, core.cljc:334-340). Put these under one namespaced key of the agent's own state map, and move `::result-objects` to **`seon.cache`** (owner ruling: "held results go through seon.cache as an LRU"). |
| src/seon/sci/eval.clj:4179 `print-session`; :4201 `projection-state` | session print options; per-evaluation registry | evaluation | Print options become **database facts** on the agent (the session), and the registry comes from the **cluster state**. |
| src/seon/schema.clj:3661 `:seon.schema.delta/candidate-forms (atom before)` | schema registrations during one eval request | SCI write hook | **A value folded through the request** (§3.5). |
| src/seon/sci/kernel.clj:329-336 `armed` map: AtomicLongs `entries`, `host-interop-observations`, `adopted-allocated-bytes`; AtomicBooleans `reached`, `travelled`; `(atom #{})` `built-in-calls`; `(volatile! nil)` `outcome` | per-evaluation interrupt counters, read by SCI's `:interrupt-fn` on another thread | interrupt-fn, deadline timer | **KEEP** the AtomicLong and AtomicBoolean fields (§3.4). Make `built-in-calls` and `outcome` returned values. |
| src/seon/batch.clj:336-337 `:seon.batch/reads (atom [])`, `:in-flight` AtomicLong; :99 sink ConcurrentHashMap; src/seon/turn/world.clj:59, :87 `stamped-ns` AtomicLong | read evidence and call-profile sinks per form | Datahike reads on any thread inside the eval | Reads are **folded** (§3.5). Keep `in-flight` and the profile sink with the counters justification (§3.4). |
| src/seon/test.clj:214, sci/eval.clj:4260 `:seon.effect/counter (atom -1)` | effect ordinal within an evaluation | effect calls | **A value folded through the request** (the ordinal is the index of the effect in the request's effect vector). |
| src/seon/cluster/registry.clj:568-569 `heads`, `key-count` | GC accounting during `gc-storage` | GC callback | **Local `volatile!`** (one call). If the callback runs on another thread, an atom it stays: "per-call accumulation is a fold" whenever Datahike lets you fold. **UNVERIFIED** which thread runs it. |

### 3.3 Per-call atoms used as accumulators (about 40)

All of these collect calls, reads or entries during one render or evaluation and are discarded at its end:
- turn.clj:1764-1765 (`calls`, `invocations`)
- render.clj:1586 (`captured`)
- render.clj:1713, :1719 (`:seon.render/cache (atom scope)`)
- render/walk.clj:405
- render/web.clj:485, :486, :1380, :1459, :1559, :1704, :1936, :2051, :2493, :2494
- health.clj:121
- test.clj:2448 (`observed`)
- test/runner.clj:403-404 (`capture`, `reported-signatures`)
- fn/analyzer.clj:576 (clj-kondo's own `*reader-exceptions*`: keep, the dependency requires it)

Verdict:
- When the producer is a callback the code does not own, such as Datahike's read observer or clojure.test's `report`, a local accumulator is the honest Clojure answer. It should be a **local `volatile!` when single-threaded**, and stay an atom only when the callback crosses threads.
- Otherwise the producer should *return* what it observed (`[value observations]`) and the caller `reduce`s.
- `clojure.test`'s binding of `*report-counters*` to a `ref` (test/runner.clj:407, sci/eval.clj:304) is clojure.test's own contract. **KEEP.**

Local `volatile!`s inside one call are allowed by the owner's rule and are not listed one by one: sci/forgive.clj:136-145, batch.clj:384-387, :713, test.clj:239, :2025-2060, db.clj:4121, fn.clj:3509 and the rest of the 244 hits. The `memoize`s inside one call over one database value are the same: fn.clj:3485, :3660; db.clj:4177, :5241; test.clj:1389; test/runner.clj:1742. They are fine as local memos. `fn/analyzer.clj:258` memoizes globally: move it to `seon.cache`.

### 3.4 Atoms to keep, each justified

| Holder | Why it is not db facts, Flow proc state, the SCI context, seon.cache or the cluster state map | What merging it would break |
|---|---|---|
| `seon.profile` counters (profile.clj:127, 143-149: LongAdder histograms per instrumented function) | Every instrumented call on every thread increments one; a database write or a `swap!` on a shared map per call would serialize all calls. | Merging into a state map makes every function call contend on one CAS: a measured hot path turns into a global lock. LongAdder exists for exactly this (striped counters). |
| `seon.cache` stats (cache.clj:44-60) | Same: per-lookup counters on the cache's own hot path. Caffeine's `recordStats` could replace them. | Same contention. Research whether Caffeine's own `CacheStats` suffices, then delete. |
| SCI interrupt counters (kernel.clj:329-336 AtomicLong and AtomicBoolean) | SCI calls `:interrupt-fn` on every fn entrance from the evaluating thread, while the deadline timer sets `reached` from another thread. Neither thread can swap a map per fn entry. | Per-fn-entry `swap!` on a map makes interpreted code markedly slower, and a lost update could miss an interrupt. |
| The running `Thread` reference (cluster/agent.clj:1334) | interrupting needs the live Thread object, which has no data form. | None: it is the one live host object in the proc's roster, as CLAUDE.md allows. It can live *inside* the proc state as an AtomicReference value. |
| clojure.test's `*report-counters*` ref | clojure.test's own API requires a ref. | Breaks clojure.test. |

Everything else defaults to the owner's rule: a specced map, validated on every write.

### 3.5 Target design

**Counts.**
- One validated state atom per **process**.
- One per **cluster**.
- Per agent: state lives in its Flow proc and its SCI context, not in atoms.
- No per-call atoms, except where a callback the code does not own needs one.
- Plus the four justified counter families above.
- From roughly 70 atom/mutable holders today, that leaves 2 state atoms and 4 counter families.

**Shape.** The state map has `set-validator!` installed with a compiled Malli validator, so an invalid `swap!` throws `IllegalStateException` and the atom keeps its old value. Clojure's own contract makes that a rejection: `set-validator!` is enforced on every `reset!`/`swap!`. Every writer goes through one function per key that names the change.

```clojure
;; resources/seon/schemas/seon.cluster.state.edn
#:seon.cluster.state
{:state
 [:map {:closed true}
  [:seon.cluster/name :seon.cluster/name]
  [:seon.cluster.state/registry :seon.schema/registry]   ; Malli registry value at a commit (today "projection")
  [:seon.cluster.state/basis-t :seon.db/basis-t]          ; the registry's database t
  [:seon.cluster.state/base-ctx :seon.sci.eval/ctx]       ; the shared incremental base (owner ruling)
  [:seon.cluster.state/agents                             ; today the routing atom
   [:map-of :seon.agent/id
    [:map {:closed true}
     [:seon.agent/branch :seon.store/branch]
     [:seon.agent/graph :seon.flow/graph]
     [:seon.agent/ctx :seon.sci.eval/ctx]]]]
  [:seon.cluster.state/web
   [:map {:closed true}
    [:seon.render.web/registrations [:map-of :string :seon.flow/channel]]]]]}

;; process: one per JVM
#:seon.process.state
{:state
 [:map {:closed true}
  [:seon.process.state/loaded-commit [:map-of :seon.cluster/name :seon.source/commit-id]]
  [:seon.process.state/memory-pressure {:optional true} :seon.health/memory-pressure]
  [:seon.process.state/pending-work [:map-of :seon.eval/id :int]]]}
```

```clojure
(defn state
  "A new cluster state atom whose every write is validated against `:seon.cluster.state/state`."
  {:malli/schema [:=> [:cat :seon.cluster.state/state] :seon.cluster.state/atom]}
  [value]
  (doto (atom value) (set-validator! (m/validator :seon.cluster.state/state))))
```

Every `swap!` validates the whole map. That is linear in the map's top-level keys, not in its handles: Malli's `:map-of` validates entries, and the `:ctx`/`:graph` leaves are predicates. If measurement shows the validation is hot, validate only the changed key in the writer function. Measure before deciding.

### 3.6 Slices

Each slice converts every caller and proves itself with a data assertion through the one test runner.

1. **Cluster state atom.**
   - Change: `seon.env/environment-state` becomes `seon.cluster.state/state` with `set-validator!`. The routing atom (cluster/agent.clj:621) and the web registration atom (cluster.clj:3431) fold in as keys.
   - Proof: `seon.cluster.state-test/an-invalid-write-is-refused-and-the-state-is-unchanged` asserts `(= before @state)` after a bad `swap!`. The existing host tests (`seon.cluster.host-test`) must stay green.
   - Size: about 2 hours, an Opus lane (boot-critical).
2. **Process state atom.**
   - Change: `loaded-identity`, `memory-pressure-state` and `pending-work` move into the process state atom; delete `!fallback-counts`.
   - Proof: `seon.process.state-test/loaded-commit-is-one-validated-key` asserts the commit id after a reload on a branch.
3. **SCI per-agent atoms.**
   - Change: the five per-fork atoms (sci/eval.clj:3052-3059) collapse into one namespaced map inside the forked ctx, and `::result-objects` moves to `seon.cache`.
   - Proof: an existing `seon.sci.eval` result-handle test asserts that `result/e…` resolves after a fork and that the base ctx holds no agent key.
4. **Flow proc state.**
   - Change: flow.clj:673-675 and :792-794, cluster/agent.clj:1101 and :1304, turn.clj:5740, plus the CountedSlidingBuffer atom, become proc state returned from `:transform`.
   - Proof: the agent-loop tests assert, as data, the turn's facts after one model call. There must be no surviving `atom` in those namespaces; the clj-kondo lint rule in slice 7 checks it.
5. **Effect counter and schema-delta atoms.**
   - Change: they become values folded through the eval request (`:seon.effect/counter`, `:seon.schema.delta/candidate-forms`).
   - Proof: `seon.repl-differential-test` (owner-approved REPL plan) asserts that a register-then-write request records both.
6. **Per-call accumulators.**
   - Change: return observations instead of accumulating them, or use a local `volatile!` where the code is single-threaded (render.clj, render/web.clj, turn.clj:1764).
   - Proof: render tests assert the captured calls in returned data.
7. **Guard.**
   - Change: a clj-kondo hook or lint (`:discouraged-var {clojure.core/atom …}` with an allow-list of the six justified sites) so a new atom fails lint with its reason.
   - Proof: `seon.contracts-compile-test` plus a lint fixture.

## 4. Research agenda, ranked by impact on getting agents running reliably

| # | Question | Why it matters for the agent loop | Prior art to read | Decision it feeds | Size |
|---|---|---|---|---|---|
| 1 | Can the agent's reply be exactly prepl's message (`:tag :ret :val :ns :ms :form :exception` plus `:out`/`:err`), carried as data through the one value printer, with `*1 *2 *3 *e` bound in SCI? | Every model and outside agent reads this reply every turn. Today it is a string-built map (repl.clj:275) with invented keys, which agents repeat and misread. | reference-code/clojure/src/clj/clojure/core/server.clj:194-262; reference-code/clojure/src/clj/clojure/main.clj:368-466; reference-code/nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj:100-230, print.clj:33-80; reference-code/sci/src/sci/core.cljc:178 | the reply schema `:seon.repl/*`; the deletion of `reply-map`; the REPL-plan slices | S: 1 day |
| 2 | Can the eval request be `clojure.main/repl`'s hook map (`:init :read :eval :print :caught`) as a `reduce` over forms, with Seon's recording inside `:eval`/`:caught`? | This is the one REPL path. Fewer invented stages mean fewer places a failure wedges a value. | reference-code/clojure/src/clj/clojure/main.clj:368-466; nREPL `eval` override interruptible_eval.clj:216; reference-code/sci/src/sci/core.cljc:390-420 (`parse-next`, `eval-form`) | the shape of `seon.batch` → `seon.repl`; the rename of pass/batch/settle | M: 2-3 days, Opus |
| 3 | How much of seon.instrument plus seon.call-preparation (2,465 lines) does Malli's `-instrument` with a `:report` hook, plus `default-value-transformer` for supplied defaults, replace? | Every contracted call goes through these wrappers. They are the hot path and the source of the "armed" vocabulary and its failures. | reference-code/malli/src/malli/core.cljc:3109-3210; reference-code/malli/src/malli/instrument.clj:1-200; reference-code/malli/src/malli/transform.cljc:484-520 | "arm" → "instrument"; deletion plan for both namespaces | M: 2 days to answer |
| 4 | Can errors be `Throwable->map` → `ex-triage` data with `:clojure.error/phase`, Malli `humanize`/spell-checking for contract problems, and three catches? | Errors reach the agent every turn. 197 catch sites and a 2,810-line error namespace are where messages get long and wrong. | reference-code/clojure/src/clj/clojure/main.clj:207-345; reference-code/clojure/src/clj/clojure/core_print.clj:473-505; reference-code/malli/src/malli/error.cljc:288-400; reference-code/malli/src/malli/dev/virhe.cljc | the errors cut (`:seon.error/layer` → phase); the error spec | M |
| 5 | What does the turn loop look like as one core.async.flow proc whose `:transform` returns `[state outputs]`, with "what's next" derived from the db, and no stored situation or disposition? | Agents hang or double-run when stored state and facts disagree; turn.clj is 6,136 lines. | reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:76-310; reference-code/core.async.flow-monitor; Datahike `listen` (datahike/api/specification.cljc:1141) | the agent-loop spec; the deletion of `:seon.turn.work/situation`; the Flow-state target for §3 | L: 1 week; the spec first |
| 6 | Atom census execution: is a `set-validator!`-guarded state map per cluster and per process fast enough on the swap path, and what remains outside it? | Unvalidated shared places are where agents' worlds silently diverge (the race comment at turn.clj:5736 is one). | Clojure `set-validator!` (reference-code/clojure/src/clj/clojure/core.clj:2410); SCI ctx `:env` (reference-code/sci/src/sci/impl/opts.cljc:306, core.cljc:334) | §3 slices 1-7 | M |
| 7 | Can one Malli registry value per commit (in `seon.cache`) replace "projection", its two core.cache tiers and `structural-registry`? | Registry acquisition is a known memory and latency cost per pass (owner: never rebuild a base per pass). | reference-code/malli/src/malli/registry.cljc; reference-code/malli/src/malli/core.cljc:2672-2720 (`validator`, `explainer` caching) | the rename "projection" → "registry"; the deletion of core.cache uses | M |
| 8 | Reload: can clj-reload's `topo-sort`/`reload` (or tools.namespace's tracker) order and execute adoption from program entities instead of files? | Reload is how an accepted merge reaches compiled code; a wrong order breaks boot. | reference-code/clj-reload/src/clj_reload/core.clj:132-360; parse.clj:163; tools.namespace (**not vendored**: vendor it to answer) | "adoption" → "reload"; deletion of cluster.clj:1860-1919 | S |
| 9 | Test runs as clojure.test plus kaocha's result model: can "member", "accretion" and the claim/worker facts become test var results, with `malli.generator/check` for generative checks? | Proofs and reaching tests now run automatically after every code change (owner ruling). Their data must be one well-known shape. | reference-code/kaocha/src/kaocha/testable.clj, result.clj; reference-code/malli/src/malli/generator.cljc:540-600; clojure.test report types | the test schemas `:seon.test.*`; renames member/accretion | M |
| 10 | Render selection as Clerk's viewer model (`:pred :transform-fn :render-fn :page-size`) plus `datafy`/`nav` for elision forms: does Seon's render/value split map onto it, and does Clerk's `present` do breadth-first budgets? | Every shown value passes here. A known model shortens seon.render (1,792 lines) and seon.render.value (1,821 lines). | reference-code/clerk/src/nextjournal/clerk/viewer.cljc:190-260, 880-1120; reference-code/portal; reference-code/morse/src/dev/nu/morse/renderers.clj; clojure/datafy.clj | render spec vocabulary; whether elision forms are `nav` | M |
| 11 | Namespace DAG: which of the 38 `requiring-resolve` cycle-breakers dissolve by passing functions as arguments or moving shared functions down? | Hidden edges defeat clj-kondo analysis, reload order and the agent's `callers`/`breaks` reads. | clj-kondo analysis `:namespace-usages` (reference-code/clj-kondo); clj-reload parse.clj | a shrink lane per cycle | S |
| 12 | Flow public API only: can seon.flow stop reaching into `flow.impl.graph`, `flow.spi` and `core.async.impl.*`? | Impl namespaces change under version bumps, and a broken launcher stops every agent. | reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:76-302; flow/spi.clj | seon.flow shrink | S |
| 13 | Findings in clj-kondo's shape (`:type :level :message :row :col`): can REPL misuse findings, contract findings and lint share it? | Agents already know clj-kondo findings, so one grammar replaces three. | reference-code/clj-kondo/src/clj_kondo/core.clj:40-110 | `:seon.repl/finding`, `:seon.fn.finding`, `:seon.health/severity` | S |

## 5. Corrections found in passing (living documents)

- vocabulary.md cites SCI `fork` at core.cljc:345 and `init` at :331. HEAD has `fork` at :334 and `init` at :320.
- vocabulary.md bans "receipt", yet 533 hits remain in `src`/schemas. turn.clj:195, schedule.clj:72 and effect.clj:66 are the worst.
- `seon.render.value`'s docstring still says "dig form" (render/value.clj:16, :28), against the owner's 2026-10-08 rename.
- `seon.batch` builds error messages with `str` (batch.clj:735-751), against the owner's "no str-built messages" ruling.
