---
type: reference
status: active
tags: [reference, orchestrator, architecture]
---

# The orchestrator's handoff

**Who this file is for — the role rule:** if you were started WITHOUT a
bounded task assignment and you are talking directly to the owner, YOU ARE
THE ORCHESTRATOR, and this is your manual. Everything binding about the
repository — the system model, the design laws, vocabulary, testing,
operating, lane mechanics — lives in [AGENTS.md](../AGENTS.md) and is not
repeated here; read it first if this session is your first. If you were
spawned WITH a bounded assignment, you are a lane: AGENTS.md's opening lane
block is your contract and this file is not for you.

The orchestrator owns user communication, the active roadmap, cross-lane
integration, final design judgment, serial integration gates, pushing the
shared branch at coherent checkpoints, reconciling the issues index at
boundaries, and proof that separately completed work forms one system.
Implementation goes to lanes; your context is scarce — spend it on design,
grounding, review, and integration.

## Session start

1. Run AGENTS.md's session-start hygiene (status, MCP tools answer, foreign
   residue, exhaust sweep with the live-runner guard).
2. Read the working edge:
   [unsettled.md](prds/sci-execution-runtime/plan/unsettled.md) top block,
   then the current dependency spine in
   [the plan README](prds/sci-execution-runtime/plan/README.md). Do not
   restart settled design.
3. **The current entry point is the agent record and turn loop PRD:**
   [agent-record-and-turn-loop-prd-2026-09-07.md](prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md).
   Read it end to end before touching the turn, wake, or agent-record
   surfaces. Apply later rulings over earlier sections: §13 declares one
   render pair per entity schema and data-returning `dir`/`doc`; §14
   stores system turns and refreshes every distinct read form by its latest
   read evidence and the since-query diff, while each agent retains one
   live SCI context receiving base diffs; §15 keeps result objects in memory
   and shown text on disk. Compaction wipes evaluations and regenerates the
   opening. Manual curation, fresh forks per turn, and serialized result
   restoration are superseded. [agent-runtime](seon/architecture/agent-runtime.md)
   contains the additive-context diagrams. Its `plan/
   README.md` ordering and `unsettled.md` working edge remain the one
   ledger of dated state; the PRD is the design, not a second ordering.
4. **Every inherited claim is a hypothesis** — the previous session's
   attributions, counts, and "in flight" lines are what was believed at
   write time. Verify the load-bearing ones with one live command each
   before building on them (PROVEN-LIVE / CLAIMED / UNKNOWN discipline).
5. Present understanding to the owner before launching lanes when the
   inherited plan is nontrivial: your reading in your own words, the first
   moves, and every open decision as priced options.

## If you are coordinating work

Codex orchestrators use the native collaboration/subagent tools; they do not
launch `bin/codex-agent`. Claude orchestrators use the repository's
`bin/codex-agent` harness (run it BARE; mechanics in
[driving-codex-agents](seon/reference/driving-codex-agents.md)). Every lane
names owned paths, protected paths, grounding documents, and one exact
deliverable; never sandbox a lane. Keep one ordered spine and fill every
other safe slot with independent work; review and integrate each return
before building on it; two lanes never edit one mechanism. Supervise on a
≤15-minute cadence by evidence (commits are the heartbeat; logs
selectively), and stop + resume with the correction the minute new
information invalidates a lane's direction.

**Write every lane spec to a file under `tmp/lane-specs/` before launching
it** (one file per lane, named for its assignment) rather than composing the
spec only in the launch command — a spec that exists as a file survives the
launch, is diffable across resumes, and is what a verifier reads to check a
lane against its actual assignment rather than against what the orchestrator
now remembers asking for.

**The gate a lane's own tests must clear is the instrumented gate**: `bin/test`
arming the same contract instrumentation a live cluster arms, not a lighter
fixture-only run. A suite that is green without that instrumentation armed
is not evidence — the 2026-09-08 backlog found 314 reds behind exactly that
gap, most of them fixtures handing a shape the contract forbade rather than
production defects. Confirm the gate a lane ran was the instrumented one
before trusting its "green."

**Lane rules learned this week (2026-09-07/08), binding for every lane spec:**

- **Protected means concurrently edited, not merely related.** Name a path
  protected because another live lane is touching it right now, not because
  it is thematically adjacent to the assignment — an over-broad protected
  list starves a lane of files it actually owns and needs to finish its own
  slice.
- **Verifiers live alongside what they verify**, not in a separate later
  pass bolted on after the fact — a verification script or regression a
  lane writes ships in the same commit as the mechanism it checks.
