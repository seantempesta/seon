---
type: review
status: REVISE
created: 2026-09-27
reviewed: [f4d45cc71, 39fe40d0a]
evidence: tmp/feedback-renders-review/
---
# Feedback renders review — REVISE

The selection substantially improves several replies, but these commits are not acceptable as written.
Four blocking findings follow. Line numbers refer to **39fe40d0a**, not the moving working tree.
The shrink list names deletions, not permission to edit another owner's files.

## 1. Return the selection as data; remove the inner printing and incorrect cut roots

**Evidence.** In MCP JVM session `feedback-render-review`, the pinned scratch schema renderer returned
`java.lang.String`, `map? false`; its distance-zero result was the string `(doc :seon.task/intent)`.
The pinned error renderer likewise returned a string. Both declare string outputs
(`error.clj:2351–2369`, `render/schema.clj:173–187`). They print inside the render through
`reply-shown`/`floor-shown`; the edge printer must then process an already printed value.
This is the admitted workaround in the implementation note, not compliance with the data ruling.
A string containing printed EDN is not the selected map promised by that note.

A direct, finite error-render probe establishes a correctness failure beyond representation:

```clojure
(let [error {:seon.error/message "scratch.frr/f refused argument 0"
             :seon.instrument/fn 'scratch.frr/f
             :seon.instrument/check :input
             :seon.error/offending ["4" (apply str (repeat 6000 "z"))]
             :seon.error/problems
             [{:seon.error/schema-path [0] :seon.error/path []
               :seon.error/argument "argument 0"
               :seon.error/expected :int :seon.error/offending "4"}]}]
  ;; unit also supplies the disposable branch's db and fixed profile
  (frr.error/render-ai (assoc unit :seon.render/value error
                                  :seon.repl/handle 'result/e1)))
```

The output's cut is `(subs (get-in result/e1 [:seon.error/fix-form 2]) 2048)`.
`(contains? error :seon.error/fix-form)` is **false**. The claimed root equivalence in
`reply-shown:2335–2349` is false: repair adds a key, and problem selection also transforms and deduplicates
paths. The probe completed in 251 ms including branch acquisition/release; `cut.edn` retains the result.
The large repeated string represents about 1,500 estimated tokens before rendering.

**Smallest fix.** `render-ai` returns `rendered-reply` or `usage` directly, with a data output contract.
Return listing information as data too. Let the one edge printer own limits and retrieval of the selected
value. Delete the AI calls to `prepare`, the string concatenation, and the claim that a transformed reply
has the original error's paths. Carry the selected value's actual retrieval provenance through the existing
printer; do not add another renderer-specific cursor or printer. Convert HTML's inner printing in the same
owner cut, preserving hiccup as data. No re-dispatch or form evaluation is needed.

## 2. The schema sample reports false failures and does work unrelated to changed inputs

**Evidence.** On a disposable branch of the real database:

```clojure
(let [k :seon.fn.arity/input-refs
      definition (clojure.edn/read-string
                   (seon.db/q '[:find ?f . :in $ ?k
                                :where [?s :seon.schema/key ?k]
                                       [?s :seon.schema/form ?f]] database k))]
  (frr.render.schema-copy/usage database k definition))
;; definition = [:set {:seon.db/index true} :qualified-keyword]
;; :stored 3789, :checked 200, :failing 200
;; :failing-example [14441 :seon.agent/execution-handle]
```

This is the **unchanged** committed schema. `stored-check:48–74` validates each raw datom's `:v`
(a keyword) against the owning set schema. It also labels a datom count an entity count.
The 372 ms complete probe is saved in `false-validation.edn`.

For unchanged `:seon.task/intent` with `[:string {:min 1}]`, three consecutive `usage` calls on the
same immutable database each returned `:checked 200, :failing 0, :stored 249`. They took
65.637, 1.204 and 1.136 ms. The read sink collected **12 reads** (three queries and one datoms read
per call), and the branch commit stayed unchanged (`usage-probe.edn`). This establishes repeat work,
not a need for a new cache. Both AI and HTML call `usage` independently. Merely viewing an unchanged
schema also takes this path.

The existing 15–42 ms table reproduces for its four simple cases (40, 18, 16, 16 ms), but is not a
bound or an armed installed-render measurement. The 200 limit bounds the number of validations,
not their input size or the work of a declared validator. Count work is O(min(N,10000)); sample work
is O(min(N,200) × validation cost), plus three queries and sorting all referring names before taking eight.
It is not proportional to changed definitions. `bounded-count` also loses whether 10,000 means an exact
count or a lower bound; HTML prints it as an exact count (`schema.clj:248–251`).

