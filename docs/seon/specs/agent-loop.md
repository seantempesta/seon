# The agent loop: one pass for every entrant (approved 2026-10-07)

This spec was rewritten in place from root's approved plan (research and three dual-review rounds:
`docs/research/agent-platform/2026-10-07/root-plan/` and `plan-research/`). The previous design's context
section is retired; its non-context invariants are kept in §4.9.

Status: APPROVED by the owner (2026-10-07). v4.1. v4's third-round reviews (`review-plan4-opus-notes.md`, `plan-v4-review-notes.md`)
asked for wording-level corrections, which are applied here. v2 and v3 were each dual-reviewed: `review-plan-opus-notes.md` and `plan-v2-review-notes.md`, then
`review-plan3-opus-notes.md` and `plan-v3-review-notes.md`, all beside this file. Every finding is either decided
here or listed in §7 as an owner decision. After the owner approves, this replaces `docs/seon/specs/agent-loop.md`
in place and keeps its normative details named in §4.9.

Evidence is in `../plan-research/r-*-notes.md`. Numbers are single measured runs.

## 1. Principles (owner rulings, 2026-10-07)

1. **The agent is a REPL.** ONE path: read → eval (SCI) → record → the one value printer. One cache
   (`seon.cache`), one loop. No second parser, evaluator, printer, store, cache or harness.
2. **The same machinery for every entrant.**
   - Model agents, MCP `eval_clj` (SCI and JVM) and test runs share the pass.
   - Each entrant supplies its read and eval stage as values: JVM keeps host read and eval, a test keeps its
     resolved body.
   - Lanes exercise the code Seon agents run.
3. **The reader is a plain REPL read** (§4.3): a linear line scan, then edamame/SCI `parse-next`, one form at a
   time, interleaved with eval. No repair, no structural trials, no second read.
4. **Context is forms that ran** (§4.4):
   - the opening (`(help)`, the task's `doc`, references' `doc`/`source`) once per assignment;
   - inputs (messages, errors, proof results) each delivered by a recorded form;
   - the agent's own forms with their real results.
   - Nothing is re-executed or re-rendered.
   - History is whole for now.
   - No `;; =>`.
   - The REPL vs DATA presentation is decided by experiment and agent feedback; a tie goes to REPL.
5. **One machinery transaction per pass**, derived at the writer on the current head. The agent's own writes
   commit when its forms run.
6. **Standard lookups** return data, on SCI's `clojure.repl`.
7. **Done means proof tests are green.** `complete!` only bails, with a reason. A task needs proofs before launch.
8. **Budgets** (§3). Over 1 s or 10 MB of Seon work is stop-and-fix in the turn it is seen. A number that gets
   worse blocks the slice.
9. Delete before adding. Reuse libraries. Understand before designing. Dual reviews. The owner approves core
   designs. Reset whenever needed (~7 s, no migrations), with the §6 manifest.

## 2. Today (measured; corrections applied)

