---
type: research
status: active
created: 2026-09-20
tags: [publication, test-system, wave/publication-velocity]
---

# Publication dissolution — decision checkpoint

Items 1–4 are **not complete**. The production tree contains the ruled publication-analysis exclusions.
A tested declaration-closure prototype is preserved in the research patch linked
below; publication/reconciliation and persistent caching remain unimplemented. No publication speedup is claimed. The sections
below retain the earlier decision evidence. Item 5 remains outside this assignment.

## Decision: what constitutes an analysis input?

The [binding specification](../plan/publication-dissolution-spec-2026-09-20.md)
requires both N-file work and analysis cached by a file's content digest.
That digest alone cannot identify a complete clj-kondo result: a caller's
findings and resolved usage metadata depend on the callee's declaration.
This matters before replacing the existing complete-analysis fallbacks in
item 1, not only when adding the persistent cache in item 3.

The native dependency probe changed `f` from one argument to two while
leaving the caller's SHA-256 unchanged. clj-kondo v2026.07.24 returned:

| Analysis | Invalid-arity findings | Elapsed |
|---|---:|---:|
| Original callee and caller | 0 | 42 ms |
| Changed callee alone | 0 | 44 ms |
| Changed callee and unchanged caller | 1 | 47 ms |

The new finding is `publication-probe.callee/f is called with 1 arg but
expects 2`. Reusing the original caller analysis by its own digest would
retain zero findings. This is a native dependency probe, **not** a canonical
armed Seon regression or a publication benchmark. It ran once, launched
zero JVMs, and removed its private temporary directory on exit.

Reproduce from the checkout:

```sh
python3 docs/prds/steward-platform/research/publication-analysis-input-probe-2026-09-20.py
```

## Dependency ledger and first-party seams

- Vendored clj-kondo reads `called-fn` arities and compares them with the
  caller's arity in `reference-code/clj-kondo/src/clj_kondo/impl/linters.clj:602`;
  it emits `:invalid-arity` at line 649 and checks privacy at line 660.
- `src/seon/fn/analyzer.clj:122` retains callee arities, privacy and macro
  information in a usage. `invoke-kondo` at line 245 uses the dependency's
  namespace cache; `analyze` at line 373 is the existing analysis owner.
- `src/seon/fn.clj:2033` analyzes one artifact; `build-manifest` at line
  2150 analyzes all captured inputs together. The manifest digest hashes
  file paths and source digests in `manifest-data`, not the resulting
  findings. Equal manifest digests alone therefore do not establish
  incremental/full-analysis parity.
- `src/seon/cluster.clj:2240` begins the existing incremental publication
  owner in the inspected working tree. Structural changes currently reach
  complete analysis. The replacement must preserve the relevant diagnosis
  while deleting that whole-publication fallback.
- The lineage repair remains separately mandatory: `publish!` in
  `src/seon/cluster/source.clj` branches from `:db`, whereas
  `record-results-at-head!` branches from the current commit. Datahike's
  `force-branch!` (`reference-code/datahike/src/datahike/versioning.cljc:323`)
  changes the supplied value's branch and parents; it does not merge the
  prior history's datoms. `seon.fn/index!` currently refuses an existing
  program unless handed `:seon.source/previous-database`.

## First decision — settled by the caller-invalidation ruling

Estimates below are rough additional implementation effort, not measured
runtime or delivery promises.

1. **Recommended: invalidate affected analysis when declaration interfaces
   change.** Ordinary body edits analyze the changed files; interface edits
   additionally revisit affected callers using declared analysis dependencies.
   Guarantee: retain complete-analysis correctness, with no unconditional
   whole-program rebuild. Cost: approximately 1–2 additional lane-days for
   dependency fingerprints and parity regressions. Give up: a literal
   N-files ceiling for interface changes. Config and analyzer changes also
   have to participate in the fingerprint; an unavailable dependency cannot
   be interpreted as no dependency.
2. **Constrain the N-file path and refuse unsupported changes.** Admit only
   edits whose analysis dependencies remain identical; explicitly refuse
   interface, schema or analyzer-input changes outside that constraint.
   Guarantee: a reused finding never claims unverified current analysis.
   Cost: approximately half a lane-day for admission checks, with broader
   publication still deferred. Give up: automatic publication for those edits.
3. **Separate cached parsing from cross-file resolution.** Retain per-file
   syntax evidence and recompute affected resolution and findings through
   the existing analyzer/indexer owners. Guarantee: N changed source files
   are parsed; dependent facts are still revisited as needed. Cost:
   approximately 3–5 additional lane-days and a larger analyzer change.
   Give up: the bounded scope and literal N-files total-work guarantee.

The stop is required by AGENTS.md §2.5: “when a decision would create hours
of cross-owner work or its guarantees cannot be stated simply, STOP before
production edits”. The binding assignment separately directs this lane to
stop at a genuine decision with three priced options.

Issue: [a file digest does not identify complete caller analysis](../../../seon/issues/a-file-digest-does-not-identify-complete-caller-analysis.md).

## Ownership and verification boundary

Started on `steward-platform`, HEAD `83c32bfc7`. During inspection another
lane advanced HEAD to `44b51bab0`; its source evidence transfer edits became
committed. No foreign session was operated or messaged.

At the last ownership check, `src/seon/cluster.clj`,
`src/seon/test/cache.clj`, and `bin/test` remained dirty. The explicitly held
cluster, turn and schema owners remain outside this lane's edits. No
worktree, scratch cluster, background shell, test suite, default operation,
or cold gate was started. No production paths have been deleted.

Read the binding specification end to end, the supplied AGENTS.md sections
1–3 and lane rules 11–16, the requested working-edge tail, the step-3 census
and required-production-change sections, and both named issues. Read the
launcher, hook, cache, source and fresh operator; inspected the named cluster
publication functions. A whole-file read of unrelated cluster sections and
the remainder of the results-reuse research note is still outstanding.

