---
type: notes
status: research
created: 2026-10-07
tags: [notes, agent-loop, deletion, census]
---

# r-delete: deletion census for root-plan §3 (D1-D6) and agent-loop.md §5

Lane r-delete, research only. Nothing in `src/` or `test/` was changed.

## Method and probe

- **Code.** HEAD is `ead9d9a14`. `git diff 722f110df HEAD -- src test resources/seon/schema*` is empty, so default
  (pid 41301, running the `722f110df` archive) runs exactly HEAD's source. Line numbers below are HEAD's.
- **Health.**
  - `bin/seon status` and MCP `runtime_status` both report default `:faulted` with 17-18 open faults. The top fault
    is `seon.turn/show-row` → `shown-failure` nil (task `7f5dfb6ad7a2`). There are 11-13 over-bound warnings,
    including `seon.batch/evaluate-batch` (mean 1.67-2.2 s, max 43.7 s).
  - None of these is in this lane's scope. All are already linked to tasks.
- **Census.**
  - Fresh clj-kondo analysis of `src test`: 19 s, 8,433 definitions, 173,243 Var usages. It was condensed into an
    index under `scratchpad/r-delete/`.
  - A closure script adds each seed and every Var whose src callers are all dead, then reports the remaining live
    src callers (the survivors) and the test namespaces.
  - The script also adds the edges clj-kondo cannot see: `requiring-resolve`/`#'` references (161 sites), and the
    quoted symbols in `mcp.clj:231` (`'seon.turn/code-sources`) and `web.clj:2439-2441` (the action symbols).
  - Every dead symbol was also grepped in `resources/`, `.agents/` and `docs/seon/` for render pairs, generators and
    skills.
