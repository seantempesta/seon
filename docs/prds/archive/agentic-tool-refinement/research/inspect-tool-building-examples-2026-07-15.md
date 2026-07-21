---
type: research
status: active
tags: [research, agent, capability, flow]
---

# Inspect examples for tool building and small-model navigation

## Decision

Keep Seon's language-native function surface and Inspect AI's native task,
solver, scorer, limit, sandbox, and `.eval` authorities. Do not translate Seon
functions into Inspect JSON tools and do not add another harness. The reusable
Inspect patterns are narrower and more valuable: resolve a dynamic callable
surface once for an immutable request, snapshot the exact contracts shown to
the model, preserve authored call order independently of completion order,
represent expected failures as bounded typed results, make cancellation
addressable, and retain rejected work as scorer-adjacent evidence.

The highest-leverage next experiment is therefore a two-turn namespace
reachability row using the existing Seon pod solver: the first turn proves the
exact database-derived compact card shown to the model, and the second proves
selection and execution of the newly reached function. Inspect's tool-search
round trip supplies the experimental shape, not a replacement implementation.

## Dependency ledger

| Dependency or mechanism | Selected identity | Source evidence | Constraint |
|---|---|---|---|
| Inspect AI | `05322696a0f784ec399ef6abbafd3d2a250ea9cc` | `reference-code/inspect-ai/src/inspect_ai/`, `reference-code/inspect-ai/tests/`, and `reference-code/inspect-ai/examples/` | Inspect owns evaluation, scoring, limits, cancellation accounting, sandboxes, and native logs. |
| Seon Inspect adapter | current checkout | `src-inspect-ai/src/seon_inspect/solver.py`, `milestone.py`, `catalog.py`, `source_admission.py`, and `scorecard.py` | The pod remains the agent runtime; the adapter preserves database evidence and rejects infrastructure faults before capability scoring. |
| Seon dynamic function surface | current checkout | `src/seon/agent/ctx/namespaces.cljs`, `src/my/ns.cljs`, and indexed `:seon.fn` / `:seon.schema` facts | Current namespace source is full; required namespaces are inert compact contracts; positive `:seon.fn/agent-facing?` facts define callable eligibility. |
| Existing stream and batch audit | current lane | [[inspect-batch-stream-cancellation-2026-07-15]] and [[batch-stream-cutoff-audit-2026-07-15]] | This report ranks source examples; it does not implement the queued P4 transport or multi-form repair. |

## Ranked source examples and interventions

### 1. Prove dynamic namespace reachability with an exact two-round row

Inspect permits a solver to replace `state.tools` between generations. Its
small dynamic example exposes only `color`, generates, then replaces the
surface with `shape` and proves both calls occurred
(`reference-code/inspect-ai/tests/tools/test_tools.py:203-270`). Its provider-
specific tool-search bridge is even closer to Seon's navigation question: the
first response sees only search, the client returns one discovered contract,
and the second response calls that contract
(`reference-code/inspect-ai/tests/agent/test_agent_bridge.py:280-340`).

Seon already owns the general form of this mechanism. Required namespaces are
derived from the current namespace's persisted require edges
(`src/seon/agent/ctx/namespaces.cljs:200-213`); compact cards pull indexed
function and schema rows from the supplied immutable database value
(`src/seon/agent/ctx/namespaces.cljs:739-799`); and `my.ns/functions` uses the
same one-line renderer and positive eligibility fact for on-demand discovery
(`src/my/ns.cljs:36-84`). The compact function record names arguments and
return types without emitting executable pseudo-code
(`src/seon/agent/ctx/namespaces.cljs:714-732`).

The gap is experimental, not architectural: the first-turn prompt bytes must
prove the expected compact contract was present, then a later turn must prove
the model selected and executed it. Use the four already designed namespace
falsifier rows rather than adding Inspect tools. A fixed agent id may preserve
the two-turn navigation trajectory; exact prompt/reply/coordinate evidence
already crosses the pod door.

Shortest falsifier: start an ordinary agent without the target namespace in
its required set, ask it to inspect or require that namespace, and on the next
turn require the exact target call. Fail if the first prompt already contains
the target card, the second prompt lacks it, the model invents an unindexed
function, or the scored outcome cannot be joined to the request's turn/eval
evidence.

Ordered owners: frozen row and scorer in `src-inspect-ai`; default require
membership in `config/system.edn` or downstream membership in
`config/acme.edn`; only then the existing namespace/index owners if the row
localizes a real projection defect.

