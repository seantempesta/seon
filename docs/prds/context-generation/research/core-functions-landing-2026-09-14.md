---
type: research
status: active
tags: [research, agent, sci, test]
---

# Core functions — 2026-09-14

## Verification boundary

The authorized follow-up below supersedes the initial referral and auto-check
fixture boundaries. All three older auto-check tests are retained: they assert
current gate selection, candidate isolation, and seeded contract checking.
No test was deleted to obtain a green result.

The core API changes and authorized follow-up are committed. Both bare test
referrals resolve in Juniper's retained default context, and all older auto-check
tests are green without deleting tests. The final required gate has one foreign
named-basis prompt assertion failure; platform is green. Exact counts and the
assertion are below. Shared publication/adoption is not claimed globally converged.
Default was neither stopped, reforked, nor reseeded. All default probes were
read-only JVM calls, including evaluations of documentation in Juniper's retained
SCI context. Source changes use the configured development adoption hook.

The initial assignment excluded `src/seon/sci/eval.clj`. Its `build-base-ctx` installed
bare `help`, `dir`, and `doc` in `clojure.core`; it did not install `deftest` or
`is`. Bootstrap seed referrals were made explicit, but that did not repair every
created or retained namespace. At the first checkpoint, the live retained
context reported `{:retained? true, :deftest? false, :is? false}`. The owner
then authorized the file and the remaining fixture repairs; the follow-up below
records their completion.

Concurrent render edits reached `src/seon/plan.clj` during this lane. Verification
therefore moved to `tmp/core-functions-wt`, based on `d71ec0852`, with only this
lane's non-render plan hunks and other owned files applied. No foreign render
hunk or session was changed. The cleanup record below covers this worktree.

## Authorities read end to end

Read `AGENTS.md`; both model-account `:text` values in
`docs/prds/context-generation/research/explain_probe_turn40_2026_09_14.edn` and
`docs/prds/context-generation/research/explain_probe_turn2_2026_09_14.edn`;
`docs/prds/context-generation/research/live-run-2-landing-2026-09-10.md`;
`docs/seon/issues/agent-facing-symbols-do-not-resolve-from-the-agent-namespace.md`;
`docs/seon/issues/auto-check-reports-its-own-contract-violation-to-the-agent.md`;
every `src/my/*.clj`; `src/seon/bootstrap.clj`; `src/seon/sci/kernel.clj`;
the auto-check owner `src/seon/test/accretion.clj`;
`test/seon/help_trial_test.clj`; and
`docs/prds/context-generation/research/help_trial_2026_09_09.clj`.
The named `dir`/`doc` implementation in `src/seon/sci/eval.clj` was read in full
and kept read-only. Also read the active roadmap entry and the relevant skills:
data-oriented-clojure, repl, clojure-testing, data-modeling, datahike, and
seon-flow-architecture.

## Dependency ledger and reproduced causes

| Mechanism | Dependency and first-party owner | Finding |
|---|---|---|
| Generated function arguments | `reference-code/malli/src/malli/generator.cljc`; `src/seon/test/accretion.clj` | Malli's `:cat` generator produces a sequence; kernel invocation requires a vector. Normalize at generator output. |
| Property exceptions | `reference-code/test.check/src/main/clojure/clojure/test/check/properties.cljc`; `src/seon/test/accretion.clj` | test.check captures a thrown exception as its result. Propagate that exception; only generator construction can yield a non-generatable skip. |
| Live SCI names and docs | `reference-code/sci/src/sci/core.cljc`; `src/seon/sci/eval.clj` | Use the retained context and its database-backed `dir`/`doc`. Bootstrap facts alone cannot add universal core referrals. |
| Writes and changed values | `reference-code/datahike/src/datahike/writer.cljc`; `src/seon/note.clj`, `src/seon/plan.clj`, `src/seon/cluster/message.clj` | Return the admitted transaction's values. Deletion returns the removed note from `:db-before`. Message delivery preserves its constructor's identity. |
| Background completion | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`; `src/seon/flow.clj`, `src/seon/effect.clj` | Use the real work launcher. Pending effects have `:seon.effect/notify`; settlement replaces it with `:seon.effect/to`. Await terminal result facts under the fixture backstop. |
| Plan state | `src/seon/plan.clj` rules and `plan` | Item reads previously passed empty readiness/blockage sets and no current item. Reuse the whole plan's derivation, including nesting and parent. |

The exact stored run-2 definition was retrieved from default, together with its
output, before changing the checker. It is also committed in
`test/seon/core_functions_test.clj` as `run-two-definition`:

```clojure
(defn largest-customer
  "Given a seq of order rows, return the customer with the largest total."
  {:malli/schema [:=> [:cat [:vector :example/order-row]] [:map [:customer :example/customer] [:total :int]]]}
  [rows]
  (->> rows
       (group-by :example/customer)
       (map (fn [[customer orders]]
              {:customer customer
               :total (reduce + 0 (map :example/amount orders))}))
       (sort-by :total >)
       first))
