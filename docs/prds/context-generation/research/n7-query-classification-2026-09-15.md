---
type: research
status: active
tags: [research, architecture, database]
---

# N7 — owner-specific classification queries

## Outcome

The owner approved schema facts and queries at each existing owner. The route,
dial, interpreter-binding, namespace-relevance, and process-claim changes are
implemented below. N7 remains open for the protected schema-composite and
stored-toolkit paths and ordinary evaluation call edges. Gate results below
state the verification boundary; this is not a class-closure claim.
Initial baseline inspected: `22893b713`, branch `steward-platform`, 2026-09-15.

Class statement: classifications inferred from copied membership or spelling
miss newly valid members. The requested structural kill is “Record the missing
fact, then query it; constructors accept no roster/prefix/count.”

Read end to end: AGENTS.md, docs/seon/issues/README.md, the N7 class note,
all nine assignment member notes, and
docs/prds/sci-execution-runtime/research/issue-class-mining-2026-08-11.md.
Loaded data-oriented-clojure and repl skills. Read the graph inventory change
`0ca3c1d64`, including its schema syntax inspector and canonical graph census.

## Initial design boundary (superseded by the owner decision below)

There is no single classification constructor shared by these owners. A literal
collection can be an authoritative declaration (schema enum, JVM option key,
closed protocol state) or a prohibited copy. Program call and keyword edges do
not by themselves encode that distinction. `0ca3c1d64` checks a defined schema
grammar; copying its census does not supply a classification grammar.

The remaining implementation crosses AI request construction, schema admission,
SCI acquisition, cluster reconciliation, process claim custody, and program
analysis. Several actual owners differ from the assignment's path list:
`resources/seon/operator/state.clj`, `src/seon/program.cljc`,
`src/seon/ai.clj`, and `src/seon/schema/edn.clj`. Cluster reconciliation is
currently under another lane's edits. This requires hours of cross-owner work;
the stop is the explicit design gate, not a foreign test failure.

## Three priced options

Estimates are engineering effort, not measured timings; serial gate waits add
wall time.

1. **Recommended — constrain classification declarations at publication.**
   Use existing schema/program declaration and admission owners to represent
   classification inputs and their authority; validate a deliberately bounded
   form grammar and derive checker subjects from those program facts. Replace
   remaining classifications with queries at each existing owner, deleting the
   obsolete mechanisms. **Guarantee:** admitted classification declarations
   cannot supply a literal membership roster or a spelling-derived selector.
   **Cost:** approximately 2–3 engineer-days across the owners above, including
   canonical regression and independent process-root proof. **Give up:** arbitrary
   Clojure as an admitted classifier; the guarantee covers this declared surface,
   not an unmarked computation anywhere in the program. Requires agreement on
   that scope before claiming the requested class checker exists.
2. **General source provenance analysis.** Extend canonical analysis to follow
   literal collection/string provenance through function calls into classified
   results, with explicit declaration provenance for legitimate constants.
   **Guarantee:** reject literal-derived classification within the analyzed
   subset, and report unsupported analysis as unknown. **Cost:** approximately
   5–10 engineer-days plus ongoing analyzer maintenance. **Give up:** a small
   implementation and unrestricted analyzable Clojure; a universal semantic
   proof over arbitrary functions is not promised.
3. **Owner-specific query invariants.** Apply the missing-fact repairs and one
   behavioral property per distinct owning mechanism; retain N7 as an umbrella
   until all member proofs close. **Guarantee:** adding a member at each repaired
   owner changes its query without a classifier edit. **Cost:** approximately
   1–2 engineer-days plus protected-owner coordination. **Give up:** the requested
   single checker that prevents future literal-roster classifiers repository-wide.

## Per-member verification at the boundary

