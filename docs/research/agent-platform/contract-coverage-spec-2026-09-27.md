---
type: spec
status: part A landed; part B patches for root
created: 2026-09-27
lane: instrumentation-opus
head: 822260a68 + this slice
jvm: default pid 99936; JVM-mode probes plus branch instrumentation-opus-probe (retired)
---

# Contract coverage: clear refusals, traced producers, the three-part rule (2026-09-27)

Lane `instrumentation-opus`. Instrumentation arms one Malli wrapper per contracted Var (`seon.instrument/compiled-wrapper`,
`src/seon/instrument.clj`); Malli validates input, output and guard on every arity it wraps
(`reference-code/malli/src/malli/core.cljc:2229-2240` single arity, `:2300-2316` multi-arity dispatch, validators
compiled once at arm time `:2213`). Owner ruling (2026-09-27): no back-fill plan. Each bug fix adds the contract at its
producer; a new or changed function is specified where it enters the program.

## 1. Refusal text (landed with this doc)

Before (default, HEAD 4ff5053ee):

```
seon.problems/open-faults refused _database at []: expected must be an immutable Datahike database value, got nil.
Fix: must be an immutable Datahike database value Called from seon.call-preparation (call_preparation.clj:1033).
```

After (proven at default's JVM REPL with the edited forms loaded):

```
seon.problems/open-faults refused argument 0 `database` at []: expected :seon.db/database-value (must be an
immutable Datahike database value), got nil. Fix: Pass :seon.db/database-value as argument 0 `database`.
Called from user (NO_SOURCE_FILE:4).

seon.problems/open-faults refused the return value at []: expected a vector, got a keyword :not-a-valid-output.
Fix: Change seon.problems/open-faults to return a vector. seon.problems/open-faults (seon/problems.clj:137)
returned it. Called from user (NO_SOURCE_FILE:7).

tmp.probe/f refused argument count at []: expected a declared argument count (1, variadic), got an argument
count of 0. ...
```

- **Caller.** `caller-frame` skipped `seon.instrument` and Malli but not the prepared-call closure that
  `call-preparation/preparing` returns (`call_preparation.clj:1033`), which wraps every contract declaring a supplied
  default. Its class prefix is now derived from the Var. The caller is named `ns/fn (file:line)`. A frame in SCI's
  interpreter yields no JVM caller: SCI's own stack (`:seon.sci.eval/stacktrace`) already names it.
- **Argument.** `argument N `name``: the position, plus the arglist name without its `_` unused marker.
- **Expectation.** The registry name comes from `malli.util/get-in` (`reference-code/malli/src/malli/util.cljc:342`)
  along the problem's `:path`. Malli dereferences the name before it reports. The fix no longer repeats the
  predicate's message.
- **Output.** The subject is the function, not the caller. The message adds the function's definition location.
- **Output validation.** Every arity was probed on real contracts (`seon.problems/open-faults`, a three-arity
  `:function`), and every one refuses an invalid return. There is one arm path (`compiled-wrapper`, scope
  `#{:input :output :guard}`). Every arming path uses it: `apply!`, `wrap-interpreted` and `restore!`. No path
  validates inputs only. There are two deliberate bypasses: `*compiling-contract*`, while a refusal is being built,
  and 25 `mi/-f->original` calls on measured hot paths (21 in instrument.clj, 4 in call_preparation.clj).
- **Lookup ref.** A keyword-headed pair is called "a lookup-ref vector" only where a string id is expected.
  `[:=> [:cat :int]]` had been described that way.
- **Patch for root:** `tmp/instrumentation-opus/error-problem-parts-empty-path.patch` (error.clj belongs to
  error-evidence-opus). It drops ` at []` for an empty path and describes `:uuid` as "a UUID". Today the text reads
  "expected a value satisfying should be a uuid", from `seon.cluster.source/commit-database`. The tests that assert
  `at []` are `refusal_grammar_test.clj:62` and `instrument_test.clj:322`, `:583`, and they need their fragment
  updated with the patch.

Cost of arming, measured at default: `open-faults` 0.32 µs armed vs 0.03 µs unarmed; `seon.db/pull` 16.5 vs 15.0 µs.
The compiled validator is already cached per wrapper, so arming every function costs about 0.3–1.5 µs per call.

## 2. Traced bugs: producer and the contract that catches it at the source

| Fault / red | Producer of the bad value | Caught where | Contract at the source |
|---|---|---|---|
| `seon.error/recording refused declared-schema … got nil` (`seon.cluster.turn-test/a-refused-definition-stays-in-its-agents-defs`) | The test's own `with-redefs` of `db/transact!` (`test/seon/cluster/turn_test.clj:1391-1409`) returns a hand-built refusal without the `:seon.error/declared-schema` metadata real writer refusals carry (`db.clj:215…1953`); `turn.clj:4111` reads it from `meta`. | `recording`'s positional arity (correct, first contracted boundary). | None possible: `with-redefs` replaces the armed root, and Malli never validates metadata. Fix the test to take a real writer refusal (AGENTS forbids `with-redefs`). The design weakness is recorded for the error owner: identity carried only in metadata is invisible to every contract. The three `turn.clj` reads (3981, 4111, 4455) go with the one-path rewrite. |
| `seon.test/reach-digest refused test-symbol … got a string. Called from seon.call-preparation` (`seon.agent-call-edges-test`) | The test builds names with `str` (`test/seon/agent_call_edges_test.clj:20-23`); `:seon.fn/sym` values are symbols. | `reach-digest` input (correct). The caller named was the preparation closure (fixed in §1). | Already there. The test must pass `(symbol ns name)`. |
| `seon.cluster.source/commit-database refused commit-id … got nil` (docstring examples) | `seon.cluster.registry/branch-commit-id` (`registry.clj:133`) answers `[:maybe :seon.source/commit-id]`: nil for "no such branch". `prepare-merge!` (`source.clj:~868`) and `accept-merge!` pass it on. | `commit-database`, one hop later, without the branch's name. | Output `[:or :seon.source/commit-id :seon.cluster.registry/branch-missing-error]`: a declared error naming store and branch. Nil becomes impossible and each caller branches. Owners: registry.clj, source.clj. |
| `seon.context/capture-tx refused request at [… :seon.cluster.prompt/text]` (open fault) | `seon.cluster.prompt/prompt` returns one of six declared alternatives, including five error maps. The turn loop (`turn.clj:~4852`) recognizes only two error keys, so the rest flow into capture as a rendered context. | `capture-tx` input (correct, one hop). | The producer's contract is exact. The gap is hand discrimination in turn.clj. Discriminate with the declared union (`m/validate :seon.cluster.prompt/rendered-context`) in the one-path rewrite. |
| Incomplete agent contract `[:=> [:cat :int]]` (branch probe) | `seon.schema/canonical-definition` (`schema.clj:633`) returned an unparseable form instead of a declared refusal. | Its own output contract (`:seon.schema/definition`), which the new text names as returning it. | Already caught at the source. canonical-definition should return a declared refusal naming the missing output schema (schema.clj owner). |
| `seon.render/unknown` "did not return an observation" (open fault, 3×) | Untraceable. The stored fact keeps no `:seon.render.unknown/producer`. `:seon.error/data-edn` holds floor-printer node maps (`#:seon.print{:face …}`, 19,575 bytes, capped). | — | Recorded for the error owner: the stored fact must keep the producer's name as data. |

A contract at the producer would have named the source in one of these six: `branch-commit-id`'s `:maybe`. Three were
caught correctly at the first contracted consumer, and the clearer caller text fixes them. Two came from tests that
fabricate values, which no contract can see.

## 3. Coverage evidence (production namespaces, default JVM, 2026-09-27)

These are the 105 namespaces under `src/`, all loaded, with 3,527 function Vars. 2,221 have a contract (63%) and 2,203
are armed. The other 18 contracted Vars were redefined unarmed at the REPL by lanes (error.clj, error/refusal.clj and
this lane's own probes); a restart re-arms them. 1,306 have no contract: 1,291 private and 15 public (record
constructors and seven `seon.cluster` lifecycle functions). The largest gaps are render.web 99, db 75, turn 71,
render.transcript 68, fn 63, sci.eval 56, schema 48, sci.reader 41, test.runner 41 and render 34. Loose contracts:
133 name `:any`, and 123 of those carry the declared polymorphic-boundary exemption. 250 name `:maybe`, and 131 are
single-arity `[:maybe …]` outputs, the ambiguous-nil class of trace 3. 247 name `:seon.schema/value`.

## 4. The three-part rule (owner 2026-09-27; AGENTS "Contract every admitted function", revised in place)

**What exists today.** SCI entry refuses an uncontracted `defn`/`defn-` (`sci/eval.clj:1005-1012`,
`missing-contract` :2460). This was proven on branch `instrumentation-opus-probe`: public and private are both refused
(677 ms and 155 ms), a contracted function is admitted, and an unchanged uncontracted function (for example
`seon.problems/restore-error-ref`) still loads and runs. `assert-complete-schema!` (`schema/internal.cljc:329`) judges
the positions of a contract that exists. It cannot fire for a `defn` with none, because there is nothing to walk. It
treats `:any` as advisory. For files, nothing refuses a missing contract. The edit hook's contract check
(`seon.contracts-compile-test/check`, run by `bin/seon-hook` `validate-contract-edit`) compiles only the contracts
that exist, and that is how 1,306 gaps accumulated.

1. **Edit hook (outside agents editing files): refuse.** Patch
   `tmp/instrumentation-opus/edit-hook-contract-completeness.patch` (bin/seon-hook and
   test/seon/contracts_compile_test.clj are free in the ledger; root applies). It extends the existing seam, `check`,
   with `::baselines`. The hook passes the file as it stands before the edit. Every `defn`/`defn-` that the edit adds,
   or whose read form differs from the baseline, is refused with an `:incomplete-contract` finding in two cases: it
   has no `:malli/schema`, or it has an `:any` outside a declared exemption. Unchanged functions are never judged,
   and the corpus deftest (no baselines) judges compilation only. The refusal teaches the fix:

   ```
   This edit adds seon.problems/sketch (defn-) without a :malli/schema. Declare each data shape it takes and returns
   as a registered schema first (resources/seon/schemas/<namespace>.edn), then name those shapes:
   {:malli/schema [:=> [:cat :my.ns/request] :my.ns/result]}. A genuinely polymorphic boundary says so: [:any
   {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason "..."}].
   ```

   Proven at default's REPL in a scratch namespace, `tmp.contracts-compile-probe`, built from the patched file. The
   new regression passed 8/8 in 72 ms. The working-tree instrument.clj checked against HEAD gave no findings, in
   448 ms including a projection rebuild; the hook passes the live projection. Cost is about 40 lines and adds no
   JVM work, since it reads the forms the check already reads.
2. **REPL (SCI evaluation): admit with a progressive warning, never refuse.** This change belongs to the one-path
   rewrite, which is replacing `sci/eval.clj`. Proposed smallest form:
   - `admit-program-change!`'s `(refuse! (missing-contract missing))` becomes a returned warning.
   - The warning is derived from facts on each evaluation, with no counters. Its data is the agent's uncontracted
     functions: the branch's changed identities (`program/changed-identities` base→branch) that have a `:seon.fn/sym`
     and no `:seon.fn/spec`, excluding macros and host-bound functions.
   - When that set is exactly the function just defined, the warning is one note: "`f` has no :malli/schema; specify
     it before proposing a merge."
   - When it holds more, or holds any row asserted before the current turn (the `:db/txInstant` of its assertion),
     the warning lists every name, and the reply leads with it.
   - It enters the reply through the existing advisory seam (`turn.clj` `append-output`, used by
     `seon.test.accretion/advisories`). `repl.clj:143`'s "was not installed" note is then deleted.
3. **Merge: refuse.** Patch `tmp/instrumentation-opus/merge-refuses-uncontracted.patch` targets `source.clj`, which
   is held by running merge lanes, so root routes it. `accept-merge!` refuses when the delta carries a `:seon.fn/sym`
   whose entity on the tested branch has no `:seon.fn/spec` and is neither a macro nor host-bound. That is the same
   predicate SCI entry uses. The refusal names the functions and says how to fix them. The predicate was proven at
   default: it picked `restore-error-ref` out of a delta that also held `open-faults` and a namespace row, in
   0.4 ms. Unchanged head functions are never in the delta.

AGENTS "Contract every admitted function" now states this rule. "Keep entry policy separate from merge" still says
entry defaults to `:gate`. For a missing contract at the REPL, the owner's ruling overrides that sentence, and root
should reconcile the two.

## Findings

- The MCP JVM reply projection failed with "No matching clause: :seon.print/projected" at
  `seon.sci.admit/semantic-leaf` (`admit.clj:570`), returning a vector of symbols.
- The stored fault `:seon.error/frame` of the capture-tx fault is the instrumentation wrapper
  (`seon.instrument$compiled_wrapper … instrument.clj:664`). The error owner's frame choice needs the same
  machinery skip as §1.
- SCI refusal of an uncontracted defn took 677 ms on first use and 155 ms warm.
