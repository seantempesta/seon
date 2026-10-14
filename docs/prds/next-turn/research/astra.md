---
type: research
status: independent research; source inspection only
date: 2026-09-26
lane: astra
---

# Predict the next information need, not the whole next turn

Seon already turns declared reads and refreshed reads into evaluation history, then selects whole recent history units for the prompt (`src/seon/turn.clj:1913-1966`, `:2034-2074`; `src/seon/cluster/prompt.clj:301-333`). The principal approach proposed here is a small transformation of explicit action evidence into a few ordinary reads, using the existing documentation, schema, evaluation and rendering owners.

## Evidence boundary

Read the mandate and `CLAUDE.md` end to end first. This is research, not implementation or an installation claim. No JVM, MCP evaluation, tests, lifecycle commands, model execution, commit or other lane's session was used. Only this file is written. Initial checkout HEAD: `20b1568603e8d1e732b31aa2568b3279c436458b` (`git rev-parse HEAD`). Citations describe the working-tree source inspected during this session, which other lanes may change. Malli checkout: `a89ebef0572628b9198b825e5aa8b59484902593` (`git -C reference-code/malli rev-parse HEAD`). All prospective costs and thresholds below are estimates or proposed experiment criteria, not measurements.

## Independent ideas, before evaluating the session's starting candidate

These ideas were written before reading the CLM and needle READMEs or evaluating entity scoring. They differ in what supplies information: constraints, declared intent, failure evidence, proof obligations, changed facts, temporal sequences, experimental questions, partial syntax, controlled interventions, and concrete examples.

### 1. Derive the missing arguments from the next function's contract

**Mechanism.** Given an explicit or predicted function and selected arity, render its caller-facing contract, then only the named schemas needed for unresolved arguments. Given a literal map prefix, identify required absent keys; do not treat a hole as a supplied `nil`. Preserve alternatives when arity or a union branch remains ambiguous. Suggest available argument values only when the agent already has them and their provenance and schema fit. A schema says what is admissible, not which business value is correct.

**Verified ingredients.** Arity rows already carry argument counts and input/output references (`resources/seon/schemas/seon.fn.arity.edn:1-31`). Documentation already resolves these references and compiles contracts in the carried projection (`src/seon/sci/eval.clj:2330-2359`); its agent-facing adaptation accounts for supplied arguments (`:2361-2390`). Malli provides resolved children, cached validators and explainers (`reference-code/malli/src/malli/core.cljc:2614-2621`, `:2645-2684`). Reuse those functions; no second schema interpreter.

**Predicts/decides.** Which argument slot, key and schema the next expression needs. Mostly deterministic System 1 after the function is known.

**Why better than a dump.** It presents the exact construction problem in front of the agent, not every schema connected to a namespace. This does not discover an unknown operation by itself.

### 2. Let the agent announce its next information need

**Mechanism.** At a natural turn boundary, the agent may name one intended operation and the uncertainty blocking it: inspect a definition, construct a request, compare two values, or explain a refusal. Initially use an ordinary explicit documentation/read form; experiment with a declared intent value only if that saves a round trip. The next system context derives the named reads. Intent is a revocable forecast, never a queued write or an instruction to execute the eventual action.

**Verified ingredients.** Receipt schemas preserve source, comment, namespace and author (`resources/seon/schemas/seon.cluster.eval.edn:27-47`). Declared render sources are parsed into ordinary forms (`src/seon/turn.clj:1939-1966`). Generated reads and prior reads already converge in `system-plan` (`:2034-2074`). There is no claim that a structured next-intent protocol already exists.

**Predicts/decides.** Obtains a forecast from the actor rather than reconstructing it from prose. The machine then decides what evidence satisfies the request.

**Why better than a dump.** One purposeful read can remove a known uncertainty. It also supplies better future training labels than assuming every displayed entity was useful. Risk: an extra planning ceremony or stale plan can cost more than a direct read.

### 3. Turn a failure into a narrowly addressed repair context

**Mechanism.** Use the last refusal's operation, failed argument path, expected form and offending value to select the applicable contract and exact implicated definition. Add one independently relevant caller or successful example only when needed to distinguish misuse from a producer bug. Show the evidence as an ordinary value; do not replace the actual error with a generated diagnosis or automatically retry a call.

