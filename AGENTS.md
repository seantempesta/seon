# Seon — shared instructions

Seon is a Clojure system in which agents write, test and improve their own code in a
running JVM. Schemas make data storable and queryable; functions and declared renders
make it executable and visible. Our outside agents are Seon agents: use the same
branch, REPL entrance, checks and reviewed merge. The orchestrator acts as root.
Build toward a codebase of 10,000 lines or fewer and sub-second ordinary operations.

This is the shared instruction authority; `CLAUDE.md` is a symlink to this file.
Keep laws here, mechanisms in [architecture](docs/seon/architecture/architecture.md)
and skills, and implementation decisions in the owning spec. Read the assignment,
nearest instructions and named authorities end to end. Verify targets against the
running system: a specification or source citation is not evidence of installation.

## Think like a principal Clojure developer

- **Take things apart.** Give each part one role and reason to change; separate braided concerns.
- **Be patient at the REPL.** State the problem, knowns and unknowns; inspect real
  values and call the owner until you can predict the result. Refuting a cause is progress.
- **Read the dependency first.** Datahike owns transactions, branches and history;
  Malli owns compiled schemas and contracts; SCI owns contexts; core.async owns graphs;
  konserve owns storage and reclamation. Compose their mechanisms instead of copying them.
- **Write small functions of data.** Use plain namespaced maps, immutable transformations,
  core functions and library calls. Split long functions by responsibility; avoid bespoke
  objects, protocols and accessor layers that hide information.
- **Fix the producer, not each symptom.** Verify the cause before naming it. A workaround,
  exemption, fallback or patch elsewhere means return to the data flow and find the owner.
- **Prefer values to places.** Derive from held immutable values; arguments/channels remove hidden order.
- **Grow by accretion.** Require no more, promise no less: open maps, validated declared members,
  optional additions and new names for changed semantics.
- **Make failure local and loud.** Each part must fail without corrupting its neighbours
  or blinding diagnosis. Never interpret silence, missing subjects or stale observations as health.

## Work in this order

1. **Understand.** Read the owning spec, Git history, dependency source and first-party
   caller before designing. Record the pinned revision, `file:line`, guarantee, inputs,
   recomputation event and cost. Verify a seam exists upstream before calling it the
   library's; prefer upstream, improving a maintained fork only to remove real duplication.
   For validation, contracts, instrumentation and error shapes, begin in
   `reference-code/malli/src/malli` and cite the Malli function used.
   Load [data-oriented-clojure](.agents/skills/data-oriented-clojure/SKILL.md) before
   Clojure design or edits; load the relevant REPL, testing, datahike, modeling and flow
   skills at their boundaries. Verify touched skill claims against `file:line`; correct
   stale skills as high-priority defects; delete unverifiable claims, never hedge them.
2. **Probe.** Start Clojure changes with `bin/seon status` and MCP `runtime_status`.
   Use `eval_clj` with explicit root and cluster; JVM mode has no custody, so pass the
   connection, e.g. `(seon.cluster.boot/connection "default")`. SCI mode mutates its
   shared context: keep probes disposable. Report and record degraded/missing tools first.
   Reproduce with one small form, inspect the complete envelope and installed schema,
   call the owner, then try the smaller algorithm. Record exact forms, values and timings.
3. **Design the smallest composition.** Begin the owning spec or landing note with one
   or two lines naming what exists, its data flow and the principal Clojure approach.
   State what disappears, each operation's cost and target complexity, concerns separated,
   and the simplest alternative: dependency seam, existing function or deletion.
   More than about 100 added lines is presumed wrong: stop before writing and report the
   smaller alternative. Net deletion does not excuse new machinery. Research existing
   libraries on the web before writing replacements in a shrinking assignment.
   If a fix, cleanup, conversion or replacement grows code, justify to the owner before
   integration what it adds, why nothing smaller works, and what it lets go later.
4. **Review before implementation.** Obtain independent design review for simplicity,
   dependency use, correctness and these laws; root rules on every finding and gives the
   implementer the reviewed design and findings. The sole exception is an obvious small
   bug fix at a named seam, stated in the assignment. Review actual diffs and evidence;
   ask what the REPL taught, what each function owns and whether it bends another owner.
   A commit is not proof. Resolve already-ruled questions from their authority.
   For genuinely new decisions with hours of cross-owner work or unclear guarantees, stop
   production edits and offer exactly three priced options: simplest viable constraint
   first, recommendation marked, each with guarantee, cost and sacrifice.
