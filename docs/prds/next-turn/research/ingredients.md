---
type: research
status: done
tags: [next-turn, ingredients]
---

# Ingredients: what Seon records and derives that could predict an agent's next turn

Lane: ingredients (2026-09-26). This is a neutral inventory, with evidence over opinion.
Volumes come from the live `default` cluster (pid 43753, booted 2026-09-26T16:47Z, max-tx
536870960), measured with three read-only JVM-mode `eval_clj` queries, plus a streaming
Python pass over the on-disk corpora. Anything marked **(est.)** is an estimate.

> **Correction (orchestrator, 2026-09-26, after this census):** the store was deliberately
> nuked at 16:47Z, so the near-zero agent-work counts reflect that nuke, not normal
> retention. Default was restarted on HEAD 20b156860, which includes the `[:set :int]`
> contract fix (e9b2066ac), so point 8's first cause no longer applies. The unbuildable
> refusal for `[:fn clojure.core/ifn?]` is task 407264e2b69d, held for an Astra
> root-cause audit. The orchestrator is logging the missing call evidence on
> evaluations as a task.

## 0. The headline numbers

| source | what it holds | measured volume |
|---|---|---|
| Datahike `default` (current value) | program rows | 5,309 `:seon.fn/sym`, 2,267 `:seon.test/sym`, 3,502 `:seon.schema/key`, 464 `:seon.ns/name`, 98,986 `:seon.fn/calls` datoms, 100,498 `:seon.fn/call-arities`, 43,239 `:seon.fn/keywords`, 1,108 `:seon.fn/writes`, 2,292 `:seon.fn/spec` (Malli contracts), 463 issues |
| Datahike `default`, agent work | turns, evaluations and similar | 4 agents, 3 turns, 14 evaluations (14 in `history` too), 21 read-evidence components, 1 message, 3 tasks, **0** provider attempts, **0** `:seon.context.capture`, **0** `:seon.error/signature`, **0** test failures, **0** `:seon.test/reach` |
| Claude Code transcripts | our own agents on this repo | 42 top-level + 1,169 subagent files, 758k lines, 4.3 GB. 143,757 tool_use blocks. 84,442 thinking blocks, of which only 22,332 carry text |
| `mcp__seon__eval_clj` inside those transcripts | Clojure forms sent to the live JVM | 13,756 calls, 7.5 M chars of code, 29 M chars of results. **2,126 `is_error`, i.e. 15.5%**. 1,796 results carry `"exception":true`. 1,508 distinct fully-qualified functions called |
| Codex sessions | GPT lanes on this repo | 2,988 files (2,820 with cwd `/Users/sean/src/seon`), 1.74 M lines, 7.5 GB. 303,349 tool calls (`exec` 229,826, `apply_patch` 4,733). 282,677 reasoning items, **all encrypted**; only 13,181 carry a summary |

The Datahike agent-work numbers are small because the cluster is reset freely. "DB data is
disposable" is standing owner policy. As a result **the database holds almost no trajectory
history today**: the behavioural corpus is on disk, in the transcripts.

## 1. Ingredient table

Access costs for DB ingredients were measured warm on the live JVM, unless marked (est.).

