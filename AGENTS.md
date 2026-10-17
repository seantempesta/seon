# Seon — shared instructions

## The goal

Software has rarely grown linearly: each addition tangles with what already exists, so cost and defects grow faster
than capability. Seon is a different way to build software, one that grows by **accretion**, the way Clojure has:
open data, stable names, new names for new meanings, and every part observable. Many agents work at once, each on
its own branch in its own SCI context, and design, prove and implement software cheaply and safely. The system gets
smaller, faster and more certain as they work.

Four properties make that possible:

1. **Data is designed first and moves provably.** Every value has a named Malli schema at its producer, and every
   consumer declares the same name. A change to what `foo` returns is therefore visible to everything that depends on
   it. A changed meaning takes a new name; the old name keeps its promise until its last consumer has moved. That is
   how Seon versions: by name, in the data.
2. **Everything is instrumented.** Every function carries its contract, and every call is checked and measured. Every
   reply reports what the work cost (calls, CPU, memory, storage) and what changed, so feedback arrives on every
   change.
3. **Work is tasks, and tasks chain.** A task is data: what must become true, references to the code and documents
   it touches, and the proofs that show it done. Tasks chain naturally: model the data and specify the interface,
   then write the proofs, then implement the body. Each step is worked by an agent whose context Seon generates from
   the task's references.
4. **The core stays small and fast.** It aims at about 10,000 lines and sub-second ordinary operations, and it
   composes proven libraries for every concept they already own.

This file (`CLAUDE.md` is a symlink) holds the laws and how to work, revised in place. Mechanisms live in
[architecture](docs/seon/architecture/README.md) and `.agents/skills`, decisions in the owning spec, history in Git.
Read the assignment, the nearest instructions and the named authorities end to end. Verify every target against the
running system: a specification or a source citation describes intent, and the running system is the evidence.

## How we design

- **Keep the task-chain design locked.** No new lifecycle attribute, clause or path without amending
  [TASK-CHAIN](docs/seon/schedule/cards/TASK-CHAIN.md) and adding a red deftest that proves the change.

Every change follows the same order, from the data outward:

1. **Trace the data flow.** Find where the value is produced, which functions transform it, where it is stored and
   where it is shown. Reuse the named schemas already on that flow; name a new schema only for a new concept, at its
   producer, with its consumers declared.
2. **Specify the interface before the body.** Give each function its name, its contract over those schemas and its
   proofs: examples that include every reported input, and a property where a general law exists. Give it a cost
   budget derived from what the work must do. The proofs are red until the body exists.
3. **Write the simplest body that satisfies the proofs.** Ask what the work is proportional to and do only that work:
   lazy over eager, what is shown over what might be shown.
4. **Change the owner.** When something is in the way, follow the data flow to the part that owns it and change it
   there. A good fix usually removes code, and every addition names what it replaces.
5. **Measure before and after.** A design is judged by its measured cost against what the work must do, on real
   data, with the numbers in the commit message.
6. **Prove the whole loop.** A change counts when it works end to end through the same path agents use: defined at
   the REPL, proven by its tests, written back and committed.

## The perspective: an agent at its REPL

Everything is modeled from a Seon agent's side: its task, its context, its REPL, its branch. Our outside agents are
Seon agents too: they use the same branch, REPL entrance, checks and reviewed merge, and the orchestrator acts as
root.

- **Core is composable.** One shared database (stable values, one writer, cheap branches), one shared SCI runtime (a
  program context each agent forks into its own SCI context) and render functions over data; each agent is its own
  concurrent Flow graph. Parts compose as functions and stage values into one system. MCP, the test harness and the
  web are views that enter through the agent's path and render its values, sharing its model, evaluator, printer and
  store.
- **There is one REPL path**: read → evaluate → record → print, from the agent's side of the prompt. Forms are read
  once, SCI evaluates them, the facts come from what the evaluation observed (with clj-kondo's analysis of the same
  text joined by exact source span), and one transaction records it all. Everything uses it: product agents, outside
  agents through MCP, tests through the harness, render forms in context assembly (and the debug page, which displays
  that assembly), schema registration, checks and merge. An entrant needing another reader or evaluator (JVM mode, a
  file, a test body) passes that stage as a value to the same pipeline. A form is parsed once and evaluated once;
  its facts and printed value are stored and queryable, while its result value lives in the runtime (after a crash or
  restart its handle answers that the value is gone). Everything before the print is a real value under its Malli
  schema: live in the runtime, a database fact or a blob. The pipeline is: text in (prose becomes comments and
  delimiters are repaired where unambiguous) → forms read and evaluated → facts and printed value stored → at the edge,
  the value's schema selects its most specific render, if any → the value printer. Every shown value passes through
  the value printer, render output included.
- **The path is total.** It always returns the evaluated value. A later stage's failure (entry policy, renderer, recording,
  classification) is attached beside the value as an error map and recorded, and the value still returns.
- **Agents change the program in REPL terms**: `defn` inside the target namespace (after `in-ns`), as in Clojure,
  `my.program/ns-unmap!` and `my.program/remove-ns!` to delete. Files are the write-back of accepted entities; the
  working medium is the REPL. Product agents work through declared eval, reload and test requests.

## How to think

- **Simple and correct first, then optimize.** The algorithmic choice has the biggest gains: ask what the work is
  proportional to and choose the approach that does only the work actually needed. Implement it in the simplest
  correct form; add complexity only for a measured, large performance win. Optimize for the common path and let
  existing recovery handle the rare failure.
- **Take things apart.** Give each part one role and one reason to change; separate braided concerns.
- **Be patient at the REPL.** State the problem, knowns and unknowns; inspect real values and call the owner until
  you can predict the result. Refuting a cause is progress. An estimated cost or a suspected cause is a hypothesis
  until measured on real data: profile before ranking work, and confirm a surprising observation comes from the
  system rather than the test.
- **Read the dependency first.** Datahike owns transactions, branches and history; Malli owns compiled schemas and
  contracts; SCI owns contexts; core.async owns graphs; konserve owns storage and reclamation. Compose their
  mechanisms; use a highly regarded library for tricky parts.
- **Write small functions of data.** Plain namespaced maps, immutable transformations, core functions and library
  calls. Split long functions by responsibility, and keep information visible in data rather than behind bespoke
  objects, protocols or accessor layers.
- **Fix at the producer.** Verify the cause before naming it, then change the part that produces the bad value. When a
  fix starts to look like a workaround, exemption, fallback or patch elsewhere, return to the data flow and find the
  owner.
- **Prefer values to places.** Derive from held immutable values; arguments and channels make order explicit. State
  lives colocated in its owner's value: database facts, the Flow proc's state, the SCI context, `seon.cache`.
  Mutable state is at most one validated state map per process and one per cluster, each an atom whose
  `set-validator!` is a Malli validator of its fully specced map, so every write is checked. An agent's state lives
  in its Flow proc and SCI context. The other mutable cells are justified counters: the per-function profile
  counters, the SCI interrupt flag, the running Thread and clojure.test's report counter; cache counters are
  Caffeine's `recordStats`. Any further atom needs a reason the owner accepts in plain words, and a lint checks this
  (ledger §3.19). Live host objects with no data form (Flow graphs, channels, executors, SCI contexts, listeners) live
  in exactly one registry per scope (process, then cluster execution), keyed by stable ids, passed explicitly and
  observable in status; admission and retirement are writer-checked facts. Per-call accumulation is a fold.
