# Batch A runtime review — review-astra-batch-a

2026-10-07. Independent Astra-side review of `c08e2c766`, `649829332`, and `69e7b56fe`.
**Request changes to reader recovery.** The value-storage deletion and attribute-selection change have sound
algorithms; their installed behavior is unverified. No tests were launched by this lane.

## Findings, by severity

### P1 — `69e7b56fe`: nested-token recovery discards subsequent forms, and still can expose inner calls

`src/seon/sci/reader.cljc:726-737` excludes nested invalid tokens from `token-end`, then selects only a
column-zero `(` or `;` as the recovery point. This is a line heuristic, not the failed form's end.
`recovering-events` records everything through that point as one error (`:765-781`) and resumes parsing
there (`:784-791`). These are concrete counterexamples to “reads every valid form and reports every
malformed one”:

| Input text | Required events | Events derived from the changed source |
|---|---|---|
| `[1 3x] 42` | error, form `42` | one error spanning the entire input |
| `[1 3x] (+ 1 2)` | error, form `(+ 1 2)` | one error spanning the entire input |
| `[1 3x]\n[42]` | error, form `[42]` | one error spanning both lines |
| `[1 3x]\n[2 4x]` | two errors, each with its location | one error; the second malformed form is never read |
| `[1 3x\n(+ 40 2)]\n(+ 1 2)` | error for the vector, form `(+ 1 2)` | error, leaked inner `(+ 40 2)`, stray-closer error, form `(+ 1 2)` |

The first four are a regression of the changed branch: the installed old reader does resume after the
token and reaches the later form, albeit with the phantom inner events this commit intends to remove.
The fifth is an inherited heuristic defect left in the newly selected recovery path, not a new regression.
It matters because an inner call can have effects when treated as a top-level form. Merely adding more
anchor characters would exchange missing forms for more leaked inner forms.

**Evidence:** read `git show 69e7b56fe`, both changed functions, `cause-data`, `reader-error`, `read-events`,
`error-event`, `recovering-events`, and `read`. The live old reader confirmed the nested-container facts
and subsequent-form behavior below. **The changed outputs above are source deductions, not an installed
execution of this commit.** No target code was loaded.

**Fix:** preserve the parser's actual failed-form boundary/resume cursor. The pinned Edamame producer
already holds its delimiter and enclosing-container state in `parse-to-delimiter`
(`reference-code/edamame/src/edamame/impl/parser.cljc:275-306`, revision `196ea5e4ef`). Extend/reuse that
producer's recovery to consume the failed outer collection once, respecting strings, comments and reader
syntax, then resume at its end on the existing reader. Do not semantically reread its children. Keep
genuinely unclosed-source recovery explicitly distinct, since its intended boundary can be ambiguous.
Add these rows to `a-failure-inside-a-collection-skips-the-whole-form`, asserting exact surviving forms,
one error per malformed top-level form, source spans and error locations. The current four assertions
(`test/seon/sci/reader_test.clj:513-523`) exercise only favorable anchors or EOF.

### P2 — `c08e2c766`: the changed settlement/fresh-context behavior has no reaching regression in this slice

The new test calls `shown-result` directly (`test/seon/sci/eval_test.clj:2572-2603`). Its allocation and lazy
realization assertions are useful, but it never reaches the changed settlement or rebinding functions.
It therefore cannot prove either that receipts contain no stored copy or that a fresh context answers
every old handle with the intended marker. This is a verification gap, **not a demonstrated runtime failure**.

Concrete missing cases: evaluate a cut vector, `nil`, `false`, and a live host object; settle normally;
read the stored receipt and compare the live handle by identity; then fork without the carried agent
context and inspect the same handle. Include a historical receipt with a value digest and no class, and
a refusal of the settled-results query. Expected: exact shown text survives, no new value digest/blob is
produced, the carried context keeps the original value, a fresh context returns the marker (class when
known), and a read refusal becomes `:seon.sci.eval/unbound-results`, not silent absence.

**Fix:** one entrance-level regression covering those cases, using the existing fixture/recording path.
Do not restart default for this test. Source inspection supports the implementation, but publication,
adoption, fresh-context binding and storage absence remain unverified until root runs that proof.

### P3 — `c08e2c766`: retirement leaves an active dangling task edge and contradictory declarations

`resources/seon/tasks/seon.sci.eval.edn:156` still puts deleted `seon.sci.eval/value-storage` in `:must-read`.
That is a current definition edge, not merely dated prose: `resources/seon/schemas/seon.task.edn:9`
declares it as such. A worker following that task cannot resolve the required function after this change.
Update the edge to the surviving owner and revise the task's instruction to obtain a stored artifact
from the printer; that instruction no longer describes the accepted fix. No adoption failure is claimed.

