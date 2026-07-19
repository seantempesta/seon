---
type: research
status: active
tags: [research, agent, database, cljs, web]
---

# Overnight integrated graduation plan

## Persistent goal

Graduate Seon as one stable, fail-loud, fast system: deterministic startup and
reload; database-backed agents that move through and share namespaces;
namespace-targeted launch, messaging, restart, and recovery; passing live
Inspect AI namespace, database-memory, planning, multi-agent, and
failure-recovery scenarios; passing browser/Datastar, complete test,
multi-cluster, downstream, immutable-package, latency, and modest-hardware
resource gates; and removal of obsolete mechanisms revealed by the integrated
proof.

This is the complete-program goal, not the name of the current implementation
slice. It remains active while any dependency-ready correctness, integration,
proof, packaging, or measurement work remains. Evidence may improve the plan
when it reveals a simpler or stronger design, but a local green test selection
does not shorten the graduation gate.

## Outcome

Graduate one Seon system whose normal product path and Inspect AI path are the
same path: deterministic startup and reload, fail-loud development, isolated
Bun execution children, one JVM database authority, database-backed agents that
move through and share namespaces, namespace-targeted launch and messaging,
restart and recovery, reactive browser behavior, immutable packaging, and
measured modest-hardware performance.

The plan may improve when source or live evidence exposes a simpler or stronger
design. It may not add a benchmark-only runtime, parallel database API, second
renderer, compatibility namespace, transcript-regex correctness authority, or
stored projection that duplicates derivable database facts.

## Dependency ledger

- ClojureScript `1.12.145`, the shadow-cljs npm `3.4.10` CLI shim, and maintained
  Shadow source commit `615430b3`; runtime compilation and
  namespace movement are owned by `reference-code/clojurescript/`,
  `reference-code/shadow-cljs/`, `src/seon/eval.cljs`, and
  `src/seon/agent/turn.cljs`.
- Datahike and Proximum revisions selected by the root `deps.edn`; database
  values, basis transactions, commit IDs, schema, transactions, and indexed
  reads are owned by `reference-code/datahike/`, `reference-code/proximum/`,
  `src/seon/db/`, and the database-authority-mesh dependency ledger.
- Namespace residency uses vendored Datahike commit
  `4c55791be1fb8bb8d9332f21c576f5c20b85b760`: schema validation permits a
  unique `:db.type/ref`, transaction ref values resolve to eids before AVET
  uniqueness, and stale database-value retry serializes concurrent creation.
  First-party owners are `src/seon/agent.cljs`, `src/seon/agent/home.cljs`,
  `src/seon/agent/runtime.cljs`, and `test/seon/agent/multiagent_test.cljs`.
- Bun is the pod and execution-child runtime selected by the packaged artifact;
  spawning, supervision, HTTP/SSE, and process evidence are grounded in
  `reference-code/bun/`, `src/seon/execution.cljs`, `src/seon/client.cljs`, and
  the operator.
- Inspect AI and Inspect Evals are pinned by
  `src-inspect-ai/evaluation-sources.lock.json` and sourced from
  `reference-code/inspect-ai/` and `reference-code/inspect-evals/`.
- Current first-party namespace evidence is
  `src-inspect-ai/src/seon_inspect/milestone.py`, which already scores namespace
  movement and later-turn database recall from database-derived eval rows.
- Current agent creation and messaging owners are `src/seon/agent.cljs`,
  `src/seon/agent/message.cljs`, and their focused tests. `:seon.agent/id`
  remains immutable identity; `:seon.ns/name` remains namespace identity.

Before changing a dependency boundary, record its selected revision and exact
source lines in this ledger or the owning successor PRD.

## Current evidence

- The clarified three-epoch namespace retry exposed a real concurrency fault,
  not a scorer miss. While sibling agents committed more than 64 transactions,
  execution child `common-dots-stay` received database-advanced events it did
  not consume, the writer closed that physical session with
  `send/session-full`, and the child retained a core write fault before exit.
  Bun's maintained socket contract confirms that `write` returning zero or a
  partial count is backpressure, while `-1` means the socket is already closed
  or shutting down. The writer had treated every database acquisition as a
  subscription to committed database values. Protocol version 11 now makes
  that delivery selectable: the pod retains it and execution children decline
  it because each child begins with one immutable database value and advances
  through its own transaction reports. A real two-session writer test proves a
  declined acquisition remains silent across a sibling transaction; the
  focused writer boundary passes 40 tests/222 assertions and the CLJS session,
  execution-host, and UDS selection passes 40/164. The fix is not graduated
  until a fresh three-epoch live run completes without delivery pressure.
