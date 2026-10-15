---
title: The plan
type: plan
status: active
created: 2026-10-10
---

# The plan

One plan, revised in place. First written by lane fable-docs on 2026-10-10; rewritten by lane fable-replan-2 on
2026-10-11 from the owner's answers in [fable-replan](../research/agent-platform/2026-10-11/fable-replan.md) §8
(questions Q1–Q16), the [task verbs catalogue](../research/agent-platform/2026-10-11/task-verbs-catalogue.md), the
[owner decisions](../research/agent-platform/2026-10-11/owner-decisions.md) of the same morning and the TASK-CHAIN card's
two reviews (`schedule/cards/TASK-CHAIN.md` §8). Live facts were checked on default (archive c1230efc3, then
f51240f34) in JVM mode, read-only. Dated evidence lives in the research pages; this file holds direction and decisions.

## 0. Where we are, and the order (revised in place by root; last 2026-10-14 16:45 local)

**State (2026-10-15 05:15 local).** Lifecycle authority: [TASK-CHAIN](schedule/cards/TASK-CHAIN.md), §7 status column.
Every chain mechanism is installed and has worked live: `chain!` (and `seon.task/from-filed`), Design → Prove →
Implement advance, the done rule, bail to root and `my.root/run!`, per-task `:outside` agents worked through MCP, and the
final merge: on 2026-10-14 outside-2's `:outside` Seon agent fixed a filed defect from generated context in 12 calls and
root's pass merged, published and retired its chain with 0 hand acts (becee4d99). That first automatic merge also
merged the first automatic regression (it broke the edit hook's babashka load, a constraint stated only in prose;
reverted e5dd2abd5). Live DeepSeek S5 run 4: Design and Prove done with 0 hand acts, Implement bailed and reran three
times on context waste and REPL defects, since fixed. Default runs HEAD; lanes, findings and the morning questions:
`../research/agent-platform/2026-10-14/root-working-state.md` ("Night of 10-14").

**FIRST, before any other work or live run: every context defect, and the live agent view** (owner, 2026-10-15
afternoon): "Why are we doing so many stupid things with the context and running agents with shitty context with known
defects? I want you to focus on fixing all context related defects first. Don't throw away good data an agent may want
the full readout for later on, write a render function for it and write html and ai versions so the user can see it if
it's important. The user should be able to follow along with the agent in realtime based on what they are doing on the
repl. A function is returned and we auto run with tests, all of that should be visible somehow even if it's nice text
that's live updating the status at first. Just tailor it to your audience. I want this UI up and running so I can help
you by saying 'that is way too much perf data for every call'."

Rules for this work (owner, 2026-10-15): **keep good data, delete garbage at its producer.** Good data an agent or the
user may want in full stays whole and requeryable, and a render pair per schema chooses what each audience sees (AI
terse, HTML full; one derivation, AGENTS.md "Choosing the design" 5). Garbage — output nobody asked for, data the
producer itself says it cannot attribute, copies of values held elsewhere, JVM-internal frames — is removed from the
producer's output, never hidden by a render: "The best solutions are the ones where the function is producing exactly
what it wants and needs. We can always accrete more complicated outputs, but agents shouldn't be dumping MB worth of
data unless it's intentional." This binds Seon's own producers — its schemas, functions and tests, and everything they put in a reply or a
block: by default Seon is efficient and computes and returns only what is relevant. An agent's own REPL values are its
own: it may evaluate and print whatever it intends (intentional, self-inflicted cost), and they are never clipped. If
an agent's context is mostly Seon-produced garbage, stop it and fix the producer first. For digging, the value printer's HTML face is already a nested expansion (`seon.print/emit-hiccup`: a
`<details>` per collection with its summary, from the same operation stream as the text): a value with no render of
its own is browsable in HTML by default. The live agent view comes first because it lets the owner judge the context
directly. No live (provider) run until the context defects below are fixed and the owner has looked at the view.

