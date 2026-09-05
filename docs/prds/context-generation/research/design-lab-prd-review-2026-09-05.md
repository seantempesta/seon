---
type: research
status: complete
date: 2026-09-05
tags: [research, context, render, web, architecture]
---

# Independent review: the Design Lab PRD

## Scope and method

I read end to end the PRD under review
(`docs/prds/context-generation/plan/design-lab-prd-2026-09-05.md`), the target
it serves (`docs/prds/context-generation/plan/agent-centric-design-2026-09-04.md`),
the prior independent review
(`docs/prds/context-generation/research/second-opinion-2026-09-04.md`), and
`AGENTS.md` sections 1--3 and 6. I then read every requested first-party seam
at HEAD `104e0bea251cb4731b58f1d778fd8efd1ed82484`, checked the live schema and
Var metadata through the required MCP on an isolated cluster, fetched the real
debug page, and measured the operator. I did not mutate the shared operator
root or `ctxprobe`, and made no paid call.

The isolated commands were:

```text
mkdir -p /Users/sean/src/seon/tmp/lane-prd-review-root
/usr/bin/time -p bin/seon --root /Users/sean/src/seon/tmp/lane-prd-review-root init
/usr/bin/time -p bin/seon --root /Users/sean/src/seon/tmp/lane-prd-review-root start prd-review
curl -sS -D - http://127.0.0.1:7848/agent/root/debug
```

An absent root directory is not created by the wrapper: the first `init`
attempt refused immediately because `bin/seon:7-12` requires an existing
directory. After `mkdir`, complete source publication took **60.59 s real**
(`68.50 s user`, `6.58 s sys`) and published commit
`6a9c4aa7-f549-5b4f-a622-7c50e754ca3a`. Start took **11.31 s real**
(`1.89 s user`, `0.34 s sys`); runtime readiness reported **5,698 ms**. The
fresh process served HTTP `7848` and prepl `64121`.

## Verdict

**Do not start Wave 1 from this PRD yet.** The purpose is clear and worthwhile,
and most substrate nouns name real mechanisms. The implementation plan is not
complete or internally consistent. It treats intended target contracts as
though the current debug page already exposes them: the current selector and
candidate enumeration are private, the prospective prompt is broken on the
fresh between-runs case, no stewardship attribute exists, the web service owns
one cluster connection rather than selectable branch connections, and the
three proposed projections do not yet share an entry model or layout function.

The root cause is a missing dependency boundary between **observation data**
and **presentation**. The PRD specifies a rich UI, but does not first specify
the total, bounded values that the UI consumes: a neighbourhood page, a
candidate explanation, an SCI-binding description, a prompt entry, and a
branch comparison. That omission makes private implementation helpers look
reusable and lets several acceptances pass on empty or unavailable data.

## 1. Completeness and internal consistency

### Contradictions

1. The lab cluster is disposable and is repeatedly reset
   (`design-lab-prd-2026-09-05.md:53-58`), while the decision log is stored on
   that cluster (`:145-150`). `reset-cluster!` atomically replaces the cluster
   branch head (`src/seon/cluster/registry.clj:242-280`); the old tail is then
   collectible (`:247-248`). A log not yet copied to the design document is
   therefore lost by the experiment it is meant to record.
2. “Any production data model change” is out of scope (`design-lab…:203-207`),
   but the first world requires extending the agent model with stewardship if
   absent (`:152-158`). It is absent: the complete agent entity schema contains
   only id, optional namespace, and optional run
   (`resources/seon/schemas/seon.cluster.agent.edn:1-16,72-82`), and the live
   installed-schema query returned no attribute whose namespace contains
   `steward`.
3. “No new infrastructure, no new names” (`design-lab…:40-48`) conflicts with
   an unowned stewardship attribute, a decision-log fact shape, branch actions,
   and several new diagnostic values. If the ruling means only “no lab-specific
   route, namespace, or command,” it must say that narrower thing.
4. Every displayed function is said to be either indexed source or an agent
   function from a settled turn (`design-lab…:49-52`), while the disposable
   world's persistent definition is said to be real Clojure in the tree
   (`:53-56`). Agent-defined functions, seed messages, plan items, and decision
   notes are branch facts and disappear on reset unless a replay/source-
   initialization mechanism is named. None is.
5. The first world's data is called “real and already present” (`:154-160`),
   but only `my.note` program facts are already present. The steward agent,
   stewardship edge, two messages, plan item, and any settled agent-defined
   functions are not present on a fresh cluster.