| Member note under docs/seon/issues/ | Verdict and HEAD evidence | Missing fact / remaining proof |
|---|---|---|
| sci-base-context-silently-hand-lists-special-callables.md | Confirmed roster remains: `src/seon/program.cljc:14–40`; live base injection map contains six namespace entries. `750ed404d` consolidated consumers but did not derive the exceptions. | Interpreter-only binding semantics/provenance at declaration; ordinary bindings should use acquisition. |
| render-walk-maintains-a-derived-edge-hand-list.md | Historical roster deleted by `bc3dfe3fd`; no `derived-edge-functions` remains in the walk. Current `src/seon/render/walk.clj:628–664` consults schema properties for declared concerns. | Existing schema relationship/derived-render facts are the candidate authority. Live relationship coverage is not yet verified; do not infer it from deletion. |
| operator-classifies-processes-by-command-substrings.md | Old classifier removed by `5342b2b4d`. `script/seon/fresh_operator.clj:537–548` consumes `observed-property-processes`; `resources/seon/operator/state.clj:957–988` reads explicit JVM root/generation properties plus OS start instant. | Root, generation, PID and start instant already exist. Parsing the explicit `-Dproperty=` argument is a protocol parse, not a role inference from arbitrary command text. Negative process-classification regression not run. |
| operator-down-misses-a-live-scratch-jvm-from-another-checkout.md | Residual authority problem remains plausible: `script/seon/fresh_operator.clj:140–174` uses the invoking repository to find process claims, then filters by selected root; `:2903–2948` consumes that census for down. | Selected-root relationship to the owning claim installation. Cross-checkout live reproduction still required; no process was stopped. |
| config-ai-request-idents-are-derived-by-string-surgery.md | Confirmed: `src/seon/ai.clj:353–367` rebuilds request keys and selects by namespace. Live helper maps `:unrelated/temperature` to `:seon.ai/temperature`. `:597–602` retains inert-settings set with an older explicit provider ruling. | Per-dial request attribute, plus provider-specific inert semantics. Declare at schema/provider owner and query at AI owner; do not silently override the older provider ruling. |
| config-dial-discovery-has-three-authorities.md | Confirmed: `src/seon/schema/edn.clj:31–37` accepts a namespace prefix. Live nonexistent `:seon.config.fake/not-a-dial` with `:string` definition returns true. Consumer roster partly dissolved, but `test/seon/config_application_test.clj:28` still starts an application-modes map. | Explicit dial membership and application acquisition semantics. Actual schema owner is in p1's assigned scope. |
| cluster-toolkit-stores-a-prefix-derived-projection.md | Confirmed: `src/seon/cluster/instruction.clj:32–57` filters `my.`; `src/seon/cluster.clj:2165–2201` stores/reconciles the projection. Live query returned 13 namespaces. | Context relevance from current agent/program relationships; delete stored projection. `cluster.clj` has concurrent uncommitted edits and was not modified. |
| initial-paint-census-is-a-hand-maintained-count.md | Historical assertion deleted by `9eca070ed`; `test/seon/render/web_test.clj:1235–1239` records removal of the old initial-paint machinery. | Expected emitted block identities belong to the current delivery/walk proof. Current replacement coverage not verified; deletion alone is not an exactly-once paint proof. |
| agent-form-calls-to-core-namespaces-are-not-indexed.md | Partial dissolution: `1f3c099d2` introduced current resolvable function-row query, `src/seon/fn.clj:475–497`. Live valid declaration analysis returns BOTH core and my.* call edges. `analyzed-form` at `:558–588` returns empty first-map facts and merges edges into declaration rows. | Ordinary evaluation edge persistence remains unproven. The nil-row call still refuses before analysis. Do not close the historical ordinary-form defect based on declaration analysis. |

All nine notes remain open at this design stop; prospective deletion-based
closures need their stated remaining proof. No member was re-fixed.

## Exact live probes and measured observations

Read-only MCP JVM mode, root `/Users/sean/src/seon`, cluster `default`.
`bin/seon status` observed PID 69622, PREPL 55914, HTTP 7994, one live cluster,
no orphan JVM. MCP runtime status answered; it reported 10 errored historical
evaluations, not a clean-runtime verdict. Default was never restarted/reforked.

Successful classification probe: 1 ms reported by PREPL:

```clojure
(let [db @(seon.operator/connection "default")]
  {:n7/core-row
   (seon.db/q '[:find ?e . :where [?e :seon.fn/sym "seon.db/q"]] db)
   :n7/fake-dial
   (#'seon.schema.edn/config-dial? :seon.config.fake/not-a-dial :string)
   :n7/route
   (#'seon.ai/config-ai-ident->request-ident :unrelated/temperature)})
;; => {:n7/core-row 4912, :n7/fake-dial true,
;;     :n7/route :seon.ai/temperature}
```

Successful declaration-analysis probe: 1,156 ms, two call edges. It also emitted
the existing `seon.db/projection-fallback` warning (1,136 ms); this is within the
concurrent p1 ambient-state class and is not attributed to N7.

```clojure
(let [db @(seon.operator/connection "default")
      source "(defn n7-probe [] (seon.db/q '[:find ?e :where [?e :seon.agent/id]]) (my.turn/wait))"
      row {:seon.fn/sym "seon.db/n7-probe"
           :seon.fn/ns [:seon.ns/name 'seon.db]
           :seon.schema.admission/source :agent
           :seon.fn/source source}]
  (seon.fn/analyze-form db source [:seon.ns/name 'seon.db] row))
;; => [{} { ... :seon.fn/calls
;;          #{[:seon.fn/sym "seon.db/q"]
;;            [:seon.fn/sym "my.turn/wait"]} ... }]
```

