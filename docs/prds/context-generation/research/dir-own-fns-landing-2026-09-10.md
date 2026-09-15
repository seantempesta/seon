---
type: research
status: active
tags: [research, sci, dir, live-test]
---

# Dir own functions — 2026-09-10

## Verified cause before production edits

Read AGENTS.md, the assigned issue, live-run-2-landing-2026-09-10.md, and
src/seon/bootstrap.clj end to end. Read the installation and documentation
owners in src/seon/sci/eval.clj, the roadmap and working edge, and the
Clojure, REPL, testing, Datahike and data-modeling skills. Searched open
issues for agent-installed program rows; the existing directory issue is
the matching defect.

MCP JVM mode on default PID 23557, explicit connection
`(seon.operator/connection "default")`. No agent was messaged or evaluated,
and default was not stopped, restarted, reforked, or reseeded. The calls
below inspect the actual retained macro functions directly without
executing their expansions in the agent context.

The installed row is complete: namespace ref, public flag, arglists,
docstring, spec, and contract relations are present. The current
`program-documentation` query includes it (64 ms). The retained Juniper
macro embeds an empty vector; the newer cluster base macro embeds the
function. Source `program-dir-var` captures `by-namespace` computed from
acquisition-time documentation. `program-doc-var` captures the same map.
`definition-row` already supplies the namespace and public facts.

This falsifies the missing-installation-relation hypothesis. Actual ownership
is eval.clj:1105–1248, outside the assignment's conditional installation-only
permission for that file. Scope clarification requested before production
edits: fix the existing documentation owner to read current program facts
through the evaluation's database custody; preserve one shared query path.

## Dependency ledger

- SCI reusable contexts, forks and macro Vars: reference-code/sci/src/sci/core.cljc
  (init, fork, new-macro-var); first-party owner program-dir-var/program-doc-var.
- Datahike pull and Datalog through src/seon/db.clj:1528 and the existing
  program-documentation query. Namespace membership already lives on :seon.fn/ns.
- Canonical fixture: test/seon/test_support.clj:261 fork-cluster-ctx;
  test/seon/sci/documentation_test.clj evaluates real SCI documentation forms.
- Archaeology: d25a12eda introduced the current structured dir/doc output;
  it retained the acquisition-time projection.

## Exact [*] agent function row (832 ms)

```clojure
(pr-str (seon.db/pull @(seon.operator/connection "default") '[*] [:seon.fn/sym "my.agents.juniper/largest-customer"]))
{:seon.fn/arglists "([rows])", :seon.fn/arities [{:seon.fn.arity/order 0, :seon.fn.arity/output-refs [#:db{:id 69542}], :seon.fn.arity/max 1, :seon.fn.arity/input #:db{:id 94264}, :seon.fn.arity/arity "1", :seon.fn.arity/return-schema #:db{:id 94265}, :seon.fn.arity/input-refs [#:db{:id 69545}], :db/id 94263, :seon.fn.arity/min 1, :seon.fn.arity/argument-count 1, :seon.fn.arity/output #:db{:id 94268}, :seon.fn.arity/arguments [{:db/id 94269, :seon.fn.argument/binding {:db/id 94270, :seon.fn.binding/form "rows", :seon.fn.binding/shape :symbol, :seon.fn.binding/symbol rows}, :seon.fn.argument/index 0, :seon.fn.argument/order 0, :seon.fn.argument/rest? false, :seon.fn.argument/schema #:db{:id 94271}}]}], :seon.fn/ns #:db{:id 69334}, :seon.fn/source "(defn largest-customer\n  \"Given a seq of order rows, return the customer with the largest total.\"\n  {:malli/schema [:=> [:cat [:vector :example/order-row]] [:map [:customer :example/customer] [:total :int]]]}\n  [rows]\n  (->> rows\n       (group-by :example/customer)\n       (map (fn [[customer orders]]\n              {:customer customer\n               :total (reduce + 0 (map :example/amount orders))}))\n       (sort-by :total >)\n       first))", :seon.fn/spec "[:=> [:cat [:vector :example/order-row]] [:map [:customer :example/customer] [:total :int]]]", :seon.fn/sym "my.agents.juniper/largest-customer", :seon.fn/ast {:db/id 94277, :seon.fn.ast/input {:db/id 94264, :seon.fn.ast/children [{:db/id 94278, :seon.fn.ast.entry/order 0, :seon.fn.ast.entry/value {:db/id 94279, :seon.fn.ast/child {:db/id 94280, :seon.fn.ast/ref #:db{:id 69545}, :seon.fn.ast/type ":malli.core/schema", :seon.fn.ast/value {:db/id 94281, :seon.fn.ast.entry/value-edn ":example/order-row"}}, :seon.fn.ast/type ":vector"}}], :seon.fn.ast/type ":cat"}, :seon.fn.ast/output {:db/id 94268, :seon.fn.ast/keys [{:db/id 94282, :seon.fn.ast.entry/key ":customer", :seon.fn.ast.entry/order 0, :seon.fn.ast.entry/value {:db/id 94283, :seon.fn.ast/ref #:db{:id 69542}, :seon.fn.ast/type ":malli.core/schema", :seon.fn.ast/value {:db/id 94284, :seon.fn.ast.entry/value-edn ":example/customer"}}} {:db/id 94285, :seon.fn.ast.entry/key ":total", :seon.fn.ast.entry/order 1, :seon.fn.ast.entry/value {:db/id 94286, :seon.fn.ast/type ":int"}}], :seon.fn.ast/type ":map"}, :seon.fn.ast/type ":=>"}, :seon.fn/keywords [:example/amount :example/customer], :db/id 94262, :seon.schema.admission/source :agent, :seon.fn/calls [#:db{:id 3174} #:db{:id 3177} #:db{:id 3275} #:db{:id 3278} #:db{:id 3291} #:db{:id 3338} #:db{:id 3403} #:db{:id 3439}], :seon.fn/private? false, :seon.fn/doc "Given a seq of order rows, return the customer with the largest total."}
```

