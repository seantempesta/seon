# S5-03h: failed definition, then a dependent form (same-pass admission design proof)

Lane s5-03h, HEAD `91b6b5706`. Notes only; no production or test change. Every probe ran in SCI mode on agent
`s5-03h` (scratch namespace `my.s5-03h`) through the one entrance; nothing was defined in default's JVM. Question
(reconciled-plan Q7): when a definition fails, can one end-of-pass analysis still avoid certifying it, and what does
the later dependent form see?

## Verdict

A failed definition needs no early analysis and no early fact application. Admission keys on SCI's completed root
write, not on the form's outcome, so there are exactly four failure shapes and the captured-per-write seam of
S5-03a (`redef-design.md`) handles all of them unchanged:

| Shape | Probe form | What SCI does | Writes the observer sees | Stored today |
|---|---|---|---|---|
| K1 init or compile fails, new name | `(defn bad1 [x] (nosuch-fn x))`, `(def bad2 (throw ...))` | Var interned UNBOUND (`SciUnbound`), no root set | none | no entity, no digest |
| K1 init or compile fails, stored name | `(defn base2 ...)` good, then `(defn base2 [x] (nosuch-fn x))`; `(def keeper (throw ..))`; `(def ^{:doc .. :malli/schema ..} mk (throw ..))` | root AND metadata unchanged (`(base2 1)` still 11, `(meta #'mk)` still the old contract) | none | old entity, old digest, old source |
| K2 bound, derivation throws | `(defn ^{:malli/schema [:=> [:cat :my.s5-03h/nosuch] :int]} cm [x] (inc x))` | root set, arming leaves a refusing root | one bind | no entity; the form carries "Not admitted ..." |
| K3 bound, then the form errors | `(do (defn g1 [] 1) (throw (ex-info "after bind" {})))` | root set, form throws | one bind | entity `g1` with the INNER definition's source |

The pinned contract is in the SCI fork: `*write-observer*` fires "once per write, after the root is set"
(`reference-code/sci/src/sci/lang.cljc:71-75`, event built in `bindRoot` `:121-127`, SCI `ce0aa53b9`). A failed
init therefore emits nothing, and the pass has nothing to certify. That is the dependency's guarantee; the
design relies on no Seon-side flag.

## What exists (pinned HEAD)

- Observer and arming: `src/seon/sci/observe.clj:42-53`, `seon.sci.arm/arm!` (`src/seon/sci/arm.clj:44`). An arm
  failure replaces the root with a refusing root and tags the write `:seon.batch/arm-error` (`:68-69`); the
  definition still answers its Var.
- Derivation: `seon.turn.batch/derive-form` (`src/seon/turn/batch.clj:303`). `finals` = last `:kind :root` bind
  per Var, `analyzed` only `(when (seq finals) ...)`, so a form with no completed bind runs no analysis and no
  `write-entity`. `declared` grows only from bind writes (plus unbound Vars of a namespace the form changed).
- `write-entity` (`:241`) calls `sfn/contract-facts-row` (`src/seon/fn.clj:3140`) for a contracted function; a
  malformed contract throws there. `derive-step` (`src/seon/batch.clj:543-582`) catches per form and makes it that
  form's `:seon.batch/facts-error`; `seon.turn:5025` turns it into "Not admitted to the program" beside the value.
- `apply-pending` (`batch.clj:513`) puts a derived change on the working value before the next form.

## Probe results (agent `s5-03h`)

1. **K1, new name.** `(defn bad1 ...)` errors "Unable to resolve symbol: nosuch-fn" (1 ms). `(bad1 1)` errors
   "Attempting to call unbound fn: #'my.s5-03h/bad1" (0 ms). Evaluation rows 3 and 4 carry `:seon.cluster.eval/error`,
   no `call-count`, no value, `code-evidence :complete`. `[:seon.fn/sym my.s5-03h/bad1]` pulls nil. `(def bad2 (throw ..))`
   errors; `bad2` then evaluates to `#object[sci.impl.vars.SciUnbound]` (a VALUE, not an error).
2. **Dependent after K1.** `(defn dep1 [x] (bad1 x))` is admitted (`calls 8`, digest `ddfc021c`), stored
   `:seon.fn/calls [clojure.core/defn]`: no edge to `bad1`. `(dep1 1)` returns the actual error value of the
   unbound call, no diagnostic. `(defn dep2 [] (+ 1 bad2))` the same, and `(dep2)` returns the actual
   `ClassCastException` on `SciUnbound`. Nothing is invented for the failed Var.