- The first retry on protocol 11 sustained three concurrent children without
  database delivery pressure; two completed and one sample scored. It exposed
  the next independent admission bound: a 34-turn run produced 21,999
  characters of valid model-transport evidence, but the web owner reused the
  16,384-character database display cap and returned only `oversized`. The
  transport projection is already bounded by the run turn limit, retry limit,
  closed attempt attributes, and per-field identity caps, so the unrelated
  aggregate display cap is removed. The same cancellation also reproduced the
  open containment issue: one Bun execution child outlived the drained branch
  pod and retained its database acquisition until normal full shutdown.
- Maintained Seon and Bun source establish the containment cause and the one
  correction. Seon deliberately launches subprocesses detached, which Bun maps
  to `setsid()`; this preserves per-child descendant-group termination but
  places children outside the pod's operator group. Normal runtime drain now
  awaits every retained execution-child exit before database detach, IPC
  disconnect reuses child shutdown, and the descriptor-derived pod environment
  enables Bun's existing no-orphans parent-death/descendant cleanup. Focused
  CLJS proof passes 54 tests/223 assertions and operator process proof passes
  61/314. A retained branch then closed normally while owning two execution
  children, including one retained across an earlier provider failure; both
  child PIDs and the pod PID were gone and the writer stayed ready. Abnormal
  parent loss is now checked on current source too. A demanded root render
  created execution child PID `42029` under Bun pod PID `62042`; direct
  `SIGKILL` of the pod removed both PIDs immediately despite their distinct
  process groups. Operator status classified the pod generation as `drained`,
  retained the ready writer and watcher, and ordinary `bin/seon up` recovered
  the pod with explicit `unexpected-exit` evidence. Focused execution-host
  proof passes 18 tests/83 assertions and operator process proof passes 61/314.
- The first post-fix namespace sample exposed persisted provider truth rather
  than a runtime failure. The source branch inherited `:openai-compat` with the
  Meta endpoint and `muse-spark-1.1`; that provider returned HTTP 402. The
  configured DeepSeek key fingerprint was the expected current key and was not
  selected. A disposable branch transaction selected Anthropic and retracted
  the incompatible endpoint attributes; the fixed namespace scenario then
  passed in 2:24 with accuracy 1.0 and fabrication 0.0. Native log:
  `src-inspect-ai/logs/2026-07-19T07-56-56-00-00_milestone-lift_jCpMNc4cVt8U5b7pdnKhbv.eval`.
- The formal fixed namespace run now passes three concurrent epochs: accuracy
  1.0, `pass_at_3` 1.0, fabrication 0.0, and no transport, database-feed,
  evidence-size, or lifecycle failure in 5:13. The first two completed agents
  used 19 turns/35 evals in 208 seconds and 26 turns/37 evals in 223 seconds;
  the slow tail confirms model work, not JVM serialization, dominates the
  variance. Native log:
  `src-inspect-ai/logs/2026-07-19T08-04-31-00-00_milestone-lift_LvFZXcaQxVu3r3bsjPBXUr.eval`.
- [x] Explicit inherited agents are process-hosted before `/agents/run`
  intake. A rebuilt non-autonomous branch opened root's turn immediately
  without a manual REPL resume.
- [x] Incomplete historical eval rows remain ordinary renderable data. The
  live root row exposed a nil-to-boolean instrumentation fault in
  `format-eval-row`; the boolean derivation and focused 8-test/19-assertion
  regression are committed at `3ee9b129`, and the rebuilt request advanced to
  the provider without retiring its execution child.
- [ ] Rerun root orchestration scoring with a valid provider credential. The
  2026-07-19 post-restart attempt reached Anthropic normally but received HTTP
  401 before model work; do not classify that external credential failure as
  product or scorer evidence.
- The first admitted live namespace battery now reaches the real scorer. Native
  log `…fxN7bWkJXsVehcJBqs9K3B.eval` completed all three samples with two
  passes, zero fabrication, and one `NaN` failure after repeated unquoted
  Datalog forms. The retained runs took 13–23 turns, 46–89 evals, and
  164.5–264.0 seconds. [[live-inspect-contract-audit-2026-07-19]] grounds the
  task-teaching, context-cost, and next database-scorer corrections.
