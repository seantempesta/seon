---
type: research
status: active
tags: [research, agent, context, render, data-model]
---

# Design lab milestone 1: smallest inspection slice — 2026-09-05

This is a read-only source inspection for milestone 1 of the
[design-lab PRD](../plan/design-lab-prd-2026-09-05.md). I read that PRD and
the related
[investigation](design-lab-investigation-2026-09-05.md) end to end. The
inspected worktree is based at `32f835b8e076221f7259a5137229f1881ce591f7`
and contains other sessions' edits, including the same-arity renderer repair.
Line references below describe those current bytes. No source, database,
cluster, process, or browser state was changed by this inspection.

## Conclusion

The smallest honest milestone is one new observation value rendered inside
the existing namespace debug page. It starts from an arbitrary Datahike entity,
keeps the viewing namespace as a separate input, and combines three values:

1. bounded outgoing datoms and bounded incoming reference assertions from one
   immutable database value;
2. the exact ordered decision that `seon.render/render-call` consumes; and
3. that call's existing retained evidence and actual returned output.

This needs no new route, renderer registry, traversal engine, SCI context,
Datastar feed, or stored inspector entity. Reuse
`GET /ns/{namespace}/debug` and `GET /agent/{id}/debug`; add a `subject` query
parameter whose EDN value is a `:seon.render.walk/lookup`, and treat the route
namespace as the viewing namespace. The agent alias resolves its assigned
namespace first. For milestone 1 the page labels the current policy exactly;
it does not claim that the viewing namespace affects selection when the current
selector instead receives an ownership-derived namespace. That policy change
belongs to milestone 2.

The implementation should not edit `seon.render.walk`. Its current pull-first
neighbourhood is useful comparison evidence, not authority for arbitrary-root
inspection or future renderer discovery.

## Current path and reusable evidence

### Existing debug and delivery owner

- `seon.render.route/routes` already owns both canonical debug paths and the
  feed (`src/seon/render/route.clj:5-27`). Query parameters require no route
  addition.
- `seon.render.web/debug-response` builds the debug document, currently as two
  AI/HTML panes (`src/seon/render/web.clj:1820-1854`).
- `debug-page-result` derives both panes at one supplied database value
  (`src/seon/render/web.clj:638-678`). `page-result` already captures actual
  HTML render calls and returns `:seon.render/captured-calls`
  (`src/seon/render/web.clj:308-439`).
- Debug pages already enter the render proc through a distinct registration
  key `[::debug-tab agent-id]` (`src/seon/render/web.clj:931-943,1459-1462`).
  The proc owns revisioned packages, deltas and keyframes
  (`src/seon/render/web.clj:680-755,988-1072`), and each tab still owns only a
  sliding-one tap and delivered revision (`:1431-1573`). The inspection panels
  should become stable fragments in that package. Do not add a second SSE or
  browser-side diff path.
- Stable morph ids come from `seon.render.block/surface-id`
  (`src/seon/render/block.clj:65-96`). The current debug CSS already establishes
  fixed chrome, dense monospace text and independently scrolling pane bodies
  (`resources/public/css/input.css:1171-1265`). Replace the two-column contents
  with a compact header plus data, candidates and result regions; preserve the
  existing palette and density.

The registration key must include every input that changes the derived page,
for example `[::debug-tab viewer-namespace subject output]`. Keeping only
`agent-id` would let two tabs with different subjects share one package and
would make retained-call evidence answer the wrong experiment. Query path and
display-only disclosure state remain browser-local signals; they do not belong
in the server key.

### Actual renderer call

The current selection chain is concentrated in `src/seon/render.clj`:

- `explicit-producer` checks the value, then the request (`:216-220`);
- `candidates` sorts the explicit namespace's public functions and validates
  the actual prepared argument plus requested output (`:178-203`);
- `schema-producer` validates pulled and transaction forms, prefers the most
  required attributes, and refuses multiple producers (`:228-270`);
- `declared-producer` and `producer` add the attribute/form and structural
  floors (`:291-318`); and
- `render-call` consumes that decision and performs the real bounded SCI call
  (`:620-709`).

The output must come from `render-call`, not from a debug imitation. A unique
debug `:seon.render.call/id` plus a supplied `:seon.render/captured-calls` atom
already yields this reusable evidence:

| Existing field | What it proves |
|---|---|
| `:seon.render.call/static-evidence` | the selection inputs that must remain current |
| `:seon.render.call/producer` | the selected qualified function symbol |
| `:seon.render/would-fall-to-floor?` | whether selection reached the structural floor |
| `:seon.render.call/declaration-row` | the acquired program snapshot row used for source identity |
| `:seon.render.call/argument` | the actual argument after render custody/default preparation, with live handles removed |
| `:seon.render.call/read-evidence` | Datahike dependency plans and revisions observed by invocation |
| `:seon.render.call/basis-transaction` | the immutable database basis used for that call |
| `:seon.render.call/output` | the actual admitted and fitted return value |

Those fields are assembled by `call-static-evidence` and `render-call`
(`src/seon/render.clj:320-338,643-697`). Invocation resolves the selected live
SCI Var, applies SCI's call-preparation hook, enforces the time limit, captures
database reads, and admits the return (`src/seon/render.clj:369-400` and
`src/seon/sci/kernel.clj:544-625`). SCI itself defines that hook as a call-time
operation over the executing context, Var, and evaluated arguments
(`reference-code/sci/src/sci/core.cljc:310-319`). This is the actual system
path; milestone 1 should not use `evaluate-candidate`.

`program-function` returns the acquired snapshot's source/namespace/private
metadata (`src/seon/sci/kernel.clj:126-152`), while full function/arity facts
remain queryable under the selected `[:seon.fn/sym ...]` entity. Existing
fields include `:seon.fn/sym`, `:seon.fn/source`, `:seon.fn/spec`,
`:seon.fn/ns`, and component `:seon.fn/arities`; each arity already carries
`:seon.fn.arity/order`, `:seon.fn.arity/arity`, `:seon.fn.arity/input`,
`:seon.fn.arity/output`, `:seon.fn.arity/input-refs` and
`:seon.fn.arity/output-refs`
(`resources/seon/schemas/seon.fn.edn:1-59` and
`resources/seon/schemas/seon.fn.arity.edn:1-46`). Pull these actual facts for
the candidate rows; do not duplicate a function registry in web state.

### Actual graph data

`seon.render.walk/root-acquisition` accepts an arbitrary lookup and returns
`:seon.render.walk/root`, `:seon.render.walk/members`,
`:seon.render.walk/order`, and, per member, its stable lookup, eid, path,
found depth, shallow value and actual connections
(`src/seon/render/walk.clj:198-312,337-410`). It is useful as a labelled
baseline beside the new local observation. It is insufficient as the datom
table because its recursive pull does not preserve every assertion's `tx` and
its traversal/ownership choices are precisely what the lab must test.

`seon.render.web/generic-entity` is also insufficient: it fully realizes EAVT
through `seon.db/datoms`, performs a Datalog reverse-ref query, and applies the
cap only after those reads (`src/seon/render/web.clj:445-513`). The `/data`
route uses it for entity roots (`:1913-1992`), so the milestone should replace
that call with the shared observation or leave `/data` alone; it must not copy
this implementation into the inspector.

The vendored Datahike already contains the missing acquisition primitive:
`datahike.api/index-page` is referentially transparent, eager, bounded, accepts
`:max-result-weight`, and returns a page plus `complete?` and an exclusive
cursor (`reference-code/datahike/src/datahike/api/specification.cljc:810-822`).
Its implementation validates index/prefix/cursor, seeks lazily, stops at the
prefix, reads width+1, excludes the cursor item, and certifies result weight
(`reference-code/datahike/src/datahike/index_page.cljc:14-37,78-160`).

Use EAVT `[eid]` for outgoing assertions. For incoming refs, enumerate the
installed ref attributes from the database schema and use an AVET page
`[attribute eid]` for each attribute that returns data. This derives reverse
edges from actual assertions without storing mirrors. Each returned datom
already has `e`, `a`, `v`, `tx`, and `added`; Seon's `datom->data` additionally
decodes declared EDN storage (`src/seon/db.clj:1207-1219`). A thin public
`seon.db/index-page` must own the Datahike call and decoding because direct
`datahike.api` use does not belong in `seon.render.data`.

The observation header can reuse:

- `seon.db/database-value-identity` for branch, basis and commit
  (`src/seon/db.clj:160-184`);
- the cluster database's one `:seon.source/digest` assertion as source program
  identity; and
- `:seon.schema.projection/fingerprint` from the exact projection carried by
  `sci.kernel/context-projection` (`src/seon/sci/kernel.clj:178-186`).

Do not call the cluster data commit the program commit. A missing source digest,
projection fingerprint, entity, cursor, or page must render a typed unavailable
diagnostic rather than an empty healthy table.

## Exact implementation slice

The smallest coherent production patch owns these files and functions:

1. **Decoded bounded index page.** Add `seon.db/index-page` beside `datoms-call`
   in `src/seon/db.clj`. It calls Datahike's existing `index-page`, decodes each
   datom through the existing `datom->data`, carries `complete?` and cursor,
   records conservative `:all` read evidence like `datoms-call`, and translates
   dependency exceptions into the existing flat database error value. Declare
   the request/page/cursor shapes in `resources/seon/schemas/seon.db.edn` and add
   focused coverage in `test/seon/db_test.clj` for first page, exclusive next
   page, prefix refusal, EDN decoding, and result-weight refusal.

2. **One immutable entity observation.** Add a public map-in/map-out function,
   suggested name `seon.render.data/entity-observation`, to
   `src/seon/render/data.clj`. Inputs are exactly database value, subject lookup,
   page limit, optional outgoing continuation and optional per-attribute incoming
   continuations. Each continuation is an observation envelope carrying the
   database-value identity, index, prefix and Datahike cursor, so using it at a
   different snapshot or prefix returns a typed refusal before reading. Output
   contains snapshot identity, resolved subject/eid, outgoing
   datom page, incoming ref pages, installed identities present on the subject,
   and explicit diagnostics. Declare it in
   `resources/seon/schemas/seon.render.data.edn`; test it in a new mirrored
   `test/seon/render/data_test.clj` with a real ref, reverse ref, missing subject,
   high-degree page and stale/cross-prefix cursor. It remains pure.

3. **One selection explanation consumed by rendering.** Extract the decision
   now inside private `producer` into one public contracted function, suggested
   name `seon.render/selection`. It returns an ordered vector of the current
   stages (explicit value, explicit request, namespace contract fits,
   schema-declared producer, floor), every discovered candidate's compatible or
   rejected status, any ambiguity/refusal, and the selected producer. A rejected
   namespace function can truthfully say that no one arity accepts the supplied
   arguments and declares the requested output; finer input/output attribution
   should be reused only if the same-arity owner exposes it. `render-call` must
   consume the returned selected producer. This makes the explanation and action one
   decision. Keep actual invocation, fit, captured reads and output in
   `render-call`. Declare the explanation rows in
   `resources/seon/schemas/seon.render.edn` and extend
   `test/seon/render_simplification_test.clj` to prove explanation order,
   selected producer equality, ambiguity, and floor. Integrate rather than
   duplicating the in-flight same-arity predicate; its owner is reviewed
   separately.

4. **Put the observation on the existing debug route.** In
   `src/seon/render/web.clj`, parse the debug `subject` and `output` query
   parameters, resolve the viewer from the canonical namespace or agent alias,
   and render stable header/data/candidates/result fragments. The result panel
   calls `render-call` once and displays the captured evidence above. Extend
   debug registration keys and feed query parameters with viewer, subject and
   output so packages and retained calls cannot cross experiments. On the
   canonical debug branch, bypass `ensure-namespace-owner!`; the ordinary
   namespace page keeps its current first-touch behavior. An agentless namespace
   therefore has a viewer namespace and cluster SCI/program projection but no
   invented agent context or prompt. Update the open schemas in
   `resources/seon/schemas/seon.render.web.edn`, the semantic styles in
   `resources/public/css/input.css`, and focused HTTP/feed cases in
   `test/seon/render/web_test.clj`. Do not edit `src/seon/render/route.clj`.

This is four seams, but one vertical behavior. Splitting out only the UI would
recreate selection and perform unbounded reads in `seon.render.web`; splitting
out only data/candidate APIs would fail the milestone's owner-visible proof.

## Acceptance proof