**Verified ingredients.** Instrumentation produces the function, arity and operation, and uses the existing Malli explanation for problem paths, expected forms and values (`src/seon/instrument.clj:550-573`). Receipt rows preserve errors and triage (`resources/seon/schemas/seon.cluster.eval.edn:63-83`). Documentation resolves current contracts (`src/seon/sci/eval.clj:2341-2359`).

**Predicts/decides.** The immediate question after an error: which assumption just failed, and where to inspect it. Errors provide a sharper signal than general task similarity.

**Why better than a dump.** A missing key asks for one input schema; an output violation may ask for the producer. They should not receive the same neighborhood. The rule must distinguish these cases, and cannot infer a correct repair from type validity alone.

### 4. Work backward from the next unproved task obligation

**Mechanism.** Choose the smallest current proof obligation that blocks progress; show its test, subject, input contract and relevant current failure. After an edit, select evidence about whether that obligation has changed, rather than repeating all task materials. Must-reads remain mandatory. No automatic task settlement or inferred acceptance.

**Verified ingredients.** Task declarations contain intent, must-reads and links, and define subject/proof edge shapes (`resources/seon/schemas/seon.task.edn:8-37`); `seon.issue/task-edges` derives those subjects and proof symbols over a supplied database value (`src/seon/issue.clj:716-732`). `gate-set` derives reaching tests (`src/seon/fn.clj:2001-2016`), and `verified?` checks results against current input/reach evidence (`src/seon/test.clj:1535-1565`). Task-seeded proof context is a requirement/prototype, not assumed installed (`docs/research/agent-platform/language-requirements-2026-09-24.md:110-120`).

**Predicts/decides.** Which evidence would establish the next small piece of progress. This is goal-directed constraint propagation, not imitation of the previous action.

**Why better than a dump.** It ties each displayed definition to a concrete obligation. It is weaker for exploration and design tasks whose proofs do not yet exist; absence of a proof is an explicit unknown.

### 5. Prefer the changed fact that can change the next decision

**Mechanism.** Start with the agent's explicit reads and compare their current dependency evidence. Present changed values that bear on the active question; where possible, identify the earliest changed prerequisite. Do not confuse any database change with an actionable change, and do not mistake a changed value for proof of causality. This focuses an existing refresh mechanism rather than inventing a watcher.

**Verified ingredients.** `latest-evaluations` groups by parsed source and namespace (`src/seon/turn.clj:1906-1911`, `:1968-2000`). `system-plan` derives none/unchanged/changed and reads changes (`:2034-2057`). `system-turn` selects non-unchanged sources and compares shown values (`:2184-2221`). Read eligibility checks connection identity, declarations, receipts and effects (`:2002-2030`).

**Predicts/decides.** Whether the agent's next useful act is reconsidering a specific premise, rather than continuing its previous plan.

**Why better than a dump.** Information gain comes from a changed premise, not from receiving the same neighborhood again. A whole historical-read scan can still dominate cost; no latency claim is made here.

### 6. Learn short action sequences with exact symbolic backoff

**Mechanism.** Parse historical actions into resolved function heads plus argument roles: task identity, function symbol, schema key, branch-local result handle, literal, or unresolved expression. Count next-action distributions conditioned on a short sequence, task subject and last outcome. Keep an abstention option. Predict one or two function heads or read templates, then resolve all identities in the current branch. Never paste old arguments or execute historical writes.

**Verified ingredients.** Evaluation rows contain source, namespace, ordinal, result/error and read evidence (`resources/seon/schemas/seon.cluster.eval.edn:27-86`). `seon.eval/of-agent` queries evaluations in turn/ordinal order (`src/seon/eval.clj:9-16`, `:44-57`). Database history and as-of are exposed through the database owner (`src/seon/db.clj:3008-3033`).

**Predicts/decides.** A small distribution over next read or call shapes. Train on sequences, not bag-of-entities similarity; condition on success/failure rather than merely the last symbol.

**Why better than a dump.** Repeated workflows may need only two likely operation contracts. This is a cheap falsifiable baseline before a learned encoder, and it can reveal that the sequence has no useful predictive signal.

