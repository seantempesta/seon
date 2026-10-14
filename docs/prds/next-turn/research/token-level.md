---
type: research
status: active
tags: [research, agent, context]
---

# Token- and form-level prediction for Seon agents

**Lane:** token-level (2026-09-26). **Question:** could we predict an agent's
Clojure forms as it writes them, and use that to (a) put the right
definitions/schemas in context, (b) speed up or constrain generation, (c) catch
mistakes before evaluation? **Method:** provider docs (fetched 2026-09-26),
Seon source, the archived autosuggest program, and a static measurement over
real forms (`tmp/next-turn-token-level/`, no JVM, no models run).

## Bottom line

1. **The program graph removes little generation work but a lot of
   uncertainty.** Measured on 10,718 recent `eval_clj` inputs, only **~4% of
   BPE tokens** are forced by scope (symbol and keyword suffixes after their
   unique prefix). Another **~7%** are closing delimiters. So "free tokens"
   are about 11%, which caps any speed-up from structure-forced decoding at
   roughly 1.1×. The conditional picture is much stronger:
   - 92% of unquoted symbols resolve in scope.
   - 60% of calls have a known arity; 25% have a single fixed arity.
   - Once a contracted head is chosen, **84% of literal argument-map keys are
     in its declared key set.** The median set holds **5 keys (≈2.3 bits)**.

   The graph's value is therefore in **choosing what goes into context** and
   **validating what comes out**. It is not a way to skip tokens.
