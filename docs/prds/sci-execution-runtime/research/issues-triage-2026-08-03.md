---
type: research
status: complete
tags: [issue, audit, architecture, evidence]
---

# Open-issue structural triage — 2026-08-03

## Verdict

The current top-level issue inventory contains 86 open notes: 9 blockers, 64
friction issues, and 13 cleanup issues. Every note was read in full. The index
validator was clean at that 86-note census before this report was written.

The queue is not 86 independent fixes. Sixty-one notes fall into six repeating
design-smell classes where one strengthened owner can dissolve several notes.
Sixteen more form five smaller structural clusters. Two active notes are
standalone. Seven notes across those groups are genuinely independent point/
sweep work. A separate seven-note group is already implementation-stale
because its acceptance implementation landed but the issue lifecycle and
schedule did not catch up.

The highest-leverage active boundary is the SCI/runtime kernel, not the render
wave by itself. It contains three blockers and nine friction issues around one
evaluation/acquisition/binding/error lifecycle. The next boundary is the
schema and contract population: one blocker and twelve friction issues are
different faces of undeclared, anonymous, privately bounded, or producer/
consumer-divergent contracts.

## Method and score

This is a source-grounded triage, not a title classifier.

- Inputs: every open note under `docs/seon/issues/`, the hand-maintained
  `docs/seon/issues/index.md`, the issue lifecycle contract, and the current
  WORKING EDGE.
- Current-source checks: the named production and test owners for every
  top stale candidate, plus repository-wide searches for copied rosters,
  closed maps, dead codec readers, polling clocks, and obsolete paths.
- History checks: the landing commits named in the stale-candidate section and
  the current branch log through `b5ffb9570`.
- Dependency boundaries consulted: maintained Datahike
  `0e8601d7f2f68c01070e13a95483bc82be04cabc`, core.async
  `dc35f3e0d7bc2eef502e77982f48641f025c8051`, and Malli `0.20.0`, through the
  exact source references already carried by the issue notes.

The rank score is deliberately simple and reproducible:

```text
score = 3 × blockers + 2 × friction + 1 × cleanup
```

This is “issues dissolved × severity” without pretending that an old line
count or a guessed implementation cost is evidence. The stale-closure group is
ranked separately because it needs lifecycle reconciliation, not another
production fix.

## Ranked active structural clusters

| Rank | Root-cause class | B/F/C | Score | One structural change |
|---:|---|---:|---:|---|
| 1 | SCI/runtime boundary fragmentation | 3/9/1 | 28 | Make one guarded invocation/acquisition kernel own database-assigned namespace, canonical bindings, per-row containment, structured failures, and arm/disarm lifecycle. |
| 2 | Contract and schema debt | 1/12/0 | 27 | Give every boundary one named open schema, one declared cap/discriminant, and one producer-to-consumer shape; reject unsafe combinations at admission. |
| 3 | Stored-derived facts, hand lists, and copied authorities | 0/10/0 | 20 | Record the missing fact once in schema/program/database data and make every classification or projection a query over it. |
| 4 | Timeout or polling in place of observable lifecycle | 0/8/0 | 16 | Return owner handles that publish readiness, transition, completion, and exact process identity; await those events with clocks only as loud backstops. |
| 5 | Second mechanisms and readerless residue | 0/5/5 | 15 | Add one reachability census from production entries through source/contracts/tests, then delete every unreachable implementation and schema row at its completed cut. |
| 6 | False-green tests and dishonest generators | 0/5/2 | 12 | Make recurring proofs discover subjects through the canonical graph, generate the actual failure class, and acquire every lifecycle object inside cleanup scope. |
| 7 | Database retention and episode economics | 1/3/0 | 9 | Declare one commit-ID/database-value retention policy, then reduce episode transactions and route bulky durable values through the existing blob/GC owners. |
| 8 | Operator boot and development-loop latency | 1/2/0 | 7 | Ship one current, digest-bound operator artifact that can read roster/prerequisites without creating state and keeps cold source load outside the ten-second path. |
| 9 | Dependency/source provenance drift | 0/3/1 | 7 | Mechanically compare resolved coordinates, submodule revisions, and skill dependency ledgers in the same commit that changes a pin. |
| 10 | Cross-cluster shared-resource isolation | 1/1/0 | 5 | Carry cluster custody into process-root admission so compute and retained-heap pressure have an enforceable per-cluster boundary. |
| 11 | Debug/render evidence mismatch | 0/2/1 | 5 | Render debug surfaces from captured facts and observed liveness, with CSS confining wide data to its own scroll surface. |

### 1. SCI/runtime boundary fragmentation

