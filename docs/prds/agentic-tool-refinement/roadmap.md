---
type: prd
status: active
tags: [prd, agent]
---

# Agentic tool refinement roadmap

## Outcome

Make Seon's ordinary functions usable by increasingly small models through
the normal dynamic namespace context. Graduate on a frozen representative
Inspect AI suite with at least 90% deterministic success overall, explicit
per-category floors, honest infrastructure-failure accounting, and durable
restart/read-back evidence.

## Current position — 2026-07-15

The lane began on a dedicated branch/worktree, but shared-checkout work is now
the repository default and its committed gains are reconciled into the main
checkout before further experimentation. Prior worktree and patch audits found
no safe missing source commit to cherry-pick: the stable planning, Inspect,
toolkit, and function-surface gains are integrated or superseded. Display-v3's
valid findings remain requirements for one database-derived, versioned export;
its renderer and synthetic-card implementations are rejected.

The isolated ACME checkout initially failed because pinned submodules and the
locked npm closure were absent. After initializing the selected dependency
sources and running `npm ci`, the current writer, `acme-client`, bootstrap,
CSS, watcher, writer, and pod built successfully. Worktree-specific operation
was retired after its committed source gains were reconciled. The lane now
uses the ordinary `acme` target from the shared checkout at port 7994; its
artifact flavor, database, process records, sockets, and logs remain isolated
from the default cluster without introducing another checkout or supervisor.

The first fresh ordinary-agent render is the current baseline:

- namespaces: 21,839 estimated tokens;
- canvas: 148;
- plan: 130;
- function menu: 258; and
- transcript: 157.

The namespace block correctly renders the current namespace in full and all
sixteen configured required namespaces as inert compact cards with public
function names, named arguments, complete input/output contracts, and schema
definitions. Its size and relevance have not yet passed the small-model test.

Static-URL Inspect runs now retain the evidence Seon already captures. The
pod response includes its final complete database coordinate and a stable
ordered turn bundle with stored rendered coordinates, exact prompt/reply bytes,
token estimates, and bounded errors; the Python solver preserves the bundle in
native `.eval` sample metadata. Focused Python and CLJS checks pass, and a live
ACME Qwen 3.5 2B BFCL smoke preserves four inspectable turns. The first reply
used the wrong JSON function identity and the next three were empty, converting
the prior opaque `:no-forms` score into actionable model/context/parser
evidence. The dependency ledger and proof are in
[[research/turn-evidence-retention-2026-07-15]].

That evidence exposed a benchmark-adapter contradiction rather than a model
selection failure. BFCL demanded bare JSON and said not to execute anything,
while Seon's stable system context requires every answer to be an executable
Clojure form. The adapter now asks for one existing lifecycle call whose string
argument is the JSON call array. On the identical `multiple_0` sample, Qwen 3.5
2B emitted one valid `complete` form, the runtime recorded one eval and closed
`:completed`, and BFCL's unchanged AST scorer returned 1.0 in seven seconds.
The exact one-turn evidence is in
[[research/bfcl-native-completion-2026-07-15]]. This proves the 24k-token prompt
did not prevent this selection; namespace weight remains an independent audit
for tasks that actually navigate or compose Seon functions.

The first live namespace audit localizes the excess. The exact ACME projection
for `metal-hairs-lose` contains 22,106 namespace tokens. `seon.db` is 3,966,
filesystem 2,732, plan 2,309, shell 2,085, and canvas 1,695. Schema records and
referenced closure dominate: 508 rendered schema lines cost about 16k tokens,
while exact duplicate definitions account for only 988. More importantly,
compact cards currently treat every public implementation function as an agent
capability. `seon.schema` advertises projection activation, registry reset,
snapshots, and rollback beside `register!`; `seon.db` advertises boot,
provenance, listener, transaction-scope, schema-bridge, and raw-entity internals
beside query/pull/transact.

Structural eligibility is now implemented for the core surface. Agent-facing
function declaration is colocated metadata persisted as an optional positive
`:seon.fn/agent-facing?` fact on the ordinary program entity. Boot indexing,
eval tee, drift repair, compact namespace cards, and both menu paths share that
fact; current namespace source stays full. Exact `seon.db` and `seon.schema`
inventories prevent internal projection/eval/database mechanics from silently
re-entering context and retain the standard current-agent accessor. The focused
gate passes 66 tests and 319 assertions across indexing, tee persistence,
namespace cards, and menus. `my.ns/functions` now answers its explicit "what
can I call?" question from the same fact while full namespace inspection keeps
the complete program graph reachable. Domain/entity schemas retain a positive
data-model inclusion rule. No renderer blocklist or benchmark-specific exclusion
list is admitted.

The downstream handback review retains only `acme.brand/tagline` and
`acme.widget/set-location!` as ACME tools. Canvas renderers, deliberate failure
probes, and intentionally unspecced indexing fixtures remain program data. The
rebuilt live database contains 1,034 indexed functions but only 114 eligible
ones. `metal-hairs-lose` renders 20,406 namespace tokens, down from 22,106;
`seon.db` fell from 36 advertised functions to 15 and `seon.schema` from 24 to
seven. The exact audit and acceptance evidence are in
[[research/namespace-surface-audit-2026-07-15]].

