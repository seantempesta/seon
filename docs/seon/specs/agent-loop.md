---
type: spec
status: approved
created: 2026-10-07
revised: 2026-10-08
tags: [spec, agent-loop, repl, one-path]
---

# The agent loop: one pass for every entrant

What exists: every entrant (a model agent's reply, MCP code, a test body, a page) supplies a read stage; SCI reads
and evaluates each form serially in the agent's world; the pass records what ran in one transaction; the value
printer shows each result. A model agent's context is a static prefix, its own history and one current-state block.
Clojure approach: values through one pipeline of plain functions, Datahike facts as the record, one Flow graph per
agent, one `seon.cache`.

Authority: the owner rulings (`docs/research/agent-platform/2026-10-08/review/owner-rulings.md`), then CLAUDE.md.
Order of work and status: [the ledger](../schedule/agent-loop-ledger.md), the only slice list. Merge mechanics:
[merge.md](merge.md). Error shapes: [errors.md](errors.md). Competency task designs:
`docs/research/agent-platform/2026-10-08/competency/competency-spec.md` §1-§8. Measurements live in the dated evidence
under `docs/research/agent-platform/2026-10-07/` and `2026-10-08/` (model-pass profile, memory report, PB-01 notes);
this spec states targets, never measurements.

Three mechanisms have redesigns under `docs/research/agent-platform/2026-10-08/redo/`, awaiting the owner's approval
and answers to their questions; this spec states the laws they implement: finishing a task (`task-finish.md`: §3.5),
the pass and its recording (`pass-and-recording.md`: §3.1, §3.3, §3.4 delivery), merge acceptance
(`merge-acceptance.md`: merge.md §3-§5). When the owner approves one, its mechanism is folded into the owning section
here in place.

## 1. Laws

1. **The REPL law.** Any valid Clojure that SCI can run works, serially, as at a real REPL: each form is read once and
   evaluated once, and later forms see what earlier forms did (definitions, redefinitions, aliases, schema
   registration, the agent's own `transact!` writes) because SCI ran them. The one transaction per pass only RECORDS
   what ran; it never vetoes, reorders or replays a form. On an agent's own branch the writer never refuses a
   REPL-valid change (removing a function or namespace others still call, redefining, retargeting): the stored
   program is the final state of each namespace, and each dangling reference becomes a finding delivered beside the
   value. Integrity is enforced where root diffs and merges the branch (merge.md). This protects valid SCI behavior;
   it does not make a malformed explicit data transaction succeed or an invalid merge acceptable.
2. **One path; entrances are read stages.** read → eval (SCI) → record → the one value printer; one cache
   (`seon.cache`), no second parser, evaluator, printer, store or harness. Each entrance supplies its read stage as one
   value (JVM mode also its eval stage, a test its resolved body); after reading, every entrance runs the same
   pipeline (§3.2, §3.3).
3. **Light forgiveness for model text only.** Prose becomes comments and one linear scan balances delimiters, within
   milliseconds and a few MB per reply. Valid Clojure passes byte for byte. Code (MCP, tests, generated) is read as
   is.
4. **Context is three parts, in this order** (§3.4): the static prefix (`(help)` and fixed instructions, changing
   only with the loaded commit); the history (only the agent's own forms with their real results, append-only); the
   current-state block (rendered fresh at every model call from stored facts, never stored, never repeated). Each
   current item appears once, in its current version. Nothing in the history is re-executed or re-rendered.
5. **Exchanges are data; wire formats are adapters** over them, selected per model (§3.4).
6. **Standard lookups return data** on SCI's `clojure.repl`; `(help)` teaches only what its commit installs (§3.7).
7. **Work ends by proofs.** A task needs proofs (deftests naming it in `:seon/tasks`) before it is assigned, and a
   proof already green does not prove new work. During work only the task's proofs run, automatically, off the pass;
   every other test reaching the change runs once at merge, on the combined program. A work task completes when its
   proofs pass on the combined program after root, or root's auto-accept policy, accepts the merge. A question task
   completes when its proof passes on the agent's answer; nothing merges. An impossible task is given back early with
   a required reason, never claimed. Proof status reaches the agent only through the current-state block. (Mechanism:
   `redo/task-finish.md`.)
8. **Budgets** (§2). A model pass costs no more than an ordinary pass of the same forms, provider call excluded.
   Transient allocation is garbage-collected and never GB per operation; retained memory stays small. An ordinary
   operation over 1 s, or any GB-class allocation, is stop-and-fix in the turn it is seen, never parked behind a
   future slice. A number that gets worse blocks the slice.
9. **Presentation.** One value printer shows every value, errors included (as flat data maps), with one budget and
   general rules only; nothing else clips. Render functions return pruned data for the top-level value; the full value
   is one step away through its result symbol. Elision forms are plain clojure.core on the live result symbol, lazy
   first. Agent-facing messages are data first, one short fixed message per variant, fixes as runnable forms.
10. Delete before adding. Reuse libraries. Understand before designing. Dual reviews. The owner approves core
    designs. Reset whenever needed, with the §6 manifest.

## 2. Budgets

Measured each slice by `seon.pass-probe` (`test/seon/pass_probe.clj`): wall ms, allocated bytes, checked calls,
transactions attempted and durable, store bytes, tokens. Seon's work only: the provider call and the agent's own
forms are excluded.

| Pass kind | Time | Transient allocation | Machinery txs |
|---|---|---|---|
| ordinary (no definitions), model passes included | < 200 ms | 20-40 MB, collected; never GB | 1 |
| definition (one-line `defn`) | < 300 ms | < 30 MB | 1 |
| the pass after a definition | < 150 ms | < 10 MB | 1 |
| current-state block, rendered at each model call | < 10 ms | < 2 MB | 0 (read only) |
| proof scheduling inside a pass that changed definitions | < 5 ms | < 1 MB | 0 |
| reply forgiveness scan, per 28.6 KB reply | ≤ 3 ms | ≤ 8 MB | — |
| history append (each pass) | < 10 ms | ≤ 3× the appended text | — |
| one-test check (runner setup, selection, recording, cleanup) | < 300 ms cold, < 150 ms warm | < 20 MB | as §3.2 |
| a new error signature | — | — | +1 at the task home |

| Retained scope | Budget |
|---|---|
| machine | the whole process on a 16 GB machine beside the user's work: heap cap 4 GB, RSS ≤ 6 GB (root's proposed targets for the owner's 16 GB requirement) |
| cluster | one shared base SCI context per loaded program, advanced incrementally; agents fork it; no pass constructs a base |
| agent | ≤ 20 MB live heap after a full GC per idle agent |
| concurrent passes | N simultaneous passes stay inside the heap cap; nothing allocates a GB |

**Output.** A core function's raw return stays under 2,000 estimated tokens in ordinary use; declared bulk-data
functions (reading a document, say) are exempt; an oversized return is a profile finding on that function. The value
printer's display budget has one override: the dynamic var `seon.print/*budget*` (`set!` for the session, `binding`
for one form, like `*print-length*`, strings included) and `:seon.print/budget` metadata on a collection; a render
function may put the same key on its returned data. A hard ceiling follows the model's context window, and an
override above the default adds one short finding with its token count.

**Context tokens.** The current-state block follows the growing history, so it is never prefix-cached; its size is a
per-call cost, printed under `:seon.print/budget` 2,000. Typical: 300-1,200 tokens; each red proof adds ≤ 150. The
static prefix and the history are cache hits on every call after their first (`:seon.ai.usage/cached-tokens`).

**Model-pass owners and their end state.** Base-context construction: zero in any pass (one shared incremental base,
an overlay per agent branch advanced by changed definitions, one fork advanced in place). Context projection,
prompt and refresh: deleted by the three-part context (held history appended per pass, block rendered per call).
Seven machinery commits: one. The surviving writer's floor (13-14 MB) and the armer, provider and query residual
(3-7 MB) are accepted as measured. Work derivation: once per pass. No competency model run starts until
`seon.pass-probe/run-model` reports a model pass inside the ordinary row.

## 3. Design

### 3.1 The pass

```
entrant → its read stage (a value) + text
one cursor, as at a REPL, for each form:
    READ    the next form at the live cursor (§3.3)
    EVAL    in the agent's world and current namespace; SCI runs it; nothing is refused;
            agent writes commit as they run; observations captured WHEN they occur (Var writes
            with their metadata at that time, macro expansions, deletions)
    PRINT   the result value, once, while observation is open → shown text, handle, diagnostics
            (lazy realization happens here and is observed)
once per pass:
    ANALYZE one clj-kondo run over the pass's code, joined to the captured observations by span;
            skipped when nothing was defined; JVM passes have no SCI observation and no analysis
    RECORD  ONE transaction at the writer on the current head: evaluation rows, program facts
            (the final state; dangling references as findings), the call row, agent facts,
            delivered items, error occurrences; staged blobs published around the commit
    FINISH  each reply = its shown result + diagnostics accumulated through RECORD, composed by
            the value printer from already-printed units; a later failure (recording included)
            is a diagnostic beside the value, never a replacement for it
    SCHEDULE when the pass changed what the task's proofs reach: one offer to the agent's proof
            work (§3.5); never awaited by the pass
```

The record is the agent's evidence, not a second execution: nothing in RECORD re-reads a mutable Var, re-runs a form
or recomputes a value. Effect provenance is pass id plus form position as values, never a ref to an evaluation not
yet recorded. Deleted with their last consumers: stored refresh evidence, the run-open intent and its recovery, the
model path's intent commit, the fault-window query, problem-assignment routing, the run-observation re-read, stamps
on transactions, stored profile measurements.

### 3.2 Entrants

| Entrant | Read stage | Eval | Serialized by | Machinery transactions |
|---|---|---|---|---|
| model, REPL entrance | reply: forgiveness, then SCI read | SCI | the agent's proc | 1 per pass |
| model, DATA entrance | data: one EDN map `{:ns sym :forms [form …]}`; `:ns` is the pass's start namespace; replies are envelope maps (read-time semantics: `redo/pass-and-recording.md`) | SCI | the agent's proc | 1 per pass |
| MCP SCI | code: text as is | SCI | the agent's proc (request port) | 1 |
| MCP JVM | code: host read | host | not serialized: each host invocation is owned until its exit; its result is bound into the agent's SCI result owner only when that context is already held, otherwise its handle answers gone-value data with the class; it never changes the agent's namespace or answeredness | 1 |
| test member | its resolved body stage (SCI or host) | as the body | its execution ownership | 1 member pass |
| test request | the runner | — | — | 1 request aggregate on the recording connection; task-home completion its own write |

The read stage is the value `:seon.batch/read-stage` with a mode (`:reply`, `:code`, `:data`); the dial
`:seon.config.ai/entrance` (`:repl :data`) selects a model agent's entrance. Error occurrences ride the pass
transaction; the authoritative copy is recorded once per signature at the task home (errors.md). A timed-out MCP
caller's queued submission is dropped if it has not started; once started, it runs to its actual exit and is
recorded.

### 3.3 Reading and evaluation

**Reading, every mode.** Each form is read once at the live cursor, after the previous form ran, by SCI with the
constant options `{:features #{:clj} :end-location true}` (`seon.sci.batch/parse-options`): SCI owns tags, records,
`*data-readers*`, `#=` and `::alias`, and `#?` picks `:clj`. A map with `:seon.repl/*` keys is data. A form that fails
to read is its own error and reading continues after it: a collection open at the failure spans to its matching
closer, so the forms inside it never run; any other failure spans to where the reader stopped; a collection that never
closes spans to its region's end. `#_` and a reader conditional that selects nothing yield no value.

**Model text (reply mode): light forgiveness**, one linear scan before reading, tracking string, character, comment
and delimiter state across lines.
- **Fences.** A line of three or more backticks, untagged or tagged `clojure`, `clj`, `cljc` or `edn`, opens a code
  fence; any other tag opens a prose fence. A fence closes with a line of at least as many backticks and no tag. A
  fence line inside a string or an open form is not a fence. Inside a code fence every line is code.
- **Code lines outside fences.** A line is code when it starts with `(`, `[`, `{`, `` ` ``, `'`, `@`, `^`, or `#`
  followed by a character other than whitespace or `#`; or when the whole trimmed line reads as exactly one literal
  (number, string, keyword, character, `##NaN`) or one namespace-qualified symbol. So `42`, `##NaN`, `` `(+ 1 2) ``,
  `\newline` and `result/e1a2b3c4d5e6` are code; `# Heading`, `1. First step`, `Done.` and `x` are prose. While a form
  is open, following lines continue it.
- **Prose becomes comments.** Outside a code fence and an open form, a prose line becomes a `;` comment attached to
  the next form; trailing prose is kept on the last exchange. After a form ends mid-line, the rest of the line is code
  only if it starts a form or `;`; otherwise it is that form's comment, so `(+ 1 2) Done.` evaluates once.
- **One balancing pass.** Text changes only where a region is unbalanced: a stray closer is dropped, missing closers
  are inserted before the next column-zero form or prose line. Each change is data beside the value of the form whose
  lines it repaired, `:seon.repl/repairs [[line :dropped ")"]]`.
- **Imitations.** `;; =>` and `ns=>` lines outside fences are prose; the reader's imitation note, with its
  `:seon.error/type`, is a diagnostic of the exchange.
- **No form.** A prose-only reply records one exchange (its call row) with the "no form" diagnostic and no
  evaluation, which tells the model to act.

**Code (MCP, tests, generated, page-authored).** Text as is: every line is code, so `result/e…`, `*1`, `:k` and
literals evaluate. A line that is exactly a fence, outside any form or string, is skipped. Input with no forms
(`#_ 1`, only comments) answers `nil` without an error.

**Evaluation.**
- The namespace is kept after a throw: an `in-ns` before the throw stands, as in `clojure.main`.
- A malformed contract keeps the function callable, its diagnostic beside the value; merge refuses it. A valid armed
  contract refuses a violating input or result.
- `remove-ns`, `ns-unmap`, `ns-unalias`, `intern` and `alter-var-root` run as SCI runs them, and the record stores the
  final state they leave.
- Printed output is captured whole and shown within the reply's one budget; its elision form reads the live output.
- A form that throws does not stop the pass; later forms run, as at a serial REPL.

Behavior against plain SCI is the table test `seon.repl-differential-test` and its per-slice namespaces.

### 3.4 Context: static prefix, history, current state

| Part | What | Changes when | Stored |
|---|---|---|---|
| **Static prefix** | `(help)`'s value and the fixed instructions | the loaded commit changes | no; derived per loaded commit, cached in `seon.cache` by commit |
| **History** | only what the agent did: its own forms and their envelopes, in agent order | a pass appends | yes: each pass's call row and evaluations |
| **Current-state block** | each current item once, in its current version | any fact it reads changes | **never**; rendered at every model call |

Provider prefix caching covers the prefix and the history, which are byte-identical from one call to the next up to
the end of the history. There is no opening pass and no input pass: the task and its references are current state;
messages and errors are unread items shown in the block; `(help)` is the prefix, not a recorded form.

**The current-state block.** One value, `seon.context/current-state [database agent-id]`, read at the call from
stored facts (a database value, never a connection), rendered by its declared AI render pair into pruned data in this
order, and printed by the value printer under the block budget. Sections, each absent when empty:
- **Task**: the task's current declaration (intent, must-reads, ordered steps), read at the task home's current head.
  A task revised by root or the user is a review: the update advances the assignment's revision in the same
  transaction, so the block always shows the current declaration and never an older one.
- **Proofs**: each proof test by name with its current stored source, printed as code (whole for an ordinary deftest),
  and its status: `:green`, `:red`, `:pending` (no result whose tested program holds the definitions the proof now
  reaches) or `:running`. Never a latest-green boolean. A red proof adds data, `{:test sym :expected … :actual …
  :message …}`, from the stored failure report. Counts head the section.
- **Must-reads**: each reference's current arglists, contract and first docstring line, with `(doc sym)` and
  `(clojure.repl/source sym)` offered as operations. An agent that runs them sees the result in its own history.
- **Unread**: messages and errors delivered to this agent and not yet delivered by a committed model pass, each with
  its identity; `(my.agent/inputs)` is the operation that shows them all.
- **Turns**: model calls left against the current outside wake, and the task's status.

When everything is green and quiet the status sections collapse to one line (`{:proofs {:green 3} :calls-left 7}`);
the task and proof sources always show, because they define done.

Offered operations (`doc`, `source`, `my.agent/inputs`) are domain lookups placed in the render's data; they are not
printer continuations, and the printer never ranks sections. A printer cut leaves its ordinary elision form on the
block's result symbol.

**Delivery.** Proof status needs none: it is recomputed from results. Each unread item the block SHOWED is delivered
by the model pass whose context presented it: its identity is recorded on that pass's call row inside the pass's one
transaction, after the writer checks it still exists and is not already delivered to this agent. A crash before
RECORD or a failed provider call delivers nothing, so the item shows again. An item cut by the budget stays unread
until it is shown. A change in what the assigner wants is a task revision, not a message.

**History.** An exchange is a pass's call row and its evaluations with source and shown text. The proc holds the
assignment's exchanges as values and appends each finished exchange; it reads ordered history once, at proc start or
a new assignment. Origins are `:model`, `:outside` and `:test`; only `:model` exchanges reach the model. Handles keep
their identity across restarts and answer gone-value data after their runtime is lost. When prefix + history + block
exceed the provider's window, the call refuses with an error the owner sees; nothing is clipped.

**Wire formats.** One pure adapter, `seon.ai/messages [wrapping prefix exchanges block] → messages`, one branch per
format; the block is always the last content before the model's turn.
- **A `:transcript`**: the prefix as the system message; one user message holding the transcript, the block at its
  end.
- **B `:chat`**: the prefix as the system message; each form text an assistant message and its envelope the next user
  message; the block is a second content part of the last user message (or the only user message before the first
  form), never a separate consecutive user message.
- **C `:prefix`**: B plus a final assistant message the model continues from (`ns=> ` for REPL, `{:forms [` for
  DATA), after the block, only where the provider declares prefix continuation; the continuation prefix is not echoed
  into the history.

The dial `:seon.config.ai/wrapping` (`:transcript :chat :prefix`) lives on the model row with an agent overlay; the
default is `:transcript` until the competency table is read; a format that loses everywhere is deleted with its
branch. Every model call records the provider-reported prompt, completion and cached tokens on its attempt row
(`seon.ai/normalize-usage`); a provider that reports none records a typed unknown, never zero. The attempt row also
carries the call's latency and the block's estimated tokens. Presentations: REPL is the form as sent, then its
envelope; DATA is the envelope map including `:form`.

**Debug view.** Exactly one: a call's context exactly as sent, produced by `seon.ai/messages` over the static prefix,
the history and the block at that call's stored basis, printed by the value printer's HTML face. No second assembler;
the web never reads the proc's memory.

### 3.5 Work, continuation, session end

| After a model pass | Next |
|---|---|
| ≥ 1 form evaluated, no session end, calls left | call the model again |
| a form's value is `(my.turn/wait {:my.turn/note …})` | disposition `:wait`; no model call until a new outside wake |
| no form (prose only), or only read errors | disposition `:wait`; the original wake is answered |
| provider failure or context refusal | counted call, no evaluations, answeredness unchanged, terminal reply |
| call cap reached | disposition `:capped`; one message to the assigner; the next outside wake resumes |
| the task's proofs all green | success (§1.7); who proposes and how a question task completes: `redo/task-finish.md` |
| a proof recorded red after the agent's answered basis | work: the agent is woken, even after `:wait`, and the block shows the red data |
| the bail form with a required reason | disposition `:bailed`; the task goes back to the assigner, who is notified |
| a newer outside wake | answered by the next pass, whatever the disposition |

**Proofs run automatically.** After a pass that changed what the task's proofs reach, exactly those proofs run as one
request through the one test entrance (`seon.test/run`), off the pass: the pass offers its change and never waits.
One request per agent is in flight; a started request runs to its actual exit and records; changes arriving meanwhile
are checked by the next request, so nothing is dropped and nothing overlaps; pending work is rederivable from
committed facts after a crash. A member whose recorded green still holds is answered from the record. The model is not
held for the run: it acts on what the block shows. Selection, scheduling and the completion contract:
`redo/task-finish.md`.

- A committed model reply advances answered to the basis its context presented and records the delivery of the unread
  items its block showed. Outside and test passes never answer.
- The cap counts call rows at the writer against the outside wake whose anchor they consumed. Root's messages are
  outside wakes. A self-wake or MCP retry never refills the cap.
- With no provider configured, no model pass is admitted. A schedule-only firing arms but opens no call.

### 3.6 Failure

- A crash before RECORD loses the pass's record, never an agent write that already committed. The next wake
  re-derives the work, so the model may repeat an action.
- On an agent's own branch any writer refusal of the record is Seon's own failure. A definite refusal retries once
  without the refused program facts, recording the evaluations, the call row under its original id and the refusal as
  a diagnostic. If that retry is refused, it records the call row and the error alone. If that fails, it is the
  storage-failure path.
- An unknown outcome gets no retry and no extra write: the pass keeps ownership of the pending transaction and its
  blob permit, and the agent stays unavailable until the outcome is known; then the diagnostic is delivered.
- Storage failure stops the graph; it never spins. Post-commit installation failure is a separate diagnostic on a
  durable record. Attempted and durable transactions are counted separately.

### 3.7 Lookups and help

`doc`, `dir`, `source`, `apropos` and `find-doc` return data on SCI's `clojure.repl`, installed once in the SCI
init; each declares the SCI context as a world input supplied from the evaluation. `(help)` is literal data teaching
only the forms its commit installs, each once, with exactly one session-end form; it is the static prefix.

### 3.8 Bounds

| Bound | Owner | Fires as |
|---|---|---|
| provider timeout `:seon.config.ai/timeout-ms` | `seon.ai/complete` | a counted failed call |
| SCI evaluation time limit per form | `seon.sci.eval` | that form's error value |
| model calls per outside wake | the writer's count | disposition `:capped` |

The MCP await uses the caller's deadline; on timeout the caller gets a terminal "still running" reply. A host call
that ignores interruption keeps ownership until its actual exit, and no second pass overlaps it; the actual-exit owner
keeps one detector that names the missing terminal event. The caller's lifecycle deadline passes explicitly into
`disarm!` and `stop-graph!`, each agent with its own slice. JVM invocations are owned individually until exit.

### 3.9 Kept normative

Immutable handles; writer-assigned agent ordinals; task-home custody of tasks and completion; unknown-outcome
ownership; actual-exit joins; a terminal reply on every path; failed-call accounting; reload/adoption overlap checks;
completion's cross-branch crash window with idempotent recovery. Capture-before-call and the intent commit are gone.

### 3.10 Experiments, merging, scoring

- **Variant.** A run's variant is its agent's settings overlay (model, wrapping, entrance, cap). No run entity: the run
  is the agent, its task is its assignment.
- **Environment.** Each run is a fresh agent on its own branch and SCI fork of the target cluster. Setup runs as an
  outside pass before assignment; the branch is unlinked after scoring.
- **Merging.** Root is whoever holds the merge task, the orchestrator or a Seon model agent, through the ordinary
  REPL; a Seon agent must find root's job easy. Root diffs the branch against the target head and enforces integrity
  there: dangling references, contracts, schema against target data, and the task's proofs plus root's chosen
  regressions on the combined program. Auto-accept is root's policy, configured on the target. Mechanics: merge.md.
- **Scoring** reads stored facts only: proof member counts, passes, tokens, read-error types, imitation notes,
  latency, merge outcome, bail reason. Proofs and scoring cost no provider call. The suite runs the competency spec's
  task × wrapping × entrance × model matrix, DeepSeek flash first, every run ending in the real merge flow.

## 4. Order of work

[The ledger](../schedule/agent-loop-ledger.md). Every slice: understanding traced, a design check, dual review of the
diff, its budget numbers, and root restarts or resets per §5.

## 5. Reset manifest (each reset point)

Before the reset: dump runtime tasks (`:seon.task/created-by`), assignments, agent settings, schedules and open error
signatures to `docs/research/<date>/reset-<n>/`; persist the tasks into task files; verify task identities and
references round-trip from the committed archive.

Restored after the reset (checked): task files ingest with identities and references intact. Archive-only (root
re-applies by documented forms if still needed): assignments and their revisions, agent settings, schedules, open
error signatures. Discarded: runtime-only definitions, messages, live handles (gone-value markers), evaluation history,
error occurrences. Retire only owned or unclaimed branches. Each schema retirement is adopted on a branch of the live
store before its slice lands.

## 6. Owner decisions

2026-10-07:
1. **Continuation:** after a model pass that evaluated ≥ 1 form, with no session end and calls left, the model is
   called again.
2. **Profile measurements** are not stored; health reads the live profile since boot.
3. **Proof-test runs:** the test body is agent work; runner setup, selection, recording and cleanup are Seon work.
4. **Capture-before-call and the intent commit** are dropped.

2026-10-08:
5. **The REPL law** (§1.1), and agent branches may make any REPL-valid change; integrity at root's diff and merge.
6. **Reader rules** (§3.3): `#?` picks `:clj`; the namespace is kept after a throw; a malformed contract keeps the
   function; bare literal and qualified-symbol lines are code; words after a form on its line are its comment; empty
   code input answers `nil`; code mode skips fence lines; forgiveness only if lightweight (ms, a few MB).
7. **Wire formats** are per-model adapters over stored exchanges; measure provider tokens, do not assume.
8. **Budgets:** no world accepts a 945 MB model pass; 20-40 MB transient per pass is fine, never GB; small retained
   memory; many agents on a 16 GB machine; one shared, incrementally advanced base per cluster.
9. **Context is dynamic for current state** (§3.4): static prefix, own history, one block rendered per call; no
   opening or input pass; agents see their proofs' current source and status.
10. **Only the task's proofs run during work**; regressions run once at merge on the combined program. A work task
    completes when its proofs pass on the combined program after acceptance; a question task when its proof passes on
    the answer.
11. **Root review and auto-accept**: every competency run ends in the real merge flow; the suite includes an
    impossible task (bail), conflicting refactors against a moving target, and root's job done by an agent.
12. **One debug view**: the context exactly as sent, from the same assembly function, printed by the HTML face.
13. **Presentation**: no clipping outside the value printer; errors printed by it as data; elision forms are plain
    clojure.core; terse Clojure messages; lean core values with renders as cliffs notes; a 2,000-token raw-return
    bound with declared bulk exemptions; the `seon.print/*budget*` override.

Open:
- **Subtree counts** (Datahike fork): child counts in branch nodes, counting at each split, or a recount job.
- **Delimiter repair in code mode.** The law (§1.3) reads code as is; `seon.sci.forgive` also repairs delimiters for
  MCP code. Recommendation: repair code-mode text too, with the same repair notes beside the value, since an MCP agent
  benefits as a model does and the scan is the same; then §1.3 says so.
- **Live result retention bound** (ledger MEM-03): recommendation there.
