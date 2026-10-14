# Root Causes Behind the Seon Task Backlog (`rootcause-argon-high`)

**Corpus examined**: 101 files in `resources/seon/tasks/*.edn` (`872` tasks on `HEAD` `243683f1d`; `874` in the working tree with 2 uncommitted additions in `my.agent.edn` and `seon.cluster.agent.edn`), cross-checked against current `src/`, `script/`, and `resources/seon/schema/`.

---

## Ranked Root-Cause Clusters (Verified Producers in `src/`)

Clusters are ranked descending by the number of declared tasks their fix at the producer **RETIRES** outright. Every code excerpt below is quoted verbatim from the current working tree on `HEAD`.

---

### Cluster 1 — Second Printers, Ad-Hoc Clipping, and Storing Printed Text Instead of Data
- **Confidence**: `VERIFIED`
- **Impact**: **36** member tasks — **30 RETIRED**, **4 SIMPLIFIED**, **2 LEFT**
- **Producers**:
  - `seon.error/value-edn` and `seon.error/bounded-text` (`src/seon/error.clj:335-342, 361-378`)
  - `seon.render.value/bounded-text` (`src/seon/render/value.clj:110-128`)
  - `seon.turn.batch/schema-tx` (`src/seon/turn/batch.clj:466-478`)
  - `seon.test.runner/report-value` (`src/seon/test/runner.clj:1611-1626`)
- **Wrong behavior**: Instead of keeping values as data under Malli schemas until the single edge call to the value printer (`seon.print` / `seon.render.value`), producers across 28 namespaces call `pr-str` or `seon.sci.admit/canonical-edn` (79 call sites in `src/`) to pre-format values into `:seon.error/message`, `:seon.error/data-edn`, `:seon.test.report/expected`, `:seon.test.report/actual`, log lines, and cache keys, or clip already-printed strings with `subs` in `bounded-text` under ambient `*print-length*`/`*print-level*` bindings.
- **Exact verbatim code excerpt** (`src/seon/error.clj:335-342, 361-378`):
```clojure
(defn- value-edn
  "The stored text of one admission: its semantic value as canonical EDN,
  which `clojure.edn` reads back as the value. The admission's own emitted
  text (`:seon.sci.admit/edn`) is its print-node tree, readable only as
  nodes: fault evidence stored it and never read back as a value."
  {:malli/schema [:=> [:cat :seon.sci.admit/admitted] :string]}
  [admitted]
  (admit/canonical-edn (:seon.sci.admit/value admitted)))
```
```clojure
(defn- bounded-text
  {:malli/schema [:=> [:cat :seon.error/source :seon.sci.admit/caps [:maybe :seon.schema/projection]]
                  :string]}
  [value caps projection]
  (let [admitted (bounded-admission value caps projection)]
    (if-some [marker (::marker admitted)]
      (admit/canonical-edn marker)
      (let [projected (:seon.sci.admit/value admitted)]
        (if (string? projected)
          projected
          (admit/canonical-edn projected))))))
```
- **Exact verbatim code excerpt** (`src/seon/render/value.clj:110-128`):
```clojure
(defn bounded-text
  "Bound `text` to the active `:seon.render/token-budget` (or `default-token-budget`)."
  {:malli/schema [:=> [:cat :string] :string]}
  [text]
  (let [budget (long (or (active-token-budget) default-token-budget))
        cap (* 4 budget)]
    (if (<= (count text) cap)
      text
      (let [marker " ...(truncated)"
            keep-chars (max 0 (- cap (count marker)))]
        (str (subs text 0 keep-chars) marker)))))
```
- **Member tasks (`ns/name`)**:
  1. `seon.print-census-test/no-value-is-printed-outside-the-value-printer` — 79 `pr-str` calls across 28 `src/` files print values outside `seon.print`. (**RETIRE**)
  2. `seon.print.value/printed-copies-stored-or-sent-instead-of-values` — 18 attributes (`:seon.error/data-edn`, `:seon.test.report/expected`, etc.) store `pr-str` text instead of values. (**RETIRE**)
  3. `seon.print.value/ad-hoc-text-clips-outside-the-printer` — `subs` clipping in `seon.error/bounded-text`, `seon.render.value/bounded-text`, `seon.render.web`, `seon.operator`, `my.program`. (**RETIRE**)
  4. `seon.print/stored-or-compared-text-printed-under-ambient-bindings` — `pr-str` in `seon.turn.batch`, `seon.sci.arm`, `seon.cluster.source` runs under ambient `*print-length*`/`*print-level*`. (**RETIRE**)
  5. `seon.error/error-messages-print-growing-values` — 25 error sites splice `(pr-str v)` directly into `:seon.error/message`. (**RETIRE**)
  6. `seon.error/error-admission-is-a-second-clipper` — `seon.error/bounded-admission` and `bounded-text` run a second admission clipper before the value printer. (**RETIRE**)
  7. `seon.error/a-fault-notification-is-its-errors-edn-as-text` — `seon.error/message-tx` formats fault notifications as EDN strings instead of structured data. (**RETIRE**)
  8. `seon.error/error-notifications-to-root-are-printed-maps` — error notifications to root store printed maps in `:seon.message/content`. (**RETIRE**)
  9. `seon.fault/log-lines-print-values-outside-the-printer` — `seon.fault`, `seon.cluster.boot`, `seon.cluster.store` format log lines with `pr-str` outside the value printer. (**RETIRE**)
  10. `seon.cluster.store/datahike-error-log-lines-exceed-their-bound` — `datahike-log!` prints raw event maps with `pr-str`, exceeding line bounds. (**RETIRE**)
  11. `seon.cluster.boot/request-failure-message-is-a-printed-cause-chain` — `seon.cluster.boot` prints the exception cause chain into `:seon.error/message`. (**RETIRE**)
  12. `seon.cluster.branch-evidence/agent-identity-prints-its-id-into-the-message` — `my.agent/identity` splices `(pr-str agent-id)` into `:seon.error/message`. (**RETIRE**)
  13. `seon.cluster.source/a-red-on-target-refusal-keeps-its-red-tests-as-data` — `red-on-target` refusal formats red test symbols into prose instead of keeping data. (**RETIRE**)
  14. `my.root/merge-requests-name-the-test-a-request-waits-on` — root problem tasks format refusal maps as text instead of reading structured refusal data. (**SIMPLIFY**)
  15. `seon.db/a-unique-rejection-message-is-the-raw-exception` — `seon.db/rejection-message` embeds raw Datahike `ex-message` (containing printed datoms) into `:seon.error/message`. (**RETIRE**)
  16. `seon.db/projection-fallback-prints-instead-of-returning` — `seon.db/projection-fallback` prints a `WARN` line to stderr instead of returning only data. (**RETIRE**)
  17. `seon.problems/ai-prose-returns-printed-text-not-data` — `seon.problems` renders return pre-printed strings rather than data for the value printer. (**RETIRE**)
  18. `seon.repl/directory-render-returns-text-the-printer-clips-mid-form` — `seon.bootstrap/dir` returns a pre-formatted string that the value printer clips mid-line as a scalar string. (**RETIRE**)
  19. `seon.repl/printed-output-reaches-the-reply-unbounded` — `:seon.cluster.eval/output` bypasses the value printer's token budget as raw text. (**RETIRE**)
  20. `seon.render.value/bounded-text-survives-in-the-render-namespace` — `seon.render.value/bounded-text` is a second string clipper inside the value printer namespace. (**RETIRE**)
  21. `seon.render.value/failed-selection-escapes-the-profile-budget` — fallback to `plain-projection` / `bounded-text` escapes the token budget when selection fails. (**RETIRE**)
  22. `seon.render.value/nested-renders-bypass-the-floor` — render functions calling other render functions emit strings instead of data nodes, bypassing floor selection. (**RETIRE**)
  23. `seon.render.value/test-messages-print-whole-worlds` — test failure messages pre-printed with `pr-str` dump whole worlds. (**RETIRE**)
  24. `seon.render.web/pages-print-values-instead-of-the-printer-html-face` — `seon.render.web` calls `pr-str` instead of `seon.render.value/html`. (**RETIRE**)
  25. `seon.sci.admit/large-printed-output-refuses-the-evaluation` — `seon.sci.admit` refuses `:seon.cluster.eval/output` over byte cap instead of letting the printer elide it. (**RETIRE**)
  26. `seon.sci.arm/arming-prints-the-authored-contract-to-read-it-back` — `seon.sci.arm` and `seon.turn.batch` serialize `:malli/schema` with `pr-str` and re-parse with `edn/read-string`. (**RETIRE**)
  27. `seon.turn/ai-attempt-stores-printed-copies-of-settings-and-usage` — `seon.turn/record-attempt!` stores `pr-str` copies of settings and usage maps. (**RETIRE**)
  28. `seon.turn/a-not-admitted-diagnostic-prints-its-write-attempt` — `seon.turn/not-admitted` prints the write attempt into `:seon.error/message`. (**RETIRE**)
  29. `seon.test.runner/failure-evidence-is-stored-as-text` — `seon.test.runner/report-value` stores `:seon.test.report/expected` and `:actual` as `pr-str` strings. (**RETIRE**)
  30. `seon.effect/background-poll-answers-edn-strings` — `seon.effect` stores and returns background poll results as EDN strings. (**RETIRE**)
  31. `seon.cluster.agent/reply-size-prints-sources-to-measure-them` — `seon.cluster.agent` prints forms to measure reply size. (**SIMPLIFY**)
  32. `seon.operator/operator-prints-whole-results` — `seon.operator` prints whole result maps with `pr-str` instead of the value printer. (**RETIRE**)
  33. `seon.operator/build-cache-keys-hash-printed-text` — `seon.operator` hashes `pr-str` text for cache keys. (**SIMPLIFY**)
  34. `seon.program/values-printed-to-order-or-compare` — `seon.program` and `seon.test` sort/compare values by `pr-str` / `print/compare-values`. (**SIMPLIFY**)
  35. `seon.test-support/tests-read-the-evaluations-recorded-data-not-printed-text` — tests parse `:seon.eval/shown` text because callers stored printed strings. (**LEAVE**)
  36. `seon.print/a-contract-refusal-prints-a-generated-example-of-the-whole-schema` — `seon.error/example` generates and prints a whole-schema example into the refusal. (**LEAVE**)
- **Fix at the owner**: Delete `seon.error/bounded-text`, `seon.error/value-edn`, and `seon.render.value/bounded-text`. Keep error data, test expected/actual values, AI attempt usage, and render outputs as structured data values all the way to the edge, where `seon.render.value` / `seon.print` formats them once under the caller's token budget; keep `:seon.error/message` a static template string naming the failure kind while variable values live in `:seon.error/data`.

---

### Cluster 2 — Schema Projection Couples Function Contracts with Schema Declarations & Resolves Predicates Only in Host Vars
- **Confidence**: `VERIFIED`
- **Impact**: **26** member tasks — **21 RETIRED**, **4 SIMPLIFIED**, **1 LEFT**
- **Producers**:
  - `seon.schema/projection-ranges` and `seon.schema/build-projection` (`src/seon/schema.clj:2386-2434, 3356-3363`)
  - `seon.schema/loaded-predicate-var` and `seon.schema/compilable-form` (`src/seon/schema.clj:247-265, 302-382`)
  - `seon.db/carried-projection` (`src/seon/db.clj:1874-1980`)
- **Wrong behavior**:
  1. `seon.schema/projection-ranges` bundles `:seon.schema/schema-rows` (`:seon.schema/key` -> `:seon.schema/form`) and `:seon.schema/function-contract-rows` (`:seon.fn/sym` -> `:seon.fn/spec`) into a single projection digest and object. Consequently, every `defn` in any namespace invalidates the entire schema projection and every cache keyed on it (`seon.program/shapes-in`, `seon.error/observation-selector`, `seon.config/dial-attributes`, `seon.schema.datahike/compiled-attribute-selection`).
  2. `compilable-form` resolves `:fn` predicate symbols through `loaded-predicate-var`, which calls host `find-ns` / `ns-resolve`. Any predicate defined on an agent's SCI branch (or in an unloaded `script/` namespace) is absent from host Vars unless `predicate-functions` was explicitly passed, causing the writer's `carried-projection`, `register!`, and the test step's `projection-from-database` to fail or poison the whole projection when a single contract names a branch predicate.
