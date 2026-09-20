---
type: research
status: complete
created: 2026-09-21
tags: [error-model, kind-retirement, sci, program]
---

# Kind sweep — sci-program

## Current result — morning continuation

Both newly assigned items are landed: `d51a6d91e` converts `my.program`;
`fd93f709a` converts call preparation, followed by the final evidence and
fixture correction commit containing this note. Owned source/resources/tests
are kind-free and load. This is implementation completion, not a green cold
gate: one database contract-rearming error remains at the held test boundary.
Final per-family iteration results and the cold command are at the end.
All earlier section-6 stops below are historical and have owner rulings.

## Historical first stop

Stopped before production edits at the error-conversion PRD section-6
required-member collision. The family is not converted. This is a schema
decision, not a stop caused by another lane's in-flight breakage.

Read the binding error-conversion PRD and all four requested landing notes
end to end: test-system, ops-effects, my-protocol, and error-family-1a.
Read the supplied AGENTS instructions, including lane rules 11–16; consulted
the active roadmap and the inventory sections for these files. Used the
data-oriented-clojure, clojure-testing and repl skills. The first history
inspection was `git log -12 --stat -- src/seon/sci`. Existing acquisition,
row-acquisition and kernel facets were inspected; no duplicate facet was
created. Both standing rulings remain accepted.

## Exact section-6 evidence

At inspected HEAD `86c3a73d49596488ab5623b39dd3b8fb6791d73e`:

- `resources/seon/schemas/seon.test.edn:247` declares
  `:seon.test/unknown-error` as base plus required `:seon.test/unknown`.
- `resources/seon/schemas/seon.test.edn:260` declares `:seon.test/expired`
  as `[:and {:description ...} :seon.test/unknown-error]`, adding no member.
- `src/seon/sci/kernel.clj:568-570`, `failure-value`, and
  `src/seon/sci/admit.clj:582-584`, `semantic-value`, name both in one output
  union. These inspected files and the test resource were clean.

The [committed probe](sci-program-facet-overlap-2026-09-21.clj) uses
`seon.schema.edn/packaged-forms` and `seon.schema.form/map-entries`, not a
hand-rostered fixture. It also checks each loaded function's output metadata.
The inherited-entry owner follows aliases and conjunctions at
`src/seon/schema/form.cljc:27-86`; `seon.error/facet-keys` includes vector
`:and` base extensions at `src/seon/error.clj:1875-1892`, so `expired` is not
excluded as a bare keyword alias.

Command:

```sh
clojure -M docs/prds/steward-platform/research/sci-program-facet-overlap-2026-09-21.clj
```

Exact substantive output:

```clojure
:loads
{:sci-program/required-members
 {:seon.test/expired
  #{:seon.error/at :seon.error/layer :seon.error/operation :seon.test/unknown}
  :seon.test/unknown-error
  #{:seon.error/at :seon.error/layer :seon.error/operation :seon.test/unknown}}
 :sci-program/output-unions
 {seon.sci.admit/semantic-value [:seon.test/unknown-error :seon.test/expired]
  seon.sci.kernel/failure-value [:seon.test/unknown-error :seon.test/expired]}
 :sci-program/shared-required-members true}
```

This is not a claim that the alias currently breaks runtime validation.
The 1a note already observed that composed observations satisfy both. The
new decision for this lane is how the later literal section-6 prohibition
applies to those two explicit SCI unions. The prior marker-only and raw-value
rulings do not decide that alias rule. No generic predicate, marker or
per-facet EDN representation was introduced.

## Three priced options

1. **Declare only `:seon.test/unknown-error` in the two SCI unions
   (recommended).** Guarantee: every currently admitted expiry observation
   still satisfies the declared test-unknown facet, and pass-through retains
   its entire value. Cost: approximately 30–60 minutes for the two contract
   edits, preservation assertions and focused verification, excluding the
   remaining sweep. Give up: the redundant expiry name in these two output
   unions; no distinction is currently encoded in the value. The owner must
   confirm this treatment under section 6 and the facet-manifest checks.
2. **Give expiry distinct substantive evidence at its test producer.**
   Guarantee: consumers can identify expiry by a required observation, such
   as its actual bound and unfinished work, rather than message text. Cost:
   approximately 2–4 hours across the test producer, its resource, exact
   contracts and canonical regressions, plus owner coordination. Give up:
   an SCI-only slice; `src/seon/test.clj` and its resource are outside this
   lane's ownership.
3. **Explicitly exempt equivalent aliases at polymorphic pass-throughs.**
   Guarantee: the existing values and both union names remain accepted;
   producer-specific consumers still require meaningful observations. Cost:
   approximately 1–2 hours for a precise PRD ruling and regression covering
   alias equivalence. Give up: the unconditional section-6 shared-member
   prohibition at those boundaries. No such exception was inferred here.

These are estimates, not measured runtimes. The finding is also recorded in
[the issue](../../../seon/issues/sci-error-unions-name-indistinguishable-test-facets.md).