- **Grow by accretion.** Require no more, promise no less: open maps, validated declared members, optional additions
  and new names for changed semantics.
- **Make failure local and loud.** Each part fails without corrupting its neighbours or blinding diagnosis. Health is
  a positive observation: silence, a missing subject or a stale observation reads as unknown.
- **NO GUARDS OR FENCES. Agents never add one.** A guard or fence is a check, catch, lock, refusal class, retry,
  verdict tier, exemption, filter, fallback or recovery path placed beside a mechanism to catch it doing the wrong
  thing, or any re-check of what a contract already guarantees. It is always the wrong solution, with no exceptions,
  because the system is designed so none is needed:
  - **Contracts check every boundary.** Every function's inputs are validated on entry and its output on exit by its
    instrumented contract. A function never receives or returns a malformed value, so no caller re-checks a shape
    (`(when (map? x))`, `(if (seq ...))` on a declared non-empty value, a defensive `try`) and no body re-validates
    its own arguments.
  - **Failures are typed at the edge, defects go to the boundary.** An interop edge catches its own exception and
    returns its one specific declared error schema. A defect throws to the entrant boundary. These are the two
    catches "Nothing crashes" allows, and nothing else catches.
  - **What can fail is handled explicitly where it is called.** A caller of a function with declared variants
    handles them as an ordinary branch on data, or declares them in its own contract, because the contract names
    them. No call site is unaware of what its callee can return. Fallible calls happen at an operation's start, so
    the functions in the middle are pure.

  Inside, we know what we are doing: no code runs "just in case". When a failure is possible, find the design that
  makes it impossible, at its owner, and change that: hold a basis instead of a value, one evaluator instead of two
  compared, a compare-and-swap that git or the writer already provides instead of a lock. Never build protection
  against a failure that has not been observed. When one is observed, fix its root cause, add its regression, and
  delete any fence standing in for it in the same commit. A fence found in the code is a defect: delete it with its
  cause's fix, or file a task naming the root cause.

## Choosing the design

How the owner chooses between designs (distilled from the owner's rulings of 2026-10-15). Solve each concept once, as a
composable value-level mechanism, and reuse it everywhere it applies. Ask these in order before writing a design:

1. **Which proven system already solved this? Take its model whole.** Branch drift is git's (`git merge main`, a
   merge base, three-way, CI after the merge); a checkout runs its own HEAD; Clojure's `ns-unmap` fixes the arity.
   Copy the model, its words and its edge-case answers, not a lookalike.
2. **What immutable value already holds the answer?** Read it instead of comparing against history. A design that
   compares each item with another branch's past is missing a value to hold (a pointer, a commit, a digest).
3. **Key it by content.** The same definition with the same input is computed once for every branch and agent that
   has it; sharing is the goal, not a leak. Never key by an agent, context or place when content is available.
4. **Do the work when its data exists, no earlier.** Resolve a name when it is written; run a predicate when there is
   data to validate; render when a viewer looks. Eager work done "to be safe" is waste and usually wrong.
5. **One derivation, every consumer.** What the agent sees, what the done rule judges and what the merge carries are
   one function's result (the merge list; the AI and HTML faces of one render). Two derivations of one fact drift.
6. **Curate with the proof that already exists.** Tests decide what merges; contracts decide what is admitted. Never
   add a hand list, filter or heuristic beside them.
7. **Make effects explicit writes by their actor.** Root applies config to a branch and merges main forward; nobody
   reads another branch's state implicitly. An exception that reaches across is a missing explicit write.
8. **Accept only the exceptions physics imposes.** The one here: host-bound code that SCI cannot interpret runs as
   loaded. Every other exception is a sign the model is wrong.
9. **A fix that grows means the model is wrong.** Patching reader after reader, threading a value through every
   layer, or a several-hundred-line patch still red: stop, return to 1-2, and ask the owner if needed.

## Words and where they come from

**The language is written down, and everyone uses it.** Shared words keep agents, code, schemas and documents
describing the same things; a word that drifts splits one concept into two. The words live in this table and in
[vocabulary](docs/seon/architecture/vocabulary.md). A new concept gets its word recorded there before the word appears
in code, schemas, tasks or prose, and a rename converts every use in the same commit.

**Use the libraries for every concept they own.** Each entry is already solved by the project named. Before touching
the concept, read its source under `reference-code/<project>` and Seon's caller, then use its functions and its words.
When Seon code duplicates one of these, the fix is to delete it and call the library. If the library truly cannot do
what is needed, bring the owner the exact gap. Retired words survive in code only until their rename (ledger §3.18) converts them; new code
and prose use the library's words.