### 7. Ask the cheapest question that separates plausible next actions

**Mechanism.** If two hypotheses disagree about the next step, select one ordinary read whose possible answers would distinguish them. Example: inspect the exact current function contract to distinguish malformed arguments from a changed API; inspect current test evidence to distinguish a missing proof from a failed proof. No new planner is necessary for a first experiment: compare two explicit candidate reads.

**Verified ingredients.** Contract documentation is an ordinary derived value (`src/seon/sci/eval.clj:2392-2413`); current verification is a query through `verified?` (`src/seon/test.clj:1535-1565`); generated forms use the existing system-turn path (`src/seon/turn.clj:2157-2207`).

**Predicts/decides.** Which observation reduces uncertainty about the action, not which action is most popular. A proposed information-gain calculation needs a calibrated distribution; it is not available merely because counts exist.

**Why better than a dump.** One discriminating result can eliminate an entire branch of speculation. The danger is spending more time choosing a question than answering it.

### 8. Attach context to the partial form, like a semantic autocomplete

**Mechanism.** Once a generated prefix identifies an operation, resolve its arity; once it identifies an argument/map path, expose only that slot's schema, available bindings and plausible continuations. Use a draft form as a candidate and validate complete literal subvalues; do not run an incomplete expression. The first experiment is offline prefix prediction, not an interactive decoder integration.

**Verified ingredients.** Current documentation derives arity contracts and supplied inputs (`src/seon/sci/eval.clj:2341-2390`); Malli's validators/explainers accept complete values (`reference-code/malli/src/malli/core.cljc:2645-2684`). SCI turn forks preserve a private layer (`src/seon/sci/eval.clj:3156-3189`). Subsequent inspection found a particularly useful existing seam: reader errors carry Edamame containers/token information and can resolve the enclosing call and argument index (`src/seon/sci/reader.cljc:583-621`). Reuse and probe that seam before contemplating another parser. Error diagnostics are not yet proof of a complete incremental-completion API, live decoder hook, or serializable private-world replay.

**Predicts/decides.** The next symbol, argument role or map key conditional on an actual prefix. This is narrower and potentially much easier than predicting a whole turn from scratch.

**Why better than a dump.** The prefix supplies immediate relevance. But context cannot simply be inserted retroactively into an already-issued provider request; a form/tool boundary or supported decoding interface would be required.

### 9. Learn usefulness by removing context, not by observing clicks

**Mechanism.** In a later authorized experiment, give the same task state to the same agent configuration with one candidate context unit present versus absent. Measure successful next work, repair reads, time and tokens, with executable checks where possible. Use historical action coverage only to screen candidates. Reconstruct the database basis where retained; treat missing private values as unreplayable cases.

**Verified ingredients.** Captures store exact prompt, basis and ordered contribution evidence (`src/seon/context.clj:359-400`). Prompt composition can detect a captured-byte mismatch (`src/seon/cluster/prompt.clj:335-360`). `as-of` exists (`src/seon/db.clj:3021-3033`), but private SCI state is preserved in memory (`src/seon/sci/eval.clj:3156-3189`), not demonstrated reconstructible from a capture.

**Predicts/decides.** The causal value of showing a unit for this class of decision, rather than its probability of being mentioned afterward.

**Why better than a dump.** It teaches what can be omitted without making the agent worse. This is an evaluation method, not a reason to add another runtime service or to replay arbitrary side effects.

### 10. Show one valid witness instead of an encyclopedic schema expansion

**Mechanism.** For the selected operation, choose a small existing test call or prior successful input with the same contract revision. Show the actual example beside the contract and required-key explanation. Replace only identity-bearing values with explicitly marked roles in a teaching example; do not present invented placeholders as runnable values. A matching example illustrates syntax, not the intended business argument.

**Verified ingredients.** Function rows have call edges (`resources/seon/schemas/seon.fn.edn:49`) and reaching-test derivation (`src/seon/fn.clj:2001-2016`); evaluation source is retained (`resources/seon/schemas/seon.cluster.eval.edn:36-41`). Contract refs and verification evidence allow revision-aware selection (`resources/seon/schemas/seon.fn.arity.edn:1-31`; `src/seon/test.clj:1553-1565`). These do not imply historical calls already have parsed argument-role labels; that derivation is proposed.