The spec's before numbers (approximately 200 seconds JVM work, including
27 seconds analysis and 64 seconds population) are inherited evidence, not
measurements from this checkpoint. One-file and complete-publication
before/after phase measurements, the lineage/reuse regression, and item-2
scratch-root verification remain owed after the decision and implementation.

The orchestrator's eventual cold proof is the landed implementation's exact
owned paths with `bin/test --paths … -- seon.test.cache-test
seon.cluster.source-test seon.test-runner-test seon.fn-test`, plus affected
namespaces and `bin/test --platform`. This is an owed command template;
there is no implementation path set or green tally yet.

## Resume: caller invalidation settled; body-dependent findings remain

Resumed at `a962d75cd`. The owner accepted option 1: use published calls and
references to invalidate the caller closure when public declaration interfaces
change. That decision is settled. Read the revised specification end to end.
`cache/compatible-changes` compares input digests; the reverse walk is
`src/seon/test/selection.clj:91–105`, over calls, references and test subjects.
Overlay admission at `:131` uses a narrower direct-call check. Publication
should share the existing graph computation. The results-reuse note identifies
selection-tx, claim-tx, completed-tx, terminated-tx and covered-by: their
original temporal history and entity identities must survive publication.

A second decision remains: clj-kondo infers return types from function bodies,
and those types change findings in unchanged callers. The new probe keeps
name, arity and an explicit Malli contract identical:

```clojure
(defn f {:malli/schema [:=> [:cat] [:or :int :string]]} [] 1)
;; Only the last expression changes to "text".
(defn g [] (inc (c/f)))
```

The caller's bytes and the callee's analyzed declaration metadata remain
identical. Native clj-kondo returns:

| Analysis | Type-mismatch findings | Elapsed |
|---|---:|---:|
| Original callee and caller | 0 | 43 ms |
| Body-changed callee alone | 0 | 36 ms |
| Body-changed callee and unchanged caller | 1 | 38 ms |

The finding is `Expected: number, received: string.` There are no error-level
findings. Reproduce with:

```sh
python3 docs/prds/steward-platform/research/publication-body-analysis-probe-2026-09-20.py
```

This is dependency evidence, not an armed publication regression. An initial
exploratory probe also observed the warning but had namespace-path errors;
the committed reproducer corrects those paths and asserts no errors. Neither
probe launches a JVM. No test suite was rerun.

Vendored `reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:877–898`
derives the return tag from the final body expression; `impl/types.clj:448–477`
defers cross-file resolution; `impl/types.clj:951–969` emits the finding.
Seon preserves type-mismatch as a warning (`src/seon/fn/analyzer.clj:22–26`).
Thus the ruled pair of file digest and declared-interface digest remains
unchanged for the caller while its complete-analysis finding changes.
Program digest equality alone cannot prove finding equality.

### Second decision — settled: exclude inferred type findings

Additional effort estimates, excluding the remaining implementation:

1. **Recommended for the N-file priority: disable type-mismatch findings in
   canonical publication analysis, complete and incremental.** Guarantee:
   this body-dependent finding class cannot go stale; name, privacy and arity
   admission remain. Cost: approximately half a lane-day for the policy change
   and parity coverage. Give up: clj-kondo type-mismatch warnings, including
   local ones. Other finding classes still require parity regressions; this
   probe does not establish their independence.
2. **Include analyzer-inferred interfaces in invalidation.** Treat a changed
   inferred return type as an interface change and propagate through the
   published caller/referrer graph. Guarantee: retain these warnings through
   caller reanalysis. Cost: approximately 1–2 additional lane-days for analyzer
   export fingerprints and propagation proofs. Give up: the N-file ceiling
   for body edits that change inferred interfaces.
3. **Recompute cross-file type findings on every publication.** Cache source
   analysis but retain a complete type-resolution pass as the findings owner.
   Guarantee: fresh type findings without declaration-only cache keys. Cost:
   approximately 1–2 additional lane-days to separate analyzer phases, plus
   recurring whole-program resolution work. Give up: N-file total work.

AGENTS.md §2.5 says to stop before production edits when guarantees cannot
be stated simply; the assignment requires a stop at the next genuine decision.
This is that boundary, not a stop for foreign breakage. Items 1–4 remain
unimplemented; no publisher paths were deleted.

### Scratch observation, ownership and verification

The authorized scratch start refused `seon.shell/stdin?` during namespace
boot. This used the shared working tree; no individual foreign edit is blamed.
Preflight: 22,213 ms (dependency warming 17,882 ms; 43 files linted in
4,328 ms). Its second preflight: 102 ms, zero files. Start failed after
20,034 ms. These are boot measurements, not publication before/after figures.
The [scratch-boot issue](../../../seon/issues/scratch-boot-refuses-the-shell-stdin-predicate.md)
records the boundary and retained logs.

`bin/seon --root tmp/publication-root down` confirmed zero recorded JVMs and
a free store flock. PID 74353 was absent. The root was deleted without
following symlinks. The shared default root was never operated. No worktree
or cold gate ran. A foreground load of `seon.fn` and `seon.cluster.source`
returned `:loads`, exit 0 (`tmp/publication-dissolution/decision-load.log`).

The launcher/cache slice subsequently landed as `a6fbf412b`, releasing those
paths. Explicitly held cluster/turn/schema paths and dirty
`test/seon/fn_test.clj` remain untouched. The specification, named cluster
publication functions, launcher, hook, cache, source, operator, requested
working edge, census and issues have been read; the results-reuse note was
finished during this resume. Unrelated sections of the full cluster file
remain unread; no production edit was made before finishing grounding.

The spec's publication measurements, lineage/reuse regression, live-JVM proof
and cold proof remain owed. The actual cache test namespace is
`seon.test-cache-test`; the earlier cold-command template's dotted spelling
does not match its declaration.

## Ruled publication-analysis policy implemented

The owner accepted option 1 at `ad2fe8554`: exclude inferred type findings
at the publication analyzer's configuration seam. `publication-config` in
`src/seon/fn/analyzer.clj` derives from the existing analysis config and is
passed to kondo by `analyze`. There is no output filter. The existing
`analyze-forms` path retains its current policy; this change is specifically
publication analysis.

