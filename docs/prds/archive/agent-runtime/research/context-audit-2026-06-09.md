---
type: research
status: active
tags: [research, agent]
---

# Context Audit — 2026-06-09

Audit of the bytes actually delivered to the agent LLM (system prompt + assembled context), against the Friday demo requirement: agents answer arbitrary questions about the seon codebase via read-only `seon.fs` and persist well-schema'd findings the next agent reuses.

## TL;DR

- **The demo's core action is impossible today: the repo is unreadable.** `seon.fs` has `allowed-roots nil` (default-deny, verified live) AND zero mention anywhere in the context — not in `<functions>` (only 7 substrate fns are indexed, none of them fs), not in capabilities, not in the system prompt. The agent cannot learn that file reading exists, and if it guessed, every call is denied.
- Turn-0 context is lean and mostly good: **10,212 chars (~2,553 tok)** + system prompt **8,126 chars (~2,032 tok)** ≈ 4.6k tokens. At run scale it explodes: a 58-turn agent's live context is **100,920 chars (~25.2k tok)**, 90% of it transcript.
- **Prompt caching is dead on arrival**: the most-static→most-dynamic ordering is defeated at **char 35** by the `Now: <ISO timestamp>` line inside `<system>` — every turn busts the entire user-message cache from the top.
- Capabilities arglists are garbled — `(seon.db/pull ())`, `(seon.schema/register! ([k v]))` — and `pull`/`entity` have no usable call shape anywhere except one incidental pull example.
- Findings/provenance modeling has **no guidance at all**; the discovery mechanism for agent #2 (domain-attrs catalog block) exists and is good, but agent #1 must invent the finding shape from scratch.
- Failure-mode coverage from runs 3–6 is mostly in place (`:with ?e`, loose `?vars`, parallel-attr, answer-before-verify, blank-message code guard) — except **`:number`/`:double` type confusion, which is taught nowhere**.

## Snapshot caveats

- A 1.5-messaging agent was concurrently editing pod files. The from/to message model (role/agent retired) **is fully landed** in this snapshot — `seon.agent/message!`/`reply!`, the system prompt, and the transcript labels are all consistent with it.
- The live pod's conn does **not** contain the Mmp/DbV/dwr runs (pod restarted on the `:memory` store; only today's messaging-test agents CMr/RYH/qhi/lNh are present). Run-6 S2 prompt-text bytes were unavailable; I used (a) a pristine turn-0 capture from a fresh agent entity (no LLM call, zero cost), (b) the live re-rendered context of CMr (58 turns) as the run-scale sample, and (c) a turn-1→turn-2 persisted prompt-text diff from qhi.
- **Persisted `:seon.turn/prompt-text` is store-capped at 16,406 chars** (`seon.eval/cap-edn`). All of CMr/RYH's late turns persist as exactly 16,406 chars while the LLM actually received ~101KB. Historical prompt bytes for long runs are therefore truncated evidence — an observability gap worth knowing during demo triage.

## 1. The real bytes

### System message

`seon.ai.deepseek/default-system-prompt`: **8,126 chars ≈ 2,032 tokens** (live count; source at `src/seon/ai/deepseek.cljs:68`). Sent as the separate `system` role message every call — stable across turns, so provider-side caching of it survives (the bust below applies to the user message).

### Per-section sizes (chars, ~tokens at /4)

Composer: `seon.agent/assemble-context`, layout `substrate-default-ctx` (8 sections, `src/seon/agent.cljs:2325`).

| Section            | Turn-0 fresh (HjP) | ~tok | Run-scale (CMr, 58 turns) | ~tok |
|--------------------|-------------------:|-----:|--------------------------:|-----:|
| :system            | 1,371              | 343  | 1,384                     | 346  |
| :capabilities      | 3,911              | 978  | 3,911                     | 978  |
| :schema-catalog    | 2,313              | 578  | 2,313                     | 578  |
| :functions-catalog | 1,029              | 257  | 1,029                     | 257  |
| :namespace-context | 85                 | 21   | 91                        | 23   |
| :warnings          | 1,436              | 359  | 1,436                     | 359  |
| :transcript        | 0 (suppressed)     | 0    | **90,468**                | **22,617** |
| :prompt            | 31                 | 8    | 288                       | 72   |
| **Context total**  | **10,212**         | **2,553** | **100,920**          | **25,230** |
| + system prompt    | 8,126              | 2,032 | 8,126                    | 2,032 |
| **Per-turn total** | **18,338**         | **~4.6k** | **109,046**          | **~27.3k** |

