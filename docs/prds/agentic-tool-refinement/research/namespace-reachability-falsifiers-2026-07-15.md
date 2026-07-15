---
type: research
status: complete
tags: [research, agent, capability, schema]
---

# Namespace reachability falsifiers — 2026-07-15

## Outcome

Four current namespace failures reduce to two database facts and one pure
render rule. A namespace reaches a fresh agent's compact surface only when the
agent's current namespace has a persisted `:seon.ns/require-edges` component
pointing to it. A function appears in that card only when its indexed entity
carries the positive `:seon.fn/agent-facing? true` fact. The absence of either
fact is the behavior; there is no false-valued eligibility default and no
renderer fallback.

The shortest current contradictions are:

| Finding | Required edge | Positive callable rows | Current result |
|---|---|---:|---|
| root orchestration | `my.agent.root` → `seon.agent` | 0 | schema-only `seon.agent` card |
| ordinary namespace discovery | no edge to `my.ns` | 1 | no card |
| ordinary skill lifecycle | no edge to `my.skills` | 3 | no card |
| ACME product tools | edges to `acme.helpers` and `acme.notes`, not the product owners | 1 in `acme.brand`, 1 in `acme.widget` | fixture cards are empty and dropped; product cards are nonresident |

No namespace relocation, standing prompt prose, second function catalog, or
task-specific projection is needed. The likely fixes are, in dependency order:
positive eligibility on the selected root operations, ordinary home edges in
`config/system.edn`, and replacement ACME home edges in `config/acme.edn`.
Each manifest change must be proved with a newly minted agent because agent
context and the structural home declaration are copied into database facts at
birth; applying a manifest does not rewrite an existing agent's copied facts.

This report sharpens the source findings in
[[tool-namespace-colocation-audit-2026-07-15]] and the proposed Inspect rows in
[[tool-reachability-falsifiers-2026-07-15]]. It does not alter the frozen
ten-member P0 suite.

## Dependency ledger

- The selected ClojureScript source is recorded at
  `946d75f3483c0c8e784e6668bff2c71a25619a77` under
  `reference-code/clojurescript`. Its analyzer persists user metadata on var
  rows in `src/main/clojure/cljs/analyzer.cljc`; Seon's
  `seon.analyzer-info/var-projection` retains a true
  `:seon.fn/agent-facing?` value and omits the attribute when metadata is
  absent.
- The selected Malli source is recorded at
  `80138076960e7820523b4cb932c5b5d1936d4e7f` under `reference-code/malli`.
  `src/malli/core.cljc` owns function-schema arities and reference walking;
  Seon's compact renderer consumes the persisted schema form and transitive
  referenced closure. Malli does not decide namespace residency.
- The repository records Datahike
  `417649383c65e13f15ea41d394fb1ed742477965`. The local reference checkout is
  intentionally advanced to `eb3e2239b650635977fdc8e73e7c657b23bf3383`
  under another owner and is not the selected runtime coordinate. Datahike's
  pure `q`, `pull`, and `entity` operations make attribute presence and
  component refs queryable from one immutable database value.
- Inspect AI is selected at
  `05322696a0f784ec399ef6abbafd3d2a250ea9cc`. Its existing `Task`, `Sample`,
  and `Scorer` contracts in `reference-code/inspect-ai/src/inspect_ai/` are
  sufficient for the four fixed development rows. Seon's existing native
  solver, source admission, final-snapshot evidence, and `.eval` read-back
  remain the only evaluation path.
- `src/seon/config.cljs` resolves the database-backed manifest view;
  `src/seon/agent.cljs` copies the resolved context and structural home
  namespace into one birth transaction; `src/seon/agent/home.cljs` reads the
  persisted home requirements; and `src/seon/agent/ctx/namespaces.cljs`
  derives the namespace block from the frozen database.
- `src/my/ns.cljs`, `src/my/skills.cljs`, `src/seon/agent.cljs`,
  `acme/src/acme/brand.cljs`, and `acme/src/acme/widget.cljs` are the function
  owners. `config/system.edn` and `config/acme.edn` are the require-edge policy
  owners.

## Exact database-to-context derivation

The current path is one composition, not a manifest-time renderer:

1. `seon.config/resolve-agent-context` reads `config-view`, resolves the base
   `:seon.config/agent-context`, and, only for id `"root"`, merges the sparse
   `:seon.config/root-context` by scalar key and block name.
2. `seon.agent/initial-agent-tx` selects
   `:seon.eval/home-requires` from that resolved context, falling back to
   `seon.agent.home/home-ns-require-specs` only when the resolved value is
   absent. The same transaction writes the agent entity and the
   `:seon.ns/name :my.agent.<id>` entity with its exact source and component
   `:seon.ns/require-edges`.
3. `seon.agent.home/home-requires-for` later prefers the agent's persisted
   `:seon.eval/home-requires` datom. Runtime namespace reconstruction therefore
   follows the born agent's database facts, not a newly changed manifest.
4. On a turn's frozen database value,
   `seon.agent.ctx.namespaces/namespaces-block` derives current namespace from
   the latest successful eval, falling back to `my.agent.<id>`. It calls
   `seon.eval/persisted-require-targets`, which pulls the current namespace's
   component edges and returns their `:seon.ns.require/target` values.
5. The current namespace renders in full. Each required target renders through
   `render-one-ns-compact`. That pull keeps only function entities with
   positive `:seon.fn/agent-facing?` and without private presence, while
   retaining the namespace's own schemas and the referenced schema closure of
   the eligible functions.
6. `compact-block` omits a card whose complete body is `(nothing indexed)`.
   Thus a persisted edge alone can produce no prompt bytes, and positive
   callable rows alone remain nonresident without an incoming edge.

The two shortest database queries are therefore enough to localize every
failure. Run both against the same immutable database value:

```clojure
(seon.db/query
  {:seon.db/db db
   :seon.db/query
   '[:find [?target ...]
     :in $ ?current-ns
     :where
     [?ns :seon.ns/name ?current-ns]
     [?ns :seon.ns/require-edges ?edge]
     [?edge :seon.ns.require/target ?target]]
   :seon.db/args [:my.agent.root]})

```

```clojure
(seon.db/query
  {:seon.db/db db
   :seon.db/query
   '[:find [?sym ...]
     :in $ ?namespace
     :where
     [?ns :seon.ns/name ?namespace]
     [?fn :seon.fn/ns ?ns]
     [?fn :seon.fn/agent-facing? true]
     [?fn :seon.fn/sym ?sym]]
   :seon.db/args [:seon.agent]})

```

Substitute the newly minted ordinary home keyword in the first query and any
of `:my.ns`, `:my.skills`, `:acme.helpers`, `:acme.notes`, `:acme.brand`, or
`:acme.widget` in the second. The rendered falsifier is the same database
value passed to `namespaces-block`; no text fixture or filesystem inventory is
an equivalent proof.

The earlier immutable ACME inventory used database id
`6813d1c2-4feb-3272-9b74-4c6769142514`, branch `:db`, commit
`6a5794c1-de19-5e23-aeed-83a5851c9ff1`, and transaction `536872580`. Current
source inspection at Seon revision
`08a9ef33917e7b2df66162d61db09078c4da6021` preserves the four contradictions.
The current ACME target is degraded: watcher PID 40101 is alive but not ready,
while writer and pod are ready. This audit performed only `bin/acme status
--edn`; it did not wake an agent, mutate the database, or treat the stale pod
as new live evidence.

## Falsifier 1 — root orchestration

### Current contradiction

`config/system.edn` gives root the structural home require
`[seon.agent :as agent]`, and its comment names the namespace as root's
orchestration surface. Current `src/seon/agent.cljs` contains no
`:seon.fn/agent-facing?` metadata on any function. The indexed namespace still
owns schemas, so its compact card is not wholly empty, but it has no `fn
seon.agent/...` callable row.

The shortest falsifier is the pair:

- require-target query for `:my.agent.root` contains `:seon.agent`; and
- positive-function query for `:seon.agent` returns `[]`.

The prompt-level assertion is that root turn one's `:namespaces` render lacks
`fn seon.agent/start!` even though the card for `seon.agent` is resident.

### Expected agent-visible delta