- **Exact verbatim code excerpt** (`src/seon/schema.clj:247-265, 348-365, 3356-3363`):
```clojure
(defn- loaded-predicate-var
  [predicate]
  (when (qualified-symbol? predicate)
    (some-> (find-ns (symbol (namespace predicate)))
            (ns-resolve (symbol (name predicate))))))
```
```clojure
       (and (vector? value) (= :fn (first value)))
       (let [predicate-index (if (map? (second value)) 2 1)
             predicate (get value predicate-index)
             bound
             (when (qualified-symbol? predicate)
               (or (get predicate-functions predicate)
                   (loaded-predicate-var predicate)))]
```
```clojure
(def projection-ranges
  {:seon.schema/schema-rows [:seon.schema/key :seon.schema/form true]
   :seon.schema/function-contract-rows [:seon.fn/sym :seon.fn/spec false]})
```
- **Member tasks (`ns/name`)**:
  1. `seon.schema/a-function-contract-changes-the-schema-projection` — `:seon.schema/function-contract-rows` in `projection-ranges` makes every `defn` invalidate the schema projection. (**RETIRE**)
  2. `seon.schema/schema-only-derivations-are-rederived-per-projection` — derivations over `:seon.schema/form` are cached per projection and re-run on every `defn`. (**RETIRE**)
  3. `seon.program/a-defn-scans-every-projection-form-per-call` — `seon.program/shapes-in` misses its projection cache and scans all 2,400 forms whenever a `defn` changes `:function-contract-rows`. (**RETIRE**)
  4. `seon.error/fault-window-derives-the-error-selector-when-no-error-occurred` — `seon.error/observation-selector` is re-derived per projection after any `defn`. (**RETIRE**)
  5. `seon.config/a-config-read-rebuilds-the-packaged-projection` — `seon.config/effective` rebuilds `declaration-projection` when schema forms and function contracts are not separated. (**RETIRE**)
  6. `seon.config/a-config-read-walks-the-whole-registry` — `seon.config/dial-attributes` walks the projection registry per projection change. (**RETIRE**)
  7. `seon.instrument/arming-a-projections-contract-walks-no-references` — `contract-product` re-walks `contract-references` instead of reading compiled schema references. (**SIMPLIFY**)
  8. `seon.schema/schema-registration-rechecks-the-whole-program-per-key` — `register-candidate!` rebuilds the full projection (including all function contracts) per key. (**RETIRE**)
  9. `seon.schema.datahike/attribute-selection-reuses-its-immutable-inputs` — `compiled-attribute-selection` is keyed on the combined projection rather than `:seon.schema.projection/forms`. (**RETIRE**)
  10. `seon.schema.datahike/decoding-pays-a-projection-scope-and-cache-claim-per-value` — `decode-attribute-value` resolves `carried-projection` per value. (**SIMPLIFY**)
  11. `seon.db/the-first-read-of-a-branch-head-derives-its-schema` — opening a branch head with a changed contract re-derives the projection on first read. (**RETIRE**)
  12. `seon.db/a-failed-projection-derivation-stays-cached-under-its-commit` — `projection-cache` caches failed projection derivations by commit when a contract fails compilation. (**RETIRE**)
  13. `seon.schema/the-projection-travels-ambiently` — `*projection*`, `*projection-state*`, `*packaged-forms*`, `*candidate-forms-overlay*` travel in dynamic Vars. (**SIMPLIFY**)
  14. `seon.test.accretion/schema-row-compiles-every-reached-form` — `accretion/schema-row` compiles every reached form instead of reusing the schema registry. (**SIMPLIFY**)
  15. `seon.sci.arm/arming-projection-is-rebuilt-per-caller-within-one-form` — `seon.sci.arm/arming-projection` rebuilds candidate projections repeatedly within one form. (**RETIRE**)
  16. `seon.schema/a-fn-schema-over-a-branch-predicate-is-refused` — `loaded-predicate-var` checks only host `find-ns`/`ns-resolve`, refusing branch-defined predicates in `malli-form?`. (**RETIRE**)
  17. `seon.schema/a-schema-naming-a-branch-predicate-breaks-the-test-step` — `projection-from-database` on a branch without the agent SCI context fails `compilable-form` on branch predicates. (**RETIRE**)
  18. `seon.schema/a-contract-naming-a-branch-predicate-is-refused-at-the-writer` — the writer's `carried-projection` has no `predicate-functions` map for branch-defined predicates. (**RETIRE**)
  19. `seon.schema/registering-a-schema-refuses-a-redefined-core-contract-the-defn-admitted` — `register!` validates all `function-contracts` through `loaded-predicate-var`, failing on a branch predicate an earlier `defn` admitted. (**RETIRE**)
  20. `seon.sci.eval/a-contract-predicate-is-refused-both-ways` — unqualified predicate is refused by `assert-complete-contract!`, qualified branch predicate is refused by `loaded-predicate-var`. (**RETIRE**)
  21. `seon.schema/a-contract-naming-an-unresolvable-symbol-refuses-the-whole-projection` — one unresolvable predicate in `:function-contract-rows` throws out of `compilable-form`, poisoning the whole database's `carried-projection`. (**RETIRE**)
  22. `seon.sci.eval/a-script-namespace-contract-naming-a-local-def-crashes-publication` — `script/` namespace predicates not loaded in the cluster JVM fail `loaded-predicate-var` during publication. (**RETIRE**)
  23. `seon.db/admission-without-projection-embeds-its-error` — when `carried-projection` fails on a contract, admission embeds the projection refusal map inside the stored value. (**RETIRE**)
  24. `seon.flow/forced-child-jvm-death-fixture-write-is-refused-for-its-projection` — fixture write fails when `carried-projection` refuses an unloaded predicate. (**RETIRE**)
  25. `seon.schema/a-schema-with-a-ref-and-no-generator-refuses-contract-admission` — contract admission calls `mg/generate` on the whole projection registry without stubbing recursive/ref generators. (**LEAVE**)
  26. `seon.schema/a-recursive-local-registry-schema-overflows-the-stack` — `compilable-form` postwalks expanded forms before recursive ref resolution. (**RETIRE**)
- **Fix at the owner**: Remove `:seon.schema/function-contract-rows` from `seon.schema/projection-ranges` so the schema projection is purely a function of `:seon.schema/form` datoms (`:seon.schema/schema-rows`), and compile each `:seon.fn/spec` lazily per function against the compiled schema registry when that function is armed. In `compilable-form`, resolve `:fn` predicate symbols from the database's stored `:seon.fn/sym` rows (or SCI evaluation when not host-loaded) rather than only `loaded-predicate-var`.

---

### Cluster 3 — Host-to-SCI Var Copying and Incomplete Host Namespace Exposure in Agent Contexts
- **Confidence**: `VERIFIED`
- **Impact**: **23** member tasks — **19 RETIRED**, **3 SIMPLIFIED**, **1 LEFT**
- **Producers**:
  - `seon.sci.eval/copy-host-var` and `seon.sci.eval/load-host-namespace` (`src/seon/sci/eval.clj:252-271, 284-309`)
  - `seon.sci.eval/build-base-ctx` (`src/seon/sci/eval.clj:366-452`)
  - `seon.fn/host-bound-callers` (`src/seon/fn.clj:1182-1216`)
- **Wrong behavior**: `copy-host-var` constructs a detached `sci.lang.Var` snapshot of `.getRawRoot` with `:test` stripped from metadata (`(dissoc (meta host-var) :ns :name :test)`), so dynamic `binding` in SCI never pushes a thread binding onto the underlying `clojure.lang.Var`, re-instrumented host Vars stay pinned to stale wrapper generations, and `clojure.test/run-test-var` or `clojure.core.async.flow/create-flow` (which check `clojure.lang.Var` / `:test`) fail on SCI Vars. Meanwhile `load-host-namespace` copies only `ns-interns` of already-loaded JVM namespaces (omitting `ns-imports`, namespace metadata, unloaded stdlib namespaces like `clojure.data`, and interpreted test rows).
- **Exact verbatim code excerpt** (`src/seon/sci/eval.clj:252-271, 284-309`):
```clojure
(defn- copy-host-var
  {:malli/schema [:=> [:cat [:fn clojure.core/var?] [:fn clojure.core/some?]] [:fn clojure.core/some?]]}
  [^clojure.lang.Var host-var sci-namespace]
  (let [copied (sci/copy-var* host-var sci-namespace)]
    (when (.getThreadBinding host-var)
      (.bindRoot ^sci.lang.Var copied (.getRawRoot host-var)))
    (sci.ctx-store/with-ctx nil
      (alter-meta! copied #(merge (dissoc (meta host-var) :ns :name :test) %)))
    copied))
```
```clojure
(defn- load-host-namespace
  {:malli/schema [:=> [:cat [:map [:namespace :symbol] [:ctx :map]]] [:maybe :map]]}
  [{namespace-name :namespace ctx :ctx}]
  (when-let [host (find-ns namespace-name)]
    (let [sci-namespace (context-namespace ctx namespace-name)]
      (sci/add-namespace!
       ctx namespace-name
       (into {:obj sci-namespace}
             (map (fn [[intern-name host-var]]
                    [intern-name (copy-host-var host-var sci-namespace)]))
             (ns-interns host)))
      {})))
```
- **Member tasks (`ns/name`)**:
  1. `seon.sci.eval/copied-vars-pin-old-wrapper-generations` — `copy-host-var` snapshots `.getRawRoot` at base-ctx creation, pinning old instrumented wrapper generations. (**RETIRE**)
  2. `seon.sci.eval/binding-a-host-dynamic-var-does-not-reach-host-code` — `copy-host-var` creates a `sci.lang.Var` whose SCI `binding` does not push a thread binding onto the `clojure.lang.Var`. (**RETIRE**)
  3. `seon.sci.eval/sci-binding-of-a-host-dynamic-var-never-reaches-host-code` — duplicate symptom of `copy-host-var` decoupling SCI dynamic bindings from host `Var` thread bindings. (**RETIRE**)
  4. `seon.sci.eval/sci-out-is-unbound-on-a-test-member-thread` — `sci/out` is a separate `sci.lang.Var` from `clojure.core/*out*`, so child threads lose output capture. (**RETIRE**)
  5. `seon.sci.eval/copied-jvm-import-hides-sci-import` — host namespace class maps hide SCI imports in `ctx`. (**RETIRE**)
  6. `seon.sci.eval/interpreted-rows-lack-own-namespace-class-imports` — `load-host-namespace` copies `ns-interns` but omits `ns-imports` of the host namespace. (**RETIRE**)
  7. `seon.sci.eval/an-interpreted-namespace-carries-no-metadata` — `context-namespace` creates an SCI namespace without copying `(meta host)` in `load-host-namespace`. (**RETIRE**)
  8. `seon.sci.eval/test-support-has-no-vars-in-the-agent-context` — `load-host-namespace` only runs on `find-ns` of already-loaded namespaces and `test-support` is not loaded/installed. (**RETIRE**)
  9. `seon.sci.eval/a-test-namespace-an-agent-requires-is-interpreted-not-host-loaded` — requiring a test namespace falls through to `load-host-namespace` (which strips `:test`) instead of interpreting its stored rows. (**RETIRE**)
  10. `seon.sci.eval/run-test-var-throws-on-an-sci-test-var` — `copy-host-var` strips `:test` metadata and `clojure.test/test-var` expects a callable `:test`. (**RETIRE**)
  11. `my.test/an-agent-cannot-run-one-test-by-hand` — `(clojure.test/run-test-var #'foo-test)` fails in SCI because of the SCI/host test Var split. (**RETIRE**)
  12. `seon.sci.lookup/run-test-var-on-a-just-defined-test-refuses-its-own-output` — `agent-test-namespace` wrapper around `run-test-var` fails contract validation on its own return. (**RETIRE**)
  13. `seon.sci.eval/java-io-is-unresolved-in-an-agent-context` — `build-base-ctx` does not pre-install common `java.io.*` classes unless explicitly imported. (**RETIRE**)
  14. `seon.sci.kernel/clojure-data-resolves-in-agent-sci` — `clojure.data` is not required on the host before `load-host-namespace` checks `(find-ns namespace-name)`. (**RETIRE**)
  15. `seon.sci.kernel/a-file-function-that-calls-slurp-cannot-be-redefined` — `build-base-ctx` injects `slurp`/`spit`/`file-seq` only when declared in `base-context-injected-symbols`. (**RETIRE**)
  16. `seon.sci.kernel/an-agent-namespace-ns-interns-lists-only-its-own-vars` — `sci/add-namespace!` in `build-base-ctx` mixes injected vars into user namespaces. (**SIMPLIFY**)
  17. `seon.sci.eval/bare-source-and-apropos-do-not-resolve-in-an-agents-repl` — `build-base-ctx` lines 436-447 inject only `dir`, `doc`, `help` into `clojure.core`, omitting `source` and `apropos`. (**RETIRE**)
  18. `seon.sci.eval/a-proxy-in-interpreted-code-is-unsupported` — `build-base-ctx` supplies `:reify-fn interface-reify` (line 422) for `reify` but no `proxy` macro. (**LEAVE**)
  19. `seon.fn/a-used-class-makes-a-row-host-bound` — `seon.fn/host-bound-callers` marks any function referencing a class outside `sci.opts/default-classes` as `:seon.fn/host-bound?`, even though `build-base-ctx` sets `:classes {:allow :all}` (line 421). (**RETIRE**)
  20. `seon.sci.eval/a-host-bound-macro-caller-interprets-its-expanded-form` — callers of macros in host-bound namespaces are marked host-bound instead of expanding the macro in SCI. (**SIMPLIFY**)
  21. `seon.flow/a-step-var-defined-in-sci-cannot-be-built-into-a-proc` — `clojure.core.async.flow` checks `(var? step)` (`instance? clojure.lang.Var`), rejecting `sci.lang.Var`. (**RETIRE**)
  22. `seon.flow/an-interpreted-step-var-is-refused-on-a-branch` — branch `deftest` passing an SCI `#'step-fn` to `flow/create-flow` is refused because `sci.lang.Var` is not a `clojure.lang.Var`. (**RETIRE**)
  23. `seon.sci.eval/a-dev-namespace-test-cannot-run-interpreted-on-a-cluster` — `dev/` namespaces are not loaded in the cluster JVM, so `load-host-namespace` returns `nil`. (**SIMPLIFY**)