3. **K1 over a stored name.** `base2` stays `(defn base2 [x] (+ x 10))`, digest `cfa28f2b`; `(def after (base2 1))` = 11;
   `dep3` (defined after the failure) is admitted with `calls [clojure.core/defn my.s5-03h/base2]`, a real edge to the
   surviving entity. `keeper` (`(def keeper (throw ..))` over a defn) stays `(defn keeper [x] (* 2 x))`, digest `27064653`,
   `(keeper 4)` = 8. `mk` keeps its old `:malli/schema` and old root (`(mk 2)` = 6).
4. **K2 malformed contract.** The defn answers its Var symbol (21 ms, 11,015 armed calls, the contract arm) with ONE diagnostic:
   "Not admitted to the program: its program facts were refused, so any Var it bound exists in your SCI context
   only ... derive: :malli.core/invalid-schema" (producer `seon.schema/contract-product`, chain from
   `seon.fn/contract-facts-row` `fn.clj:3151`). No entity (`cm` pulls nil). `(cm 1)` returns the refusing root's
   error "my.s5-03h/cm is unavailable: its contract did not arm". `dep4` (contracted, calls `cm`) is admitted
   (223 armed calls) with `calls [clojure.core/defn]` only; `(dep4 1)` returns the same actual error,
   `code-evidence :unknown`. A well-formed contract whose body breaks it (`cm2` returns a string for `:int`) is
   admitted, and its use returns the result refusal (`:unknown` evidence), i.e. a runtime failure is not a failed
   definition.
