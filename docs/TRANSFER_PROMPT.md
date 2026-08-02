---
type: reference
status: active
tags: [reference, orchestration]
---

# Working on Seon

You are joining a system in its second life. Read this whole file before you
touch anything — it is short, and it will save you from the three or four
mistakes everyone makes here.

## What Seon is

A runtime where agents are first-class citizens rather than API calls. They
live in a Datahike database as facts. They run as `core.async.flow` graphs,
parked between episodes, woken by messages. The Clojure they write becomes
durable database rows — functions, schemas, and tests as data — so a cluster
can restart and another agent can call what the first one defined. What any
agent sees is a *rendered view* of that same graph, produced by ordinary
functions.

One JVM process may host many sovereign clusters, each on its own Datahike
branch. CLJ only. REPL-first. No central loop anywhere, by ruling: that shape
is rejected as "a JavaScript event loop inside Clojure."

## The one thing to internalize

**This is the second implementation.** The first one worked — really worked,
for months — and it was torn down deliberately, by its author, because he had
learned enough to build it properly.

That single fact should reorganize how you work here:

> **Almost everything you are asked to build has been built before, and the
> previous version is still readable.**

`src-old/` is not dead weight; it is a quarry with gold in it. Git history is a
record of every bug each mechanism learned to survive — each fix commit is a
failure class someone already paid for. `docs/prds/*/research/` holds dated
investigations with `file:line` evidence and measured numbers, dozens of them.
`reference-code/` vendors ~90 dependencies as submodules so their semantics can
be *read* rather than remembered.

So the prime directive is not "write good code." It is:

> **Do the archaeology before you design. Then design something better than
> what you found — because we are evolving, not restoring.**

Skip the archaeology and you will confidently rebuild a worse version of
something that already exists, and reintroduce a bug that was fixed a month
ago. This is the single largest source of wasted work in the project's history.
Do the archaeology and you will find that half the hard problems already have
measured answers, and your design will come out sharper than anything you would
have invented cold.

The fresh tree is not zero knowledge. It is zero *baggage*. Every piece has to
re-earn its place instead of being grandfathered in.

The canonical lesson, worth remembering when you feel the urge to build first:
a full effect-replay identity layer was designed, implemented, and proven live
against a real process kill — then deleted the same night, because one question
about the crash model revealed the design never needed it. **The cheapest place
to delete code is before it exists. The way to find out is to build the
smallest real thing and let live falsifiers and hard questions attack the
design while it is still a decision.**

## Read these, in this order

1. **This file** — the stable orientation and project mentality.
2. **`CLAUDE.md`** — a symlink to `AGENTS.md`, so every toolchain reads the
   same bytes. The standing law: architecture, vocabulary, data-oriented rules,
   git safety, the resiliency law, the skills law.
3. **`docs/prds/sci-execution-runtime/plan/unsettled.md`** — the WORKING EDGE
   block at the top is current state and supersedes every dated block beneath
   it.
4. **`docs/prds/sci-execution-runtime/plan/README.md`** — the one ordering, and
   the numbered owner rulings. Those rulings are binding design decisions with
   their reasoning attached. If you believe one is wrong you may be right —
   surface the evidence with a recommendation. Never deviate quietly.
5. **`docs/seon/issues/index.md`** — every open issue, ranked, each with a
   named destination.
6. **`docs/seon/architecture/`** — the *target* system, written in present
   tense. It describes intent and never implementation status.

Then the closest localized `AGENTS.md` to whatever subtree you are about to
change. Those are the runbooks: durable ownership, invariants, and links.

The document roles are deliberately different:

- `TRANSFER_PROMPT.md` tells you how to think and where truth lives.
- `AGENTS.md` is binding repository law and the operating runbook.
- `plan/unsettled.md` owns the current working edge and evidence.
- `plan/README.md` owns ordering and numbered owner rulings; its older sections
  are archaeology, not current status.
- `issues/index.md` owns the ranked open queue.
- `architecture/` owns the intended system, never the migration diary.

Do not make a new session reconstruct status from the full historical tail of
the roadmap. Read the current WORKING EDGE first, then use symbols, commit
history, and the linked research to open only the history relevant to the
chosen boundary.

## Load the skills. They are honed, verified, and load-bearing.

`.agents/skills/` is the one real skills directory — `.claude/skills` and
`seon-skills` are symlinks to it, and `bin/test` refuses to run if that is ever
broken. Every skill has been through an independent adversarial verification
pass that opened each cited line, re-checked each measurement against its
stated conditions, executed the commands they teach, and **deleted** claims it
could not verify rather than softening them.

