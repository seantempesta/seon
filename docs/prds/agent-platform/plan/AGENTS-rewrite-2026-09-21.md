---
type: reference
status: active
tags: [agent, architecture]
---

# Seon — shared instructions

This is the one maintained repository instruction authority. Codex reads
`AGENTS.md` directly; Claude reads the same bytes through the same-directory
`CLAUDE.md -> AGENTS.md` compatibility link. When the tree contradicts a
claim here, the claim is the bug: fix this file in the same commit as the
change that exposed it.

## How we work here

**This is the second implementation.** Almost everything you are asked to build has been built
before, and the previous version is readable through Git history. `git show` and `git log` are the
quarry; `docs/prds/*/research/` holds dated investigations with `file:line` evidence and measured
numbers; `reference-code/` vendors dependencies as submodules so their semantics can be READ rather
than remembered. The prime directive is not "write good code" — it is **do the archaeology before
you design, then design something better than what you found**. The fresh tree is not zero
knowledge; it is zero *baggage*: every piece re-earns its place.

**Ask what the dependency already does before you build anything.** sci keeps a live env; konserve
has GC and binary storage; Datahike branches are head pointers, not copies. If your design
recomputes something a dependency already maintains, the design is wrong. The cheapest place to
delete code is before it exists: build the smallest real thing and let live probes falsify the
design while it is still a decision. **Read the vendored library seam before editing**: we own and
maintain the forks, so ask first whether the work has to be done at all or whether a dependency
already does it (owner, 2026-09-21). **Use the REPL to test ideas, inspect data and measure.** The
spec you were handed is a floor: **find a better way than the spec's floor and record why**.
"Clojure projects tend to be 10x smaller — I want to see that in my codebase" (owner, 2026-09-21).

**SECONDS, NOT MINUTES (owner law, 2026-09-22).** Anything that takes longer than TEN SECONDS
requires the owner's explicit authorization, and only a handful of operations may ever hold one —
the initial indexing of the whole program from zero is one; a cold JVM boot is another. Everything
else must PROVE there is no simple algorithmic solution before it is allowed to be slow: name what
work is proportional to the whole program that should be proportional to the change, and cite the
seam that already does it in seconds. Treat anything above a couple of seconds with suspicion,
especially where a tuned library (Datahike, clj-kondo, Malli, SCI, core.async) sits underneath —
their authors nailed the implementations; our failures have almost always been bad algorithmic
choices, never code correctness. Almost everything in Clojure is processing immutable data and it
is extremely efficient. A fork is a branch pointer: milliseconds. A no-change request is two commit
ids compared: milliseconds. An edit's cost is bounded by the changed declarations and their
callers. A number is never explained by which path it took ("the cold path", "the first adoption")
— it is explained by what work the algorithm should do. Tests FAIL when they exceed their declared
bound (default 5 s; `:seon.test/long-ms` with its reason is the only way up). "No double caching.
Use all the existing tool caches." (owner, 2026-09-22.)

**Prefer dissolution to addition.** The best change deletes a mechanism. When you meet a tuned
constant, ask what observable event it stands in for. When a fix feels like hardening a mechanism
against its own normal operation, stop and ask whether the mechanism belongs on that path at all.

**Derive state; do not remember it.** Verify prose — including this file — with one live command
before acting on it. Nothing stores what a query can derive: `open?` means no `closed-tx`; a
boolean is legitimate only when someone genuinely asserts the false. A database value is
immutable, so everything derived from it is computed once when the value is made and CARRIED on
it (`seon.db/carried-projection`, `src/seon/db.clj:1219`).

**No seam may act on a pre-read or a mirror that its authority will re-decide: derive at the
authority, or hand it the decision** (owner law, 2026-08-29: hand-rostered fixtures vs the config
compiler, existence pre-reads vs the writer's upsert, a reply pipe vs the process's own exit, a
lint cache vs canonical analysis — one disease). A pre-read is legitimate only when its answer
cannot change before the authority acts. Deletion refuses when surviving declarations still name
the removed identity; every repair must be present in the same transaction's final database. The
complete refusal is the refactoring input. A name without a current function row is reported by
the unresolved-call query, never repaired by minting an identity. No program tombstone or
retirement sentinel is stored.

**The recurring failure class of this whole project is a check that reads ABSENCE OF SIGNAL as
health** — a query against a descriptor that no longer exists, a regression walking less than the
writer admits, a monitor that stays silent through a crash. When you write any check, ask what it
reports when its subject is absent. If the answer is "fine," the check is worse than nothing.

**Write it down in the same beat.** Rulings into the dated ideas ledger, state into the working
edge, settled terms into the vocabulary table, defects into issues — in the turn it happens,
path-limited commit. Conversation memory is never the only record.

## 1. What Seon is and how it runs

