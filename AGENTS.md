# Seon — shared instructions

This is the one maintained repository instruction authority. Codex reads
`AGENTS.md` directly; Claude reads the same bytes through the same-directory
`CLAUDE.md -> AGENTS.md` compatibility link. The thin delegated-lane adapter
lives in `AGENT.md`; `ORCHESTRATOR.md` is a superseded historical stub.

If you were spawned as a subagent, execute the assigned task directly. Do not
spawn or delegate again. If the task is too broad, report that to the top-level
orchestrator for rescoping.

The top-level agent owns user communication, the active roadmap, cross-lane
integration, final design judgment, and proof that separately completed work
forms one system. Delegate only a coherent independent result, not fragments
whose integration requires the delegate to reconstruct the whole question.
Returned reports are claims to review: read enough source to judge them,
falsify risky conclusions independently, and keep overlapping shared files at
the top level.

## Standing goal — delete the old system at full throttle (owner-ruled 2026-07-24 PM)

THE GREAT DELETION IS THE SPINE. The pod's self-host/child execution
machinery — the cljs.js self-host engine, eval.cljs, the execution
child and its bands, every pod-only duplicate of a mechanism the JVM
now owns — gets DELETED, slice by slice, starting immediately, without
waiting for green suites. Old tests that pin a deleted path are deleted
in the same commit; the tests that replace them assert the SURVIVING
mechanism, written from the lessons learned, never green-washed. Do not
port old code into new homes: design the replacement fresh from the
architecture target, and prefer NO replacement when the JVM path
already owns the behavior.

THE CONVERSION TEST IS SIMPLIFICATION, not relocation (owner ruling
2026-07-24 night, after the R52/R53 pattern): a function that "runs on
the new tier" but keeps its old-model shape is NOT converted — it is a
ported defect. Under the stateless claim-native model the agent-facing
surface reduces to three shapes: pure code returning VALUES the driver
interprets (lifecycle, dispositions, plans); genuine capability
requests through the one guarded door (fs, web, llm, db); and durable
FACTS the driver commits (memory, messages, receipts). Anything
agent-facing that performs runtime semantics effectfully from inside
an eval — leaf-bound lifecycle calls, in-eval turn/run mutation,
side-channel delivery — is old-engine residue: redesign it into one of
the three shapes and DELETE the old form, never bind it into the new
tier. When reviewing any surviving surface, ask first: "is this
simpler than it was?" If it is equally complex, the model was ported,
not applied.

CUT FIRST, SEAM-FIX SECOND (owner ruling 2026-07-24 night): when a
deletion/conversion list exists, land the ENTIRE wave of cuts before
polishing any individual seam. A discovered seam defect during a cut
wave gets a one-line issue and the cutting continues; seam repair is
its own later wave over the finished wreckage. Never let one seam's
perfection gate the next cut — that sequencing inverts the refactor
and is how old shapes survive. A deletion slice is blocked ONLY by a real
implementation dependency — something live still calls the path and the
surviving owner genuinely cannot serve it yet. Name that dependency,
fix it at the surviving owner, resume deleting. Let it crash: breakage
exposed by deletion is discovery, not failure — fix forward, never
restore the deleted path.

The morning goal's mechanics stay in force: multiple lanes ALWAYS,
limited only by real conflicts (same-file ownership, a frozen-tree
checkpoint, an exhausted budget) — an idle slot with dependency-ready
work queued is an orchestrator bug. Supervision every ≤15 minutes of
wall time: verify each lane's transcript shows REAL work (long is fine,
quiet is not); stop + resume with the correction the same minute; lanes
commit coherent gains as they go so progress lives in git. Shared-tree
churn, rebuilds, restarts, and database resets are normal weather and
FREE resilience drills — abandon a unit only when it genuinely cannot
proceed, recording why. Research and implementation always run
concurrently. Timeouts are last resorts; interfaces express their
dependencies and publish their own readiness.

DEVELOPMENT VELOCITY OUTRANKS THE QUEUE (owner-ruled 2026-07-24 PM):
anything extremely slow that taxes every fix cycle — a 300s restart, a
14s JVM load, a blocked test runner, a stale artifact gate, a rebuild
that recomputes what a cache already knows — is attacked IMMEDIATELY in
a parallel lane the moment it is identified, never waited on and never
parked behind feature work. The fix loop's speed is the multiplier on
everything else; treat a slow loop as a production incident for
development.

## Sustained program cadence

The top-level agent keeps the complete active-program ledger visible while it
works on the dependency-critical slice. At the start of a work period and
after context compaction, read the high-level program roadmap plus the current
chunk roadmap, reconcile the working plan with both, and name the next ordered
implementation boundary and every independent lane that can advance safely.

Use available subagents continuously when concrete independent work exists:

- keep one integration/implementation lane on the critical dependency path;
- fill other slots with coherent non-overlapping source audits, downstream
  research, test/proof design, or bounded implementation in separate owners;
- give each lane the relevant architecture/roadmap context, dependency-ledger
  and `reference-code/` requirement, owned paths, protected paths, and an exact
  durable report or code/proof deliverable;
- do not wait idly for a lane when the top-level agent can advance another
  safe task, and do not parallelize edits to the same mechanism merely to keep
  slots busy; and
- review and integrate each returned result promptly, then refill the slot
  from the remaining program ledger when another independent task is ready.

Before a long verification run or likely compaction boundary, update the
current PRD roadmap with durable current state/evidence and leave the working
plan ordered. Conversation memory, an agent's private context, and a running
subagent are never the only record of what remains. Parallel throughput never
overrides dependency order, one-mechanism ownership, shared-tree safety, or
the requirement that the top-level agent prove the integrated system.

A build, restart, reset, or live-proof checkpoint is a coordinated source
freeze for every path included in its artifact digest. Before starting one,
pause source-editing lanes and wait for their owned files to be coherent; do
not count the checkpoint if a build input changes before readiness. Release
the lanes immediately after the checkpoint ends. If an interrupted operator
leaves a recorded child alive, use `bin/seon down` so the supervisor reaps its
own processes rather than killing the child directly.

After every big landing wave (a rung completing, a multi-lane day, a
deletion wave), commission an INDEPENDENT adversarial audit of the
changed tree — an agent that trusts no lane's report, sweeps for the
past's known failure modes (second mechanisms, hand lists,
stored-derived creep, unjustified clocks, symptom patches, lying
docstrings), REPL-falsifies suspicions, files ranked issues, and
reports what is genuinely in good shape (calibration, not just alarm).
Fix lanes dispatch on its return; blockers at the newest seams are the
expected yield (owner-ruled standing cadence, 2026-07-29).

Run the same control loop after every returned lane, material discovery, or
completed commit:

1. compare the result with the persistent goal and the complete program ledger;
2. update the current chunk's evidence, state, dependency edge, and next exit;
3. review and integrate or reject the returned claim before building on it;
4. advance the earliest dependency-ready implementation at the top level; and
5. refill every other safe slot from the documented queue.

