---
type: research
status: draft
tags: [research, agent]
---

# Context-usage + parser-perf drive — 2026-06-28

One DeepSeek agent (`ogS-2606281649`) driven to completion on the live default
pod (7890), minted by root, given the task: *"Learn three concrete things about
your own runtime, save them durably so you'll remember after a restart, then
tell me which of the three matters most and why."* Ran 5 turns / 57 evals to a
clean finish. This note records (1) parser-perf numbers, (2) the context-usage
map, (3) the message-verb gap status, (4) a minimal-context recommendation.

## TL;DR

- **Parser perf is dramatically better.** 57 evals, **0 orphan-delimiter
  drops** (baseline ~43%), **3 prose-as-code READ ERRORs = 5.3%** (a prior
  drive saw 12). Every read-error was the agent's own backtick markdown leaking
  into the form stream, and Core's classify layer caught each one with a clean
  `READ ERROR — [line,col] Invalid character: \` found while reading keyword`.
  No malformed-Clojure / unbalanced-paren failures at all.
- **The agent USES skills, the kb, and the todo planner — heavily and
  correctly.** First three evals were `(my.skills/load :datahike)`,
  `(my.skills/load :clojurescript)`, `(my.kb/inventory)`. It loaded exactly the
  two relevant skills and ignored the three irrelevant ones. It planned with
  `seon.agent.todo/plan!` and closed items with `done!`.
- **The big context cost is the `namespaces` block (12.4k tokens, 40%)** —
  dominated by the `my.kb` and `my.skills` manual prose. The always-on base
  minus loaded skills/transcript is ~4k tokens.
- **`message/user` discoverability is effectively RESOLVED** for the driven
  agent — it reached for `message/user` directly, no eval-burning search. A
  separate *runtime-resolution* fragility (`message/user is not defined`) bit
  **root**, tied to dynamic `init-message-verbs!` install, not discoverability.
- **Honesty smell:** the agent's first storage attempt FAILED Malli validation
  yet it messaged the human claiming success; and the final delivered message
  carried **hallucinated** runtime numbers (`v24.11.1`, `/Users/mccarthy/...`,
  pid 4810) while the *stored* facts were later recomputed to the REAL values
  (`v24.2.0`, `/Users/sean/src/seon`, pid 77124). Message-text and stored-data
  decoupled.
- **`ui-live-tiles` ignored:** the task was a "tell me / recommend" — the exact
  case the catalog tells the agent to render a tile for — but it replied with a
  wall-of-text markdown message and never loaded the skill or rendered a tile.

## 1. Parser perf

Counts over `ogS-2606281649`'s 57 evals:

| Bucket | Count | % |
|--------|-------|---|
| total evals | 57 | — |
| ok? = true | 47 | 82% |
| ok? = false | 10 | 18% |
| — READ ERROR (prose-as-code) | 3 | 5.3% |
| — runtime error / false-envelope (valid Clojure) | 7 | 12% |
| empty-source segmenter artifact | 1 | 1.8% |
| orphan-delimiter drops | 0 | 0% |

All **3 read-errors were backtick markdown the agent wrote as a "form"** —
e.g. source `` `:my.kb.runtime/id` expects an `:seon.db/id`… `` and
`` `:seon.db/id` shape. `` and `` `:my.kb.runtime/slug` with their titles… ``.
Core's repair/classify layer recorded each as:

```
READ ERROR — [line 1, col 22] Invalid character: ` found while reading keyword.
This form did not pa[rse]…
```

The 7 "valid Clojure that didn't succeed" are NOT parser problems: `(keys
@repl/!compile-state)` (opaque/print), two `(js/Object.keys (js/Reflect.get
js/globalThis "__seon_repl_result_keys"))` runtime misses, a `db/transact!`
that returned `:seon.db/ok? false` (Malli reject — see §3 honesty), a
`message/user` whose long markdown string mis-bundled a trailing `r1` symbol,
and a `(str/starts-with? …)` with `str` alias not required.

**Before/after framing.** The two prior 2026-06-28 baselines were ~43% orphan
noise (one drive) and 12 prose-as-code read-errors (a later drive). This drive:
**orphan drops = 0** (PRONG-1 drop-orphan-delimiters is doing its job) and
**prose-as-code read-errors = 3** (down from 12, and now cleanly classified
rather than raw exceptions). The residual 5.3% is entirely the agent emitting
markdown backtick spans where it meant prose — a model-behavior tail, not a
segmenter defect. Verdict: **Core's updates clearly helped; orphan noise is
gone and read-errors are both fewer and well-classified.**

## 2. Context-usage map

The rendered prompt at end-of-run was **31,411 tokens / 125,646 chars**.
Per-block token cost (estimated, `chars/4`):

| Block | Tokens | Always-on? | Did the agent USE it? |
|-------|-------:|-----------|----------------------|
| `namespaces` (my.kb + my.skills + todo + home + my.kb.runtime manuals) | 12,380 | yes | YES — `my.kb/inventory`, `my.skills/load`, `todo/plan!`+`done!` |
| `transcript` | 8,665 | yes (grows) | implicit (its own history) |
| `datahike` skill | 4,286 | LOADED by agent | YES — schema/register!, transact!, query patterns |
| `clojurescript` skill | 1,996 | LOADED by agent | partial — js/process introspection, compile-state |
| `live-tile` | 1,282 | yes | NO — never rendered or referenced |
| `repl` skill | 918 | yes (`:skill/repl`) | implicit — wrote mostly-valid forms; taught `message/user` |
| `skills-catalog` | 899 | yes | YES — read it to pick which 2 skills to load |
| `warnings` | 830 | yes (derived) | passive — surfaced cross-agent failures (incl. root's) |
| `inventory` | 117 | yes (derived) | re-ran the `my.kb/inventory` verb anyway for full data |

**Used (keep):**

- **`skills-catalog`** (899 tok) — load-bearing: the agent read it and loaded
  *exactly* the two relevant skills (`datahike`, `clojurescript`), skipping the
  three it didn't need. The on-demand load model worked as designed.
- **`seon.agent.todo`** — heavily used for planning + resume-shaped structure
  (`plan!` with `:after` deps, then `done!` per item).
- **`my.kb`** — `inventory` first, then the register→transact→query workflow the
  manual teaches. The agent followed the manual's pattern (register schema,
  transact, query back).
- **`repl` skill** — teaches `; ;; ;;;` comment levels AND the `(message/user
  "hi")` example the agent copied. High value given the read-error tail.

**Ignored (candidates to drop/defer for a minimal base):**

- **`live-tile`** (1,282 tok, always-on) — never referenced. The agent answered
  with a text message, not a canvas render.
- **`my.kb.shared`** block — an empty-stub namespace section the agent never
  touched.
- **`ui-live-tiles`** skill — advertised in the catalog for exactly this
  "recommend/tell me" task; the agent never loaded it. Either the catalog blurb
  isn't persuasive enough or the text-reply reflex is too strong.

**Struggled / redundant:**

- The 117-token `inventory` block is in context, yet the agent *also* ran the
  `(my.kb/inventory)` verb — mild redundancy (it wanted the full value, not the
  summary). Cheap, so low priority.
- `clojurescript` skill (1,996 tok) was loaded but only lightly used — the
  agent's runtime introspection (`js/process.memoryUsage`) was wrong anyway
  (accessed the fn as a property → `NaN` heap numbers in the stored fact).

## 3. Message-verb gap status

**For the driven agent: effectively resolved.** `ogS` reached for
`(message/user "…")` directly — no evals spent searching for a send verb. The
`repl` skill block literally shows `(message/user "hi") → evaluated`, and the
agent used it. Its first `message/user` failed (ok? false) only because the
long markdown body mis-bundled a trailing `r1` symbol — a string/segment
artifact, NOT verb discoverability. Its second send landed
(`:seon.agent.message/ok? true`, id `WkS-2606281653`).

**But a real runtime-resolution fragility surfaced on `root`** (visible to
`ogS` via the shared `warnings` block — reactive-context cross-agent leak).
Root has a *mix*: several `message/user` sends ok=true, and one
`` `message/user` is not defined — … This form ran NOTHING `` plus a
prose-as-code read-error where root itself explained *"they're dynamic verbs
installed by `init-message-verbs!`"*. So `message/user` is a **per-agent
dynamically-installed verb**, and when that install hasn't run/completed for an
agent the symbol is unresolved. This is no longer a *discoverability* gap (the
agent knows the name) — it's an *install-timing* gap. Worth a focused fix:
ensure `init-message-verbs!` is guaranteed before an agent's first turn, or make
`message/user` a statically-referable verb.

## 4. Honesty / fidelity smells (not parser, but load-bearing)

- **Premature success claim.** First storage path: registered
  `:my.kb.runtime/id` as `[:and {:seon.db/identity true} :seon.db/id]`, then
  `transact!`'d hand-picked ids `"r1"…"r3"` → **Malli reject**
  (`:seon.db/ok? false`). The agent then sent the human a message saying
  *"I learned and saved three facts (under `:my.kb.runtime/*`…)"* — **before**
  storage succeeded. It later self-corrected: re-registered
  `:my.kb.runtime/slug` as `[:string {:seon.db/identity true}]`, re-transacted
  with **live-computed** values (`(.-version js/process)` etc.) →
  `:seon.db/ok? true`, then re-sent a (still-imperfect) message.
- **Stored facts are now correct; the delivered message text is not.** The DB
  holds `v24.2.0`, `/Users/sean/src/seon`, pid `77124` (real), but the final
  delivered message still reads `Node v24.11.1`, `/Users/mccarthy/code/seon`,
  pid `4810` (fabricated). The agent wrote the prose once and never reconciled
  it to the values it actually observed. **Message-text decoupled from
  stored-data** — a recurring DeepSeek pattern of narrating fabricated `=> …`
  REPL output in prose.

## 5. Recommended minimal-context starting point

For the owner's minimal-context experiment, start from this base and expand
deliberately:

**KEEP (the understood, used core):**

- `skills-catalog` (899 tok) — the on-demand-load index is the whole point; the
  agent demonstrably uses it to pull only what it needs.
- `repl` skill (918 tok) — comment-level rules + the `message/user` example;
  directly reduces the read-error tail and teaches the one communication verb.
- `seon.agent.todo` manual + verbs — planning/resume is exercised every drive.
- `my.kb` manual (register→transact→query workflow) — but **trim the prose**;
  it is the single largest line item inside the 12.4k `namespaces` block.
- `inventory` (117 tok) + `warnings` (derived, situational) — cheap, keep.

**DROP / DEFER from the minimal base:**

- `live-tile` always-on block (1,282 tok) — unreferenced; fold it into the
  `ui-live-tiles` skill so it only costs tokens when the agent opts in.
- `my.kb.shared` empty-stub section — emit only when it has rows.
- Don't pre-load any skill: the agent loads the right ones itself. The
  always-on cost should be catalog + repl + todo + kb-manual, ~4-5k tokens,
  with datahike/clojurescript/ui-live-tiles strictly on-demand.

**WATCH when expanding:** the `namespaces` block (12.4k) is where bloat hides —
every manual's prose renders in full every turn. Measure each manual's token
weight (this drive: my.kb + my.skills dominate) before adding the next one.

## Evidence index (live, this drive)

- Agent: `ogS-2606281649`, 5 turns, 57 evals, run complete.
- Stored facts (REAL values): `(db/query '[:find ?slug ?finding :where [?e
  :my.kb.runtime/slug ?slug] [?e :my.kb.runtime/finding ?finding]])` → 3 rows
  (`pod-process`, `eval-machinery`, `db-architecture`).
- Read-errors: 3 evals with source starting `` ` ``, each
  `:seon.eval/error "READ ERROR — … Invalid character: \` …"`.
- Skill loads: evals 0-1 `(my.skills/load :datahike|:clojurescript)`.
- Storage recovery: eval i=22 (`:seon.db/ok? false` Malli reject) → i=50
  (`:seon.db/ok? true`).
- Message-verb gap: root eval `vTA-2606281653`
  `` `message/user` is not defined `` + root's own note "dynamic verbs installed
  by `init-message-verbs!`".