One JVM process runs everything, from source, REPL-first; CLJ only; git history is the archive for
everything deleted. The system has exactly two states: **boot** and **running**. Boot is the 0→1
construction in dependency order — process, store, facts, flow — opening the REPL before store
acquisition so a boot failure is always fixable live; each layer reads only the one below it and
publishes its own readiness. One process root owns one Datahike store under our lifetime `flock`;
Datahike's writer is its own serial loop per connection — we never build writers, we call
`transact` (`reference-code/datahike/src/datahike/writer.cljc`). Running code reads the database,
never files or env vars. EVERY AGENT IS ITS OWN FLOW GRAPH, parked between turns, woken by
notifications; there is NO central loop, dispatcher, or scheduler; every proc pins `:io` or
`:compute`, and the `:mixed` default is the one scaling cliff
(`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj`).

A **cluster** is one database branch, its agents, and their shared plumbing; boot produces ONE
environment value per cluster (`seon.env` ↔ `resources/seon/schemas/seon.env.edn`), scoped per
agent (`seon.env/scope`, `src/seon/env.clj:330`). One JVM hosts many clusters; nothing may assume
"the" cluster. A new cluster forks the published `:current-src` commit — a branch pointer, never a
re-index.

**Publication in one sentence:** the running JVM's prepl receives the changed paths, hashes only
those, lints them with clj-kondo's cache, transacts the digest difference on `current-src` and
reloads exactly the namespaces the transaction report names; only cold boot and `reset` spawn a
JVM. Hot reload is not indexing: re-evaluating a Var changes loaded behavior, file edits never
mutate program facts. Graph definitions reference transforms as vars (`#'f`), so a re-evaluated
`defn` changes proc behavior immediately; topology changes rebuild the graph, safe because channel
contents are losable by construction.

**Transport law:** anything recovery or another process could ever need is a DATABASE FACT —
identities, evaluations, messages, errors, the settled reply — with bulky durable payloads as
blobs; an evaluation stores its shown text, never a result blob. Everything IN FLIGHT rides
channels, provided loss is free: re-derivable from facts or superseded by a newer complete value.
The buffer encodes the loss semantics: sliding-1 for latest-wins, fixed for backpressure,
counted-dropping for observation. Any design where channel loss breaks recovery is wrong.

**Crash model: interrupted execution never resumes.** Boot recovery closes open turns and marks
unfinished evaluations interrupted (`seon.turn/recover-tx`, `src/seon/turn.clj:1704`); the agent
adapts from stored shown text. Private defs, atoms and result objects disappear with the JVM,
neither serialized nor restored. The cluster's program-only SCI base derives from one database
value (`seon.sci.eval/base-ctx`, `src/seon/sci/eval.clj:2218`); `fork-for-turn` (`:2184`) gives
the agent a fresh fork with its private objects reapplied.

**Errors are two classes, never mixed.** An agent mistake becomes a flat `:seon.error` value the
agent sees — nothing throws into the loop. A core fault is committed as a durable fact with
provenance, so "who should fix this" is a query. One config dial: dev throws, prod collects. Seon
is the core: consumer-specific UI, vendor integrations and domain models belong downstream, never
in `src/` or `docs/`. Mechanism detail: [docs/seon/architecture/](docs/seon/architecture/architecture.md).

## 2. The five design laws

These constructions prevent the defect classes that filled the issue archive. A review asks first
"which law does this shape obey or break?"

### 2.1 Values carry their world

Everything a computation needs travels WITH it as ordinary data: the environment, the schema
projection, the database value or connection, the render profile, an effect request's settlement
inputs. Running code receives its world — through the sci ctx/fork, submission data, proc `:args`,
or the request map — as arguments and values. It never fetches its inputs from somewhere else at
call time: not from a dynamic var, not from a process-global registry or atom, not by re-deriving
them fresh on every call. Derived state rides the value it derives from, so staleness and
cross-environment reads are structurally impossible: a database value carries its projection
(`src/seon/db.clj:1219`) and every reader takes it from there. `seon.config/defaults` is a program
constant compiled once when its namespace loads — no atom, delay, memoization or cache for it.
Fetch-at-call-time is also the recurring performance killer: the same defect recomputes a
projection on every call.

### 2.2 Facts over inference

Durable system state and program declarations are explicitly recorded in the database and
queryable. Every question — what a function accepts, whether it is private, which schema a value
satisfies, which function renders a shape, which tests reach a function — is a Datalog query over
facts we already store (`seon.fn/tests-reaching`, `src/seon/fn.clj:1586`). **If answering a
question requires a convoluted reconstruction — joining text, guessing from names, walking files —
stop: the missing fact is the root problem. Declare it at the one indexing/declaration seam, then
query it.** A question the database cannot answer is a defect report about the data model. The
three banned substitutes are one mistake in different clothes: a hand-maintained list, a naming
convention, and a regex over text. Schema discovery is registry-query-first.