No definition was installed or evaluated by that analysis probe. Earlier probes
used the wrong literal types: function identity is a string, namespace identity
is a symbol. Those refusals are probe-input errors, not N7 findings. The separate
nil program-row probe reproduced the nested `analyze-forms` contract refusal at
`[0 :seon.program/row]` already recorded in the member note.

## Gates, authority boundary, and cleanup

No production changes; no test runner or scratch JVM launched. Scoped test and
platform gates are **not run**, pending the design decision. No background shell,
worktree, or scratch root was created.

`bin/issues-index --class class/n7` returned exit 1 because the shared schedule
has eight missing rows: mcp-ordinary-values-bypass-the-value-renderer,
missing-artifact-marker-refuses-its-own-admission-contract,
nested-test-snapshot-overwrites-its-fresh-run-claim,
test-results-persistence-can-time-out-during-development-adoption,
test-result-recording-refuses-after-branch-head-change,
adoption-probe-emits-an-invalid-root-namespace-lookup,
test-program-rows-omit-admission-provenance, and
test-launcher-fixtures-omit-required-helpers. These existing notes preserve the
findings; the owner owns schedule edits. The nine explicitly assigned N7 notes
were read directly; no complete validated class census is claimed.

Inherited edits were preserved in schema admission, cluster, value renderer,
and MCP/effect/REPL/search tests. This lane changes only this landing note and
the N7 class note's link to the decision.

## Owner-directed implementation — 2026-09-15

The owner superseded the design stop: use owner-specific data, schema facts,
queries, and tests. No publication enforcement or provenance analyzer is being
added. Latest protected-file instructions keep `src/seon/schema/*` and
`src/seon/cluster.clj` outside this lane's edits.

### Protected schema classifier diff

Apply at the existing schema admission seam after p1 releases it. All existing
`seon.config.*` leaf declarations have been checked and now explicitly carry
`:seon.config/dial true`; N7's config compiler queries that property. The schema
composite builder still has this protected prefix fallback, so that member
remains open until this exact diff and its admission test land.

```diff
--- src/seon/schema/edn.clj
+++ src/seon/schema/edn.clj
@@ -32,9 +32,8 @@
   [identity definition]
   (and
    (qualified-keyword? identity)
-   (or (str/starts-with? (namespace identity) "seon.config.")
-       (true? (:seon.config/dial
-               (schema.form/attr-form-properties definition))))))
+   (true? (:seon.config/dial
+           (schema.form/attr-form-properties definition)))))

 (defn- config-dial-entries
   [forms]
```

### Protected cluster reconciliation diff

The relevance query and namespace-indexing fact are implemented. The following
removes the stored projection at its writer; existing cluster rows need only
the ordinary reconciliation retraction, not a parallel migration mechanism.
Keep the member open while this protected writer still copies the query.

```diff
--- src/seon/cluster.clj
+++ src/seon/cluster.clj
@@ -2166,18 +2166,13 @@
                  [:seon.db.process/id boot-process-identity]}})
      {:seon.db.process/id process
       :seon.boot/population :seon.db.process/process}))
-  (let [toolkit-namespaces (instruction/toolkit-namespaces @connection)
-        desired {:seon.cluster/name cluster-name
+  (let [desired {:seon.cluster/name cluster-name
                  :seon.cluster/config
                  [:seon.config/cluster cluster-name]
                  :seon.cluster/instructions
                  (mapv (fn [instruction-id]
                          [:seon.cluster.instruction/id instruction-id])
-                       instruction/instruction-ids)
-                 :seon.cluster/toolkit
-                 (mapv (fn [namespace-name]
-                         [:seon.ns/name namespace-name])
-                       toolkit-namespaces)}
+                       instruction/instruction-ids)}
         expected-current
         {:seon.cluster/name cluster-name
          :seon.cluster/config {:seon.config/cluster cluster-name}
@@ -2185,12 +2180,7 @@
          (into #{}
                (map (fn [instruction-id]
                       {:seon.cluster.instruction/id instruction-id}))
-               instruction/instruction-ids)
-         :seon.cluster/toolkit
-         (into #{}
-               (map (fn [namespace-name]
-                      {:seon.ns/name namespace-name}))
-               toolkit-namespaces)}
+               instruction/instruction-ids)}
         current (some-> (db/pull @connection
                                 '[:seon.cluster/name
                                   {:seon.cluster/config
@@ -2201,8 +2191,7 @@
                                    [:seon.ns/name]}]
                                 [:seon.cluster/name cluster-name])
                         (dissoc :db/id)
-                        (update :seon.cluster/instructions set)
-                        (update :seon.cluster/toolkit set))]
+                        (update :seon.cluster/instructions set))]
     (when-not (= expected-current current)
       (require-committed!
        (db/transact!
```