## Census and verification

Matching-line counts for
`:seon.error/kind|seon.error/class|error/error?`; dated to the inspected tree:

| Family | Source before → after | Schema before → after | Tests before → after | Fast tally |
| --- | ---: | ---: | ---: | --- |
| eval | 33 → 33 | 9 → 9 | 16 → 16 | Not run; no changed inputs |
| kernel | 2 → 2 | 7 → 7 | 1 → 1 | Not run; no changed inputs |
| admit | 4 → 4 | 1 → 1 | 1 → 1 | Not run; no changed inputs |
| reader | 2 → 2 | 5 → 5 | 14 → 14 | Not run; no changed inputs |
| program | 11 → 11 | 4 → 4 | 8 → 8 | Not run; no changed inputs |
| Total | 52 → 52 | 26 → 26 | 40 → 40 | No green claim |

Reader's actual path is `src/seon/sci/reader.cljc`, not `.clj`.
Eval test counts include eval, instrumentation, documentation, shown-text and
fork-isolation files; admit includes its declaration-population test. Zero-hit
files are included in these totals.

The probe's one foreground JVM required `seon.sci.eval`, `seon.sci.kernel`,
`seon.sci.admit`, and `seon.program`, printed `:loads`, and exited 0.
clj-kondo on the probe: **0 errors / 0 warnings**. No test JVM or cold gate
was launched. No unchanged test namespace was rerun. No default lifecycle,
adoption, hot reload, shared SCI evaluation or browser operation was performed;
this is file/loaded-metadata evidence, not a live-cluster behavioral proof.

The documentation hook reported two foreign citation errors in
`docs/prds/steward-platform/plan/wave-3a-task-family-spec-2026-09-21.md`
and `wave-3bc-render-pairs-and-template-proofs-spec-2026-09-21.md` in the
same directory: each cites Datahike gitlink
`fcbd8862800e638dc0f8f5521111f999279cbcd2` while the installed gitlink is
`e11845bac78e1241bca0766ddc07d978bd63d74a`. Those authorities were not edited.
This is a documentation-lint boundary, separate from the zero-warning probe
lint and the verified section-6 condition.

## Held-owner handoffs and remaining work

No held or foreign dirty bytes were edited or included in a commit.
The current kind sites remain with their named owners:

- `src/seon/fn.clj`: 28 matching lines, including read propagation at 49,
  index refusal at 160/195/199, namespace acquisition at 978/993/1039,
  graph reads at 1523–1630, and indexing/admission at 1998–3272.
  Held by publication-dissolution even though clean at inspection.
- `src/seon/schema/edn.clj`: 12 matching lines at
  131, 183, 191, 221, 229, 238, 247, 272, 287, 299, 317, 479.
  Foreign dirty, bridge-step2-walker ownership.
- `src/seon/schema/datahike.clj`: seven matching lines at
  263, 274, 297, 306, 365, 379, 500. Foreign dirty, same owner.
- `src/seon/cluster/source.clj`, `script/seon/fresh_operator.clj`, `bin/*`,
  other held schema paths, cluster/turn and every other inherited dirty path
  remain untouched. No foreign session was operated or messaged.

The complete producer/contract/consumer/message/R8 sweep remains owed after
the section-6 ruling. No facets were added and no stored type changed.
**RESET NEEDED:** no new obligation introduced here; no live stored-kind
census was made and the existing wave reset obligation is not claimed done.

## Cold command owed

After conversion and the required per-family fast passes, the orchestrator
owes the following accumulated cold scope (include only actual changed paths
when landing, and preserve all foreign drafts):

```sh
bin/test --paths \
  src/seon/sci/eval.clj src/seon/sci/kernel.clj src/seon/sci/admit.clj \
  src/seon/sci/reader.cljc src/seon/program.cljc \
  resources/seon/schemas/seon.sci.eval.edn \
  resources/seon/schemas/seon.sci.kernel.edn \
  resources/seon/schemas/seon.sci.admit.edn \
  resources/seon/schemas/seon.sci.reader.edn \
  resources/seon/schemas/seon.program.edn \
  test/seon/sci test/seon/program_test.clj -- \
  seon.sci.eval-test seon.sci.eval-instrumentation-test \
  seon.sci.documentation-test seon.sci.shown-text-test \
  seon.sci.fork-isolation-test seon.sci.kernel-arm-carriage-test \
  seon.sci.admit-test seon.sci.admit.declaration-population-test \
  seon.sci.reader-test seon.program-test
```

Then `bin/test --platform`. Neither was run by this lane. No scratch root,
worktree or continuing JVM was created by this investigation.

## Follow-up: expiry ruling accepted; new await distinction condition

The orchestrator accepted `48e377e5a` and ruled option 2. Expiry must carry
the bound's declared key and elapsed milliseconds; unknown keeps its current
members and underlying failure evidence. The test-system landing note was
read end to end again, as requested. `src/seon/test.clj` and
`resources/seon/schemas/seon.test.edn` were clean and released for this exact
slice. The data-modeling skill was read for the additive observation members.
The prior alias decision is settled, not reopened.

