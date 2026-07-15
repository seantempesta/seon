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

## Current runtime and boundary

Seon is one application with two processes:

- the Node ClojureScript pod owns agents, eval, context/rendering, and the
  Datastar web UI at `http://127.0.0.1:7890`;
- the JVM `seon.db.server` is the sole Datahike writer, committed-transaction
  feed/replay source, and selected heavy-database-work boundary.

The pod reads its local immutable replica and forwards writes over the typed
database protocol. A cluster is one database, a root agent, and task agents;
coordination is database data. The former embedded-Datahike JVM application,
Integrant lifecycle, core.async topology, JVM renderer/web server, and nREPL
application path were removed. Git preserves them at
`runtime-reliability-pre-refactor-2026-07-13`; they are not a second track.

The CLJS sandbox catches model mistakes; it is not a security boundary.
Isolation comes from processes and the database capability surface. Seon is the
core: consumer-specific UI, vendor integrations, and domain models belong in
downstream repositories, never `src/`, `docs/`, or `pod-host/`.

## Documentation authority

There are two documentation layers and no third:

- `docs/seon/architecture/` is the single always-current description of the
  aspirational intended system. It is target-written in present tense, but
  present tense never claims that source already implements the target. Read
  `architecture.md` first, then the relevant domain document. Update it when a
  design decision changes; never put current implementation state, gaps,
  sequencing, evidence, graduation status, or a migration diary there.
- The active program roadmap (currently
  `docs/prds/runtime-reliability/roadmap.md`) is the high-level ledger of
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

Claude Code implementation work uses Opus. Haiku is only for quick reads or
context gathering; Fable is used only when the owner explicitly asks. Codex
uses its configured coding model—Claude aliases are not portable model names.

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

Current route truth is database data in `src/seon/route.cljs`: `/` is root's
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

`seon.db` is the sole application database API. Outside `src/seon/db/`, never
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

Core source, eval history, and analyzer state are views of one code corpus.
`:seon.fn`, `:seon.ns`, and `:seon.schema` facts come from the analyzer plus
source strings. Do not reparse source with another graph builder, replay every
eval to resume, or introduce a generated bootstrap authority.

Comment grammar is agent-facing: `;` is prose/inline explanation, `;;` is a
code-block comment above a form, and `;;;` is runtime-structure demarcation.

## Runtime contracts

- Nothing throws into the agent loop. Every failure is a `:seon/error` value;
  catch sites record the fault as database data. Agent mistakes never crash the
  pod. Core faults follow the one `:seon.config/on-core-error` dial.
- Instrumentation is derived from the database program graph and reapplied on
  hot reload. Wrong schemas/calls are fixed at the source. The kill-switch is
  only emergency recovery.
- `^:async`/`await` is valid only inside a `^:async` function. Agent-facing
  eval awaits returned Promises; long work remains addressable through its
  result symbol. Read the `clojurescript` skill before changing self-host eval.
- The database, not atoms, owns important durable state. Atoms are acceptable
  only for genuinely process-local artifacts such as compiler state, a
  connection, or invocation-local coordination.
- Human-visible sizes are always estimated tokens through
  `seon.ai.tokens/estimate`, never raw character counts. Storage may keep a
  character projection, but display converts it.

Detailed ownership belongs in `src/seon/AGENTS.md` and its child authorities.

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

Branch switches, history changes, file discards, resets, and other destructive
Git operations require user coordination. Never run `git reset --hard` or
`git checkout --` to clean a shared tree. Commit coherent gains frequently
with clear messages.

## Skills and editing

Use a matching `.agents/skills/*/SKILL.md` before specialized work. Skills are
workflows, not substitutes for source reading. Especially:

- `data-oriented-clojure` before any Seon Clojure;
- `data-modeling` plus `datahike` for schemas, queries, and transactions;
- `clojurescript` for pod/self-host/async behavior;
- `datastar-web-ui` and `browser-automation` for Seon's web UI;
- `clojure-testing` for CLJS/Datahike test mechanics;
- `seon-context-config` for manifests/context blocks;
- `ui-canvas` for `my.canvas`.

Use `rg`/`rg --files` for search and `apply_patch` for edits. If repeated patch
attempts fail, the function or document is too complex—refactor it.

## REPL-driven development

Develop Clojure from the running system outward. The REPL is the first design
and diagnosis surface; checked-in source and tests remain the durable authority
today. The future ability to persist every successful edit directly from the
REPL is aspirational and must not be described as current behavior.

For each Clojure change:

1. Select the exact cluster and runtime. Use cluster-qualified `eval_cljs` for
   pod behavior and the selected cluster's `eval_clj` for writer behavior.