One focused fixture creates two agents in different namespaces and a third
namespace with no agent. It transacts one subject with two identities, a scalar,
an outgoing ref, an incoming ref, and enough additional ref assertions to cross
one page. Using the existing HTTP server and feed:

1. open the same `subject` through each agent debug alias and the agentless
   canonical namespace debug route;
2. assert the header's viewer namespace, subject, database branch/basis/commit,
   source digest and projection fingerprint;
3. assert non-empty outgoing and incoming rows, exact `e/a/v/tx/added` values,
   identity assertions, and a working exclusive continuation;
4. assert candidate rows are in the exact `seon.render/selection` order and the
   displayed selected symbol equals
   `:seon.render.call/static-evidence/:seon.render.call/producer`;
5. assert the displayed argument and output equal the captured `render-call`
   entry, including a visible flat error for a missing subject;
6. assert the two viewers remain separately labelled even if current policy
   selects the same producer, and assert the agentless GET/feed creates no
   agent and no transaction; and
7. assert a transaction changing one displayed assertion advances the same
   existing debug package/feed and updates the datom/result fragments without
   cross-painting another subject's tab.

Compare `db/basis-t`, complete datom count, agent count and render-cost fact
count before and after the initial GET/feed. They must be identical. A successful
HTTP status alone is not the proof.

The focused gates are the explicit database, render-data, render selection and
web namespaces. Browser verification checks readable source/values, stable
selection while a package arrives, and no console error; the server test proves
the SSE path if browser automation drops the connection.

## Dependencies and unresolved blockers

- **Same-arity selection is an integration dependency, not work in this slice.**
  The shared tree currently changes `seon.render/candidates` to call
  `schema/function-accepts-and-returns-in?`. Milestone 1's explanation must use
  that completed predicate and its tests after the owning lane settles; this
  report does not review or duplicate it.
- **Arbitrary candidate execution remains blocked by SCI candidate isolation.**
  Milestone 1 executes only the producer selected through the existing reviewed
  render path. Listing candidates and showing their contracts is safe. A UI
  control that invokes a different candidate waits for milestone 0's context
  isolation proof; SCI `fork` only replaces `:env`
  (`reference-code/sci/src/sci/core.cljc:345-351`).
- **Viewer preference is deliberately unresolved.** The current walk derives an
  owning namespace from the value/ref graph and nested selection differs from
  top-level selection (`src/seon/render/walk.clj:456-491,519-608`). Milestone 1
  shows that fact. It must not silently reinterpret viewer as owner or rank
  distant namespaces before milestone 2 supplies and tests those rules.
- **Incoming-ref pagination has a bounded multiplicative cost.** AVET is ordered
  by attribute then value, so arbitrary-target reverse lookup requires one seek
  per installed ref attribute. The first slice reports ref-attribute probes and
  returned work. If measured schema breadth is too costly, that evidence is the
  reason to add a dependency-owned target-first index or another explicit
  observation mechanism; it is not a reason to store reverse-edge mirrors.
- **The debug prompt is separate evidence.** `debug-prompt` still prefers a
  historical capture (`src/seon/render/web.clj:590-596`), while prospective
  history bypasses full provider prompt assembly. Keep captured/prospective
  prompt comparison visible but outside the data-selection-result assertion;
  provider parity belongs to milestone 4.
- **The Datastar skill was stale and was repaired with the implementation.**
  `.agents/skills/datastar-web-ui/SKILL.md` now describes canonical debug as
  read-only entity/render inspection and grounds it in the existing package,
  retained-call, and SSE mechanisms.

## Implementation evidence, 2026-09-05

The implemented slice stayed inside the existing route, render-call, floor,
retained-call, package and SSE owners. The canonical namespace debug GET does
not create an agent or facts. Its request identifies viewer, arbitrary subject,
output, explicit observation/pull bounds and opaque cursors. The primary stored
value uses the existing floor with structural projection enabled; ordered
selection evidence, actual output and bounded raw datoms remain separately
inspectable. Prompt comparison is explicit rather than part of initial paint.

A same-open-tab live proof observed a committed `:seon.ns/doc` marker appear at
the new database basis and disappear after its restoring transaction, without a
browser reload. The first attempt also exposed and fixed a real compatibility
failure: a retained registration created before the cursor field existed passed
nil to `seon.render.data/at` and stopped the render pass. The page now derives
the canonical root cursor when the request omits one.

