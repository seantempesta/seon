---
type: research
status: active
tags: [research, agent, runtime, architecture]
---

# Simplification catalog — the composable primitives the research raised

Collected 2026-07-28 from the last two days of research documents plus a
direct read of the fresh source. The organizing question, per the owner's
steering, is not "how does each mechanism work" but **which small primitives
compose so that N special-case mechanisms become 1 general one applied N
ways**. Entries are grouped under the primitive whose extraction dissolves
them; the final section ranks the groups by how much one-off code each
collapse deletes or prevents.

Sources swept: `projection-review-2026-07-28.md`,
`render-universality-audit-2026-07-28.md`, `test-design-review-2026-07-28.md`,
`context-blocks-plan-2026-07-28.md`, `my-message-proof-2026-07-28.md`,
`quarry-gold-inventory-2026-07-28.md`, `turn-dispatcher-design-2026-07-27.md`,
`error-handling-grounding-2026-07-27.md`, `model-failover-2026-07-27.md`,
`local-provider-2026-07-28.md`, `plan/unsettled.md`, `plan/README.md`
(Rulings 2026-07-27 batches), and fresh source read in full:
`src/seon/render.clj`, `src/seon/render/block.clj`, `src/seon/error.clj`,
`src/seon/problems.clj`, `src/seon/instrument.clj`,
`src/seon/cluster/loop.cljc`, `src/seon/cluster/prompt.cljc`,
`src/seon/cluster/message.cljc`, `src/my/run.cljc`, `src/my/message.cljc`,
plus targeted greps over `src/seon/ai.cljc`, `src/seon/cluster.clj`,
`src/seon/cluster/run.cljc`, and `src/seon/cluster/export.clj`. Every
file:line below was read directly; claims grounded only in a research doc say
so.

Entries marked **RULED** already carry an owner ruling and are implementation
alignment; **OWNER** means the collapse needs a ruling before a lane can bake
it in; **MECHANICAL** means a lane can land it under existing contracts.

## Group 1 — THE RENDER UNIT: one router, applied everywhere anything is shown

The primitive is already landed and tiny: a unit is any map whose
`seon.render`-namespaced keys carry qualified projection symbols;
`seon.render/render` late-resolves the var and applies it; `kinds` computes
the open kind set from the unit itself (`src/seon/render.clj:96-174`). The
composition claim is that **every consumer-facing presentation in the system
is this one primitive applied to a different value** — and the audit found
production is not there yet. Extracting nothing new, just APPLYING the landed
primitive, dissolves the largest amount of one-off code in the tree.

### 1.1 The prompt is the AI render of the agent's blocks

- **Composition:** `prompt = reduce over (render unit :seon.render/ai) for the
  agent's ordered blocks`. The prompt is not a formatter; it is the same block
  derivation the human page uses, asked for a different kind.
- **Pattern:** `seon.cluster.prompt/prompt` concatenates identity, peers +
  message grammar, interruption, pause continuity, sender-aware trigger, and
  execution grammar as bespoke prose (`src/seon/cluster/prompt.cljc:230-287`);
  the render audit classifies it as "the largest second rendering system"
  (`render-universality-audit-2026-07-28.md`, consumer table). Each helper
  (`interrupted-sentence` `:164-191`, `paused-sentence` `:193-219`, the peers
  sentence `:264-274`) is a context block written inline: a query plus a
  projection that omits itself when the facts are absent.
- **Collapse:** the context-blocks plan's core package — each prose piece
  becomes a named block (`:identity`, `:peers`, `:trigger`, `:interruption`,
  `:continuity`, `:execution`); `prompt` keeps only selection, validation,
  ordered reduction, and the rendered-context value
  (`context-blocks-plan-2026-07-28.md` §"Prompt convergence"). The falsifier
  is structural: replacing one block's projection symbol changes both the
  next prompt and the next page with no edit to prompt, route, or page code.
- **Size/risk:** contract-level — an explicit revision of the sealed N3
  prompt schema/function/loop/suite (review finding 5). **OWNER** (the
  morning context-blocks batch gates it). Open issue:
  `prompt-assembly-bypasses-the-render-router`.

### 1.2 stderr warnings are the log render nobody routed

- **Composition:** a development warning is `(render unit :seon.render/log)`
  over a process fact, exactly as an error line already is.