This reconciliation is the scheduling clock for the whole program. Perform it
before accepting follow-on work from a returned lane, before expanding a local
investigation, and before reporting cumulative status. The answer must still
name the earliest unsettled contract, every occupied parallel lane, the next
dependency-ready refill, and the final graduation gate. A locally green slice
does not change the persistent goal or make later units disappear.

The top-level working plan is a compact projection of that ledger, not a second
roadmap. Keep it current, with exactly one in-progress critical-path item and
explicit pending integration/proof boundaries. If the current work cannot be
traced to one of those exits, stop, record the finding if useful, and resume
the ordered program.

Keep four durable fields visible in the active PRD whenever several lanes are
running: the earliest unsettled contract, the integrated proof that closes it,
the dependency-ready parallel portfolio, and the next refill for each occupied
slot. A lane report without one of those destinations is context, not a reason
to displace the spine. Begin every investigation with the shortest falsifier
for a named exit; once evidence shows that the finding is independent, record
or delegate it and return to the ordered boundary instead of exhaustively
polishing it in the top-level context.

When the harness provides a persistent goal, its objective names the complete
program outcome and final graduation gate, never only the current lane. Check
that goal against the program ledger after every compaction and lane return.
Do not mark the program blocked because one lane is waiting: keep the goal
active while any dependency-ready spine, integration, research, or proof work
can advance, and record the local wait in its owning roadmap or issue instead.

Separate sequencing from concurrency explicitly. The earliest unsettled
contract and its integrated proof form the ordered spine; no consumer may
invent or assume that contract. Everything else is a rolling parallel
portfolio: later-unit source grounding, independent consumer implementation
against already-settled contracts, downstream packaging, and bounded proof
work may occupy the remaining slots. Keep the top level available to review
cross-boundary decisions, resolve overlaps, update the ledger, and integrate
proof; it should not duplicate a delegated implementation merely to appear
busy. A returned lane frees a slot only after its claims and owned diff are
reviewed, but unrelated lanes never wait for that review.

Do not let a locally interesting defect silently replace the program. Before
expanding an investigation, name the roadmap exit measure it blocks. If it
does not block the active slice and can be isolated safely, record it in the
owning issue/PRD with evidence and acceptance criteria, then return to the
earliest dependency-ready work. A bug becomes an interrupt only when it
invalidates current proof, threatens data or shared-tree safety, or prevents
the next ordered boundary from being implemented.

## Instruction discovery and localization

Before changing a subtree, find and read the closest nested `AGENTS.md`, and
recheck it after context compaction. Never edit a `CLAUDE.md`; a regular
first-party `CLAUDE.md` is drift that must be reconciled into the adjacent
authority before restoring the symlink.

Claude discovers descendant links when it reads in that subtree. Codex builds
its native chain only from the Git root to the task's selected working
directory: a root-started task must read the closest nested file explicitly,
while `codex --cd <subtree>` guarantees native root-to-leaf loading. Project
config raises the combined instruction budget, but localized files must still
stay tight.

Localized files contain durable ownership, invariants, runbooks, and links—not
status diaries. One fact lives in the deepest file that owns it. If a change
invalidates that fact, update the localized authority in the same commit.

## How Seon runs at its core

One JVM process runs everything, from source, REPL-first. CLJ only — the
CLJS build is off (owner, 2026-07-27). Fresh `src/`+`test/` are the
system; `src-old/`/`test-old/` are the quarry, disabled by default.

Boot is a tower; each layer reads only the one below it and publishes
its own readiness:

1. **Process.** Start reads a closed, tiny bootstrap config (store
   path, prepl bind, log dir — nothing the database could own) and
   opens its REPL at second zero. Identity is (cluster-name, pid,
   start-instant); every path derives from the cluster name.
2. **Store.** Each cluster is one Datahike store
   (`data/clusters/<name>/`), opened in-process (`:self` writer) under
   a lifetime `flock`. Datahike's writer is its own serial loop per
   connection — we never build writers, we call `transact` and it
   serializes. Exactly one live write connection per store (two JVMs on
   one store once destroyed 40/40 commits silently); one JVM may host
   many clusters, and nothing may assume "the" cluster. Clusters share
   no mutable state — reset one, the others never notice.
3. **Facts.** A config manifest reconciles into database facts;
   runtime reads the database, never files or env vars. A new cluster
   forks the shared bootstrap ancestor (indexed code + initialization
   pages) — near-instant, never a re-index. Clusters always RESET to
   current code and pages; there is no data migration.
4. **Flow.** EVERY AGENT IS ITS OWN FLOW GRAPH (owner ruling
   2026-07-28), created with the agent from one blueprint, parked
   between episodes (two procs, ~8.5 KB per parked proc — measured in
   `flow-mechanics-2026-07-28.md`), kicked off by the messages it
   receives, pausable/resumable per agent. Per cluster, a few shared
   plumbing graphs: render pipeline and fault committer.
   There is NO central loop, dispatcher, or scheduler — that shape is
   rejected as "a JavaScript event loop inside Clojure." The process
   root owns one bounded `:compute` executor and one `:io` (virtual
   threads) executor shared by every graph. Every proc pins `:io` or
   `:compute` explicitly — the `:mixed` default pins a platform thread
   per proc and is the one scaling cliff.

**Live update is two cases, one mechanism each.** Graph definitions
reference transforms as vars (`#'f`), so re-evaluating a `defn` against
the running system changes proc behavior immediately — zero restart.
Topology changes (procs, conns, buffers) rebuild the graph — stop →
`create-flow` → start, measured ~0.3 ms — which is safe because
channel contents are losable by construction; all durable work
re-derives from database facts.

