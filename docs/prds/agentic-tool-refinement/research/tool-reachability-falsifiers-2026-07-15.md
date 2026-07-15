---
type: research
status: complete
tags: [research, agent, capability]
---

# Tool reachability falsifiers — 2026-07-15

## Decision

The three P0 reachability defects need four fixed development rows before any
source change:

- one root-only orchestration row;
- one ordinary-agent namespace-discovery row;
- one ordinary-agent skill-lifecycle row; and
- one ACME downstream-tool row.

The ordinary rows are separate because one combined failure could not
distinguish the missing `my.ns` edge from the missing `my.skills` edge. These
rows use Inspect AI's existing native task, pod solver, source admission,
static-target admission, final-snapshot evidence, and native-log read-back.
They do not add a runner, a tool catalog, a prompt block, a renderer allowlist,
or a benchmark-specific tool blocklist.

The first rendered prompt is part of correctness. It proves whether the
function was reachable before the model chose anything. Successful eval rows
then prove selection and execution, later prompt bytes prove dynamic context
changes, and database-operation evidence proves durable effects. A correct
final sentence without that trajectory fails.

These are controlled development falsifiers for the already frozen tool
surface. They do not silently change the ten-member P0 suite. Once the defects
are fixed, the exact rows become focused regressions; the existing frozen
namespace workflow remains the broader P0 composition measure.

## Dependency ledger

- Inspect AI is installed from `reference-code/inspect-ai` at
  `05322696a0f784ec399ef6abbafd3d2a250ea9cc`. Its `Task`, `MemoryDataset`,
  `Sample`, and scorer contracts live in
  `src/inspect_ai/_eval/task/task.py`, `src/inspect_ai/dataset/_dataset.py`, and
  `src/inspect_ai/scorer/_scorer.py`. `EvalSample.metadata` and the public
  `read_eval_log`/`write_eval_log` boundary retain the native proof.
- `src-inspect-ai/src/seon_inspect/tasks/milestone_lift.py` is the closest
  native Seon workflow task. `src-inspect-ai/src/seon_inspect/solver.py`
  already supports an optional `agent_id` at `pod_run`; the generic pod solver
  needs only to thread that existing argument so the root row can address
  `root` without duplicating the solver.
- `src-inspect-ai/src/seon_inspect/milestone.py` demonstrates pure structured
  scorers over request-scoped eval rows, final coordinates, turn membership,
  and lossless database-operation evidence. The exact generated database
  fixtures demonstrate fail-closed evidence discrimination.
- `src-inspect-ai/src/seon_inspect/catalog.py` owns admitted native execution.
  `run_native_task` verifies selected sources and the static target before and
  after a task, finalizes the native log, and requires read-back.
- ClojureScript `1.12.145` is mirrored at
  `reference-code/clojurescript` revision
  `946d75f3483c0c8e784e6668bff2c71a25619a77`. Analyzer metadata is the source
  of the positive `:seon.fn/agent-facing?` fact; absence is the negative
  signal.
- Malli `0.20.0` is mirrored at `reference-code/malli` revision
  `80138076960e7820523b4cb932c5b5d1936d4e7f`. The compact cards consume the
  persisted Malli callable contract rather than inventing a task-local schema.
- The maintained Datahike reference checkout was observed at
  `eb3e2239b650635977fdc8e73e7c657b23bf3383` and was dirty under another
  owner. It was read but not modified or treated as a clean dependency pin.
  Seon's `seon.db` operation observer and final immutable database coordinate
  are the only database evidence path used here.
- `src/seon/agent/home.cljs` derives the home namespace and its persisted
  require edges. `src/seon/agent/ctx/namespaces.cljs` renders the current
  namespace in full and required namespaces as compact cards, filtering
  compact functions by positive eligibility. `src/seon/agent/debug.cljs`
  proves that the captured prompt uses the same renderer as the live turn.
- `src/my/ns.cljs` owns the one database-derived callable-function query.
  `src/my/skills.cljs` owns catalog, load, and unload. `src/seon/agent.cljs`
  owns root orchestration. `acme/src/acme/brand.cljs` and
  `acme/src/acme/widget.cljs` own ACME's only two eligible downstream
  functions.
