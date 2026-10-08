---
title: Agent-loop split, reconciled plan (S1, S3-S7)
type: research
status: superseded 2026-10-08 by the agent-loop ledger
date: 2026-10-08
lane: split-reconcile
---

> **Superseded 2026-10-08.** The one ordered slice list is now [the agent-loop ledger](../../../../seon/schedule/agent-loop-ledger.md); the laws and the folded Q answers are in [agent-loop.md](../../../../seon/specs/agent-loop.md). This file is dated evidence: its row ids (S1, S3-S7, PB, CS, AR, Q1-Q18) survive only as ledger ids or aliases, and its "ready" tiers, Q3 input-pass text and S5-04/Q7 rows are stale. Do not launch from it.

# Agent-loop split: reconciled plan

This plan merges two independent splits of the approved spec (`docs/seon/specs/agent-loop.md`):
`spec-split-codex-split.md` (Astra) and `spec-split-sol-split.md` (Sol 6.1). They share the same skeleton: Sol revised
Astra's text and split it further. This plan keeps their row ids. Where they differ, it says which version it took and
why, and it answers every open question with a recommendation checked against HEAD **39c976b70**. Anchors marked
"(HEAD)" were re-read with `git show HEAD:` in this lane. The packets' other anchors come from `a4d438dc6`. Re-derive
them before you assign a row. The packets' budget codes (L, O, H, I, T, D, F) and assignment contract apply unchanged.

The pass-budget rows PB-01..PB-03 (group C, §3) come from the model-pass profile
(`docs/research/agent-platform/2026-10-08/model-pass/profile.md`, `81020da6a`) and lane `pass-uncovered`. They cover
the owners no S-row removed. The owner's budget (2026-10-08) is **20–40 MB of transient allocation per ordinary model
pass**, excluding the provider call. More is tolerated when it is garbage, but **never GB per pass**, and retained heap
per agent and per cluster base stays small. A pass never constructs a base context. The cluster has one shared base,
built up incrementally as its program changes. Each agent forks it once and advances in place.

Already landed: S1-01 (`f50ef6059`) and S1-02 (`438328b34`). S3-01 is in flight as lane `s3-01`, which holds
`turn.clj`, `turn/batch.clj`, the eval schema and twelve test files, including `test/seon/turn_test.clj` and
`test/seon/mcp_entrance_test.clj`. Lane `defn-cost2` holds `db.clj`, `program.cljc`, `call_preparation.clj`,
`db_test.clj` and `schema_test.clj`. Rows that need those files queue behind the two lanes.

## Evidence gathered in this lane (broken first)

- **A model pass rebuilds a whole base SCI context on ordinary writes (GB class). Measured at HEAD `81020da6a`.** The
  measurement used a throwaway root `tmp/pass-uncovered/root` (pid 56337), not default. The drivers were
  `seon.pass-probe` windows and MCP passes, and the forms are committed in `docs/research/agent-platform/2026-10-08/model-pass/base-miss-probes.clj` (`p2`–`p5`). A
  construction shows as `seon.sci.eval/base-ctx` in the window's armed stages and as a new key in the base cache.
  - **Model agent**, fixture provider, six passes. Pass 1 builds a base (**872 MB / 584 ms**; base-ctx 123 ms). Passes 2
    and 3 build none (501 and 542 MB). The `defn` pass builds none (839 MB). The pass **after** the `defn` builds one
    (**1,360 MB / 829 ms**; base-ctx 224 ms). The next ordinary pass builds none (701 MB).
  - **MCP SCI agent.** The first pass on a new agent builds one (648 MB / 402 ms; base-ctx 216 ms). Repeat passes build
    none (34–42 MB / 45–64 ms; fork-for-turn 6 ms). The pass after `(my.agent/settings! {:seon.config.run/max-episode-runs 3})`
    builds one (318 MB / 228 ms; base-ctx 165 ms). The pass after a one-line `defn` builds one (519 MB / 301 ms; base-ctx
    247 ms).
  - **Three causes, all in the base key, not in eviction.** The key is `base-ctx-key` (HEAD `src/seon/sci/eval.clj:3026`),
    built from `program-identity` → `revision-basis` (`:2930`).
    1. The basis carries `:datahike.cache/connection-id` and `/generation`, so each agent branch has its own base. All
       four cached keys named an agent branch connection (`[uuid :agent-…]`). A new agent's first pass therefore
       rebuilds.
    2. `acquisition-attributes` (`:2912`) includes `config/dial-attributes`. One agent's settings write moves
       `:seon.config.ai/*` and `:seon.config.run/*` revisions and invalidates a program it does not change.
    3. Every program attribute revision is in the key. One `defn` moves `:seon.fn/*`, `:seon.ns/*` and
       `:seon.program/*`, and the next `acquire!` re-derives the whole program: `load-core-namespaces!`, `build-base-ctx`,
       and `acquire-program!` over every function and namespace row (`:2352-2500`). Nothing is incremental.
  - **Where it fires.** The miss lands in the refresh's `acquire-context!` today. After S4-06 deletes the refresh, it lands
    in `resume-turn`'s `acquire!` (commit `37d11f6a0`, HEAD `src/seon/turn.clj:5153`). That `acquire!` itself is right:
    it is a no-op (0 ms) when the identity is unchanged. The defect is what the identity contains.
  - **The cache breaks the cache law.** `base-context-cache` is a `clojure.core.cache` LRU of 4 (`eval.clj:2908-2910`), not a
    `seon.cache` kind. More than four live branches thrash it, and its misses are not observable in `seon.cache/status`.
  - **Retained heap is small; the cost is transient.** After three full GCs, the throwaway root held **333 MB** live with
    four cached bases. Eight new agents, each with one def, added **9 MB** (about **1.2 MB per agent**). Evicting all
    four bases freed **8 MB** (about **2 MB per base** beyond the structure it shares with the cluster). The GB-class
    problem is per-pass allocation, not retention.
  - Existing task `seon.sci.eval/acquisition-rebuilds-the-whole-program` owns this class. PB-01..PB-03 below are its
    slices.
- **Other observations from this lane.** The throwaway boot took **73.4 s**, 70 s of it in the `:seon.source/current`
  layer. That is existing issue `docs/seon/issues/from-zero-boot-takes-minutes.md`. Default reports
  `seon.schema/call-with-projection-state` max **1,346,204 ms**, under existing tasks `d27179bab996` and
  `c4ece4f4eec9`.

- **Default is behind HEAD.** `bin/seon status` reports pid 89251, source root `a4d438dc6`, health `:faulted`. The
  open fault is `seon.turn.world/record-fault`: a branch-custody write refusal, 3 occurrences, task `3d2fb1b41dc8`.
  There are four over-bound warnings. `seon.db/call-with-custody` has a max of 2,986 ms (tasks `5a8b119ea14a`,
  `3d651884784e`), `seon.cluster/adopt-entities!` 5,624 ms and `seon.fn/index!` 2,813 ms. All of these already have
  tasks. **RESET NEEDED: no.**
- **Q17 probe: a world member is supplied to a host function called from SCI.** I ran
  `(keys (seon.turn.batch/namespace-entity 'user))` through MCP SCI as root. That function declares
  `[:cat :seon.sci.eval/ctx :symbol]` (HEAD `src/seon/turn/batch.clj:59-64`) and the call omits the ctx. It answered
  the namespace keys in **3 ms** with **20 armed calls** and no diagnostic (`result/e3ec7d7102288`). The armed wrapper
  filled the omitted ctx from the evaluation's world. `:seon.sci.eval/ctx` is a declared world member at HEAD
  (`src/seon/batch.clj:47-61`), and supply runs through `src/seon/call_preparation.clj:824` and `:858`. This settles
  Q17 without an ambient lookup.
- **Codex S1-04 has a false caller claim.** `directory-value` does not call `function-doc-map` at HEAD. It calls
  `program-documentation` and `arglists-reader` (`src/seon/sci/eval.clj:2019-2054`). `function-doc-map`'s only external
  caller is `documentation-value:2071`. Sol is right.
- **`program-documentation-selector` must survive S1-07.** `documentation-value` reads it at
  `src/seon/sci/eval.clj:2062`. `test/seon/db_test.clj:148,186` also reads it in
  `seon.db-test/supplied-database-pulls-carry-the-projection-across-unbound-reads` and
  `seon.db-test/uncarried-read-reports-one-projection-fallback-per-call`. Codex's plan to delete it is wrong.
- **Neither packet lists these direct JVM test callers:**
  - `test/seon/render/value_test.clj:64,77` calls `directory-value` and `repl/render-directory-ai` in
    `seon.render.value-test/block-pairs-remain-explicit`.
  - `value_test.clj:607` calls `documentation-value` with three arguments in
    `seon.render.value-test/documentation-body-is-whole-or-one-executable-elision`.
  - `test/seon/sci/documentation_test.clj:25-26` calls both functions in
    `seon.sci.documentation-test/a-named-private-override-documents-the-jvm-boundary`.

  Outside an evaluation these callers have no world, so `supply-from-world` refuses. They must pass the ctx
  explicitly. S1-04 and S1-07a below now own these test files.
- **The stored namespace source of a runtime namespace is built with `pr-str`.** It is
  `(pr-str (runtime-namespace-form …))` at `src/seon/fn.clj:996`, augmented with interpreter refers at `:999`. That
  synthetic text becomes `:seon.ns/source` through `exact-source` (`fn.clj:165`, `namespace-entity:201`). This is the
  producer behind Q1, and it is a printer-law finding. S1-03 owns the file and fixes the producer (see S1-03).
- **The SCI `clojure.test` adaptations have no `run-test-var`.** `agent-test-namespace` (`src/seon/sci/eval.clj:318`)
  adapts `test-all-vars`, `test-ns`, `run-tests` and `use-fixtures` only. Task `run-test-var-throws-on-an-sci-test-var`
  (`resources/seon/tasks/seon.sci.eval.edn:213`) asks that run-test-var "runs one SCI test var and answers its report
  as data", proved by pass counts. That settles Q15's shape.
- **A held-context roster exists.** Routing is one atom per execution holding `:seon.agent/contexts`
  (`src/seon/cluster/agent.clj:615-621`). `acquire-context!` (`:1022`) reserves and settles entries in it. That
  answers Q9.
- **Pending writes and the blob permit.** On timeout, `transact-call` returns while the Datahike `pending` is still
  outstanding (`src/seon/db.clj:5728`). `with-publication!` releases its permit in `finally`
  (`src/seon/blob.clj:323`). This is Q5's defect.
- **The call row exists.** The turn entity holds `:seon.turn/reply` (`src/seon/turn.clj:81` contract), `/attempts`,
  `/disposition`, `/reply-blob` and `/text`, and every evaluation refs it through `:seon.cluster.eval/run`. This is the
  "call row" that spec §4.1 RECORD already names. It answers Q2.
- **Effect provenance.** `evaluation-eid` (`src/seon/effect.clj:210`) derives the evaluation from
  `:seon.effect/run` (a ref to the open run) plus `:seon.effect/form-ordinal`. `open-call:227` then stores the
  `:seon.effect/eval` ref. This is Q10's seam.
- **Skills have stale claims.** `.agents/skills/repl/SKILL.md:46-49` cites `fork-for-turn :3320` and
  `regenerate-agent-context! :3246`, but at HEAD they are at `eval.clj:2813` and `:2703`. `:56` says an uncontracted
  `defn` is refused, which contradicts CLAUDE.md's REPL-admits-with-warning law. `:68,85` describe the system turn.
  `.agents/skills/seon-flow-architecture/SKILL.md:60-61` cites `program-cache-policy :3355` (HEAD `eval.clj:2866`),
  and `:94` cites the three-write turn. CLAUDE.md calls a stale skill a high-priority defect. Neither packet scheduled
  this fix, so the plan adds slice X-01.

