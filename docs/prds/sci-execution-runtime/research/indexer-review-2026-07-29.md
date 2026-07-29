---
type: research
status: active
tags: [research, audit, sci, reader, program-graph]
---

# Function-indexer adversarial review, 2026-07-29

## Verdict

Commit `7340e2635` is a material improvement, but its two strongest safety
claims are false.

The current tree really does produce 1,242 complete function rows, including
808 complete private rows, and the full gate really is 568 tests / 2,780
assertions / 0 failures / 0 errors. Removing the build-time contract gate was
the correct simplification.

The reader replacement is still name-based hand matching. It silently
misattributes qualified lookalikes, falsely treats other qualified lookalikes
as declarations, and scans inert quoted data as if it executes. Separately, a
real declaration inside an executable top-level `do` still disappears without
a row or refusal. The recurring coverage test shares the production reader's
event stream and collapses occurrences to sets, so it can agree with both
defects.

I would not stake the corpus on "everything accounted for" or "no hand lists"
until [[resolve-namespace-changes-by-executable-operator-identity]],
[[account-for-declarations-inside-executable-top-level-forms]], and
[[make-function-coverage-independent-and-cardinality-preserving]] are closed.

| Claim | Verdict | Short reason |
|---|---|---|
| 1. No hand lists in the new paths | **FALSE** | The reader hard-codes `#{"ns" "in-ns"}` and repeatedly compares local names. |
| 2. Everything is accounted for or refused loudly | **FALSE** | Direct unplaceable declarations refuse correctly; qualified lookalikes silently misattribute, and a declaration under top-level `do` silently vanishes. |
| 3. The coverage invariant is honest and independent | **FALSE** | Current equality is independently true, but the recurring test shares reader events/namespaces and set-collapses multiplicity. |
| 4. The 808 private functions are real rows | **CONFIRMED** | All 808 are complete inventory rows. No `:seon.fn/calls` schema or edges exist yet. |
| 5. Build and eval boundaries remain distinct | **CONFIRMED** | Build admits all direct declarations; eval publishes only a valid contracted function. |
| 6. Both filed blockers are accurately scoped | **FALSE** | Both defects reproduce, but the stale-JVM issue omitted the same corrupting path through `bin/seon start NEW-CLUSTER`. |
| 7. Full gate is 568 / 2,780 / 0 | **CONFIRMED** | Independent full run exited zero with 568 tests, 2,780 assertions, 0 failures, and 0 errors. |

The frozen audit and gate inspected HEAD `6e5b0a925`; the five changed
source/test files were still byte-owned by target commit `7340e2635`, and the
gate's before/after input digest was identical. After that proof completed,
another lane began edits in `src/seon/cluster/run.cljc`, `src/seon/fn.clj`,
`src/seon/sci/eval.clj`, and a new `src/seon/program.cljc`. Those later edits
were excluded from this target-commit verdict and were not used as a reason to
rerun or reinterpret its already-frozen gate. This audit edited no source or
test file.

## Dependency ledger and method

The relevant dependency and first-party boundaries were read before the
probes:

- SCI checkout `8fac6e88f32d53a5fd82ebe80640881e317b84fd`,
  especially `reference-code/sci/src/sci/core.cljc:352-391` and
  `reference-code/sci/src/sci/impl/parser.cljc:44-51,142-190`;
- Edamame `1.6.42`, selected by `reference-code/sci/deps.edn`;
- Datahike checkout `19f5cdd950dc3c5ad2c8777a176d2ec4cb18c0bb`;
- `src/seon/sci/reader.cljc` and `src/seon/fn.clj`, both read end to end;
- the eval boundary in `src/seon/sci/eval.clj:321-555`;
- schema activation in `src/seon/schema.cljc:1702-1740`;
- transaction/application owners in `src/seon/cluster/run.cljc:558-612` and
  `src/seon/cluster.clj:359-414,511-539`; and
- operator selection in
  `script/seon/fresh_operator.clj:1036-1067,1174-1227`.

SCI's parser explicitly passes `:read-cond :allow` and caller-selected
features. The independent source census used `clojure.tools.reader`, not
`seon.sci.reader`, and selected the JVM branch of `.cljc` reader conditionals.
A fresh in-memory production database supplied the database comparison. The
stale-JVM probe used its own operator root and PID 41673, which was stopped
cleanly. The owner's PID 8515 / port 7994 was never primed, reset, stopped, or
used for mutation.

