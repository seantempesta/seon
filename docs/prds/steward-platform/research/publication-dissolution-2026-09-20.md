---
type: research
status: active
created: 2026-09-20
tags: [publication, test-system, wave/publication-velocity]
---

# Publication dissolution — decision checkpoint

Items 1–4 are **not complete**. The latest checkpoint implements the ruled
publication-analysis exclusions; the publication/reconciliation and caching
changes remain unimplemented. No publication speedup is claimed. The sections
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