The actual producer is `expired-result` (`src/seon/test.clj:1820`), called
only by `check` at `:1908`. Its bound is `:seon.test/check-time-limit-ms`;
`:seon.await/config-attribute` and `:seon.await/config-value` already name
the bound at the await seam. `:seon.test/elapsed-ms` currently appears as an
inline double in the check-result schema and can be declared once for reuse.

**New PRD section-6 condition:** the consumer needs a distinction the
callee's facets do not express. `check` classifies every returned complete
error as expiry. `seon.await/await!` returns completed Future values unchanged,
including ordinary test unknowns, and declares only
`[:or :seon.schema/value :seon.error/value]`. Its timeout diagnostic still
uses a retired kind and has no complete base. No timeout facet exists in
`seon.await.edn`. The permitted transitional base check detects an error but
cannot establish that this await's bound fired. Adding elapsed/bound fields
after that check would falsely classify ordinary completed failures as expiry.

At HEAD `0176024934605b7609aa4d6908b8a2296f00d317`, the
[new probe](sci-program-expiry-boundary-2026-09-21.clj) executed the actual
unknown producer, completed FutureTask, await owner and expiry producer. It
also called the actual timeout path with a never-started FutureTask and a
one-millisecond bound; no worker thread was launched. Exact output:

```clojure
:loads
{:sci-program/await-output [:or :seon.schema/value :seon.error/value]
 :sci-program/completed-error-preserved true
 :sci-program/completed-error-selects-expiry true
 :sci-program/original-unknown ":seon.test/selection"
 :sci-program/replacement-unknown "reaching selection"
 :sci-program/timeout-base-members {}
 :sci-program/timeout-cause :seon.await/backstop-fired}
```

This unarmed source probe exits 0; clj-kondo reports 0 errors/0 warnings.
It is not an armed canonical regression. The dependency idiom was checked
at `reference-code/clojure/src/clj/clojure/core.clj:7212-7214`: timed Future
get forwards to the underlying Future. The first-party distinction owner is
`src/seon/await.clj:116-120`, where the timed-get outcome is observed. Reading
isDone later would re-decide that outcome after a possible completion race.

No production/resource/test edits were made. The coherent expiry slice
cannot truthfully land before that distinction is available. The SCI/program
census above remains unchanged by this lane, with no fast tally or new reset
obligation. This is not a shared-tree load or foreign in-flight failure:
the four assigned namespaces load, and await source/resource are clean.
All held files and default remain untouched. The separate
[issue](../../../seon/issues/test-check-classifies-completed-errors-as-expiry.md)
records the defect and canonical acceptance conditions.

### Three priced continuation options

1. **Extend this lane to the existing await owner (recommended).** Authorize
   `src/seon/await.clj`, `resources/seon/schemas/seon.await.edn` and
   `test/seon/await_test.clj` for a complete timeout facet requiring its
   existing config-attribute/config-value observations. The `check` consumer
   recognizes that facet and adds its measured elapsed time to the ruled
   test expiry; completed failures retain their original evidence. Guarantee:
   the distinction is made where timed get observes it, with one await owner.
   Cost: 1–2 hours plus the remaining sweep and any required pass-through
   contract integration. Give up: keeping this slice strictly inside SCI/test.
2. **Have the await owner land that contract before this lane resumes.**
   Guarantee: the same precise distinction, with existing ownership retained.
   Cost: the same 1–2 hours of implementation plus coordination and a resumed
   verification pass. Give up: immediate independent progress on the requested
   first coherent expiry commit. No other lane was contacted or operated.
3. **Authorize a completion envelope at the await boundary.** Return explicit
   completion data separately from expiry evidence and convert its callers.
   Guarantee: arbitrary completed error values cannot be confused with await
   observations. Cost: approximately one day across callers and contracts.
   Give up: the existing direct-value await API and this bounded assignment;
   this larger change is not recommended.

The original cold scope remains owed. Option 1 adds the await source/resource
and test, plus the test expiry source/resource/regression paths, to the
orchestrator's eventual cold command. No cold or unchanged fast suite was run.
The previously recorded foreign Markdown citation errors recur unchanged.

## Await and test expiry implementation

The orchestrator authorized the await owner. Added `:seon.await/timeout-error`
with required existing config-attribute/config-value and measured elapsed-ms;
the awaited identity remains raw offending/diagnostic evidence. Channel closure
has a separate substantive operation/index facet. `await!` and its channel
helper preserve arbitrary completed values (their documented polymorphic
completion boundary declares the base), and name their own two exact facets.
No new predicate or per-facet projection was introduced.