Issues: `acquire-has-no-per-row-containment`,
`agent-renderers-never-enter-the-sci-program-context`,
`evals-ignore-the-agents-assigned-namespace`,
`contracted-defn-rebuilds-the-whole-schema-projection`,
`host-bound-first-party-vars-break-in-value-position`,
`negative-import-masks-escape-static-admission`,
`partial-hot-reload-produces-mixed-code-with-no-warning`,
`repl-parity-divergences`, `runtime-lint-does-not-resolve-namespace-aliases`,
`runtime-turn-and-evaluate-kernels-conflate-boundaries`,
`sci-analysis-ex-data-carries-a-symbol-nothing-reads`,
`sci-evaluate-throws-when-a-guarded-context-is-re-armed`, and
`unlogged-findings-2026-08-01`.

The renderer implementation at `094127076` materially strengthens this class:
`seon.sci.kernel` now owns guarded invocation and renderer results traverse it.
That makes the old “SCI render-execution design gate” destination stale. It
does not yet make the issue acceptance-stale: the note's complete cache,
interpreted-loop, failure-provenance, and closure claims have not all been
independently reconciled against the post-`b5ffb9570` tree.

`unlogged-findings-2026-08-01` is now a misleading umbrella. Ruling #41 and the
call-site sweep through `cced8d9a9` landed `entity`, `datoms`, `transact!`, and
the classified direct-call residual. Only its interop-observation/default-
allow item remains active here; the note should be split or narrowed before
implementation scheduling.

The structural change dissolves the renderer, namespace, host-binding,
re-arm, structured-error, and acquisition-containment family. Contracted-defn
cost, REPL parity, static negative imports, and hot-reload skew remain distinct
follow-ups even after that kernel is honest.

### 2. Contract and schema debt

Issues: `admit-inst-overlap-prefers-collection-shape`,
`anonymous-runtime-contracts-have-recurred`,
`bootstrap-teaches-bare-map-keys`,
`closed-map-contracts-survive-outside-schema-population`,
`elided-marker-carries-no-count-or-identity`,
`instrumentation-headline-unbounded-when-caps-absent`,
`map-unions-have-no-explicit-discriminants`,
`production-docstrings-teach-deleted-semantics`,
`schema-guard-refuses-accretive-loosenings-with-data`,
`sci-reader-hides-a-production-source-cap`,
`secondary-only-attributes-have-no-covering-index`,
`terminal-refusal-error-fact-fails-on-oversized-data`, and
`thinking-tool-continuations-have-no-faithful-request-shape`.

The open-map population landing did not dissolve the repository-wide blocker:
the current tree still contains live `{:closed true}` function contracts in
bootstrap, config, store, indexing, loop, run, cluster, and SCI reader owners.
The schedule must name a repository-wide open-contract wave, not the completed
canonical-population wave.

The shared repair is one declared, reusable shape per boundary. It would
dissolve the anonymous transaction/database/value contracts, private caps,
terminal error mismatch, and ambiguous unions. Three issues remain design
decisions or point work: `Inst` versus collection overlap, provider tool-
continuation semantics, and truthful production docstrings.

### 3. Stored-derived facts, hand lists, and copied authorities

Issues: `changed-test-selector-classifies-hosts-by-path-prefix`,
`cluster-toolkit-stores-a-prefix-derived-projection`,
`config-dial-discovery-has-three-authorities`,
`live-publication-has-a-hand-maintained-predicate-owner-reload`,
`namespace-binding-targets-are-symbols-not-refs`,
`operator-classifies-processes-by-command-substrings`,
`public-contract-census-can-pass-with-no-subjects`,
`render-token-budgets-are-private-dials-no-producer-supplies`,
`render-walk-maintains-a-derived-edge-hand-list`, and
`session-image-stores-derived-unrestorable-prose`.

These are one root cause in different clothes: a needed fact is absent or not
connected, so a consumer substitutes a prefix, command substring, copied set,
test roster, private dial, function vector, or English conclusion. The one
structural correction is to declare the fact and query it.

Tonight's run-trigger commits (`5d2235b8b`, `157ecb74a`) removed part of the
render-walk exception family but did not finish it: current
`seon.render.walk/derived-edge-functions` still hand-registers
`asked-for-run-edges`. The issue remains real, while its old connection-model
wave destination should become a bounded hand-list deletion follow-up.

### 4. Timeout or polling in place of observable lifecycle

Issues: `artifact-releases-the-fence-between-install-and-start`,
`changed-test-process-cleanup-polls-observable-exit`,
`concurrent-eval-test-calibrates-interpreted-work-to-wall-time`,
`eval-drives-duplicate-a-four-minute-run-clock`,
`mcp-parent-watchdog-can-follow-a-reused-pid`,
`observable-graph-transitions-are-polled-in-tests`,
`operator-subprocesses-have-unbounded-read-and-wait-paths`, and
`oversight-treats-a-20ms-ping-absence-as-state`.