---

## 1. Ready slices

### R0: ready now, no open question, no held file

| Id | One line | Group |
|---|---|---|
| S5-03a–j | Ten admission design proofs, one case per lane (notes only, disposable SCI branch) | F (parallel, disjoint notes) |
| X-01 | Correct the REPL and Flow skills' current claims against HEAD; delete what cannot be verified | G |
| PB-01 | Must-fix (GB class): one shared cluster base, keyed by loaded-program content in `seon.cache`; the agent's branch overrides are an overlay on its fork. Ready once `eval.clj` is free (held by `repl-p11`) | C (first unstarted C row) |
| I-Q4 | Inventory of the prompt, walk and capture caller closure, web/debug included, with each survivor's data entrance | H (notes) |
| I-Q10 | Inventory of the `*open*`, `open-tx`, `recover-tx`, effect, test-attempt and boot recovery caller closure | H (notes) |
| I-Q14 | Inventory of the disarm/join/backstop proofs, plus the caller deadline path from MCP to `stop-graph!` | H (notes) |

The three inventories are design-only notes, like S5-03. They turn the "exact caller closure" parts of Q4, Q10 and Q14
into data, so S4-07a/b, S6-04a/b and S6-07 can be sized.

### R1: ready once root accepts the ROOT answer below (evidence attached, files free)

| Id | One line | Blocking answer |
|---|---|---|
| S1-04 | `doc` reads SCI Var metadata and classifies authored references. `documentation-value` declares a supplied `:seon.sci.eval/ctx` input | Q17 (probe above) |
| S1-03 | The stored runtime namespace keeps only authored refers and source. The analysis carries the synthetic refers and unaugmented form | Q1 |
| S7-01 | SCI `clojure.test/run-test-var` adapter beside `run-tests`, returning summary counters as data | Q15 |

S1-04 and S7-01 both edit `src/seon/sci/eval.clj` and `test/seon/sci/documentation_test.clj`, so they run in series.
S1-04 is on the critical path and goes first.

### R2: specified, waiting only for a file held by a live lane

| Id | Waits for | Then needs |
|---|---|---|
| S4-01 | `s3-01` (turn.clj, eval schema, turn_test, mcp_entrance_test) and `defn-cost2` (db.clj) | nothing: S3 v2 §6 fully specifies the ordinal writer and read |
| S5-06 | `s3-01` (turn/batch.clj, mcp_entrance_test) | Q9 (ROOT, answered) |
| S6-01 | `s3-01` if the producer conversion touches turn.clj (I-Q10 decides) | Q10 key choice (ROOT, answered) |
| S1-10 | S1-09, then `s3-01` (test/seon/turn_test.clj) | none |

---

## 2. Open questions, merged

The packets' Q1–Q16 are identical text, and Sol adds Q17. They are merged here with the extra sub-questions found in
the S4 and S6 rows. **ROOT** means an implementation choice within the approved spec. **OWNER** means the choice changes
or settles approved semantics.

### Q1: Where does the analysis carry the authored namespace source? (S1-03) **ROOT**

- **Decision.** A runtime namespace's stored `:seon.ns/refers` and `:seon.ns/source` must exclude the interpreter refers
  that `runtime-analysis-batch` adds for analysis (`fn.clj:996-999`). An explicitly authored refer must survive.
- **Both packets.** Keep the source by span in the existing analysis value. Reject subtracting refers that look equal.
  They name no key.
- **Simplest.** Build the analysis form from the augmented entity, as today. Build the stored form from the unaugmented
  entity.
- **Recommendation.** `runtime-analysis-batch`'s result map gains one key, `:seon.fn/runtime-namespaces`. It maps each
  namespace name to `{:seon.ns/source <unaugmented form text> :seon.fn/synthetic-refers #{local …}}`. The synthetic
  refers are the interpreter refers *added*, meaning those not already in the stored entity's refers, so an authored
  `[clojure.repl :refer [doc]]` is never listed. `namespace-entity` (`fn.clj:201`) removes exactly those locals and
  uses that source when its context carries them. Static file analysis carries none, so it is unchanged.
- **Grounds.** This fixes the producer with no new stored schema. CLAUDE.md "Fix the producer" and "Declare facts at
  their producer". The same slice replaces the `pr-str` at `:996` with the value printer's code face. If that printer
  face cannot print a form as loadable code within the slice, the lane returns that as a task naming
  `seon.fn/runtime-analysis-batch` (CLAUDE.md "exactly ONE printer").

### Q2: What is the durable exchange without an evaluation? Where is the assignment boundary? (S4-01..04, S6-03) **ROOT**

- **Decision.** A prose-only reply records "one exchange with the 'no form' refusal and no evaluation" (spec §4.3).
  History needs that exchange and the assignment it belongs to. (The opening's completion fact is gone with the
  opening: owner ruling 2026-10-08, spec §4.4, §7.12.)
- **Both packets.** Extend the existing pass/call record. Do not add a second transcript store or a fake evaluation.
  Both leave it undecided.
- **Simplest.** The call row RECORD already writes (§4.1) is the exchange.
- **Recommendation.**
  - The pass's call row, today's `:seon.turn/*` entity with `:seon.turn/reply` and `/disposition` (`turn.clj:81`), is
    the exchange. It carries origin and an assignment ref. A prose-only exchange is a call row with zero evaluations
    and its "no form" diagnostic.
  - Evaluations keep `:seon.cluster.eval/run` to it.
  - History acquisition reads the agent's call rows for the active assignment with origin `:model`, each with its
    evaluations by position. There is no opening or input origin: the task, its proofs and unread items are the
    current-state block, rendered at each call and never stored (spec §4.4).
  - Agent ordinals stay on evaluations, as S3 v2 §6 decided. A pass is one transaction on one linear branch, so
    exchange order is commit order.
  - S6 deletes the open/intent machinery, not this row.
- **Grounds.** No new entity kind ("no invented entity kinds"). The spec already requires a call row. Datahike owns
  transaction order.

### Q2b: How does the current-state block show the must-reads? (CS-01) **OWNER** (superseded the opening question)

- **Decision.** The owner ruled 2026-10-08 that the task and its proofs are current state (spec §4.4). Must-reads
  reflect current code, so they are current state too; the question is only how much of each the block shows.
- **Simplest.** Each must-read's arglists, contract and first docstring line, with `(doc sym)` and
  `(clojure.repl/source sym)` as operations.
- **Recommendation.** The simplest form. The block is never prefix-cached (it follows the history), so whole docs on
  every call cost uncached tokens; an agent that needs more runs `doc` or `source`, which lands in its own history.
  S8 measures whether agents miss them.

### Q3: How do unread items reach the agent, and how are delivery identities recorded? (S4-05, CS-04) **ROOT**

**Revised 2026-10-08 (owner rulings, spec §4.4): there is no input pass and no generated input form.** Messages and
errors delivered to the agent are shown in the current-state block until delivered; proof results are current state
(status recomputed at each call) and need no delivery. The block's shown unread items are delivered by the model pass
whose context presented them: the pass's settlement carries their identities, the writer checks each still exists and
is not already delivered to this agent, and stores them as `:seon.cluster.eval/delivered` on that pass's call row in
the pass's one transaction. A crash or failed provider call delivers nothing, so they show again. `my.agent/inputs`
survives only as the agent's own lookup and elision form. What stays durable: the delivered identities, so a restart
never drops or re-shows a delivered item. The text below is the earlier input-pass recommendation, kept for its
grounds; its form and pass are retired.

- **Decision.** Spec §4.4 leaves the form's name TBD. It requires recorded identities, per-home bases, and no
  comparison of t across homes.
- **Both packets.** Reuse one recorded pass with an exact identity set.
- **Simplest.** The form is one call to a `my.*` input function. The writer validates and stores the identities it
  returned.
- **Recommendation.**
  - The generated form is `(my.agent/inputs)` (new, in `src/my/agent.clj`). With custody supplied, it returns the
    undelivered messages, errors and proof results, each with its identity and the home basis it was read at.
  - The pass's settlement request carries those identities. The writer checks that each still exists and is not
    already delivered to this agent, then stores them as one cardinality-many ref, `:seon.cluster.eval/delivered`, on
    that evaluation.
  - "Undelivered" is the identities with no such ref from this agent, read at each home.
  - With zero items there is no input pass: work is derived only when something is undelivered.
- **Grounds.** Identities come from their owners. The delivered set is a fact named at its producer, so a restart never
  redelivers. No high-water t.

### Q4: Which web and debug callers of prompt, walk and capture survive? (S4-04, S4-07) **ROOT**

- **Decision.** The exact caller closure, measured by I-Q4 at HEAD `cd8a481c2`.
- **Both packets.** Keep HTML over saved exchanges. Delete the AI traversal after its last caller.
- **Closure (I-Q4).**
  - `render.clj:1721` is `acquire-context!`, a router to `web/derive-context!:2459`, not a walk entry. `walk/history:840`
    has one non-test caller (`web.clj:2497`). `prompt/prompt:403` has two (`turn.clj:4554` and `web.clj:3188`
    `sent-context`).
  - Capture rows are written only by `context/capture-tx:359` (`turn.clj:4568`, delay `:65-66`). Their readers are
    `prompt.clj:74-104 calibration-for`, `capture-mismatch:318` and `committed-attributes` (`turn.clj:3124`). No page,
    MCP tool or transcript reads a stored capture.
  - `walk/neighborhood`, `root-acquisition`, `graph`, `related`, `with-related` and `continue` serve pages and value
    paging and survive. `repl/frame` and `transcript.clj` `history` survive.
- **Rulings.**
  - **Calibration.** `:seon.ai.tokens/characters` moves onto the model call's attempt or basis row before the capture is
    deleted. `calibration-for` reads it there, so `seon.cluster.prompt-test/calibration-uses-the-agents-recent-attempts-and-config-prior`
    stays green (and `an-unread-usage-history-is-not-an-empty-one`, whose helper `recorded-usage-tx` stops writing capture rows).
  - **Debug Context view (`?sent=true`, `web.clj:3185-3207`).** It renders the saved exchanges through the SAME pure
    function the proc uses to build its transcript at acquisition. There is no second assembler, and the web never reads
    the proc's memory.
  - **Context-action POST (`web.clj:3390`).** It is deleted with the system turn, virtual turn and compact machinery S4
    removes (`change-context:2427`, `render.clj:1731-1733`). Re-route only what survives.
  - **Agent-owned selection and compaction (`context.clj:76-250`: `selection`, `append-tx`, `compact-tx`).** No non-test
    callers. They are deleted in S4-07b with the contribution schema (`seon.context.contribution.edn`, the `contribution`
    map in `seon.context.edn`, `:seon.context/contributions` on `rendered-context`), their tests and the capture.
    `transcript.clj:617` reads `:seon.context.contribution/evaluations` and converts in the same commit.
  - **`message-custody`.** It survives. It moves out of the capture section of `context.clj`, because
    `transcript.clj:243` uses it (`test/seon/context_test.clj` `message-custody-accepts-an-absent-run-when-instrumented`).
- **Recommendation.** Close Q4 on these rulings and size S4-07a/b from I-Q4 sections 5 and 6.

### Q5: Who owns a pending write and the blob permit until the write's actual outcome? (S5-01) **ROOT**