## Claim 1 — no hand-maintained magic lists

**Verdict: FALSE.**

The old allowlist is gone, but the replacement is not derived from executable
operator identity:

- `src/seon/sci/reader.cljc:306-322` says the rule is "never a list" and then
  tests the literal set `#{"ns" "in-ns"}`.
- It calls `name` on the operator, discarding qualification.
- `next-reading-context` repeats local-name checks at lines 324-343.
- `declaration-facts` repeats the same rule for `ns` at lines 289-304.
- Function and test recognition use the same local-name shape at lines
  198-205 and 247-255.
- `namespace-changing-mention?` recursively walks every collection, including
  quoted data and function bodies. It does not derive whether the occurrence
  is executable.

Independent falsifiers:

```clojure
(ns audit.a)
(other/in-ns 'audit.b)
(defn f [] :ok)
```

produced the complete row `audit.b/f`. A real evaluated
`other/in-ns` function left `*ns*` unchanged, proving the attribution false.
No refusal fired.

```clojure
(ns audit.a)
'(in-ns 'audit.b)
(defn f [] :ok)
```

cleared attribution and refused `f`, although quote executes nothing. An
`in-ns` occurrence inside a function body has the same false effect.

`(foo/ns audit.b)` emitted a phantom namespace, `(foo/defn ghost [] 1)`
emitted a false function fact, and `(foo/deftest ghost)` emitted a false test
fact. This is one name-resolution root cause, filed as
[[resolve-namespace-changes-by-executable-operator-identity]].

## Claim 2 — everything accounted for

**Verdict: FALSE.**

The new loud-refusal rail works for the declaration shape it can already see.
This source:

```clojure
(ns audit.unplaceable)
(do (in-ns 'other))
(defn f [n] n)
```

raised `:seon.fn/index-refused` with:

```clojure
{:seon.fn/file "/absolute/path/unplaceable.clj"
 :seon.fn/unadmitted
 [{:seon.fn/line 3
   :seon.fn/source "(defn f [n] n)"
   :seon.fn/reason :seon.fn/namespace-unproven}]}
```

File, line, source, and reason are all present. That narrow claim is solid.

Two silent paths remain:

1. The qualified-lookalike probe above produced a complete row under the wrong
   namespace. A complete but false row bypasses `unadmitted-functions`.
2. A valid file containing
   `(ns audit.nested) (do (defn f [n] n))` loads and resolves
   `#'audit.nested/f`, but `seon.fn/rows` returned only the namespace row and no
   error.

The second path is the named recurring failure class. `declaration-facts`
checks only the outer top-level form, while `unadmitted-functions` treats the
same reader's `:seon.fn/arglists` lift as its complete declaration census. The
missing declaration and the sentinel disappear together. It is filed as
[[account-for-declarations-inside-executable-top-level-forms]].

The current tree contains no executable nested `defn`/`defn-`, so this defect
does not alter the independently verified 1,242 current declarations.

## Claim 3 — honest coverage invariant

**Verdict: FALSE as a recurring invariant; current coverage is independently
confirmed.**

The test is independent of the original lifted-symbol failure in one useful
way: `declared-functions` extracts the name from
`:seon.sci.reader/form`, not `:seon.fn/sym`. It therefore would have disagreed
when a direct top-level `defn` event existed but attribution and the lifted
symbol were absent. That is a real regression for the original `set!` defect.

It is not independent of the reader:

- `test/seon/fn_test.clj:63-67` obtains expectation forms from the same
  production `seon.sci.reader/read` event stream.
- Lines 69-84 repeat name-only declaration recognition and build a set.
- Lines 94-120 group all rows globally by lifted namespace into sets.
- The assertion is a set-subset check, not one row per source occurrence.

A reader-skipped event is absent on both sides. Qualified `foo/defn` is falsely
counted and indexed on both sides. A nested executable `do` declaration is
invisible on both sides. Duplicate same-symbol declarations collapse. A row
from another file in the same namespace can satisfy the file under test.

The current tree was separately scanned with `clojure.tools.reader`:

```clojure
{:files 113
 :declarations 1242
 :unique-syms 1242
 :private 808
 :duplicate-syms {}}
```

The fresh production database comparison returned:

```clojure
{:functions 1242
 :unique-syms 1242
 :namespaces 105
 :private 808
 :private-with-spec 0
 :private-incomplete 0}
```