`:seon.test/expired` now requires the observed bound and test elapsed time;
it no longer aliases unknown. `check-completion` branches on the await elapsed
member and preserves completed failures unchanged. Both SCI pass-through
unions include the new await facets. The await regression uses real futures,
promises, canonical database fixture and its projection; it validates both
distinct facets and the original unknown. Await source/tests are kind-free.

The one foreground fast request `29d134798b4d` selected `seon.await-test` with
exactly those seven changed source/resource/test paths. **0 tests executed**:
snapshot admission refused the recording authority's old
`:seon.test.runner/invalid-marker-reason-error`, which requires non-storable
`:seon.error/offending`. The actual overlay graph was
`e8cb1a8c76cfe6b393cf4b1a167ff815b1dbd56ef90d15c2373fa7fa53635411`, 72 commits
behind HEAD. 1,445 contracts armed successfully before the refusal. Evidence:
`tmp/sci-program-await-fast.log`. The held cluster caller used HEAD bytes.
No baseline publication or foreign repair was attempted, and this does not
stop the remaining sweep. Canonical fast/cold proof remains owed.

Namespace loading succeeds. Await source/resource lint is clean; test lint
resolves the new private consumer when run with its source, with zero errors.
`seon.test` retains its pre-existing shadowed-var warnings outside this slice.
The two previously recorded Markdown citation errors remain foreign.

## New section-6 decision: malformed returned error observations

Expiry landed in `603d2587c`; the required four namespaces loaded before and
after that commit. Continued inventory found a different consumer decision in
`shown-result` (`src/seon/sci/eval.clj:2460`). The existing regression
`a-returned-values-non-string-error-message-is-still-the-declared-string`
(`test/seon/sci/eval_test.clj:2454`) deliberately returns an arbitrary map with
the retired stamp and a vector-valued error message, and requires a string
`:seon.cluster.eval/error`. It is grounded in the earlier production incident,
not a marker-only test that can be silently removed.

The base schema permits an optional message, but when present it must satisfy
`:seon.error/message` (a nonempty string). The malformed return has no facet.
Adding base members and the kernel's substantive guard observation still gives
no facet; changing only the message to prose gives the kernel facet. Exact
output of the committed
[probe](sci-program-returned-error-recognition-2026-09-21.clj):

```clojure
{:sci-program/original-selects-evaluation-error true
 :sci-program/original-facets #{}
 :sci-program/base-members-with-malformed-message-facets #{}
 :sci-program/valid-message-facets #{:seon.sci.kernel/error}
 :sci-program/derived-failure-text "[:seon.ns/name user]"}
```

The probe exits 0 and loads all four owned namespaces; clj-kondo has zero
errors/warnings. Its first draft correctly refused an ambient declaration
lookup; the committed version hands the complete packaged declarations
explicitly. This is unarmed source/schema evidence, not canonical test proof.
No unchanged test namespace was rerun. An independent required-member census
of the kernel pass-through union found no equal required-member sets after
the expiry slice.

This meets §6's consumer condition: the existing behavior distinguishes an
invalid error claim from ordinary returned data, but no valid facet expresses
that distinction. Rule 1.3's transitional base check is limited to a callee
declaring `:seon.error/value`; arbitrary evaluation results have no such
contract. Facet validation changes the regression's behavior. Structural
recognition would need an explicit polymorphic-boundary ruling. The existing
[incident note](../../../seon/issues/the-over-bound-evaluation-path-returns-a-lookup-ref-where-its-contract-promises-a-string.md)
is reopened for this decision, without claiming the old string bug recurred.

### Three priced options

1. **Recognize complete declared facets only (recommended).** Treat an
   arbitrary malformed returned map as ordinary data; retain its live object
   and shown text, and revise this regression to assert that it does not set
   an evaluation failure. Keep a separate complete-facet case proving the
   failure string and evidence. Guarantee: recognition follows declarations
   without inventing a general error predicate. Cost: about 30–60 minutes
   for the consumer and canonical regression, plus the remaining sweep.
   Give up: the legacy promise that a malformed error claim itself marks a
   failed evaluation.
2. **Recognize and diagnose malformed base claims at this inspection seam.**
   Authorize a structural base-member check specifically for arbitrary
   evaluation results; construct a complete eval-owned malformed-observation
   facet with the failed schema/member and raw offending return. Guarantee:
   malformed claimed diagnostics still become explicit evaluation failures
   without weakening the base schema. Cost: 1–2 hours for declaration,
   producer, consumer and tests. Give up: rule 1.3's current restriction on
   structural recognition; the scope of this exception must be stated.
3. **Make failure an explicit evaluation outcome independent of returned data.**
   Only a thrown/refused execution or separately admitted outcome marks the
   evaluation failed; an arbitrary returned map remains a value. Guarantee:
   no inspection of arbitrary return values decides execution status. Cost:
   approximately one day to inventory and convert evaluation/status callers
   and tests, including held boundaries. Give up: this bounded sweep's scope
   and automatic failure classification for returned complete error values.