## Exact [*] shipped function row (680 ms)

```clojure
(pr-str (seon.db/pull @(seon.operator/connection "default") '[*] [:seon.fn/sym "my.plan/complete!"]))
{:seon.fn/arglists "([request])", :seon.fn/arities [{:seon.fn.arity/order 0, :seon.fn.arity/output-refs [#:db{:id 932} #:db{:id 1230}], :seon.fn.arity/max 1, :seon.fn.arity/input #:db{:id 53096}, :seon.fn.arity/arity "1", :seon.fn.arity/return-schema #:db{:id 13342}, :seon.fn.arity/input-refs [#:db{:id 878}], :db/id 53095, :seon.fn.arity/min 1, :seon.fn.arity/argument-count 1, :seon.fn.arity/output #:db{:id 53097}, :seon.fn.arity/arguments [{:db/id 53094, :seon.fn.argument/binding {:db/id 53093, :seon.fn.binding/form "request", :seon.fn.binding/shape :symbol, :seon.fn.binding/symbol request}, :seon.fn.argument/index 0, :seon.fn.argument/order 0, :seon.fn.argument/rest? false, :seon.fn.argument/schema #:db{:id 13348}}]}], :seon.fn/doc-order 2, :seon.fn/ns #:db{:id 7657}, :seon.fn/source "(defn complete!\n  \"Complete the named item now and return it. Verify its done-when first.\"\n  {:seon.fn/doc-order 2\n   :malli/schema [:=> [:cat :my.plan/complete-request] [:or :my.plan/step-summary :seon.error/value]]}\n  [request]\n  (plan/complete! (:my.plan.item/id request) (:seon.db/connection request) (:seon.agent/id request)))", :seon.fn/spec "[:=> [:cat :my.plan/complete-request] [:or :my.plan/step-summary :seon.error/value]]", :seon.fn/sym "my.plan/complete!", :seon.fn/ast {:db/id 53107, :seon.fn.ast/input {:db/id 53096, :seon.fn.ast/children [{:db/id 53100, :seon.fn.ast.entry/order 0, :seon.fn.ast.entry/value {:db/id 53099, :seon.fn.ast/ref #:db{:id 878}, :seon.fn.ast/type ":malli.core/schema", :seon.fn.ast/value {:db/id 53098, :seon.fn.ast.entry/value-edn ":my.plan/complete-request"}}}], :seon.fn.ast/type ":cat"}, :seon.fn.ast/output {:db/id 53097, :seon.fn.ast/children [{:db/id 53103, :seon.fn.ast.entry/order 0, :seon.fn.ast.entry/value {:db/id 53102, :seon.fn.ast/ref #:db{:id 932}, :seon.fn.ast/type ":malli.core/schema", :seon.fn.ast/value {:db/id 53101, :seon.fn.ast.entry/value-edn ":my.plan/step-summary"}}} {:db/id 53106, :seon.fn.ast.entry/order 1, :seon.fn.ast.entry/value {:db/id 53105, :seon.fn.ast/ref #:db{:id 1230}, :seon.fn.ast/type ":malli.core/schema", :seon.fn.ast/value {:db/id 53104, :seon.fn.ast.entry/value-edn ":seon.error/value"}}}], :seon.fn.ast/type ":or"}, :seon.fn.ast/type ":=>"}, :seon.fn/keywords [:my.plan.item/id :seon.agent/id :seon.db/connection], :db/id 3761, :seon.schema.admission/source :core, :seon.fn/calls [#:db{:id 5650}], :seon.fn/private? false, :seon.fn/doc "Complete the named item now and return it. Verify its done-when first."}
```

