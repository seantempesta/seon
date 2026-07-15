---
type: research
status: complete
tags: [research, agent, capability, orchestrator]
---

# Root orchestration surface audit — 2026-07-15

## Outcome

`seon.agent` needs exactly two positive agent-facing functions:

1. `start!` creates and hosts a new idle child without assigning work.
2. `delegate!` atomically creates, hosts, and messages a new child.

Together they are the smallest complete birth/delegation surface. The rest of
a realistic root control loop is already owned by other required namespaces:

- inspect agents and their durable results with `seon.db/query`, `pull`, and
  `entity`, plus the root's derived system and child context;
- message a known child with `seon.agent.message/agent`; and
- permanently stop a known child with the explicit-id
  `seon.agent.lifecycle/terminate`.

Adding another `seon.agent` inspection, messaging, status, or stop wrapper
would duplicate those authorities. Marking every public function would expose
boot, process-hosting, and depth-fence implementation as tools. Absence of the
positive fact remains the correct signal for those functions.

Neither proposed function is ready to mark positive without one contract fix.
Their request schemas currently use `:any` for purpose and turn limit even
though the owned concrete schemas already exist. Before admission,
`::start-request` and `::delegate-request` must use
`:seon.agent/purpose` and `:seon.agent/default-turn-limit`. This is contract
repair in the existing owner, not context prose.

## Dependency ledger

- ClojureScript `1.12.145`, reference revision
  `946d75f3483c0c8e784e6668bff2c71a25619a77`, owns analyzer var metadata.
  `reference-code/clojurescript/src/main/clojure/cljs/analyzer.cljc` retains
  doc, arglists, and user metadata; `src/seon/analyzer_info.cljs` projects the
  literal positive `:seon.fn/agent-facing? true` value into database facts.
- Malli `0.20.0`, reference revision
  `80138076960e7820523b4cb932c5b5d1936d4e7f`, owns function schemas and
  reference walking. `reference-code/malli/src/malli/core.cljc` defines
  `FunctionSchema`, `walk`, and `-ref-schema?`; the existing namespace-card
  renderer derives complete transitive schema closure from persisted forms.
- The selected application Datahike coordinate in `deps.edn` is
  `417649383c65e13f15ea41d394fb1ed742477965`. The local reference checkout was
  observed at `eb3e2239b650635977fdc8e73e7c657b23bf3383` and is owned and dirty
  under the runtime lane, so this audit neither modifies it nor treats it as a
  replacement pin. Seon's immutable database value, query, pull, entity, and
  operation-evidence paths remain the only proof substrate.
- Inspect AI is sourced from `reference-code/inspect-ai` revision
  `05322696a0f784ec399ef6abbafd3d2a250ea9cc` through
  `src-inspect-ai/pyproject.toml`. Its `Task`, `Sample`, scorer, and public
  native-log read/write contracts own all proposed trajectories and retained
  evidence; no drive script or second harness is proposed.
- `src/seon/agent.cljs` owns durable agent birth and targeted process hosting.
  `src/seon/agent/message.cljs` owns cross-agent messages.
  `src/seon/agent/lifecycle.cljs` owns explicit-id termination.
  `src/seon/agent/ctx/subagents.cljs` and `seon.render.system/system-view` own
  derived root observation. `src/seon/agent/ctx/namespaces.cljs` owns the one
  compact callable projection.
- Current measurements and reachability contracts come from
  [[context-schema-closure-measurement-2026-07-15]],
  [[tool-namespace-colocation-audit-2026-07-15]],
  [[namespace-reachability-falsifiers-2026-07-15]], and
  [[tool-reachability-falsifiers-2026-07-15]].

## Current structural reachability

Root's persisted home declaration requires `[seon.agent :as agent]`.
`seon.agent` therefore already receives a standing compact card, but none of
its public functions carries positive eligibility. The measured root card has
twenty-six owned schema definitions, zero function rows, and costs 642
estimated tokens. This is a missing positive inventory, not a missing require
edge or renderer defect.

