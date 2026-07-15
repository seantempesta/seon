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

The first admitted database-workflow sample is now gated on a symmetric run
identity rather than a start-only assertion. The common native Inspect door
rechecks the complete selected-source admission and static target after Inspect
publishes its terminal log, records both observed end identities in that same
`.eval` through Inspect's public edit API, and retains rejected terminal
evidence before raising on either drift. `bb.edn` joins the admitted operator
inputs, and `:quiesced` is classified as infrastructure rather than model
capability. The focused boundary passes 99 tests. A clean ACME restart then
admitted one Qwen2.5 Coder 0.5B database sample at Seon revision `8bae7ae9`.
Start/end source and complete static-target identities were equal. The model
repeated only its namespace header across three turns, produced no executable
forms, and closed `:no-forms`; the unchanged scorer returned incorrect with no
fabrication. Human review recorded `model reasoning failure` in Inspect score
history without changing the score. The retained prompt estimates were 22,191,
22,864, and 23,530 tokens. Exact evidence is in
[[research/qwen25-coder-05b-database-diagnostic-2026-07-15]]. The remaining
operator provenance gap is one
canonical process/status digest over the full operator closure, recorded in
[[../../seon/issues/inspect-live-cluster-caller-drift]].

That baseline closed the scheduling gate for the shared callable-contract fix.
One pure-variadic implementation body no longer overwrites several logical
Malli arities by vector position; each logical schema owns its argument labels,
and Clojure's `&` binding marker is never rendered as an argument name. The
first `query` and `transact!` request inputs are named in their colocated Malli
schemas. Compact namespace cards, menus, `my.ns/functions`, and autocomplete
all consume this one projection. The focused projection/bootstrap gate passes
five tests and 32 assertions; a rebuilt database projection and exact sample
replay are the remaining live proof for this unit.

Failure review now remains inside the same native Inspect artifact. One
scorecard operation accepts—but never infers—exactly one frozen taxonomy label,
merges it into the incorrect capability score's existing oracle metadata, and
uses Inspect's `ScoreEdit` provenance/history without changing the score or
aggregate metrics. Passing scores reject failure labels. A real offline native
round trip proves classification, source admission, end source identity, and
end target identity coexist after write, finalization, copy, and read-back.
The focused native-run, classification, admission, cluster, and milestone gate
passes 127 tests.

The pre-run model reconciliation corrected two provenance assumptions. The
0.5B listener on port 18081 is gone and must be restarted as an owned dedicated
listener. MLX-LM switches models from each request, so neither `/v1/models` nor
the process command proves immutable loaded bytes; every arm instead uses the
same absolute Hugging Face snapshot path in its dedicated server and Seon
database model value. The prior local Qwen 3.5 2B quant has vanished and is
replaced in the candidate matrix by the complete revision-pinned BF16 snapshot.
Exact evidence and order remain in [[research/local-model-serving-inventory-2026-07-15]].

The formal local-model boundary admits one immutable MLX identity through
the existing native-run start/end admission and retained-log finalizer. It
hashes the complete revision-pinned Hugging Face snapshot, server module, exact
argument vector, and serving package tuple while checking a stable PID/start;
the common capability gate joins every attempt to the full request endpoint
and absolute snapshot path, then joins each successful response model and
fingerprint. Externally mutable services and Ollama tags remain diagnostic
until they can prove loaded bytes. The focused cluster/catalog/solver/log gate
passes 110 tests. The source design and falsifiers are in
[[research/model-server-identity-audit-2026-07-15]]. The dedicated listener now
participates in one finalized and reopened native `.eval`; immutable transport
admission is closed for this arm.

The lane's dedicated 0.5B listener completed that formal replay. Source,
static-target, and model-server identities agree at start and end; all three
successful attempts join the exact endpoint, absolute snapshot, response
model, and fingerprint. The model produced three bounded narration evals with
empty source, closed `:no-forms`, scored incorrect with zero fabrication, and
was reviewed as `model reasoning failure`. Exact evidence is in
[[research/mlx-live-wiring-audit-2026-07-15]]. This closes the admission and
transport proof while leaving the database-workflow correctness proof open.

