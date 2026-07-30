---
type: research
status: active
tags: [prd, research, indexing, datahike, clj-kondo, audit]
---

# `current-src` adversarial review

## Verdict

The replacement has the right large-grained architecture: one non-executing
`:current-src` branch, exact commit-ID forks, static first-party analysis,
global schema population, guarded Datahike publication, and sovereign existing
clusters. The maintained Datahike and clj-kondo integrations are real rather
than wrappers around guessed behavior.

It is not ready to call sealed. Two correctness defects can make the published
digest and artifact claim a source tree that the database rows do not actually
represent. Two further design gaps make the advertised incremental path less
safe and less consistently fast than the documentation says.

## Findings

### P0 — a missed edit can be permanently blessed by a later incremental edit

The incremental gate checks that the artifact commit equals the published
commit, but never checks that unchanged artifact files still match the working
tree (`src/seon/cluster.clj:559-566`). It computes a whole-tree digest before
and after analyzing the reported paths, but compares only those two current
digests (`src/seon/cluster.clj:568-596`), not the current tree against the
cached tree or the unchanged per-file artifacts.

Consequently:

1. commit/artifact A describes files X and Y;
2. X changes without a successful hook publication;
3. a later hook reports only Y;
4. only Y is analyzed and upserted (`src/seon/cluster.clj:570-592`);
5. the operation seals the digest of current X+Y onto rows still containing
   old X (`src/seon/cluster.clj:610-616`); and
6. the artifact records that digest and commit (`src/seon/cluster.clj:617-618`).

This corruption survives an explicit complete `bin/seon init`: complete
analysis builds the desired manifest, but `source/publish!` short-circuits on
digest equality alone (`src/seon/cluster/source.clj:125-128`), so population
does not repair the stale database rows. The newly written complete artifact
can then hide the disagreement.

The incremental admission test must prove every unreported first-party file
and schema resource still matches the published artifact before allowing an
upsert. Any mismatch must select the complete scratch build, and complete
publication must not use digest equality as proof that program rows agree.

### P0 — complete indexing publishes ordinary clj-kondo errors

Build admission blocks findings only when their type is `:syntax` or
`:analysis-error` (`src/seon/fn.clj:260-271`). Runtime admission correctly uses
the finding level and rejects every `:error` (`src/seon/cluster/loop.cljc:80-98`).
The two policies therefore disagree for error-level findings such as
`:unresolved-symbol`, `:invalid-arity`, and `:namespace-name-mismatch`.

A direct probe analyzed this invalid file:

```clojure
(ns audit.unresolved)
(defn broken [] missing)
```

clj-kondo returned error-level `:namespace-name-mismatch` and
`:unresolved-symbol`, while `seon.fn/build-manifest` succeeded and emitted two
rows. This contradicts the stated guarantee that only correct source becomes
the published base. The complete and per-file builders both call the same
under-strict `assert-clean-analysis!` (`src/seon/fn.clj:323-330,397-420`).

One admission predicate should own this rule: error-level findings refuse
publication; warnings remain advisory. Malformed siblings may still be
analyzed for feedback, but no partial or error-bearing base is published.

### P1 — incremental replacement stores delta rows as the full file artifact

`plan-file-change` deliberately returns only scalar changed rows for database
publication (`src/seon/fn.clj:540-548`). The cluster then constructs a
replacement file artifact from those delta rows (`src/seon/cluster.clj:598-607`)
and installs it as though it were the complete projection.

A direct pure probe on `src/seon/ai/tokens.cljc` observed:

```clojure
{:action :incremental-upsert
 :rows-before 4
 :delta-rows 0
 :rows-after 0
 :identities-after 4}
```

The manifest consequently contains identities with no corresponding rows.
After a real scalar edit it retains only identity plus changed scalar fields,
not the analyzed file. A second edit commonly falls back unnecessarily because
the incomplete artifact makes unchanged component/cardinality-many fields look
new. More importantly, the artifact is no longer evidence for database/source
agreement.

