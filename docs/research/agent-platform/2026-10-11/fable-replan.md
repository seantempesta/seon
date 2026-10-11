---
type: research
title: "Fable replan, 2026-10-11: what we tried, what we learned, the simpler ways"
date: 2026-10-11
status: pass 1 of 2; questions for the owner at the end
---

# Fable replan, 2026-10-11

Written by lane `fable-replan-2` for the owner and root. Read end to end: AGENTS.md (fb98f1752 and later), the plan
(38fce69e3 and its evening revisions), root's handoff and the collection evidence (6f68122c9), lane land-r8's finding
(be337100e), the TASK-CHAIN card (a8bce9d10), Astra's day audit and loop-rounds (2026-10-10), the ROOT-AGENT and
DYNAMIC-CONTEXT cards, and the 199 commits since 2026-10-10 00:00. Two read-only sweeps were delegated: the diffs of the
nineteen fence commits (§4) and the holders of database values across transactions (§5.6). Live facts were read on default
(archive c1230efc3, basis 536871981) in JVM mode as root, queries only.

This is pass 1: the analysis, the simpler alternatives with a recommendation each, a **draft** of the plan's new §0 order,
and the questions the owner answers in writing in this file (§8). Pass 2 rewrites `docs/seon/plan.md` from the answers.
`plan.md` is unchanged by this pass.

## 1. The goal and the distance to it

**The goal.** A self-improvement loop run by Seon agents: a task with a red proof is worked by a model agent from the
context Seon generates, lands on main, disk and git when its proofs and reaching tests are green, and the next round
starts from the improved program. The owner's statement of 2026-10-11 sharpens it (card TASK-CHAIN): tasks are mined from
database queries, each task moves through a chain of separate agents, and each agent edits the task so the next agent's
context is a little better. Software grows by accretion and gets smaller, faster and more certain, measured round over
round.

**Where we are, in facts.**

| Link of the loop | State on 2026-10-11 |
|---|---|
| A model agent turns a designed task red → green from generated context | **Works** for small ordinary functions: chain runs 3, 4, 5; `r1-path-form`, `r1-search-limit`, `r3-ref-section`, `r3-problems-prose`; 1–12 calls each. Fails on the loop's own instrument tasks: `cost-diff` failed four times (rounds 2–5), `ref-section` three of four, `problems-prose` five of six (round 8 green, landing refused). |
| The end is one message to root that is the merge request | **Works** (a9feb9ba4, f540268a1). |
| Root lands by hand | **Works**: d3de85d72, 3c4f94227, a4b7e6261 (three merges reached HEAD through prepare, accept, write-back, commit). |
| The green end lands itself (`seon.cluster.source/land!` from `seon.turn/end-task!`, dial `:seon.config.root/land? true` since a9a0c23d1) | **Ran twice, landed nothing.** Round 5: 0 of 3 green. Round 8 (`r8-problems-prose`, merge task `57ad6cd33297`) was refused 10 s after its end, `:seon.source/red-test-error`, run `864c8ff86580`: two reaching tests red; lane land-r8 showed both are red the same way when interpreted on unchanged main (run `21ae1d38003e`) while the gate run was labelled `:compiled`, so the change was blamed (be337100e). Rounds 6–7 and root's hand landings of the same task each peeled a real defect (ab18c3ffc, c9d745e06, 871e6a979/4e1f4717e, e71e60a47, c1230efc3). |
| Rounds measured round over round | A table exists (`seon.competency/table`); no round since round 3 was graded against the previous one, and "landed" is not a column. |
| A chain of separate agents per task | **Never run.** Claude lanes defined, specified and proved; DeepSeek only implemented. An agent cannot edit its task entity at its REPL (`my.task` has `open`, `claim!`, `plan!`, `step!`, `done!`; no `refine!`), so the handoff medium is missing. |
| Default stays up unattended | **Broken**: the retention collection of 03:02Z reclaimed nodes a held database value still referenced; every `eval_clj` failed until a restart (§5.6). Lane `collection-holds`. |

Live census (basis 536871981): 722 tasks, 119 with a tagged proof; 9 merge tasks on main, 3 accepted, 6 undecided (3 of
them with a stored refusal); 57 branches in the roster; 114 agent entities, 56 holding a live branch. Health `:faulted`:
12 open faults, 112 errored receipts, 45 unknown tests, 9 agent-error signatures (the top one, 58 occurrences of "Unable
to resolve symbol" across agents, lists the names agents guess: `seon.schema/registry`, `seon.task/current`,
`my.program/overrides`, `seon.print/inspect`, `my.cluster.source/write`). Two of my census forms, five and ten reads
each, allocated 646 and 659 MB (`:seon.cost/alloc-mb`), the acquisition cost the filed tasks already name.

**The distance.** One link is missing: a change landing without a hand. Everything the link needs exists and has been
proven in fixtures; it has not happened on real data once. Both real attempts failed on the same cause, the
two-evaluator split (§4.2), not on landing.

## 2. What we tried and what we learned

### 2.1 The chain by hand (kept, and it paid)

Chain runs 2–5 (15:50–18:24) were run by hand as the plan said. Each cost minutes and found two to five frictions
(`loop-rounds.md`): whole source, tool markup as prose, sealed archives, the namespace line, the end message, the `.cljc`
gate fallback that ran no tests on two landings. This was the cheapest learning of the weekend. **Keep this shape for
every new mechanism**: the first automatic landing, the first chain run, the first tick all get a hand-run first.

### 2.2 The landing path (built in one night, before it had ever landed)

Between 20:40 and 00:44 about 60 commits built `seon.cluster.source/land!` (fb1fb9e60), `commit!` (0e502a59e),
`seon.turn/land-end!` behind the dial (2d19dafb0, on at a9a0c23d1), and twelve guarantees (§4). `src/seon/cluster/source.clj`
grew 980 lines to 2,136 (89 `defn`s); `src/seon/turn.clj` 441 lines to 5,888 (199 `defn`s).

