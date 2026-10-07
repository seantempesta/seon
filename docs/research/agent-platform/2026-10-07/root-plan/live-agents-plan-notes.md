# Live agents: one pass for every entrant (plan v3, for review)

Status: DRAFT v3. v2 was dual-reviewed (`review-plan-opus-notes.md`, `plan-v2-review-notes.md`, both beside this
file). This version folds in their findings and goes back for a second review before the owner sees it. Once
approved it replaces `docs/seon/specs/agent-loop.md` in place, KEEPING that spec's non-context invariants (§4.8).
Evidence lives in `../plan-research/r-*-notes.md`; numbers are single measured runs, so the workload and population
are named where they matter.

## 1. Principles (owner rulings, 2026-10-07)

1. **The agent is a REPL.** ONE path: read → eval (SCI) → record → the one value printer. One cache
   (`seon.cache`), one loop. No second parser, evaluator, printer, store, cache or harness.
2. **The same machinery for every entrant.** Model agents, MCP `eval_clj` (root and lanes, SCI and JVM) and test
   runs go through the same pass. They differ only in where the forms come from (each entrant supplies its read
   and eval stage as values; JVM keeps host eval, a test keeps its resolved body) and in the origin recorded.
   Lanes therefore exercise the code Seon agents run.
3. **The reader is a plain REPL read.** One linear scan of the text's lines: fenced code, or a line that starts a
   form, is code; every other line is a prose comment. Code is read with edamame `parse-next`, one form at a time,
   interleaved with evaluation as at any REPL. A form that does not read is that form's read error, and reading
   continues. No Parinfer repair, no structural trials, no second read.
4. **Context is forms that ran.** The agent sees ONE transcript built from stored exchanges: the opening forms
   (`(help)`, the task's `doc`, each reference's `doc`/`source`, run once per assignment), the inputs that arrived
   (new messages, proof results, each delivered by a form that ran), and its own forms with their real results.
   Nothing is re-executed or re-rendered; history is whole for now.
   - There are no `;; =>` comments.
   - Two presentations (REPL, DATA) are decided by experiment and by asking the agents; a tie goes to REPL.
5. **One machinery transaction per pass.** The agent's own writes (`transact!`, messages, task writes) commit when
   its forms run, as at a REPL. The machinery's record of the pass (evaluations, program facts, call row, agent
   facts) is ONE transaction, derived at the writer on the current head.
6. **Standard lookups** (`doc`, `source`, `dir`, `apropos`, `find-doc`) return data, on SCI's `clojure.repl`.
7. **Done means proof tests are green.** `complete!` only bails, with a reason. A task needs proof tests before
   launch, and `assign!` refuses tasks with no proof or already green.
8. **Budgets** (§3): no multi-second, multi-MB or GB Seon work. Any number that gets worse blocks the slice; over
   1 s or 10 MB is stop-and-fix in the same turn it is seen.
9. Delete what is slated before adding. Reuse libraries. Understand before designing. Dual reviews. The owner
   approves core designs. Reset whenever needed: ~7 s, no migrations, runtime tasks dumped first.

## 2. Today, measured (corrections from the reviews applied)

| What (workload) | Measured | Source |
|---|---|---|
| model turn, stub provider, warm, one specimen | 2.1 s, 3.65 GB (whole-JVM window), ~400k checked calls, 0.94 MB written, 6 machinery commits | r-turn §3a |
| its system-turn refresh | ~80% of that turn: 1.3-2.3 s, 3-4 GB; 228 datoms in this specimen (202-408 across specimens) | r-turn, r-context |
| the turn without the refresh (stages 2-6) | still ~457 ms and ~550 MB | Astra §7 |
| MCP SCI `(+ 1 2)`, no effects | 1 commit, 27 datoms, ~150 KB, 205-358 ms, 159-232 MB; ~170 MB not yet attributed to a stage (raw `d/with` 0.84 MB) | r-record, Opus |
| `defn` pass | 712-733 MB for the whole call; settlement 254 ms; 104 ms installing after commit | r-record |
| reply read, 40 one-line forms | split + per-form reread + per-form kondo: kondo 441 of 667 ms | r-read |
| reply read, 76 KB mixed reply | 4.1 s, 94 GB (cubic-like); stop-gap fix in lane `forgive-fix` | r-read |
| `code-currency` | ONE batched query with pathological per-binding cost: 60 ms and 180 MB for one symbol; 42 s for root's 672 symbols. Stop-and-fix lane `q-orjoin` | r-context, Astra |
| one named test via `my.test/check`, cold | 2.3-6.0 s around a 55-110 ms body; 384 ms warm. Stop-and-fix lane `test-cost` | root |
| contract-compile check of every lane commit | 1.7 s, 3.18 GB over 471 files. Stop-and-fix lane `contract-check` | Astra |
| reset | leaked 16 agent connections and branches: 5.1 GB live heap until restart. Stop-and-fix lane `reset-leak` | root |
| per-commit store floor | ~150 KB (two 26.7 KB root copies = 36%, index nodes 64%); accepted by the owner | r-record, Astra |