Also, `src/seon/sci/eval.clj:2806-2809` still says a fresh fork binds “settled cut values” again, whereas
`:2797-2804` binds markers. `resources/seon/schemas/seon.cluster.eval.edn:78-79` still admits
`:seon.eval/value-digest` and `:seon.eval/unstored` as stored evaluation members. Their declarations remain
at `resources/seon/schemas/seon.eval.edn:45-46`; only `value-edn` and the shown-map members were removed.
Thus the lane note's claim that the retired fields left the entity map is false. The commit message
does acknowledge a deferred migration. This does not break a current reader, but it is not complete
schema retirement under the assignment's requirement. Correct the current documentation and retire
the stored fields through the ordinary migration/adoption path, preserving whatever historical evidence
the owner requires. Keep the marker's live schema separate from the retired stored member.

The loaded REPL skill is also stale: `.agents/skills/repl/SKILL.md:30` says MCP JVM mode is outside a turn,
while the installed tool and `src/seon/turn/batch.clj:888` identify it as an ordinary recorded agent run.
Its obsolete `branch` tool instructions do not match the callable tool signature. Those files were not
edited by this bounded review. The data-oriented skill still illustrates the deleted value-blob
settlement branch. Root/its file owner should correct these current claims; they were not used as proof.

## Commit-by-commit reasoning

### `c08e2c766` — deleting evaluation value storage

The producer deletion is the simplest correct approach for the owner rule: no copy is needed once
restart restoration is intentionally removed. It removes `admit-value`, artifact serialization, and
blob staging for the evaluation result. There is no replacement serializer/cache and no new clipping.

The data flow remains: `shown-result` binds the actual object and returns it (`src/seon/sci/eval.clj:3523`,
`:3551`); the success evaluation constructor propagates the class; `evaluation-facts`
(`src/seon/turn.clj:98-185`) and `receipt-terminal-attributes` (`:1171-1201`) carry it into settlement;
`settlement-projection` (`:187-200`) preserves independently staged writes; batch `evaluation-record`
(`src/seon/turn/batch.clj:613-632`) carries the class too. `seon.repl/def-note`
(`src/seon/repl.clj:135-157`) still receives the Var class it needs. `shown-failure` keeps the whole error
as the live value and strips size/class presentation fields as before (`sci/eval.clj:3577-3591`).

Fresh-context binding queries only the class (`sci/eval.clj:2770-2785`), accepts a missing class, and
binds a marker. `fork-for-turn` does this only without a carried context and attaches rebinding failures
beside the returned context (`:2844-2860`). No blob connection is needed any more. Work removed from an
ordinary evaluation is proportional to the old bounded storage walk and serialized copy. The remaining
class lookup is constant-time; printing still follows the selected presentation, including deliberate
full printing. Fresh-context rebinding still costs O(number of saved evaluations); this patch does not
claim to remove that history query.

Search covered `src`, `test`, `resources`, and `docs` for `value-storage`, `bind-handles!`, `unstored-notes`,
`value-edn`, `value-digest`, and the qualified unstored fields, including EDN artifacts. No executable
caller of the deleted functions or consumer requiring the deleted evaluation copy remains. Matches in
captured profile/turn EDN and dated studies are historical, not runtime consumers. The active residue
is finding P3. `render.walk/continue` (`src/seon/render/walk.clj:790-847`) and `/data?value=` still have a
generic blob-artifact route, but no producer in this slice connects an evaluation digest to those routes.
Error and test-failure artifacts have separate producers/consumers, so deleting their reader here would
break surviving behavior. Their other-printer issues are outside this deletion, not evidence that an
evaluation copy must survive.

### `649829332` — selecting cell attributes from held datoms

**No correctness defect found in this diff.** The misleading commit message was disregarded; the
authority is its actual five-file diff and `433214bc5` / `waste/commit-validation-notes.md`.

Let V(a) be the unchanged `vector-attribute-in?` predicate. The new `vector-attributes-in`
(`src/seon/schema/datahike.clj:442-465`) returns exactly the keyword attributes supplied for which V(a)
holds. The cells-installed guard is unchanged; non-keyword attribute ids remain excluded, as before.
`vector-shaped-attr-in?` still derives from Malli's compiled storage shape (`:420-429`), cached per
projection/attribute. It uses `malli.registry/schema` (pinned `43c6dca8d2`, `registry.cljc:97-100`).

All callers supply the domain over which they subsequently ask membership:

- `write-entity-value` (`src/seon/db.clj:3906-3963`): the entity's own EAVT datoms, so the outside-cell
  refusal still detects every offending attribute.
- `write-owned-values-error` (`:4083-4327`): retracted report datoms for `swept`; the old predicate was
  precisely “not added and vector attribute.” Owners on both sides, component completeness, cycles,
  multiple owners, bounds, identity checks and whole-value validation are unchanged.
