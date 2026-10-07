---
type: research
status: observed
created: 2026-10-07
lane: r-context
tags: [research, agent-loop, context, history]
---

# r-context: what an agent sees and where it comes from (lane r-context, 2026-10-07)

Stage: CONTEXT AND HISTORY. Observation and measurement only; nothing in `src/` or `test/` changed.

Source: HEAD `ead9d9a14`. Default runs `722f110df`, and `git diff 722f110df HEAD -- src` is empty, so every
`file:line` here is the running code. Probes ran through MCP `eval_clj`. JVM-mode probes passed the connection
explicitly. SCI probes ran as `r-context` and the disposable `r-context-probe`.

## 0. Probe and method

- `bin/seon status` and `runtime_status` report default `:faulted` (pid 41301): 17 open faults, 13 over-bound
  warnings. Top faults:
  - `seon.batch/evaluate-step`: max 43.6 s;
  - `seon.batch/evaluate-batch`: max 43.7 s;
  - `seon.sci.eval/acquire-program!`: max 2.4 s;
  - the open fault `seon.turn/show-row` → `shown-failure` nil (task `7f5dfb6ad7a2`).
- How each quantity was measured:
  - **Wall ms:** `System/nanoTime`.
  - **Allocation:** `ThreadMXBean/getThreadAllocatedBytes` on the calling thread. Every measured stage runs on
    the calling thread; `getTotalThreadAllocatedBytes` agreed within 1 MB wherever both were taken.
  - **Armed calls:** the sum of `:seon.profile/entry-counter` over `(seon.profile/cells)`, minus the measuring
    form's own baseline of 18,460. That baseline is constant across no-op probes. Concurrent lanes can inflate
    this number; ms and allocation are the reliable measures.
  - **Commits:** history datoms grouped by `:tx`, plus `:seon.db.transaction/operation`.
- Store bytes were not measured per commit here. db-watch's per-commit floor (~130 KB per commit,
  `db-watch/notes.md`) is cited where needed and marked as cited.

## 1. The old context path, read end to end and measured

### 1.1 What runs, in order, for one model reply (`seon.turn`, turn.clj)

1. **`open-turn`** (turn.clj:4282). It first runs the **system turn** (`system-turn` :2030, `:write? true`):
   - `declared-sources` (:1725) walks the agent record. It calls `walk/neighborhood`, and each rendered unit
     becomes either a generated source form or a "data render" that is printed and never evaluated
     (`:seon.turn/rendered`, :1765).
   - `latest-evaluations` (:1794) pulls every evaluation of the agent.
   - `system-plan` (:1909) decides whether each read is `:none`, `:unchanged` or `:changed`. To decide it calls:
     - `read-only-evaluation?` (:1853);
     - `db/read-evidence-current?`;
     - `code-currency` (:1885) and `code-current?` (:1899).
   - Changed reads re-run in one preview fork (`fork-for-turn`) through `evaluate-sources`.
   - The changes are diffed against the stored shown value (`db/diff`), and one `db.fn/call` commit is admitted
     only if `(= latest (latest-evaluations current agent-id))` still holds inside the writer (:2227-2230). That
     check pulls the agent's whole history again, inside the transaction.

   Then `open-call` commits (:4326).
2. **`call-turn`** (:4359):
   - `seon.cluster.prompt/prompt` (prompt.clj:422) → `acquire-context-report` (:368) →
     `render/acquire-context!` (render.clj:1721) → `render.web/derive-context!` (web.clj:2463) →
     `render.walk/history` (walk.clj:840) → `seon.eval/of-agent` with `eval/history-selector` (eval.clj:9).
   - Each evaluation is rendered by `render/render-call` → the receipt pair `seon.repl/render-ai`
     (repl.clj:586) → `repl/entity-emission` (:497) → `repl/text` (:393).
   - Then `prompt/select` keeps the newest whole units under `:seon.config.ai/prompt-token-budget`, and
     `prompt/compose` joins them.
   - `repl/frame` appends "turns left".
   - The capture commits (`seon.context/capture-tx`, context.clj:359): the whole prompt string, plus one
     contribution entity per unit.
   - The provider call, the attempt row commit, and the `plan-tx` intent commit follow.
3. **Settlement** commits (`seon.turn.batch/program-facts-call`, plus `seon.error/commit-call` when a fault is
   recorded). Then the agent re-wakes, and the next opening runs the system turn again.

### 1.2 Commits per model reply: lp-add-2's real turns (branch `:agent-4512788dda3b`)

Every transaction from 536872921 to 536872933, from the branch's own history:

