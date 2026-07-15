---
type: research
status: active
tags: [research, agent, capability, schema]
---

# Tool namespace colocation audit — 2026-07-15

## Outcome

The agent-facing program graph has 114 positively eligible functions in
eighteen namespaces. The broad namespace ownership is mostly sound. The most
important failures are not a need for another tool catalog:

- root requires `seon.agent`, but that namespace has zero positive
  `:seon.fn/agent-facing?` facts, so its compact card contains schemas and no
  orchestration functions;
- `my.ns` and `my.skills` contain four useful eligible navigation/context
  functions but neither namespace is in the ordinary or root home requires;
- ACME requires `acme.helpers` and `acme.notes`, which contain zero eligible
  functions and render no compact card, while its two actual eligible
  downstream functions in `acme.brand` and `acme.widget` are not required;
- eleven ACME-sample database functions in `my.kb` compete with its two
  general knowledge functions in every ordinary prompt; and
- the filesystem namespace exposes two generations of read/edit operations,
  while the compact-contract renderer invents bogus second call arities for
  four `my.canvas` controls.

The first four findings are eligibility or require-edge work. The canvas
finding is a global callable-contract projection defect. Shared-schema
hoisting is a separate global presentation change already measured in
[[context-schema-closure-measurement-2026-07-15]]; neither should be disguised
as namespace movement.

## Dependency ledger

- ClojureScript `1.12.145` supplies analyzer var metadata. The selected
  reference checkout is `946d75f3483c0c8e784e6668bff2c71a25619a77`;
  `reference-code/clojurescript/src/main/clojure/cljs/analyzer.cljc` shows
  docstrings, arglists, and user metadata retained on analyzer vars.
- Malli `0.20.0` supplies persisted function schemas and schema walking.
  `reference-code/malli/src/malli/core.cljc` owns `walk` and ref-schema
  traversal. `seon.agent.ctx` uses an isolated registry so closure derivation
  is a pure function of persisted schema forms, not the mutable Malli registry.
- The maintained Datahike dependency is pinned at
  `417649383c65e13f15ea41d394fb1ed742477965` in `deps.edn`. Its `q`, `pull`,
  and entity implementations are in `reference-code/datahike/src/datahike/`.
  The local reference submodule was advanced and dirty under another owner, so
  it was read but not modified or treated as the selected runtime revision.
- `src/seon/analyzer_info.cljs`, `src/seon/indexing.clj`, and
  `src/seon/eval.cljs` project analyzer facts into `:seon.fn`, `:seon.ns`, and
  `:seon.schema` entities. Absence of `:seon.fn/agent-facing?` is intentional
  negative data, not false or an inferred default.
- `src/seon/agent/ctx/namespaces.cljs` owns compact selection and callable
  records. `src/seon/agent/ctx.cljs` owns transitive referenced-schema closure.
  `src/seon/agent/home.cljs` and the database-applied manifest own home require
  edges. `src/my/AGENTS.md` owns editable toolkit constraints.
- Relevant target authorities are [[../../../seon/architecture/toolkit]],
  [[../../../seon/architecture/context]],
  [[namespace-surface-audit-2026-07-15]], and
  [[context-schema-closure-measurement-2026-07-15]].

## Read-only method and immutable evidence

The live ACME REPL query selected every entity carrying positive
`:seon.fn/agent-facing?`, joined it to its owning `:seon.ns/name`, and read its
persisted symbol, docstring, arglists, and spec. Each row was then passed to the
same `seon.agent.ctx.namespaces/compact-fn-head` used by namespace cards,
menus, `my.ns/functions`, and autocomplete. This enumerated all 114 functions;
there was no filesystem-derived substitute inventory.

The immutable coordinate was database
`6813d1c2-4feb-3272-9b74-4c6769142514`, branch `:db`, commit
`6a5794c1-de19-5e23-aeed-83a5851c9ff1`, transaction `536872580`.
The ordinary and root require vectors were read from the same database value.
ACME restarted later under the parent lane and its watcher became degraded, so
no lifecycle operation or second coordinate was introduced for this audit.

Schema counts below use the byte-stable live closure measurement at commit
`6a570014-112f-515b-8005-e70d750ad69f`. The callable-contract refactor between
the two coordinates changed argument projection, not namespace selection or
the own/closure schema-key sets. Nonresident namespaces contribute zero schemas
to standing compact context regardless of how many definitions they own.

