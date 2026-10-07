# The product agent loop today — an exact map (2026-10-07)

Research only. HEAD `a8e3bde5b` (branch `refactor/agent-platform`). All `file:line` are at that
working tree. `turn.clj` = `src/seon/turn.clj` (6055 lines). Live numbers come from three read-only
JVM-mode `eval_clj` queries on cluster `default` (each query itself opened one MCP `:call` turn, so
turn counts moved 465 → 468 while measuring). Anything not read is marked UNVERIFIED.

## 0. Headline facts

- A single **model-driven reply** takes **three Flow passes and two turn entities**:
  pass 1 `:open` writes a *system turn* (re-evaluated context reads, closed at birth) **and** the
  real turn (`:call`); pass 2 `:call` renders the prompt, captures it, calls the provider, records
  an attempt, freezes the reply as evaluation rows, evaluates them in memory, settles everything
  in one transaction and closes the turn; pass 3 re-derives work (continue, or idle).
- **Default has never run a model turn.** 468 turns: 344 system turns (no `:seon.turn.work/situation`),
  123 `:call` turns, 1 `:generate`; 0 `:seon.turn/attempts`, 0 `:seon.ai.attempt/id`, 0 context
  captures, 0 dispositions/results. Every `:call` turn on default is an MCP/virtual **submission**
  (opened with situation `:call` by `system-run-call` → `open-call`, turn.clj:431), not a model call.
- All 584 evaluations carry `:seon.cluster.eval/author :system` — MCP submissions are written by
  `system-run-plan` with author `:system` (turn.clj:766), so `:agent` (only `plan-tx`, turn.clj:521)
  has never been written on default. Evaluations by turn kind: 449 in system turns, 129 in `:call`
  (submission) turns, 9 in the generated opening.
- 23,647 `:seon.cluster.eval/read-evidence` refs and 7,509 `:seon.cluster.eval/executed` datoms
  exist only so the next system turn can decide whether to re-run a read (§2).
- ~77% of all turn entities are system turns: the "refresh changed reads" mechanism is the
  dominant writer of turns.

## 1. One pass, wake → idle, numbered

Legend for the tag after each step: **CORE** (a model-driven agent needs it), **REC** (crash
recovery / refusal arm), **SYS** (system-authored or generated turns), **BND** (parking, permits,
backstops, episode bounds), **MCP** (outside agents / source submission), **BK** (bookkeeping).

### 1a. Wake and arming (before any pass)

1. Commit asserts a listened attribute (`:seon.message/to`, `:seon.effect/inbound`, or
   `:seon.schedule.fire/*` owner; declared `:seon.wake/listen true` in
   resources/seon/schemas/seon.message.edn:15, seon.effect.edn:88, seon.schedule.fire.edn:12).
   Datahike `listen!` callback `wake/route!` (src/seon/cluster/wake.clj:454) matches the datom and
   `offer!`s `:seon.agent/wake` on the recipient's sliding-1 mailbox channel (`wake/delivery`,
   wake.clj:327). Reads: tx-data, routing atom. Writes: nothing. **CORE** (the wake) + **BK**
   (matcher compilation, declarations-refusal).
2. No routing entry → armer: `armer-step` (src/seon/cluster/agent.clj:1602) derives
   `agents-to-arm` (agent.clj:1586 — calls `turn/more-agent-work?` = `next-agent-work` per
   candidate) and `arm!` (agent.clj:1250) stamps the per-agent graph `graph-definition`
   (agent.clj:563): procs `:seon.agent/mailbox` (`mailbox-step` agent.clj:477),
   `:seon.agent/turn` (`turn-step` agent.clj:511 → `turn/step`), `:seon.agent/schedule`. Arm
   primes one wake. Reads: agent facts. Writes: routing atom only. **CORE** (one runner per agent)
   + **BND** (arming/stopping/`::arming` reservation states).
3. `mailbox-step` forwards one payload-free `:seon.agent/episode` signal (sliding-1) to the turn
   proc. Exists so ping/pause answers during a multi-second provider call (agent.clj:17-26). **BK**.
4. `turn-step` publishes the running thread in `:seon.agent/turn-thread` so `disarm!` can
   interrupt it (agent.clj:511-561). **BND**.

### 1b. `step` — the Flow transform (turn.clj:5807)

5. Parked check: if `:seon.turn.loop/parked` and not a submission, do nothing (turn.clj:5853).
   **BND**.
6. `await-turn-permit!` (turn.clj:5618): reads dial `:seon.config.agent/turn-completion-backstop-ms`
   via `agent-dial` (turn.clj:5586: agent overlay → handle → `config/effective`), `alts!!` on the
   completion permit channel; missing dial → fault + park (turn.clj:5862-5875). Reads: agent
   overlay, config, `open-for-agent`. Writes: fault on absence. **BND**.