**Smallest fix.** Remove `stored-check` from ordinary `usage` and registration rendering. Show the changed
form and its references; rely on the canonical admission/writer's actual validation evidence. If an explicit
impact inspection is retained, reconstruct values using the schema/storage owner before validating, count
distinct entities, and expose an honest bound. Do not introduce a second admission validator inside a render.
This deletes the entire 27-line checker and its two limit definitions (8 lines) from the common path.

## 3. Read failures are silently turned into absence

**Evidence.** The pinned helpers, called at the live REPL, returned:

```clojure
(@#'frr.render.schema-copy/found {:seon.error/message "read refused"}) ; => []
(@#'frr.render.schema-copy/read-form "[")                           ; => nil
```

These are finite synthetic inputs to the unarmed scratch helpers, not Var redefinitions or a claim that
the message-only map validates as a complete database refusal. The branch handles every non-sequential
result identically, including the declared read-error alternative. `usage:105–128` then
uses missing previous data to assert `:new? true`, or missing reference data to omit dependencies.
`stored-check:55–67` likewise maps a non-sequential datoms result to zero stored values.
The canonical query error is data; this renderer discards it. `reply-probe.edn` retains the 800 ms
complete probe and also confirms that the normal error name lookup captures one read.

**Smallest fix.** Delete `found`'s refusal-to-empty conversion (6 lines), and remove `read-form`'s
catch-to-nil (the helper is 8 lines). Preserve the declared read refusal/parse diagnostic beside the
selection, or let the existing render-failure boundary print the original value with its diagnostic.
Only a successful absent query may mean “new”; only a successful empty query may mean “no referrers.”
Keep the error and schema read contracts explicit about their alternatives.

## 4. The acceptance claims exceed the proof; publication and end-to-end parity remain unproved

**REPL evidence: the numeric table reproduces.** I reran all ten error cases and four schema cases from
pinned copies, through the supplied disposable-branch harness. These are estimated tokens, using its
`seon.ai.tokens/estimate` and its manual after-render, not a tokenizer comparison of installed replies.

| Case | Before → after | Selection ms | Evaluated fix |
|---|---:|---:|---|
| Nested contract | 155 → 116 | 1 | yes, result 10 |
| Missing key | 161 → 103 | 0 | yes, result 0 |
| First-party contract | 147 → 131 | 0 | **none** |
| Exception | 73 → 73 | 0 | yes, supplied ratio fix |
| Unresolved name | 49 → 61 | 3 | yes |
| Missing entity | 111 → 73 | 28 | yes |
| Removal refusal | 3094 → 105 | 1 | **none** |
| Writer refusal | 157 → 135 | 0 | **none** |
| Schema admission | 73 → 55 | 0 | **none** |
| Nilable schema | 190 → 172 | 0 | yes |
| New schema | 5 → 34 | 40 | not applicable |
| Compatible schema change | 5 → 145 | 18 | not applicable |
| Stored-data-refusing change | 5 → 273 | 16 | not applicable |
| Unstorable schema | 5 → 34 | 16 | not applicable |

Thus 6/10 fixes were evaluated; the first-party row has **no `:fix-form`**, despite the prose table claiming
one. The writer reply only supplies the first invalid field; unresolved schema admission does not identify
the missing reference. Removal gives a useful call site, but no repaired caller was evaluated. A generated
height of zero proves admission and execution, not recovery of the author's intended rectangle.
“All cases fixed from the reply alone” is falsified, including by the original saved results.

**Entrants.** The actual pre-restart `:seon.batch/shown` differed for missing-entity and removal-refusal;
all ten manually selected after-error replies agreed. The helper named `agent-batch` invokes a local
function returning text, then calls `batch/evaluate-batch` with `turn-request`. It does not drive the
provider/Flow turn, and the after comparison invokes `reply-value` separately on extracted errors.
For schemas it constructs a registration map from the source when the installed return is still a keyword,
and renders only the MCP registration; equality there compares the old before text. These are useful
batch-adapter probes, not installed two-entrant proof.

After default was replaced externally, an installed nested-contract batch did give identical shown replies
through `code-request` and `turn-request` (2138 ms for the complete two-batch branch probe). But installed
registration still printed only:

```clojure
#:seon.schema{:key :scratch.frr/height, :definition [:int {:min 0}]}
```

No usage report appeared. The branch's carried forms did not contain `:seon.schema/registration`.
Trying to validate that name against its carried registry raised `:malli.core/invalid-schema`.
New host code being loaded was not proof of schema publication/adoption on that branch.
The unstorable case also retained `:installed? false` only in notes, absent from shown text.
These are named installation/entrant boundaries, not permission to modify default or another lane.

**Smallest fix.** Correct the implementation note and proof labels now. Coordinate publication of the
registration schema and its consumers through the existing owner, then prove the actual saved replies on
fresh branches at the same program/schema basis. Use the real virtual-provider turn entrance, compare
shown values and diagnostics, and check fixes for returned error values as well as thrown errors.
The current `:fix-works?` only checks absence of `:seon.batch/error`; returning an error map could pass.
Record arming, read evidence and timing of the actual render path. No new test runner is justified.