5. **Change one loadable slice.** Edit the owner in place; no second registry, renderer,
   runner, retry, cache or version-two namespace. Convert every caller with a retirement.
   Script mechanical sweeps once across the full set, then lint, load and hand-fix exceptions.
   Keep the ordered plan as the design: integrate corrections into its owning sections,
   never a parallel spec or appended ruling log. Correct false current claims in the same
   commit; keep dated audits and evidence outside the plan directory.
6. **Prove the installed change.** Verify publication, adoption, reload and arming, then
   repeat the probe. A shell edit or disabled hook proves none of these. Prove schema
   changes incrementally on a branch of a live store; inability to adopt is a publication
   defect, never a reason to boot from zero. Observe browser paint separately.
7. **Report honestly.** Report broken things first, then evidence, changed paths, commit,
   sizes, net source/test lines, timings, memory and verification limits in the landing note.
   Unavailable evidence is not a pass. Commit measurement scripts; publication changes need
   their clock row. Put disposable probes in `tmp/`, reusable checks in `test/`. Search and
   verify existing issues before recording one per defect class; evidence must outlive chat.
   The issue index is the owner's ranked schedule: assigned agents never edit it.

## One JVM, many realities

Verify this design model's installed seams and outstanding proofs in the owning spec and
[architecture](docs/seon/architecture/README.md); never claim a target API exists.

A **cluster** is a Datahike branch plus its agents and shared plumbing. A **branch** is
`d/branch!`, a pointer to shared immutable indexes, never a store copy or a system to boot.
One JVM hosts many clusters. Boot opens the REPL before store acquisition, then builds
process → store → facts → flow, publishing readiness at each layer. One process root
holds the store's lifetime lock; Datahike serializes transactions. Bootstrap selects
paths and binds; running configuration is database facts, secrets are environment-variable
names. `seon.config/defaults` is a compiled constant, not a cluster's configuration.

The **program** is rows: functions, tests, schemas, namespaces, render pairs, contracts,
analysis and test evidence keyed by exercised definition digests. Turns, evaluations,
messages, errors, tasks and live results are data and never merge as program. Declare
that partition on entity schemas rather than maintaining a hand list.
Default's program rows and **loaded namespaces** derive from files. A JVM has one set
of compiled Vars; other branches interpret differing rows and affected callers in SCI,
binding compiled Vars for the rest. Compute host-bound status per declaration, never
from a namespace roster; refuse unsupported overrides by name instead of running other code.

An agent's **context** is its SCI world; task-selected custody points to live cluster head
or isolated branch. Use the one shared agent REPL entrance, including filesystem agents
on their own named branches. Retain private defs/atoms/objects while advancing program
changes in place at idle boundaries.
Reacquire from the branch head at turn start, reusing content-valid derivations. Stability
comes from branch custody; no reload may run under an evaluation. A **reload** uses
`require :reload` for changed namespaces and dependents. Ordinary clusters keep their
program; selected development clusters adopt explicitly. Every program function is
callable: prompt visibility grants no execution permission; consumer domains stay downstream.

**Merge** admits program rows onto an intermediate branch. Keep conflicts there for
repair; same-identity conflicts become root tasks. Test the combined program: contracts,
task tests and every current test reaching changed functions must pass; missing coverage
refuses by name. The writer checks the tested head; only named root/owner acceptance
advances the cluster pointer. **Write-back** uses that same gate, checks file bases,
stages exact-span changes and reindexes to prove equivalent declarations before installation.
Product agents use declared evaluation/adoption/test requests, never shell self-modification.
Private experiments remain possible; local green never implies shared acceptance.
**Unlink** removes a branch from the roster; retention GC reclaims it, without per-branch cleanup.

## Laws for data and contracts

- **Pass the world explicitly.** Carry database/connection, projection, environment,
  render profile, proc and settlement inputs, including profiling/test ownership.
  Custody elides agent db/conn only; it does not authorize more ambient services.
- **Decide at the authority.** Derive at the writer or supply its decision; validate a
  pre-read's basis there if it could change. Mirrors must be derived, drift-checked or
  explicitly historical observations, never silently treated as current state.