The cluster schema currently requires `:seon.cluster/toolkit`; remove that map
entry in `resources/seon/schemas/seon.cluster.edn` with the writer change. Keep
the attribute declaration until old rows have been reconciled. The old
`cluster-toolkit-exactly-converges-to-the-computed-rule` test must be replaced
with absence of stored refs after reconciliation; the new classification test
already verifies declared namespace relevance independently of spelling.
Cluster renderers must query `instruction/toolkit-namespaces` from their supplied
database instead of counting the retired stored ref set. These dependent edits
are deliberately not half-applied while `cluster.clj` is protected.

### Protected cluster render diff

Apply with the reconciliation/schema/test changes above; this is an exact
proposed diff, not executed or browser-verified code.

```diff
--- src/seon/cluster.clj
+++ src/seon/cluster.clj
@@ -160,44 +160,43 @@
     unit
     (when-let [name (:seon.cluster/name unit)]
       (let [database (:seon.db/db unit)
-          config-name (or (get-in unit [:seon.cluster/config
-                                        :seon.config/cluster])
-                          (ref-identity database
-                                        (:seon.cluster/config unit)
-                                        :seon.config/cluster))
-          instructions (count (:seon.cluster/instructions unit))
-          toolkit (count (:seon.cluster/toolkit unit))]
+            config-name (or (get-in unit [:seon.cluster/config :seon.config/cluster])
+                            (ref-identity database (:seon.cluster/config unit)
+                                          :seon.config/cluster))
+            instructions (count (:seon.cluster/instructions unit))
+            namespaces (:seon.cluster/toolkit-namespaces unit)]
         (str "Cluster " name ".\n"
              "Configuration " (or config-name "is connected")
              "; " instructions " shared instruction"
              (when-not (= 1 instructions) "s")
-             " and " toolkit " toolkit namespace"
-             (when-not (= 1 toolkit) "s") ".")))))
+             (when namespaces
+               (str " and " (count namespaces) " context namespaces")) ".")))))

 (defn render-ai
-  "`:seon.render/ai` — source reading one exact cluster before formatting it."
+  "Generate reads of the cluster and current context-relevant namespaces."
   {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :seon.render/source]]}
   [unit]
   (when-let [name (:seon.cluster/name unit)]
     (pr-str
      (list `format-ai
-           (list 'seon.db/pull
-                 (list 'quote
-                       [:seon.cluster/name
-                        {:seon.cluster/config [:seon.config/cluster]}
-                        :seon.cluster/instructions
-                        :seon.cluster/toolkit])
-                 [:seon.cluster/name name])))))
+           (list 'assoc
+                 (list 'seon.db/pull
+                       (list 'quote
+                             [:seon.cluster/name
+                              {:seon.cluster/config [:seon.config/cluster]}
+                              :seon.cluster/instructions])
+                       [:seon.cluster/name name])
+                 :seon.cluster/toolkit-namespaces
+                 (list 'seon.cluster.instruction/toolkit-namespaces
+                       (list 'seon.db/db)))))))

 (defn render-html
-  "`:seon.render/html` — one readable cluster card."
-  {:malli/schema [:=> [:cat :seon.render/unit]
-                  [:maybe :seon.render/hiccup]]}
+  "Render a cluster with context relevance queried from the supplied database."
+  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :seon.render/hiccup]]}
   [unit]
   (when-let [name (:seon.cluster/name unit)]
     (let [database (:seon.db/db unit)
-          config-name (ref-identity database
-                                    (:seon.cluster/config unit)
+          config-name (ref-identity database (:seon.cluster/config unit)
                                     :seon.config/cluster)]
       [:article {:class "seon-family-entry seon-cluster-entry"}
        [:h3 (str "Cluster " name)]
@@ -205,8 +204,9 @@
         [:div [:dt "Configuration"] [:dd (str (or config-name "Connected"))]]
         [:div [:dt "Shared instructions"]
          [:dd (str (count (:seon.cluster/instructions unit)))]]
-        [:div [:dt "Toolkit namespaces"]
-         [:dd (str (count (:seon.cluster/toolkit unit)))]]]])))
+        (when database
+          [:div [:dt "Context namespaces"]
+           [:dd (str (count (instruction/toolkit-namespaces database)))]])]])))

 (declare readiness)

