---
type: prd
status: active
tags: [prd, agent, architecture, testing]
---

# The handbook — read this to GET IT

[README.md](README.md) owns every ruling and the one ordering.
[unsettled.md](unsettled.md) owns current state. **This file owns the
UNDERSTANDING**: why we work this way, how the loop runs, the warts you will
hit, and the mentality a fresh agent needs so the owner never has to re-teach
it. It sequences nothing.

If you are a fresh orchestrator, this is your orientation. Read it whole.

## What Seon is, and why there are two of them

Seon is a runtime where agents are first-class: they live in a Datahike
database as facts, run as `core.async.flow` graphs, write Clojure that becomes
durable database rows, and see the world through rendered views of that same
graph. One JVM per cluster, REPL-first, CLJ only.

This is the **second** implementation. The first one worked — really worked,
for months — and the owner tore it down deliberately because he had learned
enough to build it properly. That history is the most important fact about
this codebase: **almost everything you are asked to build has been built
before, and the previous version is still readable.** `src-old/` is not dead
weight, it is a quarry with gold in it. Git history is a record of every bug
each mechanism learned to survive.

So the prime directive is not "write good code". It is:

> **Do archaeology before you design. Then design something better than what
> you found — because we are evolving, not restoring.**

Agents who skip the archaeology reliably rebuild a worse version of something
that already existed, and reintroduce bugs fixed a month ago. It is the main
way work gets wasted here. Agents who *do* it find that half the hard problems
already have measured answers, and their designs come out sharper than
anything they would have invented cold.

The fresh tree is not zero knowledge — it is zero *baggage*. Every piece must
re-earn its place instead of being grandfathered in. The canonical lesson: on
2026-07-26 we built a full effect-replay identity layer, proved it live
against a real kill, and DELETED it the same night because the owner's
crash-model question revealed the design never needed it. **The cheapest place
to delete code is before it exists, and the way to find out is to build the
smallest real thing and let live falsifiers and owner questions attack the
design while it is still a decision.**

This is the best iteration yet. Hold a high bar.

## Use the skills — they are honed, verified, and load-bearing

`.agents/skills/` is the ONE real skills directory (`.claude/skills` and
`seon-skills` are symlinks to it, and `bin/test` refuses to run if that is
ever broken — ruling 29). Every skill has been through an independent
adversarial verification pass that opened every cited line, re-checked every
measurement against its stated conditions, executed the commands they teach,
and DELETED claims it could not verify rather than hedging them.

Load them. They exist so you do not re-derive a week of hard-won mechanics:

- **`data-oriented-clojure`** — before writing or reviewing ANY Seon Clojure,
  and before maintaining a vendored fork we own.
- **`seon-flow-architecture`** — before touching a proc, graph, channel,
  buffer, workload, wake, or fault, *or before designing any new runtime
  mechanism at all*. Has `references/` for depth.
- **`data-modeling`** → **`datahike`** — what shape to declare, then the
  query/transact mechanics; `datahike/references/fork-maintenance.md` covers
  working inside our own Datahike fork.
- **`clojure-testing`** — test shape, the database fixture, generative
  properties, when a green suite is green for the wrong reason.