- The last complete checkpoint before the most recent focused changes passed
  ClojureScript 1,140 tests/5,078 assertions, JVM writer 219/1,821, and operator
  278/1,570.
- Focused execution proof at `fdbac56e` passes 30 tests/118 assertions.
- Source-free release `/Users/sean/seon-release-fdbac56e` built with application
  digest `2cdc32903ca304013b56a6b688adfd06c8962e1177162cdc7290d625bdd79519`.
- Selected core failures have persisted database evidence before execution-child
  exit while the pod remained available for replacement children.
- The apparent reload loss of the `:seon.db/db` Malli schema was caused by a
  diagnostic call to the execution-child-only `load-authored-program!` inside
  the pod. That call intentionally replaces the process-local schema
  projection; clean cold start and ordinary watched reload retain
  `:seon.db/db`. Maintained Shadow now reports partial imports as failures.
- Three cold starts and three full supervised restarts against the same
  database completed ready with application digest `209b23e8…` and the
  `:seon.db/db` schema resolvable.
- Focused ticker/configuration proof passes agent-loop 17 tests/71 assertions,
  client initialization 7/23, runtime admission 16/94, and instrumentation
  delta 11/129. A deterministic live watchdog rejection persisted error entity
  `5907` with `:seon.error/fault :core` before the pod exited. The operator
  retained the watcher and writer and restored only the unexpectedly drained
  pod through the normal `up` path.
- The complete ClojureScript gate at `671e1777` passes 1,152 tests and 5,118
  assertions. Its first run exposed one canvas test that signaled completion
  before restoring a global database stub; the lifecycle fix removed all six
  downstream false failures.
- A demanded compiled root render spawned child PID `70423` with artifact digest
  `420939e7…`, exactly matching the immutable launch descriptor. A watched
  shared-source edit changed current client/execution outputs and made the
  operator target degraded, while the retained child continued to report the
  same exact admitted digest. Watched reload therefore never claims to replace
  the execution artifact; `bin/seon up` is the canonical cross-process cut.
- Commit `bef42a75` repairs four contracts exposed by the first complete live
  agent lifecycle: bounded execution diagnostics remain ordinary data,
  Datahike's installed-schema map is not mistaken for a database error,
  execution children reconcile the admitted Malli instrumentation projection,
  and `my.plan` keeps the database value on the allocation request rather than
  placing it in the pure transaction builder. The combined focused proof
  passes 52 tests/214 assertions.
- The same persisted agent `plain-chefs-do` moved through `my.units`,
  `my.convert`, and its home namespace; registered schemas; persisted and
  called the conversion functions; committed/query-read conversion facts; and
  used the exact earlier immutable database value in a later turn. It then
  redefined `my.units/celsius->fahrenheit` in place, persisted a passing test,
  and used the replacement from a fresh execution child.
- After a canonical pod restart, the same agent reconstructed its home
  namespace and admitted program without replaying historical forms, called
  `my.units/celsius->fahrenheit`, queried the retained conversion fact, and
  derived plan `yy9b6iocki7j` as done 1/1. The plan status proof also exposed
  and repaired a Datahike pull budget that had treated one entity as one pull
  result node; Datahike charges each pulled attribute and nested result node.
- Live malformed-source proof retained unterminated heredoc eval
  `cx8ookzmziks` with its exact source and read error while a database query
  proved `my.agent.plain-chefs-do/never-admitted` had no `:seon.fn` row. A
  later turn admitted the repaired definition and returned `:repaired` from
  eval `qmproratkoif`; failed source therefore cannot poison a fresh child or
  prevent the agent from repairing its program.
- The namespace-targeted slice now registers unique ref
  `:seon.agent/namespace`, reconciles existing agents to their generated home
  namespace, selects that database ref as the pre-eval starting namespace, and
  extends `start!`/`delegate!` with an optional namespace symbol. Focused
  multi-agent proof passes 8 tests/58 assertions; the Datahike bridge proof
  passes 14/81 and confirms the attribute compiles to
  `:db.type/ref` plus `:db.unique/value`; `:seon.agent/id` remains the sole
  agent upsert identity.
- The first populated-database migration exposed that the historical
  ordinary-agent existence query budgeted one intermediate result because its
  final result was scalar. Datahike rejected the 13-agent history, and startup
  had accepted the resulting error value. The query now has a bounded 4,096
  node allowance and autonomous startup fails on either database error values
  or explicit failed responses; focused client initialization passes 9/29.