Behavior seen in the one real model specimen:
- fabricated results, both `ns=> … #:seon.repl{…}` and `;; => 42`, with false "verified" claims;
- prompt markers stripped while their namespace is ignored;
- a prose-only reply evaluating its first word;
- a refused imitated result showing as a nil core fault (batch.clj:390).

Origin is `:seon.cluster.eval/author`, which lumps MCP runs and refreshes together as `:system`.

## 3. Budgets

Measured by the S0 probe on every slice: wall ms, allocated bytes (per thread where possible), checked calls,
attempted and durable transactions, store bytes, tokens shown. The profiler's own marks (~3.5 MB each) are
excluded or fixed first.

| Per pass, excluding the provider's latency and the agent's own forms' work | Budget |
|---|---|
| Seon's work | < 200 ms |
| allocation | < 5 MB (definition passes: measured and reported separately) |
| machinery transactions | 1 (plus 1 only when a commit itself fails, §4.6) |
| context build | < 10 ms; allocation < 3× the bytes of the history text it returns |
| store | ~150 KB per pass (owner) |

S0 fixes the workload: 1, 10 and 200 exchanges of a stated size.

## 4. Design

### 4.1 The pass (all entrants)

```
entrant → its forms text (model: provider reply to the context; MCP/test: the given code)
for each form, as at a REPL (one cursor):
    READ   next form (line scan + edamame parse-next; prose lines → the form's comment)
    EVAL   in the agent's world and current namespace; the agent's own writes commit as they run
    PRINT  render + value printer, in the form's world, while observation is open → shown text,
           diagnostics, handle (lazy values realize here, observed)
then once per pass:
    ANALYZE  ONE clj-kondo run over the pass's source, joined by span to the observed
             writes and macro expansions; skipped when the pass defined nothing
    RECORD   ONE transaction [:db.fn/call] at the writer on the current head: evaluation rows
             (ids = pass id + position, minted before eval; agent ordinals assigned at the writer),
             program facts (the analysis + one batched digest lookup at the writer, guarding
             changed definitions as program-facts-call does today), the call row, agent facts
             (current-ns, answered basis), fault repeats; staged blobs published around it
    DELIVER  the held shown texts (MCP reply / next context), never re-printed
```

**Admission inside a pass, the open S4 design question** (Astra §1). Today's fold derives facts and applies
pending changes before later forms in the same pass use them. S4 must show how one end-of-pass analysis keeps:
- a macro defined and used in one reply;
- `ns`/alias changes;
- nested definitions;
- a registration followed by use;
- a failed definition followed by forms that depend on it;

all without re-expanding macros or calling kondo per form. Each case gets a probe before code.

### 4.2 Entrants and transactions

| Entrant | Forms from | Read / eval stage | Serialized by | Machinery transaction |
|---|---|---|---|---|
| model | provider reply | §4.1 / SCI | the agent's proc | 1 (+ call row) |
| MCP SCI | the request | §4.1 / SCI | the agent's proc (today) | 1 |
| MCP JVM | the request | host read / host eval | **moves onto the agent's proc** (today it runs on the caller's thread, unserialized) | 1 |
| test (SCI or host body) | the resolved test body | the body stage it has | its test branch | the runner's domain facts join the one settlement where custody allows; cross-branch completion stays its own write (§4.8) |

- **Faults:** a fault the pass observed rides the pass transaction. A fault caused by the commit itself is
  recorded separately, and the value still returns with a diagnostic.
- **Effects:** provenance is pass id + position, never a ref to a not-yet-recorded evaluation.

### 4.3 The reader (S2b, before the cut)

- Line rules:
  - a line inside a fence is code (a bare symbol included);
  - a line starting `(`, `[`, `{`, `#`, `'`, `` ` ``, `@`, or a lone symbol, starts code;
  - every other line is a prose comment attached to the next form, and trailing prose is kept;
  - a prose-only reply gets the "no form" refusal.
- `ns=>` lines are prose (never stripped into code).
- A form that is a reply-map literal (`#:seon.repl{…}`) is not evaluated: it is flagged to the agent as an imitated
  result, as an error VALUE (fixing the nil fault).
- `;; =>` lines stay comments and are reported as an imitated result.
- MCP code uses the same reader; it simply has no prose.
- `seon.sci.forgive`'s split, trials and repair are deleted.

### 4.4 Context

- **Opening, once per assignment:** recorded with origin `:opening`, with an opened fact on the assignment so a
  restart or reassignment knows. A crash mid-opening reruns it.
- **Inputs:** when a wake carries something new (a message, an error delivered to this agent, a proof result), the
  next pass first evaluates ONE generated form (e.g. `(my.inbox/new)`, name TBD), recorded with origin `:input`.
  The model therefore sees inputs as forms that ran, once, in order. Nothing is answered without being shown.
- **Every pass:** ONE narrow ordered read of the agent's `:opening`, `:input` and `:model` exchanges for the
  assignment. S3 designs it; it must reach the budget, since today's narrow pull is 17 MB at 200 rows.