- **Decision.** Today the outcome is lost on timeout (`db.clj:5728`) and the permit is released in `finally`
  (`blob.clj:323`). Spec §4.6 says an unknown outcome is owned until known.
- **Both packets.** Pick one live owner, or price a blocking owner wait as the simpler option.
- **Simplest.** A blocking owner wait.
- **Recommendation.**
  - On timeout, `transact-call` returns its declared result plus a runtime `:seon.db/pending`, the Datahike promise,
    which is never persisted.
  - `with-publication!` derefs that pending to its terminal outcome before its `finally` releases the permit.
  - The owner is the thread already running the pass, which is the agent's proc. The agent is unavailable until the
    outcome is known, as §4.6 requires.
  - The MCP caller's own deadline still answers "still running" (§4.8).
  - A definite refusal returns at once. An unknown outcome is never retried.
- **Grounds.** No new roster or atom (CLAUDE.md "Atoms are a smell"). It awaits the actual exit ("Bound admission and
  await actual exit").

### Q6: What does live health show when a digest was not observed since boot? (S5-02) **ROOT**

- **Decision.** The owner already ruled that health reads the live profile (§7.2). What remains is the shape for
  "unobserved".
- **Both packets.** A typed unknown.
- **Recommendation.** `definition-comparisons` takes the live profile snapshot as an explicit argument. A missing
  parent or current observation is a typed unknown member of `seon.health/regressions`, never a stale stored comparison
  and never nil. Boot comparison records stay. CLAUDE.md says "missing evidence is a typed unknown".

### Q7: Which admission seam allows one end-of-pass analysis? (S5-04) **OWNER**

- **Decision.** Can later forms in the same pass see earlier forms' facts without per-form analysis?
  `apply-pending` provides that today (`batch.clj:513`).
- **Both packets.** Decide only after all ten S5-03 cases. One bounded early admission at a real dependency boundary is
  simpler than a speculative registry.
- **Recommendation.** Root assembles the ten case notes into one seam. If any case needs analysis before a later form,
  the design states its measured cost (§4.1). Spec §5 makes S5 a core slice whose design the owner approves. Price
  S5-04 into sub-items only after that approval.

### Q8: Which errors-cut version lands with observed-occurrence settlement? (S5-05) **ROOT**

- **Recommendation.** Keep the installed constructor and `record-fault` receipt (`turn/world.clj:136`) until the errors
  cut converts them. S5-05 passes the pass's observed occurrence identities only. Settlement then reads no window and
  does no problem-assignment routing. S5-05 is sequenced after the errors spec's own S-reads slice. Loop code never
  defines error schemas.

### Q9: Which read supplies an already acquired SCI context without acquiring one? (S5-06) **ROOT**

- **Recommendation.** A new contracted read beside `acquire-context!` in `src/seon/cluster/agent.clj`:
  `held-context [routing agent-id branch] -> ctx | absent-context error`. It reads the settled
  `:seon.agent/contexts` entry (`agent.clj:615-621`) under the same key `acquire-context!` uses, and never reserves. An
  entry still `::acquiring`, or no entry, is absent: the JVM handle then answers gone-value with its class. There is
  one roster, so there is no second one (CLAUDE.md "exactly one roster per scope").
- **Proof fixture.** The existing harness already acquires through `acquire-context!`, so a test can acquire the SCI
  context first and then run JVM mode.

### Q10: Which provenance keys replace the run ref, and what is the caller closure? (S6-01, S6-02b, S6-04a/b) **ROOT**

- **Recommendation.**
  - An effect receipt stores the pass id as a value under a NEW attribute name (not a retyped `:seon.effect/run`), with
    `:seon.effect/form-ordinal`, never a ref to an unrecorded run or evaluation. `:seon.effect/run` retires in the same
    slice (accretion: new names for changed semantics). The effect id is already `digest(turn-id, form-ordinal,
    effect-ordinal)` (`effect.clj:849-866`), so the value is rederivable.
  - `evaluation-eid` (`effect.clj:210`) and the `:seon.effect/eval` ref are deleted. A reader that needs the evaluation
    looks it up by its identity, `id/evaluation(pass-id, ordinal)`, after RECORD.
  - A background effect request inside a test attempt is refused by name, and no `:seon.effect/notify` is stored
    (`effect.clj:872`). The attempt context then needs no throwaway agent or turn row.
  - The run carrier closure, deleted in S6-04a (`*open*`, `*receipt*`) and S6-04b (the rest): `db/*open*` (`db.clj:184,458,3620`; binder `sci/eval.clj:4027`;
    producer `turn.clj:4968`; schema `:seon.db/pending-open`), `open-tx`/`open-call` (`turn.clj:694,385`) and their
    producers, `system-run-*`, `submission-open-tx` with its three writers (`turn.clj:5953`, `:5984`/`:4968`, `with-submission:3509`),
    `generated-run-tx`, `recover-tx`/`recover-call` (`turn.clj:1505,1514`) with callers `cluster.clj:2938`,
    `cluster/agent.clj:1453`, `turn.clj:5479`, and the 24 fixture files that call `turn/open-tx`.
  - `db/*receipt*` (`db.clj:180,3619`) and the `:seon.db/receipt` stamp (`seon.db.edn:258`) join that closure. They are
    a ref on every transaction to an evaluation that does not exist yet under one RECORD.
  - Boot's `:seon.boot/recovered-runs` and `recovery-operations` (`cluster/boot.clj:553`, `seon.boot.edn`,
    `cluster_test.clj:30`, `turn_test.clj:590`, `loop_proof_test.clj:764-774`) are converted in S6-04b to effect and agent
    counts, with their schema and tests. Recovery shrinks to `effect/interruption-stamps` keyed on the pass id value.
- **Production readers of the run ref (I-Q10 section 4).** `effect.clj:60-66,79,115,405`, and `turn.clj:1905-1906` (the
  kept-evaluation reuse check), which the first plan missed.
- **Grounds.** "Provenance is pass id + position" (§4.2). Identities are values, refs exist only to living entities.

### Q11: Where do test member pass rows and the request aggregate live? When may the member branch retire? (S5-07e, S6-02a/b, S7-02) **ROOT**

- **Recommendation.**
  - Member pass rows are written on the member's own branch, its custody, in one member pass.
  - The request aggregate is written on the requester's recording connection.
  - Task-home completion is its own write (§4.2, §4.9).
  - The member branch retires only after the body's actual exit **and** the aggregate's durable commit, through the
    runner's existing actual-exit owner.
- **Grounds.** Custody is the default world, and the spec's entrant table already separates member and request.

### Q12: What do the failed-call row, the counting anchor and the work union look like? (S6-05) **ROOT**

- **Recommendation.**
  - A failed provider or context call writes the call row (Q2) with zero evaluations, its error, and a ref to the
    outside wake anchor it consumed. The writer counts call rows against that anchor for the cap.
  - Answeredness moves only on a committed model pass, to the basis it presented.
  - `next-agent-work` returns the work union with no run situations. With no provider configured, no model work is
    admitted.
- **Grounds.** This is derived at the writer from rows with no counter (§4.5, "Decide at the authority").

### Q13: How do the old completion APIs convert, and where is proof eligibility checked? (S6-06a/b, S7-03) **ROOT**