- The first live namespace-targeted `delegate!` then falsified one-node scalar
  budgets on the new resident join and namespace-existence query. Both retain
  scalar output and small result-weight bounds but now admit 64 bounded
  intermediate relation nodes before the live retry.
- The next retries reached birth and proved that ordering does not let a lookup
  ref see an entity introduced in the same transaction: Datahike resolves it
  against `db-before`. Absent-namespace birth now shares one transaction-local
  negative integer tempid between the namespace row and agent ref, ordered namespace, agent,
  then initial message. Existing namespace source is still never rewritten.
- Fresh-database live proof created agent `blue-banks-swim` for
  `my.graduation.orders`. Its first completed turn evaluated `(str *ns*)` in
  `my.graduation.orders` and then entered the ordinary database-backed wait
  state. A second namespace-addressed `delegate!` returned the same immutable
  agent ID and delivered its second message rather than allocating an agent.
- Two simultaneous `delegate!` calls for the previously absent
  `my.graduation.concurrent` namespace both returned agent
  `violet-emus-create`. A database query found exactly one namespace resident
  and both distinct messages addressed to it. The stale-database-value retry
  therefore closes the concurrent create race through Datahike uniqueness
  rather than a process-local lock.
- `set-namespace!` now changes the one `:seon.agent/namespace` ref without
  changing the agent identity. The first live attempt exposed that selecting
  current namespace solely from the latest successful eval ignored a newer
  assignment. The one `seon.agent.home/current-ns` rule now compares the
  assignment transaction with the latest successful eval transaction. Live
  agent `blue-banks-swim` retained its earlier turns and messages, moved to
  `my.graduation.final`, and its next turn recorded successful eval
  `ons3bv55dk4r` in that namespace. Database queries found one agent identity,
  one current namespace ref, and no resident left on either prior namespace.
  Focused home, multi-agent, namespace, transcript, function-menu, and warning
  proof passes 37 tests and 187 assertions.
- Cross-child program propagation is live. Agent `blue-banks-swim` committed
  `my.graduation.shared/greeting`, registered
  `:my.graduation.shared/label`, and persisted passing test
  `my.graduation.shared-test/greeting-returns-prefixed-label`. Independent
  agent `violet-emus-create` did not redefine the function; eval
  `erg4qqhh0vc3` called it from `my.graduation.concurrent` and returned exact
  result `"shared:peer"`.
- The same adversarial journey exposed two core faults after the successful
  cross-child call. `grep-graph` consumed typed `execute-many` member envelopes
  as query rows, causing its own output validator to reject keyword
  `:datahike.query/result`; the owner now unwraps successful member results and
  focused search/execution/host proof passes 77 tests/330 assertions. A later
  replacement-child invocation rejected a non-eager parent value without
  retaining its structural path. IPC encoding and host error projection now
  retain an ordinary path/type diagnostic. Exact reproduction identified a
  nested eager `cljs.core/Cons` created by the ClojureScript `#(...)` reader
  expansion. `parse-forms` now materializes reader-produced seqs as persistent
  lists while the IPC predicate remains strict. Focused parser, search,
  execution, and host proof passes 116 tests/660 assertions. Live turn
  `kdhmf2xb6rih` ran that exact anonymous-function form in 233 ms, called the
  shared function with exact result `"shared:ordinary-ipc"` in 218 ms, and
  entered `wait` without a core fault.
- The first three-agent application drive then exposed two product-contract
  defects. An artificially low eight-turn run bound let all three children
  exhaust their work while searching source after the documented map form of
  `my.ns/functions` passed an omitted database as nil. That function now
  captures one current database value and uses map-based query/pull requests;
  focused proof passes 5 tests/37 assertions and the live omitted-database call
  succeeds. Root's attempted repair used `seon.agent.fs/edit-file`, duplicated
  the function head, and produced an unbalanced tracked file. Fail-loud Shadow
  publication correctly persisted the core fault and drained the pod, but the
  editor had already replaced valid bytes. Every Clojure-family mutation now
  parses the proposed complete file before writing. Focused filesystem proof
  passes 35/155; a live malformed edit returns an ordinary error, preserves the
  exact prior SHA, restores the read-only grant, and leaves the pod ready.
- Commit `c0cc1f80` binds changed-test selection to the manifest's program
  source path and digest instead of testing only the runtime file that happened
  to trigger the hook. Focused operator and program-index proof passes 17
  tests/119 assertions.