| tx | at (07:55) | operation | datoms | string chars | what |
|---|---|---|---|---|---|
| 921 | 29.017 | `seon.db/transact!` | 300 | 30,852 | system-turn refresh: closed system run of 7 reads; 60 `:executed`, 19 read-evidence rows |
| 922 | 29.146 | `seon.turn/open-call` | 9 | 20 | open turn 1 |
| 923 | 29.618 | `seon.db/transact!` | 86 | 4,619 | context capture (prompt 3,611 chars, 13 contributions); 472 ms after open = prompt render |
| 924 | 31.334 | `seon.db/transact!` | 17 | 3,352 | provider attempt row (provider ≈1.7 s) |
| 925 | 31.394 | `seon.turn/plan-call` | 37 | 1,195 | intent: 4 result-less evaluation rows + raw reply |
| 926 | 31.807 | `seon.error/commit-call` | 57 | 2,327 | the `show-row` core fault (7f5dfb6ad7a2) |
| 927 | 32.037 | `seon.turn.batch/program-facts-call` | 145 | 1,993 | settlement; 15 profile measurement upserts |
| 928 | 34.612 | `seon.db/transact!` | 408 | 76,755 | system-turn refresh before turn 2: 103 `:executed`, 47 `:repeated-calls`, 32 read-evidence rows |
| 929 | 34.818 | `seon.turn/open-call` | 7 | 20 | open turn 2 |
| 930 | 35.111 | `seon.db/transact!` | 128 | 12,456 | capture (prompt 10,916 chars, 20 contributions); 293 ms after open |
| 931 | 37.159 | `seon.db/transact!` | 20 | 3,358 | attempt (provider ≈2.0 s) |
| 932 | 37.191 | `seon.turn/plan-call` | 54 | 1,822 | intent: 6 rows |
| 933 | 37.501 | `seon.turn.batch/program-facts-call` | 213 | 4,141 | settlement |

- **Commits.** One model reply costs 6-7 commits once its refresh is counted (refresh, open, capture, attempt,
  intent, settlement, plus a fault). That is 651 datoms for turn 1. At db-watch's ~130 KB per commit (cited, not
  re-measured), it is about 0.8-0.9 MB of store per reply.
- **The refresh writes more than the model turn.** It is 300-408 datoms and 31-77 K chars, mostly
  `:seon.cluster.eval/executed` sets and read evidence.
- **The capture grows quadratically over a session.** Each capture stores the whole prompt again: turn 1 3,611
  chars, turn 2 10,916 chars.
- **Wall time.** The gap 927 → 928 (2.6 s) holds settlement close, the re-wake and the refresh. 920 → 921
  (1.7 s) holds the wake and the refresh. The refresh's share of each gap is UNVERIFIED (not timed per stage).
- The replayed prompt for turn 2 (`prompt/prompt` with `:seon.turn/id "5908ecba9ec4"`) recomposes exactly
  10,916 chars, matching the stored capture, with no `capture-mismatch`.

### 1.3 Cost of each stage (measured)

