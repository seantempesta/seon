---
type: research
status: complete
tags: [research, ui, render, architecture]
---

# Reitit adoption implementation and proof

## Dependency ledger

- Reitit is vendored at `reference-code/reitit` commit
  `106fc4c7a09290c8e2df2d4ef9570ea1322ab2ab` (release 0.10.1). The live
  dependency is `metosin/reitit-ring {:mvn/version "0.10.1"}` because the
  vendored module includes `Trie.java` and is source grounding rather than a
  usable root `:local/root` coordinate.
- `reitit.core/match-by-name!` and `match->path` are grounded in
  `reference-code/reitit/modules/reitit-core/src/reitit/core.cljc:60-76`.
  `match-by-name!` refuses missing parameters but returns nil for an unknown
  route name, so `seon.render.route/path` adds the loud unknown-name refusal.
- Default path- and name-conflict checks are grounded in
  `reference-code/reitit/modules/reitit-core/src/reitit/core.cljc:329-380`.
  The source `def` router compiles at namespace load with those defaults.
- Ring endpoint compilation, the keyword middleware registry, and request
  dispatch are grounded in
  `reference-code/reitit/modules/reitit-ring/src/reitit/ring.cljc:18-76,
  121-150,360-390` and
  `reference-code/reitit/modules/reitit-core/src/reitit/middleware.cljc:7-33,
  84-122`.
- The one creation seam is `seon.cluster/ensure-entity!`, currently defined at
  `src/seon/cluster.clj:884-895`; it calls `ensure-entity-call` inside the
  transaction and writes the process provenance metadata. Namespace ownership
  is queried by `seon.cluster.agent/owner-of` at
  `src/seon/cluster/agent.clj:100-111`.
- Existing first-party HTTP and socket proof is
  `test/seon/render/web_test.clj`. Pure named-path, conflict, and source-scan
  proof is `test/seon/render/route_test.clj`.

## Implementation

`seon.render.route/routes` is the one source route vector. A Reitit core router
compiles it at namespace load for reverse routing and build-time conflict
refusal. `seon.render.web/handler` binds the table's handler identifiers to the
service closures and compiles the Ring router with the one same-origin
middleware registry. The old `cond`, path regular expressions,
`exact-agent-id`, inline POST gate, and render-owned dynamic URL concatenations
are deleted.

Namespace admission is ordered:

1. Reitit decodes the path parameter.
2. Clojure's reader reads it with `*read-eval*` false; only a simple symbol
   whose printed spelling exactly round-trips is retained.
3. The immutable database value must already contain that `:seon.ns/name`.
4. The existing owner is used, or the one idempotent ensure transaction creates
   the deterministic owner and assignment with process provenance.

An unknown or reader-invalid namespace returns the ordinary no-match 404 before
the ensure seam. Agent routes resolve through the namespace assignment and are
aliases; `/` resolves through root's assignment. Reitit's default handler is a
plain 404, so the retired 302-home behavior remains dead.

## Recurring proof

The first focused gate after converting legacy fixtures to production namespace
assignments passed 36 tests / 150 assertions / 0 failures / 0 errors in
`seon.render.web-test`. After correcting one test expectation that used a set
where Datahike's collection find returns a vector, the combined
`seon.render.route-test` + `seon.render.web-test` gate passed 40 tests / 230
assertions / 0 failures / 0 errors.

The recurring route checks prove every named route reverse-routes and matches
back to the same name, encoded path and query values use Reitit, duplicate names
refuse at router construction, unknown names fail loudly, and a computed scan
of the render corpus finds none of the retired hand-built URL forms.

After the public walk seam landed, the combined route/web gate passed 41 tests /
240 assertions / 0 failures / 0 errors. The namespace-page regression now
asserts the total HTML walk, the canonical route's namespace projection, alias
root selection, and the debug construction: one call to public
`seon.render/walk` supplies the exact AI text beside the HTML neighbourhood.
The two panes derive from the same immutable database value captured by the
handler.

## Live scratch-cluster proof

The proof ran in a repository-local isolated operator root at
`tmp/reitit-proof-root`, whose source/resource/test paths pointed at this
checkout while its `data/clusters` and JVM were independent. The empty root
first refused to start without `current-src`, as designed; `init` published
commit `6a6d0f0a-85fa-5423-b6e1-664414bfa01d` with digest
`01f57e863aa81539a9f4595594ace9b1b52ee1b248a9381734a3116f2bdaf4ab`.
`reitit-proof` then booted on dynamic HTTP port 7739 and prepl port 58390.

The initial database sample was 20,541 datoms at basis 536870925 with no owner
for `seon.flow`. `GET /ns/seon.flow` returned 200, rendered the
`<code>seon.flow</code>` namespace projection, and ensured owner `"seon.flow"`.
The committed owner transaction resolved to process provenance
`"16071-1785532176245"`; the resulting database was 20,546 datoms at basis
536870926.

From that exact database value, `GET /ns/nonexistent.thing` and
`GET /ns/123bad` each returned 404. A second database sample remained exactly
20,546 datoms at basis 536870926, proving both refusals wrote no datoms and
committed no transaction. The former failed corpus-row admission; the latter
failed the reader's simple-symbol round trip.

`GET /`, `GET /agent/seon.flow`, and `GET /ns/seon.flow` all returned 200.
The canonical namespace and agent-alias response bodies were byte-identical;
the database query resolved root to `my.agents.root`, and `/` rendered that
namespace. `GET /ns/seon.flow/debug` returned 200 with one
`;; (seon.render/walk` header, `debug-ai-seon.flow` and
`debug-html-seon.flow` panes, the `grid-template-columns:1fr 1fr` layout, and
the HTML `seon.flow` projection. The scratch cluster then stopped through the
operator prepl path, and its empty JVM exited.