The source audit disproved the claim that type-mismatch is the only
body-dependent finding class. The seven exclusions are:

| Excluded class | Vendored clj-kondo source and input |
|---|---|
| `type-mismatch` | `impl/types.clj:951–969`, inferred argument types |
| `redundant-str-call` | `impl/linters.clj:247–256`, resolved argument tag |
| `is-message-not-string` | `impl/linters.clj:223–239`, resolved message tag |
| `redundant-primitive-coercion` | `impl/linters.clj:258–272`, resolved argument tag |
| `equals-float` | `impl/linters.clj:274–281`, resolved argument tags |
| `not-empty?` | `impl/linters.clj:282–294`, resolved sequence tag |
| `constant-condition` | `impl/linters.clj:387–399`, deferred call return type |

All paths in this table are beneath `reference-code/clj-kondo/src/clj_kondo/`.
These exclusions include local instances of the same classes; they are
declared class policies, not a post-analysis attempt to guess provenance.

One native census of the shared tree, before applying the exclusions, read
**372 files in 12,778 ms**: **0 errors, 662 warnings, 12 info findings**.
Exactly **16** findings belong to excluded classes: type-mismatch 9,
redundant-primitive-coercion 4, redundant-str-call 1, constant-condition 2;
the other three have zero. This is a dated observation, not a recurring
whole-program census added to publication. The command was:

```sh
clj-kondo --cache false --lint src test --config '{:output {:format :json} :linters {:type-mismatch {:level :warning}}}'
```

Raw output is retained compressed at
`tmp/publication-dissolution/finding-census.json.gz`. Its exit 2
reports warnings; this was not a Seon gate or a database publication.

### Findings retained and dependency audit

Every configured class except the seven above remains enabled according to
the existing config. The census's retained classes and their owners are:

| Retained class (observed count) | Source beneath the vendored `clj_kondo/` directory |
|---|---|
| `aliased-referred-var` (1) | `impl/linters.clj:754–778`, namespace bindings and usage |
| `duplicate-require` (4) | `impl/namespace.clj:18–28`, require declarations |
| `redundant-declare` (1) | `impl/analyzer.clj:2147–2153`, declaration order |
| `redundant-do` (1), `redundant-let` (17) | `impl/analyzer.clj:1354`, `:1422`, local forms |
| `redundant-nested-call` (4) | `impl/linters.clj:346`, call nesting |
| `shadowed-var` (530) | `impl/namespace.clj:571–608`, declared names and local bindings |
| `unresolved-excluded-var` (1), `unused-excluded-var` (1) | `impl/linters.clj:887`, excluded core names and usage |
| `unused-binding` (37) | `impl/linters.clj:953–984`, local usage |
| `unused-import` (5) | `impl/linters.clj:1102–1119`, imports and class usage |
| `unused-namespace` (27), `unused-referred-var` (2) | `impl/linters.clj:796–829`, namespace bindings and usage |
| `unused-private-var` (27) | `impl/linters.clj:1018–1054`, same-namespace declarations and usage |

Zero-count admission classes remain too: syntax (`impl/analyzer.clj:4386`),
unresolved-symbol/var/namespace (`impl/linters.clj:1057–1139`), invalid-arity
(`:604–649`), private-call (`:500–513`), protocol method presence/arities
(`:1192–1275`). Declaration metadata checks remain, including deprecated-var
(`:443–465`) and deprecated-namespace (`:780–794`). The latter exposes the
additional graph dependency below; the audit does NOT claim the current
function-edge-only invalidation rule covers all retained findings.

### Third decision: namespace declarations have dependents without function edges

With inferred-type classes disabled, changing only
`(ns callee)` to `(ns ^{:deprecated "now"} callee)` produces a
`deprecated-namespace` warning in this unchanged file:

```clojure
(ns caller (:require [callee :as c]))
(defn g [] 1)
```

The callee function declaration and body also remain unchanged. There are
**zero** caller var usages targeting callee, hence no corresponding published
function calls or references to traverse. Before: 0 deprecation findings
(38 ms); changed file alone: 0 (37 ms); complete analysis: 1 (37 ms).
All three analyses have zero errors. The ordinary unused-namespace warning
is present before and after and does not explain the new finding.

Reproducer:

```sh
python3 docs/prds/steward-platform/research/publication-namespace-analysis-probe-2026-09-20.py
```

The dependency source explicitly iterates **required namespaces**, not called
functions (`impl/linters.clj:780–794`, invoked at `:874`). Seon already stores
that relation as `:seon.ns/requires` (`src/seon/fn.clj:291–300`); the needed
relationship is not a proposed second dependency registry. A separate
exploratory `:refer :all` probe did NOT demonstrate a changed function
resolution; no program-fact mismatch is claimed from it. The proven mismatch
here is retained findings, which the cache contract also promises to keep fresh.

The ruled input set is explicitly changed files plus callers/referrers through
function edges. Expanding it to namespace dependencies or excluding another
non-type finding changes that contract. Three concrete choices:

1. **Recommended: include namespace-declaration dependencies from the existing
   `:seon.ns/requires` graph.** Namespace metadata/export declaration changes
   invalidate requiring files; function changes retain the ruled calls/references
   closure. Guarantee: the demonstrated finding stays current and ordinary
   body edits retain the N-file ceiling. Additional cost: approximately half
   to one lane-day for namespace fingerprints and parity coverage. Give up:
   restricting interface invalidation to function edges alone.
2. **Exclude `deprecated-namespace` from publication too.** Guarantee: the
   demonstrated namespace-metadata warning cannot become stale. Additional
   cost: a small config edit and regression, approximately one hour. Give up:
   that declaration warning; this does not prove every namespace export or
   resolution change independent of namespace dependencies.
3. **Refuse namespace-interface edits on the incremental path.** Guarantee:
   no reused finding is represented as current across an unsupported namespace
   change. Additional cost: approximately half a lane-day for explicit admission
   and refusal coverage. Give up: automatic publication of those edits until
   namespace dependency invalidation lands.