### 2. Snapshot the exact callable surface used by each model attempt

Inspect resolves `ToolSource` values afresh, normalizes callable definitions,
and deep-copies their parameter schemas before a generation
(`reference-code/inspect-ai/src/inspect_ai/model/_call_tools.py:846-917`). The
model path performs that preparation once at request entry
(`reference-code/inspect-ai/src/inspect_ai/model/_model.py:1035-1059`), gives
hooks an owned copy, and snapshots any hook-modified schema into the model
event rather than later rereading mutable definitions
(`reference-code/inspect-ai/src/inspect_ai/model/_model.py:1140-1155`). Its
canonical `ToolInfo` is deliberately small—name, description, JSON-schema
parameters, and provider options
(`reference-code/inspect-ai/src/inspect_ai/tool/_tool_info.py:24-58`).

Seon already has the stronger authority: one rendered database coordinate and
exact prompt bytes determine the dynamic namespace surface. It also persists
per-attempt model configuration, copies prompt/turn/eval evidence into Inspect
metadata, and preserves attribute absence rather than manufacturing nulls
(`src-inspect-ai/src/seon_inspect/solver.py:117-156`). Do not add a duplicated
JSON tool catalog. Instead, use the exact prompt plus rendered coordinate as
the request snapshot and query the same database value when a structured diff
is needed.

The remaining gap is a focused equality proof: changing eligible function
facts or require edges after a turn must not change that turn's reconstructed
surface, while the next turn must observe the new database value. This is the
tool-surface analogue of the already implemented immutable model-config test.

Shortest falsifier: render turn A at coordinate A, change one eligible
function contract or require edge, render turn B at coordinate B, then
reconstruct both. A must be byte-identical to its stored prompt; B must contain
exactly the intended contract delta; no ambient current database read may
change A.

Ordered owners: `src/seon/agent/ctx/namespaces.cljs` and existing context
reconstruction tests; `src/seon/web/serve.cljs` only if the current exact
prompt/coordinate projection is insufficient; `src-inspect-ai` only for
fail-closed admission of the equality evidence.

### 3. Make several forms durable in authored order before comparing batch

Inspect treats multiple tool calls as one ordered assistant action. Serial
calls return in authored order
(`reference-code/inspect-ai/tests/model/test_parallel_tools.py:125-133`).
Parallel-safe consecutive calls run concurrently, while a serial call forms an
ordering barrier; returned messages still preserve call order
(`reference-code/inspect-ai/tests/model/test_parallel_tools.py:136-239`). Every
call has its own id, result message, pending event, completion time, and
message association
(`reference-code/inspect-ai/tests/model/test_parallel_tools.py:404-430` and
`:432-487`).

The lesson is not to run Seon forms concurrently. Seon batch semantics are
sequential and stateful: an earlier require, namespace move, or transaction may
affect a later form. The reusable contract is stable authored position plus a
distinct result identity. The current process-local eval vector is ordered,
but persisted turn-to-eval membership is cardinality-many and cannot prove
authored position. This is the already recorded P4 blocker, not a reason to
patch the solver now.

Shortest falsifier: one exact reply contains success, expected failure, then an
independent success. After restart, the linked eval rows must have unique
contiguous positions `0,1,2`, the third must have executed, and no runtime
result bytes may appear in the stored assistant reply.

Ordered owners: `src/seon/eval.cljs` transaction shape, turn/eval schemas and
projection in `src/seon/agent/turn.cljs` and `src/seon/web/serve.cljs`, then
`src-inspect-ai/src/seon_inspect/solver.py` admission and the existing
milestone scorer. Preserve the dependency order recorded in
[[../../../seon/issues/multi-form-eval-order-is-not-durable]].

### 4. Separate expected function failures from core failures, and bound both

Inspect converts `ToolError` into a tool result without failing the task
(`reference-code/inspect-ai/tests/tools/test_tool_error.py:10-58`), while an
unhandled exception in a parallel stage cancels siblings and remains a core
failure (`reference-code/inspect-ai/tests/model/test_parallel_tools.py:242-318`).
Schema parse errors, missing functions, approval rejection, and termination
all still emit typed tool events before returning or raising
(`reference-code/inspect-ai/tests/approval/test_approval.py:248-376`). Tool
output has a byte cap at the execution boundary
(`reference-code/inspect-ai/src/inspect_ai/model/_call_tools.py:103-123`), and a
sandbox timeout preserves bounded partial output with a typed timeout error
(`reference-code/inspect-ai/tests/tools/test_call_tools.py:341-397`).