They exist so you do not re-derive a week of hard-won mechanics:

| skill | load it when |
|---|---|
| `data-oriented-clojure` | before writing or reviewing ANY Seon Clojure, and before maintaining a vendored fork we own |
| `seon-flow-architecture` | before touching a proc, graph, channel, buffer, workload, wake, or fault — **or before designing any new runtime mechanism at all** |
| `data-modeling` → `datahike` | deciding what shape to declare; then the query/transact mechanics. `datahike/references/fork-maintenance.md` covers working inside our own Datahike fork |
| `clojure-testing` | test shape, the database fixture, generative properties, and spotting a suite that is green for the wrong reason |
| `repl` | telling apart Seon's agent-reply reader, a live cluster's io-prepl/MCP eval, and a raw JVM REPL — they behave differently and confusing them wastes hours |
| `datastar-web-ui`, `ui-canvas`, `seon-context-config` | the current web renderer, the canvas surface, cluster configuration |
| `clojurescript` | quarry only — the CLJS pod is deleted |

Skills carry **blast radius**. They load into every agent working that area,
and are trusted *because* they are curated, so a wrong line there is far worse
than a wrong line in code. If you add or change one: every claim carries a
`file:line` or a named research document verified against current source;
anything unverifiable is deleted, not hedged; ruled-but-unbuilt designs are
marked `[TARGET]`; and substantial changes get an independent verification
pass. A stale skill is a high-priority defect, not documentation debt.

## The loop that works

**Archaeology → design → falsify → review → test-forward implementation → live
proof → independent audit.**

- **Archaeology.** Read `src-old`, git history, the research corpus, and the
  vendored dependency. Produce a keep / avoid / reconceive table. If you are
  coordinating, this is what research lanes are for, and launching them *first*
  is the whole game.
- **Falsify before you commit to a design.** Attack it: an adversarial reviewer,
  a REPL prototype, a measurement. In one recent day, roughly half the designs
  changed materially at this step and two were *dissolved entirely* — the work
  turned out unnecessary. **A well-argued refusal to build something is a great
  outcome, not a failure to deliver.**
- **Test-forward.** Falsifiers are authored before the implementation:
  generative properties over the domain, examples only as teaching. Contracts
  touching identity, effects, or custody get a crash-walk table — kill the
  process at every point, one row each — *before* sealing.
- **Stop on friction.** If a contract seems wrong or unimplementable, stop and
  report the exact friction rather than hacking around it. Friction reports are
  primary data. Silence with a workaround inside it is the failure mode.
- **Prove it live.** A change proven only by a passing test is not proven.
  Drive a real cluster, read the actual output, watch the real datom.
- **Audit independently.** After every landing wave, someone who trusts nobody's
  report re-verifies the risky claims. In one recent day this caught **five real
  blockers that had been reported as done.** Do not skip it because things look
  green.

## Your instant feedback loop

```bash
bin/test                      # the gate; bare = full suite, or pass namespaces
clojure -M:dev                # plain source-classpath REPL for load-only probes
bin/seon status               # every live cluster
bin/seon start <your-name>    # your own cluster; a fork is ~17ms
bin/seon init                 # completely publish current-src
bin/seon init --changed PATH  # incremental when safe; complete fallback
bin/seon init <name> --force  # destructive refork of an existing branch
```

For live work: `mcp__seon__runtime_status`, then
`mcp__seon__eval_clj`, cluster-qualified. **The REPL is the first design
surface, not a debugging tool of last resort** — one form against a live
cluster answers most design questions, and a plan written without a probe is a
hypothesis.

Boot your **own** cluster for anything you intend to change. Clusters are
sovereign and cheap. Never write to, reset, or bounce someone else's.

## The warts. Know them before they bite.

- **The tree is often red, and usually it is not your fault.** Several lanes
  edit concurrently. Check `git status` before diagnosing — a failure inside
  someone else's in-flight file is *their* boundary. Say whose it is and carry
  on. Judge green only on a quiet tree.
- **A long-lived JVM serves the code it loaded at startup.** `bin/seon start
  <name>` *adds* a cluster to an already-running JVM, so correct code can fail
  in a stale one. This cost a lane an entire work chunk chasing a phantom. Boot
  your own operator root when you need current source.
- **The code graph can be partial on an older cluster.** A cluster forked
  before the current indexer landed can have
  has namespaces without functions — a corpus that *looks* populated.
- **Docs, skills, and instructions can be stale.** Six skills were once
  teaching a subsystem that had been deleted for weeks. Verify before you trust
  — including verifying this file.