This stop follows the assignment's next-genuine-decision rule and AGENTS.md
§2.5. It does not stop for the held cluster owner or the earlier scratch
failure. The seven authorized exclusions are independently reviewable and
can land without deciding this expansion.

### Verification of the implemented policy

The new real-analyzer regression changes only an inferred return type and
asserts identical caller findings, with an invalid-arity error positively
retained. It passed on the first armed run. That run executed 8 tests / 33
assertions with 0 failures / 2 errors: two existing fixtures passed string
identities to `program-prelude`'s qualified-symbol contract. The owned fixture
file is corrected to symbols; no production contract was weakened.

```sh
bin/test-fast --paths src/seon/fn/analyzer.clj test/seon/fn/analyzer_test.clj -- seon.fn.analyzer-test
```

The follow-up after changing those inputs reports **8 tests / 41 assertions,
0 failures / 0 errors**. Logs: `tmp/publication-dissolution/analysis-policy-fast.log`
and `analysis-policy-fixed-fast.log`. No unchanged suite was rerun; no cold
gate, scratch-root boot, or default lifecycle command ran in this resume.

The corrected run's durable ID is `2f9d80822363`: **8 executed, 0 unchanged**,
41 recorded assertions, exit 0. The first log is 11,491 bytes, SHA-256
`21262925cf8e90aac0cd83bdeeee34c787d770ae9ecfa86fd12f3a20db1a72b0`;
the corrected log is 6,118 bytes, SHA-256
`1142ab534bca94a5f8d5e611a01cf1b6477f616a94e65df1f4a9edeb20fc5071`.
The native census's uncompressed output is 104,710,084 bytes, SHA-256
`7424f992e36f7c3e9613853ad8fd81c76e7851b263a976491f050796b3a766cd`;
its large analysis payload is compressed rather than retained twice.

At the final ownership check (`510a9236d`), `src/seon/cluster.clj` had become
clean. The stop is the namespace-dependency decision, not that earlier held
boundary. `src/seon/schema.clj`, `src/seon/schema/internal.cljc`,
`src/seon/cluster/process.clj` and dirty test paths remain untouched.

Pre-commit load of `seon.fn.analyzer`, `seon.fn` and `seon.cluster.source`
returned `:loads`, exit 0 (`tmp/publication-dissolution/policy-precommit-load.log`).

Cold command owed for this prerequisite only:

```sh
bin/test --paths src/seon/fn/analyzer.clj test/seon/fn/analyzer_test.clj -- seon.fn.analyzer-test
```

The orchestrator also owns platform proof. This is an item-1 prerequisite,
not completion of item 1 or any later item. No publisher path is deleted and
no one-file/complete-publication performance improvement is claimed.

## Private declaration decision after the namespace ruling

The owner accepted `f62978613` and ruled namespace-interface invalidation
through the published requires/alias/refer relations. That decision is settled:
the namespace digest includes metadata and public names, never bodies.

One further qualification prevents the stated analysis guarantee: only
**public** changed declarations seed the caller/referrer closure. Clojure's
Var quote can name a private function in another namespace. This fixture is
admitted by publication's retained lint policy:

```clojure
(ns callee)
(defn- f {:malli/schema [:=> [:cat :int] :int]} [x] x)

(ns caller (:require [callee]))
(defn g {:malli/schema [:=> [:cat] :int]} [] (#'callee/f 1))
```

Adding `^{:deprecated "now"}` to private `f` changes the unchanged caller's
`deprecated-var` finding. The callee's body, arity and contract are unchanged;
its namespace form and public name set are also unchanged. The published
reference can already name this target: `src/seon/fn.clj:353–386` retains
Var-quote calls/references, without excluding private targets. Kondo's
`reg-deprecated-var!` reads the callee declaration at
`reference-code/clj-kondo/src/clj_kondo/impl/linters.clj:441–465`; it is invoked
independently of private-call admission at lines 660–661.

Reproducer: [publication-private-interface-probe-2026-09-20.py](publication-private-interface-probe-2026-09-20.py).
Native analysis with all seven publication exclusions declared reports:

| Phase | Deprecated-var findings | Elapsed |
|---|---:|---:|
| Before, both files | 0 | 41 ms |
| Changed private declaration only | 0 | 38 ms |
| Complete final tree | 1 | 39 ms |

All three analyses have zero errors. Caller content digest is unchanged, and
the analyzer positively reports `caller/g` referencing private `callee/f`.
Raw output: `tmp/publication-dissolution/private-interface.json`. This is
dependency evidence, not an armed publication or performance proof. An earlier
arity-only Var-quote probe did **not** produce an invalid-arity finding; that
hypothesis is refuted, not evidence for the stop.

Exactly three choices; estimates cover this policy adjustment and regression,
not the remaining publication implementation:

1. **Recommended: include referenced private declarations in the same closure.**
   Seed changed declaration digests regardless of privacy; unreferenced private
   declarations naturally add no files. Guarantee: this retained finding stays
   fresh and body-only edits remain N-file. Cost: approximately 30–60 minutes
   for selection and regression beyond the planned closure. Give up the
   public-only interface ceiling; the ceiling becomes the actual referrer
   closure of every changed declaration.
2. **Disable `deprecated-var` in publication config.** Guarantee: this finding
   cannot become stale. Cost: approximately 15–30 minutes for config, census
   delta and regression. Give up deprecation diagnostics for public functions
   too; this alone does not prove every private-interface dependency absent.
3. **Refuse cross-namespace references to private declarations.** Guarantee:
   this dependency is unconstructable in an admitted program. Cost: an estimated
   half day or more to inventory and convert existing private-Var consumers,
   spanning other owners. Give up currently legal Clojure introspection and
   tests of private seams. This is substantially broader than publication.

No additional analyzer classes are excluded without a ruling. Items 1–4 remain
unlanded beyond the previously accepted analyzer-policy prerequisite.

