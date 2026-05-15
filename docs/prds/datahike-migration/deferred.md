---
type: prd
status: active
tags: [prd, decision, architecture]
---

# Deferred — what we deliberately haven't built, and why

Companion to `remaining.md` (operational punch-list) and `decisions.md`
(locked architecture). This file lives where the *architectural*
"we don't need this yet" calls go — each entry captures the deferral
once, links to the spec/chunk it belongs to, and names the concrete
trigger that would bring it back.

The dispositions here are decisions, not gaps. Code FIXMEs and
spec-01 chunk-status banners point back here. When a deferred item
becomes load-bearing again, its trigger fires and the entry moves to
`decisions.md` (or, if it's superseded, gets a tombstone with a
pointer to whatever replaced it).

## Static-ingest path (seon.graph.analyzer + seon.graph.ingest)

**Status:** deferred — orthogonal to substrate work; replaced as the
load-bearing graph capture surface by Phase H-2 dynamic ingest.

**What's deferred:** fixing `seon.graph.ingest/ingest-incremental!`
and `ingest-analysis!`'s lookup-ref forward-reference behaviour against
datahike. Today the ingest path transacts a `:seon.fn` entity that
lookup-refs `[:seon.spec/key …]` before the matching `:seon.spec`
entity is in the db; datalevin tolerated this (inserted-as-tempid),
datahike throws `:entity-id/missing`. Fix is well-understood
(rewrite same-batch lookup-refs to tempid strings like
`tu/transact-full-graph!` does for the shape↔entry cycle), but no
live consumer needs it today.

**Architectural why:** the JVM-only clj-kondo analyser is fine for
operator/agent introspection of seon's own source (`:scope :repo`
queries), but the post-2026-05-15 architecture (Decision 30 +
Chunk H-2) makes the *agent's* code graph a per-eval tx-listener
capture pod-side — totally different code path, JVM ingest doesn't
apply. The renderer auto-discovery that used to consume static-ingest
is dead (Path D, see §"Renderer auto-discovery" below). Net: nothing
load-bearing for the 6-week demo runway depends on this path
working.

**Reflected today in:** 6 test files documented in
`remaining.md` §"M-2b test ports — deferred to M-3 (2026-05-15)" —
`test/seon/graph/{context,ingest,query,shape,shape_generative}_test.clj`
and `test/seon/dev/test_select_test.clj`. The src under
`src/seon/graph/{analyzer,ingest,context,query}.clj` continues to
load and is exercised live by anyone who actually calls into it; the
ingest-time forward-ref bug only surfaces when the project-scan
ingest path runs.

**Trigger to revisit:**
- A real consumer for `seon.graph.query :scope :repo` (operator/agent
  introspection of seon source).
- OR the Phase H-2 dynamic-ingest design solidifies and we want a
  one-shot operator scanner that triggers the same tx-listeners as
  H-2's per-eval capture — collapsing both entry points (live agent
  eval vs operator scan) onto one ingestion pathway.

## Renderer auto-discovery (find-renderer / resolve-renderer / namespace-proximity)

**Status:** deferred indefinitely; possibly resurfaces in a future
form. Code stays dormant per Sean's 2026-05-15 "prune at the end"
call.

**What's deferred:** rewiring `seon.render/find-renderer`,
`resolve-renderer`, `resolve-renderer-cached`, `call-cached-renderer`,
`find-page-renderer`, `namespace-proximity` to function against the
running datahike flow. On the current boot they're effectively
no-ops — they query a graph that's never populated.

**Architectural why:** the renderer-redesign-proposal (committed
2026-05-15) replaced graph-query-driven auto-discovery with **Path D
— polymorphic value-types + stock Malli `:default` + symbol-resolve
at the boundary**. Agents transact entities carrying
`:seon.render/ai` / `:seon.render/html` map entries (literal value
*or* a symbol pointing at a render fn). The boundary `m/decode`s
with `default-value-transformer` to fill missing keys, then resolves
symbol values via `requiring-resolve` (CLJ) or the bundled CLJS
compiler (pod). No graph traversal at boundary time. Per
Sean: "I want explicit rendering paths expressed via specs and just
be returning data pointing at the renders. So that's a
simplification, but eventually the dynamic renderer might come back."

**Reflected today in:** Phase R chunks R-1 / R-2 / R-3 in spec-01
implement the explicit Path D dispatch; R-4 (the suggest fallback
that *would* use the graph) is itself deferred (see §"R-4" below).
The auto-discovery fns in `seon.render` are reachable but return
nil/no-op until something populates the graph — chunk M-1
renamed the internal helpers (`resolve-renderer-from-datalevin` →
`resolve-renderer-cached`, `call-datalevin-renderer` →
`call-cached-renderer`) and kept the auto-discovery surface intact.