- **Pattern:** three owners hand-compose stderr text: the core-fault
  panic/drop prints (`src/seon/cluster.clj:560-561,658-659,670-671`,
  grep-verified; classified REINVENTION except the recursion fence at the
  audit's `cluster.clj:498-503`), the export slow-path warning
  (`src/seon/cluster/export.clj:97-98`, whose docstring already expects
  replacement), and the instrumentation zero-count warning
  (`src/seon/instrument.clj:210-213`).
- **Collapse:** each condition builds a unit declaring `:seon.render/log` and
  the process-log consumer routes it; direct stderr survives only at the one
  documented recursion fence where the error/render path itself has failed.
- **Size/risk:** small **MECHANICAL** once a log consumer owner exists. Open
  issue: `stderr-presentations-bypass-the-log-render-kind`.

### 1.3 Every entity family is one unit-build point away from being renderable

- **Composition:** `entity-unit` already proves "a pulled entity IS a unit"
  (`src/seon/render/block.clj:297-322`) — `/data` renders arbitrary entities
  with ZERO authoring by falling to the generic `data-panel`. So a family's
  AI/HTML/log coverage costs exactly one producer-owned value→unit function,
  never a page model.
- **Pattern:** runs, receipts, messages, attempts, agents, config, and
  cluster have no unit-build point; errors (`seon.error/notice`,
  `src/seon/error.clj:367-396`), problems (`src/seon/problems.clj:153-184`),
  and blocks have the correct ones. The audit's family table names each
  missing build point and warns against the two wrong homes: projection
  symbols stored into receipt/attempt transactions, and a synthetic cluster
  status entity invented merely to gain a builder
  (`render-universality-audit-2026-07-28.md` §"Generic default plus
  specialist selection").
- **Collapse:** one derived unit builder per family, in the audit's accretion
  order (error/problems HTML → problems block → prompt → run+receipt →
  message+attempt → agent → config+cluster → program-graph). Each builder is
  a pure `value → unit` beside the family's owner.
- **Size/risk:** medium, incremental, **MECHANICAL** per family after the N4
  block pipeline lands; the order itself is already written.

### 1.4 Specialist selection belongs where the unit is built — `select` is the shape, and even `seon.error` doesn't use it yet

- **Composition:** `select` (generic default + ordered predicate/projection
  pairs, first accepting rule wins, broken rule costs only itself —
  `src/seon/render/block.clj:582-629`) is THE selection primitive the owner
  ruled reusable. A producer that knows more points the unit's key at a
  specialist; the consumer never branches.
- **Pattern, two violations of the shape it replaces:**
  - `seon.error/notice` hand-codes its one specialist with an `if` on the
    kind (`src/seon/error.clj:386-389`) instead of calling `select` with a
    specialists vector — fine at one specialist, a divergent second selection
    mechanism at two.
  - `ai-prose` is a generic projection carrying a `cond` over kinds, reasons,
    and error-classes (`src/seon/error.clj:499-552`) — refusal, recurring,
    no-credential, transport-before-send, and unparseable-body branches are
    five specialists living inside the default. `log-line` repeats the shape
    with per-family field lifts (`src/seon/error.clj:554-624`). The
    projection review's approved outputs are correct; their HOME is the
    hand-rolled selection.
- **Collapse:** `notice` calls `select` with its specialists; each `cond`
  branch of `ai-prose`/`log-line` becomes a named specialist selected there.
  Adding an error family's prose then never edits the generic default.
- **Size/risk:** small-medium **MECHANICAL**; the projection review already
  approved the output text, so this is a pure re-homing.

### 1.5 The normalizer's per-family evidence lift is the same drift in the fact producer

- **Pattern:** `normalize` special-cases the instrumentation family inline —
  detecting `:seon.instrument/contract-violated` and lifting its fn/arm/
  schema/args onto the fact (`src/seon/error.clj:308-310,337-344`). A second
  specialist family (say, a provider family wanting first-class disposition
  fields) would add a second inline lift to the one normalizer.
- **Collapse candidate:** specialist evidence lifting as data — a family's
  refusal names the attributes it wants promoted, or the lift moves into the
  family's own unit builder (group 1.3) leaving `normalize` truly generic.
- **Size/risk:** small, **OWNER**-flavored (it touches what the durable fact
  carries); fine to defer until a second family forces it.

### 1.6 One invocation seam: compiled Var and N5 SCI Var through the same router

- **Composition:** the router's `requiring-resolve` is one IMPLEMENTATION of
  "resolve the declared symbol" (`src/seon/render.clj:156-158`); an
  N5-acquired agent-authored projection is another. One computed invocation
  seam — provenance decides compiled-core vs acquired-SCI, both admitted,
  both returning the same result union — keeps the one-router law when agents
  author renders.
- **Evidence:** context plan Decision 2 / review finding on
  `render.clj:120-147`; recommendation Option C, sealed jointly with the N5
  evaluator owner (`context-blocks-plan-2026-07-28.md` §"Projection
  execution").
- **Size/risk:** contract-level, **OWNER** (in the reduced morning batch).

### 1.7 Omission as the third router arm kills nil-sentinels and duplicated conditions

- **Composition:** rendered / omitted / failed as three disjoint-by-key
  results makes "a warning that queries facts and contributes nothing when
  clean" expressible IN the projection, not duplicated in a selector.
- **Evidence:** context plan Decision 1, Option C recommended; today an HTML
  nil becomes a `::not-hiccup` failure at `surface`
  (`src/seon/render/block.clj:249-262`), so a clean conditional block has no
  honest way to say "nothing". This decision also settles the standing
  `[:maybe]`-in-fn-returns question for this mechanism.
- **Size/risk:** contract-level, **OWNER** (morning batch).

### 1.8 Advertised projections that do not resolve are false coverage

- **Pattern:** the program-graph catalog publishes six projection symbols
  under absent `seon.render.handlers.*` namespaces (audit row citing
  `src/seon/schema.cljc:538-575,1121-1190`; not independently re-read here).
- **Collapse:** resolve or remove; N5 owns it. The general rule is the
  router's own: every advertised symbol resolves, proven by census. Open
  issue: `program-graph-render-declarations-name-absent-functions`.
- **Size/risk:** small, N5-owned.

### 1.9 Render coordination: one invalidation owner, per-kind slots, no assumed cache

- **Pattern:** N4's earlier one-value-per-registration prose is false — a
  block's AI and HTML projections are different functions, so one completed
  value cannot serve both kinds (context plan review, finding E). Separately,
  retaining completed results was being admitted by assumption rather than
  measurement (finding 8).
- **Collapse:** one invalidation owner with independent per-kind active/
  result slots (a correctness correction, **RULED**); completed-result
  retention absent until the predeclared miss/join/hit/cost/weight experiment
  earns it.
- **Size/risk:** medium, N4 seal correction.

## Group 2 — THE ADMISSION CODEC: `seon.sci.admit` is the one bound on every value that leaves anywhere

The primitive: one total, pure, cap-bounded projection of any value
(`admit/admit` with the four config caps). The composition claim, already
mostly realized: **anything that prints, stores, ships, or displays a value
of unknown size is admission applied at a different door** — and every
hand-rolled second bound is a defect.

### 2.1 Landed proof: four doors already share the one codec

- eval results at the bounded evaluation; the error normalizer projects the WHOLE
  flow error map — including `::flow/state` holding a live connection —
  through `admit` with `(constantly nil)` interrupt
  (`src/seon/error.clj:316-319`); the instrumentation reporter bounds `:args`
  the same way, and OMITS them when it has no caps rather than printing
  unbounded (`src/seon/instrument.clj:120-163`); `data-panel` bounds what it
  panels with the same caps and refuses to render without them
  (`src/seon/render/block.clj:662-710`).
- Block/ref expansion reuses the SAME four caps as its node/depth budget
  rather than a second dial set (`src/seon/render/block.clj:363-392`) — the
  measured lesson (fan-out without cycles OOM'd the JVM at 22 blocks) is the
  same lesson admit already paid for on value trees.

### 2.2 The one remaining bypass: sci eval's failure arm (D2)

- **Pattern:** `seon.sci.eval`'s failure value `pr-str`s raw sci ex-data —
  including the `:sci.impl/callstack` volatile with live namespace objects —
  into the durable receipt, on the one path designed to be
  unbounded-`pr-str`-free (`error-handling-grounding-2026-07-27.md` §3.2 and
  D2, citing `eval.clj:276-281,362`). The projection review shows the
  consequence verbatim: receipts whose `result-edn` carries unstable
  `#object[...]` identities no EDN reader can consume
  (`projection-review-2026-07-28.md` §1).
- **Collapse:** derive the bounded diagnostic map (phase, line/column/file,
  `sci/stacktrace` as ordinary data, deepest cause kind) and run it through
  `admit` with the same caps — the grounding's S1 recipe. This also delivers
  the actionable fields the agent is never told today (WHERE its form failed,
  and whether the failure was parse/analysis/runtime).
- **Size/risk:** small-medium **MECHANICAL**, `seon.sci.eval` owner; the
  recipe is written.

### 2.3 Caps are config facts with exactly one spelling — tests included

- **Pattern:** the caps map is copied into suites (`admit_test.clj:57-61`,
  `eval_test.clj:22-26` per test review finding 7) and appears as a support
  constant in five test files (finding 9 table).
- **Collapse:** derive caps from the production effective config/request in
  every suite; never a support constant. Production-side, `data-panel`'s
  "caps REQUIRED, no default here" (`src/seon/render/block.clj:655-659`) is
  the correct shape and the context plan's one unit builder threads the same
  set to every projection (review finding 3, fixed in plan).
- **Size/risk:** small **MECHANICAL** (test units 2-3).

### 2.4 Future application: bounded projection + blob ref is the one shape for large evidence

- **Composition:** the three-tier rule (datom projection bounded by admit;
  full bytes content-addressed in the blob archive; a ref between) makes
  "how do we store a stack trace / prompt blob / large result" one answer.
  Stack traces stay out of the step-1 fact for exactly this reason
  (`plan/unsettled.md` step-1 notes); turn capture and the archive are quarry
  order Q1 (`quarry-gold-inventory-2026-07-28.md` §Q1).
- **Size/risk:** future rung; recorded so nobody invents a second bounding or
  a second archive.

## Group 3 — COMMITTED FACTS ARE THE TRANSPORT: message/to is the wake, tx-meta trigger is the cause, listen! interest is the router

The primitive: a committed fact plus attribute-indexed `listen!` interest IS
the delivery mechanism; `:seon.db/trigger` transaction metadata IS causation.
The composition claim: **queues, counters, acknowledgement flags, reply
protocols, storm fences, and render delivery are all queries over facts
already committed** — each new consumer of the chain costs zero new storage.

### 3.1 Landed proof: one wake attribute, three producers

- `:seon.cluster.message/to` wakes the recipient's loop by construction; the
  error recorder (`src/seon/error.clj:705-766`), agent sends via the loop's
  terminal transaction (`src/seon/cluster/loop.cljc:805-883`), and the
  derived completion reply (`src/seon/cluster/message.cljc:134-180`) are
  three producers of the same fact. The reply deliberately returns a
  `my.message` VALUE so it inherits the recipient check, the bound, and the
  derived id — no second way to make a message.

### 3.2 One tx-meta ref answers four questions — the quarry paid for each separately

- **Composition:** `:seon.db/trigger` on the run-opening and delivering
  transactions answers: answeredness (does any opening tx point at this
  message), chain depth (walk `caused-by` to a message with no trigger —
  the human barrier free), reply-vs-question (the trigger is an answer to us
  exactly when the message that caused it was ours), and prompt cause (the
  held run's creating transaction names its exact trigger).
- **Evidence:** `src/seon/cluster/message.cljc:70-180`;
  `my-message-proof-2026-07-28.md` §3-4 (the quarry's stored `hops` integer
  plus its deadlock-prone per-peer reset rule BOTH dissolved; the bounce
  defect fixed by the same walk answering a second question).
- **Collapse remaining — the one place still not composed:** the loop's
  `:call` branch re-asks `work/unanswered-triggers` for the prompt's trigger
  (`src/seon/cluster/loop.cljc:649-654`) although `message/trigger` already
  derives the held run's recorded cause (`message.cljc:70-86`) — two
  derivations of one fact, and the review proved the re-ask can select a
  LATER message than the run's recorded opener (context plan review finding
  6). The prompt request should name the held run and use the one owner.
- **Size/risk:** contract-level (part of the prompt seal revision), CONFIRMED
  DEFECT, **OWNER**-gated with group 1.1.

### 3.3 Both storm fences are derived counts over committed facts — never tallies

- **Composition:** error recurrence = `(count facts with this signature in
  this process)` (`src/seon/error.clj:669-681`); conversation depth = the
  chain walk. Same shape: the bound is a QUERY, the reset is structural
  (process restart; human message), and nothing increments or forgets.
- **Evidence:** the error fence measured live (six faults in 1.5 s bounded to
  limit+1 messages per signature; facts keep committing —
  `src/seon/error.clj:746-766`); the chain bound consulted only for
  arithmetic in the final live drive (`my-message-proof-2026-07-28.md` §6).
- **Remaining:** the chain-bound's currency (hops vs spend vs wall time) and
  the dial's number are morning question 1; refusal visibility to the sender
  is question 2, whose honest fix is a derived prompt sentence from the
  agent's own recent error facts — i.e., group 1's block primitive again,
  never a second notification path. **OWNER**.

### 3.4 The render delivery substrate is the same primitive generalized

- **RULED (owner, night):** the router's delivery substrate is committed
  facts + `listen!` attribute interest — subscribe to the projection-key
  attribute set; any committed entity carrying a projection key routes; never
  a second channel. This is the wake mechanism generalized from one attribute
  (`message/to`) to a computed attribute set (`plan/unsettled.md`, owner
  routing direction). N4's listen!-repaint web layer is the first consumer.
- **Size/risk:** N4-owned; recorded here because it makes "how do live
  updates travel" a solved question for every future kind.

### 3.5 Fail-closed missing-trusted-input is one shape, and its home is the request builder

- **Pattern:** four spellings of "a required non-database input is absent →
  refuse legibly, never default": message delivery's `::no-limit`
  (`src/seon/cluster/message.cljc:239-248`), the error recorder's
  `bounded?`/silent conservative half (`src/seon/error.clj:786-802`), the
  problems block's live-processes card (`src/seon/problems.clj:284-289`), and
  `data-panel`'s caps card (`src/seon/render/block.clj:707-710`). Each is
  correct alone; each is a consumer defending against a misassembled request.
- **Collapse:** the context plan's ONE request/unit builder threads caps and
  the live-process snapshot into every projection and refuses at
  CONSTRUCTION when a membership needs an absent input
  (`context-blocks-plan-2026-07-28.md` §"The block unit", review finding 7
  fixed) — consumers then keep their cards only as the last-resort fence, and
  new consumers get the check for free.
- **Size/risk:** rides the context contract; the two loop-side spellings
  (message limit, recorder limit) stay until instrumentation enforces request
  shapes.

## Group 4 — ONE CONSTRUCTION PER FAILURE CLASS: the test-design dissolutions compose out of three shared pieces

The test review's eleven findings are explicitly pattern-collapses; the
composable insight is that they factor through THREE reusable constructions,
after which most point tests are one property each
(`test-design-review-2026-07-28.md`, findings and 9-unit order).

### 4.1 `await-event!` — one event-observation owner

- **Composition:** every asynchronous wait is "consume the real event the
  production owner publishes; a clock only as a loud backstop". Where the
  production interface hides readiness, change the INTERFACE (the timeouts
  ruling's corollary), never poll.
- **Pattern dissolved:** the 5 ms polling loop (`flow_test.clj:56-69`),
  database polling despite `listen!` (`armed_test.clj:49-60`), sleep-to-infer
  negatives, 300-2000 ms channel timers, and live-producer equality races
  (finding 1's full inventory). The stop-vs-transact race fix already proved
  the production half: the loop PUBLISHES its completion and `stop!` awaits
  it (`plan/unsettled.md`, `852ef9759`;
  `src/seon/cluster/loop.cljc:428-440`).
- **Size/risk:** medium **MECHANICAL** (units 1+3); collapses wait mechanics
  in ~21 tests across flow/armed/wake.

### 4.2 `with-database` — the canonical fixture derived from the one schema authority

- **Composition:** every runtime fixture installs
  `schema/canonical-database-attributes` — the same derivation boot uses —
  derived at call time, never a cached vector and never a second registry.
- **Pattern dissolved:** eight hand-listed attribute fixtures (finding 2's
  list), the fixture-green/live-boot-red class that cost the N3 live drive
  two rounds; plus the ten duplicated-helper rows of finding 9 (~250 lines of
  scaffolding: recursive deletion ×6, refusal unwrap ×5, fresh database ×11,
  file-store probe ×4, caps copies ×5, `check!` ×2, error-value predicate
  ×2).
- **Size/risk:** small, high payoff, **MECHANICAL** (unit 2).

### 4.3 One command/model state-machine harness, applied three times

- **Composition:** generate commands from REGISTERED schemas, drive the real
  boundary, and check durable facts against a pure oracle — built once for
  the AI/turn matrix and REUSED for message delivery and the block graph.
- **Pattern dissolved:** the turn/failover scenario matrix (seven `ai_test`
  points + nine `turn_test` scenarios → one disposition property + one
  state-machine property + two teaching examples, finding 3); the message
  examples (chain depth, fan-out, refusals → one property, finding 5,
  "it can reuse the command/model harness built for the AI/turn unit"); the
  38-test block suite's 21 collapsing examples (one generated-graph property
  whose oracle is independent of `expand`, finding 4); schema-AST spellings
  (one grammar generator feeding bridge AND admission gates, finding 6);
  hand-built generator domains everywhere (finding 10 — field domains derive
  from the schema registry, models only sequence).
- **Size/risk:** medium-large, the highest-leverage point-test dissolution;
  order and exits already written (units 4-7). **OWNER** approves the order
  (morning item 2), then lanes.

### 4.4 Ownership cuts and honesty fixes that ride along

- admit owns hostile values, evaluate owns only the composition contract
  (finding 7); prose assertions become structured projection assertions with
  `prompt_test` as the model and `hiccup_test`'s exact bytes correctly exempt
  (finding 8); the `(is true)` false proof deleted immediately
  (`eval_test.clj:246-252`); benchmarks each name their question, correctness
  owner, unique root (finding 11); the runner rejects a selected zero-test
  namespace; one discovered reset-boundary falsifier (§"Reset-boundary
  coverage") closes the class no fixture can see.

## Group 5 — SERIAL IS THE NUMBER 1: the dispatcher, the launcher, and the plural derivation are generalizations, not additions

The design doc's whole shape is composition
(`turn-dispatcher-design-2026-07-27.md`):

### 5.1 One dispatcher; a serial runtime is concurrency `1`

- **Composition:** the end-state loop is one dispatcher proc — pin one
  database value, derive at most one instruction per admissible agent, submit
  to the bounded `:io` class, return; completion re-wakes. "No branch chooses
  'inline mode', and no separate serial loop remains." The current inline
  one-instruction pass (`src/seon/cluster/loop.cljc:442-474`) becomes the
  special case at dial `1`.
- **What it deletes/prevents:** a second scheduler, a durable `busy?`/queue
  row (explicitly rejected — the process-local active-agent map fences only
  the money race the database cannot see), and any "concurrent mode"
  implementation later.
- **Size/risk:** contract-level wave over six owners (schema/flow/work/loop/
  boot + suites), gated on the two-cluster measurement. **RULED** in
  direction; lands post-measurement.

### 5.2 One launcher, per-class — not a second launcher for `:io`

- **Composition:** the existing compute work launcher generalizes to
  per-class channels/active-counts/queue-depths (`:compute`, `:io`), with
  dials following the existing `:seon.config.flow.compute/*` naming — the
  bounded resource is the launcher's class, and turns are just its first
  `:io` workload.
- **Size/risk:** rides 5.1.

### 5.3 `all-work` is the plural of `next-work`; the singular becomes agent-local

- **Composition:** the pure derivation factors into `next-agent-work`
  (agent-local, used inside a task's fold — fixing the current fold's global
  `next-work` lookup that another agent can win) and `all-work` (one ordered
  vector from one basis). One derivation, two arities of scope.
- **Evidence:** `turn-dispatcher-design-2026-07-27.md` §"Race audit" (the
  fold/global-ordinal hazard) and §"Configuration" item 4; current global
  lookup at `src/seon/cluster/loop.cljc:885-890`.
- **Size/risk:** rides 5.1; the fold fix is needed the moment concurrency > 1.

### 5.4 Landed exemplars from the failover rung (cite, don't redo)

- Backup = OVERRIDES over the primary — one map, second name; partial backup
  unrepresentable; `loop/provider` deleted (`plan/unsettled.md`, step 5
  record). Providers are descriptor ROWS over two wire cores, never adapter
  arms (**RULED**, `AGENTS.md` provider boundary). The backoff schedule is
  EMPTY whenever a backup exists — the rule held by data, not by a condition
  inside the reduce (`src/seon/cluster/loop.cljc:655-661`). Disposition is
  COMPUTED from transport-phase evidence, never a kind list
  (`model-failover-2026-07-27.md` §"Computed error-class rule";
  `src/seon/ai.cljc:361` per grep). The backup's context is the notice's
  `:seon.render/ai` over the COMMITTED primary fact through the one router
  (`src/seon/cluster/loop.cljc:707-722`) — group 1 and group 3 composing.
- **Remaining in-family:** the no-auth local target is an accretion to the
  ONE four-fact target contract (absence omits the header) or a documented
  dummy credential — never a provider branch or second transport
  (`local-provider-2026-07-28.md` §"No-auth decision"). **OWNER** (morning
  small batch).

## Group 6 — SMALL MECHANICAL DEDUPS: one idiom, N hand-rolled spellings

Each is real duplicated code today; none is architectural. Batch them into
one hygiene lane.

### 6.1 The digest wrapper — already queued

- **Pattern:** `(schema/sha-256 [(.getBytes (pr-str x) "UTF-8")])` spelled at
  the plan digest (`src/seon/cluster/loop.cljc:209-213`), the error signature
  (`src/seon/error.clj:257-264`), the config digest (`src/seon/config.cljc:179`,
  grep), and the ancestor (`src/seon/cluster/ancestor.clj:140,150`, grep).
  `plan/unsettled.md` already queues "digest SHA-256 helper triplicated —
  wants one owner".
- **Collapse:** one `schema/digest` (value → hex) beside `sha-256`.
  **MECHANICAL**, tiny.

### 6.2 Existence-before-lookup-ref — three copies of one guard

- **Pattern:** `agent-exists?` in `src/seon/error.clj:645-655` and
  `src/seon/cluster/message.cljc:186-191`; the general `entity-exists?` in
  `src/seon/error.clj:657-667`. All three exist because "a lookup ref to a
  missing entity fails the WHOLE transaction", and both consumers apply the
  same policy (drop the ref / refuse the row, never the record).
- **Collapse:** one public `entity-exists?` (its two callers pass the
  identity attribute), owned where transactions are assembled. **MECHANICAL**.

### 6.3 Derived identity from provenance coordinates — one idiom, five spellings

- **Pattern:** receipt `(pr-str [run ordinal epoch])`
  (`src/seon/cluster/run.cljc:494`), attempt `<run>-attempt-<ordinal>`
  (`src/seon/cluster/loop.cljc:276-282`), message
  `<run>-<ordinal>-message-<index>` (`src/seon/cluster/message.cljc:193-199`),
  explanation message `<error-id>-<reason>` (`src/seon/error.clj:693`), fact
  tempid `seon.error/fact-<id>` (`src/seon/error.clj:641-643`). The idiom —
  identity as a function of where the thing came from, so re-execution
  upserts instead of double-writing — is load-bearing and correct everywhere.
- **Collapse:** possibly none. A shared helper would buy little and blur the
  per-family formats readers grep for. Recorded as the idiom to REUSE (a new
  durable row derives its id; nothing allocates a uuid for a row with
  provenance), not as code to merge. The two remaining `random-uuid` ids —
  run ids (`loop.cljc:553`) and loop-side error ids (`loop.cljc:230`,
  `cluster.clj:545`) — are genuinely provenance-free today; if error ids ever
  want idempotent re-record, they would derive from (signature, occurrence).
  Flag only.

### 6.4 The 60-second lease constant, hand-rolled three times

- **Pattern:** `(Date. (+ (inst-ms now) 60000))` at
  `src/seon/cluster/loop.cljc:505,564,929`. A magic number under the
  timeouts ruling, and already implicated by the filed issue
  `a-turns-model-work-can-outlive-its-own-run-lease` (60 s deadline = 60 s
  lease; the honest fix is a claim-contract interface change).
- **Collapse:** one lease derivation owned by `seon.cluster.run` (or a config
  fact), landed WITH the claim-contract fix rather than as a cosmetic
  constant-hoist. **OWNER**-adjacent (N2/N3-owned issue).

### 6.5 The agent-mistake guard and the one flat error shape

- **Pattern:** the blank/non-string guard is spelled three times
  (`src/my/run.cljc:62-66,85-89`, `src/my/message.cljc:85-97`) — acceptable
  as deliberate self-containment — but `my.run`'s two error values omit the
  REQUIRED `:seon.error/kind` and so sit outside their own declared output,
  while `my.message` does it correctly (filed:
  `my-run-error-values-omit-their-kind`, asserted deliberately by
  `my.message-test`).
- **Collapse:** fix `my.run` to the one registered shape. The guard itself
  can stay triplicated. **MECHANICAL**.

### 6.6 The value recognizers will want the computed rule at three

- **Pattern:** `disposition` and `messages` in
  `src/seon/cluster/loop.cljc:135-161` are the identical shape —
  `(when (schema/valid-candidate-value? <schema> value) value)` — over the
  two agent-facing value families. The owner's routing ruling says KEYS are
  the routing authority and schema-matching is diagnostic; two closed
  disjoint schemas are fine as two recognizers.
- **Collapse trigger:** the THIRD agent-facing value family (plan values,
  canvas values are plausible per the gold order). At that point the loop
  should read the admitted value against the computed set of registered
  closed agent-value schemas rather than grow a third copy. Recorded as a
  tripwire, not current work.

### 6.7 Refusal construction and the one unwrap point

- **Pattern:** the `refuse!` ex-info idiom (`src/seon/cluster/prompt.cljc:221-224`
  and the store/registry/ancestor/export refusal throws per the grounding's
  table rows 18-21) against the one unwrap walk `error/refusal`
  (`src/seon/error.clj:166-186`, correctly relocated from the store). The
  known seam: a refusal's ex-data does not survive Datahike's writer boundary
  (caller sees `{}`; kind/rule live in the message and cause chain), and the
  run loop's transact wrapper is the ONE unwrap point to design
  (`plan/unsettled.md`, N3 open contract decision).
- **Collapse:** when that unwrap point is designed, a shared
  `refuse!`/refusal-rule constructor could ride along so every producer's
  ex-data shape is uniform by construction. Small, **OWNER**-adjacent.

## Ranking — which primitive dissolves the most one-off code

Ranked by composability: how many distinct special-case mechanisms one
primitive's extraction (or consistent application) deletes or prevents.

1. **THE RENDER UNIT (group 1).** One landed 174-line router replaces: the
   prompt formatter (the largest second rendering system), all stderr
   presentation, per-family page code for ~10 entity families (each becomes
   one unit builder — `/data` already proves the zero-authoring floor), the
   error family's five in-cond specialists, and — via the invocation seam —
   the future agent-authored render path. It is also the delivery target for
   group 3's generalized interest routing. Nothing else in the tree turns as
   many N-mechanism surfaces into 1-primitive-N-applications; and its
   remaining blockers are exactly the four reduced morning decisions.
2. **ONE CONSTRUCTION PER FAILURE CLASS (group 4).** Three shared test
   constructions (event observation, canonical fixture, one command/model
   harness reused three times) collapse ~50+ named point tests and ~250
   lines of scaffolding, and — more important — prevent every future suite
   from re-inventing waits, fixtures, and generator domains. Highest
   deleted-line count of any group; entirely spec'd.
3. **COMMITTED-FACT TRANSPORT (group 3).** One tx-meta ref + one wake
   attribute + derived counts already dissolved the quarry's 590-line
   effectful messenger, its stored hop counter AND its reset rule, the reply
   protocol, and the error notification queue that was never built. Its
   remaining collapse (held-run trigger selection) fixes a confirmed
   wrong-answer defect, and its generalization (listen! interest as the
   render substrate) prevents every future "how do updates travel" mechanism.
4. **THE ADMISSION CODEC (group 2).** Mostly landed; the composition is
   preventive — every future door (blob evidence, new projections, new
   receipts) gets its bound for free, and the one remaining bypass (D2) is
   the difference between readable and unreadable durable receipts. Closing
   it deletes the last place a second bounded printer could grow.
5. **SERIAL = 1 (group 5).** Prevents an entire parallel implementation (a
   second scheduler, a concurrent "mode", a durable queue) rather than
   deleting existing code; the launcher and work-derivation generalizations
   are the same prevent-a-fork move. Big prevention, small deletion, gated on
   measurement.
6. **MECHANICAL DEDUPS (group 6).** Tens of lines each; batch into one
   hygiene lane (6.1, 6.2, 6.5 now; 6.4 with its owning issue; 6.3 and 6.6
   are recorded idioms/tripwires, not edits).

The cross-cutting observation the owner's steering asked for, stated once:
the tree's proven collapses all have the same anatomy — **a key on a value
(presence-as-declaration), a computed set instead of a list, and one
late-resolved owner behind one door** — and every entry above is either an
application of that anatomy or a place where a mechanism still carries its
own private copy of one of the three parts.