Ownership boundary: `src/seon/cluster.clj` was clean at the initial edit check
but acquired foreign bridge edits during this resume, including the manifest
validation seam. I removed only my two population hunks, preserving all foreign
bytes. The associated uncommitted lineage changes in `cluster/source.clj` and
its two test files were also reversed before stopping; none remain in the tree.
No foreign session was operated or contacted. The scratch root remains absent;
default was untouched, no worktree or test JVM was launched, and no unchanged
suite was rerun. The named authorities were read end to end as recorded in the
grounding section above; this resume additionally reread the complete updated
binding spec and the relevant dependency cache/linter seams.

Pre-commit namespace load of `seon.fn.analyzer`, `seon.fn`, and
`seon.cluster.source` returned `:loads`, exit 0. Log:
`tmp/publication-dissolution/private-interface-precommit-load.log`. This load
used the shared checkout, including its foreign dirty bridge bytes; it is not
a cold gate or a clean-HEAD snapshot proof. The cold command and platform proof
listed above remain owed to the orchestrator.


## Storage boundary: the executing indexer is an analysis input (2026-09-20)

The general closure ruling is accepted: every recorded declaration participates,
regardless of privacy or declaration family. No further finding-policy ruling is
requested. The new boundary concerns **stored program facts**, not findings.

The real historical `call-target` change introduced at `af800d1a0` adds Var-quote
calls without changing the function's name, arguments or metadata. Replaying its
pre-change body against the current analyzer, then the current body, changes the
stored calls of an unchanged `(defn g [] (#'a/f 1))`. The consumer does not call
its indexer, so no declaration edge connects that consumer to `call-target`.
Current owners: `src/seon/fn.clj:353` (`call-target`), `:386`
(`call-targets-by-caller`). This is a targeted historical implementation replay,
not a checkout of the whole historical revision.

The prototype selected only `src/compiler.clj`, exactly as the ruled closure
requires. Incremental analysis retained `#{clojure.core/defn}` in the consumer's
call facts; complete analysis produced `#{pub.alpha/f}`. Both final manifests had
the **same source digest**. Thus source-digest equality is necessary but cannot
prove equal program facts; the parity regression also compares entire artifacts.
Excluding a warning cannot remove this stored-fact dependency.

### Preserved implementation and evidence

[Applicable prototype patch](publication-declaration-closure-prototype-2026-09-20.patch)
contains the exact five source/schema changes and two tests used in the run.
It shares `seon.test.selection`'s existing reverse-edge traversal; reads declaration
metadata through the existing LispReader owner; carries an explicitly owned kondo
resolver cache; analyzes changed files then affected files once each; and retains
unchanged artifacts. It has NOT been connected to any publisher.

The canonical armed fast run on HEAD-plus-owned-paths (`run.fD0FB7`) recorded
**2 executed, 0 unchanged, 24 assertions, 0 failures, 0 errors**, run
`1b08bd6813ce`. Log: `tmp/publication-dissolution/toolchain-fixed-fast.log`.
The five parity cases (body, arity, deletion, namespace deprecation, private
metadata) took 1,744 ms together. The historical toolchain replay took 2,389 ms.
The slot wait was 171 seconds; these are test-body measurements, not publication
phase benchmarks. The earlier five-case run passed 20 assertions; it was rerun
only after the helper's input contract changed. Two intermediate failures were
our prototype defects (nil empty metadata, then an anonymous predicate in a
serialized contract); both were fixed before this recorded run.

No one-file/full publication before/after claim is made: no complete item has
landed. No duplicate publisher path has been deleted. Items 2–4 and the lineage
regression remain owed, as do the requested publication phase measurements.

The prototype is incomplete beyond those cases: schema-resource declaration
seeds and test-identity normalization need completion, resolver caches need
candidate-scoped custody, and N-row reconciliation, lineage, shared acquisition,
live-JVM routing and persistent cache keys are not implemented. The patch is
research evidence and resumable work, not a production implementation or a
claim of general closure coverage. Its hunks were reversed exactly from our five
owned files, and its two newly created tests removed after archival; no foreign
bytes were restored or changed. `git apply --check` passes for the archive.

### Exactly three options at the storage boundary

1. **Recommended — include publication toolchain content as a shared analysis
   input.** Its change invalidates all artifacts it can produce; ordinary
   application body edits remain N-file. Require the live publisher to execute
   the matching toolchain generation or return a typed refusal. Guarantee:
   cached rows cannot silently survive a producer implementation change.
   Cost: estimated half a day to declare toolchain provenance, enforce matching
   execution and add upgrade regressions, plus complete analysis on toolchain
   changes. Give up the unconditional N-file ceiling for publisher/compiler
   body changes. This explicitly widens the spec's declaration-only rule.
2. **Record actual producer dependencies for analysis outputs.** Invalidate by
   the content digests of the producers an artifact used. Guarantee: reuse is
   bounded by recorded producer evidence as well as source declarations.
   Cost: estimated 1–2 days and new provenance/validation work across owners.
   Give up the current narrow scope and declaration-only cache contract; this
   can eventually invalidate fewer outputs than a whole-toolchain digest.
3. **Pin the publisher between explicit upgrades.** Refuse ordinary publication
   when its producer implementation changes; upgrade through an explicit complete
   refresh using matching code. Guarantee: each admitted cache generation has
   one fixed producer. Cost: estimated 2–4 hours for provenance/admission and
   upgrade workflow, plus a full analysis and possibly a publisher process
   transition per upgrade. Give up automatic publication of indexer changes.

Estimates cover this decision's implementation, not all remaining items 1–4.
This stop uses the explicit storage/validation exception: changing invalidation
for stored facts and admitting a live publisher generation requires a ruled
input identity. It is not another declaration/finding variant.

### Ownership and verification boundary