- **Presentations:**
  - REPL: the form as sent, then its stored reply text;
  - DATA: the stored reply map including `:form`.
  - Both texts are printed once, at record time, by the value printer (no hand-built EDN). During S8 both are
    stored; the losing one is deleted afterwards.
  - Handles, errors, diagnostics and output are included.
- **Window:** when whole history exceeds the provider's window, the pass refuses with an error the agent's owner
  sees. No silent clipping (owner: history whole for now).

### 4.5 Continuation and answeredness

- A committed model reply advances the agent's answered basis to the basis its context presented.
- Wakes that arrive during the provider call stay due.
- Outside, opening, input and test passes never answer a wake.
- After a model pass, the model is called again if:
  - the reply evaluated at least one form;
  - no `complete!`/proof completion happened;
  - calls are left.

  A reply with no forms waits for the next outside wake. (Owner decision 1.)
- Calls per outside wake are counted at the writer, and root's messages count as outside wakes.

### 4.6 Failure

- A crash before RECORD loses the pass's record, never an agent write that already committed. The next wake
  re-derives the work, so the model may repeat an action (owner ruling: crash loses the pass).
- A refused or unknown settlement still writes the provider call row alone, so the call cap counts it and a
  failing settlement cannot loop on paid calls.
- An unknown commit outcome keeps the agent unavailable until resolved (old spec invariant).
- Attempted and durable transactions are counted separately.

### 4.7 Lookups and help (S1)

The repl-lookup design (`../repl-lookup/design-notes.md`; claims verified in r-context). `(help)` teaches only
what is installed in that commit.

### 4.8 Kept from the old spec

- Immutable handles.
- Writer-assigned agent ordinals.
- Task-home custody.
- Unknown-outcome ownership.
- Actual-exit joins (`turn-step`'s thread and interrupt cleanup stays until replaced and proven).
- Reply totality.
- Failed-call accounting.
- Completion's cross-branch crash window, which needs idempotent recovery.

## 5. Order of work

Every slice:
1. understanding traced;
2. a design note;
3. a dual review;
4. owner approval (core slices);
5. code;
6. a dual review of the diff;
7. S0 numbers;
8. root restarts or resets.

| # | Slice | Notes |
|---|---|---|
| P0 | Stop-and-fix lanes landing now: `forgive-fix` (stop-gap), `test-cost`, `q-orjoin`, `contract-check`, `reset-leak`; then `dh-fix2`, a re-review and the Datahike gitlink bump. Dead code (410-444 lines, keeping `turn-request`). | in flight |
| S0 | Baseline probe: one pass per entrant through the HTTP fixture endpoint set in agent settings. Behavior cases: arithmetic, `defn`, refusal, effect, blob, error. Every §3 metric. The pre-P0 baseline is kept separately. | after P0 |
| S1 | Lookups and `(help)`. | independent |
| S2a | Delete the dead debug context views. | independent |
| S2b | The plain REPL reader (§4.3), replacing forgive's machinery; the imitation flag as data. | before S4 |
| S3 | In-place preparations, used by today's entrants: origin enum, assignment-opening fact, pass ids and writer-assigned ordinals, the narrow ordered history read. Schema adopted on a branch of the live store first. | additive |
| S4 | The context (§4.4): opening and inputs as recorded forms, at the existing prompt seam. Deletes the system turn, its seed, the old prompt, walk and capture. | biggest budget win |
| S5 | The one settlement owner for all entrants (§4.1-4.2), the JVM entrant moved onto the proc, faults and effects as in §4.2, one analysis per pass (with the admission probes of §4.1). Existing entrants switch to it. | preparation for the cut |
| S6 | The cut: delete the run machinery, permits, backstop, parking and episodes; continuation and answeredness (§4.5); the three bounds; failure (§4.6). Reset point. | dependency-complete |
| S7 | Completion: proof results delivered as recorded `:input` forms; `complete!` bails; `assign!` refuses; SCI test running fixed. | |
| S8 | Experiments on DeepSeek (unlimited) through the real loop: REPL vs DATA, system role vs first form, stop on vs off, with feedback turns. Measured: protocol followed, invented results, calls to finish, correctness. | owner reads |

The slated union is ~5,700 lines (a def-span census, not a promised diff). Adds are review targets, not limits.

## 6. Owner decisions (after the second review)

1. **Continuation (§4.5):** call the model again after a pass that evaluated ≥1 form while calls are left, vs only
   on new outside wakes.
2. **JVM-mode MCP onto the agent's proc (§4.2):** serializes it with the agent's model passes (recommended), vs
   deriving ordinals and namespace at the writer and leaving it unserialized.
3. **Proof-test runs (S7):** count as agent work outside Seon's budget, or Seon work inside it?
4. **Subtree counts (dh-fix):** child counts in branch nodes (format change; old nodes stay unknown until
   rewritten; ~1 KB per branch), vs counting at each split (I/O, no format change), vs a recount job (temporary).
5. Approve the plan as the replacement for agent-loop.md, with §4.8 carried over.