The runaway section is unambiguous: **transcript**. Default n=50 messages + 50 evals, each eval row up to ~1.6KB (`eval-render-cap` 1500 + source + footer). A repo-reading demo (file contents landing in `result-edn` previews) will hit 20k+ context tokens within ~15 turns.

## 2. Per-section content audit

### :system (system-section) — verdict: good, two defects

Teaches: format contract (forms + `;;` comments, with a correct/wrong side-by-side), self-walk API (`messages`/`evals`/`current-ns`/`result`), one pull example for own-ns code. Correct vs the current API.

- Defect: the header embeds `Now: <ISO>` — busts the cache every turn (see §4). The timestamp belongs in `:prompt` at the end.
- Defect: after the first eval, `current-ns` returns a keyword and the header renders `ns=":seon.agent.qhi-…"` with a stray leading colon (turn-1 renders the symbol without it). Cosmetic but inconsistent.

### :capabilities — verdict: best section in the file, but signatures are broken and fs is absent

Teaches: register!-before-transact (with the exact rejection consequence), deep namespaced attrs, identity/ref/vector-ref shapes, a full register→transact→query worked example, **the `:with ?e` aggregation gotcha (verified present in live bytes, well-positioned right at the aggregate example)**, the empty-`#{}`-means-misspelled-attr recovery, reply!/message!, the HTML tile.