- **Long-running work dies sometimes** (upstream API errors, machine load).
  Commit in small coherent slices so churn costs minutes, not hours.

## Start here, 2026-08-02: where the program actually is

Read `plan/unsettled.md` from the TOP — its addenda run newest-first
and **ADDENDUM 15 is the session-close handoff** (tree state, owed
verifications, the queue). Then `plan/README.md` rulings **#24-#40**;
the 2026-08-01/02 ones are the current charter. Then
`issues/index.md`.

**Run `bin/test` before anything else.** The full gate has not run
since the schema consolidation and the `turn`/`evaluate` refactor
landed. Last known green: 823 tests / 4,062 assertions / 0 failures.

### What is BUILT now (this list changed enormously on 2026-08-01/02)

The substrate the earlier "state of the design" called designed-but-
unbuilt is largely built and live-proven:

- **One live SCI context per cluster**, built at cluster start and kept
  hot — the per-turn rebuild is gone (~350 ms off every turn). Agents
  in a cluster share one program; clusters share nothing (ruling #27
  closed, including the 17-var sci-fork residue).
- **Stateless resume**: a session restores value-first from
  `:seon.code.def` facts, re-evaluating only *provably pure* forms;
  effectful and nondeterministic ones never re-run (rulings #28/#32).
- **The print path**: one closed grammar, one dispatch, text and hiccup
  from one stored fact (#26). 34 REPL-parity divergences tracked; 10
  promoted.
- **Parsed function contracts as facts** (#33): "which functions accept
  X / produce X" is one Datalog query, derived with Malli's own parser.
- **Agent contract enforcement**: an agent calling another agent's
  contracted function with bad input gets the same flat error a host
  var produces — live-proven.
- **AI settings** (#34): every dial overridable per agent by the same
  attribute idents on the agent entity, resolved per turn.
  Default model `deepseek-v4-flash`, **thinking off**, max-tokens
  65536, timeout 180 s — all calibration-cited. Planners opt into
  thinking; Seon is **tool-less** — forms are how agents act (#39).
- **Model reasoning** persisted and streamed to the HTML projection
  only, never into agent context (#35).
- **The bootstrap is data**: `resources/seon/bootstrap.edn` populates
  per-cluster plan facts; editing one form is a transaction, digests
  key graded drives, prior agents stay frozen.
- **Evals**: Seon registers as an Inspect model provider — **proved on
  198 real gpqa samples**, upstream untouched (#36/#37). Goal grading
  is our own `clojure.test` + test.check against the ending commit's
  fork; judges advise, tests gate.
- **One schema resource** (`resources/seon/schema.edn`, ruling #14
  finally executed) — the per-family files and the globbing machinery
  are gone.
- **The operator**: `bin/seon --root PATH` isolation, `down` prints its
  census before acting, `reset --force` works from any wreckage, one
  `start-child-jvm!` owns every child launch.
- **A standalone jar** (`build.clj`) that boots a cluster from nothing
  but the jar, shipping pre-analyzed initialization rows.

### The open work, and the trap in each

- **The bootstrap's content is the live experiment.** The gpqa run is
  the sharpest datum yet: **196 of 198 episodes tried to WORK a
  multiple-choice question instead of answering it**. The episode
  teaches objective-work; raw-QA tasks want an answer. Trap: this is
  not a bug to fix in code — it is the empirical question the whole
  apparatus exists to answer. Edit `resources/seon/bootstrap.edn` (or
  transact a plan edit), run drives, compare by digest.
- **Store economics at eval scale** (blocker). READ THE ANATOMY BEFORE
  QUOTING ANY NUMBER HERE:
  `research/store-amplification-anatomy-2026-08-02.md`. TWO EARLIER
  FIGURES ARE NOW DISPROVEN and both were quoted in rulings and issue
  notes before anyone modelled them:
  - **"~86x inline payload amplification" is a MISREADING.** Payload
    growth is LINEAR in payload size and QUADRATIC in sequential commit
    count while roots stay shallow: the coefficient came from 40
    retained growing snapshots, `4 × (N(N+1)/2 + N)`. The model is
    validated, not asserted — a held-out 16 KiB prediction of
    56,648,465 B against 56,618,147 B measured, 0.05% error.
  - **"~42 MB per eval sample" is WRONG by ~4x**; it summed
    overlapping shared-store intervals. The reconstruction is
    9.793 MB/sample (1.939 GB selected for the 198-sample run).
  What the run actually holds: history is 47.25% of it, the commit
  record averages 969 B per transaction, and **blob content is exactly
  0 B** — the blob tier is not being exercised at eval scale at all.
  The blob threshold was lowered to 4,096 characters on the DISPROVEN
  reading, so whether that value is right is now an open question, not
  a settled one. The older "~1.5 MB per transaction regardless of
  payload" claim is also false. Three wrong numbers in one area is the
  lesson: measure before repeating, and model before tuning.
- **The agent write surface** — the hole is ergonomics and gating, not
  capability. Agents CAN transact any declared attribute (ruling #20
  makes `store/transact!` callable; `:schema-flexibility :write`
  refuses undeclared ones; refusals return as values). Ambient custody
  is FIXED (`643719904`): each evaluation binds the agent's cluster
  connection, so ambient `seon.db` reads and writes work through the
  door. What remains: the seon.db wave (ruling #41 — all Datahike core
  functions in `seon.db`, dual positional/argument-map interfaces,
  everything first-party migrated) and ruling #30's persistence gate —
  the designed control over what an agent may COMMIT — which has no
  design yet. Commission the gate design.
- **The effect door** (`seon.effect`) does not exist. That is why
  replay-safety is trivially true today, and it gates ~26% of the
  benchmark catalog.
- **My own probe tools are too narrow** — `eval_clj` cannot reach a
  `--root` JVM, a degraded boot (the REPL starts first precisely so
  those stay debuggable), a chosen namespace, or the agent's own view
  through the door. Filed; fix it early, it pays for itself.
- **Load time**: 11.8 s -> 2.14 s via a dev dependency class cache
  (first-party stays uncached, hot reload intact). The jar still pays
  ~12 s; the same mechanism could serve it, with core.async's AOT/IOC
  behavior the one thing to decide.

## The mentality

Each of these is a standing ruling. Violating one is a bug, not a style
disagreement.

- **Derive state; do not remember it.** This project has had six of six
  assumptions falsified in a single sitting. Verify with one live command
  before acting on any prose.
- **Nothing stores what a query can derive.** Status fields, counters, cached
  projections — state is *which facts exist*. `open?` means no `closed-at`. A
  boolean is legitimate only when someone genuinely asserts the false.
- **Nothing re-executes on recovery.** Reopen, record the interruption, close
  the run, let the agent adapt from derived context. Absence is the one
  representation a dead process cannot corrupt.
- **Errors are values at agent boundaries and facts at core boundaries.** Fail
  loud in development without falling down: the REPL and the UI survive a
  failure precisely so it can be understood.
- **One mechanism.** No `foo-v2`, no compatibility path, no second registry.
  Fix the owner in place and delete the superseded path in the same commit. Git
  is the archive.
- **Prefer dissolution to addition.** The best change deletes a mechanism. When
  you meet a tuned constant, ask what observable event it is standing in for.
- **Awkward tests are a design verdict.** Catching yourself writing point tests
  to fence edge cases means stop and find the construction that makes the class
  unrepresentable. A pile of exact-string assertions means say so out loud.
- **Every `[:fn]` schema carries an honest generator.** Malli never validates
  generator overrides, so a dishonest one green-washes everything downstream.
- **No hand lists, no name-prefix rules, no magic numbers.** Classifications are
  computed from facts; constants live in the defaults document with units and
  provenance. `(or x 60000)` is the banned shape.
- **Smart defaults, and ambiguity fails loudly.** Unambiguous is automatic; a
  destructive operation with several candidates refuses with the list rather
  than picking one.
- **The recurring failure class of this whole project is a check that reads
  ABSENCE OF SIGNAL as health** — a log-name glob, a query against a
  descriptor that no longer exists, a regression walking less than the writer
  admits. When you write a check, ask what it reports when the subject is
  absent. If the answer is "fine," the check is worse than nothing.
- **Review returns; do not trust them.** Read enough source to judge a claim.
  Independent verification here is not paranoia, it is the process that works.
- **Churn is weather.** A cluster vanishing, a JVM dying, a reset — adapt and
  continue. Stop only for a genuine implementation dependency, and name it
  exactly.
- **Write it down in the same beat.** Rulings into the plan README, state into
  the working edge, settled terms into the vocabulary table, defects into
  issues — in the turn it happens, path-limited commit. Being asked "what
  haven't you written down?" means it was already too late.
- **Read the actual output an agent sees**, not a summary of it. Prose that
  reads fine to you often reads badly to a model.

### Added 2026-08-01, from a day that kept finding rot

- **ASK WHAT THE DEPENDENCY ALREADY DOES BEFORE YOU BUILD ANYTHING.** The
  owner's recurring, justified complaint: *"how is it that we are computing
  this over and over when sci already offers a way to resume and keep
  evaluating?"* We were rebuilding every agent's entire program graph from
  database rows on EVERY TURN — 283 ms and 78 KB per turn — because a
  cold-start mechanism had quietly become the hot path. Nobody chose that; it
  accreted. Before designing any mechanism, read the vendored source in
  `reference-code/` for the thing you are about to reimplement. sci keeps a
  live env; konserve has GC and content-addressed blobs; Datahike branches are
  head pointers, not copies. If your design recomputes something a dependency
  already maintains, the design is wrong.
- **A SYMPTOM THAT IS EXPENSIVE TO FIX IS USUALLY THE WRONG TARGET.** One bad
  program row bricked an entire branch, and the obvious fix was per-row
  containment. The real defect was that the rebuild ran per turn at all — fix
  that and the blast radius collapses on its own. When a fix feels like
  hardening a mechanism against its own normal operation, stop and ask whether
  the mechanism belongs on that path.
- **ROT POISONS AGENTS INTO CONFIDENT WRONG ANSWERS.** This is not
  housekeeping. Today: four architecture documents still described per-agent
  capability GRANTS ("ordinary agents do not inherit those grants") long after
  the ruling that every agent may call every function; a deleted pod's config
  and a 14-file dead operator sat in live-looking paths teaching that same
  model; a root-authority sentence about effects read as a restricted toolkit.
  An agent reading any of it builds an elaborate, coherent, WRONG solution and
  cites the document while doing it. When you find a claim the code has
  outgrown, fix it in that turn — and check whether it left ammunition
  elsewhere (the doc was the claim; the dead config was the ammunition).
- **CHASE THE READERS OF ANYTHING DEAD.** Deleting one dead config surfaced
  two readers; one of them was a whole dead operator. `rg` for every reference
  before and after, verify the live entry points still work, and delete the
  transitive closure — not the one file you noticed.
- **SURFACE SMELLS THE MOMENT YOU SMELL THEM.** Not at the end of the task,
  not in a summary — the owner wants them raised at once so they can be
  addressed together. A finding that lives only in conversation is a finding
  that did not happen. Every defect, contradiction, and open question becomes
  an issue note in the same turn, with evidence and an owner. Being asked
  "is there anything we found that is not logged?" means it is already late.
- **VERIFY THE THING YOU ARE ABOUT TO CLAIM, ESPECIALLY WHEN IT IS YOUR OWN
  EARLIER CLAIM.** Today three separate measured findings overturned things
  recorded hours earlier: `sci/fork` is not the sharing mechanism; the
  "489 ms substrate" was `acquire!`, not `sci/init`; blob rehydration is
  slower than recomputation. Every one had already been written into a
  ruling. Re-derive before you repeat.
- **COUNT WHAT YOU CITE.** A mined checklist was reported as 59 rows and
  actually contained 88 — the summary silently dropped a whole category. If
  you are about to state a number, count it. And when a number is
  ALARMING, measure it before repeating it: on 2026-08-02 a "1.5 MB per
  transaction" figure survived two documents and an issue note before a
  probe showed the real cost was kilobytes and the actual problem was
  something else entirely (payload amplification).
- **A LANE'S HEARTBEAT IS ITS COMMITS, NOT ITS PROCESS.** A finished lane can
  leave its wrapper running for an hour. Read `git log` for its owned paths;
  reap it when the deliverable has landed. And a lane reporting "another lane
  is editing my files" is usually seeing itself.
- **PROTECTED PATHS MAKE LANES STOP CORRECTLY — EXPECT IT AND FINISH THE
  WORK.** Two lanes stopped honestly at boundaries today (a loud cap line that
  needed a protected file; obsolete assertions in a protected test). That is
  the system working. The orchestrator finishes those edges itself rather than
  letting them sit.

## If you are coordinating work

Codex orchestrators use the native collaboration/subagent tools; they do not
launch `bin/codex-agent`. Claude orchestrators use the repository's
`bin/codex-agent` harness. In either case, every lane names owned paths,
protected paths, grounding documents, and one exact deliverable. A spawned
subagent executes its assignment directly and does not delegate again. Use
higher reasoning for REPL-heavy falsification, review every returned claim
against source, and never sandbox a lane — it makes the lane's own output
unrecordable.

Keep one ordered spine and fill every other slot with independent work. Review
and integrate each return before building on it. Two lanes must never edit one
mechanism; if they collide, one stops and reports rather than absorbing the
other's changes.

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