Seon's analogous policy is already better aligned with small models: ordinary
function failures are `:seon/error` values, displayed source/stdout/diagnostics
share one bounded eval render path, and oversized database/model evidence is
marked rather than partially admitted. The Inspect adapter also rejects
timeout, core error, and quiescence before the task scorer
(`src-inspect-ai/src/seon_inspect/solver.py:278-293`).

The next refinement should be empirical error quality, not a new exception
taxonomy. Frozen rows should assert that malformed arguments, missing
functions, denied filesystem paths, command timeout, and oversized output each
produce one concise actionable value with no stack trace and an honest clipped
marker. Less capable models should be measured for recovery on the following
turn.

Shortest falsifier: make one invalid call whose underlying exception contains
a very large diagnostic. The next prompt contains one bounded typed failure,
no stack-trace frame, and enough expected-versus-got data for a corrected call;
the native log retains the infrastructure distinction without admitting the
large body.

Ordered owners: the existing function's namespace and Malli schema first;
shared error rendering only for a demonstrably global defect; transcript caps
in `src/seon/agent/ctx.cljs`; Inspect admission/scorer only to classify and
measure recovery.

### 5. Replace the blocking pod door before claiming streaming cancellation

Inspect proves that a single in-flight tool can be cancelled without killing
its siblings and records a typed timeout result for the target
(`reference-code/inspect-ai/tests/model/test_parallel_tools.py:321-401`). It
also requires distinct pending event identities so concurrent work can be
observed and cancelled independently
(`reference-code/inspect-ai/tests/model/test_parallel_tools.py:670-740`). At
the sample boundary, SIGINT and fail-on-error retain cancelled samples,
conversation history, events, and errors before propagating cancellation
(`reference-code/inspect-ai/tests/test_cancellation_logging.py:103-143` and
`:248-297`). Streaming log completion is required to round-trip the same event
and attachment content as materialized completion
(`reference-code/inspect-ai/tests/log/test_streaming_completion.py:192-205`).

Seon already retains interrupted native logs: `_eval_admitted_task` snapshots
published logs and finalizes new evidence on `BaseException`
(`src-inspect-ai/src/seon_inspect/catalog.py:256-305`), while finalization
copies, hashes, reads back, status-checks, and admission-checks each `.eval`
(`src-inspect-ai/src/seon_inspect/source_admission.py:240-284`). But the active
pod door is a blocking `urllib` read inside `anyio.to_thread.run_sync`
(`src-inspect-ai/src/seon_inspect/solver.py:49-81` and `:317-336`). Cancelling
the solver cannot yet prove that the addressed pod request or provider stopped.

Shortest falsifier: a deterministic provider emits one parser-confirmed form
then an infinite tail. The stream arm disconnects within a bounded grace, the
provider observes cancellation, exact emitted prefix bytes and one eval are
retained, and the native scorer runs. A second fixture cancels before the form
and must retain a cancelled `.eval` with no capability score.

Ordered owners: addressable request/cancellation contract in the existing pod
composition door, provider abort acknowledgement in the existing adapter,
then an async cancellable client in `src-inspect-ai/src/seon_inspect/solver.py`.
Do not simulate cancellation solely in Python.

### 6. Keep approvals and sandboxes at the real execution boundary

Inspect approval is contextual and per execution: the policy wraps the call,
can approve, modify, reject, or terminate, and restores the prior policy after
the scoped call (`reference-code/inspect-ai/src/inspect_ai/approval/_apply.py:23-68`
and `:82-106`). Rejection remains model-visible and transcript-visible rather
than disappearing (`reference-code/inspect-ai/tests/approval/test_approval.py:198-277`).

This is relevant only when a frozen task truly requires human approval. Seon
must not use approval as a benchmark patch or as a second capability registry.
If later required, the approval decision belongs at the same Seon function
execution boundary as the capability check, derived from database policy, and
must leave an exact bounded fact even on rejection.

Inspect sandboxes should likewise remain benchmark-owned. Seon's SWE-bench arm
already uses the official `sandbox_config` seam, records boot, entrypoint, API
network, and egress posture in sample metadata, and leaves the official scorer
path intact (`src-inspect-ai/src/seon_inspect/swebench_arm.py:320-357`). Do not
replace it with a Seon-specific fake sandbox. Inspect's own self-check also
shows why root versus non-root is observable policy, not an incidental image
choice (`reference-code/inspect-ai/tests/tools/test_sandbox_docker_and_local.py:31-89`).