The common defect is an interface that hides an observable event. Publishing
the existing Flow report channel, transaction report, `ProcessHandle`
completion, captured process identity, and store-holder lifetime dissolves the
polling and absence-as-state variants. The artifact fence and non-child MCP
watchdog still need their exact ownership interval/identity designs; they are
not fixed by replacing sleeps alone.

### 5. Second mechanisms and readerless residue

Issues: `cluster-export-is-implemented-without-a-runtime-reader`,
`dev-feedback-gates-observe-deleted-owners`,
`flow-config-dials-have-two-registration-owners`,
`flow-has-no-read-set-control-and-a-hand-rolled-egress`,
`flow-prototype-procs-survive-beside-the-live-agent-graphs`,
`monitor-graph-command-proc-throws`,
`operator-private-helpers-have-only-test-readers`,
`schema-datahike-keeps-a-readerless-second-codec`,
`schema-population-retains-five-readerless-rows`, and
`value-floor-residue-duplicate-cursors-and-marker-hand-lists`.

One production-root reachability census would expose most of this group:
prototype procs, export, private helpers, command passthrough, explicit codec,
and schema rows survive only because tests or declarations point at them. The
Flow read-set/egress issue is the exception: it has live readers, but preserves
hand-rolled substitutes beside dependency-owned mechanisms. It needs adoption
at the live graph owner rather than simple dead-code deletion.

### 6. False-green tests and dishonest generators

Issues: `ai-transport-taxonomy-test-can-run-zero-assertions`,
`context-mvp-drive-can-false-green-after-cross-agent-delivery`,
`duplicate-identity-refusal-evidence-is-unordered`,
`flow-generators-reuse-one-mutable-sample`,
`flow-monitor-test-resources-outlive-their-cleanup-scope`,
`opaque-contract-generators-share-live-process-objects`, and
`render-wave-properties-cannot-produce-their-failing-cases`.

The structural testing change is threefold but belongs at one recurring proof
surface: prove a nonempty canonical subject set, generate the named failure
class, and register cleanup immediately after each acquisition. The duplicate
identity note is a small deterministic-order point fix after the wider harness
is honest.

### 7. Database retention and episode economics

Issues: `context-capture-prompts-bypass-the-blob-splitter`,
`eval-samples-cost-42mb-of-store-each`,
`file-store-commits-pay-five-times-the-fsyncs-they-need`, and
`storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing`.

The corrected eval blocker is about retained snapshots and transaction count,
not the obsolete 42 MB headline or one blob threshold. A declared commit-ID/
database-value retention window unlocks meaningful GC. Reducing transaction
count attacks the quadratic retained-snapshot term. Routing exact prompts
through the existing blob owner removes one remaining large inline value.
Writer waiting is independent acquisition/latency policy and should not be
smuggled into that structural change as a tuned literal.

### 8. Operator boot and development-loop latency

Issues: `give-offline-roster-discovery-a-current-read-only-helper`,
`new-cluster-boot-fails-on-a-stale-published-source`, and
`source-load-is-118s-against-the-ten-second-law`.

These converge on a current digest-bound operator artifact: it can read the
roster and population prerequisites without creating/recovering state, and it
can keep dependency compilation out of the cold operator path. The stale-
published-source issue still needs an atomic refuse/cleanup behavior at the
fork boundary; fixture-only source coherence does not prove it.

### 9. Dependency/source provenance drift

Issues: `datahike-fork-is-28-commits-behind-upstream`,
`datahike-skill-pin-drifted-after-cache-cleanup`,
`malli-vendor-is-ahead-of-pinned-dependency`, and
`vendored-transit-clj-drifts-from-the-pinned-artifact`.

The structural change is a mechanical dependency ledger checked whenever a
coordinate or gitlink changes. That dissolves the Malli, Transit, Hasch, and
skill-current-pin drift. The 28-commit Datahike merge remains an independent
semantic integration because upstream and maintained schema/history policies
conflict; a version checker cannot decide that owner ruling.

### 10. Cross-cluster shared-resource isolation

Issues: `cohosted-clusters-share-one-unbounded-agent-heap` and
`root-compute-executor-has-no-per-cluster-fairness`.

Both require cluster identity at the shared process-root admission boundary.
Compute fairness is implementable in that seam. Retained heap isolation is a
larger owner design gate and may force a stronger process constraint; catching
`OutOfMemoryError` is not a resource boundary.

### 11. Debug/render evidence mismatch

Issues: `agent-pages-overflow-a-phone-viewport`,
`debug-left-pane-is-not-the-exact-prompt`, and
`debug-pages-invent-wedged-runs`.

