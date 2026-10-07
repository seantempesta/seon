---
type: design
status: proposed (dual review and owner approval come before deletions resume)
created: 2026-10-07
tags: [design, agent-loop, deletion, context]
---

# Design: retire the walk-based model context (lane walk-delete)

**What exists.** An agent's model pass has two halves:

1. A *system turn* walks the agent's record several hops out (`seon.render.walk/neighborhood`), turns each
   rendered unit into source forms, re-evaluates the ones whose stored read evidence went stale, and saves them as a
   closed run.
2. The prompt (`seon.cluster.prompt/prompt`) folds every saved evaluation's shown text back into one string
   (`seon.render/acquire-context!` → `seon.render.web/derive-context!` → `seon.render.walk/history`). The string is
   captured (`seon.context/capture-tx`) before `seon.ai/complete`.

The debug page displays that assembly. Every ordinary evaluation stores read evidence so that the next system turn
can decide what to re-run.

**After.** The happy path is the one in `lane-rules.md` ("One happy path", owner 2026-10-07): context = the forms
that ran plus the agent's own forms with their real results → model → one read → eval → record → print. Lane
loop-pass's seam puts the proc transcript (S4) where the prompt was. Everything below exists only to build or show
the old context, and goes.

Every row of this design serves one step of that path: it deletes a second producer of the "context" step and a
second writer on the "record" step. Nothing new is added except one ledger reader (§2). The rest is deletion.

Revision: HEAD `ed5898cb3` (slice A `be5ac2f5e` landed). Line numbers are HEAD's. loop-pass's uncommitted `turn.clj`
seam edit is not counted.

## 1. What exists: the closure, its data and its cost

### 1.1 The model pass in `seon.turn` (src/seon/turn.clj)

| Piece | file:line | Callers | Writes | Notes |
|---|---|---|---|---|
| `open-turn` refresh | 4282, call at 4312 | `turn` `:open` | a closed **system run** per opening (when reads changed), evaluations, and read-basis re-assertions on unchanged evaluations (2159) | runs `system-turn` with `write? true` before every provider turn |
| `system-turn` | 2030-2237 | `open-turn`, web "System turn" action, 13 test sites | as above, via `record-evaluated-tx` (1365) in one `db.fn/call` admitted only while idle and while `latest-evaluations` is unchanged | |
| `declared-sources` | 1725 | `system-turn`, `generate-turn` | — | `walk/neighborhood` over the agent record, then each unit's render source or data → forms |
| `unit-read`, `source-events`, `source-key` | 1692, 1679, 1685 | `declared-sources`, `system-plan` | — | the requery form of a data render (`render/render-form-value`) |
| `latest-evaluations` | 1794 | `system-turn` (twice: plan, then writer re-check) | — | pulls every evaluation of the agent and decodes the kept ones' read evidence |
| `system-plan`, `read-only-evaluation?`, `code-currency`, `code-current?` | 1909, 1853, 1885, 1899 | `system-turn`, `generate-turn` | — | re-runs a read-only form whose read evidence is no longer current (`db/read-evidence-current?`) |
| `generated-read-fault` | 1960 | `system-turn`, `evaluate-sources` 4984 | a diagnostic on a generated form | |
| `generate-turn` | 5260 | `turn` `:generate`; `next-agent-work` → `resume-or-generate` 2798 | appends generated forms (`append-generated-call` 798/840) and settles them | the bootstrap seed's opening (`seon.bootstrap/seed-tx` → `generated-run-tx` 847) |
| `call-turn` prompt and capture | 4359; prompt 4511, capture 4537, provider 4573 | `turn` `:call` | `seon.context.capture` entity + contributions, then the attempt row (`record-attempt!` 4066) | loop-pass's seam replaces 4511-4553 |
| `compact!`, `compact-call` | 2271, 2239 | web "Compact" action, 5 tests | retracts an idle agent's evaluations (context compaction) | |
| stored read evidence | `evaluate-sources` 4965; `receipt-read-evidence-tx` 1224; `recorded-evaluation` 1255 | every evaluation that binds no Var | `:seon.cluster.eval/read-evidence` component rows | the source comment at 4961 says: "Read evidence has one consumer, the pre-turn refresh (`system-plan`)" |
| lazy delays | 59-66 (`cluster-agent-acquire-context!`, `cluster-prompt-prompt`, `context-capture-tx`) | the above | — | |

### 1.2 The prompt and capture

- **`seon.cluster.prompt`** (460 lines): `prompt` 422, `select` 303, `compose` 291, `acquire-context-report` 368,
  `capture-mismatch` 337, calibration `model-calibration` 106 and `agent-calibration` 140.
  - Callers: `turn/call-turn`; transcript's ledger calibration and outline; web `sent-context-html`.