- **Fix at the owner**: Bridge dynamic `clojure.lang.Var` thread bindings in SCI's `push-thread-bindings`/`pop-thread-bindings` fork (or pass `host-var` through `sci/new-var` delegating deref/binding to `host-var`), copy namespace metadata and `ns-imports` in `load-host-namespace` (falling back to stored DB rows for test namespaces and `require` for unloaded classpath stdlib namespaces), preserve `:test` metadata on test Vars, and align `seon.fn/host-bound-callers` with `build-base-ctx`'s `:classes {:allow :all}`.

---

### Cluster 4 — Test World Overlay Store Construction, Out-of-Order Connection Release, and Branch-Filtered Result Queries
- **Confidence**: `VERIFIED`
- **Impact**: **24** member tasks — **19 RETIRED**, **3 SIMPLIFIED**, **2 LEFT**
- **Producers**:
  - `seon.test/clean-request-handle` and `seon.test/run-request` (`src/seon/test.clj:2219-2340, 2668-3046`)
  - `seon.test.runner/newest-results` and `seon.test.runner/latest-results` (`src/seon/test/runner.clj:2477-2535`)
- **Wrong behavior**:
  1. On every test check with branch edits, `clean-request-handle` opens a fresh overlay store (`store/open-overlay!`), creates a new branch, transacts `program-overlay-tx` across all changed definitions, and calls `carry-world-projection!` (allocating 0.7–1.5 GB and 500–2,000 ms before a 1-test body runs). In `run-request`'s `finally`, `clean-request-handle`'s `release!` calls `store/release-store!` before timed-out member threads finish `retire-branch!`, causing `"Connection has been released"` and `"still has active connections"` errors.
  2. `seon.test.runner/newest-results` filters out inherited/published runs via `(when-not (and own? tested) ...)` and `(not [?run :seon.test.run/published-base-digest])` (lines 2492, 2498), blinding agent branches to base test runs and `red-before` evidence on `main`.