- **Use the binding lane gate in PRD §10.** Bare `bin/test`, the
  subject's explicit namespaces, and `bin/test --platform` must pass.
  A lane never runs `--all` or `--full`; full suites belong to serial
  orchestrator integration checkpoints. Preserve any stricter stop rule
  in the lane's assignment when concurrent edits block verification.
- **No self-matching pollers.** A lane's background wait loop must never
  `pgrep`/`ps` for a pattern that matches its own polling command — that
  wedges the loop against itself and never exits. Kill your own background
  shells before reporting done; the orchestrator sweeps stragglers at each
  lane completion, but a lane that never exits its own poller is the defect.
- **No adversarial verbs in a lane spec.** Write specs in neutral
  engineering language — verify, falsify, probe, measure — never as an
  attack on another lane's work or a demand to prove someone wrong; a lane
  that refutes its own assignment with evidence has done its job, and
  adversarial framing produces defensive work instead of honest findings.
- **Never revert a shared file.** A lane that finds a shared-tree file in a
  state it didn't expect reports whose in-flight work it looks like and
  waits — it never `git checkout --`/`git reset --hard` a file to "clean
  up" state another lane may still be relying on.

## Working with the owner

Technical, decisive, fast. His corrections are usually architectural rather
than cosmetic, so a correction is information, not a rebuke. He wants to be
hands-on at design gates and will say when to just go. Ask with concrete
options and short trade-offs when genuinely unsure; do not ask permission for
work that obviously follows from what he already said. He would rather you
reset a cluster a hundred times and learn something each time than tiptoe
around it.

He dislikes parallel systems, ported shapes, hand-maintained lists,
stored-derived state, symptom patches, and half-finished surfaces. His standing
question about any change:

> **Is this simpler than it was?**

Complexity is the enemy. His own diagnosis of the previous system's failure
mode: too many separate processes, with bugs and slowness concentrated at the
boundaries between them, and context that was hand-built instead of derived.
Everything built now should reduce boundaries and derive more.

## Handoff details

Whoever hands off a session appends the three things that actually change:

**STATE** — one honest paragraph: the `bin/test` count, the last substantive
landing, whether the tree is red and *whose* in-flight work it is, and the
current checkpoint status. Never claim green on a tree with uncommitted lane
edits.

**IN FLIGHT** — one line per running lane: name and owned paths, so the next
session knows what it may not touch.

**PENDING THE OWNER** — designs awaiting review, outward-facing actions on his
identity, destructive operations, and conversations he asked to have himself.
Being specific here is what keeps a new session from either stalling or
overstepping.

---

*This file is the orientation. If you find yourself pasting orientation
**content** into a prompt instead of pointing here, put it here instead — two
copies of the same guidance drift, and drifted guidance misleads everyone who
reads it.*

## Evergreen orchestration lessons (accumulated; add to this, never reset)

These cost real time to learn. They are about HOW to run this program,
not about any current wave.

**Every inherited number is a hypothesis.** Five recorded figures were
disproven in one evening (2026-08-02), four in storage alone, one by
our own fresh measurement hours later: a census attributed 187 MB to an
attribute whose deletion saved 9.66 MB, because ATTRIBUTION IS NOT A
COUNTERFACTUAL. The next day a derived blob threshold of 343 characters
was refuted by measuring the cost model it had omitted (a ~300x
settlement slowdown). Reproduce before repeating; prove the
counterfactual before promising a saving.

**Read the whole spec; never grep it.** Promoted to a standing AGENTS.md
rule.

**A quiet green proves nothing about a concurrency fix.** A lane once
reported a control-priority fix "passing" with zero lines changed. Only
load-based falsifiers (flood the channel, THEN send the command) catch
that class. Write the falsifier so it fails first, always.

**Lanes over-stop at other lanes' churn.** The most common lane failure
is freezing because unrelated files are dirty. Name owned files
explicitly in the spec and say that other lanes' churn is weather. The
genuine boundary is a hunk YOU need inside a file someone else is
editing — everything else proceeds.

**Shared single files serialize the whole program.** When a file becomes
every lane's contention point, that is a structural cost, not bad luck.

**The fixture path is not the live path.** A fully green suite coexisted
with a broken live boot: schema provenance was stamped on the fixture
path only. Schema, acquisition, and process changes always need the
reset-boundary live proof, and a live cluster should be kept up so every
wave gets an end-to-end drive.