- **`seon.context`** (420 lines):
  - capture: `capture-tx` 359, contributions, `capture-ai`/`capture-html` pair;
  - agent selection: `selection` 76, `append-tx` 160, `compact-tx` 206. These have test callers only;
  - `message-custody` 257: its live caller is `seon.render.transcript/message-entry` 237, the agent-history
    render, which is not a context producer.
- **`seon.render/acquire-context!`** (render.clj:1721) → **`seon.render.web/derive-context!`** (web.clj:2463) →
  **`seon.render.walk/history`** (walk.clj:840) → `seon.eval/of-agent`. Supporting pieces:
  - `history-segments` 2422;
  - `render/call-cache-evidence`, `same-invocation-evidence?` and `retained-program-current?`. derive-context!
    is one of their callers; `render-call` and the page derivation also use them, and that use stays.
- **`seon.ai.tokens/budget-report`**: its only caller is `prompt`.

### 1.3 The debug views of that assembly

| Piece | file:line | What it shows |
|---|---|---|
| `transcript/render-session`, `render-session-loading` | 1259, 1231 | "Context at turn N": the acquired prompt, colourised, with re-read folding |
| `transcript/render-outline` and `outline-*` | 1531, 1412-1530 | one line per prompt unit with calibrated token cost |
| `transcript/render-ledger-context` | 1865 | the session view under a ledger card |
| `transcript/ledger-acquisition`, `acquire-ledger-data` | 1613, 1730 | the ledger's data, fetched through `acquire-context!` with `::ledger? true` |
| ledger "WE GENERATED / CONTEXT BEFORE REPLY / Full context as sent" | `ledger-turn-body` 1756 | the system turn's generated forms and the captured prompt size |
| `churn-problem`, `prefix-problem`, `render-captured-prefix`, `prefix-subject`, stale-but-unchanged rule | 1920, 1988, 1972, 1966, `session-problems` 2056 | problems that exist only for system re-reads and prompt prefixes |
| web `sent-context-html` | 3391 | `prompt/prompt` text "as sent" |
| web `debug-outline-response`, `?prompt=true`, `?context=`, `?sent=` | 3213, 3560-3600 | routes to the above |
| web `graph-debug-html` and 9 helpers (`?n=`) | 3407 (about 300 lines) | "the owner's context-tuning page": the walk drawn as the context graph |
| web "System turn" and "Compact" actions | `session-controls` 714, `change-context` 2430, `context-action-response` 3751 | buttons that run `system-turn` and `compact!` |

### 1.4 Cost (measured on default, 2026-10-07)

- **System turn per opened turn**:
  - `seon.turn/system-turn {:write? false}` for root (JVM pid 41301, a read-only preview) took **43.6 s**, with
    `system-plan` at 42.3 s. It exceeded the MCP's 32 s bound and finished in the background.
  - The three other calls in this JVM's life averaged 2.2 s (max 2.8 s), with `latest-evaluations` max 2.2 s and
    `declared-sources` about 340 ms (its `walk/neighborhood` 320 ms).
  - Every provider turn pays this before it opens, and its commit adds a system run.
- **Stored read evidence per ordinary evaluation**:
  - On default's branch, 24,064 rows sit on 122 of 1,383 evaluations (db-watch: 19% of datoms, 41% of value
    bytes).
  - My own probes on agent walk-delete: `my.program/callers` stored **232 rows (198 KB as EDN)** and
    `my.task/open` 141.
  - Every MCP `eval_clj` that reads the database pays this write, and only the system-turn refresh ever reads it.
- **Prompt**: none ran this boot, because default runs with no provider. History notes in source:
  - "~40 ms each, 36 s per prompt" when the opening re-keyed projections;
  - `latest-evaluations` 3.8 s before its decode fix.
- **Read-evidence capture during evaluation** (`db/*read-evidence-sink*`, `append-read-evidence!`) also feeds the
  page render cache, so the in-memory capture stays. Only its stored copy goes.

## 2. What survives, and what it reads instead