| What (workload) | Measured | Source |
|---|---|---|
| model turn, stub provider, warm, 1 specimen | 2.1 s, 3.65 GB (whole-JVM window), ~400k checked calls, 0.94 MB written, 6 machinery commits | r-turn §3a |
| its system-turn refresh | ~80%: 1.3-2.3 s, 3-4 GB; 228 datoms here (202-408 across specimens) | r-turn, r-context |
| the turn without the refresh (stages 2-6) | ~457 ms, ~550 MB | Astra v2 §7 |
| MCP SCI `(+ 1 2)` | 1 commit, 27 datoms, ~150 KB, 205-358 ms, 159-232 MB. Settle 96 ms (of it, the fault-window query 34 ms), run-observation reread 27 ms, per-form kondo ~20 ms | r-record, Opus v3 |
| a `defn` pass / the next pass | 712-733 MB per call; next pass 1.0-1.3 s, 3.2 GB (whole-program reacquire). Lane `defn-cost` fixing it | r-record, live profile |
| reply read, a 76,408-character mixed reply | 4.1 s, 94 GB. Stop-gap lane `forgive-fix` | r-read |
| one-test `my.test/check` | cold 2.3-6.0 s, warm 384 ms. After 4c9ccb3f7: cold 584-702 ms / 817 MB, warm 126 ms / 191 MB. Lane `test-cost2` continues | test-cost |
| full src/test contract census (MCP) and the per-edit hook check | census 1.7-3.0 s, 3.2 GB; per-edit hook 165 ms / 188 MB. Fixed in 730b63fa0: hook edit 57 ms / 80 MB, census 1.7 GB (edamame boxes each character; our own file reads move to Clojure's reader) | Astra, contract-check |
| `or`+join query shape (historical baseline) | 60 ms, 180 MB for one symbol. Fixed in 150feaf5d: live 13-14 ms, 21-23 MB for 1-142 symbols, the rest is a Datahike scan (lane dh-fix2) | Astra v2, q-orjoin |
| reset | leaked hosted agents (5.1 GB heap); fixed in 71439648a. Hosting an agent costs 1.4-1.6 s and ~100 MB; a reset 6-13 s. Lane `host-cost` | reset-leak |
| store per small commit | ~150 KB (root copies 36%, index nodes 64%); accepted by the owner for ordinary small passes | r-record |

Behavior seen (model transcripts plus reader/MCP probes, r-read §3): invented results (`ns=> … #:seon.repl{…}`, `;; => 42`), prompt markers
stripped while their namespace is ignored, a prose-only reply evaluating its first word, a refused imitation
showing as a nil core fault (batch.clj:390).

## 3. Budgets

Measured by the S0 probe each slice: wall ms, allocated bytes (per thread where possible), checked calls,
transactions attempted and durable, store bytes, tokens. Profiler marks (~3.5 MB each) are fixed or excluded first.

| Pass kind (Seon's work only; excluding the provider and the agent's own forms) | Time | Allocation | Machinery txs |
|---|---|---|---|
| ordinary (no definitions) | < 200 ms | < 5 MB | 1 |
| definition (one-line `defn`) | < 300 ms | < 30 MB | 1 |
| the pass after a definition | < 150 ms | < 10 MB | 1 |
| opening or input pass | < 200 ms | < 5 MB | 1 each |
| context append (each pass) | < 10 ms | ≤ 3× the appended text | — |
| cold history acquisition (proc start / new assignment) at 1/10/200 exchanges | measured | measured, then a budget set from evidence | — |
| one-test check (runner setup, selection, recording, cleanup) | < 300 ms cold, < 150 ms warm | < 20 MB | as §4.2 |
| a new fault signature | — | — | +1 at the task home |

Store: ~150 KB for an ordinary small pass, as observed (not a universal bound). Definition and blob passes are
measured and reported.

**The definition-pass and test-check allocation targets exceed the 10 MB stop-and-fix line (owner decision §7.6). S0
uses the HTTP fixture for the model entrant; other entrants use their real entrances.

S0 acceptance workload:**
- histories of 1, 10 and 200 exchanges at 2 KB of shown text each;
- one ordinary pass, one `defn` pass and the pass after it;
- one opening and one input;
- cold acquisition and warm execution reported separately;
- both encodings during S8.

The whole-history window refusal (§4.4) bounds larger histories.

## 4. Design

### 4.1 The pass

```
entrant → forms text
one cursor, as at a REPL, for each form:
    READ    next form (§4.3)
    EVAL    in the agent's world and current namespace; agent writes commit as they run;
            observations captured WHEN they occur (Var writes with their metadata at that time,
            macro expansions, deletions), never re-read from mutable Vars at the end
    PRINT   the result value, once, while observation is open → shown text, handle, diagnostics
            (lazy realization happens here and is observed)
once per pass:
    ANALYZE ONE clj-kondo run over the reader's CODE SLICES only: non-code characters blanked to
            spaces with newlines and columns kept, so spans are original offsets, and each slice's
            observed namespace and aliases carried along; joined to the captured observations by
            span; skipped when nothing was defined. JVM passes have no SCI observation and no
            analysis. Original text is kept for history
    RECORD  ONE transaction, [:db.fn/call] at the writer on the current head:
            evaluation rows (ids = pass id + position, minted before eval; agent ordinals
            assigned at the writer), program facts (analysis + one batched digest check at the
            writer, which guards changed definitions as program-facts-call does today),
            the call row, agent facts, fault occurrences; staged blobs published around the
            commit through `blob/with-publication!` (every entrant; blob bytes counted separately).
            The stored exchange = the shown result + every diagnostic known before the commit.
            Pending-commit ownership: S5 makes `seon.db/transact-call` return its pending
            completion and keep the blob permit, so an unknown outcome can be awaited
    FINISH  each reply envelope = its shown result + diagnostics accumulated through RECORD,
            composed by the value printer from already-printed units (no re-render, no reading
            EDN back). Diagnostics that arise after the commit (installation, commit failure) are
            delivered immediately to the caller, and durably through the error/input path (its
            counted task-home write), so the next context sees them as an input
```

**Same-pass admission (blocking design proof for S5).** Today `apply-pending` (`src/seon/batch.clj:480-501`)
makes earlier forms' facts visible to later forms. S5 must show how captured observations plus one end-of-pass
analysis preserve each of these, with a probe per case, before any code:
- a macro defined then used;
- def → redef → use;
- def → unmap / remove-ns;
- ns and alias changes;
- nested and cross-namespace definitions;
- a private `def`;
- a schema-only registration then use;
- a failed definition followed by forms that depend on it;
- definitions created during lazy realization;
- prose containing a quote before a definition (the analysis input rule; Astra's live specimen).

If a case needs analysis before a later form, the design says so with its measured cost.

**What the one transaction stops writing** (each deleted together with its last consumer):

| Item | Fate |
|---|---|
| read evidence, `executed`, `code-evidence`, receipt | consumed only by the refresh; deleted in S4 |
| `*open*` intent and its bindings, `open-tx`, boot `recover-tx` | still used by effects (`effect.clj:210`) and effect-reaching tests (`test.clj:179`); deleted in S6 with effect provenance and test-attempt conversion |
| the fault-window query (34 ms) | replaced by the fault occurrences the pass observed (S5) |
| problem-assignment routing | the defect that mailed an agent's own errors to another agent (task 5e7b36bfd02d); deleted |
| the run-observation reread | replies come from the held pass (S5) |
| unread token, elision and transaction hot/repeated/ms stamps | deleted |
| profile measurement upserts (51-78% of datoms) | owner decision §7.2 |

### 4.2 Entrants

| Entrant | Read / eval | Serialized by | Machinery transactions |
|---|---|---|---|
| model | §4.3 / SCI | the agent's proc | 1 per pass, plus opening/input passes |
| MCP SCI | §4.3 / SCI | the agent's proc (request port, as today) | 1 |
| MCP JVM | host / host | **not serialized.** Each host invocation is owned until its exit. Its result is bound into the agent's SCI result owner only when that context is already acquired; otherwise its handle answers gone-value data with the class (a named behavior change; `mcp_entrance_test` converts). Ordinal assigned at the writer. Never changes the agent's current namespace or answeredness | 1 |
| test member | its resolved body stage (SCI or host) | its execution ownership | 1 member pass |
| test request | the runner | — | 1 request aggregate on the recording connection; task-home completion its own write (§4.9). The runner's admission, reach/positive evidence and late-exit handling are kept |

- **Faults.** The branch-local occurrence rides the pass transaction. The authoritative copy is recorded once per
  signature at the task home, as the errors spec rules. That write is its own counted transaction, so an error
  survives its branch retiring.
- **Effects.** Provenance is pass id + position, never a ref to an evaluation not yet recorded.
- **Requests.** A timed-out MCP SCI caller's queued submission is dropped if it has not started. If it has
  started, it runs to its actual exit and is recorded (no overlap).

### 4.3 The reader

- **Fences.** A line of three or more backticks, untagged or tagged `clojure`, `clj`, `cljc` or `edn`, opens a code
  fence. Any other tag opens a non-code fence. A fence closes only with a line of at least as many backticks and no
  tag. Inside a code fence every line is code, and prompt markers there are an error the agent sees. Inside a
  non-code fence every line is prose, a tagged fence line included.
- **Outside fences:**
  - a line whose first character is `(`, `[`, `{`, `'`, `@` or `^`, or `#` followed by a non-whitespace character
    other than `#`, starts a form (so `#1 priority` is a read error the agent sees);
  - `#` followed by whitespace or `#` (a Markdown heading) and a leading backtick are prose;
  - while a form is open, following lines continue it whatever they start with;
  - all other lines are prose, so a lone word like `Done.` is prose;
  - a lone symbol is code only inside a fence.
- **Regions.** No read crosses a fence line: the text between fence lines is one region, and a read ends at its
  region's end.
- **Read errors.** A form that fails to read is its own error, in every mode (model reply, MCP SCI and JVM), and
  reading continues after it:
  - a collection open at the failure spans to its matching closer, so the forms inside it never run;
  - any other failure (a token, a stray closer, an odd map) spans to where the reader stopped;
  - a collection that never closes is unfinished: it spans to its region's end.

  Nothing is repaired and nothing is re-read.
- **No-value constructs.** `#_` and a reader conditional that selects nothing yield no value. They are skipped
  within their region; classification resumes after them (outside fences, at the next line), so they never carry
  prose or a fence line into a form.
- **Prose** is attached as the next form's comment. Trailing prose is kept on the last exchange. A prose-only
  reply records one exchange with the "no form" refusal and no evaluation.
- **Imitations.**
  - The reserved shape `#:seon.repl{…}` (and `{:seon.repl/…}`) as a form is never evaluated. It is that form's read
    error, flagged "only the REPL supplies results", which fixes the nil fault.
  - A `;; =>` line or an `ns=>` line outside fences is prose. Reporting it once as an imitation is deferred to the
    exchange (S4/S6).
  - This is a syntax rule, not a truth detector.
- **Same line:** after a top-level form closes, the rest of that line (outside a fence) is prose.
- **Compatibility.** Outside fences, bare literals and indented forms are prose (a deliberate compatibility
  choice); `(help)` teaches fences.
- **Reading** uses SCI's `parse-next` with the agent's live options, not bare edamame.
- **MCP** code is read as one code fence: every line is code (so `result/e…`, `*1`, `:k` and literals evaluate).
  Code that Seon generates or a page authors (opening blocks, preview sources) is read the same way.
- **Slicing.**
  - S2b-1 lands these rules in today's stepper `seon.sci.forgive/next-form`, which `segments`, the live row read and
    the JVM read share (no Parinfer, no trials, linear). It lands before S1, whose `(help)` teaches the fence rules.
  - S6 lands the one cursor and deletes `segments`, `forms` and the second read together with their consumers
    (`source-rows`, `submission-open-tx`, `plan-call`, the quoted MCP segmenter and its schema key).

### 4.4 Context and inputs

- **Opening pass** (origin `:opening`): runs once per assignment revision.
  - It is an ordinary pass through the proc and the fold, recorded in its own transaction before any model call.
  - Its completion fact is written beside its evaluations on the agent's branch, keyed by assignment id + task
    revision. A changed task declaration or a new assignment id causes a new opening; reassigning an unchanged
    task does not.
  - A crash mid-opening reruns it.
- **Input pass** (origin `:input`): runs when a wake carries something not yet delivered (a message, an error
  delivered to this agent, a proof result).
  - One generated form (name TBD) runs as its own recorded pass before the model call.
  - The pass supplies the identities it actually read. The writer validates them and records them on the
    evaluation, so a restart never appends the same input twice and newly arrived items are never marked
    delivered. Task-home items (proofs, errors) are read explicitly at the task home with per-home basis
    evidence; branch transaction numbers are not compared across homes.
  - It reads at a captured basis, which the model call carries as its presented basis. A message arriving
    between that read and the model reply stays due.
- **Model call context:**
  - The proc holds the agent's transcript text for the assignment and appends each pass's finished envelopes.
  - It reads ordered history once: at proc start or a new assignment, with ONE narrow read of `:opening`,
    `:input` and `:model` exchanges.
  - The same origin predicate applies to appending and to restart acquisition. Origins (S3): `:opening`,
    `:input`, `:model` (shown), and `:outside` (MCP SCI and JVM) and `:test` (not shown: owner ruling, outside
    runs stay out of the model's history; `an-sci-call-answers-what-the-agents-next-context-reads` converts).
  - Old `:system` rows are not shown: §6 makes S4 a reset point.
- **Presentations:** REPL is the form as sent, then its envelope. DATA is the envelope map including `:form`.
  Both are composed by the value printer at FINISH from already-printed units. During S8 both are stored and
  counted; the losing one is deleted afterwards.
- **Handles** keep their identity across restarts and answer gone-value data after their runtime is lost.
- **Window:** when whole history exceeds the provider's window, the call refuses with an error the owner sees.
  No clipping.
- **Cost of a wake cycle** = opening (once) + input pass + model pass, each 1 transaction, measured as a cycle.

### 4.5 Work, continuation, answeredness

| After a model pass | Next |
|---|---|
| ≥1 form evaluated, no bail, calls left | call the model again (owner decision §7.1) |
| no form (prose only), or only read errors | stored disposition `:wait`, then wait for a new outside wake (the original wake is answered) |
| provider failure or context refusal | counted call, no evaluations, answeredness unchanged, terminal reply |
| call cap reached | disposition `:capped`; one message to the assigner; the next outside wake resumes |
| `complete!` (bail) | disposition `:bailed` with the reason; message to the assigner |
| proof tests all green | task complete; message to the assigner |
| a newer outside wake | answered by the next pass, whatever the disposition |

- A committed model reply advances answered to the basis its context presented.
- Opening, input, outside and test passes never answer.
- Calls are counted at the writer against the outside wake whose anchor they consumed.
- Root's messages count as outside wakes.
- A self-wake or MCP retry never refills the cap.
- With no provider configured, no model pass is admitted.
- A schedule-only firing arms but opens no call.

### 4.6 Failure

- A crash before RECORD loses the pass's record, never an agent write that already committed. The next wake
  re-derives the work, so the model may repeat an action (owner ruling).
- **A definite refusal** retries once without the refused program facts. The retry records the evaluations, the
  call row (under its original call id) and the refusal as a diagnostic. If that retry is refused too, it records
  the call row and the fault alone, in one transaction. If that fails as well, it is the storage-failure path
  below.
- **An unknown outcome** gets no retry and no extra write. The pass keeps ownership of the pending transaction and
  the agent stays unavailable until the outcome is known, then the diagnostic is delivered.
- **Storage failure** stops the graph; it never spins.
- **Post-commit installation failure** is a separate diagnostic on a durable record.
- Attempted and durable transactions are counted separately.

### 4.7 Lookups and help (S1)

The repl-lookup design, corrected: keep `my.program/declaration`, which has another caller. `find-doc` becomes a
data adaptation of SCI's walk. `(help)` teaches only what is installed in its commit.

### 4.8 Bounds

| Bound | Owner | Fires as |
|---|---|---|
| provider timeout `:seon.config.ai/timeout-ms` | `ai/complete` | a counted failed call |
| SCI evaluation time limit per form | `seon.sci.eval` | that form's error value |
| model calls per outside wake | the writer's count | disposition `:capped` |

The MCP await uses the caller's deadline. On timeout the caller gets a terminal "still running" reply. A host call
that ignores interruption keeps ownership until its actual exit, and no second pass overlaps it.
`turn-step`'s thread and interrupt owner stays. The caller's lifecycle deadline also bounds disarm and join
(`stop-graph!`'s bound today comes from the deleted backstop). "No second pass overlaps" applies to the serialized
SCI/model execution; JVM invocations are owned individually until exit.

### 4.9 Kept from the old spec (normative)

- Immutable handles.
- Writer-assigned agent ordinals.
- Task-home custody of tasks and completion.
- Unknown-outcome ownership.
- Actual-exit joins.
- A terminal reply on every path.
- Failed-call accounting.
- Reload/adoption overlap checks.
- Completion's cross-branch crash window with idempotent recovery.

Two earlier rulings are reversed and need owner approval (§7.4): capture-before-call (the capture is deleted), and
the intent commit (deleted). The crash law already matches §1.5 (AGENTS.md).

## 5. Order of work

Every slice:
1. understanding traced;
2. a design note;
3. a dual review;
4. owner approval (core slices);
5. code;
6. a dual review of the diff;
7. S0 numbers;
8. root restarts or resets per the §6 manifest.

| # | Slice | Becomes true |
|---|---|---|
| P0 | Stop-and-fix lanes: forgive-fix, test-cost2, q-orjoin, contract-check, defn-cost, host-cost. dh-fix2, then a re-review and the gitlink bump. Dead code (410-444 lines, keeping `turn-request`). | budget fixes |
| S0 | Baseline probe (§3 workload) per entrant, through the HTTP fixture endpoint in agent settings | measured baseline |
| S1 | Lookups and help | standard lookups |
| S2a | Delete the dead debug context views | — |
| S2b-1 | Reader rules in the shared stepper (§4.3); lands before S1 | the reader contract, linear |
| S3 | In-place preparations used by today's entrants: origin enum (`:opening :input :model :outside :test`), pass ids, writer-assigned ordinals, the opening fact, input delivery identities, schema adopted on a live-store branch | facts exist |
| S4 | Context: opening and input passes, the proc-held transcript, the prompt seam. Deletes the system turn, its seed, the old prompt, walk and capture; persisted `:generate` rows are terminated. **Reset point.** | the model sees forms that ran; the refresh is gone |
| S5 | Reviewed in three parts: (a) in-place recording preparation, including `transact-call` exposing pending completion and the blob permit; (b) entrant, effect and error integration; (c) the admission proof (§4.1), then one settlement owner shared by all entrants. Deletes the fault-window query, problem-assignment routing and the run-observation reread. Old run duties (`*open*`) stay until S6 | one writer |
| S6 | The cut: one cursor plus D5 (§4.3); `*open*`, `open-tx`, `recover-tx` and effect provenance converted; delete the run machinery, permits, backstop, parking and episodes; §4.5 work rules; §4.6 failure; §4.8 bounds; launch and bail (`complete!`, `assign!` refusals, the proof seam via `my.test/check`). **Reset point.** | one machinery transaction per pass |
| S7 | Proof results as input passes; SCI `run-test-var` fixed (task 9236eba3f99e) | test-based completion |
| S8 | Experiments on DeepSeek (unlimited): REPL vs DATA, system role vs first form, stop on vs off, feedback turns | owner reads |

Until S7, an episode ends by bail or by the cap.

## 6. Reset manifest (each reset point)

Before the reset:
- dump runtime tasks (`:seon.task/created-by`), assignments, agent settings, schedules and open error signatures
  to `docs/research/<date>/reset-<n>/`;
- persist the tasks into task files (as `persist-tasks` did);
- verify task identities and references round-trip from the committed archive.

Restored after the reset (checked): task files ingest with identities and references intact. Archive-only (root
re-applies by documented forms if still needed): assignments and their revisions, agent settings, schedules, open
error signatures. Discarded: runtime-only definitions, messages, live handles (gone-value markers), evaluation
history, error occurrences.

After the reset: retire only owned or unclaimed branches (`reset-leak` makes stop release hosted agents).

Each schema retirement is adopted on a branch of the live store BEFORE its slice lands.

## 7. Owner decisions (2026-10-07)

Decided:
1. **Continuation:** after a model pass that evaluated ≥1 form, with no bail and calls left, the model is called
   again.
2. **Profile measurement upserts:** no longer stored. Health reads the live profile since boot.
3. **Proof-test runs:** the test body is agent work; runner setup, selection, recording and cleanup are Seon work.
4. **Reversed rulings:** capture-before-call and the intent commit are dropped.
5. **Allocation:** a one-line definition pass < 30 MB and a one-test check < 20 MB are interim targets.
   Stop-and-fix still applies above them; tighten them with evidence after S5/S6.
6. **Approved** as the replacement for agent-loop.md. Work starts: P0, then S0.

Open:
- **Subtree counts** (dh-fix, independent of this plan): child counts in branch nodes, counting at each split, or a
  recount job.
