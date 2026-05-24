---
type: concept
status: active
tags: [concept, architecture, agent, cljs]
---

# Code as data — the runtime IS the database

**The CLJS substrate's source, the agent's eval log, and the
in-memory analyzer state are three views of the same code corpus.
Persisting the agent's defining forms as `:seon.fn` / `:seon.ns` /
`:seon.schema` entities lets the substrate, the agent, the resume
mechanism, the warnings system, and the cross-agent code-sharing
gate all read from one place.**

This is the architectural principle that connects bootstrap
seeding, detect-and-tee, bulk-load resume, the publish gate, and
the disk-write debug mode. They look like five separate features;
they are one mechanism viewed from five angles.

## The three views

1. **Substrate source code** (`.cljs` files on disk in development;
   bundled resources in WASM). The thing humans (or earlier agents,
   in the recursive-bootstrap case) wrote and committed.
2. **Live analyzer state** at `@!compile-state` —
   `:cljs.analyzer/namespaces` carries every `defn`, every `def`,
   every ns's `:requires`, every var's metadata. Populated at boot
   from the bootstrap analyzer caches + extended on every successful
   agent eval.
3. **Datahike entities** — `:seon.ns`, `:seon.fn`, `:seon.schema`
   in the DB. Identity-attr upsert means "the agent's current
   program graph".

These views agree by construction. The analyzer state is built FROM
source files (at substrate boot) or FROM agent evals (via
detect-and-tee). The DB entities are projections of the analyzer
state. The reconstituted source for bulk-load resume is built FROM
the DB entities, then re-evaled to rebuild the analyzer state.
Source → analyzer → DB → source (reconstituted) → analyzer. The
circle closes.

## Five mechanisms, one principle

### Substrate boot — source files as the substrate seed

First-boot helper walks `@!compile-state`'s `seon.*` namespaces,
reads each source file from disk, slices defining forms by
`:line`/`:column`, transacts `:seon.ns` + `:seon.fn` +
`:seon.schema` entities. No separate `bootstrap.edn` file.

Same code path as detect-and-tee — both produce the same entity
shape from analyzer state. The substrate is just the agent's
first commit.

### Detect-and-tee — agent code becomes DB entities

After every successful `cljs.js/eval-str`, snapshot/diff
`@!compile-state`'s `:defs` map for the eval's ns. New defs
produce `:seon.fn` entities (with `:fn-var?` distinguishing
`defn` from non-fn `def`). Atom-diff against
`seon.schema/*schemas` produces `:seon.schema` entities.

Identity-attr upsert means redefinitions replace; history retains
prior versions. The DB's program graph IS the agent's accumulated
work.

### Bulk-load resume — DB entities become source again

For each persisted `:seon.ns`, reconstitute one source string from
DB (ns form + all schemas + all defs in `:created-at` order).
Topo-sort over `:seon.ns/requires`; bulk-eval each ns-string as a
single file. The analyzer handles intra-file ordering itself.
Same model `cider.nrepl.middleware.load-file` uses when you hit
ctrl+enter in an editor.

Why this is right: an agent with 100 defns across 10 nses
resumes via 10 `eval-str` calls, not 100. Forward refs within a ns
resolve naturally (the analyzer's `truly-undeclared?` check fires
in the callback after all JS executes). `(ns foo …)` at file top
is non-destructive of the file's own defs. Cycles get caught at
write time, not resume time.

### Publish gate — well-specced+tested code propagates to all agents

A tx-listener on `:seon.schema/source` mirrors DB writes into the
in-memory Malli registry — schemas propagate freely (Sean: "they
are fully namespaced this should be fine"). A parallel listener on
`:seon.fn/source` mirrors into the function-schema registry, but
only when `:seon.fn/specced? true` (v1) AND (v2+)
`:seon.test/last-passed-at > :seon.test/last-failed-at`. The gate
ensures only proven code becomes globally instrumented.

This is how agents coordinate via the database. Agent A commits a
specced+tested fn; the listener fires; the fn lands in every
agent's instrumented runtime on next render. No subscription, no
RPC, no event bus.

### Disk-write debug mode — runtime IS a file system

When `:seon.runtime/debug-write? true`, every detect-and-tee + every
bulk-load resume also writes the reconstituted ns source to
`tmp/debug/agents/<agent-id>/<ns-as-path>.cljs`. The runtime's
program graph becomes a real directory tree of `.cljs` files.

Uses:

- **Inspection** — `cat tmp/debug/agents/AbCdEfGh1234/seon/agent/AbCdEfGh1234.cljs`
  shows exactly what the agent's home ns contains right now.
- **Editor hookup** — point shadow-cljs (or any CLJS editor) at the
  debug folder. Get autocomplete, jump-to-def, real navigation
  over the agent's accumulated work.
- **Export** — `zip -r agent-AbCdEfGh1234.zip tmp/debug/agents/AbCdEfGh1234`
  produces a runnable substrate snapshot. Drop it elsewhere, point
  a fresh pod at it, the agent's work runs.
- **Recursive bootstrap** — an agent's accumulated `.cljs` files
  become the basis for the *next* seon substrate version. The
  agent's work isn't trapped in a DB; it's source code, ready to
  ship as substrate the next round.

The disk write is a DERIVATION from DB state, on a flag. No
authority is moved off the DB; the disk files are just another
view, like the analyzer state.

## What this rules out

- **Build-time `bootstrap.edn`** — replaced by source-at-boot. No
  separate emission rig, no drift risk between source and a
  generated file.
- **`(seon.code/extract-defn-name)`-style source re-parsing for
  program-graph extraction** — the analyzer state IS the
  authoritative view of what was defined. Source re-parsing throws
  away information the analyzer already produced.
- **Per-eval replay for resume** — "execute every fucking eval
  we've ever done" is the wrong model. Bulk-load matches how
  editors actually work.
- **A separate "publish" RPC for cross-agent code sharing** —
  schemas + specced+tested fns propagate via the same tx-listener
  that mirrors the registry. The publish gate is a query on
  current DB state; no separate machinery.

## What it does NOT rule out

- **Source files as the authoring surface** — substrate developers
  edit `.cljs` files in their editor. Source is a first-class
  citizen.
- **The DB as the agent's authoring surface** — agents author via
  REPL evals; detect-and-tee captures them as DB entities. Source
  is the rendering format, not the storage format.
- **Caching of expensive derivations** — if reconstitute-ns-source
  becomes a hot path, memoize keyed on the ns's latest tx-id. Same
  rule as reactive-context: cache, don't bifurcate.

## Cross-references

- `docs/seon/concepts/reactive-context.md` — the sibling principle
  for the rendering surface. Code-as-data is to the program graph
  what reactive-context is to the warnings tile: derive from DB,
  no separate path.
- `docs/prds/agent-runtime/v1.md` §2.2 — the schemas
- `docs/prds/agent-runtime/v1.md` §7.3 — substrate seed from source
- `docs/prds/agent-runtime/v1.md` §7.4 — bulk-load resume
- `docs/prds/agent-runtime/research/analyzer-driven-extraction-and-resume-2026-05-24.md`
- `docs/prds/agent-runtime/research/resume-as-bulk-file-load-2026-05-24.md`
- `docs/prds/agent-runtime/research/schema-registry-unification-and-resume-2026-05-24.md`