What it bought: a landing that is one call, records each effect on the merge task, and refuses rather than commits
anything unproven. `land!`'s shape (prepare, accept, write-and-commit) is right.

What it cost: a night, and no landing. Two avoidable reasons:

1. **The guarantees were built from the audit's findings, not from observed failures.** Of the nineteen checks,
   locks and refusal classes added this weekend, twelve guard a failure that has not happened on real data (§4.1).
   The law is the other order: simple and correct first, then harden from the regressions found.
2. **The probe was the hardest possible landing.** `problems-prose` is a change in `seon.problems`, a platform namespace
   whose change reaches 24 tests, several interpreted-only red or never run on main. Every defect it found was in the
   interpreted proof path. Chain run 5's class of task (one ordinary function, a handful of reaching tests, all green
   interpreted on main) would have landed the first time the dial was on. The probe should have been chosen by reach.

### 2.3 Interpreted versus compiled (necessary as isolation; parity is the wrong target)

Since e361536a9 a non-root agent's tests run interpreted from their rows, never loaded into the shared JVM. The isolation
is required: many agents, one JVM, compiled Vars direct-link so an agent's override is invisible to compiled callers, and
uncommitted source never loads into default.

Its costs this weekend: 205 interpreted-only reds in 846 tests (bff46841f); 95 cleared, 110 remain (3ec015a72); the fuller
census ran 1,500 of 2,781 tests interpreted, 1,026 green and 474 red (2215f18f8). About ten interpreter fixes, two SCI
fork bumps; `seon.sci.eval` grew 597 lines. Per test run 0.5–1.8 s and 0.5–4.6 GB; `land!` 9–19 s and 5–11 GB;
`seon.sci.eval/install-row!` warm self 7.38× main; `seon.test/arm-first-loads!` 14.8× (172d19408). And the two real
automatic landings were refused because an interpreted red was compared with a compiled baseline (§4.2).

What we learned: the 50 R-class tests (`with-redefs`, `alter-var-root`, `intern`) break a law and will never interpret
faithfully; the D class needs `dev/` namespaces a cluster never loads; `proxy`, `&env` macros and `await-event!` are host
semantics. Chasing parity for these is a square peg. The gate on main is the reference semantics (owner ruling
2026-10-10); the agent's interpreted run is its own fast feedback and must never be the gate's verdict.

### 2.4 The cost instrument (worth having; its presentation ate the rounds)

FEEDBACK-LOOP stages 1–13 and 16 landed on the morning of 2026-10-10. What it bought: the byte-bounded node cache (2.37 →
0.38 GB for a new agent's first request, 12473ca94), writer and commit cost in the reply, like-for-like cold and warm cost
per [symbol scope] (b37a3ff72), and every regression named in this document. What it costs: `seon.profile` 717 lines,
`seon.batch` 982; the settle stage a constant 25–30 ms per request (within the 100 ms law); and the diff's own defects
became the loop task `cost-diff`, which failed four rounds in a row with 12–14 agent errors each. Four of the fourteen
agent runs in rounds 2–5 were spent on the instrument's presentation. That is Sonnet lane work, not round work.

### 2.5 Task and proof quality (the lesson every round repeated)

Green: `slug`, `edn-symbol-text?`, `match-row`, `path-form`, `search` limit, `ref-section` (third try), `problems-prose`
(sixth). All small owner functions with an `are` table holding the reported input. Red for rounds: `cost-diff` (platform
presentation, four proofs), `ref-section` until its intent named both changes, `problems-prose` until its proof read the
program instead of files and, later, until the docstring change also contracted a function. Root's assessment holds:
nearly every failure traced to the proof or the task, not the implementer. With auto-merge on green, a wrong proof lands
wrong code or blocks right code; a proof-check stage (TASK-CHAIN stage 4) is the highest-leverage addition.

### 2.6 Process

- Four code lanes at once on disjoint files; collisions came from task files and test files (handoff).
- Root misread landing state four times (the fourth: round 8's refusal was recorded and shown by `seon.context/ended`
  within 10 s; root read it as "outcome unread"). The CronCreate audit never fired. 4,491 lines of docs in one day.
- Standing rule "no new round until items 3, 4 and 6 land" (38fce69e3, 20:48) was honored in the letter (items 1–5
  landed by 22:34, rounds 5–8 ran from 22:48) and missed in substance: it named prerequisites, not which tasks a round
  may use, and item 6 (the interpreted proof path) was the one that mattered.
- 114 agents and 56 live branches accumulated; retirement when a merge request closes is the law and is not done.

## 3. The simpler ways, mechanism by mechanism

### 3.1 The first automatic landing: choose the probe by reach

Nothing to build. Pick one task of chain run 5's class (one ordinary function in a `my.*` or small owner namespace, `are`
table red on main, every reaching test green interpreted on main at its current reach, no schema or test-file change),
launch one agent, let the end land it. A refusal is the next day's first defect for an Opus lane; a landing is followed by
two more of the same class before any other kind. **Recommendation: item 1 of the plan.** `problems-prose`, `cost-diff` and
`ref-section` are platform tasks for Claude lanes, never landing probes.

### 3.2 The landing guarantees: remove the root causes, then delete the fences

Revised after the fence review (§4). Earlier draft said "keep the fence, exact bytes and the lock"; the owner's concern
is right and §4.3 names the root fixes that make them unnecessary. **Recommendation: freeze the landing path now (no new
guarantee without an observed failure on real data); land the three root fixes of §4.3 in order; delete each fence in the
commit that removes its cause.**

### 3.3 Interpreted fidelity: one gate evaluator; host-bound tests are data