- **Declare facts at their producer.** Search the registry before adding a key. Use
  fully namespaced keys, one schema per identity and `seon.id`; avoid name guessing,
  text parsing and invented entity kinds. Production regex requires owner permission;
  ordinary `rg` searches do not. Enums describe real bounded states or dependency grammar.
- **Preserve native values.** Absence is no key, never stored nil; symbols remain Datahike
  symbols, not strings converted at each reader. Prefer `find` or `get` with a sentinel;
  `contains?` tests vector indices and also finds nil-valued map keys.
- **Validate final owning values.** Include swept refs and identity-less components;
  refuse missing children, cycles, multiple owners and exhausted bounds. Retraction
  deletes; history answers what existed. Required refs refuse invalidating deletion,
  optional refs may sweep, components cascade. Repair surviving declaration references
  in the same transaction or refuse; never invent placeholder functions/tests.
- **Separate storage from observation.** Store a value when it must outlive its subject.
  Derive pulled shapes from entity schemas and selectors; never silently truncate pulls.
  Require each function/test's analysis digest: empty cardinality-many is not proof of work.
- **Use the database owner.** Direct `datahike.api` calls belong only in `seon.db`,
  store/registry, classified branch-custody owners and system listeners. Keep `my.*` thin.
  Put provenance on transactions; derive write bounds from it and retain proposed data
  in bounded refusals. Reconcile config differences; resolve function symbols to current rows.
- **Contract every admitted function, including private ones.** Use complete, well-formed,
  fully namespaced Malli schemas and reaching tests. Compile touched contracts against
  the packaged projection; use declared schemas or registered named predicates, never
  anonymous contract functions or incomplete forms. Justify polymorphic boundaries.
- **Keep entry policy separate from merge.** Schema/test/test-first entry defaults to
  `:gate`, refusing before evaluation; explicit `:warn` admits experiments with declared
  diagnostics. Preserve malformed authored contracts as data, never arm them or substitute
  stale wrappers. Arm every valid contract: invalid inputs prevent entry, invalid outputs
  refuse. Every check must pass before merge, regardless of entry policy.
- **Match supplied defaults by declared schema name.** Caller arguments win; structural
  resemblance is not identity. Use the [data guide](docs/seon/architecture/data-modeling-guide.md).

## Laws for failure, execution and presentation

- **Declare errors as flat data.** Each error has one named schema over `:seon.error/base`;
  arities enumerate exact error alternatives. No general error predicate, copied union,
  registry-wide union or discriminator stamp. Deduplicate distinguishing content through
  the identity owner; occurrences are components, not undeclared map copies. Contracts
  alone do not prove body-derived error unions; that needs its producer, checker and regression.
- **Keep the whole cause.** Catch only declared cases or resurface class, message, `ex-data`,
  cause chain and Seon frames. Refusals name operation/member, expected shape and offending
  value; missing evidence is typed unknown, never nil/default/message-only success.
- **Return agent mistakes; store and deliver core faults.** Return declared `:seon.error`
  mistakes to their agent. Commit every other failure with `:seon.error/chain` at its owning
  boundary, then wake the responsible namespace agent or root through the ordinary route;
  no overload channel may drop it. Recording without delivery is a defect.
- **Panic visibly.** `:seon.config/on-core-error :panic` is the development/default policy:
  throw to the caller, stop the failing graph and show failure in status, page and REPL;
  keep the JVM/REPL available. `:record` keeps the rest running after storage and delivery.
  If storage fails, panic in both modes. Diagnose at the REPL; no fallback store or retry loop.
- **Let Flow own execution.** Give each agent a graph; procs declare `:io` or `:compute`,
  transforms reference Vars, topology changes rebuild graphs. Add no central scheduler
  or dispatcher. Listen before deriving work; channels carry only rederivable or replaceable
  data and buffers express that loss policy. Durable recovery payloads belong in facts/blobs.
- **Bound admission and await actual exit.** Every execution surface declares its bound;
  name the missing terminal event when it fires. Timeout/cancellation is not termination:
  retain ownership/resources and prevent overlap until exit. SCI interrupts interpreted
  code, not arbitrary host calls; retain isolation where termination cannot be guaranteed.
