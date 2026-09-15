---
type: research
status: active
tags: [research, schema, render, sci]
---

# Refusal grammar — 2026-09-15

## Implementation outcome — continuation

The approved parser extension is implemented. Structured Malli problems,
reader evidence, guard/check input and result contracts, and generic diagnostic
fields feed the existing `seon.error` grammar. The value renderer remains the
only offending-value projection seam. Database write refusal constructors
already use that grammar; no database read path was changed.

Run-11's note request now declares an existing entity reference at
`:my.note/about`; annotated union branches become one problem at that path.
Missing-key explanations select the declared child schema, so the fix asks
for a string rather than a map. The kernel now emits the declared evaluation
failure marker (`true`) and deadline marker (observed function entries), rather
than `:evaluation`; the old shape bypassed the error pair entirely.

The registry-derived catalog check now supplies the registry to Malli's
`:gen/schema` generator. Once that check could run, it found seven absent
schema messages and one undeclared render-reason attribute. Those declarations
are repaired in place. No class list was added.

Focused fast verification: **17 tests, 4,901 assertions, zero failures/errors**.
The complete subject gate passed: **114 tests, 5,547 assertions, exit 0**, with durable result recording. The wording follow-up passed **46 tests, 317 assertions, exit 0**; the final enum/flow/error gate passed **36 tests, 162 assertions, exit 0**. The platform run before that wording change passed **85 tests, 523 assertions, exit 0**; the final platform gate also passed **85 tests, 523 assertions, exit 0**, with recorded results.

### Live evidence after the changes

The [read-only script](refusal-grammar-final-probe-2026-09-15.clj) and
[returned envelope](refusal-grammar-final-probe-2026-09-15.json) exercise default
PID 69622 through MCP JVM mode with explicit connection and projection. The
completed combined probe took 6,537 ms. The final probe carries the projection on the database value, as the concurrent P1 development code now requires. The reader's run-7 refusal is **107
estimated tokens**, names `my.plan/current!`, proposes `:my.plan.item/id`, and
includes its real docstring example. Four contiguous prose lines produce
**one refusal plus the following valid form**; its refusal is **70 tokens**
and names `Gate:`. A generic `seon.db/pull` diagnostic is **49 tokens**.
`doc seon.db/pull` retains three ordered input alternatives and three
`[:or :nil :map :seon.error/value]` outputs, without generator bodies.

The [kernel probe](refusal-grammar-kernel-probe-2026-09-15.clj) and
[envelope](refusal-grammar-kernel-probe-2026-09-15.json) validate the constructed
unresolved-symbol value against its installed error schema and render it in
**65 tokens** (5,543 ms). This is a constructor proof; the canonical real-SCI
regression separately verifies stored shown text. A further live violation
probe took 4,283 ms: both semantic coordinates reference the identical
offending object and `:seon.instrument/args` is absent. The historical serialized
copy was already dissolved at `6acd8818e`; it was not reimplemented here.

### Exact remaining boundaries

Current member dispositions (the table later in this note is the pre-edit audit):

| Member | Disposition |
|---|---|
| Reader token/enclosing contract | Resolved; exact run-7 source and live grammar verified. |
| Documentation contract bodies/arity alternatives | Resolved; declared Malli positions and named output alternatives verified. |
| Duplicate serialized offending argument | Superseded; actual-object retention already replaced that storage model. |
| Six-word error expansion | Superseded; September 15 grammar and token-profile acceptance replace the old character target. |
| One prose mistake becoming many reader errors | Superseded; parser recovery resolved, opening teaching retained as a named residual. |
| Error catalog/renderer disagreement | Derived check repaired; broader discriminator migration remains open. |
| Blocked-plan projection refusal | Open; no nonempty live subject is present, protected rendering owner untouched. |

- The catalog's broader kind-to-shape migration remains open; this change
  proves the derived declaration/renderer check, not that migration.