## Boundaries verified without a blocking finding

**Return contract.** The new `register!` result is the authored registration data, not durable acceptance.
On a disposable branch, `call-with-registration-delta` returned
`{:seon.schema/key :scratch.frr/width :seon.schema/definition [:int {:min 0}]}`;
`registration-delta-form` returned `[:int {:min 0}]`; a database query for the new key returned nil.
`m/validate :seon.schema/registration` against the archived packaged projection returned **true**.
Its function metadata declares `[:=> [:catn …] :seon.schema/registration]`.
The output contract and packaged shape agree. Installation in the carried projection is the gap above.

**Form face.** The new attachment on `:seon.error/base` is right under the form-face ruling:
with a held error the armed `seon.error/render-form` returned `(seon.error/reply-value result/e1)`;
without one it returned nil. It constructs data and performs no evaluation. It recomputes the displayed
selection over the held value in the viewer's custody; it is neither the repair operation nor permission
to run it during rendering. Preserve this behavior. Stored unheld errors may use an existing identity
requery if needed; absence of that enhancement is not a blocker. Do not confuse this correct form face
with the broken cut root in finding 1.

**Read-only scope.** Ordinary pinned `usage` left its disposable branch commit unchanged and captured
four reads per call in `seon.db/*read-evidence-sink*`. The error name lookup captured one read.
Both functions accept the explicit viewer database; no database writes were observed in these probes.
This proves direct-call capture, not that both entrants attach those reads to their saved shown units.
The scratch render functions were unarmed; first-party dependencies were installed. No claim of an armed
0–4 ms bound, arbitrary-validator purity, refresh correctness or browser paint follows.

## Ranked shrink list

Counts include comments and blank lines in the named ranges at 39fe40d0a; they are gross removal
candidates, not a promised net total. Coordinate surviving callers and attachments in the same cut.

1. **Remove sample validation from the render:** `render/schema.clj:20–27,48–74`, **35 lines**, plus the
   sample-only output fields and HTML. Fixes the false 200/200 failure and eliminates repeated validation.
2. **Remove inner printing:** `error.clj:2335–2369`, **35 lines**, and
   `render/schema.clj:158–187`, **30 lines**, replaced by short data-returning functions. The HTML-only
   use of `floor-shown` must move to the one printer boundary too. This fixes the bad cut path and duplicate
   budgeting, rather than adding another retrieval system.
3. **Delete replaced render paths:** `error.clj:2448–2479` `render-contract-ai`, **32 lines**, and
   `2428–2439` `source-entries`, **12 lines**, after converting its remaining `sci/kernel.clj:822–824`
   caller and `seon.instrument.edn` attachment. Delete `render/ns.clj:179–208` (`schema-parts`, `schema-ai`,
   `schema-html`), **30 lines**, after converting the test caller. Keep the still-attached `schema-form`.
4. **Retire the old refusal prose chain together:** `error.clj:1103–1391`, **289 lines**, contains
   `reader-correction`, `refusal-data`, `refusal-text`, `instrumentation-prose`, `notice-ai-prose`,
   `notice-prose`, `ai-prose`. The new `reply-value` selects the same refusal members and composes another
   “operation refused argument” sentence; retaining both paths defeats the advertised single selection.
   Convert remaining resource attachments/callers, preserving producer-specific reader evidence as data.
   This is a coordinated cut, not 289 lines of immediately dead code.
5. **Prefer producer evidence to synthesized calls:** remove `plain?`, `as-written`, `repaired`
   (`2016–2067`, **52 lines**) and `fix-form` (`2080–2116`, **37 lines**): **89 lines**.
   Keep an explicit producer-supplied repair/example as data if valuable. `as-written` guesses supplied
   arguments by dropping non-plain prefixes and removing world-named keys, although explicit caller inputs
   must win. A generated value is an example, not the intended correction. Existing `explain-problem`
   already supplies path, expectation, offending value and fix guidance. Keep its precise leaf evidence;
   fix its producer if whole-argument schemas leak, rather than reinterpreting paths at every render.
6. **Delete failure-to-absence helpers:** `schema.clj:32–46`, **15 lines** including separator. Preserve
   diagnostic data as in finding 3. Do not replace these with another fallback.

Do **not** delete `seon.error.refusal/chain`, the canonical refusal constructor, or the producer's
`explain-problem` merely because this renderer is large. They construct and preserve evidence; selection
is a different role. `problem-parts`/`problem-sentence` (`error.clj:1022–1061`, **40 lines**) still serve
producer messages; remove them only after their live consumers consume the same structured problem.
The new selector should not rebuild their semantics or prefer a generic outer wrapper over actionable
cause data: the REPL probe with outer message `"outer"` and a second cause `"root failure"` selected only
`{:seon.error/message "outer"}`. Keep the complete chain in the value, and select actionable evidence
from that owner rather than adding another chain normalizer.