5. **K3.** The `do` form errors "after bind" with `call-count 8`; `g1` is admitted with `:seon.fn/source "(defn g1 [] 1)"`
   (the inner definition's exact text, not the `do`), and `(g1)` = 1.
6. **Tests.** `(deftest t-bad (is (nosuch-fn)))` and `(deftest t-run-bad (is (= 1 (nosuch2))))` fail at definition
   ("Unable to resolve symbol"): no entity for either, though `(resolve 't-bad)` finds an interned Var. A deftest that
   defines fine and calls the dependent (`t-dep-of-failed`: `(is (= 1 (dep1 1)))`) runs RED:
   `{:test 1 :pass 0 :fail 0 :error 1}`. A failed definition gives no digest, so no evidence key can name it, and its
   dependent's test cannot go green.
7. **Edge never recomputed.** After `(defn cm [x] (inc x))` is fixed in a later pass, `dep4` still stores `calls [clojure.core/defn]` (`dep1` likewise; `bad1` was never defined). The same loss happens without
   any failure: `(declare later) (defn early [] (later)) (defn later [] 1)` stores `early` with
   `calls [clojure.core/defn]` and no edge to `later`. This is the open task `seon.turn.batch/a-declared-var-call-stores-no-edge`
   (resources/seon/tasks/seon.turn.batch.edn:27) plus a second half: a dependent is not re-analyzed when its callee
   is defined later (finding 1).

## Costs

| Item | Measure |
|---|---|
| Failed form with no completed bind (K1) | 0-1 ms per form, no `analyze-form`, no `apply-facts` (replies: `:ms 1`, `:ms 0`) |
| K3 form | 1 ms, 8 armed calls |
| K2 defn | 21 ms, 11,015 armed calls (`arm!` of a contracted defn; the same 20 ms, 11,074 calls for a good contracted defn `cm2`) |
| Allocation | not measured: the shared JVM's thread-allocation counters are dominated by other lanes (see `redef-design.md`) |

Failure adds no cost beyond the arm that already happens for a good contracted definition.

## Seam for this case (what root approves)

The S5-03a seam holds: capture one small value per final root write at the write, derive after the pass. For failure:

1. **Capture comes only from the bind event.** A failed init has no event, so K1 contributes no capture, no
   `declared` name, no entity, and the dependent sees today's facts. Do NOT derive the program from namespace Vars
   at the pass end: a failed new name leaves an unbound Var there, and the S5-03a falsifier (final `@v`/`(meta v)`
   instead of per-write capture) applies. Not probed here: what an end-of-pass walk of namespace Vars would do with
   `SciUnbound`.
2. **K3 must derive.** The form's outcome is irrelevant; capture covers every bind event of a form even when the form
   errored. The existing `form-step`/`derive-step` order already does this; the batched version must keep capturing
   writes of errored forms.
3. **Derivation failures stay per write.** K2's throw (`contract-facts-row`) must stay that one form's
   `:seon.batch/facts-error`. In an end-of-pass loop the single `runtime-analysis-batch` call analyzes sources only,
   so it cannot throw for K2; the per-write `write-entity` step needs the same per-form catch that `derive-step`
   has now (`batch.clj:574-582`), keyed by form ordinal, so one bad contract cannot drop its neighbours' entities.
4. **No `apply-pending` dependency.** The failed form proposes nothing, so a later form never needs to read it from
   the working value. The dependent's analysis uses `declared` (bind events only), matching today.
5. **Delivery stays where it is.** The failure reaches the agent as the failed form's own error value (K1, K3) or as
   the "Not admitted" diagnostic beside the Var symbol (K2); the dependent's use returns its actual value or error.

If the one-call analysis ever loses the K1/K3 distinction (a form that errored but bound), the cost is stated
in S5-03a: analysis before the later form, i.e. today's shape for that form only. This case does not need it.

## Proof specified (`test/seon/cluster/turn_test.clj`, lands with S5-04, not authored here)

`seon.cluster.turn-test/a-failed-definition-does-not-certify-a-later-dependent-form`, one pass per row (data only):

| Assertion | Today |
|---|---|
| K1 new name: no entity, no `:seon.program/definition-digest`; the failed form's evaluation has an error and no `:seon.cluster.eval/call-count` | passes |
| K1 over a stored name: entity digest and source equal the earlier definition; the dependent defined after the failure has an edge to it | passes |
| K3: entity for the bound name with the inner definition's source though the form errored | passes |
| K2: no entity; the defn's evaluation carries the not-admitted diagnostic; a later use returns the refusing-root error as its value | passes |
| the dependent form is admitted with calls limited to resolvable entities and its use returns the actual error value; no `:code-evidence`-style claim of success for it | passes |
| a red test depending on the failed definition has no green record (`{:pass 0 :error 1}`) | passes |
| after the callee is defined correctly in a LATER pass, the dependent's `:seon.fn/calls` contains the callee | **fails today** (probe 7) |

The first six rows pin today's correct behavior so the batched seam cannot regress it; the last is the open defect.

## Findings for root to file

- `seon.fn/a-dependent-keeps-no-edge-to-a-callee-defined-after-it` (new): `(defn bad1 [x] (nosuch-fn x))`, then `(defn dep1 [x] (bad1 x))`,
  then in a later pass a working `bad1`; and `(declare later) (defn early [] (later)) (defn later [] 1)`: `early` and `dep1` keep
  `calls [clojure.core/defn]`. Evidence: probe 7; the declare half is the open task above. Proof: the last table row.
- `seon.sci.eval/a-test-error-report-prints-the-whole-host-stack` (new): `(clojure.test/run-tests 'my.s5-03h)` in SCI mode with
  one erroring assertion answers `:out` of 10,673 estimated tokens (the full Java stack, Seon and Malli frames included);
  the reply's own finding is `:large-output`. Seon generates the bulk (`seon.sci.eval/agent-run-tests`, `eval.clj:302`); CLAUDE.md
  "never generates bulk of its own". Evidence: result `e9257fefd3468`.
- `seon.render.value/transacted-passes-a-basis-descriptor-to-stored-collection` (new): showing
  `(seon.db/pull (seon.db/db) '[*] [:seon.cluster.eval/id "af9da2066437"])` through the printer records the diagnostic
  "seon.db/stored-collection :malli.core/invalid-schema-at-call ... Don't know how to create ISeq from: clojure.lang.Keyword"
  at `render/value.clj:358` (producer `seon.render.value/transacted`); the offending call is `[{:db-name .. :t .. :datahike/commit-id ..}
  :seon.cluster.eval/read-evidence ()]`, a basis descriptor where `:seon.db/database-value` is expected. Evidence: result `e497d4c908865`.
- `seon.fn/contract-facts-row-names-no-schema-in-its-refusal` (new, minor): a contract naming an unregistered schema refuses as
  "derive: :malli.core/invalid-schema" and the defn's diagnostic never names `:my.s5-03h/nosuch`; the key is only in the
  chain (`fn.clj:3151`). Malli's humanized problems (`malli.error/humanize`) should be the prose.

## Design check

The design held against the real code. The behavior that matters (no bind event, nothing to certify) is the
dependency's documented contract, not a Seon rule, and every K shape is already isolated per form by `derive-step`'s
catch; the batched seam only has to keep those two properties (capture from events, catch per write). Awkward:
probe 7's missing edge is not caused by failure and is not fixed by batching, so the table keeps it as a separate
red row. No new registry, cache, atom or printer is needed. Probes added only SCI Vars on branch `s5-03h`; nothing
in default's JVM, no RESET NEEDED.
