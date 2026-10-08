---
name: clojure-testing
description: "When to write a Seon test and how, in library style: public entrance, data in and data out, tables, small setup, properties only for laws, errors asserted as data, no Var redefinition. Also the test deletion and conversion policy, three complete minimal Seon tests and the harness facts that hold today. Use for test design, fixture choice, deletion and reading results."
---

# Fewer, better tests

The laws are in [CLAUDE.md](../../../CLAUDE.md), under "Proof and performance". This skill covers how a test looks,
when not to add one, and when an existing one is deleted or converted. The owner's goal is fewer, better tests: one
test per observable behavior, through its owning entrance, asserting data. Examples cite the pinned `reference-code/`
checkouts listed in the [data-oriented-clojure skill](../data-oriented-clojure/SKILL.md); Seon examples are named by
test or function (find them with `rg`). A citation says what the source does; whether a test is green is a run's
evidence, not this skill's.

## 1. When not to add a test

Add none when one of these holds:

1. **The test would only re-express a schema's shape.** Tighten the producer's Malli schema instead. A schema does
   not prove the production behavior around it, so keep (or write) one regression through the real entrance for each
   refusal that matters: that the entrance is armed, that the writer refuses the final owning value, that state is
   unchanged after the refusal, and that the right variant reaches the reply. `m/explain` is a schema probe, not that
   proof.
2. **The behavior belongs to a dependency** (Datahike, Malli, SCI, core.async) and Seon only calls it. Seon's
   integration with it (supplied worlds, maintained-fork hooks, our report function) is Seon's behavior and is tested.
3. **The only thing to assert is printed text or ordering no consumer reads.**
4. **The change is a refactor with unchanged behavior.** The existing tests prove it.
5. **The case is a new row in an existing table** (§2.4).
6. **The check is a census** (every X compiles, carries a message, has a render pair) that boot arming, the edit hook
   or lint already performs. Name the installed check before relying on it.
7. **The claim is a wall-clock time.** Profile it and record the number as evidence; the runner holds the bound.
   Deterministic work counts (cache misses, recomputations, query counts) are different: when the spec states them,
   test them. Context-specific cache counts await the owner's context redesign; the rejected agent-loop §3
   design does not establish an implementation requirement.

Otherwise, if an agent or a user would notice the break, write one test through the entrance, asserting data.

A task names its success tests before it is assigned (§2.12): new behavior gets the test that fails until the task is
done; a refactor names the existing tests that reach it.

## 2. How to write one

**2.1 Test through the entrance the agent uses, with data in and data out.** Prefer the public function or the REPL
path over internal stages. (Libraries do sometimes reach private Vars, e.g. `malli/test/malli/core_test.cljc:2836`
dereferences `#'m/-function-schemas*`; Seon prefers the entrance because its agents call only that.)

GOOD: `edamame/test/edamame/core_test.cljc:28-30`, `(is (= 'foo (e/parse-string "foo")))`. For REPL-path behavior use
`seon.test-support/agent-value` (the value the agent reads) or `evaluate` (the whole evaluation map).

**2.2 Compare the whole value, or the narrowest key the behavior is about.**

GOOD: `edamame/test/edamame/core_test.cljc:21-26` asserts the message and `{:row 1 :col 1}`, the data a caller uses.

```clojure
(is (thrown-with-data?
     #"The map literal starting with :a contains 3 form\(s\). Map literals must contain an even number of forms."
     {:row 1 :col 1}
     (e/parse-string "{:a :b :c}")))
```

**2.3 Pick the smallest world the behavior reads, and keep setup short.** Setup that needs many lines usually means a
missing support function or a subject with too many inputs; fix that rather than copy a fixture. Never remove
required custody, render profiles or writer provenance to shorten setup.

- **A plain value**: a pure transform needs no world. A render that reads the database receives an immutable database
  value and a fixed render profile.
- **A database branch**: `support/with-database`, writes through `support/transacted!` or the owner's writer.
- **An agent evaluation**: `support/fork-cluster-ctx` plus `support/agent-value` or `support/evaluate`.

