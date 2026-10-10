---
title: The plan
type: plan
status: active
created: 2026-10-10
---

# The plan

One plan, revised in place. Written by lane fable-docs on 2026-10-10 from AGENTS.md (fb98f1752), the 2026-10-09
plan and its two audits, the 2026-10-10 research (measurement, one-system, astra-one-system, designed-task-examples,
generative-testing, astra-malli-behavior, fable-simplify) and the cards BEHAVIOR-SPEC, COST-REG and PLATFORM-STEP.
Live facts were checked on default (archive ffe62e1c3, pid 20363, `:faulted`) in JVM mode, read-only. It supersedes
`docs/research/agent-platform/2026-10-09/plan.md`.

## 0. Where we are (revised in place; last 2026-10-10 evening)

Direction, not a ledger. The dated chain runs and loop rounds, with their numbers, are evidence in
[loop-rounds](../research/agent-platform/2026-10-10/loop-rounds.md); the independent review of the day's landings is
[Astra's day audit](../research/agent-platform/2026-10-10/astra-day-audit.md), whose order this section follows.
Finished work leaves this section; history is in git.

**Where we are.** The loop runs end to end by hand: a DeepSeek-flash agent takes a task with a red proof green on its own
branch from the context Seon generates, its one end message is root's merge request, and root prepares, accepts, writes
back and commits (chain runs 4 and 5 reached HEAD; of eleven agent runs in rounds 1–4, five went green and two merged;
round 4 went 0 of 2). What keeps it from running alone: a landing proves paths, not the bytes it commits, and does not
recover from a partial install; the merge gate does not fence the target program; interpreted proofs are not yet
faithful; a schema change cannot merge. Root's decision after the audit: no new rounds and no tick or restart automation
until the landing is exact, the proof path honest and one data-first task has gone through.

| Essential | Status | Blocked by | Working it now |
|---|---|---|---|
| 1 An honest number in every reply | **Done.** FEEDBACK-LOOP stages 1–13 and 16 live; like-for-like cold/warm cost per [symbol scope] (b37a3ff72); the diff shows only changed definitions. The cost diff still lists unchanged interpreted definitions (task `seon.batch/an-unchanged-interpreted-definition-shows-no-cost-entry`, the `cost-diff` loop task). | — | nobody; stages 14, 15, 17 wait (§6) |
| 2 One task worked end to end through the REPL | **Done by hand** (chain run 5: 4 calls, no root rescue, landed 3c4f94227; round 1 merged a4b7e6261). Not yet exact or unattended: the landing commits paths, not proven bytes; a partial install does not recover; the target is not fenced (audit 1, 2). | items 1–3 | lane `schema-merge` |
| 3 Red proofs before any assignment | `seon.task/assign!` runs the proofs in the agent's world and refuses all-green (7ce008211); it still assigns on a proof-run error (audit 7). 80 of 674 tasks carry a tagged proof. | nothing | lane `audit-admission` (item 4) |
| 4 Default runs HEAD | Routine: `bin/seon start --head` per landing batch. Boot readiness 5.3 s on a quiet machine, 15–18 s under lanes' check JVMs. The lag workarounds are not yet deleted (item 12). | — | — |
| 5 Correctness and efficiency over size | Node cache by bytes: a new agent's first request 2.37 → 0.38 GB. Still: prepare 4–6 s / 1–3 GB per one-function merge; publication about 26 s per check; one end summary 184–311 ms / 218–286 MB warm; a `defn` reaching about 13 tests 3.4–3.9 s / 4.3–5.5 GB in the test step. | — | item 8, unassigned |

**Next, in dependency order.** Each item names its owner lane or "unassigned" and what it unblocks. An item starts
only when what it depends on has landed, except where marked "beside".

1. **A branch schema change merges** (audit 4). `seon.program/changed-identities` refuses every family but functions,
   tests and namespaces, so round 3's green schema change could not land; remove the allowlist under the existing
   stored-data validation and ordered norms (tasks `seon.cluster.source/a-branch-schema-blocks-diff-and-merge`,
   `seon.test/check-fails-on-agent-registered-schemas`). Lane `schema-merge`. Unblocks: 2, 7.
2. **The merge target fence** (audit 1). At the writer, acceptance refuses and prepares again when the target's program
   changed since the prepared run (first conservatively: any program change since the captured target basis; the merge
   spec §4 already calls for it). Lane `schema-merge`, after 1. Unblocks: 3.
3. **An exact, restart-safe landing** (audit 2). `seon.cluster.source/commit!` compares accepted candidate bytes, tree
   and parent, never paths alone; write-back and Git hold the affected checkout files under one owner for the whole
   sequence (`bin/lane`'s lock while both entrances exist); accepted inputs and completed effects stay on the merge
   task so a retry resumes from evidence instead of `commit-as-db` on a collected commit; the agent retires only after
   that evidence is safe. Lane `schema-merge`, after 2. Unblocks: the auto-landing dial (5), any new round (9), the tick (10).
4. **Admission and completion are positive observations** (audit 7, 8). `assign!` requires an observed failing proof
   and returns an infrastructure failure separately (the earlier "assign on proof-run error" acceptance is reversed);
   `seon.context/ended` keeps the small historical outcome after the agent's branch is retired and answers a typed
   unknown when evidence is gone. Lane `audit-admission`, beside 1–3. Unblocks: honest round grades (9), the tick (10).
5. **The task-end message is idempotent at the writer** (audit 6). One stable task-end occurrence and its delivery
   obligation are recorded in the end's transaction; the send derives from them, so a crash between send and close or a
   refused send neither duplicates nor loses root's message. With it lands ROOT-AGENT stage 2 (`end-task!` calls
   `land!`) with its dial **off** by default; the dial flips on when 2 and 3 have landed. Lane `root-stage2`, beside
   1–3. Unblocks: unattended landing.
6. **One honest interpreted proof path** (audit 3). A redefinition never reuses a stale red (lane `stale-reuse`, in
   flight); `use-fixtures` and the other top-level initialization run in the agent's SCI context (task
   `seon.sci.eval/an-interpreted-test-namespace-runs-without-its-fixtures`, unassigned); evidence reuse declares its
   evaluator, proven by two evaluator contexts with different setup that cannot share a green (unassigned); then the
   interpreted census classes touched by the day's fixes are rerun (the 205 of bff46841f is not a current count).
   Beside 1–5. Unblocks: trusting an agent's reached-red and its reaching tests; 7; 9.