- The complete inventory and original immutable ACME coordinate are in
  [[tool-namespace-colocation-audit-2026-07-15]]. The broader measurement
  contract is [[inspect-suite-freeze-2026-07-15]].

## Current-state falsifier

The source and retained ACME audit establish three direct contradictions:

- root's home namespace requires `seon.agent`, but no function in that
  namespace has a positive eligibility fact;
- ordinary home requires omit both `my.ns` and `my.skills`; and
- ACME adds `acme.helpers` and `acme.notes`, whose functions deliberately lack
  positive eligibility, while omitting eligible `acme.brand` and
  `acme.widget`.

The current ACME target is degraded while shared runtime files are dirty. A
read-only MCP probe found no live `acme-client` runtime advertisement, matching
the structured operator status. No agent was woken, no database was mutated,
and no restart was attempted. The source contradictions plus the earlier
immutable live audit are sufficient to define the red rows; their actual
before run waits for a clean admitted ACME artifact.

## Shared evidence contract

Every row runs as one fixed `Sample`, one epoch, through the existing static
ACME native door. The scorer consumes only these retained fields:

- start and end source admission plus byte-identical static-target identity;
- the final complete database coordinate;
- ordered request turn ids and exact prompt/reply bytes from `turn_evidence`;
- ordered request evals with eval transaction ids and operation evidence;
- ordered model-attempt evidence; and
- the native Inspect score, check map, and manually reviewed failure class.

The first request turn must contain the expected compact function identity and
callable schema. This is a substring/structured-card check, not an exact whole
prompt snapshot. It may not be satisfied by a later transcript echo. Eval and
operation coordinates must belong to the request's turns, share the final
database attachment, and be no later than the final coordinate.

Each scorer emits the same check vocabulary:

- `surface` — the required card and real home edge were present on turn one;
- `selection` — the intended general function was actually called;
- `execution` — its eval and required database operations succeeded;
- `dynamic_context` — a later prompt reflects the requested namespace or
  skill transition;
- `verification` — the model queried or observed the resulting state; and
- `report` — `message/user` and `complete` report the observed value.

Missing or malformed retained evidence fails closed. Timeout, core error,
quiescence, source drift, target drift, or failed native-log read-back remain
infrastructure failures and receive no capability score.

## Row 1 — root orchestration

Run this row against the existing root agent by threading `agent_id="root"`
through the ordinary pod solver. Use a clean target with no child carrying the
fixed purpose before the sample.

### Exact prompt

```text
You are the root orchestrator. Spawn exactly one new idle child whose purpose is `audit invoices reachability`. Do not choose an id and do not send the child a task. End that turn after the spawn so you can read the real returned id. In a later turn, run a scalar Datalog query that finds the child whose parent is root and whose purpose is exactly `audit invoices reachability`. Report the queried child id to your human with `message/user`, then `complete` with the same id. Never invent the id and do not inspect source files.

```

### Acceptance

- Turn one's prompt contains a compact `seon.agent/start!` row with its
  complete map-in contract. It contains no eligible row for `mint!`,
  `ensure-initial-agent!`, `spawn-depth`, `unhost!`, or boot/runtime helpers.
- A successful eval calls `agent/start!` or fully qualified
  `seon.agent/start!` with the exact purpose. `create!` and `delegate!` do not
  satisfy this row: the task asks for a minted idle child with no message.
- Its captured transaction is successful and contains one new agent with the
  exact purpose and a parent ref resolving to root. Oversized, missing, or
  malformed operation proof fails rather than falling back to eval-level
  `ok=true`.
- A later successful scalar `db/query` joins child purpose, child parent, and
  root's id. Its observed scalar result equals the spawned id and its operation
  coordinate is no earlier than the spawn transaction.
- Later `message/user` and `complete` forms, plus the delivered reply, contain
  that exact observed id.

### Current failure and classification

The first prompt cannot contain the function row because every
`seon.agent` function lacks the positive fact. Classify the reviewed failure
as `tool absent`. Once the row is present, choosing another operation is
`wrong selection`; truthful cards plus no executable forms is
`model reasoning failure`; an unverified or invented id is
`verification failure`.