- **Recover facts, not execution.** Close interrupted turns and mark unfinished evaluations;
  never resume interrupted execution or serialize private objects/live results. Persist
  exact shown text; settle its durability contract before deleting other durable evidence.
- **Derive wakes and completion.** Use listened datoms and a qualifying ordinary reply's
  basis; openings/system-only turns answer nothing. Handling is separate, message subject
  is literal, `:seon.message/from` marks inside wakes. Refresh each distinct changed read
  from latest evidence before a turn; append changed reads, never replay writes/effects or
  rewrite history. Compaction clears evaluations and regenerates the opening. Archive
  agents; preserve task/agent identity on loud, resumable budget exhaustion.
- **Render ordinary values totally.** Use one AI/HTML pair per entity schema, scalars in
  its block, components/declared queries in theirs. Default to the attribute-map printer;
  failed rendering gives a diagnostic. Compose error base and satisfied schema renderers.
  An attribute request stays an attribute request; report ugly output and estimate tokens.
- **Apply presentation limits once.** Only AI render functions/value renderer clip; save
  their exact text, select whole prompt units, never clip HTML or historical units again.
  Keep query-work bounds and deadlines separate; elisions name bound, count, path and requery,
  with floor hits counted. Derive missing profiles at entry or refuse explicitly.

## Laws for proof and performance

- **Test the real system with injected worlds.** Use plain `deftest` or contracted test
  functions declaring their inputs. Build worlds through canonical writers/helpers,
  surface every refusal, prepare once, share reads and branch writes from a captured commit.
  Use real SCI, armed contracts and fixed render profiles; substitute only external effects
  as explicit values. No hand-built rows, bespoke fixture machinery or Var redefinitions.
- **Run tests as agents run.** Fork the context onto the test branch, inject custody,
  run one body, unlink. Call the agent's owners, never a test-side copy. Ordinary work
  uses the main JVM; only platform/bootstrap, declared destructive and host-bound work
  gets isolated JVMs. If an edit cannot reach the main JVM, report the precise gap.
- **Protect execution ownership.** No JVM-global test state or restoring reloaded roots;
  preserve instrumentation. Never `alter-var-root`, `with-redefs`, `load-file` or `intern`
  over default's Vars; probe in throwaway namespaces/scratch clusters. Stop graphs before
  retracting their facts; release resources even if setup/cleanup fails.
- **Require current positive proof.** Select by current dependencies and each member's
  actual tested program/input evidence; past observed reach is diagnostic. Unknown/red
  cannot reuse green. Record completion even with no armed calls; recording failure is not
  success. Keep one regression per behavior class and destructive isolation until proven confined.
- **Use focused verification.** Within a cut run focused installed requests; at completion
  the orchestrator runs platform/affected integration and the bulk tier once, not per edit.
  For each red: delete tests of deleted machinery, update retired assumptions, or fix the
  surviving owner. Replacement behavior tests land with the cut; do not wholesale-triage reds.
  HEAD must load and the named probe answer. Gate inputs are `seon.test.cache/input-roots`:
  documentation never publishes or widens gates; fix a tool that does so before proceeding.
- **Make bounds honest.** Tests fail beyond their declared bound, default five seconds;
  longer bounds need the measured operation and reason beside the number. Distinguish
  acquisition from warm execution; priming never excuses timeouts. The
  [testing skill](.agents/skills/clojure-testing/SKILL.md) identifies installed enforcement.
- **Make ordinary work sub-second.** Profile every contracted call and time boot, reload,
  publication, probes, tests and transactions; measure memory too. Results over one second
  name the responsible armed functions and direct the causing agent to fix the cost before
  continuing, or justify beside the number its proportionality and why it cannot be sub-second.
  Over ten seconds needs explicit owner authorization and an issue even when justified;
  “cold”, “expected” and “known cost” are not reasons. Reject reports lacking these numbers.
- **Make cost follow changed inputs.** Eliminate whole-program work per change and per-call
  work that belongs once per evaluation. Branch creation is not full context construction.
  Hash admitted source inputs; compare commit identities for unchanged adoption. Hot-path
  commits time the same probe on parent and change; fix regressions over 20% or 50 ms before commit.
