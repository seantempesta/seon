---
type: spec
status: approved
created: 2026-10-07
revised: 2026-10-08
tags: [spec, agent-loop, repl, one-path]
---

# The agent loop: one path for every entrant

What exists: every entrant (a model agent's reply, MCP code, a test body, a page) supplies a read stage; SCI reads
and evaluates each form serially in the agent's world; each eval request records what ran in one transaction; the value
printer shows each result. A model agent's context is a static prefix, its own history and one current-state block.
Clojure approach: values through one pipeline of plain functions, Datahike facts as the record, one Flow graph per
agent, one `seon.cache`.

Authority: the owner rulings (`docs/research/agent-platform/2026-10-08/review/owner-rulings.md`, the current rulings
by topic, each naming the section here that implements it), then CLAUDE.md.
Order of work and status: [the ledger](../schedule/agent-loop-ledger.md), the only slice list. Merge mechanics:
[merge.md](merge.md). Error shapes: [errors.md](errors.md). Competency task designs:
`docs/research/agent-platform/2026-10-08/competency/competency-spec.md` §1-§8. Measurements live in the dated evidence
under `docs/research/agent-platform/2026-10-07/` and `2026-10-08/` (model-pass profile, memory report, PB-01 notes);
this spec states targets, never measurements.

Mechanisms redesigned under `docs/research/agent-platform/2026-10-08/redo/`, all approved by the owner on
2026-10-08: finishing a task (`task-finish.md`, as amended: tests run as a serial step; all relevant tests, not only
proofs) is folded into §1.7 and §3.5; merging (`merge-acceptance.md`, in git's words) into merge.md; the eval request
and its recording (`pass-and-recording.md`, approved on condition of the transaction census fold-in, done in
`b48082ca9`) into §3.1, §3.3 and §3.4 delivery; the entrants (`entrants.md`) into §3.2.

**Words.** This spec uses the approved Clojure names (ledger §3.18): one submission of forms is an **eval request**
(nREPL's word; code still says "pass" and `seon.batch` until CN-20 lands), its one transaction is the **recording
transaction** (code: settlement, CN-7), what returns is the **reply** (code: envelope, CN-8) and the stored text is
the **printed value** (code: shown text, CN-9). Code converts as each rename row lands.

## 1. Laws

1. **The REPL law.** Any valid Clojure that SCI can run works, serially, as at a real REPL: each form is read once and
   evaluated once, and later forms see what earlier forms did (definitions, redefinitions, aliases, schema
   registration, the agent's own `transact!` writes) because SCI ran them. The one transaction per eval request only RECORDS
   what ran; it never vetoes, reorders or replays a form. On an agent's own branch the writer never refuses a
   REPL-valid change (removing a function or namespace others still call, redefining, retargeting): the stored
   program is the final state of each namespace, and each dangling reference becomes a finding delivered beside the
   value. Integrity is enforced where root diffs and merges the branch (merge.md). This protects valid SCI behavior;
   it does not make a malformed explicit data transaction succeed or an invalid merge acceptable.
2. **One path; entrances are read stages.** read → eval (SCI) → record → the one value printer; one cache
   (`seon.cache`), no second parser, evaluator, printer, store or harness. Each entrance supplies its read stage as one
   value (JVM mode also its eval stage, a test its resolved body); after reading, every entrance runs the same
   pipeline (§3.2, §3.3).
3. **Light forgiveness.** In model text, prose becomes comments; in every mode, one linear scan balances delimiters,
   within milliseconds and a few MB per reply, with each repair shown beside the value it repaired. Valid Clojure
   passes byte for byte. Code (MCP, tests, generated) gets no prose conversion: every line is code.
4. **Context is three parts, in this order** (§3.4): the static prefix (`(help)` and fixed instructions, changing
   only with the loaded commit); the history (only the agent's own forms with their real results, append-only); the
   current-state block (rendered fresh at every model call from stored facts, never stored, never repeated). Each
   current item appears once, in its current version. Nothing in the history is re-executed or re-rendered.
5. **Exchanges are data; wire formats are adapters** over them, selected per model (§3.4).
6. **Standard lookups return data** on SCI's `clojure.repl`; `(help)` teaches only what its commit installs (§3.7).
7. **Work ends by proofs; every relevant test runs by itself.** A task needs proofs (deftests naming it in
   `:seon/tasks`) before it is assigned: assignment refuses a task with no proofs or whose proofs are all green
   (tests give their tasks proofs in their setup). After every eval request that evaluated a form, the task's proofs and then every test reaching the change run automatically on the agent's branch,
   as a serial step inside the eval request, recorded with that eval request (§3.5); the agent never has to call `my.test/check`. A
   test the change broke is a blocker the agent sees and fixes, as in any intentional refactoring. The step has a time
   bound: proofs first, reaching tests after, and what the bound leaves continues at the next eval request, shown as pending.
   A code task requests a merge when its proofs are green and no test its change broke is red; `merge!` runs the
   relevant tests again on the merged program and commits only when every one is green (merge.md §4). A task whose
   proofs read an answer completes when they pass on the agent's answer; nothing merges. The task declares its
   answer's Malli schema, and `my.agent/answer!` validates the answer against it and stores it as ordinary Datahike
   data; scratch values stay live in the agent's Vars. An impossible task is given
   back early with a required reason, never claimed. Test status reaches a model agent through the current-state
   block and an MCP agent through the reply.
8. **Budgets** (§2). A model eval request costs no more than an ordinary eval request of the same forms, provider call excluded.
   Transient allocation is garbage-collected and never GB per operation; retained memory stays small. An ordinary
   operation over 1 s, or any GB-class allocation, is stop-and-fix in the turn it is seen, never parked behind a
   future slice. Every regression is reported with its numbers; 10× over what the work should cost is a defect.
9. **Presentation.** One value printer shows every value, errors included (as flat data maps), with one budget and
   general rules only; nothing else clips. Render functions return pruned data for the top-level value; the full value
   is one step away through its result symbol. Elision forms are plain clojure.core on the live result symbol, lazy
   first. Agent-facing messages are data first, one short fixed message per variant, fixes as runnable forms.
10. Delete before adding. Reuse libraries. Understand before designing. Dual reviews. The owner approves core
    designs. Reset whenever needed, with the §5 manifest.

## 2. Budgets

Owner 2026-10-08: no arbitrary targets. Every cost is judged against what the work must do: an expectation from first
principles (what the work is proportional to and the rough cost that implies), the measured parent and change, and
10× or more over the expectation is a defect to diagnose; any regression is reported. The owner's own numbers are
rulings: a model eval request at 20-40 MB transient is fine and never GB; many agents on a 16 GB machine; small retained
memory; a core function's raw return under 2,000 estimated tokens; forgiveness within milliseconds and a few MB per
reply; CLAUDE.md's 1 s and 10 s. Measured each slice by `seon.pass-probe` (`test/seon/pass_probe.clj`): wall ms,
allocated bytes, checked calls, transactions attempted and durable, store bytes, tokens. Seon's work only: the
provider call and the agent's own forms are excluded.

| Pass kind | Expected from the work | Machinery txs |
|---|---|---|
| ordinary (no definitions), model eval requests included | read, evaluate and print the forms, then one recording transaction of the datoms they produced: follows the forms, not the program or the history; a model eval request within the owner's 20-40 MB, never GB | 1 |
| definition (one-line `defn`) | as ordinary, plus one analysis of the form and a few program datoms (the expectation table of ledger RP-0) | 1 |
| the eval request after a definition | as ordinary: nothing is rebuilt for an unchanged program | 1 |
| current-state block, rendered at each model call | follows the task, its proofs and the unread items shown; a handful of pulls, no whole-program work | 0 (read only) |
| the test step after an eval request that evaluated a form (Seon's work, test bodies excluded) | follows the selected tests and their reach inputs, never the program; the next eval request waits no longer than the step bound; never GB (a branch's first reaching check included) | the test requests' own, as §3.2 |
| reply forgiveness scan | one linear scan of the reply: milliseconds and a few MB (owner) | — |
| history append (each eval request) | follows the appended text | — |
| one-test check (runner setup, selection, recording, cleanup) | follows the one member's reach inputs | as §3.2 |
| a new error signature | — | +1 at the task home |

| Retained scope | Expected |
|---|---|
| machine | the whole process on the owner's 16 GB machine beside the user's work: every size, count, byte budget and timeout is a configuration dial with a default sized for it (approved: heap cap 4 GB, `:seon.config.operator/heap-max-bytes`; `seon.cache` 512 MB with live results inside it; Datahike's node cache byte-bounded; ledger §3.24) |
| cluster | one shared base SCI context per loaded program, advanced incrementally; agents fork it; no eval request constructs a base |
| agent | live heap after a full GC per idle agent follows the values it holds, under the one cache's byte budget |
| concurrent eval requests | N simultaneous eval requests stay inside the machine's memory; nothing allocates a GB |

**Output.** A core function's raw return stays under 2,000 estimated tokens in ordinary use; declared bulk-data
functions (reading a document, say) are exempt; an oversized return is a profile finding on that function. The value
printer's display budget has one override: the dynamic var `seon.print/*budget*` (`set!` for the session, `binding`
for one form, like `*print-length*`, strings included) and `:seon.print/budget` metadata on a collection; a render
function may put the same key on its returned data. A hard ceiling follows the model's context window, and an
override above the default adds one short finding with its token count.

**Context tokens.** The current-state block follows the growing history, so it is never prefix-cached; its size is a
per-call cost, printed under `:seon.print/budget` 2,000. Typical: 300-1,200 tokens; each red proof adds ≤ 150. The
static prefix and the history are cache hits on every call after their first (`:seon.ai.usage/cached-tokens`).

**Model eval request owners and their end state.** Base-context construction: zero in any eval request (one shared incremental base,
an overlay per agent branch advanced by changed definitions, one fork advanced in place). Context projection,
prompt and refresh: deleted by the three-part context (held history appended per eval request, block rendered per call).
Seven machinery commits: one. The surviving writer's floor (13-14 MB) and the armer, provider and query residual
(3-7 MB) are accepted as measured. Work derivation: once per eval request. No competency model run starts until
`seon.pass-probe/run-model` reports a model eval request within the owner's 20-40 MB with no owner 10× over its expectation.

## 3. Design

### 3.1 The eval request

```
entrant → its read stage (a value) + text
one cursor, as at a REPL, for each form:
    READ    the next form at the live cursor (§3.3)
    EVAL    in the agent's world and current namespace; SCI runs it; nothing is refused;
            agent writes commit as they run; observations captured WHEN they occur (Var writes
            with their metadata at that time, macro expansions, deletions)
    PRINT   the result value, once, while observation is open → shown text, handle, diagnostics
            (lazy realization happens here and is observed)
once per eval request:
    ANALYZE one clj-kondo run over the eval request's code, joined to the captured observations by span;
            skipped when nothing was defined; JVM eval requests have no SCI observation and no analysis
    RECORD  ONE transaction at the writer on the branch's tip: evaluation rows, program facts
            (the final state; dangling references as findings), the call row, agent facts,
            delivered items, error occurrences; staged blobs published around the commit
    FINISH  each reply = its shown result + diagnostics accumulated through RECORD, composed by
            the value printer from already-printed units; a later failure (recording included)
            is a diagnostic beside the value, never a replacement for it
    TEST    when the eval request evaluated a form: the task's proofs, then the tests reaching the change, on the
            agent's branch, serially, up to the step bound (§3.5); each request carries the eval request id,
            so its results are recorded with the eval request; the reply carries the step's summary as data
```

The record is the agent's evidence, not a second execution: nothing in RECORD re-reads a mutable Var, re-runs a form
or recomputes a value. Effect provenance is the eval request id plus form position as values, never a ref to an evaluation not
yet recorded. Deleted with their last consumers: stored refresh evidence, the run-open intent and its recovery, the
model path's intent commit, the fault-window query, problem-assignment routing, the run-observation re-read, stamps
on transactions, stored profile measurements.

### 3.2 Entrants

| Entrant | Read stage | Eval | Serialized by | Machinery transactions |
|---|---|---|---|---|
| model, REPL entrance | reply: forgiveness, then SCI read | SCI | the agent's proc | 1 per eval request |
| model, DATA entrance | data: one EDN map `{:ns sym :forms [form …]}`; `:ns` is the eval request's start namespace; replies are envelope maps (read-time semantics: `redo/pass-and-recording.md`) | SCI | the agent's proc | 1 per eval request |
| MCP SCI | code: text as is | SCI | the agent's proc (request port) | 1 |
| MCP JVM | code: host read | host | not serialized: each host invocation is owned until its exit; its result is bound into the agent's SCI result owner only when that context is already held, otherwise its handle answers gone-value data with the class; it never changes the agent's namespace or answeredness | 1 |
| test member | its resolved body stage (SCI or host) | as the body | its execution ownership | 1 member eval request |
| test request | the runner | — | — | 1 request aggregate on the recording connection; task-home completion its own write |

The read stage is the value `:seon.batch/read-stage` with a mode (`:reply`, `:code`, `:data`); the dial
`:seon.config.ai/entrance` (`:repl :data`) selects a model agent's entrance. Error occurrences ride the eval request
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

**Code (MCP, tests, generated, page-authored).** Every line is code, so `result/e…`, `:k` and literals evaluate; the
one balancing pass runs as in reply mode, its repairs beside the value. A line that is exactly a fence, outside any form or string, is skipped. Input with no forms
(`#_ 1`, only comments) answers `nil` without an error.

**Evaluation.**
- The namespace is kept after a throw: an `in-ns` before the throw stands, as in `clojure.main`.
- A malformed contract keeps the function callable, its diagnostic beside the value; merge refuses it. A valid armed
  contract refuses a violating input or result.
- `remove-ns`, `ns-unmap`, `ns-unalias`, `intern` and `alter-var-root` run as SCI runs them, and the record stores the
  final state they leave.
- Printed output is captured whole and shown within the reply's one budget; its elision form reads the live output.
- A form that throws does not stop the eval request; later forms run, as at a serial REPL.
- **What a form defined is known from SCI**: the Var written and its metadata at that time, never a form's head or a
  regex; file text is known from clj-kondo's analysis and schemas from the Malli registry (ledger UL-1). A `defn`
  nested in another form is saved from its own source span (SCI's write hook plus the analyzer's span) like any
  definition; one that closes over a local stays live-only with a short note to define it at top level (RP-9).
- **An alias retarget follows Clojure's rule.** Reloading an earlier definition after its namespace retargeted an
  alias reloads the stored source as a Clojure file would, so the definition breaks under the new alias. A finding
  warns the agent when the retarget happens, and merge refuses the broken definition (card RP-8).
- `*1 *2 *3 *e` are bound at an agent's REPL only if easy and cheap; lowest priority (CN-23).

Behavior against plain SCI is the table test `seon.repl-differential-test` and its per-slice namespaces.

### 3.4 Context: static prefix, history, current state

| Part | What | Changes when | Stored |
|---|---|---|---|
| **Static prefix** | `(help)`'s value and the fixed instructions | the loaded commit changes | no; derived per loaded commit, cached in `seon.cache` by commit |
| **History** | only what the agent did: its own forms and their envelopes, in agent order | an eval request appends | yes: each eval request's call row and evaluations |
| **Current-state block** | each current item once, in its current version | any fact it reads changes | **never**; rendered at every model call |

Provider prefix caching covers the prefix and the history, which are byte-identical from one call to the next up to
the end of the history. There is no opening eval request and no input eval request: the task and its references are current state;
messages and errors are unread items shown in the block; `(help)` is the prefix, not a recorded form.

**The current-state block.** One value, `seon.context/current-state [database agent-id]`, read at the call from
stored facts (a database value, never a connection), rendered by its declared AI render pair into pruned data in this
order, and printed by the value printer under the block budget. Sections, each absent when empty:
- **Task**: the task's current declaration (intent and the names it references), read at the task home's current
  main. A task revised by root or the user is a review: the update advances the assignment's revision in the same
  transaction, so the block always shows the current declaration and never an older one. Task steps are shelved
  (owner 2026-10-08): the block shows none; the simplest parts of steps may later move into tasks themselves.
- **Proofs**: each proof test by name with its current stored source, printed as code (whole for an ordinary deftest),
  and its status: `:green`, `:red` or `:pending` (no result whose tested program holds the definitions the proof now
  reaches, including tests the step bound left for the next eval request). Never a latest-green boolean, never `:running`
  (tests run serially inside the eval request). A red proof adds data, `{:test sym :expected … :actual … :message …}`, from the
  stored failure report. Counts head the section.
- **Broken tests**: tests reaching the agent's change, not its proofs, that are red on its branch, each as the same
  data as a red proof, plus the count still pending. These are blockers the agent fixes. A test already red at the
  merge base is marked so; it does not block the merge request and stops `merge!` for root.
- **Must-reads**: built automatically from what the task names: its must-reads and the fully qualified symbols and
  keywords backticked in its intent, each resolved to its current definition: arglists, contract and first docstring
  line, with `(doc sym)` and `(clojure.repl/source sym)` offered as operations. Library names resolve the same way
  (`malli.core/validator`, `datahike.versioning/merge!`, a namespace such as `datahike.norm.norm`): read-only from the
  host's loaded Var, with the project's pinned `reference-code/` revision, and nothing copied into the store.
  Secondary to symbols, a must-read that is not a Var (a README, a doc page) is a path under
  `reference-code/<project>/…`, shown with the pinned revision and one read operation. A name resolving to nothing
  shows as a problem on the task. An agent that runs the operations sees the result in its own history.
- **Unread**: messages and errors delivered to this agent and not yet delivered by a committed model eval request, each with
  its identity; `(my.message/inbox)` is the operation that shows them all (it is extended for unread items; there is
  no `my.agent/inputs`).
- **Turns**: model calls left against the current outside wake, and the task's status.

When everything is green and quiet the status sections collapse to one line (`{:proofs {:green 3} :calls-left 7}`);
the task and proof sources always show, because they define done.

Offered operations (`doc`, `source`, `my.message/inbox`) are domain lookups placed in the render's data; they are not
printer continuations, and the printer never ranks sections. A printer cut leaves its ordinary elision form on the
block's result symbol.

**Delivery.** Proof status needs none: it is recomputed from results. Each unread item the block SHOWED is delivered
by the model eval request whose context presented it: its identity is recorded on that eval request's call row inside the eval request's one
transaction, after the writer checks it still exists and is not already delivered to this agent. A crash before
RECORD or a failed provider call delivers nothing, so the item shows again. An item cut by the budget stays unread
until it is shown. A change in what the assigner wants is a task revision, not a message.

**History.** An exchange is an eval request's call row and its evaluations with source and shown text. The proc holds the
assignment's exchanges as values and appends each finished exchange; it reads ordered history once, at proc start or
a new assignment. Only exchanges whose call row carries a model attempt reach the model; there is no origin tag
(ledger §3.15). Handles keep their identity across restarts and answer gone-value data after their runtime is lost.
Live results are held in `seon.cache` as a kind (`:seon.repl/result`, keyed by evaluation id) under its configured
byte budget, with no count limit, evicted by Caffeine's own policy (W-TinyLFU, which approximates LRU); an evicted result's handle answers gone-value data with its saved shown
text. Values the agent keeps in its own Vars are unaffected.

**Storage roles** (`docs/research/agent-platform/2026-10-08/storage-roles/storage-roles.md`): the cache holds the full
live value; a database fact holds small facts plus shown text up to 4096 bytes
(`:seon.config.eval.result/blob-threshold`, UTF-8 bytes); a blob holds anything larger, the fact keeping its digest
and size, staged and published with that fact's transaction; no second printed copy of a value is ever stored.
Seon's own diagnostics follow the same rule: the full error maps under a result handle, their shown text stored. When prefix + history + block
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

| After a model eval request | Next |
|---|---|
| ≥ 1 form evaluated, no session end, calls left | call the model again |
| a form's value is `(my.turn/wait {:my.turn/note …})` | disposition `:wait`; no model call until a new outside wake. A reply to someone is `(my.message/send …)` followed by `(my.turn/wait …)`; `my.turn/complete`, `my.agent/done` and `seon.run/complete` are deleted |
| no form (prose only), or only read errors | disposition `:wait`; the original wake is answered |
| provider failure or context refusal | counted call, no evaluations, answeredness unchanged, terminal reply |
| call cap reached | disposition `:capped`; one message to the assigner; the next outside wake resumes |
| the task's proofs all green and no broken test | a code task requests a merge (merge.md §1) and the agent waits; a code task with no change against main is done after checking main still holds the tested program, with no merge request; a task whose proofs read an answer is done at the task home; nothing merges |
| a proof recorded red after the agent's answered basis | work: the agent is woken, even after `:wait`, and the block shows the red data |
| `(my.task/complete! {:seon.task/reason …})` (the reason is required) | disposition `:bailed`; the task goes back to the assigner, who is notified; this is the only use of `my.task/complete!` |
| a newer outside wake | answered by the next eval request, whatever the disposition |

**Tests run automatically, inside the eval request.** After RECORD of any eval request that evaluated a form, the agent's proc runs, through the one test entrance
(`seon.test/run`) on the agent's branch: first the task's proofs, as one request naming exactly them; then the tests
reaching the eval request's change, as one reaching request. Each request carries the eval request id, so its results are recorded
with that eval request. The step is serial: the next submission or model call starts after it, so the next block or reply
shows real results and nothing is ever `:running`. A test whose recorded result still holds for the program it now
reaches is answered from that record, so an eval request that changed nothing costs lookups. A test check run inside the
eval request sees the eval request's earlier definitions. It has a time bound (a cluster configuration dial, default 10 s): proofs always
first, reaching tests after; when the bound fires, a started member runs to its actual exit and records, and the
tests not yet run continue at the next eval request's step and show as pending. A newer eval request's change supersedes the queued
tests of the older program (the last version wins). A member whose recorded green still holds for the program it now
reaches is answered from the record. At proc start, tests left unfinished are
derived from stored evidence. The test requests' own writes and merge-request bookkeeping never trigger the step.
There is no proof proc and no running or dirty state.

- A committed model reply advances answered to the basis its context presented and records the delivery of the unread
  items its block showed. Outside and test eval requests never answer.
- The cap counts call rows at the writer against the outside wake whose anchor they consumed. Root's messages are
  outside wakes. A self-wake or MCP retry never refills the cap.
- With no provider configured, no model eval request is admitted. A schedule-only firing arms but opens no call.

### 3.6 Failure

- A crash before RECORD loses the eval request's record, never an agent write that already committed. The next wake
  re-derives the work, so the model may repeat an action.
- On an agent's own branch any writer refusal of the record is Seon's own failure. A definite refusal retries once
  without the refused program facts, recording the evaluations, the call row under its original id and the refusal as
  a diagnostic. If that retry is refused, it records the call row and the error alone. If that fails, it is the
  storage-failure path.
- An unknown outcome gets no retry and no extra write: the eval request keeps ownership of the pending transaction and its
  blob permit, and the agent stays unavailable until the outcome is known; then the diagnostic is delivered.
- Storage failure stops the graph; it never spins. Post-commit installation failure is a separate diagnostic on a
  durable record. Attempted and durable transactions are counted separately.

### 3.7 Lookups and help

`doc`, `dir`, `source`, `apropos` and `find-doc` return data on SCI's `clojure.repl`, installed once in the SCI
init; each declares the SCI context as a world input supplied from the evaluation. A name the agent's context does not
define falls back to the host's loaded Var, so library code is readable at an agent's REPL: `source` of a library Var
is its text in the pinned `reference-code/` checkout, as at the JVM REPL. `(help)` is literal data teaching
only the forms its commit installs, each once, and says plainly how to end a session (`my.message/send` then
`my.turn/wait`, or `my.task/complete!` with a reason to give a task back); it is the static prefix.

### 3.8 Bounds

| Bound | Owner | Fires as |
|---|---|---|
| provider timeout `:seon.config.ai/timeout-ms` | `seon.ai/complete` | a counted failed call |
| SCI evaluation time limit per form | `seon.sci.eval` | that form's error value |
| model calls per outside wake | the writer's count | disposition `:capped` |

The MCP await uses the caller's deadline; on timeout the caller gets a terminal "still running" reply. A host call
that ignores interruption keeps ownership until its actual exit, and no second eval request overlaps it; the actual-exit owner
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
  outside eval request before assignment; the branch is deleted after scoring.
- **Merging.** Root is whoever holds the merge request, the orchestrator or a Seon model agent, through the ordinary
  REPL; a Seon agent must find root's job easy. Root diffs the source branch against the target branch and enforces
  integrity there: dangling references, contracts, schema against the target's data, and the task's proofs, the tests
  reaching the change and root's chosen tests on the merged program. `merge!` commits when every test is green;
  automatic merging is root's choice, configured on the target. Mechanics: merge.md.
- **Scoring** reads stored facts only: proof member counts, eval requests, tokens, read-error types, imitation notes,
  latency, merge outcome, bail reason. Tests and scoring cost no provider call. The suite runs the competency spec's
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

The owner's rulings are kept, current and by topic, in
`docs/research/agent-platform/2026-10-08/review/owner-rulings.md`; each names the section above that implements it.
This spec states them in those sections and keeps no log of its own. Questions still open for the owner: the ledger's
§6.