The first policy unit now resolves run ceilings through one config manifest
section and persists three scalar singleton facts. The live isolated database
reports 100 batch turns, 300 stream forms, and a 1,800,000 ms deadline; both
`open-run!` and the idle readline query that frozen database policy. The
manifest values support Aero environment overrides without making the runtime
read environment state.

The AI transcript now rotates whole raw-history chunks instead of compacting
or sliding one event per turn. With the current database policy it retains 50
turns, drops the oldest 25 at each boundary, keeps a 25-turn HTML window, and
charges the settled chunk against 8,192 estimated tokens using each complete
rendered event. Result bodies decay `0→4096`, `2→1024`, and `5→512`; complete
facts and blobs remain queryable. Focused config, run, and transcript tests
pass 41 tests and 174 assertions, and the rebuilt ACME pod reports the exact
policy datoms through the repository REPL boundary.

The first combined default-cluster restart exposed a warm-schema edge: the
transcript fallback resolved policy keys through Malli's last activated
database projection, which could predate the new `:default` properties. The
fallback now compiles the current `seon.schema` declaration itself. The full
CLJS checkpoint passes 911 tests/4,645 assertions, and an existing default
database now restarts, resumes both agents, and serves `/` without the prior
nil retention-window core fault.

The config-apply operator defect is now fixed through one live pod operation
shared with boot. An already-ready target resolves the selected manifest once
through the canonical Aero reader and reconciles only routes, skills, and the
config singleton. The state compiler canonicalizes an empty cardinality-many
input to database attribute absence while preserving presence-sensitive
comparison for stored facts. Two unchanged live applies wrote zero operations;
an intentional policy delta and restoration each wrote two operations; and the
watcher, writer, and pod PIDs stayed byte-for-byte stable. Evidence and the
dependency ledger are in [[research/live-config-apply-2026-07-14]].

A subsequent purity probe found that historical result-handle display depends
on a process-local cache. The target is now explicit and falsifiable: the same
agent and resolved database coordinate regenerate a byte-identical cacheable
body across delay and pod restart. A root-only free dynamic tail may carry live
clock, Unix 1/5/15-minute load averages, and bounded process memory after every
cache boundary; prompt blobs preserve those exact ephemeral bytes.

Top-level context ordering is also still manual. The renderer sorts blocks by
stored integer priority even though namespace/surface recency and block-chain
hash mechanisms already exist. The candidate general policy records per-turn
block hashes and sizes, estimates change probability from that database
changelog, and sorts within semantic bands by change risk per cacheable token.
Orders freeze for measured epochs with hysteresis so the optimizer cannot
create more cache churn than it removes.

## Experimental contract

Inspect AI owns all simulations, tasks, solvers, and scorers. The lane freezes
ordinary system prompt and context-block prose during a tool-surface experiment.
Permitted refinement surfaces are namespace placement, default requires,
function identity, line-one description, argument/key names, complete Malli
input/output schemas, honest envelopes, and consolidation of overlapping
functions in their existing owner.

Failures are classified as tool absent, tool not required, wrong selection,
unclear identity, unclear description, opaque schema, unclear arguments,
overlap, misleading envelope, unactionable error, missing fact, plan failure,
verification failure, sandbox/bridge failure, model reasoning failure, or
benchmark/scorer failure.

## Ordered work

1. Audit the live ordinary-agent namespace surface by namespace, callable,
   schema closure, repeated tokens, public/internal eligibility, and normal
   task category. Preserve full contracts while removing only proven noise or
   duplication through the one renderer/program graph.
2. Inventory the installed Inspect and inspect-evals catalogs, exact local
   model/provider client, and selected sandbox implementation. Freeze small
   deterministic development, milestone, and blind memberships before tuning.
3. Establish raw-model and unchanged-Seon baselines with a 4B-or-smaller model,
   then probe 3B, 2B, 1.5B, and sub-1B models where locally practical.
4. Compare `:batch` and `:stream` as first-class execution strategies, including
   task outcome, calls, attempted forms, generated tokens, elapsed time, cache
   reuse, fabrication, and recovery. Measure transcript decay/plateau schedules
   from live rendered prompts; never infer success from aggregate token cost.
5. Cluster failures and change the smallest current owner. Verify through a
   focused mechanical test, the original live ACME REPL form, and the exact
   failed Inspect samples.
6. Compare equal-budget arms: no explicit plan, small-model-authored plan,
   large planning proposal encoded by the small executor, and the optional
   pretransacted diagnostic plan. Prove database outcome, provenance,
   expectation-checked close, report-before-close, and restart resumption.
7. Freeze the surface, open the blind set once, preserve raw logs, dataset and
   dependency locks, model/artifact identity, scorecard, classifications, and
   ACME restart/read-back evidence.

## Open blockers

- `inspect-source-dependency-is-not-content-pinned.md` — the mutable local
  Inspect source dependency prevents reproducible scored claims.
- `inspect-live-cluster-caller-drift.md` — concurrent per-sample live clusters
  still need the operator's ownership-fenced lease and coordinate contract.
- `autocomplete-data-quality-pipeline-drift.md` — runtime and Inspect need one
  structured, versioned, schema-closed export.
- `deprecated-skill-render-functions-indexed.md` — stale public functions remain
  eligible distractors.

No blocker authorizes another harness or context-coaching path.