6. The page promises the SCI environment, prompt, and HTML “from the same facts
   by the same looked-up functions” (`:31-38`), but elsewhere gives each
   projection a local ordering algorithm (`:63-67`) and gives SCI installers
   rather than `/ai` or `/html` render functions in the served target
   (`agent-centric-design-2026-09-04.md:241-258`). There is no defined common
   selection contract for an installer and a renderer.
7. The implementation note says the Cytoscape script is “inlined in the page
   and pinned from cdnjs as the atlas does” (`design-lab…:173-177`). The atlas
   does not inline it; it loads a network script from cdnjs
   (`repl-first-atlas.html:1-3`). Version-pinned, network-loaded, locally
   vendored, and inlined are different delivery guarantees.
8. The PRD says the current prospective prompt is the comparison baseline
   (`design-lab…:59-62,134-140`) and that every wave must keep current debug
   output working (`:193-197`). On a fresh cluster it is already unavailable;
   HTTP success hides that failed subject. This is recorded independently in
   `docs/seon/issues/prospective-debug-walk-omits-agent-id.md:8-46` and was
   reproduced below.
9. Wave 4 says a schema edit, reset, refresh, graph, candidates, and projections
   complete in under 15 s (`design-lab…:171`), but measured full publication
   plus start was **71.90 s** before browser work. `reset --force` itself downs
   processes, deletes the managed root, republishes/reforks `default`, and does
   not start the web server (`script/seon/fresh_operator.clj:2994-3017`), so a
   browser refresh needs a subsequent start as well.

### Undefined terms and decisions

- **“Existing agent-creation route.”** The HTTP table has no creation route
  (`src/seon/render/route.clj:5-27`). `seon.cluster/ensure-entity!` is a real
  function (`src/seon/cluster.clj:1893-1945`), while namespace-page access has
  an implicit creation path that assigns the namespace itself as the agent's
  home (`src/seon/render/web.clj:1752-1769`). Neither creates
  `my.agents.<id>` plus a separate stewarded `my.note` edge.
- **World loader.** No file, function, transaction boundary, idempotence rule,
  or reset hook is named for the seed messages and plan item. “Kept as real
  Clojure in the tree” does not say whether this is source initialization data,
  an operator action, or a replayed agent turn.
- **Entity picker and identity.** Missing/ambiguous lookup refs, entities with
  several identity attributes, and entities matching zero/several schemas have
  no selection or refusal rule. `matching-shapes-in` can honestly return many
  schemas (`src/seon/schema.clj:3313-3334`), but it does not choose an identity.
- **Neighbourhood page.** Stable order, page size, database basis, continuation,
  requery identity, and behavior when an entity disappears between pages are
  unspecified. “Bounded, ordered page” is not a contract.
- **Transaction instant per attribute.** Cardinality-many attributes have one
  datom and potentially one transaction per value; the PRD does not say whether
  it displays each assertion, the newest assertion, or current value plus
  provenance.
- **Proposed candidate ranking.** “Namespace distance,” “coverage,” “recency,”
  the injectable set, and the ordering of ties are not defined. With many
  stewards, namespace distance also needs an explicit graph and direction.
- **Processing candidate.** An input ref being present does not establish that
  every other argument can be supplied, that a collection is accepted, or that
  the function is safe to recommend. The prior review already identifies this
  gap (`second-opinion-2026-09-04.md:224-231`).
- **SCI projection.** No named owner specifies how requires, newest defs,
  readable result handles, and wrappers are described without mutating a ctx;
  no error values cover an unreadable def, tied definition, absent program row,
  or wrapper refusal.
- **Demand DAG.** The PRD does not carry forward the served design's limitation
  to settled usage facts / reader-resolvable names
  (`agent-centric-design-2026-09-04.md:511-514`). Aliases, refers, macros, and
  generated symbols remain outside the stated proof.
- **“Main” and “branch.”** `:db`, `:current-src`, the lab's
  `:cluster-<name>` branch, an experiment branch, and a Git code branch are
  distinct. The page sketch uses `main`, the branch section says “lab cluster's
  head,” and the reset experiment says “code branch” without defining the
  comparison baseline.
- **Decision log.** Identity, transaction provenance, ordering, bounds, write
  route, which branch receives a note, and the mechanism that copies it to a
  tracked document are all unspecified. “When a question closes” supplies no
  closing criterion or authority.