Held paths remain untouched: `src/seon/cluster.clj`, `src/seon/turn.clj`, all
`src/seon/cluster/*.clj` except owned `source.clj`, `src/seon/schema.clj`,
`src/seon/schema/internal.cljc`, and foreign dirty launcher/test-system paths.
At this checkpoint `src/seon/test/runner.clj` and
`test/seon/test_failure_facts_test.clj` have foreign edits. The latter still
calls the evidence-copy helper whose removal belongs with lineage conversion;
that caller must be converted in the same slice when released. Foreign dirty
`test/seon/fn_test.clj` was excluded from our snapshot. These are integration
boundaries, not the reason for this decision stop. No foreign session was read,
operated or contacted. No default operation, cold gate or worktree was used.
The fast snapshots removed themselves; both regression fixture roots were
released in `finally`. `tmp/publication-root` remains absent.

The original grounding was read end to end as recorded above; this resume also
read the complete updated binding spec and the actual producer/cache seams.
The archived experiment is reproducible after applying its patch with:

```sh
bin/test-fast --paths src/seon/fn.clj src/seon/fn/analyzer.clj src/seon/fn/signature.cljc src/seon/test/selection.clj resources/seon/schemas/seon.fn.file.edn test/seon/fn/publication_test.clj test/seon/fn/publication_toolchain_test.clj -- seon.fn.publication-test seon.fn.publication-toolchain-test
```

The corresponding cold command owed to the orchestrator after implementation
is the same path/namespace list through `bin/test`, followed by `bin/test
--platform`. The original required cache/source/runner/fn suites and the live
scratch-root proof remain owed; these two focused tests do not replace them.

Pre-commit namespace load of `seon.fn.analyzer`, `seon.fn`, and
`seon.cluster.source` returned `:loads`, exit 0, after removing the prototype.
Log: `tmp/publication-dissolution/toolchain-precommit-load.log`. This used the
shared checkout including foreign dirty bridge bytes, not a clean-HEAD gate.


## Toolchain ruling implementation — 2026-09-20 continuation

The owner accepted `c4ffc428b` and chose the shared toolchain identity. The
prototype is now restored and extended in the existing analysis/index owners.
The entry points are `seon.fn/build-manifest` and `seon.fn/index!`; producer
membership follows namespace requires facts with the existing test-selection
closure. The digest includes those files, the gate's recorded gitlinks and
`deps.edn`, and the analyzer's declared `.clj-kondo` configuration/hooks.
Configuration is resolved under the supplied source root. The published source
seal can carry `:seon.source/toolchain-digest`; manifests retain declaration
identities/digests. A changed producer forces complete analysis in the regression.

Application invalidation follows stored declaration edges, including private
references, namespace requires/alias/refer, schema contracts, test subjects/reach,
and stored row-schema relationships. Selected-file indexing hands only those
files' rows and changed schema declarations to the existing transaction owner.
An unchanged contract population reuses its supplied projection. The canonical
fixture verifies that indexing one function body leaves a stranger's row intact.

This is preparation for item 1, not its completion. The three publication paths
remain; no end-to-end speedup or lineage guarantee is claimed. The publisher
must still own isolated resolver-cache generations, acquire the previous graph,
and call these incremental APIs. Persistent analysis caching and the live-JVM
and finding-delta items remain pending.

Intermediate evidence: `fd647ef3a7e1` passed 2 tests / 27 assertions;
`167249f3142b` passed 3 tests / 29 assertions. A previous schema fixture omitted
canonical renderer contracts and failed; it was corrected to use the canonical
previous database. A bare schema keyword supplied to `id/digest` also failed
under arming and was corrected to a collection input. These are lane defects,
not foreign failures. Logs are under `tmp/publication-dissolution/`.

The required command in the spec named the nonexistent `seon.test.cache-test`;
the actual namespace is `seon.test-cache-test` in `test/seon/test_cache_test.clj`.
The spec spelling is corrected. The first broad invocation failed loading that
namespace before test execution. One subsequent waiting snapshot was cancelled
before JVM acquisition after a syntax error was caught; the source was repaired
before retrying. No unchanged completed suite was rerun for either attempt.


### Remaining coordinated conversion

The held `src/seon/cluster.clj:1741` population currently hands `index!` its
previous database only when `change-classes` is non-nil, and does not carry a
previous manifest or selected paths. Its full/incremental refresh owners still
choose separate builders. Changing the source publisher to branch the current
lineage without this population conversion would hit the indexer's existing
fresh-branch refusal. These callers must change in one coherent integration.
The `result-preservation-tx` and `preserved-evidence-tx` copies in
`src/seon/cluster/source.clj` then disappear; their test callers must convert
in that same slice. `src/seon/test/runner.clj` and
`test/seon/test_failure_facts_test.clj` are now clean following the other lane's
landing, but the cluster population and both schema owners remain dirty/held.

The pending lineage regression must retain `selection-tx` (including its as-of
meaning), member `claim-tx`, `completed-tx`, `terminated-tx`, and `covered-by`.
Their exact reader evidence is in the results-reuse note's "Publication-dissolution
must retain these references" paragraph. It must record a result, publish one
changed file, and select the recorded result for reuse.

A source-reader review also corrected declaration metadata grammar: a map-valued
`def` or a map expression in a function/test body is not an attribute map;
a multi-arity function's trailing attribute map is metadata. Grounding:
`reference-code/clojure/src/clj/clojure/core.clj:305–319`, `:452`, `:1762`,
`:5921`. The focused regression checks both cases without another fixture build.


Toolchain provenance alone does not verify the code loaded in a live JVM.
The live publication entry must compare its executing producer generation with
the requested toolchain identity before reuse; that admission remains part of
the coordinated publisher conversion. A toolchain/pin change also requires a
fresh dependency resolver generation, rather than retaining old external kondo
cache entries. The current incremental request deliberately requires an explicit
caller-owned cache root, but generation acquisition has not yet been connected.


### Existing complete-publication baseline in the required fast pass

The `incremental-first-party-publication-retains-complete-scalar-rows` test
measured **77,697.430167 ms** for its complete publication. Its manifest was
already prepared: this excludes analysis and JVM startup. This is the old
publisher with the new prerequisite code, not an after measurement of the
shared incremental path. The test passed. The existing progress callback
prints the elapsed duration of the **previous** phase, so the labels below
name the work measured (`test/seon/cluster/source_test.clj:423`).