| # | Context defect (evidence) | Fix at the producer |
|---|---|---|
| C0 | The owner cannot watch an agent: no live view of its eval requests (forms, value, test step, cost) | Live agent view: per eval request, the forms, value, auto-run tests' live status, done refusals and cost, through the render proc's Datastar morph; keyed by agent id, not namespace; render functions beside their data; the focus layout (main panel = most recently changed unit, right rail by recency) |
| C1 | Every reply's cost map is ~25 lines, half work the reply cannot attribute (`:jvm-window`, `:shared`) | One render function for the cost value: AI face one line (ms, alloc, calls; slow functions only when over), HTML face the whole map |
| C2 | An error's cause chain carries ~60 frames into replies (task `seon.error/a-cause-chain-holds-a-reference-not-its-frames`) | The chain holds a reference; its render shows the first-party frames, the full chain requeryable |
| C3 | A failed test's report copies a whole 14k value into `:seon.test.failure/actual` | The report holds the value's reference; its render shows the differing part |
| C4 | Prompt caching fell to 23% (S5 run 6) | Argon task A (in flight): the first unit that varies per call moves after the stable prefix |
| C5 | Cold block 4.7-18 s | Argon task B (in flight) + the three-way readers' deletion (P1+P3) |
| C6 | Must-reads of uncontracted functions show declaration-absent error maps with timestamps; ~400 tokens of library must-reads from plan prose | Must-reads render the declaration (or its absence as one typed line), library names at name level |
| C7 | The merge preview shows `:seon.error/at` and `:unknown` under "not green"; a reply carries no proofs/branch summary when proofs go green | The block's proofs and merge sections render result states as data: green, red with assertion, not run — never an error stamp |
| C8 | The changes section is not yet the merge list; forward-merge conflicts appear only in `merge-main!`'s reply | Merge list (ruling 5a) and conflicts (P4) rendered in the block, after the merge round |
| C9 | Reasoning-without-answer / token-starved replies are delivered like unparseable ones | The turn tells the agent what happened (its reply had reasoning and no forms) as data |
| C10 | A quoted symbol in test data yields an `:auto-required` finding; multi-word `my.program/search` answers nothing; `my.program/diff` cannot inspect a changed namespace | Fix each producer; file what is over 15 minutes |
| C11 | Context and help tests are red on HEAD (`seon.context-test` 3, `seon.help-test` 5) | Red census (in flight) classifies; fix or retire with the census |
| C12 | ~150 scratch agent branches list in `my.agent/branches` | Root closes their admissions after the lanes end (649029d26 lifecycle deletes) |
| C13 | A request whose forms do no work (`(+ 1 1)`) costs 120-200 ms, its test step 44-150 ms and 67-263 MB on a branch with nothing new to test (lane redefine-cost, HEAD 1119db9e1, measured under load) | Measure on a quiet JVM; the test step's per-request selection does only what changed |
| C14 | A reply's `:seon.cost/diff` lists `seon.db/db`, `pull`, `pull-call` as `#{:interpreted :new}` for an agent that never touched `seon.db` (same lane) | Find whether the diff reads other branches' profile data or seon.db is interpreted on the branch; fix the producer |
| C15 | 32 tests red on HEAD (red-census.md a6d059a75), 8 of them context and help (rows 5-7: a missing reference shows no problem; 11-15 help) | Lanes context-help-reds and classified-reds; the 25 UNKNOWN rows bisected before 76c4bed09 next |

**Second: generative schemas and functions, specified** (owner, 2026-10-15; brief tmp/gen-pipeline.md). Every schema an
agent defines is checked for being generative and fast (and round-trips to the database when it is an entity schema);
every contracted function is checked generatively when it changes, failures shrunk to the smallest case; agents see
shrunk example inputs and outputs when they view a function; the live view lists the branch's changed schemas and
functions with their check results and data present; every function the branch changed must be checked green to exit a
stage (this also clears scratch). Pipeline: Haiku library dig (§1) → Sonnet web research (§2) → Opus design (§3) in
docs/research/agent-platform/2026-10-15/generative-schemas.md; it supersedes the measured-gates hybrid (both plans feed
§3). Infrastructure first where the whole cannot land now.