- **Ruled population.** The 300 ms gate names no entity, datom counts, browser,
  warm/cold state, percentile, database basis, or whether Cytoscape layout time
  is included.

## 2. Does it explain why?

The high-level purpose is clear. Section 0 says the work is to redesign storage
and rendering for “the SCI environment,” “the text sent to the completion
endpoint,” and “the HTML the user sees,” and to test storage shapes and render
functions before production commitment (`design-lab…:14-29`). A reader with no
design-session context can understand why this lab exists.

What is missing is the decision procedure. The phrase “until the best system
for all three projections is found by evidence” (`:27-29`) never defines
*best*, which observations decide between two shapes, or when the lab has
answered enough to authorize production work. Likewise, “when a question
closes” (`:148-150`) does not name who closes it or the evidence required. The
PRD should state the terminal deliverable: a chosen storage model plus three
total projection contracts, with every rejected alternative and the decisive
measurement recorded. Without that, the page can become a compelling explorer
that never resolves the architecture question.

## 3. Feasibility by wave

### Wave 1 — feasible only after a bounded observation contract

What exists:

- The route is real and wired: `/agent/{id}/debug` is a named GET route
  (`src/seon/render/route.clj:13-16`) bound to `debug-response`
  (`src/seon/render/web.clj:2024-2046`).
- The shell already gives a debug tab its own feed URL and keeps the Datastar
  opener outside morph targets (`src/seon/render/web.clj:181-228,1823-1857`).
- `generic-entity` can enumerate direct datoms and reverse refs
  (`src/seon/render/web.clj:445-513`), and `matching-shapes-in` supplies the
  honest 0/1/many schema result (`src/seon/schema.clj:3313-3334`).
- The print owner has real structural elision data carrying omitted count,
  path, next offset, profile, and either requery identity or a refusal
  (`src/seon/print.cljc:283-304,686-707,800-827`).

What is missing or wrongly assumed:

- `generic-entity` is not used by the debug page at all; its only call is the
  `/data` handler, with reverse refs disabled
  (`src/seon/render/web.clj:1943-1947`). It materializes every reverse-ref pair,
  groups and counts in memory, and only then takes the cap
  (`:487-510`), so it is not the bounded database-side count/page primitive the
  PRD describes.
- Direct refs are reduced to `{:db/id ...}` handles and no datom transaction
  instant is retained (`:454-464`). Wave 1 needs a new pure observation value,
  not merely a new drawing.
- The current debug SSE is not live on an ordinary database commit. The proc
  sets `derive-all?` only for a join, settlement, or stream
  (`src/seon/render/web.clj:1287-1319`), and a debug registration refreshes only
  when that flag is true (`:935-947`). A commit wake can therefore leave an
  open graph stale until reconnect or another qualifying event.
- The shell's head contains only local CSS and local Datastar
  (`src/seon/render/web.clj:197-206`). The real HTTP response had no
  `Content-Security-Policy` header, matching the page response's sole content-
  type header (`:1838-1841`), so CSP does **not** prevent Cytoscape today.
  Cytoscape is technically feasible, but the PRD must choose one delivery
  design: extend the global shell, accrete a head-assets input, or serve a
  vendored `/js` resource. Loading/initializing a script inside an SSE-morphed
  body is not specified, and a cdnjs dependency is neither inlined nor
  offline-deterministic.
- Cytoscape instance teardown/rebuild after a morph is unowned. Repeated page
  updates can leave event handlers and graph objects behind unless the lifecycle
  is explicit.

Conclusion: the route and delivery substrate are reusable, but the observation
contract and browser lifecycle are new work. Calling this only “the graph of
real data” hides the critical half.

### Wave 2 — the selector exists; diagnostic enumeration and ranking do not

What exists:

- HEAD selection really is explicit render function, then the unique contract-fitting
  public function in one explicit namespace, then a schema-declared render function,
  then floor (`src/seon/render.clj:218-320`). It returns deterministic
  ambiguity data rather than choosing insertion order (`:207-216,313-320`).
- `render-ai`, `render-html`, and `render-call` execute through the guarded SCI
  boundary and the one fit owner (`src/seon/render.clj:369-400,509-573,622-650`;
  `src/seon/print.cljc:908-943`). `call-static-evidence` already knows the
  selected render function, declaration row, argument, and floor status
  (`src/seon/render.clj:322-340`).