- **Recommendation.**
  - `my.task/complete!` (new) is the bail and takes a required reason. `my.turn/complete` (`src/my/turn.clj:5`),
    `my.task/done!` (`src/my/task.clj:163`) and `seon.run/complete` are deleted, and every caller converts in the same
    commit (CLAUDE.md "new names for changed semantics; retire the old path").
  - The `assign!` writer checks proof eligibility at the task home, against the home program, through `seon.test/verified?`.
  - A refactor task whose proofs are all green needs a new failing proof. This already follows from CLAUDE.md ("a test
    already green before the work does not prove it"), so it is not a new semantic.

### Q14: Which deadline bounds disarm and join once the backstop is gone? (S6-07) **ROOT**

- **Recommendation.** The caller's lifecycle deadline passes explicitly into `disarm!` and `stop-graph!`, as §4.8
  states. `flow/join-graph!` already waits on it (`flow.clj:136`, `await.clj:117-150`) and leaves the executor shut down
  but unterminated, so `arm!` and a retried `disarm!` already own the resources until exit (I-Q14 finding 6). There is no
  new timer or pool.
  - **Required argument.** `disarm!` takes `:seon.await/deadline-nanos` as a required argument. Every caller passes one:
    `release-context!` (via `stop-owned-graphs!:904`), `arm!` (`agent.clj:1269`) and the ~40 test sites. A caller with no
    deadline of its own computes one at its own entry from the configured dial, and the dial survives for those entrants
    (the dial's consumers in I-Q14 section 1 that are not the deleted backstop: `evaluate-source!`, `stop-work-launcher!`,
    `flow-workload-attributes`, the await config-attribute enum).
  - **Per-agent slice.** `disarm-agents!` (`cluster.clj:3557-3561`) gives each agent its own deadline slice. One stuck
    agent never zeroes the others' budget.
  - **Detector kept.** Removing the active-turn bound must not drop detection of a pass blocked in a host call (I-Q14
    finding 5). S6-07 keeps one detector: the actual-exit owner names the missing terminal event when the deadline
    passes. Dropping detection altogether would need an owner decision.
  - **Typed error returned.** `disarm!` and `disarm-agents!` return the typed timeout error their contract declares
    (`[:or :nil :seon.await/timeout-error]`) and stop throwing (`turn-completion-failure!`, `agent.clj:1402,1523-1530`).
    `disarm!`'s contract widens from `:nil` to that union, and `stop-owned-graphs!`, `arm!` and every caller handle a
    returned value. Disarm keeps no second fault recorder. Task: `seon.cluster/a-missed-agent-join-throws-instead-of-answering`.
- **Proofs.** I-Q14 section 2 lists them; `release-joins-every-owned-graph-before-it-closes-the-branch` (`:1020`) and
  `turn_backstop_test.clj` were missing from the first list.
  - Converted, deadline passed explicitly, `with-redefs` of `prompt/prompt` replaced by an injected blocked host call:
    `seon.cluster.agent-test/disarm-interrupts-an-in-flight-turn-and-records-it-interrupted` (`agent_test.clj:843`),
    `…/disarm-has-a-declared-loud-turn-completion-backstop` (`:923`; now asserts the returned typed error, not the dial),
    `…/release-joins-every-owned-graph-before-it-closes-the-branch` (`:1020`).
  - Converted, `with-redefs` over `async/close!` replaced by data from a watch on the routing atom:
    `…/disarm-drops-the-route-before-closing-it` (`:793`).
  - `seon.turn-test/a-fired-turn-bound-requests-its-owners-stop-and-settles-its-join-whole` (`turn_test.clj:2102`)
    converts to the kept detector.
  - Deleted with the permit and state atom: `…/turn-start-has-the-same-declared-loud-completion-backstop` (`:1178`),
    `…/a-failed-turn-transform-releases-its-permit-and-retires-its-backstop` (`:1215`), the three deftests of
    `test/seon/turn_backstop_test.clj`, `loop_proof_test.clj` "a lost turn permit faults…" (`~208-243`); drop
    `:seon.agent/turn-backstop-state` in `cluster/turn_test.clj:1661`.
  - New, none yet: `seon.cluster.turn-test/a-timed-out-pass-keeps-ownership-until-it-exits`, plus a one-stuck-agent case
    in `disarm-agents!` proving the other agents' joins still get their slices, and a case proving the typed error
    (not a throw) for a missed agent join.

### Q15: Does run-test-var take a Var or a symbol, and what does it return? (S7-01) **ROOT**

- **Both packets.** Prefer a symbol routed into `my.test/check` and recorded as proof.
- **Recommendation.** It takes a **Var** and returns clojure.test's **summary counters**, exactly as the standard
  `run-test-var` does. It is an `agent-run-test-var` adapter in `agent-test-namespace` (`eval.clj:318`), copying
  `agent-test-ns`'s begin line and `test-vars` call.
- **Grounds.** The task's done-when asks for "one SCI test var… report as data… pass counts"
  (`seon.sci.eval.edn:213-216`). A proof is recorded only by `my.test/check`. Making run-test-var certify would give it
  two meanings, so it stays a bounded capture helper like `run-tests`. This is a ~15-line adapter, not a second runner.

### Q16: What does task-home completion validate after a branch proof commits? (S7-03) **ROOT**

- **Recommendation.** One idempotent task-home writer checks assignment id, task revision, and each proof's recorded
  definition, input and reach digests against the current combined program. Recovery after the crash window reruns the
  same writer, and a repeat is a no-op by identity (§4.9 "Completion's cross-branch crash window with idempotent
  recovery"). There is never a latest-green boolean.

### Q17: How does `documentation-value` get the SCI context without an ambient lookup? (S1-04, S1-07a/b, S1-08) **ROOT**

- **Decision.** The S1 design keeps "the same 3-arg signature" and relies on `sci.ctx-store/get-ctx`. HEAD already does
  so at `eval.clj:2133`, and S1 design claim 3 does too. CLAUDE.md requires passing the world explicitly.
- **Astra.** S1-04 is ready.
- **Sol.** Blocked, prefer an explicit argument.
- **Recommendation.** Each lookup function declares `:seon.sci.eval/ctx` as its first input, a world member
  (`batch.clj:61`). The macro expansion omits it, and the armed wrapper supplies it from the evaluation's world. Proven
  above (`result/e3ec7d7102288`, 3 ms). The signature becomes `documentation-value [ctx database authored resolved]`,
  and the macro keeps passing `(seon.db/db)`. Direct JVM test callers pass ctx explicitly.
  `stored-source-fn`/`source-fn` drop `sci.ctx-store/get-ctx` in S1-08 by the same rule.
- **Grounds.** CLAUDE.md "Supply defaults by declared schema name… SCI context… comes from that evaluation's own
  world".

### Q18: Is an analysis-free `doc` lookup still a `:seon.fn` program edge? (S1-09) **ROOT** (new)

- **Decision.** After retargeting, clj-kondo must record bare and qualified `doc` as calls to `clojure.repl/doc`
  (S1 design claim 4, unverified).
- **Recommendation.** S1-09's new `seon.fn-test/lookup-analysis-names-the-installed-repl-vars` is the falsifier. If
  kondo treats the macro arguments as usages, the lane reports that and does not filter text.

---

## 3. Slice ledger (assuming the recommendations are accepted)

Groups are file-ownership groups:

- **A**: render, bootstrap and help.
- **B**: `turn.clj` family.
- **C**: `sci/eval.clj` family.
- **E**: `db`, `blob`, `health`, `effect`, `test`, `task`.
- **F, H**: design notes.
- **G**: skills.

Rows in the same group run in series. Different groups run in parallel once their claimed sets are disjoint. "Q" in the
dependency column means root must accept that answer first.

### Ready (R0/R1/R2, see §1)

| Id | Change | Files | Proof tests | Budget | Deps | Group |
|---|---|---|---|---|---|---|
| X-01 | Re-anchor and correct the REPL and Flow skill claims at HEAD (`fork-for-turn`, regeneration, uncontracted `defn` admission, system turn, cache policy, three-write turn). Delete unverifiable claims | `.agents/skills/repl/SKILL.md`, `.agents/skills/seon-flow-architecture/SKILL.md` | `seon.contracts-compile-test/a-check-of-no-sources-has-no-contract-findings` (commit gate only, docs publish nothing) | D | none | G |
| I-Q4 | Notes with the exact caller/test closure of `prompt.clj:171-402`, `context.clj:309-415`, `render.clj:1721` walk entry, and each web/debug survivor's data entrance | `docs/research/agent-platform/2026-10-08/inventory/q4-prompt-walk.md` | same gate | D | none | H |
| I-Q10 | Notes with the closure of `*open*`, `open-tx:697`, `recover-tx:1473`, effect `:seon.effect/run` producers, `test.clj:179-216` attempt writers and boot recovery caller | `…/inventory/q10-open-provenance.md` | same gate | D | none | H |
| I-Q14 | Notes with the disarm/join/backstop proofs and the deadline path MCP → `disarm!:1465` → `stop-graph!:1508` | `…/inventory/q14-exit-deadline.md` | same gate | D | none | H |
| S5-03a | Admission design, def → redef → use | `docs/research/agent-platform/2026-10-08/s5-admission/redef-design.md` | specifies `seon.cluster.turn-test/a-pass-records-the-definition-each-form-observed` | O def | none (the S1-03 dependency is dropped: probes read today's behavior) | F |
| S5-03b | Macro defined, then used | `…/macro-design.md` | `…/a-pass-admits-a-macro-before-its-use` | O | none | F |
| S5-03c | Unmap and remove-ns | `…/removal-design.md` | `…/a-pass-records-unmap-and-namespace-removal-in-order` | O | none | F |
| S5-03d | ns and alias changes | `…/alias-design.md` | `…/a-pass-analyzes-aliases-at-their-observed-position` | O | none | F |
| S5-03e | Nested and cross-namespace definitions | `…/nested-design.md` | `…/a-pass-joins-nested-and-cross-namespace-definitions-once` | O | none | F |
| S5-03f | Private def | `…/private-design.md` | `…/a-pass-preserves-private-definition-custody` | O | none | F |
| S5-03g | Schema, then use | `…/schema-design.md` | `…/a-pass-admits-a-schema-before-its-use` | O | none | F |
| S5-03h | Failed def, then a dependent form | `…/failed-def-design.md` | `…/a-failed-definition-does-not-certify-a-later-dependent-form` | O | none | F |
| S5-03i | Definition during lazy realization | `…/lazy-design.md` | `…/a-pass-captures-definitions-realized-after-the-form-body` | O | none | F |
| S5-03j | Prose with a quote before a def | `…/prose-design.md` | `…/a-pass-keeps-original-definition-spans-after-prose` | O | none | F |
| S1-04 | `reference-in` (new). `documentation-value [ctx database authored resolved]` reads SCI Var doc and arglists and keeps the DB contract and override note. `function-doc-map` takes metadata explicitly and drops its `arglists-reader` re-read. `documentation-unavailable` widens to `authored-reference`. The macro passes authored syntax | `src/seon/sci/eval.clj`, `resources/seon/schemas/seon.sci.eval.edn`, `test/seon/sci/documentation_test.clj`, `test/seon/render/value_test.clj` (direct caller `:607`) | existing `seon.sci.documentation-test/documentation-is-returned-data-without-a-second-printed-copy`, `…/documentation-keeps-named-contracts-and-guarded-result-shapes`, `…/a-named-private-override-documents-the-jvm-boundary`, `seon.render.value-test/documentation-body-is-whole-or-one-executable-elision`. New `seon.sci.documentation-test/doc-classifies-authored-references` | L `(doc seon.task/create!)`, `(doc clojure.set/union)` | Q17 (the S1-03 dependency is dropped: no shared file or data) | C |
| S1-03 | `runtime-analysis-batch` returns `:seon.fn/runtime-namespaces`. `namespace-entity` drops the added refers and stores the unaugmented source. The `pr-str` producer at `fn.clj:996` is fixed or tasked | `src/seon/fn.clj`, `test/seon/fn_test.clj` | existing `seon.fn-test/an-alias-an-earlier-form-requires-resolves-in-later-forms-of-the-batch`, `…/a-fold-derived-definition-has-static-index-edge-parity`. New `seon.fn-test/runtime-namespace-source-keeps-only-authored-refers` | O def/after-def | Q1 | B′ (fn.clj, disjoint from s3-01) |
| S7-01 | `agent-run-test-var [var] -> summary counters` in `agent-test-namespace`. Resolves task `run-test-var-throws-on-an-sci-test-var` | `src/seon/sci/eval.clj`, `test/seon/sci/documentation_test.clj`, `resources/seon/tasks/seon.sci.eval.edn` (task closes by proof) | new `seon.sci.documentation-test/run-test-var-answers-one-branch-test-as-data` (branch deftest, pass/fail counts, `:out` holds the report, no host root mutation). Existing `…/clojure-test-at-the-agent-repl-reports-into-the-evaluations-output` | T | Q15, after S1-04 (file) | C |
| S4-01 | Unique tuple `:seon.cluster.eval/agent-ordinal`, assigned once per ordered writer batch. `seon.db/last-agent-ordinal` (rseek that accepts only the same attribute and agent). `of-agent` reads the tuple range | `src/seon/db.clj`, `src/seon/turn.clj` (`receipt-settle-batch-call`, `record-evaluated-call`), `src/seon/eval.clj`, `resources/seon/schemas/seon.cluster.eval.edn`, `test/seon/turn_test.clj` (the writer owner; `seon.db-test` for `last-agent-ordinal` alone) | new `seon.turn-test/evaluation-ordinals-follow-writer-order`. The existing `seon.context-selection-test/selection-references-terminal-evaluations-in-writer-decided-order` converts into `seon.turn-test`, since `context_selection_test` is deleted in S4-07b | H | S3-01 landed, `defn-cost2` released | B |
| S5-06 | JVM mode stops acquiring. `seon.cluster.agent/held-context` (new) or gone-value with class | `src/seon/mcp.clj`, `src/seon/turn/batch.clj` (jvm-request/jvm-show), `src/seon/turn/world.clj`, `src/seon/cluster/agent.clj`, `test/seon/mcp_entrance_test.clj` | existing `seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle` (cold row → gone-value), `…/a-jvm-reply-keeps-its-duration-after-an-earlier-run`. New `…/a-jvm-call-does-not-acquire-an-sci-world` | O/F | S3-01, Q9 | E |
| S6-01 | Effect receipt stores pass id under a NEW attribute plus the form ordinal, as values. Retire `:seon.effect/run`, delete `evaluation-eid` and the `:seon.effect/eval` ref. Convert the readers `effect.clj:60-66,79,115,405` and `turn.clj:1905-1906`, and the receipt fixtures in `web/jvm_test`, `shell/jvm_test`, `background_blob_test`, `render_coverage_test`, `background_test`, `my/background_test`, `render/value_test` | `src/seon/effect.clj`, `resources/seon/schemas/seon.effect.edn`, `src/seon/turn.clj` (`:1905`), `test/seon/effect_test.clj` (`:1029` walk converts to identity lookup) plus the fixtures above | existing `seon.effect-test/request-commits-before-io-dispatch-and-settles-once`, `…/guarded-sci-evaluation-supplies-the-effect-identity-context`, `…/effect-refs-its-evaluation-and-handler` (converted to identity values), `…/background-settlement-carries-its-connection-across-a-thread-hop` | F/O | Q10, I-Q10 | E |

### S1 lookups (group C, in series after S1-04; S1-10 and S1-11 in group A)

| Id | Change | Files | Proof tests | Budget | Deps |
|---|---|---|---|---|---|
| S1-05 | Schema and namespace branches inside `documentation-value`: `schema-doc`, `namespace-doc` with `:publics`. Check `sci/find-ns` before `dir-fn` | eval.clj, eval schema, documentation_test | existing `seon.sci.documentation-test/an-example-ends-at-the-next-fixed-heading`. Extend `…/doc-classifies-authored-references` | L `(doc :seon.task/intent)`, `(doc seon.task)` | S1-04 |
| S1-06 | Task branch (one pull at `seon.task/home`, `task.clj:381`) and test branch (`{:sym :recorded :verified?}` through `recorded-result:1425`, `verified?:1562`) | eval.clj, eval schema, documentation_test | new `seon.sci.documentation-test/task-doc-reads-home-declaration-and-branch-proofs`, `…/lookup-does-not-certify-a-stale-test-result` | L (task with must-read, test with a recorded result) | S1-05 |
| S1-07a | `directory-value [ctx authored] -> var-summaries` from SCI `dir-fn`, no contracts, no DB. Delete `program-documentation`, `arglists-reader` (last caller) and `seon.repl` directory render AI/HTML with its schema. **Keep** `program-documentation-selector` (Sol's version: `eval.clj:2062`, `db_test.clj:148,186`) | eval.clj, eval schema, documentation_test, `src/seon/repl.clj`, `resources/seon/schemas/seon.repl.edn`, **`test/seon/render/value_test.clj`** (`:64,77`) | existing `seon.sci.documentation-test/directory-observes-a-function-installed-after-context-acquisition`, `…/a-named-private-override-documents-the-jvm-boundary` (`:26` caller), `seon.render.value-test/block-pairs-remain-explicit` (directory row removed). New `…/directory-answers-data` | L `(dir seon.task)` | S1-06 |
| S1-07b | `apropos-value`/`find-doc-value [ctx lookup-pattern]` as qualified SCI Vars | eval.clj, eval schema, documentation_test | new `seon.sci.documentation-test/searches-answer-data`. Existing `…/documentation-is-returned-data-without-a-second-printed-copy` | L `(clojure.repl/apropos "task")`, `(clojure.repl/find-doc "symbol-in")` | S1-07a |
| S1-08 | `source-value [ctx database authored]`: stored source first, then host `source-fn`, else unavailable. Returns `{:seon.sci.eval/sym :seon.render/ai text}`. `source-fn` drops `sci.ctx-store/get-ctx` (Q17) | eval.clj, eval schema, documentation_test | existing `seon.sci.documentation-test/source-at-the-agent-repl-prints-the-stored-declaration` (converted to data, adds private/test/library/missing rows) | L `(clojure.repl/source seon.id/symbol-in)` | S1-04 (may run before S1-05..07) |
| S1-09 | Lookups in `sci/init` `clojure.repl` once. `refer-declared-bindings! [ctx projection] -> ctx`. Retarget the binding schema. Delete `install-program-doc!` with its call, the `seon.bootstrap/doc` and `/dir` macros (`bootstrap.clj:161,166`) and the build's hand lists. Constructors become `doc-var`, `dir-var`, `source-var`, `source-fn`. Retarget the `seon.sci.eval.edn:123,190,204` must-reads | eval.clj, `resources/seon/schemas/seon.sci.binding.edn`, documentation_test, `test/seon/fn_test.clj`, `src/seon/bootstrap.clj`, `resources/seon/tasks/seon.sci.eval.edn` | existing `seon.sci.documentation-test/retained-context-receives-the-current-repl-macro-through-its-core-alias`, `…/bare-test-macros-resolve-without-namespace-referrals`, `…/retained-context-receives-new-bare-test-referrals`. New `…/bare-and-qualified-lookups-share-vars`, `seon.fn-test/lookup-analysis-names-the-installed-repl-vars` (Q18 falsifier) | O plus L bare forms | S1-03, S1-07b, S1-08 |
| S1-10 | `help-value [] -> [help-entry]` literal, no DB, teaching the landed same-line rule. Delete the help render pair and its scanner. `namespace-form` and `usage-form` → `(dir ns)` | `src/seon/bootstrap.clj`, `resources/seon/schemas/seon.help.edn`, `test/seon/help_test.clj`, `src/seon/render/ns.clj` (`namespace-form`, HEAD `:78`), `src/seon/run.clj:58`, `test/my/turn_test.clj`, `test/seon/render/history_test.clj`, `test/seon/turn_test.clj`, `test/seon/render/ns_test.clj`, `resources/seon/tasks/seon.documentation.edn` | new `seon.help-test/help-names-only-installed-forms`. Existing `…/an-agent-identity-offers-help-as-its-operation`. Converted: `my.turn-test/the-usage-test-renders-the-lifecycle-walkthrough`, `seon.render.history-test/form-is-the-third-output-of-the-existing-selection-chain`, `…/a-neighbour-the-walk-reached-renders-by-its-own-shape`, `seon.turn-test/generated-system-runs-grow-only-after-their-settled-prefix`, `seon.render.ns-test/a-namespace-renders-as-one-graph-node` (namespace-form row). Delete `seon.help-test/help-evidence-tracks-its-code-version` | L `(help)` | S1-09, S3-01 released (turn_test) |
| S1-11 | Delete `my.program` `file-line`/`nearest`/`source` (`program.clj:849-874`) and their two subject tests. Correct the `search`/`declaration-diff` docs. Convert `my/program_test.clj:135` | `src/my/program.clj`, `test/my/program_read_test.clj`, `test/my/program_test.clj`, `resources/seon/tasks/my.program.edn` | existing `my.program-read-test/search-ranks-bounds-and-offers-more`, `…/sizes-list-lines-largest-first`. Standard source proof in S1-08 | D, L source | S1-08, S1-10 |

I take Sol's S1-07a/b split. Astra's single S1-07 carried directory, two searches, deletions and three render or test
files, which is beyond 150 lines.

### S4 context (group B, in series, after S3-01; S4 is a reset point)

| Id | Change | Files | Proof tests | Budget | Deps |
|---|---|---|---|---|---|
| S4-01 | (see Ready, R2) | | | H | S3-01 |
| S4-02a | Call row gains origin and an assignment ref (Q2). Written by the existing settlement for the model/outside passes. No opening completion attribute (retired with the opening) | `src/seon/turn.clj` (plan/settle call row), `resources/seon/schemas/seon.turn.edn`, `test/seon/cluster/turn_test.clj` | new `seon.cluster.turn-test/a-pass-call-row-names-its-assignment-and-origin` | O | S4-01, Q2 |
| S4-02b | **Static prefix** replaces `declared-sources`/`system-turn`'s opening and `seed-tx` (`turn.clj:1751,2056`): `(help)`'s value per loaded commit, in a `seon.cache` kind keyed by commit, sent as the system message. No pass, no recorded form, no opening origin | `turn.clj`, `src/seon/bootstrap.clj:312`, `src/seon/ai.clj` (`request-body:572`), `test/seon/cluster/turn_test.clj` | new `seon.cluster.turn-test/the-static-prefix-is-byte-identical-across-calls-of-one-commit` (two calls' system messages `=`; no evaluation row). Existing `…/generated-opening-preserves-one-provider-turn-budget` deleted with the opening | I | S4-02a, S1-10 |
| S4-03 | History projection reads call rows of the active assignment with origin `:model`, plus evaluations by position, from stored emission units (no re-render, no EDN decode) | `src/seon/render/transcript.clj:246,726`, `src/seon/eval.clj`, `src/seon/repl.clj`, schemas, `test/seon/render/transcript_run_test.clj` | new `seon.render.transcript-run-test/reacquired-history-is-the-finished-exchange`. Existing `…/render-run-selects-only-the-requested-run` | H | S4-02a |
| S4-04 | The proc holds the transcript (acquired at init or a new assignment, appended per pass). `prompt` validates the token window and refuses when over, with no clipping | `turn.clj` (`call-turn`, `step`), `src/seon/cluster/prompt.clj:349,403`, prompt schema, `test/seon/cluster/prompt_test.clj` | existing `seon.cluster.prompt-test/the-history-path-never-refits-a-rendered-unit`, `…/the-prompt-reads-the-cluster-its-caller-names`. Converted `…/a-budget-smaller-than-the-history-keeps-whole-newest-units` → new `…/an-over-window-transcript-refuses-without-dropping-exchanges` | H/O | S4-03 |
| S4-05a | `my.agent/inputs` (new) returns undelivered items with identity and home basis; the block's Unread section and its elision form read it (Q3 revised) | `src/my/agent.clj`, `resources/seon/schemas/my.agent.edn`, `src/seon/context.clj:257`, `test/seon/cluster/turn_test.clj` | new `seon.cluster.turn-test/inputs-read-each-home-at-its-own-basis` | L | Q3 |
| S4-05b | **Retired** (owner 2026-10-08): no input pass. Its delivery write moves to CS-04 and its message-history append retirement to CS-04 | | | | |
| S4-06 | Delete the refresh (`turn.clj:1686-2232`, `receipt-read-evidence-tx:1217`) and persisted freshness evidence, keeping runtime observation | `turn.clj`, `src/seon/render.clj` callers, eval schema, `test/seon/turn_test.clj`, `test/seon/turn_error_test.clj` | new `seon.turn-test/settlement-does-not-store-refresh-evidence`. Delete `seon.turn-test/generated-read-evidence-rejects-turn-activity`, `…/a-turn-dependent-generated-form-keeps-its-value-beside-the-fault` | O + read-heavy row | S4-02b, S4-04, CS-04; root approves the closure |
| S4-07a | Move `:seon.ai.tokens/characters` onto the model call's attempt or basis row, written by the existing settlement. `calibration-for` reads it there; `recorded-usage-tx` stops writing capture rows. Move `message-custody` out of the capture section of `context.clj` | `src/seon/cluster/prompt.clj:74-104`, `src/seon/context.clj:257`, `src/seon/turn.clj` (settlement), attempt/call schema, `test/seon/cluster/prompt_test.clj` (`recorded-usage-tx:354`) | existing `seon.cluster.prompt-test/calibration-uses-the-agents-recent-attempts-and-config-prior`, `…/an-unread-usage-history-is-not-an-empty-one`, `seon.context-test/message-custody-accepts-an-absent-run-when-instrumented` | O | S4-04 |
| S4-07b | Delete capture, the agent-owned selection and compaction code, walk composition and the prompt selection closure (Q4). The debug Context view renders saved exchanges through the proc's pure transcript function. Delete the web context-action POST together with `change-context`, `system-turn`, `virtual-turn!` and `compact!` (root: the approved spec deletes system turns, context is rendered live) | `src/seon/context.clj:76-250,309-415` (`message-custody` already moved by S4-07a), `prompt.clj:171-402` (`calibration-for:74-104` reads the new row), `render.clj:1721,1731-1733,49-50`, `render/web.clj:2410-2520,3185-3207,3390`, `walk.clj:840`, `turn.clj:3124,4559-4580,65-66`, `render/transcript.clj:617`, schemas `seon.context.capture.edn`, `seon.context.contribution.edn`, `seon.context.edn`, `seon.cluster.prompt.edn`, tests per I-Q4 §5: delete `context_capture_test`, `context_capture_history_test`, `context_selection_test` (all four deftests; S4-01 moved its ordinal proof to `seon.turn-test`), the compaction transcript test `seon.render.transcript-test/selected-evaluations-project-only-their-stored-source-and-result` (`:967`: deleted if it tests only the deleted selection code, otherwise converted), the prompt-test deftests that price retained history (`:93,208,260,281,293,329,493`); convert `turn_loop_test:307`, `web_debug_test:93`, `web_context_test` (four deftests), the survivors listed in I-Q4 §5, and the `with-redefs` of `prompt/prompt` in `agent_test` | new `seon.cluster.prompt-test/a-model-call-stores-basis-without-prompt-capture`. Existing `…/identical-context-does-not-depend-on-a-retained-prompt-cache`, `…/calibration-uses-the-agents-recent-attempts-and-config-prior` (still green), `seon.context-test/message-custody-accepts-an-absent-run-when-instrumented`, `seon.render.web-context-test/the-context-view-is-computed-only-when-opened` | O/H (split further by file group, web then prompt then context, if the closure exceeds about 15 minutes) | S4-07a, S4-06, Q4 |

S4-02 and S4-05 are split further than in both packets. Each original row mixed a new fact, a producer and a deletion.
S4-02a puts the fact on with its first producer (the existing settlement), so it is never a consumerless schema.

### CS current-state block (group B for `turn.clj`/`ai.clj` rows, E for task rows; spec §4.4)

The block is one read-only value rendered at each model call, placed last before the model's turn, never stored. Each
row is about 15 minutes and asserts data, never printed text.

| Id | Change | Files | Proof tests (data) | Budget | Deps |
|---|---|---|---|---|---|
| CS-01a | `seon.context/current-state [database agent-id] → :seon.context/current-state` (new, schema in `seon.context.edn`): the Task section (current declaration and steps read at the task home through `seon.task/assignment`, `task.clj:496`) and Turns (calls left, task status) | `src/seon/context.clj`, `resources/seon/schemas/seon.context.edn`, `test/seon/context_test.clj` | new `seon.context-test/current-state-shows-the-current-task-declaration`: revise the task's intent at the home; the next value holds the new intent and no key holds the old | L | S4-02a |
| CS-01b | Proofs section: each proof from `seon.task/task-edges` (`task.clj:321`) with its stored source, status by the basis rule of `seon.render.var/status` and reach of `seon.test/verified?` (`test.clj:1562`), counts, and each red as `{:test :expected :actual :message}` from the stored report (`seon.test.report.edn:7-24`) | `src/seon/context.clj`, its schema, `test/seon/context_test.clj` | new `seon.context-test/a-red-proof-shows-expected-actual-and-message-as-data` (value keys asserted, no text); new `…/a-proof-result-before-the-latest-reached-definition-is-pending` | L | CS-01a |
| CS-01c | Must-reads (arglists, contract, first doc line, `doc`/`source` operations; Q2b) and Unread (items from `my.agent/inputs` with identities) | `src/seon/context.clj`, its schema, `test/seon/context_test.clj` | new `seon.context-test/unread-shows-each-undelivered-item-once-with-its-identity` | L | CS-01a, S4-05a |
| CS-02 | The block's declared AI render pair and print budget: 2,000 estimated tokens through the value printer, cut order (counts and red data, task, proof sources, must-reads, unread), elision forms as data | `src/seon/context.clj` (render), `resources/seon/schemas/seon.context.edn`, `test/seon/context_test.clj`, `test/seon/pass_probe.clj` (block row) | new `seon.context-test/an-over-budget-block-offers-its-elision-forms` (render data holds `(clojure.repl/source sym)` and `(my.agent/inputs)` for the cut parts). **Budget row** in `seon.pass-probe`: block render < 10 ms, < 2 MB; estimated tokens recorded for green-and-quiet, one red, over budget | L | CS-01b, CS-01c |
| CS-03a | Placement, wire format A: `seon.ai/messages` puts the static prefix in the system message, the transcript, then the block at the end of the one user message | `src/seon/ai.clj` (`request-body:572-575`), `test/seon/ai_test.clj` | new `seon.ai-test/transcript-ends-with-the-block-and-keeps-its-prefix`: for two successive calls, request 2's user text up to the end of request 1's history equals request 1's; the block appears exactly once and last | L | CS-02, S4-04 |
| CS-03b | Placement, format B: the block as a second content part of the last user message (or the only user message before the first form) | `src/seon/ai.clj`, `test/seon/ai_test.clj` | new `seon.ai-test/chat-puts-the-block-in-the-last-user-message`: no two consecutive user messages; messages before the last are `=` across calls | L | CS-03a, competency C5 |
| CS-03c | Placement, format C: B plus the assistant prefix after the block, only where the provider declares prefix continuation | `src/seon/ai.clj`, `test/seon/ai_test.clj` | new `seon.ai-test/prefix-continues-after-the-block` | L | CS-03b |
| CS-04 | Delivery: the model pass's settlement records `:seon.cluster.eval/delivered` for the unread items its block showed (writer checks existence and not-yet-delivered); the message-history append is retired | `turn.clj` (`delivery-rows:3173`, settlement), `resources/seon/schemas/seon.cluster.eval.edn`, `test/seon/cluster/turn_test.clj` | new `seon.cluster.turn-test/a-committed-model-pass-delivers-what-its-block-showed`; new `…/a-failed-provider-call-delivers-nothing` (fixture provider; the next block shows the same identities) | I | CS-01c, S4-04, S5 settlement |
| CS-05 | A task update by root or the user advances the assignment's `:seon.task/revision` in the same transaction, so `assignment` (`task.clj:496-519`) never refuses `:revised-since-assignment` for a reviewed update and the next block shows it | `src/seon/task.clj`, `test/seon/task_ingestion_test.clj` | new `seon.task-ingestion-test/a-revised-task-shows-on-the-next-call-without-reassignment` | L | CS-01a |
| CS-06 | Delete the opening: `system-turn`'s opening units, `declared-sources` (`turn.clj:1751`), `seed-tx` and their tests | `turn.clj`, `src/seon/bootstrap.clj`, tests | existing callers converted; `seon.cluster.turn-test/the-static-prefix-is-byte-identical-across-calls-of-one-commit` stays green | O | S4-02b, CS-03a |

### S5 recording

| Id | Change | Files | Proof tests | Budget | Deps | Group |
|---|---|---|---|---|---|---|
| S5-01 | `transact-call` returns a runtime `:seon.db/pending` on timeout. `with-publication!` awaits terminal before releasing the permit (Q5) | `src/seon/db.clj:5683`, `src/seon/blob.clj:304`, `resources/seon/schemas/seon.db.write.edn`, `seon.blob.edn`, `test/seon/db_test.clj`, `test/seon/blob_publication_test.clj` | new `seon.db-test/a-write-timeout-keeps-its-pending-completion`, `seon.blob-publication-test/an-unknown-root-commit-keeps-publication-owned`. Existing `seon.db-test/transaction-success-and-refusal-shapes-are-declared`, `seon.blob-publication-test/publication-and-collection-are-exclusive-in-both-orderings` | F/O | Q5, `defn-cost2` released | E |
| S5-02 | Health reads the live profile snapshot. Delete `significant-tx` and stored measurements | `src/seon/health.clj:460,508`, `src/seon/profile/measurement.clj`, `turn.clj:3505`, `turn/batch.clj:709` call sites, health/profile schemas, `test/seon/health_storage_test.clj`, `test/seon/health_test.clj`, `test/seon/profile/measurement_test.clj` | new `seon.health-storage-test/health-uses-live-profile-without-writing-measurements`. Existing `seon.health-test/a-slower-boot-phase-is-a-regression-until-it-recovers` | O/T | Q6, S3-01 released | E (touches B files: serialize after S3-01) |
| S5-04 | One analysis and captured facts. **Not a lane.** Root prices sub-items after Q7 | — | — | — | S5-03a–j, Q7 | — |
| S5-05 | Observed fault occurrences replace `fault-window`/`with-fault-windows` (`turn.clj:3441`) and routing | `turn.clj`, `turn/world.clj:136`, `src/seon/batch.clj`, error schemas, `test/seon/cluster/turn_test.clj` | existing `seon.cluster.turn-test/a-red-form-stays-with-its-author-and-the-fold-continues`. New `…/a-pass-delivers-only-the-errors-it-observed` | F/O | S5-01, Q8 | B |
| S5-06 | (see Ready, R2) | | | O/F | S3-01, Q9 | E |
| S5-07a–f | Sol's six serial adapters, unchanged (writer, JVM, SCI, model, member, retire old) | per Sol's table | per Sol's table, sharing new `seon.mcp-entrance-test/every-entrant-keeps-values-through-recording-failure` | O/F/T | S5-01, S5-04, S5-05, S5-06; 07e after S6-02a | B/E serial |

I take Sol's S5-03 (ten rows) and S5-07 (a–f). Astra deferred both, and Sol names exact files and proofs.

### S6 cut (S6 is a reset point)

| Id | Change | Files | Proof tests | Budget | Deps | Group |
|---|---|---|---|---|---|---|
| S6-01 | (see Ready, R2) | | | F/O | Q10 | E |
| S6-02a | Bounded test body through the one fold, member pass on the member branch, aggregate on the requester (Q11) | `src/seon/test.clj` (`member-result:1987`, `bounded-result:216`), `turn/batch.clj:950`, `turn/world.clj:70`, test schemas, `test/seon/test_test.clj` | existing `seon.test-test/interpreted-test-bodies-use-the-sci-interrupt-bound`, `…/recording-preserves-admission-and-refuses-a-deleted-definition`, `…/overlapping-admissions-reserve-complementary-memberships`. New `…/a-member-records-one-pass-before-its-request-aggregate` | T/F | S5-07d, S6-01, Q11 | E |
| S6-02b | Delete the effect attempt agent/turn preparation (`attempt-writers`, `attempt-request-context`); the context keeps a pass id value, ordinal, environment, counter and cluster. A background effect request in an attempt is refused by name and stores no notify | `test.clj:179-216`, `effect.clj:872`, test/effect schemas, `test/seon/test_test.clj`, `test/seon/effect_test.clj`, `test/seon/test/effect_attempt_test.clj` | new `seon.test-test/a-test-effect-needs-no-attempt-agent-or-turn`, `seon.test.effect-attempt-test/a-background-effect-in-a-test-attempt-is-refused-by-name`. Existing `seon.test.effect-attempt-test/an-agent-capability-stub-settles-a-receipt-only-in-an-effect-reaching-attempt` and the effect proofs above | T/F | S6-02a, S5-07e, S6-01 | E |
| S6-03 | One live cursor (`seon.sci.forgive/next-form`) feeds the fold. Delete `segments`, `source-rows`, `submission-open-tx` and the segmenter where no JVM failure-span caller remains. A prose-only reply is a call row with no evaluation (Q2) | `turn.clj`, `src/seon/cluster/agent.clj:644`, `src/seon/sci/forgive.clj:389`, schemas, reader/entrant tests | existing `seon.sci.forgive-test/the-reader-reads-a-reply-by-its-line-rules`, `seon.cluster.turn-test/reply-reading-follows-evaluated-alias-and-dynamic-require-state`, `…/a-prose-only-provider-reply-settles-without-parking-the-turn-proc`. New `…/one-cursor-reads-each-form-in-its-current-world` | O, 28.7 KB corpus | S4-03, S5-07f | B |
| S6-04a | Delete `db/*open*` with its binder (`sci/eval.clj:4027`), its producer (`turn.clj:4968`) and `:seon.db/pending-open`, and `db/*receipt*` with the `:seon.db/receipt` stamp (Q10). Settlement carries no prefixed open; effects and evaluations already hold pass id values (S6-01) | `db.clj:180-184,458,3619-3620`, `sci/eval.clj:3985,4026-4027`, `turn.clj:4968,3509-3530,5953,5984` (`submission-open-tx` writers), `resources/seon/schemas/seon.db.edn:258-259`, `test/seon/settlement_receipt_provenance_test.clj` (the only `*open*` test, deleted), `test/seon/db_test.clj` | new `seon.db-test/a-transaction-stamp-carries-no-ref-to-an-unrecorded-evaluation`. Existing `seon.effect-test/request-commits-before-io-dispatch-and-settles-once` | O/F | S6-01, S6-02b, S6-03 |
| S6-04b | Delete the intent commit, `recover-tx`/`recover-call`, the open/resume/close/generate families and `open-turn`; the 24 `turn/open-tx` fixture files convert to the pass entrance or pass-id values. Convert boot's `:seon.boot/recovered-runs` and `recovery-operations` to effect and agent counts; recovery shrinks to `effect/interruption-stamps` keyed on the pass id. Split further by fixture file group if it exceeds about 15 minutes | `turn.clj`, `cluster.clj:2938`, `cluster/agent.clj:1453`, `cluster/boot.clj:553`, `seon.boot.edn`, turn/run schemas and tests (`cluster_test:30`, `turn_test:590`, `loop_proof_test:764-774`, `effect_test:875` converted) | new `seon.cluster.turn-test/a-crash-before-record-keeps-only-durable-effects`. Retire `…/turn-intent-is-the-complete-crash-falsifier`. Existing `seon.effect-test/recovery-marks-open-receipts-interrupted-without-refiring` (keyed on the pass id) | O/F | S6-04a |
| S6-05 | Writer answeredness to the presented basis, a cap counted per anchor, continuation (Q12) | `turn.clj:2584-2908`, agent/call schemas, `test/seon/turn_work_test.clj` | existing `seon.turn-work-test/a-wake-arriving-mid-turn-opens-the-next-turn`, `…/two-wakes-in-one-transaction-are-one-turn`, `…/only-a-turn-whose-reply-came-from-a-model-attempt-answers`. New `…/model-passes-answer-only-the-basis-they-presented` | O/I/F | S6-04b, Q12 | B |
| S6-06a | `assign!` refuses tasks with no proofs or all-current-green proofs, checked at home (Q13) | `src/seon/task.clj:421,467`, `resources/seon/schemas/seon.task.edn`, `test/seon/task_ingestion_test.clj` | new `seon.task-ingestion-test/assignment-requires-a-non-green-proof` | F/O | Q13 (independent of S6-05: the task writer shares no file) | E |
| S6-06b | `my.task/complete!` bail with a required reason. Delete `my.turn/complete`, `my.task/done!` and `seon.run/complete` with their callers | `src/my/task.clj`, `src/my/turn.clj`, `src/seon/run.clj`, task/my.turn/my.task schemas, `test/seon/task_ingestion_test.clj`, `test/my/turn_test.clj` | new `seon.task-ingestion-test/completion-with-a-reason-gives-the-task-back`. Converted `my.turn-test/a-blank-completion-is-an-error-value-not-a-throw`, `…/a-disposition-is-an-ordinary-value` | F/O | S6-06a, S6-05 (disposition settlement) | E |
| S6-07 | Delete the backstop timer, parking and permit (`turn-completion-error:5593`, `await-turn-permit!:5667`, `write-refusal-bound:5717`, `arm-turn-completion-backstop!:5798`, `step:5856`) but keep one detector: the actual-exit owner names the missing terminal event. `disarm!` takes an explicit deadline from every caller (`release-context!`, `arm!`, ~40 test sites; entrants without one compute it from the dial, which survives for them). `disarm-agents!` gives each agent its own slice. `disarm!`/`disarm-agents!` return the typed timeout error and stop throwing. Fix the docstrings naming the backstop and correct spec §4.8 (`docs/seon/specs/agent-loop.md`) and the "delete the backstop" wording to "the actual-exit owner keeps one detector". Resolves task `seon.cluster/a-missed-agent-join-throws-instead-of-answering` | `turn.clj:5593-5856`, `src/seon/cluster/agent.clj:904,921,1269,1304-1354,1402-1535`, `src/seon/cluster.clj:3509-3568`, `src/seon/flow.clj:621,734`, config/run/await schemas, `resources/seon/tasks/seon.cluster.edn`, `test/seon/cluster/agent_test.clj`, `test/seon/cluster/turn_test.clj:1661`, `test/seon/turn_test.clj`, `test/seon/turn_backstop_test.clj`, `test/seon/loop_proof_test.clj`, the ~40 bare `disarm!` test callers and the dial setups listed in I-Q14 §2 | new `seon.cluster.turn-test/a-timed-out-pass-keeps-ownership-until-it-exits`, `seon.cluster.agent-test/a-stuck-agent-does-not-zero-the-others-deadline-slices`, `…/a-missed-agent-join-answers-the-typed-timeout-error`. Converted (injected blocked host call, no `with-redefs`) `…/disarm-has-a-declared-loud-turn-completion-backstop`, `…/disarm-interrupts-an-in-flight-turn-and-records-it-interrupted`, `…/release-joins-every-owned-graph-before-it-closes-the-branch`, `…/disarm-drops-the-route-before-closing-it` (watch on the routing atom), `seon.turn-test/a-fired-turn-bound-requests-its-owners-stop-and-settles-its-join-whole` (to the kept detector). Deleted with the permit: `…/turn-start-has-the-same-declared-loud-completion-backstop`, `…/a-failed-turn-transform-releases-its-permit-and-retires-its-backstop`, `seon.turn-backstop-test` (3 deftests), the `loop_proof_test` lost-permit test | F/O | S6-04b, S6-05, I-Q14, Q14 | B |

I take Sol's S6-02a/b. Astra's single S6-02 created a cycle with S5-07: the test adapter needed the old settlement
retired, and that retirement needed the test body converted. S6-06a is moved earlier: the assignment writer needs only
Q13, not S6-05.

### S7 proofs

| Id | Change | Files | Proof tests | Budget | Deps | Group |
|---|---|---|---|---|---|---|
| S7-01 | (see Ready, R1) | | | T | Q15 | C |
| S7-02 | **Retired** (owner 2026-10-08): proof results reach the agent as current state in the block (CS-01b), not as input items. No delivery identity is needed for a status | | | | | |
| AR-01 | Automatic proof runs (spec §4.5). **Owner 2026-10-08: only the task's PROOF tests run during the work — a handful, not every test reaching the change; reaching (regression) tests run once at merge, on the combined program (auto-accept `:no-new-red`).** The pass's SCHEDULE offers its changed set to the agent's proof proc (`:compute`, in the agent's Flow graph), which unions offers in its state and runs one `seon.test/run` over the task's proofs reached by the union (`test.clj:2225`, `:named`) at a time, to actual exit. Resolves task `seon.render.var/reaching-tests-are-never-run-and-a-first-red-is-silent` (scheduling half) | `turn.clj` (settlement hands the changed set), the agent graph's proc table, `src/seon/test.clj` (none expected), `test/seon/cluster/turn_test.clj` | new `seon.cluster.turn-test/a-defn-reaching-a-proof-records-its-result-without-a-check-call`: after one model pass with a `defn` (fixture provider), a member row for the proof has `:seon.test.member/completed-tx`, and the pass's reply was answered before it (reply commit tx < member completed tx). New `…/changes-during-a-run-are-checked-by-the-next-run` (no change lost, no overlap). **Budget row**: in-pass < 5 ms / < 1 MB; one-test request < 150 ms warm, < 20 MB Seon work (today 769 ms / 74,244 calls) | T/I | S6 (agent graph), S7-01 |
| AR-02 | Success from all-green proofs at current reach: on an agent's branch, the proof proc's recording proposes the branch (`my.program/propose-merge!`'s writer, `program.clj:425-460`); on root's merge branch, acceptance completes the task through the idempotent task-home writer (Q16) | `src/seon/task.clj:698`, `src/seon/test.clj:72,1562`, `test/seon/test_test.clj`, `test/seon/task_ingestion_test.clj` | new `seon.task-ingestion-test/a-task-completes-only-from-fresh-proofs-after-its-change`; new `…/all-green-proofs-propose-once` (a repeat recording is a no-op by identity). Existing `seon.test-test/recording-preserves-admission-and-refuses-a-deleted-definition` | T/F | AR-01, S6-06b, Q16 |
| AR-03 | A proof result recorded red after the agent's answered basis is work: the work union wakes a waiting agent | `turn.clj` (`next-agent-work`), `test/seon/cluster/turn_test.clj` | new `seon.cluster.turn-test/a-red-proof-after-wait-wakes-the-agent` (disposition `:wait`, then a red recording; the work union names it) | I | AR-01, S6-05 |
| AR-04 | The Var render names a red reaching test with no earlier result (today `status` answers `{}` for it) and its namespace docstring states what runs. Resolves the rest of task `seon.render.var/reaching-tests-are-never-run-and-a-first-red-is-silent` | `src/seon/render/var.clj`, `test/seon/render/var_test.clj` (new file) | new `seon.render.var-test/a-first-red-reaching-test-is-named` | L | none (independent of AR-01) |

### PB pass budget (group C; Opus: acquisition is boot-critical)

The base context becomes **one shared base per cluster, a function of the JVM's loaded program**. It is built once per
loaded commit and then advanced by changed namespaces (PB-03). An agent's branch differences (agent-authored rows
whose digest differs from the loaded one, plus their reverse closure) form an **overlay applied only on that agent's
fork**. The overlay advances by changed definition digests (PB-02). The agent's context forks once and advances in place
at idle boundaries (PB-02b). No pass constructs a base. `acquire!` already advances a held context in place and carries
the context's own Vars (HEAD `eval.clj:3209`); it keeps that role. The base's identity, the derivation's inputs and the
per-pass regeneration change.

What disappears:
- the revision-basis base key, meaning connection id, generation, dial revisions and whole-program revisions (`base-ctx-key:3026`);
- the `clojure.core.cache` LRU of 4 (`:2908`);
- whole-program re-derivation on every program change;
- per-pass `regenerate-agent-context!` when nothing changed.

Cost follows the changed definitions and their closure, not the program.

| Id | Change | Files | Proof tests (data) and budget row | Deps |
|---|---|---|---|---|
| PB-01 | Split `derive-base-ctx`/`acquire-program!` into two parts. The **cluster base** loads core namespaces, builds the ctx and caches the program snapshot from the **loaded** value. It lives in a `seon.cache` kind `:seon.sci.eval/base-context`, keyed by `[loaded commit id, projection identity, recorder]` and weighed by its function-row count. The **overlay** computes the authored overrides against the loaded digests plus their `seon.fn/reverse-closure`, interprets them and arms them with the branch's current dial values on the caller's fork (`copy-base-ctx`). `acquired-database?` compares the definition digests and the loaded commit, never dial or connection revisions. A dial change re-arms only the overlay's interpreted wrappers, and a ctx records the dial values it armed with. Delete `base-ctx-key`'s revision key, `program-cache-policy`'s base entry and the LRU. Contracts: `base-ctx [database arm-request] -> ctx` is unchanged; a new private `overlay! [ctx database] -> acquisition`. | `src/seon/sci/eval.clj`, `resources/seon/schemas/seon.sci.eval.edn`, `test/seon/sci/branch_execution_test.clj`, `test/seon/sci/acquisition_attributes_test.clj` (deleted with the dial/attribute basis if no caller remains, else converted), `test/seon/cluster/evaluate_source_test.clj` | New `seon.sci.branch-execution-test/agent-branches-and-a-settings-write-share-the-cluster-base`. Two new branches' first acquisitions, a repeat acquisition and an acquisition after a dial write give **zero** `:seon.cache/misses` for `:seon.sci.eval/base-context` in `(seon.cache/status)` after the cluster base exists. Each branch's overlay is exactly its authored overrides plus their closure. New `seon.cluster.evaluate-source-test/a-second-model-pass-and-a-settings-write-construct-no-base` (fixture provider, base misses asserted as data). Existing, kept green: `…branch-execution-test/a-base-context-is-memoized-by-its-program-and-each-caller-gets-its-own-fork`, `…/two-concurrent-acquisitions-of-one-program-derive-it-once`, `…/branch-definitions-and-unchanged-callers-share-one-context`, `…/a-branch-forked-before-a-platform-change-binds-the-loaded-compiled-var`, `…/an-override-of-a-declaration-the-indexer-marks-host-bound-refuses-by-name`, `seon.cluster.evaluate-source-test/an-armed-agents-run-sees-a-program-change-committed-by-another-writer`. **Budget row** (throwaway root, `seon.pass-probe/run-model "default" 3 #{2}` plus the `p3` MCP sequence of `base-miss-probes.clj`): a new agent's first pass and the pass after a settings write show no `seon.sci.eval/base-ctx` stage. MCP first pass ≤ 50 MB / 100 ms (today 648 MB / 402 ms); after settings ≤ 50 MB / 100 ms (today 318 MB / 228 ms). Retained probe (`p4`/`p5` form: live heap after three `System/gc`, eight agents): one base per loaded program, ≤ 2 MB retained per agent (today 333 MB baseline, +1.2 MB per agent, about 2 MB per extra base) | `repl-p11` releases `eval.clj`; independent of S4 (S4-06 only moves where the miss lands) |
| PB-02 | The overlay advances incrementally. The ctx's program snapshot holds the definition-digest map it acquired. `acquire!` diffs the branch's `function-digests` (memoized per commit) against it. It installs added and changed rows plus their `reverse-closure` on the ctx in place (`install-row!`), unmaps removed ones, and pulls only those rows and their namespaces. Unchanged digests do no read and no install. A loaded-commit change re-forks from the advanced cluster base (PB-03) and re-applies the overlay. | `src/seon/sci/eval.clj`, `resources/seon/schemas/seon.sci.eval.edn`, `test/seon/sci/branch_execution_test.clj`, `test/seon/cluster/evaluate_source_test.clj`, `test/seon/sci/eval_test.clj` | New `seon.sci.branch-execution-test/a-one-definition-change-installs-only-its-closure`: zero base misses; the installed set equals `#{changed} ∪ closure`; a private def's object is `identical?` before and after; the changed definition is visible. New `seon.cluster.evaluate-source-test/a-pass-after-a-definition-constructs-no-base` (model pass, fixture provider). Existing, converted to the advance: `…branch-execution-test/a-data-only-commit-reuses-the-acquired-program-and-a-program-entity-reacquires`, `…/a-fork-taken-after-a-program-entity-commit-holds-that-entity`, `…/an-unchanged-host-bound-caller-of-a-changed-definition-runs-as-compiled`, `…/interpreted-callers-resolve-unchanged-names-to-compiled-vars`, `seon.sci.eval-test/database-provenance-regenerates-the-base-and-reverts-for-retained-forks`. **Budget row**: the MCP pass after a one-line `defn` ≤ 60 MB / 100 ms (today 519 MB / 301 ms). The model pass after a `defn` shows no `base-ctx` stage and drops by ≥ 400 MB (today 1,360 MB / 829 ms) | PB-01 |
| PB-02b | Fork once, advance in place. `fork-for-turn` with a carried agent ctx whose acquired overlay and base are unchanged since its last pass returns that ctx without `regenerate-agent-context!` (`:2732`). Regeneration runs only after PB-02 advanced the overlay or PB-03 the base. This is the owner's "forks once and advances in place" shape, not a budget item (the fork costs 6–7 ms / ~5 MB). | `src/seon/sci/eval.clj`, `test/seon/sci/eval_test.clj` | New `seon.sci.eval-test/an-unchanged-program-pass-reuses-the-agents-context`: the same ctx `:env` atom and the same private Var objects, with an `alter-var-root` of an inherited Var still carried. Existing `seon.sci.eval-test/acquired-source-context-forks-have-branch-custody-and-private-defs`, `…/acquired-source-context-forks-own-their-lazy-program-state`, `…/sci-fork-copies-existing-var-roots-on-write`. **Budget row**: `fork-for-turn` ≤ 1 ms on an unchanged pass (today 6–7 ms per call, 2 per model pass) | PB-02 |
| PB-03 | Adoption advances the cluster base. When the loaded commit moves, the new base entry is a fork of the previous one with only the reloaded namespaces' Vars re-copied (the changed set `cluster/refresh-source!` already holds). It is keyed by `[previous base key, changed namespace digests]`, and nothing re-derives the whole program. This is outside any pass, and it completes "built up incrementally, never rebuilt". | `src/seon/sci/eval.clj`, `src/seon/cluster.clj` (passes the changed set), `test/seon/sci/branch_execution_test.clj` | Converted `seon.sci.branch-execution-test/an-adoption-record-moves-the-loaded-program-every-acquisition-compares-against` (one advance, zero derivations). New `…/an-adoption-advances-the-cluster-base-by-its-reloaded-namespaces`. **Budget row**: adopting one changed namespace costs ≤ 50 MB / 100 ms in the base (today a full derivation of 428 MB / 180–215 ms) | PB-02 |

Accepted as measured, not sliced (owner, 2026-10-08: transient garbage above 40 MB is tolerated, never GB). No accepted
owner threatens the envelope at today's sizes:
- the surviving writer after S5/S6/P14 (one checked commit, 13–14 MB, 21–30 ms; existing tasks
  `seon.db/every-commit-validates-its-write-report-three-ways`, `seon.db/the-checked-writer-is-slow-on-a-fresh-branch`);
- the armer/provider/query residual (3–7 MB, `ai/complete` 3 ms).

Watch: the writer's owning-value validation grows with an agent's turn history (37 µs per turn, the second task). If a
long-lived agent's settlement passes 40 MB or 100 ms, that task becomes a slice.

### Pass budget after all slices (projection, owner by owner)

Warm ordinary model pass, excluding the provider call. Estimates come from measured surviving work
(profile tables and this lane's probes). They are not measurements of the end state; the PB and S budget rows measure it.

| Owner | Today, first / warm (MB, ms) | Removed by | Projected after all slices (MB, ms) |
|---|---|---|---|
| Base construction | 428 / 0 on a hit; a miss on every new agent, settings write or `defn` (648–1,360 MB passes) | PB-01, PB-02, PB-03 | **0** in any pass. A `defn`'s next pass pays only its overlay delta (est. 5–20 MB). Adoption pays a base advance outside passes |
| Context projection, render, prompt | 405 / 350; 190–200 ms | S4-02b, S4-03, S4-04, S4-06, S4-07b, CS-06 (the block adds its own < 10 ms / < 2 MB row) | 3–10 MB, < 10 ms (held transcript append; provider request serialization still ∝ transmitted history) |
| Checked writer (seven commits) | 97–125; 140–175 ms | S4-06/07b, S5, S6-04, P14 | **accepted** one commit: 10–18 MB, 15–30 ms |
| Evaluate/record remainder | 8–18; 2–24 ms | P6/P7/P8/P13, S5-05/07, S6-03 | 2–8 MB, 5–15 ms |
| Refresh bookkeeping | 12–17; inside 165–357 ms | S4-06 | 0 |
| Context fork/regeneration (2 calls) | 11–13; 10–15 ms | S4-06 (one fork), PB-02b | < 1 MB, ≤ 1 ms on an unchanged pass |
| Work/episode derivation | 6–10; 6–8 ms | S6-05/07 | 2–4 MB, 2–4 ms |
| Armer/provider/query residual | 3–7; ~3 ms | none | **accepted** 3–7 MB, ~3 ms |
| **Total** | 785–928 first, 473–578 warm; 375–675 ms | all above | **≈ 20–45 MB, 30–70 ms**, at the 20–40 MB target with garbage-only overshoot. Retained: one base per loaded program plus ≤ 2 MB per agent |

### Critical path and parallel launch now

- **Must-fix first in C:** PB-01 → PB-02 → PB-02b → PB-03 start as soon as `repl-p11` releases `eval.clj`. They go
  ahead of every unstarted C row because they remove a GB-class per-pass cost. PB-01 and PB-02 can land before S4. If S4-06
  lands first, the miss moves to `resume-turn`'s `acquire!` and is still removed by PB-01/02.
- **In series:** C: S1-04 → S7-01 → S1-05 → S1-06 → S1-07a → S1-07b, with S1-08 between any two C rows after S1-04.
  Then S1-09 (needs S1-03) → S1-10 → S1-11.
- **In parallel now:** X-01; I-Q4, I-Q10 and I-Q14; S5-03a–j; S1-03 (after Q1); S1-04 (after Q17).
- **After `s3-01` lands:** S4-01 (also needs `defn-cost2` released), S5-06 and S6-01.

---

## 4. Disagreements that reveal real spec ambiguity

1. **The `documentation-value` arity and the SCI context (Q17).** The S1 design (§4.3, claim 3) keeps three arguments
   and an ambient `get-ctx`. CLAUDE.md says pass the world explicitly. Astra called it ready, Sol called it blocked.
   The world-member supply resolves it without changing what the macro caller writes (probe above).
2. **The order of the test entrant and the shared settlement (S5-07 vs S6-02).** Spec §5 puts "one settlement owner
   shared by all entrants" in S5 and test-attempt conversion in S6. Astra's rows were circular. Sol's 02a/02b staging is
   the consistent reading, and the spec's S5/S6 boundary should say the member adapter lands in S6.
3. **Admission probes: one lane or ten (S5-03).** Spec §4.1 says "a probe per case, before any code" but does not say
   one lane per case. Sol's ten rows follow from the cases' different fixture and provenance boundaries.
4. **The exchange without an evaluation (Q2).** Both packets saw that §4.3 "records one exchange… no evaluation" has no
   named record. The spec already names "the call row" in §4.1 RECORD, so the ambiguity is wording, not a missing
   design. Root should fold "the exchange is the pass's call row" into §4.4 in place.
5. **Must-reads (Q2b).** Superseded by the owner's 2026-10-08 ruling: no opening; must-reads are current state in
   the block, shown as signatures with `doc`/`source` operations (recommended).
6. **The S3 origin enum.** Both packets note that §5 lists `:opening :input :model :outside :test` under S3, while the
   approved S3 v2 adds only `:model :outside`. The spec's §5 S3 row should be revised in place.
7. **Total path vs the current MCP refusal assertion.** `mcp_entrance_test.clj:149-153` asserts no unsaved values after
   a refusal, but §1/CLAUDE.md "The path is total" requires value plus diagnostic. Both packets flag this. It converts
   in S5-07c, not as a separate slice.

Disagreements on facts, not ambiguity, all resolved for Sol after checking HEAD:

- `function-doc-map`'s callers.
- Whether to keep `program-documentation-selector`.
- The blob test file: `test/seon/blob_publication_test.clj` owns the permit proof, not `blob_test.clj`.
- The task name: `seon.batch/a-jvm-run-answers-evaluated-but-not-recorded`.
- The long proof name: `seon.loop-proof-test/virtual-loop-end-to-end` (`loop_proof_test.clj:303-305`).

Dependencies I dropped as unsupported by any file or data:

- S1-04 on S1-03.
- S5-03 on S1-03.
- S6-06a on S6-05.