## Row 2 — ordinary namespace discovery

Run with the normal nil `agent_id`, which mints an ordinary agent through the
existing door.

### Exact prompt

```text
Without reading files or using filesystem or graph search, use the callable-function discovery operation available in your ordinary home tool surface to list the positively callable functions in `seon.agent.web`. In that same turn move into `seon.agent.web`, then end the turn. On the next turn, confirm from the full current-namespace context that those functions are present and call `grants` unqualified. Return to your home namespace, report the discovered function names to your human, and `complete` with the same names.

```

### Acceptance

- Turn one's prompt contains exactly one compact `my.ns/functions` row and its
  map-in/map-out contract.
- A successful eval calls `my.ns/functions` for `seon.agent.web`; a hand-written
  Datalog query or guessed list does not satisfy selection. The captured read
  operations must query the `:seon.ns/name` row and pull positively eligible
  functions.
- A later successful eval moves with `in-ns` to `seon.agent.web`.
- The next turn's prompt renders `seon.agent.web` as the current full
  namespace and exposes `fetch`, `grants`, and `search` with their schemas.
  This later prompt, not a final claim, proves dynamic navigation.
- A successful unqualified `(grants)` eval runs while the eval namespace is
  `seon.agent.web`. The agent then returns home and reports exactly `fetch`,
  `grants`, and `search` through both terminal functions.

### Current failure and classification

The function is eligible and indexed but has no incoming ordinary home edge.
If turn one lacks its card, classify the reviewed failure as
`tool not required`. If the card is present but the model guesses Datalog or
another search path, use `wrong selection`. A successful function call without
the namespace move or later full-context proof is `verification failure`.

## Row 3 — ordinary skill lifecycle

This row is separate from namespace discovery so one missing edge cannot mask
the other.

### Exact prompt

```text
Without reading files or searching the program graph, use the skill operations available in your ordinary home tool surface. List the available skills, then load the `repl` skill and end that turn. On the next turn, confirm that the REPL skill body is present in your dynamic context, unload it, and end the turn. On the following turn, confirm that the body is absent. Report the completed load-and-unload cycle to your human and `complete`. Do not merely claim that the context changed.

```

### Acceptance

- Turn one's prompt contains compact rows for `my.skills/list`,
  `my.skills/load`, and `my.skills/unload`, each with its complete contract.
- Successful evals call `my.skills/list`, then `my.skills/load :repl`, then
  `my.skills/unload :repl` in that order. Their eval transactions are ordered
  and belong to distinct context-observation phases.
- Load operation evidence contains a successful context-block transaction for
  `:skill/repl`; unload operation evidence contains the successful inverse.
- The first prompt after load contains the actual REPL skill body from
  `seon-skills/repl/SKILL.md`. The first prompt after unload lacks that body.
  Load/unload result messages or transcript mentions cannot satisfy the body
  checks.
- The final reports occur only after the absent-body prompt.

### Current failure and classification

The three functions are eligible and indexed but have no ordinary home edge.
A missing turn-one card is `tool not required`. With truthful cards, failure
to choose the lifecycle operations is `wrong selection`; calls without the
later prompt proofs are `verification failure`.

## Row 4 — ACME downstream tools

Run with an ordinary agent under `config/acme.edn`. The row tests replacement,
not additive noise: fixture namespaces must leave the home surface while real
downstream capability owners enter it.

### Exact prompt

```text
Use ACME's ordinary downstream product tools visible in your home namespace. Call the product branding operation to obtain ACME's tagline and call the product widget operation to set the location to `Boston`. End that turn so you can read both real returned values. On the next turn report both exact values to your human and `complete` with both. Do not inspect files, search the program graph, or guess either return value.

```

### Acceptance

- Turn one's rendered home namespace has real require edges to `acme.brand`
  and `acme.widget`, and has no home requires for `acme.helpers` or
  `acme.notes`.
- The same first prompt contains compact rows for `acme.brand/tagline` and
  `acme.widget/set-location!` with complete schemas. Empty fixture cards or
  bare namespace names cannot satisfy the surface check.
