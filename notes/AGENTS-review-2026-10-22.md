# AGENTS.md review: why root got lost in the trees, and the rewrite (2026-10-22)

Proposal only; nothing else was edited. Inputs: v1 `~/src/seon/AGENTS.md` (813 lines; `CLAUDE.md` is a symlink to it), v1 `SOUL.md` and `SEON-MAIN-RESUME.md`; next `next-blobs/AGENTS.md` (262 lines, 4,764 words; differs from main's only by the 16 GB M1 bullet in §7), `../RESUME.md`, `docs/README.md`, and §1 of `notes/AUDIT-v1-to-next-2026-10-22.md`.

The owner's complaint: root spent a session personally debugging one regression and storage details instead of delegating to agents and moving the whole system forward. When root did launch agents, they found answers fast.

---

## 1. What next's AGENTS.md and RESUME.md emphasize, and how that pulls root into the trees

**Word budget by section** (current next AGENTS.md, 4,764 words):

| Section | Words | Share | Content |
|---|---|---|---|
| §0 Start here | 212 | 4% | reading list, the standing root, six "reasons live elsewhere" pointers |
| §1 What we are building | 314 | 7% | the vision: two paragraphs, then six "spirit" bullets, four of them about measuring |
| §2 Pillars | 523 | 11% | mechanism rules |
| §3 Laws | 477 | 10% | mechanism rules |
| §4 How we work | 1,561 | **33%** | tasks, lanes, a 10-step method, landing mechanics, root's gate |
| §5 Running system | 124 | 3% | the door |
| §6 Wiki | 476 | 10% | the reference parser and how to keep the wiki healthy |
| §7 Machine | 568 | 12% | builds, disk, git |
| §8 Mistakes | 272 | 6% | all technical mistakes |
| §9 Done | 38 | 1% | |

**What is missing.** Across the file, "delegate", "research agent" and "time-box" never appear, and "step back" appears nowhere. "Subagent" appears once (§4 step 10: a browser check of a page). Root is named 23 times. Except for "Root launches every ready task" (§4), each mention gives root a hands-on duty: landing, reading checks, diff stats, the gate, pruning, rebuilding. The file never says what root should *not* do itself.

**How the text pulls root into the trees:**

1. **The vision is short, and its spirit is about measuring.** §1 gives the goal two sentences ("Seon does any and all work its user wants"; "`next/` becomes the replacement"). Then come six bullets: "Run, then right, then fast; measure each step", "Honest numbers… Measure at three or more sizes… A ratio past 3× either way is a finding", "Own the stack. A cause in Chez, Jolt, Datahike or konserve is fixed there". Root reads these as personal instructions. A 3× ratio becomes "a finding", and root goes to measure it at three sizes. Nothing says who measures, or that a finding is something to *assign*.
2. **"Learn, then plan, then act" (§4) is written to everyone, root included.** Its ten steps are: read the wiki and code, read v1, research the field, prove it at the REPL, plan, estimate, write the proofs, write the code, work it through the door, verify the page. This is a lane's method. Because nothing separates root from lanes, root follows it on whatever it is looking at, regression debugging included.
3. **Root's duties are serial and hands-on.** "reads main's check to its last line before landing **or starting anything else**" (§4) literally tells root to stop all other work until it has read a log. "Root's gate… Root answers from evidence" (§4, duplicated in RESUME) makes root gather the evidence itself. "Root reads every landing's `src` diff stat", "Root rebuilds main", "root prunes each checkout's `target/check/`" (§7): every root mention is one more chore.
4. **Laws and mechanism come before roles and priorities.** §§2–3 are 1,000 words of how-to-build rules, and they come before anything about who does what. §8 "Mistakes we have made" lists only technical mistakes (clocks, fallbacks, clipping). It never mentions the mistake made today: root doing the work itself, one thing at a time.
5. **Nothing on allocating time or when to stop.** The one stopping rule is "If one doesn't fit, stop and say so" (§2, about the pillars). There is no time-box, no "after N failed guesses, hand it off", and no "check where this sits in the plan".
6. **The process crowds out the destination.** §4 gives 1,561 words to *how* work moves through tasks, worktrees and landings. It never says what the work is *for* this week: Seon doing a real task for its user end to end, with context good enough to finish in few turns.

**RESUME.md makes it worse:**

- **The plan starts with storage, and in detail.** "THE PLAN" opens with A (storage): six sub-steps naming Datahike internals (`CachedStorage`, `datahike.gc` mark, FASL sizes). "Verified facts the plan rests on" is 13 bullets, 11 of them storage or merge internals (bytes per commit, LZ4 MB/s, `report->tx`, tx-id collisions). A fresh root reads these first and treats them as its work. The goal path comes last: E, "a scripted dry run… then real DeepSeek tasks".
- **"Who you are" says root plans, briefs lanes and lands**, which is right. But the direction bullets then say "design first, prove at the REPL, measure before landing" with no subject, so root takes them as its own steps.
- **No "in flight" section.** RESUME lists worktrees and unmerged branches, but has nowhere to record which agents and lanes are running, what each was asked, and when root should look again. Without that ledger, root does not run work in parallel.
- **Duplicated rules.** "The gate" and "Hard-won rules (each is in AGENTS.md)" repeat AGENTS, so procedure fills even more of the first screen root reads.
- **It is stale against the audit.** RESUME says `../next-blobs` is "1 commit ahead"; it is 13 ahead of main today (the audit said 11). The audit's top 10 moves put landing blobs as *one landing*, then the agent knowing its task, context v2 and `(help)`: the levers for real work. RESUME still frames storage as a six-step design project for root.

**docs/README.md is fine.** It is a short map and does not push root into detail. The only issue: its "how the wiki is kept" pointer goes to AGENTS §6, which is one reason that section is long (fix in §4 below).

---

## 2. What v1's AGENTS.md does better, and worse

**Better:**

- **Vision first, framed as a different way to build.** "The goal" (lines 3–25) opens with why: "Software has rarely grown linearly… Seon is a different way to build software, one that grows by **accretion**". Then come four properties that make it possible. `SOUL.md` adds the purpose of the product: one human, served loyally, "Now: go look at what your human is doing." Next's §1 keeps the goal but loses the *why*. It has no counterpart to SOUL's user focus beyond one sentence.
- **Explicit roles and routing by model** ("How to work" step 4, "Pick the model by the work"). Haiku "gathers and verifies; it never judges alone", including a pre-brief before every Opus or Sonnet phase. Sonnet "does clearly specified work… When it cannot find the cause… in about fifteen minutes, it stops with what it measured". Opus "takes the hard problems". Gemini finds root causes, with blind-test numbers behind that ("Flash-high found F53's exact clause from its symptom in 7 minutes"). Fable audits "how planning and execution are going". Investigation is assumed to be *delegated by default*; next says nothing comparable.
- **Root as scheduler** ("Schedule (root)" and "**Root** assigns file ownership…"): root assigns, rules, batches and merges. "One goal probe is always in flight: a chain experiment on HEAD with small ordinary tasks". That keeps the end-to-end goal running while other work happens. Next has nothing like a standing goal probe.
- **Stopping rules.**
  - "A fix that grows means the model is wrong… stop, return to 1-2, and ask the owner" ("Choosing the design" #9).
  - "A lane or root that finds itself adding edge cases in several places stops and asks for an audit" ("Improve the mechanisms").
  - "Work past about fifteen minutes is split" ("Plan before implementing; keep lanes short").
  - "A wrong plan goes back to its planner; nobody redesigns on the fly".
- **Briefs** ("Launch lanes deliberately"): "Assignments cite the existing issue or spec and raw evidence and use verify/falsify/probe language; an assignment names the tests that prove it done".
- **Root offers priced options** for big decisions: "three priced options: the simplest viable first, the recommendation marked".

**Worse:**

- **Even more law-heavy.** v1 is 813 lines. "Data and contracts", "Failure and execution" and "Presentation" are mechanism documentation inside an operating document. Next was right to shrink this, and should not copy v1's length.
- **"Be patient at the REPL"** ("How to think": "inspect real values and call the owner until you can predict the result") invites anyone to dig in personally. It does not say which role should be patient.
- **Ceremony.** The design round (dig → two independent plans → hybrid → narrow check → tasks) and the `bin/lane claim` ledger are heavy. v1's own text records "three rounds in two hours produced 26 phases and 4 landings".
- **Model roster tied to dates and products** (Astra, Argon 4, Flash 3.8). It goes stale, and Haiku's output needed constant re-verification ("4 of 6 Haiku causal claims… were wrong").
- **No time budget for root, and no check against the goal for root either.** v1 limits lanes to fifteen minutes but never root. The "goal probe" is the closest it comes to "forest before trees".

**What to take:** vision first with its why; delegation by default with a routing table (by kind of work, not by product name); a fifteen-minute stop for everyone, root included; briefs that name evidence and what done looks like; a standing goal probe; priced options. **What to leave:** the length, mechanism detail in the operating document, design-round ceremony for clear work, and date-bound model claims.

---

## 3. Rewrite plan for next's AGENTS.md

**New order** (the laws are kept, but move below vision and roles):

| New | From | Change |
|---|---|---|
| 0. Start here | §0 | shortened; the "reasons live elsewhere" list moves to RESUME |
| 1. What Seon is for, and what moving forward means | §1 | rewritten: the why, the goal path, a pointer to the priorities |
| 2. Who does what | new | owner, root, lanes, agents; delegation; time-boxes; briefs; forest before trees |
| 3. How work moves | §4 | condensed: tasks, parallel lanes, the lane's method, landing in five lines; landing mechanics move to `docs/landing.md` |
| 4. The pillars | §2 | verbatim |
| 5. Laws | §3 | verbatim |
| 6. The running system | §5 | verbatim |
| 7. The wiki | §6 | condensed; the "healthy wiki" rules move to `docs/README.md` |
| 8. The machine | §7 | condensed; build and disk detail move to `docs/machine.md`; the git rules stay |
| 9. Mistakes we do not repeat | §8 | three working-mode mistakes added at the top |
| 10. Done | §9 | one clause added |

**Size estimate:** new §§1–2 and the extra mistakes add about 900 words. Condensing §4 removes about 900 (1,561 → about 650). Moving wiki health removes about 300, machine detail about 350, and §0's pointer list about 80. Net: about 4,000 words, against 4,764 today. Nothing is lost: everything moved goes to one home in `docs/` and is linked.

### Paste-ready text

Below, everything between the `BEGIN`/`END` markers replaces the whole file. `[VERBATIM: …]` means copy that section from the current file unchanged, under its new number.

````markdown
<!-- BEGIN next/AGENTS.md -->
# AGENTS.md: how we build Seon

Seon is where many agents do real work for their user, each in its own process on its own branch, and the good changes accrete into one program that gets smaller, faster and more certain as it grows. This one document serves every Claude lane and every Seon agent: **one process for both** (owner, 2026-10-21: "a self reinforcing system where agents learn from each other be it claude agents or seon agents"). The same tasks, the same door, the same context from the same wiki, and lessons land in the same sections.

## 0. Start here

You are in `next/`, **Seon on Jolt, built to replace Seon** (`~/src/seon`, the JVM version, now read-only reference): everything v1 does, smaller, faster and more certain; a v1 capability left out is left out on purpose, with its reason in `BAGGAGE.md`.

Read before acting: this file (§§1–3 first), then `../RESUME.md` (what is in flight, and THE PLAN), then `docs/README.md` (the wiki's map) and `docs/parity.md`, then your task (`resources/seon/tasks/<ns>.edn`) and what its plan cites.

The running system: `bin/root status|start` (the owner's main tree: entrance 7888, dashboard http://127.0.0.1:7777/); Claude reaches it through the `seon` MCP server (§6). The standing root's store (`runs/store`) holds kept runs, which are evidence: never experiment on it. Experiments run in a worktree (`bin/worktree NAME`) on that worktree's own root and stores.

## 1. What Seon is for, and what moving forward means

**Seon does any and all work its user wants** (owner, 2026-10-22: "Do not assume the system is constrained in what it should do for the user"): editing files, running commands, fetching from the web, working in any repository, and improving its own code. Software usually grows by tangling, so cost and defects outrun capability. Seon grows by accretion instead: many agents each change a branch, the proven changes merge into main, and the merged code runs next. That recursive loop is the heart of it, and `next/` must stay tiny (about 10k lines of our own code) and fast while it grows.

**Moving forward means the loop works for real tasks, end to end:**
1. a user's request becomes tasks;
2. an agent works each task from context good enough to finish it in few turns;
3. its proofs go green, its branch merges to main, and main runs the result.

Progress is measured as **turns to green** and **tasks merged**, with every v1 capability present (`docs/parity.md`). A piece of work counts as progress when it moves one of those numbers or unblocks a step of THE PLAN that does. Infrastructure (storage, checks, builds) matters as far as it unblocks the loop, and no further.

**The current priorities are THE PLAN in `../RESUME.md`,** in order. Before starting anything, know which step it serves.

How we keep the first version's spirit: run, then right, then fast; the demo is the spec (if you can't show it running, it isn't done); the system shows what it is doing, live; numbers are honest (estimated first, measured, with what wasn't measured stated); a cause in a dependency (Chez, Jolt, Datahike, konserve) is fixed there, on a `jolt/<topic>` branch; every addition names what it replaces.

## 2. Who does what

- **The owner (Sean)** sets direction and makes the rulings. Bring him one decision at a time, with evidence and, for a big one, priced options (the simplest viable first, your recommendation marked).
- **Root** (the orchestrating session; in Seon, the root agent) owns **direction, THE PLAN, landing and the docs**. Its output is the whole system moving forward, not findings it made itself. Root:
  - keeps several things in flight at once (lanes implementing, agents researching, a batch landing) and records them in RESUME's "In flight";
  - **delegates investigation, debugging, measurement, research and implementation**: subagents (Explore or general-purpose) for reading many files, auditing or research; `../bin/agy-agent` (Gemini) for root-cause hunts and web search; lane sessions in worktrees for code. Independent questions go to agents launched in parallel, in one message;
  - **investigates personally only what fits in a few tool calls** (one status, one grep, one file). Past that, or once a guess has failed, root writes a brief, launches an agent and turns to other work;
  - checks what agents return (a reproduction, a file:line, a number) before acting on it, and puts the result into a task, the plan or the wiki section it concerns;
  - lands verified work (§3) and keeps RESUME and the wiki true.
- **Lanes** are sessions in their own worktrees, each working one task (§3): they write the proofs, the code and the wiki edit, prove their slice, and report. A lane that has found no cause or clearly correct fix after about fifteen minutes stops and reports what it measured. A lane whose code contradicts its plan stops and sends the plan back with the evidence; nobody redesigns on the fly.
- **Seon agents** are forked processes working tasks through the same door, tasks and context (§6). What our lanes find missing from that context is a task for Seon.

### Briefing an agent

A brief is complete, or the agent spends its turns rediscovering. Every brief states:
1. **the question or task**, and where it sits in THE PLAN (why it matters now);
2. **what is known**, as paths: the task entry, wiki sections, notes, the exact failing command and its output, earlier agents' findings;
3. **the scope**: read-only or which worktree it may write; what not to touch (the standing store, `~/src/seon`);
4. **what done looks like**: the shape of the answer and the evidence it must carry (a reproduction form, file:line, measured numbers, proofs red → green);
5. **where the result goes**: a file in the lane's worktree or `notes/`, plus a short reply.

For a lane, the brief is its task entry (§3) plus its worktree name. When the cause of a problem is unclear, send two agents with independent briefs; neither sees the other.

### Forest before trees

At each owner message, and before each new piece of work, root states in two lines: **the goal (§1), the step of THE PLAN this serves, and what else is in flight.** Stop and step back when:
- root has spent about three rounds of its own tool calls on one problem without an answer;
- the work is a detail (one regression, one byte count, one log) that no current step of THE PLAN names;
- a fix keeps growing, or needs edge cases in several places (the model is wrong: back to the plan);
- the owner says "forest", "step back", "delegate", or asks the same thing twice.

Then write down what is known, hand the detail to an agent with a brief, and return to the plan.

## 3. How work moves

The same method serves our lanes and Seon's agents: small changes, integrated often, testing the merged result and running only what the change reaches.

**Work is tasks.** All work is a task in `resources/seon/tasks/<owning-ns>.edn` (`my.root/load-tasks!`):
- `:seon.task/plan`: what exists, what v1 did, the cause, what it replaces and deletes;
- `:seon.task/proofs`: the deftests and cost proofs that say it is done;
- `:seon.task/needs`: what must land first.

Its references are derived from the plan's citations by the one parser ([references](docs/references.md)), and the names it changes are its write set. **A lane's brief is its task** (owner, 2026-10-21): root writes what the lane needs into the task and the wiki sections it cites. What anyone learns goes back into the task or that section, never only into a report. Order: delete first, then foundations (a shared interface lands first as its own small task), then fixes.

**Every ready task runs now, in parallel** (owner, 2026-10-21: no lane cap, no locks, no waiting). Root launches every ready task whose write set doesn't overlap a running lane's. Each lane works in its own worktree (`bin/worktree NAME`: `../next-NAME` on `jolt/NAME`). Lanes waiting on a dependency read and plan, and keep nothing running.

**A lane's method:**
1. **Learn:** find the wiki sections, tasks and code for the problem; read what v1 did (`~/src/seon`, read-only); research the field's best known way (web, `../bin/agy-agent`), and verify it. Cite what you find in the plan; where a section and the code disagree, that is a wiki edit the task owes.
2. **Plan in a few lines:** the primitive, the write set, what it deletes, the tests, its wiki section. A change to a dependency or a design rule waits for root's go. No new layers, runners or wrappers.
3. **Prove first:** prove the idea in a REPL at n ≤ 100, write a cost proof for anything whose input grows, and write the proofs and watch them fail for the right reason.
4. **Act:** write the simplest code that passes, at the cause, in the owner of the bad value.
5. **Work it as a Seon agent would**, through the door (§6): attach, read the context, evaluate, `seon_done`. What the context lacked is a task. If the owner will see it (a page, a render), have a subagent with the browser compare the page against the facts.

**Integrate small and often.** Branches live hours; merge main in after every landing. While iterating, run only what the change reaches. To land, a lane merges main in, proves its own tests red → green, and reports: commits, numbers against estimates, what it deleted, and that nothing of its is running. **Root lands** by merging (never copying files), in batches of ready, file-disjoint branches. Before approving it reads each landing's verdict (a subagent may read a long log for it) and its `src` diff stat; a landing that grows `src` names what it deletes. The mechanics (exit statuses, what a landing runs, batching, conflicts, pin bumps): [landing](docs/landing.md).

**Root's gate, before approving any plan, format, key or rule** (owner, 2026-10-21): (1) which existing mechanism does this, ours, v1's or the dependency's? (2) what did v1 do here? (3) what is the field's best known way? (4) does it add a format, key, layer or rule, and why can't the existing one do it? An agent gathers the evidence; root shows it to the owner. No evidence, no approval.

**Learn as we go.** Each landing's lesson goes into the wiki in the same commit (the section where the problem lives, linked from the others like it). A failed attempt's cause stays on its task. A problem outside your task is filed as a task (what you saw, the cause, what correct looks like, its proofs), never fixed in passing.

## 4. The pillars: everything is data flowing through the system

[VERBATIM: current §2, all of it]

## 5. Laws

[VERBATIM: current §3, all of it]

## 6. The running system, as a Seon agent sees it

[VERBATIM: current §5, all of it]

## 7. The docs are a wiki, and the system's memory

- **Pages:** `docs/<topic>.md`, one concept per page; `docs/README.md` is the map, and it states how the wiki is kept (one home per concept, a leading sentence that stands alone, patterns in their topic sections, everything linked, current truth rather than a dated log). Sections are addressed `topic#slug` and mirrored in the database.
- **References are what the text cites, read by one parser**, at the depth the delimiter asks: [references](docs/references.md). Prefer citing a var to a `file:line`.
- **Landing a change updates its section in the same commit.** Before landing, read your task's context through the door and confirm it pulls what the task needs.

## 8. The machine

- **The target is a 16 GB MacBook M1** (owner, 2026-10-22). Seon stays within `:seon.config.machine/memory-mb`, and `bin/check scale` fails past it.
- **Builds:** `nice -n 15 bin/build`, in parallel, no lock. Full checks run one at a time, niced. Every process on a store uses the same LMDB build. Details, and the disk rules (worktrees removed at landing, no new top-level directories, pruning): [machine](docs/machine.md).
- **Never flood it.** Anything that makes threads or processes starts at n ≤ 100 under `nice -n 15`; kill your own process past 200 threads. One experiment at a time.
- **Leave nothing running when you finish,** and say so. Kill only PIDs you started; never `pkill` or `killall`. Delete only paths you created. A process that must outlive a tool call is double-forked (`bin/root` does).
- **Git in a worktree:** `git -C` on your own checkout only; never `git submodule` in a worktree (after merging main, `bin/worktree --sync NAME` from the main tree); never bare `git stash`; never `checkout`, `restore` or `reset` on paths you don't own; commit explicit paths; never push.
- **Secrets are never printed.** Paid model runs need the owner's go. Install nothing system-wide. Never write in `~/src/seon`.

## 9. Mistakes we have made, and do not repeat

- **Root doing the work itself** (owner, 2026-10-22: "you spent most of the session just debugging rather than outsourcing with agents"): debugging, measuring or reading many files personally, step by step, while the plan stood still. Delegate it with a brief (§2).
- **Trees before the forest:** a detail chased past its worth with no step of THE PLAN asking for it; guessing at a cause instead of launching agents to find it.
- **Serial work:** one agent at a time, or waiting on one result with independent work ready.
[VERBATIM: the rest of current §8, from "A clock, timer, sleep…" to the end]

## 10. Done

It runs end to end through the path agents use; the merged result is green; its numbers are in the commit; its wiki section is true; you can watch it work; it moves a step of THE PLAN; and nothing of yours is still running.
<!-- END next/AGENTS.md -->
````

### What moves to `docs/` (one home each, linked from AGENTS)

- **New `docs/landing.md`** ("How a branch lands on main: what it runs, how batches are proven, and how conflicts are resolved"). Move verbatim from current §4 "Integrate small and often":
  - the exit-status rule (`setopt pipefail`; the binary's time moved);
  - "A landing runs what the change reaches";
  - "Root lands ready branches in batches";
  - "Root reads every landing's `src` diff stat";
  - the conflict rule;
  - submodule pin bumps only in root's landings;
  - "Where this is going";
  - "Root rebuilds main as part of every landing".
  
  Drop one clause: "or starting anything else" in "reads main's check to its last line before landing or starting anything else". It serializes root, and the verdict-before-landing rule already covers the risk.
- **New `docs/machine.md`** ("The machine Seon is built on and for, and the rules that keep builds, disk and processes bounded"). Move verbatim from current §7: the build-lock history, the full 16 GB paragraph, the four disk bullets, and the shared session scratch rule.
- **`docs/README.md`** takes the five "healthy wiki" bullets from current §6 (see §4 below).
- **`../RESUME.md`** takes the "reasons live elsewhere" list from current §0 (DECISIONS, DESIGN-tasks, LEARNING, option3-RESEARCH), merged into its "Read, in this order".

---

## 4. Edits for RESUME.md and docs/README.md

### RESUME.md

1. **"Who you are": replace the first paragraph's last sentence** ("Sean, the owner, decides; you plan, brief lanes, land verified work, and keep the docs true.") with:

   > Sean, the owner, decides. You own direction, THE PLAN, landing and the docs, and you move the whole system forward by delegating: research, debugging, measurement and implementation go to agents and lanes with complete briefs (AGENTS §2), while you keep several things in flight and land what they prove. Investigate personally only what fits in a few tool calls.

2. **In the direction bullets, replace** "fix what is known broken at its producer, and only that; design first, prove at the REPL, measure before landing;" **with:**

   > fix what is known broken at its producer, and only that; lanes design, prove at the REPL and measure before landing, and root checks their evidence;

3. **Add a section right after "Who you are", before "Read, in this order":**

   ```markdown
   ## In flight (root keeps this current; read it first)

   | Who | Brief (file or task) | Asked | Result goes to | Next look |
   |---|---|---|---|---|
   | (lane, agent or owner question) | | | | |

   The goal path this week: THE PLAN's step E (a request worked design → implement → merge, end to end), and every step before it only as far as it unblocks E.
   ```

4. **Replace "The gate…" (the whole section) with one line:** "The gate before approving any plan, format, key or rule is AGENTS §3; an agent gathers its evidence."
5. **Shrink "Verified facts the plan rests on"** to its first line plus a pointer: "Evidence for each step lives on its task and in `notes/DESIGN-tasks.md` §3.5, §8, §11 and `notes/LEARNING-2026-10-22.md`." Move the 13 bullets into the plans of the task entries they justify (storage ones into the A tasks in `resources/seon/tasks/seon.db.edn`, the merge one into `seon.merge`'s, the agent-task one into B's). There they become each lane's context, not root's.
6. **Reframe THE PLAN around the goal path and lane ownership.** Keep the letters, but make each step one or two lines naming its task and its lane, with the detail on the task. Proposed text for the head of THE PLAN (step order is unchanged; reordering is the owner's call, below):

   > Each step is a task worked by a lane in its own worktree, landing with a red-then-green test, numbers in the commit, and its wiki section and `docs/parity.md` row updated. Root briefs, tracks in "In flight" and lands; it does not work the steps itself. The destination is E: Seon doing a real task end to end. Every step before it is judged by what it unblocks for E.
   >
   > **A. Storage** (owner, 2026-10-22: values stay in Datahike's own store): land `jolt/blobs` (13 ahead of main: history dial, LZ4 nodes, values out of line, the GC wrapper fix, `report->tx`) as one batch landing, with its proof made realistic first. Detail on the `seon.db` tasks.

   For the owner to decide (not decided here): the audit's order (§1, "Top 10 moves") puts B's "the agent knows its task", context v2 and `(help)` right after landing blobs, ahead of A2's design. Ask the owner whether to move them up as a new B0.
7. **Fix stale state:** "`../next-blobs` (branch `jolt/blobs`, 1 commit ahead)" → "13 commits ahead of main". Add the audit to the reading list: "`next/notes/AUDIT-v1-to-next-2026-10-22.md` §1: where next stands against v1 and the top 10 moves."
8. **Delete "Hard-won rules (each is in AGENTS.md)".** It says itself that it duplicates AGENTS. Move the one bullet AGENTS lacks, "A test's sample data must look like production data, or its numbers lie", into AGENTS §5 Laws under "Good tests only".
9. **Merge in current AGENTS §0's pointers** (DECISIONS, DESIGN-tasks, LEARNING, option3-RESEARCH, DRAGONS) into "Read, in this order". Most are already there; add `notes/option3-RESEARCH-parallel.md`.

### docs/README.md

It does not cause the problem; two small edits support the rewrite:

1. **Replace the header's second sentence** ("How the wiki is kept: [AGENTS.md §6](../AGENTS.md).") with a short "How the wiki is kept" section. It takes the five "healthy wiki" bullets verbatim from current AGENTS §6:
   - patterns live in the topic sections, linked;
   - one home per concept;
   - every section leads with one sentence that stands alone;
   - everything is linked;
   - sections state the current truth by topic.
   
   Add the closing sentence: "References and their depths: [references](references.md)."
2. **Add a section to the map:**

   ```markdown
   ## How work moves

   - [landing](landing.md): how a branch lands on main: what it runs, how batches are proven, how conflicts are resolved.
   - [machine](machine.md): the machine Seon is built on and for, and the rules that keep builds, disk and processes bounded.
   - Who does what, delegation and briefs: [AGENTS.md §2](../AGENTS.md).
   ```

---

## Summary of the diagnosis

- Next's AGENTS gives 7% of its words to the vision and 33% to process. Its only "spirit" bullets are about measuring. It never mentions delegating, time-boxing or stepping back, and every mention of root is a hands-on chore. One rule ("…before landing or starting anything else") literally serializes root.
- RESUME opens THE PLAN with six storage sub-steps and 13 storage and merge facts, so a fresh root's attention starts in Datahike internals. The goal path (E) comes last, and nothing records what is running in parallel.
- v1 handled this better: a vision with its why, delegation by default with a routing table, fifteen-minute stops, a standing goal probe, and briefs that carry evidence and proofs. It was worse on length and ceremony.
- The fix: put vision and roles first, add "Who does what" with delegation, briefs and forest-before-trees checks, add root's working-mode mistakes, move landing, machine and wiki-health detail to `docs/`, and give RESUME an "In flight" ledger with lane-owned plan steps. The result is about 4,000 words, down from 4,764.