- **Broken signatures**: arglists render double-wrapped or empty — `(seon.schema/register! ([k v]))`, `(seon.db/transact! ([& call-args]))`, `(seon.db/pull ())`, `(seon.db/entity ())`. Cause: `capabilities-section` concatenates the raw stored arglists string (`"(" sym " " arglists ")"`, `agent.cljs:1446`) instead of using `fn-catalog-sig`'s unwrap; and `arglists-from-source` returns `"()"` for `pull`/`entity` (multi-arity extraction failure in `client.cljs/arglists-from-source` against those defns).
- **Dangling docs**: one-line docs truncate at exactly the wrong place — "Two call shapes:" with no shapes following, three times. `pull` and `entity` have no callable shape anywhere in the context (the system section's one pull example is the only lifeline; `entity` has none).
- **Missing entirely**: any mention of `seon.fs` (the ns IS loaded in the pod precisely so agents can call it — `client.cljs:113-115` — but nothing surfaces it), any numeric-type guidance (`:int` vs `:double`; there is no `:number` — the run-5 confusion is untaught), any finding/provenance shape.

### :schema-catalog — verdict: good mechanism, incomplete kind coverage

Teaches: every renderable entity kind with attrs, compact types, id-attr flag, optionality, live instance counts; a generic query-by-id-attr example. The trailing **domain-attrs block** ("REUSE these exact keywords", per-attr type + live entity count) is the right reuse surface — it rendered empty in this fresh DB (no domain attrs yet) but the code path is live and is exactly what agent #2 needs.

- **Omitted kinds**: only kinds carrying `:seon.schema/render-fn` list. `:seon.agent`, `:seon.turn`, `:seon.session`, `:seon.ctx`, `:seon.user` are invisible — the agent cannot learn that `:seon.user/id` exists (needed to query "messages from the human") or that turns carry `prompt-text`/`woken-by`. Deliberate for the ai-window, wrong for the catalog.
- Counts are live (`236 instances` of `:seon.eval` etc.) — semi-static claim is false in practice: every eval/message tx changes a count. Moot today because the cache is already busted at char 35, but it becomes the next bust point after the timestamp fix.

### :functions-catalog — verdict: mechanism right, corpus far too thin

Teaches: signature + one-line doc per fn across the whole substrate, own-ns in full source — the correct reuse surface. But the seeded corpus is **7 fns** (`db/transact! query pull entity current-agent-id`, `schema/register!`, `test.runner/run!` — `client.cljs/substrate-vars`). Missing: all of `seon.fs` (read-file/walk-dir/list-dir/stat/file-exists?), `seon.agent/reply!`/`message!`/`messages`/`evals` (taught only as prose examples elsewhere — acceptable), `seon.render/render-namespace`. Same dangling-doc problem ("Two call shapes:"). Prior agents' fns will appear automatically via detect-and-tee — that part is sound.

### :namespace-context — verdict: fine at scale, confusing at turn 0

For a fresh agent renders `;; requires: seon.agent.<id> (not in db)` — mislabels the agent's own empty home-ns as an unresolved require. One-line fix in `render-one-ns-ai`.

### :warnings — verdict: right mechanism, wrong audience at turn 0

The clustered explain-once + affected-list format is good, and `check-hop-exhausted`/`check-parallel-attr`/`check-failing-tests` are exactly the right reactive teachers. But runtime checks are global by design, so a **fresh agent's turn 0 carried 1,436 chars of OTHER agents' debris** — 22 slow-eval ids and 4 hop-exhausted messages from finished test runs, each ending "Please correct before moving on." A fresh agent is told to correct evals it never ran. At minimum the imperative phrasing should be conditional on ownership.

### :transcript — verdict: correct content, unbounded budget

Interleaved messages+evals is the right shape; per-eval `cap-result-body` with the narrow-your-query guide is good teaching. But there is **no total-section budget**: 50+50 items × up to ~1.6KB = the 90KB observed. Needs a chars budget (drop/shrink oldest first, e.g. strip eval source bodies beyond the last N turns).

### :prompt — verdict: good

Turn-pressure escalation (5-turn nudge → halfway → FINAL WARNING 3 before cap) is well-positioned where the model reads last.

## 3. Demo desk-check: "how does seon validate schemas at transact time?"

Fresh agent, only the captured context:

- **(a) Know it can read the repo — FAIL, doubly.** Nothing names `seon.fs` or any file capability. Even on a lucky guess, `allowed-roots` is nil → every op returns the denied envelope. (Silver lining: the denial message itself teaches `(seon.fs/configure! …)`, and the soft boundary lets the agent self-grant — but it has no way to know the repo path, and self-granting is not the demo story.)
- **(b) Find relevant source efficiently — FAIL.** No content search exists anywhere in the substrate (`walk-dir` + `read-file` only). Locating "validation at transact time" (`seon.db/validate-entity-values!` in `src/seon/db.cljs`) among hundreds of files requires either a grep the agent writes itself (walk-dir → read-file → `str/includes?` — feasible but untaught and turn-expensive) or prior knowledge. No repo map, no `docs/` pointer, no naming-convention hint ("seon.db owns transact").
- **(c) Model + persist a finding with provenance — PARTIAL.** The `kb.doc` worked example (path/title/tags + identity upsert) is a usable template, and the system prompt's "durable work goes in a shared domain namespace" + "design the schema around the question" point the right direction. But nothing names the provenance fields that make a finding reusable (source path, line, claim text, confidence, found-at), so each agent will invent a different shape — exactly the parallel-attr forking failure, one level up.
- **(d) Agent #2 discovers the finding — PASS (mechanism), PARTIAL (affordance).** The domain-attrs catalog block lists agent-registered attrs with types + live counts and states the reuse contract; the schema-catalog query example shows how to enumerate instances. Missing: a sample row or a "query this kind" one-liner per domain group, so discovery costs one extra exploratory turn.

## 4. Cache + budget economics

- **First divergence between consecutive real turn prompts: char 35** (qhi turn-1 vs turn-2, persisted bytes). The diverging text is `Now: 2026-06-09T21:44:56.860Z` inside `<system>`. The entire ~10KB+ of carefully-ordered static sections downstream is re-tokenized uncached every turn.
- After fixing the timestamp, the next bust points in order: `<system>` ns attr (changes on first eval, then stable), schema-catalog **instance counts** (every data tx — i.e. every turn, since evals/messages are counted kinds), functions-catalog (only on fn (re)define), namespace-context (ns edits), warnings/transcript/prompt (every turn, by design). To get real cache value, counts either move to the dynamic tail, round to buckets, or exclude the high-churn substrate kinds (eval/message) from counting.
- Budget at demo scale: turn-0 ~4.6k tokens total; observed 58-turn agent ~27.3k tokens per turn, 83% transcript. With DeepSeek pricing this is tolerable for one demo run but compounds across the agentic loop (cap 20 turns/message → worst case ~0.5M input tokens per user question at run scale). Transcript budget is the lever; catalogs grow only with the corpus (fine).

## 5. Failure-mode coverage (runs 3–6)

| Failure | Taught now? | Where | Positioned when relevant? |
|---|---|---|---|
| `?at` loose outside quoted query | yes | system prompt ("Two more reader details") | adequate — generic, not at the query examples; acceptable |
| `:number`/`:double` confusion | **no** | nowhere | **gap** — register! shapes list only string/keyword/vector/ref |
| parallel-attr forking | yes | system prompt (reuse-before-register) + domain-attrs block + `check-parallel-attr` warning | good — three layers, reactive layer fires at the moment of the crime |
| answer-before-verify | yes | system prompt ("never report a number you did not just read back") + capabilities aggregates | good |
| empty assistant messages | yes — **code guard** | `message!` rejects blank; `ask-and-eval!` skips blank turn-log writes | best possible (enforced, not taught) |
| `(sum)` without `:with` | yes | capabilities worked example with inline `;; :with ?e is REQUIRED` | excellent — verified present in live turn-0 bytes |
| hop ping-pong | yes | wake-time refusal + `check-hop-exhausted` warning | good |

## 6. Prioritized fix list

### (a) Must-fix for Friday

1. **Grant fs access at boot.** In `start-agent!` (or `-main`): `(seon.fs/configure! {:seon.fs/allowed-roots [(.cwd js/process)] :seon.fs/read-only? true})`. Zero context cost. Without this nothing else matters.
2. **Surface `seon.fs` in context.** Add `#'fs/read-file #'fs/walk-dir #'fs/list-dir #'fs/stat #'fs/file-exists?` to `substrate-vars` (appears in `<functions>` automatically) AND add a "Reading the repo" block to `capabilities-section`: the allowed root(s) stated explicitly, one `walk-dir` + one `read-file` worked example, and a 3-line search recipe (walk-dir `.cljs`/`.md` → read-file → `str/includes?`) since no grep exists. Cost ≈ +800 chars (~200 tok).
3. **Add a findings-with-provenance worked example** to capabilities (sibling of the kb.doc block): a canonical shape — identity attr, claim `:string`, source-path `:string`, source-line `:int`, confidence `:double`, found-at `:inst` — registered in a shared domain ns, plus one line: "later agents find these via the domain-data-attrs catalog." Locks all demo agents onto one reusable shape. Cost ≈ +500 chars.
4. **Fix capabilities signatures**: use `fn-catalog-sig` in `capabilities-section`; fix `arglists-from-source` multi-arity extraction for `pull`/`entity`; add one `pull` and one `entity` worked example (the only core fns with no callable shape today). Cost ≈ +300 chars net.
5. **Transcript total budget**: cap the section at ~30–40KB chars (shrink oldest evals to `> source` + one-line result first, then drop). Protects both token spend and the LLM's attention during the long demo runs.

### (b) High-value (Wed/Thu if time)

6. **Move `Now:` out of `<system>`** into `:prompt` (and freeze or drop the ns attr in the header) — pushes first divergence past system+capabilities (~5.3KB cacheable); then decide on catalog counts (bucket or exclude eval/message kinds) to extend the static prefix further.
7. **Turn-0 warnings hygiene**: scope runtime checks' "Please correct" phrasing to evals/messages the agent owns; other agents' problems render as FYI, not as orders.
8. **One line of numeric-type teaching** in the register! shapes list: "counts/ids `:int`, measures `:double` — `:number` does not exist."
9. **Catalog the invisible kinds** (`:seon.agent`, `:seon.turn`, `:seon.session`, `:seon.user`) — even a compact appendix block; the agent currently can't learn `:seon.user/id` exists.

### (c) Later

10. `ns=":…"` leading-colon inconsistency in system-section/prompt-section after first eval.
11. `;; requires: <own-ns> (not in db)` mislabel for a fresh home-ns.
12. Domain-attrs block: one sample row (or pull one-liner) per domain group.
13. A `docs/` discovery pointer (the repo's own documentation tree is the best corpus for the demo questions and nothing mentions it).
14. Persisted-prompt observability: `:seon.turn/prompt-text` silently caps at 16,406 chars while the LLM gets ~101KB — store the true char count alongside the capped text.

## Appendix — measurement provenance

- Pristine turn-0: fresh agent entity `HjP-2606091748` transacted live (no LLM call), `assemble-context` rendered in the agent's `with-agent` scope. Full text captured; quoted excerpts above are verbatim live bytes.
- Run-scale: live `assemble-context` per-section render for `CMr-2606091740` (58 turns, today's 1.5-messaging test run).
- Cache diff: persisted `:seon.turn/prompt-text` of qhi turn-1 (10,737 chars) vs turn-2 (14,179 chars), first divergence index 35.
- fs state: `@seon.fs/!config` ⇒ `{:seon.fs/allowed-roots nil, :seon.fs/read-only? false}`; `SEON_FS_ROOT`/`SEON_FS_READ_ONLY` unset in the pod env.
- Corpus: `:seon.fn` count 7 (exact syms listed in §2); `(seon.warn/domain-attrs …)` ⇒ `[]`.
- Incidental finding while measuring: `:seon.agent/id` is schema-constrained to exactly 14 chars (`[:string {:min 14 :max 14}]` via `:seon.db/id`) — a hand-minted short id is rejected by transact!. Fine for the substrate (ids come from `db/new-id!`), but worth knowing for test tooling.