```

## Implementation evidence (owner-specific queries)

- SCI binding declarations use the existing `sci/copy-var*` integration
  (`reference-code/sci/src/sci/core.cljc:112`); ordinary program acquisition
  remains the callable owner. Malli properties remain ordinary schema data
  (`reference-code/malli/src/malli/core.cljc:2582`).
- The existing `seon.schema/canonical-schema-rows` and
  `seon.schema.datahike/storable-properties-in` already persist declared
  storable properties; no second indexing or publication mechanism was added.
- Namespace metadata is copied at `seon.fn/namespace-row`; the existing
  program-owned attributes include the new relevance fact.
- Process discovery reads explicit JVM arguments via Java ProcessHandle, then
  reads the exact installation claim. Stop still re-reads and checks generation,
  root, PID, and start instant at the existing owner before signaling.

### Iteration results

1. First HEAD-plus-owned-paths fast iteration: 83 tests, 485 assertions,
   11 failures, 1 error. The new namespace fixture initially omitted required
   function admission provenance; this was repaired and its transaction result
   is now asserted before checking query membership.
2. Second fast iteration: 127 tests, 610 assertions, 47 failures, 10 errors.
   Classification and AI namespaces passed. All failures were in the existing
   SCI evaluation namespace.
3. Clean HEAD snapshot `caddf111be08955f3f3d3476366c28cc7f3b9731`,
   `bin/test-fast --paths README.md -- seon.sci.eval-test seon.program-test
   seon.cluster.instruction-test`: 91 tests, 482 assertions, 55 failures,
   11 errors. Every failing SCI test name from iteration 2 reproduced except
   `evaluate-invokes-eval-form-exactly-once-on-every-path`. That test assumed
   schema registration was injected into a bare context; it now uses the
   canonical fixture and real program acquisition before evaluation.
4. Third fast iteration: 9 tests, 42 assertions, 1 failure, 2 errors. All
   five new owner regressions passed, including an actual foreign-installation
   claim file round trip and ordinary SCI completion after acquisition.
   The strengthened per-attribute census exposed the readerless
   `:seon.config.effect/long-call-ms` dial (source search found only the
   declaration and shipped default); both are deleted. Two old application
   fixture calls used a partial config instead of the effective config and
   omitted the now-required agent id; both are corrected.

These are iteration results, not isolated-gate success. The gate results below
are authoritative for the final snapshot. No foreign lane's session was operated.

The first isolated gate also exposed three existing optional dials missing
explicit absence decisions in `config/default.edn`: `show-all-settings`,
`no-provider`, and `retain-reasoning`. A 6,275 ms default JVM comparison of
manifest keys against shipped document keys returned exactly those three;
the optional declarations were already present at HEAD. The document now
declares `:seon.config/absent` for each, retaining its prior effective behavior.
The stale comment for the deleted `long-call-ms` dial is removed with it.

### Live observations after in-place development adoption

Read-only MCP JVM probes on default (no provider calls):

- 3,623 ms: extending the database-derived schema forms with `:sample/heat`
  carrying `:seon.ai/request-attribute :seon.ai/temperature` yields that route;
  `:seon.config.ai/impostor` with no property is excluded. A Datalog query over
  the installed temperature schema returns the same request attribute.
- 5,988 ms: the database-derived binding query returns 42 symbols from four
  explained declarations; `my.turn/complete` is not bootstrap-injected.
- 4,559 ms: with the database projection explicitly supplied,
  `wire-settings` marks temperature inert and omits the temperature wire field
  in high thinking mode. The first attempted probe omitted the required
  projection and correctly refused; it was corrected, not treated as an N7 bug.
- 75 ms: the then-current default database had 93 explicit dial facts, but no
  namespace relevance facts yet; its context query returned an empty vector.
  Thus no complete namespace-adoption proof is claimed from this intermediate
  observation. The canonical fixture did index all eleven authored namespaces.

Exact route and wire probes:

```clojure
(let [database @(seon.operator/connection "default")
      forms (:seon.schema.projection/forms
             (seon.schema/projection-from-database database))
      extended (assoc forms
                      :sample/heat [:double {:seon.config/dial true
                                             :seon.ai/request-attribute :seon.ai/temperature}]
                      :seon.config.ai/impostor :double)]
  {:n7/route (get (seon.ai/request-attributes extended) :sample/heat)
   :n7/nonmember (contains? (seon.ai/request-attributes extended)
                            :seon.config.ai/impostor)
   :n7/declared-route
   (seon.db/q '[:find ?attribute .
                :where [?schema :seon.schema/key :seon.config.ai/temperature]
                       [?schema :seon.ai/request-attribute ?attribute]] database)})