- Schema matching over actual pulled/transaction forms already handles
  several candidate entity schemas and most-specific required attributes
  (`src/seon/render.clj:230-272`).

What is missing or wrongly assumed:

- The PRD names `seon.render/producer` as though it were reusable public API
  (`design-lab…:117-124`). Both `producer` and `candidates` are private
  `defn-`s (`src/seon/render.clj:178-205,301-320`); the isolated live metadata
  probe returned `{:producer-private? true :candidates-private? true}`. The
  public `render-call` exposes one result, not the complete ordered candidate
  set or the rejection explanations the inspector needs.
- Today's candidates validate the complete **actual argument** and output
  contract within one explicitly supplied namespace
  (`src/seon/render.clj:178-205`). The proposed scheme instead reasons from
  arity input refs, injectables, namespace distance, coverage, and recency.
  None of that proposed ranking exists in these owners.
- “Run any candidate” needs a new public diagnostic boundary which invokes the
  selected symbol through the same kernel and fit profile without transacting
  render-cost facts. Direct Var invocation would bypass the mechanism under
  test. Rendering currently avoids cost writes only when the request lacks a
  held run/connection pair (`src/seon/render.clj:700-710`).
- A contract fit alone does not establish purity. The program graph has
  `:seon.fn/external-sink` and `:seon.fn/projection-boundary` precisely for this
  exclusion (`AGENTS.md:454-456`); the PRD does not say how effectful functions
  are refused from render-candidate execution.

Conclusion: Wave 2 is a substantive render-owner API/design wave, not a debug-
page wiring wave. It should not reach into private Vars from `seon.render.web`.

### Wave 3 — the three projections do not yet have one shared entry model

What exists:

- `debug-prompt` prefers the latest durable captured prompt and otherwise calls
  the prospective path (`src/seon/render/web.clj:515-600`). A capture stores
  exact prompt bytes and ordered contribution evidence
  (`src/seon/context.clj:136-200`).
- Prompt assembly already computes per-segment token contributions as
  differences of cumulative calibrated estimates, so separators are counted
  exactly once and the contributions sum to the whole estimate
  (`src/seon/cluster/prompt.clj:151-180`).
- The page's normal HTML delivery and debug keyframe/delta transport are real,
  bounded and reconnectable (`src/seon/render/web.clj:1330-1577`).

What is missing or wrongly assumed:

- The prospective path does not work at HEAD for a never-run/between-runs
  agent. It puts the agent ID only in `:seon.render.walk/lookup`
  (`src/seon/render/web.clj:546-561`), while history separately reads
  `:seon.cluster.agent/id` for `message-custody`
  (`src/seon/render/walk.clj:763-776`). On the isolated fresh cluster,
  `GET /agent/root/debug` returned HTTP **200**, body **3,769 bytes**, status
  `unavailable`, error kind
  `:seon.render.web/prospective-context-unavailable`, and contract arguments
  `[nil]` where `message-custody` requires database, optional run id, agent id,
  and message eid. This is absence of the prompt hidden by transport success.
- Prospective history entries carry call id, basis, form, printed value, and
  bytes (`src/seon/render/walk.clj:780-793`). They do **not** carry token count,
  intent/provenance, selected render function, handle, or prerequisite edges.
- Durable prompt contributions carry tokens and a generic block name `:walk`,
  but not the producing render function (`src/seon/cluster/prompt.clj:172-179`;
  `src/seon/context.clj:143-152`). The current path cannot populate the proposed
  per-entry inspector without accreting evidence at its owner.
- There is no named SCI-description projection in the requested seams. The
  actual agent row stores only its namespace/run refs, while installed bindings
  live in the SCI ctx and restored defs have separate facts. The PRD must name
  the pure fact-to-description owner before a UI lane can implement it.
- Current prompt and page are calculated separately: `debug-page-result` calls
  `page-result` for HTML and `debug-prompt` for AI
  (`src/seon/render/web.clj:642-680`). That is not yet “the same entries through
  `/html`.”
- Current history ordering is only non-current-task entries followed by the
  current task (`src/seon/render/walk.clj:796-829`). No live layout function,
  dependency DAG, or teaching-before-use insertion implements the proposed
  order.

Conclusion: Wave 3 contains at least four new owners—entry data, SCI
description, prerequisite derivation/layout, and evidence accretion—plus UI.
The existing prospective prompt is useful quarry, not a working baseline.