| # | ingredient | where (file:line) | volume now | access cost at turn time | predictive use |
|---|---|---|---|---|---|
| 1 | Evaluation receipt: source, comment, output, error, ns, ending-ns, author, ordinal | `resources/seon/schemas/seon.cluster.eval.edn:1-116` | 14 | Index lookup per agent run, sub-ms (est.). Source/output/comment are `:seon.db/no-history? true` (`:7,17,25`), so **old values vanish from history** | The last N forms and their outcomes are the strongest next-action prior: retry after an error, the next step of a plan |
| 2 | Shown text (exact rendered result the agent saw) + renderer identity | `seon.eval.edn:2-3,39-42`; `seon.cluster.eval.edn:63` | 14 | Same as #1 | What the agent actually read, so what it will reference next (symbols, keys, ids in the output) |
| 3 | Per-eval cost/outcome: `duration-ms`, `fn-entries`, `allocated-bytes`, `host-interop-count`, `outcome` `:ok/:time/:error` | `seon.eval.edn:4,35-38` | 14 | Free with #1 | Predicts timeouts and heavy forms; `fn-entries` counts armed calls inside a form |
| 4 | **Read evidence**: per evaluation, the Datahike dependency plan (which attributes each query read) + attribute revisions | `seon.cluster.eval.edn:4-5,195-211`; producer `src/seon/db.clj:918` (`read-evidence`), `db.clj:866-914` (revision keyed on `:datahike.cache/attribute-revisions`); `db.clj:1167` `read-evidence-current?` | 21 | Stored as components. Currency check compares attribute revisions, O(attributes) | The exact **data the agent depends on**. Changed reads are already re-shown (`src/seon/turn.clj:2157` `system-turn`). A predictor could pre-fetch the entities behind these attributes |
| 5 | Turns: text, reply (blob), forms, starting-ns, trigger, opened/closed tx, turns-remaining, changes (datoms) | `seon.turn.edn:133-200` | 3 | Sub-ms per turn (est.) | Turn-level sequence. `:seon.turn/changes` holds the datoms changed since the last turn, which is exactly the "what's new" signal |
| 6 | Provider attempts: reasoning text/blob, finish-reason, usage-edn, settings-edn | `seon.ai.attempt.edn:1-14` | **0** (no paid runs on default, per owner policy) | Blob fetch per attempt | Reasoning states intent before the forms. It is the best text predictor of the next forms, but empty here |
| 7 | Context capture: the exact prompt + contributions + char count | `seon.context.capture.edn:1-37`; `seon.context.contribution.edn` | **0** | Blob-sized string | (prompt → forms) pairs are the supervised training signal for "what context produced this action" |
| 8 | Messages: content, from/to, about, caused-by, assignment | `seon.message.edn:19-134` | 1 | Sub-ms | The task statement: a wake's message usually names the target functions |
| 9 | Tasks: intent, must-read, evidence, declaration links, files | `seon.task.edn:3-23` | 3 | Sub-ms | `must-read` is a hand-declared context prior; task links name subjects |
| 10 | Issues: functions, tests, errors, namespaces, files, commits, detector | `seon.issue.edn:2-39` | 463 | Index join, ms (est.) | Issue → function sets give a prior over what gets edited for a class of problem |
| 11 | Errors: signature (sha, identity), chain (class, message, ex-data, first-party frames), occurrences (count, first/last, agent, turn), resolved-by digest-then/now, regressions | `seon.error.edn:278-300`; `seon.error.occurrence.edn:3-21` | **0** now | Index by agent (`:seon.error/agent` indexed, `:270`) | Recurring signatures predict the next error. Frames name the function that will be edited. `resolved-by` digests link an error to the fix |
| 12 | Malli explain / refusal payloads on contract violations | `src/seon/instrument.clj:297,571,655` | Transient, with occurrences | Computed once per violation | The offending value + expected schema is precisely the next edit's argument shape |
| 13 | Program: function rows (source, form-span, arglists, doc, ns, private?, macro?, host-bound?, defined-by) | `seon.fn.edn:14-35,202-209` | 5,309 | Pull by identity, sub-ms | The candidate vocabulary of calls |
| 14 | **Call graph** `:seon.fn/calls` (indexed, AVET) + `call-arities` (callee, argc) | `seon.fn.edn:49,76-90` | 98,986 edges / 100,498 arity tuples | Forward: `stored-call-reach` `src/seon/db.clj:1457` = 2 index reads per node. **Measured** 4 nodes in 0.08 ms; 820 nodes (`evaluate-source!`) in 6.5 ms. Reverse direct: AVET `[:seon.fn/calls sym]`, 784 callers of `seon.db/q` in 1.3 ms. Transitive reverse `seon.fn/reverse-closure` `src/seon/fn.clj:1902` is one query per level | Next call is very likely a neighbour of the last touched function. Arities give argument counts |
| 15 | Semantic edges: `writes` (attributes written), `keywords` (qualified keywords in source), `invokes`, `reference-to`, `must-read`, `see-also`, `references`, `destroys` | `seon.fn.edn:17-18,50,68-72,121-124` | writes 1,108, keywords 43,239 | AVET index, sub-ms | Keyword → function inverted index: from a key in shown output to the functions that read or write it. This is the data-to-code bridge |
| 16 | Contracts: `:seon.fn/spec` (Malli string) + compiled projection; contract findings | `seon.fn.edn:208`; `seon.fn.contract.edn:1-27`; compiled cache `src/seon/schema.clj:354-378,1311-1336,2312-2354` (core.cache basic + LRU threshold 8); `schema.clj:3114` projection memoized per committed db | 2,292 specs; 3,502 schema keys | Cached projection is warm. **But the profile shows `seon.schema/call-with-projection-state` inclusive total 4,247 s, max 560 s, x182** since boot | Given a predicted call, its input schema is the data the agent must pass. Malli can also generate examples |
| 17 | Test reach: `:seon.test/subject` (indexed), `reach`, `reach-digests`, results (pass/fail/error counts, failures with expected/actual) | `seon.test.edn:2-60`; `seon.test.failure.edn:3-37`; gate set `src/seon/fn.clj:1868` (`call-reach`), `:2001` `gate-set` | 2,267 tests, **0** stored reach, 2 runs | Derived by bound queries per call (the docstring cites no whole-program index) | A predicted edit → the tests it will be run against, which are prefetchable |
| 18 | Namespace rows: requires, aliases, refers, imports | `seon.ns.edn:2-39` | 464 | Sub-ms | Aliases decide the spelling of the next form (`d/q` vs `datahike.api/q`) |
| 19 | Agent rows: branch, mode, namespace, situation, unread-message-count, open-run | `seon.agent.edn:1-192` | 4 | Sub-ms | Agent kind and namespace strongly condition the vocabulary |
| 20 | Config (`seon.config.*`, 30 schema files) | `resources/seon/schemas/seon.config*.edn` | n/a | Cached effective config | Minor: render profiles and limits |
| 21 | **Live call profile**: per armed callable and per SCI context, calls/total/max/throws (LongAdders) | `src/seon/profile.clj:1-24,55-70,132-150` | Every armed function since boot; **not persisted** | In-process read, microseconds per cell (est.) | Per-context call frequencies and throw counts give a live unigram prior over functions and flag throwing ones. Context cells separate agents |
| 22 | clj-kondo analysis cache (the indexer's source of calls, keywords and arities) | `src/seon/fn/analyzer.clj:4,15-16,315` | `.clj-kondo/.cache` 108 KB | Files | Already folded into #14-#15 |
| 23 | Claude Code transcripts | `~/.claude/projects/-Users-sean-src-seon/**/*.jsonl` | See §0 | Stream-parse with a byte prefilter: **3 s** for all 4.3 GB (measured) | (state → tool call → result) triples. eval_clj code gives real Seon function-call sequences; `is_error` labels failures |
| 24 | Codex sessions | `~/.codex/sessions/**/*.jsonl` | See §0 | 5 s for all 7.5 GB, prefiltered (measured) | Mostly shell (`exec`) and `apply_patch`. Reasoning is encrypted, so it gives action sequences only |

### Format of a step (on disk)

- **Claude Code.** One JSON object per line. Keys include `type`, `uuid`, `parentUuid`,
  `isSidechain`, `message`, `timestamp`, `sessionId`, `gitBranch` and `cwd`.
  `message.content` is a list of blocks:
  - `text`
  - `thinking`: only 26% carry text, the rest are empty or redacted
  - `tool_use {id,name,input}`
  - `tool_result {tool_use_id, content, is_error}`

  The chain is linked by `parentUuid`. Subagent runs sit under `<session>/subagents/`.
- **Codex.** `{timestamp, ordinal, type, payload}`, where `type` is one of:
  - `session_meta`: cwd, base_instructions
  - `turn_context`: model. Counts: gpt-5.6-sol 7,529, gpt-6-astra 1,977
  - `response_item`: `message`, `reasoning` (encrypted, with an optional summary),
    `custom_tool_call`/`function_call` with name and input, and `*_output`
  - `event_msg`: token counts

### Access to the DB ingredients

All reads go through `seon.db` (`db/q`, `db/datoms`, `db/pull`). Program reads go through
`my.program/callers` / `tests-reaching` (`src/my/program.clj:152,167`). What is memoized:

- the compiled schema projection, per committed db value (`schema.clj:3114`)
- Datahike's own query-cache keyed on commit id (`db.clj:851-857`, citing
  `reference-code/datahike/src/datahike/query.cljc:2658-2671`)