Keep two values with distinct names: the complete desired file artifact for
manifest replacement, and the scalar transaction rows for `source/upsert!`.
The regression should assert full row equality after two consecutive safe
edits, not merely that an artifact vector is non-empty
(`test/seon/cluster/boot_test.clj:549-578`).

### P1 — the supposedly JVM-free edit path launches a new JVM when idle

The research ruling says a separate JVM costs 16.58 seconds and is forbidden
on the edit path (`current-src-publication-2026-07-30.md`, “Measured cost
boundary”). Yet `init!` invokes `source-process-value!` whenever no live anchor
exists (`script/seon/fresh_operator.clj:1322-1343`), and that function launches
`clojure -M:dev -e` (`script/seon/fresh_operator.clj:1285-1301`). The edit hook
always calls `bin/seon init --changed ...` for admitted paths
(`bin/seon-hook:817-849`).

Therefore an idle checkout pays the forbidden cold-JVM path on every edit, or
fails if another process owns the store. Either constrain hook publication to
an already-running store-owning JVM and say so, or give `current-src` one
long-lived operator owner. The current code and the “always current and ready
to fork” claim do not compose.

The live hook evidence during this review also repeatedly failed against a
stale JVM because that process's classpath predated the new `clj-kondo.core`
dependency. Reloading `seon.fn.analyzer` cannot add a missing dependency to an
already-started JVM (`script/seon/fresh_operator.clj:1264-1273`). This is an
operational proof gap, not evidence that a freshly restarted JVM fails.

### P1 — scalar safety is a hand list outside the transaction owner

The planner's component/cardinality-many boundary is a literal attribute set
(`src/seon/fn.clj:66-71`). `source/upsert!` itself accepts arbitrary maps and
transacts them (`src/seon/cluster/source.clj:193-230`); it neither derives nor
checks scalar-only safety. A future program attribute can therefore become
component or cardinality-many without changing this list, silently admitting
the anonymous-component duplication class this wave intends to eliminate.

Derive the unsafe attributes from the canonical schema/Datahike projection and
enforce the scalar delta at the publication owner, or make the updater private
and pass a validated scalar transaction shape that cannot contain those
attributes. The current caller happens to cover today's five namespace/call
attributes, but the invariant is not structural.

### P2 — row-less clj-kondo findings can crash per-form admission

`analyze-forms` calls numeric span comparison with `::row` directly
(`src/seon/fn/analyzer.clj:237-249`). The analyzer normalization intentionally
keeps only present values (`src/seon/fn/analyzer.clj:25-31`), so a file/global
finding without a row reaches `touches-span?`, whose `<=` calls cannot accept
nil (`src/seon/fn/analyzer.clj:202-205`). No recurring test supplies a row-less
finding. Such a finding should either attach to the whole candidate with an
explicit policy or remain a top-level diagnostic; it must not throw out of the
agent loop.

### P2 — hook failure text overclaims prior-head preservation

Every nonzero `bin/seon init --changed` result is reported as “the previous
commit remains published” (`bin/seon-hook:838-848`). Expected-head and analysis
failures do preserve the old head, but failures after `force-branch!`—artifact
move failure, readback/cleanup failure, or process loss after publication—can
occur after the branch advanced (`src/seon/cluster/source.clj:155-190`;
`src/seon/cluster.clj:617-618`). The feedback should report publication failure
without asserting which head is visible unless it reads the head and proves
that fact.

## What is genuinely sound

- `source/current-branch` is the single literal `:current-src`; complete and
  incremental publication use process-owned scratch branches and retire them
  (`src/seon/cluster/source.clj:23-25,93-115,117-249`).
- Cluster creation snapshots an immutable commit ID and branches from that
  UUID (`src/seon/cluster.clj:1101-1111`;
  `src/seon/cluster/registry.clj:235-257`). Existing branches bypass current
  source lookup and remain sovereign.