No additional production edits were made after the expiry commit. Current
literal occurrence census: eval 37 (33 matching lines), kernel 2, admit 4,
reader 2, program 11; await and test source each zero. These remaining families
are not claimed kind-free. The prior held-file handoffs remain owed, including
fn.clj and schema/edn and schema/datahike sites; none was edited.

Fast evidence remains **0 executed**, refused before execution as recorded
above. The orchestrator still owes the accumulated cold command and platform
proof. For the landed expiry slice specifically:

```sh
bin/test --paths src/seon/await.clj resources/seon/schemas/seon.await.edn src/seon/test.clj resources/seon/schemas/seon.test.edn src/seon/sci/kernel.clj src/seon/sci/admit.clj test/seon/await_test.clj test/seon/test_reaching_test.clj -- seon.await-test seon.test-reaching-test
```

No cold gate, default lifecycle command, foreign repair, or worktree was run.

## D12 and eval conversion

The orchestrator ruled option 1 (D12): only complete declared facets mark a
returned value as an evaluation error. `shown-result` now validates against
the request's carried projection. The real-SCI regression compares a complete
kernel facet with the same map carrying a malformed message: the latter keeps
its value and shown text without acquiring an evaluation error.

Eval source retirement occurrences fell from 37 to zero, resource class
declarations from nine to zero, and all four affected eval test files have
zero retirement matches. Missing row, installation mismatch, cyclic namespace
bindings, absent documentation/declaration and schema identity failures have
substantive required members. Acquisition reuses the existing row facet.
Raw offending values remain on the error observation. The two SCI pass-through
contracts name these facets. Database and call-preparation propagation sites
retain same-line named debt while those callees expose the generic union.

The one eval fast snapshot (`tmp/sci-program-eval-fast.log`, run.An97RS)
armed 1,459 contracts (1,456 program-armable), then refused admission with
**zero tests executed**. Even after `6dae626e0`, the recording authority rejects
the optional raw `:seon.error/offending` on the runner marker-reason facet as
non-storable. This is an observed foreign recording boundary, not a reason
to stop. The tally is **owed**. The four namespaces load; Clojure lint has
zero errors, with existing shadow/unused warnings outside the conversion.
Two generated diagnostic-operation fields were corrected to their actual
observer after the snapshot; no unchanged suite was rerun.

Cold eval scope owed: `bin/test --paths src/seon/sci/eval.clj
resources/seon/schemas/seon.sci.eval.edn src/seon/sci/kernel.clj
src/seon/sci/admit.clj test/seon/sci/eval_test.clj
test/seon/sci/documentation_test.clj test/seon/sci/shown_text_test.clj
test/seon/sci/eval_instrumentation_test.clj -- seon.sci.eval-test
seon.sci.documentation-test seon.sci.shown-text-test
seon.sci.eval-instrumentation-test`, plus the orchestrator's platform proof.

## Kernel conversion

Kernel source kinds 2 → 0, resource class markers 7 → 0, kernel arm test 1 → 0.
Invocation and failure-admission observations reuse the existing guard facet;
foreign-arm assertions inspect the observed arm id and operation. The pure
cause-chain reader still exposes the generic base alongside its facet union;
its existing structural pass-through check is explicitly named as debt to
`seon.error.refusal/refusal`, whose base is the target of `:seon.error/value`.
This is thrown-failure normalization, not D12 recognition of returned data.

The one kernel fast request (`tmp/sci-program-kernel-fast.log`, run.gtUEep)
refused snapshot admission at the same optional raw runner offending member.
**0 executed; tally owed.** Source/resource/test lint has zero errors;
existing style warnings are excluded from the clean conversion lint. The
required four namespaces load. Cold scope owed: `bin/test --paths
src/seon/sci/kernel.clj resources/seon/schemas/seon.sci.kernel.edn
test/seon/sci/kernel_arm_carriage_test.clj -- seon.sci.kernel-arm-carriage-test`.

## Admission conversion

Admission source kinds 4 → 0, resource class markers 1 → 0, tests 1 → 0.
The emitted-byte refusal uses the existing bound/observation facet; projection
failure requires the actual failed value's class symbol and retains that value
raw in offending evidence. Missing caps use the config facet and declared key.
Public admission and partitioned admission name the config failure they pass
through. Both SCI unions name projection failure. No per-facet EDN field was
introduced. Kernel resource whitespace from the preceding deletion is cleaned.

The single admission snapshot (`tmp/sci-program-admit-fast.log`) again refused
recording admission at the optional raw offending member; **0 executed, tally
owed**. All four namespaces load and conversion lint is clean with baseline
style warnings excluded. Cold command owed: `bin/test --paths
src/seon/sci/admit.clj resources/seon/schemas/seon.sci.admit.edn
src/seon/sci/kernel.clj resources/seon/schemas/seon.sci.kernel.edn
test/seon/sci/admit_test.clj -- seon.sci.admit-test`.

## Reader conversion

