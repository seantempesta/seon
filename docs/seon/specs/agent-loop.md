---
type: spec
status: approved
created: 2026-10-07
revised: 2026-10-08
tags: [spec, agent-loop, repl, one-path]
---

# The agent loop: one pass for every entrant

Status: APPROVED by the owner 2026-10-07 (v4.1), revised in place 2026-10-08 with the owner's REPL law, the approved
reader rules, wire formats, the competency suite, session end, the model-pass end state, the three-part context with
its current-state block, and automatic proof runs. Installed claims cite HEAD
`81020da6a`; everything not installed is marked TARGET.

Evidence and ledgers (not normative): v4.1 research and reviews in `docs/research/agent-platform/2026-10-07/root-plan/`
and `plan-research/`; the 2026-10-08 [REPL plan](../../research/agent-platform/2026-10-08/repl-audit/repl-plan.md)
(slices P0-P14), [reconciled split](../../research/agent-platform/2026-10-08/split/reconciled-plan.md) (S1, S3-S7
ledger, root answers Q1-Q18), [competency suite](../../research/agent-platform/2026-10-08/competency/competency-spec.md)
(S8) and [model-pass profile](../../research/agent-platform/2026-10-08/model-pass/profile.md). Numbers are measured runs.

## 1. Principles (owner rulings 2026-10-07 and 2026-10-08)

1. **The REPL law.** Any valid Clojure that SCI can run works, serially, as at a real REPL: each form is read once and
   evaluated once, and later forms see what earlier forms did because SCI ran them. The one transaction per pass only
   RECORDS what ran; it never vetoes, reorders or replays a form. On an agent's own branch the writer never refuses a
   REPL-valid change (removing a function or namespace others still call, redefining, retargeting): the stored program
   is the final state, and each dangling reference becomes a finding delivered beside the value. Integrity is enforced
   where root diffs and merges the branch (§4.10). Installed: the native veto is deleted (P1, `ded5e35de`); SCI owns
   reader policy (P2, `e21fc4674`). TARGET: the writer side (P8).
2. **One path, entrances as read stages.** read → eval (SCI) → record → the one value printer; one cache
   (`seon.cache`), one loop, no second parser, evaluator, printer, store or harness. Each entrance supplies its read
   stage as one value (JVM also its eval stage, a test its resolved body); after reading, every entrance runs the same
   pipeline (§4.2, §4.3).
3. **Light forgiveness for model text only** (§4.3): prose becomes comments and one scan balances delimiters. Valid
   Clojure passes through byte for byte. Code (MCP, tests, generated) is read as is.
4. **Context is three parts, in this order** (§4.4; owner 2026-10-08: "This is not a system where we just infinitely
   append everything"):
   - the **static prefix**: `(help)` and the fixed instructions; it changes only with the loaded commit and is cached;
   - the **history**: only what the agent did, its own forms with their real results, append-only, whole for now;
   - the **current-state block**: rendered fresh at every model call from stored facts, never stored, never repeated.
     Each item appears once, in its current version: the task's current declaration, its proof tests (names and
     current source), proof status with red results as data, the must-reads' signatures, unread messages and errors,
     calls left and the task's status. A task revised by root or the user shows its new version on the next call,
     the old one gone from view.
   - Nothing in the history is re-executed or re-rendered. No `;; =>`.
5. **Exchanges are data; wire formats are adapters** (§4.4). A per-model dial selects the format; the default stays
   one transcript message until the competency suite measures otherwise.
6. **Standard lookups** return data, on SCI's `clojure.repl`.
7. **Success is proofs green, then propose** (§4.5). After a pass that changes definitions, the task's PROOF tests
   that the change reaches run automatically off the pass; the agent reads their status in the current-state block.
   Every other test reaching the change (regressions) runs once at merge, on the combined program (owner 2026-10-08). `my.task/complete!` only bails, with a required reason. A task needs proofs before launch.
8. **Budgets** (§3). A model pass costs no more than an ordinary pass (provider call excluded). Over 1 s or 10 MB of
   Seon work is stop-and-fix in the turn it is seen. A number that gets worse blocks the slice.
9. Delete before adding. Reuse libraries. Understand before designing. Dual reviews. The owner approves core
   designs. Reset whenever needed (~7 s, no migrations), with the §6 manifest.

## 2. Today (measured)

| What (workload) | Measured | Source |
|---|---|---|
| model pass, fixture provider, first wake of a new agent (`seon.pass-probe/run-model`) | 751-928 MB, 522-675 ms, 7 durable commits per wake cycle | model-pass profile |
| the same agent's later wakes | 473-578 MB, 375-438 ms, 7 commits | model-pass profile |
| owners of a first model pass (sampled) | base-context acquisition 428 MB / 180 ms; context projection and prompt 405 MB / ~190 ms; writer, 7 commits, 97 MB / 140 ms; the rest < 50 MB | model-pass profile |
| MCP SCI `(+ 1 1)` | 32-63 MB, 37-67 ms, 1 commit (writer threads 13-14 MB); a base-context miss on a retained agent 463 MB / 256 ms | model-pass profile, REPL plan §1 |
| MCP one-line `defn` | 110-135 ms, 159-162 MB | REPL plan §1 |
| `(+ 1 1)` after `(set! *print-length* 50)` | 170-231 ms, 341-534 MB (lane `repl-p11`) | REPL plan §1 |
| reply read, 28.7 KB reply, armed | 10.5 ms, 4.09 MB | S2b-1 (`84a3c5e4d`) |
| one-test `my.test/check` | warm 126 ms / 191 MB; cold 584-702 ms / 817 MB; one red test in a new SCI test namespace on a fresh MCP agent branch: 769 ms, 74,244 checked calls (lane `spec-tail`, HEAD `666c767f7`) | test-cost; `spec-tail` probe |
| store per small commit | ~150 KB (root copies 36%, index nodes 64%); accepted by the owner | r-record |
| reset | 6-13 s; hosting an agent 1.4-1.6 s, ~100 MB | reset-leak |

