# S5-03d: ns and alias changes (admission design proof)

Lane s5-03d. Notes only. Probes ran on the lane's own SCI agent branch (agent `s5-03d`, namespaces `probe.alias*`), or
as read-only JVM calls (`mode jvm`) of existing pure functions (`seon.fn/runtime-analysis-batch`,
`seon.fn.analyzer/analyze`, `seon.program/definition-digest`, `sci.core/init`) with a database value read from
default. HEAD `cd8a481c2`; every line number below is HEAD's (the working tree has other lanes' edits). No production or test file changed. Specifies
`seon.cluster.turn-test/a-pass-analyzes-aliases-at-their-observed-position`.

## Owners read

- `src/seon/batch.clj:513` `apply-pending`, `:543` `derive-step`, `:594` `form-step`: the form's `ns-before` is read
  before it, `derive-form` runs right after it (before the next form), and the change is validated onto the working
  value only when a later form follows.
- `src/seon/turn/batch.clj:57` `namespace-entity` (SCI's resolver bindings now), `:226` `analyze-form` (builds the
  request with `namespace-entity ctx (:seon.batch/ns-before entry)`), `:303` `derive-form`, `:480` `namespace-facts`
  (the namespace row "as SCI holds it now", read at `form-facts` time, so again at settlement for the whole pass),
  `:568` `program-facts`.