The admitted native runner now bridges its validated source, static-target,
and model-server start maps into every sample state through Inspect's public
task-setup mechanism before task-owned setup runs. Eval metadata and sample
metadata exact-merge the same immutable maps: equal values are accepted and a
contradiction fails before task setup can act. One real finalized/reopened
Inspect log proves complete attempt evidence and model identity reach the
unchanged admission gate and scorer. The older frozen-tool scorer proof now
uses this common admitted door instead of calling `inspect.eval` directly.
The combined focused catalog, native-run, and frozen-tool gate passes 57 tests.

The pinned Inspect source audit also identifies seven reusable mechanisms for
later tool work: database-native namespace exposure, frozen-coordinate
reconstruction, durable multi-form positions, bounded typed recovery,
addressable stream cancellation, real approval boundaries, and retained native
scorer evidence. These are experimental inputs rather than permission to add a
second harness or synthetic tool registry. The exact source examples and Seon
translations are in [[research/inspect-tool-building-examples-2026-07-15]].

The frozen database-workflow scorer no longer accepts a token-level sketch of
the task. Its host-only oracle metadata now retains the five records and
threshold while leaving sample id and prompt bytes unchanged. Correctness
requires both typed schema registrations (including unique identity), all five
records in one transaction, a later strict-threshold query, and separate human
and completion reports containing 327. The focused freeze/oracle/native-run
gate passes 151 tests. Formal P0b now survives admitted live execution and
native-log read-back, but the model did not perform the transaction or query.
The scorer correctly failed closed, so a successful bounded proof is still
required to close
[[../../seon/issues/database-workflow-scorer-lacks-query-result-evidence]].

That evidence contract is implemented through the existing global database
observer rather than a benchmark hook. Awaited evals retain ordered normalized
read and transaction observations; nonempty vectors are serialized as stable,
round-trippable EDN through `my.blob` and attached to the eval in its accepted
transaction. The composition door derives request eval membership and order
from the final immutable database value, validates the blob and operation
coordinates, and exposes only bounded tagged JSON or a bounded failure status.
The generated database scorer now requires the exact successful transaction
and later scalar query result from the request's turns. Three focused CLJS
selectors pass seventeen assertions and the focused Inspect modules pass
fifty-three tests. The admitted live sample proves that canonical absence
survives native-log read-back without being mistaken for success; a later
capable-model sample must supply the positive transaction/query graduation
proof. Exact falsifiers and ownership are in
[[research/database-query-result-evidence-audit-2026-07-15]].

Model transport evidence has the same intent-versus-execution distinction. A
final coordinate can reconstruct final database intent but cannot prove an
earlier provider attempt. `seon.ai/resolved-config` derives endpoint, adapter
timeout, credential-source name, diffusion backend, and extra-body digest with
per-key provenance from any immutable database value. Each request attempt now
captures one such value and coordinate before dispatch, passes that immutable
resolution through the adapter, and persists an ordered turn-owned component
fact with request identity, effective timeouts, outcome, and bounded response
identity. Retries may observe a later database value, but no attempt can mix
two configurations. Historical and split-read tests cover that distinction.
The remaining boundary makes the evidence bounds database-configured within
the same immutable resolution and projects the facts through the composition
door into fail-closed Inspect admission. Grounding and falsifiers are in
[[research/model-transport-evidence-audit-2026-07-15]].

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

The batch/stream decision is frozen as an experiment rather than a preference.
Batch is the hypothesis to beat because one large stable prompt can carry
several sequentially awaited forms; OpenAI-compatible stream already cuts off
at the first parser-confirmed executable form and may win on feedback and
fabricated-tail avoidance. A valid comparison first needs durable per-turn eval
positions, addressable cancellation through the blocking Inspect door,
exact-prefix cutoff evidence, and database-owned no-form/repetition limits. The
paired native design is in [[research/batch-stream-cutoff-audit-2026-07-15]].

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

## Execution ledger

This ledger is the resumption authority. Work follows dependency gates rather
than whichever local failure is most interesting. Independent rows may run in
parallel against the isolated ACME cluster or read-only source, but only one
row owns a source namespace or cluster lifecycle at a time. Every row begins
with its dependency ledger and ends with committed evidence in `research/`.