- The complete Inspect offline suite passes 523 tests with eight intentional
  skips. Commits `c007ef49` and `ba95bfa1` close a false-green proof fixture:
  every oracle arm now asserts its expected metric, decimal weights use the
  declared `:double` schema, and generated integer variants explicitly retain
  `:int`. Commit `cc230208` adds native database-derived scenarios for
  namespace-targeted residency, cross-agent reuse and repair, child recovery,
  and pod restart. The real 24-arm offline proof reports exact means for every
  expected success and failure arm; live execution still requires the one
  ownership-fenced cluster lease and typed final database read-back.
- The browser/Datastar graduation audit is recorded in
  [[browser-datastar-graduation-matrix-2026-07-19]]. It identifies the existing
  one-feed behavior and the smallest missing product proofs: namespace plus
  initial-message creation from `/agents`, visible selected-call validation,
  rapid-submit cancellation, semantic feed sharing, reconnect, and slow-client
  isolation. These remain acceptance work, not a reason to add another route,
  renderer, or reactive channel.
- Commit `81070ae9` closes the HTTP half of namespace-targeted browser birth.
  Existing `POST /agents` fields translate directly to `start!` or atomic
  `delegate!` under root's agent scope; malformed namespace symbols return 422
  before lifecycle work. Focused proof passes 20 tests/82 assertions. The root
  view still needs its dedicated namespace and initial-message controls before
  the browser checkbox can close.
- The live application drive exposed that the normal operator's repository
  grant was read-only but unlocked: root changed the process-global grant and
  rewrote `src/my/ns.cljs`. Commit `4f0d3045` makes the one normal operator set
  both `SEON_FS_READ_ONLY=1` and `SEON_FS_LOCK=1`. Focused proof passes 7
  tests/47 assertions. The rebuilt pod reported a locked read-only grant,
  refused both reconfiguration and the attempted edit, and retained the exact
  committed file.
- The resumed fulfillment agent called `my.customers/display-name` in eval
  `e2w2ay0f5drr` and `my.orders/total-cents` in eval `p8j78ybzf2jk`. It then
  reproduced the vector-only integration defect with an ordinary list and set,
  moved into `my.orders`, redefined that same function with a sequential input
  schema in eval `xwlhot0s684z`, and ran its attached test through the one test
  runner in eval `xjbefkcupnu7`. This is cross-child reuse and cross-namespace
  repair in place, not a parallel function.
- The same fulfillment agent then defined and tested
  `my.fulfillment/summary`, transacted and query-read
  `fulfillment-001` as ready, notified both peers through messages
  `auiwcn4q81l3` and `gyee3abcjuxr`, completed its database-backed plan, and
  waited. The deliberate source `(js/process.exit 1)` retired child PID 11097
  and its one automatic replacement PID 18205. Database turns
  `fazjdpbih5jn` and `nuywq50kf80e` both retain the exact failed eval source
  with status `:interrupted`; the pod and writer remained responsive. A later
  human message started a fresh child, whose eval `mk34zokwc12g` called the
  shared summary with exact result `"Ada Lovelace: 3249 cents"`, eval
  `qifgkhbcwvqr` read the fulfillment status as ready, and the agent returned
  to `wait`.
- [[deterministic-core-fault-boundary-audit-2026-07-19]] finds the older
  six-boundary checkbox stale. Ticker, reload, startup/publication,
  selected-call, and top-level compiled-child paths already have focused and
  exact live evidence. The earliest remaining contract is exact-artifact
  persist-before-exit proof for a generic core render failure.
- Commit `b76ff42f` adds the focused generic-render contract through the real
  core-error configuration and ordinary database hook: the operation-selected
  policy is used and exactly one core fault transaction is requested. The
  coordinated artifact still needs the documented temporary render fault,
  record-before-exit observation, pod-only recovery, and fault removal.
- Commit `03afd53b` closes the root web UI source gap with one form for optional
  namespace, purpose, and initial message posting to the existing `POST
  /agents` path. It remains in the one root shim and one database-driven feed;
  focused Datastar proof passes 16 tests and 70 assertions. Live browser and
  database read-back remain the graduation evidence.
- The last exact-artifact fault cut now passes. Generic render fault entity
  `17690` committed in transaction `536874764` at
  `2026-07-19T06:26:10.375Z`; the pod observed its child's exit 234
  milliseconds later and remained ready. After the temporary fault was
  removed, the same agent rendered its retained history and completed exact
  reply `clean render recovered` from a fresh child in 9.34 seconds.

## Execution ledger

### 1. Deterministic startup, reload, and fail-loud development