- Datahike's maintained `force-branch!` checks the expected head before and
  inside the mutable update, writes immutable values first, publishes the
  roster last, and verifies readback
  (`reference-code/datahike/src/datahike/versioning.cljc:323-444`). The stale
  publication tests exercise the actual dependency refusal
  (`test/seon/cluster/source_test.clj:190-208,235-258`).
- Complete source roots are first-party `src` and `test`; dependencies only
  supply analyzer context (`src/seon/fn.clj:18-20,397-420`). Global schemas
  come from `schema/canonical-schema-rows`, not namespace ownership
  (`src/seon/fn.clj:587-605`).
- Repository definitions are not evaluated. Exact source comes from analyzer
  spans, and the only additional read is the namespace form through Clojure's
  reader with `*read-eval* false` (`src/seon/fn.clj:82-104,118-190`).
- Artifact replacement uses a same-directory temporary file plus
  `ATOMIC_MOVE` and `REPLACE_EXISTING` (`src/seon/cluster.clj:506-525`). Commit
  mismatch selects a complete fallback.
- The maintained Datahike clj-kondo export is generated from the API
  specification and includes the versioning operations. A native lint of
  `src/seon/fn.clj` plus `src/seon/cluster.clj` completed in 100 ms with zero
  errors and no unresolved Datahike vars. No application JVM heap warming was
  involved.
- Runtime per-form admission is real: error-bearing forms become quoted flat
  values while clean forms retain exact bytes and ordinals
  (`src/seon/cluster/loop.cljc:67-104`). The recurring test executes nine clean
  forms and the one refusal (`test/seon/cluster/loop_test.clj:50-96`).

## Verification performed

- Native clj-kondo consumer lint: zero errors, three unrelated warnings in
  `seon.cluster`.
- Pure artifact probe: reproduced the four-rows-to-zero artifact corruption
  above.
- Invalid-source probe: reproduced successful manifest construction despite
  two error-level findings.
- Focused tests passed `seon.fn-test`, `seon.fn.analyzer-test`, and
  `seon.cluster.source-test`, and progressed through `seon.cluster.boot-test`
  into loop/operator coverage without a failure. The run was stopped before
  completion because the top-level full gate was already exercising the same
  tree; it is not claimed as a completed gate.

The earliest honest exit is to fix the two P0 agreement/admission defects,
make the manifest retain complete desired artifacts, then rerun the missed-edit
sequence and two-consecutive-edit sequence against a fresh file-store before
accepting any latency number or `current-src` completeness claim.

## Resolution — 2026-07-30

Every finding above was addressed at its owning invariant:

- the artifact records exact per-file digests and incremental admission proves
  every unreported source/resource still matches; disagreement selects a full
  rebuild;
- complete publication no longer treats digest equality as row agreement;
- one error-level admission predicate blocks repository publication, while
  clj-kondo's unsound local `:type-mismatch` inference remains visible as a
  warning rather than becoming a database admission authority;
- manifest replacement stores the complete analyzed file artifact and sends
  only its derived scalar delta to the database;
- unsafe incremental attributes are derived from the Malli→Datahike
  projection, and the publication owner independently checks the installed
  database schema for cardinality-many/component attributes;
- row-less findings attach to every candidate form instead of entering numeric
  span comparison;
- hook failure text makes no unproved claim about the visible branch head; and
- changed-file initialization now requires an already-running store-owning JVM
  instead of launching the measured slow child process.

Runtime analysis now also derives known namespaces, public functions, and
private functions from the queried `:seon.fn` program graph, filtered to the
namespaces syntactically referenced by the candidate forms. This fixes the
audit's remaining hidden context gap: a clean fully qualified call to a
packaged or previously committed agent function is accepted, while private
cross-namespace use is refused, without injecting roughly 1,200 irrelevant
function stubs into every analysis. Independent forms retain their own
findings and ordinals.

Additional dependency proof found and fixed clj-kondo's incorrect
auto-resolution of source metadata. The maintained dependency commits and
exact source paths are recorded in
`current-src-publication-2026-07-30.md`, not left in an agent transcript.
