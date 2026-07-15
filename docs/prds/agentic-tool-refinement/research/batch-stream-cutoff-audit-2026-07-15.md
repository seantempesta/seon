---
type: research
status: active
tags: [research, agent, flow]
---

# Batch, stream, and early-cutoff audit

## Decision

Keep `:batch` as the hypothesis to beat, not as a predetermined winner. It
amortizes one large stable prompt across several forms and lets a small model
advance several independent ideas before another inference. `:stream` already
has one structural advantage: the OpenAI-compatible adapter aborts generation
at the first parse-confirmed executable form, so fabricated result tails never
enter the reply. The paired experiment must measure whether that safety and
faster feedback outweigh the extra prompts, database coordinates, and cache
churn.

Early cutoff is a transport decision over exact model-authored prefix bytes. It
never rewrites a reply, inserts a delimiter, fabricates a result, or converts a
limit into success. A first valid form is a normal `:stream` completion. Exact
repetition and a generous formless-output budget may stop wasted generation,
but retain their cutoff reason and prefix as model/no-progress evidence. A
provider failure, deadline, or evaluator cancellation remains infrastructure
evidence and cannot reach a capability scorer.

The paired comparison is blocked on durable multi-form execution positions.
The current process-local batch vector is ordered, but the database connection
from a turn to its evals is cardinality-many and the external projection sorts
by time and random identity. That cannot prove authored or execution order.

## Dependency ledger

| Dependency or mechanism | Selected identity | Source and existing proof | Constraint on this unit |
|---|---|---|---|
| Seon source | `de5e606d389c2bb058030ab14745349308df71a7` at audit start | `src/seon/agent/turn.cljs`, `src/seon/agent/loop.cljs`, `src/seon/eval.cljs`, `src/seon/repl/internal.cljc`, `src/seon/ai/openai_compat.cljs`, `src/seon/web/serve.cljs`, `src/seon/config.cljs` | Mode, parsing, evaluation, cancellation, evidence, and policy stay in their existing owners. |
| ClojureScript | vendored `946d75f3483c0c8e784e6668bff2c71a25619a77`; runtime version `1.12.145` | `reference-code/clojurescript/`; `src/seon/eval.cljs` | Agent forms are self-hosted CLJS; `eval-batch!` awaits each form and records values, not Promises. |
| rewrite-clj parser | existing Seon dependency | `src/seon/repl/internal.cljc`; `test/seon/repl/internal_test.cljc` | One parser classifies forms, comments, prose, literals, and read failures. Streaming may use a cheap close gate only when the same parser confirms an executable form. |
| Inspect AI | `05322696a0f784ec399ef6abbafd3d2a250ea9cc`; nested `ts-mono` overlay intentionally dirty per the source lock | `reference-code/inspect-ai/`; `src-inspect-ai/pyproject.toml` path dependency | Inspect owns tasks, solvers, scoring, limits, cancellation accounting, and native `.eval` logs. Provider batching is not Seon batch mode. |
| Inspect cancellation | same Inspect pin | `tests/test_cancellation_logging.py`, `tests/test_operator_interrupt.py`, `tests/test_sample_limits.py`, `tests/log/test_recover_e2e.py`, `tests/log/test_streaming_completion.py` | Cancelled work retains partial samples/events. Operator interruption is a logged operator limit, not a successful model completion. Recovery preserves incomplete evidence without inventing a score. |
| Inspect ordered calls | same Inspect pin | `tests/model/test_parallel_tools.py`, `tests/tools/test_call_tools.py`, `tests/model/test_generate_loop.py` | Ordered call ids and ordered result messages are the useful idiom. Seon forms remain Seon eval facts; do not synthesize Inspect tool calls. |
| Native Seon solver | local `src-inspect-ai` at the admitted Seon revision | `src-inspect-ai/src/seon_inspect/solver.py`, `catalog.py`, `source_admission.py` | The current `urllib` request inside `anyio.to_thread.run_sync` is a blocking response door. Inspect cancellation cannot yet promptly cancel the pod/provider request. |
| Existing research and issues | current branch | [[inspect-batch-stream-cancellation-2026-07-15]], [[qwen25-coder-05b-database-diagnostic-2026-07-15]], [[../../../seon/issues/multi-form-eval-order-is-not-durable]], [[../../../seon/issues/configured-turn-limit-masks-mode-specific-budget]] | P0b precedes this P4 comparison. The first 0.5B diagnostic supplies the real repetition/no-form attractor, not accepted comparative evidence. |