The program graph indexes public functions whether or not they are eligible.
Compact cards then select only non-private functions whose persisted
`:seon.fn/agent-facing?` fact is present and true. Marking two functions adds
two rows through that global mechanism; every unmarked public function remains
queryable program data and stays out of the card.

## Complete current public function inventory

The eleven literal public `defn` forms below are the complete current
`seon.agent` function inventory. Every function has a Malli contract; none has
positive eligibility today.

| Function | Persisted callable contract | Actual owner role | Decision |
|---|---|---|---|
| `armable-agent-ids` | map-in `::armable-agent-ids-request` → `::armable-agent-ids-response` | Boot/wake census of derived-idle agents | Keep noneligible |
| `resumable-agent-ids` | map-in `::resumable-agent-ids-request` → `::resumable-agent-ids-response` | Cold-start process-host census | Keep noneligible |
| `create!` | map-in `::create-request` → `::create-response` | Reconcile a caller-chosen durable id; does not host it | Keep noneligible |
| `mint!` | map-in `::mint-request` → `::create-response` | Allocate durable identity and facts; does not host it | Keep noneligible |
| `ensure-initial-agent!` | positional `::ensure-initial-agent-request` → `::ensure-initial-agent-response` | First-boot transition | Keep noneligible |
| `spawn-depth` | positional database value + agent id → integer | Hard depth-fence computation | Keep noneligible |
| `start!` | map-in `::start-request` → `::start-response` | Mint and host one idle direct child | **Positive, rank 1 dependency** |
| `delegate!` | map-in `::delegate-request` → `::delegate-response` | Mint, host, then send one task atomically | **Positive, rank 2 dependency / rank 1 frequency** |
| `resume!` | positional `:seon.agent.runtime/resume-request` → `:seon.agent.runtime/resume-response` | Reconstruct process-local hosting | Keep noneligible |
| `unhost!` | positional `:seon.agent.runtime/unhost-request` → `:seon.agent.runtime/unhost-response` | Remove process-local hosting without durable termination | Keep noneligible |
| `set-purpose!` | map-in text plus optional agent id → `::section-response` | Mutate one purpose attr | Keep noneligible |

`start!` ranks first in dependency order because `delegate!` calls it.
`delegate!` ranks first for ordinary delegated work because it is the safe
one-form composition: it awaits the asynchronous child id before messaging.
Both remain necessary. `delegate!` requires task content and therefore cannot
express “create an idle child with no task”; `start!` deliberately can.

The namespace also has public `def` aliases for context/render functions:
`messages`, `current-turn`, `evals`, `current-ns`, `ctx-entities`,
`host-timezone`, `truncate-edn`, `message-label`, `cap-result`,
`cap-result-body`, `namespaces-block`, `render-namespace`, `warnings-block`,
`transcript-block`, `context-root`, `derive-status`, and `message!`.
`eval-render-cap` and `user-ref` are public non-function values. These aliases
retain old symbol resolution or delegate to their real namespace owners; they
do not become a second callable inventory and must remain noneligible.

## Why every excluded function stays excluded

### Birth without hosting is not an agent operation

`create!` and `mint!` commit durable birth facts but do not reconstruct a
runtime listener. A root that selected either and then messaged the returned
id could create durable work for an unhosted child. They are necessary boot
and reconciliation mechanics, but inferior and unsafe substitutes for
`start!`. `ensure-initial-agent!` is the one first-boot transition over the
same mechanism.

### Process hosting is not durable lifecycle

`resume!` and `unhost!` operate on process-local listeners and advertisements.
They do not mean resume or stop in the durable agent FSM. Exposing `resume!`
beside `seon.agent.lifecycle/resume` would create a particularly misleading
name collision; exposing `unhost!` would let a model make a live durable agent
quiet without recording termination. Root's permanent stop is already
`seon.agent.lifecycle/terminate`.

### Census and guards are implementation data