### P0 — Freeze the measurement contract

This is the first blocking unit. Select and record deterministic development,
milestone, and unopened blind memberships from Inspect and inspect-evals. The
development slice covers database inspect/query/aggregate, schema
registration, store/update/retrieve/verify across turns, namespace navigation,
function composition, filesystem/shell/web work, planning and restart, and a
final evidence-backed report. Preserve upstream datasets and scorers. Record
the failure taxonomy, per-category floors, overall 90% graduation target, run
budget, seed, and serial execution rule.

Exit evidence is one reproducible serial ACME run whose native `.eval` retains
the selected task identity, exact prompt/reply bytes, Seon database and turn
coordinates, artifact/config identity, model identity, and classified result.

P0 is split into two gates. P0a freezes membership, scorers, floors, and
failure classification without running the suite. P0b is the first complete
serial run and depends on the static-target portion of P1. Native milestone
tasks are runnable serially through the admitted static target. Planning and
per-sample paths remain fail-closed at `ClusterLeaseUnavailable` until the
operator lease exists. Do not treat task construction or a legacy Python row
run as P0b evidence.

P0b is recorded by the finalized `database_workflow-seed1-000` artifact at
Seon `74530d90`. It is a valid classified incorrect result, not a task pass:
all admission and evidence checks succeed, while the model emits no forms and
the scorer preserves exact operation-evidence absence.

### P1 — Establish reproducible execution

P1 begins beside P0 and must finish before comparative claims. Content-pin the
Inspect and inspect-evals sources and record provider and model identities.
Define the one ownership-fenced operator lease used by future concurrent
samples; until it exists, scored runs remain serial against the static ACME
target. Keep native Inspect `.eval` output as the evaluation authority.

Exit evidence is replay of the P0 sample from its recorded identities and
coordinates, plus cleanup and restart proof without cross-sample state drift.

P1 has two separately visible boundaries. P1a makes serial development honest:
strict source/lock identity, mandatory native-log finalization, an explicitly
owned static ACME URL, and bounded pod-provided evidence. P1b adds the
operator-owned, token-fenced per-sample lease needed for parallel runs and
isolated restart claims. P0b may proceed after P1a; comparative concurrency
and graduation may not proceed before P1b.

### P2 — Measure context and model baselines

After P0 membership freezes, two independent measurements proceed in parallel:

- Measure the exact schema closure, definitions, references, repeated bytes,
  and token contribution in every selected prompt. Design one database-derived
  structured namespace export with shared schema closure; do not optimize from
  the aggregate 20,406-token number alone.
- Verify the installed MLX and Ollama endpoints and exact model artifacts. Run
  unchanged-Seon baselines across practical sub-1B, 1.5B, 2B, 3B, and
  4B-or-smaller coding/agentic models. Use Meta Muse or DeepSeek only as a
  stronger sanity-check and planning baseline. Do not select a preferred small
  model from one BFCL sample.

Exit evidence is a per-sample context inventory and a comparable raw baseline
matrix using the frozen development slice.

### P3 — Improve the global context mechanism

P3 consumes P2 measurements. Implement shared schema presentation only when
it preserves complete function names, named input arguments, output data, and
the relevant transitive schema closure. Current namespace source remains full;
required namespaces remain compact and complete. Menus, namespace cards,
autocomplete, and Inspect consume one structured database-derived export.

In the same unit, prove that an identical database coordinate renders
byte-identical cacheable context across delay, cache eviction, and pod restart.
Process-local result handles may not affect historical rendering. Live clock,
Unix 1/5/15 load averages, and bounded memory belong only in the uncached tail.

Exit evidence is focused mechanical coverage, byte comparison, live ACME REPL
inspection, and exact reruns of affected P0 samples. Record both token change
and task outcome; size reduction alone is not success.

### P4 — Compare execution and parser behavior

With P0–P3 stable, compare `:batch` and `:stream` on identical tasks and
budgets. Measure outcome, calls, attempted and accepted forms, generated
tokens, elapsed time, cache reuse, fabrication, recovery, and safe early
cutoff. Explicitly test multiple independent forms in one turn and next-turn
result visibility.

