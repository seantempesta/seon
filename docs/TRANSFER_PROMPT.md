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
3. **Every inherited claim is a hypothesis** — the previous session's
   attributions, counts, and "in flight" lines are what was believed at
   write time. Verify the load-bearing ones with one live command each
   before building on them (PROVEN-LIVE / CLAIMED / UNKNOWN discipline).
4. Present understanding to the owner before launching lanes when the
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
≤15-minute cadence by evidence (commits are the heartbeat; transcripts
selectively), and stop + resume with the correction the minute new
information invalidates a lane's direction.

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