**Derive or die (owner law, 2026-08-13):** every hand-maintained mirror of derivable state — a
member list, a count in prose, a roster, a reserved-name list, a vocabulary enumeration — must be
DERIVED by a query, ENFORCED by a checker that fails on drift, or DATED as a point-in-time record.
A bare mirror is a defect on sight. **A REGEX IN PRODUCTION CODE REQUIRES THE OWNER'S PERMISSION —
STOP AND ASK.** The program graph answers questions about code, Malli schemas about shape, the
reader about forms, Datalog about facts. (`rg` while working is ordinary tooling.)

### 2.3 Bounded, event-driven execution — both halves, always together

Detection is event-driven: interfaces express their dependencies and publish their own readiness
(a start returns a completion, a resource announces attached, `listen!` before derive). AND
nothing in this system is allowed to run indefinitely: every execution surface carries its
declared bound, enforced at the seam that admits the work — sci evals under the one
`time-limit`/`:interrupt-fn` (`reference-code/sci/doc/interrupt.md`), capability calls under
deadline and output caps as config facts, test events under the declared
`seon.test-support/event-backstop-seconds`. A bound firing is itself a bug report naming what never
arrived — never a silent retry, never a pass. Dropping either half is the defect: a bare tuned
timeout hides the observable event, and an unbounded event wait turns one missing fact into a
silent wedge. **A hang is a worse defect than a failure.** A new execution surface's bound is part
of the seam's contract; unbounded work should be unconstructable.

### 2.4 Total, honest, bounded boundaries

Every failure at an agent or runtime boundary is a flat `:seon.error` value — nothing throws into
the loop. EVERY function carries a complete Malli contract, private included (owner ruling
2026-09-17); arming selects every loaded contracted Var (`seon.instrument/collect-contracts!`,
`src/seon/instrument.clj:898`) and re-arms a wrapper only when its definition or contract changed
(`current-wrapper?`, `:844-858`). A function whose declared contract fails does not run — the
violation is a typed value naming the function and the offending argument. A refusal names what
was missing: the layer, the member, the expected shape, the offending value
(`seon.error/diagnostic`, `src/seon/error.clj:304`). An unavailable observation is the typed
unknown, never absence, success, or silence. A silent fallback that "happens to be right" is a
defect even while it works. Diagnostics tell the truth or say nothing.