**Predicts/decides.** Which concrete example helps construct the next call. A single example can complement #1 where a large nested schema is technically complete but cognitively poor.

**Why better than a dump.** An immediately applicable witness plus its limits teaches a call more directly than many adjacent declarations. Counterexamples and changed contracts must disqualify stale witnesses.

## Ranking and priced experiments

**Recommendation: test #1 first, #4 second, #6 third.** They successively exploit a known action, a known goal, and learned uncertainty about the action. #3 is the high-signal error case of #1; #10 supplies its examples. #2 is worth a very cheap protocol trial but should lose to an ordinary direct read unless announcing intent removes a real round trip. #5 should remain part of correctness regardless of prediction. #7 and #9 are ways to evaluate/select information. #8 is the ambitious follow-on once a measurable prediction ceiling exists.

All numbers in this table are **estimates for a narrow first useful implementation**, including focused verification but excluding broad architecture retirement. Latency is **incremental CPU work per turn**, assuming bounded inputs and already-acquired program/schema state; it is not an end-to-end measured system-turn budget. Cold acquisition and large dependency closures must be measured separately. Model cost means incremental inference/training, not the ordinary agent call. Local electricity and developer salaries are excluded from dollar estimates.

“$0 API/turn” below means no extra predictor call. Changing context changes the ordinary model's token bill and possibly its prompt-cache reuse; neither net savings nor a free larger prompt is assumed. Measure total billed input/output tokens and total completion time in the paired pilot.