- Default currently has zero blocked items for both `juniper` and `root`
  (read-only probe, 2,906 ms). This is **not** a successful reproduction of
  the historical blocked-plan renderer defect. The protected value renderer
  was not edited.
- The prose recovery mechanism is fixed; the opening's comment instruction
  remains in protected `src/seon/cluster/instruction.clj` (wording below).
- A live documentation probe exposed an independent EDN/arglist mismatch,
  recorded in [documentation-arglists-with-auto-keywords-are-not-edn](../../../seon/issues/documentation-arglists-with-auto-keywords-are-not-edn.md).
  Refusal examples reuse docstring extraction without decoding arglists.
- The isolated verification base is `caddf111b` plus only this lane's paths.
  Instrumentation arming, database reads, and all other concurrent edits are
  excluded. This is not integration proof for the shared in-flight tree.

The original investigation and design-gate record below is historical; its
statements that no production code changed describe that earlier checkpoint.

## Landing commits and verification boundary

- `63373df6ab056d86d106a79caecc2ebfca18d357`: Edamame dependency evidence extension (local dependency commit).
- `a24ad11b7`: parent dependency selection and gitlink.
- `a65985098`: shared grammar, request shape, parser integration, declared catalog repairs and regressions.
- `35531f67e`, `f000669b0`, `1fd81b2be`: missing-enum regression and final schema-derived enum wording. The final form at `1fd81b2be` preserves concrete alternatives, phrases them directly from schema values, and does not parse Malli messages.

The dependency commit is present locally and selected by the parent gitlink;
this lane did not publish it to a remote fork. A fresh remote clone needs that
commit published before the parent can be distributed.

The implementation commit was made with the verified worktree as Git's
work-tree input and an explicit path list. The shared index's unrelated staged
notes were excluded. The main tree's concurrent instrumentation-arming hunk
remains present and uncommitted. Subsequent P1 edits to SCI acquisition and
documentation projection carriage were also preserved.

The first isolated subject run passed its assertions but could not persist
results because this new worktree had no `current-src`. The lane initialized
its own source publication, then reran the canonical gate successfully. One
platform run exposed a real formatter defect: a writer can already supply the
missing field's scalar schema, whereas Malli supplies its parent map. The
formatter now selects a child only from a map. The same class regression
includes a sparse function write. A subsequent live probe exposed the enum
wording issue finalized by `1fd81b2be`. A generic enum sentence briefly hid the workload choices; the platform check caught it, and the final formatter enumerates the declared values directly. These failures were not attributed to a
foreign lane.

Default's final live function probe succeeded. Full adoption convergence is
**not** established: the 4,476-ms comparison at 20:44Z found adopted commit
`6aa99c9f-cb2c-5412-8586-cf05d9b024e9` and published commit
`6aa9adba-7fa8-5277-adf4-942772e4385a`. The lane's explicit adoption client
(PID 3189, exact command `init --dev default --changed src/seon/error.clj`)
reported request acceptance but no completion for nearly eight minutes. The
lane terminated that client only; default PID 69622 was never stopped,
reforked, or restarted. The existing adoption-timeout issue is concurrently
edited and was not changed by this lane. The source commit comparison is a
read-only observation, not a lifecycle operation or browser-paint proof.

### Final gate and cleanup record

The owner's 21:05Z orchestrator-only test rule arrived after these gates.
No further lane test invocation was made. The requested namespace list and
`platform` are submitted in `tmp/orchestrator/gate-requests/refusal-grammar.txt`;
the orchestrator's batched integration gate remains pending. This lane does
not wait for that gate.

Post-restart focused `clj-kondo --lint` over `src/seon/error.clj`,
`src/seon/sci/reader.cljc`, `test/seon/refusal_grammar_test.clj`,
`test/seon/sci/reader_test.clj`, and `test/seon/contracts_plan_test.clj`
reported zero errors and 52 warnings (exit 2; shadowed names and unused
private vars). No post-rule test run was launched.