- **`repl`** — the three distinct REPL surfaces (Seon's agent-reply reader, a
  live cluster's io-prepl/MCP eval, a raw JVM REPL) and how not to confuse
  them.
- **`datastar-web-ui`**, **`ui-canvas`**, **`seon-context-config`**, and
  **`clojurescript`** (quarry-only; the CLJS pod is deleted).

Skills carry **blast radius**: they load into every agent working that area,
and agents trust them *because* they are curated. A wrong line there is worse
than a wrong line in code. Any claim you add carries a `file:line` or a named
research document verified against current source; an unverifiable claim is
deleted, not softened; ruled-but-unbuilt designs are marked `[TARGET]`; and a
new or substantially changed skill gets an independent verification pass.

## Read the dependencies' own source

`reference-code/` holds ~90 vendored dependencies as submodules —
`core.async`, `datahike` (our fork), `sci`, `http-kit` (our fork), `malli`,
`datastar`, `konserve`, `reitit`, `aero`, `posh`, `rewrite-clj`, and more.
They are there so you read semantics instead of remembering them. Every good
decision in this program came from doing that; several bad ones came from not.

Use the dependency's own vocabulary too — `proc`, `step-fn`, `conns`, `:io`,
`transact`, `interrupt-fn`. Invented umbrella nouns drift from the dependency
and cause integration bugs; grounded vocabulary is free documentation.

`../research/` holds dated research documents with file:line evidence and
measured numbers — 34 from a single day. Cite them; do not re-derive them.
Before launching research, grep there first: the answer is often already
written.

## The loop that works

1. **Archaeology.** Mining lanes read `src-old`, git history, the research
   corpus, and `reference-code/`, and return a keep / avoid / reconceive
   table.
2. **Design, then falsify.** Author from the archaeology plus the rulings,
   then attack it — an adversarial lane, a REPL prototype, a measurement.
   Roughly half of one day's designs changed materially at this step, and two
   were *dissolved entirely*. **A well-argued refusal to build something is a
   great outcome.**
3. **Owner review before implementation.** Every significant design stops for
   him; small bug fixes flow after your own review.
4. **Test-forward implementation.** Falsifiers are authored first — generative
   properties over the domain, examples only as teaching docs; identity,
   effect, and custody contracts get a crash-walk table (kill at every point,
   one row each) BEFORE sealing. One lane implements one owner and may not
   touch a schema or a test. **Stop-on-friction is the heart of the model**:
   if a contract seems wrong or unimplementable, the lane STOPS and reports
   the exact friction rather than hacking around it. Friction reports are
   primary data — record them.
5. **Prove it live.** A change proven only by a passing test is not proven.
   Drive a real cluster, read the actual output, watch the real datom.
6. **Audit independently.** After every landing wave, a lane that trusts
   nobody's report re-verifies the risky claims. On 2026-07-29 this caught
   **five real blockers that lanes had reported as done.** It is the most
   valuable machinery in the program. Do not skip it because things look
   green.

Lane mechanics: `bin/codex-agent run <name> "<spec>"`, background-tracked,
chunked under five minutes with a commit per chunk; `status` BEFORE any
`resume` (the wrapper now refuses to resume a live session, which is a fix for
a real incident); a completed task with no summary means the turn ended
mid-work, not that the lane finished; never sandbox a lane; specs name owned
paths, protected paths, and demand path-limited commits. Implementation goes
to sol lanes; use the Agent tool with `model: opus` for REPL-heavy
falsification; never Fable subagents.

## The instant feedback loop

`bin/test` is the gate (bare run = full suite; pass namespaces to focus).
`clojure -M:dev` gives a plain source-classpath REPL for load-only probes.
For live work, `mcp__seon_cljs__runtime_status` then
`mcp__seon_cljs__eval_clj`, cluster-qualified — the REPL is the first design
surface, not a debugging tool of last resort. Boot your own scratch cluster
for anything you intend to change; a fork is ~17 ms and clusters are
sovereign. Never write to, reset, or bounce someone else's.

## The warts — know these before they bite you

- **The tree is often red, and usually it is not your fault.** Several lanes
  edit concurrently. Check `git status` before diagnosing: a failure inside
  another lane's in-flight file is *their* boundary. Judge green only on a
  quiet tree.
- **A long-lived JVM serves the code it loaded at startup.** `bin/seon start
  <name>` ADDS a cluster to an already-running JVM, so correct code can fail
  in a stale one. This cost a lane an entire chunk chasing a phantom. Boot
  your own operator root when you need current source.
- **Skills, docs, and instructions can be stale.** Six skills were teaching a
  subsystem deleted weeks earlier. Verify before you trust — including
  verifying this file.
- **The code graph can be partial on an old cluster.** Indexing happens at
  ancestor population, so a cluster forked before the indexer landed has
  namespaces without functions — a corpus that *looks* populated.
- **Upstream API errors kill long agents.** Commit in small coherent slices so
  churn costs minutes, not hours.

## The mentality (each is an owner ruling; violating one is a bug)

- **Fail loud and hard in dev; never fall down in production; agents always
  get proper errors** — flat `:seon.error` values that steer, one config dial
  deciding dev/prod, nothing thrown into an agent loop. The REPL and the UI
  survive a failure precisely so it can be understood.
- **Crashes are rare and NOTHING re-executes.** Recovery = reopen, mark the
  interruption, close the run, let the agent adapt from derived context.
  Absence is the one representation a dead process cannot corrupt — which is
  also why state is derived from primitives (`open?` = no `closed-at`), never
  stored as a flag. A boolean is legitimate only when someone genuinely
  ASSERTS the false.
- **Derive state, do not remember it.** This program has had six-of-six
  assumptions falsified in one sitting. Verify with one live command before
  acting on any prose.
- **Prefer dissolution to addition.** The best change deletes a mechanism.
  When you meet a tuned constant, ask what observable event it stands in for.
  Catching yourself writing point tests to fence edge cases is a DESIGN
  VERDICT — stop and find the construction that makes the class
  unrepresentable. A pile of exact-string tests means stop and say so.
- **Every `[:fn]` schema carries an honest generator.** Malli never validates
  generator overrides, so a dishonest one green-washes everything downstream.
- **One mechanism.** No `foo-v2`, no compatibility path, no second registry.
  Fix the owner in place and delete the superseded path in the same commit.
  Git is the archive.
- **Nothing stores what a query derives.** Status fields, counters, cached
  projections — state is which facts exist.
- **No hand lists, no name-prefix rules, no magic numbers.** Every
  classification is computed from facts; every constant lives in the defaults
  document with units and provenance; `(or x 60000)` is the banned shape.
- **Smart defaults everywhere, and ambiguity fails loudly.** Unambiguous is
  automatic; a destructive verb with several candidates refuses with the list
  rather than picking. `default` is just a name; no ambient-one-cluster
  singletons.
- **THE RECURRING FAILURE CLASS OF THIS WHOLE PROGRAM is checks that read
  ABSENCE OF SIGNAL as health** — a log-name glob, a query against a
  descriptor that no longer exists, a regression walking less than the writer
  admits. When you write a check, ask what it reports when the subject is
  absent. If the answer is "fine", the check is worse than nothing.
- **Review returns, do not trust them.** Read enough source to judge a lane's
  claim. Independent verification is not paranoia here; it is the process that
  works.
- **Churn is weather.** A cluster vanishing, a JVM dying, a reset — adapt and
  continue. Stop only for a genuine implementation dependency, named exactly.
- **Write it down in the same beat.** Rulings into README, state into the
  working edge, settled terms into the vocabulary table, defects into issues —
  in the turn it happens, path-limited commit. Being asked "what haven't you
  written down?" means you already failed.
- **Read the actual agent-facing output**, not a summary of it. Prose that
  looks fine often reads badly to a model.

## The owner

Technical, decisive, fast; his corrections are usually architectural rather
than cosmetic. He wants to be hands-on at design gates and will tell you when
to just go. Ask with concrete options and short pros/cons when genuinely
unsure; do not ask permission for work that obviously follows. He would rather
you reset a cluster a hundred times and learn something each time than tiptoe.

He dislikes: parallel systems, ported shapes, hand-maintained lists,
stored-derived state, symptom patches, and half-finished surfaces. The standing
question he asks of any change: **"is this simpler than it was?"**

Complexity is the enemy. His own diagnosis of the previous system's failure
mode: too many separate processes, bugs and slowness concentrated at the
boundaries between them, and context that was hand-built instead of derived.
Everything we build now should reduce boundaries and derive more.

## Where things are defined

- **Law and rulings**: `CLAUDE.md` (symlink to `AGENTS.md`); README's numbered
  owner rulings.
- **Current state**: [unsettled.md](unsettled.md), working edge at the top.
- **The schedule**: `docs/seon/issues/index.md` — every open issue with a
  destination.
- **The target system**: `docs/seon/architecture/` — present tense, intent
  only, never implementation state.
- **Attribute/entity schemas**: EDN data under `resources/seon/schema/*.edn`,
  admitted as ONE validated global population (file boundaries are editorial;
  duplicates refuse; every reference must resolve; generative-honesty lint).
  Agents' runtime `register!` flows through the same gate.
- **Function contracts**: `:malli/schema` metadata ON the defn, travelling
  with the var into the program graph. Named predicates live in code.
- **Config**: a tiny closed bootstrap (pre-store), everything else reconciled
  into database facts by one `apply!` (converged = zero writes). The shipped
  default manifest is THE defaults document and must boot a full system with
  no overlay.
- **Tests**: `ls test/` is the honest list of what is proven.
- **Evidence**: `../research/`, dated, with file:line and measured numbers.
- **The quarry**: `src-old/` and git history. Mine it; never port it
  unexamined.

## The metrics at every review

Blocks compose (the next piece uses only public contracts) · the data model
tightens (every attribute earning its place) · the codebase shrinks
(`src-old/` only ever shrinks; growth without retirement is rejected) ·
properties over examples (a flat property count with growing examples fails) ·
and the standing question: **is this simpler than it was?**