| Rank | Proposed scope | Engineering days, estimate | Added / deleted lines, estimate | Per-turn latency / extra memory, estimate | Model / dollar cost, estimate | Principal risk and sacrifice |
|---|---|---|---|---|---|---|
| **1: contract-directed context (#1)** | One known operation; expose applicable contract and unresolved literal-map slots via existing documentation/render owners | **2–4 days** | **45–85 production + 40–70 verification; 0 deletion credited** | **2–15 ms**, **1–5 MiB** retained derived data | **No model; $0 API/turn** | Overloaded/relational contracts do not determine values; starts only once a function is named. Unknown expressions remain unknown. |
| **2: next proof obligation (#4)** | One active task, its explicit proofs/must-reads, one current missing/red obligation; direct links before transitive expansion | **3–5 days** | **55–95 production + 45–80 verification; 0 deletion credited** | **5–40 ms**, **1–10 MiB**; full reaching-test work excluded | **No model; $0 API/turn** | Poor or saturated proof links produce large or irrelevant sets; sacrifices broad exploratory discovery. |
| **3: symbolic action forecasts (#6)** | Offline sequence counts; top two function/read shapes with abstention; current branch resolves arguments and schemas | **3–6 days** | **50–90 production + 100–180 offline/verification; 0 deletion credited** | **1–10 ms** lookup plus **2–20 ms** selected reads; **1–20 MiB** for a bounded table | **No neural model/training; $0 API/turn** | Sparse actions, policy drift and shell-heavy transcripts; sacrifices novel-operation prediction for precision and cheap failure. |

**Line-budget accounting.** These are independent alternatives, not permission to stack three new subsystems. Count verification and tooling too: gross additions would be 85–155, 100–175 and 150–270 lines respectively. I claim **no deletions** because useful existing documentation, refresh and history code cannot simply be removed to fund this work. Any cut above roughly 100 added lines needs a smaller demonstrated design or explicit owner justification before integration. The first experiment can remain an offline query/analysis artifact; no model server, second registry or new renderer is justified. Existing function-contract reference derivation (`src/seon/program.cljc:1011-1045`) and documentation expansion (`src/seon/sci/eval.clj:2330-2390`) are the mechanisms to reuse, not rebuild.

### Smallest experiments that can falsify the top three

These are **proposed future experiments, not runs performed by this lane**. Thresholds are predeclared decision criteria, not statistical guarantees.

1. **Contract-directed context: test the oracle head first.** Select 30 historical call-construction or input-refusal cases from several tasks; hide the subsequent repair. Give the candidate method the actual next function head, an intentionally generous upper bound. Compare existing `doc` output with a slot-focused view using the same contract. Include multi-arity calls, optional keys, supplied arguments, schema revisions, unresolved symbols and nested values. Stop if it cannot expose the needed schema/key without inventing values in at least 24/30 cases, or if it is not smaller/more usable than `doc`. That falsifies the value proposition before predicting anything. If it passes, authorize a small paired agent experiment: stop if focused context causes more failed constructions or merely adds another turn. Offline selection needs no paid model; a 20-pair continuation pilot is **estimated $1–10**, with a chosen model and token cap fixed before spending.

2. **Proof-directed context: test coverage before clever ordering.** Choose ten tasks with concrete proofs; use only each task's state before the next recorded edit/probe. Compare task proof/must-read selection with the existing neighborhood and with a direct subject-only baseline. An evaluator blinded to selection method identifies the definition/schema needed to advance each obligation. Stop if three or more tasks have no useful proof links, if needed definitions are omitted in more than one task, or if median context fails to shrink by at least 30% at equivalent coverage. Trace common-library edges separately: a reference to a query helper is not a request to display all its callers. A later paired completion check must confirm that token reduction preserves success; smaller context alone is not a pass.

3. **Symbolic forecasts: test a counts-only ceiling.** Take 100 resolved native REPL action boundaries across at least ten task/session families, reserving whole later families as holdout. Normalize names using the recorded namespace/aliases, not regex; exclude unresolved shell text rather than inventing a native call. Compare unconditional popularity, repeat-last-action, and conditional short-sequence counts. Score only the first subsequent agent-authored action, not generated context reads. Stop if the best conditional method cannot achieve 80% top-two precision on a subset covering at least 25% of holdout boundaries, or cannot beat the strongest trivial baseline by ten percentage points at matched coverage. Also score schema-family coverage: different correct calls may need the same schema. If there are too few resolved examples, that is a data-availability failure, not evidence for buying a larger model. Passing only justifies a shadow predictor; show predictions to agents only after a causal pilot.

Each screen could take **0.5–1 engineering day, estimate**, before production code. Small samples reject obviously weak ideas; they cannot establish reliable deployment thresholds. Broader chronological evaluation follows only a promising screen.

## What the starting CLM/entity/render-cache idea gets right and wrong

The candidate is the proposal at `docs/prds/next-turn/README.md:21-33`, evaluated here after the ten independent ideas above.

### Keep the useful pieces

- **Separate state from candidate encoding.** The port has separate `project` calls and computes one state's dot products against action vectors (`src-clm/src/seon_clm/engine.py:103-120`). This is a reasonable optional scorer for a modest, explicitly constructed action set.
- **Use content-valid reuse.** The source already distinguishes renderer code, supplied inputs, projection, private callable identity and read dependencies (`src/seon/render.clj:795-840`, `:879-885`). Preserve that correctness when changing eviction policy.
- **Bound recomputable memory.** An LRU/weighted cache is independently useful; prediction quality should not be a prerequisite for fixing memory growth. Apply eviction to disposable derived artifacts, not historical shown text (`src/seon/repl.clj:143-147`) or exact prompt captures (`src/seon/context.clj:359-400`).
- **Keep the scorer replaceable by a table or nothing.** Rank explicit candidate reads/functions after mandatory task/error evidence. Let the experiment determine whether CLM beats #6 and simple task-local baselines; do not make an 8B encoder a prerequisite for every ordinary turn.

### Change the object being scored

**Entity relevance is the wrong objective by itself.** A highly relevant entity can be information the agent already knows. The valuable unit is a proposed observation that helps construct or choose the next action. Examples: this operation's input contract, this particular failing value, or whether this test's current evidence is valid. A source body, a signature and one input field have different costs and uses even when attached to the same entity. An entity×renderer Cartesian product creates variants without establishing marginal usefulness.

**Next-action likelihood is not context value.** Training on observed actions asks “what did this policy do after this prompt?” It can learn the mistakes, detours and context-induced habits we want to eliminate. System-generated reads must not become action labels. The local reference describes the state/action contrastive objective (`reference-code/CLM/README.md:192-204`); the upstream [CLM model card](https://huggingface.co/Contrastive-LM/CLM-v0.1-8B#limitations) explicitly says the model only scores supplied candidates and benchmark verifier results require fine-tuning. Neither establishes that entity-render scores improve Seon's next turn. That is an inference to test with #9, not an existing capability.

**Raw scores do not justify confidence thresholds.** The MLX scorer returns scaled cosine logits and sorts them (`src-clm/src/seon_clm/engine.py:112-120`). Calibrate abstention on held-out Seon decisions. A top item always exists even when none of the candidates helps. Duplicate renderer variants also distort a softmax without adding new information.

### Correctness and cost traps

1. **“Input digest + renderer digest” is insufficient if input means only the entity.** A renderer may depend on supplied database facts, schema projection, viewing context and transitive helper definitions. Existing cache evidence tracks precisely such inputs and reads (`src/seon/render.clj:795-840`, `:879-885`). Hashing the final text identifies text only after rendering; it does not tell you that a stale cached render may be reused. If “input” includes all of this evidence, keep that existing ownership rather than introduce another key scheme.
2. **The README's per-agent cache description is not the inspected implementation.** `shared-cache` scopes the cache to the branch and shares it among same-branch forks (`src/seon/render.clj:1637-1660`), unlike `docs/prds/next-turn/README.md:67-68`. This lane did not measure current cache size or eviction behavior, and does not repeat the 70 MB figure as current fact.
3. **The matrix multiply is not the whole per-turn cost.** The scorer projects missing candidate texts, sorts scores and builds results as well as encoding the state (`src-clm/src/seon_clm/engine.py:40-53`, `:103-120`). Any budget needs candidate acquisition, invalidation, render misses, tokenization, transfer, ranking, prompt composition and contention. The default encoder keeps the last 2,048 tokens (`src-clm/src/seon_clm/encoder.py:38-52`): appending recent history can push task intent out of its input. Design the state projection explicitly.
4. **Candidate vectors are not free.** At the documented 512 dimensions (`src-clm/README.md:43-48`), 100,000 float32 vectors require **204,800,000 bytes, about 195 MiB, calculated**, before strings, IDs, cache metadata or encoder weights. The vector cache is count-bounded and keys by namespace plus full text (`src-clm/src/seon_clm/engine.py:33-53`); it is not a whole-pipeline byte bound.
5. **“Re-rendering is cheap” must be established by workload.** Entity acquisition can pull refs and namespace connections on a miss (`src/seon/render/walk.clj:427-454`). Cache eviction correctness and the cost of recreating an entry are separate questions. An LRU that repeatedly evicts expensive useful entries can fit memory while losing the latency target.
6. **Do not replace the graph with an unbounded catalog scan by default.** The graph already supplies explicit edges and bounded acquisition (`src/seon/render/walk.clj:942-964`, `:1032-1080`). Those relations are strong candidate generators and explanations. Use a learned scorer only for unresolved choice among purposeful observations; high-degree nodes are a reason to narrow the question, not to embed the entire store.

**Verdict.** Keep bounded render reuse, separate state/action vectors, and CLM as one challenger. Drop “score every renderable entity every turn” as the starting architecture. First show incremental useful-next-step coverage over the three cheap candidates, under the same context-token budget and with mandatory task/error evidence held constant. No CLM latency or accuracy was measured here; the mandate's timing figures are not reproduced results of this lane.

## Token-level ambition: yes, but distinguish three different products

1. **Prefix-conditioned context selection.** A function head and argument position can select schemas deterministically, using the reader diagnostic seam found above. This is the most direct answer to the owner's autocomplete idea. Measure the prediction ceiling at prefixes ending before the call, after the head, after the first argument, and inside a literal map. Compare remaining required-schema uncertainty at each point. A sharply improving curve would justify moving the decision closer to the form boundary.
2. **Small-model suggestions.** Use needle or a small coder to suggest one form or argument shape, then read and validate it through the existing owners. The inspected needle generation path uses KV-cached greedy decoding (`src-needle/src/seon_needle/generate.py:68-91`); encoder input prioritizes query tokens and puts tools into remaining space (`:28-36`). Its README's parity, speed and ten-example overfit reports (`src-needle/README.md:51-77`) are historical port evidence, not proof of current Seon autocomplete accuracy. The README's zero contrastive-head finding (`:81-89`) is also a dated report, not a checkpoint inspection by this lane.
3. **Speculative target-model decoding.** This accelerates token generation through target-model verification; it is a different problem from deciding what the agent needs to know. Upstream MLX-LM already exposes a draft model and draft-token count in [its generation implementation](https://github.com/ml-explore/mlx-lm/blob/main/mlx_lm/generate.py), so research that seam before writing another decoder. This does not prove compatibility with needle, Seon's provider path, or a different tokenizer, nor that extra GPU work is profitable.

**Dream-big hypothesis.** The hard model should choose the uncertain semantic step. The database, reader, contracts and a cheap conditional predictor should supply the routine syntax and immediate evidence surrounding that step. Exact whole-turn prediction is unnecessary if the correct few schemas remain identifiable across several plausible continuations. For example, two candidate operations may differ but consume the same named request schema; a distribution over actions can yield a confident information decision. Measure that possibility rather than assuming it.

## Use the trajectory corpus without fooling ourselves

The file inventory was inspected with Python `Path.glob/rglob` and `stat`, without loading models or evaluating transcript forms. The following command reproduces its scope and prints only metadata:

```python
from pathlib import Path
import json
claude = Path.home() / '.claude/projects/-Users-sean-src-seon'
codex = Path.home() / '.codex/sessions'
for label, root, recursive in [
    ('Claude top-level', claude, False),
    ('Claude recursive', claude, True),
    ('Codex recursive, all projects', codex, True),
]:
    paths = list(root.rglob('*.jsonl') if recursive else root.glob('*.jsonl'))
    print(json.dumps({'scope': label, 'files': len(paths),
                      'bytes': sum(p.stat().st_size for p in paths)}))
```

Observed in this session: **39 / 538,147,488 bytes** for top-level Claude files; **1,211 / 1,929,912,834 bytes** recursively; **2,988 / 8,073,788,616 bytes** for all Codex sessions. The recursive Claude count came from a second metadata-only invocation; these are not a simultaneous snapshot. This does not reproduce the mandate's “39 files, ~4.3 GB” (`docs/prds/next-turn/README.md:48-51`). Files may be changing; dataset manifest and scope must precede model-cost estimates. All-project Codex size is not Seon training volume.

A structural sample confirmed distinct formats: the Claude file `/Users/sean/.claude/projects/-Users-sean-src-seon/0440793c-b7cd-4f9b-a518-0145ad3ec693.jsonl:1-3` starts with queue operations then a user message; `/Users/sean/.codex/sessions/2025/09/24/rollout-2025-09-24T14-45-14-01997d0b-43d0-76b3-91ca-b2f0288a1d99.jsonl:1-3` starts with session metadata, then response items. Only key names/types were printed. This proves neither next-action predictability nor that all files concern Seon.

The smallest honest dataset would:

- Filter Codex by recorded working directory/task lineage; connect resumed/forked sessions before splitting. Dedupe repeated prompts and carried transcript history across parent/child sessions.
- Prefer native Seon receipt source/outcome pairs for initial training. Shell commands and documentation searches are a different action space; cross-domain transfer is a separate experiment, not an assumed free corpus.
- Split by task family and time, not randomly by adjacent turns. Resolve program/schema identity as it existed at the decision; future definitions, later successful repairs and target-turn reasoning must not leak into the input.
- Exclude generated system reads from next-agent-action labels. Record their presence in the original prompt as an exposure variable. Otherwise a selector learns to imitate itself.
- Report **head recall, argument-role accuracy, needed-schema coverage, calibration/abstention, successful progress, repair-read count, prompt/output tokens, total latency and peak memory** separately. Exact next-action match is only one diagnostic: a different valid action can be better.
- Use paired interventions for usefulness. Historical captures prove what was shown, not what was necessary (`src/seon/context.clj:359-400`). Preserve all unavailable-context cases in the report; private objects prevent assuming a database snapshot reconstructs a whole agent (`src/seon/sci/eval.clj:3156-3189`).

## What should not be built

- **A second planner/executor that runs predicted writes.** Speculation is useful for reads, parsing and checking explicit candidates; an isolated database branch does not establish isolation of host effects. Existing refresh eligibility already distinguishes connection ownership, receipts and effects (`src/seon/turn.clj:2002-2030`). Prediction must never turn confidence into authorization or claim it executed the agent's intent.
- **A universal schema-to-Clojure generator.** Arbitrary predicates, binding expressions and application semantics are not solved by valid map syntax. Reuse Malli for complete values and reader diagnostics for prefixes; leave unresolved constraints visible. Do not add a parallel language or hard-ban every continuation absent from a closed tool catalog.
- **A graph-distance replacement made of global embeddings.** Graph edges answer dependency questions; learned associations answer uncertain behavior questions. Removing the first to install the second loses exact evidence. Neither fixed distance nor similarity alone is a sufficient relevance policy.
- **A new string clipping layer.** The prompt selects whole history units (`src/seon/cluster/prompt.clj:301-333`), and saved shown text is returned unchanged (`src/seon/repl.clj:143-147`). Select new observations and use declared rendering; do not rewrite the agent's historical observations to fit a predictor.
- **A render/vector cache that becomes durable truth.** Derived text/vector eviction is allowed; history, captures and actual results have different owners. A text digest cannot substitute for dependency freshness.
- **Training on provider reasoning as if it were a ground-truth plan.** Reasoning has a declared field (`resources/seon/schemas/seon.ai.attempt.edn:13`), but its existence proves neither complete capture nor correctness. It can be a prior-turn feature in a properly bounded dataset, not future-visible supervision masquerading as causal evidence.
- **A large service before an oracle experiment.** If perfect knowledge of the next operation does not make contract-directed context useful, an expensive predictor cannot rescue that design. If a proof-linked view cannot beat `doc` and a direct task read, stop. If counts cannot beat popularity, establish a real information deficit before adding a neural model.

## Source caveats and handoff

The source contains the new `walk/graph` with default depth 3 (`src/seon/render/walk.clj:1032-1057`), while the inspected opening's `declared-sources` still calls `walk/neighborhood` (`src/seon/turn.clj:1913-1931`). Prompt acquisition separately defaults depth to 2 (`src/seon/cluster/prompt.clj:37`, `:374-382`). Treating all of these as one installed “fixed 2-hop selector” would be inaccurate. Source is not runtime adoption evidence.

The older task-context design explicitly reports that its scratch prototype disappeared and its small-context timing was not reproducible (`docs/research/agent-platform/task-context-install-design-2026-09-25.md:56-65`). It also records a historically missing task renderer there; today's inspected task schema declares a render pair (`resources/seon/schemas/seon.task.edn:23-27`). Do not turn dated reports into current runtime facts. No runtime tools were called, so live health, adoption, arming, actual latencies, model availability and peak memory remain unverified by this lane.

Only this research file is changed; no source/test lines changed, no commit created. The next owner decision is which one falsification screen to authorize, not which prediction infrastructure to install.

## Final summary

1. **Contract-directed context:** once a function is named, derive its unresolved argument schemas through existing documentation and Malli. Estimated 2–4 engineering days; no model; 2–15 ms incremental warm work. First test with the actual next function supplied as an oracle.
2. **Next proof obligation:** use a task's explicit proofs, must-reads and current evidence to expose the smallest concrete obstacle. Estimated 3–5 days; no model; 5–40 ms incremental bounded work. Reject it if ten real tasks show poor coverage or no useful size reduction.
3. **Symbolic action forecasts:** count short resolved REPL sequences, predict at most two operation/read shapes, and abstain. Estimated 3–6 days; no neural model; 3–30 ms including selected reads. Reject it if held-out task families do not beat trivial baselines.

**The most important insight:** the next action need not be certain for its information needs to be certain. Several plausible continuations may require the same contract, changed fact or proof. Predict that small information requirement, verify it against the agent's branch, and render it through the ordinary REPL path. Keep CLM as a challenger and bounded caching as a separate memory concern. Judge success by better next work, not by similarity scores or imitation of historical actions.