### Wave 4 — branch primitives exist, selectable branch rendering does not

What exists:

- `registry/branch!` creates a named branch from a branch or exact commit and
  is idempotent (`src/seon/cluster/registry.clj:168-214`).
- `store/open-branch!` opens an existing roster branch and refuses absent or
  already-open branches (`src/seon/cluster/store.clj:401-432`).
- `reset-cluster!` can replace a disconnected cluster branch from an exact
  source commit (`src/seon/cluster/registry.clj:242-281`).

What is missing or wrongly assumed:

- Every debug/page/feed read dereferences the web service's single
  `:seon.store/connection-object` (`src/seon/render/web.clj:1823-1837,
  1899-1914`). The service contract does not even carry the root store
  (`resources/seon/schemas/seon.render.web.edn:96-119`). Selecting a branch
  requires explicit store custody, connection acquisition/release, and a
  database value handed through every derivation.
- The live SCI ctx and its schema projection were acquired for the running
  cluster's program commit. Rendering an older/different experiment branch
  through that ctx can mix program facts from one branch with Vars/projection
  from another. The PRD does not state the compatibility fence or typed
  refusal.
- No GET/POST route exists for fork, compare, or decision-log writes
  (`src/seon/render/route.clj:5-27`). The only mutating web route is the
  same-origin message POST (`:17-19`). Branch creation and note append require
  explicit same-origin effect boundaries; they cannot be hidden in a render
  GET.
- A page tab that switches branches needs a connection-lifecycle owner. An
  open branch connection cannot be opened twice in the process
  (`src/seon/cluster/store.clj:401-432`), so per-click acquisition without a
  registry/ref-count design will either refuse or leak.
- Reset publishes/reforks `default`, not the named `prd-review` cluster, and
  leaves no web server running (`script/seon/fresh_operator.clj:2994-3017`).
  The measured 71.90 s publication+start path refutes the 15 s acceptance at
  this HEAD; it is not merely an unmeasured risk.

Conclusion: the database dependency has the low-level branch mechanisms, but
the proposed web ownership, compatibility, and lifetime model are absent.
Wave 4 is not confined to `seon.render.web` and `seon.render.route`.

## 4. Acceptance criteria: measurable only after strengthening

Several criteria currently read absence of their subject as health.

1. **Wave 1 graph.** “Direct Datalog verifies the counts”
   (`design-lab…:165`) can pass for an absent entity or a real entity with zero
   inbound/outbound refs. The gate must first assert one unambiguous identity,
   the database basis, and nonzero independently-created fixtures for direct,
   inbound, and cardinality-many refs. It must then compare the complete count
   plus a stable page against an independent query. A rendered empty graph is
   not proof.
2. **Wave 1 requery.** “The generated requery form works” (`:165`) does not say
   that an elision was actually produced, rather than a fit refusal, nor what
   identity and basis the next page preserves. The fit owner can emit either a
   requery identity or a refusal (`src/seon/print.cljc:283-304,800-827`). The
   gate needs a deliberately oversized value, a present elision, a successful
   next page, no duplicate/omitted members across pages, and an explicit stale-
   basis result.
3. **Wave 1 latency.** “Ruled population” and 300 ms (`design-lab…:165`) omit
   population, hardware, browser, cold/warm state, percentile, and whether
   query, serialization, morph, and Cytoscape layout are included. It cannot be
   reproduced as written.
4. **Wave 2 candidates.** “Every displayed candidate is real” (`:167`) passes
   vacuously when the current candidate set is empty—the PRD's own risk says
   this will often happen (`:198-199`). Require fixtures that independently
   establish one winner, a deterministic tie/refusal, a floor, a throwing
   render function, and a projection-boundary/external-sink exclusion. Assert the
   expected symbols before asserting their buttons work.
5. **Wave 2 real values.** `my.note` being indexed does not prove a note or
   message value exists in the cluster. Require named note and message
   identities, source transactions, expected schemas, and non-empty render
   results before comparing candidates.
6. **Wave 3 layout.** “Editing the layout function changes order” (`:169`) can
   be a no-op on zero or one entry. Require at least three named entries with a
   real prerequisite edge; before/after must preserve the exact entry identity
   and content-digest multiset while the asserted order changes.
7. **Wave 3 token accounting.** The gate should state the actual equality:
   contribution deltas sum to the calibrated estimate of the fully assembled
   prompt, including separators. That is the existing mechanism
   (`src/seon/cluster/prompt.clj:151-180`), whereas merely naming
   `seon.ai.tokens/estimate` leaves byte boundaries and separator ownership
   ambiguous.