| Phase | Milliseconds |
|---|---:|
| publication start | 360 |
| schema population started | 1060 |
| schema population complete | 0 |
| instruction rows | 36 |
| program rows started | 2344 |
| contract projection started: 3239 schemas, 1449 functions | 1351 |
| contract projection complete | 7394 |
| contract rows: 1884/11302 | 7746 |
| contract rows: 3768/11302 | 836 |
| contract rows: 5652/11302 | 150 |
| contract rows: 7536/11302 | 632 |
| contract rows: 9420/11302 | 2572 |
| contract rows: 11302/11302 | 7840 |
| program population compiled: 33272 entities, 25337 identities, 41173 keyword facts | 30982 |
| population: 99782/99782 | 0 |
| program rows complete | 0 |
| initialization rows | 1279 |
| publication issue indexing | 4469 |
| publication test evidence | 0 |
| publication activation seal | 552 |
| publication branch head | 8084 |

Raw log: `tmp/publication-dissolution/owned-regression-final-fast.log`.
The controlled one-file commit measurement, analysis timing, and after figures
remain owed until the common publisher and held callers are connected.


The broad pass exposed four assertions in the existing evidence/race case.
Two expected empty recording to leave the head/basis unchanged; the current
writer deliberately records its run event, including zero results. The test now
queries that event positively. The other two found a real no-op publication:
`upsert!` appended unchanged source/input identity facts after the activation
owner had correctly returned an empty change set. That map is now appended only
when its facts differ on the privately owned scratch connection. The source-head
CAS remains unchanged. Issue:
`docs/seon/issues/unchanged-publication-identity-facts-still-advance-the-branch.md`.

The evidence/race case is moved intact to `seon.cluster.source-evidence-test`
(with its platform status and an explicit 600,000 ms bound), allowing its focused
verification without repeating every full-publication fixture. Its previous
public test name has no callers in `src/` or `test/`. The declaration-reader
regression likewise has its own `seon.fn.publication-signature-test` namespace.


The activation-refusal regression also repeated five complete publications for
one refusal class. Its focused version supplies all five missing prerequisites
at once and asserts that the report retains every one, that no source/cluster
branch is published, and that scratch is retired. It uses the same `with-store`,
real population, and activation boundary. Four redundant complete fixture builds
are deleted from that test; the old measured duration and focused replacement's
duration are reported with the final test evidence below.

The remaining publisher integration must acquire graph edges without truncated
wildcard pulls (cardinality-many pull defaults to 1,000). Static artifact rows
already carry their recorded edges; current recorded test reach and canonical
schema relationships must be handed completely or queried through the database's
indexes. The prototype's `published-rows` argument is explicit data, not permission
to rebuild a full database projection on every one-file publication.


The source suite's `populate-schema!` helper now hands `populate-source!` the
canonical `@test-support/source-manifest`, as the existing program-publication
regression already does. Previously every synthetic publication asked `index!`
to analyze the entire unchanged checkout again. This reuses the fixture's real
complete analysis; population, armed contracts, database transactions, and
activation still execute through the production owners. No row roster or mock
population is introduced.


### Discovered validation boundary in the required runner namespace

The broad fast invocation was stopped, exit 143, at
`seon.test-runner-test/concurrent-bin-test-invocations-both-reach-their-tallies`.
That fixture launches two **real cold gates**, explicitly applying
`as-orchestrator` to their environments. It cannot run inside the assignment's
one-JVM/no-cold-gate constraint. A nested JVM (32918) had started beneath our
JVM (15851) before the fixture's source was inspected. Both and all enumerated
child gate processes were terminated; absence was verified and `run.O81Ldt`
was removed by the launcher. This corrects any implication that this entire
invocation remained within one JVM: it did not. No foreign process was touched.

Issue: `docs/seon/issues/fast-runner-suite-can-launch-cold-gates-and-exceed-lane-jvm-budget.md`.
The cache and source namespaces completed (four source assertions, corrected
above); the runner namespace was partial and no final tally was reached.
The function suites had not executed. They continue with the focused changed
cases in a separate fast invocation, without repeating completed namespaces.
The remaining runner validation is explicitly owed to the orchestrator.


The first function pass also found two required-observation errors. The owned
`seon.fn/analyze-forms` refusal lacked `:seon.error/at`, `/layer`, and `/operation`;
these fields now name the observation at that boundary. The other error is the
same omission in `test/seon/fn_test.clj`'s refusal fixture. That file is held and
already has the matching uncommitted correction (plus a bridge API conversion)
from its owner. Those bytes were inspected, preserved, and excluded from our
snapshot. The lane fixes the owned production call; it neither copies nor edits
the held fixture. The namespace is rerun only because this production input changed.


### Recorded focused verification

Run `f8e9a506d7fd` (`tmp/publication-dissolution/focused-final-fast.log`):
**73 executed, 0 unchanged, 533 assertions, 1 failure, 2 errors**. The two
errors are the diagnostic observations described above (one owned and repaired,
one held fixture). The failure was our row-schema test editing the outer `:and`
instead of its nested entity map, so it had changed nothing. The fixture now
changes the actual map and positively checks that a real doc field is present
before and absent afterwards. This is a fixture correction, not evidence that
its previously asserted schema-change case was covered.

The following members passed in that recorded request: all five application
analysis parity cases, selected-file indexing, the historical toolchain replay,
configuration-input identity, declaration metadata grammar, and both focused
source-evidence tests. The no-op seal regression now reports three attempts,
with no fourth retry for the unchanged B seal. The published toolchain fact and
empty run event were also positively observed.

The activation-refusal class measured **410.815949 s before** (five complete
publications in the broad pass) and **72.739101 s after** (one complete
publication retaining all five missing prerequisites). Four duplicate fixture
builds were removed: **338.076848 s less, about 82%** in this comparison.
This is a test-cost measurement, not the spec's unimplemented one-file landing
latency claim. The evidence/race case took 307.945276 s in the focused pass.

The remaining rerun selects only `seon.fn-test` and `seon.fn.publication-test`,
whose inputs changed for the owned refusal and corrected schema fixture. Passed
source-evidence, signature, and toolchain namespaces are not repeated.