**The order (owner rulings 2026-10-15, `../research/agent-platform/2026-10-15/branch-model-rulings.md`):** a branch's
program is its own writes over main's HEAD; merge by reach closure, reusing the branch's results; the writer follows the
branch; config reads main's HEAD unless the branch wrote it.

| Step | Work | Model | Waits for |
|---|---|---|---|
| 1 | Branch runs its own HEAD (own-delta-hybrid.md 1c510bbf4): P1 interpret set by commit pair, P2 readers, P3 reach/recorder (three-way readers deleted), P4 root merges main forward, P5 config apply --branch/--chains, P6 writer uses branch predicates. Status: P5 done 3418b8d7b; P1+P3 done 04fcb4c12 (source +86/−179; the recorder's stale check deleted); P4 done 58b1a68cc (`my.root/merge-main!`; conflicts only in its reply until a stored attribute lets the block show them: next slice); P6 done 0a73676ee (admission resolves the predicate name on the branch, data runs the branch's body; schema.clj +42/−48); P2 in flight (deletes seen-database and its helpers). Measured: the S5 chain differs from the loaded commit in 135 digests, reverse closure 2,296 functions, which is why P4 is needed | Opus P1-P4, Sonnet P5-P6 | — |
| 2 | Merge by reach closure (seeds: proofs + green branch tests reaching changes; all hops; program entities only; unreached additions reported) and by result lookup | Opus | 1 |
| 3 | `ns-unmap!` [subject]/[ns sym] with a world-derived context; retire non-chain agent branches; bb-load property for the hook's namespaces | Sonnet ×3 | — |
| 4 | Stage exits that tell each agent what done is, revised from `:outside` runs | Opus + Sonnet | 1, 2 |
| 5 | S5 chain (held task c12a57408f49, `:chain-f1f6dc8e6652`): after 1 and 2 its merge carries only the reach closure | Opus run lane | 2 |

Deferred until S5, each for a reason: S2c-2, S3c, S3e, S3f (the first run is watched; Claude-Code-as-Seon-agent runs
follow S5); S3d-2..6 and the S3d-3 patch (context is usable, 58f130e03); the cache phases 2 and 4-8 and the Argon third
catalogue (one design round at a time); reconcile-writer and boot-dup-analysis (unless a live run shows the cost
blocking); the namespace-agents retirement (needs a restart batch); the red sweep (bulk tier first, centrally, in a quiet
hour; owner 2026-10-14: old reds matter less once chains mine and fix).

**Beside the chain** (only into a free slot and free files; never a critical-path file while S2-S3b are open):
REPL-path frictions by producer (`repl-trial.md`, `dig-repl-commit-path.md`; filed under `my.program`, `my.test`,
`seon.cluster.source`, `seon.operator`); request-view (later forms see the request's own definitions, one recording
transaction); projections (one per content, speculative per operation); the cache phases of `truths-hybrid.md`; the
errors cut E2 → E6 (`errors-cut-plan.md`); the check queue wait (`seon.operator/a-check-waits-for-a-slot-longer-than-it-runs`);
the test step's 2.6-7.3 GB per `defn` (Astra).