## Complete inventory and reachability

“Ordinary” and “root” mean resident through the configured home require vector.
ACME's ordinary vector adds `acme.helpers` and `acme.notes`, but both cards are
empty after positive eligibility filtering and are dropped. Counts in the
schema columns are own definitions plus additional cross-namespace closure
records in the current compact projection.

| Namespace | Eligible functions | Ordinary | Root | Own + closure schemas |
|---|---:|---|---|---:|
| `seon.agent` | 0 | no | yes, but no callable rows | 26 + 0 |
| `seon.agent.message` | 2 | yes | yes | 13 + 3 |
| `seon.agent.lifecycle` | 5 | yes | yes | 2 + 15 |
| `seon.agent.search` | 2 | yes | yes | 38 + 0 |
| `seon.agent.fs` | 12 | yes | yes | 60 + 10 |
| `seon.agent.shell` | 8 | yes | no | 39 + 9 |
| `seon.agent.web` | 3 | yes | no | 43 + 0 |
| `seon.db` | 15 | yes | yes | 79 + 5 |
| `seon.schema` | 7 | yes | yes | 11 + 0 |
| `my.blob` | 5 | yes | no | 26 + 0 |
| `my.canvas` | 11 | yes | yes | 28 + 15 |
| `my.data` | 4 | yes | yes | 9 + 4 |
| `my.kb` | 13 | yes | yes | 21 + 11 |
| `my.plan` | 14 | yes | yes | 50 + 7 |
| `my.ui` | 7 | yes | yes | 21 + 7 |
| `my.ns` | 1 | no | no | nonresident |
| `my.skills` | 3 | no | no | nonresident |
| `acme.brand` | 1 | no | no | nonresident |
| `acme.widget` | 1 | no | no | nonresident |
| **Total** | **114** | **108 resident** | **92 resident** | — |

The exact eligible symbols, grouped without omission, are:

- `seon.agent.message`: `agent`, `user`.
- `seon.agent.lifecycle`: `complete`, `pause`, `resume`, `terminate`, `wait`.
- `seon.agent.search`: `grep`, `grep-graph`.
- `seon.agent.fs`: `edit-file`, `file-exists?`, `grants`, `home-dir`, `insert!`,
  `list-dir`, `read-file`, `replace!`, `stat`, `view`, `walk-dir`, `write-file`.
- `seon.agent.shell`: `grants`, `job-output`, `job-status`, `job-stop!`,
  `list-jobs`, `py-run`, `run`, `run-bg!`.
- `seon.agent.web`: `fetch`, `grants`, `search`.
- `seon.db`: `as-of`, `at-coordinate`, `basis-t`, `cas-assert`,
  `current-agent-id`, `entity`, `head-coordinate`, `history`, `index-datoms`,
  `installed-schema`, `pull`, `query`, `rseek-datoms`, `since`, `transact!`.
- `seon.schema`: `enum-members`, `identity-attr?`, `register!`,
  `registered-schemas`, `registered?`, `schema-definition`,
  `schemas-in-namespace`.
- `my.blob`: `concat!`, `get`, `put!`, `stat`, `text`.
- `my.canvas`: `button`, `clear!`, `form`, `input`, `pinned`, `save!`,
  `select`, `show!`, `state`, `toggle`, `view`.
- `my.data`: `group-sum`, `max-by`, `rows`, `sum-by`.
- `my.kb`: `clear-rating!`, `forget-source!`, `recall`, `remember`,
  `remember-sources!`, `replace-topics!`, `retitle-source!`, `source-detail`,
  `source-entity`, `source-stats`, `title+rating`, `titles`,
  `titles-by-author`.
- `my.ns`: `functions`.
- `my.plan`: `active!`, `document`, `done!`, `drop!`, `list-open`, `move!`,
  `needs!`, `next`, `plan!`, `reconcile!`, `reopen!`, `status`, `step!`, `tree`.
- `my.skills`: `list`, `load`, `unload`.
- `my.ui`: `badge`, `bullets`, `kv-table`, `progress`, `section`,
  `status-line`, `table`.
- `acme.brand`: `tagline`.
- `acme.widget`: `set-location!`.