(let [database @(seon.operator/connection "default")
      projection (seon.schema/projection-from-database database)]
  (seon.schema/call-with-projection
   projection
   (fn []
     (let [dials (seon.config/effective database "default")
           target (:seon.ai/primary (seon.ai/targets database dials))
           request (assoc target :seon.ai/prompt "N7 local projection probe"
                          :seon.ai/thinking :high :seon.ai/temperature 0.5)
           projected (seon.ai/wire-settings request)]
       {:n7/temperature-inert?
        (contains? (:seon.ai/inert projected) :seon.config.ai/temperature)
        :n7/temperature-sent?
        (contains? (:seon.ai/sent projected) "temperature")}))))
```

The first explicit full adoption attempt waited behind another publication and
then refused because source changed during analysis. The second attempt and
final convergence status are recorded below. Neither restarted default.

The second CLI waited 152,686 ms for the lifecycle lock, then reported request
accepted without completion for more than ten minutes. The lane terminated
only its verified client PID 86505 (exit 143). The shared failure log reported
a publication bound expiry; causation is unproven and recorded in
[the existing publication issue](../../../seon/issues/complete-publication-takes-seventy-seconds.md).
Default remained alive. A subsequent read-only JVM probe returned in 24 ms:
all eleven namespace relevance facts were present and `toolkit-namespaces`
returned those same eleven names. This supersedes the intermediate empty
namespace observation above; it does not prove the protected stored toolkit
projection was removed, nor establish complete source-commit convergence.

```clojure
(let [database @(seon.operator/connection "default")]
  {:n7/relevant
   (seon.db/q '[:find [?name ...]
                :where [?ns :seon.ns/name ?name]
                       [?ns :seon.ns/context-relevant? true]] database)
   :n7/context (seon.cluster.instruction/toolkit-namespaces database)})
```

A later 4,525 ms read-only JVM probe of the database-derived schema population
returned 18 request routes, 92 explicit dials, and 42 interpreter bindings;
`my.turn/complete` remained absent from bootstrap membership. The dial decrease
from 93 to 92 verifies adoption of the deleted readerless `long-call-ms` dial.

The source-convergence probe returned in 6 ms: default's recorded adoption
commit was `6aa99c9f-cb2c-5412-8586-cf05d9b024e9`, while `source/current`
returned `6aa9a911-652d-5e7c-bd59-47605532754f`. These differ. The N7 live
observations prove those facts and loaded functions, not complete adoption.

```clojure
(let [database @(seon.operator/connection "default")
      instance (get @seon.operator.runtime/running-instances "default")
      store (:seon.store/store instance)]
  {:n7/adopted (seon.db/pull database [:seon.source/commit-id]
                            [:seon.cluster/name "default"])
   :n7/published (when store (seon.cluster.source/current store))
   :n7/store-present? (some? store)})