Exercise malformed delimiters, empty replies, prose adjacent to forms, ghost
result echoes, large values, and thrown agent mistakes. Recover unambiguous
prose as comments through the reader rather than regex rewriting. Agent errors
remain bounded data; core faults still follow the configured crash policy.

Exit evidence is the equal-task batch/stream table, parser fixtures, bounded
transcripts without stack or source dumps, and the selected default justified
by outcomes rather than latency alone.

### P5 — Compare planning and transcript policy

Run equal-budget arms with no explicit plan, a small-model-authored database
plan, a Muse/DeepSeek proposal encoded by the small executor, and an optional
pretransacted diagnostic plan. Each task proves stored expectations, database
outcome, provenance, report-before-close, and restart resumption. Retain solid
plans as ordinary Inspect fixtures.

Measure the configured 50-turn window, 25-turn chunk rotation, 8,192-token
settled plateau, and 4,096/1,024/512 result decay against these runs. Tune only
from retrieval, repetition, cache-prefix stability, and completion evidence.
Evaluate database-changelog block ordering with epochs and hysteresis only
after byte-identical rendering is proven.

Exit evidence is the planning-arm scorecard, restart read-back, transcript
schedule comparison, and a database-derived policy recommendation with no
hardcoded runtime numbers.

### P6 — Refine tools from clustered failures

Cluster frozen-slice failures by the experimental taxonomy. Fix the smallest
existing owner: namespace requirement, colocated schema, function identity,
argument name, return envelope, query tuple legibility, overlap, or missing
capability. Remove or combine functions when that makes the surface more
discoverable. Add a function only after a frozen task proves the gap. Standing
context prose changes only when false or when the batch/multi-form experiment
proves that non-derivable execution semantics are absent.

Each change requires the focused test, original REPL reproduction, exact failed
Inspect sample, relevant development slice, live ACME proof, issue disposition,
documentation update, and one coherent commit.

### P7 — Graduate

Freeze the tool and context surface, run the milestone set, then open the blind
set once. Preserve raw native Inspect artifacts, dependency and dataset locks,
model and provider identities, context coordinates, scorecard, classifications,
batch/stream evidence, planning comparison, and restart/read-back proof.
Graduate only at 90% deterministic success overall with the recorded
per-category floors and no infrastructure failures counted as model failures.
Prove both the isolated ACME fresh-checkout path and the default cluster after
coordinating lifecycle ownership.

### Morning checkpoint

The intended first overnight checkpoint is P0 frozen and run once, P1 identities
pinned with serial reproducibility recorded, and both P2 inventories complete.
If those gates finish early, continue through P3 and exact development-slice
reruns. Do not skip a gate to accumulate broad model runs, redesign unrelated
tools, run the entire test suite, or chase a failure outside the selected
sample and its current owner. Record an external discovery as one issue with
evidence and return to the ledger unless it blocks the active gate.

## Resumption packet — 2026-07-15

Read this short operational handoff before logs, broad tests, or source
archaeology.

**Active gate:** close the second global callable-contract defect, rebuild the
committed projection into ACME, prove the database-indexed `query`,
`transact!`, and canvas-control contracts from one immutable database value,
then replay the exact admitted
`database_workflow-seed1-000` sample. Do not run the ten-member slice or model
matrix until that controlled before/after pair is retained.

**Last durable lane checkpoint:** `de5e606d` archives the stale diagnostic-
schema test after `6c597259` retained and classified the first admitted 0.5B
sample and fixed the shared callable projection. Later shared-tree commits may
exist and are not automatically part of this lane. Always re-read
`git status --short` and `bin/acme status --edn`; never infer ownership or
readiness from this paragraph.

### Proven and committed

- The proposed development slice has ten exact members and fixed category
  floors in [[research/inspect-suite-freeze-2026-07-15]]. Its membership is
  frozen; its native execution path is not yet complete.
- Native Inspect wrappers for the shell, file, and web rows and generated
  database/namespace workflows are committed. Focused offline coverage passes;
  their existence is P0a evidence, not a P0b run.