The [verification manifest](refusal-grammar-verification-2026-09-15.json)
records every selected path, the exact commands, final file hashes, dependency
commit, counts and exit codes. The full subject run passed 114 tests / 5,547
assertions in 130 coordinator seconds. The final enum/flow/error follow-up
passed 36 / 162 in 93 seconds. The final platform run passed 85 / 523 in 128
seconds. All three exited 0 and recorded their results. One obsolete platform
invocation was explicitly cancelled after its snapshot was superseded by the
last wording edit; it is not counted as proof.

Before cleanup, the lane root held 3,205,512 KiB. Its operator status showed
0/0 live clusters and no orphan JVMs; the process table showed no JVM holding
that worktree. The lane's test sessions and adoption client had all exited.
The worktree, its result stores/caches, and `tmp/refusal-adoption.log` were
removed after preserving the evidence above. The dependency checkout was
preserved. Default remained PID 69622.

Five member notes were archived, two broader member notes retain their open
residuals, and the opening and arglist issues are separately recorded. The
owner's issue schedule was not edited. Markdown hook feedback still names
foreign gitlink-citation errors in existing audit notes; no unrelated
note was modified to silence them.

### Implementation paths

- `deps.edn`
- `reference-code/edamame`
- `resources/seon/schemas/my.note.edn`
- `resources/seon/schemas/seon.context.edn`
- `resources/seon/schemas/seon.error.edn`
- `resources/seon/schemas/seon.render.unknown.edn`
- `resources/seon/schemas/seon.render.web.edn`
- `resources/seon/schemas/seon.sci.eval.edn`
- `resources/seon/schemas/seon.sci.reader.edn`
- `resources/seon/schemas/seon.test.run.edn`
- `src/seon/error.clj`
- `src/seon/instrument.clj`
- `src/seon/sci/eval.clj`
- `src/seon/sci/kernel.clj`
- `src/seon/sci/reader.cljc`
- `test/seon/contracts_fixture.clj`
- `test/seon/contracts_plan_test.clj`
- `test/seon/error_class_schema_test.clj`
- `test/seon/error_test.clj`
- `test/seon/refusal_grammar_test.clj`
- `test/seon/sci/documentation_test.clj`
- `test/seon/sci/reader_test.clj`

Dependency source: `reference-code/edamame/src/edamame/impl/parser.cljc`.

## Owner-authorized continuation

Option 1 is authorized on 2026-09-15, including run-11 reference, auto-check,
and contiguous unreadable-span cases. Before implementation, the repeated
`rg -n '^tags:.*(contracts|errors)' docs/seon/issues/*.md` query at
`ff60a3cf7` finds only `blocked-plan-values-refuse-pull-during-ai-projection.md`.
The dated set is the six members in the table below plus
`a-prose-line-without-a-comment-marker-becomes-one-error-per-word.md`.
The broader catalog discriminator migration and blocked-plan pull behavior
remain separately scoped residuals; this continuation owns their refusal text.

Default PID 69622 answered the health probe (all three plumbing procs replied).
A read-only JVM reader probe reproduced run 7 in 5 ms: the error retains line
1, column 36 and the source but no invalid-token or enclosing-call evidence.
The implementation will extend Edamame's existing delimiter loop's exception
data, retain that data in Seon's existing reader events, and derive correction
from the called function's declared map entries. No second parser is added.
The dependency will be selected explicitly in deps.edn; changing its reference
checkout alone is not executable evidence.

### Continuation checkpoint — 20:06Z

Edamame commit `63373df` retains `:edamame/token`, token kind and the enclosing
collection elements in its existing delimiter loop. `deps.edn` now selects
that checkout explicitly. The fresh-JVM reader regressions passed. A default
JVM hot load of that parser (no lifecycle operation) and the adopted Seon
reader/error functions produced this exact 67-estimated-token text:

```text
my.plan/current! refused source at [1 36]: expected readable Clojure source (:seon.cluster.eval/source), got unreadable source ":my.plan/item/id". Fix: Use :my.plan.item/id. Example: No docstring example is available.
```

Subsequent work adds the called function's example through the existing
documentation owner. A read-only default schema query confirmed the run-11
attribute's two failed union branches and its two supplied map entries.
Malli's `subschemas` supplies the union's shared value path, so one declared
explanation replaces branch-by-branch guesses.

The first fast run was 90 tests / 612 assertions, three failures, zero errors.
The old generic-render assertion required a raw map dump; the new run-11 case
also exposed a pre-existing fixture omission: `contracts-fixture/with-agent`
applied config but never seeded a cluster, leaving call preparation without
its environment. It now calls the canonical `seed-cluster!` helper. The third
fast run passed all four contracts-plan tests, including run 7 and run 11;
its only remaining failure expected vector notation for Malli's actual list
of generated arguments. The assertion now preserves the actual input shape.
The isolated subject gate is running against worktree HEAD `caddf111b`, with
only owned paths overlaid. This is not yet a passing gate claim.

The opening's comment sentence is in `src/seon/cluster/instruction.clj`, now
under the concurrent classification lane's edits. It is protected. Needed
wording: “Prose lines are comments only when every line starts with `;`; a
comment ends at the newline.” This lane leaves that file's bytes to its owner.
Instrumentation's arming hunk is also excluded from the test overlay; only
the report/grammar hunks belong to this lane.

## Original design-gate result

Design-gate checkpoint, not a completed class repair. No production code was
changed and no member is closed by this checkpoint. The exact run-7 source
still cannot produce the requested schema-derived correction from the parser
facts it supplies. The dependency does not supply the token or enclosing call;
this is not merely Seon dropping those fields.

The assignment explicitly requires stopping before production edits when the
kill entails hours of cross-owner work. The options below price the dependency,
reader, schema-context, and renderer work. Default was never stopped, reforked,
restarted, or evaluated through shared SCI mode. No provider request was made.

## Class and structural kill

Class: an agent-visible refusal loses its actionable coordinate or expands
internal evidence instead of explaining the correction.

Proposed guarantee: every refusal renders through `seon.error` from retained
structured evidence, with explicit unknowns when evidence is unavailable, and
only the value renderer projects offending values under the render profile.

The mining report's N5 row specifies one evidence-complete flat constructor;
N1 specifies one total outward render construction; N12 specifies documentation
derived from installed schemas. This assignment combines the refusal-text
portion of those boundaries. The archived N5 class note is already resolved;
its earlier closure does not prove this stronger grammar requirement. The N1
and N11 class notes still cover broader work and cannot be closed by this lane.

Read the repository AGENTS.md, issue README and localized issue instructions,
the four explicitly named member notes, the reader-context member, the tagged
blocked-plan member, and both named September 15 landing notes. Consulted the
mining report's structural-kill rows and the active roadmap. Applied the
data-oriented-clojure, repl, datahike, and clojure-testing skills. The full
historical N5 member re-audit was not completed before this design gate.

## Dated member enumeration before implementation

Command: `rg -n '^tags:.*(contracts|errors)' docs/seon/issues/*.md`.
At inspected HEAD `22893b71383cec23c8763df7da841524259a1774`, the only
top-level tag match was `blocked-plan-values-refuse-pull-during-ai-projection.md`.
The explicit assignment adds four notes regardless of their older tags; the
schema-audit landing adds the reader-context residual. The reviewed set is:

| Member | Current evidence and verdict |
|---|---|
| [error-class-catalog-and-renderers-disagree](../../../seon/issues/error-class-catalog-and-renderers-disagree.md) | Open. `test/seon/error_class_schema_test.clj` already derives class keys from schema properties and asserts nonempty subjects. The member's acceptance also requires deleting `:seon.error/kind`; that remains in `src/seon/error.clj:227,319,339` and `resources/seon/schemas/seon.error.edn:45,119,214`. A grammar change cannot truthfully close that broader migration. No fresh class gate was run. |
| [a-six-word-eval-error-renders-as-two-thousand-characters](../../../seon/issues/archive/a-six-word-eval-error-renders-as-two-thousand-characters.md) | Open. `src/seon/error.clj:1466` still falls back to `pr-str` of the full map when no semantic problem exists. The member's real unresolved-symbol turn measurement was not repeated before the design gate. `src/seon/cluster.clj` and `src/seon/render/value.clj` had foreign in-flight edits at entry. |
| [contract-evidence-carries-the-offending-argument-twice](../../../seon/issues/archive/contract-evidence-carries-the-offending-argument-twice.md) | Historical serialized-args construction changed already: `src/seon/instrument.clj:278–380` retains semantic problems; `test/seon/instrument_test.clj:403,496` asserts semantic data and actual-object identity. The old construction-size acceptance has been replaced by an object-retention/allocation test. A diagnostic-offending reference and problem-offending reference still coexist. No closure claimed without the live and gate checks. |
| [doc-contract-lines-print-schema-bodies-and-flatten-arity-alternatives](../../../seon/issues/archive/doc-contract-lines-print-schema-bodies-and-flatten-arity-alternatives.md) | The cited role-contract-lines owner is gone; `src/seon/sci/eval.clj:1149–1237` now returns structured documentation. `f879b0ea6` accreted supplied-key documentation. The supplied-keys landing still shows expanded output schema bodies, so the whole member is not proven dissolved. No current doc/pull live proof completed. |
| [blocked-plan-values-refuse-pull-during-ai-projection](../../../seon/issues/blocked-plan-values-refuse-pull-during-ai-projection.md) | Included by the requested tag query. Its underlying pull/render failure is distinct from explaining a valid refusal. Source ownership includes protected value-render and plan owners. The historical observation is not a fresh reproduction; leave open. |
| [reader-refusals-drop-the-invalid-token-and-enclosing-contract](../../../seon/issues/archive/reader-refusals-drop-the-invalid-token-and-enclosing-contract.md) | Reproduced live below. The stronger root cause is missing Edamame evidence, before Seon's error projection. Leave open. |

These are verification statuses, not six independent patch assignments.

## Dependency and owner ledger

- Edamame `reference-code/edamame/src/edamame/impl/parser.cljc:44–63`
  constructs exception data. `:638–661` holds the invalid keyword token but
  passes only prose to `throw-reader`. `:617–636` loses the unfinished map
  when child parsing throws. Dependency identity was checked with
  `git ls-tree HEAD reference-code/edamame`.
- SCI's dependency manifest `reference-code/sci/deps.edn:2` selects Edamame
  1.6.42. Editing the reference checkout alone would not establish that the
  running artifact changed; dependency selection and fresh-process proof are
  required for a parser extension.
- Malli `reference-code/malli/src/malli/core.cljc:2204–2222` passes compiled
  input/output/guard schemas and actual values to report. Guards are checked
  after function execution. The existing instrument owner already calls
  `m/explain` on these values; do not parse error messages or move validation.
- `src/seon/sci/reader.cljc:630` projects reader failure data; its
  `cause-data` and `parse-classification` already inspect structured Edamame
  evidence. Accrete there instead of adding another parser.
- `src/seon/error.clj:715,800,1460` owns semantic explanations and rendering.
  `refusal-data` handles problems, reader source, and incomplete authored
  schemas, but does not turn all generic diagnostic fields into problems.
- `src/seon/db.clj:2582–2621,2921` already constructs write problems and
  delegates their projection to the error pair. Preserve writer admission.
- `src/seon/instrument.clj:249–277` already explains lookup-ref vectors and
  runtime-supplied entries; `f879b0ea6` owns that accretion. Preserve arming.