7. **One data-first task through the whole loop** (audit 4). A real task that adds a named schema, its producer,
   consumer, proof and task description, worked by a model agent from generated context, merged, written back
   (task-file write-back, §8 Q2 (a), ROOT-AGENT stage 12) and committed through the same path. Unassigned; root picks
   the task once 1 lands. Unblocks: 9; the claim that the loop is data-first.
8. **Remove the repeated acquisition work** (audit 5). Reuse the acquired program-only base and the immutable namespace
   and schema derivations in `seon.cache`; measure the request with its workers, not the calling thread alone (tasks
   `seon.test/one-test-run-at-the-repl-allocates-over-a-gigabyte`,
   `seon.context/one-agents-current-state-costs-hundreds-of-megabytes`,
   `seon.cluster.source/accepting-a-one-function-merge-allocates-hundreds-of-megabytes`); per-file derivation inputs
   for publication (26 s per check, 3–5 h; `2026-10-10-boot-source.md`). Unassigned: performance work with the owner's
   files in the lane, never a specialist without them. Beside 1–7. Unblocks: platform proofs under 30 s; cheap rounds
   and a cheap tick.
9. **Loop round 5**: the refined `problems-prose` and `cost-diff` (its third failure, 12 errors) and the data-first task,
   graded against round 3's table. Unassigned; after 3, 4 and 6. Unblocks: 10.