2. Reproduce the failure with one small form. Inspect the complete returned
   envelope, live database facts, installed schema, and immutable database
   coordinate before inferring a cause.
3. Call the existing pure transformation or owning function directly with
   representative data. Probe dependency behavior from `reference-code/` at
   this boundary instead of rebuilding its semantics from memory.
4. Define and evaluate the proposed data shape or transformation in the REPL.
   Prefer immutable examples that expose inputs and outputs; perform database
   writes only when the experiment requires them, then inspect `::db/ok?` and
   the resulting datoms.
5. Edit the one owning namespace, let hot reload apply it, and rerun the same
   form against the same live evidence. Restart only for load-time config,
   bootstrap, process, or artifact behavior that hot reload cannot exercise.
6. Persist the behavioral regression, run the smallest affected gate, then
   verify the user-visible page/feed, database fact, log, or process transition.

Use one form at a time unless batch semantics are the subject of the probe.
Do not leave speculative definitions, sessions, or mutations as hidden proof;
record the decisive form and result in the active PRD when it changes the plan.
Losing a named REPL session loses only process-local values, not database truth.

## Dev feedback and testing

Live diagnosis and narrow behavioral verification start through the repository
MCP server loaded by `.codex/config.toml` and `.mcp.json`:

- use `eval_cljs` for the running Node pod, keeping `agent_id` cluster-qualified
  when more than one live pod can host the id;
- use `eval_clj` for the selected JVM writer's stateful `io-prepl` session;
- use the default session for disposable probes and a named `session_id` only
  when later forms intentionally depend on `*1`/`*2`/`*3`;
- treat a named-session restart error as lost process-local REPL state and
  choose a fresh session id; never infer that the underlying database state was
  lost; and
- keep correctness tests in `bin/test-cljs`, `bin/test-writer`, and
  `bin/seon test operator`. MCP eval is the first probe, not another test
  runner.

The server derives every supported artifact flavor's Shadow cache coordinate
from `seon.dev.config`, discovers each live dynamic nREPL port, and resolves
cluster-qualified agents across the combined advertisements. A bare id present
in several clusters must fail as ambiguous; never select Shadow's latest
runtime. CLJ discovery uses the selected cluster's dynamic writer port file.
After changing MCP code or client registration, restart the Codex or Claude
task: already-running clients do not reload stdio server definitions or tool
schemas.

The edit hook parses changed Clojure files and requests conservative affected
tests through one public operation:

```bash
bin/seon test changed --path src/seon/example.cljs
```

Parse errors may block malformed edits. Test results are advisory and never
undo a refactor; obsolete tests may need deletion. Read the retained report and
full log rather than rerunning just to obtain output.

There are two testing surfaces:

1. code correctness through the existing boundary runners:
   `bin/test-cljs`, `bin/test-writer`, and `bin/seon test operator`;
2. agent/model evaluation through `src-inspect-ai/`.

Do not restore the gym, add bespoke drive scripts, or create another runner.
Use focused tests while iterating, then one relevant complete checkpoint at the
natural unit boundary. Never run overlapping CLJS suites inside the live pod.
Tests assert facts, transitions, envelopes, DOM identity, omission,
idempotency, and structure—not exact context prose.

The default Shadow watcher is the sole owner of the canonical `test` build and
`out/test` artifact. A downstream artifact flavor watches only its own client
build; ACME owns `acme-client`, never `test`. Separate cache roots do not make a
shared output safe. Build selection, readiness, failure detection, publishing,
and pruning must consume the same flavor-owned build vector.

When exercising a real agent, use long-term planning plus database-backed
memory: a multi-step plan that survives restart, and schema'd facts stored then
queried in a later turn. Do not use old workout/trading toy scenarios.

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

Use project-local `logs/`, `tmp/`, and `data/`; never system `/tmp`. Leave ACME
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

The default LLM provider remains DeepSeek. DiffusionGemma is opt-in only through
explicit provider configuration; never activate it as a side effect. Embeddings
use the one `seon.embed`/Vertex path when `SEON_EMBED` is enabled. Credentials,
project IDs, and service-account files stay outside Git. Details live in
`src/seon/ai/AGENTS.md`, `docs/seon/reference/llm-adapters.md`, and the
embeddings PRD.

## Key entry points

- `docs/seon/architecture/architecture.md` — intended system map; read first;
- `docs/prds/runtime-reliability/roadmap.md` — current branch work ledger;
- `docs/prds/runtime-reliability/AGENTS.md` — current chunk runbook;
- `docs/conventions.md` — code/schema patterns;
- `src/seon/AGENTS.md` — one-mechanism and runtime ownership table;
- `src/my/AGENTS.md` — agent-facing toolkit constraints;
- `AGENT.md` — thin delegated-lane compatibility adapter.