- `logical-datoms` (`:4357-4401`): report datoms for element changes, **and separately the held before/after
  cell datoms** for position-only changes. This second lookup is essential: a reorder report does not
  contain the element attribute. The diff includes it, so it does not drop the logical owner fact.

`write-report-error` (`db.clj:5289-5343`) still judges attempted and effective writes, retention,
render targets, attribute validity, deletion, owned values, definition digests, and prepared arity.
`validate-write-report` (`:5371-5403`) still uses the final database's projection. Malli's
`validator` (`core.cljc:2672-2678`) and Datahike's final-report `validate-report`
(`reference-code/datahike/src/datahike/db/transaction.cljc:1225-1236`, pin `d9121bbbad`) retain the
same acceptance rules. The patch removes registry enumeration, not validation.

Search found no remaining one-argument source/test caller. Cost is O(held datoms to collect attributes)
plus one predicate call per distinct keyword attribute **per invocation**, not one per registry form.
The predicate's compiled result is reused by projection. This is not a claim that the entire validator
is O(distinct written attributes): it still reconstructs affected owning values and has other costs.
The simple set/intersection approach is appropriate; a second global cache is unnecessary.

The new overhead regression bounds work below registry size and checks successful final validation
(`test/seon/db_overhead_test.clj:80-101`). The shape-equivalence table covers duplicate attributes,
integer ids and the cells-installed guard (`test/seon/schema/vector_storage_test.clj:92-119`). Existing
storage-refusal and reorder-only tests reach the subtle cases (`:460-493`, `:630-649`). The datom-law test
does not justify removing any validation and the commit correctly removes none.

### `69e7b56fe` — recovery and artifact diagnostics

The nested odd-map classification is supported by Edamame: `throw-odd-map` receives the map's opening
location, even when nested (`parser.cljc:620-647`). One recovery instead of repeated outer-prefix reads
removes the measured multiplicative reread behavior for the favorable inputs. It does not prove a
general linear reader: exception construction, enclosing-container evidence, line scans and resumed
reader creation still have their own costs. Finding P1 prevents accepting the wider correctness claim.

`read-artifact` now checks all returned events for an error before judging artifact shape
(`src/seon/render/value.clj:1428-1450`). This correctly avoids reporting a recovered parser failure as
“another kind of blob.” Empty input, multiple valid forms and a wrong artifact shape still refuse;
one valid artifact still returns unchanged. Its surviving `render.walk/continue` caller checks the
refusal before extracting the artifact value. The new regression covers the misclassification, though
its prefix assertion alone would not catch losing the actual reader message. Add equality against the
reader's error message or a structured cause assertion. Deep successful read-back remains explicitly
unverified/unfinished, as the task added by this commit acknowledges.

## Live observations and reproducibility

Both `bin/seon status` and MCP `runtime_status` answered. Default runs committed `a8e3bde5b`, PID 30874,
start instant `2026-10-06T11:15:24.710Z`; it is **faulted**, with 14 open faults, 105 failed tests and 43
unknown tests at entry. Heap used was approximately 8.65 GB, live 7.39 GB, maximum 11.0 GB. This is the
foreign runtime boundary, not a red attributed to these commits. No namespace was required, reloaded,
adopted or redefined; probes were finite lexical JVM forms, `agent "root"`, `cluster "default"`.

Reader probe, result `result/ef935518ec5f8`: for the five inputs listed below, call
`(seon.sci.reader/read {:seon.sci.reader/text text :seon.config.eval.result/max-source 4096})`, record
elapsed `System/nanoTime`, and select form/error-kind/line/column/containers/span from the events.
Installed results:

- `[1 3x] 42`: error span `[0 1]`, form `1`, error span `[3 6]`, form `42`; 2.461 ms.
- `[1 3x]\n[42]`: same initial phantom events, then form `[42]`; 2.025 ms.
- `[1 3x]\n[2 4x]`: error/form/error for each vector, preserving the second failure; 4.024 ms.
- `[1 3x\n(+ 40 2)]\n(+ 1 2)`: leaked `(+ 40 2)`, stray closer, final `(+ 1 2)`; 2.244 ms.
- `{:a {:b 1 :c}}\n(+ 1 2)`: outer error, leaked `:a`, nested odd-map error, final call; 1.681 ms.

The first vector failure names line 1, column 6, with container `[` at row/column 1/1 and elements `[1]`.
The nested-map failure locates the inner `{` at line 1, column 5. These parser facts support the source
deductions without pretending the new recovery is installed. The live envelope elided repeated detail
in later rows; those elided details are not claimed as inspected evidence.