- a parsed-query memo (`db.clj:620`)

The call graph, reverse closure and gate set are **not** memoized. They are cheap per call
(ms), except that the reverse closure computes `declared-callers` over all declared edges on
every call (`fn.clj:1853-1866`).

## 2. Surprising ingredients

1. **Read evidence is a dependency plan per evaluation**, stored as facts. It is not a
   result copy. Datahike's fork exposes per-attribute revisions
   (`:datahike.cache/attribute-revisions`, `db.clj:913`). So "which data this agent
   watches" is already a machine-readable set of attributes, and "did it change" is an
   O(attributes) comparison. This is a ready-made working-set signal.
2. **The keyword inverted index** (`:seon.fn/keywords`, 43k, and `:seon.fn/writes`) maps any
   namespaced key in a shown value to the code that produces or consumes it. In Seon the
   output text is full of namespaced keys, so every shown result points at code.
3. **`call-arities` gives the argument counts of real call sites** (100k tuples). With the
   Malli input schema, a predicted call already has a shape template, before any model runs.
4. **Every attribute of agent work is marked `:seon.wake/context-inert true`**, e.g.
   `seon.cluster.eval.edn:1-26` and `seon.ai.attempt.edn`. The schema already partitions
   "facts about turn-taking" from "facts about the world". A predictor can use the same
   declaration to know which reads are world-state.