7. `arm-turn-completion-backstop!` (turn.clj:5749): an executor thread that records a fault if the
   transform does not finish within allowance (+provider/eval allowance via `await-part`,
   turn.clj:4371). Holds an `atom` `:seon.agent/run-id` (turn.clj:5659). **BND**.
8. Submission branch (`:seon.agent/request` port): `system-run-plan` (turn.clj:5895 → 732) asks
   the writer's decision on the db value; a staged-blob reply opens its run in its own commit
   (turn.clj:5900-5904); otherwise the open rides the settlement (`submission-open-tx`, turn.clj:788;
   `with-submission`, turn.clj:3482). Work = `{:situation :resume :ordinal 0}`. **MCP**.
9. Otherwise `next-agent-work` (turn.clj:5907 → 2848) on one db value. Reads (§1c). Writes nothing.
   A refused read throws to the graph fault route (turn.clj:5916). **CORE**.
10. nil work → report `{:idle? true}`, answer reply if any (turn.clj:5925-5929). **CORE**.
11. Else `turn` (turn.clj:5930 → 5468) with the situation. After: write-refusal counting and
    park (`write-refusal-bound` 5668, `record-write-refusal-fault!` 5709; turn.clj:5950-5978)
    **BND**; answer submission reply (turn.clj:5979) **MCP**; on `:closed` offer an armer wake
    (turn.clj:5986-5991, "first-agent supervision") **BK**; **self-rewake** own mailbox
    (turn.clj:5999-6001) **CORE**; `finally` republishes permit and cancels backstop
    (turn.clj:6038-6054) **BND**.

### 1c. `next-agent-work` (turn.clj:2848) — derivation order

12. `agent-run` (turn.clj:2319) = `open-for-agent` (turn.clj:385): the turn with
    `:seon.turn/agent` = agent and no `:seon.turn/closed-tx`. If open:
    - has `:seon.turn/reply-size` → `fold-or-close` (turn.clj:2798): `next-ordinal`
      (turn.clj:2332, first evaluation row without `:seon.eval/shown`/`error`/`interrupted-at`)
      → `:resume` with ordinal, else `:close`. **REC** in practice (an ordinary pass freezes and
      settles in the same pass, step 20; a frozen-but-open turn only survives a crash between
      freeze and settle — and boot `recover-call` closes those, so this arm is near-dead).
    - situation `:generate` → `resume-or-generate` (turn.clj:2813). **SYS**.
    - situation `:call` without reply → `:call`. **CORE** (the provider pass).
13. No open turn: `opening-deferred?` (turn.clj:2730) — reads `max-episode-runs`
    (agent settings → cluster dial, turn.clj:2703), `wake/declarations-refusal`,
    `outside-wake-t` (turn.clj:2627: history seek over every listened attribute, newest outside
    wake), `spent-runs` (turn.clj:2665: turns since then with attempts or a reply, or a `:call`
    closed before any attempt), and "failed attempt since the anchor" query (turn.clj:2763).
    **BND**.
14. `unanswered-wakes` (turn.clj:2951): wake datoms with `:t` > `latest-answering-turn-t`
    (turn.clj:2924: newest turn opening tx with a successful attempt or a reply asserted after
    opening). Or `continuing-reply?` (turn.clj:2825): the latest closed turn has reply, a
    non-error attempt and no `:seon.turn/disposition`. Either → `{:situation :open,
    :seon.message/id oldest}`. **CORE** (the question "is there something to answer").

### 1d. `turn` (turn.clj:5468)

15. Binds the cluster's schema projection state; merges `ai/agent-overlay` into the handle
    (turn.clj:5519); dispatches on situation (turn.clj:5522-5527); afterwards
    `episode-exhausted!` (turn.clj:5400) records a fault when this pass spent the last run and
    wakes stay deferred (**BND**); any Throwable → `close-escaped-run!` (turn.clj:5439 →
    `recover-tx`) and rethrow (**REC**).

### 1e. Pass A — `:open` → `open-turn` (turn.clj:4300)