**Rules** (AGENTS.md is the authority; these are the ones today's audit sharpened): claims are commit windows and a
stopped lane releases; at most one host-bound lane per check slot (4); REPL-first for ordinary function changes, every
friction filed; a Haiku or Gemini claim is reproduced before it is a task; restarts pause and resume lanes; one design
round in flight; a dependency or fork bump only just before its restart. Rhythm (owner 2026-10-14 evening): proven
work lands the hour it is proven (root applies a stopped lane's proven patch itself rather than queueing it); restarts
come often, since agents keep their branches and reacquire, and a nuke is acceptable when it unblocks; every lane,
host-bound ones included, starts as a Seon agent at the REPL and reports its context frictions, because bad context is
the defect that costs every agent.

**Restart batches.** Next restart: the clj-kondo fork bump `eb245d82` (`../research/agent-platform/2026-10-14/kondo-fork-bump.md`;
fixes `my.program/write-branch!`'s NullPointerException; proven by `an-agent-writes-back-a-file-whose-analysis-runs-a-hook`),
plus every commit since 5fddc2bfd. After S5: the namespace-agents retirement (`namespace-agents-inventory.md`).

**Owner rulings of 2026-10-14** (one line each; recorded in their owning documents):
- TASK-CHAIN: filed tasks enter as full chains and are deleted at merge; per-task model with `:outside`; no checkpoint; three reference levels (bare summary, one-backtick doc, three-backtick code) with the structural fallback; "plan" names only the task field.
- Agents work through the REPL; files are written at merge and `bin/lane` retires once the merge carries its checks; no per-agent worktree.
- Everything immutable is shared, keyed by the content it reads; no duplicate caches (stored data is read from its owner); test results carry across branches, red included.
- Retention pruning off (`:seon.config.maintenance/retention?` false); deleted branches still reclaimed.
- Models by evidence: Haiku gathers, Sonnet implements and prototypes, Opus takes hard problems and the critical path, Astra performance and second plans, Fable hybrids and audits, Gemini Flash root causes and audits, Argon long hunts; no paid Claude API; DeepSeek for live runs, Claude Code agents as Seon agents for context checks.
- Check slots 4: development tooling may exceed the 16 GB runtime budget; the queue wait is a defect to fix.
- Namespace agents retire (a task supersedes them); question tasks parked.

## 1. The goal

Software that grows by accretion: open data under named schemas, stable names, every call checked and measured, built by
many agents each in its own branch and SCI context. Work is a chain of tasks ([TASK-CHAIN](schedule/cards/TASK-CHAIN.md)):
Design, then Prove, then Implement, each a new agent working the task as the previous one left it, all on one branch
that root merges to main once. Tasks are mined from the database (slow functions, guessed names, uncontracted functions,
red proofs), and root closes a filed task whose proofs turn green on main (goal 4; not designed yet). A core of about
10,000 lines where ordinary work is sub-second.

**The direction: the opposite of virtual machines** (owner, 2026-10-15; AGENTS.md "The system"). Reuse as much as
possible, and still give every agent its own small world to build out, change and experiment in. Isolation comes from
immutable values and branches, never from copies, walls or per-agent recomputation: a branch is a pointer to main's
shared indexes and runs its own HEAD like a git checkout; SCI interprets only what the branch changed, everything else is
the one loaded program; every derivation is keyed by the content it reads, so identical work is done once for every
branch and agent that has it; main moves only by an explicit merge curated by the branch's tests.

Root and its lanes drifted from this repeatedly on 2026-10-14/15. Check every design against these before writing it:

| Drift (observed) | The direction |
|---|---|
| Three-way readers comparing each declaration against main's history (`seen-database`, `moved-past`, `merge-sides`) | A branch reads its own database value; the only cross-pointer work is which declarations to interpret, a lookup between two values, cached |
| Cache keys by context, agent, branch or commit identity, to "keep agents apart" (P6 brief) | Key by the content read: the same definition with the same input shares one entry across branches; sharing is not a leak |
| An exception reading across branches (config read from main) | Root writes to the branch explicitly (`config apply --branch`); a branch never reads another's state implicitly |
| A candidate world booted to re-test at merge | Reuse the branch's results by reach digest; rerun only tests whose reach the merge changed |
| A hand list or junk filter for what merges | The branch's tests curate it: changes reached by its proofs and green tests merge, the rest stay and are shown |
| Per-agent leftovers kept alive (a closed attempt's graph; ~100 never-retired scratch branches) | An agent's world exists while it works; end it, retire its branch, keep nothing a value already holds |
| Guards against agents harming each other | No security isolation (owner); correctness comes from values, branches and the merge, never fences |

## 2. The essentials

1. **An honest, short number in every reply.** Committed (FEEDBACK-LOOP stages 1–13, 16; like-for-like cold and warm cost
   per [symbol scope], b37a3ff72). Being cut to one line: an agent learns it wrote slow software in forty tokens, not five
   hundred.
2. **Tasks worked end to end by Seon agents through the REPL.** A model agent has turned a designed task green and root
   has merged three by hand. The chain of separate agents on one branch is the next shape (TASK-CHAIN §2, slices §7).
3. **Proofs before work, a done rule after.** A task's proofs are a field, `:seon.task/proofs`; Prove writes the next
   task's, red by design, and one rule, `seon.cluster.source/done-refusals`, ends every task (TASK-CHAIN §2.4). A task
   whose proofs are all green at its start ends at once. Installed until S2c/S3a: tagged deftests and
   `seon.task/assign!`'s refusal. Proof quality is the weak point: nearly every failed round traced to the proof or the
   task, not the implementer, which is why Prove is its own stage (TASK-CHAIN §4).
4. **Default runs HEAD.** Routine: `bin/seon start --head` per batch of commits; readiness 5–6 s on a quiet machine.
5. **Correctness and efficiency over size, for now** (owner, 2026-10-10). Temporary growth is acceptable; Seon agents
   trim it later using the cost data; every change is judged by correctness and measured cost. Growth that is a fence is
   not acceptable.

## 3. The method: a chain of agents

The method is TASK-CHAIN. A chain is a vector of plain task maps that `seon.task/chain!` writes in one transaction on one
branch, linked by `:seon.task/next` and started from a template per stage (`seon.step.design/template`, `prove`,
`implement`). Root's listener starts a new agent per task; the agent works its block at the REPL, edits the plan and the
next task's proofs with `my.task/update!`, and ends when `done-refusals` is empty. The finished task's plan flows to the
next. Any end that is not done bails to root, with no relaunch; a finished chain merges to main once. The MVP chain is
Design → Prove → Implement, for bug fixes and new features alike; specialized chains are other templates on the same
mechanism. Installed until S3a: copies, children and per-step merges.

Every new mechanism is run by hand once before it is automated: each early chain run found two to five frictions at
minutes of cost, the cheapest learning there is. Live runs and their order are §0.

## 4. Done

The honest-measurement task (FEEDBACK-LOOP stages 1–13 and 16); the merge base by `t` (d9eb15c05, aa7ffb17f); the
byte-bounded node cache (12473ca94); the first links of the agent lifecycle (DYNAMIC-CONTEXT stages 1–3: the block names
the namespace, an agent closes at its end, one end message to root); non-root agents never load the shared JVM
(e361536a9); a function and its schema merge as one unit (ff61d0b29); the collection fix (f51240f34). History is in git
and the dated research pages.

## 5. Superseded

Everything that describes copies, subjects, children, `needs`, `seon.task/effective`, obligations, `:seon/tasks` tags,
`refine!` or relaunch is replaced by TASK-CHAIN (§6 there lists the deletions). Git history keeps it.

## 6. Deliberately deferred

- ROOT-AGENT stages 4–13 (grants, root tasks, `decline-merge!`, tick, triage, spend report): `my.root` holds root's tools
  first; the tick waits for three chain runs; triage waits for a judgment duty to occur three times.
- DYNAMIC-CONTEXT stages 4, 5 and 7 (shown declarations, working set, reflection on a snapshot): only if a chain run
  shows prompt growth caps agents. Stage 6, history by reference, is installed by size (S5 run 4b prompt cost; the
  card's declaration-showing rule is not): an exchange
  older than the newest `:seon.config.context/history-whole` whose reply is over `:seon.config.context/reference-tokens`
  shows its form, result handle, reply size in estimated tokens and requery form (`seon.context/exchange-form`).
- Publication (a compiled check is 32–45 s measured) and boot's whole-program derivation: platform commits pay the
  existing check; per-file derivation inputs when the chain runs show the cost dominating.
- Merging through git objects or a dedicated checkout: committed only if a real collision is observed.
- COST-REG slices 2–3; growth tests and guards; embeddings (after lexical search is used and found wanting); the Datahike
  fork rebase; a stateful lifecycle test suite; a Flow-hosted writer (refused).
- The positive-wording pass over AGENTS.md's remaining negatives: root, in small commits.

## 7. Decisions, by owning topic (every owner ruling, deduped; superseded ones marked)

**Laws and design**
- 2026-10-10 Seon grows by accretion; the four properties and the design order in AGENTS.md are the laws.
- 2026-10-11 NO GUARDS OR FENCES, no exceptions (AGENTS.md); supersedes "every restriction names the failure it prevents".
- 2026-10-11 Focus: improving the tasks, not more platform machinery; embedding search later.
- 2026-10-10 Correctness and efficiency over size for now; temporary growth acceptable; agents trim later.
- 2026-10-10 Datahike's writer stays as it is (go-loops, group commit); Seon does not batch; Flow procs run on platform
  threads, one per task; accounting closes at `seon.flow/var-process`; the reply is published after accounting.
- 2026-10-10 Stored data is described in Datahike's terms (entities, attributes, values, datoms).
- 2026-10-10 Merge bases are found by the fork's `t` and `as-of`, never by walking commits (committed d9eb15c05).
- 2026-10-10 Full history is kept; `as-of`, `since` and `history` are unaffected. (Retention is off by dial, §0.)

**Tasks and the chain**
- 2026-10-10 Work is a chain of separate agents; run by hand first.
- 2026-10-11 The chain is TASK-CHAIN; build it generally, experiment with variations, keep the stages worth their calls.
  Its "every stage records an explicit decision" and the subject/step shape are superseded by the 2026-10-13/14 rulings
  in TASK-CHAIN's header (stages are linked tasks on one branch; a stage's check is its handoff predicate, §4).
- 2026-10-10 Tasks are entities with history; author little, derive the rest; task names are fully namespaced symbols;
  the database is the source of truth and files are lossless write-back, proven by a round trip. Superseded as to
  members (authored = name, intent, must-read; derived = proofs): authored = `plan`, `instructions`, `proofs`; derived =
  `references` (TASK-CHAIN §1).
- 2026-10-11 Auto-retire: root closes a filed task whose proofs turn green on main; not designed yet (goal 4).
- 2026-10-11 Vocabulary (Q16): agents have `my.task/current`, `update!` (was `refine!`, TASK-CHAIN §3a) and `send-back!`
  (stays, §2.7); success has no verb; root's namespace is `my.root`; merge words are git's (`merge!`, `close!`,
  `:merged`/`:closed`); home → main, refuted → a red proof, outcome → end, proposal → merge request.
- 2026-10-08 A question task declares its answer's Malli schema; the answer is ordinary Datahike data.
- 2026-10-09 Cost has four meters (calls, CPU, allocation, storage); an agent's budget is its turns
  (`seon.turn/turns-left`, TASK-CHAIN §2.7) and completion is the done rule.
- 2026-10-11 Guessed names: answer at the failure with the nearest real symbols and calling forms; learn guessed →
  resolved from the agent's next form; link it to the task; a name guessed twice becomes a mined task.

**Runtime and scheduling**
- One path: an agent wakes and runs a task; everything uses it (chain tasks, problems, recurring attempts); the
  reconciler, the clock and the heartbeat are the procs that host it, not tasks. Agents never start agents.
- Progress is derived from facts written once (chain tasks and their `next` links, attempts, outcomes); intent is stored
  on the task (`:held :running :paused :stopped :cancelled`) plus cluster `running/paused`; nothing writes "actual" to
  the database except the heartbeat's audit.
- Order on a chain is `:seon.task/next` (TASK-CHAIN §2.1). Superseded: "order is `needs` only, parents never run"; `needs`
  and `children` are deleted (§6 there).
- Instructions are data on the task. A stage is a template: an ordinary held task (`seon.step.design/template`, `prove`,
  `implement`, one file each) merged into a chain's task maps by its creator, with its checks a contracted handoff
  predicate and one deftest (TASK-CHAIN §1, §4). No stage type entity, no template language, no second merge rule.
- Problems are tasks assigned to root by identity, created where their facts already pass; nominal ends wake nobody;
  error signatures and cost regressions go to fresh agents. A bail is one problem class (TASK-CHAIN §2.7).
- One scheduling system: a recurring thing is a task with a cron; one clock proc writes firings; a function firing runs in
  the clock (maintenance, heartbeat); an attempt firing runs on the path; a firing to a live agent is a message wake;
  refused delivery is a problem task. `seon.schedule`'s per-agent proc goes.
- Flow owns process and computation; channels carry only ephemeral data; everything of consequence is a fact, so a crash
  and a resume to any commit are derivations. `start` resumes; `start --paused` for debugging.
- `max-running` is a dial, default 4; agents are to be lightweight: context MB and ms to start are measured targets.
- The cache of a branch's interpreted definitions (`seon.sci.eval/interpret-overlay!` per branch) is the first
  performance lane.

**Proofs and tests**
- 2026-10-09 A task's proofs are its definition of done and a task without proofs never finishes (TASK-CHAIN §2.4); the
  rest is an unproven backlog.
- 2026-10-10 Proofs as written: an `are` table with the reported input plus one `is` on a count for a cost task; a seeded
  property only where a one-line law exists; COST-REG slices 2–3, guards and growth tests cut.
- 2026-10-11 How to write good proofs: the Prove template's instructions are the catalogue (TASK-CHAIN §4), revised by
  live runs.
- 2026-10-11 Interpreted must agree with compiled for every declaration that is not host-bound; every mismatch is a bug,
  never a verdict class; platform changes use the existing compiled check.
- 2026-10-10 Fixtures are data on the namespace entity (`:each`, `:once`), written by `use-fixtures` and file indexing.
- 2026-10-10 Only reliable, like-for-like cost data is surfaced; one cost entity per [symbol, scope], cold and warm.

**Merge**
- 2026-10-10 Auto-merge on green is the target. Until criteria for non-controversial merges are written, root reads the
  diff before it merges (AGENTS.md). Superseded 2026-10-11 (Q8) "a merge request is a task assigned to root": root merges a
  chain branch once (TASK-CHAIN §2.6); `my.root/merge-requests` is installed until S3b.
- 2026-10-11 One close owner, `seon.task/close!`, decides and deletes branches; `my.root/close!` calls it.

**Measurement and cost**
- 2026-10-11 Concise performance feedback: one line of about forty tokens only when a changed definition got costlier than
  main; request cost as `ms / MB`; the teaching goes in the instructions (Q1).
- 2026-10-10 Numbers are live: each reply shows the request's own values against main's stored values; cost entities per
  function with values replaced in place, no history; schema cost measured once per digest at contract time.
- 2026-10-09 A cost regression against the last green run on main is a red test, not a health note.

**Data, documents and search**
- 2026-10-10 Doc sections are entities identified by path and anchor, with parsed references as facts; files are the
  write-back.
- 2026-10-10 Search: the fork's Lucene secondary index (`:scriptum`) first; vectors (`:proximum`) only after a chain run
  shows a lexical miss.

**Process and roles**
- Roles: AGENTS.md "Pick the model by the work" and §0 Models. Superseded: 2026-10-10 "DeepSeek-flash implements
  non-platform; Opus implements platform". 2026-10-11: design is interactive with the owner; lanes implement agreed
  designs (Q9).
- 2026-10-10 When the owner is not replying, root decides with the recommended option, proceeds and reports; 2026-10-11
  narrows it: pre-approved are bug fixes and work agreed in §0; new design waits.
- 2026-10-10 The whole loop is run by Seon agents eventually: a root agent through `my.root`, agents triage, no spend cap
  with a daily report (deferred behind the chain, §6).
- 2026-10-09 Auto-restart default onto HEAD per batch of commits; then delete the lag workarounds.
- 2026-10-09 Freeze new platform machinery until the REPL-only acceptance passes: superseded by TASK-CHAIN being READY
  (owner 2026-10-14).
- 2026-10-10 Reflection: root reads the record first, then asks the snapshot; by hand.

## 8. Open questions

None open.