Behavior against plain SCI is the table test `seon.repl-differential-test` and its per-slice namespaces
(`seon.repl-differential-p1-test` … `-p5-test`); each landed P slice adds its rows.

## 3. Budgets

Measured by the S0 probe (`test/seon/pass_probe.clj`) each slice: wall ms, allocated bytes (per thread where possible),
checked calls, transactions attempted and durable, store bytes, tokens.

| Pass kind (Seon's work only; excluding the provider and the agent's own forms) | Time | Allocation | Machinery txs |
|---|---|---|---|
| ordinary (no definitions), **model passes included** (excluding the provider call) | < 200 ms | 20-40 MB transient, garbage collected; never GB (owner 2026-10-08) | 1 |
| definition (one-line `defn`) | < 300 ms | < 30 MB | 1 |
| the pass after a definition | < 150 ms | < 10 MB | 1 |
| current-state block, rendered at each model call (§4.4) | < 10 ms | < 2 MB | 0 (read only) |
| proof scheduling inside a pass that changed definitions (reach read and one offer; the run is off the pass) | < 5 ms | < 1 MB | 0 |
| reply forgiveness scan, per 28.6 KB reply | ≤ 3 ms | ≤ 8 MB | — |
| context append (each pass) | < 10 ms | ≤ 3× the appended text | — |
| cold history acquisition (proc start / new assignment) at 1/10/200 exchanges | measured | measured, then a budget set from evidence | — |
| one-test check (runner setup, selection, recording, cleanup) | < 300 ms cold, < 150 ms warm | < 20 MB | as §4.2 |
| a new fault signature | — | — | +1 at the task home |

Store: ~150 KB for an ordinary small pass, as observed. The definition-pass and test-check targets exceed the 10 MB
stop-and-fix line (owner decision §7.5). S0 uses the HTTP fixture for the model entrant; other entrants use their real
entrances. S0 workload: histories of 1, 10 and 200 exchanges at 2 KB of shown text each; one ordinary pass, one `defn`
pass and the pass after it; the current-state block's render time, allocation and estimated tokens (green and quiet,
one red proof, over budget); cold acquisition and warm execution reported separately; each wire format during S8. The
window refusal (§4.4) bounds larger histories.

**Context tokens.** The current-state block is never prefix-cached (it follows the growing history), so its size is a
per-call cost: ≤ 2,000 estimated tokens, the value printer's budget for the block (§4.4). Typical: 300-1,200 (task,
steps, one to three proof sources, a green status line). Each red proof adds ≤ 150 tokens. The static prefix and the
history are cache hits on every call after their first (measured from `:seon.ai.usage/cached-tokens`).

### 3.1 Model-pass end state

A model pass costs no more than an ordinary pass of the same forms plus the current-state block's row, excluding the
provider call. Today's 926-945 MB pass is a defect, not a baseline. What removes it, by owner:

| Owner (first-pass MB) | Removed by |
|---|---|
| base-context acquisition (428; `resume-turn` still acquires, `src/seon/turn.clj:5153`) | TARGET new slice: acquisition and context retention (task `seon.sci.eval/acquisition-rebuilds-the-whole-program`). S4-06 deletes only the refresh's caller; S5-06 stops only JVM-mode acquisition |
| context projection, prompt and refresh (405 + 12) | S4-02b, S4-03, S4-04, S4-06, S4-07b |
| seven commits → one (97) | S4-06, S4-07b, S5-02, S5-05, S5-07a-f, S6-04a/b, P14 |
| the surviving commit's floor (13-14 MB) | TARGET new slice: writer floor (tasks `seon.db/the-checked-writer-is-slow-on-a-fresh-branch`, `seon.db/every-commit-validates-its-write-report-three-ways`) |
| per-pass fork and regeneration (11-13) | TARGET, the acquisition and retention slice: SCI's own `sci/fork` without Seon's namespace walk |
| work derivation (6-10) | S6-05, S6-07 (task `seon.turn/a-turn-pass-derives-its-work-twice`) |
| armer, provider configuration and query residual (3-7) | TARGET new measured residual slice |

After only the enumerated slices the profile forecasts 20-40 MB and 40-100 ms: inside the time budget, 4-8× over the
allocation budget. The three TARGET slices are required. No competency model run starts until the probe's model row is
inside the ordinary row (competency C0).

### 3.2 System memory budget (owner 2026-10-08)

Many agents run at once, and a user needs no more than 16 GB of RAM. Transient per-pass allocation may exceed the
per-pass rows when it is garbage collected, but an operation never allocates a GB.

