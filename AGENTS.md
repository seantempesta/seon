# Seon — shared instructions

Seon is a Clojure system in which agents write, test and improve their own code in a running JVM. Schemas make data
storable and queryable; functions and declared renders make it executable and visible. Build toward a codebase of
10,000 lines or fewer and sub-second ordinary operations.

This file (`CLAUDE.md` is a symlink) holds laws and how to work, revised in place, never appended to. Mechanisms
live in [architecture](docs/seon/architecture/README.md) and `.agents/skills`, decisions in the owning spec, history
in Git. Read the assignment, nearest instructions and named authorities end to end. Verify targets against the
running system: a specification or source citation is not evidence of installation; never claim a target API exists.

## The perspective: an agent at its REPL

Everything is modeled from a Seon agent's side: its task, its context, its REPL, its branch. Our outside agents are
Seon agents too: they use the same branch, REPL entrance, checks and reviewed merge, and the orchestrator acts as
root.

- **Core is composable.** One shared database (stable values, one writer, cheap branches), one shared SCI runtime (a
  base context each agent forks into its own world) and render functions over data; each agent is its own
  concurrent Flow graph. Parts compose as functions and stage values, never as parallel systems. MCP, the test
  harness and the web are views that enter through the agent's path and render its values; they hold no second
  model, evaluator, printer or store.
- **There is one REPL path**: read → evaluate → record → print, from the agent's side of the prompt: forms are read
  once, SCI evaluates them, the facts come from what the evaluation observed (with clj-kondo's analysis of the same
  text joined by exact source span), and one transaction records it all. Everything uses it — product agents,
  outside agents through MCP, tests through the harness, render forms in context assembly (and the debug page,
  which only displays that assembly), schema registration, gates and merge; none keeps its own reader, evaluator,
  printer or recording. An entrant needing another reader or
  evaluator (JVM mode, a file, a test body) supplies that stage as a value to the same pipeline. A form is parsed
  once and evaluated once; its results and facts are stored and queryable. Everything before the print is a real
  value under its Malli schema — live in the runtime, a database fact or a blob — never a string built to be
  clipped. The pipeline is: text in (prose becomes comments and delimiters are repaired where unambiguous) → forms
  read and evaluated → results and facts stored → at the edge, the value's schema selects its most specific render,
  if any → the value printer. Every shown value passes through the value printer, render output included.
- **The path is total.** It always returns the evaluated value. A later stage's failure (gate, renderer, recording,
  classification) is attached beside the value as a diagnostic and recorded as a fault; it never replaces, blocks or
  wedges the value.
- **Agents change the program in REPL terms**: `defn` inside the target namespace (after `in-ns`), as in Clojure,
  `my.program/ns-unmap!` and `my.program/remove-ns!` to delete. Files are the write-back of accepted entities, not the
  working medium. Product agents use declared evaluation, adoption and test requests, never shell self-modification.

## How to think

- **Simple and correct first, then optimize.** The algorithmic choice has the biggest gains: ask what the work is
  proportional to and prefer the approach that does only the work actually needed (lazy over eager, what is shown
  over what might be). Implement it in the simplest correct form you can think of; add complexity only for a
  measured, large performance win. Optimize for the common path, not the rare failure recovery already handles.
- **Take things apart.** Give each part one role and one reason to change; separate braided concerns.
- **Be patient at the REPL.** State the problem, knowns and unknowns; inspect real values and call the owner until
  you can predict the result. Refuting a cause is progress. An estimated cost or a suspected cause is a hypothesis
  until measured on real data: profile before ranking work, and check a surprising observation is not the test's own.
- **Read the dependency first.** Datahike owns transactions, branches and history; Malli owns compiled schemas and
  contracts; SCI owns contexts; core.async owns graphs; konserve owns storage and reclamation. Compose their
  mechanisms instead of copying them; use a highly regarded library for tricky parts.
- **Write small functions of data.** Plain namespaced maps, immutable transformations, core functions and library
  calls. Split long functions by responsibility; avoid bespoke objects, protocols and accessor layers that hide
  information.
- **Fix the producer, not each symptom.** Verify the cause before naming it. A workaround, exemption, fallback or
  patch elsewhere means: return to the data flow and find the owner.