16. `system-turn {:write? true}` (turn.clj:4330 → 2045). **SYS**. Sub-steps:
    a. `declared-sources` (turn.clj:1738): `walk/neighborhood` from `[:seon.agent/id …]`
       renders the agent's one-hop units (AI face); each unit's render returns source blocks
       (e.g. `situation-form` → `(help)`, agent.clj:246) or data; blocks are segmented by
       `planned-sources` into forms; data renders become `:seon.turn/rendered` rows with a
       requery source (`unit-read`, turn.clj:1705). Uses two atoms (`calls`, `invocations`).
    b. `latest-evaluations` (turn.clj:1807): pulls **every** evaluation of the agent with
       `:seon.cluster.eval/run` situation and read-evidence refs, keeps latest per
       `source-key` (namespace + read forms — re-reads each source, turn.clj:1692), applies
       stored `{:seon.repl/changes …}` diffs to reconstruct shown values, then pulls full
       read-evidence for kept ones.
    c. `system-plan` (turn.clj:1922): declared opening + every previously-evaluated
       *read-only* source (`read-only-evaluation?` turn.clj:1866: has read evidence, all on this
       connection, no declaration, no `:seon.db/receipt` tx, no effect) without a
       `generated-read-fault` (turn.clj:1973); status `:none | :pending | :changed | :unchanged`
       via `db/read-evidence-current?` and `code-current?` (digests of `:executed` symbols,
       turn.clj:1898-1920).
    d. For `:none`/`:changed`: evaluate each (one shared preview fork, or the agent's acquired
       ctx when writing — `acquire-context!` agent.clj:1049) via `evaluate-sources`; compare
       shown value with previous → `:unchanged` gets only a refresh of read-basis/evidence on the
       old evaluation (turn.clj:2164-2181); changed ones store `{:seon.repl/changes diff}` as
       their shown text (turn.clj:2193-2205).
    e. Write: `record-evaluated-tx` (turn.clj:1378 → `record-evaluated-call` 1309, `row-tx`
       1057) — a **new closed turn** (`:seon.turn/id/agent/starting-ns/reply/opened-tx/closed-tx`,
       no situation) with one evaluation per emitted read, inside a `:db.fn/call` that refuses
       if the agent became busy or `latest-evaluations` changed (turn.clj:2237-2245).
    Reads: whole evaluation history of the agent, read evidence, program digests. Writes:
    system turn + evaluations + blobs, or evidence refresh datoms.