2. **Hosted providers cannot be steered mid-completion in any useful way.**
   - DeepSeek (Seon's default) offers stop sequences, `json_object`, strict
     JSON-schema tool arguments, and a *beta* assistant-prefix continuation.
     It offers no grammar, no logit bias, and no predicted outputs.
   - Anthropic rejects prefill with a 400 on Claude 4.6 and later. Its
     structured output is JSON-schema only.
   - OpenAI has the richest controls: a Lark/regex CFG on GPT-5 custom tools,
     and Predicted Outputs on GPT-4o/4.1. Neither is Seon's provider.
   - Injecting context mid-stream is possible **only** as
     abort → new request → continue from the assistant prefix. That works on
     DeepSeek beta and on local models, and costs one extra time-to-first-token
     per injection.
3. **Real token-level control is local.** `mlx_lm` already exposes
   `logits_processors` and `draft_model` hooks (below). The archived needle
   effort's lesson was that the *projection* (context), not model capacity,
   capped prediction quality. It also showed that constrained decoding carries
   real weight for small models.
4. **The cheapest real win is at the read stage.** Evaluation errors that are
   statically catchable are ~3.9% of evals, or ~24% of all eval errors. The
   stream already reaches a sink per chunk, so completed top-level forms could
   be read, checked and evaluated while the model is still generating. That
   also allows early cancellation.

## 1. What provider APIs allow today

| capability | DeepSeek (default) | Anthropic | OpenAI | usable for Seon forms? |
|---|---|---|---|---|
| Assistant prefill / continue a partial reply | **Yes, beta**: last message `role: assistant`, `"prefix": true`, base URL `https://api.deepseek.com/beta` ([docs](https://api-docs.deepseek.com/guides/chat_prefix_completion)) | **No**: "Prefilling is not supported on Claude 4.6 and later models" (400) ([docs](https://platform.claude.com/docs/en/build-with-claude/working-with-messages)) | No prefill on chat completions | DeepSeek only. Enables abort-inject-continue. |
| FIM (prefix + suffix) | Yes, beta, `max_tokens` ≤ 4K ([docs](https://api-docs.deepseek.com/guides/fim_completion)) | No | No (legacy only) | Could complete *inside* a form. Not a chat turn. |
| Predicted outputs / speculative hints | No | No | Predicted Outputs on GPT-4o/4.1 family. Rejected tokens are billed; no tools, no logprobs ([docs](https://developers.openai.com/api/docs/guides/predicted-outputs)) | Only for re-emitting known text, such as rewriting an existing `defn`. |
| Logit bias | Not listed in the chat-completion reference ([ref](https://api-docs.deepseek.com/api/create-chat-completion)) | No | Token-id bias on chat completions | Token ids are not symbols; a 10k-symbol scope cannot be biased with it. |
| Logprobs | `logprobs`, `top_logprobs` ≤ 20 (same ref) | No | Yes | Useful for *measuring* uncertainty at a head or key after the fact. |
| Grammar / structured output | `response_format: json_object`; tool calls with `strict` (JSON-schema) (same ref) | JSON-schema via grammar-compiled constrained sampling, grammars cached 24 h. **"No regex or context-free grammar"** ([docs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs)) | **CFG** (Lark or regex) on GPT-5 custom tools, enforced via LLGuidance ([cookbook](https://developers.openai.com/cookbook/examples/gpt-5/gpt-5_new_params_and_tools)) | JSON wrapping breaks the one REPL path (forms are text read by the Clojure reader). An s-expression CFG is possible only on GPT-5. |
| Stop sequences | ≤ 16 | Yes | Yes | Already used: `:seon.config.ai/stop ["#:seon.repl"]` (`config/default.edn:450`). Stops are static strings, so they cannot mean "any symbol finished". |
| Streaming + early cancel | SSE. Closing the connection stops generation; tokens already produced are billed (standard behaviour, not a documented DeepSeek guarantee) | SSE | SSE | Seon streams by construction (below). |
| Prompt caching | Automatic prefix cache. Units end at user-input ends, model-output ends and fixed intervals. Exact-prefix only; lasts hours to days ([docs](https://api-docs.deepseek.com/guides/kv_cache)). Flash hit $0.003–0.006/M vs miss $0.15–0.30/M, output $0.60–1.20/M ([pricing](https://api-docs.deepseek.com/quick_start/pricing)) | Explicit `cache_control`. Mid-conversation `system` messages do not invalidate the earlier cached prefix (working-with-messages page above) | Automatic | Any injection must sit *after* the cached prompt. See §4. |

**Seon's current request builder** sends exactly one optional system message
and one user message (`src/seon/ai.clj:677-718`; messages at `:686-689`). An
assistant-prefix message would be a new wire shape. `stop`, `response_format`
and `thinking` are declared wire settings
(`resources/seon/schemas/seon.config.ai.edn:94-119`, `:136-142`).
`extra-body-edn` merges provider-specific fields last but cannot add messages
(`src/seon/ai.clj:644-718`).

**Streaming:**
- `stream-fold` publishes every chunk's snapshot to a sink and keeps sink
  failures away from transport (`src/seon/ai.clj:841-892`).
- The turn installs a sink that `offer!`s the whole partial onto a sliding-1
  channel (`src/seon/turn.clj:4489-4503`).
- A truncated stream keeps what arrived: `interruptible-lines`
  (`src/seon/ai.clj:1155-1170`) and `truncated-completion`, where "downstream
  settles the forms that arrived" (`:1207-1214`).

So the seam that per-form streaming would use already exists. Nothing reads
forms from the partial today.

**Stale skill claim (defect, flagged here, not edited):**
`.agents/skills/llm-providers/SKILL.md` cites `config/default.edn:392-396` for
the default model and `:425` for thinking-disabled. Those lines now hold render
and test configuration. The facts are at `config/default.edn:407-411`
(`deepseek-flash`) and `:440` (`:thinking :disabled`).

## 2. Local options on this Mac

**Hooks that already exist in the installed `mlx_lm` 0.31.3** (`src-needle/.venv`):
- `generate_step(..., logits_processors=[fn(tokens, logits) -> logits])` at
  `mlx_lm/generate.py:307-313`, applied per step at `:409-416`.
- `speculative_generate_step(..., draft_model, num_draft_tokens=2, logits_processors)`
  at `:473-481`.

A grammar- or scope-constraining processor and a draft model therefore need
no fork.

**Published numbers** (estimates for M5 Max; nothing was run here):

| option | evidence | implication |
|---|---|---|
| Decode speed | M4 Max: Qwen3-30B-A3B 109.7 tok/s, Qwen3-8B 93.3, Qwen3-0.6B 525.5; prefix-cache TTFT 245 → 42 ms at a 512-token prefix ([vllm-mlx, arXiv 2601.19139](https://arxiv.org/html/2601.19139v2)) | A local A3B model is roughly at hosted-flash speed. It is not a free lunch. |
| Speculative decoding on Metal | MTP 1.20–1.40× greedy (1.09–1.30× sampled); **n-gram lookup 1.6× on coding-agent turns**; "K-token verify scales close to linearly with K", so depth-1 is optimal on Apple Silicon ([mlx-optiq](https://mlx-optiq.com/docs/mtp)) | Copying from context (n-gram) is the best draft for copy-heavy forms. Deep drafts do not pay on Metal. |
| Grammar-constrained decoding | llguidance ≈ 50 µs/token mask for a 128k vocabulary, Lark-like CFGs; XGrammar < 40 µs ([llguidance](https://github.com/guidance-ai/llguidance)) | Mask cost is negligible next to a ~9 ms decode step. |
| EDN/Clojure grammar | Found no maintained Lark/EBNF grammar for Clojure. S-expressions are a ~20-line CFG; tree-sitter-clojure exists but is not a decoding grammar | Write a small one. The *context-sensitive* parts (heads in scope, keys per contract) belong in a Python logits processor fed from the program graph, not in a CFG. |

**What the archived needle autosuggest effort learned** (sources:
`git show 215447c46^:docs/prds/archive/repl-autosuggest/...`; the path moved
to `archive/` in 607147a62, and the files were deleted in 215447c46; also
`src-needle/README.md`):

- **Speed was never the problem.**
  - Needle 26M MLX: prefill ~127,600 tok/s at 1024 tokens (8 ms), decode
    428 tok/s; about 0.25 s per suggestion (`src-needle/README.md`, "Measured").
  - Stock Qwen2.5-Coder-1.5B-instruct: 0.28–0.92 s per suggestion
    (`research/kt3b-coder-models-2026-07-12.md`).
- **The context was the ceiling.**
  - DeepSeek-v4-pro, given exactly the projection and cards, predicted the
    turn's forms at useful-match **0.261** (0.362 when ingredients coverage
    was ≥ .75; 0.105 below .25).
  - Pearson(coverage, useful) = +0.29, positive in every form kind
    (`research/kt3-signal-ceiling-2026-07-12.md`).
  - A stock 1.5B model with 3 exemplars **tied** the frontier (0.265 vs 0.261)
    at $0 (kt3b).
- **Contracts missing from the cards were ruled a defect.** The KT3-redux run
  was halted because the cards had their `{:malli/schema …}` stripped ("the
  arg contract is missing from every card"). The dominant failure was
  "wrong-fn selection + missing emission, NOT hallucination", with
  hallucinated heads ≤ 3% (`research/kt3-redux-full-index-2026-07-12.md`).
- **Constrained decoding carries real weight for small models.**
  - Unconstrained needle garbles argument keys (design.md).
  - A QLoRA'd 1.5B improved substance (useful .334 among parsing rows), but
    parse fell from .895 to **.593** (39 repetition loops, 48 malformed
    literals). The report concluded: "Reliability, not substance, is the
    binding failure" (`research/qwen-lora-2026-07-12.md`).
- **Needle's 1024-token encoder envelope failed KT1:** 95.3% of real inputs
  overflowed (`research/kt1-tokenizer-envelope-2026-07-12.md`). The
  contrastive head ships as all zeros (README).
- **How it ended.** The work was halted for "garbage in, garbage out"
  (instrument and data correctness), then archived 2026-07-21 as superseded
  (roadmap.md). The checkpoint and adapter survive under `src-needle/checkpoints/`.

**Takeaway for today:** a small local model as a *proposer* is viable and
cheap. It needs (1) the right contracts in its input and (2) a scope- and
contract-constrained decoder. Both come straight from Seon's rows.

## 3. How much of an agent's code is determined by the program graph?

**Scripts** (all in `tmp/next-turn-token-level/`):
- `extract_evals.py`: 13,758 `eval_clj` calls / 12,564 unique sources from
  1,211 transcript files, 2026-07-31 → 2026-09-26, in 3 s.
- `cljreader.py`: a positional Clojure reader. It parses 545/545
  src/test/lib/schema files.
- `measure.py`: builds the index and measures the corpus. Run it with
  `src-needle/.venv/bin/python measure.py > results.json`; 15 s.
- `per_eval.py`: per-eval sizes.
- `defn_rewrites.py`: redefinitions of existing program vars.

**Index** (static, HEAD; no JVM):
- 10,282 vars: 3,746 program vars (2,080 carry `:malli/schema`), 4,238 library
  vars (malli, datahike including its `api-specification`, sci, core.async,
  konserve), and 763 core vars (dumped by `bb`).
- 3,498 registry keys from `resources/seon/schemas/*.edn`.

Tokens are Qwen2.5-Coder BPE, a proxy for DeepSeek's tokenizer.

**Corpora:**
- `src/` forms (54k calls).
- `eval_clj` inputs since 2026-09-12: 10,718 unique evals, 1.91M BPE tokens.

**Caveat:** these are *outside* agents (Claude Code over MCP, mostly JVM
mode), not product Seon agents. Product replies live in Datahike
(`:seon.cluster.eval/source`). Re-running `measure.py` on them is the obvious
next step.

| measure | evals (since 09-12) | src/ |
|---|---|---|
| Unquoted symbols resolving in scope (local/ns/alias/lib/core) | **92.2%** | 96.4% |
| Calls with known arity / single fixed arity (the close paren is forced) | 59.7% / 25.1% | 58.6% / 29.6% |
| Calls to contracted program fns | 12.6% (21,417) | 11.2% |
| Symbol chars after the unique prefix among visible candidates | 27.4% | 28.9% |
| Registry-keyword chars after the unique prefix among 3,498 keys | 17.4% | 17.8% |
| **Literal arg-map keys (to contracted fns) inside the declared key set** | **83.9%** (4,965 keys) | 73.5% |
| Median declared key-set size at such a position | **5 (≈2.3 bits)** | 8 (3 bits) |
| Chars of those keys after the unique prefix within the key set | 42.2% | 37.1% |
| **BPE tokens fully scope-determined** | **3.9%** | 3.1% |
| BPE tokens that are closing delimiters only | 7.0% | 5.5% |
| BPE tokens determined by scope or closers | **11.0%** | 8.7% |

**Where the tokens go** (BPE share by class, evals): delimiters 21%, keywords
21%, quoted symbols/datalog variables/comments/reader macros 15%, strings 8.5%,
core symbols 7.9%, program symbols 7.6%, locals 5.7%, interop 2.2%, numbers
1.9%, whitespace-only 4%. In src, strings (docstrings) are 17%. Values
(strings, numbers, locals, datalog variables) stay free.

**Concentration and injection points** (`per_eval.py`):
- An eval is a median **136** BPE tokens (p90 379).
- It calls a median of 1 qualified program fn (mean 1.53, p90 4); 57% call at
  least one.
- The first program head completes **~10% into the eval** (median), so the
  earliest mid-stream trigger arrives early.
- 624 distinct program fns were called. **The top 20 cover 67% of
  program-head calls; the top 100 cover 86%.**
- Only 1.0% of evals redefine an existing program var (110 evals; 1.1% of
  tokens). That is the Predicted-Outputs case. It is rare for outside agents;
  product agents that `defn` in the REPL are unmeasured.

**Static checks vs the eval's actual error** (evals since 09-12):
- Outcomes: 83.7% ok, 9.9% other runtime, 2.6% unknown symbol, 1.8%
  host-type (ClassCast/NPE), 0.6% syntax, 0.6% timeout, 0.5% contract,
  0.2% arity.
- **Statically catchable classes (unknown symbol, syntax, arity, contract)
  are 3.9% of evals, about 24% of all errors.**
- Recall of this crude HEAD-only index: 55% of unknown-symbol errors, 42% of
  syntax errors, 20% of arity errors.
- It also marks 19% of *ok* evals as having an unresolved symbol. Most of those are API drift: for example
  `seon.operator/connection` was called 1,209 times, but that namespace was
  removed in 076827cc9, and the `seon.test/run` arity changed.

A live-context check would resolve exactly, so these numbers are a floor on
precision. Seon already runs clj-kondo analysis with "syntax/name/arity errors
remain blocking" for program rows (`src/seon/fn/analyzer.clj:22-39`).

**Interpretation:**
- Hard-forced tokens (~11%) cannot deliver meaningful decode speed-ups on
  hosted models.
- What the graph *does* determine is the closed menus: which head (a scope of
  ~10k, but 100 heads cover 86%), which keys (about 5 per head), and which
  arity. A frontier model predicts these nearly perfectly **when the contract
  is in context**, and needle's KT3 showed exactly that coverage gradient.
- So the payoff is in (a) choosing contracts and (c) checking. For a *local*
  model, the same menus become decoding masks.

## 4. Mid-generation context injection

**Mechanism** (DeepSeek beta or local; not Anthropic 4.6+, not OpenAI chat):
1. Stream the completion.
2. On a completed head symbol that is not already rendered in context, close
   the stream.
3. Send `[system, user(prompt), user(card for f), assistant(partial, prefix: true)]`.

**Cache interplay.** DeepSeek cache units end at user-input ends, so the
original prompt still hits. Only the card (~100–300 tokens, estimate) and the
partial (median ~14 tokens at 10% of 136) miss.

**Costs:**
- Dollars per injection at flash miss prices: ~300 tokens × $0.15–0.30/M ≈
  **$0.0001**. Negligible.
- Latency: one extra TTFT per injection. Estimate **0.5–1.5 s**; Seon has no
  measured DeepSeek TTFT on record (searched `docs/`). Tokens in flight after
  the head are wasted.
- At 1.5 contracted fns per eval, injecting every time could double turn
  latency. Injecting only on *misses* is the only sane policy.

**Risks:**
- Quality under a "user message inserted before my half-written reply" is
  untested (a KT-style A/B is needed).
- It needs a new messages shape in `request-body` (`src/seon/ai.clj:686-689`).
- The prefix endpoint is beta.

**Cheaper alternative:** multi-turn micro-steps, where the agent *asks* for the
contract through an ordinary REPL form. This is already possible today and
costs a whole turn.

## Top 3 ideas, with cost estimates

All engineering days and latencies below are estimates.

### 1. Streamed read → check → pipelined eval (the read stage learns to see partial forms)

**What.** The sink already receives every partial (`turn.clj:4489-4503`).
When a top-level form closes, read it once through the one reader
(`src/seon/sci/reader.cljc:785`). Check it against the live SCI context:
resolution, arity, and armed Malli input contracts on literal arguments. Then
either
- evaluate it immediately, overlapping evaluation with generation, or
- on a certain mistake, cancel the stream and return the flat error to the
  agent at once.

This is one stage of the existing pipeline, not a second reader or evaluator.

**Must verify first.**
- Does a reply currently evaluate later forms after an earlier form fails? If
  so, early cancellation changes semantics.
- Does reading a prefix preserve "a form is parsed once"?

**Value.**
- Catches the 3.9% statically catchable evals before any output tokens are
  spent on the rest of the reply.
- Overlaps slow evaluations (queries, tests) with generation.
- On an early error, saves the remaining generation: at p90 379 tokens and an
  assumed ~60 tok/s, up to ~5 s. Dollar savings are cents per hundred turns.

**Cost.** 3–4 days; roughly 80–120 lines in turn/reader, with a matching
deletion of the post-completion whole-reply read if it lands cleanly.

### 2. Contract-first context: render the heads the agent will call, with their contracts; prefetch during the stream

**What.** Two parts:
- **Before the completion:** rank candidate heads by task, trajectory and
  call-graph neighbourhood (the predictability lane's job), and render each
  chosen head *with* its contract and key set. The top 100 heads cover 86% of
  calls, and a key menu of about 5 per head then makes the arguments
  near-determined.
- **During the stream:** once a head completes (10% in), prefetch its
  contract render. This warms the render cache for the check in idea 1 and for
  the *next* turn's changed reads. It adds no provider call.

**Optional experiment.** Abort-inject-continue on DeepSeek beta for misses
only.

**Value.** Directly attacks the measured ceiling (needle KT3: useful-match
0.105 at low coverage vs 0.362 at high coverage).

**Cost.**
- Ranking plus rendering: owned by the synthesis. Prefetch hook: 1–2 days.
- Injection A/B: 2–3 days. API cost under $1 for ~200 turns (flash prices
  above). About +0.5–1.5 s per injection.

### 3. Local System-1 proposer with contract-constrained, copy-speculative decoding

**What.** A local model (Qwen3.6-35B-A3B-4bit already cached; roughly 110
tok/s class) drafts the next form, or drives cheap mechanical turns. It uses:
- an `mlx_lm` `logits_processors` mask with balanced delimiters, heads in
  scope (locals and new `defn` names allowed), map keys from the callee's
  contract at known positions, and registry keywords in pull/query patterns;
- speculation from n-gram/prompt lookup plus the current definition source
  as the draft for `defn` rewrites (depth-1 on Metal).

Hosted frontier models remain the planner.

**Value.**
- 1.2–1.6× decode (published: MTP and n-gram).
- Removes syntax, unknown-symbol and arity failures by construction; that is
  the qwen-lora parse collapse (.593) this was meant to fix.
- $0 per token.

**Cost.** 5–8 days, plus GPU-lane time and an evaluation set. Only worth it
once a local agent tier is a product decision. Memory: ~20 GB for A3B-4bit,
~1–3 GB for a 1.5B.

### Not worth pursuing now

- Anthropic prefill (rejected with a 400 on 4.6+).
- JSON or strict-tool wrapping of forms (splits the one REPL path).
- `logit_bias` (token ids, not symbols).
- OpenAI Predicted Outputs (wrong provider; only 1% of evals redefine a
  known var).
- Retraining needle (the envelope failed KT1; the head is all zeros).

## Possible now vs later

| now (no provider change) | later (needs new wire shape, a local tier, or evidence) |
|---|---|
| Per-form read and check from the existing stream sink; early cancel; pipelined eval (idea 1) | Abort-inject-continue via DeepSeek `prefix: true` (beta), gated by an A/B |
| Contract-carrying renders of predicted heads; head-triggered prefetch (idea 2) | GPT-5 Lark CFG for s-expressions if a GPT-5 row is ever added |
| `logprobs` on DeepSeek to measure uncertainty at heads and keys (diagnostic) | Local constrained plus speculative proposer (idea 3) |
| Re-run `measure.py` on product-agent `:seon.cluster.eval/source` rows | FIM for in-form completion (a separate beta endpoint) |