Shortest falsifier for a future approval row: the same function call under an
absent policy executes, under reject policy returns a typed rejection and does
not mutate state, and under an explicitly modified approval executes only the
approved arguments; all three remain distinguishable after `.eval` read-back.

Ordered owners: existing Seon capability/execution boundary, database policy
schema and config manifest, then a native Inspect task policy only if a real
user workflow requires it. Existing benchmark sandbox adapters remain in
`src-inspect-ai` and official scorers stay untouched.

### 7. Preserve scorer evidence through edits, rejection, and recovery

Inspect score edits preserve the original score, add provenance, and require
an epoch when sample ids are ambiguous
(`reference-code/inspect-ai/tests/scorer/test_score_editing.py:297-315` and
`:353-385`). Score-edit events remain structurally inside the scorer span
(`reference-code/inspect-ai/tests/log/test_score_edit_events.py:34-88`). Its
recovery path merges flushed samples with completed and in-progress buffer
state, marks the recovered log honestly, and proves disk read-back
(`reference-code/inspect-ai/tests/log/test_recover_e2e.py:108-224`).

Seon already applies this well. Failure classification accepts one explicit
frozen label, merges rather than replaces oracle metadata, uses Inspect
provenance, refuses passing scores, and avoids metric recomputation
(`src-inspect-ai/src/seon_inspect/scorecard.py:144-195`). The milestone scorer
keeps individual check failures and fabrication evidence in score metadata
(`src-inspect-ai/src/seon_inspect/milestone.py:580-605`). Database-operation
proof is fail-closed on membership, order, source, coordinate, result, and
bounded inline status
(`src-inspect-ai/src/seon_inspect/milestone.py:169-200` and
`src-inspect-ai/tests/test_milestone.py:236-271`).

The remaining rule is discipline: every new small-model row should put the
minimum decisive structured evidence in score metadata, keep exact bulky
bytes in the database/blob/native-log authorities, and preserve rejected
terminal logs. Never make a plausible final reply substitute for executed
function evidence.

Shortest falsifier: classify one failed admitted sample, write and copy the
native log, read it back, and prove unchanged score value, original oracle
metadata, one provenance-bearing history entry, exact source admission at
start/end, and the retained turn/eval evidence used for the diagnosis.

Ordered owners: task-specific scorer, `scorecard.py` only for global taxonomy
mechanics, `source_admission.py` for native-log byte retention, and Seon
database/blob projection for exact execution evidence.

## Intervention order

1. Run the existing four namespace reachability falsifiers as exact two-turn
   Inspect rows after the ACME dependency-coordinate handoff and admitted live
   sample. Change only the owning require/namespace/function surface that a
   failure localizes.
2. Add frozen-coordinate reconstruction equality around the current prompt
   and namespace projection; do not add a JSON tool snapshot.
3. Close durable multi-form positions, then run the ordered three-form fixture
   before any batch-versus-stream comparison.
4. Add the small error-recovery matrix and rank function/schema/error-envelope
   defects by observed next-turn recovery.
5. Replace the blocking pod request with an addressable cancellation path and
   prove provider-side cutoff before measuring the real stream arm.
6. Exercise approval only for a real workflow requiring it; retain official
   benchmark sandboxes and scorers unchanged.
7. For every row, finalize and read back the native `.eval`, preserve rejected
   logs, and use provenance-bearing score edits only for explicit human
   classification.

## Global falsifiers

- A test passes because a second JSON tool registry tells the model what Seon's
  ordinary dynamic namespace surface did not.
- A tool/function contract is reconstructed from ambient current state rather
  than the database coordinate rendered for that attempt.
- Several authored forms lack unique contiguous durable positions, or results
  are reordered by completion time or random identity.
- An expected function failure becomes a sample crash, or a core/transport
  failure reaches a capability scorer.
- A clipped result looks complete, a stack trace reaches the model, or the
  retained marker omits what was clipped and why.
- Solver cancellation returns while pod/provider work continues without an
  addressable cancellation acknowledgement.
- Approval rejection or sandbox denial disappears from the transcript/native
  log, or approval is used to curate benchmark-specific capability access.
- A failure classification overwrites the oracle score, drops original score
  metadata, or cannot survive native-log copy and read-back.
