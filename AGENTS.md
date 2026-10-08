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
  once and evaluated once; its facts and shown text are stored and queryable, while its result value lives only in the
  runtime (a crash or restart loses it; its handle then answers that the value is gone). Everything before the print is a real
  value under its Malli schema — live in the runtime, a database fact or a blob — never a string built to be
  clipped. The pipeline is: text in (prose becomes comments and delimiters are repaired where unambiguous) → forms
  read and evaluated → facts and shown text stored → at the edge, the value's schema selects its most specific render,
  if any → the value printer. Every shown value passes through the value printer, render output included.
- **The path is total.** It always returns the evaluated value. A later stage's failure (gate, renderer, recording,
  classification) is attached beside the value as a diagnostic and recorded as an error; it never replaces, blocks or
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
  `seon.cache`). Mutable state is at most one validated state map per process and one per cluster, each an atom
  whose `set-validator!` is a Malli validator of its fully specced map, so every write is checked or refused; an
  agent's state lives in its Flow proc and SCI context. The only other mutable cells are justified counters: the
  per-function profile counters, the SCI interrupt flag, the running Thread and clojure.test's report counter; cache
  counters are Caffeine's `recordStats`. Any other atom needs a seriously good reason the owner accepts in plain
  words, and a lint refuses new ones (ledger §3.19). Live host objects with no data form
  (Flow graphs, channels, executors, SCI contexts, listeners) live in exactly one roster per scope (process, then
  cluster execution), keyed by stable ids, passed explicitly and observable in status; admission and retirement are
  writer-checked facts. Per-call accumulation is a fold.
- **Grow by accretion.** Require no more, promise no less: open maps, validated declared members, optional additions
  and new names for changed semantics.
- **Make failure local and loud.** Each part fails without corrupting its neighbours or blinding diagnosis. Never
  read silence, missing subjects or stale observations as health.
- **Add no hypothetical restrictions.** A restriction names the correctness failure it prevents or is deleted.

## Words and where they come from

**DO NOT RECREATE THESE CONCEPTS. USE THE LIBRARIES.** Each row is already solved by the project named. Before touching
the concept, read its source under `reference-code/<project>` and Seon's caller; use its functions and its words. A
Seon reimplementation of anything below is a defect: delete it and call the library. If the library truly cannot do
what is needed, stop and bring the owner the exact gap; never build around it yourself. Retired words survive in code
only until their rename lands; never use them in new code or prose.