- [x] Reproduce the missing `:seon.db/db` schema from a clean start and identify
  it as an unsupported execution-child loader call that replaced the pod's
  process-local schema projection; prove the supported cold/reload path retains
  the schema.
- [x] Make schema and program publication atomic: ready with the complete
  admitted program or recorded core fault plus process exit. Maintained Shadow
  now reports caught Node import failures truthfully, and admission permits the
  next build to recover from `:unavailable`. Live proof rejected a guarded
  `seon.log` import, performed no rehost/ticker install, then committed all 754
  functions and returned the same pod to ready on the repaired next build.
  Execution children admit only the digest-verified immutable launch artifact;
  changed watched outputs degrade the target until canonical republish rather
  than silently changing a live child.
- [x] Apply the database-selected core-fault policy
  consistently at ticker, reload, publication, render, selected-call, and
  top-level child boundaries. The ticker now retains the already-acquired
  configuration and has exact persist-before-exit and pod-only recovery proof.
  The failed-import run exposed the same missing scope in Shadow failure
  notification; reload now acquires the database configuration before recording
  either build failure or publication failure. Exact watched proof persisted
  core-fault entity `5923`, exited under `:crash`, retained watcher/writer, and
  restored only the pod through normal `up`. The generic-render artifact then
  committed its core error before child exit and recovered cleanly after the
  temporary fault was removed.
- [x] Prove three cold starts and three pod restarts against the same database.
- [x] Inject one deterministic core failure at each affected process boundary;
  prove the database record precedes exit and the supervisor restores only the
  replaceable process.

Exit: no alive-but-unready pod, repeated ticker fault, incomplete application,
missing schema, or silently rendered core failure.

### 2. One complete restart-safe agent lifecycle

- [x] Create one agent, deliver a message, and move through `my.units`,
  `my.convert`, and its home namespace using normal CLJS namespace operations.
  Agent `plain-chefs-do` and message `zil4x609qvr4` were persisted, but the
  first turn exposed an execution-result diagnostic that retained a rejected
  host map key and therefore made its own bounded agent error invalid for IPC.
  The interrupted turn `twu48whmx73j` and crashed run `c4wuf8lqotfw` are the
  durable recovery starting point. The same agent then exposed and survived a
  second core defect: Datahike's installed-schema map was mistaken for an error
  merely because it contains the installed `:seon.error/message` attribute.
  After that fix it ran eight stable turns, revealing that self-host eval
  callbacks had lost the explicit agent ALS scope: `my.plan/step!` could not
  inject `:seon.agent/id`. The eval-batch owner now re-establishes that scope;
  focused home, execution, and eval-receipt proof passes 50 tests/190
  assertions. A live retry proved the agent scope itself was present—direct
  `seon.db/current-agent-id` returned `"plain-chefs-do"`—but `my.plan/step!`
  still received no injection. The execution child had activated the program
  projection without reconciling its instrumentation wrappers. Program load
  now performs the same complete wrapper reconciliation as runtime admission;
  focused program-load, receipt, home, and execution proof passes 57 tests/219
  assertions. The next live retry proved wrapper injection: the same ordinary
  `my.plan/step!` advanced past agent resolution, then exposed that `my.plan`
  placed the database value inside the generated-ID builder instead of on the
  allocator request. The allocator seam is repaired and focused plan,
  allocator, program-load, and receipt proof passes 52 tests/214 assertions.
  After the canonical build, the same persisted agent completed plan step
  `yy9b6iocki7j`, moved through `my.units` and `my.convert`, registered schemas,
  persisted both conversion functions, called them successfully from a fresh
  execution child, and committed/query-read conversion facts.
- [x] Register schemas, define functions and tests, transact data, and query it
  in a later turn from the exact earlier immutable database value.
- [x] Redefine the function in place and prove a fresh child uses the latest
  admitted namespace source without replaying historical forms.
- [x] Reject invalid source from the admitted program while retaining its failed
  eval evidence and a functioning repair path.
- [x] Stop the child, restart the pod, resume the same agent, plan, namespace,
  messages, and database facts, and continue successfully.

Exit: the live journey and focused tests agree on namespace, program, database,
and recovery semantics.

### 3. Namespace-targeted agents and messaging

- [x] Source-ground the smallest database representation connecting an agent to
  the `:seon.ns/name` it is asked to steward. Do not rename `:seon.agent/id` or
  treat stewardship as code ownership.