| Probe | ms | alloc | armed calls | notes |
|---|---|---|---|---|
| `prompt/prompt` lp-add-2, replay of turn 2, first | 198 | 370 MB | 21,257 | 25 evaluations |
| same, again (same db value) | 4.0 | 5.4 MB | 1,767 | `derive-context!` reuse (its `::ai-calls` cache in `render/shared-cache`) |
| `prompt/prompt` lp-add-2, current, first | 98 | 180 MB | 27,001 | |
| `prompt/prompt` r-context-probe (5 evals), first / again | 101 / 3.1 | 178 / 4.1 MB | 6,851 / 1,345 | |
| profile, whole JVM life: `prompt/prompt` | mean 189, max 552 | | 11 calls | `acquire-context!` mean 173, `walk/history` mean 153 |
| `walk/neighborhood` (the opening's walk) | mean 136, max 393 | | 19 calls | |
| `seon.repl/entity-emission` | mean 3.3 per evaluation | | 549 calls | one `changed?` Datalog query per evaluation (repl.clj:527-540) |
| **`seon.turn/system-turn`** | **mean 4,715, max 43,627** | | 14 calls | the 43.6 s run is root's |
| **`seon.turn/code-currency`** | **mean 7,644, max 41,905, total 114,653** | | 15 calls | the whole cause, see §1.4 |
| `system-turn {:write? false}` r-context-probe (6 opening reads, 5 evaluations) | 3,940 / 5,063 | **11.0 / 10.9 GB** | 108k / 15k | two runs |
| `system-plan` alone, same agent | 6,035 | **19.2 GB** | 1,143 | almost no armed calls: the cost is inside one `seon.db/q` |
| `declared-sources` alone | 29 | 36 MB | 7,527 | |
| `latest-evaluations` probe (13 kept) / root (1,227 kept) | 10.8 / 1,046 | 15 MB / 1.77 GB | | root's alone is over the 1 s budget |
| `fork-for-turn` (fresh preview fork) | 18 | 42 MB | 564 | |
| `evaluate-sources` of `(+ 1 2)` in that fork | 3.8-4.4 | 4.3 MB | 1,630 | |
| `evaluate-sources` of `(seon.error/for-agent …)` | 16 | 28 MB | 2,034 | |
| `(seon.error/for-agent (seon.db/db) "r-context-probe")` as an agent form (0 errors) | 55 | — | 89,971 | lp-add-2's capture shows 98,051 |

### 1.4 Why a read-only system-turn probe ran past 32 s (root cause, measured)

- **What happens.** `seon.turn/code-currency` (turn.clj:1885) asks one `seon.db/q` for the digest of every
  symbol the kept evaluations executed:
  `[:find ?sym ?digest :in $ [?sym ...] :where (or [?e :seon.fn/sym ?sym] [?e :seon.test/sym ?sym]) [?e :seon.program/definition-digest ?digest]]`.
- **Root's case.** For root that is 672 symbols over 1,227 kept evaluations.
- **The cost is in Seon's wrapper, not Datahike.** Measured on the same database value:

  | query | ms | alloc |
  |---|---|---|
  | `datahike.api/q`, 142 symbols | 7.9-9.8 | 21 MB |
  | `seon.db/q`, 142 symbols (`code-currency`) | 9,581 | 19.2 GB |
  | `seon.db/q`, 10 symbols | 658 | 1.36 GB |
  | `seon.db/q`, 40 symbols | 2,287 | 5.4 GB |
  | `seon.db/q`, scalar `?sym` | 88 | 160 MB |
  | `seon.db/q` without the trailing `definition-digest` join | 1.1 | 0.8 MB |
  | `seon.db/q`, no `or` (`:seon.fn/sym` only), 142 symbols | 3.7 | 2.3 MB |
  | `datahike.api/q`, the two-query split, root's 672 symbols | 5.6 | 4.4 MB |

- **The scaling.** `seon.db/q` costs about 65-88 ms and 135-160 MB per binding when an `or` clause is followed by
  a join. 672 × ~63 ms ≈ 42 s, which matches the profile's `code-currency` max of 41.9 s inside the 43.6 s
  `system-turn`.
- **Where the time goes.** `system-plan` makes only 1,143 armed calls in 6 s, so the cost is in the wrapper's
  unarmed query handling (`encode-query-request` / `vector-query-call`, db.clj:2788-2850) or below it. Which
  sub-step is UNVERIFIED.
- **Task created:** `seon.db/an-or-clause-joined-to-a-pattern-costs-a-scan-per-binding` (`5035789a467c`). It is a
  `seon.db/q` defect class, so it survives the system turn's deletion: any agent query of this shape pays it.
- **Root's 43.6 s run is not an outlier.** A 5-evaluation probe agent's read-only system turn already costs
  4-5 s and 11 GB, because its MCP runs executed 142 distinct functions.

### 1.5 What the generated opening shows (and hides)

`declared-sources` emits text forms beside the values it shows. Evaluated as an agent types them (as
`r-context-probe`, SCI):

| Form | Result |
|---|---|
| `(seon.db/pull (quote [:seon.agent/id …]) [:seon.agent/id "r-context-probe"])` | value, 54 calls |
| `(get (seon.db/pull (quote [:seon.message/_to]) …) :seon.message/_to)` | `nil`, 45 calls |
| `(seon.agent/settings (seon.db/db) "r-context-probe")` | value, 95 calls |
| `(dir (quote user))` | error: `directory-value refused argument 1 namespace-name: expected a symbol, got a sequence` |
| `(seon.db/pull (quote [*]) [:seon.runtime/agent {:seon.agent/id "r-context-probe", :db/id 125969}])` | error: "Expected number or lookup ref for entity id" |
| `(count (seon.error/for-agent (seon.db/db) "r-context-probe"))` | 0, 55 ms, 89,971 calls |

- In lp-add-2's captured prompt, the `:seon.runtime/agent` read is shown as `{:seon.render/operations [...]}`: a
  value with only operations. The failure was hidden from the model, because data-render units are printed from
  the render and never evaluated.
- Task: `seon.turn/generated-opening-reads-fail-when-evaluated-as-written` (`b021cba684aa`).
- **Probe artifact (not filed).** Calling `system-turn` in JVM mode as agent `r-context` for another agent
  refused: `seon.cluster.message/render-inbox-ai` reported "The inbox's recipient names no agent". The render's
  supplied world was the calling evaluation's (r-context's branch, which predates the probe agent), not the
  request's database. As the agent itself, the call answers. The plan should know that renders supplied by world
  read the caller's world.

### 1.6 Debug views