`armable-agent-ids`, `resumable-agent-ids`, and `spawn-depth` exist so boot,
wake, and the hard spawn cap share one derivation. Root observation instead
uses ordinary database reads and derived child/system surfaces. Advertising
the implementation leaves would increase choices without adding an outcome.

### Purpose mutation is not required orchestration

`set-purpose!` is a thin one-attribute mutation whose request oddly names its
text `:seon.render/ai`. It is neither needed to create a correctly purposed
child nor to inspect, message, or terminate one. Keep it as indexed source
until a real agent task proves a distinct need and its request is named by the
data it changes. The root can already transact a deliberate correction through
the standard database surface.

## Complete root control loop through existing owners

| Outcome | Existing callable owner | Why no `seon.agent` wrapper is needed |
|---|---|---|
| Create idle child | `seon.agent/start!` | Unique outcome; proposed positive row |
| Create and task child | `seon.agent/delegate!` | Unique atomic composition; proposed positive row |
| Inspect child facts | `seon.db/query`, `pull`, `entity` | The database is authoritative and already resident |
| Observe progress/results | Root system view and derived child context; database reads | State and results are derived from facts, not another status registry |
| Send or retask | `seon.agent.message/agent` | The one cross-agent message writer already wakes the child |
| Stop permanently | `seon.agent.lifecycle/terminate` | Explicit-id durable termination plus unhosting |
| Pause/resume work | Message the child so it controls its own run | `pause`/`resume` are scoped self-lifecycle; process `resume!` is not this operation |

## Expected context and schema cost

At the measured immutable ACME root value, the schema-only `seon.agent` card
costs 642 estimated tokens. The exact projected compact headers for the two
functions are 305 characters, or 76 tokens under
`seon.ai.tokens/estimate`:

```text
fn seon.agent/start! — map-in :seon.agent/start-request -> :seon.agent/start-response — "Spawn a child agent — the capability-gated spawn lifecycle function."
fn seon.agent/delegate! — map-in :seon.agent/delegate-request -> :seon.agent/delegate-response — "Spawn a child AND hand it its task in ONE call."

```

Their combined transitive cross-namespace closure is ten definitions:

- `:seon.agent.runtime/error` and
  `:seon.agent.runtime/resume-response`;
- `:seon.db/transact-response`, `:seon.db/coordinate`, and
  `:seon.db/error`; and
- `:seon.db.coordinate/coordinate`, `database-id`, `branch`, `commit-id`,
  and `t`.

Rendered with the current closure spelling, those ten definitions are 1,659
characters, or 414 estimated tokens. Current per-card presentation therefore
adds about 491 tokens including the two rows, before negligible blank-line
framing, for a resulting `seon.agent` card near 1,133 tokens. Tightening the
four opaque request slots to the two existing concrete schemas adds roughly
another twenty tokens to the owned definitions.

Eight of the ten closure definitions already occur elsewhere in root's
standing cards. Under the separately proposed global shared-schema section,
only the two `seon.agent.runtime` definitions are new global schema material;
the database and coordinate definitions deduplicate naturally. Eligibility
should not wait for that presentation optimization, but this is why the
long-term cache cost is much smaller than the current per-card delta.

## Exact Inspect trajectories

These are fixed development rows through the existing native Inspect task,
solver, source admission, static-target admission, operation evidence, and
finalized-log read-back. Their prompts state outcomes and constraints only;
they do not name functions or coach a tool sequence.

### Row A — create one idle child

```text
Create exactly one new idle direct child with purpose `audit invoices reachability`. Give it no work. Verify from durable facts that it is your direct child and remains idle, then report its real id. Do not invent an id.

```

Acceptance requires the first prompt to contain exactly the positive
`seon.agent/start!` and `seon.agent/delegate!` rows and no row for any excluded
function. Selection must call `start!`, not `delegate!`, `create!`, or `mint!`.
Operation evidence must show one newly allocated child with exact purpose and
parent root and no task message to it. A later database read must return that
exact id and facts before the human/completion report. Hosting is proven by the
focused `start!` behavior test rather than fabricated from database evidence.