| Concept | Use (project, source) | Retired words |
|---|---|---|
| REPL round: read, eval, print, caught; one eval request per submission | `clojure.main/repl`, prepl, nREPL `eval` ([clojure](https://github.com/clojure/clojure), [nrepl](https://github.com/nrepl/nrepl)) | `pass`, `batch`, own stage names |
| function schemas, instrumenting, validation, defaults | `m/-instrument`, `malli.instrument`, `m/validator`, `m/explain`, `malli.registry`, `default-value-transformer` ([malli](https://github.com/metosin/malli)) | own wrappers, `armed`, `projection`, `supply` |
| transactions, branches, merge, data conversion | `transact` → tx-report, `branch!`, `delete-branch!`, `merge!`, norms ([datahike](https://github.com/replikativ/datahike), our fork) | `receipt`, `settlement`, `unlink`, `migration` |
| interpreters for agents | `sci/init`, `sci/fork`, SCI's reader ([sci](https://github.com/babashka/sci), our fork) | a second evaluator or reader |
| reading and editing Clojure text | [edamame](https://github.com/borkdude/edamame), [rewrite-clj](https://github.com/clj-commons/rewrite-clj) | string or regex parsing of code |
| per-evaluation dynamic state | `binding`, `bound-fn*`, `with-bindings` (clojure.core) | `world`, `environment` |
| tests | clojure.test report maps, `malli.generator/check`, [kaocha](https://github.com/lambdaisland/kaocha) | `member`, `accretion test` |
| reloading code | `require :reload`, [clj-reload](https://github.com/tonsky/clj-reload) | own dependency ordering, `adoption` |
| static analysis | [clj-kondo](https://github.com/clj-kondo/clj-kondo) analysis and findings | own analyzers |
| errors | `ex-info`, `ex-data`, `Throwable->map`, `clojure.main/ex-triage` | `fault`, own exception printers |
| printing | [fipp](https://github.com/brandonbloom/fipp) via the one value printer; `*print-length*`-style budget | a second printer, `pr-str` copies |
| configuration files | `aero.core/read-config` with `#profile`, `#env`, `#include`, `#ref` ([aero](https://github.com/juxt/aero)); Malli validates the result | own EDN loaders, profile or precedence code |
| caching | [Caffeine](https://github.com/ben-manes/caffeine) via `seon.cache`, its `recordStats` | own counters, `memoize`, atoms |
| agent execution | `clojure.core.async.flow` procs and step-fns ([core.async](https://github.com/clojure/core.async)) | own schedulers, state tags |
| durable bytes | [konserve](https://github.com/replikativ/konserve) (our fork) | own stores |
| merging vocabulary | git: branch, main, merge request, source/target branch, merge base, ours/theirs, unmerged | `land`, `landing`, `unlanded`, invented names |
| stored data | Datahike: entity, attribute, value, datom, transaction (`t`), commit (a stored snapshot, named by its commit id) | `row`, `table`, `column`, `record` for facts |

Words genuinely Seon's, recorded in [vocabulary](docs/seon/architecture/vocabulary.md): task, proof, reaching test,
turn, render function, current-state block, write-back, wake, answered t. Seams per area:
[reference code](docs/seon/architecture/reference-code.md).

## How to work

1. **Understand.** Read the owning spec, Git history, dependency source and first-party caller before designing.
   Code dives into `reference-code/` and web research are always authorized; use them instead of guessing.
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
   missing tools first. Use `eval_clj` with explicit root and cluster; JVM mode has no checked-out branch, so pass the
   connection, e.g. `(seon.cluster.boot/connection "default")`. SCI mode mutates its shared context: keep probes
   disposable, in scratch namespaces or branches with finite inputs. Reproduce with one small form, inspect the
   complete reply and installed schema, call the owner, then try the smaller algorithm. On real data: parse,
   transform, validate each value against its schema, store it (disposable branch or `d/with`), pull it back and
   compare. Try data models and ideas at the REPL before writing them down. Never load uncommitted source into
   default's JVM: try it on your own agent's SCI branch. Verify a tool's limit before reporting it. Every reply is
   also a health check: read its timing, findings and recorded errors. An error, a panic instead of an error value,
   a recorded error without its task, or an ordinary operation over one second is a finding. Record it as a task, even when it is outside your
   assignment. Record exact forms, values and timings; the edge cases found become the regressions.
3. **Design the smallest composition.** Open the owning spec or design note with one or two lines naming what
   exists, its data flow and the principal Clojure approach. State what disappears, each operation's cost and target
   complexity, the concerns separated, and the simplest alternative: dependency seam, existing function or deletion.
   Research existing libraries before writing a replacement. Line counts are a codebase budget, not a per-change
   ledger: a change need not delete what it adds, feature for feature. Write the simplest correct form of what is
   needed; when the code grows, name what it lets go elsewhere and plan that deletion. Large additions still start
   with the smaller alternative considered, and net deletion never excuses needless machinery.
4. **Pick the model by the work; every lane proves its slice** (owner, 2026-10-12 and 10-14, by measured outcomes).
   - **Haiku gathers and verifies; it never judges alone.** Digs before a design round (prior attempts, the code path,
     rulings, edge cases, library seams); a **pre-brief before every Opus or Sonnet phase** (every caller, test,
     quoted symbol and schema reference of what the phase changes or deletes, the reds on HEAD, the prior notes and
     the file collisions); inventories; red triage from stored runs by the commit that changed the behavior; **flake
     hunting** (run a suspect test alone several times at the suspect commits before anyone designs a fix); the
     **performance sentinel** after each restart (`script/seon/sentinel.clj` against its last baseline). Haiku never
     makes SCI requests as root on default (they write test runs to main): JVM mode or throwaway checks only. A Haiku
     or Gemini claim becomes a task or a decision only with a reproduction form that root or a stronger lane ran (on
     2026-10-14, 4 of 6 Haiku causal claims and Gemini's corpus-wide counts were wrong). Its
     output is evidence, verified against the code or a test before use; it does not pick tasks or decide on
     judgement.
   - **Sonnet** does clearly specified work and REPL prototypes (measured 2026-10-14: 3.5 of 4 REPL prototype questions,
     Haiku 1): implements a phase whose design and red proof are written, mechanical fixes and sweeps, renames,
     filing and retiring tasks, applying patches, narrow checks, and running the central round. When it cannot find
     the cause or a clearly correct fix in about fifteen minutes, it stops with what it measured.
   - **Opus** takes the hard problems: the semantics of the one REPL path (eval request, evaluation, render,
     evidence), the writer, the test runner, boot-critical code, design gaps a check found, root causes nobody has
     isolated, and one of the two independent plans of a design round; it designs by dogfooding (alternatives tried at
     the REPL on its own branch, the tests reaching the change, timings).
   - **Astra, an outside model trained on a different distribution,** takes the hard performance work and FIXES what
     it finds (super-linear transformations, repeated work, whole-program work per change, re-reading what the caller
     holds), and writes the other independent plan of a design round.
   - **Gemini (Flash 3.8 and Argon 4), outside models,** find root causes and judge before anything is committed
     (measured 2026-10-14 on blind tests with known answers: Flash-high found F53's exact clause from its symptom in
     7 minutes, both rejected the Fressian change later reverted as a regression, both found the fix of `17ec81f0b`).
     Flash-high is the default; Argon takes long open-ended performance hunts and independent plans. Every quoted
     excerpt and every retire-this list is reproduced before anyone acts on it. They run through `bin/agy-agent`.
   - **Fable** writes the hybrid of a design round (the tie-break between the two plans, which it did not write) and
     the periodic audit of how planning and execution are going. Never revisions, fixes or implementation: a check's
     failures go to Opus (design) or Sonnet (mechanical).
   - **Live runs: no paid Claude API.** DeepSeek product agents are the measured run; Claude Code agents act as Seon
     agents through MCP to judge whether each task's generated context is complete.
   - **Count before designing.** Before choosing between design options, measure how often the case occurs in the
     code (a design for multimethods was stopped when `src` turned out to hold none): the owner prefers the simple
     general rule over modeling a rare construct.
   - **Schedule** (root): file-disjoint lanes run in parallel; two lanes needing one file run one after the other, the
     second briefed with the first's commit; a phase waits for the files it needs rather than editing around them
     (patch files for a held file). The critical path's files go to the critical path: while a milestone's slices are
     open, a side lane touches those files only as a patch. Host-bound lanes run at most one per check slot; REPL-first
     and docs lanes run beside them. One design round is in flight at a time, and its phases are in the order before the
     next round opens (three rounds in two hours produced 26 phases and 4 landings). A design round is dig → two independent plans → hybrid → narrow check → tasks
     (see "Design by independent plans and a hybrid"). One goal probe is always in flight: a chain experiment on HEAD
     with small ordinary tasks that carry red proofs, its hand acts counted against the previous run.
   - **Every lane refuses bad changes on sight** and proves its own slice before committing (see "Prove each slice
     before it is committed"). **Every review is dual and independent**: a fresh Opus agent and Astra, neither seeing
     the other, each finding reproduced before it is fixed; a review never substitutes for the proof. A performance
     commit is proven by the cost it targets, end to end (see "Make cost follow changed inputs").
   - **Improve the mechanisms, never patch around them** (owner, 2026-10-12). Every fix strengthens the general
     machinery: the producer returns the right data, a schema-specific view is a render function, and only a gain for
     all values goes into the value printer. No trimming, counting or special case added to pass one test. A lane or
     root that finds itself adding edge cases in several places stops and asks for an audit to get back on track.
   - **Root** assigns file ownership so no two lanes edit one file at once, rules on disputes, resolves
     already-ruled questions by citing their authority, batches restarts, and merges. For a new decision with hours of
     cross-owner work, root offers three priced options: the simplest viable first, the recommendation marked, each
     with its guarantee, cost and sacrifice.
5. **Change one loadable slice.** Edit the owner in place: no second registry, renderer, runner, retry, cache or
   version-two namespace. A moved, renamed or deleted function converts every caller in the same commit — tests,
   quoted symbols, error operations and schema references included — and retires the old path. Script mechanical
   sweeps once across the full set, then lint, load and hand-fix exceptions. HEAD must boot: before committing, lint
   `src script test` with `--cache false` and compile touched contracts, schema and configuration resources with
   their consumers (shipped defaults included) from a `git archive` of HEAD plus the diff, with zero findings.
   A change to schema resources, publication, indexing, reload, boot or instrumentation is proven by publishing and
   reloading it on a branch of the live store: a fresh install is the same reload onto an empty store, never a second
   path to rehearse. Root's batched `start --head` restores the previous commit when a new JVM cannot boot.
   Designs and plans are revised in place — corrections go into their owning sections, never a parallel spec or
   appended ruling log. Correct false current claims in the same commit; keep dated audits and evidence outside plans.
   Fix problems on code you touch when that takes about fifteen minutes, within file ownership (`bin/lane add` or a
   patch); otherwise create a task and reference it (`ns/task-name`) in that var's docstring. When a design proves
   wrong, stop and report it with options instead of patching around it.
6. **Prove the installed change.** Verify publication, reload and instrumentation, then repeat the probe. A shell
   edit or disabled hook proves none of these; neither does restarting to find out. Prove at the REPL and statically
   first; root batches one restart for many commits. Prove schema changes incrementally on a branch of a live store;
   inability to reload is a publication defect, never a reason to boot from zero. Observe browser paint separately.
7. **Report honestly.** Broken things first, then evidence, changed paths, commit, sizes, net source/test lines,
   timings, memory and verification limits in the commit message. Unavailable evidence is not a pass. Commit
   measurement scripts; publication changes carry their timing. Disposable probes go in `tmp/`, reusable checks in
   `test/`. Search existing issues before recording one per defect class; evidence must outlive chat. The issue
   index is the owner's ranked schedule: assigned agents never edit it.

## The system: one JVM, many realities

**The opposite of virtual machines** (owner, 2026-10-15): reuse as much as possible, and still give every agent its own
small world to build out, change and experiment in. Isolation comes from immutable values and branches, never from
copies: a branch shares main's indexes and runs its own HEAD (like a git checkout); only what it changed is
interpreted; everything else is the one loaded program; every derivation is keyed by the content it reads, so the same
definition with the same input is computed once for every branch and agent that has it.

- **Clusters and branches.** A cluster is a Datahike branch, called main, plus its agents and shared plumbing; one
  JVM hosts many. A branch is `d/branch!`, a pointer to shared immutable indexes, never a store copy or a system to
  boot. An agent's context has one branch checked out. `d/delete-branch!` removes a branch from `d/branches`; retention
  GC reclaims its commits without per-branch cleanup. An ended branch (assignment, chain or merge) stays
  as history when `:seon.config.agent/keep-ended-branches?` holds (shipped true, owner 2026-10-16: keep them and mine the
  sessions; keeping must be cheap, and a cost that is not is a defect at its producer), else it is deleted once its
  merge publishes or root abandons it; main's attempt rows name the branch, and root's `my.agent/retire-branch!` removes
  a kept one.
- **Boot and configuration.** Boot opens the REPL before store acquisition, then builds process → store → facts →
  flow, publishing readiness at each layer. One process root holds the store's lifetime lock; Datahike serializes
  transactions. Bootstrap selects paths and binds; running configuration is database facts; secrets are
  environment-variable names. `seon.config/defaults` is a compiled constant, not a cluster's configuration. Boot
  reads each dial from the configuration it applied, never the store's value from before the reload, so a new dial exists at
  its first read. Every size, count, byte budget, heap cap, timeout and threshold is a dial whose shipped default fits
  a 16 GB machine running many agents beside the user's work, never derived from the host's RAM (development
  tooling that only speeds our own work, such as check JVM slots, may exceed it; runtime never does): a 4 GB heap cap,
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
  from source sees its definitions. Compute host-bound status per declaration, never from a hand list of namespaces.
- **An agent's context** is its SCI context; its task selects the branch it checks out: its task's `:seon.task/branch`
  (installed: its chain's branch; a new agent per task, in turn), or for root the live cluster head. Every agent, filesystem agents on their own named branches
  included, uses the one REPL entrance. Private defs, atoms and objects survive program changes advanced in place at
  idle boundaries; each eval request reacquires from the branch head, reusing content-valid derivations.
- **Reload.** A reload is `require :reload` for changed namespaces and their dependents. It goes entities → load →
  instrument → record and waits for no one. Stability comes from the checked-out branch: work that overlaps a reload
  compares the loaded program identity at start and end and pushes back, never takes a lock. Failure leaves affected
  execution unavailable until exact bytes, behavior and instrumentation converge; never call an unchanged entity healthy.
  Ordinary clusters keep their program; selected development clusters reload explicitly.
- **Merge is a root task.** An agent writes no merge request. Target (task-model T3b, owner 2026-10-17): a merge is an
  ordinary task after the work it merges, its `:seon.task/target` main; when it is done (`seon.cluster.source/done-refusals`,
  the one done rule; green at its start ends it with no model call), the done step accepts onto main once, in one
  fenced transaction. Installed until T3b: when a chain's last task is done, root's `db<->flow` merges the chain branch
  to main once, the terminal attempt as its source (and until S3b-1, one merge request per assignment). Root's tools live in
  `my.root` (`instructions`, `merge-requests`, `merge!`, `close!`, `ended`; the reconciler launches agents) and use
  git's words: a merge request is merged (`:merged`) or closed with a reason (`:closed`). `merge!` takes the whole delta
  since the merge base and commits only when it is green: the last task's proofs, every test the branch defines or
  changes, every reaching test and whatever root chose to run, contracts complete, recorded as evidence; a test already
  red on main stops it for root. Conflicts stay unmerged on the merge branch, which root repairs at the ordinary REPL
  holding both sides (the merge branch forked from main and a fork of the agent's SCI context on the source branch; ours
  is the target, theirs the source), moving data between them by identity from the diff value. Norms run in their listed
  order. The writer checks the touched entities' digests; only root's merge advances main. Local green never implies
  shared acceptance.
- **Write-back** uses the same checks, checks file bases, stages exact-span changes and reindexes to prove equivalent
  declarations before installation. Chain tasks are runtime data, never written into task files; the merge of the tasks that worked a filed task deletes that entry (TASK-CHAIN §2.6, installed until S3f-1).

## Data and contracts

- **Model from the agent outward.** The agent entity is its one-hop attachments (task, settings, messages,
  evaluations, calls), each with a declared render; context is rendering what is attached, detail one requery away.
  Tell a story, not a star: agent (current namespace, answered basis, disposition) → its evaluations in agent order,
  each with the call that produced it; no turn or run entity sits between. Namespace information lives on the
  namespace, agent information on the agent. Every stored fact names its consumer or is deleted; hiding it behind a filter or a
  missing render is not a fix. Choose each relationship deliberately: component, optional or required peer ref, identity
  value, or an edge the recording transaction moves; a statement about a living entity is a ref, an observed name is a value; a component
  is validated as its whole owning value on each write, so size trees by what each write owns.
- **Agents are compartments; root acts cluster-wide** (owner, 2026-10-12). A non-root agent holds its task's branch (installed: its chain's) and
  its own SCI context, and nothing else: it writes only that branch, never main or another branch, and never loads code
  into the platform JVM. That is what lets many agents run at once safely. Its tracked facts (task updates, its end,
  sent messages) and its code reach main only through the one merge of its branch (installed: its chain's), whose contents are the derived diff since
  the merge base (git's untrusted-contributor model: an agent never pushes). Only root acts cluster-wide: it holds main,
  and it merges, starts and stops agents and loads code. Root and an agent differ only by the branch they hold. **Agents
  transact facts; root reacts.** An agent never performs an effect that reaches main or another agent: it writes its
  end, task updates and outgoing messages as facts on its branch, and root's `db<->flow`, woken by that branch's listen
  route, executes everything that affects main or other agents (advancing the work along `needs`, the merge, message delivery,
  stopping it, starting the next agent, problem tasks). Effects local to its compartment (model calls, its own
  evaluation, its own tests in a fork of its SCI context) stay its own. Within its compartment an agent may make serious
  changes, first-party code included: the immutable database and rollback are the safety net. What each kind of entrant
  receives (database value, connection, branch, SCI fork, agent id, task, capabilities) and where its writes go is
  declared once in the owning spec, never implied.
- **Pass the evaluation's bindings explicitly.** Carry the database or connection, the registry, render profile, proc
  and recording inputs, including profiling and test ownership. The checked-out branch elides only the agent's
  db/conn; it authorizes no other ambient service.
- **Fill defaults by declared schema name, from the evaluation's bindings.** The instrumented wrapper plans each
  function's defaults once, when it is instrumented, and fills omitted inputs at call time, never from the parse (a
  call's target is known only when it runs). Any input, required or optional, whose declared schema is one of the
  evaluation's bindings (connection, writer, agent, task, profile, call sink, SCI context) comes from that
  evaluation's own bindings, never a global or another evaluation's; a database value is never bound: it is read from
  the bound connection at the call, so a call after a write sees it; this includes binding keys missing from a map
  argument whose schema declares them. Defaults are filled before input validation, so a forgotten binding never
  fails the call, and only what is missing is filled: a value the caller passes, such as another branch's database,
  is never overwritten. Other omitted optional inputs stay absent. A function whose meaning needs a binding to be
  absent (no checked-out branch) declares a different schema name. A missing required binding refuses by name. A call already matching a
  declared arity is never prefixed; caller arguments always win; structural resemblance is not identity.
  See the [data guide](docs/seon/architecture/data-modeling-guide.md).
- **Decide at the authority.** Derive at the writer or pass its decision; validate a pre-read's basis there if it
  could change. Mirrors are derived, drift-checked or explicitly historical, never silently current state.
- **Declare facts at their producer.** Search the registry before adding a key. Use fully namespaced keys, one schema
  per identity and `seon.id`; identities are minted by their owner (a task's handle by the one task writer), never
  hand-typed; no name guessing, text parsing or invented entity kinds. Production regex needs owner permission (ordinary
  `rg` searches do not). Enums describe real bounded states or dependency grammar.
- **Preserve native values.** Absence is no key, never stored nil; symbols stay Datahike symbols, not strings
  converted at each reader. Prefer `find` or `get` with a sentinel; `contains?` tests vector indices and also finds
  nil-valued map keys.
- **Validate final owning values.** Include swept refs and identity-less components; refuse missing children,
  cycles, multiple owners and exhausted bounds. Admission and completeness rules judge writes; reading committed
  entities never re-applies them. Retraction deletes; history answers what existed, as it was written. Required refs
  refuse invalidating deletion, optional refs may sweep, components cascade. Repair surviving declaration references
  in the same transaction or refuse; never invent placeholder functions or tests.
- **Separate storage from observation.** Store a value when it must outlive its subject; a stored copy of a value
  (recorded error evidence, an effect's result) is the value printer's printed value plus ids, nothing more.
- **Three places, one decision.** The full live value lives once, in `seon.cache` under its result handle (an LRU
  under the configured byte budget). Datahike holds small facts and printed values up to the one threshold dial
  (`:seon.config.eval.result/blob-threshold`, UTF-8 bytes); anything larger is a konserve blob (immutable, compressed)
  staged and committed in the same transaction as the fact holding its digest and size. The form is decided once,
  where the stored form is produced, by `seon.blob/inline-or-stage`; no other dial or step re-decides it. Never build a
  big value and trim it later: produce the stored form at the source and pass references, not copies. Stored-text
  attributes carry the registered bounded schema, so the checked writer refuses an oversized value and a later
  re-copy fails loudly at its source. Derive pulled shapes and
  attribute lists from entity schemas and selectors: a hand list beside a schema silently drops the next key. Never
  silently truncate pulls. Require each function's and test's analysis digest:
  an empty cardinality-many is not proof of work.
- **Use the database owner.** Direct `datahike.api` calls belong only in `seon.db`, store/registry, the classified
  owners of a checked-out branch and system listeners; keep `my.*` thin. Put provenance on transactions, derive write bounds
  from it and retain proposed data in bounded refusals. Reconcile config differences; resolve function symbols to
  current entities.
- **Contract every admitted function, private ones included.** Complete, well-formed, fully namespaced Malli schemas
  and reaching tests. Compile touched contracts against the packaged registry; use declared schemas or registered
  named predicates, never anonymous contract functions or incomplete forms. Justify polymorphic boundaries. Every
  bug fix adds or tightens the contract at the producer of the bad value, so that class of bug is refused at its
  source from then on; a contract is not proof of a body's error union, its regression is. Existing uncontracted
  functions are back-filled as generated tasks (`seon.contracts/task`, the [contracts guide](docs/seon/architecture/contracts-guide.md)),
  skipping those with a pending deletion task or no live use.
  A new or changed function without a complete contract: the edit hook refuses it; the REPL admits it with a
  warning beside its value that grows as the agent's uncontracted definitions accumulate; the done rule refuses a
  task that leaves one, and the merge refuses a chain that carries one.
- **Keep entry policy separate from merge.** Schema and test entry default to `:gate`, refusing before evaluation;
  explicit `:warn` admits experiments with declared findings. A missing function contract is never refused at
  the REPL: the value returns with the growing warning above. Preserve malformed authored contracts as data; never
  instrument them or substitute stale wrappers. Instrument every valid contract: invalid inputs prevent entry, invalid outputs
  refuse. Entry policy never relaxes merge acceptance.
- **Documentation is data.** A docstring says briefly what the function returns; Markdown headings (Examples, Edge
  cases, Errors, Known issues) only on public `my.*` docstrings; cross-cutting knowledge in Markdown pages. A backticked
  span reading as one fully qualified symbol or keyword is a reference; a task's is `ns/task-name`, its identity derived
  from namespace and name. A definition cites its tasks by reference in its docstring; a task's proofs are its
  `:seon.task/proofs` field, never metadata on a test (`:seon/tasks` tags are installed until S2c-2). Target: the index
  resolves backticked references into the subject's context and reports one resolving to nothing as a problem on that
  var. No dates, measurements, history or `file:line` in source: costs are profile evidence. Known issues reference
  their task. Never author what the call graph already derives.
- **The right context is the product.** The goal: every agent, ours included, works a task from the context Seon
  generates at the REPL (its current-state block, then `doc`, `source` and its references, one evaluation each), and
  that context is complete and correct. References everywhere (task plans, help, Markdown docs, docstrings) follow one
  rule and go through one resolver; every tool that pulls them in must do it well. When context is missing, wrong,
  noisy, slow or misleading (an unresolved reference, a stale line, `passed? false` with nothing run, a reply full of
  noise), fix it at its producer if it takes about fifteen minutes, otherwise file a task on that producer with the
  exact form and reply. Never work around bad context silently.
- **Write tasks as data an agent can be handed.** A task is one entity
  ([TASK-CHAIN](docs/seon/schedule/cards/TASK-CHAIN.md) §1): `:seon.task/ns` and `:seon.task/name` (the task is
  `ns/name`), `:seon.task/plan` (Markdown: what must become true, with its references inline), `:seon.task/instructions` (its own work), `:seon.task/proofs` (the deftests that must be green for it to be
  done) and, in the target model (task-model T1–T4, owner 2026-10-17), `:seon.task/needs` (the set of prerequisites),
  its environment (`:seon.task/branch`, `:seon.task/target`, `:seon.task/config`, `:seon.task/model`) and
  `:seon.task/execution`. A declared task lives in `resources/seon/tasks/<ns>.edn`; runtime tasks are never written into
  task files. Target: tasks are desired state on main, the observed state is facts written by their observers, and one
  reconciler (`seon.cluster.reconcile/reconcile`) closes the gap; there is no chain entity. `seon.task/create-all!` writes a
  vector of plain task maps (a template merged with a plan, `seon.task/template`) in one transaction on main, every task
  `:held`; nothing autoruns, root's `my.root/run!` is the one release, and `:seon.config.agent/max-running` caps parallel
  attempts. A single task is a task with no needs, followed by a merge task when it should reach main. Installed until
  then: `seon.task/chain!` writes a chain of task maps on its own branch linked by `:seon.task/next`, a single task being
  a chain of one. The plan flows to the next task at each advance (target: along `needs`); `instructions` and `proofs`
  never flow. The one task writer derives
  `:seon.task/references`; it is never authored. Proofs are deftests, never prose: prefer an existing failing test or a
  new row in an existing table test; write a new deftest only for a behavior no test covers; a task already green proves
  nothing; at merge root keeps a new test only if it guards a new behavior class. References are typed by plain EDN and
  must resolve: code is a backticked fully qualified symbol (Seon or library, e.g. `` `malli.core/validator` ``), a
  schema a backticked fully qualified keyword, any other file a relative path whose first segment names its base (the
  checkout, or `reference-code/<project>` at its pinned revision) with `#heading` anchors. Never write a file or line
  for code: they are derived from the Var or the analysis. A reference never points into gitignored `tmp/`. Ingestion
  and `my.task/update!` report an unresolved reference as a problem on the task. Retire a task in the commit that
  deletes its subject or makes it moot. Write the plan typed: the fully qualified symbols and keywords the work creates,
  changes and calls, each backticked inline; prose naming only the behavior costs the agent its calls searching for the
  owner (bench E1, `cards/README-bench.md` Results).
- **Use the agreed words** ("Words and where they come from" above). Keep JVM REPL and SCI evaluation, live result
  and printed value distinct; cite legacy identifiers without reviving retired terms. Correct language in scope or
  file its issue. Source comments use `;` for prose, `;;` above forms, `;;;` for runtime structure.

## Failure and execution

- **Nothing crashes.** No evaluation, reply, eval request, page, tool call, test run or boot step ever ends in an
  uncaught exception. Failures come in two kinds (owner, 2026-10-17):
  - **Expected failures are declared variants.** These are failures a caller can act on, and they only arise at an
    interop edge, where Seon calls the platform or a library: a missing file, a provider timeout, a merge conflict, a
    refused write.
    - That edge function catches its own exception and returns its ONE specific error schema, never the general one.
      Its contract lists exactly those variants (`[:or success :seon.db/read-error]`, target: one `:multi` on
      `:seon.error/type`).
    - Every caller either handles the variant as an ordinary branch on data, or declares the same variant in its own
      contract. Each caller is updated when the variant is introduced.
    - Do fallible calls at the start of an operation and pass the resulting values down. The functions in the middle
      are then pure and never see an error.
  - **Defects are thrown, never declared.** A failure that only a bug can cause, such as a malformed query on an
    immutable value or a broken invariant, is not a variant for every caller to handle. It throws, and only the entrant
    boundary catches it. The entrant boundary is the one-path fold, MCP, the web handler, Flow procs, schedule handlers,
    boot and the test runner. It turns the exception into an error map, records it as Seon's own error, and delivers it
    as data beside whatever value exists. The producer's defect is then fixed.

  Apply the law to the code you touch or write, as part of that change. Never convert code that is about to be
  deleted, and never sweep untouched code all at once. A census checks every function changed since the law was
  introduced, so new guards cannot enter while the old ones leave with their code.

  There are exactly two kinds of catch:
  - an interop edge's catch of its own exception, translated to its declared variant with the cause kept;
  - the entrant boundary.

  No other layer catches.
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
  separate `fault` concept). Every occurrence returns as data in the reply of the agent whose evaluation or call it
  happened in (the MCP reply for outside agents, the next context for Seon agents, both reading the same saved
  observation), with a result handle to follow; an agent's own mistake is only delivered. Target (the errors cut;
  today `seon.fault` records them): the entrant records Seon's own errors once per signature at main, with
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
  that eval request's bookkeeping (evaluation entities, call entity, answered t), never an effect a form already made durable;
  the work stays due, so an effect repeats unless its owner is idempotent. Until the agent-loop cut, boot closes
  interrupted runs and marks their unfinished evaluations. Everything with consequences beyond the form (merges,
  write-back, tasks, config, schema reload, boot and publication facts, recorded errors, branch lifecycle,
  deliveries) is likewise durable before its caller is answered.
- **Derive work and completion.** Work is a listened wake the agent has not answered; a message subject is literal,
  `:seon.message/from` marks inside wakes; never replay writes or effects. Context is rendered live from the agent's
  task and its references, never stored. A chain agent's first turn comes from its task's red proofs. At the attempt's
  start and after any eval request that evaluated a form, the branch's changed tests, the tests reaching the change and
  then the task's proofs run on the chain branch (reused by reach digest); all green at the start ends the task with no
  model call. One rule judges every task done, `seon.cluster.source/done-refusals` (TASK-CHAIN §2.4: proofs green, no
  regression of main, no stray red, no contract gap), and root advances the work. Every other end bails to root with a
  problem task, the task held and the branch kept; nothing relaunches by itself: root changes the held task and releases
  it again (`my.root/run!`), abandons the chain (target: cancels the tasks and every task that needs them) or builds a
  new one. A task whose desired environment differs from the observed one is restarted clean, never advanced in place
  (target, task-model T4). Proofs guide the agent and the fix is code or data
  updates; there are no question tasks or answer verb (owner, 2026-10-16). `my.task/send-back!`,
  with a required reason and a counterexample, bails a broken or impossible task to root; an agent's other task verbs
  are `my.task/current` and `my.task/update!` (`clojure.core/update` on a member), and
  success has no verb; a reply is `my.message/send` then `my.turn/wait`; a model agent's own `:work` task ends by
  `my.turn/wait` or the call cap; the answered basis is `:seon.agent/answered-t`, and system turns and context capture
  are gone. Archive agents; budget exhaustion is loud, and a resumed task's next attempt has a new attempt and agent
  identity.

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
  schema, never a separate fact; like any function, its omitted declared inputs are filled by schema name.
- **Return data, not comments; renders return data.** What comes back to an agent is data whenever possible: a reply
  is one map whose keys carry the value, its handle, its timing, its errors and its findings; comment lines are
  confusing next to data and agents repeat them. A render function takes the value and returns data, never forms to
  execute:
  - The AI face returns the pruned, processed data the agent cares about; the value printer prints it. Offered
    operations and elision or requery forms are part of that data, shown so the agent may run them, and never run by the
    render step.
  - The HTML face returns hiccup: a thing with a look and its operations.
  - The form face (`:seon.render/form`) returns the form that shows how this data can be found again, e.g. its
    requery. It is shown as data beside the value and is never executed by the render step.
  - A render runs once, when the reply that shows it is built, inside the viewer's SCI context. It is READ-ONLY: it gets a
    database value, and no connection or writer. Its database reads are captured as read evidence, and the render is
    cached by what it read, so a changed read re-renders it.
  - Work with consequences belongs to the evaluation that caused it. For example, a defn's eval request schedules its reaching
    test agents; the Var render only reads their status.
  - A render is cached by the value's content digest, so nothing renders twice. A model call stores its basis, not
    its prompt; a history unit re-renders only when its content, profile or the printer changes.
  - Render output goes straight to the value printer, which never re-dispatches. A render that wants a nested thing
    rendered calls that render explicitly.
  - A render that fails falls back to the value itself, printed, with one error beside it. Failed rendering gives an
    error; error base and satisfied schema renderers compose; an attribute request stays an attribute request.
- **Apply presentation limits once.** The value printer holds the one budget and spreads it across the value's shape;
  nothing else clips, and nothing prints unbounded: a log line, exception message, test report or HTTP error that
  prints a value outside the value printer is a defect. Printing is rendering with two consumers on one pipeline: AI agents
  through MCP and humans through the browser share the same selection, node tree and cut retrieval; only the final
  output (text or hiccup) differs. Save the exact printed value, select whole prompt units, never clip HTML or historical
  units again. Keep query-work bounds and deadlines separate; elisions name bound, count, path and requery, with
  cuts counted. Derive missing profiles at entry or refuse explicitly. Build minimal infrastructure. Report ugly
  output, and state the size of anything an agent sees in estimated tokens, never bytes or characters.

## Proof and performance

- **Test the real system with injected inputs.** Plain `deftest` or contracted test functions declaring their
  inputs, the same for humans and agents. The harness prepares its databases and contexts once through canonical writers and helpers —
  every fixture write carries what the writer checks, digests included — surfaces every refusal, shares reads and
  branches writes from a captured commit. Use real SCI, instrumented contracts and fixed render profiles; substitute only
  external effects, as explicit values. No hand-built entities, bespoke fixture machinery or Var redefinitions. Tests are
  deterministic: generated inputs are bounded so every seed passes.
  A test checks one behavior, asserted on DATA: the value, the render's data, the stored fact or the error's key that
  behavior is about, and only that part, so unrelated changes never break it. Printed text is asserted only by the
  value printer's own tests and a few declared golden transcripts. A test body is a program run through the one path,
  so its failure values reach the runner's report through the value printer. An assertion message is prose and never
  prints a value.
- **Run tests as agents run.** Fork the context onto the test branch, check that branch out, run the body in its test
  var's context, delete the branch. Call the agent's owners, never a test-side copy. Ordinary work uses the main JVM; only
  platform/bootstrap, declared destructive and host-bound work gets isolated JVMs. If an edit cannot reach the main
  JVM, report the precise gap. No JVM-global test state (loaded program identity included) or restoring reloaded
  roots; preserve instrumentation (a test instruments only its own Vars, never the whole JVM); never `alter-var-root`,
  `with-redefs`, `load-file` or `intern` over any Var. A test driver waits for what production waits for. Stop
  graphs before retracting their facts; release resources even if setup or cleanup fails.
- **Require current positive proof.** Select tests by current dependencies and each test var's actual tested program
  and input evidence; past observed reach is advisory. Unknown or red cannot reuse green. Record completion even
  with no instrumented calls; a recording failure is not success. Keep one regression per behavior class, and destructive
  isolation until proven confined.
- **Prove each slice before it is committed; run the broad tiers centrally.** An ordinary function changes REPL-first: the
  lane `defn`s it on its own branch in the main JVM, the reaching tests run there incrementally (unchanged green
  test vars reused by reach digest), and the file is written back from the branch. It commits through `bin/lane commit
  LANE --branch-evidence AGENT PATHS`, which checks that each committed file declares exactly its branch's definition
  digests and that every test reaching them holds green evidence current on that branch, lints, and boots no JVM; a
  changed declaration no test reaches is unproven. Host-bound and platform slices (a declaration SCI cannot
  interpret, the evaluator, writer, test runner, boot, schema publication, schema resources, configuration,
  dependencies, `bin/` and `script/`) commit through `bin/lane commit LANE -m MSG --test NS... PATHS`, which runs
  `bin/seon check`: the candidate archive (HEAD plus the slice) is published and reloaded on disposable branches, then
  a throwaway JVM boots from it and runs the named tests through the one runner; any red, boot failure or lint error
  refuses the commit. Name the tests reaching the change. Root restarts default after each batch of commits and runs
  platform/affected integration and the bulk tier once, not per edit. Classify each red from stored runs by the
  commit that changed its behavior, then delete tests of deleted machinery, update retired assumptions or fix the
  surviving owner; replacement behavior tests are committed with the cut; never wholesale-triage reds. Test-selection inputs are
  `seon.test.cache/input-roots`: documentation never publishes or widens test selection; fix a tool that does so before
  proceeding. HEAD must load and the named probe answer.
- **Make bounds honest.** Tests fail beyond their declared bound, default five seconds; a longer bound carries a
  reason naming the measured operation, its numbers in profile evidence. Distinguish acquisition from warm execution; priming never
  excuses a timeout. A timing proof compares parent and change in the same run, or asserts allocation, call counts or
  CPU time, so concurrent checks cannot fail it; a wall-clock bound alone flakes under load. The [testing skill](.agents/skills/clojure-testing/SKILL.md) names installed enforcement.
- **Make ordinary work sub-second.** Profile every contracted call; time boot, reload, publication, probes, tests
  and transactions; measure memory. A result over one second names the responsible instrumented functions and the causing
  agent fixes the cost before continuing, or justifies beside the number its proportionality and why it cannot be
  sub-second. Over ten seconds needs explicit owner authorization and an issue even when justified. "Cold",
  "expected" and "known cost" are not reasons; reject reports lacking these numbers.
- **Make cost follow changed inputs.** Eliminate whole-program work per change and per-call work that belongs once
  per evaluation. Branch creation is not full context construction. Hash admitted source inputs; compare commit
  identities for an unchanged reload. A performance commit is proven by the cost it targets: a
  `seon.profile/measure` assertion on the end-to-end probe (the request or stage it claims to make cheaper), timed on
  parent and change with the same probe on a quiet JVM; a count of calls, reads or allocations is supporting evidence,
  never the proof. A measured regression, or no measured gain, is not committed, and a change to a stored encoding
  needs a measured win (owner, 2026-10-12, after f18ab95e3 committed a measured regression). Judge cost against what the work must do (what it is proportional to), never an invented
  target: 10× over that expectation means wrong work to find and fix. Every policy number is a config dial sized for a
  16 GB machine.
- **Reuse content-valid work; derive without stamps.** Key derivations by what they read: content digests or the
  dependency's upstream change tracking. A commit id identifies a database value, not an excuse to recompute after
  unrelated writes. Memoize functions of immutable values; no writer `vary-meta`, transaction-carried candidate
  registry or atom beside a connection. Use Datahike commit ids and definition digests for identity,
  `versioning.cljc` for lineage, `history`/`as-of` for time, `d/listen` for notification and the writer for
  serialization; cite the seam first. Reads, renders, probes and evaluations never invalidate derived state; count
  misses and recompute only reached invalid inputs.
- **One central cache, bounded and configured.** Every Seon cache is a kind in `seon.cache` (Caffeine), keyed by
  content so nothing is invalidated, weighed at insert and bounded by one configured byte budget; dependencies and
  external tools keep their own caches where correct (Datahike's, clj-kondo's) under configured dials; no `memoize` or
  atom holds derived state. Link shared caches into every root, snapshot and run. A cached SCI context is worth only
  the evaluation cost it saves: evict it rather than grow.

## Operating and collaborating

- **Work as a Seon agent.** Our own lanes are the first Seon agents. Root files each slice as a task
  (`seon.task/chain!`) whose proofs are red (an existing failing test, a table-test case, or a measured-cost assertion
  through `seon.profile/measure`); the lane's first form reads its block, `(seon.context/current-state)`, then works by
  `defn` on its branch: the edit hook evaluates changed forms there and the proofs and reaching tests run by themselves
  after each eval request. It changes its task with `my.task/update!` (text by `my.edit/text`), writes back with
  `my.program/write-branch!`, commits with `--branch-evidence`, and files every gap in its context as a task. Every
  reply carries `:seon.repl/ms` and `:seon.repl/alloc-mb`: read them, and treat 10× over what the form should cost as
  wrong work. Tests read reply items by content (the shared test-support reader), never by position.
- **Plan before implementing; keep lanes short.** Design decisions are made up front in implementation cards
  (`docs/seon/schedule/cards/`) by an agent with full context; implementers follow one card in about fifteen minutes and
  stop and report when the code contradicts it. Work past about fifteen minutes is split. A fix that fights the design
  goes to the owner with options. `bin/lane commit` takes one `--test` per test and runs a declared-long test only when
  named by var.
- **Design by independent plans and a hybrid** (owner, 2026-10-11; revised 2026-10-12). Any design whose spec we are
  not sure of is a chain whose plan holds what we know, what to look into and the questions → a thorough **dig**
  (Haiku: buried data in `reference-code/`, our code, docs, notes and git history, including earlier attempts and why
  they failed, and the existing references and docs to build on, so no plan starts from zero or redoes done work) →
  **two independent plans** written in parallel by Opus and Astra (`gpt-6-astra`), neither seeing the other, each
  experimenting at the REPL, explaining how the existing system and libraries already do the work, adding no
  infrastructure the libraries own, and phased with a proof per phase → a **hybrid** by Fable, who wrote neither
  plan: the best of each, or something better if both missed it → a **narrow check** before any task is filed:
  every claim about the live system reproduces on default, every deletion is in the commit that converts its last
  caller (HEAD boots after each phase), and every phase names a red proof that resolves → the phases as
  implementation tasks with red proofs, and a phase-status line in the owning plan that each commit updates, so an
  unfinished phase stays visible. After the phases merge, Astra reviews the work against the plan; each finding is
  reproduced before it is fixed, at its root cause, never by a fence. The models were trained on different
  distributions; use them to design, not only to review: open-ended review of a design anchors on its author and
  turns hypothetical failures into fences. A clear slice needs one plan and the narrow check. Root runs this shape
  by hand until it runs as a chain.
- **Restart in batches.** A restart or reset is a batch: root commits every ready dependency or fork bump and pending fix
  just before it. A dependency or fork bump, or a rename touching most files, is committed only inside a scheduled
  restart batch that root opens, never mid-day by a lane: each one refuses every lane's commit until default restarts
  (owner, 2026-10-12: thirteen restarts in one day, five forced by fork bumps). Nothing is ever left staged in the
  shared index (it blocks every lane's commit).
- **Operate through `bin/seon`.** Use its installed help for start/status/open/init/config apply/stop/down/reset/
  nuke, never deleted internals. Every JVM runs a committed archive, submodules at their pinned revisions, never the working tree: `start` launches HEAD;
  `start --head` replaces a live root with HEAD, retaining store and caches; a dependency-class miss is filled before
  the old JVM stops, and our own classes are cached as boot loads them, so a miss never lengthens downtime. Reset
  deletes branches and forks from program entities, retaining caches. Nuke replaces one JVM, holds the sibling store lock through
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
  with uncommitted changes nobody claimed, and `bin/lane status` is the ledger. A claim covers one commit window
  (claim, edit, prove, commit, release), never a lane's whole run; a scripted sweep claims its files only for its
  window; a lane that stops releases every claim, leaving unfinished work as a patch with its files at HEAD (on
  2026-10-14 a 166-path hold stalled the critical path two hours). A change to a held file goes to its
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
  Never push, merge or open a pull request beyond explicit owner authorization.
- **Launch lanes deliberately.** Astra runs through `bin/codex-agent` and Gemini through `bin/agy-agent`; read the
  [codex-lanes](.agents/skills/codex-lanes/SKILL.md) or [agy-lanes](.agents/skills/agy-lanes/SKILL.md) skill first.
  Assignments cite the existing issue or spec and
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