**Trigger to revisit:**
- Path D's explicit-pointer convention proves to be too much
  overhead for agents (e.g. the agent forgets to attach the render
  symbol and the fallback gets called constantly).
- A use case surfaces that needs *discovery* over *declaration* —
  i.e., "given this data, find the best renderer" is genuinely
  required by some agent flow that can't pre-declare.

## R-4 — suggest-on-nil + functions-accepting-keys

**Status:** deferred; no live consumer.

**What's deferred:** spec-01 Phase R chunk R-4 — implementing
`seon.graph.query/functions-accepting-keys` (input-side inverse of
`functions-with-output-key`) + `seon.harness/suggest` + the always-on
suggest output at the MCP boundary when `try-render` returns nil.

**Architectural why:** R-4 consumes the static-ingest graph path
(see §"Static-ingest path" above). Without a populated `:seon.runtime`
graph, `functions-accepting-keys` returns empty results regardless of
how nicely `seon.harness/suggest` formats them. The renderer redesign
(Path D, see §"Renderer auto-discovery" above) removed the
auto-discovery surface that was R-4's primary motivation. Nothing
substrate-side depends on R-4 landing.

**Reflected today in:** spec-01 Phase R chunk R-4 header carries a
status banner pointing here. No code stub exists; `seon.harness`
namespace doesn't exist.

**Trigger to revisit:**
- The Phase H-2 per-agent dynamic ingest lands and populates the
  agent's own user-namespace graph, AND a use case for "suggest fns
  that accept these keys" surfaces in agent-facing UX.
- OR static-ingest comes back for `:scope :repo` queries and we want
  the same affordance for operators exploring seon's source.

## seon.repl/code-index-updated-test (and the eval-form! code-index side-effect)

**Status:** test deferred; live behaviour swallows its own failure.

**What's deferred:** asserting that `seon.repl/eval-form!`
successfully populates the knowledge graph after each form is
evaluated. The deftest `code-index-updated-test` was dropped from
`test/seon/repl_test.clj` during the M-2b port.

**Architectural why:** `eval-form!` calls
`update-code-index!` → `seon.graph.ingest/ingest-incremental!`,
which trips the same `:entity-id/missing` forward-ref bug as the
deferred static-ingest path above. The exception is caught and
logged inside `update-code-index!` (`(try … (catch Exception e
(log/warn …)))`) — so the *test surface* this exercises stays
exception-free, but the actual code-index update is broken on
datahike. The fix lives behind the static-ingest revival.

**Reflected today in:** dropped deftest in `test/seon/repl_test.clj`
with an inline comment naming this entry. `update-code-index!` itself
keeps catching + logging — the warn-spam shows up in test output as
each `eval-form!` runs.

**Trigger to revisit:** when the static-ingest path is revived
(§"Static-ingest path" above), restore the deftest and assert that
the post-eval graph state contains the expected `:seon.fn` row.

## seon.ctx/persist! round-trip tests

**Status:** test cluster deferred behind chunk M-4.

**What's deferred:** the four ctx persist/load round-trip tests in
`test/seon/ctx_test.clj` (`persistence-round-trip-test`,
`manual-persist-test`, `non-serializable-stripped-test`,
`load-without-instance-test`), plus the two lifecycle persist tests
(`instance-resume-round-trip-test`, `backup-all-instances-test`) in
`test/seon/ns/lifecycle_test.clj`.

**Architectural why:** `seon.ctx/persist!` still calls into
`seon.db/resolve-conn` (the deprecation shim left after M-2 that
throws `:seon.db/unregistered-namespace` for any caller). M-4
redesigns `*ctx*` per the Forward Decisions in `remaining.md` —
atom semantics, `add-watch` auto-persist, warn-on-unserializable,
and rehydrate-from-datahike-on-resume. The round-trip tests should
come back wired to the M-4 mechanism, not the legacy `persist!`
shape.

**Reflected today in:** dropped deftests in `ctx_test.clj` +
`lifecycle_test.clj` with inline `;; M-2b: dropped pending M-4`
comments. The three `ensure-instance-*` lifecycle tests stub
`lifecycle/inject-vars!` via `with-redefs` to skip the dependent
`*conn*` injection path — that stub retires when M-3 / M-4 fix the
production inject-vars to not assoc `::db-name nil` into the
downstream call.

**Trigger to revisit:** chunk M-4 lands.

## seon.ai.claude + seon.ai requiring-resolve stubs

**Status:** code stubbed with `FIXME(M-3):` breadcrumbs; tests
deleted permanently (subject removed in M-2).