- Inspect, Inspect Evals, dataset, Python-lock, pod-client, model, and operator
  identity gaps are localized in
  [[research/inspect-reproducibility-boundary-2026-07-15]]. The Inspect view
  uses an intentional nested overlay: `ts-mono` is at `f3588038` while its
  parent records `eccde6b7`. The source lock verifies both coordinates, the
  nested tree, and cleanliness; do not reset either commit or replace this
  with an excluded pathspec.
- Local serving inventory is recorded in
  [[research/local-model-serving-inventory-2026-07-15]]. No preferred small
  model has been selected. The live candidates are evidence inputs, not a
  leaderboard conclusion.
- Source admission pins the Inspect parent, nested overlay, tree and
  cleanliness. Native-log finalization reopens the retained `.eval`, requires
  success, and verifies the exact admission map. Pod `/agents/run` evidence is
  request-scoped and comes from the same final immutable database snapshot.
- The admitted Seon closure now includes actual runtime build inputs: source,
  operator scripts, resources, ACME overlay, build declarations, and package
  locks. A concurrent dirty runtime file or commit rejects the run; the first
  live preflight did so instead of scoring through mixed watcher bytes.
- Standard benchmarks and Seon-native tasks now share the admitted evaluator.
  The native path admits before task construction, preserves the task's own
  solver/scorer, enforces serial execution, and makes finalized log read-back
  mandatory. It also records the operator's exact ready target EDN and requires
  byte-identical artifact/process/endpoint identity after the sample. The
  83-test focused gate includes a real native-log read-back.
- Operator readiness now hashes the current flavor-owned client output and
  Shadow runtime closure against the process's admitted artifact identity. A
  live ACME probe changed from false-ready to degraded after concurrent hot
  reloads; structured status retained the non-secret artifact/environment
  digests and PID start identity needed to explain the transition. The focused
  operator gate passes 25 tests and 95 assertions.
- The Inspect composition door no longer substitutes a hardcoded five-minute
  timeout for the database-owned run deadline. An absent `timeout_ms` follows
  the same global/agent precedence as `open-run!`; an explicit Inspect value
  remains part of the experiment. The common Python solver and its milestone
  and planning callers now preserve that absence instead of injecting their
  former 300-second fallback. Focused gates pass 15 CLJS tests/65 assertions
  and 78 Inspect tests; the live ACME database resolves 1,800,000 ms. The pod
  response and native sample metadata now retain that effective value and its
  `request` or `database` source as derived evidence.
- Admitted execution now retains native terminal evidence when Inspect is
  interrupted or returns no accepted logs. It diffs the selected log directory,
  copies only newly published artifacts, reopens them with exact source
  admission, preserves truthful non-success status, and rejects capability
  scoring. Focused coverage passes 42 tests; a real OS-SIGINT probe retained
  one cancelled `.eval` with its partial sample.
- Infrastructure timeouts and core errors invalidate a sample instead of
  becoming a model score. A no-forms close remains model/runtime evidence.
- A fresh ordinary ACME agent renders 22,171 total estimated tokens, including
  20,406 namespace tokens. The exact closure audit is in
  [[research/context-schema-closure-measurement-2026-07-15]]: schemas account
  for 15,155 tokens and one canonical shared section saves 1,642 tokens while
  preserving the closure. This supports a stable shared-schema topology, not
  a claim that size reduction alone fixes model behavior.
- The first dirty Qwen2.5 Coder 0.5B database diagnostic is preserved in
  [[research/qwen25-coder-05b-database-diagnostic-2026-07-15]]. It produced
  zero forms, repeated runtime/context narration, and grew from 21,947 to
  23,201 prompt tokens over three turns. Parser recovery stayed bounded. The
  run is deliberately **not accepted** because Inspect reported dirty Seon
  source and the direct task invocation bypassed run-level admission.
- The corresponding clean admitted replay is retained at revision `8bae7ae9`.
  Source and static-target identities are equal before and after. Three replies
  repeated only the agent namespace header, created three bounded narration
  evals with empty source, and closed `:no-forms`; the score is incorrect with
  zero fabrication and reviewed classification `model reasoning failure`.