The debug views of this assembly (session, outline, ledger context, `?sent=`, the graph page) are fully
censused in `walk-delete/design-notes.md` §1.3 and in r-delete. Their per-request cost is UNVERIFIED here (not
probed).

## 2. Stored exchanges

### 2.1 What an evaluation row stores

The schema is `:seon.cluster.eval/receipt` (resources/seon/schemas/seon.cluster.eval.edn), plus the
`:seon.eval/*` keys (seon.eval.edn). Keys with their root population (1,389 rows):

| Key | Meaning | Present on root |
|---|---|---|
| `:seon.cluster.eval/id` | identity; the handle is `result/e<id>` (`admit/result-handle`) | all |
| `:seon.cluster.eval/run` | ref to the turn | all |
| `:seon.cluster.eval/ordinal` | position in its turn | all |
| `:seon.cluster.eval/at` | instant | all |
| `:seon.cluster.eval/source` | the form's exact text (`:db/noHistory`) | all |
| `:seon.cluster.eval/comment` | the agent's prose before the form | 5 |
| `:seon.cluster.eval/ns` | ref to the namespace the form started in | 1,389 |
| `:seon.sci.eval/ending-ns` | symbol, only when the namespace changed | 163 |
| `:seon.eval/shown` | the exact text the value printer produced, once, at evaluation | 1,389 |
| `:seon.cluster.eval/error` | presence means a failure; its shown text is the flat error | 179 |
| `:seon.cluster.eval/diagnostics` | later-stage errors beside the value, as printed text | — |
| `:seon.cluster.eval/output` | `:out` | 4 |
| `:seon.eval/renderer` | the AI renderer that produced `:shown` | 37 |
| `:seon.eval/duration-ms` | | 1,372 |
| `:seon.cluster.eval/call-count` | armed calls | 125 |
| `:seon.eval/value-tokens`, `:seon.eval/shown-tokens`, `:seon.eval/elisions` | sizes | 129 |
| `:seon.eval/value-class` | the value's class | — |
| `:seon.cluster.eval/author` | `:agent` or `:system` | all |
| `:seon.cluster.eval/executed` | `[sym digest]` set | — |
| `:seon.cluster.eval/code-evidence` | | — |
| `:seon.cluster.eval/read-evidence` | vector of component evidence entities | — |
| `:seon.cluster.eval/read-basis-transaction` | | — |
| `:seon.cluster.eval/faults` | | — |
| `:seon.cluster.eval/repeated-calls` | | — |
| the `:seon.test.accretion/*` gate counts | | — |

- **The value is never stored.** `:seon.eval/value-digest` is retired (2026-10-07). Only the printed text
  survives.
- **The reply map is not stored either.** `repl/response` (repl.clj:359) rebuilds `#:seon.repl{…}` at every read
  from the stored fields:
  - `:findings` comes from `:seon.config.eval/bounds`, compiled from the packaged schema at first use
    (repl.clj:288-304);
  - `:seon.repl/changed-since?` costs one extra query per evaluation (`entity-emission`, repl.clj:527-540).

### 2.2 Reading one agent's exchanges in order: the index and its cost

**Order key.** The order is `[turn's :seon.turn/id tx, ordinal]`. This is what `seon.eval/of-agent` sorts by
(eval.clj:54-70), joining agent → `:seon.turn/agent` → `:seon.cluster.eval/run` → `:seon.cluster.eval/ordinal`.
There is no per-agent ordinal across turns.