- **Prefer values to places.** Derive from held immutable values; arguments and channels remove hidden order. Atoms
  are a smell: state lives colocated in its owner's value (database facts, the Flow proc's state, the SCI context,
  the dependency's cache); an atom needs a stated reason no owner holds it. Live host objects with no data form
  (Flow graphs, channels, executors, SCI contexts, listeners) live in exactly one roster per scope (process, then
  cluster execution), keyed by stable ids, passed explicitly and observable in status; admission and retirement are
  writer-checked facts. Per-call accumulation is a fold.
- **Grow by accretion.** Require no more, promise no less: open maps, validated declared members, optional additions
  and new names for changed semantics.
- **Make failure local and loud.** Each part fails without corrupting its neighbours or blinding diagnosis. Never
  read silence, missing subjects or stale observations as health.
- **Add no hypothetical restrictions.** A restriction names the correctness failure it prevents or is deleted.

## How to work

1. **Understand.** Read the owning spec, Git history, dependency source and first-party caller before designing.
   Record the pinned revision, `file:line`, guarantee, inputs, recomputation event and cost. Verify a seam exists
   upstream before calling it the library's; prefer upstream, improving a maintained fork only to remove real
   duplication. For validation, contracts, instrumentation and error shapes, begin in
   `reference-code/malli/src/malli` and cite the Malli function used. Load
   [data-oriented-clojure](.agents/skills/data-oriented-clojure/SKILL.md) before Clojure design or edits, and the
   REPL, testing, datahike, modeling and flow skills at their boundaries. Verify touched skill claims against
   `file:line`; a stale skill is a high-priority defect; delete unverifiable claims, never hedge them.
2. **Probe.** Start Clojure changes with `bin/seon status` and MCP `runtime_status`; report and record degraded or
   missing tools first. Use `eval_clj` with explicit root and cluster; JVM mode has no custody, so pass the
   connection, e.g. `(seon.cluster.boot/connection "default")`. SCI mode mutates its shared context: keep probes
   disposable, in scratch namespaces or branches with finite inputs. Reproduce with one small form, inspect the
   complete envelope and installed schema, call the owner, then try the smaller algorithm. On real data: parse,
   transform, validate each value against its schema, store it (disposable branch or `d/with`), pull it back and
   compare. Try data models and ideas at the REPL before writing them down. Every reply is also a health check: read
   its timing, diagnostics and faults. An error, a panic instead of an error value, a fault without a linked task, or
   an ordinary operation over one second is a finding. Record it as a task, even when it is outside your
   assignment. Record exact forms, values and timings; the edge cases found become the regressions.
3. **Design the smallest composition.** Open the owning spec or landing note with one or two lines naming what
   exists, its data flow and the principal Clojure approach. State what disappears, each operation's cost and target
   complexity, the concerns separated, and the simplest alternative: dependency seam, existing function or deletion.
   Research existing libraries before writing a replacement. A change's added lines are justified by the duplicate
   paths it deletes in the same cut, named with line counts. More than about 100 added lines without matching
   deletions is presumed wrong: stop before writing and report the smaller alternative. Net deletion does not excuse
   new machinery; code that still grows is justified to the owner before integration: what it adds, why nothing
   smaller works, what it lets go later.
4. **Opus builds at the REPL; Astra profiles and fixes.**
   - **Opus designs and implements by dogfooding.** In its own Seon branch it tries alternatives at the REPL (defn,
     the tests reaching the change, timings), keeps what worked, and proposes it through the ordinary merge;
     write-back then updates only the files that changed.
   - **Astra, an outside model trained on a different distribution, follows each landed slice.** It profiles the
     slice at the REPL and FIXES what it finds directly, with no review round to wait on: super-linear
     transformations (everything O(n) or O(n log n) in what changed), repeated work (the same derivation executed
     thousands of times, whole-program work per change, re-reading what the caller holds), and correctness or law
     violations.
   - **Both refuse bad changes on sight,** so feedback is immediate and designs iterate fast.
   - **Root** assigns file ownership so the two never edit the same file at once, rules on disputes, and resolves
     already-ruled questions by citing their authority. For a new decision with hours of cross-owner work, root
     offers three priced options: the simplest viable first, the recommendation marked, each with its guarantee,
     cost and sacrifice.