8. **Wave 4 storage choice.** “Changes the graph, candidates and projections as
   predicted” (`design-lab…:171`) can pass if the edited attribute is absent,
   its subject is never selected, or both branch views accidentally read the
   same connection. Require a baseline assertion that the attribute is absent,
   exact subject/attribute/value and commit IDs on both sides, an asserted
   non-empty difference, and proof each render reports the selected branch and
   basis.
9. **Wave 4 persistence.** A decision-log acceptance must include reload and
   reset behavior. If the note is intentionally disposable until copied, the
   UI must visibly report that unsaved state; otherwise the current disposable-
   cluster/reset combination silently loses the result.
10. **Wave 4 timing.** The 15 s gate must identify its start and terminal
    event. A useful end-to-end gate starts before publication/reset and ends
    only when the browser shows the new source commit and database basis—not
    when an operator command exits. At HEAD the measured publication+start
    baseline is 71.90 s, so 15 s is presently a target, not acceptance.

The PRD also needs cross-wave negative criteria: the debug prompt status is
not `unavailable`; every selected entity/branch/candidate is present; every
render names its basis and source commit; a normal database commit refreshes an
already-open debug feed; read-only GETs add no datoms or render-cost facts; and
candidate execution performs no external effect. The fresh page's HTTP 200
with an unavailable prompt demonstrates why transport success cannot stand in
for subject health.

## 5. Risks the PRD misses

- **Program/database split-brain.** A side branch can describe functions and
  schemas that the running cluster's SCI ctx has not acquired, or omit rows for
  Vars it did acquire. Without a commit-compatibility fence, “render this
  branch” combines two worlds and produces plausible false diagnostics
  (`src/seon/render/web.clj:1823-1837`; `AGENTS.md:80-85`).
- **Effectful diagnostic execution.** A public function that contract-fits an
  entity may be an external sink. A “run” button can issue a database write,
  provider request, or other effect unless candidate derivation excludes the
  program graph's boundary facts before invocation (`AGENTS.md:454-456`).
- **Unbounded observation before display bounds.** `generic-entity` realizes
  and groups the entire reverse-ref population before `take`
  (`src/seon/render/web.clj:487-510`). A bounded HTML result therefore does not
  imply bounded query work or memory.
- **Browser-code safety and lifecycle.** Network-loaded Cytoscape adds
  availability and integrity dependencies; embedding names/values into script
  data needs safe serialization for `</script>` and hostile strings; SSE morphs
  need deterministic destroy/recreate behavior. The present shell supplies no
  graph asset or lifecycle owner (`src/seon/render/web.clj:181-228`).
- **Mutation through a read route.** Forking a branch or appending a decision
  note from a GET/render path would make refresh mutate state. The existing
  mutation precedent is an explicit same-origin POST
  (`src/seon/render/route.clj:17-19`); the PRD does not impose that boundary.
- **Connection exhaustion and leaked custody.** Tabs, refreshes, and branch
  switches can repeatedly open the same branch. `open-branch!` refuses an
  already-open connection (`src/seon/cluster/store.clj:401-432`), so the lab
  needs one process owner and release semantics, not per-request opens.
- **Evidence from incomparable bases.** The latest captured prompt can precede
  current facts, while prospective HTML/SCI views use the current database.
  A side-by-side page without visible basis/source identifiers invites causal
  conclusions from different snapshots (`src/seon/context.clj:136-200`;
  `src/seon/render/web.clj:515-600`).
- **Silent staleness on live edits.** A file edit hot-reloads a Var but does not
  update program facts; publication updates `:current-src` but existing
  clusters remain sovereign (`AGENTS.md:80-85`). The layout experiment must
  say whether it tests a hot-reloaded Var or a cluster forked from the new
  commit, or its “immediate” result is not reproducible.
- **Shared-worktree code branches.** The experiment asks for a dedicated code
  branch (`design-lab…:53-56`), but branch switching changes the shared tree.
  The operating contract requires user coordination for history/branch changes
  (`AGENTS.md:718-720`). The lab needs a separate checkout/worktree or an
  explicit coordinated window.
- **Reset destroys both stimulus and conclusions.** Seeded messages, plan
  items, settled agent functions, and an unexported decision log are branch
  facts. `reset-cluster!` replaces their head (`src/seon/cluster/registry.clj:
  242-280`); no replay owner is named.
