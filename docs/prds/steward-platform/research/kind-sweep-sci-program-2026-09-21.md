---
type: research
status: blocked
created: 2026-09-21
tags: [error-model, kind-retirement, sci, program]
---

# Kind sweep — sci-program

## Result

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