```

```clojure
(let [database @(seon.operator/connection "default")
      forms (:seon.schema.projection/forms
             (seon.schema/projection-from-database database))]
  {:n7/routes (count (seon.ai/request-attributes forms))
   :n7/dials (count (seon.config/dial-attributes forms))
   :n7/interpreter-bindings (count (seon.program/base-context-injected-symbols forms))
   :n7/ordinary-bootstrap-member?
   (contains? (set (seon.program/base-context-injected-symbols forms))
              'my.turn/complete)})
```

### Live cross-checkout claim census

The isolated gate launched PID 95143 from its pool-1 checkout with root
`tmp/test-runs/run.CNlCO3/workers/pool-1/tmp/fresh-operator-test/2e13b00d-9e0a-4f7c-bfe8-7bee0e20b43c`.
After that test's worker exchange expired, the JVM remained alive. Read-only
`bin/seon --root <that root> status` from the main checkout reported the live
`init-command` cluster, PID 95143, PREPL 58543, HTTP 7783. More decisively, the
following main-checkout Babashka probe (classpath `script:src:resources`)
returned exactly one claim, generation `c0bd406b-9943-40c8-bb8b-19e3aa192931`,
whose installation was the pool-1 checkout. This verifies discovery of a real
foreign-checkout claim from the advertised fact, not merely an advertisement.

```clojure
(require 'seon.fresh-operator)
(prn
 (mapv #(select-keys % [:seon.boot/pid
                       :seon.operator.process-record/generation
                       :seon.operator.process-record/repository-root])
       (:seon.fresh-operator/process-records
        ((deref (ns-resolve 'seon.fresh-operator 'read-process-records))
         "tmp/test-runs/run.CNlCO3/workers/pool-1/tmp/fresh-operator-test/2e13b00d-9e0a-4f7c-bfe8-7bee0e20b43c"))))
```

After the gate finished, the main checkout ran `bin/seon --root <that root>
down --force`: `records=1 unreadable=0`, PID 95143 and the same exact
generation, followed by `path=SIGTERM`, stale advertisement removal, and
`flock free; roster readable (3 branches)`. Exit 0; the process table confirmed
PID 95143 absent. The same cleanup succeeded for the second expired worker's
PID 96133, generation `09dff5b1-61a7-4f02-9357-c71816e183c7`, rooted at
`tmp/test-runs/run.CNlCO3/workers/pool-2/tmp/fresh-operator-test/a9a80a55-d9f0-403b-84e5-ceafb82aaa8a`.
Both foreign claim installations were discovered from new JVM facts. Neither
command operated default or a foreign lane's root.

## Isolated gate results

The first path-isolated gate over classification, AI, config, config
application, SCI evaluation, operator, and fresh-operator namespaces used HEAD
`8574992e43ebd6e435a89629175b8d61bcbe4d19` plus exactly the owned paths below.
It ran **214 tests / 1,056 assertions / 47 failures / 14 errors**, exit 1;
coordinator-and-tests phase **1,546 seconds**. All five new classification
regressions and the corrected evaluation-count fixture passed. Every failing
SCI test name appeared in the earlier clean-HEAD baseline. The stale default
document failure is corrected above and re-gated below.

Two fresh-operator cases failed worker exchange rather than producing an
assertion verdict, both at the declared 270-second completion bound and again
in isolated confirmation: `init-owns-current-source-and-dormant-cluster-lifecycle`
and `live-init-reloads-schema-runtime-and-moved-predicate-owners-before-admission`.
They also left the two live JVMs cleaned above. This observation is recorded
against the existing parallel lifecycle issue; no shared-resource cause is
asserted. Three operator test failures are compared against clean HEAD below.

The runner separately detected 23 lost instrumentation wrappers after the
existing SCI namespace-reload test. The fixture restoration defect is recorded
in [its issue](../../../seon/issues/sci-reload-test-leaves-worker-instrumentation-changed.md).

Clean-HEAD operator comparison (`bin/test-fast --paths README.md --
seon.operator-test`, HEAD `a826fc5d8e4b7be3c9189d55ad3d537584179ac7`) ran
**29 tests / 152 assertions / 1 failure / 2 errors**, exit 1. It reproduced
the exact three names from the isolated gate:
`cluster-cleanup-uses-one-stop-retire-delete-and-collect-composition`,
`lifecycle-verbs-only-call-their-delegates`, and
`public-contracts-refuse-invalid-input-and-output`. Their boundaries are,
respectively, a cleanup return contract missing `:seon.error/kind`, a lifecycle
delegate-call expectation, and Malli function-schema registration. None is a
process-claim classification failure; no change was made to those owners.

After correcting the three absence decisions, the path-isolated owner gate
(`seon.classification-test seon.ai-test seon.config-test
seon.config-application-test`) passed **82 tests / 418 assertions / 0 failures /
0 errors**, exit 0; coordinator-and-tests phase **104 seconds**. Its source
base digest was `f82d11b56b97b44caa281ae125133e6701f86cbaf05c77aa0aac6ce1d68b6390`.
The successful isolated root was removed by the runner. The earlier failed
root was removed by N7 after its two remaining exact claims were stopped and
a process-holder check returned none. No symlink target was traversed.

The separate `bin/test --paths <the same owned paths> --platform` gate passed
**86 tests / 542 assertions / 0 failures / 0 errors**, exit 0; its
coordinator-and-tests phase took **164 seconds**. Snapshot HEAD was
`a659850987f89faeaeb1fdedb271580db4496c4b`; source base digest
`111239c6ef8e4b83a4b904baa93371d2066db9c0b8362c8b99948f23f34d848c`.
It selected 86 platform tests and zero bulk tests. No `--all` or `--full` run
was made. Every test invocation in this lane was serial.

## Final dated member verdicts

Implementation commit: `5deb40e4e` on `steward-platform`.
Guarantee: each implemented owner queries declared classification facts, so
adding a member changes its answer without changing classifier code.

| Member | Verdict | Evidence or exact residual |
|---|---|---|
| [SCI base bindings](../../../seon/issues/archive/sci-base-context-silently-hand-lists-special-callables.md) | Resolved | Explained schema declarations replace the executable roster; live query 42 bindings, ordinary completion acquired through the program. |
| [Render walk edges](../../../seon/issues/archive/render-walk-maintains-a-derived-edge-hand-list.md) | Superseded | Historical roster deleted by `bc3dfe3fd`; current schema relationship coverage remains the named proof, not a claimed browser observation. |
| [Operator command classification](../../../seon/issues/archive/operator-classifies-processes-by-command-substrings.md) | Resolved | `5342b2b4d` replaced command inference with root/generation properties; N7 excludes command-text lookalikes in its regression. |
| [Cross-checkout process cleanup](../../../seon/issues/archive/operator-down-misses-a-live-scratch-jvm-from-another-checkout.md) | Resolved | Claim-installation fact, canonical disk regression, and two real cross-checkout exact-identity stops. Pre-change JVMs still require their creating checkout. |
| [AI request routes](../../../seon/issues/archive/config-ai-request-idents-are-derived-by-string-surgery.md) | Resolved | 18 declared routes, non-family member regression, live inert-wire projection, per-attribute application census. |
| [Dial discovery](../../../seon/issues/config-dial-discovery-has-three-authorities.md) | Open, narrowed | Compiler queries 92 declared dials; protected `schema/edn.clj` still has the spelling fallback. Exact patch above. |
| [Stored toolkit projection](../../../seon/issues/cluster-toolkit-stores-a-prefix-derived-projection.md) | Open, narrowed | Relevance indexing/query and live eleven-namespace answer verified; protected `cluster.clj` still stores the copy. Exact patches above. |
| [Initial-paint census](../../../seon/issues/archive/initial-paint-census-is-a-hand-maintained-count.md) | Superseded | Historical count deleted by `9eca070ed`; current exactly-once delivery proof remains named, with no browser proof claimed. |
| [Agent-form calls](../../../seon/issues/agent-form-calls-to-core-namespaces-are-not-indexed.md) | Open, narrowed | Valid declaration analysis has core and agent call edges; persisted edges of ordinary non-defining evaluations remain unproven. |

N7 itself stays open because three members remain. No generic classifier
analyzer, publication enforcement, naming rule, or production regex was added.
Protected files were not edited; no other lane's session was operated.

### Final live boundary and cleanup

After the code commit, a 5,274 ms read-only default JVM probe still returned
92 dials, 18 routes, and the eleven declared context namespaces. Default was
alive. Its adopted commit remained `6aa99c9f-cb2c-5412-8586-cf05d9b024e9`, while
current source was `6aa9af96-0018-53c3-b1cd-646bca18fa2c`: full adoption is
**not converged**. The N7 facts and functions were observed through in-place
adoption; no fork, restart, or browser-paint claim is substituted for that
observation. The publication issue above owns the incomplete completion.

All lane-owned background commands have exited. Both successful gate roots
were removed by the runner; the failed root was removed after its exact
process claims were stopped. No lane worktree or scratch cluster remains.
The landing note's Markdown validation is green. Repository pin validation
reports eleven errors in three unrelated historical/audit documents; none is
in an N7 document. The owner-maintained issue schedule is not edited by this
lane; its existing omissions and newly archived references remain an index
maintenance boundary.

### Exact code paths

The implementation changes **46 files, 508 added lines, 322 removed lines**.
`git show --format= --binary 5deb40e4e` is **74,079 bytes**. The source/test/schema
paths below are the complete path-limited code commit and gate overlay:

```text
config/default.edn
resources/seon/operator/state.clj
resources/seon/schemas/seon.ai.edn
resources/seon/schemas/seon.config.agent.edn
resources/seon/schemas/seon.config.ai.backup.edn
resources/seon/schemas/seon.config.ai.edn
resources/seon/schemas/seon.config.ai.retry.edn
resources/seon/schemas/seon.config.blob.edn
resources/seon/schemas/seon.config.effect.background.edn
resources/seon/schemas/seon.config.effect.edn
resources/seon/schemas/seon.config.error.edn
resources/seon/schemas/seon.config.eval.edn
resources/seon/schemas/seon.config.eval.result.edn
resources/seon/schemas/seon.config.flow.compute.edn
resources/seon/schemas/seon.config.flow.edn
resources/seon/schemas/seon.config.flow.io.edn
resources/seon/schemas/seon.config.maintenance.edn
resources/seon/schemas/seon.config.message.edn
resources/seon/schemas/seon.config.operator.edn
resources/seon/schemas/seon.config.render.edn
resources/seon/schemas/seon.config.run.edn
resources/seon/schemas/seon.config.test.edn
resources/seon/schemas/seon.config.web.edn
resources/seon/schemas/seon.ns.edn
resources/seon/schemas/seon.operator.process-record.edn
resources/seon/schemas/seon.sci.binding.edn
script/seon/fresh_operator.clj
src/my/agent.clj
src/my/background.clj
src/my/edit.clj
src/my/fs.clj
src/my/message.clj
src/my/note.clj
src/my/plan.clj
src/my/shell.clj
src/my/test.clj
src/my/turn.clj
src/my/web.clj
src/seon/ai.clj
src/seon/cluster/instruction.clj
src/seon/config.clj
src/seon/fn.clj
src/seon/program.cljc
test/seon/classification_test.clj
test/seon/config_application_test.clj
test/seon/sci/eval_test.clj
```