§4.2 and Q3. The gate (prepare on a merge branch, root) runs reached tests compiled, from a compiled load of the candidate,
and records `:compiled` truthfully; the agent's interpreted run on its own branch is feedback, recorded `:interpreted`, and
never enters a verdict. Tests SCI cannot run (`proxy`, `&env`, `await-event!`, `dev/` requires) are declared host-bound as
data, by the classification functions already carry (`:seon.fn/host-bound?`), and run only at the gate. The 50 R-class
tests are deleted or rewritten under their existing proof `seon.test-census-test/no-test-redefines-a-var`. The census ends
when every ordinary test interprets green or is declared host-bound. **Recommendation: this replaces the parity target.**

### 3.4 The cost instrument: keep the numbers, take the diff off the loop's plate

No code change; a scheduling rule: the diff's defects are Sonnet lane work; `cost-diff` retires from the round task set.
Q1 asks whether ordinary agents should see the diff at all.

### 3.5 Agent context: the names agents guess are the next help line

DYNAMIC-CONTEXT stages 1–3 landed; stages 4–7 (shown declarations, working set, history by reference, reflection) are
unbuilt. The top live agent error is 58 guessed names. Simpler than the working set: the help or block lists the `my.*`
operations once (about 1.5k tokens), proven by the agent-error signature count per round. TASK-CHAIN's stable-prefix
ordering of `seon.context/current-state` (laws and help, then stage instructions, then the task, then the agent's own
history) is the context work that matters next. **Recommendation: the help line and the stable ordering first; the working
set after a round shows prompt growth caps agents; reflection stays by hand.**

### 3.6 ROOT-AGENT stages 4–13: defer all but the launch

`seon.root/launch!` (three existing calls) and a round table with a "landed" column are what unattended runs need; the
tick is one schedule entry after three landed runs. Grants, root tasks, `decline-merge!`, triage and the spend report wait
for a judgment duty to have occurred three times. **Recommendation: `launch!` and the table now; the rest deferred.** The
card's two refuted claims are corrected when it is next touched.

### 3.7 TASK-CHAIN: the general mechanism, built smallest first

The card (a8bce9d10) is the owner's direction and the right generality: a task value, stage types as data, one context
function, so pipeline variations are data and are compared, not coded. What it changes: **rounds become runs of the
chain**, graded per stage (calls, errors by signature, send-backs, landed) and per pipeline variation. Its first build item,
`my.task/refine!`, is the missing handoff medium and is independent of landing; the transition and stage entities follow;
the first chain experiment (one task, five separate DeepSeek agents) waits for the first automatic landing. The owner's
§3b (b643f8b5f) makes it one system end to end: a stage advances when its **stage proofs** are green, the same rule as a
task completing; stage proofs are ordinary deftests owned by the pipeline, one per stage type, run against the task
entity; every stage records an explicit decision component (`:seon.task.stage/name`, `/decision` such as `:added
:changed :removed :unchanged`, `/reason`, `/keys`), so "nothing to do" is `:unchanged` with a reason, never an absence.
This replaces the card's earlier done-check functions and answers the first point below. Two points for its review: (1) Prove's stage proof ("red on main for the stated reason") and the Check-proofs stage then differ only
in who runs it, the pipeline's deftest or a short model call, so the experiment measures directly whether the model call
pays; (2) `:seon.task/instructions` as a rendered field on the task is a stored copy of a template, against "derive,
never store"; render it from the stage type at `current-state` time instead, which also keeps the stable prefix stable.

### 3.8 Branches and the collection

Fix the held value (§5.6, item 0), then the chore the law names: delete the branch of every agent whose merge request is
closed or whose task ended, with a receipt and before/after bytes. **Recommendation: a root chore the day item 0 lands;
`my.agent/retire-branch!` exists.**

### 3.9 Lanes and documents

Two code lanes overnight, one always the probe (launch, read the outcome, file the defect with a red proof), the other
fixing the filed defect; one dated evidence page per day plus the plan; cards revised in place. **Recommendation: standing
rules in §0.**

### 3.10 Deferred, and stays deferred

Publication at 26 s per check and boot's whole-program derivation (owner: slots go to the loop; Q10); COST-REG slices 2–3;
growth tests; embeddings; the Datahike fork rebase. None blocks the first landing.

## 4. The fences: what each prevents, and the root fix that makes it unnecessary

The owner's concern, verbatim: "fences tend to be patching an issue that should be root solved and they add overhead and
complexity. I want to avoid those things." Nineteen commits were read diff by diff (sweep, read-only). "Observed" means
the commit cites a run, agent or incident on real data; "hypothetical" means an audit finding or a constructed scenario
with a new red test as its only evidence.

### 4.1 The table