10. **ROOT-AGENT stages 4–9** (grants, root tasks and the role section, `decline-merge!`, `launch!` and `tick!`, the
    restart rule), after correcting the card's two refuted claims: write-back conflicts do not disappear with Claude
    lanes (one checkout owner stays), and "a failure leaves nothing written" becomes per-effect durable outcomes. A
    restart must keep the output needed to diagnose its failure. Unassigned; after 9. Unblocks: unattended rounds; the
    daily spend report (stage 10) is independent and may go first.
11. **A small ready frontier, not top-30 triage** (ROOT-AGENT stages 11–13): the next few tasks get current red proofs
    and typed references; the 600-task backlog is never folded into a schedule. Unassigned; after 10.
12. **Smaller, beside the above, root chores never ahead of 1–7**: FEEDBACK-LOOP stages 14, 15, 17; the host-bound
    half of the REPL-only acceptance (`proven-file` unreached); A10 (a visited test namespace makes later requests 3×
    slower); `seon.db/pull-logical` about 3× main on agent branches; the cold-calls dial not reaching cells from the
    cluster configuration; deleting the lag workarounds (the write-back rebase's archive snapshot in
    `my.test/check-platform`, template-per-slot, "live after restart" proofs); archive sealing's real guarantee stated
    and `setWritable`'s result checked (audit 9); the two reds on clean HEAD classified
    (`seon.config-test/the-default-document-has-one-canonical-complete-location`,
    `seon.db-test/equal-declaration-content-shares-one-projection-across-values`); the leftover agent branches retired
    after a collection receipt and before/after bytes (audit 8). All unassigned.

**Standing rules** (the 2026-10-10 stall review and the audit's process corrections, folded):

- Every lane names the essential it serves; one lane always serves the goal (essential 2 or 3), never the instrument;
  the head of the list above is work in flight with a named lane, never a label. At most two or three implementation
  lanes, on disjoint files.
- Progress is credited at completed guarantees, never at intermediate steps: a landed primitive is "available", not a
  stage of autonomy; a Git commit of paths is not branch evidence; proof-only green is not done; compiled green is not
  interpreted green; a stored number proves only the scope its counter measured.
- No new loop round, tick or restart automation until items 3, 4 and 6 have landed.
- A finding is filed the same day with a red tagged proof (a row in an existing table where one fits) or it is
  invisible to the schedule; an instrument finding is filed and sequenced unless it surfaces false data, which blocks
  the goal and is fixed. A friction met twice (source order, the `.cljc` fallback, a census reading files, shared-file
  interference) changes an entrance rule or an existing proof in the same beat; the next model never rediscovers it.
- Errors are judged by their producer, not their submitter: the same signature across agents is a Seon defect; every
  checkable AGENTS.md law has a test.
- Root chores run beside lanes, never ahead of goal work; after a landing root launches the next item in the same turn.
  Blocked owner work goes to its owner directly, or the whole slice transfers; a specialist with no editable slice is a
  research handoff and is priced as one.
- Every chain run keeps its branch and ends with reflection: root reads the record first, then asks the snapshot.
- This section is one dependency list; dated rounds and measurements go to evidence pages, never here. A living
  document's claim made stale by a landing (a skill contradicting the gate) is corrected in that landing.
- The REPL-only acceptance's host-bound half (31b442840 fell back to `--test`; `proven-file` unreached) still gates new
  platform machinery.
- Lanes delete with `rm`, never `git rm`, and leave nothing staged; a whole-namespace `--test` on a namespace with
  declared-long tests is refused (name the vars); a dependency bump lands last in a batch, then one restart. A closing
  lane leaves one committed evidence link and one disposition per scratch root (promoted, preserved, or removed after
  inspecting the live holder); `tmp/` is never swept by age or name. A claim is released when its lane closes; the
  ledger's claim count is not the running-lane count.

## 1. The goal

Software that grows by accretion: open data under named schemas, stable names, every call checked and measured, built
by many agents each in its own branch and SCI world. Work is tasks that chain (define → research → specify → prove →
implement), each step an agent whose context Seon generates. A core of about 10,000 lines where ordinary work is
sub-second.

## 2. The essentials

Five things matter now; everything else waits on them.

1. **An honest number in every reply.** A redefinition reports 28 MB and costs 2.1 GB and 2.4–3.2 CPU-seconds
   (measurement.md, one-system.md, reproduced by astra-one-system). We cannot judge any change, budget or fix until the
   reply tells the truth. This is the first designed task (§4).
2. **One task worked end to end by hand, through the REPL.** Both audits said the same thing: run the acceptance before
   building more. The chain (§3) is run by Claude lanes playing each role, on default's REPL, for the honest-measurement
   task. Every friction met becomes the next task. Nothing else platform-shaped lands first.
3. **Red proofs before any assignment.** 44 of 604 tasks had a tagged proof (astra audit); 13 of 15 fan-out fixes
   needed root rescue; the fan-out 9 bug passed its one reaching test. A task is assignable when it has an `are` table
   holding the reported input (and, for cost tasks, one `is` on a count), red on default. Ten such tasks, not 590.
4. **Default runs HEAD.** Most of wave A's machinery (file rebase, published-archive snapshots, "live after restart",
   templates per slot) exists because default lags HEAD (opus review §3.1). Restart onto HEAD after each landing batch,
   then delete the workarounds.