## Live evidence

Default PID 69622, PREPL 55914. Initial runtime status reported all three
plumbing procs replying. MCP JVM session `refusal-grammar`, explicit root
`/Users/sean/src/seon`, cluster `default`, obtained its connection with
`(seon.operator/connection "default")` and handed the database projection.
The completed reader probe took 1,016 ms inside the JVM.

Exact source:

```clojure
(my.plan/current! {:my.plan/item/id "juniper/define"})
```

Deepest dependency exception data, read from the cause chain:

```clojure
{:type :edamame/error :line 1 :column 36}
```

No token, enclosing form, map position, or called function is present. The
two enclosing SCI wrappers add parse phase/location; Seon's wrapper adds
failure-start 0, empty partial-events, and namespace reading state. None
adds the missing call structure.

Exact rendered text, 300 UTF-8 bytes (ASCII byte count of the returned text):

```text
seon.sci.reader/read refused source at [1 36]: expected readable Clojure source (:seon.cluster.eval/source), got unreadable source "(my.plan/current! {:my.plan/item/id \"juniper/define\"})". Fix: Correct the reader error: Invalid keyword: :my.plan/item/id. Example: No docstring example is available.
```

This is a JVM reader/error-render proof, not a full agent-turn proof or an
adoption-convergence claim. The first probe's direct `:t` lookup returned nil;
it is not a usable database-basis measurement.

The [saved expanded probe](refusal-grammar-probe-2026-09-15.clj) adds a generic
diagnostic comparison and token estimates. Its load-file call timed out at
20,000 ms; no generic comparison or token estimate is claimed. The existing
[default probe issue](../../../seon/issues/default-component-probe-times-out-after-adoption.md)
records the timeout. No alternate transport or lifecycle workaround was used.

## Three priced options — owner decision

Estimates are engineering effort, excluding machine-slot queues.

1. **Minimal parser evidence extension — recommended, 4–8 hours.** Extend
   Edamame's existing exception data with the invalid token and enclosing
   parsed call/map coordinates; preserve it through the Seon reader. Resolve
   the call against the supplied program/schema context, suggest only a
   uniquely supported declared key, and feed the existing error grammar.
   Guarantee: run-7's direct call obtains `:my.plan.item/id` from its declared
   request shape; ambiguous context is explicit unknown. Cost includes the
   dependency artifact/pin, reader schemas/callers, canonical regression and
   fresh-process verification. Give up guesses for arbitrary malformed macro
   forms. Finish the other refusal paths in the same error owner.
2. **Keep parser facts unchanged, 2–4 hours.** Complete generic diagnostic
   grammar and regression consolidation, retaining location/source plus an
   honest syntax fix for reader failures. Guarantee: no invented schema
   correction. Give up the exact run-7 `Fix: Use :my.plan.item/id` acceptance;
   the reader member and class remain open. This explicitly reduces scope.
3. **General partial-form parser evidence, 2–3 days.** Extend the dependency
   with structured partial forms across nested lists/maps, reader macros,
   aliases, and recovery, then resolve known contracts through that evidence.
   Guarantee: nested contexts retain their parser provenance and uncertainty.
   Cost: a broader dependency API, recovery regression surface, artifact
   integration, and Seon admission/render integration. Give up a small patch
   and fast landing; still never guess ambiguous corrections.

The catalog's kind-to-shape migration remains separately owned under all
three options; a derived grammar/catalog check does not complete it.

## Original design-gate verification

No production edits, test additions, test invocations, or scratch roots were
created before the required design stop. Therefore there is no subject or
platform gate result and no claim that the class is fixed. The evidence
script's initial require/namespace lint errors were corrected to a script
require form. Markdown lint reported a foreign gitlink citation mismatch in
`p1-ambient-state-2026-09-15.md`; that path was not edited.
All shell commands exited; this lane owns no background shell or process.
Foreign edits and sessions were preserved.
