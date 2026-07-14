---
type: issue
status: open
severity: friction
tags: [issue, agent, database, research, schema]
---

# Autocomplete datasets and scoring bypass canonical runtime projections

## Problem

Autocomplete training and evaluation artifacts are assembled by scratch
Needle parsers and scorers that no longer reproduce the runtime's database-
derived context and function-card formats. There is no one versioned export
contract that closes referenced schemas, freezes held-out membership, stages a
real database world, and feeds the canonical Inspect/data-quality path.

Consequently prior scores are not comparable evidence, held-out rows can drift
while being transformed, and a model can be trained or judged against cards
that the runtime never shows. Autocomplete training remains paused until this
issue's data gate is satisfied.

## Evidence

- `src/seon/repl/autocomplete.cljs` renders serving cards through
  `seon.agent.ctx.namespaces/compact-fn-head` over as-of `:seon.fn` rows and
  stamps only a projection Git SHA. Its export does not carry a format/schema
  version, referenced-schema closure, immutable split manifest, or staged-world
  verdict.
- `src-needle/src/seon_needle/kt1_envelope.py::compact_card` performs a
  string-aware brace deletion over rendered cards, while
  `src-needle/scripts/kt3_redux.py::render_card` constructs fake `(defn ...)`
  cards from a JSON function index. `src-needle/src/seon_needle/build_v2.py`
  applies the former again while changing row membership and target shape.
  These are parallel projections, not consumers of the runtime card format.
- The current runtime has a referenced-schema closure in
  `src/seon/agent/ctx.cljs`, but the scratch exports and fake-card renderers do
  not consume that closure. A function spec can therefore name schemas whose
  definitions are absent from the exported model input.
- `data/tune/acme-2026-07-12.jsonl` began as 214 held-out rows; subsequent
  reports refer to 213-row v2 artifacts and transformations that drop or alter
  rows. The split has contamination checks, but no canonical immutable row-id
  manifest that makes membership changes explicit and reviewable.
- `docs/prds/repl-autosuggest/research/fair-scoring-2026-07-12.md` depends on
  `src-needle` parsers, a pinned worktree, and an isolated audit harness.
  `research/inspect-harness-integration-2026-07-14.md` records its useful
  measured conclusion but explicitly rejects importing that implementation;
  it must be rebuilt in the canonical data-quality pipeline.
- The LoRA audit found 149 of 557 retained pairs hard-failed the live REPL.
  Text-only and `:seon.eval/ok?`-only curation therefore cannot certify gold
  trajectories.
- The stable worktree's untracked continuation probe found that a balanced
  first-form stop improved clean single-form shape from `.19` to `.81`, but
  head accuracy on the small slice remained approximately zero. It also found
  42 of 213 bundles begin with mechanical `in-ns`. A serving stop primitive
  therefore cannot substitute for a versioned target/projection contract, and
  namespace bookkeeping must not silently define a continuation score.
- The dirty stable fair scorer passed its predeclared creative-alternative and
  real-error cases and moved the audited frontier from `.264` to `.436`, but it
  consumes the retired pin and obsolete text-card grammar. Its behavior is a
  specification to reproduce through Inspect, not a second scorer to import.

The source/evidence audit inspected the current `export!` row precisely. Its
metadata contains turn id, agent id, bare basis `t`, a database-name string
derived from the cluster directory, projection Git SHA, coverage, and optional
rating. It does not carry the architecture's complete
`{database-id, branch, commit-id, t}` coordinate, artifact/config/profile and
renderer identities, tree-dirty/content identity, target projection mode,
referenced-schema closure, frozen row/split identity, current-world replay
verdict, or an addressable rejection record. Dated default output filenames
also make the filesystem path wall-clock-derived rather than content-addressed.

The historical typeahead corpus has useful prompt/reply blob hashes and
verbatim sections, but it likewise names only cluster `acme` and reads blobs
from a checkout-local path. It is calibration evidence, not the canonical
export requested here.

## Owner

The database-derived autocomplete exporter and the canonical
`src-inspect-ai` data-quality/scoring path together own one versioned artifact
contract. `src-needle` may consume that artifact for model work, but must not
parse source, invent cards, choose split membership, or own a second scorer.

## Acceptance

- One schema-registered export request/response produces a versioned manifest
  plus rows from explicit as-of database values. The manifest records runtime
  artifact/config identity, projection version, database identity and basis,
  renderer/profile version, row identities, content digests, split assignment,
  and rejection reasons; repeated export at the same basis is byte-identical.
- The complete coordinate includes database id, branch, commit id, and `t`;
  content identity also binds the dirty-tree/source closure, runtime artifact,
  config/profile/renderer, dependency locks, and export schema version. The
  default artifact name is derived from content, not the wall clock.
- Every target declares its projection semantics. Observed historical bundles,
  counterfactual re-projections, and substantive next-form targets are distinct
  modes; `in-ns` or other harness-owned bookkeeping is never silently added to
  or removed from the scored target.
- Exported cards are exactly the runtime's inert compact-card representation.
  Referenced schemas are closed through the program graph and emitted once by
  the same runtime mechanism; no Python/Clojure fake-`defn`, brace stripping,
  source parser, or static function-index renderer remains in the active data
  or scoring path.
- Held-out membership is frozen by a reviewed manifest. Every derived format
  preserves those row identities and split assignments; a drop, addition, or
  reassignment is a new version with an explicit reason. Training inputs prove
  disjointness against the frozen held-out identities and content canaries.
- Candidate trajectories are staged from real database facts, rendered through
  the serving context path, executed through the current eval boundary, and
  retained only with database-derived outcome evidence. Rejected trajectories
  remain evidence rather than silently disappearing.
- Every rejection is an addressable row with source coordinate, attempted
  target, reason class, and replay evidence; aggregate skip counters are a
  projection, never the only retained record.
- Inspect owns layered parse/schema/eval/productivity/history results and the
  deterministic oracle/judge calibration. The prior fair-scoring acceptance
  cases pass there, and historical model results are labeled non-comparable
  unless regenerated from the canonical artifact.
- A future continuation arm may use a bounded string/comment-aware first-form
  stop, but it consumes the same artifact and Inspect scorer. It does not own a
  second renderer, dataset, eval path, or metric.
- A clean held-out export and scored baseline pass the data-quality gate before
  autocomplete training is deliberately resumed.