`MISSING`, `EXTRA`, `FILE_MISMATCHES`, and `NS_MISMATCHES` were all empty.
Thus the reported 1,242 / 105 / 808 result is genuine for today's source, but
the test's blanket statement that it cannot share the reader's bug is false.
The repair is filed as
[[make-function-coverage-independent-and-cardinality-preserving]].

### Attribution edges

The following were exercised independently:

| Edge | Observed result |
|---|---|
| `set!` | Retained the last explicit namespace. |
| top-level `(load! {})` | Retained the last explicit namespace. |
| `register-core-predicate!` | Retained the last explicit namespace. |
| direct `(in-ns 'other)` | Changed attribution to `other`. |
| malformed `ns` | Removed attribution; following direct declaration refused loudly. |
| `(do (in-ns 'other))` | Removed attribution; following direct declaration refused loudly. |
| `.cljc` reader conditional | Selected and attributed the `:clj` branch under the indexer's features. |
| qualified `other/in-ns` | **Wrongly** changed attribution; silent false row. |
| quoted `in-ns` | **Wrongly** removed attribution; false refusal. |

## Claim 4 — private functions are real rows

**Verdict: CONFIRMED for inventory; there are no call edges yet.**

The independent source census and fresh database both found exactly 808
private declarations. Every one of the 808 database rows had:

- a resolvable `:seon.fn/ns` reference;
- a string `:seon.fn/arglists`;
- `:seon.fn/source`;
- `:seon.fn/private? true`; and
- no `:seon.fn/spec`.

No row was incomplete. Removing the `:seon.fn/spec` admission condition in
`src/seon/fn.clj:21-40` is a clean strengthening of the one existing row
mechanism.

`:seon.fn/calls` does not exist in `resources/seon/schema/program.edn`, was not
installed in the fresh database, and has no datoms. A private helper therefore
cannot yet participate in call or reachability edges. The 808 rows currently
buy a complete durable declaration inventory, namespace/source/arglists/privacy
metadata, optional explicit workload metadata, generic inspection, and the
input from which later graph analysis can be derived.

The target commit's present-tense call-reachability comment at
`7340e2635:src/seon/fn.clj:28-36` was a lying implementation comment. It was
filed as [[function-indexer-claims-unbuilt-call-graph-reachability]] and then
resolved by concurrent commit `52423e362`, which now calls the absent graph
**future** input.

## Claim 5 — build and eval boundaries remain distinct

**Verdict: CONFIRMED.**

`seon.fn/durable-row` now admits every direct function declaration carrying
`:seon.fn/sym`, whether or not `:seon.fn/spec` exists.

The public eval path was exercised through `seon.sci.eval/evaluate`:

| Agent-authored form | Eval result |
|---|---|
| contracted `defn` | `:seon.sci.eval/program-row` present for the function |
| uncontracted `defn` | admitted runtime Var reference, no program row |
| bare `(+ 1 2)` | value `3`, no program row |

That matches `src/seon/sci/eval.clj:321-439`: eval-time durable publication
still requires a valid Malli contract. The selective-admission ruling was not
blurred.

Three mandatory skills nevertheless still teach contracted-only BUILD
indexing. That separate high-blast-radius documentation defect is filed as
[[build-indexing-skills-still-require-function-contracts]].

## Claim 6 — filed blockers

### Eval-time schema/test recurring proof

**Verdict: CONFIRMED.**

The issue
[[eval-time-schema-and-test-rows-have-no-recurring-proof]] accurately describes
the process-global registry collapse.

Using the real turn/eval schema activation owners against a fresh fixture
database:

```clojure
{:before-schema-keys 543
 :before-agent-id? true
 :before-run-value? true
 :after-schema-keys 1
 :after-agent-id? false
 :after-run-value? false}
```

Activation itself returned no error. Subsequent unrelated validations failed
with invalid schemas, including `:my.run/value` and
`:seon.cluster.agent/id`. A second cluster in the same JVM observed the same
missing registry entry.

The source agrees: `activate-program-schemas!` derives a projection only from
one database's rows, then `schema/activate-projection!` swaps process-global
`!schema-state`. A fixture holding one agent-authored schema row therefore
replaces 543 active schemas with one. The exact previously reported count of
four unrelated test failures was not recreated because the attempted test is
not committed, but the claimed mechanism and cross-cluster violation were
reproduced directly.

The issue's adjacent schema-admission blocker also reproduced. An unqualified
`register!` is not recognized; qualifying it stores `(quote fn?)`, and the
eval path refuses that non-Malli static form as `schema-refused`.