| Survivor | Today reads | After |
|---|---|---|
| The model pass (`turn` `:call`) | `prompt/prompt` + `context/capture-tx` | the proc transcript (S4; loop-pass seam). Whether the exact prompt is still recorded before the paid call (capture ruling 4) is loop-pass's decision; if it is, it should be one attribute on the run, not `seon.context.capture` with contribution components |
| `turn` `:open` | `system-turn` refresh, then `open-call` | `open-call` only |
| `:generate` runs (bootstrap seed) | `generate-turn` → walk | bootstrap stops seeding a generated opening (spec §5: "the generated opening becomes a message to root", loop-pass owns bootstrap); until then `generate-turn` closes the run with no forms |
| MCP / eval_clj recording | `record-evaluated-call` + read-evidence rows | `record-evaluated-call` unchanged; no read-evidence rows |
| `seon.eval/of-agent` | default selector expands read evidence | default selector without read evidence; `history-selector` deleted |
| Debug ledger | `acquire-context!` with `::ledger? true` | one private `ledger-data` reading `seon.eval/of-agent` with the ledger selector. It keeps reply, results, effects, stalled/fabricated/errors/repeated/empty-reply/directory/fault problems and the budget |
| Debug session controls | system turn, virtual turn, compact | virtual turn only, posted straight to `change-context` |
| Web pages | `render/shared-cache`, `walk/root-acquisition`, `neighborhood`, `graph` (via `related`), `continue` | unchanged |
| Agent-history render (`transcript/render-ai`, `message-entry`) | `context/message-custody` | `message-custody` moves beside its one caller in `seon.render.transcript` |
| Token calibration (`seon.ai.tokens`) | captures joined to attempts via `prompt/model-calibration` | deleted with the capture unless loop-pass keeps a budget that needs it (provider lane: "prompt budget fits window") |

Not in this closure, but nearby (checked; each needs its own measurement before any cut):

- **Source-producing renders**:
  - `:seon.render/source-blocks` and `:seon.render.call/source-output?` in `seon.render/render-call`
    (render.clj:1599-1635);
  - the web render experiment that evaluates a render's source (`render-source-call` web.clj:1357).

  `declared-sources` was their main consumer. CLAUDE.md says renders return data, never forms. After this cut, the
  remaining producers are the `:seon.render/form` faces (requery forms, which are data). Proposed as the next design
  item, not part of these slices.
- **`seon.render/shared-cache`** is an atom beside the ctx, not a `seon.cache` kind (CLAUDE.md "One central cache").
  It serves web pages, so it is not this cut. It needs its own task.

## 3. Deletion order (each slice boots, and keeps eval_clj, MCP and tests working)

0. **Seam (loop-pass)**: `call-turn` sends the proc transcript (S4). After it, `prompt/prompt` and
   `context/capture-tx` have no production caller.
1. **B1: debug views**. Files: render.clj, web.clj, transcript.clj, schemas `seon.render.edn` and
   `seon.render.transcript.edn`, transcript_test, web_test, web_context_test, plus a patch to web_debug_test
   (held by repl-lookup).
   - Delete the session, outline, ledger-context and sent views, the captured-prefix pair and the graph page.
   - Convert the ledger to `ledger-data` over `eval/of-agent`.
   - The page keeps only the virtual-turn action.

   This slice is independent of the seam. A patch exists (scratchpad `slice-b1-debug-views.patch`: render.clj
   +8/-10, web.clj +25/-422, transcript.clj +103/-747, schemas -7). The graph page was added after that patch was
   made.
2. **B2: system turn and generated opening** (turn.clj, after the seam lands and loop-pass releases it).
   - Delete `system-turn` and its private closure (§1.1), `compact!` and `compact-call`, `append-generated-*`, and
     the `open-turn` refresh.
   - `generate-turn` closes the run without forms. It is deleted once bootstrap stops seeding `:generate`.
   - `generated-read-fault` goes, and its use in `evaluate-sources` with it.
   - Then `render/render-form-value`, `sci.eval/rendered-evaluation` (patch to repl-lookup), `sci.kernel/pending?`,
     `db/diff` and `db/apply-diff` become dead and go in the same slice.
3. **B3: prompt, capture and history assembly**.
   - Delete `seon.cluster.prompt`, `seon.context` (moving `message-custody` to transcript),
     `render/acquire-context!` and its delays, `web/derive-context!` and `history-segments`, and `walk/history`.
   - Also `eval/history-selector`, `tokens/budget-report`, and their schemas (`seon.cluster.prompt.edn`,
     `seon.context.edn`, `seon.context.capture.edn`, `seon.context.contribution.edn`, the context keys in
     `seon.render.edn`, `seon.render.history.edn` if no key survives).
4. **B4: stored read evidence**.
   - `evaluate-sources` stops attaching `:seon.cluster.eval/read-evidence`, and `receipt-read-evidence-tx` goes.
   - The attribute and its component schema are retired in `seon.cluster.eval.edn` and `seon.eval.edn`.
   - `eval/of-agent`'s default selector drops it.
   - Publication and adoption on a branch prove the slice. **RESET NEEDED** after it lands.

Each slice is proven by `bin/lane commit … --test` naming the tests that reach it, plus at least:

- an eval_clj SCI run and a JVM run on a branch (recording);
- `seon.test/run` of one namespace;
- the debug page answering 200 for root.