5. **Change one loadable slice.** Edit the owner in place: no second registry, renderer, runner, retry, cache or
   version-two namespace. A moved, renamed or deleted function converts every caller in the same commit — tests,
   quoted symbols, error operations and schema references included — and retires the old path. Script mechanical
   sweeps once across the full set, then lint, load and hand-fix exceptions. HEAD must boot: before committing, lint
   `src script test` with `--cache false` and compile touched contracts, schema and configuration resources with
   their consumers (shipped defaults included) from a `git archive` of HEAD plus the diff, with zero findings.
   A change to schema resources, publication, indexing, adoption, boot or arming is proven by publishing and adopting
   it on a branch of the live store: a fresh install is adoption onto an empty store, the same path, never a second
   one to rehearse. Root's batched `start --head` restores the previous commit when a new JVM cannot boot.
   Designs and plans are revised in place — corrections go into their owning sections, never a parallel spec or
   appended ruling log. Correct false current claims in the same commit; keep dated audits and evidence outside plans.
6. **Prove the installed change.** Verify publication, adoption, reload and arming, then repeat the probe. A shell
   edit or disabled hook proves none of these; neither does restarting to find out. Prove at the REPL and statically
   first; root batches one restart for many landings. Prove schema changes incrementally on a branch of a live store;
   inability to adopt is a publication defect, never a reason to boot from zero. Observe browser paint separately.
7. **Report honestly.** Broken things first, then evidence, changed paths, commit, sizes, net source/test lines,
   timings, memory and verification limits in the landing note. Unavailable evidence is not a pass. Commit
   measurement scripts; publication changes need their clock row. Disposable probes go in `tmp/`, reusable checks in
   `test/`. Search existing issues before recording one per defect class; evidence must outlive chat. The issue
   index is the owner's ranked schedule: assigned agents never edit it.

## The system: one JVM, many realities

- **Clusters and branches.** A cluster is a Datahike branch plus its agents and shared plumbing; one JVM hosts many.
  A branch is `d/branch!`, a pointer to shared immutable indexes, never a store copy or a system to boot. **Unlink**
  removes a branch from the roster; retention GC reclaims it without per-branch cleanup.
- **Boot and configuration.** Boot opens the REPL before store acquisition, then builds process → store → facts →
  flow, publishing readiness at each layer. One process root holds the store's lifetime lock; Datahike serializes
  transactions. Bootstrap selects paths and binds; running configuration is database facts; secrets are
  environment-variable names. `seon.config/defaults` is a compiled constant, not a cluster's configuration. Boot
  reads each dial from the configuration it applied, never the store's pre-adoption value, so a new dial exists at
  its first read.
- **The program is entities**: functions, tests, schemas, namespaces, render pairs, contracts, analysis and test
  evidence keyed by exercised definition digests. Turns, evaluations, messages, errors, tasks and live results are
  data and never merge as program; declare that partition on entity schemas, not in a hand list. Default's program
  entities and loaded namespaces derive from files. A JVM has one set of compiled Vars; other branches interpret
  differing entities and their affected callers in SCI and bind compiled Vars for the rest. A branch never changes
  the platform: compiled (host-bound) code, the evaluator, printer and test runner included, keeps running as
  compiled and working on every branch, and sees a change only after merge and reload; what the branch evaluates
  from source sees its definitions. Compute host-bound status per declaration, never from a namespace roster.
- **An agent's context** is its SCI world; its task selects custody of the live cluster head or its own branch.
  Every agent, filesystem agents on their own named branches included, uses the one REPL entrance. Private defs,
  atoms and objects survive program changes advanced in place at idle boundaries; each turn reacquires from the
  branch head, reusing content-valid derivations.
- **Reload and adoption.** A reload is `require :reload` for changed namespaces and their dependents. Adoption goes
  entities → load → arm → record and waits for no one. Stability comes from branch custody: work that overlaps a reload
  compares the loaded program identity at start and end and pushes back, never takes a lock. Failure leaves affected
  execution unavailable until exact bytes, behavior and arming converge; never call an unchanged record healthy.
  Ordinary clusters keep their program; selected development clusters adopt explicitly.
- **Merge is a root task.** An agent proposes only after the tests naming its task pass on its branch; the proposal
  becomes a merge task. Root works it through the ordinary REPL holding both worlds — a merge branch forked from the
  cluster head and a copy of the agent's world — with easy tools to move data between them, and chooses exactly what
  to take by identity from the diff value; the task never lists what to merge. Acceptance requires the task's tests
  to pass on the combined program plus whatever root chose to run, recorded as evidence; reaching-test counts are
  shown, not gated. Conflicts stay on the merge branch for repair. The writer checks the touched entities' digests; only
  root's acceptance advances the cluster pointer, and the agent's branch retires when its merge closes. Local green
  never implies shared acceptance.