### Stop boundary and priced choices

Item 1 is **not complete**. This slice supplies declaration/toolchain identity,
selected analysis and row construction, and a verified no-op seal repair. It
does not replace the three publisher paths. The required final conversion is
`src/seon/cluster.clj` (`populate-source!`, `source-base!`, and both refresh
functions), still dirty and explicitly HELD at launch. The assignment requires
a stop/report before that conversion. `src/seon/turn.clj`, other cluster owners,
and `src/seon/schema.clj` / `internal.cljc` remain untouched. The dirty
`test/seon/fn_test.clj` fixture is also excluded. No worktree was created.

The runner selection conflict additionally crosses the validation boundary.
Three options (engineering estimates, not measured gate durations):

1. **Recommended: move real child-gate fixtures to an orchestrator integration
   namespace, preserving their platform coverage.** About 30–60 minutes for
   the fixture move and selection audit, plus the orchestrator's gate. Lane
   namespace selection then retains one-JVM semantics. Gives up the current
   convenience of one namespace containing both pure runner and launcher proofs.
2. **Keep the fixtures; reserve the whole runner namespace for the orchestrator.**
   About 10–20 minutes to record the assignment/selection rule, plus each
   orchestrator run. No runner implementation change. Gives up lane-local
   runner-suite proof; it remains explicitly owed at every relevant landing.
3. **Authorize child cold gates for this namespace explicitly.** No fixture
   refactor; pays the complete child-gate work and overlapping JVMs each time.
   Gives up the owner's one-JVM/no-cold lane guarantee. Not recommended.

Separately, release the named cluster publication region after its owner lands
before continuing item 1. Neither choice authorizes editing another lane's
uncommitted bytes.

Deleted production publisher paths: **none yet**. Four redundant complete
publication executions were removed from the activation-refusal regression.
Items 2–4, the actual recorded-result → changed publication → reuse lineage
regression, full publication digest parity, one-file phase timings, and the
live scratch-root proof remain owed. The 77.7-second complete baseline above
excludes analysis/JVM startup and must not be compared as a complete end-to-end
after measurement. No default operation was performed.

I read the assignment and its named grounding end to end (the specified
AGENTS sections, working-edge tail, research census sections, both issues,
and the listed launcher/hook/cache/source/cluster/operator owners). Earlier
rulings and the dependency finding census remain recorded above.

Owned implementation/test paths in this slice:

- `src/seon/fn.clj`
- `src/seon/fn/analyzer.clj`
- `src/seon/fn/signature.cljc`
- `src/seon/test/selection.clj`
- `src/seon/test/cache.clj`
- `src/seon/cluster/source.clj`
- `resources/seon/schemas/seon.fn.edn`
- `resources/seon/schemas/seon.fn.file.edn`
- `resources/seon/schemas/seon.fn.manifest.edn`
- `resources/seon/schemas/seon.source.edn`
- `test/seon/cluster/source_test.clj`
- `test/seon/cluster/source_evidence_test.clj`
- `test/seon/fn/publication_test.clj`
- `test/seon/fn/publication_toolchain_test.clj`
- `test/seon/fn/publication_signature_test.clj`

Cold proof owed to the orchestrator after held-owner integration and the
runner-selection decision (these commands were NOT run by the lane):

```sh
bin/test --paths src/seon/fn.clj src/seon/fn/analyzer.clj src/seon/fn/signature.cljc src/seon/test/selection.clj src/seon/test/cache.clj src/seon/cluster/source.clj resources/seon/schemas/seon.fn.edn resources/seon/schemas/seon.fn.file.edn resources/seon/schemas/seon.fn.manifest.edn resources/seon/schemas/seon.source.edn test/seon/cluster/source_test.clj test/seon/cluster/source_evidence_test.clj test/seon/fn/publication_test.clj test/seon/fn/publication_toolchain_test.clj test/seon/fn/publication_signature_test.clj -- seon.test-cache-test seon.cluster.source-test seon.cluster.source-evidence-test seon.test-runner-test seon.fn-test seon.fn.publication-test seon.fn.publication-toolchain-test seon.fn.publication-signature-test
bin/test --platform
```


Final focused run `bc535b64249a`
(`tmp/publication-dissolution/declaration-final-fast.log`): **68 executed,
0 unchanged, 485 assertions, 0 failures, 1 error**. The only error is
`seon.fn-test/a-refused-reference-read-refuses-gate-set-derivation`, whose
held fixture lacks the observation fields. The owned production refusal
and the corrected row-schema edge regression both pass. This is a qualified
fast result, not a green complete namespace or a cold proof. No passing
evidence/toolchain/signature suite was repeated after its inputs stabilized.

Precommit namespace load exited 0, requiring `seon.fn`, `seon.fn.analyzer`,
`seon.fn.signature`, `seon.test.selection`, `seon.test.cache`, and
`seon.cluster.source` (`tmp/publication-dissolution/precommit-load.log`).
That command used the shared tree, including foreign edits; the fast evidence
above used HEAD plus the explicit owned paths. A committed-HEAD-only namespace
load follows the commit; it is a source load, not another suite execution.


### Accepted 8edfae1b7 followup: selection-policy boundary

The prescribed fixture split was audited before editing. Existing `:platform`
and `:long` cannot provide all three requested properties: explicit platform
coverage, exclusion from bare gates, and mechanical exclusion from fast named
requests. The child fixture also reaches the declared destructive population
owner that the platform checker forbids. My earlier recommendation omitted
that conflict. Exact file/line evidence and three priced choices are recorded
in [the existing nested-gate issue](../../../seon/issues/fast-runner-suite-can-launch-cold-gates-and-exceed-lane-jvm-budget.md#followup-existing-metadata-cannot-express-the-requested-isolation).

No production or fixture changes, no suite rerun, and no default operation in
this followup. The population owner is still dirty/held. The requested ordering
was fixture-split commit first; that slice stops before changing validation
policy. Items 2–4 remain unimplemented.