5. **Correctness and efficiency over size, for now** (owner, 2026-10-10). Temporary growth of the codebase is
   acceptable; Seon agents trim it later, using the cost data the feedback loop now stores. Every change is judged
   by whether it is correct and by its measured cost.

## 3. The method: the chain, run by hand

A task moves through five agents, each with its own context, each handing the next a value in the database:

| Stage | Produces | Lane for the first run | Later |
|---|---|---|---|
| 1 Define | `:seon.task/intent` in plain language, must-reads, the reported input | owner + root | owner, root |
| 2 Research | what exists to reuse or change: producers and consumers of each key (`:seon.fn.arity/input-refs`, `output-refs`), the dependency seam, the simplest alternative | Claude (Opus) | Claude |
| 3 Specify | Malli schemas in `resources/seon/schemas/<ns>.edn` and the function contracts, by name, on both ends | Claude (Sonnet) | Claude |
| 4 Prove | deftests in the subject's test namespace, tagged `:seon/tasks`, red: an `are` table with the reported input, one `is` on a count for a cost task, a seeded property only where a one-line law exists | Claude (Sonnet) | Claude |
| 5 Implement | bodies until the proofs are green; REPL-first on a branch, `my.program/write-branch!`, `bin/lane commit --branch-evidence` (host-bound: `--test`) | Claude (Opus, platform) | DeepSeek-flash (ordinary), Opus (platform) |
| Review and merge | dual review (fresh Opus, Astra), root merges | root | root |

Rules of the run: each stage's output is checked at the REPL before the next starts (stage 3 compiles its schemas
against real values; stage 4 confirms red on default); a stage that finds the design wrong stops and reports with
options; every friction (a tool missing, a read over a second, a refusal with no fix text) is filed as a task with the
exact form and reply. The first run is a measurement of the chain itself: how many requests each stage took and where
the context was missing. That measurement, not a card, decides what gets built next.

## 4. The first task: honest measurement (done)

Worked as the FEEDBACK-LOOP card (`schedule/cards/FEEDBACK-LOOP.md`, stages 1–13 and 16 landed); its design, proofs and
measurements are there and in git. The step's allocation it predicted was the node cache, since bounded by bytes.

## 5. Next steps, in order