Every row had a persisted spec and line-one docstring. “Complete” does not mean
“correct”: the common renderer exposed opaque or prohibited schema shapes such
as `:any` and `[:maybe ...]`, and four canvas rows gained phantom arities. Those
are source-schema or global renderer defects, not missing inventory rows.

## Exact overlap and placement clusters

### P0: root orchestration is configured but absent

`config/system.edn` says root's `seon.agent` require is its orchestration
surface. The live compact projection instead has 26 schema rows and zero
function rows because no public function in `src/seon/agent.cljs` carries the
positive eligibility declaration. Public implementation functions include
boot-only and process-only operations, so marking the whole namespace by
public visibility would recreate the defect closed by
[[namespace-surface-audit-2026-07-15]].

The namespace owner is correct. Add positive metadata only to the true root
capabilities, with an explicit decision over `start!`, `delegate!`, `resume!`,
`set-purpose!`, `armable-agent-ids`, and whether chosen-id `create!` is intended
for agents. Keep `mint!`, `ensure-initial-agent!`, `spawn-depth`,
`resumable-agent-ids`, and `unhost!` as indexed implementation data.

### P0: navigation and skills have no incoming default edge

`my.ns/functions` is the one database-derived answer to “what can I call in
this namespace?” and consumes the same compact renderer. `my.skills/list`,
`load`, and `unload` are the explicit skill-context operations. All four are
eligible but absent from both curated home vectors, so a model must already
guess their fully qualified names to discover them.