Attribute-cost probe, result `result/ed95c08f37b40`: obtain the database with
`(seon.db/db (seon.cluster.boot/connection "default"))`, its carried projection, and a fresh compiled-cache
holder via `#'seon.schema/with-compiled-cache`. Time the installed one-argument `vector-attributes-in`:
3,389 forms, 206 vector-shaped members, **218.546 ms**. Independently select using
`vector-attribute-in?` over `[:seon.fn/sym :seon.fn/call-arities :seon.fn/arities :seon.cluster.eval/id
:seon.eval/shown :seon.eval/value-class]`: **0.225 ms**, `#{:seon.fn/arities}`, equal to the full set's
intersection. This corroborates the cause, not an after-install defn benchmark. The claimed 143–177 ms
stack cost and projected 35–140 ms final validation remain the lane's measurements/estimate, not mine.

Printer-floor probe, result `result/e11ccdd20ef19`: call installed `shown-result` with
`{:seon.cluster.eval/source "value" :seon.render/profile (seon.render/agent-render-profile
seon.config/defaults)}` and no caps, intentionally bypassing the old storage branch. Inputs are built
before measurement. ThreadMXBean allocation / elapsed time:

| Value | Time | Allocated | Whole original retained by identity |
|---|---:|---:|---|
| `(vec (range 1000))` | 3.036 ms | 1,367,608 bytes | yes |
| `(vec (range 100000))` | 3.285 ms | 1,353,104 bytes | yes |
| counted `(map f (range))` | 3.311 ms | 1,300,296 bytes | yes; 32 elements realized |

All show one elision and no value-edn. This is a component comparison on old code, not a run of the
changed normal request path. The lane's 417 ms / 740 MB old-storage number was not repeated. Neither
storage reclamation nor post-install retained heap was measured by this review.

## Verification and central test request

Reviewed commit diffs and an immutable `git archive` of `c362de9d7e29cb1700aa0d95071917333175593f`;
the shared working tree has foreign changes across the runtime, schemas, tests and skills. None were
modified. Dependency files cited above match the HEAD gitlink pins. No worktree was created.

`clj-kondo --lint src script test --cache false --parallel` on the archive: **0 errors, 1,555 warnings,
10,529 ms**. Exit status 2 is the warning status. The first attempt lacked the ignored analyzer config;
after linking that config (not the cache), the complete rerun produced these results. This requested
whole-corpus lint is outside ordinary-operation latency; it is not a measured runtime regression.

Called the already loaded `seon.contracts-compile-test/check` against the archive's packaged projection,
without loading reviewed source. All seven touched source files and six test files returned **[]**:
reader/bridge/render 176.605 ms; db/eval/turn/batch 408.673 ms; tests 174.410 ms. Result handles:
`result/ee51fbdb046d5`, `result/e6ba5c5c99b2d`, `result/e29783b2513fa`. This compiles declared contracts;
it proves neither boot/adoption nor test behavior.

Root's focused list (none run by this lane):

- `seon.sci.eval-test/showing-a-value-realizes-and-stores-only-what-is-shown`.
- `seon.turn-test/receipt-transitions-preserve-one-terminal-outcome` and
  `seon.turn-test/recovery-preserves-terminal-receipts-exactly`.
- Add `seon.sci.eval-test/settled-results-remain-live-and-fresh-contexts-bind-markers` for P2
  (**proposed name; not an installed test**).
- `seon.db-overhead-test/final-validation-asks-cell-storage-of-written-attributes-only`.
- `seon.db-test/transaction-report-datom-law`.
- `seon.schema.vector-storage-test/vector-attributes-are-declared-by-shape`,
  `the-writer-refuses-vector-storage-faults`, `reports-name-the-vector-owner`, and `peer-vectors-keep-ref-semantics`.
- `seon.owned-value-test/owned-values-validate-child-only-writes-and-before-owners` and
  `seon.owned-value-test/incomplete-unowned-cyclic-and-shared-components-refuse`.
- `seon.sci.reader-test/a-failure-inside-a-collection-skips-the-whole-form` with the P1 rows;
  `source-round-trips-and-spans-partition-the-tree`, `every-form-shape-has-a-cursor-derived-span`,
  `read-failures-are-isolated`, `structured-error-kind-classification`,
  `recovered-forms-carry-the-coordinates-of-the-whole-source`, `recovery-keeps-the-callers-reading-policy`,
  and `reader-errors-retain-token-and-enclosing-parser-evidence` in the same namespace.
- `seon.render.value-test/an-unreadable-artifact-names-the-readers-failure` and
  `seon.render.value-test/value-artifact-stores-only-the-print-node-source`.

Review-only change: one Markdown file, **0 source/test lines changed**. No runtime repair, test pass,
shared acceptance, restart, browser paint or post-install latency is asserted. This file preserves the
findings and probe evidence; existing defect classes remain with their owning tasks/lanes.