Superseded 2026-10-10: the order is §0's dependency list. Of the five steps written here that morning, the second
chain run and the proofs at assignment are done (chain runs 3–5; `seon.task/assign!` runs the proofs, 7ce008211); the
rest live in §0 as items 12 (default runs HEAD and the lag workarounds; A10) and 7 (the task entity's write-back).

## 6. Deliberately deferred

- Child tasks, task graphs, planning and fan-out (`my.plan`, `seon.plan` are in git at cea904e92 for later).
- COST-REG slices 2–3 (baselines at assignment, 32 runs, factor/floor reds, `:seon.task/cost-accepted`): interpreted
  bodies cost 280× their compiled self (G4), so every correct agent body is red against main; counts in proofs (§3
  stage 4) and the honest window replace it.
- Growth tests (n…8n exponents), guards, guard admission fixes, the structural schema index, the Malli `mg/check` seed
  change: 9% + 1% of defects, three blockers, confusing to the owner. Properties stay optional where a one-line law exists.
- Auto-generated checks on `defn`, per-function meters and real-input capture in the armed wrapper: not needed by the
  first task; each is machinery with no friction yet behind it. Revisit after chain run two.
- A storage meter beyond the tx report's datom count (index files and bytes per commit, G3): wanted, not yet needed.
- Embeddings and semantic search (§8 Q4): after lexical search over indexed docs has been used and found wanting.
- The Datahike fork rebase onto upstream (165 vs 171 commits; `listen-commits!`, metrics, binding conveyance): a root
  batch after step 1 lands, modelled on upstream's event shape so the rebase is a deletion.
- A stateful lifecycle test suite (23% of defects): its own owner and research card, not per task.
- A Flow-hosted Datahike writer: refused (a second writer, loses group commit, cljc).
- The positive-wording pass over AGENTS.md's remaining negatives: root, in small commits, never a lane's task.

## 7. Decisions made

- 2026-10-10 Seon grows by accretion; the four properties and the design order in AGENTS.md are the laws.
- 2026-10-10 Work is a chain of five separate agents; run by hand first; the first task is honest measurement; nail a
  single task before child tasks.
- 2026-10-10 Roles: Claude designs; DeepSeek-flash implements non-platform; Opus implements platform; root reviews and merges.
- 2026-10-10 Datahike's writer stays as it is (go-loops, group commit); Seon does not batch; `seon.batch` is a misleading
  name for the eval-request fold (rename belongs to its owner).
- 2026-10-10 Flow procs run on platform threads, one per task; accounting closes at `seon.flow/var-process`; the reply
  is published after accounting; writer cost per transaction via `:write-fn-map`; node cache bounded by bytes.
- 2026-10-10 Tasks are entities upserted as the chain refines them, with history; author little, derive the rest.
- 2026-10-10 Task names are fully namespaced symbols; docs live at the symbol level, linked in the database.
- 2026-10-10 The feedback loop (honest step cost, cost entities, live diff to main, schema cost) is designed in
  [FEEDBACK-LOOP](schedule/cards/FEEDBACK-LOOP.md) as 17 stages of about 15 minutes each; it replaces §4 and §5
  steps 1–2 as the work order. The loop works crudely after stages 1, 2, 4 and 5.
- 2026-10-10 Stored data is described in Datahike's terms: entities, attributes, values, datoms (AGENTS.md vocabulary).
  Cost is a cost entity per function, identified by its symbol, with values replaced in place and no history.
- 2026-10-10 Numbers are live: each reply shows the request's own measured values against main's stored values (which
  change only when merges land). A threshold of 1.41× only gates when a branch rewrites its stored value.
- 2026-10-10 Schema cost is measured once per schema digest at contract-writing time (generate samples, validate,
  record), flagged in that reply when over budget, and re-measured only when the schema changes.
- 2026-10-10 Land the Datahike fork's commit-cost change now (FEEDBACK-LOOP stage 11).
- 2026-10-10 Merge bases are found by the fork point's `t` and `as-of`, never by walking commits the collection may
  have removed (task `seon.cluster.source/merge-base-survives-collection`). There is no live data to migrate:
  default is nuked and rebuilt after the fix.
- 2026-10-10 Focus on correctness and efficiency; temporary codebase growth is acceptable, and Seon agents trim the
  codebase later. Next, in order: the node-cache cut (efficiency, measured by the loop), the REPL-only acceptance
  rerun, a second chain run with a DeepSeek-flash implementer, then backlog triage. FEEDBACK-LOOP stages 14, 15
  and 17 wait. At most two or three implementation lanes at once.
- 2026-10-10 Only reliable data is surfaced (owner: "false regressions are the bug"). A number shown or stored as a
  baseline is like-for-like: the same scope (interpreted or compiled), warm, and with enough calls. Otherwise it is
  an explicit unknown, never a misleading ratio. Landed b37a3ff72 and confirmed live: one cost entity per [symbol, scope],
  each with a cold sample (a definition's first call in the process) and a warm sample. Every comparison is cold to
  cold or warm to warm, in the same scope, with enough calls; otherwise it is an explicit unknown. Health reads warm;
  cold cost is its own query. The 81 ms / 8.6k-call reading was root's own context (8,763 calls); a fresh agent's
  warm `(+ 1 2)` is 72–74 ms / 6,668 calls, so no counting overhead. Open: the cold-calls dial does not yet reach cells from the cluster
  configuration (task filed).
- 2026-10-10 The whole loop is run by Seon agents (owner): a green merge is accepted automatically (proofs and every
  reaching test green); a DeepSeek root agent handles root's requests, platform work included, through root-specific
  tasks and a root system context that grant it more freedom; DeepSeek agents triage the backlog; paid rounds have
  no cap and report their tokens and cost daily. Designed in card ROOT-AGENT (lane fable-root-agent).
- 2026-10-10 (root, owner asleep) Test evidence readers accept the agent's own evaluator plus compiled: compiled is
  the reference semantics and host-bound tasks complete on compiled runs; an interpreted green never counts as root's
  compiled proof. Fixtures are data: the namespace entity holds its fixture symbols by kind (`:each`, `:once`), written
  by the REPL's `use-fixtures` and by file indexing; no other unnamed top-level form is kept, so REPL expressions stay
  ephemeral.
- 2026-10-10 Owner: when the owner is not actively replying, root decides with the recommended option, proceeds and
  reports the decision; it never blocks lanes on a question.
- 2026-10-09 Freeze new platform machinery until the REPL-only acceptance passes (one non-host-bound and one host-bound fix).
- 2026-10-09 Auto-restart default onto HEAD per landing batch; then delete the lag workarounds.
- 2026-10-09 Proof gates scheduling; the rest is an unproven backlog. Stay on deepseek-flash. Commits 1–3 lines; docs
  only for evidence that would otherwise be lost.
- 2026-10-09 Budgets with four meters (calls, CPU, allocation, storage) estimated by the designer; the agent pushes back
  with data; root decides; completion is automatic when proofs are green.
- 2026-10-10 Doc sections are entities identified by path#anchor, with parsed references as facts; files are write-back.
- 2026-10-10 The database is the source of truth: Seon persists to disk and can start from disk, and the conversions
  are lossless, proven by a round-trip test (tasks, code, schemas, docs).
- 2026-10-10 Search: the fork's Lucene secondary index (`:scriptum`) now; EmbeddingGemma vectors (`:proximum`) only
  after a chain run shows a lexical miss.
- 2026-10-10 Latest performance numbers are stored per function, test and schema validator, as rolling values
  updated in place with no history. Every reply carries a compact cost diff to main, always on; live feedback is
  automated wherever it is cheap.
- 2026-10-10 Flow procs move to platform threads (tens of KB each, a few per agent). Moving Datahike's writer into
  Flow is deferred; it is correct as it is.
- 2026-10-08 A question task declares its answer's Malli schema; the answer is ordinary Datahike data.

## 8. Open questions for the owner

Each with options and a recommendation; findings are stated as facts checked today.

**Q1. Where do cross-cutting docs live, so agents grepping code see them?** *Decided 2026-10-10: (a), doc sections as entities.*
Facts: docstrings are already database facts with full history (`:seon.fn/doc`, `:seon.ns/doc`; 4,234 functions have
doc history; `seon.sci.arm/arm!`'s docstring has 6 versions since 2026-09-28). `seon.ref` already parses every Markdown
text once (commonmark, cached by digest) into headings, code spans and links, resolves them, and reports unresolved
ones as problems; 524 task entries reference `.md` paths. The web view has no Markdown renderer yet (nextjournal/markdown
is in deps.edn, unused). Namespace-docstring expansion failed before because prose in a `ns` form has no identity of its
own, cannot be referenced by section and is re-indexed with the namespace.
Options: (a) **doc sections as entities**: each Markdown heading section is an entity identified by its path and anchor
(the identity `seon.ref` already derives), with its text (inline or blob), its parsed references as facts
(`:seon.doc/references` → function symbols, schema keys, tasks) and the inverse read "the sections that reference
`seon.fn/gate-set`" one indexed query; files stay the write-back, as for functions; `my.program/source` and the task
block show the referencing sections. (b) Docs as symbol-named entities written at the REPL (`my.doc/define!`), files
generated. (c) Expand docstrings.
Recommendation: (a) now, (b) as its REPL face later; never (c). It is the linkage, not the storage, that stops drift:
a function's context lists the sections that cite it, and a section citing a renamed symbol is a problem on that
section at ingestion. Honest answer on files: files remain better for humans, git review and editors, and the harness
greps them; the database index is what the agent needs. Markdown inside doc entities is wanted and cheap to render
(nextjournal/markdown → hiccup through the one printer's HTML face). What this never builds: a second resolver, a docs
folder scanner, prose in `ns` forms.

**Q2. What does a task author, and what is derived?** *Decided 2026-10-10: the database is the source of truth; files are lossless write-back and boot input (see §7).*
Recommendation (root's proposal, with one change): authored = `:seon.task/name`, `:seon.task/intent` (one Markdown
paragraph with typed references), `:seon.task/must-read`, later `:seon.task/depends-on`; derived = proofs (deftests
tagged with the task), interface (functions tagged), schemas (from those contracts), status (proofs green). No budget
key: a budget is one `is` in a proof (fable-simplify R3), so it lives in git beside the test. Retire `:seon.task/kind`.
Upserts come free with history (Q3). The chain's stages upsert the same entity: stage 2 adds must-reads, stage 3 the
schema keys, stage 4 the proofs by tagging. The blocker to remove first: task files are not writable from the REPL
(`seon.task/create!` writes the database; write-back of `resources/seon/tasks/<ns>.edn` does not exist), so stage 1–4
agents cannot record their output. Options: (a) write-back for task files through `my.program/write-branch!`'s path;
(b) the database is the task's home and the EDN file is generated at commit; (c) keep hand-edited files.
Recommendation: (a); it is the same mechanism functions use.

**Q3. Is full history really kept?** *Decided 2026-10-10: yes; keep the 1 h window. The merge base, the one consumer that loaded old commits, moves to `t` (see §7).*
Verified at the REPL (JVM mode, default): `d/history` sees 819 transactions spanning 265 hours (2026-09-28 to today);
`d/as-of` at 2026-10-03 answers 5,610 functions against 5,874 now; the last collection (2026-10-09, 4.3 GB reclaimed,
792,620 objects swept) removed none of it. Why: with `:keep-history? true` retracted datoms live in the temporal
indexes of the head commit, and `datahike.gc/reachable-in-branch` marks the head's temporal roots whatever
`remove-before` is; the one-hour window prunes only commit records and index nodes no head reaches. What is lost after
one hour: loading an old snapshot by commit id (`d/commit-as-db`), branching from an old commit, and per-commit
provenance beyond `:db/txInstant`. `as-of`, `since` and `history` are unaffected.
Options: (a) keep the 1 h window; (b) lengthen it for provenance. Recommendation: (a); nothing we do needs old
snapshots. Record this in the datahike skill (verify, then cite `datahike.gc/reachable-in-branch`).

**Q4. Search: lexical, semantic or both?** *Decided 2026-10-10: (a) now, (b) later.*
Facts: our Datahike fork has secondary indexes declared in schema and maintained in-transaction
(`datahike.index.secondary`): `:scriptum` is Lucene full text with branch-aware copy-on-write
(`datahike.index.secondary.scriptum`), `:proximum` is an HNSW vector index; `:db.secondary/only` keeps a large value in
the index and out of the datoms. Lucene 10.5 is already in Seon's deps.edn and nothing uses it. EmbeddingGemma 2
(blog and model card, 2026): 740M parameters total, 270M for text, about 190 MB active RAM quantized, 768-d vectors
truncatable to 128, 8k context, Apache 2.0, MTEB-Code 78.7; runs on CPU through llama.cpp, ONNX or sentence-transformers.
Honest answer: embeddings are not simply better. Agents searching code mostly know a name or a fragment, where lexical
search is exact and explains its hits; semantic search wins for "which function does X" discovery and loses on exact
symbols, numbers and keywords; hybrids beat either in published retrieval benchmarks, at twice the machinery. Costs on a
16 GB machine: Lucene over ~6k docstrings and ~500 doc sections is a few MB and sub-millisecond; embeddings are
200–600 MB resident model, 5–20 ms per text on CPU, 18 MB of vectors at 768 d (3 MB at 128 d), re-embedded per changed
entity keyed by content digest (fits the cache model), and a JVM integration (ONNX Runtime Java in-process, or a
llama.cpp sidecar) that every user must run.
Options: (a) Lucene secondary index over `:seon.fn/doc`, `:seon.ns/doc`, task intents and doc sections, with blobbed
text indexed at `seon.blob/inline-or-stage` time or declared `:db.secondary/only`; (b) embeddings in `:proximum` with a
local EmbeddingGemma; (c) both, hybrid.
Recommendation: (a) now, one schema declaration and one `my.program/search` face; (b) added as a second secondary index
only after a chain run shows a discovery the lexical search missed. What this never builds: a Seon search module or a
sidecar store for vectors.

**Q5. Accept the one-system design as decided?** *Decided 2026-10-10: (a); Datahike writer into Flow deferred.* The writer stays as Datahike's go-loops observed through
`:write-fn-map`; all procs on platform threads; the fork's commit runs synchronously on one platform thread for exact
commit cost; GC and JIT shown as a labelled residual, never apportioned. Options: (a) accept all four; (b) only the
turn proc on platform (lift `var-process`'s `:mixed` refusal); (c) keep commit as wall time, labelled shared.
Recommendation: (a). It is what §4 is written against; (b) saves two threads per agent for a second rule; (c) leaves
the konserve commit writes (53 MB sampled on one redefinition) unattributed.

**Q6. Rule on the test design.** *Decided 2026-10-10: proofs as written (an `are` table with the reported input plus counts), and in addition latest numbers stored and a cost diff to main in every reply (§5 step 2).* From fable-simplify: cut COST-REG slices 2–3 and the acceptance key; a stored member cost
is shown, never red by itself; properties optional (one seeded deftest where a one-line law exists); guards dropped;
the growth test retired; the proof is an `are` table holding the reported input plus one `is` on a count. Two places I
disagree with the current rulings and ask for a change: prove ten tasks first, not thirty (ten is one afternoon and
enough for two fan-outs); and defer auto-instrumentation and real-input capture until a chain run asks for them.
Options: (a) accept as written; (b) keep COST-REG slice 2 as a shown comparison only; (c) keep growth tests for
designer-declared cost tasks. Recommendation: (a), with (b) as the compromise if a member's stored cost must be visible
beside main's (it already is, slice 1).