### Priming with stale loaded code

**Verdict: CONFIRMED defect, FALSE scope as filed.**

The issue
[[priming-indexes-with-the-live-jvms-loaded-code]] reproduced in an isolated
operator root:

1. A fresh loaded indexer produced 1,242 function rows and 1,931 total rows
   from disk digest `75e983...`.
2. The live JVM's `seon.fn/rows` Var was replaced with the previous
   contracted-only admission, yielding 379 functions and 1,068 total rows.
3. Real operator indexing committed 379 functions while recording the same
   current disk digest `75e983...`.

The digest therefore lied exactly as claimed. The isolated JVM was PID 41673
and was stopped cleanly.

The filed scope was understated. `script/seon/fresh_operator.clj:1036-1067`
also selects a live anchor for `bin/seon start NEW-CLUSTER`.
`src/seon/cluster.clj:359-414` then ensures a missing current-digest ancestor
through the target JVM's loaded `populate-ancestor!`, reader, and indexer. A
stale JVM can therefore publish a fresh-digest ancestor with old
interpretation before the new cluster exists. The existing issue was extended
with that owner, evidence, and an explicit start-path acceptance proof.

## Claim 7 — full gate

**Verdict: CONFIRMED.**

An independent `bin/test` run:

```text
Ran 568 tests containing 2780 assertions.
0 failures, 0 errors.
```

Exit status was zero. Elapsed time was 475 seconds. A digest over `src/`,
`test/`, `resources/`, `deps.edn`, and `bin/test` was identical before and
after the run:

```text
ecb4408083becabc380d80ec7dc271428e24514f848a3d486bd8a0272e612ac6
```

No other lane's source or test breakage contaminated the gate.

## Standing failure-mode sweep

| Failure mode | Verdict |
|---|---|
| Hand lists / prefix / regex / special cases | **Found:** literal namespace-operation set and repeated local-name matching. |
| Silent absence read as health | **Found:** executable nested declaration is absent from both row and refusal census. |
| Symptom patch | **Found:** the polarity of the old operation list changed, but executable identity was not derived. |
| Lying comments/docstrings | **Found in target:** "never a list"; "cannot agree ... by sharing their bug"; "one row per declaration"; every reader-seen `defn` lifts arglists; present-tense `:seon.fn/calls` reachability. The last was corrected after the frozen proof by `52423e362`. |
| Stale mandatory skills | **Found:** three skills still require a contract for build-time function rows. |
| Stored-derived state | No new instance in the target source/test diff. Program rows are the intended durable facts. |
| Second mechanisms | No second production reader, registry, or indexer was added. The flawed test reuses production rather than introducing a second owner. |
| Unjustified clocks | None added. |
| `:any` without a proven boundary | None added. |
| Test passing for the wrong reason | **Found:** shared event stream, local-name matching, global namespace grouping, and set collapse permit false agreement. |

No per-file special case or regex was found in the target paths. The new test's
temporary UUID creates an isolated fixture directory; it is not a semantic
clock or tuned backstop.

## Calibration — what is genuinely solid

- The build-time contract gate is gone from the one existing admission owner.
  No parallel registry or durability flag was introduced.
- Today's `src/` and `test/` sources independently contain exactly 1,242 unique
  direct top-level function declarations, and today's database contains the
  same 1,242 symbols with no file or namespace mismatch.
- All 808 private rows are complete and contract-free.
- Ordinary top-level `set!`, `load!`, and predicate registration no longer
  erase following direct declarations.
- A direct declaration whose existing reader event lacks proven namespace
  attribution is refused with file, line, source, and reason.
- The eval-time contract boundary is unchanged.
- The full gate is genuinely green.

Those are substantial gains. They are not enough to support the stronger
claims that namespace semantics are list-free, every declaration is accounted
for, or the recurring coverage proof is independent.

## What I would not stake the corpus on

I would not stake the corpus on a parser that treats `foo/ns`, `foo/in-ns`,
`foo/defn`, and `foo/deftest` as core declaration operations because their
local names match. I would not stake it on a loudness check whose entire
declaration census is produced by the same lift whose silence it is meant to
detect. I would not stake "one row per declaration" on sets grouped globally
by namespace.

I would stake the current 1,242-row measurement, the completeness of the 808
private rows, the unchanged eval boundary, and the 568 / 2,780 / 0 gate on the
evidence above.