Add real home require edges, not prose. The exact aliases need one collision
check (`ns` is also Clojure's namespace declaration form); the capability must
remain fully qualified as the always-correct floor. Root may deliberately omit
skill loading if root's role contract excludes it, but ordinary agents need a
database-visible discovery path.

### P0: ACME requires the reproductions, not its tools

`config/acme.edn` claims `acme.helpers` and `acme.notes` are the overlay
toolkit. They intentionally contain unspecced reproduction helpers and zero
positive eligible functions, so both compact cards are dropped. The actual
eligible downstream functions, `acme.brand/tagline` and
`acme.widget/set-location!`, have no incoming home edge and never become
resident. This is a fresh-third-party-integration failure, not an argument for
showing every downstream public function.

Require the namespaces that own real downstream capabilities. Keep unspecced
SCI/indexing fixtures indexed and reachable through source inspection, but do
not label them as a toolkit or force them into every prompt.

### P1: `my.kb` mixes the general API with one sample domain

`remember` and `recall` are general knowledge operations. The other eleven
eligible functions manipulate or report a hard-coded sample-source model
(`rating`, `topics`, author, titles, and sample findings). They are useful full
source teaching examples when an agent moves into `my.kb`; they are not eleven
general standing tools. Positive eligibility should be removed from the sample
functions while their source and schemas remain colocated and inspectable.

This reduces choice noise and schema closure globally without a benchmark
blocklist. It also preserves the architectural distinction between a reusable
knowledge surface and a worked domain model.

### P1: filesystem exposes two generations

`read-file` and `view` both page file content. `view` adds line numbering and a
SHA fence. `edit-file` overlaps both the newer exact anchored `replace!` and
line-anchored `insert!`, but lacks their common stale-SHA and candidate
diagnostics. The newer operations have stronger model-facing contracts, while
the older functions remain attractive by generic name.

Do not move these functions to another namespace. Run the small-model
selection falsifier, then retain one coherent read/edit family in
`seon.agent.fs`. A likely boundary keeps `view`, `replace!`, `insert!`,
`write-file`, and directory/stat operations; an unnumbered text-read operation
is still useful for programmatic processing but needs a name/contract that
cannot be mistaken for the editing view. Delete or unmark the superseded path
in the same refactor; do not add another wrapper.

### P1: callable projection invents canvas arities

The live compact rows for `my.canvas/button`, `input`, `select`, and `toggle`
each show the correct map-in request followed by `OR positional [...] ->
<return unspecified>`, where the bracketed value is the function's returned
Hiccup body. The functions have one physical destructuring arglist and one
Malli callable schema. This is not a source arity and must never be presented
as callable input.

Fix `callable-contract` globally from persisted callable grammar; do not patch
the four symbols. Add a table-driven case for a function whose output schema
resolves through recursive/vector Hiccup data so output data can never be
reinterpreted as a second `:=>` arity.

### P2: schema opacity is source-owned, not colocation

Representative live contracts include `my.data/max-by -> [:maybe :map]`,
`seon.db/current-agent-id -> [:maybe :string]`, and multiple `seon.db` or
`my.kb` inputs/outputs as `:any`. These conflict with current schema laws and
leave small models unable to predict result shape. Tighten schemas in their
own code namespaces and shared registered shapes. Moving them or explaining
them in context prose would preserve the defect.

## No-change findings

- `seon.db` correctly owns query, pull, entity, temporal values, coordinates,
  indexes, and installed database schema. `seon.schema` correctly owns
  declaration/registry semantics. `installed-schema` is a physical database
  read and should not move merely because its result contains schemas.
- `seon.agent.search/grep` searches granted filesystem content;
  `grep-graph` searches persisted program facts. Their shared search owner and
  distinct line-one summaries are coherent.
- `seon.agent.shell` groups foreground commands, Python specialization, and
  addressable background-job lifecycle. `seon.agent.web` groups grants,
  fetch, and search. No cross-namespace move is warranted.
- `my.ui` owns static visual composition. `my.canvas` owns focal canvas pin,
  state, and interactive controls. Their overlap is composition, not duplicate
  authority.
- `my.blob/get` provides a full value for code while `text` provides bounded
  line windows for model display. Their distinct response contracts justify
  both names.
- `seon.agent.lifecycle` correctly owns self/run lifecycle while `seon.agent`
  owns root orchestration. The fix is positive root eligibility, not merging
  the namespaces. Line-one summaries must continue to distinguish self
  `resume` from targeted orchestration `resume!`.
- Current-full plus required-compact selection is the right global context
  rule. No benchmark-specific bundle, hardcoded function list, or instructional
  prose block is needed.

## Shortest model-facing falsifiers

Each falsifier uses ordinary Inspect execution and frozen task text. Score the
selected function and observed database/effect result, never exact prose.

1. **Root reachability:** “Start one child with purpose `audit invoices` and
   report its real id.” Current expected failure: no `seon.agent` function row.
   Acceptance: selects the eligible root spawn function and the child/purpose
   facts exist.
2. **Namespace navigation:** “Without reading files, list callable functions in
   `seon.agent.web`, then move there.” Current expected failure: `my.ns` is not
   resident. Acceptance: uses the database-derived discovery function and
   records the namespace movement.
3. **ACME tool:** “Set the ACME location to Boston.” Current expected failure:
   only empty reproduction namespaces are required. Acceptance: calls
   `acme.widget/set-location!` from its ordinary compact card.
4. **Knowledge choice:** “Remember one verified fact, then recall it.” Compare
   the current thirteen-function `my.kb` card with the two-general-function
   card. Acceptance requires durable read-back, not mere source selection.
5. **Filesystem choice:** “Change one unique old string in a file that mutates
   between read and write.” Acceptance requires a stale-SHA refusal followed by
   a re-viewed deterministic edit; choosing an unfenced legacy path fails.
6. **Canvas contract:** “Build a button calling a map-in handler.” Before any
   model run, assert the compact card has exactly one logical arity and contains
   none of the button's returned Hiccup body. Then measure correct construction.

## Ordered globally beneficial changes

1. Repair the one callable-contract projection so every later model experiment
   starts from truthful input/output contracts. Prove exact card arity and
   unchanged function identity across cards, menus, `my.ns`, and autocomplete.
2. Restore real capability reachability: positively mark only the intended
   root operations; add the missing `my.ns`/`my.skills` home edges as allowed by
   role; replace ACME's empty fixture edges with its real capability owners.
3. Remove positive eligibility from `my.kb`'s sample-domain functions while
   keeping their full source and colocated schemas as teaching material.
4. Run the filesystem falsifier, then consolidate one read/edit family in
   place and delete or unmark the superseded functions.
5. Tighten opaque source schemas using shared registered shapes. Treat every
   remaining `:any` or `[:maybe ...]` as a named exception to prove, not a
   renderer concern.
6. Only after the semantic surface is stable, implement the previously
   measured shared-schema projection inside the existing namespace block. It
   changes cache topology and repetition, not ownership or eligibility.

The graduation gate remains the frozen representative Inspect suite with at
least 90% deterministic success and category floors. A smaller card is not a
success unless the agent selects, composes, executes, and verifies the right
ordinary functions.