## Current semantics

### Batch

One LLM call returns a buffered completion. `parse-forms` produces an ordered
vector of entries. `eval-batch!` then awaits entries sequentially in source
order. Every form has its own result/error recording boundary; a failed form
does not prevent a later independent form from running. Namespace movement and
earlier database writes can affect later forms in the same batch because each
entry completes before the next begins.

This is multi-action inference, not parallel execution. The model may submit N
independent investigations at once, but Seon executes and awaits them in order.
The model sees none of their runtime values while it is still authoring that
reply. All real values and errors appear in the next rendered transcript.
Model-authored result echoes are prose and create no result facts.

Batch therefore offers fewer model calls and a longer cacheable prefix per unit
of work, at the cost of delayed feedback. A dependent later form may fail after
an earlier error, although unrelated later forms still run.

### Stream

The turn passes `:seon.ai/stream? true`. Only the OpenAI-compatible adapter
currently honors it; Anthropic, DiffusionGemma, and typeahead buffer and ignore
the flag. `stream-until-form!` consumes SDK deltas, scans for the first
top-level delimiter close, confirms at least one executable `:form` through
the ordinary parser, then calls the SDK stream's `.abort`. A closed top-level
map or vector is not enough because the parser demotes it to prose.

The turn retains entries only through the first parsed form. Any extra text in
the completing delta stays in the raw reply blob but is not evaluated. Aborted
provider usage is explicitly estimated because the final usage chunk is lost.
If the stream ends without a form, its whole returned prefix is parsed normally
and the turn contributes zero attempted forms. Three consecutive zero-form
turns currently close the run `:no-forms`.

The existing focused tests prove split-delta form completion, rejection of a
demoted data literal as a cutoff, natural formless completion, SDK abort, and
transport failure as an error value. They do not prove that `.abort` stopped
remote compute, because the stub only records the method call.

### Evidence and the present order gap

The raw reply blob is byte ground truth. `/agents/run` also returns exact prompt
and reply bytes per turn, prompt/reply token estimates, the final database
coordinate, and selected eval rows. The Python solver preserves those values
in native sample metadata.

`eval-batch!` returns ordered eval ids in process memory, but persisted
`:seon.agent.turn/evals` membership has no order. `/agents/run` currently sorts
rows by `[:seon.eval/at :seon.eval/id]` and omits the originating turn id and
execution position. Equal timestamps and random ids make that presentation
order, not execution truth. No multi-form outcome claim is admissible until
[[../../../seon/issues/multi-form-eval-order-is-not-durable]] is closed.

## What may be cut off without rewriting output

| Observed condition | Safe action | Truthful outcome |
|---|---|---|
| First parser-confirmed executable top-level form in `:stream` | Abort immediately after the delta containing that form; retain the exact prefix and evaluate only the first form. | Normal stream turn with deliberate first-form cutoff and estimated usage. |
| Exact repeated suffix before useful progress | Abort after a database-configured exact-byte/token repetition threshold; retain every emitted byte and the matched-span evidence. | No-progress cutoff. Evaluate already complete forms only under a separately proven batch policy; zero forms advances the formless streak. Never call the truncated text a natural completion. |
| No executable form after a generous generated-output budget | Abort at the configured bound and retain the exact prefix. | Formless-output cutoff; zero attempted forms. It remains model/runtime evidence, not infrastructure success. |
| Per-attempt timeout or run deadline | Abort through the existing signal/fence and retain partial turn evidence. | Timeout/infrastructure close; capability scoring is rejected. |
| Inspect/operator cancellation | Propagate cancellation to an addressable pod request, then to the provider controller. | Native cancelled/operator-limited sample with partial evidence; no model score. This propagation is not built through the blocking door yet. |

Do not cut off on semantic similarity, a prose classifier, a guessed intended
form, an unmatched delimiter that might later close, or a result-looking
string. Do not normalize whitespace or case for repetition decisions: exact
bytes are explainable and reproducible; semantic normalization creates an
unreviewable output-rewrite boundary. Do not append a closer or turn prose into
comments in the transport. Parser recovery may classify the exact returned
prefix after generation stops, using the same ordinary reader path.

An exact-repeat guard should be conservative: require a minimum repeated span,
several consecutive exact copies, and no newly accepted form since the first
copy. A formless-output guard is a separate ceiling. Combining them hides
whether the model was looping or merely verbose.

## Database evidence shape