## Exact retained Juniper dir expansion (1 ms)

```clojure
(let [routing (:seon.agent/routing (get @seon.operator.runtime/running-instances "default")) ctx (get-in (seon.cluster.agent/armed routing "juniper") [:seon.turn.loop/cluster :seon.sci.eval/agent-ctx]) dir (sci.core/resolve ctx 'clojure.repl/dir)] (pr-str (@dir '(dir my.agents.juniper) {} 'my.agents.juniper)))
(clojure.core/let [namespace__344514__auto__ (seon.db/pull (quote [:seon.ns/name #:seon.schema{:_ns [:seon.schema/key :seon.schema/form]}]) (quote [:seon.ns/name my.agents.juniper]))] (clojure.core/cond (:seon.error/kind namespace__344514__auto__) namespace__344514__auto__ (clojure.core/or (:seon.ns/name namespace__344514__auto__) (clojure.core/find-ns (quote my.agents.juniper)) false) {:functions (quote []), :schemas (clojure.core/into (quote {}) (clojure.core/map (clojure.core/fn [row__344515__auto__] [(:seon.schema/key row__344515__auto__) (clojure.edn/read-string (:seon.schema/form row__344515__auto__))])) (:seon.schema/_ns namespace__344514__auto__))} :else (quote {:seon.sci.eval/documentation-unavailable my.agents.juniper, :seon.error/kind :seon.sci.eval/documentation-unavailable, :seon.error/message "No public program documentation is available for my.agents.juniper."})))
```

## Exact cluster base dir expansion (1 ms)

```clojure
(let [ctx (:seon.sci.eval/ctx (get @seon.operator.runtime/running-instances "default")) dir (sci.core/resolve ctx 'clojure.repl/dir)] (pr-str (@dir '(dir my.agents.juniper) {} 'my.agents.juniper)))
(clojure.core/let [namespace__344514__auto__ (seon.db/pull (quote [:seon.ns/name #:seon.schema{:_ns [:seon.schema/key :seon.schema/form]}]) (quote [:seon.ns/name my.agents.juniper]))] (clojure.core/cond (:seon.error/kind namespace__344514__auto__) namespace__344514__auto__ (clojure.core/or (:seon.ns/name namespace__344514__auto__) (clojure.core/find-ns (quote my.agents.juniper)) true) {:functions (quote [{:sym my.agents.juniper/largest-customer, :doc "Given a seq of order rows, return the customer with the largest total.", :in [:cat [:vector :example/order-row]], :out [:map [:customer :example/customer] [:total :int]]}]), :schemas (clojure.core/into (quote #:example{:customer :string, :order-row [:map #:seon.db{:attributes true} [:example/order :example/order] [:example/amount :example/amount] [:example/customer :example/customer]]}) (clojure.core/map (clojure.core/fn [row__344515__auto__] [(:seon.schema/key row__344515__auto__) (clojure.edn/read-string (:seon.schema/form row__344515__auto__))])) (:seon.schema/_ns namespace__344514__auto__))} :else (quote {:seon.sci.eval/documentation-unavailable my.agents.juniper, :seon.error/kind :seon.sci.eval/documentation-unavailable, :seon.error/message "No public program documentation is available for my.agents.juniper."})))
```

## Operational boundary

Initial runtime health timed out; combined row probes timed out at 20,000 ms.
Separate pulls returned the complete rows above. Recorded the observations in
`docs/seon/issues/default-component-probe-times-out-after-adoption.md`.
No timeout is interpreted as row absence or healthy Flow.

Foreign working-tree edits in reader/reply and render/web tests were preserved.

## Proposed repair at the existing owner