The existing library seams suffice: pinned Malli **43c6dca8d2d8df3162b13725939954807be4a4ed**,
`core.cljc:2672` `validator` caches on the compiled schema; `util.cljc:342` `get-in` is its lens lookup;
`generator.cljc:519` `generate` takes a size/seed but supplies no general time bound. None promises that
raw storage datoms have the owning schema's value shape. Schema-specific selection belongs here;
budgets, collection traversal, cuts, cycle/reference handling and printing belong to the value printer.

## Landing and verification

Only this review document is changed by this lane; source/test delta **0/0**. The reviewed commits add
**1511 and delete 50 lines** together (net **+1461**); `error.clj` is **+410/−28**, net **+382**, and
`render/schema.clj` is **+260/−0**. The initial `+436` figure is the first commit's changed-line stat,
not its net addition. No runtime test request was started.

Probes ran through MCP `eval_clj`, explicit root `/Users/sean/src/seon`, cluster `default`, JVM session
`feedback-render-review`. Mutating evaluation used `agent/acquire-context!` with `:seon.agent/isolate? true`
and `:seon.cluster.registry/from` equal to the captured head; every acquired execution released in `finally`.
The proof's helper namespaces were renamed to `frr.*`; reviewed sources came from `git archive 39fe40d0a`.
On the old runtime, the HTML route's `id/printed` was private; the scratch copy used the same Var via
`@#'seon.id/printed`. No production Var was replaced. The original proof and another lane's files were untouched.

Foreign boundary: initial pid **62088**, archive **f15ea624a8a89c428bafd282d134dbd359a2f347**, had no
`seon.render.schema/usage` and still declared a keyword `register!` result. During review its advertisement
vanished, pid 76305 briefly answered “database connection is not acquired,” then pid **77505** became ready
on archive **59c89eb90adfd07718e6c512c3b0dc2d073df1f6**. I did not initiate any stop, reset or restart.
The latter loaded the new host return contract while the acquired branch still lacked its registration schema.
Health reported 11 open faults, 150 failed tests and 42 unknown failures initially; the later snapshot also
reported 23 stale Vars. These are not passes or findings attributed to these commits.

Whole multi-batch proof envelopes took 3.503–5.941 s for error cases and 0.743–1.329 s for schema cases,
including context acquisition, setup, two entrants, rendering, optional repair and release. Those exceed the
ordinary-operation target; their render-only timings are separated above. The supplied benchmark has no
per-stage armed profile or per-render allocation evidence, so I do not claim those costs are justified or
that the render commits caused them. No extra profiling run or test run was launched. Runtime cache weight
was about 511 MB initially and 462 MB after replacement; these are shared cache readings, not render memory.

Required precommit checks ran from **git archive 23991131e769176b547f398d577e7cfbf88a85bb** plus
this document, excluding every foreign working-tree edit. `clj-kondo --cache false --lint src script test`
from that archive: **0 errors, 2748 warnings, 6534 ms**. The warnings are not represented as a clean lint.
`seon.contracts-compile-test/check` against that archive's packaged projection and the three reviewed
consumers (`error.clj`, `render/schema.clj`, `schema.clj`): **0 findings, 461.769 ms**, invoked at the live
REPL. No test runner was called. Logs remain in `tmp/feedback-renders-review/final-{lint.log,contracts.edn}`.
A final optional probe received `:session-lost` at 02:09:33Z after another external replacement; it did not
execute. All prior branch scopes had already released, and no owned process remains.

Root's exact existing focused tests for the implementation fixes (the document itself needs no runtime test):

- `seon.error-test/the-default-renderers-accept-an-attribute-shaped-error`
- `seon.error-test/an-unstored-error-renders-its-own-message`
- `seon.error-test/a-held-error-observation-renders-as-held-without-a-read`
- `seon.error-test/a-declared-refusal-prints-its-own-message-and-members`
- `seon.html-views-test/fault-pairs-preserve-ai`
- `seon.refusal-grammar-test/structured-refusals-share-one-actionable-rendering`
- `seon.instrument-test/a-missing-key-refusal-holds-values-and-names-the-key`
- `seon.returned-error-test/returned-refusal-is-a-schema-first-error-with-unchanged-history`
- `seon.render.ns-test/task-and-schema-entities-render-as-graph-nodes`

The implementation owner must add regressions for the transformed-reply cut, cardinality-many schema
inspection, unchanged-schema no-validation, refusal propagation, registered return contract/adoption, and
actual two-entrant read capture. These behaviors currently lack demonstrated focused tests; this review
does not invent installed test identities for them.