- **Reuse content-valid work.** Key derivations by what they read: content digests or the
  dependency's upstream change tracking. A commit id identifies a database value, not an
  excuse to recompute after unrelated writes. Link shared caches into every root, snapshot
  and run; use the dependency cache, otherwise Clojure's cache tools. Count misses; recompute
  only reached invalid inputs. Reads/renders/probes/evals must not invalidate derived state.
- **Derive without stamps.** Memoize functions of immutable values; no writer `vary-meta`,
  transaction-carried candidate projection or atom beside a connection. Use Datahike commit
  ids/definition digests for identity, `versioning.cljc` for lineage, `history`/`as-of` for
  time, `d/listen` for notification and the writer for serialization. Cite the seam first.

## Operate and collaborate without crossing ownership

Use installed `bin/seon` help for start/status/open/init/config apply/stop/down/reset/nuke,
never deleted internals. `start --head` moves to committed HEAD retaining store/caches;
reset unlinks and forks from program rows, retaining caches. Nuke replaces one JVM, holds
the sibling store lock through deletion of store/all derived caches and rebuild/boot from
committed inputs, reports full failures and falls back to the newest booting commit rather
than refusing recovery. A competing start winning the replacement gap is never killed or
deleted by the loser. Destructive drills/signals need a root and verified `(pid, start-instant)`.

Keep the development REPL available. Assigned agents never stop or reset default:
report **RESET NEEDED**. Root preserves evidence, batches recovery, reconnects and verifies;
disposable data/private state may not be reconstructible. Root coordinates shared exhaust
and integration. Publish schema resources and consumers together; pause hooks during source
cuts and record pause/resumption. Adopt exclusively through rows → load → arm → record,
excluding conflicting reloads/evaluations. Failure leaves affected execution unavailable
until exact bytes, behavior and arming converge; never call an unchanged record healthy.

One file has one owner at a time, no shared hunks. Root checks
`tmp/orchestrator/file-ownership.md` before every launch/follow-up. Work needing a held
file goes to its owner or waits; agents request new ownership without editing it. At each
check-in launch ready file-disjoint work or name its dependency/collision; no artificial
lane, JVM-slot or prober cap. Address oversized functions/files with shrink assignments.
Resolve ruled questions immediately by citation; ask genuinely new ones promptly.

Work within the assignment and stop boundary; never resume/message/edit another agent's
session or files. Foreign breakage does not stop independent work: use one `seon.test/run`
request on your branch via `bin/test-check [--root ROOT] CLUSTER --ns <namespace>`
(or `--test NS/TEST`, `--changed NS/SYM`, or MCP). Agents never run `bin/test` gates;
root owns cold/platform proof. If the shared tree cannot load, prove HEAD plus your diff
in a `git archive` snapshot with shared caches linked and name the foreign boundary.
No git worktrees without the owner's personal authorization for that specific use.

Preserve unrelated edits: no `git add -A`, `reset --hard`, `stash` or shared-file restore.
Commit coherent slices with `git commit --only -- <owned paths>`; report released paths
so root updates the ledger. Never push or merge beyond explicit owner authorization.
Use native collaboration, the documented launcher or Opus; read the lane skill before
`bin/codex-agent`. Do not nest delegation in bounded assignments, sandbox assigned work,
or overlap your own probe/test JVMs. Cite the existing issue/spec and raw evidence in
assignments; use verify/falsify/probe language. Resume usage-limited agents only with
owner authorization, retaining their identity. Make paid provider runs deliberate.

Treat unresolved dependency/protocol Vars in clj-kondo as stale cache until disproven:
repopulate from the publication classpath with `clj-kondo --lint "$(clojure -Spath)"
--dependencies --skip-lint --copy-configs`, never the test alias's `.`. Do not change
correct code to satisfy stale analysis. Clean owned shells/disposable roots before
reporting, but preserve uncertain resources and unresolved evidence. Inspect actual
live holders, never a self-matching process search; roots younger than the oldest live
test launcher are not sweepable. Recursive deletion never follows symlinks.

Use [grounded vocabulary](docs/seon/architecture/vocabulary.md): Clojure's term, then the
dependency's, then a justified new term recorded once. Keep JVM REPL/SCI evaluation,
turn/evaluation and live result/shown text distinct; cite legacy identifiers without
reviving retired terms. Correct language in scope or file its issue. Source comments use
`;` for prose, `;;` above forms, `;;;` for runtime structure. Add no hypothetical restrictions.