Reader source kinds 2 → 0, resource class markers 5 → 0, reader test kinds
14 → 0. The existing diagnostic producer now declares its four actual
facets. Required observations identify the unreadable member, refused token,
fabricated response keys, or observed source length/bound. A refused tag stays
a symbol; the reader-eval token stays its observed string. The pure reader
already accepts zero as a source bound, so its observed nonnegative bound is
separate from the config dial's positive-only schema. Source objects remain
raw offending evidence, not a new per-facet serialized field. Messages no
longer append source excerpts. Both SCI pass-through contracts name the facets.

The reader fast snapshot run.uxXAxl armed 1,465 contracts (1,462 program-armable)
then refused at the same runner offending-member recording boundary;
**0 executed; tally owed** (`tmp/sci-program-reader-fast.log`). Conversion lint
has zero errors/warnings (one pre-existing unused excluded-var info), and all
four namespaces load. Cold command owed: `bin/test --paths
src/seon/sci/reader.cljc resources/seon/schemas/seon.sci.reader.edn
test/seon/sci/reader_test.clj src/seon/sci/kernel.clj src/seon/sci/admit.clj --
seon.sci.reader-test`.

## Program conversion and final census at this boundary

Pure program source kinds 11 → 0. Declaration refusal requires the observed
identity attributes; no-declaration carries its byte position and examined
count; invalid bindings carry function and argument index; signature joins
carry the member and count observed at the failed seam. Arbitrary binding,
identity and schema values ride offending/evidence, never facet EDN strings.
The database override pass-through names the DB union and its two debts.
Both SCI pass-through unions name the pure program facets. The old schema
property regressions now use the real `:seon.db/attributes` property, including
its false value, rather than an error class marker.

The one program snapshot (`tmp/sci-program-program-fast.log`) armed 1,480
contracts (1,477 program-armable), then refused at the same recording authority:
**0 executed, tally owed**. The final property-assertion correction was made
while that snapshot ran; no second fast request was made. The final load and
conversion lint pass. A final eval evidence correction keeps an unqualified
namespace symbol in row-member and reports the absence as message evidence,
whose scalar grammar accepts strings but not unqualified symbols.

Counts below include source, owning resource and affected tests, against
`603d2587c`, measured by literal occurrence, not line count:

| Family | error/kind before → now | error/class before → now |
| --- | ---: | ---: |
| eval | 53 → 0 | 9 → 0 |
| kernel | 3 → 0 | 7 → 0 |
| admit | 5 → 0 | 1 → 0 |
| reader | 16 → 0 | 5 → 0 |
| program | 15 → 0 | 8 → 2 |

All assigned source/test files have zero retirement matches. The two remaining
class schemas in seon.program.edn are protocol-owned read-refused-error and
not-found-error; they are not claimed converted. The shared declaration-refused
boolean is also still emitted by the protocol. No new replacement predicate,
boolean facet marker or per-facet EDN string was added.

## New section-6 boundary: shared program protocol observations

The actual `my.program/read-result` and `supplied-context` producers return
real failures with **zero declared facets**. The pure declaration producer
now returns the complete identity-attribute facet. Exact output of
[sci-program-shared-program-facets-2026-09-21.clj](sci-program-shared-program-facets-2026-09-21.clj):

```clojure
{:sci-program/read-facets #{}
 :sci-program/context-facets #{}
 :sci-program/declaration-facets #{:seon.program/declaration-refused-error}
 :sci-program/read-member sample/f
 :sci-program/context-member :my.program/context
 :sci-program/declaration-members #{:seon.fn/sym}}
```

The probe exits 0, loads the four assigned namespaces and uses the complete
packaged declaration projection. It is unarmed source/schema evidence, not a
canonical regression. No foreign dirty source was edited. `src/my/program.clj`
and its tests are clean, but are outside this explicit ownership assignment.
The separate [issue](../../../seon/issues/program-protocol-refusals-have-no-substantive-facet.md)
records the producer/caller evidence. This is PRD §6's consumer condition:
D12's `shown-result` cannot distinguish these real library failures through
the facets currently expressed. Structural recognition is explicitly ruled
out. This is not the foreign snapshot-admission failure used as a stop.

### Three priced continuation options

1. **Extend ownership to the program protocol (recommended).** Add
   `src/my/program.clj`, its producer/consumer contracts and affected tests
   (`test/my/program_test.clj`, `test/my/program_mutation_test.clj`, and query
   tests if affected). Declare the read/context/mutation observations from
   their actual required evidence and finish the shared resource in one
   coherent slice. Guarantee: complete facets reach D12 without changing its
   semantics. Cost: approximately 1–2 hours plus canonical verification.
   Give up: keeping the assignment limited to the core SCI/program files.
2. **Have the protocol owner land that slice.** Supply this probe and keep the
   same required-member and D12 acceptance conditions. Guarantee: the same
   complete error recognition with file ownership unchanged. Cost: the same
   1–2 hours plus coordination and resumed integration. Give up: independent
   completion of the shared resource by this lane; no owner session was
   contacted or operated.