Only process, store, boot and concurrency subjects need full cluster fixtures.

GOOD: `datahike/test/datahike/test/lookup_refs_test.cljc:11-26` builds its world with one `d/db-with` form.

**2.4 Put cases of one behavior in a table.** Use `are` when one expression states the assertion; use `doseq` over
named cases when each case has several independent observations (`are` would hide which one failed). Keep tests
separate when their boundary, setup, failure class or resource lifetime differs, even if their names share a prefix.

GOOD: `datahike/test/datahike/test/lookup_refs_test.cljc:16-26` has two tables: four lookups, and three that throw.
`malli/test/malli/core_test.cljc:382-389` labels each row with the branch it takes. `clojure/test/clojure/test_clojure/string.clj:90-104`
is one row per function for one failure class (it asserts exception classes, not error data).

```clojure
(are [eid res] (= (tdc/entity-map db eid) res)
  [:name "Ivan"]   {:db/id 1 :name "Ivan" :email "@1" :age 35}
  [:name "Sergey"] nil)
```

**2.5 Use a property for a law: a round trip, idempotence, injectivity, or two paths that must agree.** Bound the
generators, fix the seed, and assert with `support/assert-check!`, which requires a positive trial count.

GOOD: `editscript/test/editscript/core_test.cljc:196-205` (dependency pattern, abridged here: one law over 2,000
trials). SEON: `cluster-branch-is-one-injective-derivation` states injectivity and repeatability with a fixed seed; that
is a law, correctly a property.

**2.6 Assert an error as data: its `:seon.error/type`, then the one member the behavior is about.** A returned error
is a value, so compare it. A thrown one is `(ex-data e)`, read once. One test per failure class per producer,
as a table of input to type. Today's example: `seon.fs.jvm-test/an-absent-path-answers-the-not-found-error-type`
asserts `(= :my.fs/not-found-error (:seon.error/type result))`. `support/refusal-data` still recognizes an error by the
pre-cut `:seon.error/at` and `:seon.error/layer` (it will misread errors after the errors cut drops them); read `:seon.error/type`
directly.

**2.7 Never redefine a Var.** No `with-redefs`, `alter-var-root`, `intern` or stand-in functions over production
Vars. Inject the external effect as a world value: a fixture `:seon.ai/send!`, a provider reply, a clock.

GOOD (Seon): `seon.ai/complete` reads its HTTP leaf from the request (`:seon.ai/send!`, default `send-request`), so
`seon.ai-test` supplies a local reply without redefining a Var and asserts what the leaf received:

```clojure
(ai/complete (seon.schema/handed-projection)
             (assoc target :seon.ai/prompt "hello"
                           :seon.ai/send! (fn [request-data]
                                            (swap! requests conj request-data)
                                            {:seon.ai/text "local reply"})))
```

To convert a stand-in test, find or add the input through which the production function already takes the effect
(a declared request key, a world member supplied by schema name), then pass the fixture value there.

SEON: `disarm-interrupts-an-in-flight-turn-and-records-it-interrupted` uses `with-redefs` over `prompt/prompt`. It is a
concurrency test (discuss with root, §3), converted when its subject's injected-world entrance exists.

**2.8 Assert printed text only in the value printer's own tests and in declared golden transcripts.** Elsewhere read
a reply back as data with `support/reply` and assert its keys. Inside the printer's tests, assert a diagnostic as
data even when the reply text is the subject (`a-renderer-that-throws-still-prints-through-the-floor-and-says-large-value`
asserts its diagnostics with regex counts over `(str diagnostics)`; that part converts).

**2.9 One behavior per test, with a short behavior name and at most one line of why.** History goes to Git and the
spec. An assertion message is prose that says what should hold; it never restates the assertion or prints a value.

**2.10 Never copy a fixture into a second file.** A shared world goes in `seon.test-support` or in the subject's
namespace, with explicit inputs and cleanup.