- **Write-back** uses the same gate, checks file bases, stages exact-span changes and reindexes to prove equivalent
  declarations before installation.

## Data and contracts

- **Model from the agent outward.** The agent's record is its one-hop attachments (session, plan, settings, messages,
  tasks), each with a declared render; context is rendering what is attached, detail one requery away. Tell a story, not
  a star: agent → session (current namespace, ordered turns) → evaluations; namespace information lives on the namespace,
  agent information on the agent. Every stored fact names its consumer or is deleted; hiding it behind a filter or a
  missing render is not a fix. Choose each relationship deliberately: component, optional or required peer ref, identity
  value, or a settlement-moved edge; a statement about a living entity is a ref, an observed name is a value; a component
  is validated as its whole owning value on each write, so size trees by what each write owns.
- **Custody is the default world, not a wall.** Agents are cooperative; nothing is isolated for security. An
  evaluation's custody (its branch connection) is where its calls read and write by default, so each agent works in
  its own scratch space (branch and SCI fork) and only what root deliberately merges reaches the cluster. Root's own
  context is its cluster. Reaching another branch, any program function or any context on purpose is allowed; what a
  prompt shows neither grants nor limits execution, and consumer domains stay downstream. Agents may make serious
  changes, first-party code included: the immutable database and rollback are the safety net. What each kind of
  entrant receives (database value, connection, branch, SCI fork, agent id, task, capabilities) and where its writes
  land is declared once in the owning spec, never implied.
- **Pass the world explicitly.** Carry database/connection, projection, environment, render profile, proc and
  settlement inputs, including profiling and test ownership. Custody elides only the agent's db/conn; it authorizes
  no other ambient service.
- **Supply defaults by declared schema name, from the world.** The armed wrapper plans each function's supply once at
  arm time and fills omitted inputs at call time, never from the parse (a call's target is known only when it runs).
  Any input, required or optional, whose declared schema is a member of the evaluation's world (database, connection,
  writer, agent, task, profile, call sink, SCI context) comes from that evaluation's own world, never a global or
  another world; this includes world-member keys missing from a map argument whose schema declares them. Supply runs
  before input validation, so a forgotten world member never fails the call, and it only fills what is missing: a
  value the caller passes, such as another branch's database, is never overwritten. Other omitted optional inputs stay
  absent. A function whose meaning needs a world member to be absent
  (no custody) declares a different schema name. A missing required member refuses by name. A call already matching a
  declared arity is never prefixed; caller arguments always win; structural resemblance is not identity.
  See the [data guide](docs/seon/architecture/data-modeling-guide.md).
- **Decide at the authority.** Derive at the writer or supply its decision; validate a pre-read's basis there if it
  could change. Mirrors are derived, drift-checked or explicitly historical, never silently current state.
