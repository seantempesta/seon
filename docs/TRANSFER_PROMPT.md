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

## How to establish what is broken right now

Do not preserve a second issue list in this orientation. It went stale within
one day and taught a repaired registration failure as current fact. The current
queue is exactly `docs/seon/issues/index.md`; each note carries evidence,
acceptance, and an owner. The current implementation edge and latest frozen
gate live at the top of `plan/unsettled.md`. Verify both against `git status`,
`git log`, `bin/seon status`, and the smallest relevant test before scheduling.

The 2026-07-30 replacement removed evaluated build inspection and live source
synchronization. clj-kondo now statically analyzes first-party `src/` and
`test/`; exact locations become namespace/function/test rows, while global
schema rows come from admitted EDN. Dependency caches are resolution context,
never database authorship. One malformed file still yields findings and
analysis for its valid siblings, but publication never seals a partial branch.
Type-mismatch findings remain visible warnings—the type checker is useful
context, not a sound database admission authority—while syntax, resolution,
privacy, and arity errors block the affected source. Runtime analysis queries
the program graph and materializes only namespaces referenced by the candidate
forms, preserving cross-namespace knowledge without paying to inject the whole
graph into every reply.

Hot reload and source publication remain distinct. A re-evaluated Var changes
behavior in its JVM; it does not update database program facts. The edit hook
publishes safe first-party changes to one `:current-src` branch and falls back
to a complete scratch build for structural changes. Bare `bin/seon init`
requests that complete publication explicitly. Existing clusters are
sovereign; only `bin/seon init CLUSTER --force` destroys and reforks one. The
current evidence and any still-open integration edge live at the top of
`plan/unsettled.md`. The edit hook never runs tests: it returns static feedback
and publication promptly. Gemini review is optional and asynchronous, with all
reviewable edits coalesced into at most one batch per two-minute window;
provider failure silently drops the batch. A coherent checkpoint explicitly
invokes the changed-test selector, and full suites remain frozen-tree gates.

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
  you are about to state a number, count it.
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