## 4. Size, schema and tests

| Slice | src lines out (est.) | in |
|---|---|---|
| B1 | about 1,450 (patch -1,179 + graph page about 300) | about 130 |
| B2 | about 900 (§1.1 rows) | about 10 |
| B3 | about 1,050 (prompt 460, context 420 less `message-custody` 47, render/web/walk about 170) | about 50 |
| B4 | about 60 | 0 |
| **Total** | **about 3,400** | **about 190** |

Schema attributes retired (stored data; **RESET NEEDED** once, after B3/B4):

- `seon.context.capture/{id, run, basis-t, prompt, contributions}`;
- `seon.context.contribution/{id, position, hash, tokens, agent, evaluations, error}`;
- `seon.cluster.prompt/{error-agent-id, derivation-observation}`;
- `seon.cluster.eval/read-evidence` and its component entity.

Value schemas without stored data also go: request, result and context keys in `seon.render.edn`,
`seon.turn.edn` (`system-request`, `system-result`, `refused-system-agent`), and `seon.render.history.edn`.

Tests:

- **Deleted, because their behavior is retired with its mechanism** (clojure-testing §3 rule 1):
  - whole files: cluster/prompt_test (577), context_test, context_capture_test, context_capture_history_test,
    context_selection_test, context_cookbook_trial_test, rereads_test (233), rereads_panel_test, turn_error_test;
  - single tests: the system-turn, compaction and generated-opening deftests in turn_test, cluster/turn_test,
    loop_proof_test, turn_loop_test, db_test (`system-turn-renders-a-changed-read-with-value-diff`),
    repl_grammar_test, task_steps_test (compaction), render/faults_test, error_recording_test, root_pull_test
    (history), transcript_test (`one-reply-reads-identically…`, prefix profile), web_debug_test (session,
    outline, graph, prefix, churn), web_context_test (`context-and-page…`, `selected-session-defers…`), the golden
    `next-turn-context-keeps-runtime-history-through-an-empty-turn-and-a-wake` (root approved) and the
    bootstrap_resume_child generate-turn boundary.
- **Converted**:
  - `context_blocks_fixture/install-running!`, used by schema_redeclare_test and loop_proof_test, loses its
    system-turn opening;
  - web_test's debug fetches use `/agent/root/debug`, not `?prompt=true`, and the action-set assertion becomes
    `#{"virtual-turn"}`;
  - run6_stall_test stays as is (it reads `session-problems`, whose signature is unchanged);
  - the ledger tests in web_debug_test (`passive-ledger…`, `ledger-derives-shared-data-once`) read `ledger-data`;
  - mcp_entrance_test's "a form that binds a Var stores no read evidence" becomes "no evaluation stores read
    evidence" (B4).
- **Root's call** (clojure-testing §3): loop_proof_test is an end-to-end virtual loop through system turns. Its
  surviving half (virtual turn → evaluate → record) should be kept as the loop's proof and re-pointed at
  loop-pass's context.

## 5. Risks

1. **The seam order.** Deleting B2 or B3 before loop-pass's seam leaves `call-turn` resolving a deleted Var at
   runtime (`requiring-resolve`, invisible to clj-kondo). B1 is the only slice independent of the seam.
2. **Bootstrap `:generate` runs.** Until bootstrap stops seeding them, `generate-turn` must still close such a run.
   Otherwise the seeded agent's run stays open and every later wake meets `agent-already-running`.
3. **Capture ruling.** The capture is the evidence that a paid call's prompt existed before the call (ruling 4).
   If loop-pass needs that evidence, it must re-home it in the seam. B3 must not land before that decision.
4. **Read-evidence retirement is a schema change.** Proof is adoption on a branch of the live store, then one
   reset. Stored rows (24k on default) become unreadable history for retired attributes.
5. **Hidden consumers.** Quoted symbols and `requiring-resolve` escape clj-kondo. Each slice re-runs the closure
   script (scratchpad `closure.clj`) and `rg` for the quoted names, including resources and config.
6. **Shared files.** web_debug_test and sci/eval.clj belong to repl-lookup, and turn.clj and bootstrap.clj to
   loop-pass. Each slice either sends patches to root or waits for the file.

## Findings on the way

- `(my.task/open (seon.db/db))` took 1,443 ms with 18,480 calls (`query-variable?` ×8,895). Existing task:
  `my.task/open-takes-over-a-second-validating-query-variables`.
- `my.program/callers`' refusal example prints a live `#object[datahike.db.DB …]`.
- The `system-turn` root preview took 43.6 s against the 32 s MCP bound. That is this design's main cost number,
  and the code is deleted rather than optimized.
