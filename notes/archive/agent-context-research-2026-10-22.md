# Agent context: the field's best practice against Seon's context v2 (2026-10-22)

Research for root (read-only; nothing run). It serves the owner's goal for this week: real Seon agents fixing filed tasks, measured as turns to green, with paid DeepSeek runs inside $50. The code read is main at `0c7cac8` (context v2 landed in land5): `src/seon/context.clj`, `src/seon/turn.clj`, `src/seon/model.clj`, `docs/agent.md`, and `resources/seon/config/default.edn`. Evidence from our own runs comes from `notes/AUDIT-2026-10-22-agents.md` §4–5, `next/notes/AUDIT-v1-to-next-2026-10-22.md`, `notes/LEARNING-2026-10-22.md` and RESUME's step-E entries. The dry-run worktree has no `dry-run.md` note; its findings are in its commit messages (`5c40dad`, `79f961f`, `15beea1`, `a7cced9`).

## 1. What Seon sends today (context v2)

`seon.context/messages` builds one request per turn, and a turn is exactly one model call:

1. **system**: the help page, `docs/agent.md`, read from the db (`help-text`). This file was measured at 6,953 chars, about 2.2k tokens at 3.2 chars per token. It is the same for every agent and every turn.
2. **user**: the attempt message (`attempt-message`, made of `attempt-keys` `[:task :references :found :sections :evidence]`). It is built from the first turn's basis, so it does not change during an attempt. Its budget is `:seon.config.render/context-budget` 16,000.
3. **The scrollback.** Each earlier turn is an assistant message (its `eval` tool calls) followed by one tool message per call (the recorded evaluations, at most `evals-budget` 8,000 each turn). `kept-turns` keeps whole turns within `history-budget` 32,000 tokens: the newest turn always, then older turns back to the oldest that fits.
4. **user, last**: the turn message (`turn-message`). It holds the turn number, the proofs, `:refused`, `:test-run`, `:unmerged` and `:will-merge`, and its budget is again 16,000. It is rebuilt every turn and is not kept in the scrollback.

The request also sets `temperature 0`, `stream true`, one tool `eval`, `max_tokens` 65,536, and `thinking :disabled` (`seon.model/request-body`). The turn records prompt, completion, cache-hit and cache-miss tokens (`seon.turn/usage`).

**Context size.** I did not run a render: an outside read (`seon.turn/context-block`) records a turn in the store. The sizes below are estimates, except the help page, which was measured:

| Part | Tokens | Basis |
|---|---|---|
| help (system) | ~2.2k | measured: 6,953 chars ÷ 3.2 |
| attempt message | ~4–10k (cap 16k) | estimated. Plans: median 1,102 chars, p90 2,507 over 155 task plans (measured from `resources/seon/tasks`). On top of that come cited vars (up to 3k each at code depth), 5 `:found` vars at doc depth, and the cited sections |
| scrollback | 0 → 32k | estimated at ~1.5k per reply plus ~3k of evals per turn, so about 4.5k a turn; full after about 7 turns |
| turn message | ~1.5–10k | AUDIT-agents §4: the current-state block was 5,900–39,000 chars (p10–p90) |
| **one turn's request** | **~10k at turn 1 → ~45–55k after turn 7** | agrees with config's own comment, "a turn sends ~20-55k" |

## 2. What the field does