| Commit | What it adds (symbol) | Failure prevented | Evidence | Root cause | Over-claim in the message |
|---|---|---|---|---|---|
| 5261c17eb target fence | `seon.cluster.source/target-program-held-tx` in the accepting transaction; +42 src | f tested against g₀, lands beside g₁ | hypothetical (audit 1) | main moves between prepare and accept; the writer is not the arbiter of the whole tested program | namespace-row exemption narrower than stated |
| 14b8ea164 exact bytes | `blob-digest`, pre- and post-commit checks in `commit-locked`; +45 | a lane edit rides into the landing commit | hypothetical (audit 2a) | landing writes into the shared working tree | post-commit refusal leaves HEAD advanced ("committing nothing" is false there) |
| 17138ccad one checkout owner | `write-and-commit!` under `.git/lane-commit.lock`; +47 | two landings read old bytes, replace each other | hypothetical (audit 2b) | shared working tree | excludes only lock-takers; a bare edit is detected by 14b8ea164, not prevented |
| 860220add resumable record | `merge-task-accepted-tx`, per-file `installed-tx`, `recorded-landing`; +208 | JVM dies after merge, before files | hypothetical (audit 2c) | acceptance and effects spread over transactions with no durable record | "never prepares again": record read pre-lock, two first landings both prepare |
| 25979a693 resume on every end | `seon.turn/unfinished-landings`, `land-end!` widened; +58 | a stopped landing waits forever | reproduced with a synthetic unwritable directory | 860220add set `:accepted`, so `land-end!` skipped it | files its own two follow-ups (concurrent resumers, needs another end) |
| 4ff7249cd transient/permanent, `:blocked` | `commit-refusal` reasons, `awaits-decision?`, `stuck-landings`, `blocking-landing`; +137 | endless retry; a later landing commits around a stuck one | hypothetical | 25979a693 retried on absence of a git commit | a blocker resolved any way but `landed-git` stays stuck; blocker read pre-lock |
| 2cc872997 record under lock | `recorded-landing` inside `call-with-commit-lock`; +33 | second resumer writes back against stale bytes | hypothetical (25979a693's follow-up) | a database value captured before the lock and used after it | accurate |
| 0794ffc8b uncommitted edits | `uncommitted-paths` vs HEAD blob, twice; +86 | a lane's uncommitted edit lands under the merge's name | reproduced in a regression, no production incident | shared working tree; proof against disk, not HEAD | "waits for their commit": nothing watches; it retries on the next end |
| 36890687e one open proposal | `seon.task/one-open-proposal-tx` at the writer; +24 | two open merge tasks per assignment | hypothetical | safety relied on the agent loop's step order | a merge task refused before acceptance counts as open: an agent cannot re-propose after a red gate without root |
| 2a84e933a verdict tiers | `seon.turn/reached-verdicts`: red, red-on-target, unverified; +163 | an interpreter defect on the branch blamed on the change (r5-problems-prose, 24 "not green") | **observed** | two evaluators for one test; unrun conflated with red | `:unverified` does not block, so a real break of a test main never ran ends `:done` |
| 67ff477d5 run-on-target | `run-on-target!` writes evidence on main before the verdict; +107 | 11 tests pending on main, 10 red there, blamed on the change (fa08193bc6b9) | **observed** | absence of a baseline read as "not red" | honest; its own 2.5–3.6 GB per proposal is unfiled |
| 9d4884373 typed landing exception | `try/catch Exception` in `land-end!`; +60 | a NUL in the root dial escaped `end-task!`, agent never closed | hypothetical input | no entrant-boundary catch at the landing | catches `Exception`, not `Throwable`; the recorder inside the catch can throw |
| ab18c3ffc scratch namespace filter | `program-changes` drops undeclared namespace rows; +23 | `user` namespace row refused the landing `:one-file` (r7-problems-prose) | **observed** | agent REPL state entered the program diff | none |
| 7ab559153 evaluator on runs | `:seon.test.run/evaluator` by agent id; `select` filters; +22 | a non-root agent reused root's compiled green | **observed** | two evaluators, evidence keyed only by digests | labelled per request by agent id, not per member: root's run interprets a file test that reaches a branch change and still records `:compiled` (this is exactly round 8's refusal) |
| fbc7454ec evaluator-aware readers | `accepted-evaluators`, every "latest result" reader; +213 | an interpreted green read as root's proof | hypothetical (follows from 7ab559153) | two evaluators | every pre-7ab559153 run became invisible to every reader (a baseline wipe), unstated |
| 9cd874d94 archive sealing | `seal-archive!` clears write bits; +23 script | an agent's relative `spit` rewrote the running archive | **observed** (docstring, no run id) | the JVM's cwd is its archive | directories stay writable: sibling write plus rename (what `install!` does) replaces a sealed file |
| 4027882d4 pin sealing | `seal-pinned!`; +17 script | same, into Datahike/SCI/Malli source | hypothetical (same class) | same | same |
| f540268a1 one end message | message id = digest of (agent, task); `send-call` no-op on an existing id; +76 | duplicate or lost end message across a crash | hypothetical (audit 6) | send then close in two transactions | per (agent, task), not per end kind: a `:capped` then `:done` end drops the second message |
| e361536a9 non-root never loads the JVM | `interpret-source-namespaces!`, `jvm-evaluate` refuses non-root; +325 | an agent's `require` loaded main's file into the shared host | **observed** (run 8b481c35f6bb) | one JVM, many branches | refusal is by the string `"root"`; `host-namespace!` still reachable from the core loader (untraced) |

Count: 7 observed, 12 hypothetical. Gross source added by the nineteen: about 1,700 lines.

### 4.2 Three root causes behind fifteen of them

1. **Landing writes into the shared working tree that Claude lanes edit** (14b8ea164, 17138ccad, 0794ffc8b, 2cc872997,
   and the ordering half of 4ff7249cd). Every one of these detects an interleaving between a landing and a hand edit of
   the same checkout. The audit asked for them; none has fired on real data.
2. **Two evaluators for one test** (7ab559153, fbc7454ec, 2a84e933a, 67ff477d5, and the interpreter fixes). Both real
   automatic landings were refused by this cause: round 8's two reds fail interpreted on unchanged main while the gate run
   was labelled `:compiled` (be337100e); round 5's 24 "not green" were interpreter errors on the branch. The labels and tiers
   are patches over the fact that the verdict compares results from different evaluators.
3. **A database value held across transactions or calls** (2cc872997's cause; §5.6's collection incident; `acquire!`'s
   snapshot `:seon.db/db`; `write-back!`'s `before`/`after` snapshots held across per-file `installed-tx`). The law already
   says a world holds a connection, never a database value.

The remaining four (5261c17eb, 860220add, 25979a693, 4ff7249cd's resume half, 36890687e, f540268a1, 9d4884373, ab18c3ffc,
the sealings) are either the writer doing its job (5261c17eb and 36890687e are writer checks, which is where Seon puts
decisions), the one entrant catch the law requires (9d4884373), a correct partition fix (ab18c3ffc), or crash recovery
for a crash class not yet seen (860220add, 25979a693, 4ff7249cd).

### 4.3 The root fixes, and what each deletes

| Root fix | Design | Deletes | Keeps |
|---|---|---|---|
| **A. Landings never touch the shared working tree.** The landing owner writes the proven bytes into git objects (`git hash-object`, `update-index --cacheinfo`, `write-tree`, `commit-tree`, `update-ref` with compare-and-swap on the expected parent) or into a dedicated landing checkout no lane edits; the shared tree is a lane's view that `git pull`s or that `bin/seon start --head` reads. The parent compare-and-swap is git's own exclusion. | 17138ccad's lock span, 2cc872997, 0794ffc8b, the pre-commit half of 14b8ea164, the `:blocked` file ordering of 4ff7249cd (`landing-paths`, `blocking-landing`), `bin/lane`'s lock participation. | The post-commit parent check (now the CAS itself); `proven-file`'s reanalysis (it proves the candidate, not the tree). |
| **B. One gate evaluator.** The gate's verdict compares only results of one evaluator: the merge branch's reached tests run compiled after a compiled load of the candidate (today's throwaway check JVM path, `bin/seon check`, or a compiled reload on a merge-dedicated JVM), labelled `:compiled` because they are. The agent's interpreted run on its branch is feedback and never a verdict input. Host-bound tests are data and run only there. | 2a84e933a's three tiers (one predicate: red at the gate and not red on main at the gate's evaluator), 67ff477d5's `run-on-target!` (the gate runs on main's compiled baseline by construction), `fbc7454ec`'s accepted-evaluator sets in every reader (one evaluator per record kind; the attribute stays as a fact), the evaluator-mismatch classes of the interpreted census. | 7ab559153's `:seon.test.run/evaluator` attribute, labelled per member by actual resolution, not per request by agent id. Cost: a compiled gate run is 30–90 s today (publication 26 s), so Q10 moves up if B is chosen. |
| **C. Hold a basis, never a value.** `seon.sci.eval/acquire!` keeps the commit id, `revision-basis` and `program-identity` in its snapshot instead of `:seon.db/db`; `acquired-database?` compares bases; `::loaded-database` becomes a commit id opened from `::loaded-connection` when needed; execution handles drop `:seon.db/db`; `write-back!` reads its `before`/`after` by `d/as-of` inside each step; `carried-projection` evicts a failed cell. A lint (ledger §3.19's shape) refuses a `:seon.db/db` stored in an atom, cache or handle. | The collection incident class entirely; 2cc872997's reason to exist (with A). | Datahike's own `as-of` and `since`, which are what a basis means. |

Recommendation: **C first** (it is the broken-right-now cause and a day's work), **B second** (both real landings failed on it;
it also ends the parity chase), **A third** (no observed failure yet, but it deletes the most code and is what lets the
loop run while humans still edit the tree). Q12 asks the owner to confirm this order against freezing the fences as they
are.

## 5. Root's reflections, verified

1. **Was the automatic landing worth its cost? What is the simplest correct landing?** Root's hand review plus `land!`
   worked hours earlier (3c4f94227 at 18:24). The automatic hook cost the night and has not landed; but the night's defects
   were real and all but one were in the proof path, which hand landing would have met too. The simplest correct landing is
   `land!` as it was at fb1fb9e60 plus fixes B and C: prepare on a merge branch, gate compiled, accept at the writer, write
   bytes to git objects with a CAS parent. One call, no lock, no tiers, no resume loop. The automatic hook is one line
   calling it on a green end; keep it, with the dial on, and choose probes by reach.
2. **Is landing state legible?** No. The outcome of merge task `57ad6cd33297` is spread over `:seon.task/outcome` (absent),
   `:seon.task/refusal`, `:seon.task/refused-run`, `:seon.task/refused-tests`, `:seon.task/verdicts`, `:seon.task/landed`,
   `:seon.task/landed-git`, `:seon.task/refusal-reason`, `:seon.task/blocked-by`, `:seon.task/accepted-tx`,
   `:seon.task/gate-run`, `:seon.task/installed`; `seon.context/ended` and `recorded-landing` each pull a different
   subset. Root's four misreads were different subsets. One value, `:seon.source/landing` on the merge task (the same
   `land!` answers: landed with commit, or the first refusal typed), written once per attempt with its render, would make
   `(my.program/landing merge-task)` the one read. With fix A most of the per-effect attributes disappear anyway.
3. **"No rounds until landing is exact", then rounds 5–8.** The behaviour found real bugs; the rule was wrong in what it
   named. A rule about rounds must name the task class a round may use (ordinary owner functions picked by reach) and the
   prerequisite that matters (the gate's verdict is one evaluator's), not "exactness" guarantees that no round exercises.
4. **Claims the code does not support.** From the table: "committing nothing" after a post-commit refusal (14b8ea164);
   "can no longer replace each other's declarations" (17138ccad, only among lock-takers); "waits for their commit"
   (0794ffc8b, retries on the next end); "whatever the landing path throws" (9d4884373, `Exception` only); "cannot have its
   platform source rewritten" (9cd874d94, rename works); "reaches root once" (f540268a1, drops a later end kind);
   "proves what its evaluator ran" (7ab559153, labelled by agent id); "broke no reached test" (2a84e933a, `:unverified`
   does not block); and 36890687e silently blocks re-proposing after a red gate. Astra would have caught these; the dual
   review did not run on the night's landings (Q8).
5. **Is one evaluator for agents' tests the right model?** Two evaluators exist by necessity (isolation); the mistake is
   letting both feed one verdict. Fix B: the agent's interpreted run is feedback; the gate's compiled run is the verdict.
   Parity work then has a bound (ordinary tests interpret or are declared host-bound) and a purpose (good feedback), not a
   correctness burden.
6. **Do other cached derivations hold database values?** The sweep (read-only, `src/seon`) found: the live cluster
   context's program snapshot holds `:seon.db/db` (written by `seon.sci.eval/acquire!`, read by `acquired-database?` →
   `seon.db/carried-projection` → `declares-program?` with `d/datoms` on the held value; the exact incident chain, in
   JVM mode via `seon.mcp/eval-clj` → `jvm-run` → `seon.cluster.agent/acquire-context!`); `::loaded-database`
   (`d/commit-as-db` of the loaded source commit, read by `overridden-row?`, `overlay!`,
   `install-function-from-database!`; a collection would break those too); `:seon.db/db selected` on every execution
   handle in the per-execution roster and the Flow proc state (kept alive, not read back). `seon.cache` kinds, the
   `program-identity-cache` and `refusal-recording-cache` LRUs and the projection caches hold data or delays, not values;
   `carried-projection`'s commit branch never evicts a delay that threw, so one node-not-found there would also persist
   until restart. Fix C above covers all four.
7. **Per-request cost: what the work is proportional to, and the biggest waste.** A test run should be proportional to
   the tests selected and the declarations they reach (tens of KB of source, a few MB of index reads); it costs 0.5–4.6 GB
   because each request re-acquires the program-only base (`copy-host-var` 4,633–7,460 times per run, ee1d195f8),
   re-installs the overlay, and re-derives namespace resolution with hundreds of logical pulls. `land!` should be
   proportional to the delta's declarations and their reaching tests; 5–11 GB because prepare forks and acquires a whole
   world twice (merge branch, then `run-on-target!` on main) and `install-row!` walks the reverse closure at 7.38× main.
   The biggest single waste is acquisition that does not reuse the immutable program-only base by its identity; the filed
   tasks already name it (plan item 8 of 38fce69e3). Fix B's compiled gate makes the gate's cost a JVM boot (publication
   26 s) instead of an acquisition; that is why Q10 and this item travel together.

## 6. Draft of the plan's new §0 order (DRAFT; pass 2 writes it into plan.md after the owner's answers)

Each item names its owner and what it unblocks; an item starts when its dependency has landed unless marked "beside".
Rounds are runs of the chain once TASK-CHAIN's items 5–6 land; until then a round is a set of hand-launched agents.

0. **The entrance survives a collection** (lane `collection-holds`, Opus, in flight): fix C for `acquire!`'s snapshot;
   proof: a test that collects past a held value and the entrance still answers. Same day: the chore of retiring finished
   agents' branches with a receipt (§3.8). Unblocks everything unattended.
1. **The first automatic landing, probe chosen by reach** (Sonnet picks as `real-task-pick` did; root launches one agent;
   chain-run-5 class only). A refusal is an Opus lane's defect the same day; three landings of this class before any other
   kind. Beside 0. Unblocks 4, 7.
2. **One gate evaluator (fix B)** (Opus, `seon.cluster.source`/`seon.test` owner): the gate's verdict is one compiled run
   of the candidate's reached tests against main's compiled baseline; `:seon.test.run/evaluator` labelled by actual
   resolution; the three tiers and `run-on-target!` deleted; host-bound tests declared as data; R-class tests deleted or
   rewritten. Proof: round 8's merge task lands or is refused for a red that is red compiled. After 0. Unblocks honest
   landings of platform tasks, 6.
3. **`my.task/refine!`, stage decisions as task components, generic stage proofs** (TASK-CHAIN's first build items;
   Sonnet, `src/my/task.clj`, `src/seon/task.clj`, `resources/seon/schemas/seon.task.edn`): an agent edits its task's
   fields at its REPL over `seon.task/create!`'s upsert, each edit recorded as a stage decision component
   (`:seon.task.stage/decision`, `/reason`, `/keys`); one deftest per stage type asserts on the task entity what the
   card's table says that stage leaves behind; task-file write-back in batches. Beside 1–2. Unblocks 5.
4. **Root clears the undecided merges and grades rounds 5–8** (root, by hand): decline the five stale merge tasks with a
   reason; add the "landed" column; grade against round 3. Beside 1.
5. **Stage types, transition and auto-retire** (Sonnet after 3): `:seon.task/stage` set by the pipeline when the stage's
   proofs are green, the same rule as task completion; instructions rendered from the stage type at `current-state` time
   (not stored); a task whose proofs are green on main retires itself. Unblocks 6.
6. **The first chain experiment**: one mined task through Survey, Prove, Check-proofs, Implement and Land as separate
   DeepSeek agents, measured per stage; then the same task family through a second pipeline (Survey+Model merged, or
   Check-proofs inside Implement). After 1, 2, 5. Unblocks the loop as the owner described it.
7. **Landing through git objects (fix A)** (Opus, `seon.cluster.source`): write proven bytes as blobs and a tree, commit
   with a CAS on the expected parent; delete the lock span, record-under-lock, uncommitted-edits, pre-commit bytes check
   and `:blocked` ordering. After three landings (1). Unblocks hand editing beside unattended landing without fences.
8. **The repeated acquisition work** (owner-file lane): `install-row!` 7.38×, `arm-first-loads!` 14.8×, 650 MB census reads,
   0.5–4.6 GB test runs (tasks filed). Beside 1–7. Unblocks cheap chain runs and a cheap compiled gate (with Q10).
9. **The help lists the operations agents guess at; `current-state` in the stable order** (§3.5), proven by the per-round
   signature count. Beside 6. Then the working set only if prompts cap agents.
10. **`seon.root/launch!` and the tick** as one schedule entry, after 6 has run three chain runs. Grants, root tasks,
    triage, spend report: deferred.
11. **Chores beside, never ahead**: the two reds on clean HEAD; FEEDBACK-LOOP 14, 15, 17; the lag workarounds; the
    sealing's real guarantee stated; one `:seon.source/landing` value per attempt (reflection 2); the `tmp/` dispositions.

Standing rules to add or change: a round's tasks are ordinary owner functions picked by reach until item 6; the landing
path is frozen to observed failures and shrinks with each root fix; the instrument's defects are lane work, never round
tasks; a round or chain run is graded against the previous one before the next is launched; one probe lane and one fix
lane overnight; one evidence page per day; a fence is admitted only with the observed failure it prevents and the root fix
it stands in for, named in its commit.

## 7. What is wrong with the current plan.md (to correct in pass 2)

- §0 says "no new loop round until items 3, 4 and 6 have landed" while rounds 5–8 ran; the rule must name task classes.
- §0 items 1–5 landed (ff61d0b29; 5261c17eb; 14b8ea164, 17138ccad, 860220add; c95848065, fff956fff; f540268a1,
  2d19dafb0); item 6 partly (7ab559153, fbc7454ec, ceb09bab9, c8e1c02e2, ad86afd43). They leave §0; several become
  deletion candidates under §4.3.
- §2 essential 2 says "done by hand … not exact": the exactness landed; the missing fact is "never landed automatically".
- §3's chain table is superseded by TASK-CHAIN's stages; §3 should point at the card.
- §7 has duplicates (two "correctness and efficiency" rulings; two on cost numbers) and one superseded ruling (merge bases
  by commit walk → by `t`). §8 Q1–Q6 are all decided and move to §7 with their facts to evidence.
- ROOT-AGENT §2 ("write-back conflicts exist only while Claude lanes edit") and §8's "nothing written" claim are refuted
  (audit 10) and are corrected when the card is touched.

## 8. Questions for the owner

Answers go under each question in this file; pass 2 rewrites `plan.md` from them. Where the owner does not answer, root
decides with the recommendation (ruling 2026-10-10) and marks it so.

**Q1. The cost diff in every ordinary agent's reply.** Context: always on by ruling; no model agent has acted on it; its
defects consumed four round slots. Options: (a) keep, but its defects are lane work and never a round task; (b) show the
diff only to root and at the gate; ordinary agents see their request cost only; (c) keep `cost-diff` as a round task until
an agent fixes it. **Recommendation: (a)**; (b) if three rounds show no use.

> **Owner answer (2026-10-11):** Concise perf feedback (owner: "I want agents to know when they are writing shitty software … 500 tokens is too much"). One line (≤40 tokens) only when a definition the agent changed got costlier than main, e.g. `slower: seon.foo/bar 3.1× main (alloc 40→124 MB, 12k calls)`; nothing otherwise. Request cost shrinks to `ms / MB` on one line. The teaching goes in the instructions: the Implement template carries "ask what the work is proportional to", and tasks where cost matters carry a cost proof. Facts (research): `seon.batch/cost-diff` ≈500 tokens; no model agent acted on it in 464 model evaluations; three open defects. Defects are lane work, never round tasks.

**Q2. The auto-landing dial while the first landings happen.** Options: (a) on, probe chosen by reach; (b) off, root runs
`(land! …)` by hand on each green end until three land; (c) on, `problems-prose` stays the probe. **Recommendation: (a)**.

> **Owner answer (2026-10-11):** (a), decided earlier: auto-merge on green, probe chosen by reach.

**Q3. The gate's evaluator.** Context: both real automatic landings were refused because an interpreted red was compared
with a compiled baseline (round 8: two tests red interpreted on unchanged main, run `21ae1d38003e`, gate labelled
`:compiled`; round 5: 24 interpreter errors). Options: (a) parity class by class, keep the tiers and labels; (b) one gate
evaluator (compiled run of the candidate at the gate), interpreted runs are the agent's feedback only, host-bound tests
are data, R-class tests deleted or rewritten; (c) stop interpreting tests; agents' reaching tests run only at the gate.
**Recommendation: (b)**; (c) loses the agent's own fast feedback.

> **Owner answer (2026-10-11):** Interpreted must agree with compiled for every declaration that is not host-bound; **every mismatch is a bug**, not a verdict class (owner: "why would an interpreted not work on the compiled, that sounds like a bug … platform doesn't work in SCI, that's a known issue"). The interpreted census is the bug list: fix interpreter bugs at their owners, rewrite the ~50 law-breaking tests (`with-redefs`, `alter-var-root`, `intern`), declare host-bound cases as data. The gate compares directly with no verdict tiers; host-bound and platform changes are checked by the existing compiled check (`my.test/check-platform`, 32–45 s measured, not "publication 26 s"). The gate's per-request `:compiled` label (`seon.test/run-request`, by agent id) is a bug: label by actual resolution per member. Q10 folds into this: ordinary landings gate in default; platform landings pay the check JVM.

**Q4. What rounds work on until the chain runs.** Options: (a) the loop's own findings, as rounds 2–8; (b) small ordinary
owner tasks picked by reach; the loop's findings go to Claude lanes with red proofs; (c) mixed. **Recommendation: (b)**
until item 6, then chain runs decide.

**Q5. ROOT-AGENT stages 4–13.** Options: (a) build as the card orders; (b) `launch!` and the tick after three chain runs,
the rest deferred until a judgment duty has occurred three times; (c) stop the card. **Recommendation: (b)**.

**Q6. The undecided merges and the 56 live agent branches.** Options: (a) root decides and retires by hand with a receipt
as soon as item 0 lands; (b) build retirement-on-close first; (c) leave them. **Recommendation: (a)**; (b) lands later from
a lane; (c) is what broke the entrance.

> **Owner answer (2026-10-11):** Clean now plus one close owner. Root declines the three stale merges (fda9b3f7af69, 3ea28f83185c, 25ad5b9045b0) and retires ended agents' branches by hand today with a receipt. One owner, "close merge task" (decide, retire S and the proposing branch), called by `seon.cluster.source/land-held!`, `my.program/accept-merge!` and decline; an ended agent with no open merge task retires its branch at its close. (Research: the hand accept path already retires; live counts 59 branches, 58 agents with a live branch.)

**Q7. DYNAMIC-CONTEXT stages 4–7.** Options: (a) build now; (b) the help line and TASK-CHAIN's stable `current-state`
order first, measured by the guessed-name signature count over three rounds; working set after if prompts cap agents; (c)
BENCH-9's history window as a dial. **Recommendation: (b)**.

**Q8. Review cadence.** Context: the night's landings carried nine over-claims (§5.4) and no dual review ran. Options:
(a) dual review per landing as ruled; (b) Astra audits each day's landings once, Opus reviews each platform landing,
ordinary landings are reviewed by the gate alone (the auto-merge ruling); (c) weekly. **Recommendation: (b)**.

**Q9. Lanes overnight and documents.** Options: (a) as this weekend; (b) two code lanes (probe and fix), one evidence page
per day, cards revised in place; (c) no unattended lanes. **Recommendation: (b)**.

**Q10. Publication at 26 s per check and boot's whole-program derivation.** Context: deferred; fix B (one compiled gate)
would pay it on every landing of a platform task. Options: (a) stay deferred; ordinary landings gate on the merge branch
in the main JVM (compiled Vars already loaded; only the candidate's declarations differ), platform landings pay the check
JVM; (b) per-file derivation inputs now (3–5 h); (c) accept and batch host-bound landings to one restart a day.
**Recommendation: (a) with (c)'s batching**; (b) when the chain table shows it dominating.

> **Owner answer (2026-10-11):** Folded into Q3: no separate publication work now; platform landings use the existing check (32–45 s).

**Q11. The round-8 refusal.** *Answered by lane land-r8 (be337100e):* the two reds are red interpreted on unchanged main
and the gate run was labelled `:compiled`; the proposal also carries a scratch schema `:seon.problems/qty` the agent
never used. Remaining decision: (a) land `problems-prose` by hand after dropping the scratch schema, and let fix B make
the class impossible; (b) wait for fix B and re-gate; (c) decline it. **Recommendation: (a)**, and a proposal carries
only the declarations its proofs and reaching tests reach (task `an-agents-scratch-schema-never-rides-its-landing`),
decided at `compare-branch`, not by a filter at landing.

> **Owner answer (2026-10-11):** Fix the two interpreter bugs and re-land. Both refusing tests are red the same way interpreted on unchanged main, so they are interpreter bugs (a nil connection supplied on the interpreted path; the failure-facts reader mismatch), plus the gate's `:compiled` mislabel. An Opus lane fixes them at their owners with the round-8 tests as red proofs; then `land!` re-runs 57ad6cd33297 without the unused scratch schema `:seon.problems/qty`. That is the first automatic landing.

**Q12. The fences: remove their root causes, or freeze them?** Context: §4; twelve of nineteen guard hypothetical
failures; three root causes explain fifteen; the owner wants no patches. Options: (a) remove the root causes in the order
C, B, A and delete each fence in the commit that removes its cause; (b) freeze the fences as they are, add none, revisit
after ten landings; (c) delete the hypothetical fences now and rely on the writer and the immutable database as the
safety net until a failure is observed. **Recommendation: (a)**; (c) is tempting but deletes detection before the cause
is gone, so a lane edit could land unproven in the meantime.

> **Owner answer (2026-10-11):** (a), and stronger: AGENTS.md law NO GUARDS OR FENCES (c722d8d22), no exceptions. Executed as Q15.

**Q13. Where TASK-CHAIN goes, and the first chain experiment.** Options for order: (a) `my.task/refine!` beside the first
landing, stage entities after it, the first chain run after the first landing (draft §6); (b) the whole card before any
further landing work; (c) the chain after three landed rounds of the old shape. Options for the experiment: (i) one mined
task (a slow-function query result) through Survey, Prove, Check-proofs, Implement, Land, each a separate DeepSeek agent;
(ii) the same with Model added on a data-first task; (iii) two pipeline variations on one task family from the start.
**Recommendation: (a) and (i)**, then (iii) on the second family, with the first build items in the owner's §3b order:
`my.task/refine!`, stage decisions as task components, generic stage proofs. Open from the card for the owner: is
Check-proofs a separate agent or the start of Implement (recommend separate, measured once, merged if the pipeline's own
Prove stage proof never disagrees with it); may Implement send back by itself (recommend yes, recorded as a `:removed` or
`:changed` decision with its reason); are instructions stored on the task or rendered from the stage type (recommend
rendered); and may a stage's proof be a short model call where no data predicate exists (recommend no for the first
experiment: every stage proof is a data deftest, and a judgment that cannot be written as one is a send-back).

**Q14. How the task entity is written at the REPL and how context is pruned.** Options for writing: (a) `my.task/refine!`
over `seon.task/create!`'s upsert, any field, one note per edit, file write-back in batches at landing; (b) per-field
operations (`add-must-read!`, `set-intent!`); (c) agents edit the EDN file through write-back. Options for pruning: (i)
only `must-read` removal by the agent (the next context reads less because the task names less); (ii) plus the working
set (DYNAMIC-CONTEXT stage 5) for the agent's own reads; (iii) a budget dial. **Recommendation: (a) and (i)** first; (ii)
when measured prompt growth asks for it.

**Q15 (added by root). Revert the night's fences?** Audit (read-only Opus, 2026-10-11): of 73 commits since 2026-10-10
20:30, 16 add fences; reverting them in reverse order is clean except trivial task/schema EDN hunks, one `merge_test`
hunk and one real conflict (f540268a1 in `seon.turn/end-task!`); about −1,500 lines; ~40 good commits kept (interpreter
fixes, f51240f34, e71e60a47, `land!`, `land-end!`, the dial, typed refusals, `:seon.test.run/evaluator`). Corrections to
§2.2: `turn.clj` grew 5,622 → 5,888 overnight, `source.clj` 1,488 → 2,136. Fences outside the night: `bin/lane`'s
`.git/lane-commit.lock`, archive sealing (9cd874d94, 4027882d4), `:root-refused` (93d9e4ef6), the `"root"` string check
(e361536a9); each gets a root-cause task.

> **Owner answer (2026-10-11):** One batched revert, generated with `git revert --no-commit` in a throwaway
> `git clone --shared` under `tmp/` (never the shared index), applied to claimed files; `end-task!` rewritten to send the
> end message and close the agent in one transaction; observed-failure tests kept as red proofs for the root fixes; one
> central test round; schema retirement proven on a live-store branch.