- The global callable projection now separates one physical pure-variadic body
  from several logical Malli arities, uses named request inputs for `query` and
  `transact!`, and omits `&` as an argument label. Five focused tests pass with
  32 assertions. A clean ACME reindex and exact replay remain before/after
  proof, not additional design work.
- The complete database-derived tool audit at
  [[research/tool-namespace-colocation-audit-2026-07-15]] enumerates 114
  eligible functions across eighteen namespaces. It distinguishes real
  reachability/eligibility problems from namespace movement: root's configured
  orchestration namespace exposes no eligible functions, `my.ns` and
  `my.skills` have no home edge, ACME requires empty fixture namespaces instead
  of its real downstream tools, sample-domain `my.kb` functions polluted the
  standing surface, and filesystem presents overlapping generations. Positive
  eligibility is now removed from the eleven sample-domain functions while
  their public source and schemas remain inspectable; exactly `remember` and
  `recall` remain general standing knowledge tools. The audit also found a
  second global correctness defect: four one-argument canvas controls rendered
  returned Hiccup as a phantom positional arity. The shared source-arglist
  indexer is repaired, and the focused gate proves those four controls expose
  exactly one callable input. Reachability and filesystem policy changes wait
  for their controlled falsifiers.
- The reachability follow-up reduces those namespace failures to two positive
  database facts: a persisted home require edge makes a compact card resident,
  and `:seon.fn/agent-facing? true` makes a function callable inside it. Root
  already has its `seon.agent` edge but no eligible operation; ordinary agents
  lack edges to eligible `my.ns` and `my.skills`; ACME points at empty fixture
  namespaces instead of its eligible product owners. Home requirements are
  copied at birth, so manifest application does not retrofit an existing agent
  and every ordinary/ACME before-after row must mint a fresh one. Exact queries
  and four fixed trajectories are in
  [[research/namespace-reachability-falsifiers-2026-07-15]].
- The filesystem follow-up ranks the twelve-function standing surface by
  likely small-model gain. Its first measured unit converges
  `read-file`/`view` onto one bounded SHA-bearing read, retains anchored
  `replace!`/`insert!`, retires the unfenced legacy `edit-file`, and unifies
  four incompatible result dialects. Existing-target `write-file` then needs
  the same SHA fence; `file-exists?` and `home-dir` currently erase useful
  failure or grant facts. Four no-coaching workspace rows freeze the selection
  and outcome evidence before any source change. The full inventory, schema
  cost, and task bytes are in
  [[research/filesystem-surface-audit-2026-07-15]]. These changes remain after
  the first admitted evidence replay, not an excuse to displace P0b.
- One OpenAI-compatible attempt now consumes one immutable resolved config
  value. A fake provider call captured config A, transacted ambient config B,
  and retained A for endpoint, credential source, model, sampling, thinking,
  timeout, extra-body digest, and secret-free response evidence. Ordered
  turn-owned attempt components retain coordinates, request identity,
  effective adapter and outer timeouts, outcomes, and present provider response
  identity; zero temperature and false thinking remain present values. The
  database-configured caps now come from that immutable request resolution.
  The final composition door reads its display bound once from the frozen final
  database, re-derives adapter identity from each attempt's historical
  resolution, and re-derives stream mode from the linked turn's frozen rendered
  coordinate. Exact transaction-origin validation rejects random, sibling, and
  unretained commit ids for both model and database-operation evidence. The
  focused source gate passes 40 tests and 218 assertions across config, web,
  and retry/attempt persistence. Immutable model-server weights identity and
  immutable model-server identity. The admitted live read-back now closes the
  transport gap; model-revision equality still does not prove mutable serving
  state for services without a content-pinned process observer.
- One awaited database-operation observer spans eval plus auto-await and
  retains ordered normalized reads and transactions with explicit position,
  success, source classification, and complete coordinate. Canonical blob
  persistence and atomic eval attachment preserve absence for no-operation
  evals. The final-snapshot door projection is bounded and lossless for
  supported EDN, and the generated scorer fails closed without the exact
  transaction and later query result. The admitted live sample correctly
  preserves operation-evidence absence, so a successful transaction/query
  sample remains the one correctness proof for this contract.