17. `next-id` (turn.clj:530): digest of (branch, agent, count of agent's turns). **BK**.
18. `open-call` (turn.clj:401) inside the writer: refuses no-such-agent, no-ops when a turn is
    open, writes `{:seon.turn/id, :agent, :opened-tx, :seon.turn.work/situation :call,
    :trigger?}` and, when triggered, `:seon.agent/runtime {:seon.runtime/trigger …}`
    (turn.clj:431-435). **CORE** (open) + **BK** (runtime trigger mirror).
19. Refusal → `settle!` records the error (turn.clj:4351-4358) **REC**; else report `:released`.
    The pass ends; step rewakes (step 11).

### 1f. Pass B — `:call` → `call-turn` (turn.clj:4377)

20. `provider-targets` (turn.clj:4064): `ai/settings` from `config/effective` + agent overlay,
    `ai/targets`. `await-turn-part!` extends the backstop by the provider timeout (**BND**).
21. `opening-db` (turn.clj:268): as-of the turn's opening tx. Prompt:
    `seon.cluster.prompt/prompt` (prompt.clj:422) — see §4. **CORE**.
22. Capture **before** the provider: `seon.context/capture-tx` (context.clj:359) writes
    `:seon.context.capture/{id,run,basis-t,prompt,contributions}` + `:seon.ai.tokens/characters`
    + one contribution row per segment (context.clj:336), or a refusal capture
    (turn.clj:4553-4568). **BK** (owner ruling 4, 2026-07-28; capture-before-call evidence).
23. No-provider dial → `freeze!` with empty text (turn.clj:4574). **MCP/test** path.
24. `ai/complete` (turn.clj:4591; src/seon/ai.clj UNVERIFIED internals) with optional streaming
    sink onto the cluster stream channel. **CORE**.
25. `record-attempt!` (turn.clj:4084): one transaction with `:seon.ai.attempt/*` row (id,
    ordinal, at, endpoint, model, settings ref + settings-edn, usage + usage-edn, reasoning or
    blob, finish-reason, error/truncation fact refs), `[:db/add turn :seon.turn/attempts
    "attempt"]`, error facts, and `ai/model-observation-tx` (`:seon.ai.model/last-used-at`,
    latency, usage). **CORE** (last provider call) + **BK** (model observation, settings copy).
26. Failure → close the turn (`close-tx`, turn.clj:4634) or `fail!` → `settle!` (**REC**).
27. `freeze!` (turn.clj:4447): `planned-sources` (turn.clj:3047 → `segmented-sources` 3024,
    `forgive/segments` in `:reply` mode: prose → comments, delimiter repair); `stage-reply!`
    (turn.clj:78) puts reply in `:seon.turn/reply` or a blob; `plan-tx` → `plan-call`
    (turn.clj:653) writes `:seon.turn/reply-size`, `:seon.turn/starting-ns`
    (requested → `previous-ending-namespace` 563 → agent namespace), reply/blob, and one
    evaluation row per form (`source-rows` 615: id, run, ordinal, at, author `:agent`, source,
    ns, comment) — **"intent commit"**, durable before evaluation. **CORE** (store forms) + **REC**
    (the intent rows exist so a crash leaves "rows without terminal facts").
28. Directly `resume-turn` in the same pass (turn.clj:4497-4510).

### 1g. `resume-turn` (turn.clj:5072) — evaluate and settle

29. `sci.eval/fork-for-turn` (turn.clj:5093) forks the base SCI ctx for this turn; unbound
    result handles become a fault (turn.clj:5106-5112). **CORE**.
30. `message/trigger` (message.clj:21): the run's `:seon.turn/trigger` message id. **CORE**
    (who asked).
31. Rows: from the submission value (**MCP**/**SYS**) or `fold-evaluations` (turn.clj:4228: pull
    the frozen rows back). Starting namespace: submission → `fold-namespace` (turn.clj:4261) →
    agent namespace.
32. `evaluate-sources` (turn.clj:4875) → `seon.batch/evaluate-batch` (src/seon/batch.clj:650) with
    `seon.turn.batch/turn-request` (turn/batch.clj:830): per row read once (`read-row` 4681),
    evaluate (`evaluate-row` 4718), show (`show-row` 4746), publish disposition + result handle
    (`publish-row` 4771, `early-disposition?` 4656), derive program change
    (`turn.batch/derive-form` turn/batch.clj:301), fold. In memory; nothing written. **CORE**.
33. Per form `problems/form-problem` (turn.clj:5181-5188; src/seon/problems.clj UNVERIFIED
    internals) → assignment message for a failing form of an owned namespace. **BK**.
34. `settle-evaluated!` (turn.clj:3619) → `settle-batch!` (turn.clj:3512). One transaction:
    - `turn.batch/program-facts-call` (program entities of the batch) **CORE** (program change);
    - `receipt-settle-batch-tx` (turn.clj:986/974 → `receipt-settle-call` 1437) — terminal facts
      on each evaluation row from `evaluation-facts` (turn.clj:97): shown, error, diagnostics,
      output, read-evidence, read-basis-transaction, executed (only beside evidence), code-evidence,
      call-count, repeated-calls, ns, ending-ns, duration/acquisition/allocated, value tokens,
      elisions, value-digest/class, unstored/full/replaced, print length/level, origin,
      renderer(+fn), `:my.turn/value` (not an attribute; dropped at row-tx UNVERIFIED);
    - `with-fault-windows` (turn.clj:3468 → `fault-window` 3448): `:seon.cluster.eval/faults`
      signatures of errors since the agent's previous settlement, on the last row **BK**;
    - `evaluation-terminal-data` (turn.clj:3330) per form: disposition (`:seon.turn/disposition`,
      `:seon.turn/result`), `undisposed?` close (a triggered agent's last undisposed form closes),
      `asked-value` → `message/reply` (message.clj:86: reply to the trigger's sender) or a
      problem assignment, delivered through `message/delivery` (`delivery-rows` turn.clj:3207),
      delivery refusals as error facts **CORE**;
    - `measurement/significant-tx` for executed definitions' profiles **BK**;
    - `close-folded-call` (turn.clj:2371 → `close-call` 447): closes the run and asserts
      `:seon.turn/handled` for messages sent before opening **CORE** (close) + **BK** (handled);
    - `seon.fault/folded-tx` repeats **BK**; `with-submission` prepends a submission's open **MCP**.
    Refused commit → revalidate per form and retry once without refused program facts
    (turn.clj:3594-3610), else `settle-batch-refusal!` (turn.clj:3786) **REC**.
35. `sci.eval/install-evaluated-rows!` (turn.clj:5237): bind committed definitions into the
    base ctx; failure → fault. **CORE**.
36. Report `:closed` or `:released` with basis (turn.clj:5251-5254).

### 1h. Pass C and the other situations

37. Rewake → `next-agent-work`: no open turn; `continuing-reply?` true unless disposition
    `:completed`/`:wait` → `:open` again (back to step 16) until `max-episode-runs`; else idle.
    **CORE** (continue/stop) + **BND** (episode cap).
38. `:close` → `close-turn` (turn.clj:5256): `close-tx`; failure → `settle!`. **REC**.
39. `:generate` → `generate-turn` (turn.clj:5278): re-derive `declared-sources` + `system-plan`
    with no history, append remaining forms (`append-generated-tx` 851) as a submission so they
    open/settle/close in one tx through `resume-turn`. Only opened by `bootstrap/seed-tx`
    (src/seon/bootstrap.clj:334, root's first task) via `generated-run-tx` (turn.clj:858). **SYS**.
40. Stop: `disarm!` (agent.clj:1492) interrupts the transform, joins the executor, then
    `record-interruption!` (agent.clj:1462) → `recover-tx`. Boot: `seon.cluster` closes every
    open turn with `recover-tx` (src/seon/cluster.clj:2966-2972). **REC**.

Stale doc: `step`'s docstring (turn.clj:5808-5811) and agent.clj:27-28 say the pass "settles this
agent's orphan"; no orphan-settling code exists in `step` — recovery is boot/disarm/escape only.

## 2. Entities and attributes the loop writes

Consumers = `src/` files other than turn.clj that name the key (grep), plus turn.clj readers
named explicitly. "Agent?" = could live on the agent entity; "Eval?" = could live on an evaluation.

### 2a. Turn entity `:seon.turn/turn` (resources/seon/schemas/seon.turn.edn:5)

| attribute | writer | readers | live | agent / eval? |
|---|---|---|---|---|
| `:seon.turn/id` | open-call 431, record-evaluated-call, system-run-plan | ~27 files (status, transcript, web, message, problems, fault, error, effect, eval, drive, prompt, mcp, test…) | 468 | identity of a grouping; evals could order by their own tx/ordinal |
| `:seon.turn/agent` | same | 18 files; `open-for-agent`, `latest-evaluations`, `spent-runs`, `next-id`, `fault-window`, `of-agent` | 468 | becomes evaluation → agent ref |
| `:seon.turn/opened-tx` | open-call ("datomic.tx") | 10 files; `fault-window`, transcript, walk | 468 | **redundant**: equals the tx of `:seon.turn/id` datom, which is what `opening-db`/`latest-answering-turn-t` actually use |
| `:seon.turn/closed-tx` | close-call 447, recover-call, record-evaluated | 11 files; THE busy fence (`open-for-agent`) | 468 | agent: "busy" = presence of an unsettled evaluation, or nothing (one pass is synchronous) |
| `:seon.turn.work/situation` | open-call (`:call`), generated-run-tx (`:generate`); system turns none | turn.clj dispatch, repl.clj, walk.clj:891 (exclude own evals), transcript | 123 call, 1 generate, 344 absent | derived/deleted |
| `:seon.turn/trigger` | open-call | message/trigger, transcript, drive, bootstrap, agent.clj; `evaluation-terminal-data` (undisposed close) | 13 | eval (the message the batch answers) or agent "last answered wake t" |
| `:seon.turn/starting-ns` | plan-call, system-run-plan, record-evaluated | fold-namespace, transcript, mcp, turn.batch | 468 | agent `current namespace` (= last eval's ending-ns) |
| `:seon.turn/reply` / `reply-blob` | plan-call/stage-reply | transcript only (reply); status+transcript (blob) | 124 | eval: the raw reply text as the batch's source (or agent "last reply") |
| `:seon.turn/reply-size` | plan-call, system-run-plan | answeredness (`latest-answering-turn-t`), `spent-runs`, `fold-or-close`, `close-folded-call`, `continuing-reply?`, repl, web, transcript, drive | 464 | it is used as a *flag* ("plan frozen / reply accepted"); derivable from evaluations existing |
| `:seon.turn/attempts` | record-attempt! 4084 | prompt (calibration), status, repl, transcript, drive; `spent-runs`, `opening-deferred?`, `continuing-reply?` | 0 | agent: last provider call (ref) |
| `:seon.turn/disposition` | evaluation-terminal-data | transcript (render), drive; `continuing-reply?` | 0 | agent: "done/wait" state, or derived from last eval value |
| `:seon.turn/result` | evaluation-terminal-data | drive.clj, reconcile.cljc | 0 | the `:completed` value; already the last eval's value and the reply message content |
| `:seon.turn/handled` | close-call 467 | **only close-call itself (turn.clj:464) and tests** | 13 | **no production consumer** — delete |

### 2b. Runtime component `:seon.runtime/entity` (seon.runtime.edn) via `:seon.agent/runtime`

| `:seon.runtime/trigger` | open-call 435 | transcript.clj:929, 2291, 2311 (render only) | 1 | a mirror of the latest turn's trigger; derived; delete or move |

`:seon.runtime/listens` (wake patterns) is read by wake.clj — not loop-written.

### 2c. Evaluation entity `:seon.cluster.eval/receipt` (seon.cluster.eval.edn:49)

Written at freeze (`source-rows`/`receipt-row` turn.clj:706) and settled
(`receipt-settle-call` 1437) — or both at once for system/submitted turns.

| attribute | readers outside turn.clj | note |
|---|---|---|
| id, run, ordinal, at | everywhere (eval.clj `of-agent`, transcript, problems, fault, web) | `run` is the only path eval→agent today |
| source, ns, comment, origin | repl, transcript, system-turn | keep |
| author | **none outside turn.clj** (`latest-evaluations` 1828, `evaluation-terminal-data` 3383) | on default always `:system`; agent/system distinction can be `origin` |
| shown, error, diagnostics, output, interrupted-at | repl, transcript, sci/eval, drive | keep |
| ending-ns | repl, transcript, sci/eval; `previous-ending-namespace` | IS the agent's current namespace |
| duration-ms, allocated-bytes | repl, status, kernel, transcript | keep |
| acquisition-ms | **no reader found** (maybe generic via `:seon.config.eval/bounds`, UNVERIFIED) | flag |
| value-tokens, shown-tokens, elisions, value-digest | sci/eval.clj only (producer; render reads UNVERIFIED) | flag |
| value-class, unstored, full, replaced, print length/level, renderer, renderer-fn | repl/transcript/sci.eval | keep |
| read-evidence (23,647 refs), read-basis-transaction | eval.clj, web, sci/eval, transcript; `system-plan`, `read-only-evaluation?` | exists for system-turn refresh and render cache |
| executed (7,509), code-evidence | **only turn.clj `code-currency`/`code-current?`** | system-turn only |
| call-count, repeated-calls | repl.clj | keep (shown) |
| faults | transcript only | 1 live datom |
| `:seon.problems/id`, `:seon.test.accretion/*` | problems/test | UNVERIFIED writer path |

### 2d. Provider attempt `:seon.ai/attempt` (seon.ai.attempt.edn; UNVERIFIED file read)

Writer `record-attempt!` (turn.clj:4084). Readers: ai.clj, status, prompt (calibration from usage),
drive, transcript. 0 live. Ref'd from turn via `:seon.turn/attempts`. Natural home: agent
`:seon.agent/last-call` (or a cardinality-many on the agent if history is wanted).
`:seon.ai.attempt/settings-edn` duplicates `:settings` ref + config — BK.

### 2e. Context capture `:seon.context.capture/*` + contributions

Writer `context/capture-tx` (context.clj:359). Readers: prompt.clj `capture-mismatch`
(prompt.clj:336, replay only), transcript. 0 live; the contribution attributes are **not
installed** in default's schema (`(filter #(= "seon.context.contribution" (namespace %)) …)` → `()`),
so the first real model turn's capture would refuse (UNVERIFIED: whether boot installs them under
another name). Stores the exact prompt text per call — a second copy of what the evaluation
history + frame reconstruct.

### 2f. Other writes in the loop

- Messages: `message/delivery` rows (completed reply, problem assignment); error facts
  (`error/recording`); faults (`fault/fault!`) — several from BND paths.
- `:seon.ai.model/*` observation (record-attempt!) — BK.
- Profile measurement datoms (`measurement/significant-tx`) — BK.
- Program entities (`program-facts-call`) — CORE.
- Blobs (reply, cut values, reasoning) — CORE.

## 3. Kinds of turn that exist

| kind | how it is recognised | opened by | why (introduced) |
|---|---|---|---|
| **Ordinary model turn** | situation `:call`, has `:attempts` | `open-turn` → `open-call` | episode dial + per-agent derivation, d1ec4a019 (2026-07-28) |
| **System turn** (context refresh) | no situation, opened and closed in one tx, author `:system` | `system-turn {:write? true}` from `open-turn` | "Compute system turns from retained read evidence", a5f453368 (2026-09-08): re-run the agent's read forms whose evidence changed so the next prompt shows current values without replaying |
| **Generated opening** | situation `:generate` | `bootstrap/seed-tx` → `generated-run-tx` | "Append generated opening forms after settled prefixes", 53ee9c472 (2026-08-12); root's first task |
| **Submitted source run** (MCP `eval_clj`, `evaluate-source!`) | situation `:call`, no attempts, reply-size set at open | `submit-source!` (agent.clj:665) → request port → `system-run-plan` | "Run submitted source through durable agent episodes", 671657d51 (2026-09-06): outside agents use the same path |
| **Virtual turn** | same as submitted | `virtual-turn!` (turn.clj:2299) — web button + fixtures | f3bd1d58c (2026-09-08): fixture replies without a provider |
| **Intent-frozen / resumed turn** | open, reply-size set, rows without terminal facts | crash between `plan-call` and settlement | the "ONE INTENT COMMIT" design (turn.clj:4448-4452); now always closed by boot `recover-call` or `close-escaped-run!`, so `:resume`/`:close` from `next-agent-work` serve only generated/submitted shapes and races |
| **No-provider turn** | `:seon.config.ai/no-provider` | `call-turn` freezes "" | default runs no-provider (memory: NO PAID AGENT CALLS) |
| **Compacted** | evaluations retracted, turns kept | `compact!` (turn.clj:2286) | f3bd1d58c |

Note: answeredness (`latest-answering-turn-t`, turn.clj:2924) deliberately excludes submitted and
system turns (their plan freezes in the opening tx), so 13 messages on default stay answered only
by ordinary or virtual turns.

## 4. What the prompt is built from today

1. Pass A writes a system turn (§1e step 16): the declared opening (render functions of the agent's
   one-hop units, e.g. identity, situation `(help)`, inbox) evaluated as forms, plus every retained
   read-only form whose read evidence or executed code changed — stored as evaluations; unchanged
   ones only refresh evidence. Changed reads store a `{:seon.repl/changes diff}` against their
   previous shown value, not the full value (turn.clj:2193-2205).
2. Pass A opens the real turn; its opening tx `T` fixes the basis.
3. Pass B: `prompt` (prompt.clj:422) → `effective-ai-settings` → `model-calibration` (chars/token
   from past attempts' usage) → `acquire-context-report` (prompt.clj:367).
4. `render/acquire-context!` (src/seon/render.clj:1724) takes `opening-db` (as-of `T`) →
   `render.web/derive-context!` (src/seon/render/web.clj:2461): cached by read evidence; otherwise
   `render.walk/history` (src/seon/render/walk.clj:841): **every evaluation of the agent**
   (`seon.eval/of-agent`, src/seon/eval.clj:17, ordered by turn tx then ordinal), excluding the
   current turn's own rows (unless `:generate`), each rendered once through the evaluation
   schema's AI pair (`seon.repl/render-ai`) → one history unit each.
5. `select` (prompt.clj:302): newest whole units under `:seon.config.ai/prompt-token-budget`,
   with one elision naming the dropped count and the requery `(seon.eval/of-agent (seon.db/db) id)`.
6. `compose` + `repl/frame` (src/seon/repl.clj:56): appends `"turns left: N of M"` (reads
   `episode-runs` again).
7. `capture-mismatch` only for a replay. Contributions are priced per segment for the capture.
8. The text is the whole prompt (no system prompt/messages structure in this path; how
   `ai/complete` frames it into a provider request is UNVERIFIED — `src/seon/ai.clj` not read).

So the context is already "the agent's evaluations, in order, rendered, newest that fit" — the
system turn exists to append fresh read results into that sequence before each call.

## 5. Lines of code per concern (approximate, from form lengths)

turn.clj (6055):

| concern | ~LOC | main forms |
|---|---|---|
| Core path | ~2,250 | step core (~120), turn 76, open-turn 63, call-turn 267, resume-turn 184, evaluate-sources+rows ~330, settle-batch! 107, evaluation-terminal-data 116, evaluation-facts 92, settle! 67, plan-call/plan-tx/source-rows/open-call/close-call ~200, receipts ~200, next-agent-work+wakes ~170, record-attempt!+attempt-* ~260, segmenting 56, delivery/disposition ~100 |
| Recovery / refusal arms | ~600 | recover-*, interrupt-stamps, close-escaped-run!, open-run-tx-call, settle-batch-refusal! 112, refusal-terminal-data 54, settle-evaluated! 50, deletion-proposals 63, fold-* 48, next-ordinal 39, close-turn 22, warnings 59 |
| Bounds / permits / backstops / parking | ~560 | await-turn-permit! 50, arm-turn-completion-backstop! 58, write-refusal-* 58, turn-completion-* 42, episode-exhausted! 39, agent-dial/dial-absent 32, outside-wake-t/spent-runs/episode-runs/max/turns-left/opening-deferred?/deferred-triggers ~170, step park/submission ~60 |
| System / generated turns | ~1,150 | system-turn 209, declared-sources 69, latest-evaluations 59, system-plan 51, generated-read-fault 72, read-only-evaluation? 30, code-currency 24, unit-read 33, record-evaluated-call/tx 128, row-tx + declared-* ~200, generate-turn 122, append/generated ~70, preview-sources 38, compact 45 |
| Outside-agent submission | ~200 | system-run-plan/call/tx, submission-open-tx, system-run-if-admitted, with-submission, virtual-turn!, code-sources |
| Bookkeeping / presentation | ~500 | problems routing (problem-id … plan-settlement) ~240, render-ai/html ~130, committed-attributes 52, opening-db 38, fault-window 34, next-id 15 |

Other loop files: cluster/agent.clj 1755 (graph, arm/armer, submit, disarm ≈ half lifecycle/bounds),
cluster/wake.clj 578, cluster/message.clj 918 (delivery + inbox renders), cluster/prompt.clj 460,
context.clj 420 (capture/selection), batch.clj 725 + turn/batch.clj 931 + turn/world.clj 154 (the
fold — CORE, shared with MCP/tests), ai.clj 1331 (provider — CORE, not read), render/walk history +
web derive-context ~150 (CORE context). Total loop surface ≈ 13,300 lines; the turn/run model
itself (turn.clj minus fold/attempt core) ≈ 3,800.

## 6. Sketch of the minimal loop

### Agent entity would hold
- `:seon.agent/id`, `:namespace` (assignment), `:settings`, `:cluster`, `:branch` (exist).
- `:seon.agent/evaluations` — ordered (eval → agent ref + ordinal/tx; the evaluation entity stays,
  minus `run`). Each eval: source, comment, ns, ending-ns, shown/error/output, timing, read
  evidence, `origin` (model reply | system read | outside agent | test).
- `:seon.agent/current-ns` — or derived: last evaluation's `ending-ns`.
- `:seon.agent/answered-t` — the basis of the last context the agent saw (replaces
  `latest-answering-turn-t` and `:seon.turn/trigger`/`handled`), or derived from the last provider call.
- `:seon.agent/last-call` — the provider attempt (usage, model, finish, error ref); prompt text as
  a blob only if replay must be exact.
- `:seon.agent/disposition` (+ result) — done/wait, cleared by a new outside wake; or derived from
  the last evaluation's value.
- `:seon.agent/task` (exists as `:seon.task/of-agent` back-ref).

### Loop
wake → derive `(unanswered wakes since answered-t) or (continuing and not done)` and episode
budget → build context (render agent's evaluations + fresh reads) → provider call → record call
→ evaluate reply forms → ONE transaction: evaluations + program facts + deliveries + call +
answered-t/disposition → rewake.

### Each mechanism becomes

| mechanism | fate |
|---|---|
| turn entity, `next-id`, open/close/situation, `open-for-agent` busy fence | delete; one pass is synchronous in its proc; busy = proc running |
| intent commit (`plan-call` freeze before eval), `:resume`/`:close`, `next-ordinal`, `fold-*` | delete (CLAUDE.md "Recover facts, not execution": a crash loses the unanswered run) — owner question 1 |
| `recover-call`, `close-escaped-run!`, `record-interruption!` | delete with open turns (nothing open to close) |
| system turn as a separate turn | keep the *refresh* idea, store its reads as evaluations with origin `:system` in the same settlement — or render reads at context time without storing (question 3) |
| generated `:generate` turn | becomes the first refresh (declared opening) — delete situation |
| submitted/virtual runs | same pass with origin `:outside`; reply port kept |
| `:seon.turn/trigger`, `:seon.runtime/trigger`, `:seon.turn/handled` | delete; answeredness = agent `answered-t` |
| `reply`/`reply-blob`/`reply-size` | reply text as the evaluations' sources + comments (already); keep a blob only if raw bytes matter (question 4) |
| `:seon.turn/attempts` + attempt rows | agent `last-call` (or attempts ref'd from agent) |
| context capture + contributions | delete or keep as a blob on the call (question 5); contributions not installed on default |
| episode bound (`outside-wake-t`, `spent-runs`, deferred) | keep as one counter derived from calls since last outside wake (question 6) |
| completion backstop, permit, write-refusal park | keep one bound (provider timeout + eval limit already bound work); question 7 |
| `:seon.cluster.eval/author` | fold into `origin` |
| `executed`/`code-evidence` | keep only if stored-read refresh stays |
| problems routing, fault windows, measurement | unchanged consumers of evaluations; move off turn refs |

### Open questions for the owner
1. May a crash mid-evaluation lose the model reply entirely (no intent rows), given the reply is
   paid? Or store the reply text on the agent before evaluating (one extra small write)?
2. Do evaluations stay their own entities (ref → agent) or become a component list on the agent?
   (component validation re-validates the whole owning value on each write — CLAUDE.md "size trees
   by what each write owns" — argues for ref → agent.)
3. Fresh reads before a call: re-evaluate and **store** (today) or **render live** at context time
   and store nothing until the agent evaluates?
4. Keep the raw reply bytes (prose included) anywhere, or only per-form source + comment?
5. Keep the exact prompt per provider call (replay/audit, `capture-mismatch`) or rely on
   reconstruction from evaluations as-of a basis?
6. Episode bound: keep "N calls since the last outside wake", or a simpler per-wake call cap?
7. Which of the four bounds survive: completion backstop, write-refusal park, episode cap,
   provider timeout?
8. Do MCP outside-agent runs advance the agent's answered-t (today submissions never answer wakes)?
9. Does history ordering need a turn grouping at all (render "reply N" headers), or is
   evaluation order + origin enough?

UNVERIFIED (not read): src/seon/ai.clj internals, seon.problems, seon.eval.drive, seon.schedule,
render/transcript renders, `row-tx` (turn.clj:1057) detail, `seon.batch` stage internals beyond
names, docs/prds/next-turn/ (untracked, present in tree).