| Concept | Use (project, source) | Never |
|---|---|---|
| REPL round: read, eval, print, caught; one eval request per submission | `clojure.main/repl`, prepl, nREPL `eval` ([clojure](https://github.com/clojure/clojure), [nrepl](https://github.com/nrepl/nrepl)) | "pass", "batch", own stage names |
| function schemas, instrumenting, validation, defaults | `m/-instrument`, `malli.instrument`, `m/validator`, `m/explain`, `malli.registry`, `default-value-transformer` ([malli](https://github.com/metosin/malli)) | own wrappers, "armed", "projection", "supply" |
| transactions, branches, merge, data conversion | `transact` → tx-report, `branch!`, `delete-branch!`, `merge!`, norms ([datahike](https://github.com/replikativ/datahike), our fork) | "receipt", "settlement", "unlink", "migration" |
| interpreters for agents | `sci/init`, `sci/fork`, SCI's reader ([sci](https://github.com/babashka/sci), our fork) | a second evaluator or reader |
| reading and editing Clojure text | [edamame](https://github.com/borkdude/edamame), [rewrite-clj](https://github.com/clj-commons/rewrite-clj) | string or regex parsing of code |
| per-evaluation dynamic state | `binding`, `bound-fn*`, `with-bindings` (clojure.core) | "world", "environment" |
| tests | clojure.test report maps, `malli.generator/check`, [kaocha](https://github.com/lambdaisland/kaocha) | "member", "accretion test" |
| reloading code | `require :reload`, [clj-reload](https://github.com/tonsky/clj-reload) | own dependency ordering, "adoption" |
| static analysis | [clj-kondo](https://github.com/clj-kondo/clj-kondo) analysis and findings | own analyzers |
| errors | `ex-info`, `ex-data`, `Throwable->map`, `clojure.main/ex-triage` | "fault", own exception printers |
| printing | [fipp](https://github.com/brandonbloom/fipp) via the one value printer; `*print-length*`-style budget | a second printer, `pr-str` copies |
| configuration files | `aero.core/read-config` with `#profile`, `#env`, `#include`, `#ref` ([aero](https://github.com/juxt/aero)); Malli validates the result | own EDN loaders, profile or precedence code |
| caching | [Caffeine](https://github.com/ben-manes/caffeine) via `seon.cache`, its `recordStats` | own counters, `memoize`, atoms |
| agent execution | `clojure.core.async.flow` procs and step-fns ([core.async](https://github.com/clojure/core.async)) | own schedulers, state tags |
| durable bytes | [konserve](https://github.com/replikativ/konserve) (our fork) | own stores |
| merging vocabulary | git: branch, main, merge request, source/target branch, merge base, ours/theirs, unmerged | invented names |

Words genuinely Seon's, recorded in [vocabulary](docs/seon/architecture/vocabulary.md): task, proof, reaching test,
turn, render function, current-state block, write-back, wake, answered t. Seams per area:
[reference code](docs/seon/architecture/reference-code.md).

## How to work

1. **Understand.** Read the owning spec, Git history, dependency source and first-party caller before designing.
   Record the pinned revision, the seam by fully qualified symbol (a `file:line` only in dated evidence), guarantee,
   inputs, recomputation event and cost. Verify a seam exists
   upstream before calling it the library's; prefer upstream, improving a maintained fork only to remove real
   duplication. For validation, contracts, instrumentation and error shapes, begin in
   `reference-code/malli/src/malli` and cite the Malli function used. Load
   [data-oriented-clojure](.agents/skills/data-oriented-clojure/SKILL.md) before Clojure design or edits, and the
   REPL, testing, datahike, modeling and flow skills at their boundaries. Verify touched skill claims against
   the code they name; living documents cite code by symbol (which resolves to file and line), never a hand-written
   `path:N`; a stale skill is a high-priority defect; delete unverifiable claims, never hedge them.
2. **Probe.** Start Clojure changes with `bin/seon status` and MCP `runtime_status`; report and record degraded or
   missing tools first. Use `eval_clj` with explicit root and cluster; JVM mode has no custody, so pass the
   connection, e.g. `(seon.cluster.boot/connection "default")`. SCI mode mutates its shared context: keep probes
   disposable, in scratch namespaces or branches with finite inputs. Reproduce with one small form, inspect the
   complete envelope and installed schema, call the owner, then try the smaller algorithm. On real data: parse,
   transform, validate each value against its schema, store it (disposable branch or `d/with`), pull it back and
   compare. Try data models and ideas at the REPL before writing them down. Never load uncommitted source into
   default's JVM: try it on your own agent's SCI branch. Verify a tool's limit before reporting it. Every reply is
   also a health check: read its timing, diagnostics and recorded errors. An error, a panic instead of an error value,
   a recorded error without its task, or an ordinary operation over one second is a finding. Record it as a task, even when it is outside your
   assignment. Record exact forms, values and timings; the edge cases found become the regressions.
3. **Design the smallest composition.** Open the owning spec or landing note with one or two lines naming what
   exists, its data flow and the principal Clojure approach. State what disappears, each operation's cost and target
   complexity, the concerns separated, and the simplest alternative: dependency seam, existing function or deletion.
   Research existing libraries before writing a replacement. Line counts are a codebase budget, not a per-change
   ledger: a change need not delete what it adds, feature for feature. Write the simplest correct form of what is
   needed; when the code grows, name what it lets go elsewhere and plan that deletion. Large additions still start
   with the smaller alternative considered, and net deletion never excuses needless machinery.
4. **Pick the model by risk; every lane proves its slice.**
   - **Sonnet** measures, diagnoses, writes specs, deletes and sweeps, and implements clearly specified slices. When
     it cannot find the cause or a clearly correct fix in about fifteen minutes, it bows out with what it measured.
   - **Opus** changes the semantics of the one REPL path (eval request, evaluation, render, evidence) and other
     boot-critical platform code, designing by dogfooding: alternatives tried at the REPL on its own branch, the
     tests reaching the change, timings.
   - **Astra, an outside model trained on a different distribution, takes the hard performance work** a Sonnet lane
     bowed out of, and FIXES what it finds: super-linear transformations, repeated work (the same derivation
     executed thousands of times, whole-program work per change, re-reading what the caller holds).
   - **Every lane refuses bad changes on sight** and proves its own slice before committing (see "Prove
     each slice before it lands"). Every review is dual (a fresh Opus agent and Astra, independently) and never
     substitutes for that proof.
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
   Fix problems on code you touch when that takes about fifteen minutes, within file ownership (`bin/lane add` or a
   patch); otherwise create a task and reference it (`ns/task-name`) in that var's docstring. When a design proves
   wrong, stop and report it with options instead of patching around it.
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

- **Clusters and branches.** A cluster is a Datahike branch, called main, plus its agents and shared plumbing; one
  JVM hosts many. A branch is `d/branch!`, a pointer to shared immutable indexes, never a store copy or a system to
  boot. An agent's context has one branch checked out. `d/delete-branch!` removes a branch from the roster; retention
  GC reclaims its commits without per-branch cleanup. A finished agent's branch is deleted once its task is done or
  given back and its merge request is closed, so its history and blobs can be collected.
- **Boot and configuration.** Boot opens the REPL before store acquisition, then builds process → store → facts →
  flow, publishing readiness at each layer. One process root holds the store's lifetime lock; Datahike serializes
  transactions. Bootstrap selects paths and binds; running configuration is database facts; secrets are
  environment-variable names. `seon.config/defaults` is a compiled constant, not a cluster's configuration. Boot
  reads each dial from the configuration it applied, never the store's pre-adoption value, so a new dial exists at
  its first read. Every size, count, byte budget, heap cap, timeout and threshold is a dial whose shipped default fits
  a 16 GB machine running many agents beside the user's work, never derived from the host's RAM: a 4 GB heap cap,
  `seon.cache` 512 MB with live results inside it, Datahike's node cache byte-bounded (replacing its soft-referenced
  retention), 4,096 bytes inline before a blob, a 1 KB blob compression floor, storage I/O on virtual threads. A
  hardcoded policy number in code is a defect. Target (ledger DIAL-1): configuration files are read by Aero
  (`#profile`, `#env`, `#include`), one file for the JVM and the Babashka operator; Malli validates the result and
  the database reconcile stays.
- **The program is entities**: functions, tests, schemas, namespaces, render pairs, contracts, analysis and test
  evidence keyed by exercised definition digests. Evaluations, calls, messages, errors, tasks and live results are
  data and never merge as program; declare that partition on entity schemas, not in a hand list. Default's program
  entities and loaded namespaces derive from files. A JVM has one set of compiled Vars; other branches interpret
  differing entities and their affected callers in SCI and bind compiled Vars for the rest. A branch never changes
  the platform: compiled (host-bound) code, the evaluator, printer and test runner included, keeps running as
  compiled and working on every branch, and sees a change only after merge and reload; what the branch evaluates
  from source sees its definitions. Compute host-bound status per declaration, never from a namespace roster.
- **An agent's context** is its SCI world; its task selects custody of the live cluster head or its own branch.
  Every agent, filesystem agents on their own named branches included, uses the one REPL entrance. Private defs,
  atoms and objects survive program changes advanced in place at idle boundaries; each eval request reacquires from
  the branch head, reusing content-valid derivations.
- **Reload and adoption.** A reload is `require :reload` for changed namespaces and their dependents. Adoption goes
  entities → load → arm → record and waits for no one. Stability comes from branch custody: work that overlaps a reload
  compares the loaded program identity at start and end and pushes back, never takes a lock. Failure leaves affected
  execution unavailable until exact bytes, behavior and arming converge; never call an unchanged record healthy.
  Ordinary clusters keep their program; selected development clusters adopt explicitly.
- **Merge is a root task.** An agent requests a merge only after its proofs pass and no test its change broke is red
  on its source branch; the merge request is a task. Root works it through the ordinary REPL holding both worlds — a
  merge branch forked from the target branch (main) and a copy of the agent's world — with easy tools to move data
  between them, and chooses exactly what to take by identity from the diff value (since the merge base; ours is the
  target, theirs the source); the task never lists what to merge. `merge!` commits only when every test on the merge
  branch is green (the proofs, the tests reaching the change and whatever root chose to run, recorded as evidence); a
  test already red on main stops it for root. Conflicts stay unmerged on the merge branch for repair. Norms run in
  their listed order. The writer checks the touched entities' digests; only root's merge advances main, and the
  agent's branch is deleted when its merge request closes. Local green never implies shared acceptance.
- **Write-back** uses the same gate, checks file bases, stages exact-span changes and reindexes to prove equivalent
  declarations before installation.

## Data and contracts

- **Model from the agent outward.** The agent's record is its one-hop attachments (task, settings, messages,
  evaluations, calls), each with a declared render; context is rendering what is attached, detail one requery away.
  Tell a story, not a star: agent (current namespace, answered basis, disposition) → its evaluations in agent order,
  each with the call that produced it; no turn or run entity sits between. Namespace information lives on the
  namespace, agent information on the agent. Every stored fact names its consumer or is deleted; hiding it behind a filter or a
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
  Any input, required or optional, whose declared schema is a member of the evaluation's world (connection, writer,
  agent, task, profile, call sink, SCI context) comes from that evaluation's own world, never a global or another
  world; a database value is never held in a world: it is read from the world's connection at the call, so a call
  after a write sees it; this includes world-member keys missing from a map argument whose schema declares them. Supply runs
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
- **Separate storage from observation.** Store a value when it must outlive its subject; a stored copy of a value
  (recorded error evidence, an effect's result) is the value printer's shown text plus ids, nothing more.
- **Three places, one decision.** The full live value lives once, in `seon.cache` under its result handle (an LRU
  under the configured byte budget). Datahike holds small facts and shown text up to the one threshold dial
  (`:seon.config.eval.result/blob-threshold`, UTF-8 bytes); anything larger is a konserve blob (immutable, compressed)
  staged and committed in the same transaction as the fact holding its digest and size. The form is decided once,
  where the stored form is produced, by `seon.blob/inline-or-stage`; no other dial or step re-decides it. Never build a
  big value and trim it later: produce the stored form at the source and pass references, not copies. Stored-text
  attributes carry the registered bounded schema, so the checked writer refuses an oversized value and a later
  re-copy fails loudly at its source. Derive pulled shapes and
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
  source from then on; a contract is not proof of a body's error union, its regression is. Existing uncontracted code is not back-filled wholesale: much of it is headed for deletion.
  A new or changed function without a complete contract: the edit hook refuses it; the REPL admits it with a
  warning beside its value that grows as the agent's uncontracted definitions accumulate; merge acceptance refuses
  a proposal that carries one.
- **Keep entry policy separate from merge.** Schema and test entry default to `:gate`, refusing before evaluation;
  explicit `:warn` admits experiments with declared diagnostics. A missing function contract is never refused at
  the REPL: the value returns with the growing warning above. Preserve malformed authored contracts as data; never
  arm them or substitute stale wrappers. Arm every valid contract: invalid inputs prevent entry, invalid outputs
  refuse. Entry policy never relaxes merge acceptance.
- **Documentation is data.** A docstring says briefly what the function returns; Markdown headings (Examples, Edge
  cases, Errors, Known issues) only on public `my.*` docstrings; cross-cutting knowledge in Markdown pages. A
  backticked span reading as one fully qualified symbol or keyword is a reference; a task's is `ns/task-name`, its
  identity derived from namespace and name. A definition or test names its tasks with `:seon/tasks` metadata (on a
  deftest, `^{…}` before the name); the deftests naming a task are its success tests; `:seon/tasks` holds
  `ns/task-name` symbols. Target: the index resolves backticked references into the subject's context and reports one
  resolving to nothing as a problem on that var. No dates,
  measurements, history or `file:line` in source: costs are profile evidence. Known issues reference their task.
  Never author what the call graph already derives.
- **The right context is the product.** The goal: every agent, ours included, works a task from the context Seon
  generates at the REPL (its current-state block, then `doc`, `source` and its references, one evaluation each), and
  that context is complete and correct. References everywhere (task intents, Markdown docs, docstrings) follow one
  rule and go through one resolver; every tool that pulls them in must do it well. When context is missing, wrong,
  noisy, slow or misleading (an unresolved reference, a stale line, `passed? false` with nothing run, a reply full of
  noise), fix it at its producer if it takes about fifteen minutes, otherwise file a task on that producer with the
  exact form and reply. Never work around bad context silently.
- **Write tasks as data an agent can be handed.** A task lives in `resources/seon/tasks/<ns>.edn`, is ingested by
  the same code that serves Seon agents, and is assigned only with a proof. Its entry is `:seon.task/name` (a symbol
  name; the task is `ns/name`), `:seon.task/intent` (what must become true, short), `:seon.task/must-read` (fully
  qualified symbols) and optional `:seon.task/evidence` (paths); `:seon.task/kind` is still required until its retirement row lands. Its proofs are the deftests carrying `ns/name` in
  `:seon/tasks`, never prose: write or tag the proving test when you file the task; a task already green proves nothing.
  References are typed by plain EDN and must resolve: code is a backticked fully qualified symbol (Seon or library,
  e.g. `` `malli.core/validator` ``), a schema a backticked fully qualified keyword, any other file a relative path
  whose first segment names its base (the checkout, or `reference-code/<project>` at its pinned revision) with
  `#heading` anchors. Never write a file or line for code: they are derived from the Var or the analysis. Evidence
  never points into gitignored `tmp/`. Ingestion reports an unresolved reference as a problem on the task. Retire a
  task in the commit that deletes its subject or makes it moot.
- **Use the agreed words** ("Words and where they come from" above). Keep JVM REPL and SCI evaluation, live result
  and printed value distinct; cite legacy identifiers without reviving retired terms. Correct language in scope or
  file its issue. Source comments use `;` for prose, `;;` above forms, `;;;` for runtime structure.

## Failure and execution

- **Nothing crashes.** No evaluation, reply, eval request, page, tool call, test run or boot step ever ends in an uncaught
  exception. A function whose job is to answer (`my.*` tools, validators, merge checks, gates) RETURNS its error map
  and lists exactly those variants (target: one `:multi` on `:seon.error/type`); every other function throws the same
  map inside `ex-info` and declares only its success value, except reads, which return refusals until the errors
  spec's S-reads converts each read family with its callers. There are three catches: a returning tool's one top catch
  of its own declared variants, a deliberate translation that names its own type and keeps the cause, and the entrant
  boundary (the one-path fold, MCP, the web handler, Flow procs, schedule handlers, boot, the test runner), which takes
  a thrown map back once with `ex-data`, turns any other exception into an error map, records it and delivers it as
  data beside whatever value exists. No other layer catches; an unclassifiable exception is its producer's defect.
- **Errors are ordinary values of one shape.** An error is one flat map: `:seon.error/type` (its variant's schema
  key, extending `:seon.error/base`), `:seon.error/operation`, `:seon.error/message`, optional fix, Malli problems and
  the variant's members; the boundary adds the cause chain. One constructor builds it where the failure is detected
  and validates the variant, for both carriers; nothing stamps metadata or guesses the type downstream. Target (the
  errors cut): the base drops `:seon.error/at` and `:seon.error/layer`, and the constructor becomes
  `(error type operation message data)` with `fail!`; until then use the installed constructor. Errors hold
  references, never copies: a database is its commit id or basis, a transaction its id, a context its agent and
  branch, a large value one blob digest, a place (atom, delay, promise) its class and identity, never its contents.
  Missing evidence is a typed unknown, never nil, a default or message-only success.
- **Deliver every error to whoever can act on it.** Seon's own failures are errors (the errors cut retires the
  separate fault concept). Every occurrence returns as data in the reply of the agent whose evaluation or call it
  happened in (the MCP reply for outside agents, the next context for Seon agents, both reading the same saved
  observation), with a result handle to dig into; an agent's own mistake is only delivered. Target (the errors cut;
  today `seon.fault` records faults): the entrant records Seon's own errors once per signature at the task home, with
  a count, a task and one notification; repeats only count. A recorded error resolves when its producer is deleted, or
  when the producer changed and its task's proofs (at least one) ran green since. Recording without delivery, a log
  line or a panic alone is a defect. A graph stops only when its own execution cannot continue or storage fails.
  Diagnose at the REPL; no fallback store or retry loop.
- **Let Flow own execution.** Each agent has a graph; procs declare `:io` or `:compute`, transforms reference Vars,
  topology changes rebuild graphs. No central scheduler or dispatcher. Listen before deriving work; channels carry
  only rederivable or replaceable data and buffers express that loss policy. Durable recovery payloads belong in
  facts or blobs.
- **Bound admission and await actual exit.** Every execution surface declares its bound and names the missing terminal
  event when it fires. Timeout or cancellation is not termination: retain ownership and resources and prevent overlap
  of that same work until exit, while unrelated work proceeds. SCI interrupts interpreted code, not arbitrary host
  calls; keep isolation where termination cannot be guaranteed.
- **Recover facts, not execution.** Never resume interrupted execution or serialize private objects or live
  results. An eval request reads and evaluates every form, then writes its recording transaction as ONE synchronous transaction, durable
  before it answers, under 100 ms; no fire-and-forget write, so the next call sees it. A crash before that transaction loses
  that eval request's bookkeeping (evaluation rows, call row, answered basis), never an effect a form already made durable;
  the work stays due, so an effect repeats unless its owner is idempotent. Until the agent-loop cut, boot closes
  interrupted runs and marks their unfinished evaluations. Everything with consequences beyond the form (merges,
  write-back, tasks, config, schema adoption, boot and publication records, recorded errors, branch lifecycle,
  deliveries) is likewise durable before its caller is answered.
- **Derive work and completion.** Work is a listened wake the agent has not answered; a message subject is literal,
  `:seon.message/from` marks inside wakes; never replay writes or effects. Context is rendered live from the agent's
  task and its references, never stored. A task names its success tests (deftests carrying it in `:seon/tasks`)
  before it is assigned or launched; a test already green before the work does not prove it. Target (agent-loop P5
  and cut): `seon.task/assign!` refuses a task with no proofs or whose proofs are all green; after any eval request
  that evaluated a form, the task's proofs and then the tests reaching the change run on the agent's branch inside
  that eval request (unchanged results answered from their record, the step's time limit a dial defaulting to 10 s),
  and all green completes the task, the only success; a question task declares its answer's Malli schema and
  `my.agent/answer!` stores the validated answer as ordinary Datahike data for its proofs to read;
  `my.task/complete!`, with a required reason, only gives a broken or impossible task back to its assigner, who is
  notified; a reply is `my.message/send` then `my.turn/wait`; a model agent launches only on an assigned task with
  proofs, and its own `:work` task ends by `my.turn/wait` or the call cap; the
  answered basis is `:seon.agent/answered-t`, and system turns and context capture are gone. Archive agents; preserve
  task and agent identity on loud, resumable budget exhaustion.

## Presentation

- **There is exactly ONE printer: the value printer.** Nothing else turns a value into text: no second printer,
  serializer, `bounded-text`, floor, truncation, `pr-str` into a message, log or page, and no printing to hash, sort,
  compare or store a copy. Identity is a structural digest, never printed text; comparison uses the data; an error
  message is short fixed text with its values as data keys; a page shows values through the printer's HTML face; data
  that must be stored is stored as data. Agents' own values are never clipped; Seon never generates bulk of its own
  (whole stack traces, dumps, re-printed copies). Writing a new printer, or a helper that wraps `pr-str` under another
  name, is refused in review; finding one means deleting it and converting its callers in the same commit.
- **Three points, and cost decides between them.** The original value stays whole: many functions use it, so never
  trim it for presentation. A render function is schema-specific by definition, so all specific selection lives
  there and renders less. The value printer is the last stage every shown value passes through, with or without a
  render: it takes only general improvements (budget, shape, laziness, cycles, references), never a schema-specific
  branch, and shows each value's essence with runnable elision forms for what it cuts: plain clojure.core (`drop`,
  `take`, `nth`, `get-in`, `subs`, `keys`, `count`) on the live result symbol, lazy first. Functions are data: an
  ordinary function's source prints whole, as code; only a real monster is cut, with the elision form for the rest. Pulling unused data is waste, showing an agent irrelevant data costs more on every call, and re-touching
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
    database value, and no connection or writer. Its database reads are captured as read evidence, and the render is
    cached by what it read, so a changed read re-renders it.
  - Work with consequences belongs to the evaluation that caused it. For example, a defn's batch schedules its reaching
    test agents; the Var render only reads their status.
  - A render is cached by the value's content digest, so nothing renders twice. A model call stores its basis, not
    its prompt; a history unit re-renders only when its content, profile or the printer changes.
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
- **Prove each slice before it lands; run the broad tiers centrally.** A lane commits through `bin/lane commit LANE
  -m MSG --test NS... PATHS`, which runs `bin/seon check PATHS --test NS...`: the candidate archive (HEAD plus the
  slice) is published and adopted on disposable branches, then a throwaway JVM boots from it on a disposable store
  and runs the named tests through the one runner; any red, boot failure or lint error refuses the commit. Name the
  tests reaching the change. Root restarts default after each landing and runs platform/affected integration and the
  bulk tier once, not per edit. Classify each red from stored runs by the commit that changed its
  behavior, then delete tests of deleted machinery, update retired assumptions or fix the surviving owner; replacement
  behavior tests land with the cut; never wholesale-triage reds. Gate inputs are `seon.test.cache/input-roots`:
  documentation never publishes or widens gates; fix a tool that does so before proceeding. HEAD must load and the
  named probe answer.
- **Make bounds honest.** Tests fail beyond their declared bound, default five seconds; a longer bound carries a
  reason naming the measured operation, its numbers in profile evidence. Distinguish acquisition from warm execution; priming never
  excuses a timeout. The [testing skill](.agents/skills/clojure-testing/SKILL.md) names installed enforcement.
- **Make ordinary work sub-second.** Profile every contracted call; time boot, reload, publication, probes, tests
  and transactions; measure memory. A result over one second names the responsible armed functions and the causing
  agent fixes the cost before continuing, or justifies beside the number its proportionality and why it cannot be
  sub-second. Over ten seconds needs explicit owner authorization and an issue even when justified. "Cold",
  "expected" and "known cost" are not reasons; reject reports lacking these numbers.
- **Make cost follow changed inputs.** Eliminate whole-program work per change and per-call work that belongs once
  per evaluation. Branch creation is not full context construction. Hash admitted source inputs; compare commit
  identities for unchanged adoption. Hot-path commits time the same probe on parent and change and report every
  regression with its numbers. Judge cost against what the work must do (what it is proportional to), never an invented
  target: 10× over that expectation means wrong work to find and fix. Every policy number is a config dial sized for a
  16 GB machine.
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
- **One file, one owner.** A file has one owner at a time, no shared hunks. A lane claims every file it will edit,
  tests included, with `bin/lane claim LANE PATHS` before editing; the claim refuses a file another lane holds or one
  with uncommitted changes nobody claimed, and `bin/lane status` is the ledger. A change to a held file goes to its
  owner as a patch or waits; a lane needing another file claims it with `bin/lane add` or asks root. At each check-in root launches all ready
  file-disjoint work or names its dependency or collision, with no artificial JVM-slot or prober cap; editing lanes are limited to what root can prove
  in one central test round, and
  addresses oversized functions and files with shrink assignments.
- **Stay inside the assignment.** Work within its stop boundary; never resume, message or edit another agent's
  session or files. Do not nest delegation in bounded assignments, sandbox assigned work or overlap your own probe
  or test JVMs. Foreign breakage does not stop independent work. If the shared tree cannot load, prove HEAD plus
  your diff in a `git archive` snapshot with shared caches linked and name the foreign boundary.
- **Use Git without touching others' work.** No `git add -A`, `reset --hard`, `stash` or shared-file restore. Commit
  coherent slices only through `bin/lane commit` (it refuses unclaimed paths and anything else staged in the shared
  index, then commits exactly the named paths with `git commit --only`), and `bin/lane release` when done.
  Never push, merge or open a pull request beyond explicit owner authorization. No git worktrees without the owner's
  personal authorization for that specific use.
- **Launch lanes deliberately.** Astra runs through `bin/codex-agent`; read the
  [codex-lanes](.agents/skills/codex-lanes/SKILL.md) skill first. Assignments cite the existing issue or spec and
  raw evidence and use verify/falsify/probe language; an assignment names the tests that prove it done, and a task
  without them is not launched. Resume usage-limited agents only with owner authorization,
  retaining their identity. Make paid provider runs deliberate.
- **Trust analysis only when current.** Treat unresolved dependency or protocol Vars in clj-kondo as stale cache
  until disproven: repopulate it at the loaded JVM's REPL with `(seon.fn.analyzer/analyze-dependencies!)`, which
  lints that JVM's classpath minus the checkout's own roots (about 6 s warm; `seon.fn/dependency-analysis-command`).
  Never change correct code to satisfy stale analysis, and never write the shared cache a booting JVM reads.
- **Clean up what you own.** Remove owned shells and disposable roots before reporting, but preserve uncertain
  resources and unresolved evidence. Inspect actual live holders, never a self-matching process search; roots
  younger than the oldest live test launcher are not sweepable. Recursive deletion stays inside your own disposable
  root and never follows symlinks.
