---
type: reference
status: active
tags: [reference, orchestration, onboarding]
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

One JVM per cluster. CLJ only. REPL-first. No central loop anywhere, by
ruling: that shape is rejected as "a JavaScript event loop inside Clojure."

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

1. **`CLAUDE.md`** — a symlink to `AGENTS.md`, so every toolchain reads the
   same bytes. The standing law: architecture, vocabulary, data-oriented rules,
   git safety, the resiliency law, the skills law.
2. **This file** — orientation.
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
```

For live work: `mcp__seon_cljs__runtime_status`, then
`mcp__seon_cljs__eval_clj`, cluster-qualified. **The REPL is the first design
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
- **The code graph can be partial on an older cluster.** Indexing happens when
  a cluster's ancestor is populated, so one forked before the indexer landed
  has namespaces without functions — a corpus that *looks* populated.
- **Docs, skills, and instructions can be stale.** Six skills were once
  teaching a subsystem that had been deleted for weeks. Verify before you trust
  — including verifying this file.
- **Long-running work dies sometimes** (upstream API errors, machine load).
  Commit in small coherent slices so churn costs minutes, not hours.

## What is actually broken right now

Nothing here is hidden and nothing is a surprise waiting for you. All of it is
filed with evidence and acceptance criteria in `docs/seon/issues/`; this is the
honest shape of the debt as of 2026-07-29 night.

**Blockers (5).**
- `resolve-namespace-changes-by-executable-operator-identity`, with
  `account-for-declarations-inside-executable-top-level-forms` and
  `make-function-coverage-independent-and-cardinality-preserving` — the code
  graph's reader recognizes declarations by matching an operator's LOCAL NAME,
  so `(foo/defn ghost [] 1)` mints a phantom function row, `(other/in-ns 'x)`
  misattributes every declaration below it, a `defn` inside a top-level `do`
  vanishes with no row and no refusal, and `'(in-ns 'x)` in inert quoted data
  makes an entire real source file unindexable. The recurring coverage test
  shares the reader's own event stream and set-collapses declarations, so it
  agrees with all of it. This is the single most important open item, because
  everything in Seon queries the program graph. A fix lane is dispatched;
  `research/indexer-review-2026-07-29.md` is the evidence. Read the lesson at
  the end of the code-graph block in `plan/unsettled.md` before touching that
  reader — the day produced a hand-maintained allowlist, its replacement by
  hand-maintained name matching, and a coverage test blind to both, and all
  three passed a green gate.
- `priming-indexes-with-the-live-jvms-loaded-code` — `bin/seon index` reads
  source files from disk but interprets them with the reader the target JVM
  loaded at BOOT, then records the ancestor digest from the disk files. So the
  recorded digest lies about which code produced the corpus, and priming a
  long-lived cluster silently writes a stale corpus and reports success. This
  cost the orchestrator an hour and two wrong diagnoses in one evening.
- `program-graph-render-declarations-name-absent-functions` — the render
  catalog advertises projection functions that do not exist, so an advertised
  family can return unresolvable.
- `fresh-operator-instrumentation-cannot-resolve-render-value-schema` — a
  boot-ordering hazard between instrumentation and the schema registry; the
  choke-point fix landed, this is the residue.
- `finish-deleting-the-old-operator-classpath-from-retained-tooling` — the hook
  linters and one test runner still need the mixed quarry classpath, which is
  the last thing keeping the dead operator's classpath alive.

**The two that tell you most about the system's real state.**
- `eval-time-schema-and-test-rows-have-no-recurring-proof` — writing that test
  exposed why it was missing: schema activation rebuilds the WHOLE projection
  from one database's rows through a PROCESS-GLOBAL call, so a fixture holding
  one agent-authored schema collapses the registry and kills unrelated tests in
  the same JVM. That contradicts "clusters share no mutable state," which is a
  design law, not a test inconvenience.
- `observable-graph-transitions-are-polled-in-tests` — tests still sleep where
  an event exists. Standing doctrine says interfaces publish their own
  readiness; this is where they do not yet.

**Sharp edges you will personally hit.**
- `partial-hot-reload-produces-mixed-code-with-no-warning` — `:reload` reloads
  one namespace, not its dependencies, so a live JVM happily runs a NEW caller
  against an OLD callee. Armed instrumentation catches it; nothing else does,
  and the error names a schema rather than the actual problem.
- `root-store-holder-does-not-canonicalize-store-dir` — equivalent relative and
  absolute store paths produce different holder keys, so path identity is
  treated casually in a place where it decides cluster identity.
- `cluster-reset-shadows-clojure-core-reset` — a new `reset!` shadows
  `clojure.core/reset!` inside a namespace full of atoms.
- `development-mcp-advertises-deleted-cljs-tools`,
  `fresh-cluster-docstrings-teach-deleted-bin-repl`,
  `loadable-skills-component-describes-deleted-pod-importer` — tooling and docs
  still pointing at the deleted pod era. Assume anything you read may be stale
  and verify it.

**Contract quality.** `database-and-transaction-boundaries-use-anonymous-any-contracts`
and `flow-config-dials-have-two-registration-owners` are real; `:any` at a
database boundary is a boundary nobody has proven.

**Known incomplete, by choice.** The UI is deliberately tabled until the
context rendering system is proven — do not build UI. Context is still
hand-assembled blocks; the walk-rendered replacement is designed, falsified,
and measured but unbuilt. `:seon.fn/calls` reachability does not exist yet, so
the 808 private function rows are groundwork rather than a working call graph.
The checkpoint has failed graduation six times and every failure found
something real; that loop is the most valuable machinery here.

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

## If you are coordinating work

Implementation goes to `bin/codex-agent` lanes: background-tracked, chunked
under five minutes, a commit per chunk. Use higher-reasoning agents for
REPL-heavy falsification. Every lane spec names owned paths, protected paths,
the grounding documents to read, and one exact deliverable. Check `status`
before any `resume`. A finished task with no summary means the turn ended
mid-work, not that the work is done. Never sandbox a lane — it makes the lane's
own output unrecordable.

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