The first controlled change adds exactly the selected root operation's compact
callable row, beginning with `seon.agent/start!` and its complete map-in/result
contract. It must not add `mint!`, `ensure-initial-agent!`, `spawn-depth`,
`unhost!`, boot helpers, or every public function by visibility. Current
namespace source and the other required cards remain byte-identical for the
same frozen post-reindex database value.

The fixed Inspect row then asks root to spawn one idle child with a fixed
purpose, read the real returned id on the next turn, query the parent/purpose
facts, and report the observed id. The pre-change failure is `tool absent`;
after a truthful row exists, wrong selection or fabricated verification is a
model failure rather than a surface failure.

### Likely owner

Rank 1: `src/seon/agent.cljs` owns positive metadata on the true root
operations. `src/seon/indexing.clj` and
`src/seon/agent/ctx/namespaces.cljs` already preserve and consume the fact and
should not receive a special root rule. A clean rebuild/reindex is the proof
boundary.

## Falsifier 2 — ordinary namespace discovery

### Current contradiction

`my.ns/functions` is positively eligible, indexed, and already consumes the
same compact function projection as namespace cards. The ordinary
`:seon.eval/home-requires` vector in `config/system.edn` has no `my.ns` entry,
so a newly minted ordinary home namespace receives no incoming edge and never
renders the one discovery row.

The shortest falsifier is:

- positive-function query for `:my.ns` returns exactly
  `my.ns/functions`; and
- require-target query for a newly minted ordinary home namespace omits
  `:my.ns`.

The prompt assertion is absence of `fn my.ns/functions` on turn one. Searching
files or hand-writing the equivalent Datalog query does not disprove the
missing edge; those are alternate capabilities selected after the intended
function was already absent.

### Expected agent-visible delta

A newly minted ordinary agent gains one compact `my.ns/functions` row with its
map-in/map-out contract. A call for `seon.agent.web`, followed by
`(in-ns 'seon.agent.web)`, makes the next frozen prompt render that namespace
in full; `fetch`, `grants`, and `search` remain the positive function set. No
standing namespace list or discovery prose is added.

### Likely owner

Rank 2: `config/system.edn` owns the ordinary home edge. Use one non-colliding
alias in the structural require spec; fully qualified invocation remains the
universal floor. `src/my/ns.cljs` and the renderer already implement the
desired behavior.

## Falsifier 3 — ordinary skill lifecycle

### Current contradiction

`my.skills/list`, `my.skills/load`, and `my.skills/unload` are positively
eligible and indexed. The ordinary home vector omits `my.skills`, so all three
are unreachable in the turn-one compact surface. The absence is independent
of whether skill corpus entities exist: catalog data affects the return of
`list`, while the namespace card depends on the structural require edge and
function facts.

The shortest falsifier is:

- positive-function query for `:my.skills` returns exactly `list`, `load`, and
  `unload`; and
- require-target query for a newly minted ordinary home namespace omits
  `:my.skills`.

The prompt assertion is absence of all three `fn my.skills/...` rows on turn
one.

### Expected agent-visible delta

A new ordinary agent gains three complete compact rows. Loading `:repl`
transacts the one `:skill/repl` context block; the next prompt contains its
body. Unloading retracts that block; the following prompt omits the body. The
function-result narration cannot substitute for those two later prompt facts.
No always-on catalog block or skill prose is introduced.

### Likely owner

Rank 3: the same `config/system.edn` home-require vector owns this independent
edge. Land and prove it separately from `my.ns`, so a combined failure cannot
mask which edge is absent. Root access remains a separate role decision:
`root-context` replaces the ordinary scalar vector, so adding an ordinary edge
does not silently grant root the same functions.

## Falsifier 4 — ACME product tools

### Current contradiction

`config/acme.edn` replaces the ordinary home-require vector with the system
vector plus `acme.helpers` and `acme.notes`. Those namespaces are deliberate
indexing and SCI fixtures. Their public functions have no positive eligibility
facts, so the compact cards have no callable rows and are dropped as empty.

The actual downstream product functions are already positive:

- `acme.brand/tagline`; and
- `acme.widget/set-location!`.

Neither owner has an incoming ACME home edge. The shortest falsifier is:

- a new ACME ordinary home requires `:acme.helpers` and `:acme.notes` and
  omits `:acme.brand` and `:acme.widget`; and
- positive-function queries return no rows for the two fixture namespaces and
  one row for each product namespace.

The prompt assertion is stronger than function absence: turn one has no
fixture home requirements and does have compact product rows. Adding product
edges without removing fixture edges fails the replacement contract even if
empty-card suppression happens to hide their rendered bytes.

### Expected agent-visible delta

A newly minted ACME agent's structural home declaration drops the two fixture
edges and adds the two product edges. Its first prompt gains exactly
`acme.brand/tagline` and `acme.widget/set-location!` with complete schemas.
The fixture namespaces remain indexed and full-source inspectable through the
existing downstream source path; they simply stop pretending to be the
standing product toolbelt.

### Likely owner

Rank 4: `config/acme.edn` owns the downstream replacement. Do not change
`SEON_EXTRA_SRC`, the source indexer, the two product namespaces, or compact
empty-card behavior.

## Related required-namespace boundary

Require policy is copied data, not a live manifest pointer. Existing agents
keep their persisted `:seon.eval/home-requires`, home `:seon.ns/source`, and
component require edges. `seon.agent/create!` is idempotent and preserves a
complete born entity; `seon.agent.home/home-requires-for` prefers that datom
over current config. Therefore a source-level manifest fix can coexist with an
old agent whose prompt remains unchanged.

The falsifier for this boundary is intentionally simple:

1. read an existing agent's home targets from immutable database value A;
2. apply a manifest with a changed home vector;
3. read the same existing home from value B and observe unchanged copied
   targets; and
4. mint a new agent from B and observe the new targets.

That is expected derive/store behavior, not a reason to add a render-time
manifest read. Every before/after reachability row must use a newly minted
agent, except root orchestration where the structural edge already exists and
only reindexed positive function facts change.

## Dependency order and proof gates

1. Land the four pure Inspect scorer discrimination fixtures through the
   existing native task path. Golden evidence passes; removing or delaying the
   first prompt row, replacing the intended call, removing a later dynamic
   prompt, or preserving only the final prose fails the named check.
2. After the runtime lane hands off one coordinated dependency coordinate,
   rebuild only ACME and require a ready admitted artifact. The current
   degraded target is not a valid before-run surface.
3. Mint fresh agents and retain one expected-red native `.eval` per falsifier.
   Query the same final immutable database value for edges and positive
   function rows before interpreting model behavior.
4. Apply one owner at a time: root eligibility, `my.ns` edge, `my.skills`
   edge, then ACME fixture-to-product replacement. Rebuild/reindex or mint as
   required by the owning data boundary.
5. Rerun only the affected fixed row, reopen its finalized native log, and
   require the expected first-prompt delta plus successful selection,
   execution, dynamic-context, verification, and report evidence.
6. After all four focused rows pass, replay the frozen namespace workflow.
   Do not run the full benchmark suite to diagnose one reachability failure.

## Existing coverage and missing regression

Current focused tests already prove the mechanisms:

- `test/seon/analyzer_info_test.cljs` proves true eligibility metadata is
  persisted and absent metadata stays absent;
- `test/seon/index_core_test.cljs` proves deliberate positive inventories and
  that unmarked public functions remain program data;
- `test/seon/agent/home_test.cljs` proves structural require contracts and
  config/persisted precedence;
- `test/seon/agent/ctx/namespaces_test.cljs` proves current-full,
  required-compact, nonrequired-dropped selection and positive-only compact
  function filtering; and
- `test/seon/config_test.cljs` proves root/base merge and sparse-versus-explicit
  agent-context behavior.

The missing regression is policy-specific: no focused test currently pins the
intended ordinary, root, and ACME home targets together with the exact positive
callable rows they expose. The later implementation should add narrow
manifest/home/index/card assertions plus the four native Inspect trajectories;
it should not snapshot the entire prompt or create a second namespace export.

## Mechanical validation

- This audit edited only this report.
- It did not restart ACME, invoke a model, wake an agent, mutate a database, or
  touch dependency and restore paths.
- Acceptance is `seon.dev.markdown/validate-file`, `git diff --check`, and a
  path-limited commit of this file.