3. **Accept the core slice and explicitly defer protocol completeness.**
   Record the two class schemas and incomplete protocol producers as the
   owning lane's remaining work. Guarantee: only the converted core producers
   and their kind-free source, not correct protocol-error recognition. Cost:
   no additional code now, but the same repair and proof remain owed. Give up:
   this sweep's full resource/D12 acceptance until that follow-up lands.

## Held-owner handoffs (measured at this boundary)

- `src/seon/fn.clj`: 28 kind lines at 49, 160, 195, 199, 614, 978, 993, 1039,
  1106, 1230, 1523, 1524, 1628–1630, 1997, 2090, 2242, 2321, 2408, 2602,
  2615, 2626, 2858, 2897, 2974, 3263, 3271. In particular 1230 consumes
  declaration-at and must branch on its position/count observation. The
  publication-dissolution owner retains this file, cluster/source.clj,
  fresh_operator.clj and bin/*.
- `src/seon/schema/edn.clj`: 12 kind lines at 131, 183, 191, 221, 229, 238,
  247, 272, 287, 299, 317, 479. `src/seon/schema/datahike.clj`: 7 at 270, 281,
  304, 313, 372, 386, 528. These remain bridge-step2-walker ownership, together
  with schema.clj/internal and their dirty tests. Cluster/turn remain held.
- `test/seon/edit_test.clj:376` still asserts declaration-at's retired stamp;
  its owner must assert the requested position/examined count and operation.
- `src/seon/fn/signature.cljc` and seon.fn/seon.fn.binding resources still own
  their independent legacy failures. No edit was made there. The new pure
  program failures describe the observations made inside program.cljc.
- Error-owner complete pass-through manifests must accrete the newly declared
  eval, admission, reader and program facets, plus the await facets from the
  preceding slice. This lane updated both of its SCI unions; it did not edit
  error.clj or error/refusal.clj. Kernel retains the explicitly named generic
  cause-chain-reader debt; DB and call-preparation debts remain at eval/program
  sites until their owners narrow those outputs.

Cold program scope owed: `bin/test --paths src/seon/program.cljc
resources/seon/schemas/seon.program.edn test/seon/program_test.clj
src/seon/sci/kernel.clj src/seon/sci/admit.clj src/seon/sci/eval.clj --
seon.program-test`. The preceding per-family commands and orchestrator
`bin/test --platform` remain owed. No fast family has a green execution tally:
all five fast snapshots stopped before test execution at recording.
All named authorities were read end to end earlier in this lane. Default,
foreign sessions, foreign dirty files and cold gates remain untouched.

Final declaration review also admits string-valued file/lint identities in the
installation mismatch member, as required by program identity declarations.
That schema accretion is included in the program checkpoint; its cold scope
therefore also includes resources/seon/schemas/seon.sci.eval.edn.

The final shared-facet probe constructs the context input with
`seon.env/environment` and validates it against `:seon.env/environment` before
asserting the refusal's missing facet. It does not infer a reachable producer
failure from an invalid hand-built environment. Final output is unchanged.


## Morning continuation — protocol facets

The owner extended ownership to `my.program` and its tests. Read/context,
not-found, blocked-mutation and native-call failures now have substantive
facets; the generic `checked` helper is dissolved at its actual callers.
Database and function-owner generic returns remain inline, named step-6 debts
(`seon.db/q`, `pull`, `datoms`, `as-of`, `history`, `db`, `transact!`;
`seon.fn/gate-set`, `gate-sets`, `functions-using`).

First fast iteration: run `600f1f2a5da5`, 10 executed, 0 unchanged,
124 assertions, 7 failures, 2 errors. The new complete-facet regression passes.
SCI calls still meet the assigned call-preparation snapshot defect (its old
marker refusals fail the now-required base), which the next slice owns.
The query regression also supplied a string where program identity is a
symbol; its fixture/expectation is corrected. The snapshot failure emitted
roughly 79 MB of diagnostic output in one tool chunk; this is unreadable
failure reporting, not additional independent failures.

The four SCI/program namespaces plus `my.program` load; touched Clojure lint
is clean. No default lifecycle or foreign file was changed. Cold proof is
still orchestrator-owned. The next fast iteration includes call-preparation
and `seon.db-test`, with the protocol namespaces because their supplier
acquisition input changes.


## Morning continuation — call preparation

The previously unassigned hook family now declares six complete facets:
incoherent supplier (key, supplier, schema and expected coherence), unavailable
value (target, key, supplier and argument index), unresolved supplier (symbol),
thrown supplier (symbol and exception class), invalid supplied value (target,
key, supplier and expected schema), ambiguous call (target, count and candidate
positions). Raw invalid output is retained at `:seon.error/offending`.

`error-value` and `error-value?` are removed together with every caller.
Call preparation's own consumers inspect their required facet members;
the genuinely polymorphic supplier-result boundary uses the error owner's
complete facet validation under the acquired projection (D12). The error
owner requires call preparation, so its two inspection Vars resolve once
through delays, following its existing SCI load-cycle idiom. No copied facet
registry or per-call resolution is introduced.

Supplier coherence accepts the row's success shape and declared facet shapes.
One explicit transitional allowance remains for `seon.db/supplied-database-value`
and `supplied-connection`, whose held owner still declares `:seon.error/value`.
Database query/history results also retain named inline debts; pass-through
contracts use that owner's `:seon.db/error-result` union. `plan-for`/`plan`
only produce a plan or nil and no longer claim an error they never return.

The first combined snapshot waited for both occupied repository JVM slots;
it was stopped before starting a test JVM so the completed canonical fixture
repairs could be included. The completed slice loads and has clean lint.
The post-commit fast pass is pending below. The protocol's `overrides` reader
also normalizes a returned database failure, rather than promising an
undeclared pass-through. The large diagnostic is recorded in
[the reporting issue](../../../seon/issues/call-preparation-refusal-dumps-the-acquired-projection.md).


## Final morning evidence

| Family / namespace | Latest relevant run | Executed | Failures | Errors |
|---|---|---:|---:|---:|
| call preparation | `7f3aac1e66b7` | 17 | 0 | 0 |
| program reads | `a27611812ade` | 5 | 0 | 0 |
| program mutations | `a27611812ade` | 3 | 0 | 0 |
| program query regressions | `a27611812ade` | 2 | 0 | 0 |
| database regression handoff | `a27611812ade` | 64 | 0 | 1 |

The final call-preparation pass recorded **96 assertions**, 17 executed,
0 unchanged. The combined pass recorded 91 executed, 0 unchanged,
705 assertions, 5 failures and 7 errors; its call-preparation results are
superseded by the final pass. Its 10 program tests passed, including real
SCI calls and the complete-facet regression. No unchanged database/program
namespace was rerun for the subsequent test-only fixture repair.

The fixture repair retains the canonical helper's `:agent` admission instead
of overriding it to `:core` without the required indexed-file relation
(`seon.fn.edn:151–154`). The malformed supplier value now has an invalid
instant member, so it reaches call preparation as ordinary data; a complete
base returned from a contract declaring no error facet is rejected earlier
by instrumentation. The test proves that mere base-key presence does not
make the supplied value an error.

One admission iteration executed no tests: required ambiguity candidates
were a non-storable nested vector. The final facet records target, supplied
count and alternative count; the complete placements and arguments travel
as error evidence, with raw values at `:seon.error/offending`. No per-facet
EDN string was introduced. The final code also declares the private
`read-result` and `coherent-supplier` return unions explicitly.

The database diff/no-change, diff/refusal, replay, and arity/component cases
named in the assignment passed. The remaining wildcard-pull test failed
before its assertion, at `instrument/apply!`, with “The loaded function
contract cannot compile.” Exact evidence and the intentionally unassigned
cause are in [the rearming issue](../../../seon/issues/wildcard-pull-rearming-refuses-a-loaded-contract.md).
The error was not treated as a reason to stop the owned conversion.

R1–R8 census for this continuation: call-preparation source **2 → 0** kind
references; its tests **10 → 0**; program resource **2 → 0** class markers.
The four program protocol marker producers and general `checked` consumer
are replaced by substantive facets and per-callee branches. The new files
have zero `:seon.error/kind`, `seon.error/class`, or `error/error?` matches.
Lint is clean. Four original SCI/program namespaces plus `my.program` and
`seon.call-preparation` load before and after commits. No default lifecycle,
foreign session, held file, worktree, or cold gate was operated.

Handoffs remain: `fn.clj`'s generic gate/read return contracts; `schema/edn`
and `schema/datahike` sites listed in the earlier sweep inventory; database
suppliers' generic base return declarations; the wildcard rearming boundary;
and the error/test reporting owner's oversized diagnostic issue. Error
owners should include the new program/call-preparation facets in any enforced
canonical pass-through inventory they own. Foreign dirty `error.clj`,
`sci/eval.clj`, and `fn_test.clj` were explicitly excluded by overlay admission;
this lane tested their HEAD bytes. No store-lock retry was needed.

Cold proof owed to the orchestrator (not run by this lane):

```sh
bin/test --paths src/my/program.clj resources/seon/schemas/seon.program.edn test/my/program_test.clj test/my/program_mutation_test.clj test/my/program_query_test.clj src/seon/call_preparation.clj resources/seon/schemas/seon.call-preparation.edn test/seon/call_preparation_test.clj -- seon.call-preparation-test seon.db-test my.program-test my.program-mutation-test my.program-query-test
bin/test --platform
```

The earlier SCI-family cold commands remain owed as recorded above. The
latest raw logs are `tmp/sci-program-fast-facets.log` and
`tmp/sci-program-fast-cp-fixtures.log`; the run identities and measured
outcomes are retained here independently of those disposable files.