The same immutable database value must determine policy, execution evidence,
and the cacheable transcript. Use attributes and connections rather than an
opaque per-turn telemetry map:

- the config singleton carries mode-specific work ceilings, deadline, total
  run output-token budget, formless-output budget, exact-repeat minimum span
  and repeat count, and no-form streak limit;
- an absent optional cutoff attribute disables that guard; do not store nil or
  a sentinel;
- a turn carries its mode and, only when cutoff occurs, a cutoff reason,
  emitted-output estimate, and exact repeated-span measurements;
- the existing reply blob retains the exact emitted prefix; hashes identify
  evidence but never replace it;
- each eval event connects to its originating turn and carries a contiguous
  zero-based execution position; the turn's component ref continues to own
  lifecycle/removal;
- the run's derived counts distinguish inference calls, turns, attempted
  forms, accepted forms, failed forms, and output tokens; and
- the external projection reads one frozen database value, emits evals ordered
  by the stored position, and rejects absent, duplicate, foreign-turn, or
  non-contiguous positions as infrastructure evidence.

Normal natural completion needs no stored `natural? true` flag. Absence of a
cutoff fact is itself the signal. Deliberate first-form cutoff should be
recorded because it changes provider usage and explains why usage is estimated.

## Configurable limits

The database already owns `:seon.config/repl-mode`, batch turn limit `100`,
stream form limit `300`, and deadline `1,800,000` ms through the selected Aero
manifest. Those values are current defaults, not recommended experimental
budgets. The model row owns per-completion maximum tokens and adapter timeout.

The comparison still exposes hardcoded or process-only policy:

- the consecutive no-form limit is a source literal of three;
- the LLM-attempt and loop-step timeouts are direct environment accessors
  rather than database policy; and
- no run-total output-token ceiling, formless-completion ceiling, or exact
  repetition guard exists.

Before P4, move behavioral numbers into one schema'd manifest section that is
reconciled into scalar database facts. Environment overrides belong only in
the Aero manifest resolution path; runtime consumes the database. Record an
explicit local/paid resource profile as data instead of inferring mode or
limits from provider/model strings. Per-agent overrides may win where the
existing run policy already permits them, but the resolved values used by a
run must be reconstructable from its opening database coordinate.

## Inspect-native paired experiment

Use one native Inspect task factory with unchanged dataset and scorer. The
Inspect model remains `mockllm/model`; the real model is the pod's exact
absolute snapshot. Run serially until the operator lease exists.

### Controlled mechanism fixtures

These are native Inspect samples backed by a deterministic incremental
OpenAI-compatible fake provider, not a new runner:

1. **First form plus infinite tail.** The provider emits a complete valid form
   and then repeats forever. Stream must abort promptly, provider-side work
   must observe cancellation, the exact prefix must land in the reply blob,
   one eval must land, and the native scorer must run.
2. **Formless exact repetition.** The provider emits a fixed exact prose span
   forever. The repeat guard must abort at the configured evidence boundary,
   record zero evals and the cutoff reason, and never append or repair bytes.
3. **Verbose unique prose.** The provider emits non-repeating prose with no
   form. Only the separate formless-output budget may stop it. This falsifies a
   repeat detector that is really a length detector.
4. **Three authored forms.** One batch reply contains three forms, including
   one failure between two independent successes. Exactly three uniquely
   identified evals must land at positions `0,1,2`; the third must run; all
   results appear only in the next prompt; the assistant reply remains
   byte-identical and contains no runtime-inserted values.
5. **Cancellation during generation.** Cancel the Inspect sample after the
   provider starts. The cancellation must reach the addressed pod run and
   provider signal, preserve a cancelled native `.eval` with partial turn
   evidence, and produce no capability score.

The fifth fixture requires an addressable/cancellable composition request or
disconnect-aware server. `urllib` in `anyio.to_thread.run_sync` cannot provide
that proof today. Inspect's `sample_active().interrupt(...)` is the idiom for
operator accounting, not a substitute for transport cancellation.

### Real-model pair

After the mechanism fixtures and durable order gate, run the same frozen task
membership twice from byte-identical seeded cluster/database state:

- arm A: `:batch`;
- arm B: `:stream`.

Hold exact source admission, task/sample ids, model snapshot, temperature,
maximum completion tokens, plan facts, namespace/context surface, seed,
deadline, total output-token budget, cutoff policy, and scorer constant. Use a
fresh isolated cluster per arm; do not toggle the singleton beneath an existing
agent because agent/context facts and history would differ.