- Successful evals call both downstream functions, with `Boston` passed to
  `set-location!`. Alias and fully qualified spellings are both valid.
- A later prompt contains the runtime results. The final human and completion
  reports contain exactly `Acme — the third-party harness.` and
  `acme location set: Boston`.

### Current failure and classification

Both functions are positively eligible and indexed, but ACME requires only
the reproduction namespaces. A missing first-turn card is
`tool not required`. If both cards are present and the model still inspects
files or invents outputs, classify as `wrong selection` or
`verification failure` from the retained trajectory.

## Offline discrimination before a model run

Implement the scorer fixtures before changing the surface. For every row, a
golden structured trajectory must pass and each single mutation below must
fail the named check:

- remove the expected first-turn compact card;
- move the card into a later prompt so transcript echo cannot satisfy it;
- replace the intended function call with a guessed direct query or literal;
- mark the selected eval unsuccessful;
- remove, oversize, corrupt, reorder, or foreign-attach required database
  operation evidence;
- remove the later current-namespace or loaded-skill prompt;
- leave the skill body present after unload;
- retain ACME's fixture requires while adding real requires; and
- preserve a correct final answer while deleting the execution trajectory.

This proves that the task measures reachability, execution, and verification
rather than final prose.

## Minimal later source owners

No renderer or context prose change is required.

1. `src/seon/agent.cljs`: positively mark only the root operations selected by
   the orchestration contract. The first implementation boundary needs
   `start!`; `delegate!` and `armable-agent-ids` should be decided from their
   own root workflows. Do not mark every public function or expose boot,
   minting, process-hosting, or internal derivations.
2. `config/system.edn`: add real ordinary home require edges for `my.ns` and
   `my.skills`. Use non-colliding aliases and retain fully qualified calls as
   the universal floor. Decide root skill access separately from ordinary
   access; do not copy an ordinary edge into root without a root role use case.
3. `config/acme.edn`: replace `acme.helpers` and `acme.notes` home edges with
   `acme.brand` and `acme.widget`. Keep the fixture namespaces indexed and
   source-inspectable through `SEON_EXTRA_SRC`; they are not standing tools.
4. Existing focused config, home-require, indexing, and namespace-card tests:
   prove the database require edges and exact eligible rows before a live run.
5. `src-inspect-ai/src/seon_inspect/solver.py`: thread an optional fixed
   `agent_id` through the existing `seon_pod_solver`; `pod_run` already owns
   the wire field and refusal semantics.
6. A small native Inspect task and pure scorer under the existing
   `src-inspect-ai/src/seon_inspect/` package, with focused tests. Reuse
   `run_native_task`, `_record_result`, fail-closed evidence decoding, and the
   current score metadata pattern. Do not extend the database/namespace
   milestone dispatch merely to hide unrelated reachability rows inside it.

`src/seon/web/serve.cljs` is not an owner for this unit. Its current bounded
first-turn prompt, turn, eval, model-attempt, and database-operation evidence
already suffice. A new task-specific projection would be a second evidence
mechanism.

## Exact run order

1. Land the offline scorer discrimination fixtures while the surface remains
   unchanged.
2. On the first clean admitted ACME artifact, run each row once and retain the
   expected red native log. Use a clean target for the root row so its fixed
   purpose is absent before execution.
3. Apply one source owner at a time: root eligibility, ordinary home edges,
   then ACME edge replacement. After each change, query one immutable database
   value for persisted eligibility and require edges before invoking a model.
4. Rerun only the affected exact row with byte-identical task text and model
   configuration. Read the finalized `.eval` back and compare the first prompt,
   selected evals, operation evidence, model attempts, final coordinate, and
   classification.
5. Only after all four rows pass should the broader frozen namespace workflow
   be replayed. Do not run the full suite to diagnose one red reachability row.

## Mechanical validation

- No runtime, config, ACME source, or Inspect source was edited or restarted
  for this audit.
- The live read-only probe failed closed because the degraded ACME watcher had
  no advertised `acme-client` runtime; it did not fall back to another pod.
- `seon.dev.markdown/validate-file` and `git diff --check` are the acceptance
  gates for this report.