**DeepSeek API** (api-docs.deepseek.com, read 2026-10-22):
- **Prices.** `deepseek-flash` (DeepSeek-V4.1-Flash-1017, 1M-token context, up to 384K output) costs, per million tokens: input cache hit **$0.003**, input cache miss **$0.15**, output **$0.60**, all off-peak. Peak is twice that: 01:00–04:00 and 06:00–10:00 UTC on weekdays. `deepseek-v4-pro` costs $0.022 / $0.66 / $1.98. Config's numbers match these. [pricing](https://api-docs.deepseek.com/quick_start/pricing)
- **Prefix cache.** Caching is on by default and costs nothing extra. A request hits only if it *fully matches* a stored "cache prefix unit". Units are made at three kinds of point:
  - the end of each request's input and the end of its output;
  - common prefixes detected across requests, which is lazy (a third request hits what two earlier requests shared);
  - fixed intervals inside long inputs.

  The cache is best effort, takes seconds to build, and is cleared hours to days after its last use. Usage reports `prompt_cache_hit_tokens` and `prompt_cache_miss_tokens`. [kv_cache](https://api-docs.deepseek.com/guides/kv_cache)
- **Thinking mode.** It is on by default at effort `high`; `{"thinking":{"type":"disabled"}}` turns it off, and `reasoning_effort` takes low, high or max. **With `tools`, "the `reasoning_content` must be fully passed back to the API in all subsequent requests"; omitting it is a 400.** In thinking mode, temperature has no effect. [thinking_mode](https://api-docs.deepseek.com/guides/thinking_mode)
- **Tool calls.** Strict mode (beta, `/beta` base URL, `"strict": true`) validates arguments against the JSON schema. The chat API cannot insert tool calls mid-history. [tool_calls](https://api-docs.deepseek.com/guides/tool_calls)

**Harnesses and results:**
- **SWE-agent's agent-computer interface (ACI)** (Yang et al. 2024, [arXiv 2405.15793](https://arxiv.org/html/2405.15793v3)). Its four principles:
  - simple actions with concise documentation;
  - compact actions that make progress each step;
  - feedback that is informative but concise;
  - guardrails such as a linter on edit.

  Ablations on SWE-bench Lite against the default's 18.0% resolved:

  | Variant | Resolved | Change |
  |---|---|---|
  | edit without lint | 15.0 | −3.0 |
  | iterative search instead of summarized | 12.0 | −6.0 |
  | file viewer showing the whole file instead of 100 lines | 12.7 | −5.3 |
  | full history instead of collapsing all but the last 5 observations | 15.0 | −3.0 |
  | no demonstration | 16.3 | −1.7 |

  "Agents succeed quickly and fail slowly": a success takes a median of 12 steps, a failure a mean of 21.
- **CodeAct / OpenHands** (Wang et al. 2024, [arXiv 2402.01030](https://arxiv.org/html/2402.01030v1); [OpenHands CodeActAgent](https://docs.all-hands.dev/usage/agents)). Code as the single action space gives up to 20% higher success than JSON or text actions. It also takes fewer turns: 5.5 against 7.6–7.7 for GPT-4-1106, and fewer for 12 of 17 models. Seon's one `eval` tool already is CodeAct.
- **Aider's repo map** ([aider.chat/docs/repomap](https://aider.chat/docs/repomap.html)). The map lists files with their key symbols and definition lines. It is ranked as a graph by how often each identifier is referenced, with a default budget of 1k tokens (`--map-tokens`), and it grows when no files are in the chat.
- **Manus's context engineering** ([blog](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus)):
  - "the KV-cache hit rate is the single most important metric for a production-stage AI agent";
  - input to output runs about 100:1;
  - the context is append-only (one edited past token invalidates everything after it);
  - serialization is deterministic;
  - tools are masked rather than removed;
  - the file system serves as restorable memory;
  - "recitation", objectives rewritten at the end of the context;
  - errors are kept in the context;
  - uniform few-shot patterns are avoided, because they make the agent copy them.
- **Anthropic's context engineering and Claude Code** ([anthropic.com/engineering/effective-context-engineering-for-ai-agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents); [writing-tools-for-agents](https://www.anthropic.com/engineering/writing-tools-for-agents); summarized from memory and a [secondary summary](https://agentic-ai.readthedocs.io/en/latest/ContextEngineering/anthropic/), not re-fetched today):
  - treat the context as a finite attention budget: the smallest set of high-signal tokens;
  - retrieve just in time, by grep or glob, rather than preloading;
  - compact near the limit;
  - clear old tool results, the lightest compaction (context editing cut tokens 84% on a 100-turn evaluation);
  - keep notes outside the window;
  - truncate tool output with a message saying how to get the rest.
- **REPL-driven agents.** I found no controlled study. Practitioner tools (ClojureMCP; [dirge's nREPL plugin](https://docs.rs/crate/dirge-agent/0.21.5/source/plugins/nrepl/README.md); [Codex+ClojureMCP guide](https://codex.danielvaughan.com/2026/05/23/codex-cli-clojure-development-teams-clojuremcp-repl-driven-agent-workflows/)) name unbalanced delimiters as the main failure spiral and check structure before eval. Seon already repairs delimiters and shows the repair.
- **Our own measurements:**
  - helpers named in the context took v1 R4 from 7/32 green to 22/24, cut median turns from 3 to 1, and cut tokens 69% (LEARNING, "Worked");
  - a stable prefix gave v1 85–91% cache hits (AUDIT-v1-to-next);
  - loops took 45% of 27.8M tokens, and replies were byte-identical in 46 consecutive pairs (AUDIT-agents §4–5).

## 3. Where Seon stands against that

Already at best practice:
- one code action (CodeAct);
- a stable system message, then a stable attempt message (Manus, v1);
- cited vars at their citation's depth, capped, with the form that reads the rest (SWE-agent's 100-line window, Anthropic's truncation);
- `:found` ranked by tf-idf (aider's ranked map, R4);
- errors kept and shown as data;
- the current state last, which is recitation;
- cache-hit tokens recorded per turn.

Gaps, ranked by expected effect on turns to green:

### 1. Show a small ranked map of the task's namespaces, beside `:found`
- **Change:** add `:map` to `attempt-keys`. It holds the public vars of the namespaces the proofs and cited vars live in, at summary depth (arglists and first sentence), ranked by `found`'s tf-idf, within about 1k tokens, with a line naming `(clojure.repl/dir ns)` for the rest.
- **Why:** naming the helpers was our strongest measured lever (R4: 7/32 → 22/24, median turns 3 → 1). Aider's 1k-token ranked map is the field's version. The dry runs show agents hunting with `slurp` and `file-seq` for helpers the context never named (AUDIT-agents §5.1). `:found` reaches one hop from the citations and stops at 5.
- **Where:** `seon.context/found` and `attempt-keys`; a new config key `:seon.config.render/map-tokens`.
- **Measure:** turns to green, and turns before the first `defn` on the branch, across the same filed tasks with and without the map. Also the share of evals that are `apropos`/`grep`/`dir` (look-ups) in the first 3 turns.

### 2. End an attempt on no progress, as a fact of the turn
- **Change:** when a reply's eval calls repeat the previous turn's code byte for byte (or carry the same set of forms), and the proofs and `:will-merge` have not changed, end the attempt with outcome `:no-progress`. Its evidence is the repeated form, and refinement starts from that evidence (`seon.template/attach-tx` already carries evidence).
- **Why:** "agents succeed quickly and fail slowly" (SWE-agent: success median 12 steps, failure mean 21). Our runs had 46 identical consecutive pairs; one attempt ran 17 identical turns, and loops took 45% of tokens (AUDIT-agents §5.3). A fresh attempt that holds the evidence beats more turns on a stuck one. This ends the attempt and records why; it gates nothing.
- **Where:** the `cond` in `seon.turn/work!`, beside `stop` and `hairy`, using `:seon.turn/calls` of the last two turns.
- **Measure:** turns per failed attempt, and green per attempt after refinement, against the current baseline.

### 3. Bound a reply to what a reply is: `max-tokens` 65,536 → ~8,192
- **Change:** set `:seon.config.ai/max-tokens` to 8192 while thinking is disabled. A task that turns thinking on raises it through the resolver.
- **Why:** the largest ordinary reply was about 5k tokens. Loop replies ran for minutes: 168 of 181 model-minutes and 12.4M tokens (AUDIT-agents §5.2). `:your-last-reply :cut` already tells the model to answer with less. Each turn returns faster, so a fixed wall budget allows more turns.
- **Where:** `resources/seon/config/default.edn`, `:seon.config.ai/max-tokens`.
- **Measure:** p99 completion tokens, the `:seon.turn/truncated` rate (it should stay under ~2%), and model-ms per turn.

### 4. One worked demonstration in the help page
- **Change:** add one short, complete transcript to `docs/agent.md`: read a red proof, find the helper with `my.agent/callers`, redefine it in its namespace with a contract, watch the proof go green, then record the finding with `my.task/update!`. Make it a run-checked form, as the page's forms already are (`seon.repl-test/the-help-page-runs`).
- **Why:** SWE-agent without a demonstration lost 1.7 points. The page sits in the cached system message, so it costs about $0.003 per million tokens after the first hit. Manus warns against uniform few-shots, so keep one example of the method, not a template to copy.
- **Where:** `docs/agent.md`, in `## Done` or a new section.
- **Measure:** turns to the first green proof, and the rate of contract gaps (`:refused :contract-gap`) in the first attempt.

### 5. Collapse old evaluations in chunks, not a sliding window
- **Change:** when the scrollback passes the history budget, collapse the evals of all turns but the last N (SWE-agent's 5) to one line each: the form, its status, and `(nth (my.turn/evals) k)`-style handles. Do it once, at the boundary, so the collapsed prefix stays fixed for the next several turns. Today `kept-turns` drops the oldest turn every turn once full.
- **Why:** collapsing old observations beat full history by 3 points in SWE-agent, and Anthropic reports clearing tool results as the lightest compaction. A sliding window changes the message after the attempt message every turn, so from turn ~7 on every scrollback token misses the cache (Manus: append-only).
- **Where:** `seon.context/kept-turns` and `messages`; the history budget config.
- **Measure:** cache-hit share per turn from turn 8 on (`:seon.turn/cache-hit-tokens` ÷ prompt), and turns to green on long attempts.

### 6. Keep the turn message in the scrollback (append-only), for the cache
- **Change:** each turn's state message stays in the history, as `user` after that turn's tool messages, instead of being dropped and re-rendered. The newest one is the current state.
- **Why:** DeepSeek stores cache units at the end of each request's input and output. Today request k+1 drops request k's last message (the state), so the newest scrollback turn sits on no unit boundary. It hits only through the lazy common-prefix units, about one turn behind. Append-only makes request k+1 start with request k's whole input plus its output, which is a unit. This saves roughly a turn's worth (~5–8k tokens) of miss per turn. Older states could be shown as a diff against the one before to keep them small.
- **Where:** `seon.context/messages` and `turn-messages`. It needs the basis of each earlier turn (`turn-text` exists).
- **Measure:** hit share per turn before and after on the same scripted dry run, from `:seon.turn/cache-hit-tokens`. Expect >85%, v1's figure.

### 7. Pass `reasoning_content` back, before thinking is ever on
- **Change:** `turn-messages` and `wire-message` send `:seon.turn/reasoning` back as `reasoning_content` on each assistant message when thinking is enabled.
- **Why:** DeepSeek returns a 400 when tools are declared and reasoning is not passed back. Today thinking is `:disabled` in config, but the resolver lets a task or model turn it on. That turn's second model call would fail as `:seon.model/provider-error`, costing an attempt.
- **Where:** `seon.context/turn-messages` (it already reads the turn record; add `:seon.turn/reasoning` to `turn-record`'s pull) and `seon.model/wire-message`.
- **Measure:** a proof that `request-body` for a thinking-enabled provider carries `reasoning_content` on every prior assistant message.

### 8. Prove the prefix is byte-stable
- **Change:** a deftest that renders turn k's and turn k+1's messages from recorded bases and asserts that messages 0–1 (help and attempt) and the kept scrollback are byte-identical. The same test should render from two processes, since printer map order must not depend on the process.
- **Why:** Manus names unstable serialization as a silent cache-breaker. Context v2 rebuilds the attempt message from the first basis on every turn, which is correct only if the printer is deterministic.
- **Where:** `test/seon/context_test.clj`.
- **Measure:** the test itself, red → green.

### 9. Lower the per-attempt turn cap toward the measured success curve, and use refinement
- **Change:** set the per-step `:seon.config.agent/max-turns` to about 2× the p90 of turns to green, measured on the first 20 real tasks (v1 R4's p90 was 6). Leave 40 until that number exists. This is the owner's call: he objected to 8.
- **Why:** SWE-agent's success median was 12 steps, while failures run long. Turns past p90 rarely turn green, and a refinement starts with evidence and a clean context.
- **Where:** `resources/seon/config/default.edn`, or per task template.
- **Measure:** greens per dollar and turns to green across all attempts of a task.

### 10. Report turns to green and the cache-hit share as the run's first numbers
- **Change:** `seon.learn` gains a report: median and p90 turns to green per step template and per model, tokens per green, cache-hit share, and the top waste. It shows on the dashboard and in root's context (v1's `mine.clj:544-729`).
- **Why:** every change above is judged by these numbers; Manus calls the hit rate the first metric. The facts are recorded already (`:seon.attempt/turns`, `:seon.turn/cache-hit-tokens`), but nothing reports them.
- **Where:** `seon.learn`.
- **Measure:** the report exists and matches a hand count on the dry run.

## 4. What a dry-run task costs on DeepSeek

The context sizes are the estimates in §1, not a render. Model `deepseek-flash`, thinking off. A task is design → implement → merge.

| Case | Turns | Input tokens | Output tokens | Cost off-peak | Cost peak |
|---|---|---|---|---|---|
| Typical: ~10 turns per step, ~31k average input, ~1.5k output, 80% cache hit | 30 | 0.93M (0.74M hit, 0.19M miss) | 45k | **~$0.06** | ~$0.12 |
| Typical with no cache hits | 30 | 0.93M | 45k | ~$0.17 | ~$0.34 |
| Worst: 40 turns per step × 3, 57k input, no hits, 3k output | 120 | 6.8M | 0.36M | ~$1.25 | ~$2.50 |
| Runaway: as worst, every reply hits `max_tokens` 65,536 | 120 | 6.8M | 7.9M | ~$5.8 | ~$11.5 |

For comparison, v1's worst run (222536, 13.35M tokens, mostly input) would cost about $2 at today's miss price.

**Against the $50 budget:**
- ~800 typical tasks with caching, ~300 without, or ~40 worst-case tasks;
- runaway replies are the only way to spend it fast, which is another reason for change 3.
- `deepseek-v4-pro` costs about 4× more per task: a typical task is ~$0.23 with the cache and ~$0.70 without.
- Run off-peak (outside 01–04 and 06–10 UTC on weekdays) to halve all of the above.

**Unmeasured:** the real attempt and turn message sizes for a filed task, and the real hit share. One scripted dry-run render (`seon.context/render` on the dry-run root's own store) gives the first; the first paid task's `:seon.turn/cache-hit-tokens` gives the second.