| Scope | Budget | Status |
|---|---|---|
| **Machine** | the whole Seon process runs on a 16 GB machine beside the user's other work: JVM heap cap ≤ 4 GB, process RSS ≤ 6 GB | TARGET; default's heap max is about 11 GB today |
| **Shared** | one base SCI context per cluster, shared and advanced incrementally; agents fork it; never rebuilt per pass | TARGET (§3.1 acquisition and retention slice) |
| **Per agent** | retained memory (live heap after a full GC) per idle agent ≤ 20 MB, so 100 agents ≈ 2 GB | TARGET |
| **Concurrent passes** | N simultaneous passes stay within the heap cap; transient allocation is collected; no operation allocates a GB | TARGET |

Measurement (TARGET; root runs it): a probe of live heap after a full GC with 1, 10 and 50 agents, plus per-agent
growth. The numbers stay TARGET until measured.

## 4. Design

### 4.1 The pass

```
entrant → its read stage (a value) + text
one cursor, as at a REPL, for each form:
    READ    next form (§4.3)
    EVAL    in the agent's world and current namespace; nothing is refused, SCI runs it;
            agent writes commit as they run; observations captured WHEN they occur (Var
            writes with their metadata at that time, macro expansions, deletions), never
            re-read from mutable Vars at the end
    PRINT   the result value, once, while observation is open → shown text, handle, diagnostics
            (lazy realization happens here and is observed)
once per pass:
    ANALYZE ONE clj-kondo run over the reader's code slices only (non-code blanked to spaces,
            newlines and columns kept, so spans are original offsets; each slice's observed
            namespace and aliases carried along); joined to the captured observations by span;
            skipped when nothing was defined. JVM passes have no SCI observation and no analysis
    RECORD  ONE transaction, [:db.fn/call] at the writer on the current head: evaluation rows
            (ids = pass id + position, minted before eval; agent ordinals assigned at the
            writer), program facts (the final state; dangling references as findings, §1.1),
            the call row, agent facts, fault occurrences; staged blobs published around the
            commit through `blob/with-publication!`. The stored exchange = the shown result +
            every diagnostic known before the commit. `seon.db/transact-call` keeps its pending
            completion and the blob permit until an unknown outcome is known (S5-01)
    FINISH  each reply envelope = its shown result + diagnostics accumulated through RECORD,
            composed by the value printer from already-printed units. Diagnostics that arise
            after the commit are delivered immediately to the caller, and durably as recorded
            errors, so the next current-state block shows them as unread (§4.4)
    SCHEDULE when the pass changed definitions: one offer of the changed set to the agent's
            proof proc (§4.5); never awaited by the pass
```

**Same-pass admission** is settled by the ten S5-03 case notes and the revised
[admission design](../../research/agent-platform/2026-10-08/s5-admission/admission-design.md): a later form sees an
earlier form's definition, macro, alias, schema or removal because SCI ran it, not because facts were applied. TARGET
(P8): per-form `apply-pending` and `db/with` and the facts-error stamp are deleted; one analysis and fold at the end of
the pass; a successful evaluation is never marked failed.

**What the one transaction stops writing** (each deleted together with its last consumer):

| Item | Fate |
|---|---|
| read evidence, `executed`, `code-evidence`, receipt | consumed only by the refresh; deleted in S4 |
| `*open*` intent and its bindings, `open-tx`, boot `recover-tx` | still used by effects and effect-reaching tests; deleted in S6-04a/b with effect provenance and test-attempt conversion |
| the model path's intent commit (`stage-reply!`, `plan-tx`) | folded into the settlement (P14) |
| the fault-window query | replaced by the fault occurrences the pass observed (S5-05) |
| problem-assignment routing | deleted (task 5e7b36bfd02d) |
| the run-observation reread | replies come from the held pass (S5) |
| unread token, elision and transaction hot/repeated/ms stamps | deleted |
| profile measurement upserts | no longer stored (§7.2) |

### 4.2 Entrants

| Entrant | Read stage | Eval | Serialized by | Machinery transactions |
|---|---|---|---|---|
| model, REPL entrance | reply: forgiveness, then SCI read (§4.3) | SCI | the agent's proc | 1 per pass |
| model, DATA entrance (TARGET, competency C6) | data: the reply is one EDN map `{:ns sym :forms [form …]}`, read once with locations; `:ns` is the pass's start namespace (the seam MCP's `namespace` argument uses); each `:forms` element is evaluated at its span; the reply is envelope maps (the DATA presentation) | SCI | the agent's proc | 1 per pass |
| MCP SCI | code: text as is | SCI | the agent's proc (request port) | 1 |
| MCP JVM | code: host read | host | **not serialized.** Each host invocation is owned until its exit. Its result is bound into the agent's SCI result owner only when that context is already held; otherwise its handle answers gone-value data with the class (S5-06). Never changes the agent's current namespace or answeredness | 1 |
| test member | its resolved body stage (SCI or host) | as the body | its execution ownership | 1 member pass |
| test request | the runner | — | — | 1 request aggregate on the recording connection; task-home completion its own write |

Installed: the read stage is a value, `:seon.batch/read-stage` (`src/seon/sci/batch.clj:19-23`), with modes
`[:enum :reply :code]` (`resources/seon/schemas/seon.batch.edn:28`). TARGET: the `:data` mode and the
`:seon.config.ai/entrance` dial (`:repl :data`) that selects it and its presentation.

- **Faults.** The branch-local occurrence rides the pass transaction. The authoritative copy is recorded once per
  signature at the task home, as the errors spec rules, in its own counted transaction.