Outward values cross one total render contract: renders never throw and never refuse an ordinary
value. Evaluation retains the actual result object in the agent's SCI context and stores the exact
shown text. **The AI projection is bounded by the render profile** — the AI RENDER FUNCTIONS and
the VALUE RENDERER (`seon.render.value`) apply its string, child-count, depth and token limits, and
that is the ONE place presentation elides anything — never the walk, never the history, never a
render terminal, never a request seam (owner, 2026-09-08: "DO NOT INTRODUCE MORE spots where
clipping occurs"). A request without a profile gets one derived at the render entry
(`seon.render/request-profile`, `src/seon/render.clj:115`) or a typed refusal. The projection is
made once at evaluation time; historical evaluations render their saved shown text unchanged.
**HTML has no presentation clipping.** The three controls are the AI presentation profile,
query-work bounds, and evaluation deadlines; the walk's distance is a query-work bound, reported
as its own elision naming it (`src/seon/render/walk.clj:698`). Omitted detail is an elision value
— count, path, requery identity — never bare truncation. UGLY OUTPUT IS A DEFECT: report it or
file it naming the shape and where it surfaced. Human display sizes are estimated tokens
(`seon.ai.tokens/estimate`, `src/seon/ai/tokens.cljc:145`).

### 2.5 One mechanism, accreted in place

Do not create `foo-v2`, a compatibility namespace, or a second registry/renderer/feed/retry/
config/test path to avoid fixing the existing owner. Fix cycles, callers, and schemas in place;
delete the superseded path in the same refactor — git is the archive. Maps are OPEN: declared keys
are validated rigorously and a supplied extra is ignored, never refused (`{:closed true}` does not
appear under `resources/seon/schemas/`). Adding is free; CHANGING is breakage: different semantics
means a NEW KEY with a new name; narrowing an input, requiring an optional key, or promising less
in an output is breakage even when the schema still validates. The standing test, from the owner:
**is this simpler than it was?** If it is equally complex, the model was ported, not applied.

**Owner design gate:** when a decision would create hours of cross-owner work or its guarantees
cannot be stated simply, STOP before production edits and bring the owner exactly three concrete
options — simplest viable constraint first, marked recommendation, each with guarantee, cost, and
what we give up.

## 3. Data and schema — the rulings

Mechanics, with their Datahike grounding, live in
[the data-modeling guide](docs/seon/architecture/data-modeling-guide.md) and the `datahike` skill;
use the `data-oriented-clojure` skill at design time.

- Immutable data and pure transformations; derive projections instead of storing them; fully
  namespaced keys and attributes; schemas declared once under `resources/seon/schemas/`; one
  namespaced map in/out for API-like functions; no `:any`/`:some`/`[:maybe X]` without a proven
  polymorphic boundary; absent = no key, never stored nil (`(get m k sentinel)` or `find`, never
  `contains?` on a vector).
- **Anything that IS a symbol is stored as a symbol, never as a string** (owner, 2026-09-17: "ALL
  OF IT"): Malli `:symbol`/`:qualified-symbol` → `:db.type/symbol` (`src/seon/schema/datahike.clj:27-28`);
  a `(str sym)` or `(symbol s)` at a store seam is the sighting. A type change is a RESET.
- **An entity IS its attributes, values and refs — never a stamped kind.** No `:type`/`:kind`
  discriminators; query attribute presence, identify by a unique identity attribute, relate by
  refs. A closed set of states may be an enum-valued attribute, rare and justified in its docstring.
- **Deletion is retraction; the past is a history query** (G1). No retirement attribute;
  `history`/`as-of`/`since` answer what was true before. `retractEntity` sweeps every incoming ref
  and cascades into components — read the datahike skill before deleting.
- **Required versus optional IS the deletion dial**: the final-report validator re-validates every
  touched entity, so a required ref REFUSES a deletion and an optional one lets it SWEEP; say which
  in the attribute's docstring. Write admission validates ALL inputs, always; a failure aborts the
  whole transaction (F2).
- **A fact that must outlive its target stores a VALUE, not a ref** (G2): a statement about a
  living entity is a ref, an observed token is a value; call edges and test reach are
  `[:set :qualified-symbol]`. **A function with live callers is not deletable until the callers are
  fixed** (owner, 2026-09-16): retraction and repair in one transaction, or the deletion refuses and
  hands back the breakage — equally for an SCI evaluation and a publication.
- **If a reader must distinguish "looked and found nothing" from "never looked", the looking is an
  event and the event is a datom** (G4): `#{}` emits no datoms. **A component is part of its
  parent's value** (G5): validated as one owning value; no invented identities on component rows.
- **An entity schema describes the STORED entity only**; a pulled shape DERIVES from it under the
  reader's selector, never a second hand-written schema. Pull's silent cut at 1,000 members
  (`reference-code/datahike/src/datahike/pull_api.cljc:16`) is the named failure class inside the
  dependency: our fork removes the silent default; a bounded pull reports an elision naming it.
- **Errors** (D12/D13, §1q): an error is `:seon.error/at`, `/layer`, `/operation`, `/message` plus
  the members its own schema declares — the error's SCHEMA is its meaning; no `:seon.error/kind`,
  no class marker, no general `error?` predicate. Every function's contract names the errors it can
  return and the wrapper validates them; a consumer distinguishes errors by a declared member, never
  a guard. Recurrence identity is `seon.id/id` over the distinguishing content, never the process.
  The offending value is a normal `result/e<id>` reachable from the agent's REPL; nothing the schema
  does not declare is stored; aggregate by identity, never an entity per event.
- **Tasks** (D1/D2, 2026-09-19): `seon.task` is the ONE family for work an agent does — linked facts
  (subject, tests, errors, functions) plus an optional agent; a "template" is the render pair the
  task's data selects, never an entity. A trigger resolves to a task identity at the writer: an
  existing task's agent receives a wake, otherwise the task is created and an agent started.
  Namespace responsibility (`:seon.ns/agents`, many-to-many) is context, never the routing key.
  Done is a query: cited tests verify or the detector no longer names the subject. Root resolves
  merge conflicts through the same gate (D6).
- `seon.db` is the ONE database namespace; direct `datahike.api` calls survive only inside it, the
  store/registry and branch-custody owners, and system-side listeners. The `my.*`/`seon.*` split is
  surface versus owner: one fact family, one writer (`src/my/message.clj:31`); two fact families for
  one noun is the defect. Config reconciles a manifest into facts by transacting the DIFFERENCE;
  provenance is transaction metadata, never copied onto domain entities. Refs point from the later,
more specific fact to the more stable identity (occurrence → error → function → namespace →
agent); counts, states and ownership are derived at read time, never stored.

## 4. Vocabulary — grounded names, never invented ones

Use Clojure's own name; else the closest seam's name (Datahike, Malli, SCI, core.async — read the
source in `reference-code/` and take its semantics with the name); only when a concept is genuinely
ours, coin once, record it here, use it everywhere. Never assume you understand a row from its
name: follow its link and read that slice. **Retire drift on sight**: a legacy spelling met in scope
is updated in the same commit, otherwise filed. The third column is a recognition aid for reading,
never an option for writing.

| Term | Meaning and grounding | Legacy spellings |
|---|---|---|
| database, `db` | the `seon.db` authority; vocabulary is Datahike's: database value, basis `:t`, commit ID, connection, store, branch, transaction report | store, inventory, memory |
| boot / environment / running | boot is the 0→1 construction (REPL first); the environment is the one per-cluster value it produces (`resources/seon/schemas/seon.env.edn`), scoped per agent | the runtime, the platform, ambient |
| cluster | one database branch, its agents, and shared plumbing; produces one environment | environment (for the cluster) |
| attributes + connections | the Datahike model; an entity is its attributes | entity kind/type |
| call preparation, supplied defaults | sci's hook seam supplying a function's declared-and-absent arguments from the environment, keyed on the declared schema NAME; caller wins (`reference-code/sci/src/sci/core.cljc:331`) | ambient injection, batteries |
| web UI, namespace page | `/`, `/ns/{namespace}`, `/agent/{id}`, their debug routes and `/data` (`src/seon/render/route.clj:5`) | inspector, dashboard |
| `ctx`, `fork`, `intern` | sci's own names (`reference-code/sci/src/sci/core.cljc:331`, `:345`, `:260`) | warm base, the agent's world |
| base SCI context / agent SCI context / prompt; private layer | the cluster's program-only context from one database value (`src/seon/sci/eval.clj:2218`); the agent's fork with its private objects reapplied (`:2184`); the ordered rendering of its stored evaluations; the agent's defs and atoms as objects in its context, lost on JVM restart (installed functions, schemas and tests are durable program facts) | turn fork, "the context", `:seon.def/*`, session image |
| `:interrupt-fn`, `interrupt!`, `time-limit`; `:seon.eval/fn-entries` | sci's one zero-arg fn on every fn body entrance and `loop/recur`; the uncatchable stop; the SCI deadline (`reference-code/sci/doc/interrupt.md`); a RECORDED DIAGNOSTIC, never a limit | guard, door, cage, fuel, step budget, safepoint |
| `:io` / `:compute` / `:mixed`; proc, step-fn, conns, graph-def; `(sliding-buffer 1)` | core.async's and `clojure.core.async.flow`'s own vocabulary (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:78`, `:165`) | eval pool, scheduler nouns, latest-wins mailbox |
| turn | an agent's ordered evaluations and any provider attempts; open means no `:seon.turn/closed-tx`, enforced at the writer (`open?` `src/seon/turn.clj:215`, `open-call` `:395`, `close-call` `:442`) | run, `seon.cluster.run`, episode |
| system turn | an ordinary turn with a reply and no provider attempt; "system" is derived, never stamped (`seon.turn/system-turn`, `src/seon/turn.clj:2103`) | generated opening episode |
| turn loop | the per-agent Flow proc deriving work from facts (`step` `src/seon/turn.clj:5263`, `next-agent-work` `:2888`) | run loop, driver |
| wake, answered, handled, inside wake | listened attributes identify wake datoms; answered derives from the datom's `:t` and `seon.turn/latest-answering-turn-t` (`src/seon/turn.clj:2980`); handled is a claim ref written at settlement; inside means it cannot refill the turn bound, `:seon.message/from` the sole marker (`src/seon/cluster/wake.clj:317`) | inbox, mailbox, claim, pending, settle, trigger |
| message subject | the string supplied in `:my.message/about`, stored verbatim in `:seon.message/about` (`resources/seon/schemas/seon.message.edn`) | inside marker, correlation |
| the agent's history, shown text; compaction | the walk over `(seon.eval/of-agent db agent)` (`src/seon/eval.clj:9`) from the exact value-renderer text saved at evaluation with source, out and error; all turns shown; nothing assembles the history but the walk; compaction wipes the evaluations and the next system turn regenerates the opening — no manual curation path | transcript, session units, result EDN, editor, revision, curation |
| live result object | the actual result retained by evaluation id in the agent's SCI context, bound as `result/e<id>` (`seon.id/symbol-in`, `src/seon/id.clj:47`); never serialized or restored | result blob, restorable node |
| read evidence | the dependency plans a read observed, used with its `:t` to detect changed facts (`reference-code/datahike/src/datahike/query.cljc:2877`); every distinct read participates, writes and effects never rerun | copied read result |
| render function, `:seon.render/ai`, `:seon.render/html` | a function of the data that chooses its forms; ONE AI/HTML pair per entity schema, declared as schema properties (`src/seon/repl.clj:447`, `:480`; `src/seon/render/hiccup.clj`) | producer, view, `/form` face |
| render profile, elision value, candidates | the presentation policy applied once by the value renderer (`resources/seon/schemas/seon.render.profile.edn`); omitted detail as data (`resources/seon/schemas/seon.print.edn` ↔ `src/seon/print.cljc`); the contract-fitting render selection per call (`src/seon/render.clj`) | cap, window, ellipsis, roster |
| `seon.effect/request!` | the system-side owner for declared capability requests (`src/seon/effect.clj:1046`); about effects crossing out, never about which functions an agent may call | the door, capability dispatch |
| every function is callable; program graph | an agent may call ANY function in its cluster's `:seon.fn`/`:seon.ns`/`:seon.schema`/`:seon.test` facts; rendering never gates execution | toolkit, grants, allowlist, corpus |
| `my.program` | agent-facing program reads: `callers` (`src/my/program.clj:152`), `tests-reaching` (`:167`), `reads-key` (`:217`), `breaks` (`:278`), `history` (`:357`) | my.refactor, impact, blast radius |
| referrer; caller; reference; subject; reach | a live entity naming the subject; `:seon.fn/calls`; `:seon.fn/references`; a test's present claim; advisory evidence from a past run (`resources/seon/schemas/seon.program.edn`) | dependency |
| redefinition; retraction; override | replace definition facts at one identity (`src/seon/program.cljc:1042`); remove facts, leaving the past to history; a function whose current admission is `:agent` in an indexed `src` namespace — a query, never a flag | soft delete, retirement, override flag |
| namespace agent, `:seon.ns/agents` | an agent responsible for a namespace: its faults, tasks and requests render into its context; many-to-many; never the routing key (D1, 2026-09-19) | steward, `:seon.ns/steward` |
| task, `seon.task`; conversation | the one family for agent work (§3); a conversation is DERIVED from `seon.message` facts | issue (as family), `my.task`, work packet |
| error schema, occurrence, signature, distinguishing member | an error's meaning is its schema; occurrences are components under the signature root; a distinguishing member is a required member no sibling in the declared union shares | kind, class, fault, facet, family |
| `my.agents.<id>` | the DEFAULT namespace for a temp agent only; `:seon.agent/namespace` is not unique | agent workspace, sandbox ns |
| `seon.id/id`, `/digest`, `/evaluation` | THE ONE IDENTITY DERIVATION: SHA-256 of `(pr-str data)` (`src/seon/id.clj:29`, `:40`, `:55`); `random-uuid` only for a genuinely fresh EVENT; never a new generator | short-id, nanoid, `(str (random-uuid))` |
| tuple (`:db/tupleType`) | Datahike's single-value ordered construct; cardinality-many is a SET (`reference-code/datahike/src/datahike/index/persistent_set.cljc`) | small limited vector |
| wire; evaluation, result | a crossing that LEAVES the process (provider HTTP, browser SSE); in prose never "receipt"; MCP modes are `jvm` (host prepl) and `sci` (the cluster's SCI context) | wire (internal), receipt |

## 5. REPL-driven development

**Start every Clojure change at a running system, not at a file.** The tools that connect you to
the metal are the difference between guessing and knowing — the REPL is the first design and
diagnosis surface; checked-in source and tests are the durable authority. The loop:

1. **Get a live system.** `bin/seon status`; use `default` for ordinary changes, as configured in
   `.claude/seon-hook.edn`. Isolated operator roots are for destructive drills.
2. **Reach it.** `mcp__seon__runtime_status` reports the selected cluster's health;
   `mcp__seon__eval_clj` evaluates in the selected cluster's JVM (qualify the cluster when several
   are live — ambiguity must fail). Its `jvm` mode is the host prepl with NO cluster custody bound —
   `seon.db`'s elided db/conn arities refuse there; `(seon.operator/connection "default")` supplies
   explicit custody. `mode: "sci"` evaluates through the cluster's SCI ctx where elision holds (and
   mutates that shared ctx, so keep probes disposable). **If these tools are down, degraded, or
   missing, SAY SO IMMEDIATELY** and file the issue; a silent workaround is how tool rot spreads.
3. **Reproduce with one small form** and read the COMPLETE returned envelope. Inspect live facts
   and the installed schema before inferring a cause.
4. **Call the owning function directly** with representative data. When the question is about a
   dependency, read its source in `reference-code/` at that boundary.
5. **Design in the REPL** on immutable examples exposing inputs and outputs.
6. **Edit the one owning namespace**; hot reload applies it — including flow proc behavior,
   because procs reference step-fns as vars. Rerun the same form against the same live evidence.
7. **Persist the regression**, run the tests reaching the change in-process, then verify the
   user-visible fact, page, log line, or process transition. A change proven only by a passing
   test is not proven.

Before planning any change: read the named authorities END TO END — never grep a named authority;
write the dependency ledger (exact libraries and mechanisms, pinned `reference-code/` paths,
first-party call sites that demonstrate the idiom) and read that source; probe the critical
assumption in the REPL; then strengthen the one existing mechanism in place. **A dependency's
semantics are READ from the vendored source at design time, never inferred from observed
behaviour** — cite the seam by `file:line`, because what a dependency does in the cases you
happened to run is not what it guarantees.

**Where work lives.** Never a session scratchpad or system temp directory. Probes go in `tmp/`
(project-local, visible); anything whose RESULT is evidence gets its script committed and numbers
recorded in the owning PRD's `research/`; anything that will run again is real code under `test/`.
The test: if the machine were wiped right now, what would be lost? **Comment grammar:** `;`
prose/inline, `;;` a code-block comment above a form, `;;;` runtime structure. **Skills**
(`.agents/skills/`) load before specialized work; every claim carries `file:line`, verified when
touched; an unverifiable claim is DELETED, never hedged. `docs/seon/architecture/` is the
always-current target; one fact lives in the deepest file that owns it, updated in the same commit
as the change that invalidates it.

## 6. Testing

**Fixtures — the one right way.**

1. **Never hand-roster schema.** Use the canonical database fixture
   (`seon.test-support/with-database`, `test/seon/test_support.clj:982`) plus
   `::test-support/extra-schema` for genuinely synthetic attributes.
2. **Hand the projection/environment explicitly**, exactly like production callers.
3. **Supply every declared proc input** (cluster name, render interest, profile) — a missing input
   becomes a typed refusal that poisons downstream consumers.
4. **Fixed render profiles in fixtures** — deriving per call reads as a hang under load.
5. **Every await is bounded and loud** via the declared `seon.test-support/event-backstop-seconds`
   (`:30`); await the exact terminal facts, not quiescence.
6. **Assert current ruled behavior** — typed diagnostics not absence, terminal verdicts, total
   bounded renders; a test expecting the old lenient shape is stale and the fix is the expectation.
7. **Own nothing global.** No assumptions about the JVM's namespace load-state or scheduling, and
   no mutation of it; a probe namespace that must be unloaded has NO file on any classpath.
8. **Fixture entities come from the canonical helpers, never a hand-written map:** `program-fn-row`
   (`:1026`), `apply-config!` (`:1039`), `seed-cluster!` (`:1058`), and every fixture write through
   `transacted!` (`:309`), which surfaces the writer's refusal instead of letting the test read
   absence as behaviour.
9. **A fixture never retracts what a running loop is settling**: stop the graph first or hand the
   loop the decision (`seon.turn/open-run-tx-call`, `src/seon/turn.clj:465`). Acquire resources in
   `with-open` (`seon.test-support/closeable`, `:1116`); tests that change instrumentation use
   `preserving-instrumentation-state` (`:1073`).

**Bounds.** Every test carries a bound, default 5 s, and FAILS over it; `:seon.test/long-ms` with
its reason is the only way up, and a reason without a number is a refusal. A slow test is an
algorithm finding, never a tuning item. A test that passes because a bound fired is a defect.
**The gate is the tests reaching the change, run in-process** — never the suite. Selection is a
query over `:seon.fn/calls` (`seon.fn/gate-set`, `src/seon/fn.clj:1573`), run through
`seon.test/check` (`src/seon/test.clj:1901`) over `run-owned` (`:576`) in the cluster's JVM on a
forked branch and SCI context, recorded through `seon.test.runner/commit-results!`
(`src/seon/test/runner.clj:3145`); a request whose recorded green matches executes zero. The
platform tier (boot from zero, declared `:seon.test/platform`) is the one subprocess. Every
regression runs under the same contracts a cluster arms: a test that passes only unarmed is a
defect.

Tests find design issues; structure dissolves them: when a failure class appears, move the
invariant to one choke point and keep ONE regression per class asserting the WANTED behavior. A
smaller suite is a desired outcome; the health metric is class coverage. Every proof must be
claimed by a recurring surface. Fixture load paths are not the live boot path: schema, acquisition,
and process changes need the reset-boundary live proof. After code changes, verify the running
system, not only the tests, and report what is still broken honestly. Deeper mechanics:
`.agents/skills/clojure-testing/SKILL.md`.

## 7. Operating

`bin/seon` is the one development operator:

```bash
bin/seon [--root PATH] COMMAND   # --root = an ISOLATED operator root
bin/seon start [CLUSTER] [--config PATH]
bin/seon config apply [CLUSTER] PATH
bin/seon status | open [NAME]
bin/seon init [--changed PATH] | init NAME [--force]
bin/seon stop [--force] [NAME] | down [--force]
bin/seon reset --force           # preflight (syntax, lock holder), down all, destroy, republish, refork, start, adopt
```

Absent cluster means `default` for `start`/`config apply`; bare `init` means the published
`current-src`. Stop/down act on exact recorded process identity — a reused pid can never be killed
by mistake. Never launch the operator's internals separately or kill its children blindly; use
`bin/seon down` so the supervisor reaps its own. DESTRUCTIVE DRILLS AND SECOND DEPLOYMENTS USE
`--root`. `bin/acme` is a thin root-scoped wrapper selecting cluster `acme`.

**The default cluster is the development environment**: the edit hook publishes each admitted edit
to it (`.claude/seon-hook.edn`); shell writes bypass the hook, so follow them with `bin/seon init
--dev default --changed PATH`. The hook runs clj-kondo and never tests; a kondo "Unresolved var" on
a protocol or dependency name is a stale dependency cache until proven otherwise — repopulate it,
never rewrite correct references to satisfy it. **Resets are free**: database data is disposable, every
stored-shape change is a RESET recorded in the spec, never a migration; reset only with no
uncommitted `src/` or `test/` edits, and recover `default` decisively rather than asking.
Everything under `tmp/` is throwaway; a disk filling with dead roots is fixed the minute it is seen.

**Churn is weather, not a blocker.** Clusters, JVMs, and advertisements come and go while you work
— ADAPT AND CONTINUE. Your cluster vanished: re-derive from `bin/seon status` and start a fresh
one. A long-lived JVM serves the code it loaded at start — suspect staleness before suspecting
correct code. Stop for a genuine implementation dependency, named exactly. **Recursive deletion
NEVER follows symlinks**; plant a symlinked sentinel in any cleanup regression. The shipped model is
DeepSeek through the single `seon.ai` HTTP owner; AI dials are config facts (`config/default.edn`);
credentials name environment variables and never become datoms; paid runs are deliberate, cheapest
probe first ([reference](docs/seon/reference/llm-adapters.md)).

**No hobbling for hypothetical risk:** agents are trusted collaborators needing full capability,
including reading every environment variable. The design concern is catching HONEST MISTAKES
(bounded output, digests, atomic writes) — a restriction is admissible only after evidence of a
real problem.

## 8. Working and reporting

VERIFY THE CLAIM BEFORE YOU NAME THE CAUSE: an attribution is a hypothesis until a probe confirms
it; a lane that refutes its assignment with evidence has done its job.

**Lanes.** Opus researches, Fable writes specs, astra implements and reviews (primary lens: better
algorithmic selection of when and how data is processed; then errors). Lanes own disjoint files,
one JVM each, never a second JVM or a background probe. A lane's first act is reading its spec's
seam in `reference-code/`; its second is a REPL probe with a number. A file another running lane
holds is a STOP and a report, never a workaround, a worktree, or a revert. Commits are the
heartbeat: path-limited (`git commit --only -- …`), one coherent slice each, HEAD loadable after
every one (`clojure -M -e "(require …)"` for the touched namespaces; a retirement and the
conversion of every caller are ONE slice). Never `git add -A`, `reset --hard`, or `checkout --`.
Landing notes go under `docs/prds/agent-platform/landing/` with exact forms and measured numbers;
the note is the deliverable, never chat. Words: verify / falsify / probe — never adversarial verbs.

**Issues are how the system learns.** A bug, smell, duplicate mechanism, stale test, wrong
vocabulary, or documentation mismatch: search `docs/seon/issues/`, then create or update ONE note
before returning, with frontmatter per [the issues README](docs/seon/issues/README.md). Fix the
CLASS, not the instance, with one regression proving the class dead; a recurring class earns a
rule in this file. A detected defect is a task with a detector subject (§3).

**Reporting to the owner.** Sober summaries, broken things first. Full repository-relative markdown
links for every document. Say that you read each named authority end to end. Ask the moment a
genuine decision exists — 2-4 priced options, recommendation first; never park a decision awaiting
markup. Tool breakage, exploding context, and ugly output get reported the moment they are seen.

## 9. Pointers

- [docs/prds/agent-platform/plan/README.md](docs/prds/agent-platform/plan/README.md) — the plan
  from here to the self-improving system; its `lane-*.md` specs own the per-seam reading lists,
  REPL protocols and numbers;
- [docs/seon/architecture/architecture.md](docs/seon/architecture/architecture.md) — the system
  map with its `reference-code/` seams, then the domain documents beside it;
- [docs/seon/architecture/data-modeling-guide.md](docs/seon/architecture/data-modeling-guide.md)
  — every modeling ruling with its Datahike grounding;
- [docs/seon/issues/README.md](docs/seon/issues/README.md) — issue lifecycle, severity, tags;
- `.agents/skills/` — load the matching skill before specialized work.