- **Declare facts at their producer.** Search the registry before adding a key. Use fully namespaced keys, one
  schema per identity and `seon.id`; identities are minted by their owner (a task's handle by ingestion), never
  hand-typed; no name guessing, text parsing or invented entity kinds. Production regex needs
  owner permission (ordinary `rg` searches do not). Enums describe real bounded states or dependency grammar.
- **Preserve native values.** Absence is no key, never stored nil; symbols stay Datahike symbols, not strings
  converted at each reader. Prefer `find` or `get` with a sentinel; `contains?` tests vector indices and also finds
  nil-valued map keys.
- **Validate final owning values.** Include swept refs and identity-less components; refuse missing children,
  cycles, multiple owners and exhausted bounds. Admission and completeness rules judge writes; reading committed
  entities never re-applies them. Retraction deletes; history answers what existed, as it was written. Required refs
  refuse invalidating deletion, optional refs may sweep, components cascade. Repair surviving declaration references
  in the same transaction or refuse; never invent placeholder functions or tests.
- **Separate storage from observation.** Store a value when it must outlive its subject. Derive pulled shapes and
  attribute lists from entity schemas and selectors: a hand list beside a schema silently drops the next key. Never
  silently truncate pulls. Require each function's and test's analysis digest:
  an empty cardinality-many is not proof of work.
- **Use the database owner.** Direct `datahike.api` calls belong only in `seon.db`, store/registry, classified
  branch-custody owners and system listeners; keep `my.*` thin. Put provenance on transactions, derive write bounds
  from it and retain proposed data in bounded refusals. Reconcile config differences; resolve function symbols to
  current entities.
- **Contract every admitted function, private ones included.** Complete, well-formed, fully namespaced Malli schemas
  and reaching tests. Compile touched contracts against the packaged projection; use declared schemas or registered
  named predicates, never anonymous contract functions or incomplete forms. Justify polymorphic boundaries. Every
  bug fix adds or tightens the contract at the producer of the bad value, so that class of bug is refused at its
  source from then on. Existing uncontracted code is not back-filled wholesale: much of it is headed for deletion.
  A new or changed function without a complete contract: the edit hook refuses it; the REPL admits it with a
  warning beside its value that grows as the agent's uncontracted definitions accumulate; merge acceptance refuses
  a proposal that carries one.
- **Keep entry policy separate from merge.** Schema and test entry default to `:gate`, refusing before evaluation;
  explicit `:warn` admits experiments with declared diagnostics. A missing function contract is never refused at
  the REPL: the value returns with the growing warning above. Preserve malformed authored contracts as data; never
  arm them or substitute stale wrappers. Arm every valid contract: invalid inputs prevent entry, invalid outputs
  refuse. Entry policy never relaxes merge acceptance.
- **Documentation is data.** Docstrings are Markdown with a few fixed headings (Examples, Edge cases, Costs,
  Recommendations, Errors, Known issues); cross-cutting knowledge lives in Markdown pages. `[[sym]]` links resolve
  as data, and a subject's context shows every doc item linking it. Costs are prose stating armed or unarmed and
  when measured; every known issue links its task. Never author what the call graph already derives.
- **Use grounded vocabulary** ([vocabulary](docs/seon/architecture/vocabulary.md)): Clojure's term, then the
  dependency's, then a justified new term recorded once. Keep JVM REPL and SCI evaluation, turn and evaluation, live
  result and shown text distinct; cite legacy identifiers without reviving retired terms. Correct language in scope
  or file its issue. Source comments use `;` for prose, `;;` above forms, `;;;` for runtime structure.

## Failure and execution

- **Nothing crashes.** No evaluation, reply, turn, page, tool call, test run or boot step ever ends in an uncaught
  exception. A function that can fail declares each failure as a named alternative of its output union and RETURNS it
  as an error value that says what went wrong and how to fix it. `throw` is reserved for internal control flow that
  its own boundary converts to that value in the same function or its declared caller. Every entrant boundary (the
  one-path fold, MCP, the web handler, Flow procs, schedule handlers, boot, the test runner) converts any exception
  that still reaches it into the one error value, records it as a fault, and delivers it as data beside whatever value
  exists. A throw that escapes to an entrant is a defect of its producer: fix it there and add its union alternative.
- **Errors are ordinary values of one shape.** An error is one flat map under one named extension of
  `:seon.error/base`: the producer's fields, message, source location and the whole cause chain (class, message,
  `ex-data` per link); a refusal adds operation/member, expected shape, offending value and fix. Arities enumerate
  their exact error alternatives; no general error predicate, copied union or discriminator stamp (a contract alone
  does not prove a body's error union — that needs its producer, checker and regression). The producer
  declares identity; nothing downstream guesses it. Errors hold references, never copies: a database is its commit
  id or basis, a transaction its id, a context its agent and branch, a large value one blob digest, a place (atom,
  delay, promise) its class and identity, never its contents. Catch only
  declared cases or resurface the whole cause; missing evidence is a typed unknown, never nil, a default or
  message-only success. There is one way to build, store and show an error; delete every other wrapper, stringifier,
  cursor or copy.
- **Deliver every error to whoever can act on it.** Agent mistakes return to their agent. Core faults are recorded
  at their owning boundary and delivered as data in the tool results of the agent that caused them or can act on
  them — the MCP reply for outside agents, the next context for Seon agents, both reading the same saved
  observation. Recording without delivery, a log line or a panic alone is a defect. A graph stops only when its own
  execution cannot continue or storage fails. Diagnose at the REPL; no fallback store or retry loop.
- **Let Flow own execution.** Each agent has a graph; procs declare `:io` or `:compute`, transforms reference Vars,
  topology changes rebuild graphs. No central scheduler or dispatcher. Listen before deriving work; channels carry
  only rederivable or replaceable data and buffers express that loss policy. Durable recovery payloads belong in
  facts or blobs.
- **Bound admission and await actual exit.** Every execution surface declares its bound and names the missing terminal
  event when it fires. Timeout or cancellation is not termination: retain ownership and resources and prevent overlap
  of that same work until exit, while unrelated work proceeds. SCI interrupts interpreted code, not arbitrary host
  calls; keep isolation where termination cannot be guaranteed.
- **Recover facts, not execution.** Close interrupted runs and mark unfinished evaluations; never resume interrupted
  execution or serialize private objects or live results. A run's settlement (every form the run parsed and evaluated,
  in one transaction) returns once applied in order; its durability follows through the database owner's one
  fire-and-forget write, kept only while measurably faster. A crash may lose the newest such runs; recovery closes
  them and the agent redoes the work — do not represent the in-between state. Everything with consequences beyond the
  form (merges, write-back, tasks, config, schema adoption, boot and publication records, faults, branch lifecycle,
  deliveries) is durable before its caller is answered.