Debug acquisition now occupies an ordinary retained-call entry with database
read evidence, so an unchanged database value reuses it. Evaluation changes use
a separate sliding-1 input on the same render proc. That input invokes the same
render pass with retained-call invalidation, covering both the bounded debug
observation and selected output; a database wake cannot replace this code-change
signal. The durable regression interleaves a database wake after each of two
evaluation markers and requires all three visible phases (initial plus both
updates).

The existing live database feed test passed independently during development.
The exact unchanged-database acquisition regression, the final combined gate
and a fresh-topology reset remain integration work because adding the required
render proc input changes graph topology. Every render-step fixture now carries
the separate channel. The focused evaluation-wake regression passed through
`seon.test.runner/run-var!`: 80 assertions, zero failures and zero errors. It
painted the initial phase plus two evaluation changes, placing an ordinary
database wake behind each evaluation signal.

### Integration checkpoint after the shared reset

The shared operator and MCP health query both report no running cluster at
this checkpoint. `bin/seon status` reports 0.71 GiB at the root and 619 GiB
usable. The other session owns the shared reset; no fresh browser or topology
proof is claimed. The required channel fixtures and focused interleaved
evaluation/database-wake regression are complete.

The prerequisite fault-storage gate is red: `bin/test seon.error-test
seon.cluster.fault-storage-test` recorded 34 tests, 224 assertions, six failures
and zero errors in `tmp/test-runs/run.7Lwok8`. Its file-backed trial stored 500
faults with a maximum inline evidence length of 658, but grew from 56,381,169
to 147,575,152 bytes. The 91,193,983-byte growth does not satisfy the storage
issue's acceptance criterion. Investigation must distinguish retained data,
index rewrite cost and reclaimable objects before changing that criterion.
One test also incorrectly demanded an uncapped 3.6 MB payload from an
admission-capped projection and printed that payload on failure; fixing that
fixture does not resolve the measured storage growth.

The ordered-form/evaluation audit is a separate prerequisite simplification:
the current start records are written for an entire reply before execution,
so their presence does not prove execution began. The audit is checking result
settlement, interruption, curation and effect references before removing the
duplicate entity. Its evidence lives in
[the form/evaluation audit](form-evaluation-storage-audit-2026-09-05.md).

The subsequent focused checks used the existing
`seon.test.runner/run-var!` capture path: generated-system prefix settlement
returned 81 passes, zero failures and zero errors; runtime evaluation markers
interleaved with database wakes returned 80 passes, zero failures and zero
errors. Every current render-step fixture now supplies its required evaluation
channel. These are focused proofs, not a combined gate or a fresh running
browser proof. Both checks also encountered a pre-body source-span indexing
error on an earlier attempt; that evidence is recorded in the existing
[shared-edit publication issue](../../../seon/issues/bin-test-shared-base-compiles-other-lanes-half-edits.md).

### Fresh process observations

The other session subsequently restarted `default`: PID 1170, process start
2026-09-05T23:32:33Z, PREPL 53638, web port 7994. A fresh MCP JVM session
reported the required runtime-evaluation channel present and zero core fault
facts at basis 536870955, commit
`6a9ca73e-481b-5663-a7d8-2138d1a1f7ce`, source digest
`ab402df7a2ee4381a9b550693080dcfe695842358ce24da7d998afaac6cad41b`.
This is point-in-time evidence; later file edits still require publication or
explicit hot reload before a live claim.

The canonical `seon.flow` debug GET returned its 1,818-byte loading shell in
20 ms. A bounded ten-second connection to its declared feed received one
14,444-byte Datastar patch with data, selection and output fragments; HTTP
first-byte latency was 3.8 ms. That first-byte measurement is not a measured
time-to-complete-render. The full selected HTML output was reduced to an
elision describing 63,913 original characters, so a readable actual-output
preview remains a visual defect. Browser automation could not inspect the
layout because the Mac was locked.

The cache falsifier also remains red at this point: an unrelated message
transaction changed observation/discovery/invocation counts from 1/1/1 to
2/2/2. The fix is in the existing read-evidence mechanism and must retain
comparison values only transiently, preserving the batched evaluation path's
removal of bulky durable read results.