5. **Live per-context call profile** (`seon.profile`): every armed function keeps a call
   count and a throw count per SCI context. It is a zero-cost online frequency model per
   agent, but it is process-local and cumulative since boot.
6. **15.5% of our own MCP evals error.** That is 2,126 of 13,756 over the transcripts, and
   the dominant callees are `seon.db/db`, `seon.db/q`, `cluster.boot/connection` and
   `seon.db/pull`. The next-turn problem has a large, cheap-to-label "will error" class,
   and a narrow head of functions: the top 5 are about 12.8k of the calls (est. from the
   regex counts).
7. **Datahike history/as-of could replay the exact world of each past turn.** The run loop
   already renders at an as-of basis (`db.clj:866-914`), and turns record `opened-tx` /
   `closed-tx`. **But** evaluation `source`/`output`/`comment` and `turn/reply` are
   `:seon.db/no-history? true`, so history keeps the world and drops the agent's own text,
   and resets delete it all anyway.
8. **A live defect was found while measuring.** `seon.fn/gate-set` on the running default
   JVM throws. `seon.fn/identity-names` refused a `#{...}` argument against the contract
   `[:sequential :int]`, although HEAD `src/seon/fn.clj:1850` says `[:set :int]` (commit
   e9b2066ac), so the JVM has not adopted HEAD. Building the refusal then failed with
   `:seon.schema/unnamed-callable` on `[:fn clojure.core/ifn?]`. The refusal blob digest is
   `c5a2bf04...`. This affects any test-reach ingredient at turn time until reload, and the
   `[:fn ifn?]` contract breaks refusal building on its own.

## 3. Gaps: what prediction needs that is not recorded

1. **There is no durable trajectory in the DB.** Resets wipe it. There are 0 attempts and 0
   context captures on default, and no-history text attributes drop old sources. Without
   (prompt, reasoning, forms, outcome) tuples that survive reset, the in-system corpus stays
   near empty. The disk transcripts are the only real corpus, and they are Claude Code/Codex
   tool steps, not Seon turns.
2. **There is no join from a Seon evaluation back to its transcript step.** MCP evals from
   outside agents are not recorded with the calling session/tool_use id. On default, only
   14 evals exist at all.
3. **The function calls a form actually made are not stored.** Evaluation stores only the
   `fn-entries` count (`seon.eval.edn:36`), not which armed functions ran with which
   argument shapes. The profile has per-function counts, but not per evaluation and not
   persisted. "Functions it will call" therefore has no per-step ground truth in the DB;
   the only ground truth is regex over transcript code.
4. **Test reach is not populated** (`:seon.test/reach` = 0), and the gate-set derivation
   is currently broken on the live JVM (§2.8).
5. **Error facts are empty** on default (0 signatures). Error → fix linkage (`resolved-by`
   digest-then/now) exists in the schema but has no data.
6. **Thinking and reasoning are mostly unavailable.** 74% of Claude thinking blocks are
   empty, and 100% of Codex reasoning is encrypted (7% have summaries). Intent text must
   come from assistant `text` blocks, comments (`:seon.cluster.eval/comment`) or task
   intent.
7. **No record of what was shown but unused**: which rendered context units the next forms
   referenced. `seon.render.history` has units and bytes (`seon.render.history.edn`), but
   nothing links a context unit to a later use. That link is the label a context-selection
   model would train on.