**What's deferred:** porting `seon.ai.claude` and `seon.ai`'s
message + session persistence from the deleted `seon.ai.datalevin`
namespace to a registered `:seon.ai` datahike-flow namespace.
Currently `seon.ai/datalevin-write!` is a no-op with a warn;
`seon.ai.claude` and `seon.ai`'s `requiring-resolve` call sites are
stubs returning nil/placeholder maps.

**Architectural why:** chunk M-3 (`:seon.runtime` + `:seon.ai`
migration to datahike flow) owns this work. M-2 stubbed rather
than ported because the LLM provider abstraction (litellm
integration) is its own focused effort and shouldn't ride in the
substrate-cleanup commits. M-2's stubs carry `FIXME(M-3):` markers
naming the disposition.

**Reflected today in:** `src/seon/ai.clj` `datalevin-write!` stub +
5 stubbed read fns; `src/seon/ai/claude.clj` 3 stubbed call sites;
the `/agents` HTTP route returns 404 (deleted with `seon.web.agents`
in M-2's `c415374`). The `test/seon/ai_test.clj` + `ai/claude_test.clj`
are gone permanently — restore as datahike-fixture ports when
M-3 lands.

**Trigger to revisit:** chunk M-3 lands.

## seon.test.bootstrap (and the shape-test fixture machinery)

**Status:** stays deleted; needs a `with-test-db-fixture`-shaped
replacement if/when shape testing returns.

**What's deferred:** the `seon.test.bootstrap` namespace (three
POCs deleted in M-2: `bootstrap.clj`, `bootstrap_v1_inmemory.clj`,
`bootstrap_v2.clj`) plus the `with-test-bootstrap` macro it
exposed. `test/seon/graph/shape_test.clj` +
`graph/shape_generative_test.clj` consumed this macro.

**Architectural why:** the bootstrap namespace was a Phase-2/3
research POC, not part of the boot path. Its functionality
(stand up a fixture with extracted-graph data preloaded) is now
covered by `tu/transact-full-graph!` against the canonical
`tu/with-test-db-fixture`. The two shape tests are deferred behind
the static-ingest path (§"Static-ingest path" above) since they
depend on the broader `seon.graph.*` fixture story working.

**Reflected today in:** `seon.test.bootstrap` is gone; the shape
tests are in the §"Static-ingest path" deferral group.

**Trigger to revisit:** when the static-ingest path is revived,
port the two shape tests to `tu/transact-full-graph!`.

## Renderer machinery cleanup (post-MVP prune)

**Status:** deferred per Sean's "let's prune at the end once we
have a working system" call (2026-05-15).

**What's deferred:** removing the unused render machinery in
`seon.render` (`find-renderer`, `resolve-renderer`, the
specificity-sort algorithm), the multimethod-based `seon.ns.view`,
the dead `seon.ui.viewer`, and `seon.render.example`. Renderer-
redesign-proposal §H originally proposed a 250-line deletion in
R0; that prune was scoped down to ~40-80 lines of actively-broken
datalevin-conn paths only.

**Architectural why:** the dormant machinery might inform future
approaches (or come back if §"Renderer auto-discovery" is revived);
deleting it now closes off optionality the team isn't ready to
close.

**Reflected today in:** the namespaces above all load; their
unused fns are reachable but uninvoked.

**Trigger to revisit:** post-MVP, when the new system is shipping
and the experimental playground can be pruned.

---

## How this file is referenced

- **Code FIXMEs:** existing `FIXME(M-3):` / `FIXME(M-4):` breadcrumbs
  in `src/seon/ai.clj` etc. point at the relevant chunk. New
  FIXMEs added for architecture-relevant stubs should append
  `;; deferred — see datahike-migration/deferred.md §<anchor>`.
- **Spec-01 chunk banners:** affected chunks carry a
  `**Status: deferred — see `seon/docs/prds/datahike-migration/deferred.md` §<anchor>**`
  line directly under the chunk heading. Currently: R-4.
- **`remaining.md`:** the M-2b section points at this file for
  architectural framing; operational disposition (which test file,
  which line) stays in `remaining.md`.

## How a deferred item moves out

When the trigger condition fires, the entry's disposition resolves:

- **Becomes implemented** → entry is removed; the change-log of
  `decisions.md` (or the chunk in spec-01) records the new state.
- **Becomes superseded** → entry is replaced with a tombstone:
  one-line "superseded by <thing>; see commit <SHA>", date.
- **Becomes permanently abandoned** → entry stays but moves under a
  §"Permanently deferred" subsection with a final rationale.

No silent removals.