- `src/seon/fn.clj:814` `runtime-require-specs`, `:944` `runtime-analysis-batch` (one `ns` form per run of consecutive
  requests in one namespace, using the first request's row).
- `src/seon/db.clj:5211` `definition-digest-of`, `:5227` `namespace-digests-tx`, `:5648` its trigger;
  `src/seon/program.cljc:452` `used-resolver-context`, `:469` `definition-digest`.
- `src/seon/sci/observe.clj:23` `observe` (write observer, `changed-namespaces` after the evaluation);
  `src/seon/sci/arm.clj:71` `write-event`, `:86` `changed-namespaces`.
- SCI `ce0aa53b9` `src/sci/impl/namespaces.cljc:478` `sci-alias`; Clojure `3bc2b3e91`
  `src/jvm/clojure/lang/Namespace.java:250-258`; clj-kondo `57252e079`.

## Probes and observed data

Each `eval_clj` call is one pass. A pull inside the same pass reads the committed value, not the pass's pending facts,
so the stored data below was read in a following call.

1. **Retarget between two uses, one pass** (`probe.alias`: `(require '[clojure.string :as x])`, `(defn f1 .. (x/upper-case
   "a"))`, `(alias 'x 'clojure.set)`, `(defn f2 .. (x/union ..))`): `f2` stored `calls [defn clojure.set/union]`; `f1`
   has NO entity. Its evaluation carries the diagnostic `{:seon.db/transaction-refused true :seon.error/member
   :seon.program/definition-digest :seon.error/message "Not admitted to the program: ... The stored definition digest of
   [:seon.fn/sym probe.alias/f1] is not the one its definition produces."}`. The Var `f1` works in SCI (`(f1)` -> "A").
   The namespace row stored at the end is `aliases #{x -> clojure.set}` only.
2. **Additive alias, one pass** (`probe.alias2`: `s` then a def, then a new alias `t` then a def): both stored,
   `f1 calls [defn clojure.string/upper-case]`, `f2 calls [defn clojure.set/union]`. Changing the namespace row does
   not refuse a definition whose used context did not change.
3. **Keyword alias** (`probe.alias5`: `(defn k1 .. ::x/k)` with `x -> clojure.string`, then `x -> clojure.set`,
   `(defn k2 .. ::x/k)`): `k2 keywords [:clojure.set/k]`, `k1` refused like probe 1. `k3` in a following form after
   switching namespace and back: `[:clojure.set/k]`, and `probe.alias5b/g3` (a different namespace with its own
   `x -> clojure.set`) `calls [defn clojure.set/union]`.
4. **Retarget inside the defining form** (`probe.alias5`: `(do (defn h [] (x/upper-case "a")) (alias 'x
   'clojure.set))`): the pass answered `nil` (the `do`'s value) and `h` was ADMITTED with `calls [defn
   clojure.set/upper-case]`. That edge is wrong: SCI resolved `x/upper-case` to `clojure.string/upper-case` when it
   evaluated the `defn`, and `clojure.set/upper-case` does not exist. `derive-form` analyzes after the form with the
   row after the form.
5. **Retarget by refer** (`probe.alias7`: `:refer [upper-case]` from `clojure.string`, def `r1`, then `(require
   '[clojure.set :refer [union] :rename {union upper-case}])`, def `r2`): `r1` refused with the same digest diagnostic;
   `r2` stored `calls [defn]` with no edge to `clojure.set/union`. The ns form `runtime-require-specs`
   (`fn.clj:814`) emits `:refer [target-name]` only and drops the local name, so a renamed refer is invisible to
   analysis.
6. **Retarget across passes** (`probe.alias4`: pass 1 defines `f1` (`x -> clojure.string`) and `k1`; pass 2 `(alias 'x
   'clojure.set)`): the namespace row now holds `x -> clojure.set`, `f1` still holds `calls [.. clojure.string/upper-case]`
   and its old digest `a5e1d27b...`. `(db/definition-digest-of projection f1 stored-ns-map)` is `701a836c...`: the stored
   digest is not the digest of the stored definition under the stored namespace. `namespace-digests-tx` re-derives
   dependants only when the written namespace map carries `:seon.program/definition-digest` (`db.clj:5648`), which a
   batch's namespace row does not.
7. **SCI differs from Clojure on retarget.** `(require '[clojure.set :as x])` after `x -> clojure.string` returns nil and
   retargets; so does `(alias 'x 'clojure.walk)` (`sci-alias`, `namespaces.cljc:478-486`, a bare `assoc-in`). Clojure
   refuses: `Namespace.java:257-258` throws "Alias x already exists in namespace .., aliasing .." for a different
   target. A refer replacement is a warning, not an error, in Clojure (`Namespace.java:121`), so a retargeted refer is
   legal on the JVM.
8. **Falsifier of the end-of-pass seam** (JVM, `runtime-analysis-batch` on default's database value): two requests in
   namespace `probe.a`, row 1 `x -> clojure.string`, row 2 `x -> clojure.set`, forms `(x/upper-case "a")` and
   `(x/union #{1} #{2})`. Batched in one call: usages `[clojure.string upper-case]` and `[clojure.string union]` (the
   second is WRONG, one `ns` form carries the first row for the whole run; `partition-by :namespace-name`,
   `fn.clj:1013`). The same two requests analyzed one by one: `[clojure.string upper-case]`, `[clojure.set union]`.
9. **The kondo seam exists.** One source of three `(ns probe.a (:require [.. :as x]))` forms with `x ->
   string`, `-> set`, `-> string` between defs: usages `f1 clojure.string/upper-case`, `f2 clojure.set/union`, `f3
   clojure.string/lower-case`; keywords `:clojure.string/k`, `:clojure.set/k`. A repeated `ns` form for the same
   namespace re-resolves aliases per position, so the batched call only needs a new `ns` block where the observed
   row changes.
10. **Cost.** Capturing the resolver state at a write: raw env entry `get-in @env [:namespaces ns]` 0.26 us, 64 B;
    `namespace-entity` 2.3 us, 3.1 KB (1000 calls, warm). Analysis of definition-bearing forms: one form per call
    9.3 ms / 5.2 MB; ten separate 95 ms / 52 MB; ten batched 10 ms / 6.9 MB; 200 separate 2,204 ms / 1,045 MB;
    200 batched 61 ms / 135 MB. The batched call is superlinear in forms: 200 -> 68 ms / 128 MB, 800 -> 821 ms /
    1,627 MB, 1600 -> 2,511 ms / 6,284 MB (the allocation quadruples per doubling).

## The invariant the cases expose

The writer digests a declaration against the namespace row the SAME transaction leaves stored
(`definition-digest-of`: "the namespace row the same transaction leaves stored"), and a pass settles as one
transaction, so a namespace has one stored row per pass: the row after the last form. A definition whose USED
resolver context (`used-resolver-context`: aliases, requires and refers of the namespaces its calls, references and
keywords name) is the same under that final row and under the row it was evaluated under is reproducible from the
stored program; one whose used alias or refer was retargeted later in the pass is not. Reloading the stored namespace
text and the stored `f1` source would resolve `x/upper-case` to `clojure.set`. Admitting it would store a program that
cannot reproduce the Var. So the writer's refusal in probe 1 is right; what is wrong around it is:

- the analysis row is the row after the form, not the row the definition ran under (probe 4: `h` is admitted with a
  false edge);
- a batched analysis uses one row per namespace run (probe 8);
- the refusal message ("the stored definition digest is not the one its definition produces") names no cause (probe 1);
- the cross-pass retarget leaves stored digests stale with no refusal and no re-derivation (probe 6).

## Design: the smallest seam for this case

**No early admission is needed. A definition's observation carries the resolver state it was written under.**

1. **Observation (the write).** `seon.sci.observe/observe`'s write observer, for a `:bind` of kind `:root` with a
   `:ns` meta (the same writes `write-entity` joins), adds `:seon.batch/resolver` to the write event: the namespace
   row `seon.turn.batch/namespace-entity` returns for the namespace the form evaluates in at that moment (the SCI
   current namespace; the same name `:seon.batch/ns-before` holds unless the form switched namespaces, which is S5-03e).
   Capture is an immutable value read at the write (probe 10: 2.3 us / 3.1 KB, or the raw env entry at 0.26 us / 64 B
   projected later). Absent when the write has no source (as `:seon.batch/origin` is absent).
2. **Analysis input.** `derive-form` passes `analyze-form` the write's captured row, not `namespace-entity ctx
   ns-before` after the form. `runtime-analysis-batch` splits its runs by `(juxt :namespace-name
   :seon.fn/namespace-row)` instead of `:namespace-name` (one expression, `fn.clj:1013`), so rows that do not change
   share one `ns` block (zero extra cost for a pass without alias changes) and a change emits one more `ns` block
   (probe 9 shows kondo re-resolves per position). This is a precondition of S5-04's single call, not an extra
   mechanism.
3. **Digest.** Computed from the captured row through the existing `definition-digest-of`, as `analyzed-entities`
   already does for `ns-row`. The writer stays the authority: at settlement it compares the stored digest with the
   digest under the stored (final) namespace row, so a definition whose used context was retargeted later is refused
   and the later one is admitted (probe 1 behavior, now for the right reason and for the `h` case of probe 4).
4. **Join.** Unchanged: exact write-time observations joined by span (`join-write`, `entry-coords`); the analysis'
   `ns` blocks shift rows, and `analysis-coords` already carries each request's first row.
5. **Refusal cause (BEHAVIOR CHANGE, small).** When `derive-form` holds the captured row and the namespace's row after
   the form (or the pass's final row at settlement) differ in a part `used-resolver-context` keeps for that definition,
   the definition's `:seon.batch/private` carries `:seon.batch/reason :resolver-retargeted` and the names of the
   retargeted alias or refer, instead of only the writer's digest diagnostic. Option, not required for the proof.

Contracts the packet would write (one line each, registry names): `:seon.batch/write` gains optional
`:seon.batch/resolver [:map-of ..]` (the `namespace-entity` shape); `analyze-form` takes the row;
`runtime-analysis-batch` keeps `[database requests] -> map` (no signature change); nothing weakened.

### Alternatives

| Option | What | Price | Verdict |
|---|---|---|---|
| A. Observed row at the write (above) | capture a value at the bind, one `ns` block per distinct row, writer unchanged | ~10 lines observe/arm + schema key, ~6 lines `derive-form`/`analyze-form`, 1 line `fn.clj`, 1 regression; under 150 | Recommended. Matches the plan ("observed namespace/aliases"), keeps one transaction, no new store fact |
| B. Refuse alias retarget at the producer | make `alias` and `require :as` refuse a different target as Clojure does (`Namespace.java:257`), `ns-unalias!` is the guarded path | a seon hook on `alias` (the existing `native-call-refusal` already intercepts `ns-unalias`) plus SCI `require :as`, which has no callee to intercept (SCI fork patch) | Removes alias position-dependence but not refer retarget (legal in Clojure), `ns-unalias!` then `alias`, or ns re-evaluation. Do as a separate cut after the owner decides; does not replace A |
| C. Store the observed context on the declaration | digest from an entity-held resolver context, not the stored ns row | new attribute, writer rule change, write-back cannot reproduce the Var from text anyway | Not recommended |
| D. Early admission at a retarget | settle a partial transaction when a used alias changes | breaks "settlement is ONE synchronous transaction" for every pass with an alias change | Not recommended |

## Disagreement with the plan's assertion

The table row (`reconciled-plan.md`/`spec-split-sol-split.md:266`) asks for "exact target symbols/keywords and recorded
namespaces match each position" for two uses across a changed alias. With one stored namespace row per pass the first
use cannot be stored (invariant above); only its LIVE value and its refusal are observable. The proof therefore asserts:

- the later use is stored with its exact calls and keywords (`clojure.set/union`, `:clojure.set/k`);
- the earlier use has no entity, its evaluation `:diagnostics` name `:seon.program/definition-digest` as the member
  (and, with step 5, `:resolver-retargeted`), and its Var still returns the value it computed;
- the same-form case (`(do (defn h ..) (alias ..))`) is refused, not admitted with the edge of the final alias;
- an additive alias in the same pass keeps both uses with their exact targets;
- each evaluation's `:seon.cluster.eval/ns` (`:seon.batch/ns-before`) matches its position (`probe.alias5b` for the
  switched run).

If the owner wants both uses stored, that is option C and a new ruling; I recommend against it.

## Proposed regression (specified, not authored)

`seon.cluster.turn-test/a-pass-analyzes-aliases-at-their-observed-position`, one pass, a table of cases of one
behavior, asserting data only:

| form sequence | stored | not stored |
|---|---|---|
| `x -> string`, `u1` (call and `::x/k`), `x -> set`, `u2` (call and `::x/k`) | `u2 calls clojure.set/union, keywords :clojure.set/k` | `u1`, diagnostic member `:seon.program/definition-digest` |
| `(do (defn h .. (x/upper-case ..)) (alias 'x 'clojure.set))` | none | `h` |
| `x -> string`, `u1`, `(require '[clojure.walk :as w])`, `u3` | `u1 calls clojure.string/upper-case`, `u3 calls clojure.walk/..` | none |

Companion in `test/seon/fn_test.clj` for S5-04: two same-namespace requests with different rows analyzed in one
`runtime-analysis-batch` give `[clojure.string upper-case]` and `[clojure.set union]` (probe 8 inverted). Existing
comparison cases: `seon.cluster.turn-test/contracted-redefinition-exactly-replaces-the-entity`,
`seon.fn-test/an-alias-an-earlier-form-requires-resolves-in-later-forms-of-the-batch`.

## Findings (not fixed here; each needs a durable task, not yet filed)

1. `(do (defn h ..) (alias 'x ..))` stores a false call edge to a Var that does not exist (probe 4). Fixed by step 1-2.
2. SCI `alias`/`require :as` retarget silently where Clojure throws (probe 7). Option B above. Related open issue
   `docs/seon/issues/reload-refuses-an-alias-retargeted-by-the-new-source.md`.
3. A cross-pass alias or refer retarget leaves dependants' stored digests stale: no refusal, no re-derivation (probe 6).
   `namespace-digests-tx` fires only for a namespace map carrying `:seon.program/definition-digest`.
   Owner: `seon.db/prepared-write` (`db.clj:5648`). Falsifier: probe 6 then compare `definition-digest-of` with the
   stored digest.
4. `runtime-require-specs` drops a renamed refer's local name, so analysis loses the edge (probe 5, `r2`).
   Owner `fn.clj:814`; S1-03 holds `fn.clj`.
5. `runtime-analysis-batch` is quadratic in forms (probe 10: 1600 forms 2.5 s / 6.3 GB). The per-form
   `(count (filter #{\newline} source))` in its reducer (`fn.clj:1005`) re-counts the accumulated source for every form.
   An incremental line count is the fix (S5-04's call, same file as the partition change).
6. The digest refusal message names no cause (probes 1, 5, 6). Step 5.

Owner: root files these (this lane could only record them on its own branch, which is not shared).

## Design check

The design held against the real code with one correction to my own starting hypothesis: I expected the cases to need
an early-admission boundary at an alias change. They do not, because the writer already refuses the definition that
cannot be reproduced, and the per-form derive plus an observed row gives the later definition its exact position.
The awkward parts are that the plan's assertion (both uses stored) is not achievable under the invariant, and that the
fix touches `fn.clj` (held by `s1-03`) for the one-line partition and the counter, so the packet follows S1-03.
Everything above was measured on HEAD `cd8a481c2`; no claim about a future API is a pass.