- [x] Extend the existing `start!` and `delegate!` lifecycle requests with the
  optional namespace through one atomic child-birth/message transaction.
- [x] Resolve a message addressed to a namespace to its active steward; when no
  steward exists, atomically create one and deliver the message.
- [x] Prove two concurrent assignments produce one active steward, while every
  agent remains free to inspect and repair every namespace.
- [x] Prove reassignment changes ordinary database facts without duplicating
  agents, program entities, runs, turns, plans, or messages.
- [x] Prove newly committed functions, schemas, and tests become available to
  every relevant fresh child through the one program mechanism.

Exit: root can launch, find, message, stop, resume, and reassign agents by
namespace while immutable agent IDs preserve history and refs.

### 4. Live multi-agent application journey

- [x] Root delegates `my.orders`, `my.customers`, and `my.fulfillment` work to
  separate agents.
- [x] Agents exchange database-backed messages with explicit from/to refs.
- [x] One agent uses functions written by another; a different agent finds and
  fixes a defect in that namespace without creating a parallel function.
- [x] Kill one execution child during work, record the failed
  turn/eval evidence, replace it once, and continue from database state.
- [x] Restart the pod between phases and complete the integrated application.

Exit: database queries prove agent, parent, run, turn, message, namespace,
function, schema, test, transaction, and resumed-plan relationships; transcript
prose is supporting evidence only.

### 5. Inspect AI graduation

- [x] Pass the complete offline `src-inspect-ai` tests and oracle liveness proof.
- [x] Separate ordinary database acquisition from database-advanced delivery;
  keep the pod subscribed and let execution children decline unconsumed events.
- [x] Preserve every structurally bounded model-attempt row instead of applying
  a render display cap to formal Inspect evidence.
- [x] Prove normal retained-branch close leaves no execution child or database
  acquisition after the awaited host drain.
- [x] Prove abnormal pod TERM/KILL leaves no execution child or descendant
  through Bun no-orphans cleanup.
- [x] Pass three consecutive fixed live namespace epochs without fabrication.
- [x] Pass the fixed live namespace and later-turn database-memory scenarios.
- [x] Pass a seeded generated database-memory row against dynamic attributes
  and the typed final database query.
- [ ] Pass generated namespace and database variants without adding scorer
  exceptions for model answers.
- [ ] Graduate plan persistence across pod restart with the same agent.
- [ ] Add and pass namespace-targeted launch, cross-agent reuse/repair, child
  crash/recovery, and pod restart scenarios using database-derived evidence.
- [ ] Retain native Inspect logs and append the scorecard with model provenance,
  mean, pass@k, latency, token usage, and classified infrastructure failures.
- [ ] Require three consecutive fixed-scenario passes and at least four of five
  generated variants per scenario; deterministic infrastructure must pass every
  run.

Exit: Inspect AI drives the real pod door and scores durable facts/evals rather
than a special harness runtime or transcript regex.

### 6. Browser and Datastar graduation

- [ ] Root launches and messages a namespace-targeted agent from the web UI.
- [ ] Verify reactive agent, status, message, plan, canvas, and error changes
  without page reload.
- [ ] Exercise button, input, select, toggle, validation, rapid submission,
  focus preservation, and database read-back.
- [ ] Verify gzip SSE reconnect and tool reconnect after child and pod restart.
- [ ] Prove identical active renders share computation where function,
  arguments, and database value match; a slow client does not block a fast one.
- [x] Run multiple browser tabs and concurrent feeds without duplicate actions,
  stale output, Promise rendering, console errors, or leaked interests.

Current-source live proof created three idle namespace agents through the web
UI. Two independent root tabs each moved from 9 to 10 agents after one POST,
rendered the same database-backed purpose, and retained independent canvas/plan
selection. After a clean supervised restart both tabs reconnected and each
received the next database update, moving from 10 to 11 agents without reload.
The rebuild interval produced Datastar's expected network/retry diagnostics and
backed off to 30 seconds before reconnecting; post-reconnect morph delivery is
the acceptance signal. Root, agent, and database server-side clients each
received `datastar-patch-elements`; browser pages reported no application
console faults before the intentional restart.

Exit: the real browser journey and server-side gzip client agree on one reactive
render/feed mechanism.

### 7. Complete correctness and distribution gates

- [x] Complete ClojureScript suite.
- [x] Complete JVM writer suite.
- [x] Complete operator suite.
- [ ] Complete Inspect AI Python suite and live scenarios.
- [x] Concurrent independent-cluster isolation and restart.
- [ ] ACME downstream application journey.
- [x] Source-free immutable release, restart/read-back, unchanged recursive
  digest, and clean shutdown with no surviving child.