```

Stored output, exact bytes:

```text
No example test gates my.agents.juniper/largest-customer; add one to teach intended behavior.
Auto-check skipped: seon.sci.kernel/invoke violated its contract (invalid-input): invalid type at [[:seon.sci.eval/args]]
```

Live generator probe, seed 424242:

```clojure
{:arguments [[-18 207856276 27160381 -4 828 -505868]],
 :class "class clojure.lang.LazySeq", :vector? false}
```

After normalization the original function fails for the meaningful reason:
arguments `[[]]` produce nil, violating its declared map output. A valid
vector-input `row-count` passes all 25 generated cases. The regression also
injects a checker exception under preserved instrumentation and requires the
same exception to propagate; it must not become skipped text. This proves the
checker boundary, not an independently observed fault-committer transaction.

## Per-function review

This is a dated review of the public callable functions/macros in `src/my/`.
Every retained name below now has a complete first sentence, a worked example,
and a return-shape description. Generator Vars are data, not functions.
`my.turn/render-namespace-ai` and `usage-form` are declared internal helpers.
Value constructors retain their names because the turn interprets their
returned values; the constructors do not perform the delivery or transition.
The existing `background` and `run` macro spellings are retained.

| Before → after | Why / useful result |
|---|---|
| `my.agent/identity` → same | Names actual `:my.agent/*` identity, namespace and steward keys. |
| `my.agent/done` → same | Explains the returned wait disposition and session completion, matching run 2. |
| `my.agent/settings` → same | Documents overrides and how to request effective settings. The context-renders lane owns removal of the derived remaining-turn field in the underlying owner. |
| `my.agent/settings!` → same | Exact qualified setting example and resulting overrides. |
| `my.background/background` → same | Replaces the invalid shell-command example with a real argv call; identifies the effect ref and independent deadline. Invalid syntax names the macro, expected call, and supplied forms. |
| `my.background/poll` → same | Explains pending versus settled effect evidence, with a real background call. |
| `my.background/await` → same | Explains that it returns a wait condition and does not block. |
| `my.edit/form` → `form!` | File mutation spelling; example uses a real prior digest and named form. |
| `my.edit/exact` → `exact!` | File mutation spelling; example shows exact old/new text and prior digest. |
| `my.edit/lines` → `lines!` | File mutation spelling; example supplies inclusive bounds and old/new windows. |
| `my.fs/read` → same | Documents window versus whole-file digests and paging keys. |
| `my.fs/write` → `write!` | File mutation spelling; example shows nested content and absence precondition. |
| `my.fs/glob` → same | Root, pattern and traversal bounds, with examined/returned/completeness data. |
| `my.fs/stat` → same | Explicit path metadata and symbolic-link behavior. |
| `my.message/inbox` → same | Chronological order and actual inbox entry keys. |
| `my.message/read` → same | Identity-first map; stored message shape and missing-message error. |
| `my.message/send` → same | Addressed returned value with its eventual message id; explains form-result delivery. Uses the canonical fresh-event identity function. |
| `my.message/decline` → same | Adds an id to the returned declination; delivery preserves it just as for ordinary messages. |
| `my.note/notes` → same | Current, agent-scoped notes in identity order. |
| `my.note/add!` → same | Replaces raw transaction teaching with the actual useful note API; shows identity-first upsert. |
| `my.note/forget!` → same | Returns the removed note including its content, rather than only the id. History remains queryable. |
| `my.plan/plan` → same | Describes the objective, steps, current item and derived work sets. |
| `my.plan/item` → same | Fixes state, parent and depth by using the owning plan's derivation. Example uses separate write/read evaluations because reads carry an immutable basis. |
| `my.plan/current` → same | Documents empty-map meaning and the selected item shape. |
| `my.plan/ready` → same | Explains readiness and dependency ids. |
| `my.plan/blocked` → same | Explains blockage and dependency ids. |
| `my.plan/items` → removed; use `steps` | This public function duplicated `steps` exactly; no caller remained. The system-side ordered-id `seon.plan/items` is a different operation and remains. |
| `my.plan/steps` → same | All authored steps in tree order with state and criteria. |
| `my.plan/ready-subjects` → same | Explains resolved subject ids and duplicate removal. |
| `my.plan/add!` → same | Identity/title/criterion map; returns the saved step. |
| `my.plan/update!` → same | Identity first; omitted fields retained; returned changed step. |
| `my.plan/complete!` → same | Worked criterion check, completion transaction, and current-selection clearing. No guessed `complete` alias. |
| `my.plan/current!` → same | Selects an owned open step and returns it as current. |
| `my.shell/run` → `run!` | Makes the effect explicit; argv/cwd/stdin and exit/stdout/stderr are documented. |
| `my.test/run` → same | Documents real count maps and honest empty results; a refused symbol lookup remains one flat error. |
| `my.turn/complete` → same | A returned completed disposition with its result text. |
| `my.turn/wait` → same | A returned wait disposition with its reason. |
| `my.web/fetch` → same | Resource URL, HTTP result fields and body access; the regression supplies `example-url` from a real local HTTP server. |
| `my.web/search` → same | Query/max-results, result data and provider response blob; regression configures the real HTTP owner to the local server. |

The system-side `seon.plan/items` now delegates each identity to `item` and
propagates a missing-item error instead of deriving a second inconsistent view.
No render function was edited by this lane.

## Read-only plan evidence on default

Before the fix, the same database value produced:

```clojure
[{:id "juniper/read", :steps-state :completed, :item-state :completed}
 {:id "juniper/define", :steps-state :completed, :item-state :completed}
 {:id "juniper/test", :steps-state :completed, :item-state :completed}
 {:id "juniper/save", :steps-state :completed, :item-state :completed}
 {:id "juniper/add", :steps-state :ready, :item-state :open}
 {:id "juniper/again", :steps-state :blocked, :item-state :open}
 {:id "juniper/report", :steps-state :blocked, :item-state :open}]
```

The first probe exceeded its declared 10-second limit. The projection-scoped
retry with a 30-second bound returned in 13,579 ms. No system restart was used.

After development adoption, the retained-context projection scoped the same
read-only item calls and returned in 21 ms:

```clojure
[{:my.plan.item/id "juniper/add", :my.plan/state :ready, :my.plan/depth 0}
 {:my.plan.item/id "juniper/again", :my.plan/state :blocked, :my.plan/depth 0}]
```

## Retained-context documentation bytes

Both forms ran through `seon.sci.eval/evaluate` using Juniper's retained
`:seon.sci.eval/agent-ctx`, explicit default connection/database, assigned
namespace, and a 10-second evaluation limit. Neither evaluation returned an
error. The JVM printed the raw returned values to avoid MCP projection elision.

Read-only reproduction form for JVM mode, with root and cluster `default`:

```clojure
(let [instance (get @seon.operator.runtime/running-instances "default")
      ctx (get-in @(:seon.agent/routing instance)
                  [:seon.agent/armed "juniper" :seon.turn.loop/cluster
                   :seon.sci.eval/agent-ctx])
      connection (:seon.boot/cluster-connection instance)]
  (seon.schema/call-with-projection-state
   (:seon.sci.eval/projection-state ctx)
   (fn []
     (mapv
      (fn [source]
        (seon.sci.eval/evaluate
         {:seon.sci.eval/ctx ctx
          :seon.db/db @connection :seon.db/connection connection
          :seon.agent/id "juniper"
          :seon.cluster.eval/ns [:seon.ns/name 'my.agents.juniper]
          :seon.cluster.eval/source source
          :seon.sci.eval/time-limit-ms 10000
          :seon.sci.admit/caps
          (seon.config/result-caps (seon.config/defaults))
          :seon.config/on-core-error :panic}))
      ["(dir my.note)" "(doc my.note/add!)"]))))
```

Inspect both complete envelopes, then print each `:seon.sci.admit/value` from
the JVM session's raw `*1` to preserve exact bytes without MCP projection.

`(dir my.note)`:

```clojure
{:schemas {:my.note/add-request [:map [:my.note/id :my.note/id] [:my.note/content :my.note/content] [:my.note/about {:optional true} :my.note/about] [:seon.db/connection :seon.db/connection] [:seon.agent/id :seon.agent/id]], :my.note/forget-request [:map [:my.note/id :my.note/id] [:seon.db/connection :seon.db/connection] [:seon.agent/id :seon.agent/id]], :my.note/note [:map {:seon.db/attributes true, :seon.render/ai seon.note/render-note-ai, :seon.render/html seon.note/render-note-html, :seon.render/form seon.note/render-note-form} [:my.note/id :my.note/id] [:my.note/agent :my.note/agent] [:my.note/content :my.note/content] [:my.note/about {:optional true} :my.note/about]], :my.note/notes [:vector #:seon.render{:ai seon.note/render-notes-ai, :html seon.note/render-notes-html, :form seon.note/render-notes-form} :my.note/note], :my.plan/request [:map [:seon.db/db :seon.db/database-value] [:seon.agent/id :seon.agent/id]], :seon.error/value [:map [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/doc {:optional true} :seon.error/doc] [:seon.error/data {:optional true} :seon.error/data]]}, :functions [{:sym my.note/add!, :arglists ([request]), :doc "Add or update my note by identity and return the saved note.", :in [:cat :my.note/add-request], :out [:or :my.note/note :seon.error/value]} {:sym my.note/forget!, :arglists ([request]), :doc "Forget my current note while retaining its database history.", :in [:cat :my.note/forget-request], :out [:or :my.note/note :seon.error/value]} {:sym my.note/notes, :arglists ([request]), :doc "Read my current notes in identity order.", :in [:cat :my.plan/request], :out [:or :my.note/notes :seon.error/value]}]}
```

`(doc my.note/add!)`:

```clojure
{:summary "Add or update my note by identity and return the saved note.", :body "Supply :my.note/id and :my.note/content; optionally supply :my.note/about\nas an existing entity ref. My agent identity and connection are supplied.\nReturns {:my.note/id string :my.note/content string :my.note/agent ref},\nwith :my.note/about when present. An id owned by another agent is refused.", :example "(my.note/add! {:my.note/id \"observation\"\n:my.note/content \"Verified the customer total.\"})", :arglists ([request]), :in [:cat [:map [:my.note/id :my.note/id] [:my.note/content :my.note/content] [:my.note/about {:optional true} :my.note/about] [:seon.db/connection :seon.db/connection] [:seon.agent/id :seon.agent/id]]], :out [:or [:map {:seon.db/attributes true, :seon.render/ai seon.note/render-note-ai, :seon.render/html seon.note/render-note-html, :seon.render/form seon.note/render-note-form} [:my.note/id :my.note/id] [:my.note/agent :my.note/agent] [:my.note/content :my.note/content] [:my.note/about {:optional true} :my.note/about]] [:map [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/doc {:optional true} :seon.error/doc] [:seon.error/data {:optional true} :seon.error/data]]]}
```

## Regression design and gate record

`test/my/examples_test.clj` derives the namespace list from the source namespace
forms, obtains every public function from real `(dir ns)`, and reads examples
from real `(doc symbol)` metadata. It reads those examples as Clojure forms and
evaluates each in the canonical database/SCI fixture with armed contracts.
The fixture admits real evaluation identities for write provenance, runs real
filesystem and local HTTP capabilities, and waits for real background terminal
facts. It also checks every help Tools entry and every returned qualified
function symbol resolves, and verifies the contract-error doc map reaches the
agent on a bad note call. A real statically indexed fixture test exercises
returned counts through SCI. This is deliberately separate from runtime test
admission: that path reproduces the open usage-schema defect described in
`docs/seon/issues/incremental-publication-refuses-test-usage.md`. Adding usage
metadata to the runtime source did not repair its projected row either.

The background fixture's originally missing turn agent and opening transaction
are corrected, and setup now asserts the admitted transaction. The help grammar
test retains exact complete-text equality; its redundant fixed character count
was removed because changing truthful help prose changes that count.

Initial required fast pass: **45 tests, 901 assertions, 7 failures, 0 errors**.
Six were owned fixture/assertion defects corrected during iteration. The seventh
was `seon.loop-proof-test/virtual-loop-end-to-end`, line 533: it expects the
prompt for an already-open turn to include later evaluations, contrary to the
landed named-basis prompt behavior. This overlaps the existing issue
`docs/seon/issues/prompt-tests-retain-incompatible-turn-fixtures.md` and its
excluded turn/prompt owner. This lane did not edit that concurrently owned test.

The broad isolated gate ran the owned namespaces plus the three required
integration namespaces: **82 tests, 1,037 assertions, 6 failures, 8 errors**,
233 seconds in the coordinator. Its snapshot was
`ecb40bd76301a2cd6c275715a8132ed8a6fb5ad1264958693695042d6f6a714a` over
`d71ec0852`. The new API and exact run-2 checker regressions passed. The failed
subjects were seven older shell tests, three older auto-check tests, and the
named-basis prompt assertion in `seon.loop-proof-test`.

The shell fixture was then fixed through the existing `config/apply!` owner.
Its old raw configuration transaction was refused, so blob handling later
found no threshold. Its effect setup also lacked required turn facts and the
real environment, and its handler wrapper tried to publish absent staged writes
on a valid cwd refusal. The repaired isolated shell gate is green:
**8 tests, 42 assertions, 0 failures, 0 errors**, 31 seconds in the coordinator,
snapshot `de446363f111de05b631fde18b6c06c4c7dd85e22ee32eb722444a19183e69fa`.
Only comment/local-variable/test-name vocabulary cleanup followed that gate;
the production and assertion behavior is identical. The preceding fast shell
iteration had 8 tests, 41 assertions and one cwd-wrapper error, repaired here.

The three old auto-check failures reproduce without this lane's source changes:
**8 tests, 24 assertions, 2 failures, 2 errors** at `d71ec0852`, using
`bin/test-fast --paths test/seon/test/accretion_test.clj -- seon.test.accretion-test`.
The [auto-check issue](../../../seon/issues/archive/auto-check-reports-its-own-contract-violation-to-the-agent.md)
records the exact admission boundaries. The full broad gate remains red; a
later focused green gate does not erase that evidence.

The separate `bin/test --platform` is green: **84 tests, 505 assertions,
0 failures, 0 errors**, 76 seconds in the coordinator. It exercised this lane's
production changes before the shell fixture repair. No `--all` or `--full` ran.

The final focused example pass is green: **2 tests, 235 assertions, 0 failures,
0 errors** (the count includes the real arithmetic test executed by
`my.test/run`). The broader gate also includes the existing shell and
auto-check namespaces, so their failures are not excluded from its result.

At the convergence check, `current-src` was
`6aa8ad08-e6b4-5072-8c62-3f862ae1e430` and default's adopted source fact was
`6aa8a23e-c981-5789-bc4f-48f8c43e62f3`. The publication log explicitly reported
“Source changed during development adoption; the next edit must converge it.”
Consequently the live note/plan observations above are claimed individually;
they are not a claim that every shared edit completed adoption.

The final read-only check returned in 4 ms:

```clojure
{:published "6aa8b18a-9ed0-57ed-a41a-de4da3084ff7"
 :adopted "6aa8b002-bc44-5500-8e78-c6e309f97fc0"
 :equal? false :bare {:deftest? false :is? false}}
```

The first attempt incorrectly passed a database value to
`seon.cluster.source/current`; its contract rejected the probe. The corrected
call above passes the instance's `:seon.store/store`. No cluster state changed.

## Commits and cleanup

| Commit | Slice |
|---|---|
| `6ed95a2ce` | my.agent documentation |
| `58527cf0d` | my.background documentation and syntax errors |
| `1d99c82a7` | my.edit mutation names and examples |
| `2a276cce3` | my.fs mutation name and examples |
| `c98d61b01` | my.message identities and documentation |
| `62d1200fc` | my.note changed return and documentation |
| `250d9ac9e` | my.test error propagation and documentation |
| `f3e67310c` | my.turn constructor documentation |
| `adca64fa0` | my.web request examples |
| `cdad23ed6` | my.plan API and owning-plan derivation |
| `427cd5f5e` | auto-check generator and core-fault propagation |
| `9f6ca8880` | bootstrap help and seed referrals |
| `0824fa2ff` | canonical SCI examples and resolution regression |
| `8dd90415a` | my.shell mutation name and real fixture configuration |

All commits were path-limited. The plan commit used a temporary index holding
only this lane's non-render hunks; the shared renderer edits stayed uncommitted
in the working tree. No render hunk was included. No branch was switched, no
foreign session was operated, and default was never stopped or reseeded.

Cleanup: all lane test commands exited. A process-table and cwd-holder check
found no live holder of `tmp/core-functions-wt`; its `reference-code` symlink
was unlinked before `git worktree remove --force`. The worktree, including its
retained failed run root, was removed. Own top-level probe logs, patch and
formatting scratch were deleted after their results were recorded here. The
orchestrator's live files and foreign worktrees were preserved.

## Concrete remaining referral change

Historical proposal: the owner authorized this exact scope on 2026-09-14.
Both seams are now patched; see the follow-up evidence below.

The excluded owner can add these two entries where `install-program-doc!`
already updates `clojure.core`, and likewise to `build-base-ctx`'s initial core
referrals:

```clojure
'deftest (sci/resolve ctx 'clojure.test/deftest)
'is (sci/resolve ctx 'clojure.test/is)
```

The regression must use an agent namespace with no declared clojure.test
referrals, resolve both bare names, evaluate a bare test declaration, and check
the retained-context update separately. Bootstrap-specific referrals are not
that proof. This proposed change is recorded for review and has not been
applied to the excluded file.

## Authorized follow-up — 2026-09-14

`build-base-ctx` and `install-program-doc!` now put the existing SCI
`clojure.test/deftest` and `is` Vars in `clojure.core`, alongside `dir`/`doc`.
The canonical regression checks a namespace with no test referrals, both the
initial base and acquired context, evaluates a bare test declaration, and
admits an ordinary test through `seon.db/transact!`. A separate regression
starts with an old base lacking the referrals, forks an agent context, updates
the base, and uses `fork-for-turn` to receive the new bindings in the identical
retained context. It then evaluates another bare test declaration.

The two remaining admission failures exposed a real schema defect:
`:my.turn/usage-unit` is a render request but declared `:seon.db/attributes true`.
The bridge consequently required every ordinary test to carry usage=true.
Removing that erroneous marker preserves the render request's contract and
lets normal test entities follow their existing `:seon.test/test` schema.
The regression does not manufacture a usage fact. The older fixtures now
assert admission, carry declared program provenance and namespace refs, and
use sets for unordered call edges. Their redundant extra-schema row was removed.

### Exact foreign loop-proof boundary

The required path-limited fast run at `6a0781d21`, with only this follow-up's
four files overlaid, reports:

```text
FAIL in (virtual-loop-end-to-end) (loop_proof_test.clj:533)
three-form reply, actual handles, and additive history
expected: (= (stored-text (clojure.core/deref connection)) (:seon.cluster.prompt/text (prompt)))
```

The actual comparison is false. Current history has 9,763 UTF-8 bytes,
SHA-256 `8dad0ea03a31fbbe3c90011d72af80955f5bf7902e2cf9e9c8601b2289b78bfc`;
the named-turn prompt has 9,583 bytes,
SHA-256 `992c26150af40d5c0bf80a90ed5580421a7038cd2e482cefe79a7f82e72bdfe1`.
Both contain the three sources `(+ 1 1)`, `(+ 2 2)`, `(+ 3 3)`;
only current history includes their later response lines with values 2, 4, 6.
This is the same assertion reproduced before this follow-up and follows
`63ac0608a`'s named-opening-basis change in `src/seon/render.clj`. The lane
did not edit that owner or the concurrently edited loop-proof test.

### Final gate and live evidence

All four follow-up source/test paths were overlaid on HEAD `6a0781d21`:
`src/seon/sci/eval.clj`, `resources/seon/schemas/my.turn.edn`,
`test/seon/sci/documentation_test.clj`, `test/seon/test/accretion_test.clj`.
The selected namespaces were `seon.sci.documentation-test`,
`seon.test.accretion-test`, `seon.core-functions-test`, `my.examples-test`,
`seon.help-trial-test`, `seon.repl-grammar-test`, `seon.loop-proof-test`.

| Command | Result |
|---|---|
| `bin/test-fast --paths <four paths> -- <seven namespaces>` | 27 tests, 564 assertions, 1 failure, 0 errors; only the foreign assertion above. |
| `bin/test --paths <four paths> -- <seven namespaces>` | 27 tests, 568 assertions, 1 failure, 0 errors; the same assertion independently confirmed. Coordinator: 227 seconds. |
| `bin/test --paths <four paths> --platform` | 84 tests, 505 assertions, 0 failures, 0 errors. Coordinator: 129 seconds. |

Both isolated gates used snapshot
`b56627525353c93d82f684b62f57ddd928feb99bf4e0571f385367fb6533299b`.
The first referral iteration had a test syntax error, immediately repaired;
the next iteration exposed two asserted admission errors, repaired by the usage
request schema change. No failing semantic assertion was removed.

Normal development adoption first failed in the tracked
`seon.fn/exact-source:142` offset race. The lane applied the hot-reloaded
`seon.sci.eval` owner, invoked `install-program-doc!` on the existing base, and
used `fork-for-turn` with Juniper's existing `:seon.sci.eval/agent-ctx` to receive
that base update. A `finally` re-armed instrumentation with the existing
projection. The operation returned in 329 ms:

```clojure
{:same-retained-context? true, :same-database-basis? true}
```

The separate read-only JVM verification returned in 3 ms:

```clojure
(let [instance (get @seon.operator.runtime/running-instances "default")
      ctx (get-in @(:seon.agent/routing instance)
                  [:seon.agent/armed "juniper" :seon.turn.loop/cluster
                   :seon.sci.eval/agent-ctx])]
  (sci.core/binding [sci.core/ns (sci.core/create-ns 'my.agents.juniper)]
    {:retained? (some? ctx)
     :deftest? (boolean (sci.core/resolve ctx 'deftest))
     :is? (boolean (sci.core/resolve ctx 'is))}))

{:retained? true, :deftest? true, :is? true}
```

A later normal adoption reached SCI acquisition and JVM instrumentation, then
reported source changes during adoption. A final read-only probe returned in
6 ms and independently observed the schema repair in default:

```clojure
{:referrals {:deftest? true, :is? true}
 :usage-schema "[:map {:description \"A usage-test render request, not an additional entity constraint on every test.\", :seon.render/form my.turn/usage-form} [:seon.test/sym :seon.test/sym] [:seon.test/usage [:= true]]]"
 :published "6aa8b667-340d-5504-8a30-53684ef05b5d"
 :adopted "6aa8b5af-69a7-5a00-92a1-04661375d322"}
```

An extra closing parenthesis in that final probe's first attempt was rejected
by the MCP reader before evaluation; the corrected form produced the value
above. Neither verification wrote database facts or evaluated a Juniper turn.
Default was never stopped, reforked, or reseeded.

Follow-up commits: `1a0e688e5` (referrals, ordinary test admission and regressions),
`6d497561d` (all older auto-check fixtures, preserved assertions).
The referral, checker, and ordinary test admission issues are resolved and
archived. Explicit runtime usage metadata loss remains a separate filed issue;
no usage flag was invented to obtain admission.

All follow-up test and adoption commands exited. The successful platform root
was removed by the gate; the retained failed root `tmp/test-runs/run.ChYKZC`
was removed after process and cwd-holder checks found no live owner. The lane's
top-level logs were removed after recording results here. `tmp/core-functions-wt`
remains absent. Foreign edits, test roots and operator hook processes were left
alone.