- Failed eval diagnostics now retain the database-configured 1,500-character
  component cap under no flag, `full?`, escape-clipping, or both. Successful
  authored/citable content keeps its existing release behavior. A 100K
  malformed line is windowed around its exact parse coordinate before render;
  raw replies and error evidence remain separate and exact. This closes the
  context-amplification defect exposed while using small-model failures as
  diagnostic signal without changing the core-error escalation dial.

### Current blockers

- The admitted database workflow is infrastructure-valid but capability-red.
  Qwen2.5 Coder 0.5B repeated visible context across three turns, emitted no
  executable source, and closed `:no-forms`. Exact transaction, later-query,
  and answer evidence remain absent. One failure does not authorize prompt
  prose changes or a broad model matrix.
- The first ranked reachability row cannot yet address the existing root agent
  through `seon_pod_solver`. The existing solver must forward an explicit
  agent id while preserving omission for tasks that mint a fresh ordinary
  agent. See
  [[../../seon/issues/inspect-pod-solver-cannot-address-existing-agent]].
- Root has a configured `seon.agent` require edge but no eligible callable;
  ordinary agents lack home edges to eligible `my.ns` and `my.skills`; and
  ACME still requires empty fixture namespaces instead of its real product
  namespaces. These are separate positive-fact changes and must be falsified
  one row at a time with newly minted ordinary agents.
- A planning restart on the static development target must be owned by the
  semantic ACME operator and preserve the same database/agent identity. The
  full parallel solution remains the P1b lease.
- The 0.5B narration provenance is exact: Qwen creates the first repetition,
  and correct parser recovery plus durable narration feed it into later turns.
  A manifest-only `readline?` arm cannot prove a one-byte-factor comparison:
  fresh agents/clusters change other database facts, while applying a manifest
  does not rewrite an existing agent's copied context tree. The paired prompt
  experiment waits for the ownership-fenced fork/lease boundary.
- Multi-form execution position is not yet an explicit durable database fact.
  The final-snapshot evidence now carries originating turn identity and orders
  separate eval entities by their identity-datom transaction, but the contract
  for several forms emitted and processed within one model reply remains
  implicit. This is recorded in
  [[../../seon/issues/multi-form-eval-order-is-not-durable]] and belongs to
  P4 after the first accepted serial slice unless it directly invalidates the
  active sample.
- The filesystem standing surface still contains overlapping read/edit
  generations and inconsistent result envelopes. Its measured convergence
  unit remains after the four reachability rows, not an excuse to change tools
  before the current selection evidence exists.

### Exact next order

1. Commit the retained P0b artifact plus its reviewed classification and
   update this roadmap, the MLX evidence, and the closed PATH issue together.
2. Repair explicit existing-agent routing in the one pod solver. Run only the
   focused solver and reachability tests; do not add another task adapter.
3. Run `root_orchestration` through common source/target/model admission and
   inspect its six scorer facts. Treat the first run as controlled evidence,
   not permission to edit several namespace owners at once.
4. Run `namespace_discovery`, `skill_lifecycle`, and `acme_product_tools` one
   at a time. Mint fresh ordinary/ACME agents where home requirements are
   copied at birth, and change only the positive require/eligibility facts
   falsified by that row.
5. After all four rows pass, run frozen `namespace_workflow-seed1-000` as the
   first composition proof of movement, dynamic require, cross-namespace
   definition, database use, and reporting.
6. Use the four frozen filesystem rows to converge the bounded read and
   anchored edit surface; rerun exact failures before any broader slice.
7. Make multi-form execution order an explicit durable database fact before
   the P4 batch/stream comparison. Never substitute timestamps, random ids,
   Python list position, or synthetic Inspect calls.
8. Only then advance the remaining frozen suite serially and compare models.
   Every run retains exact admission and receives a reviewed classification.

### Stop rules

- Do not run a full repository or benchmark suite while a selected sample has
  an unresolved infrastructure failure.
- Do not optimize context from aggregate token counts or model prose; require
  an exact rendered block, database coordinate, and classified Inspect result.
- Do not create another harness, supervisor, worktree, lifecycle helper, or
  stored context authority.
- Do not change standing context prose unless it is false or a controlled
  batch/multi-form experiment proves a non-derivable rule is missing.

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