- [ ] Delete obsolete code and tests revealed by the integrated proof; do not
  preserve compatibility mechanisms.

Current-source complete correctness evidence is CLJS 1,179 tests/5,254
assertions, JVM writer 221/1,833, and operator 280/1,582. The complete offline
Inspect gate passes 521 tests with eight environment-gated skips. Its one first
run failure was a stale frozen-tool fixture using retired database wrapper
fields; the current ordinary database-value and model-transport contract now
passes. The combined Inspect checkbox remains open until its provider-backed
live scenario matrix also graduates.

Source-free application `ae860bc4522ec10b21a6a9bf14cd55737598cbcd2c92c90819258bfd37b2ba6c`
from commit `be5bd11c` passed from `/Users/sean/seon-release-be5bd11c` with the
producer development cluster down. The release-owned JVM writer and Bun pod
reached readiness, served `/` and `/data`, and created agent
`nine-lands-tickle` with purpose `release-persistence-proof` through the
ordinary `POST /agents` route. The root Datastar feed rendered that committed
fact before and after a clean writer/pod restart. Normal shutdown left both
release processes absent. The recursive package digest remained exactly
`8dbbefab82993fd712781ab9af5fd8f91e3fab54bedb7e8ed13cb120ebf67a47`
before startup, while running, after restart, and after shutdown.

The current default and ACME clusters also passed concurrent isolation. The
first ACME build exposed and fixed a real ownership error: a changed writer
artifact tried to send a local stop transition for ACME's external JVM writer.
Commit `d15097fa` now limits `rebuild-writer` to a live writer owned by the
selected target; focused operator proof passes 38 tests/100 assertions. ACME
then restarted its watcher and pod while default watcher PID `61610`, writer
PID `61939`, and pod PID `62040` remained unchanged and ready. ACME agent
`curly-lizards-shop` survived the restart and its feed rendered afterward;
normal ACME shutdown retired only its watcher and pod. The provider-backed
downstream application journey remains open with the live Inspect matrix.

Exit: one exact source revision passes every maintained gate and product journey.

### 8. Architecture-level performance and modest-hardware proof

- [ ] Measure direct JVM reads against Bun→JVM→Bun reads for cold and cached
  query, pull, entity, and index access.
- [ ] Measure identical queries over one database value from 1/2/4/8 children,
  including shared computation and serialization costs.
- [ ] Measure transaction latency, committed database-value propagation,
  execution-child cold/warm start, and program-delta acquisition.
- [ ] Measure Datastar first render, database-update render, and 1/10/50/100
  simultaneous feeds.
- [ ] Measure private memory, proportional set size, retained heap, idle CPU,
  event-loop delay, and reclamation after warm timeout and termination.
- [ ] Optimize only material measured architecture costs; rerun correctness and
  live gates after every accepted simplification.

Exit: the completed architecture has explicit latency and resource evidence on
modest hardware, with no micro-optimization displacing a correctness boundary.

## Scheduling clock

- **Ordered spine:** section 5's fixed live Inspect namespace,
  database-memory, planning,
  multi-agent, and failure-recovery scenarios. The complete section 4 journey
  already has database-derived live proof.
- **Integrated proof:** three namespace-targeted agents exchange database-backed
  messages, reuse and repair one shared program, survive one child replacement
  and one pod restart, and complete from database state.
- **Dependency-ready parallel portfolio:** Inspect offline verification is
  green with metric-bearing oracles; its isolated branch lease plus typed
  product database read is being closed. The root browser control is committed
  and its live browser/feed proof remains independent of section 1's temporary
  render fault cut.
- **Next refill:** after the fixed live Inspect scenarios close, run their
  generated variants and repeated pass thresholds while browser proof advances.
- **Final graduation gate:** sections 1–8 are checked against one exact source
  revision, including live Inspect, browser, downstream, package, and measured
  modest-hardware evidence.

## Progress rule

After every material commit, live discovery, returned lane, or complete gate:

1. update the relevant checkbox and exact evidence here;
2. record any changed dependency or acceptance boundary;
3. keep exactly one earliest unsettled contract in progress;
4. update the program roadmap if order or graduation evidence changed; and
5. commit the coherent documentation update with the owning source or proof.

Do not mark a section complete from focused tests alone. A complete section has
its named live evidence and leaves the next section dependency-ready.