**Transport law (owner ruling 2026-07-28, revising "disposable values
only"):** anything recovery or another process could ever need is a
DATABASE FACT — identities, receipts, messages, errors, the settled
reply — with bulky payloads as blobs and the row carrying
identity/digest/size. Everything IN FLIGHT rides channels however
large (8 MB crosses a channel ~7,000× faster than a file-store
transact), provided loss is free: re-derivable from facts or
superseded by a newer complete value. The buffer encodes the loss
semantics: sliding-1 for latest-wins (streamed tokens), fixed for
backpressure, counted-dropping for observation. Any design where
channel loss breaks recovery is wrong by definition.

**Crash model: nothing re-executes.** Recovery = reopen the store, mark
dangling receipts `:interrupted`, re-derive the graph; the agent adapts
from derived context. Runs are claimable database state (CAS + epoch +
lease + receipts held on `:seon.agent.run/process` — never say
"claimant").

**Errors are two classes, never mixed.** An agent mistake becomes a flat
`:seon.error` value the agent sees — nothing throws into the loop, and
sci containment catches mistakes but is not a security boundary. A core
fault rides flow's error-chan into the fault-committer proc, which
commits it as a durable fact with provenance — so "who should fix this"
is a query. One config dial: dev panics, prod degrades.

**Scheduling is core.async's own enum, derived never declared.**
`:compute` = bounded ≈ cores, must never block — sci evals run here on
platform threads under the one `:interrupt-fn`; `:io` = blocking
transport (model calls, SSE writes), must never compute; `:mixed` =
fail-closed default for code the graph cannot resolve (its own
platform thread — safe, expensive, the incentive to annotate).
Classification is per-function and DERIVED (owner ruling 2026-07-28,
`workload-classification-2026-07-28.md`): key capability leaves carry
`^{:seon.workload :io}`/`:compute` defn metadata lifted into
`:seon.fn/workload` at index time; chains derive by reachability over
`:seon.fn/calls` — only-compute ⇒ `:compute`, only-io ⇒ `:io`, both in
one chain ⇒ `:mixed`, unresolved ⇒ `:mixed`. Never annotate everything;
scheduling acts at exactly two seams (proc workload tags and the eval/
capability door) — per-function classification, per-proc execution.

Orientation for anyone new: `docs/TRANSFER_PROMPT.md` (what Seon is, why
archaeology precedes design, which skills to load, the warts, the
mentality). Deeper: plan `README.md` (rulings + ladder), research
docs `flow-mechanics-2026-07-28.md`, `flow-inventory-2026-07-28.md`,
`workload-classification-2026-07-28.md` (the agents-are-flows model,
measured), `flow-per-cluster-2026-07-27.md`,
`datahike-multistore-2026-07-27.md`, `flow-dynamic-update-2026-07-27.md`
(every claim above carries file:line evidence there), and the sources
themselves: `reference-code/core.async/.../flow.clj` + `flow/impl.clj` +
`flow/spi.clj`, `reference-code/datahike/src/datahike/writer.cljc` +
`writing.cljc`, `reference-code/konserve/`.

Gotchas: the `flock` is ours — nothing in Datahike stops a second
process opening the same store; `listen!` fires on transact only, so
register interest before deriving current work; never block a
`:compute` thread or compute on `:io`.

Seon is the core: consumer-specific UI, vendor integrations, and domain
models belong in downstream repositories, never `src/` or `docs/`.

## Portable code, platform edges, and SCI

Write portable `.cljc` by default. A `.cljc` is wrong only if it
contains unconditional platform code.

- **Family core + one leaf per tier.** Every capability family (fs,
  shell, web, blob, LLM, db) is a pure portable core plus one thin
  platform leaf per tier; the entry functions are the only
  reader-conditional site. Platform residue (js/Date, node:fs,
  java.net.http, SDK objects) lives in leaves or reader-tag islands,
  never mid-logic.
- **Async is contagious upward — push it down.** The CLJ path is plain
  synchronous (virtual threads park); the CLJS leaf awaits. Logic above
  the leaf is plain portable Clojure; `^:async` markers exist only at
  the one executor/leaf call.
- **Agent code needs no conditionals, ever.** Agents write plain Clojure
  into the corpus (database facts); SCI is one interpreter on every
  tier, so pure corpus code runs anywhere. Portability is DERIVED, not
  declared: `plan-execution` computes placement from the indexed call
  graph (capability edges, package prefixes, purity), and a contract
  predicate is admissible exactly when its call graph is pure and
  capability-free.
- **Every sci invocation passes the one guarded door** (fuel + deadline
  + output caps, all config facts); durable defns REQUIRE a complete
  `:malli/schema` (no `:any` — use named predicate schemas for genuine
  polymorphism); registrations are committed `:seon.schema` facts that
  tiers ACQUIRE at a basis — loading a namespace never publishes
  schema.
- **Boundaries turn values into data.** Wire crossings carry
  schema-projected ordinary values; tier-local objects cross as
  result-symbol references; failures are flat error values. The writer
  compiles core predicates via `requiring-resolve` — it never needs
  SCI.
- **Prove portability, don't assume it.** A `.cljc` rename is not a
  portability proof: require the namespace on the JVM immediately, and
  put dual-tier tests below a namespace directory so both runners
  discover them. Read `docs/prds/sci-execution-runtime/conversion-wiki.md`
  before portable-core work and append new scars.

## Documentation authority

There are two documentation layers and no third:

- `docs/seon/architecture/` is the single always-current description of the
  aspirational intended system. It is target-written in present tense, but
  present tense never claims that source already implements the target. Read
  `architecture.md` first, then the relevant domain document. Update it when a
  design decision changes; never put current implementation state, gaps,
  sequencing, evidence, graduation status, or a migration diary there.
- The active program roadmap (currently
  `docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md`) is the high-level ledger of
  current state, remaining architecture deltas, dependency order, and success
  measures. It points to bounded successor PRDs; it does not absorb their
  detailed audits or implementation plans.
- `docs/prds/<chunk>/` contains one implementable roadmap chunk on its own
  branch. Its `roadmap.md` owns that chunk's exact source inventory, built/gap
  state, implementation order, evidence, and graduation status. Its localized
  `AGENTS.md` is a tight runbook/index, and dated audits/raw evidence live in
  its `research/` directory. Carve the folder before doing deep research for
  the chunk, then finish and merge it before starting dependent implementation.

After a material change, update the affected architecture target, the active
PRD roadmap, and any localized authority whose durable guidance changed.
Research depth lives in dated `docs/prds/<chunk>/research/` files with evidence
and raw external responses; conversations are not durable research artifacts.

Architecture map:

- `context.md` — database-derived blocks, namespace context, cache gradient;
- `data-model.md` — entities, attributes, refs, and `my.*` schemas;
- `agent-runtime.md` — loop/run/turn, lifecycle, isolation, nothing wedges;
- `ui.md` — blocks/renders/surfaces/canvas/cards/slots and live updates;
- `observability.md` — turn capture, blobs, reproduction, and forensics;
- `toolkit.md` — agent-facing function surface;
- `laws.md`, `library-grounding.md`, and `decisions/` — measured laws,
  source read-map, and settled ADRs.

Supporting docs: `docs/conventions.md` for code/schema patterns and
`docs/seon/vision/` for the thesis and aspirational capabilities.

### Markdown

Every `docs/**/*.md` file has YAML frontmatter with valid `type`, `status`, and
`tags`. `seon.dev.markdown` auto-fixes spacing/trailing whitespace and reports
structural errors. Use ATX headings, one H1, no heading jumps, dash lists,
existing wikilink targets, and no bare URLs.

## Model, research, and source policy

The top-level orchestrator designs, grounds specs, reviews diffs, rules on
stops, and runs serial integration gates; implementation goes to capable code
agents. Haiku is only for quick reads. Never haiku for coding. Codex uses its
configured coding model—Claude aliases are not portable model names.

**Delegation follows the orchestrator's native agent system.** A Codex
orchestrator launches and manages its own lanes with Codex's collaboration
tools (`spawn_agent`, `send_message`, `followup_task`, `interrupt_agent`, and
`wait_agent`). It MUST NOT launch its own agents through `bin/codex-agent`.
The native task tree is the ownership and supervision surface; give every
agent the same bounded paths, protected paths, grounding, and exact deliverable
required of any lane.

A Claude orchestrator has no Codex-native collaboration tree, so it launches
Codex code agents through `bin/codex-agent` as harness-tracked background
commands (Bash `run_in_background: true`, description naming the lane — never
`nohup`/`&`, never hand-rolled shell):

```bash
bin/codex-agent run <name> "<the full spec>"   # or spec on stdin (heredoc)
```

A Codex orchestrator inheriting a Claude-started `bin/codex-agent` lane may
inspect, stop, or collect that existing lane for a safe handoff, but launches
all NEW work through its native collaboration tools. A Claude orchestrator
uses the harness lifecycle below for every lane it owns.

**NEVER SANDBOX A LANE** (owner ruling 2026-07-26). There is no sandbox
dial and there must not be one: a read-only audit finished a 63-file
inventory and then had its one `apply_patch` rejected, losing every
per-file evidence sentence it had produced. A sandbox does not make an
audit safer — it makes the audit's output unrecordable, and every lane
must commit its own report and file its own issue notes. Ownership is
enforced by NAMING OWNED PATHS in the spec, path-limited commits, and
your review of the diff. An audit is kept read-only by its spec and
proven by its diff.

For Claude-started lanes, the script owns the conventions: model/effort dials
(`LANE_MODEL`, `LANE_EFFORT`), the `-o` summary
in `tmp/orchestrator/<name>-summary.txt`, and `tee`-streamed stdout so
the user's task panel shows the live transcript while
`<name>-stdout.log` persists. Tracked means lane exit re-invokes the
orchestrator — no watcher loops. Lane stdout never enters the
orchestrator's context: read the summary (`bin/codex-agent summary <name>`),
then query the log selectively with `tail`/`rg`, never a whole-file
read. Also: `bin/codex-agent status | watch <name> | stop <name> |
resume <name> "<followup>"` (resume auto-reads the session id and keeps
the lane's full context).

Never let a lane keep working on a direction that new information has
invalidated (owner ruling 2026-07-24). A Codex orchestrator uses its native
message/interrupt/follow-up tools; a Claude orchestrator spot-checks the
harness transcript selectively and uses `stop` then `resume` with the
correction. Stopping is cheap because resume loses nothing but the in-flight
turn. Claude harness mechanics, resume recipe, and model dials:
`docs/seon/reference/driving-codex-agents.md`.

For research, use one agent with the complete relevant context rather than many
agents with slivers. Independent source domains may run in parallel, but one
question gets one coherent audit. External research uses `agy`; long prompts
go through stdin. Every research agent writes its durable report under the
active PRD's `research/` directory.

For multi-unit program work, the top-level agent maintains one ordered
dependency spine and uses every other safe slot for a coherent independent
implementation, proof, or source-grounded audit. After every lane return,
material commit, context compaction, or newly discovered blocker, reread the
complete program ledger—not only the local task—record the changed dependency
or evidence, integrate or reject the return, and refill the slot from the
earliest dependency-ready unit. A deep investigation stays on the spine only
while it blocks a named exit measure; otherwise preserve the finding in its
owning issue/PRD and resume forward progress. Never invent parallel edits in a
shared owner merely to keep a slot busy.

Every research or implementation unit begins with a dependency ledger. Name
the exact libraries and existing Seon mechanisms the unit depends on, their
selected versions/SHAs, the relevant `reference-code/` paths, and the
first-party call sites/tests that already demonstrate the desired idiom. This
ledger is part of the plan and durable research evidence, not an optional step
after a design has already been invented.

Before planning a change or writing code:

1. Read the closest localized `AGENTS.md` and the active roadmap's current
   state, gap, evidence, and success measure.
2. Identify the exact dependencies/mechanisms, then read their actual source
   in `reference-code/` and the best idiomatic Clojure usages in this checkout.
   Never plan from remembered library behavior or unzip installed packages. If
   the exact pinned or maintained source is absent, locate or mirror it before
   continuing; a plan that names only an API is not grounded.
3. Observe the live system and define a falsifiable failure plus acceptance
   evidence.
4. Read the existing implementation and tests that own that behavior.
5. Probe the critical dependency assumption directly in the REPL or with the
   smallest executable experiment.
6. Implement by strengthening the one existing mechanism in place.

For Clojure work, use `data-oriented-clojure` before the plan, not only before
the edit. Treat immutable data, pure transformations, attributes/connections,
ambient database values, and errors-as-values as design inputs. If a plan is
organized around mutable steps, object-like kinds, imperative accumulators, or
stored derived state, stop and re-ground it in good Clojure source before
implementation.

Parallel agents divide independent dependency/source domains or independent
implementation units. They do not split one semantic question into partial
answers. Research agents return grounded constraints and success measures;
implementation agents receive that complete evidence and retain authority to
work out local details within the named owner and acceptance boundary.

After writing code, verify the running system—not only the tests. Falsify the
change with an observed datom, page/feed, log line, or REPL result. Report what
is still broken honestly.

## One mechanism, no hacks

Do not create `foo-v2`, `foo-new`, a compatibility namespace, or a second
registry/renderer/feed/retry/config/test path to avoid fixing the existing
owner. Fix cycles, callers, and schemas in place; delete the superseded path in
the same refactor. Git is the archive.

When an agent misbehaves, the context is wrong or the code is wrong. Find and
remove that cause. Regex-rewriting model output, warning/scold prose, marker
layers, and post-hoc containment merely hide symptoms. If the cause is not yet
known, record the evidence and continue the investigation.

Assume inconsistencies, coercions, stale schemas, and duplicate mutable state
are bugs until proven otherwise. Fix an understood in-scope smell. Otherwise
report the file/line, observed mismatch, expected owner, and uncertainty; never
silently work around it.

When you discover a bug, code smell, duplicate implementation, stale or broken
test, unsafe edge, or documentation mismatch, report it to the agent that
launched you and search `docs/seon/issues/` for the root cause. Create or update
one issue note before returning. If you fix it in the same unit, close and
archive the note with the commit plus behavioral or live proof; otherwise leave
it open with current evidence, owner, and acceptance criteria. Never add a row
to a private registry or leave the finding only in chat. This records the
problem; it does not authorize unrelated production edits.

## Vocabulary

Use discoverable code names, not umbrella nouns or synonyms:

| Say | Never | Meaning |
|---|---|---|
| functions, schemas, tests | verbs | ordinary Clojure constructs |
| database or `db` | store, inventory, memory | the `seon.db` authority |
| canvas | tile, live-tile, world | `:seon.render.canvas/content`, the focal agent surface |
| surface; card for CSS only | tile | a context render; a visual component |
| web UI | inspector | `/`, `/agent/{id}`, debug, and `/data` |
| subagents | collaboration system | agents connected through database refs |
| cluster | environment | one database, pod, root, and task agents |
| attributes + connections | entity kind/type | the Datahike model |
| build, operator, artifact | flavor | shadow-cljs's build; the `bin/seon`/`bin/acme` supervisor scope; the digested output |
| get-in, path | drill | paged navigation into a nested value by `get-in` path |
| execution plan, `plan-execution` | bare "plan" for placement | the derived placement/manifest value; `my.plan` is the agent planning toolkit, never shortened across that boundary |
| provider descriptor row | adapter, integration | one hosted provider's data row under the config singleton |
| packages/, package.json, deps.edn, node_modules | npm-pkgs, maven-pkgs | per-cluster `data/clusters/<name>/packages/` using each ecosystem's own manifest names |
| contexts on hosts, binding tables | sandbox, VM, jail | sci's own vocabulary for agent execution |
| `:interrupt-fn` | the guard, the door, the cage | the ONE zero-arg fn sci calls on every fn body entrance; `reference-code/sci/doc/interrupt.md:6` ↔ `seon.sci.interrupt` |
| `interrupt!` | stop!, steering-error! | how an `:interrupt-fn` stops an eval uncatchably; `reference-code/sci/src/sci/interrupt.cljc:32` |
| `time-limit` | fuel, gas, interpreter-step budget, deadline-ms | the ONLY limit. Sci counts nothing — it has no step concept; `reference-code/sci/doc/interrupt.md:32` |
| `:seon.eval/fn-entries` | a step budget | a RECORDED DIAGNOSTIC, never a limit: 271M entries in 500ms reads as a spin, 12 reads as blocked in a host call |
| every `fn` body entrance | safepoint | where sci calls the `:interrupt-fn`. A JVM safepoint is a different real thing (GC); `reference-code/sci/doc/interrupt.md:50` |
| `ctx`, `fork` | warm base, sandbox, the agent's world | sci's own names; `reference-code/sci/src/sci/core.cljc:318` |
| `:io` / `:compute` / `:mixed` | eval pool, wait pool | core.async's workload tags: `:io` may block but must not compute, `:compute` must not block; `reference-code/core.async/.../impl/dispatch.clj:122-134` |
| `:seon.agent.run/process` | **claimant** | the process holding a run; `script/seon/dev/process.clj` ↔ JDK `ProcessHandle` |
| accretion / breakage | graduation, nursery, graduated | a change that requires no more and provides no less. **Attribution to Rich Hickey's Spec-ulation is UNVERIFIED** — do not cite it as established |
| initialization pages, rows, transaction data | seed bundle, sidecar | paged database population; `src-old/seon/db/protocol.cljc` (pages/phases) ↔ Datahike tx-data |
| process record, generation, (pid, start-instant) identity | orphan registry, liveness flag | operator-managed process descriptors; `script/seon/dev/process.clj` + `state.clj` ↔ JDK `ProcessHandle` |
| pre-processing, apply, resume | warmup, hydration | the explicit derive-once/install/attach operations (R45); `docs/prds/sci-execution-runtime/research/preprocessing-design-2026-07-23.md` until code owners land |
| reduce (plan execution) | fold | executing a plan is a reduce over its forms; the accumulator is the basis; `clojure.core/reduce` ↔ the Datahike transaction report's `:db-after` (`r/fold` is parallel — wrong word) |
| run loop | driver, driving | the loop claiming runs via `:db.fn/cas` and reducing plans; `src-old/seon/agent/driver.clj` until the rename wave |
| `seon.effect`, `effect/request!` | the door, capability dispatch, call center | the one system-side owner every flat `my.*` tool call enters; effects carry the one request identity |
| program graph | corpus | the collective name for `:seon.fn`/`:seon.ns`/`:seon.schema` facts; owners rename to `seon.code.fn`/`.ns`/`.schema`/`.test` |
| proc, step-fn, conns, graph-def, report channel | invented scheduler nouns | `clojure.core.async.flow`'s own vocabulary — adopted Path A, `seon.flow` implements `flow.spi`; `reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj` |
| `(sliding-buffer 1)` tap | latest-wins mailbox | core.async's own newest-only delivery; `reference-code/core.async/src/main/clojure/clojure/core/async/impl/buffers.clj` |
| tuple (`:db/tupleType`) | small limited vector, ordered many | Datahike's single-value ordered construct — one datom, whole-value replace; homogeneous cap 8 (fork lift queued); cardinality-many is a SET (`reference-code/datahike/src/datahike/index/persistent_set.cljc:133`) |
| `my.agents.<id>` | agent workspace, sandbox ns | each agent's scratch namespace; a real namespace has at most one assigned agent (`:seon.agent/namespace`, unique) |
| `:seon.render/ai` + `:seon.render/html` | widget, view-model, dual render | the ONE render contract: two projections on one unit — `ai` derives into agent context, `html` rides the flow pipeline to canvas (focal, `:seon.render.canvas/content`) or a surface (context render); no third surface noun |
| wire (external crossings only) | wire for anything in-process | "wire" is reserved for a crossing that LEAVES the process to an external service — the provider HTTP request, the browser SSE connection (owner ruling 2026-07-29). Internal transport is channels, flow, and database facts, and is never called a wire; this refactor deleted the internal wire protocols and nothing may reintroduce the word for them |
| block | widget, component, panel | ONE render function's identified output: the function + its stable element id + its current bytes — the unit of rendering, morph targeting, equality suppression, and churn ranking (owner ruling 2026-07-29). A page is a scaffold plus blocks |
| package, keyframe, delta | frame, bundle, snapshot-stream | the DELIVERY units (render-pipeline-design-2026-07-29.md): one revisioned package per change carries delta fragments (changed blocks) and/or the keyframe (every block, serialized once, multed to all tabs); a revision gap snaps to keyframe; new page loads serve from the latest keyframe with zero re-render |

This table is maintained: when a boundary term is settled, add its row in the
same change, and when the meaning spans an integration boundary, name the
defining source on BOTH sides (ours ↔ the dependency's) so a reader can
verify what the word really means without archaeology.

Database vocabulary is the dependency's vocabulary, never a Seon wrapper noun:

- **database value** — the immutable ordinary value sent across the protocol;
  it carries `:db-name`, basis transaction `:t`, `:as-of`, `:since`,
  `:history`, and `:datahike/commit-id`;
- **basis transaction** — the database value's `:t`;
- **commit ID** — Datahike/Proximum's `:datahike/commit-id`;
- **connection ID** — Datahike's process-local connection identity, whose
  self-writer form is `[store-id branch]` and whose remote form also includes
  the writer backend;
- **store ID**, **branch**, and **branch head** — the individual Datahike and
  Proximum facts used by branch management; and
- **transaction report**, **db-before**, and **db-after** — the committed-change
  vocabulary.

Do not introduce or preserve generic database “coordinate”, “point”, or
“attachment” maps. `seon.db` clients exchange database values. Internal code
that must call Datahike or Proximum passes the required store ID, branch, basis
transaction, or commit ID by those names. A UI screen location or ordinary
English coordination is unrelated and is not covered by this database rule.

Name every interface only after reading the maintained sources on both sides
of that interface. Reuse the producer's concrete output terms and the
consumer's concrete input terms; when they differ, translate fields directly
at the boundary. Never invent a third umbrella noun merely to make the two
sides sound uniform. Record the source files and selected dependency revisions
that establish those names in the owning PRD's dependency ledger.

Ground every name in the source material (owner ruling, 2026-07-23). Seon is
transaction processing: data arrives from sources (user, scheduled fires,
remote-call responses), is transformed, partly stored, and partly emitted as
side effects. Stay close to the metal — no clever coinages, no
object-oriented reconceptualization of what the dependency already names.
Before naming anything at an integration point: vendor the dependency as a
git submodule under `reference-code/`, read its code, find the ideal
integration point, and use the same names it uses (Datahike says transaction
id — never rename that to "coordination"; a build artifact of pre-parsed rows
is transaction data for initialization pages, not a novel noun). Invented
vocabulary drifts from the dependency and causes integration and debugging
mistakes; grounded vocabulary is free documentation.

Current route truth is database data in `src-old/seon/route.cljs`: `/` is root's
system view, `POST /agents` creates an agent, and `/agent/{id}` is its page.

## Data-oriented Clojure rules

Use the `data-oriented-clojure` skill before writing or reviewing Seon Clojure.
The compact invariants are:

- immutable data and pure transformations first;
- derive projections instead of storing them;
- fully namespaced map keys and database attributes, without exceptions;
- schemas colocated in the real code namespace that owns the data;
- errors as values at agent/runtime boundaries;
- one namespaced map in/out for API-like functions, or fully named/spec'd
  positional arguments for ordinary functions;
- every public function has a correct Malli input/output schema;
- no `:type`/`:kind` entity taxonomy, stored nil, `[:maybe X]`, bare key, or
  `:any` without a proven genuinely polymorphic boundary.

An entity is its attributes and connections. Query attribute presence to find
entities, use a unique identity attribute to identify one, and follow refs to
relate/remove it. `:seon.entity/id-attr` enumerates identity attributes; it is
not a kind stamp.

Register shared shapes once and reference them. If the Malli→Datahike bridge
cannot express the required referenced shape, fix the bridge rather than
inlining copies.

`seon.db` is the sole application database API. Outside `src-old/seon/db/`, never
call `datahike.api` directly. The pod forwards writes through
`seon.db.replica`; the JVM server alone owns durable Datahike resources.

An explicitly selected config manifest reconciles its declared subset into
database facts. Runtime reads the database, not environment variables or the
file. Config is optional when reopening an existing database; explicit apply
repairs drift and writes nothing when converged.

Provenance is minimal transaction metadata: resolvable `:seon.db/user` and
`:seon.db/process`. Do not copy provenance onto domain entities as
`created-by`, `created-at`, eval, or turn projections.

## Reactive context and code as data

Agents see derived views of the database. A new warning/status/context feature
is a render function that queries current facts and omits itself when the facts
are absent—not a notification queue, acknowledgement flag, or stored render.
Cross-agent visibility follows naturally from queries that do not filter by
agent. Cache measured expensive derivations; do not bifurcate the architecture
into stored-fast and derived-slow paths.

Live/streaming-style updates stay on the ONE database path, made cheap by
construction rather than by a side channel:

- high-churn presentation state (streamed reply partials, progress text)
  rides CHANNELS under the transport law above (owner ruling 2026-07-28,
  superseding the earlier no-history-attribute design): a sliding-1
  channel into the render proc gives latest-wins by construction, the
  database commits only the settled fact at the terminal, and a crash
  loses nothing recovery needs;
- efficiency is the existing chain, not new machinery: attribute-indexed
  interest delivery wakes only subscribed renders → equality suppression
  skips unchanged renders → the per-tab sliding-1 tap gives a slow
  browser the newest morph only. The settled UI remains a pure function
  of the database value; reconnect = repaint (in-flight partials are
  superseded, never replayed).

Ephemeral display state belongs on channels; durable truth belongs in the
database. The transport law above is the one fence — do not add a second
durable path and do not commit in-flight partials as facts.

Core source, eval history, and analyzer state are views of one code corpus.
`:seon.fn`, `:seon.ns`, and `:seon.schema` facts come from the analyzer plus
source strings. Do not reparse source with another graph builder, replay every
eval to resume, or introduce a generated bootstrap authority.

Comment grammar is agent-facing: `;` is prose/inline explanation, `;;` is a
code-block comment above a form, and `;;;` is runtime-structure demarcation.

## Runtime contracts

- Timeouts and magic numbers are design smells until proven backstops
  (owner ruling 2026-07-24). A deadline may only guard genuinely
  unobservable external state (a remote HTTP call, a foreign process).
  For anything the process or database can observe — a thread
  completing/dying, a datom committing, a child exiting, a phase
  settling — detection must be event-driven (supervision, completion
  callbacks, `listen!`), with the clock kept only as a loud last-resort
  backstop whose firing is itself a bug report, never the primary
  failure path. When you meet a tuned constant, first ask what
  observable event it is standing in for. Corollary (explicit goal,
  same ruling): wherever an interface can be changed to EXPRESS its
  dependencies and publish its own readiness — a start that returns a
  completion, a resource that announces attached, a consumer that
  declares what it awaits — change the interface; do not bolt
  detection onto an interface that hides the event.
- Nothing throws into the agent loop. Every failure is a `:seon/error` value;
  catch sites record the fault as database data. Agent mistakes never crash the
  pod. Core faults follow the one `:seon.config/on-core-error` dial.
- Fail LOUD in development, never crash in production — one config dial, not
  per-site judgment. Degraded fallbacks (e.g. codec text-serialization of a
  value with no ordinary wire projection) always warn loudly; in development
  the same event panics so it is found immediately (owner ruling R41).
- Classification rules are COMPUTED, never name-based. A namespace-prefix or
  literal-list trust/privacy/placement rule is a hand list; derive the fact
  from provenance, the corpus, or the artifact inventory instead (R34
  precedent).
- Instrumentation is derived from the database program graph and reapplied on
  hot reload. Wrong schemas/calls are fixed at the source. The kill-switch is
  only emergency recovery.
- `^:async`/`await` is valid only inside a `^:async` function, and only in
  CLJS leaves. Agent-facing eval awaits returned Promises; long work remains
  addressable through its result symbol. The self-host (cljs.js) engine is
  interim and dies at the great deletion — never invest in it; read the
  `clojurescript` skill before touching it.
- The database, not atoms, owns important durable state. Atoms are acceptable
  only for genuinely process-local artifacts such as compiler state, a
  connection, or invocation-local coordination.
- Human-visible sizes are always estimated tokens through
  `seon.ai.tokens/estimate`, never raw character counts. Storage may keep a
  character projection, but display converts it.

Detailed ownership belongs in `src-old/seon/AGENTS.md` and its child authorities.

## Git and shared-tree safety

Multiple agents share this working tree. Preserve unrelated edits and untracked
files. Safe operations are read-only Git inspection and staging explicit owned
paths. Do not use `git add -A`.

The Git index is shared too: another lane can stage files after your cached
name check and before a plain `git commit`. Every agent commit must therefore
be path-limited (`git commit --only ... -- <explicit-owned-paths>`). Add a new
untracked owned file explicitly first, then name it in the same path-limited
commit. A clean `git diff --cached --name-only` is useful evidence but is not a
locking mechanism and never makes an unbounded commit safe.

The shared checkout is the normal collaboration model. Do not create or move
work into a Git worktree unless extreme circumstances make shared-checkout work
unsafe or impossible. Assume other agents are editing the same source tree;
coordinate through narrow ownership, inspect overlapping diffs, and preserve
their changes. Agents normally isolate live verification with their own named
pod/cluster and process coordinates, not another source checkout. If concurrent
work creates a real concern that these rules do not cover, ask the owner before
introducing a worktree; otherwise roll with the shared system.

Separately launched Codex tasks are still directly coordinateable: use the app
thread list/read/message tools to identify the task by checkout and purpose,
then request a coherent commit or explicit path handoff before editing overlap.
Do not infer anonymous ownership indefinitely from `git status`, and do not add
a `COORDINATION.md` status diary; the active PRD roadmap remains the durable
ledger while thread messages handle ephemeral ownership.

Branch switches, history changes, file discards, resets, and other destructive
Git operations require user coordination. Never run `git reset --hard` or
`git checkout --` to clean a shared tree. Commit coherent gains frequently
with clear messages.

## Skills and editing

Use a matching `.agents/skills/*/SKILL.md` before specialized work. Skills are
workflows, not substitutes for source reading. Especially:

- `data-oriented-clojure` before any Seon Clojure;
- `seon-flow-architecture` before touching a proc, graph, channel, buffer,
  workload, wake, or fault — or before designing any new runtime mechanism;
- `data-modeling` plus `datahike` for schemas, queries, and transactions;
- `clojure-testing` for test mechanics and the database fixture;
- `repl` for how an agent's own forms are read and evaluated.

**A SKILL'S BLAST RADIUS IS EVERY AGENT THAT LOADS IT** (owner ruling
2026-07-29). A wrong line in code fails one lane; a wrong line in a skill
silently poisons the context of everyone who invokes it, and they will
trust it precisely because it is the curated guidance. So:

- every factual claim in a skill carries a `file:line` or a named research
  document, and is verified against CURRENT source when written OR touched;
- a claim that cannot be verified is DELETED, never hedged — "probably" in a
  skill is worse than silence;
- designs that are ruled but unbuilt are marked explicitly (`[TARGET]`) so no
  one writes code against them;
- a skill that no longer matches the system is a HIGH-PRIORITY defect, not
  documentation debt: fix or retire it the day it is noticed;
- new or substantially changed skills get an INDEPENDENT verification pass
  that trusts nothing — the same adversarial standard as a landing wave.

Use `rg`/`rg --files` for search and `apply_patch` for edits. If repeated patch
attempts fail, the function or document is too complex—refactor it.

## REPL-driven development

**Start every Clojure change at a running system, not at a file.** The REPL is
the first design and diagnosis surface; checked-in source and tests remain the
durable authority. A plan written without a probe is a hypothesis — this
program has repeatedly had six-of-six assumptions falsified in one sitting.

If you are spinning up fresh, this is the loop:

1. **Get a live system.** `bin/seon status` shows running clusters. Boot your
   OWN scratch cluster for anything you intend to change (`bin/seon start
   <your-name>`); never reset, bounce, or write to a cluster someone else is
   using — clusters are sovereign and cheap (a fork is ~17 ms). Load-only
   probes need no cluster at all: `clojure -M:dev` gives you the namespaces.
2. **Reach it.** `mcp__seon_cljs__runtime_status` lists live clusters and their
   ports; `mcp__seon_cljs__eval_clj` evaluates in the selected cluster's JVM.
   Qualify the cluster when more than one is live — ambiguity must fail, never
   silently pick. The default session is right for disposable probes; use a
   named `session_id` only when later forms intentionally depend on `*1`/`*2`.
   A named-session restart loses process-local values only, never database
   truth: choose a fresh session id and continue.
3. **Reproduce with one small form**, and read the COMPLETE returned envelope —
   not just the value. Inspect live facts, the installed schema, and the
   immutable database value before inferring a cause.
4. **Call the owning function directly** with representative data. When the
   question is about a dependency, read its source in `reference-code/` at that
   boundary rather than rebuilding its semantics from memory.
5. **Design in the REPL**: evaluate the proposed shape or transformation on
   immutable examples that expose inputs and outputs. Write to the database
   only when the experiment requires it, then inspect the transaction result
   and the resulting datoms.
6. **Edit the one owning namespace**, let hot reload apply it, and rerun the
   same form against the same live evidence. Re-evaluating a `defn` changes
   running behavior immediately — including flow proc behavior, because procs
   reference their step-fns as vars. Restart only for load-time config,
   bootstrap, process, or artifact behavior hot reload cannot exercise.
7. **Persist the regression**, run the smallest affected gate, then verify the
   user-visible fact, page, log line, or process transition. A change proven
   only by a passing test is not proven.

Use one form at a time unless batch semantics are the subject of the probe. Do
not leave speculative definitions, sessions, or mutations as hidden proof:
record the decisive form and its result in the active PRD when it changes the
plan, and put reusable probe scripts in `tmp/` (project-local, visible to
everyone) rather than a private scratch directory.

## Dev feedback and testing

Live diagnosis and narrow behavioral verification start through the repository
MCP server loaded by `.codex/config.toml` and `.mcp.json`:

- use `runtime_status` to see which clusters are live and reachable;
- use `eval_clj` against the selected cluster's JVM (its stateful `io-prepl`
  session), qualifying the cluster whenever more than one is live — an
  ambiguous selection must FAIL rather than silently pick;
- use the default session for disposable probes and a named `session_id` only
  when later forms intentionally depend on `*1`/`*2`/`*3`;
- treat a named-session restart error as lost process-local REPL state and
  choose a fresh session id; never infer that database state was lost; and
- keep correctness tests in `bin/test`. MCP eval is the first probe, not
  another test runner.

`eval_cljs` and the pod it addressed are GONE (CLJS off, owner ruling
2026-07-27); nothing in the fresh system evaluates ClojureScript.

The server discovers live clusters from their advertisements and resolves the
selected cluster's prepl coordinate. A bare id present in several clusters
must fail as ambiguous. After changing MCP code or client registration,
restart the Codex or Claude task: already-running clients do not reload stdio
server definitions or tool schemas.

The edit hook parses changed Clojure files and requests conservative affected
tests through one public operation:

```bash
bin/seon test changed --path src/seon/cluster/run.cljc
```

Parse errors may block malformed edits. Test results are advisory and never
undo a refactor; obsolete tests may need deletion. Read the retained report and
full log rather than rerunning just to obtain output.

Tests find design issues; structure dissolves them. When a failure class
appears, do not fence the symptom with point tests — move the invariant
to one choke point (a total codec, an admission gate, a computed
discovery rule, a derived classification) and keep ONE regression per
class. Schemas generate edge cases: generative round-trips are standing
totality properties, not test suites to enumerate. Every proof must be
claimed by a recurring surface — a test invisible to every runner, or a
live proof that ran once in a lane, counts as NOT COVERED. Fixture load
paths are not the live boot path: schema, acquisition, and process
changes always need the reset-boundary live proof, because that is a
different failure class than any fixture can see. Before writing a
test, ask: which class is this failure, and what construction makes the
class unrepresentable? The test you then write is the one that proves
the class dead.

There are two testing surfaces:

1. code correctness through `bin/test` — the one gate for the fresh system
   (a bare run is the full suite; pass namespaces to focus it);
2. agent/model evaluation through `src-inspect-ai/`.

Do not restore the gym, add bespoke drive scripts, or create another runner.
Use focused tests while iterating, then one relevant complete checkpoint at the
natural unit boundary. Tests assert facts, transitions, envelopes, DOM
identity, omission, idempotency, and structure—not exact context prose.

When exercising a real agent, use long-term planning plus database-backed
memory: a multi-step plan that survives restart, and schema'd facts stored then
queried in a later turn. Do not use old workout/trading toy scenarios.

## Resiliency — churn is weather, not a blocker (owner ruling 2026-07-29)

The shared tree and the shared machine are the normal collaboration model.
Clusters, JVMs, ports, and advertisements come and go WHILE YOU WORK — another
lane stops a JVM, the owner bounces a cluster, a shared JVM takes a SIGTERM, a
build churns, a database is reset. None of that is a failure. **ADAPT AND
CONTINUE.** An agent that stops and reports "blocked" because its environment
moved has misread the situation; stop only for a genuine implementation
dependency (something live still needs a path whose owner cannot serve it yet),
and say exactly what that dependency is.

Concretely, when the ground moves under you:

- **Your cluster vanished** (advertisement gone, prepl refused, pid dead):
  re-derive from `bin/seon status`, start a fresh one, and re-run your probe.
  Nothing durable was in that process — facts live in the store, and a cluster
  is a fork away.
- **A stale advertisement** names a dead pid: it is a leftover file, not a
  live claim. Clear it and proceed.
- **`bin/seon start` adds to an EXISTING JVM** when one is running, so your
  cluster may share a process with another lane's and die with it. For work
  that must survive, boot your OWN operator root — that is the isolation
  pattern the seam re-audits use.
- **A long-lived JVM serves the code it loaded at start**, not the code in the
  tree. If correct code fails there, suspect staleness before suspecting the
  code (this trap cost a lane an entire chunk chasing a phantom).
- **Another lane's in-flight edits made the gate red**: name whose failures
  they are and carry on with your own boundary; do not fix or steer their work.
- **Your own long operation died** (API error, killed wrapper, machine load):
  the work committed so far stands. Re-read the tree, resume from what is
  actually there, and never assume your uncommitted state survived.

**Recursive deletion NEVER follows symlinks.** A fixture cleaning its own
scratch directory followed repository symlinks on 2026-07-29 and deleted 55
tracked paths — all of `src/` and thirteen vendored submodule trees — while a
suite was running. Any recursive delete walks without `FOLLOW_LINKS` (or
`lstat`s each entry) and refuses a path that resolves outside its own root,
derived from that root rather than from the process working directory. Plant a
symlinked sentinel in the cleanup regression and assert it survives. The lane
that did this behaved correctly afterwards — it detected the damage, captured
an exact inventory, and refused to self-recover without authorization, which
is why it cost ten minutes instead of a day.

The rule behind all of it: derive current state from the system rather than
remembering it, keep your own work committed in small coherent slices so churn
can only ever cost you minutes, and treat every restart as the free resilience
drill it is.

## Operating the system

`bin/seon` is the one development operator:

```bash
bin/seon up
bin/seon status
bin/seon logs pod --follow
bin/seon restart
bin/seon down
bin/seon cluster reset default  # destructive: wipes that test database
```

The supervisor owns watcher → database-server → pod ordering, locking,
readiness, logs, and shutdown. Do not launch its internal processes separately
or kill them blindly. `up` rebuilds current code and starts incremental
watching; only `--open` launches a browser.

NEVER USE A SESSION SCRATCHPAD OR SYSTEM TEMP DIRECTORY (owner ruling
2026-07-25). Some harnesses hand an agent a private scratchpad under
`/tmp`, `/private/tmp`, or a per-session directory and invite it to work
there. Do not. That directory is deleted without warning, it is invisible
to every other lane and to the owner, and nothing in it can be reviewed,
committed, or reproduced. Work that lives only there is work that did not
happen.

Everything goes in the repository from the first keystroke:

- throwaway probes, REPL scripts, and one-off experiments → `tmp/`
  (project-local, gitignored, survives the session, visible to everyone);
- anything whose RESULT is evidence — a measurement, a repro, a
  benchmark → commit the script and record the numbers in the owning
  PRD's `research/` directory, because an unreproducible number is an
  anecdote;
- anything that will run again — a crash harness, an adversarial suite,
  a regression — is real code. New nucleus tests belong under fresh `test/`;
  State A tests remain under `test-old/` until explicitly adopted. A separate
  top-level package is also valid; a scratch directory is not.

The test: if the machine were wiped right now, what would be lost? If the
answer is anything, it was in the wrong place. Leave ACME
alone while another lane owns it. After runtime/source changes, prove the
default cluster before coordinating a downstream update.

`bin/acme` is a semantic wrapper over the same operator with the ACME artifact
flavor, not a second supervisor. It owns a separate process directory, cluster,
Shadow cache, `acme-client` output, and dynamic endpoint files. `acme/deps.edn`
adds only downstream source/dependencies; the root `:writer` and `:cljs`
aliases remain the authority for Seon's maintained Datahike, Konserve,
superv.async, and partial-cps coordinates in both default and ACME. Do not copy
or override those shared forks downstream.

## Provider and optional subsystem boundaries

The default LLM provider remains DeepSeek. Hosted providers are
DESCRIPTOR ROWS under the config singleton selecting one of two wire
cores (`:openai-compat`/`:anthropic`) — adding a hosted provider is a
row plus a catalog entry, never a new adapter arm. Local-worker
providers (DiffusionGemma/typeahead) stay on their documented compiled
dispatch. DiffusionGemma is opt-in only through
explicit provider configuration; never activate it as a side effect. Embeddings
use the one `seon.embed`/Vertex path when `SEON_EMBED` is enabled. Credentials,
project IDs, and service-account files stay outside Git. Details live in
`src-old/seon/ai/AGENTS.md`, `docs/seon/reference/llm-adapters.md`, and the
embeddings PRD.

## Key entry points

- `docs/TRANSFER_PROMPT.md` — **the orientation**: read it whole if you are new
  to Seon (what it is, why archaeology precedes design, which skills to load,
  the warts, the mentality, how the owner works);
- `docs/seon/architecture/architecture.md` — intended system map;
- `docs/prds/sci-execution-runtime/plan/README.md` — **the one ordering**: the
  numbered owner rulings, plus `unsettled.md` (current state) and
  `history.md`;
- `docs/prds/sci-execution-runtime/AGENTS.md` — current chunk runbook;
- `docs/conventions.md` — code/schema patterns;
- `src-old/seon/AGENTS.md` — State A one-mechanism and runtime ownership table;
- `src-old/my/AGENTS.md` — State A agent-facing toolkit constraints;
- `AGENT.md` — thin delegated-lane compatibility adapter.