Replace the acquisition-time documentation capture with an evaluation-time
read using the supplied database. Keep the public-row query common to shipped
and agent-installed functions, join namespace membership through :seon.fn/ns,
and use the existing selector and contract formatting helpers. The directory
must include parsed :arglists alongside :sym, :doc, :in and :out; the current
shipped directory also omits arglists despite selecting their stored bytes.
Both dir and doc must observe the same current facts, and the reads must enter
the ordinary read-evidence path so an initially empty directory can refresh.
No new schema attribute, installation relation, cache, or agent-only query
is needed.

## Regression boundary

Added directory-observes-a-function-installed-after-context-acquisition to
test/seon/sci/documentation_test.clj. It acquires the canonical SCI context,
evaluates a contracted defn, writes its canonical program row to the real
fixture database, crosses install-evaluated-rows!, then evaluates dir in the
same context. This is an evaluation/installation regression, not a Flow-loop
or provider proof. The runner arms the normal program contracts.

The first run exposed a test fixture error: directly writing the raw evaluation
row included :seon.sci.eval/evaluated?. The corrected fixture uses the existing
program/canonical-row persistence projection; no production behavior was changed
to accommodate it.

Corrected run: `bin/test-fast --paths test/seon/sci/documentation_test.clj --
seon.sci.documentation-test` on HEAD ed529ed56: 3 tests, 48 assertions,
1 failure, 0 errors. The only failure is the wanted directory row versus
the actual empty vector. Function evaluation, canonical persistence, and
installation succeeded. Snapshot run.lUvv0Y was removed by the runner.
The isolated correctness gate and platform gate are pending the production
repair; this checkpoint is a reproduced defect, not a green landing.

Files touched at this checkpoint: test/seon/sci/documentation_test.clj;
docs/seon/issues/dir-omits-the-agents-own-durable-functions.md;
docs/seon/issues/default-component-probe-times-out-after-adoption.md;
this landing note. No production files changed. All owned test shells exited;
no scratch cluster or worktree was created.

## Repair resumed — 2026-09-14

The owner authorized the actual documentation owner in eval.clj. Removed the
acquisition-time documentation map and namespace grouping. The SCI macros now
expand to ordinary evaluated calls carrying `(seon.db/db)`. `doc` resolves its
symbol in the calling SCI namespace at execution time, rather than resolving
against the context captured when the macro was created.

`program-documentation` reads public function rows through their existing
`:seon.fn/ns` relation, scoped to one namespace. `directory-value` and
`documentation-value` share that query and the existing formatting helpers.
The ordinary `seon.db/q` and `pull` paths record read evidence, including an
empty namespace's future functions. Function data now includes parsed
`:arglists`; no installation facts, schemas, or cache were added. Namespace
documentation and declared-schema listings retain their existing shapes.

The regression also records an initially empty directory's read evidence,
checks that installation invalidates it, and reads the installed function
through both local and qualified `doc` forms. The original four-namespace
fast run passed 8 tests / 96 assertions; the expanded regression and isolated
gates are recorded below when complete.

Final fast regression: 3 tests / 57 assertions, zero failures or errors.
The fixture explicitly creates the empty namespace before acquiring the
context; a missing namespace correctly returns documentation-unavailable and
is a different observation from an existing empty directory.

Adoption initially reached JVM instrumentation but refused because source
changed during adoption (publication 6aa88e34-a11a-57c3-a4e9-cca3b5fe6995).
Retried the ordinary `bin/seon init --dev default --changed
src/seon/sci/eval.clj` operation. The isolated gate overlays only the lane's
paths on HEAD; concurrent render/CSS/test edits are excluded from that proof
and were left untouched. No default lifecycle operation was used.

Isolated gate: `bin/test --paths src/seon/sci/eval.clj
test/seon/sci/documentation_test.clj
docs/prds/context-generation/research/dir-own-fns-landing-2026-09-10.md --
seon.sci.documentation-test seon.directory-test seon.repl-grammar-test
seon.help-trial-test` passed 8 tests / 109 assertions, zero failures or errors.
The runner's coordinator-and-tests phase took 150 seconds. Its successful
snapshot run.OybuZu was removed by the runner. The separate platform gate
and final live observation follow below.

Platform gate with the same owned paths and `--platform`: 84 tests / 505
assertions, zero failures or errors; coordinator-and-tests 179 seconds.
Successful root run.aAKL47 was removed by the runner. No `--all` or `--full`
run was requested or executed. `git diff --check` passed for the code and tests.