GOOD: `konserve/test/konserve/memory_test.cljc:22-23`: every backend calls the one `compliance-test`
(`konserve/src/konserve/compliance_test.cljc:14`), whose assertions live in the helper. SEON: `with-render-context-proc`
appears in three test files and `with-cluster` in four; check that their responsibilities and lifetimes match before
merging them.

**2.11 A docstring example is documentation, not a test.** The owner ruled `test/my/examples_test.clj`, which executed
public `my.*` examples, deleted (removed in `819a8ed4b`). A public `my.*` behavior at risk gets one focused test
through its entrance; an example that no longer runs is a docstring defect fixed where it is touched.

**2.12 A test that proves a task names it.** `(deftest ^{:seon/tasks #{…}} a-refused-settlement-is-the-forms-refusal
…)`: the deftests naming a task are its success tests (`seon.task/task-edges` `:proofs`). Installed
(`d9789e22e`): `seon.task/assign!` (src/seon/task.clj:482) refuses a task with no proofs (`:no-proofs`, :505) or whose
proofs are all green (`:proofs-green`, :507). TARGET (agent-loop.md §1.7, §3.5): after any eval request that evaluated
a form, the proofs and the tests reaching the change run on the agent's branch, and all green completes the task. Metadata
placed after the test name is refused (`:metadata-after-test-name`). Today the set holds the task's derived handle
(`seon.task/task-handle` of namespace, name and kind; never hand-type it); TARGET (lane task-tags): it holds the
`ns/task-name` symbol. A new behavior or bug regression fails before the work and passes after it. A behavior-preserving refactor names
existing green tests (§1); green alone cannot establish that the refactor has been done, so under the target
admission rule a refactor task needs one proof that fails before it (how such tasks name that proof is an open owner
question).

## 3. Delete or convert

Classify the behavior before touching its test. Then:

**Delete** a test only when:

1. its behavior is retired in the same change (the mechanism and its test leave in one commit, per the owning spec's
   per-test triage in the errors and agent-loop specs' test sections); or
2. another current test proves the same behavior through the same entrance (name it; move any extra inputs into its
   table); or
3. it re-asserts a schema's shape and one regression through the real entrance still covers refusal, delivery and
   unchanged state (§1.1); or
4. it is vacuous: no assertion event can fail on a regression. Count assertion events, including those inside
   helpers (`support/assert-check!`, a shared compliance function); a body with no literal `is` is not vacuous by
   itself. The runner counts events (`seon.test.runner/assertionless-failure`).

Delete the helpers the deletion orphans in the same commit. An owner-approved future deletion is not a present one,
and a rename alone retires no behavior.

**Convert** every other problem test in the change that touches it, so the behavior keeps its proof:

| Problem | Conversion |
|---|---|
| Private reach (`#'ns/private`, an intermediate map no outside caller reads) | an entrance test asserting the public data the private step produces |
| Var stand-in (`with-redefs`, `alter-var-root`) | an injected world value at the entrance |
| Text assertions (`str/includes?`, `re-find`) outside printer and golden tests | assertions on the reply or error data |
| Timing or revision assertions | profile evidence; keep deterministic work-count assertions the spec requires |
| A schema-shape re-test | one entrance regression for refusal, delivery and unchanged state; then delete the rest |

Never replace the only proof of a behavior with a task to write one later: the replacement lands with the cut.
When a conversion is larger than about 15 minutes, file the task and keep the old test until it lands.

**Discuss with root** before deleting or converting tests of concurrency, crash recovery, the store lock, GC and
reclamation, boot and adoption; the value printer's own tests; golden transcripts; and any test that is the only one reaching a `my.*`
function.

## 4. Three minimal Seon tests

Each is source-verified against `test/seon/test_support.clj` and the subject's source (2026-10-07); none was run for
this skill. Examples 2 and 3 show shape only: `seon.reset-edges-test/analysis-is-a-required-positive-fact` and
`seon.sci.admit-test/an-absent-storage-bound-refuses-and-names-the-key-it-wanted` already prove those behaviors, so
do not add them. Each runs as a member of `seon.test/run`, which supplies the execution handle `with-database` needs.

**Example 1: a pure value table (no world).** `seon.id/symbol-in` builds a runnable qualified handle from a
namespace, leading letter and id. This example avoids the printed-data hash that the identity owner is replacing.
Add the test to the existing `seon.id-test`; do not replace that namespace's `ns` form.

```clojure
(deftest result-handles-keep-their-namespace-and-prefix
  (are [space letter identity expected] (= expected (id/symbol-in space letter identity))
    "result" \e "abc123" 'result/eabc123
    "result" \e "012345" 'result/e012345
    "other"  \r "abc123" 'other/rabc123))
```

Require `seon.id` as `id` and refer `deftest` and `are` from `clojure.test`. No setup or teardown is needed.

**Example 2: a refused write leaves the basis unchanged (a database branch).** The behavior: the writer refuses
retracting a required analysis fact, answers a write-attempt identity, and commits nothing. Shape of
`seon.reset-edges-test/analysis-is-a-required-positive-fact`, reduced to one attribute.

```clojure
(ns seon.analysis-fact-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest a-required-analysis-fact-refuses-retraction
  (support/with-database
   (fn [connection]
     (let [target [:seon.fn/sym 'seon.id/id]
           database (db/db connection)
           basis (db/basis-t database)]
       (is (= 64 (count (:seon.program/analyzed-source-digest
                         (db/pull database [:seon.program/analyzed-source-digest] target)))))
       (let [refusal (db/transact! connection
                                   [[:db.fn/retractAttribute target :seon.program/analyzed-source-digest]])]
         (is (string? (:seon.db.write.attempt/request-id refusal)))
         (is (= basis (db/basis-t (db/db connection)))))))))
```

The first `is` is positive proof that the fact existed, so the refusal is not read from silence. `with-database`
acquires its own branch and releases it in `finally`. `db/transact!` returns its refusal today; when the errors cut lands,
also assert the variant: `(is (= <the writer's variant> (:seon.error/type refusal)))` (TARGET: read the declared
alternative from `seon.db/transact!`'s contract then; do not guess its name now).

**Example 3: an agent evaluation that answers an error map (an agent world).** The behavior: an invalid call made
from an agent's REPL comes back as an error value, not a thrown exception. Shape of
`seon.sci.admit-test/an-absent-storage-bound-refuses-and-names-the-key-it-wanted`.

```clojure
(ns seon.invalid-call-test
  (:require [clojure.test :refer [deftest is]]
            [seon.test-support :as support]))

(deftest an-invalid-call-answers-a-contract-error
  (support/with-database
   (fn [connection]
     (let [refusal (support/agent-value
                    (support/fork-cluster-ctx connection)
                    "(seon.sci.admit/admit {:seon.sci.admit/value [1 2 3] :seon.sci.admit/caps {} :seon.sci.admit/interrupt-fn (fn [])})")]
       (is (= :input (:seon.instrument/check refusal)))
       (is (= 'seon.sci.admit/admit (:seon.instrument/fn refusal)))))))
```

`agent-value` runs the source through the one REPL path with the shipped caps and time limit and returns
`:seon.sci.admit/value`. TARGET (the errors cut, instrumentation from Malli's report): assert instead
`(= :malli.core/invalid-input (:seon.error/type refusal))` and `(= 'seon.sci.admit/admit (:seon.error/operation refusal))`;
the errors spec's instrumentation example adds the misspelling problem at `[0 :my.fs/pth]` for `my.fs/read`.

## 5. The harness today

Each fact was checked against `test/seon/test_support.clj`, `src/seon/test.clj` and `src/seon/test/runner.clj` by
symbol name. Re-check a symbol before relying on it.

- **A test is an isolated agent.** `seon.test/run` takes `{:seon.test/execution … :seon.test/recording-connection …
  :seon.test/policy :named|:incremental|:platform|:all}` plus optional `:seon.test/identities`, `:seon.test/namespaces`,
  `:seon.test/changed`, `:seon.test/include-long?` and `:seon.test/check-time-limit-ms`. It runs each member on its own
  branch, records the result, and returns `:seon.test/passed?`. Agents call `(my.test/run)` and
  `(my.test/check {:seon.test/changed ['ns/test-name]})` (a test named as changed is itself a member); from an agent's SCI context `clojure.test/run-test-var` cannot see
  test Vars (live run 1), so a named test runs through `my.test/check`.
- **Worlds.** `support/with-database` runs its body on a fresh branch of the executing member's commit and releases
  it afterwards; outside a `seon.test/run` member it refuses by name (`execution-handle`).
  `{:seon.test-support/fresh-store? true}` gives an empty in-memory store with the installed attributes, for
  store-global subjects such as blob keys. `:seon.test-support/extra-schema` is only for synthetic declarations.
  `fork-cluster-ctx` forks the member's SCI context onto a fixture connection.
- **Writes.** `support/transacted!` is the one fixture write: it throws at a refused write and names the offending
  row. `program-fn-entity` returns an indexed or analyzed declaration; `agent-tx` creates a fixture agent through
  `seon.cluster.agent/creation-tx`. `apply-config!` sets dials through `config/apply!`; a later manifest changes only
  its own keys. `seed-cluster!` seeds the cluster/config path. Never hand-build a production entity or write a literal
  tx id.
- **Evaluation.** `support/evaluate` runs source through the one REPL path (fold, evaluate and show stages) and
  settles nothing; it needs `:seon.sci.admit/caps` and reads the member's database when none is given.
  `support/agent-value` returns what the agent reads.
- **Waiting and resources.** `support/await-event!` waits on a channel, latch, future or reference under a bound
  (default `event-backstop-seconds`, 20) and names what did not arrive. No sleeps. `support/closeable` adapts a
  release function to `with-open`.
- **Concurrency and processes.** Contend on the same identity or path production does. Lock exclusion needs a real
  second OS process. Wait for exit with `ProcessHandle.onExit` on the retained `(pid, start-instant)`; a closed
  connection is not an exit. `test/seon/cluster/boot_test.clj` has working examples.
- **Global state.** Ordinary tests never mutate JVM-global state; a test arms only its own Vars. Branch fixtures do
  not isolate JVM Vars, classes or live-cluster projections. `preserving-instrumentation-state` and
  `preserving-schema-registry` are cleanup for the existing host-bound harness, not permission to mutate another
  owner's state or restore roots across a reload; a subject that needs such effects uses the declared isolated
  platform path.
- **Bounds and exit.** The default body bound is 5,000 ms (`:seon.test/time-limit-ms` in
  `resources/seon/schemas/seon.test.edn`); raising it requires `:seon.test/long` (the reason, naming the measured
  operation) and `:seon.test/long-ms`. `seon.test.runner/duration-failures` reports an overrun when a body completes.
  The SCI arm interrupts interpreted work at the bound but cannot stop a host call; `seon.test/bounded-result` watches
  the body's actual exit and keeps its branch and resources until then. A timeout is not a kill or proof of cleanup.
- **Assertions.** `seon.test.runner/assertionless-failure` fails a test that reported no assertion events.
- **Pure derivations** may use a Datahike `with` value only when it carries the writer's final-report validator
  (`reference-code/datahike/src/datahike/db/transaction.cljc:1225-1234`, invoked at `:1296`). That proves the
  derivation; it does not prove durable publication, boot or adoption.
- **Proving a slice.** `bin/seon check PATH... --test NS` publishes and adopts the candidate on disposable branches,
  then boots a throwaway JVM and runs the named tests. Lanes commit through
  `bin/lane commit LANE -m MSG --test NS... PATHS`, which also lints. Name the tests that reach the change; root runs
  the broad tiers.