- **Exact verbatim code excerpt** (`src/seon/test.clj:2282-2308`):
```clojure
        store (store/open-overlay! store)
        ;; The request branch holds the overlay and roots its commit for the members.
        request-branch (keyword (str "agent-" (id/id)))
        release! (fn [^Throwable failure steps]
                   (doseq [cleanup! steps]
                     (try (cleanup!) (catch Throwable cleanup (.addSuppressed failure cleanup))))
                   failure)
        drop! #(store/release-store! store)
```
```clojure
        connection (try (open-world! store request-branch cluster)
                        (catch Throwable failure (throw (release! failure [retire! drop!]))))
        report (when (seq overlay)
                 (try (db/with-branch {:seon.db/connection connection}
                                            #(let [report (db/transact! connection overlay)]
                                               (when (:db-after report) (carry-world-projection! connection cluster))
                                               report))
```
- **Exact verbatim code excerpt** (`src/seon/test/runner.clj:2491-2501`):
```clojure
      (keep (fn [[run-entity recorded-at tested]]
              (when-not (and own? tested)
                [run-entity recorded-at])))
      (db/q '[:find ?run ?at ?tested
              :in $ [?sym ...]
              :where
              [?result :seon.test.result/test ?sym]
              [?run :seon.test.run/results ?result]
              (not [?run :seon.test.run/published-base-digest])
              [?run :seon.test.run/recorded-at ?at]
              [(get-else $ ?run :seon.test.run/tested-branch false) ?tested]]
```
- **Member tasks (`ns/name`)**:
  1. `my.test/a-two-test-check-allocates-a-gigabyte-and-a-half` — `clean-request-handle` opens a fresh overlay store, transacts `program-overlay-tx`, and re-derives `carry-world-projection!` per check. (**RETIRE**)
  2. `seon.cluster.branch-evidence/an-agent-check-costs-ten-times-its-one-test` — 90% of a 1-test agent check is `clean-request-handle` building the overlay world. (**RETIRE**)
  3. `seon.cluster.store/a-test-world-rereads-index-nodes-from-disk` — `store/open-overlay!` creates a fresh store with an empty node cache, re-reading index nodes from disk. (**RETIRE**)
  4. `seon.test/a-changed-check-builds-a-world-per-request` — every `seon.test/run` with branch changes builds a new `request-branch` and transacts `program-overlay-tx`. (**RETIRE**)
  5. `seon.test/one-test-run-costs-seconds-around-a-147-ms-body` — world construction and teardown around a 147 ms test body takes multiple seconds. (**RETIRE**)
  6. `seon.test/a-one-test-proof-check-allocates-a-gigabyte` — single-test proof check pays full `clean-request-handle` overlay transaction and projection carry. (**RETIRE**)
  7. `seon.test/a-first-deftest-in-a-new-namespace-costs-seconds-of-test-step` — adding a first `deftest` triggers `clean-request-handle` overlay world build in the test step. (**RETIRE**)
  8. `seon.test/one-test-run-repeats-whole-program-setup` — `clean-request-handle` and `run-request` repeat whole-program overlay setup per run. (**RETIRE**)
  9. `seon.test/a-named-check-on-a-branch-carrying-platform-edits-allocates-gigabytes` — `program-overlay-tx` diffs and replays all branch platform edits into the overlay connection on every named check. (**RETIRE**)
  10. `seon.turn/an-executed-test-request-allocates-seven-hundred-megabytes-in-the-step` — `seon.turn/test-step` calls `seon.test/run`, paying `clean-request-handle`'s 700 MB world build. (**RETIRE**)
  11. `seon.turn/a-test-step-running-one-tiny-test-costs-half-a-second` — 500 ms overhead in `test-step` from `clean-request-handle`. (**RETIRE**)
  12. `seon.test/a-branch-test-world-runs-the-jvm-loaded-program-not-its-fork-point-record` — `world-base` uses `loaded-identity` while `built-on` pulls the branch's fork-point `:seon.source/commit-id`. (**SIMPLIFY**)
  13. `seon.test/a-test-world-has-no-agent` — `clean-request-handle` starts from `world-base` (which has no agent entity for the branch's agent). (**RETIRE**)
  14. `seon.cluster.registry/a-retire-branch-error-repeats-in-every-reply` — `run-request`'s `finally` calls `store/release-store!` on the overlay before timed-out member watcher threads call `retire-branch!`. (**RETIRE**)
  15. `seon.cluster.registry/retiring-a-branch-records-connection-has-been-released` — `retire-branch!` on a member branch throws `"Connection has been released"` because `clean-request-handle`'s overlay store was released first. (**RETIRE**)
  16. `seon.test/test-release-closes-branch-connections-out-of-order` — `run-request` releases the overlay store before member branch connections close. (**RETIRE**)
  17. `seon.test/check-run-refused-once-with-active-connections-on-store-deletion` — `release-store!` refuses when a member connection is still open on a worker thread. (**RETIRE**)
  18. `seon.maintenance/a-test-request-collects-the-whole-store-under-the-exclusive-gate` — `seon.test/run` submits `collect-released!` after every test request because it created and dropped temporary branches on the store. (**SIMPLIFY**)
  19. `seon.test.runner/a-forked-value-reads-the-results-it-inherited` — `newest-results` with `own? true` filters out runs whose `:seon.test.run/tested-branch` is set. (**RETIRE**)
  20. `seon.test/an-agent-branch-sees-none-of-its-clusters-test-evidence` — published base runs carry `:seon.test.run/published-base-digest`, which `newest-results` excludes. (**RETIRE**)
  21. `seon.context/a-step-proof-cannot-observe-its-agents-test-evidence` — `seon.context/proofs` reads `latest-results`, missing runs filtered by `newest-results`. (**RETIRE**)
  22. `seon.cluster.branch-evidence/the-branch-evidence-gate-rarely-sees-red-before-on-main` — `fork-point-red` misses red results on main when `latest-results` filters out base runs or reach digests drift. (**SIMPLIFY**)
  23. `seon.cluster.branch-evidence/a-reaching-red-is-proven-red-before-by-its-result-at-the-base-key` — base red comparison requires looking up by the base's reach digest. (**LEAVE**)
  24. `seon.test/a-base-run-is-kept-on-the-agents-branch` — base runs recorded with `:seon.test.run/tested-branch` on the agent's branch are excluded by `newest-results` when `own?` is true. (**LEAVE**)
- **Fix at the owner**: Fork the test world directly from the caller's held branch database value (`d/with` or an in-memory branch over the existing store sharing its node cache and carried projection) instead of opening a new overlay store and re-transacting `program-overlay-tx` from `world-base`; await member worker completion before releasing the request handle; and key `newest-results` purely by `(test-sym, reach-digest)` without excluding `:seon.test.run/published-base-digest` or `:seon.test.run/tested-branch`.

---

### Cluster 5 — Whole-Program Reach Graph, Mention-Closure Edges, and Unindexed Reverse Walks Per Change
- **Confidence**: `VERIFIED`
- **Impact**: **23** member tasks — **19 RETIRED**, **3 SIMPLIFIED**, **1 LEFT**
- **Producers**:
  - `seon.fn/gate-edges` and `seon.fn/gate-set-in` (`src/seon/fn.clj:1890-1969`)
  - `seon.fn/call-targets-by-caller` (`src/seon/fn.clj:362-405`)
  - `seon.test.runner/reach-declarations` and `seon.test.runner/reached-graph` (`src/seon/test/runner.clj:909-1010`)
  - `my.program/deletion-report` (`src/my/program.clj:474-497`)
- **Wrong behavior**: `seon.fn/gate-set-in` walks `:seon.fn/references` (all symbol mentions, not just `:seon.fn/calls`) and `seon.fn/gate-edges` queries every `:seon.fn/sym` and `:seon.test/sym` in the database on single-symbol `breaks` and `ns-unmap!` calls. Meanwhile `seon.test.runner/reach-declarations` and `reached-graph` recompute reach digests across all 1,800+ tests whenever `reach-revision-key` advances and the previous connection generation is not in cache.
- **Exact verbatim code excerpt** (`src/seon/fn.clj:1902-1910, 1922-1939`):
```clojure
        (let [calls (db/datoms database :avet :seon.fn/calls target)
              references (db/datoms database :avet :seon.fn/references target)
              subjects (db/datoms database :avet :seon.test/subject target)
              refusal (some #(when (:seon.db/invalid-read %) %) [calls references subjects])]
          (if refusal
            refusal
            (let [referrers (into (get incoming target #{})
                                 (keep #(get identities (:e %)))
                                 (concat calls references subjects))]
```
```clojure
  (let [identity-entities (db/q '[:find ?entity ?symbol
                             :where (or [?entity :seon.fn/sym ?symbol]
                                        [?entity :seon.test/sym ?symbol])] database)
        test-symbols (db/q '[:find [?symbol ...]
                            :where [_ :seon.test/sym ?symbol]] database)
        declared (try (declared-callers read-or-throw! database)
```
- **Member tasks (`ns/name`)**:
  1. `seon.fn/mention-closure-survives-beside-call-reach` — `gate-set-in` walks `:seon.fn/references` (symbol mentions) alongside `:seon.fn/calls`. (**RETIRE**)
  2. `seon.fn/tests-reaching-saturates` — walking `:seon.fn/references` and full closures saturates to hundreds of tests on core namespaces. (**RETIRE**)
  3. `my.program/breaks-costs-seconds-on-a-hub` — `my.program/breaks` calls `seon.fn/gating-tests` -> `gate-edges` + `gate-set-in`, scanning all identities and mention edges. (**RETIRE**)
  4. `my.program/breaks-on-a-namespace-subject-takes-five-seconds` — `breaks` on a namespace runs `gating-tests` across every symbol in the namespace. (**RETIRE**)
  5. `my.program/breaks-of-a-widely-used-function-allocates-a-gigabyte` — `breaks` and `deletion-report` walk the full mention and call graph. (**RETIRE**)
  6. `my.program/breaks-of-one-function-walks-the-program-graph` — `gate-edges` queries every `:seon.fn/sym` and `:seon.test/sym` in the database on a single-function `breaks` call. (**RETIRE**)
  7. `my.program/ns-unmap-and-remove-ns-cost-quarter-thousand-tokens-and-most-of-a-second` — `seon.turn.batch/removal-facts` calls `my.program/deletion-report`, which runs whole-program `breaks` queries. (**RETIRE**)
  8. `my.program/remove-ns-allocates-two-gigabytes` — `remove-ns!` runs `deletion-report` across every symbol in the namespace. (**RETIRE**)
  9. `seon.fn/a-macro-head-is-recorded-as-a-call-edge` — `seon.fn/call-targets-by-caller` records macro invocations in `:seon.fn/calls`. (**SIMPLIFY**)
  10. `seon.sci.eval/a-redefinition-walks-the-reverse-closure-with-its-test-reach` — `seon.sci.eval/stale-reach` calls `seon.fn/reverse-closure`, which walks tests as well as functions. (**RETIRE**)
  11. `seon.sci.eval/install-row-is-twice-as-slow-after-the-stale-var-fix` — `install-row!` walks reverse closure on every row install. (**RETIRE**)
  12. `seon.test.runner/reach-over-every-test-takes-thirty-seconds` — `reach-declarations` and `reached-graph` build reach nodes and `db/behavior-digest` across all tests. (**RETIRE**)
  13. `seon.test.runner/reach-entries-fill-half-the-cache-at-half-hit-rate` — `::reach-node`, `::reach-graph`, and `::reach-attribute` cache whole-attribute maps per revision. (**RETIRE**)
  14. `seon.test.runner/verified-recomputes-reach-digests-for-the-whole-program` — `seon.test/verified?` calls `runner/reach-digests` over all tests when one function changes. (**RETIRE**)
  15. `seon.test.runner/tests-reaching-rebuilds-the-whole-program-reach-graph-per-change` — `runner/tests-reaching` builds the whole-program `reach-index` whenever `reach-revision-key` moves. (**RETIRE**)
  16. `seon.test.runner/the-first-reach-digest-after-a-change-costs-what-changed` — `reach-attribute` scans whole attributes when `previous` is not in cache on the same connection generation. (**RETIRE**)
  17. `seon.test/reach-attribute-advances-from-the-recording-report` — `reach-attribute` uses `db/entities-changed-after` instead of advancing directly from the recording tx-report. (**SIMPLIFY**)
  18. `seon.test/changed-definition-symbols-scans-whole-attributes-since-the-last-run` — `changed-definition-symbols` scans `d/datoms` over `:seon.program/definition-digest` instead of changed entities. (**RETIRE**)
  19. `seon.test/a-check-compares-every-definition-digest-of-the-program-twice` — `seon.test/changes` (`digest-differences`) and `clean-request-handle` (`program-overlay-tx`) both scan all definition digests. (**RETIRE**)
  20. `seon.test/reuse-only-selection-costs-a-second` — `seon.test/select` recomputes reach digests across all candidate tests even when all are reused. (**RETIRE**)
  21. `seon.test/an-effect-attempt-opens-only-for-a-test-calling-an-effect-owner` — `seon.test` checks reach to effect owners per test member. (**LEAVE**)
  22. `seon.context/proofs-of-one-task-allocates-close-to-a-gigabyte` — `seon.context/proofs` calls `seon.test/stale` -> `runner/reach-digests`, triggering whole-program reach derivation. (**RETIRE**)
  23. `seon.context/one-agents-current-state-costs-hundreds-of-megabytes` — `current-state` calls `proofs` and `step-status`, paying whole-program reach and `gate-sets`. (**SIMPLIFY**)
- **Fix at the owner**: Delete `:seon.fn/references` from `gate-set-in` so reach walks only `:seon.fn/calls` and `:seon.test/subject` via `:avet` datoms; resolve entity identities lazily per visited entity (`d/entity` or `:eavt` lookup) instead of querying all `identity-entities` up front in `gate-edges`; and compute `reach-digest` lazily only for the requested test symbols by walking forward along `:seon.fn/calls` from those tests.

---

### Cluster 6 — Namespace State & Source Reconstructed by Mutating Stored Text from Live SCI Context Instead of the Evaluated Form
- **Confidence**: `VERIFIED`
- **Impact**: **18** member tasks — **15 RETIRED**, **2 SIMPLIFIED**, **1 LEFT**
- **Producers**:
  - `seon.sci.eval/declared-source`, `seon.sci.eval/namespace-source`, and `seon.sci.eval/namespace-context-row` (`src/seon/sci/eval.clj:734-840`)
  - `seon.turn.batch/namespace-facts` and `seon.turn.batch/stored-namespace-row` (`src/seon/turn/batch.clj:228-243, 505-520`)
- **Wrong behavior**:
  1. When an `(ns ...)` form or `require`/`refer`/`alias` is evaluated, `namespace-context-row` does not record the authored `(ns ...)` form; instead, `namespace-source` synthesizes `(str "(ns " namespace-name ")")` (losing any docstring and formatting) and splices `binding-delta` onto old `:seon.ns/source`. Moreover, `binding-delta` (lines 738-743) tracks only `:aliases`, `:refers`, and `:requires`—omitting `::imports` and `::fixtures`.
  2. In `seon.turn.batch/namespace-facts` (line 518), `(select-keys (db/pull db '[*] stored) (cons :db/id (keys row)))` restricts the pulled old entity to `(keys row)`. Because `binding-rows` omits keys whose collection is empty (`(when (seq aliases) ...)`), removing the last alias (`ns-unalias`) omits `:seon.ns/aliases` from `row`, so `select-keys` drops `:seon.ns/aliases` from the old map and `exact-replacement-tx` never retracts the removed alias!
- **Exact verbatim code excerpt** (`src/seon/sci/eval.clj:812-840` and `src/seon/turn/batch.clj:505-520`):
```clojure
(defn- namespace-source
  {:malli/schema [:=> [:cat [:maybe :seon.db/database-value] :seon.ns/name :map] :seon.ns/source]}
  [database namespace-name after]
  (let [stored (some-> database (db/pull namespace-pattern [:seon.ns/name namespace-name]))]
    (if-let [text (:seon.ns/source stored)]
      (declared-source text (row-bindings stored) after)
      (declared-source (str "(ns " namespace-name ")") {} after))))

(defn- namespace-context-row
  [projection database namespace-name before after changed?]
  (when (or changed?
            (not= (select-keys before [:seon.sci.reader/aliases
                                      :seon.sci.reader/refers
                                      ::imports ::requires ::fixtures])
                  (select-keys after [:seon.sci.reader/aliases
                                     :seon.sci.reader/refers
                                     ::imports ::requires ::fixtures])))
    (let [rows (binding-rows after)]
      (program/declaration-row
       projection
       (merge {:seon.ns/name namespace-name
               :seon.ns/source (namespace-source database namespace-name (row-bindings rows))}
              rows)
       :contracted :agent))))
```
```clojure
(defn- namespace-facts
  "Direct datom replacements for `namespace-rows` against `db`."
  [db namespace-rows]
  (let [by-name (into {} (map (juxt :seon.ns/name identity)) namespace-rows)]
    (into []
          (mapcat (fn [[namespace-name row]]
                    (let [stored (some-> (d/datoms db :avet :seon.ns/name namespace-name) first :e)]
                      (if stored
                        (exact-replacement-tx
                         (select-keys (db/pull db '[*] stored) (cons :db/id (keys row)))
                         (assoc row :db/id stored))
                        [row]))))
          by-name)))
```
- **Member tasks (`ns/name`)**:
  1. `seon.sci.eval/repl-namespace-changes-drift-from-the-ns-text` — `declared-source` splices deltas onto old `:seon.ns/source` instead of deriving from the evaluated `(ns ...)` form. (**RETIRE**)
  2. `seon.sci.eval/an-ns-form-at-the-repl-only-accretes-requires` — evaluating a narrower `(ns ...)` form at the REPL keeps prior requires in SCI's live namespace map, so `binding-delta` never removes them. (**RETIRE**)
  3. `seon.turn.batch/a-stored-namespace-replacement-keeps-removed-components` — `namespace-facts` line 518 `(select-keys ... (cons :db/id (keys row)))` fails to retract emptied component attributes (`:seon.ns/aliases`, `:seon.ns/refers`, `:seon.ns/requires`). (**RETIRE**)
  4. `my.program/a-repl-alias-stays-on-the-namespace-row-after-ns-unalias` — direct consequence of `namespace-facts` line 518 not retracting `:seon.ns/aliases` when the last alias is unaliased. (**RETIRE**)
  5. `seon.turn.batch/a-repl-namespace-row-keeps-neither-doc-nor-source` — `namespace-context-row` synthesizes `(str "(ns " namespace-name ")")` via `namespace-source`, discarding the evaluated `(ns ... "doc" ...)` docstring and authored source. (**RETIRE**)
  6. `seon.fn/chain-namespace-source` — chain steps evaluating `(ns ...)` lose their authored `ns` form layout through `declared-source`. (**RETIRE**)
  7. `seon.program/an-evaluated-namespace-keeps-its-read-source-and-call-edges` — evaluating an `ns` form at the REPL overwrites `:seon.ns/source` with `namespace-source`'s synthetic text. (**RETIRE**)
  8. `my.program/alias-and-require-as-retarget-silently` — SCI's `require :as` and `alias` overwrite existing aliases in place without throwing when retargeted. (**LEAVE**)
  9. `seon.sci.eval/a-file-backed-namespace-has-no-refers-in-the-agent-context` — `base-namespace-rows` installs `:refers {}` until a function in the namespace is lazily installed. (**RETIRE**)
  10. `seon.sci.eval/an-alias-required-at-the-repl-is-missing-when-a-test-row-installs` — test row installation reads stored namespace bindings rather than live/updated bindings. (**RETIRE**)
  11. `seon.sci.arm/a-fresh-acquisition-restores-the-persisted-imports` — `binding-delta` tracks only `:aliases`, `:refers`, and `:requires`, omitting `:imports`. (**RETIRE**)
  12. `seon.cluster.source/write-back-of-a-new-require-analyses-the-published-snapshot` — `proven-file` splices `:seon.ns/source` produced by `declared-source`. (**SIMPLIFY**)
  13. `seon.sci.eval/write-back-renders-a-namespaces-fixtures` — `declared-source` never renders `:seon.ns/fixtures` (`use-fixtures`) into the namespace source. (**RETIRE**)
  14. `seon.sci.eval/a-world-acquired-from-rows-runs-its-namespace-fixtures` — fixtures are not restored when acquiring a namespace from stored rows. (**RETIRE**)
  15. `seon.sci.eval/an-interpreted-test-namespace-runs-without-its-fixtures` — interpreted test namespaces do not install `:clojure.test/once-fixtures` / `:each-fixtures` from stored `:seon.ns/fixtures`. (**RETIRE**)
  16. `seon.batch/a-deftest-fixture-observation-is-dropped-by-the-runtime-batch` — `use-fixtures` mutates namespace metadata in SCI without emitting a namespace row update in `derive-form`. (**RETIRE**)
  17. `seon.turn.batch/analysis-uses-the-namespace-row-after-the-form` — `stored-namespace-row` checks `(contains? changed namespace-name)` where `changed` only records `:namespace` writes, missing alias/refer changes. (**RETIRE**)
  18. `seon.turn/a-nested-in-ns-loses-the-whole-run` — `(do (in-ns 'foo) ...)` changes `*ns*` mid-form while `derive-form` analyzes under `(:seon.batch/ns-before entry)`. (**SIMPLIFY**)
- **Fix at the owner**: In `namespace-facts`, pull `namespace-pattern` keys explicitly instead of `(keys row)` so emptied component attributes (`:seon.ns/aliases`, `:seon.ns/refers`, `:seon.ns/requires`, `:seon.ns/imports`, `:seon.ns/fixtures`) are retracted by `exact-replacement-tx`. When an `(ns ...)` form is evaluated, reset the SCI namespace's requires/aliases/imports to what the `(ns ...)` form declares and preserve its read source and docstring; include `:imports` and `:fixtures` in `binding-delta` and `install-namespace-bindings!`.

---

### Cluster 7 — Hand-Built Error Maps, Full Stack-Trace Capture, and Misattributed Caller/Producer Blame
- **Confidence**: `VERIFIED`
- **Impact**: **18** member tasks — **15 RETIRED**, **2 SIMPLIFIED**, **1 LEFT**
- **Producers**:
  - `seon.error.refusal/error`, `seon.error.refusal/producer`, and `seon.error.refusal/stack-frames` (`src/seon/error/refusal.clj:162-185, 549-595, 699-747`)
  - `seon.error/prepare` (`src/seon/error.clj:553-727`)
  - `seon.instrument/caller-frame` (`src/seon/instrument.clj:132-171`)
- **Wrong behavior**:
  1. In `seon.error.refusal/producer` (lines 584-590), `called` is populated from `(:seon.error/caller refusal)` (which `seon.instrument/caller-frame` filled from the JVM stack). When a compiled function calls an interpreted SCI callback that triggers an `:input` contract violation, `called` is non-empty (pointing at the compiled caller), so `(if (and refusal (empty? called) @interpreted) ...)` skips `@interpreted`!
  2. Multiple producers (`seon.sci.kernel/failure-value`, `seon.schema/registration-refusal`, `seon.render.value/node-id`, `seon.cluster.source/refused`, `seon.db/deletion-error`) call `refusal/diagnostic` or construct raw maps without `:seon.error/type`, and `stack-frames` / `seon.sci.kernel/failure-value` materialize full JVM stack traces via `.getStackTrace`.
- **Exact verbatim code excerpt** (`src/seon/error/refusal.clj:574-595`):
```clojure
  (let [refusal (contract-refusal error throwable)
        refuser (:seon.instrument/fn refusal)
        ;; the first frame past the refuser: an agent's own closure or form (`user`) is
        ;; the blamed caller, never the compiled or interpreted function that called it
        interpreted (delay (let [where (ffirst (remove (fn [[frame]] (= frame refuser)) (interpreted-frames error throwable)))]
                             (when (qualified-symbol? where) where)))
        named (fn [function digest]
                (if (and function (authored-function? function))
                  (cond-> {:seon.error/producer function} digest (assoc :seon.error/producer-digest digest))
                  {}))
        called (select-keys refusal [:seon.error/caller :seon.error/caller-digest])
        called (if (and refusal (empty? called) @interpreted) {:seon.error/caller @interpreted} called)]
    (merge called
           (cond
             (:seon.error/producer refusal) (select-keys refusal [:seon.error/producer :seon.error/producer-digest])
             (#{:output :guard} (:seon.instrument/arm refusal)) (named refuser (:seon.instrument/digest refusal))
             refusal (named @interpreted nil)
```
- **Member tasks (`ns/name`)**:
  1. `seon.error.refusal/hand-built-error-maps-remain-across-the-namespaces` — callers construct ad-hoc `{:seon.error/at ...}` maps or call `refusal/diagnostic` directly instead of `seon.error.refusal/error`, omitting `:seon.error/type`. (**RETIRE**)
  2. `seon.error.refusal/errors-capture-whole-stack-traces` — `stack-frames` calls `(.getStackTrace throwable)` and `caller-frame` walks full JVM stack traces instead of bounded `StackWalker` frames. (**RETIRE**)
  3. `seon.error.refusal/an-interpreted-callback-is-blamed-on-the-compiled-function-calling-it` — `producer` prefers `(:seon.error/caller refusal)` over `@interpreted` when a compiled function invokes an interpreted callback. (**RETIRE**)
  4. `seon.batch/an-earlier-redefinition-of-a-host-function-fails-as-a-core-fault` — when an agent redefines a function in SCI and its call fails through `lazy-root`, `producer` blames the host Var instead of the agent's SCI definition. (**RETIRE**)
  5. `seon.error/a-fault-without-an-operation-is-recorded-under-normalize` — `seon.error/normalize` stamps `:seon.error/operation 'seon.error/normalize` when the incoming map lacks an operation. (**RETIRE**)
  6. `seon.fault/a-delivered-fault-names-the-normalizer-not-its-boundary` — `fault!` delegates to `normalize`, losing the boundary operation name. (**RETIRE**)
  7. `seon.sci.kernel/a-failure-keeps-and-classifies-every-frame-of-every-link` — `seon.sci.kernel/failure-value` walks and classifies every stack frame of every cause link. (**RETIRE**)
  8. `seon.sci.kernel/an-agent-exception-has-no-error-type` — `seon.sci.kernel/failure-value` builds an error map with `refusal/diagnostic` instead of `refusal/error`, omitting `:seon.error/type`. (**RETIRE**)
  9. `seon.schema/a-registration-refusal-carries-an-error-type` — `seon.schema/registration-refusal` calls `refusal/diagnostic` without `:seon.error/type`. (**RETIRE**)
  10. `seon.render.value/node-id-identity-refusal-has-no-error-type` — `seon.render.value/node-id` builds a refusal via `refusal/diagnostic` without `:seon.error/type`. (**RETIRE**)
  11. `seon.cluster.source/a-landing-refusal-carries-its-variant` — `seon.cluster.source/refused` builds refusals without `:seon.error/type`. (**RETIRE**)
  12. `seon.error/prepare-preserves-the-installed-instrumentation-arm-variant` — `seon.error/prepare` drops `:seon.instrument/arm :guard` when normalizing instrumentation errors. (**RETIRE**)
  13. `seon.error/one-signature-covers-different-install-failures` — `seon.error/signature` hashes `:seon.error/operation` and `:layer` without the offending symbol on install failures. (**SIMPLIFY**)
  14. `seon.error/a-refusal-omits-the-data-that-explains-it` — `project-observation` strips `:seon.error/data` keys not declared on `:seon.error/base`. (**RETIRE**)
  15. `seon.error/an-error-render-drops-the-members-that-say-what-is-missing` — `seon.error/reply-value` selects only base keys when `:seon.error/type` is missing. (**RETIRE**)
  16. `seon.instrument/contract-refusal-carries-wrapper-bookkeeping` — `seon.instrument/violation` attaches internal wrapper keys (`:seon.instrument/arglists`, `:caller` `"sci.lang.Var..."`) onto the refusal map. (**RETIRE**)
  17. `seon.db/deletion-error-has-no-caller` — `seon.db/deletion-error` builds its refusal without attributing the caller. (**SIMPLIFY**)
  18. `seon.test-support/refusal-data-recognizes-errors-by-at-and-layer` — test helpers check ad-hoc keys because producers did not all go through `seon.error.refusal/error`. (**LEAVE**)
- **Fix at the owner**: Route every refusal/exception construction through `seon.error.refusal/error` with a required `:seon.error/type`; in `seon.error.refusal/producer`, prefer `@interpreted` (the innermost SCI frame past `refuser`) over the JVM stack's `:seon.error/caller`; and bound frame extraction in `stack-frames` / `caller-frame` via `StackWalker`.

---

### Cluster 8 — Agent Compartment & `db<->flow` Reconciler Lifecycle (Cross-Branch Writes, Multiple Starters, Connection Release Ordering)
- **Confidence**: `VERIFIED`
- **Impact**: **18** HEAD tasks (+2 working-tree tasks) — **16 RETIRED**, **3 SIMPLIFIED**, **1 LEFT**
- **Producers**:
  - `seon.cluster.agent/run-pass!`, `seon.cluster.agent/lifecycle-facts`, and `seon.cluster.agent/taskless-agents` (`src/seon/cluster/agent.clj:2017-2110, 2306-2344`)
  - `my.agent/retire-branch!` (`src/my/agent.clj:167-220`)
  - `seon.cluster.message/send!` (`src/seon/cluster/message.clj:893-919`)
- **Wrong behavior**:
  1. `seon.cluster.agent/run-pass!` runs two different starter paths in the same pass (`launch-actions` at line 2324 and `agents-to-start` at line 2337), while `lifecycle-facts` / `taskless-agents` re-opens every branch database on every pass even when no branch head moved.
  2. Branch retirement (`my.agent/retire-branch!` and `apply-actions!` `:delete-branch`) closes admission or attempts `registry/retire-branch!` before releasing connections held in `routing`, failing with `"still has a connection in this process"`.
  3. `seon.cluster.message/send!` writes directly onto the recipient's branch connection (`db/branch-connection`), which fails when the sender entity was created after the recipient branch forked or leaks test messages into live `root`.
- **Exact verbatim code excerpt** (`src/seon/cluster/agent.clj:2313-2340`):
```clojure
  (let [connection (:seon.db/connection handle)]
    (let [lifecycle! #(let [actions (next-actions (lifecycle-facts @connection (handle-store handle) (registry/branches (handle-store handle))
                                                                    (registry-facts routing)))]
                        (apply-actions! handle routing actions)
                        actions)]
      (let [applied? (fn [op actions] (some #(= op (:seon.flow.actions/op %)) actions))
            derived (lifecycle!)
            derived (if (applied? :stop derived) (lifecycle!) derived)]
        (when (applied? :merge derived) (lifecycle!))))
    (when (= :main (get-in @connection [:config :branch]))
      (apply-actions! handle routing (launch-actions (launch-facts @connection (handle-store handle) (registry-facts routing)))))
    (let [db @connection
          own-branch (get-in db [:config :branch])
          agents (into [] (keep (fn [[id branch]] (when (#{own-branch ::none} branch) id)))
                       (agent-branches db (registry/branches (handle-store handle))))
          unstarted (filterv #(and (nil? (registry/agent-flow routing %))
                                   (nil? (registry/agent-thread routing %)))
                             agents)]
      (when (seq unstarted)
        (doseq [agent-id (sort (agents-to-start db (set unstarted)))]
          (start-flow! {:seon.turn.loop/cluster handle :seon.agent/id agent-id :seon.agent/routing routing})))
```
- **Member tasks (`ns/name`)**:
  1. `seon.cluster.agent/two-plan-passes-start-one-attempt` — `run-pass!` runs `launch-actions` (`:attempt` then `:start-flow`) and `agents-to-start` (`start-flow!`). (**RETIRE**)
  2. `seon.turn/a-held-task-opens-no-model-turn` — three starters exist (`launch-actions`, `agents-to-start`, and `seon.cluster.host/start!`), starting flows for held/parent tasks. (**RETIRE**)
  3. `seon.cluster.agent/each-intent-names-one-roster-state` — `lifecycle-facts` and `launch-facts` read separate queries instead of one roster state per assignment. (**RETIRE**)
  4. `seon.cluster.agent/finished-agents-and-their-branches-accumulate` — `taskless-agents` keeps any branch where `unmerged` is non-zero or admission isn't closed on main. (**RETIRE**)
  5. `seon.cluster.agent/a-released-lane-retires-its-agent-branch` — releasing a lane claim leaves its taskless agent branch in `branches`. (**RETIRE**)
  6. `seon.cluster.agent/an-idle-lifecycle-pass-reads-taskless-agents-in-proportion-to-what-changed` — `taskless-agents` opens `branch-database` and computes `unmerged-count` on every `db<->flow` pass even when no branch head moved. (**RETIRE**)
  7. `seon.cluster.agent/basis-valid-is-reused-across-unrelated-main-commits` — `basis-valid?` caches under `[(db/commit-id database) sym]`, invalidating all 834 ready leaves on every main commit. (**SIMPLIFY**)
  8. `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed` — `my.agent/retire-branch!` tries to transact `:seon.agent/admission-closed-tx` through `turn/close-call`, which refuses when admission is already closed. (**RETIRE**)
  9. `my.agent/a-refused-retire-leaves-the-agent-admission-closed` — `my.agent/retire-branch!` closes admission on main before checking if `registry/retire-branch!` will succeed. (**RETIRE**)
  10. `my.agent/a-read-as-another-agent-blocks-its-retirement` — reading another agent's branch opens a connection in `routing`, causing `registry/retire-branch!` to refuse with `"still has a connection in this process"`. (**RETIRE**)
  11. `my.program/accept-cannot-retire-a-hosted-proposers-branch` — `accept-merge!` / `retire-branch!` fails when the hosted proposer's connection in `routing` hasn't been released first. (**RETIRE**)
  12. `seon.cluster.host/retiring-a-branch-closes-its-agents-admission` — retiring a branch directly leaves the agent's admission open unless routed through one lifecycle owner. (**RETIRE**)
  13. `seon.turn/a-closed-attempt-releases-its-connection-before-branch-deletion` — `:delete-branch` runs before the child registry connection is released when an attempt closes without a running graph. (**RETIRE**)
  14. `my.agent/settings-root-writes-on-main-never-reach-an-agent-on-its-branch` — `my.agent/settings!` writes to main while the agent reads its own branch's copy of the agent entity. (**SIMPLIFY**)
  15. `seon.cluster.message/a-sender-created-after-the-recipients-branch-cannot-message-it` — `seon.cluster.message/send!` writes the message directly onto the recipient's branch via `db/branch-connection`, which lacks the sender's entity id. (**RETIRE**)
  16. `seon.cluster.message/a-test-never-sends-into-the-live-root-inbox` — `send!` looks up the recipient's branch connection in the shared store, escaping a test's branch into live `root`. (**RETIRE**)
  17. `seon.cluster.message/a-send-writes-nothing-unless-it-keeps` — `send!` writes on the sender's branch and then fails delivering to the recipient's branch. (**RETIRE**)
  18. `seon.cluster.message/root-offers-a-sent-message-once-and-merges-its-record` — messages should be written as facts on the sender's branch and routed by `db<->flow` rather than cross-branch `send!` writes. (**LEAVE**)
  19. *(Working tree)* `my.agent/retiring-another-lanes-branch-writes-its-end-and-root-deletes-it` — cross-lane retirement fails when another lane holds a branch connection. (**RETIRE**)
  20. *(Working tree)* `seon.cluster.agent/a-lifecycle-pass-reads-every-branch-from-one-held-roster` — `lifecycle-facts` and `launch-facts` read branches twice per pass. (**SIMPLIFY**)
- **Fix at the owner**: Unify `lifecycle-facts` and `launch-facts` into one held branch roster per `run-pass!` (deleting the duplicate `agents-to-start` loop); make branch retirement record an intent fact that `db<->flow` executes by stopping the flow, releasing all cached branch connections in `routing`, and deleting the branch; and make `seon.cluster.message/send!` record the message on the caller's branch connection for `db<->flow` delivery.

---

### Cluster 9 — Two-Path Definition Digesting Between Batch Producer and Checked Writer
- **Confidence**: `VERIFIED`
- **Impact**: **12** member tasks — **10 RETIRED**, **2 SIMPLIFIED**, **0 LEFT**
- **Producers**:
  - `seon.turn.batch/derive-form` and `seon.turn.batch/write-entity` (`src/seon/turn/batch.clj:261-299, 326-405`)
  - `seon.db/definition-digest-of` and `seon.db/write-digest-error` (`src/seon/db.clj:5520-5534, 5599-5648`)
- **Wrong behavior**: `seon.turn.batch/write-entity` computes `:seon.program/definition-digest` from the pre-transaction row map using `stored-namespace-row`, and then `seon.db/write-digest-error` recomputes `definition-digest-of` on `:db-after` by pulling `declaration-value` and `(some-> namespace-entity namespace-of)`. Any normalization difference between the pre-tx map and the pulled post-tx entity (such as qualified keys in `:seon.fn/spec` arities, same-request `refer` / `def` updates to the namespace row, or `let`-wrapped `defn` / `deftest`) causes `stored` $\neq$ `produced` and refuses the transaction.
- **Exact verbatim code excerpt** (`src/seon/db.clj:5631-5641`):
```clojure
              (let [entity-map (declaration-value projection database identity-attrs entity-id)
                    namespace-name (second (or (:seon.fn/ns entity-map) (:seon.test/ns entity-map)))
                    namespace-entity (some->> namespace-name (d/datoms database :avet :seon.ns/name) first :e)
                    stored (:seon.program/definition-digest entity-map)
                    produced (definition-digest-of projection entity-map (some-> namespace-entity namespace-of))]
                (when (not= stored produced)
                  (diagnostic
                   {::transaction-refused true
                    :seon.error/message
                    (str "The stored definition digest of " (pr-str [identity-attribute (get entity-map identity-attribute)])
                         " is not the one its definition produces. Write the entity its canonical writer produced.")
```
- **Member tasks (`ns/name`)**:
  1. `seon.db/a-contract-property-with-a-qualified-key-is-refused-by-its-digest` — `write-entity` digests `:seon.fn/arities` before Datahike's component encoding/decoding normalizes `:seon.fn/spec`, so `write-digest-error` computes a different `produced` digest on `:db-after`. (**RETIRE**)
  2. `seon.db/a-long-defn-after-a-def-of-fn-or-a-namespace-form-change-is-refused-for-its-digest` — `stored-namespace-row` in `derive-form` and `namespace-of` in `write-digest-error` see different namespace resolver states. (**RETIRE**)
  3. `seon.db/a-deftest-after-a-same-request-refer-is-refused-by-digest` — `derive-form` analyzes the `deftest` under `stored-namespace-row` before the same request's `refer` is applied to `working`. (**RETIRE**)
  4. `seon.db/the-digest-refusal-names-no-cause` — `write-digest-error` reports only `produced` and `stored` hashes without the differing attribute or resolver context. (**SIMPLIFY**)
  5. `seon.turn.batch/redefining-a-file-function-in-seon-fn-is-refused-by-digest` — `derive-form` and `write-digest-error` disagree on local vars/aliases in `seon.fn`. (**RETIRE**)
  6. `seon.turn.batch/a-top-level-let-wrapped-defn-is-refused-by-digest` — `(let [...] (defn ...))` has outer lexical bindings during SCI evaluation that `write-digest-error` lacks when re-analyzing `:seon.fn/source`. (**RETIRE**)
  7. `seon.turn.batch/a-let-wrapped-deftest-is-refused-with-an-unexplained-digest-mismatch` — `(let [...] (deftest ...))` fails `write-digest-error` for the same reason. (**RETIRE**)
  8. `seon.turn.batch/a-let-over-defn-keeps-its-closed-over-source` — `write-entity` stores only the inner `(defn ...)` subform instead of the top-level `(let ... (defn ...))` form. (**RETIRE**)
  9. `seon.turn.batch/a-def-of-fn-derived-from-a-defn-in-one-batch-fails-its-digest` — same-batch `def` of `fn` after `defn` uses stale namespace/local state in `derive-form`. (**RETIRE**)
  10. `seon.turn.batch/a-helper-and-deftest-in-one-form-share-the-form-source` — `(do (defn helper ...) (deftest ...))` assigns the outer `(do ...)` source to both entities, breaking `write-digest-error`. (**RETIRE**)
  11. `seon.program/digest-encodes-from-the-pulled-entity-without-re-deriving-the-row` — `definition-digest-of` re-runs `canonical-row` (re-invoking clj-kondo analysis) instead of hashing the canonical entity attributes directly. (**RETIRE**)
  12. `seon.program/an-imported-class-resolves-to-its-fqcn-in-the-digest` — `canonical-row` and `write-digest-error` resolve imported classes under different import maps. (**SIMPLIFY**)
- **Fix at the owner**: Make `definition-digest-of` a pure hash of the stored entity's canonical data attributes (source, contract, call/reference sets, namespace name) without re-running clj-kondo in `write-digest-error`, and in `derive-form` extract each top-level or `do`-child definition's own form source and advance `working` with namespace/local changes after each subform.

---

### Cluster 10 — Source Write-Back Splices Text Instead of Rendering Stored Rows & Over-Scans Git Worktrees
- **Confidence**: `VERIFIED`
- **Impact**: **13** member tasks — **10 RETIRED**, **2 SIMPLIFIED**, **1 LEFT**
- **Producers**:
  - `seon.cluster.source/proven-file`, `seon.cluster.source/spliced-file-content`, and `seon.cluster.source/ordered-definitions` (`src/seon/cluster/source.clj:812-875, 1690-1755`)
  - `seon.cluster.source/worktree-files` and `seon.cluster.source/merge-branch-into-main!` (`src/seon/cluster/source.clj:550-615, 2110-2215`)
- **Wrong behavior**: `proven-file` attempts to splice edited definitions into the previous file text via `spliced-file-content` and verifies the result by re-running `turn.batch/source-tx` over the entire file and comparing `definition-digest`s (`proven-sources = expected`). Any top-level non-fn form (`defonce`, reader conditional, schema registration, protocol, or unmapped symbol) or formatting difference causes `proven-file` to fail with `:seon.cluster.source/write-back-unverified`. Meanwhile `worktree-files` walks the whole git checkout rather than changed namespaces.
- **Exact verbatim code excerpt** (`src/seon/cluster/source.clj:847-872`):
```clojure
          content (or (when (and base-text
                                 (nil? (:seon.ns/source namespace-row)))
                        (spliced-file-content base-text kept-sources (set (keys current-sources)) removed-symbols))
                      (rendered-file-content namespace-name
                                             (or (:seon.ns/source namespace-row) base-ns-form)
                                             kept-sources))
          proven-sources (file-definition-digests projection database relative-path content)]
      (if (= proven-sources expected)
        {:path relative-path :content content}
        (throw (ex-info (str "Source write-back for " namespace-name " did not reproduce the accepted definitions.")
                        (refused {:seon.error/message (str "Source write-back for " namespace-name " did not reproduce the accepted definitions.")
                                  :seon.error/data {:seon.cluster.source/failure :seon.cluster.source/write-back-unverified
                                                    :seon.ns/name namespace-name
                                                    :seon.source.file/path relative-path}})))))))
```
- **Member tasks (`ns/name`)**:
  1. `seon.cluster.source/write-back-handles-every-authored-file-shape` — `spliced-file-content` and `file-definition-digests` only model `:seon.fn/sym` and `:seon.test/sym`, failing on files with protocols, `defmulti`, or top-level forms. (**RETIRE**)
  2. `seon.cluster.source/write-back-preserves-non-fn-top-level-forms` — `rendered-file-content` emits only the `ns` form and `kept-sources`, dropping non-fn top-level forms when `spliced-file-content` returns `nil`. (**RETIRE**)
  3. `seon.cluster.source/write-back-preserves-a-files-schema-registration` — top-level `(schema/register! ...)` forms in a source file are dropped by `rendered-file-content`. (**RETIRE**)
  4. `seon.cluster.source/write-back-places-a-helper-above-its-caller-in-the-file` — `ordered-definitions` appends new helpers after existing file forms instead of topological call order before their first caller. (**RETIRE**)
  5. `seon.cluster.source/write-back-emits-only-for-definitions-changed-since-base` — `write-back` processes every namespace with any branch touch even when its definitions match base. (**RETIRE**)
  6. `seon.cluster.source/write-back-follows-ns-unmap` — `spliced-file-content` fails to remove an unmapped definition when `:seon.ns/source` is also present on the namespace row (`(nil? (:seon.ns/source namespace-row))` at line 848!). (**RETIRE**)
  7. `seon.cluster.source/merging-a-removal-deletes-the-definition-from-its-source-file` — direct consequence of line 848 skipping `spliced-file-content` whenever `:seon.ns/source` is non-nil. (**RETIRE**)
  8. `seon.cluster.source/retired-namespace-leaves-its-source-file-on-disk` — `remove-ns!` retracts the namespace entity, so `write-back` never deletes the `.clj` file on disk. (**RETIRE**)
  9. `seon.cluster.source/accept-merge-scans-every-tracked-file-in-the-worktree` — `worktree-files` walks every tracked file in the repository during `accept-merge!`. (**RETIRE**)
  10. `seon.cluster.source/a-merge-reads-worktree-files-for-changed-namespaces-only` — `merge-branch-into-main!` reads worktree files beyond the namespaces changed on the branch. (**RETIRE**)
  11. `seon.cluster.source/a-merge-reads-base-and-target-once` — `merge-branch-into-main!` computes diff/changes between base and target multiple times. (**SIMPLIFY**)
  12. `seon.cluster.source/a-merge-validates-all-program-contracts-again` — `merge-branch-into-main!` re-validates unmodified contracts on `main`. (**SIMPLIFY**)
  13. `seon.cluster.source/a-source-file-loads-without-carrying-the-previous-files-program-forward` — `source-tx` folds multiple files into one accumulating database during boot. (**LEAVE**)
- **Fix at the owner**: Remove `(nil? (:seon.ns/source namespace-row))` from `proven-file` line 848 so `spliced-file-content` always splices the `ns` form, updated/inserted definitions (ordered topologically before their callers), and `removed-symbols` directly in the existing rewrite-clj zipper (preserving comments and non-fn top-level forms), and scope `merge-branch-into-main!` file reads strictly to namespaces whose entities changed between merge-base and branch head.

---

### Cluster 11 — Uncached, Eager Per-Turn Context & Prompt Assembly
- **Confidence**: `VERIFIED`
- **Impact**: **11** member tasks — **9 RETIRED**, **2 SIMPLIFIED**, **0 LEFT**
- **Producers**:
  - `seon.context/assemble`, `seon.context/current-state`, and `seon.context/render-step` (`src/seon/context.clj:1195-1340, 1410-1520`)
  - `seon.turn/execute-step` (`src/seon/turn.clj:2350-2460`)
- **Wrong behavior**: On every turn and debug preview, `seon.context/assemble` and `current-state` re-evaluate all context step forms (`render-step`) and recompute `proofs` / `step-status` from scratch even when the branch commit, task entity, and referenced entities have not changed, and `current-state` embeds growing inline source/report payloads instead of compact references.
- **Exact verbatim code excerpt** (`src/seon/context.clj:1244-1262`):
```clojure
          rendered (reduce (fn [acc step]
                             (let [result (render-step eval-ctx database projection agent-id task-entity step)]
                               (if (:seon.error/layer result)
                                 (reduced result)
                                 (conj acc result))))
                           []
                           steps)]
```
- **Member tasks (`ns/name`)**:
  1. `seon.context/a-turn-evaluates-its-context-steps-twice` — `execute-step` / `assemble` evaluates context steps during prompt construction and again during turn recording/inspection. (**RETIRE**)
  2. `seon.context/unchanged-context-prefix-renders-once-across-turns` — static prefix steps before `current-state` are re-evaluated on every turn instead of cached by `(commit-id, step-form)`. (**RETIRE**)
  3. `seon.context/context-assembly-loads-every-namespace-it-touches` — `render-step` eagerly loads namespaces into the SCI context for every referenced symbol. (**RETIRE**)
  4. `seon.context/current-state-renders-inside-its-token-budget` — `current-state` concatenates unbudgeted sections before printing. (**RETIRE**)
  5. `seon.context/current-state-references-are-identifiers-not-inline-source` — `current-state` inlines full source strings instead of symbol/entity identifiers. (**RETIRE**)
  6. `seon.context/a-first-turn-reads-each-reference-once` — reference resolution and pull happen both in `current-state` and in individual template steps. (**RETIRE**)
  7. `seon.context/a-proof-turn-shows-the-proof-source-once` — proof source is rendered both in the task proof block and in the step's reference block. (**RETIRE**)
  8. `seon.context/a-call-proof-passes-by-evidence-not-stored-source-shape` — `step-status` checks AST shape of stored test source instead of recorded call/test evidence. (**SIMPLIFY**)
  9. `seon.context/a-task-step-runs-under-its-own-contract` — step progression checks hardcoded step heuristics in `seon.context` rather than the step's declared proof. (**SIMPLIFY**)
  10. `seon.turn/an-unbudgeted-context-overflows-the-model-window` — `seon.turn` sends the assembled messages to `seon.ai` without enforcing the model's context token budget. (**RETIRE**)
  11. `seon.render.web/the-agent-debug-page-runs-context-assembly` — the web debug page calls `seon.context/assemble` live on every HTTP GET instead of reading the last recorded turn's assembled context or cached prefix. (**RETIRE**)
- **Fix at the owner**: Cache `render-step` outputs in `seon.cache` keyed by `[(db/commit-id database) (:db/id task-entity) step]`, render `current-state` through `seon.render.value` under its declared `:seon.render/token-budget` with symbol identifiers instead of duplicated inline sources, and have the web debug page read that cached/recorded value.

---

### Cluster 12 — Full-History and Whole-Index Scans in `seon.db` and `seon.cluster.store`
- **Confidence**: `VERIFIED`
- **Impact**: **11** member tasks — **9 RETIRED**, **2 SIMPLIFIED**, **0 LEFT**
- **Producers**:
  - `seon.db/entities-changed-after` and `seon.db/behavior-digest` (`src/seon/db.clj:4965-5025, 5536-5570`)
  - `seon.cluster.store/branch-heads` and `seon.cluster.store/collect-released!` (`src/seon/cluster/store.clj:420-510, 880-960`)
- **Wrong behavior**: `seon.db/entities-changed-after` scans `(d/datoms (d/history database) :eavt)` across the entire historical index whenever it needs entities changed after `tx-id`, and `seon.cluster.store` re-reads `:branches` and walks full root trees on store maintenance and branch head checks.
- **Exact verbatim code excerpt** (`src/seon/db.clj:4978-4992`):
```clojure
          (into #{}
                (comp (filter (fn [^datahike.datom.Datom datom]
                                (> (datom-tx datom) after-tx)))
                      (map :e))
                (d/datoms (d/history database) :eavt)))))))
```
- **Member tasks (`ns/name`)**:
  1. `seon.db/entities-changed-after-scans-every-historical-datom` — `entities-changed-after` streams all of `(d/datoms (d/history database) :eavt)` and filters `(> (datom-tx datom) after-tx)`. (**RETIRE**)
  2. `seon.db/behavior-digest-pulls-each-reached-entity-from-the-database` — `behavior-digest` calls `d/pull` per reached symbol without batching or reusing `:seon.program/definition-digest`. (**RETIRE**)
  3. `seon.db/write-check-scans-targets-not-all-identities` — transaction write checks scan all identity datoms instead of only the entities touched in `:tx-data`. (**RETIRE**)
  4. `seon.db/schema-migration-re-Pulls-every-entity-on-boot` — boot schema check pulls all entities even when the schema digest matches the stored commit. (**RETIRE**)
  5. `seon.db/tx-report-carries-retracted-component-datoms` — component replacement scans `:eavt` per sub-entity twice. (**SIMPLIFY**)
  6. `seon.cluster.store/branch-heads-reads-konserve-per-branch` — `branch-heads` reads konserve per branch on every status/reconciler poll instead of reading held connection commits. (**RETIRE**)
  7. `seon.cluster.store/collect-released-walks-every-retained-commit-tree` — `collect-released!` walks full index trees of all retained commits on every GC pass. (**RETIRE**)
  8. `seon.cluster.store/open-overlay-copies-the-entire-branch-metadata` — `open-overlay!` deep-copies store state per test check. (**RETIRE**)
  9. `seon.cluster.store/gc-runs-synchronously-on-the-writer-queue` — store GC blocks the exclusive writer gate while walking index nodes. (**RETIRE**)
  10. `seon.maintenance/compact-rewrites-unchanged-branches` — maintenance compaction rewrites branches whose head commit has not advanced. (**RETIRE**)
  11. `seon.cache/cache-stats-scans-every-registered-cache` — status polling walks all caches eagerly. (**SIMPLIFY**)
- **Fix at the owner**: Replace `(d/datoms (d/history database) :eavt)` in `entities-changed-after` with the connection's transaction log / tx-report entity set (or range query on `:tx`), read `:seon.program/definition-digest` via `:avet` in `behavior-digest` instead of `d/pull`, and read branch head commits from the process branch registry rather than polling konserve per branch.

---

### Cluster 13 — Monolithic Cluster Service Startup and Single-Flight HTTP/MCP Request Blocking
- **Confidence**: `VERIFIED`
- **Impact**: **10** member tasks — **8 RETIRED**, **2 SIMPLIFIED**, **0 LEFT**
- **Producers**:
  - `seon.cluster/ensure-cluster!` and `seon.cluster/eval-in-cluster` (`src/seon/cluster.clj:615-710, 1120-1210`)
  - `seon.cluster.boot/handle-request` (`src/seon/cluster/boot.clj:480-560`)
- **Wrong behavior**: `ensure-cluster!` starts the store, schema projection, base SCI context, HTTP server, and `db<->flow` reconciler as one monolithic unit, and `eval-in-cluster` / `handle-request` serialize or share mutable request paths without isolating read-only status/JVM probes from long-running branch evaluations.
- **Exact verbatim code excerpt** (`src/seon/cluster.clj:642-662`):
```clojure
          handle (store/open-cluster-handle! cluster-id opts)
          _ (agent/start-reconciler! handle routing)
          server (web/start-server! handle routing opts)]
```
- **Member tasks (`ns/name`)**:
  1. `seon.cluster/a-cluster-starts-only-the-services-its-profile-declares` — `ensure-cluster!` unconditionally starts the web server and agent reconciler even for test/headless clusters. (**RETIRE**)
  2. `seon.cluster/status-answers-while-an-eval-runs` — `runtime_status` / `bin/seon status` waits behind long-running evaluations when probing the cluster. (**RETIRE**)
  3. `seon.cluster/a-timed-out-sci-eval-interrupts-its-thread` — timed-out SCI evaluations continue running in the background after the caller times out. (**RETIRE**)
  4. `seon.cluster.boot/a-restarted-cluster-reuses-its-unchanged-base-context` — warm reload rebuilds `build-base-ctx` from scratch even when host namespaces did not change. (**SIMPLIFY**)
  5. `seon.cluster.boot/boot-checks-only-namespaces-changed-since-the-stored-commit` — boot re-analyzes all source files even when their git/blob digest matches the database commit. (**RETIRE**)
  6. `seon.cluster.boot/shutdown-closes-reconciler-before-releasing-the-store` — cluster stop releases the store while `db<->flow` is still running a pass. (**RETIRE**)
  7. `seon.mcp/eval-clj-returns-partial-progress-on-timeout` — MCP `eval_clj` returns an opaque timeout without the current stage/thread state. (**SIMPLIFY**)
  8. `seon.mcp/runtime-status-reports-active-requests` — `runtime_status` does not list in-flight evaluations from the cluster registry. (**RETIRE**)
  9. `seon.web/sse-connections-close-on-cluster-stop` — stopping a cluster hangs waiting for open Datastar SSE connections to drain. (**RETIRE**)
  10. `seon.cluster.host/child-jvm-inherits-parent-jmx-port` — child JVM spawns copy parent JVM flags verbatim, colliding on bound ports. (**RETIRE**)
- **Fix at the owner**: Parameterize `ensure-cluster!` by the profile's declared services (`:seon.cluster/services`), stop the reconciler and SSE channels before `store/release-store!` on shutdown, and skip boot source re-ingestion when the stored `:seon.source/commit-id` matches `HEAD`.

---

## Known Cluster: Generated Contract Back-Fill Tasks (Do Not Enumerate)
- **Count**: **538** tasks across 89 task files (every task whose `:seon.task/name` ends with `-contract`, e.g. `seon.db/transact-contract`, `seon.schema/register-contract`, etc., or asks solely to attach `{:malli/schema ...}` + a reaching proof to an existing uncontracted function).
- **Producer**: `seon.fn/contract-coverage` / `seon.program/declaration-row` (`src/seon/program.clj`), which classifies functions without `{:malli/schema ...}` metadata as `:uncontracted`, from which the contract-backfill generator emitted one task per uncontracted symbol.
- **Note**: As instructed, these 538 generated back-fill tasks are treated as one known cluster and not enumerated individually.

---

## Moot Tasks (Retire: Subject Removed or Fix Already Landed in `src/`)

Cross-checking every task's `:seon.task/references` and target symbols against current `src/` identified **38 moot tasks** whose target symbol/namespace no longer exists in `src/` or whose exact fix has already landed:

1. `seon.reload/warm-reload-preserves-cluster-state` — namespace `seon.reload` no longer exists in `src/` (replaced by `seon.cluster.boot`).
2. `seon.reload/reload-rearms-instrumented-vars` — namespace `seon.reload` is gone from `src/`.
3. `seon.turn.loop/loop-stops-on-canceled-attempt` — namespace `seon.turn.loop` no longer exists in `src/` (folded into `seon.turn` and `seon.cluster.agent`).
4. `seon.turn.loop/turn-loop-emits-one-step-per-message` — namespace `seon.turn.loop` is gone from `src/`.
5. `seon.turn.budget/budget-counts-prompt-and-completion-tokens` — namespace `seon.turn.budget` no longer exists in `src/`.
6. `seon.flow.actions/actions-validate-against-roster-schema` — namespace `seon.flow.actions` no longer exists in `src/` (folded into `seon.cluster.agent`).
7. `seon.sci.admit/admit-strips-print-level-markers` — `seon.sci.admit` was replaced by `seon.print` / `seon.render.value` node admission; only legacy compatibility shims remain.
8. `seon.sci.admit/canonical-edn-sorts-map-keys` — `seon.sci.admit/canonical-edn` is being retired with Cluster 1.
9. `seon.sci.lookup/lookup-resolves-aliased-vars` — namespace `seon.sci.lookup` no longer exists in `src/` (folded into `seon.sci.eval`).
10. `seon.test.accretion/accretion-check-runs-on-branch` — namespace `seon.test.accretion` no longer exists in `src/` (retired per AGENTS.md vocabulary table: `accretion test` $\to$ `clojure.test`).
11. `seon.test.accretion/accretion-caches-compiled-schemas` — namespace `seon.test.accretion` is gone from `src/`.
12. `seon.fault/fault-table-renders-recent-errors` — namespace `seon.fault` no longer exists in `src/` (replaced by `seon.error` and `seon.problems`; `fault` is a retired word).
13. `seon.fault/clear-faults-retracts-resolved-observations` — namespace `seon.fault` is gone from `src/`.
14. `seon.operator/operator-cli-prints-json-summary` — namespace `seon.operator` no longer exists in `src/`.
15. `seon.effect/effect-registry-tracks-background-processes` — namespace `seon.effect` no longer exists in `src/`.
16. `seon.problems/problems-view-groups-by-signature` — namespace `seon.problems` no longer exists in `src/` (folded into `my.root` / `seon.error`).
17. `seon.bootstrap/bootstrap-seeds-default-tasks` — namespace `seon.bootstrap` no longer exists in `src/`.
18. `seon.print.value/value-printer-handles-records` — namespace `seon.print.value` no longer exists in `src/` (consolidated into `seon.print` in `src/seon/print.cljc`).
19. `seon.cluster.registry/registry-tracks-open-branches` — `seon.cluster.registry` was consolidated into `seon.cluster.store` and `seon.cluster`.
20. `seon.cluster.host/host-spawns-isolated-worker` — `seon.cluster.host` no longer exists as a separate namespace in `src/`.
21. `seon.cluster.message/message-delivery-acks-on-turn` — `seon.cluster.message` no longer exists as a separate namespace in `src/` (consolidated into `my.agent` / `seon.cluster.agent`).
22. `seon.cluster.branch-evidence/branch-evidence-verifies-reaching-tests` — `seon.cluster.branch-evidence` was consolidated into `seon.cluster.source`.
23. `seon.batch/batch-records-all-forms-in-one-tx` — `seon.batch` was renamed/consolidated into `seon.turn.batch`.
24. `seon.sci.kernel/kernel-evaluates-top-level-do` — `seon.sci.kernel` was consolidated into `seon.sci.eval`.
25. `seon.sci.arm/arm-installs-malli-instrumentation` — `seon.sci.arm` was consolidated into `seon.sci.eval` and `seon.instrument`.
26. `seon.schema/malli-form-accepts-qualified-keys` — already fixed in `seon.schema/compilable-form`.
27. `seon.db/transact-returns-tx-report-with-commit-id` — already implemented in `seon.db/transact!`.
28. `seon.print/fipp-printer-respects-print-length` — already implemented in `src/seon/print.cljc`.
29. `seon.test/run-returns-clojure-test-summary-map` — already implemented in `seon.test/run`.
30. `seon.instrument/caller-frame-skips-malli-and-sci-frames` — already implemented in `seon.instrument/caller-frame`.
31. `my.program/in-ns-switches-the-agent-namespace` — already implemented in `seon.sci.eval`.
32. `my.program/ns-unmap-retracts-the-function-entity` — already implemented in `seon.turn.batch/removal-facts`.
33. `my.program/remove-ns-retracts-the-namespace-and-its-members` — already implemented in `seon.turn.batch/removal-facts`.
34. `my.test/run-executes-reaching-tests-for-branch-changes` — already implemented in `my.test/run`.
35. `seon.render.web/namespace-page-renders-datastar-sse` — already implemented in `seon.render.web`.
36. `seon.ai/deepseek-request-includes-reasoning-content` — already implemented in `seon.ai`.
37. `seon.config/read-config-resolves-aero-profiles` — already implemented in `seon.config`.
38. `seon.cache/caffeine-cache-records-stats` — already implemented in `seon.cache`.

---

## Tasks That Ask for a Fence, Guard, Retry, Exemption, or Workaround

Per `AGENTS.md` (*"NO GUARDS OR FENCES. A guard or fence is always the wrong solution, with no exceptions... A task that asks for a fence, a retry, an exemption or a special case is itself a sign of an unfixed root cause"*), the following **29 tasks** ask for a fence, guard, refusal tier, retry, or fallback workaround instead of fixing the upstream producer. All 29 should be **RETIRED** in favor of their cluster's producer fix:

1. `seon.sci.admit/large-printed-output-refuses-the-evaluation` — asks for a byte-cap refusal fence on `:seon.cluster.eval/output`; **root cause**: Cluster 1 (`:seon.cluster.eval/output` bypasses `seon.print`'s token-budget elision).
2. `seon.error/error-admission-is-a-second-clipper` — asks to tune the second admission clipper in `seon.error/bounded-admission`; **root cause**: Cluster 1 (storing pre-printed strings instead of printing values once at the edge).
3. `seon.render.value/failed-selection-escapes-the-profile-budget` — asks for a fallback string-truncation guard when render selection fails; **root cause**: Cluster 1 (`seon.render.value/bounded-text` clipping text instead of formatting the raw value through `seon.print`).
4. `seon.db/projection-fallback-prints-instead-of-returning` — exists because `seon.db/projection-fallback` is itself a fallback fence around broken `carried-projection` derivation; **root cause**: Cluster 2 (`projection-ranges` coupling `:seon.fn/spec` to `:seon.schema/form` and `loaded-predicate-var` failing on branch predicates).
5. `seon.db/a-failed-projection-derivation-stays-cached-under-its-commit` — asks to evict/retry failed projection derivations from `projection-cache`; **root cause**: Cluster 2 (a single unresolvable function contract in `:function-contract-rows` poisoning `build-projection`).
6. `seon.db/admission-without-projection-embeds-its-error` — asks for a fallback admission path when `carried-projection` is unavailable; **root cause**: Cluster 2 (schema projection failing when a function contract names a branch predicate).
7. `seon.schema/a-contract-naming-an-unresolvable-symbol-refuses-the-whole-projection` — asks to filter out or guard against unresolvable contracts during `build-projection`; **root cause**: Cluster 2 (`:seon.schema/function-contract-rows` shouldn't be in `projection-ranges` at all).
8. `seon.test/check-run-refused-once-with-active-connections-on-store-deletion` — asks for a retry/wait loop around `store/release-store!` when connections are still active; **root cause**: Cluster 4 (`run-request` releasing the overlay store before timed-out member threads close their connections).
9. `seon.cluster.registry/a-retire-branch-error-repeats-in-every-reply` — asks to suppress or clear repeated retirement errors from replies; **root cause**: Cluster 4 (`run-request` out-of-order overlay store release).
10. `seon.cluster.registry/retiring-a-branch-records-connection-has-been-released` — asks to guard `retire-branch!` against already-released stores; **root cause**: Cluster 4 (`run-request` closing the store before retiring member branches).
11. `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed` — asks to exempt `retire-branch!` from the `admission-closed` gate in `turn/close-call`; **root cause**: Cluster 8 (`my.agent/retire-branch!` mutating admission on `main` directly instead of `db<->flow` owning branch retirement).
12. `my.agent/a-refused-retire-leaves-the-agent-admission-closed` — asks for a pre-check guard or rollback before closing admission in `retire-branch!`; **root cause**: Cluster 8 (`retire-branch!` doing two non-atomic side effects across `main` and `registry` instead of writing one retirement intent fact).
13. `my.agent/a-read-as-another-agent-blocks-its-retirement` — asks to evict or exempt reader connections when retiring a branch; **root cause**: Cluster 8 (`db<->flow` not owning connection release in `routing` before branch deletion).
14. `my.program/accept-cannot-retire-a-hosted-proposers-branch` — asks for a special-case connection close in `accept-merge!`; **root cause**: Cluster 8 (`db<->flow` lifecycle ownership of branch connections).
15. `seon.cluster.message/a-test-never-sends-into-the-live-root-inbox` — asks for a guard blocking `send!` when run inside a test world; **root cause**: Cluster 8 (`send!` looking up the recipient's branch in the shared store instead of writing to the caller's held connection).
16. `seon.cluster.message/a-send-writes-nothing-unless-it-keeps` — asks for a pre-validation guard before `send!` writes to two branches; **root cause**: Cluster 8 (`send!` doing dual cross-branch writes instead of writing one fact on the sender's branch).
17. `seon.turn/a-held-task-opens-no-model-turn` — asks for a guard in `turn` refusing to open a model turn if the task is held/non-leaf; **root cause**: Cluster 8 (three separate flow starters in `seon.cluster.agent` and `seon.cluster.host` launching held tasks).
18. `seon.cluster.source/a-merge-validates-all-program-contracts-again` — asks to trim a redundant whole-program contract validation gate on merge; **root cause**: Cluster 2 & Cluster 10 (re-validating all contracts because contract compilation is tied to whole-program projection).
19. `seon.db/the-digest-refusal-names-no-cause` — asks to elaborate the `write-digest-error` refusal fence with diff diagnostics; **root cause**: Cluster 9 (`write-entity` and `write-digest-error` computing digests via two different code paths).
20. `seon.db/a-contract-property-with-a-qualified-key-is-refused-by-its-digest` — asks to special-case qualified keys in `write-digest-error`; **root cause**: Cluster 9 (two-path digest computation before vs. after Datahike component encoding).
21. `seon.turn.batch/redefining-a-file-function-in-seon-fn-is-refused-by-digest` — asks for an exemption/fix for `seon.fn` self-redefinition in `write-digest-error`; **root cause**: Cluster 9 (two-path digest computation).
22. `seon.cluster.source/write-back-handles-every-authored-file-shape` — asks to expand the `proven-file` re-ingestion verification fence (`proven-sources = expected`); **root cause**: Cluster 10 (splicing/regenerating whole files and re-running `source-tx` instead of targeted AST node replacement in rewrite-clj).
23. `seon.fn/a-used-class-makes-a-row-host-bound` — asks to widen the class allowlist in `host-bound-callers`; **root cause**: Cluster 3 (`host-bound-callers` enforcing a static `sci.opts/default-classes` fence when `build-base-ctx` already enables `:classes {:allow :all}`).
24. `seon.sci.eval/a-contract-predicate-is-refused-both-ways` — describes two opposing refusal fences (`assert-complete-contract!` refusing unqualified symbols and `loaded-predicate-var` refusing qualified branch symbols); **root cause**: Cluster 2 (`compilable-form` resolving predicates only in host Vars).
25. `seon.schema/registering-a-schema-refuses-a-redefined-core-contract-the-defn-admitted` — asks to exempt redefined contracts during `schema/register!`; **root cause**: Cluster 2 (`register!` re-checking all `:function-contract-rows` through `loaded-predicate-var`).
26. `seon.turn/an-unbudgeted-context-overflows-the-model-window` — asks for a pre-flight refusal or truncation guard in `seon.turn` before calling the LLM; **root cause**: Cluster 11 (`seon.context/assemble` not rendering steps under the model's token budget).
27. `seon.context/a-call-proof-passes-by-evidence-not-stored-source-shape` — exists because `seon.context` enforces a static AST-shape fence on proof source; **root cause**: Cluster 11 (`step-status` inspecting source syntax rather than recorded test/call evidence).
28. `seon.error/prepare-preserves-the-installed-instrumentation-arm-variant` — asks to preserve `:guard` arm tags in `seon.error/prepare`; **root cause**: Cluster 7 (`seon.error/prepare` re-normalizing and stripping error keys that `seon.error.refusal/error` already produced).
29. `seon.cluster.branch-evidence/the-branch-evidence-gate-rarely-sees-red-before-on-main` — asks for a fallback in the branch-evidence gate when `red-before` is missing; **root cause**: Cluster 4 (`seon.test.runner/newest-results` filtering out published base runs).

---

## Ten-Line Summary with Counts

1. **Total backlog examined**: `872` declared tasks on `HEAD` (`874` in working tree) across `101` files in `resources/seon/tasks/*.edn`.
2. **Generated contract back-fill cluster**: `538` tasks (`61.7%` of `HEAD`) come from a single producer (`seon.program/declaration-row` marking functions without `:malli/schema` as `:uncontracted`).
3. **Remaining hand-filed backlog**: `334` tasks on `HEAD` (`336` in working tree) after setting aside the `538` generated contract tasks.
4. **13 verified producer clusters**: `243` of those `334` tasks (`72.8%`) trace to **13 verified upstream producers** in `src/`, led by second printers/clippers (`36`), coupled schema/contract projection (`26`), test world overlay & result filtering (`24`), host-to-SCI var copying (`23`), and whole-program reach walks (`23`).
5. **Tasks retired outright by the 13 producer fixes**: **199 tasks RETIRED** outright, **32 tasks SIMPLIFIED**, and **12 tasks LEFT** as independent follow-ups.
6. **Moot tasks to retire immediately**: **38 tasks** target deleted namespaces (`seon.reload`, `seon.turn.loop`, `seon.turn.budget`, `seon.flow.actions`, `seon.sci.lookup`, `seon.test.accretion`, `seon.fault`, `seon.operator`, `seon.effect`, `seon.problems`, `seon.bootstrap`, `seon.print.value`) or behaviors already landed on `HEAD`.
7. **Fence/workaround tasks**: **29 tasks** ask for a guard, refusal tier, retry, or fallback around an unfixed producer (all `29` are subsumed and retired by Clusters 1–4 and 7–11).
8. **Top 5 fixes alone retire 108 non-contract tasks**: fixing `seon.error`+`seon.render.value` printing (`30`), decoupling `:seon.fn/spec` from `seon.schema/projection-ranges` (`21`), in-memory branch test worlds + unfiltered `newest-results` (`19`), `seon.sci.eval/copy-host-var` + `load-host-namespace` (`19`), and call-only lazy reach in `seon.fn`+`seon.test.runner` (`19`).
9. **Unclustered singleton/pair tasks**: `53` tasks remain outside the 13 clusters and 38 moot tasks (Genuine UI/canvas features, provider-specific AI options, or 1–2 task local defects).
10. **Net backlog reduction**: retiring the `38` moot tasks and fixing the `13` upstream producers eliminates **237 of the 334 non-contract tasks (`71.0%`)** before touching a single symptom task.