- **Observation perturbs the experiment.** Normal rendering can transact cost
  facts when a held run and connection are supplied (`src/seon/render.clj:
  700-710`). Candidate comparison and branch inspection need an explicitly
  read-only request shape and before/after datom check.
- **Load is `:all`, not one chosen entity.** A retained debug page registers
  interest in all changes (`src/seon/render/web.clj:817-821`). Large graphs or
  several tabs can recompute on unrelated activity, so the 300 ms single-page
  measure does not cover the delivery load the implementation actually creates.

## 6. Smallest safe start and three options

The smallest PRD change that makes work safe to begin is to split Wave 1 into a
pure observation gate before any graph UI:

1. **Wave 1A — `neighbourhood-page` value.** On the existing fresh root agent,
   derive a total, bounded value from an explicit database value, lookup ref,
   basis, stable order, page size, and continuation. Return subject-present
   evidence; all current datoms with transaction identity; independently
   counted direct, inbound, and cardinality-many refs; one bounded page; and a
   typed stale/missing/ambiguous refusal. Prove it against non-empty synthetic
   facts in the canonical fixture and one isolated live cluster. Do not add
   stewardship, branch switching, or a decision-log schema in this gate.
2. **Wave 1B — visualization.** Only after 1A is green, render that same value
   as the table and Cytoscape graph, select a local-vendored asset strategy,
   and prove ordinary commits refresh an open feed without leaking graph
   instances.

That split turns the missing boundary into a falsifiable value contract and
prevents a polished empty graph from certifying correctness. Before calling
prospective prompt output the baseline, the existing
`prospective-debug-walk-omits-agent-id` issue must also be green with an
explicit non-`unavailable` assertion.

Because the unsplit plan crosses database, render, web, SCI, schema, and
operator ownership, the owner-design gate has three concrete choices:

1. **Recommended — split Wave 1A/1B as above.** Guarantee: the first committed
   seam is total, bounded, independently testable, and reusable by any HTML
   presentation. Cost: one value/schema/API decision and fixtures before visible
   graph work. Give up: no first-day Cytoscape screenshot.
2. **Build the graph directly in the existing debug page.** Guarantee: the
   route and SSE shell can show a prototype quickly. Cost: new querying, asset,
   and lifecycle logic is entangled in `seon.render.web`; empty/stale results can
   still look healthy. Give up: an independently reusable observation contract.
3. **Build all target prerequisites first.** Guarantee: stewardship,
   diagnostic selector APIs, projection evidence, branch custody, and decision-
   log persistence are production-shaped before the lab. Cost: the largest
   cross-owner wave and delayed feedback. Give up: the lab's purpose of cheaply
   falsifying those designs before commitment.

**Verdict:** after option 1 is written into the PRD and the prospective prompt
baseline is proven present, Wave 1A is safe to start. Waves 2--4 still require
their named public data contracts and ownership decisions before implementation;
the current wave acceptances do not authorize them.

## Verification

The requested seams' focused explicit gate passed at source HEAD
`104e0bea251cb4731b58f1d778fd8efd1ed82484`:

```text
bin/test seon.render.web-test seon.render.route-test seon.print-test seon.cluster.agent-test seon.cluster.agent-namespace-test seon.cluster.registry-test seon.cluster.store-test

Ran 111 tests containing 724 assertions.
0 failures, 0 errors.
```

The explicit documentation gate also passed:

```text
bin/test seon.dev.markdown-test

Ran 29 tests containing 363 assertions.
0 failures, 0 errors.
```

Direct `seon.dev.markdown/validate-file` calls returned `valid? true` with zero
violations for this review and the status-output issue note.

This green tally does not overturn the live prospective-prompt finding. The
suite includes tests named `a-fresh-cluster-debug-page-renders-a-prospective-
prompt` and `a-never-run-agents-debug-context-is-labeled-prospective`, but the
real fresh page returned `unavailable`. The existing issue shows that the test
fixture supplies the top-level agent id production omits
(`docs/seon/issues/prospective-debug-walk-omits-agent-id.md:32-46`). That is the
owner-law failure: absence of the production subject is green in the fixture.

The isolated `prd-review` cluster was stopped through the same isolated root
after the measurements; the final root status reported `prd-review` stopped
and `0/0 clusters alive`. The shared operator root and `ctxprobe` were never
mutated.