### Row B — delegate one task atomically

```text
Give exactly one new direct child the purpose `reconcile invoice evidence` and the task `Find the three unmatched invoice ids and store the verified ids as database facts.` Verify that both the child and the exact task message exist, then report the child's real id.

```

Acceptance requires selection of `delegate!` in one eval. Evidence must show
one child birth with parent root, then exactly one outbound task message from
root to that returned id with byte-exact content. A later database read must
join the returned id, purpose, parent, and message. The focused behavior test
owns the process-hosting assertion. A `start!` plus guessed id, a task sent to
nil, or final prose without both effects fails.

### Row C — inspect, message, and permanently stop

The fixture creates one idle direct child with purpose
`retire stale invoice worker` before the request.

```text
Find the real id of your direct child whose purpose is `retire stale invoice worker`. Send it exactly `Stop processing; this assignment is retired.`, permanently stop it, and verify that it is terminated before reporting the id.

```

Acceptance requires an observed database query result before the message,
`seon.agent.message/agent` with the observed id and exact content, then
`seon.agent.lifecycle/terminate` with that same id. Ordered operation evidence
must contain the message row and a termination fact. A later read must prove
the termination attr is present and the derived state is `:terminated` before
the report. No new `seon.agent` status, message, or stop function is permitted
to satisfy the row.

### Offline scorer discrimination

For each row, a golden retained trajectory must pass and each mutation below
must fail its named check:

- omit either proposed positive row from the first prompt;
- add any excluded `seon.agent` row to the first prompt;
- retain `:any` in either admitted request contract;
- replace `start!` with `delegate!`, `create!`, or `mint!` in row A;
- replace `delegate!` with a split spawn/message sequence in row B;
- invent or substitute a child id after a real operation;
- remove, reorder, corrupt, oversize, or foreign-attach the required operation
  evidence;
- remove the parent, purpose, task message, or termination fact;
- move verification after the terminal report; or
- preserve a correct final sentence while deleting the execution trajectory.

The scorer uses the existing `surface`, `selection`, `execution`,
`dynamic_context`, `verification`, and `report` check vocabulary. Infrastructure
failure remains source drift, target drift, quiescence, core error, timeout, or
failed native-log read-back—not model capability failure.

## Focused implementation and live proof

Implementation should touch only the existing owner and focused evidence:

1. Tighten the two request schemas, add positive metadata to `start!` and
   `delegate!`, and add an exact `seon.agent` inventory assertion beside the
   existing protected inventories in `test/seon/index_core_test.cljs`.
2. Add a compact-card regression in
   `test/seon/agent/ctx/namespaces_test.cljs`: exactly the two rows, complete
   concrete contracts, no internal function rows, and no unresolved closure.
3. Retain the existing behavior proofs in
   `test/seon/agent/multiagent_test.cljs` and
   `test/seon/agent_loop_test.cljs`; add only a missing atomic-delegation
   assertion if the Inspect fixture exposes an uncovered branch.
4. Add pure scorer golden/mutation fixtures for rows A–C before invoking a
   model. Run only their focused Python modules and the affected CLJS
   namespaces, for example one `bin/test-cljs --test=<namespace>` invocation
   at a time.
5. After the runtime lane hands off one coordinated dependency coordinate,
   rebuild ACME once, mint no replacement root, and prove on one frozen
   database value that root's home edge, two positive program facts, compact
   card, and complete schemas agree. Render twice and require byte identity.
6. Run rows A–C serially through the admitted native Inspect door. Reopen each
   finalized `.eval` and require exact source/model/target identities plus the
   retained prompt, eval, database-operation, verification, and score evidence.

The default cluster is currently degraded and ACME rebuild is explicitly
withheld pending the runtime dependency handoff. This audit performed no
restart, database mutation, source edit, or model invocation; the proposed live
proof remains intentionally pending.
