---
type: research
status: completed
tags: [research, prd, agent, flow, database]
---

# Evaluation harness replacement

## TL;DR

Replace `seon.gym` with [Inspect AI](https://inspect.aisi.org.uk/) as the one
evaluation control plane. Do not refactor the gym, port its predicate language,
or create a Clojure evaluation engine beside Inspect.

This is not a speculative framework choice. The repository already contains a
substantial Inspect integration in `src-inspect-ai/` that uses the right
boundary: Inspect owns samples, orchestration, scoring, metrics, concurrency,
and durable evaluation logs; Seon remains the system under test and runs its
real agent FSM through `POST /agents/run`. The existing long-term-planning
journey already drives an agent, restarts its pod, resumes the same agent, reads
the resulting plan state, and scores the trajectory mechanically. That is the
foundation to finish, not a second mechanism to replace.

The cutover should leave exactly these layers:

- Focused CLJS tests cover production functions, typed error behavior, and
  deterministic edge cases without booting a model or matching response prose.
- Inspect journeys cover real agent behavior across turns, process restarts,
  database branches, and optional provider calls.
- Seon's production lifecycle owns database fork, attach, restore, restart, and
  cleanup. The evaluation adapter calls that lifecycle; it does not transact
  Datahike internals or replay arbitrary evals.
- Inspect `.eval` logs are the authoritative evaluation artifact. Database
  facts remain authoritative for the state the agent produced.
- Harbor remains an adapter for upstream container benchmarks that require it,
  such as Terminal-Bench. It does not become Seon's primary harness.

The current gym is unusually expensive and unusually fragile for what it
proves. Its driver, scorecard, tests, scenario EDN, and command scripts amount
to a homegrown evaluator with its own schema, predicate interpreter, pass-rate
reporting, process-global fixtures, and artifact conventions. It accounts for
about 137–152 seconds and 24 complete cluster seeds in the current CLJS gate,
roughly half of a 269–318 second run. The default tests can pass without running
23 of its model-gated scenarios. Removing it from the normal gate is both a
design simplification and a material test-speed improvement.

The first migrated battery should be deliberately small:

1. Long-term planning across a real pod restart, using the same agent and
   durable open plan items.
2. Schema'd database memory stored in one turn and retrieved in a later turn,
   including restart continuity.
3. Add an interactive web UI/canvas journey only after its button, input,
   transaction, and render behavior can be checked mechanically.

Do not preserve health, workout, expense, or todo fixture vocabulary merely to
maintain historical continuity. Preserve the capability being measured and
rewrite the sample in a neutral software-planning or knowledge task. Old gym
files remain available in Git history.

## Decision

### Selected control plane: Inspect AI

Inspect is the best fit because it supplies the machinery Seon should not own:

- datasets and repeatable samples;
- custom agents and solvers;
- deterministic and model-based scorers;
- epochs and aggregate metrics;
- concurrency limits and time/resource limits;
- incremental, portable evaluation logs;
- crash recovery and retry support;
- local execution, CI execution, and framework extensions;
- a permissive MIT license and active stewardship by the UK AI Security
  Institute and community.

Inspect's core abstraction is a task composed from a dataset, solver or agent,
and scorer. A Seon evaluation maps cleanly onto it:

| Inspect concept | Seon meaning |
| --- | --- |
| `Sample` | One prompt, fixture manifest, capability target, and lifecycle choreography |
| `Solver` or external agent | The thin client that leases a Seon cluster and calls its normal agent API |
| `TaskState` | The run record, including agent id, branch coordinate, turns, restart events, and artifact refs |
| Deterministic `Scorer` | A read-only query against the final immutable database coordinate plus typed run results |
| Model `Scorer` | An explicitly enabled judge for semantic quality that mechanics cannot establish |
| Epoch | A fresh isolated database branch and agent process for one repeated attempt |
| `.eval` log | The authoritative evaluation event and score artifact |

The solver must not imitate Seon's agent loop. It should issue one normal run
request per phase and let Seon's FSM decide eval count, planning actions,
termination, and recovery. Reusing the same Seon agent id across phases is how
the journey observes continuity.

### Non-decisions

This choice does not make Inspect authoritative for production state,
provenance, provider selection, or process lifecycle.

- Datahike remains authoritative for Seon state.
- Seon's normal supervisor remains authoritative for pod and writer lifecycle.
- Seon's database/config path remains authoritative for the agent model and
  provider.
- Transaction metadata remains authoritative for `:seon.db/user` and
  `:seon.db/process` provenance.
- Inspect records what was evaluated and how it scored; it does not become an
  authorization or runtime ownership system.

Inspect's own checkpointing must not stand in for Seon restart and recovery.
The evaluation needs to exercise the production restart operation because that
is the behavior under test.

## Current repository evidence

### The existing Inspect package has the correct boundary

`src-inspect-ai/src/seon_inspect/solver.py` already treats Seon as an external
agent runtime. It resolves the endpoint, starts a run through
`POST /agents/run`, reuses an agent id when supplied, waits for the real run
result, and records runtime evidence. Inspect is not generating Seon tool calls
or evaluating Clojure itself.

The solver already captures useful evidence including:

- the Seon agent id;
- turn and eval counts;
- close reason and timeout status;
- elapsed time;
- runtime-derived model configuration;
- prompt, transcript, and blob references.

The package also supports `model=None` conceptually: Seon's provider performs
the model call, so Inspect does not need a fake model to drive the task. The
current pinned Inspect source now supports that directly; the existing
`mockllm/model` workaround should be deleted during the compatibility update.

### Restart/resume already exists as an Inspect journey

`src-inspect-ai/src/seon_inspect/planning.py` already implements the essential
long-running shape:

1. Run phase one against a real Seon agent.
2. Restart the pod.
3. Run phase two against the same agent id.
4. Read the durable plan state.
5. Score both the resulting facts and the trajectory.

This should be migrated onto the canonical branch/query lifecycle and kept.
It should not be rewritten as gym predicates or duplicated in a new runner.

The frozen dataset manifest also already reserves rows for
`memory_store_recall`, `long_term_planning`, and `ui_tiles` in
`src-inspect-ai/src/seon_inspect/freeze.py`. Those rows are the intended landing
place for the first journeys. Freeze/manifest code is useful for curating
samples and can remain, but it is not a second score authority.

### The gym duplicates framework machinery

The current gym surface includes approximately:

- 2,117 lines in `test/seon/gym/driver.cljs`;
- 445 lines in `test/seon/gym/scorecard.cljs`;
- 1,282 lines of driver tests;
- 21 EDN scenario/config files;
- dedicated `bin/gym` and `bin/gym-scorecard` commands.

It owns concerns that a mature evaluation framework already provides:

- scenario validation and loading;
- process-global fixture setup;
- run repetition and paid-test gating;
- a predicate language;
- multi-axis result aggregation;
- pass-to-the-power-of-k calculations;
- JSONL scorecard storage and presentation;
- prompt and transcript evidence extraction.

Each scenario also reconstructs the complete boot seed while swapping
process-global database, filesystem-config, environment, and Malli registry
state. That is closer to an integration environment manager than a unit test,
but without robust process isolation.

Several predicates prove implementation text rather than behavior:

- transcript substring checks;
- prompt heading and substring checks;
- regular expressions over emitted eval source;
- expected words in final answers.

Those assertions are fragile when context composition, formatting, or agent
wording changes even though the behavior remains correct. They conflict with
the repository's current requirement to prove typed facts and edge behavior,
not particular response prose.

### The current Python package also has duplication to remove

Adopting Inspect does not mean preserving every current adapter implementation.
The following are migration debt:

- `src-inspect-ai/pyproject.toml` and its README describe an old Inspect build,
  while `reference-code/inspect-ai` is currently pinned to Inspect `0.3.246`,
  commit `05322696…` from 2026-07-10. The submodule SHA should be the deliberate
  compatibility pin, and package metadata/docs should match it.
- `src-inspect-ai/src/seon_inspect/scorecard.py` implements its own append-only
  `evals/scorecard.jsonl`, pass-to-the-power-of-k reduction, and alarms. Replace
  those with Inspect metrics, epoch reducers, and `.eval` logs. If humans need
  a small trend table, generate it as a projection keyed by source-log hashes;
  never make it a second authority.
- `bench_common.py` hand-authors Datahike schema and sends direct
  `datahike.api` transactions through string wire-REPL forms. That bypasses
  `seon.db`, the normal config path, and transaction provenance. Remove it once
  the production lifecycle offers fixture seeding and branch leases.
- `planning.py` reads plans through a string wire-REPL snapshot. Replace this
  with a typed, read-only database query/snapshot API at an explicit
  coordinate.
- `tb2_agent.py` obtains Harbor from a sibling `tmp/tb2-venv`, fragmenting
  dependency ownership. Put external-benchmark support behind a declared
  optional dependency/lock.
- `tb_agent.py` and the Harbor-backed `tb2_agent.py` are parallel paths for the
  same upstream benchmark family. When Harbor is the canonical Terminal-Bench
  runner, delete the old path.

## Requirements and framework comparison

### Evaluation criteria

The primary harness must support all of the following without becoming a
second Seon runtime:

- invoke the real CLJS pod and real agent FSM;
- preserve an agent across multiple messages and a process restart;
- score long-term planning and schema'd database memory;
- combine deterministic state/trajectory checks with optional model judges;
- allocate writable Datahike branches from a known commit and inspect immutable
  final coordinates;
- exercise restore as a production operation;
- route providers/models through Seon and keep paid calls explicit;
- retain reproducible artifacts and provenance;
- run isolated samples concurrently without sharing Node globals;
- bridge Python to the Clojure/Node system through a small typed boundary;
- run locally and in CI;
- extend to upstream benchmarks without forcing their data model into Seon;
- remain actively maintained under acceptable licensing.

### Comparison

| Capability | Inspect AI | Harbor | DeepEval | LangSmith / Braintrust |
| --- | --- | --- | --- | --- |
| Drive the existing Seon agent | Existing custom solver already does this | Custom agent adapter can do it | Possible through custom task/tracing integration | Possible through custom/remote evaluation functions |
| Stateful multi-phase lifecycle | Natural in a custom solver; lifecycle events can be explicit | Natural if Seon is wrapped as an environment | Must be built around the metric/tracing model | Must be built in user evaluation code |
| Datahike fork/restart/restore | Thin calls to Seon's lifecycle API | Would need to be forced into environment provisioning | No native sample-lifecycle substrate | User code or remote worker owns it |
| Mechanical graders | First-class custom scorers and metrics | First-class task test/verifier and reward | First-class custom metrics | First-class custom evaluators |
| Optional model judge | First-class scorer model, independently selected | Possible, less central to verifier model | Strong LLM-as-judge catalog | Strong hosted evaluator support |
| Repeats and aggregation | Epochs plus reducer/metric support | Repeated jobs/trials | Test runs and metric aggregation | Repetitions and experiment comparison |
| Logs and crash recovery | Portable incremental `.eval` logs and sample recovery | Job/trial artifacts and viewer | Trace/test artifacts, often platform-oriented | Strong managed experiment/trace artifacts |
| Concurrency/isolation | `max_samples` plus solver-managed leases/sandboxes | Strong container/cloud isolation | Caller-managed runtime isolation | Service/worker-managed concurrency |
| Local/offline core | Yes | Yes | Yes | SDKs run locally; durable value centers on hosted or self-hosted service |
| Existing Seon investment | Substantial and correctly shaped | Existing Terminal-Bench adapter only | None | None |
| License/control-plane fit | MIT, open-source | Apache-2.0, open-source | Apache-2.0, open-source | Commercial service terms; self-host constraints vary |
| Recommended role | Primary Seon evaluation control plane | Upstream container benchmark adapter | No adoption | Optional future log export, not core |

### Why Harbor is not the primary harness

[Harbor](https://github.com/harbor-framework/harbor) is the strongest credible
alternative for containerized agent benchmarks. It is the official harness for
Terminal-Bench 2.0, supports arbitrary agents, Docker and cloud environments,
parallel trials, artifacts, and an evaluation viewer. Its task model centers on
an instruction, an environment, and a verifier/test script.

That is excellent for a benchmark whose unit is a disposable container. It is
less natural for Seon's unit of evaluation: an accumulating Datahike branch, a
real supervised pod, several messages to one agent, and explicit
restart/restore operations. Making Harbor primary would require embedding
Seon's branch and supervisor semantics inside Harbor's environment abstraction
without eliminating any Seon lifecycle work. The repository would also discard
the already-correct Inspect solver and scorer integration.

Keep Harbor narrowly: when an upstream benchmark is authored for Harbor, run it
through the Seon external-agent adapter and let Harbor remain authoritative for
that upstream job. Do not translate every internal Seon journey into a Harbor
task.

### Why DeepEval is not the primary harness

[DeepEval](https://github.com/confident-ai/deepeval) is active, Apache-2.0, and
has a broad metric catalog, custom metrics, agent/multi-agent evaluation, CI
features, and tracing. Its agent evaluation model is primarily trace- and
metric-oriented. Seon would still need to build the sample lease, database
branch, pod lifecycle, restart choreography, retry isolation, and durable local
run artifact substrate around it.

That would replace a working Inspect integration with a fresh orchestration
project for no identified capability gain. DeepEval can be reconsidered only
if a specific metric proves materially better and can be used independently;
it should not become another harness dependency merely for metric variety.

### Why hosted experiment platforms are not the core

[LangSmith](https://docs.langchain.com/langsmith/experiment-configuration) and
[Braintrust](https://www.braintrust.dev/docs/evaluate) provide strong hosted
experiment comparison, custom evaluators, repetitions, concurrency, traces, and
team workflows. Both can call external code. Either could receive exported
Inspect/Seon artifacts later if the collaboration value justifies it.

They should not own the primary reliability gate:

- local reproducibility and branch inspection must not depend on a service;
- provider credentials and experimental state would cross another control
  plane;
- Datahike lifecycle still has to be implemented in Seon-side worker code;
- LangSmith self-hosting is an Enterprise add-on;
- Braintrust self-hosting still uses a managed control plane.

### Screened-out tools

[Promptfoo](https://www.promptfoo.dev/docs/configuration/expected-outputs/) is
well suited to prompt/provider matrices and output assertions, not supervised
long-lived agent state. Raw `pytest` remains the right tool for testing Python
adapter functions, but it should not grow its own epochs, score logs, recovery,
or evaluation viewer. Neither is a replacement for the required control plane.

## Target architecture

### One thin Python boundary

Add or refine one `SampleLease`-style adapter in `src-inspect-ai/`. Its public
surface should be typed data and production operations, not arbitrary code
evaluation. Conceptually it needs:

- acquire a fresh writable branch from a retained base commit;
- attach/start one wire server and pod for that branch;
- seed declared fixture facts through the normal Seon transaction/config path;
- create or resume a Seon agent;
- send a message through `POST /agents/run`;
- restart the pod while preserving the branch and agent id;
- restore the branch to an explicit coordinate when the sample requires it;
- perform read-only queries/snapshots at a named coordinate;
- export runtime and database evidence;
- stop the processes and drop or retain the branch according to policy.

Each operation should return a fully typed JSON result with stable error codes.
The adapter should never send arbitrary Clojure forms, call `datahike.api`
directly, author a second schema, or infer success from log prose.

The Python boundary is intentionally small. Python is appropriate for Inspect
plugins and orchestration; Clojure/Node remains appropriate for Seon's database
and agent runtime. A language bridge is not a reason to duplicate business
logic on either side.

### Database coordinate and branch lifecycle

After the runtime-reliability branch lifecycle is implemented, every sample
attempt should begin with one canonical base coordinate containing at least:

```clojure
{:seon.db/store-id  ...
 :seon.db/branch    ...
 :seon.db/commit-id ...
 :seon.db/t         ...}
```

The exact registered attribute names remain a production data-model decision,
but the semantic requirements are fixed:

1. Select one retained known-good commit.
2. Fork one writable branch for the sample id and epoch.
3. Start one writer and pod attached only to that branch.
4. Apply fixtures through the same production transaction boundary used by
   normal boot/config or root-supervised operations.
5. Drive every phase through normal agent and lifecycle APIs.
6. Close the sample at an immutable final coordinate.
7. Score against that coordinate or an `as-of` value, never a moving
   connection.
8. Export evidence before branch cleanup.
9. Retry on a new branch from the same base coordinate, never on partially
   mutated state.

Datahike's immutable history makes `as-of` inspection cheap and trustworthy,
but a branch alone does not isolate JavaScript process globals. Therefore one
concurrent sample needs one branch plus one process group. Two samples must not
share a pod, Malli registry, compiler state, environment variables, or global
database connection.

Restore is an operation under test, not a special evaluator trick. A restore
journey should call the production supervisor/root operation, observe the new
writable head, and query the resulting state. It must not replay arbitrary eval
source: evals may have irreversible side effects and are not a recovery log.

### Fixture and provenance rules

Fixture facts should be transacted through one normal production API. The
transaction records factual provenance using the runtime-reliability model:

- `:seon.db/user` references the acting root, human, or agent identity;
- `:seon.db/process` identifies boot, config, REPL, or another genuine process;
- domain facts remain facts, not records of evaluator implementation steps.

The harness does not need an `eval` database user or a new gym process
identity. If a fixture is installed by the root user through the REPL/config
process, record those existing facts. Inspect metadata may separately say that
the transaction happened during a particular sample and epoch; that is run
evidence, not domain provenance or authorization.

Fixture declarations should use production identifiers and lookup refs. They
should not create `:seon.gym.*` entities or transport the gym's schema into the
new path.

### Scoring contract

Use three evidence levels, in this order:

1. **Database facts.** Query whether the required plan items, knowledge facts,
   refs, statuses, provenance, and absence conditions exist at the final
   coordinate.
2. **Typed runtime trajectory.** Check restart events, agent identity
   continuity, close reason, error codes, turn boundaries, and bounded eval or
   resource counts from structured results.
3. **Semantic output quality.** Use an optional model judge only where facts and
   trajectory cannot establish usefulness.

Examples of good mechanical assertions:

- a plan has several durable items before restart;
- completed items remain completed and an open item is resumed afterward;
- the same agent ref owns both phases;
- a later answer retrieves the right schema'd fact and rejects a nearby
  distractor;
- the final branch contains no unregistered attribute or invalid reference;
- a button action creates the expected fact and the next rendered HTML contains
  the corresponding semantic value;
- a run terminates with a typed close reason before a configured bound.

Examples of assertions to reject:

- the reply contains a particular phrase;
- the prompt contains a heading or prescribed sentence;
- the transcript includes a namespace name;
- eval source matches a regular expression;
- the agent emits an exact number of prose paragraphs;
- a canvas uses exact CSS classes when equivalent semantic output is valid.

For multi-part journeys, prefer separate Inspect scorers or a dictionary-valued
score with named legs such as state, continuity, and answer. Inspect metrics
then aggregate those scores across epochs. Do not recreate the gym's axis and
scorecard objects.

When a canvas is part of the answer, preserve both render twins as artifacts:
the raw agent-facing/AI representation and the HTML representation. Mechanical
UI scoring should check database state, control routing, and semantic DOM
content. A model judge may inspect the AI twin or screenshot only for qualities
such as clarity that cannot be expressed mechanically.

### Provider routing and paid gates

The agent model is selected inside Seon through its normal database/config and
provider routing. The Inspect task should use `model=None` for the system under
test. A judge model, if present, is a separate explicit Inspect scorer role and
must be recorded independently.

Every paid run must require an explicit operator opt-in and record:

- exact Seon provider and model;
- exact judge provider and model, if any;
- relevant model configuration;
- dataset and sample ids;
- epoch count;
- time, turn, output-token, and cost ceilings;
- Seon and Inspect source revisions;
- config digest and base database coordinate.

Inspect's built-in model cost limit can observe model calls made through
Inspect. It cannot fully account for provider calls made behind Seon's HTTP
boundary. Seon should therefore return aggregate usage/cost evidence from its
normal run API and enforce its own production turn/token/cost bounds; Inspect
records those values and supplies the outer wall-clock fence.

An env-gated test that silently skips the model and reports green is not a paid
evaluation. Paid suites should be separate explicit commands/jobs whose result
is `passed`, `failed`, or `not run`, never a vacuous pass.

### Artifacts

The Inspect `.eval` file is the authoritative evaluation log. It should contain
or reference:

- dataset version, sample id, epoch, and seed;
- exact input messages and lifecycle choreography;
- agent id and model configuration;
- base branch/commit/time coordinate;
- final branch/commit/time coordinate;
- restart, restore, timeout, and retry events;
- structured run results and scorer evidence;
- raw prompt and transcript blob refs;
- both context/canvas render formats when relevant;
- final answer and optional screenshots;
- transaction provenance summaries;
- Seon git revision, compiled bundle revision, and config digest;
- resource usage and cost evidence;
- scorer implementation/version and optional judge configuration.

Inspect writes evaluation logs incrementally and supports recovery from an
interrupted evaluation. Use that facility for control-plane recovery, while
ensuring a retried Seon sample receives a fresh branch. The `.eval` log and a
retained failed branch together should be enough to inspect a failure without
rerunning it.

Do not maintain `scorecard.jsonl` as an additional historical truth. A human
dashboard can derive compact trend rows from `.eval` files and identify each
row by the source artifact hash. Deleting or recomputing that projection must
not lose evidence.

## Scenario disposition

### Move to focused CLJS tests

The following capability shapes belong in production tests because they do not
need a stochastic model:

- blank-message refusal;
- typed capability-envelope honesty;
- the deterministic S01 stub pipeline;
- schema validation and registered-attribute rejection;
- stable error envelopes and non-wedging recovery functions;
- run termination and timeout boundaries;
- render registration, route wiring, and button/input dispatch;
- lifecycle operations over temporary database branches.

Tests should call functions or HTTP boundaries with deterministic inputs and
assert typed results and database effects. They should not boot a real provider,
construct full context merely to locate a heading, or match exact response
text.

### Move to Inspect journeys

Consolidate the gym scenarios into a few behavioral journeys:

| Existing capability family | New journey | Evidence |
| --- | --- | --- |
| `plan-resume-across-restart`, multistep todo tracking | `long_term_planning` | plan facts before/after restart, same agent, durable progress, bounded completion |
| findings/consult storage, category and cross-category recall | `memory_store_recall` | registered schema, provenance refs, later-turn query result, distractor rejection |
| cross-agent finding recall | Optional memory variant | writer and reader agent refs plus shared database facts |
| narrow-context retrieval | Optional retrieval variant | target fact selected from larger fixture without checking prompt text |
| interactive/canvas scenarios | `ui_tiles`, after stabilization | routed control event, resulting datom, updated HTML/AI twin, no console/server error |
| configuration/context comparison | Dedicated measured experiment if still valuable | token estimate and behavioral outcome from normal debug/context data, never prompt substrings |

Do not port every scenario one for one. Many current scenarios are variants of
the same memory capability and would inflate runtime without increasing
coverage. Use a small generated dataset or several samples inside one Inspect
task when variants measure genuinely different distractors or boundaries.

### Delete rather than migrate

Delete these elements at cutover:

- health, workout, and expense fixture language;
- todo-only placeholder journeys that duplicate long-term planning;
- prompt-heading and prompt-substring predicates;
- transcript substring predicates;
- regular expressions over eval source;
- exact final-response wording;
- custom gym axes, result prose, pass-rate history, and scorecard presentation;
- model-gated tests that count as passing when no model ran;
- scenario-loader tests whose only purpose is the retired gym EDN format.

Preserve useful capability intent in the new neutral samples and preserve the
old implementation in Git history. Do not add an archive reader or legacy
compatibility namespace.

### Data worth migrating

For each retained capability, migrate only factual evaluation inputs:

- a stable sample id and capability tag;
- neutral user messages;
- production-shaped seed facts and refs;
- the phase/restart/restore choreography;
- expected database facts and forbidden facts;
- trajectory bounds;
- an optional semantic rubric;
- the legacy scenario id as non-authoritative source metadata, when useful for
  tracing the migration.

Do not migrate the gym's predicate AST, schema keywords, scoring maps, or
rendered scorecard strings.

## Deletion scope

Once the migrated journeys and focused tests have live proof, remove in the
same cutover branch:

- `test/seon/gym/driver.cljs`;
- `test/seon/gym/scorecard.cljs`;
- every gym-only test namespace;
- gym scenario/config EDN;
- `bin/gym` and `bin/gym-scorecard`;
- gym-only aliases, CI jobs, docs, and command references;
- gym-only public helpers after verifying no production caller remains;
- the gym dependency/comment around `always-my-nses` in
  `src/seon/agent/ctx/namespaces.cljs` if its non-gym behavior is no longer
  required.

Do not touch `acme/gym/**` during this refactor. It is outside this branch's
ownership and another agent is working in ACME. Its eventual migration can use
the stable Inspect interface after the Seon core cutover.

There should be no compatibility interval in a committed state: no
`seon.gym-v2`, no adapter translating gym predicates to Inspect scorers, and no
dual score histories. Capture an old baseline before the patch if useful,
prove the new path during development, then delete the old path before landing
the cutover.

## Concurrency and performance

Inspect's `max_samples` should bound the number of leased Seon process groups.
The practical concurrency unit is one sample/epoch:

```text
sample + epoch
  -> writable Datahike branch
  -> sole writer
  -> one CLJS pod
  -> one or more agents inside that sample only
```

Several agents participating in one cross-agent sample can share that sample's
cluster. Unrelated samples cannot.

Prior calibration found two concurrent samples delivered useful throughput
(about 1.84×), while four contended heavily. Re-measure after boot seeding,
instrumentation, and render fan-out are fixed; do not encode that observation
as a permanent magic concurrency constant. A calibration command can select a
safe local default from CPU and memory ceilings, while CI pins an explicit
value.

Avoid opening the web UI SSE feed during non-UI journeys. An open feed can
cause repeated whole-render work and substantial RSS sawtoothing on a grown
store. UI journeys should open only the required feed, cap render work, capture
evidence, and close it deterministically.

## Local and CI test tiers

### Default developer gate

- Focused CLJS unit/integration tests only.
- No gym.
- No paid model.
- No repeated complete boot for each logical assertion.
- Fast Python `pytest` for Inspect adapter parsing, dataset generation,
  scorers, artifact projection, and error mapping using fakes/fixtures.

### Runtime acceptance gate

- Explicit command, not hidden inside the default suite.
- Fresh scratch branch and real production lifecycle.
- One deterministic scripted-agent or fixture-provider journey for branch,
  restart, restore, query, and artifact mechanics.
- One offline web UI control/render journey when that surface is stable.
- Mechanical evidence only.

### Paid behavioral evaluation

- Explicit operator, nightly, or milestone job.
- Real Seon provider and model recorded in the artifact.
- Initially the two core journeys only.
- More than one epoch only when the statistical question requires it.
- Optional judge independently enabled and budgeted.
- Hard turn, token, cost, wall-clock, and sample concurrency bounds.

### Upstream benchmark gate

- Separate from internal reliability evaluations.
- Harbor for Terminal-Bench/Harbor-authored tasks.
- SWE-bench through the existing Inspect adapter or the upstream-required
  runner.
- Never block the fast developer gate on downloading benchmark environments or
  invoking paid models.

## Ordered migration

### Phase 1 — freeze the boundary

- Declare `src-inspect-ai` the only internal evaluation control plane.
- Pin the tested Inspect submodule SHA and make package metadata accurate.
- Add a small compatibility test around the used Inspect APIs.
- Remove the fake Inspect model requirement.
- Inventory each gym scenario into focused test, Inspect journey, or deletion.

Exit proof: the existing solver can run a deterministic sample using
`model=None`, and its `.eval` log opens with the pinned Inspect version.

### Phase 2 — provide production lifecycle operations

- Land the runtime-reliability database branch/commit coordinate model.
- Expose typed supervisor operations for fork, attach, start, restart, restore,
  close, and cleanup.
- Expose a typed read-only query/snapshot operation at an explicit coordinate.
- Seed fixtures through `seon.db` and the normal provenance/config path.
- Mechanically test every operation without Inspect.

Exit proof: a scratch branch can be created, mutated, restarted, queried,
restored, queried again, and cleaned up using only production APIs.

### Phase 3 — reduce the Inspect adapter

- Introduce the single sample lease around those lifecycle operations.
- Delete arbitrary wire-REPL/Datahike forms and duplicate schema setup.
- Add final/base coordinates and lifecycle events to `TaskState` metadata.
- Make retry allocate a fresh branch from the same retained base.
- Consolidate Python dependencies and isolate Harbor as an optional extra.

Exit proof: one deterministic Inspect sample survives pod restart, produces a
scorable final coordinate, and cleans up without process or branch leakage.

### Phase 4 — migrate the two core journeys

- Finish `long_term_planning` from the existing planning journey.
- Implement `memory_store_recall` with neutral schema'd facts and a later-turn
  retrieval.
- Score database facts and typed trajectory first.
- Add a model judge only for a clearly named semantic leg.
- Capture prompt/transcript blobs and final database coordinates.

Exit proof: both journeys fail when their essential state transition is
deliberately removed and pass after it is restored; wording-only changes do not
alter the mechanical score.

### Phase 5 — replace score history

- Remove the custom Python scorecard writer and alarm code.
- Use Inspect epoch reducers/metrics and `.eval` artifacts.
- Add a read-only generated trend projection only if humans need it.
- Define branch and artifact retention/garbage-collection policy.

Exit proof: every displayed score traces to an immutable `.eval` artifact and
can be regenerated without `scorecard.jsonl`.

### Phase 6 — cut over atomically

- Capture any final gym baseline needed for comparison.
- Move deterministic regressions to focused tests.
- Remove all gym code, tests, scenarios, scripts, aliases, and docs.
- Remove the gym from the default CLJS gate.
- Run the trimmed default tests, Python adapter tests, deterministic runtime
  acceptance, and the two explicit paid journeys.
- Query the live scratch stores and inspect the produced `.eval` artifacts.

Exit proof: no `seon.gym` runtime or test namespace remains, no command invokes
it, the default gate has no paid/skipped pseudo-tests, and all retained
capabilities have one clear owner.

### Phase 7 — expand only from evidence

- Add the web UI journey after event/render behavior is stable and mechanically
  observable.
- Add memory variants only when they detect a distinct failure mode.
- Tune sample concurrency from profiling after startup fixes.
- Integrate external benchmarks through their native Inspect/Harbor adapters.

Exit proof: every new sample names a failure it catches that existing samples
do not, and its runtime/cost is visible.

## Open owner choices

These decisions affect policy rather than framework mechanics and should be
made before Phase 5:

1. **Failed-branch retention.** Recommendation: delete passing sample branches
   after verified artifact export; retain failed and harness-error branch heads
   for a fixed seven-day debugging window, with an explicit pin for milestone
   runs. Is seven days the desired default?
2. **Trend projection.** Recommendation: `.eval` files plus artifact storage are
   authoritative; a dashboard derives a cache/projection keyed by artifact
   hash. Should that projection live only in local generated data, or should a
   compact non-authoritative summary be committed for milestone comparisons?
3. **First paid battery.** Recommendation: start with exactly long-term
   planning/restart/resume and database store/later-retrieve. Defer UI until its
   mechanical controls are stable. Is cross-agent memory important enough to
   be a third initial sample, or should it follow?
4. **Historical fixtures.** Recommendation: leave old workout/expense/todo
   scenarios in Git history only and port their capability semantics into
   neutral samples. Is any old scenario result required as a named historical
   benchmark series?

Framework selection itself does not need another experiment unless a missing
requirement is identified. Inspect already satisfies the control-plane needs
and already has the deepest correct integration in this repository.

## Source notes

### Primary external sources

- [Inspect AI documentation](https://inspect.aisi.org.uk/) describes the
  open-source task/dataset/solver/scorer model, agents, sandboxes, and extension
  surface.
- [Inspect custom scorers](https://inspect.aisi.org.uk/custom-scorers.html) and
  [metrics](https://inspect.aisi.org.uk/metrics.html) document deterministic or
  model-based scoring and aggregation.
- [Inspect evaluation logs](https://inspect.aisi.org.uk/eval-logs.html) and
  [error handling](https://inspect.aisi.org.uk/handling-errors.html) document
  incremental `.eval` artifacts, sample recovery, and retry behavior.
- [Inspect parallelism](https://inspect.aisi.org.uk/parallelism.html) and
  [limits](https://inspect.aisi.org.uk/setting-limits.html) document sample
  concurrency and execution/resource fences.
- [Inspect agents](https://inspect.aisi.org.uk/agents.html) documents custom and
  external agent integration.
- [Inspect AI source repository](https://github.com/UKGovernmentBEIS/inspect_ai)
  provides the MIT license and current implementation; the repository's pinned
  copy is `reference-code/inspect-ai`.
- [Harbor source repository](https://github.com/harbor-framework/harbor),
  [core concepts](https://www.harborframework.com/docs/core-concepts), and
  [running evaluations](https://www.harborframework.com/docs/run-jobs/run-evals)
  document its task/environment/verifier model, arbitrary agents, parallel
  trials, and artifacts.
- [DeepEval agent evaluation](https://deepeval.com/docs/getting-started-agents),
  [custom metrics](https://deepeval.com/docs/metrics-custom), and
  [source repository](https://github.com/confident-ai/deepeval) document its
  trace-centered agent evaluation and Apache-2.0 implementation.
- [LangSmith experiment configuration](https://docs.langchain.com/langsmith/experiment-configuration)
  and [self-hosting](https://docs.langchain.com/langsmith/self-hosted) document
  repetitions, concurrency, evaluator configuration, and Enterprise self-host
  terms.
- [Braintrust evaluation](https://www.braintrust.dev/docs/evaluate),
  [running evaluations](https://www.braintrust.dev/docs/evaluate/run-evaluations),
  [remote evaluations](https://www.braintrust.dev/docs/evaluate/remote-evals),
  and [self-hosting](https://www.braintrust.dev/docs/admin/self-hosting) document
  its evaluator, worker, experiment, and managed-control-plane model.
- [Promptfoo assertions](https://www.promptfoo.dev/docs/configuration/expected-outputs/)
  and [output formats](https://www.promptfoo.dev/docs/configuration/outputs/)
  establish its prompt/provider assertion and result-reporting focus.

### Repository sources

- `src-inspect-ai/src/seon_inspect/solver.py`
- `src-inspect-ai/src/seon_inspect/planning.py`
- `src-inspect-ai/src/seon_inspect/freeze.py`
- `src-inspect-ai/src/seon_inspect/scorecard.py`
- `src-inspect-ai/src/seon_inspect/bench_common.py`
- `src-inspect-ai/src/seon_inspect/tb2_agent.py`
- `src-inspect-ai/src/seon_inspect/tb_agent.py`
- `test/seon/gym/driver.cljs`
- `test/seon/gym/scorecard.cljs`
- `test/seon/gym/driver_test.cljs`
- `test/seon/gym/scenarios/`
- `docs/prds/gym-v2/design.md`
- `docs/prds/gym-v2/scenarios-draft-2026-06-28.md`
- `docs/prds/agent-fsm/research/inspect-ai-harness-deep-dive-2026-06-30.md`
- `docs/prds/diffusion-dynamic-context/research/gym-third-party-adoption-2026-06-28.md`
- `docs/prds/runtime-reliability/research/test-runtime-trim-design-2026-07-12.md`
- `docs/prds/runtime-reliability/research/cljs-test-suite-speed-and-quality-audit-2026-07-12.md`

### Short source excerpts

The framework descriptions above were checked against the current primary
documentation. The shortest phrases that expose each tool's intended center
are:

> “An open-source framework for large language model evaluations.”

— [Inspect AI](https://inspect.aisi.org.uk/)

> “A task includes an instruction, environment, and test script.”

— [Harbor core concepts](https://www.harborframework.com/docs/core-concepts)

> “Agent evals in deepeval are powered by tracing.”

— [DeepEval agent evaluation](https://deepeval.com/docs/getting-started-agents)

> “an add-on to the Enterprise plan”

— [LangSmith self-hosting](https://docs.langchain.com/langsmith/self-hosted)

> “Your code handles task execution. The playground handles the rest.”

— [Braintrust remote evaluations](https://www.braintrust.dev/docs/evaluate/remote-evals)