- **Effects.** Provenance is pass id + position as values, never a ref to an evaluation not yet recorded (S6-01).
- **Requests.** A timed-out MCP SCI caller's queued submission is dropped if it has not started. If it has started, it
  runs to its actual exit and is recorded (no overlap).

### 4.3 Reading and evaluation rules (approved 2026-10-08)

**Reading, every mode.** Each form is read once at the live cursor, after the previous form ran, by SCI under the
constant options `{:features #{:clj} :end-location true}` (`src/seon/sci/batch.clj:13-17`): SCI owns tags, records,
`*data-readers*`, `#=` and `::alias`, and `#?` picks `:clj` (installed). A map with `:seon.repl/*` keys is data
(installed, P2). A form that fails to read is its own error and reading continues after it: a collection open at the
failure spans to its matching closer, so the forms inside it never run; any other failure spans to where the reader
stopped; a collection that never closes spans to its region's end. `#_` and a reader conditional that selects nothing
yield no value.

Installed today: S2b-1's stepper `seon.sci.forgive/next-form` (`src/seon/sci/forgive.clj:347`) over SCI `parse-next`.
TARGET (P7): `sci/parse-next+string` (`reference-code/sci/src/sci/core.cljc:400`) at the live cursor; `segments`,
`seon.turn/read-row` and the pre-segmented rows are deleted.

**Model text (reply mode): light forgiveness.** TARGET (P6, lane `repl-p6`): one linear scan before reading, tracking
string, character, comment and delimiter state across lines, within ≤ 3 ms and ≤ 8 MB per 28.6 KB reply.
- **Fences.** A line of three or more backticks, untagged or tagged `clojure`, `clj`, `cljc` or `edn`, opens a code
  fence; any other tag opens a prose fence. A fence closes with a line of at least as many backticks and no tag. A
  fence line inside a string or an open form is not a fence. Inside a code fence every line is code.
- **Code lines outside fences.** A line is code when it starts with `(`, `[`, `{`, `` ` ``, `'`, `@`, `^`, or `#`
  followed by a character other than whitespace or `#`; or when the whole trimmed line reads as exactly one literal
  (number, string, keyword, character, `##NaN`) or one namespace-qualified symbol. So `42`, `##NaN`, `` `(+ 1 2) ``,
  `\newline` and `result/e1a2b3c4d5e6` are code; `# Heading`, `1. First step`, `Done.` and `x` are prose. While a form
  is open, following lines continue it.