- **Known over-approximation.** clj-kondo counts a syntax-quoted operation name (`` `system-turn `` inside an error
  map) as a call. Example: `generated-read-fault` → `system-turn`. Such edges only make a Var look more alive, so
  the census never under-reports a survivor.
- **"Lines"** means the def form's span (`end-row - row + 1`): docstrings and contracts are included, blank lines
  between defs are not. Totals are therefore 5-10% below `wc -l` deltas.
- **Unverified.** I did not re-run walk-delete's 43.6 s `system-turn` preview. I did not trace a model turn; lanes
  r-turn and r-read own that.
- **Branch.** Running eval_clj as agent `r-delete` minted branch `:agent-c87a2805ad4f`. It was retired:
  `(my.agent/retire-branch! {:seon.store/branch :agent-c87a2805ad4f})` as root, 69 ms, 993 calls. My first
  zero-argument call was refused by arity, which was my own mistake.

## Two measurements that bear on the order

On default, JVM mode, measured with the per-thread allocation counter (`getThreadAllocatedBytes`):

| Form | ms | Allocated | Answer |
|---|---|---|---|
| `(#'seon.turn/latest-evaluations db "root")` (the system-turn refresh read) | 902 | **1,763 MB** | 1,227 kept sources |
| `(seon.eval/of-agent db "root")`, default selector | 297 | 667 MB | **an error map**: `:seon.db/invalid-read`, "The EDN-backed attribute :datahike.read/dependency-plan has an invalid logical value." |
| `(seon.eval/of-agent db "root" seon.eval/history-selector)` | 410 | 833 MB | 1,387 evaluations |

- **The default selector refuses.** The agent-facing history read with its default selector now refuses on root,
  because it pulls the stored read-evidence components whole. This is a new defect class (a refusal, not a cost).
- **Task created:** `seon.eval/of-agent-default-selector-refuses-on-stored-read-plans` (id `6ccd16292e4f`).
- **Related existing tasks (cost and size):**
  - `seon.turn/history-acquisition-decodes-stored-read-plans`;
  - `seon.turn/read-evidence-rows-are-two-fifths-of-the-stored-bytes`.
- **Stored read evidence today** on default: 23,705 `:seon.cluster.eval/read-evidence` refs on 1,387 evaluations.
  - Each ref carries a `:datahike.read/dependency-plan` and a `:datahike.read/revision`: 23,742 datoms each on the
    loop-pass branch `:agent-4512788dda3b`.
  - The 38 ms count query is the evidence.

## Census by group

Each group is the closure of its seeds (listed in `scratchpad/r-delete/groups.edn`). Survivors are live src callers
outside the group. They must be converted in the same commit, or the group waits.

### D1: the old multi-hop context (2,837 lines in 112 Vars, split into four ordered sub-slices)

**D1a. Debug views of the assembly: 864 lines, 35 Vars. Independent of the model pass.**

- **Deleted:**
  - transcript: `render-session` (1259, 115 lines), `render-session-loading` (1231), `render-outline` (1531, 81) and
    `outline-*` (1412-1530), `render-ledger-context` (1865), `render-captured-prefix` (1972), `prefix-subject`,
    `acquire-ledger-data` (1730), `ledger-acquisition` (1613), `emission-label`, `ledger-effects`, `turn-effects`,
    `ledger-entities`, `attempt-html`/`-usage`, `session-origin`, `outline-url`;
  - web: `graph-debug-html` (3407, 138 lines) and its 8 helpers (≈ 154 more), `sent-context-html` (3391),
    `debug-outline-response` (3213), `context-action-response` (3751);
  - `seon.cluster.prompt/agent-calibration`.
- **Survivors to convert, each to an existing owner:**
  - `web/debug-response` (3546) and `web/debug-turn-response` (3188) drop their routes to the deleted views
    (`?prompt=`, `?context=`, `?sent=`, `?n=`, outline, session);
  - `web/context-response` (3792) keeps only the virtual-turn action;
  - `transcript/render-ledger` (2224), `render-ledger-turn` (1854) and `ledger-evaluations` (1629) lose
    `ledger-acquisition` → read `seon.eval/of-agent` **with an explicit selector**. The default selector refuses,
    as shown above.
  - `transcript/prefix-problem` (1988) and its `session-problems` row go with the captured prefix.
- **Stored data:** none.
- **Tests:** `web_debug_test` (12 of 23 defs, 793 lines hit), `web_context_test` (1 def). `run6_stall_test` reads
  `session-problems` and is unchanged.

**D1b. System turn, generated opening, compaction: 1,103 lines, 32 Vars.**

- **Deleted:**
  - turn: `system-turn` (2030, 208 lines), `declared-sources` (1725), `latest-evaluations` (1794), `system-plan`
    (1909), `read-only-evaluation?`, `code-currency`, `code-current?`, `unit-read`, `source-events`, `source-key`,
    `generated-read-fault` (1960, 69 lines), `record-evaluated-tx` (1365), `compact!`/`compact-call` (2271/2239),
    `generate-turn` (5260, 121 lines), `append-generated-call`/`-tx` (798/840), `generated-run-tx` (847),
    `cluster-agent-acquire-context!`;
  - `seon.context/selection`, `append-tx`, `compact-tx` and their helpers (181 lines). **These are test-only in src
    today.**
  - `seon.render/render-form-value` (1480), `seon.sci.eval/rendered-evaluation` (3729), `seon.sci.kernel/pending?`,
    `seon.db/diff`, `apply-diff`, `value-changes`.
- **Survivors to convert:**
  - `turn/open-turn` (4282) drops the refresh at :4312 and keeps `open-call`;
  - `turn/evaluate-sources` drops the `generated-read-fault` block at :4978-4991;
  - `turn/turn` (5450) drops the `:generate` case;
  - `bootstrap/seed-tx` stops seeding a `:generate` run. The spec says the opening becomes a message to root.
    Until it does, `generate-turn` must still close such a run (walk-delete risk 2, still true);
  - `web/change-context` loses its quoted `'seon.turn/system-turn` and `'seon.turn/compact!` actions
    (web.clj:2439-2441).
- **Stored data:** system runs and their evaluations (`:seon.cluster.eval/author :system`,
  `:seon.turn.work/situation :generate`, 148 situation datoms on default) and compaction retractions.
  - These are history only, with no reader left after D1c. No reset is needed for D1b by itself.
- **Tests:**
  - deleted whole: `rereads_test` (233 lines, 5/6 hit), `rereads_panel_test`, `turn_error_test`,
    `context_selection_test`, `repl_grammar_test` (2/4), `task_steps_test` (compaction, 2 defs),
    `cluster/bootstrap_resume_child`;
  - converted: `context_blocks_fixture` (its system-turn opening), `turn_test`, `cluster/turn_test`, `db_test`
    (`system-turn-renders-a-changed-read-with-value-diff`), `render/faults_test`, `error_recording_test`.

**D1c. Prompt, capture and history assembly: 852 lines, 44 Vars. Must land in the same slice as the new context
(S1).**

- **Deleted:**
  - `seon.cluster.prompt`, the whole namespace (460 lines by `wc`, ≈ 430 in defs);
  - `seon.context` capture (`capture-tx` 359, `contribution-hash`, `capture-ai`/`-html`, `contribution-entity`);
  - `seon.render/acquire-context!` (1721), `retained-program-current?`, `render-web-derive-context!`,
    `turn-opening-db`;
  - `web/derive-context!` (2463), `history-segments`, `change-context`, `turn-function-result`;
  - `seon.render.walk/history` (840);
  - `seon.ai.tokens/budget-report`, `calibrate` and `recent-calibration` (99 lines);
  - `seon.eval/history-selector`;
  - `seon.repl/frame` (55). **This is where "turns left: N" prose is printed into the context** (repl.clj:74-77).
- **Survivors to convert:**
  - `turn/call-turn` (4359): its prompt (:4511) and capture (:4537) are replaced by the new context;
  - `seon.context/message-custody` (257) moves beside its only caller, `transcript/message-entry` (237);
  - `.agents/skills/data-oriented-clojure/SKILL.md` names `turn-function-result`; fix it in the same commit;
  - `.agents/skills/datahike/SKILL.md` and `docs/seon/architecture/vocabulary.md` name `system-turn`.
- **Stored data:**
  - `seon.context.capture/*` and `seon.context.contribution/*` (33 contributions on `:agent-4512788dda3b`, 0 on
    default);
  - `seon.cluster.prompt/{error-agent-id, derivation-observation}`.
  - Schema files: `seon.context.edn` (84 lines), `seon.context.capture.edn` (37), `seon.context.contribution.edn`
    (33), `seon.cluster.prompt.edn` (55), `seon.render.history.edn` (24). **RESET NEEDED** after the schema retires.
- **Tests:**
  - deleted: `cluster/prompt_test` (577 lines, 17/23 defs), `context_capture_test`, `context_capture_history_test`,
    `render/history_test` (1/5), `render/call_test` (1/2), `render/retained_test` (1/5), `ai/tokens_test` (5/10),
    `render/root_pull_test` (history, 2/21), `render/transcript_test` (1/49).

**D1d. Stored read evidence: 18 lines plus edits. Lands after D1a and D1b, because their readers go first.**

- **Writers:**
  - `turn/receipt-read-evidence-tx` (1224);
  - `evaluate-sources` :4965 (`(assoc :seon.cluster.eval/read-evidence (db/read-evidence reads))`).
- **Readers:**
  - `seon.eval/of-agent`'s default selector (eval.clj:38);
  - `transcript` 1378 and 1624 (D1a);
  - `web` 1419 (`debug-render-experiment`);
  - `sci.eval/rendered-evaluation` (D1b);
  - `turn` 135-146 (`evaluation-facts`) and 1255-1276 (`recorded-evaluation`, `stored-record-content`).
- **Survivors to convert:** `receipt-settle-call` and `record-evaluated-call`. These are the two settlement
  writers, so the edit falls in D4's files.
- **Stored data:**
  - `:seon.cluster.eval/read-evidence` and `:seon.cluster.eval/read-evidence-entity`;
  - the `datahike.read/*` values (≈ 47k datoms per branch that holds root's history).
  - Retire the attribute in `seon.cluster.eval.edn` and `seon.eval.edn`. **RESET NEEDED.**
- **Test:** `mcp_entrance_test`'s "a form that binds a Var stores no read evidence" becomes "no evaluation stores
  read evidence".

### D2: run machinery and the situations dispatch (1,718 lines, 57 Vars, closure keeping the MCP path)

**Deleted:**

- turn:
  - `open-turn` (4282, 62 lines), `call-turn` (4359, 266 lines), `close-turn` (5238), `plan-call` (642, 61),
    `plan-tx` (495), `plan-tx-for-author`, `next-id` (519);
  - `next-agent-work` (2833, 62) with `resume-or-generate`, `fold-or-close` and `continuing-reply?`;
  - `more-agent-work?`, `episode-runs`, `spent-runs`, `turns-left`, `opening-deferred?` (51), `deferred-triggers`,
    `unanswered-wakes` (52), `unanswered-triggers`, `latest-answering-turn-t`, `outside-wake-t` (which moves to
    `seon.cluster.wake` per the spec), `agent-eid`, `wake-attribute-set`;
  - the attempt helpers: `record-attempt!` (4066, 143 lines), `attempts`, `attempt-evidence`, `attempt-request`,
    `provider-targets`, `provider-wait-ms`;
  - `assignment-facts`, `form-settlement`, `plan-settlement`, `red-receipt?`;
  - `committed-attributes` (51 lines, **test-only in src**);
  - `receipt-start-tx` and `receipt-start-call` (**test-only**);
  - `row-tx` (1046, 123 lines). **It is test-only in src; its own docstring says "No production caller".** Its
    `declared-*` helpers go with it, and so do `seon.schema/projection-without-schema`,
    `projection-with-function-contract` and `schema-removal-blockers` (71 lines, now reached only by `row-tx` and
    tests).
- `seon.cluster.wake/agent-wake-datoms` and `inside-wake?`;
- `seon.effect/interruption-stamps`.

**Survivors to convert.** The MCP path, boot and the test runner reach these today:

| Survivor | Uses | Convert to |
|---|---|---|
| `turn/step` (5789, 249 lines), the `:resume` arm of `turn/turn` | `next-agent-work`, the dispatch | the one pass (S4). **MCP SCI eval_clj runs through `step` → `turn :resume` → `resume-turn` → `evaluate-sources`**, so step cannot be deleted before its replacement serves MCP too |
| `cluster.agent/agents-to-arm` (1586) | `more-agent-work?` | the wake derivation of the new `work` (spec P4a) |
| `cluster/recover-runs!` (2937, boot), `cluster.agent/record-interruption!` | `recover-tx` | stays while runs exist; spec §6 drops boot `recover-tx` at the cut |
| `seon.test` `attempt-writers` (test.clj:179) | `turn/open-tx` (the run open, for effect-reaching tests) | stays while the run entity exists. 26 test files use `open-tx` as a fixture writer |
| `bootstrap/situation` (98), `bootstrap/seed-tx`, `bootstrap/supervision-tx` | `episode-runs`, `unanswered-triggers`, `open-for-agent`, `generated-run-tx`, `system-run-tx` | the help-situation render loses its turns-left row; the seed becomes a message (D1b) |
| the `(help)` text (bootstrap.clj:63) | "`(seon.turn/turns-left)` reads your remaining turns", "`(my.agent/done)` must be the last form" | rewrite the help text in the same commit (agent-facing; quoted names are not seen by clj-kondo) |
| `problems/deferred-agents` (problems.clj:281-305, 601, 646) | `episode-runs`, `deferred-triggers` | delete the deferred-agent problem with its derivation |
| `transcript/session-stall` (1128), `session-budget` (2028) | `turns-left`, `next-agent-work`, `#'max-episode-runs` | delete with the budget strip, or read calls per wake |
| `eval.drive/terminal-state` (221) | `next-agent-work` | drive's remainder is dead (see "Dead now") |
| `seon.ai/complete` | only `call-turn` calls it | **must be kept explicitly**: a naive closure deletes the whole provider client (≈ 1,000 lines of `seon.ai`) |

**Stored data:**

- the attempt rows (`:seon.ai.attempt/*`);
- the plan intent (`:seon.turn/reply`, `reply-blob`; `:seon.turn.work/situation`);
- the open/close stamps (`:seon.turn/opened-tx`/`closed-tx`, 948 runs on default);
- `:seon.turn/handled`/`trigger` (24).

Runs survive while MCP uses them. **RESET NEEDED** only when the run entity's attributes retire.

**Tests:**

- deleted: `turn_work_test` (14/33 defs, 491 lines), `no_provider_test` (2/2), `turn_disposition_test`,
  `turn_work_cost_test`, `turn_continue_test` (1/6, 146 lines), `background_test`, `cluster/problem_routing_test`
  (2/12), `gen/loop_test` (3/15);
- converted: `cluster/agent_test` (13/66 defs, 1,047 lines hit), `cluster/turn_test` (20/75, 1,151), `turn_test`
  (25/69, 1,301), `turn_loop_test` (10/33, 656), `data_shapes_test` (8/11), `loop_proof_test` (2/6, 769);
- `row-tx`'s 8 test namespaces (`db_test`, `schema_test`, `datahike_fork_test`, `sci/eval_test`, …) use it as a
  fixture writer and convert to a canonical writer.

### D3: permit, backstop, write-refusal park and episodes (382 lines, 16 Vars)

**Deleted:**

- turn: `await-turn-permit!` (5600), `arm-turn-completion-backstop!` (5731, 57 lines), `turn-completion-error`,
  `turn-completion-backstop-failure`, `record-turn-backstop-fault!`, `write-refusal-bound`/`-error`,
  `record-write-refusal-fault!`, `agent-dial`, `dial-absent`, `episode-exhausted!` (5382);
- cluster.agent: `open-turn!` (1418), `turn-completion-failure!`, `cancel-turn-backstop!`, `record-interruption!`
  (1462), `interrupt-turn!` (1395).

**Survivors:**

- `turn/step`, the park and permit branches (S4);
- `cluster.agent/stop-graph!` (1535) and `arm-graph!` (1301): the permit channel and the turn-thread holder;
- `cluster.agent/turn-step` (511): the interrupt holder.

**Config dials (stored facts, 1 each on default):**

| Dial | Readers | Disposition |
|---|---|---|
| `:seon.config.agent/write-refusal-bound` | `turn.clj`, `turn_test` | retire with D3 |
| `:seon.config.run/max-episode-runs` | turn, bootstrap, repl, transcript, drive, 16 tests | rename to calls-per-wake (spec P7) |
| `:seon.config.agent/turn-completion-backstop-ms` | **also `cluster.agent/evaluate-source!` (781), as the MCP await bound**, plus `seon.flow`, `seon.await.edn`, `seon.flow.edn` | it survives D3 unless the MCP await takes another bound |
| `:seon.config.ai/stop` | provider | §6.1 decides |

**Stored data:**

- the faults the park and the backstop recorded;
- the stored dial facts above (retract, or reset).

**Tests:** `turn_backstop_test` (2/4), `turn_test`'s backstop and park deftests.

### D4: the two settlement writers (the plan's wording points at the smaller one)

| Writer | Used by | Size |
|---|---|---|
| `seon.turn.batch/settle` (682) + `record-facts` (631) + `evaluation-record` (611) + `revalidate`/`rebase` | the JVM MCP path (`mcp/jvm-run` → `jvm-request`), and the `:seon.batch/settle` stage of every `program-request` | **110 lines** |
| `seon.turn` settlement: `settle!` (3880), `settle-batch!` (3494, 106), `settle-evaluated!`, `settle-batch-refusal!` (3768, 111), `evaluation-terminal-data` (3312, 115), `refusal-terminal-data`, `deletion-proposals`, `receipt-settle-call`/`-tx`/`-batch-*`, `fault-window`/`with-fault-windows`, `delivery-rows`, `error-tx`, `record-evaluated-call` (1296, 68; the JVM path's `record-call` value), `recover-*`, `close-*` | the SCI model pass and SCI eval_clj (`evaluate-sources` dissocs `:seon.batch/settle`; turn.clj:4954) | **49 Vars, 1,365 lines**, excluding the run open (`system-run-plan`, `submission-open-tx`, `open-call`) |

- Shared by both: the program facts (`turn.batch/program-facts`, `form-facts`, `schema-tx`, …; ≈ 250 lines).
- The plan's D4 row deletes the `turn.batch` side (≈ 110 lines). The census says the `turn.batch` writer is already
  the `seon.batch` stage owner, and the `seon.turn` side is 12× larger.
- Which duties the one owner must keep is a design question (refusal arm, deletion proposals, delivery rows,
  assignment facts, fault windows). I report the sizes only.
- Shape differences:
  - `evaluation-record` stores `:seon.cluster.eval/error` as `(ex-message error)` text and
    `"facts not proposed: …"` (batch.clj:625-627);
  - `evaluate-sources` folds a fault's `core-text` into the `:seon.cluster.eval/diagnostics` string
    (turn.clj:4988-4991).

### D5: the second reply read (215 lines, 11 Vars)

- **Read 1:** `forgive/segments` (forgive.clj:337, an edamame structural reader with its own `structural-ctx`),
  through `turn/segmented-sources` (3009), `planned-sources` (3031) for model replies and `code-sources` (3047) for
  MCP.
- **Read 2:** `turn/read-row` (4663) re-reads each segment with `forgive/next-form` `:code` at the live cursor.
- **The one-read path already exists, unused.**
  - `seon.turn.batch/turn-request` (829) is `program-request` with `:seon.batch/mode :reply`. Its default stages
    are `forgive/read-state` + `sci.batch/read-next` (the live forgiving reader).
  - It has **no caller anywhere**. `code-request` is used only by `test_support`.
  - D5's replacement owner is therefore `turn-request`/`sci-stages`, not new code.
- **Survivors:**
  - `cluster.agent/segmenter` (644) and `submit-source-in-projection` (690). Segmentation happens before the run
    opens, because the run request carries `:seon.turn/sources` and `:seon.turn/reply-size`.
    - The reply size is computed as `(count (pr-str sources))` at cluster/agent.clj:764, a one-printer violation.
  - `system-run-plan` and `source-rows` write source rows at open.
  - `mcp/sci-run` passes the segmenter `'seon.turn/code-sources` (mcp.clj:231). `resources/seon/schemas/seon.agent.edn`
    declares `:seon.agent/segmenter`.
- **Order constraint.** D5 cannot land before the run open stops writing per-form source rows (D2's
  `plan-call`/`source-rows`, and the submission open). It lands with S4, or immediately after it. **Draft S2 is
  ordered before S4: that order does not hold.**
- **Stored data:** none new.
- **Tests:**
  - `cluster/turn_test` (segments);
  - `planned-sources` is used by 8 test namespaces (`ai_test`, `evaluate_sources_test`, `host_test`, `turn_test`,
    `loop_proof_test`, `transcript_test`, `repl_parity_test`, `cluster/turn_test`);
  - `code-sources` is named in 4 test files and `seon.agent.edn`.
- **Stopped WIP:** `stopped-wip/reader-prose.patch` (+156/-48) edits `batch.clj` and `forgive.clj`.

### D6: duplicate lookup machinery (133 lines, 9 Vars, plus repl-lookup's +110)

**Deleted:**

- `sci.eval/directory-value` (2019), `program-documentation` (1835), `program-doc-var` (2079), `program-dir-var`
  (2095);
- `seon.repl/render-directory-ai`/`-html` (36/47; render pair in `seon.repl.edn`);
- `my.program/source` (874) + `file-line` + `nearest` (`declaration` stays: `declaration-pair` uses it).

**Survivors:**

- `render.ns/directory` (ns.clj:235) → `directory-value`;
- `seon.agent.loop/tool-lines`, `namespace-unit`, `function-units`, `test-units` → `directory-value`,
  `documentation-value`, `stored-source` (agent.loop is unwired, see below);
- `install-program-doc!` (2137) and `build-base-ctx` (348-429): the two refer installers;
- the `(help)` text names `my.program/source` (bootstrap.clj:56);
- `test/seon/schema/datahike_parity.edn` names `render-directory-html`. That file is the historical oracle and is
  left alone.

**Stored data:** `:seon.eval/renderer 'seon.repl/render-directory-ai` symbols in history (37 renderer datoms on
default). They are values, not refs, so nothing breaks.

**Tests:** `my/program_read_test` (2/4), `sci/documentation_test` (2/12), `render/value_test` (2/65),
`repl_parity_test`. Stopped WIP: `stopped-wip/repl-lookup.patch` (+285/-246).

### Dead now: no caller in src, tests, resources or skills (444 lines, 26 Vars)

- `seon.eval.drive/run-sample!` (364) and its closure: `run-episode!`, `grading-branch!`, `inbound!`, `await-fact!`,
  `model-attempts`, `completed-results`, `full-transcript`, …; 18 Vars, 336 lines.
  - `terminal-state` is used only by `turn_loop_test`.
  - `run-receipts` stays: `cluster.agent/run-observation` uses it for the MCP reply.
- `seon.render.web/join-package` (9);
- `seon.turn/render-html` (8; no render-pair reference);
- `seon.turn.batch/read-only?` (760), `test-request` (911);
- `seon.turn.batch/turn-request` (829): **keep it; it is D5's owner**;
- `seon.turn.world/test-agent-world` (70), `merge-world` (104). `agent-world` is then used only by tests.

**Kept alive only by tests** (beyond render pairs and agent tools):

- `seon.agent.loop`: the whole namespace (230 lines). It is unwired, because loop-pass's seam is still in
  `stopped-wip/loop-pass.patch` (+357/-408 over `turn.clj`, `bootstrap.clj`, `loop.clj`, `seon.help.edn`).
- `seon.context/selection`/`append-tx`/`compact-tx` (181 with helpers), `turn/row-tx` (123 + helpers),
  `turn/receipt-start-tx`, `turn/committed-attributes` (51), `turn/plan-settlement`, `turn.batch/code-request`.

## Dependency order (HEAD keeps booting; eval_clj, MCP and tests keep working)

1. **Dead now** (−≈ 410; keep `turn-request`). It has no survivors. Tests: `turn_loop_test`'s `terminal-state`
   leg.
2. **D6** (−133 −170 / +110 per repl-lookup). It is independent of the loop. It converts `render.ns/directory` and
   the agent.loop callers, and rewrites the help line.
3. **D1a debug views** (−864, about +30 for the ledger's direct `of-agent` read with an explicit selector). It is
   independent of the model pass.
4. **D1b system turn** (−1,103). It edits `open-turn`, `evaluate-sources` and `turn`'s `:generate` arm. It needs
   `bootstrap/seed-tx` to stop seeding `:generate` first, or in the same commit (loop-pass's patch touches
   bootstrap).
5. **D1d read evidence** (−≈ 60 with reader edits). It comes after D1a and D1b, because their readers go first. It
   is a schema retirement: publish and adopt on a live-store branch, then **RESET NEEDED**.
6. **D1c + the forms context, S1** (−852; +agent.loop's context, already 230 lines in the tree). It edits
   `call-turn`, moves `message-custody`, and retires the context and prompt schema files. **RESET NEEDED** (it can
   share step 5's reset).
7. **D2 + D3 + D4 + D5, S4, one slice.**
   - It deletes −1,718 (D2), −382 (D3), D4 (either −110 or ≈ −1,000 net of what the one owner keeps) and −215 (D5).
   - **The MCP SCI path goes through `step`/`turn`/`resume-turn`**, so all four groups must land with the pass that
     replaces them for MCP, JVM and test entrants, not before.
   - The run entity, `open-tx` (the test runner's attempt writer) and `recover-tx` (boot) stay until the run
     attributes retire.
   - The dials `write-refusal-bound` (retire) and `max-episode-runs` (rename) follow. `turn-completion-backstop-ms`
     stays as the MCP await bound unless S4 replaces it.

## Net lines per group (src, def spans; tests separately)

| Group | src out | src in (estimated) | Test lines in defs that reach it |
|---|---|---|---|
| Dead now | 410-444 | 0 | ≈ 60 |
| D6 | 133 + ≈ 40 (installer/refer duplication) | ≈ 110 (repl-lookup) | ≈ 120 |
| D1a | 864 | ≈ 30 | ≈ 900 |
| D1b | 1,103 | ≈ 10 | ≈ 900 |
| D1c | 852 | agent.loop exists (230) + seam ≈ 50 | ≈ 1,100 |
| D1d | ≈ 60 | 0 | ≈ 50 |
| D2 | 1,718 | the one pass (spec ≈ 550, shared with D3-D5) | ≈ 4,500 |
| D3 | 382 | (in the pass) | ≈ 200 |
| D4 | 110 or ≈ 1,000 | (in the pass) | ≈ 300 |
| D5 | 215 | ≈ 5 (wire `turn-request`) | ≈ 300 |
| **Union** (closure with the MCP path kept) | **≈ 5,700** | **≈ 750-1,000** | **11,769** test lines in 76 files hit; many convert rather than delete |

The spec's "about 3,500 lines out, 550 in" counts D1-D3 without the debug views, D1c's assembly or the test-only
code. Walk-delete's "about 3,400 out, 190 in" covered D1 only, and the census agrees with it (2,837 in def spans).

## Other dead or duplicate machinery in the loop area

1. **Six run-opening writers:**
   - `open-call` (model);
   - `submission-open-tx` (MCP SCI);
   - `record-facts` (JVM, with `opened-tx = closed-tx`);
   - `generated-run-tx` (bootstrap seed);
   - `system-run-tx` (bootstrap supervision);
   - `open-tx` (the test runner's `attempt-writers` and 26 test files).
2. **Five readers of an agent's evaluation rows:**
   - `seon.eval/of-agent`;
   - `eval.drive/run-receipts` (117);
   - `seon.turn/run-receipts` (1534, private);
   - `transcript/history-entries` and `candidate-history`;
   - `turn/latest-evaluations`.

   One MCP reply (`cluster.agent/run-observation`, 852) reads two of them, `history-entries` and
   `drive/run-receipts`, and zips them by ordinal.
3. **Two reply reads** (D5). The single-read entrance `turn.batch/turn-request` is written and unused.
4. **Two settlement writers** (D4). Both feed `seon.turn`'s receipt writers (`record-evaluated-call` is the JVM
   path's `record-call` value).
5. **Three readers of a namespace's public names:** `program-documentation` (eval.clj:1835),
   `render.ns/public-functions` (ns.clj:219) and SCI's `dir-fn`. A fourth goes through
   `agent.loop/namespace-unit` → `directory-value`.
6. **Two refer installers:** `build-base-ctx` (eval.clj:348-429) and `install-program-doc!` (2137). The latter runs
   at every acquisition, and its `db` and `projection` arguments are unused (repl-lookup).
7. **Three resolutions of `seon.cluster.prompt/prompt`:** turn.clj:63, transcript.clj:1505, web.clj:3397.
8. **Test-only code in src:** `row-tx` (123 + 71 schema helpers), `seon.context` selection/append/compact (181),
   `committed-attributes` (51), `receipt-start-tx`, the `seon.agent.loop` namespace (230).
9. **`turns left: N` is printed as prose into the context** (`seon.repl/frame`, repl.clj:74-77). The help text
   teaches `(seon.turn/turns-left)` and `(my.agent/done)` (bootstrap.clj:63). These are agent-facing references to
   D2/D3 machinery that clj-kondo cannot see.
10. **Source docstrings with dates and measurements** (a CLAUDE.md violation) in owners touched by these slices:
    - `forgive/segments` ("## Costs … 2026-09-28");
    - `seon.eval/history-selector` ("3.7 s … (2026-09-27)");
    - `turn/latest-evaluations`;
    - `turn/turn` ("measured 2026-09-07").

    They go when their Vars go.

## Facts the draft plan gets wrong

1. **§3 D4.** The draft deletes "`turn.batch/settle`'s run-shaped `record-facts`" as the second writer. That side is
   110 lines and is already `seon.batch`'s `:settle` stage. The `seon.turn` side is 1,365 lines. The choice of
   owner is open; it should not be presented as already settled.
2. **§5, S2 (D5) before S4.** D5 depends on the run open no longer storing per-form sources, which is D2/S4. The
   one-read entrance (`turn.batch/turn-request`) already exists, unused.
3. **§3 "model pass keeps working throughout" and §2's driver description omit that MCP SCI eval_clj runs through
   the same `step` → `turn :resume` → `resume-turn` → `settle-evaluated!`.** D2, D3 and D4 therefore cannot precede
   the pass that serves MCP.
4. **D3's `turn-completion-*`.** The backstop dial is also the MCP await bound (`evaluate-source!` 781). It is not
   model-loop-only.
5. **The D1 list omits:**
   - `seon.repl/frame` (the turns-left prose);
   - `ai.tokens` calibration;
   - the 300-line graph debug page;
   - test-only `seon.context` selection/compaction.
6. **"The debug ledger reads `eval/of-agent`".** With the default selector that read refuses on root today. It must
   pass an explicit selector, or D1d must land first.
7. **The spec's line counts (3,500 out) undercount by about 2,000 src lines** once the debug views, the test-only
   code and the dead-now code are counted.

## Constraints the plan must respect

- `seon.ai/complete` and the provider client (≈ 1,000 lines) are reached only through `call-turn` today. A
  closure-driven deletion must keep them explicitly.
- `open-tx` is the test runner's attempt writer (`seon.test`, test.clj:179-200) and a fixture for 26 test files.
  `recover-tx` is called at boot (`cluster/recover-runs!`). Both live until the run entity retires.
- Quoted references that clj-kondo cannot see must be converted by hand:
  - mcp.clj:231 `'seon.turn/code-sources`;
  - web.clj:2439-2441 action symbols;
  - `seon.agent.edn` `:seon.agent/segmenter`;
  - the help text (bootstrap.clj:56, 63);
  - two skills and `vocabulary.md`.
- Retiring the schema (context, capture, contribution, prompt, read evidence, render.history,
  `write-refusal-bound`) needs publish and adopt on a live-store branch, then one reset. The stored dial facts exist
  on default (one each).

## Implications for the plan

- Land these first (no survivors on the model or MCP path): dead now, then D6, then D1a.
- Pair D1b with bootstrap's seed conversion, then D1d (reset), then D1c with S1.
- Treat D2+D3+D4+D5 as one S4 slice that serves MCP, JVM and test entrants.
- Re-decide D4's owner from the sizes above.
- Wire `turn.batch/turn-request` for D5 rather than writing a reader.