**The index query reads all of an agent's evaluations.** No index starts at the window, so the query is
O(agent's total N) whatever window is shown. Measured on default, warm branch, raw Datahike:

| Read | ms | alloc |
|---|---|---|
| index query, root, all 1,387 (query cache defeated with a fresh variable name) | 4.5 | 7.6 MB |
| narrow `pull-many` of the last 10 (11 attrs: id, source, comment, shown, error, diagnostics, ordinal, author, duration, calls, ending-ns, ns name) | 0.26 | 0.48 MB |
| the same, last 200 (181 K chars ≈ 45 K tokens) | 3.2-3.4 | 9.0 MB |
| the same, all 1,387 | 24 | 62 MB |
| `seon.db/pull-many` (Seon's wrapper), last 200 | 5.7 | 14.5 MB |
| `pull-many '[*]`, all 1,387 | 187 | 630 MB |
| …of which `:seon.db.vector/cells` alone (read-evidence components expanded) | 174 | 584 MB |
| any other single attribute, per 1,387 rows | ~7.5 | ~18 MB |
| `seon.eval/of-agent` + `history-selector`, root 1,387 | 400-514 | 0.83-0.95 GB |
| …armed calls in that read | 480 K | the `seon.db/q` pull wrapper |
| `datahike.api/q` with the same pull, root | 218 | 711 MB |
| `of-agent` + `history-selector`, lp-add-2 (25) / growth (157), warm | 2.3-5 / 8.8 | 3-6 / 18 MB |
| the first read of a branch in this JVM (lp-add-1, walk-delete) | 250-320 | 525-535 MB |
| a later identical read once (all three branches) | 100-185 | 250 MB |

- That later read then returned to 2.5-5 ms on rerun. The cause of the 250 MB step is UNVERIFIED.
- **Measured against the plan's budget** of "N = 200 < 10 ms": the raw index plus a narrow pull is ≈ 8 ms, 17 MB
  allocated. Through `seon.db` wrappers it is ≈ 11 ms, 22 MB. Through `of-agent` + `history-selector` it is far
  over (root: 0.4-0.5 s, 0.8-0.95 GB).
- **Allocation misses the budget at N = 200.** The 5 MB budget cannot be met by any of these reads: pull-many
  costs about 45 KB of allocation per entity.

### 2.3 Turning stored exchanges into text

| How | N=10 | N=200 |
|---|---|---|
| Today's REPL text: `repl/entity-emission` + `repl/text` per row (root's last N) | 11 ms / 20 MB | 179-221 ms / 370 MB |
| Plain concatenation of `ns=> source\nshown\n\n` from the same narrow pull | — | 3.3 ms / 8.7 MB (183 K chars) |
| DATA vector of exchange maps built from stored fields | — | 5.0 ms / 9.2 MB |
| …printed by `seon.render.value/core-text` (the value printer's core, fixed budget) | 7.5 ms → 2,664 chars | 7.2 ms → **2,755 chars** |

- The value printer's budget cuts a 200-exchange vector to about 700 tokens.
- With 4 exchanges the printed text shows the form as an escaped string (`"(let [cdb @(seon.cluster.boot/connection \"default\") …"`), keys in the printer's layout order
  (`:ns :result :ms :form :value`, not insertion order), and re-elided values (`…14`, `…18` inside the re-read
  shown value).

### 2.4 How origin is recorded today (model vs outside MCP vs system vs test)

There is **no origin attribute**. `:seon.eval/origin` is something else: "the identity of the entity whose
declaration generated this evaluation", written only by the system refresh, and present on 0 of root's 1,389
rows. Origin is derived from four facts:

| Run kind | Turn | Eval `:author` | Opening tx operation | Observed |
|---|---|---|---|---|
| model reply | has `:seon.turn/attempts`, `:seon.turn.work/situation :call` | `:agent` | `seon.turn/open-call` | lp-add-2: 2 turns |
| MCP SCI run (`evaluate-source!` → `open-call`) | `:call`, no attempts | `:system` | `seon.turn/open-call` / `seon.db/absent-call` | lp-add-2: 2 (root's setup forms); root: 115+ |
| MCP JVM run (`record-evaluated-call`, turn.clj:1305, stamps `:system` at :1323) | no situation | `:system` | `seon.turn/record-evaluated-call` | root: 796 |
| system-turn refresh (`record-evaluated-tx` via `db.fn/call`) | no situation | `:system` | `seon.db/transact!` | lp-add-2: 2; root: 4 |
| generated bootstrap opening | `:generate` | `:system` | `seon.cluster/ensure-entity-call` | root: 1 |
| a test body | runs on a disposable test branch, unlinked (CLAUDE.md "Run tests as agents run") | | | UNVERIFIED that none land on an agent's branch |

- **Today's filter excludes the opening forms too.** "The model's own forms" = `:seon.cluster.eval/author :agent`
  (equivalently, the turn has attempts), and it is one datom per row. But the plan's opening forms, recorded as
  ordinary evaluations, would carry `:system` like MCP runs. Keeping them while excluding outside runs needs a
  fact that does not exist today.
- **The draft's premise holds.** lp-add-2's real prompt shows root's five MCP setup forms, including
  `seon.agent/settings!`, as the agent's own history: this is confirmed in the data above.

### 2.5 `result/<id>` after a restart

- **The marker.** `seon.sci.eval/fork-for-turn` (eval.clj:2808) on a fresh fork calls `bind-settled-results!`
  (:2786). That reads every evaluation id of the agent (`settled-results` :2769) and binds each handle to
  `{:seon.eval/unstored :live-only, :seon.eval/unstored-note "lived only in the context that evaluated it; values are not stored", :seon.eval/value-class "…"}`.
- **Root's context.** After this JVM's start (07:48), root holds 1,387 handles, 1,383 of them markers. Example:
  `result/ea558d2d712a5` → the marker with `:seon.eval/value-class "clojure.lang.PersistentArrayMap"`.
- **Cost.** One query plus one binding per historical handle, paid once per fresh fork. The per-call cost is
  UNVERIFIED.
- **Effect on stored history.** Every dig form in stored shown text (`(get-in result/e… [...])`) answers the
  marker after a restart, not the cut data. The `:result` handles in the draft's DATA maps would too.

## 3. Lookups as an agent's SCI forms today (agent `r-context`, namespace `scratch.rctx-lookup`)

| Form | Result | ms / calls |
|---|---|---|
| `(help)` | a map `{:seon.help/lines [...]}` with its own render override (`seon.bootstrap/render-help-ai`), shown as one 1,092-token string; `:large-value` finding | first 314 / 433; warm 17 / 427 |
| `(doc seon.id/symbol-in)` | `{:summary :body :example :arglists :in :out}` | first 7 / 417; warm 2 / 299 |
| `(doc seon.task/create!)` | contract printed with every map entry as `[[schema][schema]]`; 1,628 tokens | 2 / 359 |
| `(doc seon.task)` (namespace) | docstring parts plus bogus `:in [] :out []` | 1 / 53 |
| `(doc :seon.task/intent)` | input-contract refusal: "documentation-value refused argument 1 … expected a symbol, got a keyword" | 3 / 10 |
| `(doc clojure.string/split)` | `documentation-unavailable` | 0 / 123 |
| `(doc scratch.add2/add-one-returns-its-argument-plus-one)` (a task) | `documentation-unavailable` | 0 / 123 |
| `(doc 'seon.id/symbol-in)` | `ClassCastException` PersistentList → Named | 0 |
| `(dir seon.task)` | a 4-key map; `:large-value` 2,290 value tokens, 1,500 shown | first 110 / 8,995; warm 30-44 / ~9,000 |
| `(dir 'seon.id)`, `(dir (quote user))` | refused: "expected a symbol, got a sequence" | 2 / 10 |
| `(clojure.repl/dir-fn 'seon.task)` | 31 names | 0 |
| …if run before `(ns …)` created the MCP `namespace` | "No namespace: scratch.rctx-lookup found" | |
| `(source seon.id/symbol-in)`, `(apropos …)`, `(find-doc …)` bare | "Unable to resolve symbol" | 0 |
| `(clojure.repl/source seon.id/symbol-in)` | value `nil`, text on `:out` | 1 / 71 |
| `(clojure.repl/source-fn 'seon.task/create!)` | the stored source string | 0 / 71 |
| `(clojure.repl/source-fn 'clojure.set/union)` | `nil` | 4 / 173 |
| `(clojure.repl/apropos "symbol-in")` | `(seon.id/symbol-in)` | 1-3 |
| `(clojure.repl/find-doc "symbol-in")` | `nil`, prints (12 ms with `with-out-str`) | 6 |
| `(my.program/source 'seon.id/symbol-in)` | `{:sym :kind :line :file :source :doc :contract}` | 4 / 90 |
| `(my.program/search "symbol-in")` | 6 matches | 489 / 8,160 |

**Agent-side failures in the real turns.** In lp-add-2's second model turn:
- `(dir 'scratch.add2)` failed the same way;
- `(clojure.repl/source add-one)` showed `nil`;
- `(clojure.test/run-test-var #'add-one-adds-one)` failed with `ClassCastException` "sci.lang.Namespace cannot be
  cast to clojure.lang.Symbol".

**`(help)` teaches functions the plan deletes or replaces**, and some of its claims are false:

- It names `(seon.turn/turns-left)` (turn.clj:2706), `(my.agent/done)` (my/agent.clj:23) and
  `(my.program/source …)`.
- It says "The prompt shows your namespace user". That is the assigned namespace, not the current one.
- It says "results arrive in your NEXT turn".
- It ends "Tools: ." (empty for an agent whose namespace requires nothing).
- Source: bootstrap.clj:30-81.

**The repl-lookup design notes, checked against the running code.**

- **Verified:**
  - bare `source`/`apropos`/`find-doc` do not resolve;
  - SCI's `apropos` works;
  - `find-doc` prints;
  - `dir` costs ~9,000 calls and 2,290 tokens, and `dir-fn` gives 31 names fast;
  - warm `doc` is 2 ms / ~300 calls;
  - `doc` refuses a keyword by contract, answers a library var "unavailable", and gives a namespace bogus
    `:in [] :out []`;
  - library `source-fn` is nil;
  - two installers (`build-base-ctx` and `install-program-doc!`, eval.clj:2137, its `_db _projection` unused);
  - `(dir x)` wraps its argument in a quote (`program-dir-var`, eval.clj:2095-2104), and so does `(doc x)`
    (:2079-2093).
- **Line numbers.** They hold at `ead9d9a14` except `build-base-ctx` at :348, not :350, and
  `render.ns/directory`'s helper, which is `public-functions` at ns.clj:219, not `public-names` at :224.
- **Differences:**
  - `my.program/search` is 489 ms on its first call here, not 125-211 ms;
  - `my.program/source` is 4 ms, not 9 ms.
- **Not probed (UNVERIFIED):** the SCI `source-fn` load-fn `:ctx` contract refusal.
- **The `(doc f)` contract garbling is already filed:** `seon.sci.eval.edn` lines 4 and 146.

## 4. The draft's REPL vs DATA presentations: the stored fields each needs

| Field the presentation shows | REPL | DATA | Stored today? |
|---|---|---|---|
| prompt namespace | `ns=>` | `:ns` | yes: `:seon.cluster.eval/ns` (ref; `:ending-ns` only when it changed) |
| the agent's prose | `;;` comment above the form | `:comment` | yes: `:seon.cluster.eval/comment` (5 rows on root; the model's prose) |
| form | the source text | `:form` | yes: `:seon.cluster.eval/source` (text). In DATA it prints as an escaped string unless read back into a form, which would be a second read |
| value | shown text | `:value` | **only as printed text** (`:seon.eval/shown`). The live value exists only in the evaluating context and becomes a marker after a restart. DATA's `:value` must re-read printed text (`repl/shown-value`): 114 of root's 1,389 are not one EDN value and come back as strings; elided ones read back with elision symbols |
| error | the flat error | `:error` | yes: `:seon.cluster.eval/error` presence + its shown text |
| `:out` | printed text | `:out` | yes: `:seon.cluster.eval/output` |
| handle | — (REPL has none) | `:result` | derived from `:seon.cluster.eval/id`; a marker after a restart |
| ms, calls | — | `:ms`, `:calls` | yes: `:seon.eval/duration-ms`, `:seon.cluster.eval/call-count` |
| order | | | `[turn tx, ordinal]` (§2.2) |
| origin (model / opening / outside) | | | **no** (§2.4) |
| "calls left" | | | derived (`repl/frame`) |

- **REPL needs nothing new**, if its text is concatenated from the narrow pull (§2.3: 3.3 ms at N = 200), not
  built through `entity-emission`.
- **DATA needs either the value or a lossless data form of it**, and neither exists. If the vector goes through
  the value printer's ordinary budget, history is cut to about 700 tokens.

## 5. Facts the draft gets wrong

1. **The draft omits the cause of the 32 s overruns.** The cause is `seon.db/q`'s `or` + join cost per binding
   inside `code-currency`, not the walk (§1.4). The defect stays in `seon.db/q` after the system turn is deleted.
2. **"History read from the agent's stored evaluations (origin :model; outside runs excluded)".** No origin
   attribute exists.
   - `:seon.cluster.eval/author :agent` selects model forms.
   - Recorded opening forms would be `:system`, the same as MCP SCI and JVM runs and system refreshes.
   - `:seon.eval/origin` means something else.
3. **DATA: "printed by the one value printer, with the same budget and dig forms as any value".** That cuts 200
   exchanges to 2,755 chars. It contradicts owner decision 2 ("history all of it, unclipped").
4. **DATA's `:value` cannot be the value.** Only the printed text is stored. The `:result` handles in the example
   answer a `:live-only` marker after a restart.
5. **"Each map is exactly the reply map MCP returns".** The reply map is not stored. It is rebuilt per read from
   stored fields, plus findings from the schema bounds, plus a per-row `changed?` query. "Nothing re-rendered"
   holds for `:shown` only.
6. **"Context read … O(N) on one indexed read, < 10 ms at N = 200".**
   - The index read is O(agent's total N), not O(window).
   - The time fits only on raw Datahike or a narrow `seon.db` pull (8-11 ms).
   - Allocation cannot meet "< 5 MB" with Datahike pull at N = 200 (9-14 MB pull alone).
   - `of-agent` + `history-selector` is 0.4-0.5 s and 0.8-0.95 GB for root.
7. **The opening form `(doc 'scratch.add2/add-one-returns-…)`** throws `ClassCastException` here (as in Clojure).
   Without the quote, `doc` of a task symbol answers `documentation-unavailable` today.
8. **§2 "One model reply takes 5-6 commits".** It takes 6-7 once the refresh commit before it is counted (§1.2).
   The refresh writes more datoms than the model turn's own record.

The other §2 claims about my stage verify:
- bare `source`/`apropos`/`find-doc`;
- `(dir ns)` ~9,000 calls, 30-51 ms;
- `seon.error/for-agent` at ~90-98 K calls;
- the agent sees root's setup runs.

## 6. Constraints the plan must respect

- Store and read the model/opening/outside distinction as one fact per evaluation or turn. Today only
  `:author :agent` separates the model's forms.
- Values do not survive the evaluating context. Any presentation of history is printed text plus handles that
  become markers on restart.
- The index read is over all of an agent's evaluations: about 4.5 ms per 1,387 rows on raw Datahike.
  Datahike pull allocates about 45 KB per entity.
- Avoid `seon.db/q` `or` + join shapes (§1.4) and the `entity-emission` per-row query on any per-turn path.
- `render/shared-cache` holds `derive-context!` results per branch scope. It is an atom, not a `seon.cache` kind.
- The first read of a branch in a JVM costs 250-320 ms / 525 MB.
- `dir`/`doc` are macros that quote their argument, so `(dir 'x)` and `(doc 'x)` fail, as in Clojure.

## 7. Slated for deletion in my area, with callers (verified by grep at `ead9d9a14`)

| Var | Callers in src |
|---|---|
| `seon.cluster.prompt/prompt` (whole ns, 460 lines) | `seon.turn` (delay `cluster-prompt-prompt`), `seon.repl` (doc mention), `seon.render.transcript`, `seon.render.web` |
| `seon.render/acquire-context!` | `seon.cluster.prompt`, `seon.render.web`, `seon.render.transcript` |
| `seon.render.web/derive-context!`, `change-context`, `history-segments` | `seon.render/acquire-context!` |
| `seon.render.walk/history` | `seon.render.web/derive-context!` only (plus 5 test files) |
| `seon.context` (420 lines: `capture-tx`, selection, `append-tx`, `compact-tx`, `message-custody`) | `seon.turn/call-turn` (capture), `seon.turn/compact!`; `message-custody` is called by `seon.render.transcript/message-entry` |
| `seon.turn/system-turn` and closure (`declared-sources`, `latest-evaluations`, `system-plan`, `code-currency`, `code-current?`, `read-only-evaluation?`, `generated-read-fault`, `record-evaluated-tx`) | `open-turn`, `generate-turn`, web context actions (`render.web/change-context`); 16 test files |
| `seon.eval/history-selector` | `seon.render.walk/history` |
| `seon.repl/entity-emission`'s `changed?` query and `:seon.repl/changed-since?` | `repl/render-ai`, `render.transcript`, `seon.turn` |

The full census is in r-delete's notes and walk-delete's design.

`seon.eval/of-agent` survives: `seon.render.ns`, loop-pass history, 17 test files.

## 8. Implications for the plan

1. Read the agent's exchanges with one narrow pull, plus an ordinal-ordered index. Leave `of-agent` with
   `history-selector` and `entity-emission` off the per-turn path.
2. Decide the origin fact before S1, because opening forms and outside runs share `:author :system` today.
3. Price DATA honestly. It shows printed text re-read as data, under a printer budget that cuts history. It needs
   a rule for that budget, or it does not meet "history unclipped".
4. Fix or route around the `seon.db/q` `or` + join cost before any slice measures "Seon's work < 200 ms".
5. Rewrite `(help)` with the opening. Today it teaches `turns-left`, `my.agent/done` and `my.program/source`.

## 9. Tasks and disposable resources

- **Created:**
  - `seon.db/an-or-clause-joined-to-a-pattern-costs-a-scan-per-binding` (`5035789a467c`);
  - `seon.turn/generated-opening-reads-fail-when-evaluated-as-written` (`b021cba684aa`);
  - `seon.eval/of-agent-default-selector-refuses-on-roots-stored-read-plans` (`3c11cc770677`). This one
    **duplicates** r-delete's `seon.eval/of-agent-default-selector-refuses-on-stored-read-plans`
    (`6ccd16292e4f`), filed earlier the same hour. Root should close mine.
- **Linked (existing):**
  - `seon.sci.eval.edn` lines 4 and 146 (doc contract garbling);
  - `seon.turn/history-acquisition-decodes-stored-read-plans`;
  - `seon.turn/a-trivial-agent-turn-takes-eleven-seconds`;
  - `seon.turn/read-evidence-rows-are-two-fifths-of-the-stored-bytes`;
  - `seon.db/the-query-wrapper-costs-36x-datahike`;
  - `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed`.
- **Disposable branches**, which root must retire:
  - `r-context-probe` on `:agent-f3c728c39a5f`: `my.agent/retire-branch!` from `r-context` refused ("still has a
    connection in this process"). From the probe itself it answered only `{:seon.error/message "run transition refused: admission-closed"}`,
    with no reply map and no handle (known task above);
  - `r-context` on `:agent-aeeb04b9c485`.

  Neither holds program changes except `scratch.rctx/add-two` on the probe branch.
- **Ugly output seen:**
  - an `entity-emission` contract refusal repeats the whole offending value in each of its 5 problems;
  - map keys printed as cut vectors when a frequency map is keyed by vectors.

  Both are value-printer or error-shape findings that belong to their owners. Not filed separately.
- **RESET NEEDED:** no.