The primary comparison is outcome under equal wall-clock and total generated
output-token budgets. Mode-specific turn/form ceilings stay generous
backstops, because equating one batch turn to one stream form would structurally
bias the result. Report success and every resource dimension rather than
collapsing them into one score:

| Dimension | Required retained evidence |
|---|---|
| Outcome | unchanged task score, infrastructure admission, failure classification |
| Work | inference calls, turns, attempted/accepted/failed forms, close reason |
| Generation | provider tokens when final usage exists; estimator plus cutoff reason when abort loses usage |
| Time | elapsed run time, per-turn generation and eval duration |
| Safety | fabricated result echoes, read failures, repetition/no-form cutoffs, provider cancellation acknowledgement |
| Context/cache | exact prompt hashes and sizes, longest byte-identical prefix between turns, provider cached-token telemetry when available |
| Database | start/end coordinates, turn ids, eval ids, originating turn, contiguous positions, restart/read-back equality |

Use at least one task where independent inspection/edit/query forms can be
batched, and one where later action genuinely benefits from seeing the first
result. The first tests inference amortization; the second tests feedback
latency. A single task cannot decide the default.

## Cache and transcript implications

Batch sends the stable context once for N forms. Stream commonly sends it N
times, each time at a later database coordinate with another turn and eval in
the transcript tail. Provider prefix caching may make those repeated inputs
cheap, but it cannot make them free; the experiment must retain actual cached
token telemetry when the provider supplies it and byte-prefix measurements
when it does not.

The 50-turn window and 25-turn chunk rotation improve long-run cache stability,
but most P4 samples should finish before the first rotation. Do not attribute a
short-run difference to rotation. When rotation does occur, compare at the
same policy boundary and record which whole chunk disappeared. No model-written
compaction is introduced.

Early cutoff reduces completion work but can cause an earlier next prompt.
That trades output tokens for input/cache traffic. Cutoff facts belong in the
new transcript tail; they must not reorder stable context blocks or mutate old
prompt bytes. Three batch eval results append together on the next turn, which
creates one larger tail delta instead of three separate prompt deltas. Durable
positions are therefore both a correctness requirement and a cache-diff
requirement.

## Falsifiers

- A stream aborts on a balanced map/vector, comment, or prose fragment that
  `parse-forms` does not classify as executable.
- A byte appears in the stored reply that the provider did not emit, or an
  emitted byte before cutoff is absent.
- Exact repetition and generic formless length produce the same cutoff reason.
- A cutoff is scored as natural success, or a provider/Inspect cancellation
  reaches a capability scorer.
- Provider-side generation continues beyond a bounded cancellation grace even
  though the client reports abort.
- A multi-form reply projects missing, duplicate, non-contiguous, or
  foreign-turn positions; ordering by timestamp/id is still accepted.
- A failed middle form prevents an independent later form from executing, or
  an unattempted form receives a fabricated eval.
- Runtime values appear in the assistant reply rather than only in eval facts
  and the next prompt.
- The two real-model arms differ in any admitted source, seeded database fact,
  context bytes before the mode-specific fragment, model identity, or budget.
- A claimed cache saving lacks either provider cached-token telemetry or an
  exact byte-prefix measurement.
- Anthropic, DiffusionGemma, or typeahead is reported as a stream arm while it
  still ignores `:seon.ai/stream?`.

## Ordered acceptance path

1. Finish the admitted P0b serial slice. Do not let this later P4 work displace
   the current measurement gate.
2. Close the durable multi-form order issue in the existing eval/turn/database
   mechanism and make the external projection fail closed on bad positions.
3. Prove the three-form live fixture through the next-turn transcript and
   native `.eval` read-back.
4. Reconcile all behavioral limits through the config manifest into database
   facts; preserve absence for disabled optional guards.
5. Generalize the existing incremental consumer around exact prefix events and
   explicit stop reasons. Keep parser confirmation and provider cancellation
   in the current owners; add no output rewriting layer.
6. Add request-scoped cancellation propagation from Inspect through the pod
   door to the provider controller, then prove partial native-log retention.
7. Run the controlled first-form, repeat, unique-formless, and cancellation
   fixtures. Reject the unit on any falsifier before using a real model.
8. Run the frozen real-model batch/stream pair from identical seeded clusters,
   inspect every native log, and classify every failure.
9. Select the default by deterministic outcome first, then total generated and
   cached tokens, elapsed time, fabrication, and recovery. Preserve an explicit
   alternate profile when task structure, not provider identity, favors it.