The two correctness notes dissolve when debug reads the exact captured prompt
and the observed live-process set rather than rerendering current state or
inventing an empty set. Phone overflow is a genuinely independent CSS point
fix.

## Genuinely independent point or sweep work

These notes should not be advertised as if one broad architecture refactor
will close them:

- `ai-retry-proof-still-cites-the-deleted-run-lease` — update one shipped
  schedule explanation and regression against a current owner.
- `context-wave-leaves-three-small-honesty-defects` — its own note explicitly
  contains three independent edits; do not invent a shared mechanism.
- `admit-inst-overlap-prefers-collection-shape` — owner semantic ruling, then
  one ordering regression.
- `agent-pages-overflow-a-phone-viewport` — CSS containment.
- `duplicate-identity-refusal-evidence-is-unordered` — preserve input order in
  one refusal.
- `production-docstrings-teach-deleted-semantics` — source-documentation
  honesty sweep, not runtime machinery.
- `source-load-is-118s-against-the-ten-second-law` — measured development-loop
  incident; the dependency AOT design has its own classloader constraints.

`repl-parity-divergences` is also not one fix despite living in the SCI
cluster. Its recurring gate already partitions independent semantic families;
promote them one family at a time.

## Landed work that made open notes stale

These seven notes no longer describe unimplemented production work. Their
index destinations must say closure/archive reconciliation rather than name a
completed lane.

| Open note | Landing evidence | Current verdict |
|---|---|---|
| `malformed-sse-data-can-change-agent-code` | `cbaffa1f0`; current AI/stream tests assert `:seon.ai/unparseable-body` and terminal no-splice behavior | Acceptance implemented; archive after ordinary issue review. |
| `work-submission-can-block-before-its-time-limit` | `28540c431`; current `submit!!` refuses saturated capacity and the saturation regression is present | Acceptance implemented; archive after ordinary issue review. |
| `blob-get-assumes-file-store-callback-shape` | `02bc149da`; file and memory backend round trips are both recurring tests | Acceptance implemented; archive. |
| `schema-map-extraction-still-depends-on-position-two` | `fb968724a`; loop extraction calls `schema.form/map-entries` and tests both Malli shapes | Acceptance implemented; archive. |
| `keep-history-is-on-by-default-without-a-decision` | `db4efb4fd`; creation emits explicit `:keep-history? true` and the focused store proof reads it | Acceptance implemented; archive. |
| `datahike-allocates-a-konserve-cache-it-never-reads` | maintained Datahike `0e8601d7`, selected by `ccde63a4c` | Acceptance implemented; archive. |
| `fresh-cljc-files-are-jvm-only` | `290416d38` through `8d53e1c74` plus earlier loop conversion | Production portability claim fixed. Repair live skill citations that still name converted `.cljc` paths, then archive; the note's “no stale current docs” resolution sentence was too broad. |

Two more destinations are stale without the issues themselves being stale:

- `agent-renderers-never-enter-the-sci-program-context`: guarded kernel landed
  at `094127076`; schedule an acceptance/closure audit, not a design gate.
- `unlogged-findings-2026-08-01`: database write/read completion and the call-
  site sweep landed through `cced8d9a9`; retain only the interop-observation
  work or split the note.

## Schedule corrections implied by the triage

The index should stop naming completed or overly broad destinations. The
highest-value corrections are:

- completed fixes above → `landed-fix closure sweep` (the CLJC row additionally
  names its skill-citation repair);
- canonical open-map landing → `repository-wide open-contract wave` and
  `union discriminant design wave`;
- renderer design gate → `guarded renderer acceptance audit`;
- per-cluster live-graph umbrella → distinct `SCI acquire containment` and
  `contract-projection performance` waves;
- completed print path → `REPL-parity semantic follow-up`;
- stale `Core` rows → `SCI guard lifecycle`, `Flow lifecycle-report exposure`,
  and `pod/CLJS reader-closure deletion` waves;
- stale `general` rows → explicit interop, dependency pin-alignment, and REPL
  reload-coherence waves; and
- completed context/store waves → bounded residual follow-ups rather than the
  old lane names.

## Graduation consequence

The queue should not be executed issue-by-issue in index order. The next
structural portfolio is:

1. close and archive the seven landed fixes so severity counts stop lying;
2. seal the SCI invocation/acquisition boundary, with the guarded-renderer
   acceptance audit as the first falsifier;
3. run the repository-wide named/open contract wave;
4. publish lifecycle events and exact process identity instead of repairing
   each polling test separately; and
5. add the production-root/schema reachability census before the next deletion
   wave, then delete the exposed readerless closures together.

Final graduation remains the program's integrated gate, not this triage: a
quiet-tree full suite plus the required fresh-cluster/reset/live proofs for the
owners changed by each wave.