- **Prose becomes comments.** Outside a code fence and outside an open form, a prose line becomes a `;` comment
  attached to the next form; trailing prose is kept on the last exchange. After a form ends mid-line, the rest of the
  line is code only if it starts a form or `;`; otherwise it becomes that form's comment, so `(+ 1 2) Done.` evaluates
  once. (This replaces S2b-1's rule that every form on a started line runs.)
- **One balancing pass.** Text changes only when a region is unbalanced: a stray closer is dropped, and missing closers
  are inserted before the next column-zero form or prose line. Each change is a note beside the value,
  `{:line n :inserted ")"}` or `{:line n :dropped "}"}`.
- **Imitations.** `;; =>` and `ns=>` lines outside fences are prose. TARGET: the reader's imitation note, carried in the
  exchange's diagnostics with its `:seon.error/type` (the competency suite scores it).
- **No form.** A prose-only reply records one exchange (its call row) with the "no form" refusal and no evaluation,
  which tells the model to act.

**Code (MCP, tests, code Seon generates or a page authors).** Text as is: every line is code, so `result/e…`, `*1`,
`:k` and literals evaluate. TARGET: a line that is exactly a fence, outside any form or string, is skipped (P6); input
with no forms (`#_ 1`, only comments) answers `nil` without an error (P12).

**Evaluation.**
- The namespace is kept after a throw: an `in-ns` before the throw stands, as in `clojure.main` (installed P3,
  `src/seon/sci/eval.clj:4182`; `seon.repl-differential-p3-test/in-ns-before-a-throw-is-kept`).
- A malformed contract keeps the function callable, with its diagnostic beside the value; merge refuses it. A valid
  armed contract refuses a violating result (installed P4; `seon.repl-differential-p4-test`).
- `remove-ns`, `ns-unalias`, `intern` and `alter-var-root` run as SCI runs them (installed P1).
- Printed output answers within the value printer's budget with a dig form (installed P5).
- A form that throws does not stop the pass; later forms run, as at a serial REPL.

### 4.4 Context: static prefix, history, current state

A model call's context is three parts, in this order (owner ruling 2026-10-08, §7.12). Only the history is stored;
the other two are derived at the call. Provider prefix caching covers the static prefix and the history, because both
are byte-identical from one call to the next until the end of the history.

| Part | What | Changes when | Stored |
|---|---|---|---|
| **Static prefix** | `(help)`'s value (it teaches only what is installed in its commit, §4.7) and the fixed instructions | the loaded commit changes | no; derived per loaded commit, cached in `seon.cache` by commit |
| **History** | only what the agent did: its own forms and their envelopes, in agent order | a pass appends | yes: the pass's call row and evaluations (§4.1 RECORD) |
| **Current-state block** | each current-state item once, in its current version (below) | any stored fact it reads changes | **never**; rendered at every model call, never appended to the history |

**There is no opening pass and no input pass.** What the opening used to put into the history once (the task, its
references) is current state and lives only in the block, so an update shows on the next call with no stale copy left
behind. What an input pass used to record (messages, errors, proof results) is either current state (proof status) or
an unread item shown in the block, whose delivery the model pass records (below). `(help)` is the static prefix, not a
recorded form.

**The current-state block** (TARGET, slices CS-01..CS-06 in the reconciled plan). One value,
`seon.context/current-state [database agent-id] → :seon.context/current-state`, read at the call from stored facts,
rendered by its declared AI render pair and printed by the value printer under one budget. Read only: it gets a
database value, never a connection. Sections, each absent when empty:

- **Task**: the task's current declaration (`:seon.task/intent`, `:seon.task/must-read`, its ordered steps), read at
  the task home's current head. The declaration's identity is `seon.task/task-revision` (`src/seon/task.clj:249-256`,
  a digest over the declaration); the assignment row carries the revision root reviewed (`:seon.task/revision`, read
  by `seon.task/assignment`, `src/seon/task.clj:496-519`). Installed today: a revised declaration makes `assignment`
  refuse `:revised-since-assignment` until root reassigns. TARGET (CS-05): root's or the user's task update is a review,
  so the task-update writer advances the assignment's `:seon.task/revision` in the same transaction; the block always
  shows the current declaration and never an older one.
- **Proofs**: each proof test by name with its current source, printed as code through the value printer (whole for an
  ordinary deftest), and its status. Proofs are the deftests whose `:seon.task/tasks` holds the task, read by
  `seon.task/task-edges` (`src/seon/task.clj:321-338`, `:seon.task/proofs`; probe: 62 tasks at HEAD have proofs). The
  source is the stored declaration source the program index holds (installed lookup:
  `(clojure.repl/source comp.geo-test/area)` prints a stored deftest whole as `:out` in SCI; probed at HEAD). Status
  per proof: `:green`, `:red`, `:pending` (no result whose basis holds the proof's current reach) or `:running`. A
  result counts only when its tested program holds the current definitions the proof reaches: the basis rule of
  `seon.render.var/status` (`src/seon/render/var.clj:85-127`) and the reach evidence of `seon.test/verified?`
  (`src/seon/test.clj:1562`), never a latest-green boolean. Each red proof adds data, never text: `{:test sym
  :expected … :actual … :message …}` from the stored failure report (`:seon.test.failure/expected`, `/actual`,
  `/message`, `resources/seon/schemas/seon.test.report.edn:7-24`; probe: a red member recorded `["(= 9 (c/area 3))" "(not
  (= 9 12))" ""]`). Counts head the section: `{:green n :red n :pending n :running n}`.
- **Must-reads**: each must-read reference's current arglists, contract and first docstring line, with
  `(doc sym)` and `(clojure.repl/source sym)` as operations. Recommended over whole docs: they reflect current code
  (an agent that changes a must-read sees the new signature on the next call), and the block's uncached tokens stay
  small. The agent's own `doc` or `source` call lands in its history as its own form.
- **Unread**: messages and errors delivered to this agent and not yet delivered by a committed model pass, each with
  its identity, shown through the value printer; beyond the budget, a count and the elision form `(my.agent/inputs)`.
- **Turns**: model calls left against the current outside wake (the cap, §4.5) and the task's status (assigned,
  proposed, accepted, bailed).

When everything is green and quiet the status sections collapse to one line (`{:proofs {:green 3} :calls-left 7}`);
the task and proof sources are always shown, because they are the definition of done.

**Budget and growth.** The block is printed by the one value printer with a budget of 2,000 estimated tokens (§3,
"Context tokens"). The printer spreads it across the block's shape: the counts and red data first, then the task, then
the proof sources, then must-reads, then unread items. A cut leaves its elision form as data (`(clojure.repl/source
sym)`, `(my.agent/inputs)`, `(doc sym)`), which the agent may run; what it runs lands in its history as its own form.
Nothing else clips the block. Render cost: § 3 row (< 10 ms, < 2 MB).

**Delivery identities (durable).** Proof status needs none: it is recomputed from results. Each unread item the block
SHOWED is delivered by the model pass whose context presented it: its identities are recorded on that pass's call
row as `:seon.cluster.eval/delivered` (cardinality many, a ref per item) inside the pass's one transaction, after the
writer checks each still exists and is not already delivered to this agent. "Unread" is the items with no such ref
from this agent, read at each home's basis (no t comparison across homes). A crash before RECORD or a failed provider
call delivers nothing, so the items show again on the next call (at least once, §4.6). An item cut by the budget is
not delivered until it is shown, or until the agent's own `(my.agent/inputs)` evaluation shows it, which records it
the same way. Once delivered, an item leaves the block; the agent re-reads it with `(my.agent/inputs {:my.agent/delivered
true})`. A change in what the assigner wants is a task revision (shown as current state), not a message.

**History.**
- An exchange is a pass's call row and its evaluations with source and shown text. The proc holds the assignment's
  exchanges as values and appends each pass's finished exchange; it reads ordered history once, at proc start or a new
  assignment, with one narrow read of the active assignment's `:model` exchanges. Origins: installed `:model :outside`
  (`resources/seon/schemas/seon.cluster.eval.edn:36-41`); `:test` is added by S6-02a. `:outside` and `:test` exchanges
  are not shown to the model. No `:opening` or `:input` origin is added.
- Handles keep their identity across restarts and answer gone-value data after their runtime is lost.
- **Window:** when static prefix + whole history + block exceed the provider's window, the call refuses with an error
  the owner sees. No clipping.

**Wire formats** (TARGET, competency C5). One pure adapter, `seon.ai/messages [wrapping prefix exchanges block] →
messages`, with one branch per wire format; `request-body` takes exchanges when present. In every format the block is
the LAST content before the model's turn, so the cached prefix runs to the end of the history.
- **A `:transcript`**: the static prefix as the system message; one user message holding the transcript, then the
  block at its end. Today `request-body` sends an optional system message plus one user message
  (`src/seon/ai.clj:572-575`).
- **B `:chat`**: the static prefix as the system message; each form text as an assistant message and its envelope as
  the next user message; the block is a second content part of the last user message (never a separate consecutive
  user message), or the only user message before the first form. The cache covers everything before that last message.
- **C `:prefix`**: B plus a final assistant message the model continues from (`ns=> ` for REPL, `{:forms [` for DATA),
  after the block, only where the provider supports prefix continuation (DeepSeek's beta endpoint).

The dial `:seon.config.ai/wrapping` (`:transcript :chat :prefix`) lives on the model row with an agent overlay; the
default stays `:transcript` until the competency table is read. A format the table shows losing everywhere is deleted
with its branch.

- **Token measurement.** Every model call records the provider-reported prompt, completion and cached tokens on its
  attempt row (installed: `seon.ai/normalize-usage`, `src/seon/ai.clj:864-875`, maps `cached_tokens` and DeepSeek's
  `prompt_cache_hit_tokens` to `:seon.ai.usage/cached-tokens`). Misses are prompt − cached; with the block last, a warm
  call's misses are about the block plus the newest exchange. A provider that reports no usage records a typed unknown,
  never zero. TARGET: `:seon.ai.attempt/latency-ms` from the value `complete` returns, and the block's estimated tokens
  on the attempt row.
- **Presentations.** REPL (the REPL entrance) is the form as sent, then its envelope. DATA (the DATA entrance) is the
  envelope map including `:form`. Both are composed by the value printer at FINISH from already-printed units.
- **Cost of a wake cycle** = the model pass (1 transaction) + the block render (0 transactions).

### 4.5 Work, continuation, session end

| After a model pass | Next |
|---|---|
| ≥ 1 form evaluated, no session end, calls left | call the model again (§7.1) |
| a form's value is `(my.turn/wait {:my.turn/note …})` (installed, `src/my/turn.clj:17`) | disposition `:wait`; no model call until a new outside wake |
| no form (prose only), or only read errors | disposition `:wait`; the original wake is answered |
| provider failure or context refusal | counted call, no evaluations, answeredness unchanged, terminal reply |
| call cap reached | disposition `:capped`; one message to the assigner; the next outside wake resumes |
| the task's proofs all green at their current reach | **success**, from the proof proc's recording (below), never from a model call: on the agent's own branch the agent's branch is proposed (§4.10), which opens the merge task; on root's merge branch, all green on the combined program plus root's acceptance completes the task, written by the idempotent task-home writer (TARGET AR-02, Q16) |
| a proof result recorded red after the agent's answered basis | work: the agent is woken (even after `:wait`) and the next block shows the red data (TARGET AR-03) |
| `(my.task/complete! {… reason})` | **bail** (TARGET S6-06b): disposition `:bailed`, reason required, the task goes back to the assigner, who is notified. `my.turn/complete` (`src/my/turn.clj:5`), `my.task/done!` and `seon.run/complete` are deleted with it |
| a newer outside wake | answered by the next pass, whatever the disposition |

**Proofs run automatically** (TARGET AR-01; CLAUDE.md: "a defn's batch schedules its reaching test agents; the Var
render only reads their status").
- **What runs.** After a pass whose settlement changed definitions (program facts added, changed or removed), the
  task's PROOF tests reaching that changed set (a handful) run as ONE request. Other reaching tests are regression
  checks and run once at merge on the combined program (`merge.md`, criterion `:no-new-red`), never per pass. The request goes through the one test entrance,
  `seon.test/run` (`src/seon/test.clj:2225`) with `:seon.test/policy :named` and `:seon.test/changed` the pass's changed
  set: the same request `my.test/check` makes (`src/my/test.clj:6-29`). A member whose recorded green still holds is
  answered from the record without execution, so the work is proportional to the reaching tests whose reach changed.
- **Scheduling.** The pass never waits. At SCHEDULE (§4.1) it offers its changed set to the agent's **proof proc**, a
  `:compute` proc in the agent's own Flow graph. The proc holds the pending changed set in its state and unions each
  offer into it; when idle it starts one request with the union and clears it. One request per agent is in flight; a
  started request runs to its actual exit and records (§4.8), and changes that arrive meanwhile wait in the union for
  the next request, so nothing is dropped and nothing overlaps. Results are recorded on the agent's recording
  connection; members run on their own branches (Q11).
- **Bound.** In the pass: < 5 ms, < 1 MB (one changed-set offer; §3). Off the pass: the request's
  `:seon.test/check-time-limit-ms` (`src/seon/test.clj:2246-2285`) and the per-member §3 row (< 150 ms warm, < 20 MB
  of Seon work per one-test check; measured 769 ms / 74,244 calls on a fresh MCP agent branch, which AR-01's budget row
  must bring inside).
- **Reading.** The next model call's block shows each proof's status, `:running` while its request is in flight and
  `:pending` when no result holds its current reach. The model is not held for the run: it acts on what the block
  shows, and a red result recorded after it waits wakes it.

**Today (HEAD `666c767f7`).** Installed: the Var render reads reaching-test status (pending, newly red, newly green)
and offers `(my.test/check {:seon.test/changed [sym]})` (`src/seon/render/var.clj:85-139`); `my.test/check` runs the
reaching tests through `seon.test/run` and records member results and failure reports with expected, actual and
message (`resources/seon/schemas/seon.test.report.edn:7-24`); task proofs are read by `seon.task/task-edges`
(`src/seon/task.clj:321-338`). **Not installed:** no pass schedules reaching tests. The only callers of
`seon.test/run` are `my.test/check`, `my.test/run-owned` (`src/my/test.clj:26,51`), `my.program/propose-merge!`
(`src/my/program.clj:448`) and root's candidate gate (`src/seon/cluster.clj:2621-2640`). Probe on a fresh MCP agent
branch: after `(defn area …)` was redefined, `seon.render.var/status` answered `{:pending [tailprobe.core-test/area-squares]}`
and no member row existed, also after a later pass; the Var render's namespace docstring
(`src/seon/render/var.clj:11-12`) claims the batch schedules them (task `seon.render.var/reaching-tests-are-never-run-and-a-first-red-is-silent`).
After an explicit `my.test/check` recorded the test red, `status` answered `{}`: a red with no earlier result is
neither pending nor newly red, so the Var render shows nothing (same task). `(doc <task-symbol>)` refuses with
`:seon.sci.eval/documentation-unavailable`.

- A committed model reply advances answered to the basis its context presented, and records the delivery of the
  unread items its block showed (§4.4). Outside and test passes never answer.
- The cap: installed as `:seon.config.run/max-episode-runs` (`src/seon/turn.clj:2692`). TARGET (S6-05): call rows
  counted at the writer against the outside wake whose anchor they consumed. Root's messages are outside wakes. A
  self-wake or MCP retry never refills the cap.
- With no provider configured, no model pass is admitted. A schedule-only firing arms but opens no call.

### 4.6 Failure

- A crash before RECORD loses the pass's record, never an agent write that already committed. The next wake re-derives
  the work, so the model may repeat an action (owner ruling).
- On an agent's own branch the writer never refuses a REPL-valid program change (§1.1). Any refusal there is Seon's
  own failure, handled below.
- **A definite refusal** retries once without the refused program facts. The retry records the evaluations, the call
  row (under its original call id) and the refusal as a diagnostic. If that retry is refused too, it records the call
  row and the fault alone, in one transaction. If that fails as well, it is the storage-failure path.
- **An unknown outcome** gets no retry and no extra write. The pass keeps ownership of the pending transaction and the
  agent stays unavailable until the outcome is known, then the diagnostic is delivered.
- **Storage failure** stops the graph; it never spins.
- **Post-commit installation failure** is a separate diagnostic on a durable record.
- Attempted and durable transactions are counted separately.

### 4.7 Lookups and help (S1)

`doc`, `dir`, `source`, `apropos` and `find-doc` return data on SCI's `clojure.repl`; `(help)` teaches only what is
installed in its commit. Landed: S1-01 (`f50ef6059`), S1-02 (`438328b34`), S1-03 (`d11dbe2b6`), S1-04 (`7876f594e`),
S1-05 (`28c141d56`). S1-06..S1-11 follow the reconciled ledger.

### 4.8 Bounds

| Bound | Owner | Fires as |
|---|---|---|
| provider timeout `:seon.config.ai/timeout-ms` | `ai/complete` | a counted failed call |
| SCI evaluation time limit per form | `seon.sci.eval` | that form's error value |
| model calls per outside wake | the writer's count | disposition `:capped` |

The MCP await uses the caller's deadline. On timeout the caller gets a terminal "still running" reply. A host call
that ignores interruption keeps ownership until its actual exit, and no second pass overlaps it; the actual-exit owner
keeps one detector that names the missing terminal event. The caller's lifecycle deadline passes explicitly into
`disarm!` and `stop-graph!` (TARGET S6-07). "No second pass overlaps" applies to the serialized SCI/model execution; JVM
invocations are owned individually until exit.

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

Capture-before-call and the intent commit are deleted (§7.4).

### 4.10 Experiments: variants, environments, merging

- **Variant.** A run's variant is its agent's settings overlay (`my.agent/settings!`): model, wrapping, entrance, cap.
  No run entity: the run is the agent, its task is its assignment.
- **Environment.** Each run is a fresh agent on its own branch and SCI fork. Setup code runs as an outside pass before
  assignment; the branch is unlinked after scoring, so every run starts from the same program.
- **Merging.** The agent proposes; root diffs the branch against the cluster head and enforces integrity there:
  dangling references, contracts and the task's proofs on the combined program (§1.1). The mechanics are
  `docs/seon/specs/merge.md` (TARGET, lane `spec-merge`).
- **Scoring** reads stored facts only: proof member counts, passes, tokens, read-error types, imitation notes, latency
  (competency §3). Proofs and scoring cost no provider call.

## 5. Order of work

Every slice: understanding traced; a design note; a dual review; owner approval (core slices); code; a dual review of
the diff; S0 numbers; root restarts or resets per the §6 manifest.

| # | Slice | Becomes true |
|---|---|---|
| S0 | Baseline probe (§3 workload) per entrant: `seon.pass-probe` (installed) | measured baseline |
| P0-P14 | REPL plan §5. Landed: P0-P5. In flight: P6 (`repl-p6`), P11 (`repl-p11`). Then P7, P8, P9, P10, P12, P13, P14 | the REPL law, §4.3 |
| S1 | Lookups and help (§4.7) | standard lookups |
| S2a, S2b-1 | Landed: dead debug views deleted (`25bb94be0`); reader rules in the shared stepper (`84a3c5e4d`) | — |
| S3 | Landed: S3-01, origin `:model :outside` (`2bf3e10db`) | facts exist |
| S4 | Context: static prefix, history of the agent's own exchanges held by the proc, the current-state block (CS-01..CS-06) and the prompt seam. Deletes the system turn, its seed, the opening, the old prompt, walk and capture. **Reset point** | the model sees what it did plus the current state |
| S5 | One writer shared by all entrants (S5-01..07; S5-04 is P8) | one writer |
| S6 | The cut: one cursor (S6-03 after P7), `*open*`/`open-tx`/`recover-tx` and effect provenance converted, run machinery, permits, backstop, parking and episodes deleted; §4.5 work rules; §4.6 failure; §4.8 bounds; launch and bail. **Reset point** | one machinery transaction per pass |
| S7 | Automatic proof runs off the pass (AR-01), success from all-green proofs (AR-02), a red result wakes (AR-03); SCI `run-test-var` (S7-01) | test-based completion |
| X-perf | TARGET: acquisition and context retention; writer floor; residual (§3.1) | model pass inside the ordinary row |
| S8 | The [competency suite](../../research/agent-platform/2026-10-08/competency/competency-spec.md) (C0-C8): eight tasks with proof deftests, the task × wrapping × entrance × model matrix (3 × 2 × 8 × 3 repetitions per model, DeepSeek flash first), scores read from the database. Model runs wait for C0 (§3.1); the full matrix waits for S4 | owner reads the table |

Slice rows (files, proofs, dependencies) live in the ledgers named at the top. Until S7, a session ends by wait, bail
or the cap.

## 6. Reset manifest (each reset point)

Before the reset:
- dump runtime tasks (`:seon.task/created-by`), assignments, agent settings, schedules and open error signatures
  to `docs/research/<date>/reset-<n>/`;
- persist the tasks into task files;
- verify task identities and references round-trip from the committed archive.

Restored after the reset (checked): task files ingest with identities and references intact. Archive-only (root
re-applies by documented forms if still needed): assignments and their revisions, agent settings, schedules, open
error signatures. Discarded: runtime-only definitions, messages, live handles (gone-value markers), evaluation
history, error occurrences.

After the reset: retire only owned or unclaimed branches. Each schema retirement is adopted on a branch of the live
store BEFORE its slice lands.

## 7. Owner decisions

2026-10-07:
1. **Continuation:** after a model pass that evaluated ≥ 1 form, with no session end and calls left, the model is
   called again.
2. **Profile measurement upserts:** no longer stored. Health reads the live profile since boot.
3. **Proof-test runs:** the test body is agent work; runner setup, selection, recording and cleanup are Seon work.
4. **Reversed rulings:** capture-before-call and the intent commit are dropped.
5. **Allocation:** a one-line definition pass < 30 MB and a one-test check < 20 MB are interim targets. Stop-and-fix
   still applies above them; tighten them with evidence after S5/S6.

2026-10-08:
6. **The REPL law** (§1.1), including P8: the writer never refuses a REPL-valid change on an agent's own branch;
   integrity is checked at root's diff and merge.
7. **The REPL plan's policies** (§4.3): `#?` picks `:clj`; the namespace is kept after a throw; a malformed contract
   keeps the function; bare literal and qualified-symbol lines are code; words after a form on its line are its
   comment; empty code input answers `nil`; code mode skips fence lines.
8. **Wire formats** are per-model adapters over stored exchanges; the default stays `:transcript` until measured.
9. **S8 is the competency suite.**
10. **The model-pass budget** is the ordinary-pass budget: 20-40 MB transient per pass is fine when garbage collected; never GB; retained memory stays small (§3.2); no world accepts today's 945 MB pass. A task completes when its proofs pass on the combined program after root (or the auto-accept policy) accepts the merge.
11. **Memory** (§3.2): many simultaneous agents on a 16 GB machine; transient allocation may be collected, never GB per
    operation.

12. **Context is dynamic for current state** (§1.4, §4.4): "This is not a system where we just infinitely append
    everything. So the task is always current and if root or the user requests an update the agent would see the
    current version every turn with no repeating." The context is the static prefix, the agent's own history and one
    current-state block rendered at each call. The opening and input passes are deleted. The agent needs "the actual
    tests that they are expected to pass": the block shows each proof by name with its current source and status.
13. **Proofs run after every pass that changes code they reach** (§4.5), off the pass, and their status reaches the
    agent only through the current-state block, never repeated in the history.

Open:
- **Subtree counts** (dh-fix): child counts in branch nodes, counting at each split, or a recount job.
- **Must-reads in the block** (reconciled Q2b, superseded): recommended signatures plus the first docstring line, with
  `doc` and `source` as operations; whole docs only if the competency table shows agents missing them.