**Independent verification finds what lane reports never do.** Socratic
probes with checkable answers ("store X, read it back — what TYPE
returns?", "kill the JVM mid-Y — what does recovery derive?") caught a
process-global launcher that killed sibling clusters' work, a silent
(first) over an ambiguous schema match, and a non-idempotent
reconciliation. A verifier that can pass by agreeing is worthless.

**Stopping a lane is cheap.** Resume loses only the in-flight turn and
keeps full context. Stop the moment new information invalidates a
direction; never let a lane keep working on a dead premise.

**Commit in slices, always.** Small path-limited commits are what make any
of this reversible, and they are the only honest heartbeat — judge lanes
by git log, never by the process table.

**Rejecting a dependency is not the end of the analysis — mine it.**
Integrant and clj-reload were both evaluated at source and rejected on
measured grounds, and both rejections still paid: Integrant's ecosystem
gave the reloaded-workflow verb set and the queryable-event logging
argument (Duct's own README justifies event keywords over English
prose — our law arriving from outside), and clj-reload gave the
dependency-ordered reload idea, rebuilt natively on program-graph facts
because its `remove-ns` engine severs the Var identity our live SCI
contexts and flow procs depend on. Take the ergonomics, not the
machinery. A rejection that produces no design is usually an
under-read.

**Ask what the analysis already computes before adding a mechanism.**
"Which tests exercise this function?" was unanswerable for months, and
the fix was not a new analyzer — the one clj-kondo pass had computed
the caller set all along and the producer discarded it. Same for
keyword usage, which then dissolved a hand-maintained config ledger
into a query with dead-dial detection for free. Standing rule: index
what the analysis computes; DISCARDING is the decision that needs
justification.

**A count you did not produce is a guess.** Numbers in reports — including
your own from an hour ago — are hypotheses until reproduced.

**Fixture drift looks exactly like a regression.** Two separate
"failures" on 2026-08-03 were hand-built test fixtures that had fallen
behind the live boot shape (a missing render context channel), not
broken running code. Before bisecting, diff the fixture against what
the live path actually constructs — and prefer fixtures that build
themselves from the live shape over ones that hand-assemble it.

**Long-lived JVMs go stale in more than one way.** Three incidents in
one day: a mutated class cache, a stale first-party Var that silently
discarded index data, and soft-reference eviction that PERMANENTLY
loses rarely-executed dynamically-defined classes. When correct code
fails in a long-lived process, suspect the process before the code —
and prefer on-disk, content-addressed caches so eviction is
recoverable rather than fatal.

**Distinguish slow from wedged with a dump before designing anything.**
A boot "failure" that blocked the whole program for a day (P19,
2026-08-05) was a 94-second directory walk being reported as a hang by
a 30-second timeout; one virtual-thread-aware `jcmd` dump named the
exact frame in minutes. The timeout was REPORTING the problem, not
causing it. Corollary found in the same frame: the expensive
computation fed a decision that read none of its output — when
something is slow, first ask what its result is actually FOR.

**The instrument can be the defect.** The two worst scores in the
bootstrap experiment were broken graders, not broken systems: O4
graded delegation against a run that correct behavior CLOSES (working
delegation scored 0/10), and O5 asserted a refusal a ruling had
deleted. When a measurement contradicts a prior live proof, verify the
measurement before redesigning the subject.

**A rename sweep must not rewrite prose that DESCRIBES the rename.**
Blind textual replacement turned "X is deleted in favour of Y" into
"Y is deleted in favour of Y" across three planning documents.
Sentences about a rename keep the old spelling as their historical
referent; verification searches prove cleanliness over code-bearing
surfaces (src, resources, test, scripts, skills, architecture docs),
never over ruling records, ledgers, or research.

**No-migration pays dividends you did not plan.** The program's
scariest open problem — 357 GiB of store needing a data-loss-prone
reclaim — simply evaporated because the rename pass's format/reset had
already destroyed the old store. Reset-and-rebuild is not only safer
than migration; it dissolves whole problem classes that migration
would have had to solve.

**Use vocabulary the model already owns; teach only the differences.**
Datahike's branching maps almost exactly onto git, so the agent-facing
story became git's own words — working tree, checkout, ff-only,
rebase — and the entire mental model costs two sentences of teaching
plus an explicit list of what is absent (no index, no remotes, no
merge). Inventing teachable nouns where a strong prior exists is paying
to create confusion; the O1-inversion datum (teaching hurting a
competent model) points the same direction.

**"Attributes, not enums" applies to functions themselves.** One
conversation chain (2026-08-05): no bare `:type` keys in error values →
`private?` is an attribute, never an execution wall (a schema may
reference a private predicate; ruling #20 restored to full letter) →
public functions are simply the namespace's API, rendered as the
foreign-walk card. The presence of attributes and refs makes a thing
what it is; every wall someone builds from a boolean is the enum
mistake recurring.

**A composition of lock-taking owners needs under-lock arms, and the
regression must run the COMPOSED verb.** `refork!` acquired the
lifecycle lock and called `cleanup-cluster!`, which re-acquired the
same non-reentrant file lock: a live deadlock the sealed spec had
warned about in prose but no test exercised, because every test ran
the parts and none ran the composition. Public entry points acquire
once; internal compositions call `-under-lock!` arms; and the
regression forces the real composed operation on a real store.

**The overnight of 2026-09-17: the mistakes the orchestrator repeated,
and the rule that stops each** (written at the owner's request after a
compaction — "make sure the mistakes the agent kept making are corrected").

1. *Presenting a blanket rule as a ruling request* ("every non-component
   ref is living"). The owner: "Doesn't it depend? DO NOT LOCK US INTO
   BAD PRIOR DECISIONS." Rule: per-attribute research first; come with
   "I've researched this and this is the best solution, with pros and
   cons"; never letter codes; prior rulings are inputs, not constraints.
2. *The questions tool while the owner is away.* It stops the main
   thread. Rule: use it only with the owner present, with full background
   and real rows; overnight, write options into the plan and keep working.
3. *Load.* Nine-plus agents at once put load 161 on 18 cores; three test
   slots with a dozen `bin/test-fast` wrappers queued and batch 116 waiting
   17 minutes for a slot (load 71). Rule: at most four editing lanes; prune
   before adding; research passes are read-only; a cold gate is launched
   only when a slot is free NOW.
4. *Lanes running cold gates and duplicate test JVMs.* Two lanes ran
   forbidden `bin/test`; one ran duplicate JVMs. Now refused by exported
   lane identity (`aba5d94a5`); Claude subagents still need the
   instruction in their brief until hook-side admission lands.
5. *Gate invocation mistakes.* `bin/test --platform` with named namespaces
   exits 64 (a named selection takes no tier flag): run the platform tier
   and the named list as two invocations. zsh does not word-split `$ns`:
   write the namespaces out, and verify each maps to a file first.
6. *Stopping a background shell that had launched lanes in a loop* killed
   a live codex lane. Rule: one lane launch per shell, never a loop;
   `stop` before `resume`; verify the pid is gone.
7. *Trusting a lane's printed session id.* `resume` picked a fake id from
   stdout. Rule: the launcher record is the identity (`e1d2347f4`
   backfills); never quote transcript text.
8. *Agents ending their turn with a run in flight.* Rule: foreground runs
   with a long timeout in the brief; orphan gates are announced by the
   slot wait.
9. *Assuming a hook config took effect.* Codex hooks were silently OFF
   for every lane after `.codex/hooks.json` was rewritten (trust snapshot
   mismatch). Rule: the launcher passes
   `--dangerously-bypass-hook-trust`; after any hook change, stop and
   resume every codex lane; verify with hook log lines, never by assuming.
10. *Reset that lied.* The first ordered reset waited on a dead lock
    holder, died on a half-written `db.clj`, left 4.6 GB, exited 0. Rule:
    a reset preflights syntax over changed files and lock-holder liveness,
    refuses in seconds, exits non-zero, ends in an adopted cluster
    (`c4d1be3ac`, `5cd10e8d4`); lint every dirty file before any restart.
11. *My own "same-commit vocabulary rows" rule blocked a lane* because
    AGENTS.md was held by another lane. Rule: commit the code; rows land in
    a follow-up when the file frees.
12. *Serialized adoptions past the lock hold* left default without its 68
    required config facts. Rule: one `init --dev` at a time; a lock-hold
    timeout inside reconciliation is a blocker to report the minute it is
    seen, not a symptom to work around.

## The orchestrator's sweep (owner, 2026-09-08)

"You are the orchestrator; you need to be finding problems and fixing them
and restarting agents and improving the docs." Between lane reports, run
this sweep and act on what it finds — never wait idle for a landing:

1. `bin/codex-agent status`; tail each lane's `tmp/orchestrator/<lane>-stdout.log`
   (bounded); a lane whose log is stale for 15 minutes or that stopped at an
   obstacle gets stopped and resumed with the obstacle named as a root
   cause to fix.
2. `curl` the debug page on `default` and read `data/clusters/default/logs/seon.log`
   for `ERROR`; a 500 is assigned within the minute to the lane whose
   in-flight file caused it, with the log line quoted.
3. `ps` for JVMs and `bin/test` roots: a gate that is not one namespace per
   worker, a poller, a stale worktree or scratch root is swept.
4. `git status`: uncommitted files older than an hour are a stopped lane's
   or a stuck one's — decide which and act.
5. Every measured problem becomes either a fix you land yourself (small,
   root cause, path-limited) or a lane launched BARE and tracked, with the
   measurement in its brief; every lesson lands in AGENTS.md, this file, or
   the PRD's lane rules in the same beat.

Launch and resume lanes bare and tracked (no shell redirect, no `&`); stop
a lane before resuming it; `bin/codex-agent watch` only for a lane that was
launched headless.

