# Seon — shared instructions

This is the one maintained repository instruction authority. Codex reads
`AGENTS.md` directly; Claude reads the same bytes through the same-directory
`CLAUDE.md -> AGENTS.md` compatibility link. Role-specific workflow lives in
`ORCHESTRATOR.md` and `AGENT.md`.

If you were spawned as a subagent, execute the assigned task directly. Do not
spawn or delegate again. If the task is too broad, report that to the top-level
orchestrator for rescoping.

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
  intended system. It is target-written in present tense. Read
  `architecture.md` first, then the relevant domain document. Update it when a
  design decision changes; do not put implementation status or a migration
  diary there.
- `docs/prds/<chunk>/` contains one implementable roadmap chunk on its own
  branch. Its `roadmap.md` records what is built, gaps, evidence, and the path
  from current code to the architecture target. Its localized `AGENTS.md` is a
  tight runbook/index. Finish and merge the chunk before carving the next
  architecture delta into a new PRD folder and branch.

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

Before writing code:

1. Observe the live system and define what failure looks like.
2. Read the existing implementation and its closest localized `AGENTS.md`.
3. Read the actual dependency source in `reference-code/`; never guess library
   behavior or unzip installed packages.
4. Probe the assumption directly in the REPL or with the smallest executable
   experiment.
5. Implement by strengthening the one existing mechanism in place.

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

## Dev feedback and testing

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
- `ORCHESTRATOR.md` / `AGENT.md` — role-specific workflow.