- **Derive wakes and completion.** Use listened datoms and a qualifying ordinary reply's basis; openings and
  system-only turns answer nothing. Handling is separate, a message subject is literal, `:seon.message/from` marks
  inside wakes. Before a turn, refresh each distinct changed read from the latest evidence and append it; never
  replay writes or effects or rewrite history. Compaction clears evaluations and regenerates the opening. Archive
  agents; preserve task and agent identity on loud, resumable budget exhaustion.

## Presentation

- **Three points, and cost decides between them.** The original value stays whole: many functions use it, so never
  trim it for presentation. A render function is schema-specific by definition, so all specific selection lives
  there and renders less. The value printer is the last stage every shown value passes through, with or without a
  render: it takes only general improvements (budget, shape, laziness, cycles, references), never a schema-specific
  branch, and shows each value's essence with runnable dig forms (on the result symbol) for what it cuts. Pulling unused data is waste, showing an agent irrelevant data costs more on every turn, and re-touching
  cached data costs more than leaving it: invest in the render function.
- **Choose the render function per consumer (AI or HTML); the first match wins**: the value's own `:seon.render/ai` or
  `:seon.render/html` key (the master override: a renderer symbol, an inline function or rendered data); a render
  function whose contract fits the value, defined in the viewing agent's namespace; the schema's declared pair (most
  specific first, along the schema's ancestry); none, so the value goes to the value printer directly. Whether a render function serves AI or HTML is its attachment key where attached, else its declared output
  schema, never a separate fact; like any function, its omitted declared inputs are supplied by schema name.
- **Return data, not comments; renders return data.** What comes back to an agent is data whenever possible: a reply
  is one map whose keys carry the value, its handle, its timing, its errors and its findings; comment lines are
  confusing next to data and agents repeat them. A render function takes the value and returns data, never forms to
  execute:
  - The AI face returns the pruned, processed data the agent cares about; the value printer prints it. Offered
    operations and dig or requery forms are part of that data, shown so the agent may run them, and never run by the
    render step.
  - The HTML face returns hiccup: a thing with a look and its operations.
  - The form face (`:seon.render/form`) returns the form that shows how this data can be found again, e.g. its
    requery. It is shown as data beside the value and is never executed by the render step.
  - A render runs once, when the reply that shows it is built, inside the viewer's world. It is READ-ONLY: it gets a
    database value, and no connection or writer. Its database reads are captured as read evidence on the shown unit, so
    the refresh before a turn re-renders it when they change.
  - Work with consequences belongs to the evaluation that caused it. For example, a defn's batch schedules its reaching
    test agents; the Var render only reads their status.
  - A render is cached by the value's content digest, so nothing renders twice. The reply the agent saw is its stored
    history, and history is never re-rendered.
  - Render output goes straight to the value printer, which never re-dispatches. A render that wants a nested thing
    rendered calls that render explicitly.
  - A render that fails falls back to the value itself, printed, with one diagnostic. Failed rendering gives a
    diagnostic; error base and satisfied schema renderers compose; an attribute request stays an attribute request.
- **Apply presentation limits once.** The value printer holds the one budget and spreads it across the value's shape;
  nothing else clips, and nothing prints unbounded: a log line, exception message, test report or HTTP error that
  prints a value outside the value printer is a defect. Printing is rendering with two consumers on one pipeline: AI agents
  through MCP and humans through the browser share the same selection, node tree and cut retrieval; only the final
  output (text or hiccup) differs. Save exact shown text, select whole prompt units, never clip HTML or historical
  units again. Keep query-work bounds and deadlines separate; elisions name bound, count, path and requery, with
  cuts counted. Derive missing profiles at entry or refuse explicitly. Build minimal infrastructure. Report ugly
  output, and state the size of anything an agent sees in estimated tokens, never bytes or characters.

## Proof and performance

- **Test the real system with injected worlds.** Plain `deftest` or contracted test functions declaring their
  inputs, the same for humans and agents. The harness prepares worlds once through canonical writers and helpers —
  every fixture write carries what the writer checks, digests included — surfaces every refusal, shares reads and
  branches writes from a captured commit. Use real SCI, armed contracts and fixed render profiles; substitute only
  external effects, as explicit values. No hand-built entities, bespoke fixture machinery or Var redefinitions. Tests are
  deterministic: generated inputs are bounded so every seed passes.
  A test checks one behavior, asserted on DATA: the value, the render's data, the stored fact or the error's key that
  behavior is about, and only that part, so unrelated changes never break it. Printed text is asserted only by the
  value printer's own tests and a few declared golden transcripts. A test body is a program run through the one path,
  so its failure values reach the runner's report through the value printer. An assertion message is prose and never
  prints a value.
- **Run tests as agents run.** Fork the context onto the test branch, inject custody, run the body in its member's
  context, unlink. Call the agent's owners, never a test-side copy. Ordinary work uses the main JVM; only
  platform/bootstrap, declared destructive and host-bound work gets isolated JVMs. If an edit cannot reach the main
  JVM, report the precise gap. No JVM-global test state (loaded program identity included) or restoring reloaded
  roots; preserve instrumentation (a test arms only its own Vars, never the whole JVM); never `alter-var-root`,
  `with-redefs`, `load-file` or `intern` over any Var. A test driver waits for what production waits for. Stop
  graphs before retracting their facts; release resources even if setup or cleanup fails.
- **Require current positive proof.** Select tests by current dependencies and each member's actual tested program
  and input evidence; past observed reach is diagnostic. Unknown or red cannot reuse green. Record completion even
  with no armed calls; a recording failure is not success. Keep one regression per behavior class, and destructive
  isolation until proven confined.
- **Use focused verification, run centrally.** Test worlds contend for one JVM, so root runs one focused list of
  installed requests per batched restart and returns each red to its owner; at completion root runs platform/affected
  integration and the bulk tier once, not per edit. Classify each red from stored runs by the commit that changed its
  behavior, then delete tests of deleted machinery, update retired assumptions or fix the surviving owner; replacement
  behavior tests land with the cut; never wholesale-triage reds. Gate inputs are `seon.test.cache/input-roots`:
  documentation never publishes or widens gates; fix a tool that does so before proceeding. HEAD must load and the
  named probe answer.
- **Make bounds honest.** Tests fail beyond their declared bound, default five seconds; a longer bound carries the
  measured operation and reason beside the number. Distinguish acquisition from warm execution; priming never
  excuses a timeout. The [testing skill](.agents/skills/clojure-testing/SKILL.md) names installed enforcement.
- **Make ordinary work sub-second.** Profile every contracted call; time boot, reload, publication, probes, tests
  and transactions; measure memory. A result over one second names the responsible armed functions and the causing
  agent fixes the cost before continuing, or justifies beside the number its proportionality and why it cannot be
  sub-second. Over ten seconds needs explicit owner authorization and an issue even when justified. "Cold",
  "expected" and "known cost" are not reasons; reject reports lacking these numbers.
- **Make cost follow changed inputs.** Eliminate whole-program work per change and per-call work that belongs once
  per evaluation. Branch creation is not full context construction. Hash admitted source inputs; compare commit
  identities for unchanged adoption. Hot-path commits time the same probe on parent and change; fix regressions over
  20% or 50 ms before commit.
- **Reuse content-valid work; derive without stamps.** Key derivations by what they read: content digests or the
  dependency's upstream change tracking. A commit id identifies a database value, not an excuse to recompute after
  unrelated writes. Memoize functions of immutable values; no writer `vary-meta`, transaction-carried candidate
  projection or atom beside a connection. Use Datahike commit ids and definition digests for identity,
  `versioning.cljc` for lineage, `history`/`as-of` for time, `d/listen` for notification and the writer for
  serialization; cite the seam first. Reads, renders, probes and evaluations never invalidate derived state; count
  misses and recompute only reached invalid inputs.
- **One central cache, bounded and configured.** Every Seon cache is a kind in `seon.cache` (Caffeine), keyed by
  content so nothing is invalidated, weighed at insert and bounded by one configured byte budget; dependencies and
  external tools keep their own caches where correct (Datahike's, clj-kondo's) under configured dials; no `memoize` or
  atom holds derived state. Link shared caches into every root, snapshot and run. A cached SCI context is worth only
  the evaluation cost it saves: evict it rather than grow.

## Operating and collaborating

- **Operate through `bin/seon`.** Use its installed help for start/status/open/init/config apply/stop/down/reset/
  nuke, never deleted internals. Every JVM runs a committed archive, submodules at their pinned revisions, never the working tree: `start` launches HEAD;
  `start --head` replaces a live root with HEAD, retaining store and caches; a dependency-class miss is filled before
  the old JVM stops, and our own classes are cached as boot loads them, so a miss never lengthens downtime. Reset
  unlinks and forks from program entities, retaining caches. Nuke replaces one JVM, holds the sibling store lock through
  deletion of the store and all derived caches and the rebuild and boot from committed inputs, reports full failures
  and falls back to the newest booting commit rather than refusing recovery. A competing start that wins the
  replacement gap is never killed or deleted by the loser. Destructive drills and signals need a root and a verified
  `(pid, start-instant)`.
- **Keep the development REPL available.** Assigned agents never stop or reset default: report **RESET NEEDED**.
  Root preserves evidence, batches recovery, reconnects and verifies; disposable data and private state may not be
  reconstructible. Root coordinates shared exhaust and integration. Publish schema resources and consumers together;
  pause hooks during source cuts and record pause and resumption.
- **One file, one owner.** A file has one owner at a time, no shared hunks. Root checks
  `tmp/orchestrator/file-ownership.md` before every launch or follow-up; a change to a held file goes to its owner
  as a patch or waits; agents request new ownership without editing the ledger. At each check-in root launches all ready
  file-disjoint work or names its dependency or collision, with no artificial JVM-slot or prober cap; editing lanes are limited to what root can prove
  in one central test round, and
  addresses oversized functions and files with shrink assignments.
- **Stay inside the assignment.** Work within its stop boundary; never resume, message or edit another agent's
  session or files. Do not nest delegation in bounded assignments, sandbox assigned work or overlap your own probe
  or test JVMs. Foreign breakage does not stop independent work. Assigned agents start no test requests or
  `bin/test` gates: they name the focused tests their slice needs, and root runs them centrally and owns cold and
  platform proof. If the shared tree cannot load, prove HEAD plus your diff in a `git archive` snapshot with shared
  caches linked and name the foreign boundary.
- **Use Git without touching others' work.** No `git add -A`, `reset --hard`, `stash` or shared-file restore. Commit
  coherent slices with `git commit --only -- <owned paths>` and report released paths so root updates the ledger.
  Never push, merge or open a pull request beyond explicit owner authorization. No git worktrees without the owner's
  personal authorization for that specific use.
- **Launch lanes deliberately.** Astra runs through `bin/codex-agent`; read the
  [codex-lanes](.agents/skills/codex-lanes/SKILL.md) skill first. Assignments cite the existing issue or spec and
  raw evidence and use verify/falsify/probe language. Resume usage-limited agents only with owner authorization,
  retaining their identity. Make paid provider runs deliberate.
- **Trust analysis only when current.** Treat unresolved dependency or protocol Vars in clj-kondo as stale cache
  until disproven: repopulate it at the loaded JVM's REPL with `(seon.fn.analyzer/analyze-dependencies!)`, which
  lints that JVM's classpath minus the checkout's own roots (about 6 s warm; `seon.fn/dependency-analysis-command`).
  Never change correct code to satisfy stale analysis, and never write the shared cache a booting JVM reads.
- **Clean up what you own.** Remove owned shells and disposable roots before reporting, but preserve uncertain
  resources and unresolved evidence. Inspect actual live holders, never a self-matching process search; roots
  younger than the oldest live test launcher are not sweepable. Recursive deletion stays inside your own disposable
  root and never follows symlinks.
