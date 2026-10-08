---
title: Owner rulings, current
type: reference
status: active
revised: 2026-10-08
---

# Owner rulings, current

The owner's rulings as they stand, one entry each, by topic. A ruling is revised here in place when the owner changes
it; superseded wording is deleted (git keeps it). This page states WHAT was ruled and WHERE it is implemented or
stated; the mechanism lives in the document each "Lives in" names, which must agree with it. Authority: these
rulings, then AGENTS.md (CLAUDE.md is a symlink to it). Order of work: the ledger
(`docs/seon/schedule/agent-loop-ledger.md`); questions still open: its §6.

## 1. REPL and reading

- **The REPL law.** "Execute the forms serially and they should all work just like in a normal repl." Any valid Clojure
  SCI can run works: each form sees every earlier form's effects (define, call, redefine, call again; `register!` then
  a write in the same eval request; alias retargets; the agent's own `transact!`). The stored program is each
  namespace's final state. Anything that refuses or degrades valid REPL behavior to fit the recording is wrong.
  Lives in: agent-loop.md §1.1, §3.3; AGENTS.md "There is one REPL path"; ledger §3.2 (RP rows).
- **Agent branches do anything.** "They get their own sci env and their own DB so why can't they do everything?" On an
  agent's own branch the writer never refuses a REPL-valid change; dangling references become findings beside the
  value; integrity is enforced at root's diff and merge. Lives in: agent-loop.md §1.1; merge.md intro and §2; ledger
  RP-1w.
- **An alias retarget follows Clojure's rule.** After an agent retargets an alias, reloading an earlier definition
  reloads its stored source as a Clojure file would, so that definition breaks under the new alias. A finding warns
  the agent when the retarget happens; merge refuses the broken definition. Lives in: agent-loop.md §3.3; card RP-8.
- **Reader rules.** A bare literal or qualified-symbol line is code (`42`, `result/e…`, `##NaN`); bare words (`Done.`)
  stay prose; in MODEL replies, words after a form on its line are that form's comment; MCP code runs every line;
  `#?` picks `:clj`; the namespace is kept after a throw; a valid contract refuses a bad input or return, a malformed
  contract never breaks its function; input with no forms answers `nil`. Lives in: agent-loop.md §3.3; ledger P2-P6,
  LR-READa/b (landed), RP-11a/b, RP-13.
- **Lightweight forgiveness.** "Forgiving stuff is great as long as it's lightweight." Prose becomes comments and
  delimiters are auto-balanced by one linear scan (milliseconds, a few MB per reply), never at the cost of seconds or
  hundreds of MB. Balancing also applies to MCP code, with the repair noted beside the value. Lives in: agent-loop.md
  §1.3, §3.3; ledger P6, P7a (landed).
- **Know what things are.** "We must KNOW a function was defined the way SCI knows." Evaluated code is classified from
  SCI's observation (the Var written and its metadata), file text from clj-kondo's analysis, schemas from the Malli
  registry; never from a form's head or a regex. Lives in: agent-loop.md §3.3; ledger UL-1a..d.
- **Nested definitions are saved.** A `defn` inside another form is saved from its own source span (SCI's write hook
  plus the analyzer's span) like any definition; one that closes over a local stays live-only with a short note to
  define it at top level. Lives in: agent-loop.md §3.3; ledger RP-9.
- **Entrances are read stages.** Model text, MCP code and EDN data (`{:ns … :forms […]}` answered by reply maps) are
  read stages on the one path. Lives in: agent-loop.md §1.2, §3.2; ledger RP-10, RP-16.
- **Lookups return data; library code is readable.** `doc`, `dir`, `source`, `apropos`, `find-doc` return data on
  SCI's `clojure.repl`; a library name resolves to the host Var and its pinned `reference-code/` source. Lives in:
  agent-loop.md §3.7; ledger S1 rows, H-01.
- **`*1 *2 *3 *e`** are bound at an agent's REPL only if easy and cheap; lowest priority. Lives in: agent-loop.md §3.3;
  ledger CN-23.

## 2. The eval request and its recording

- **One transaction is the recording only.** "At most one transaction" means the machinery records an eval request's
  forms and results in one transaction; it never restricts what the agent does. Lives in: agent-loop.md §1.1, §3.1;
  AGENTS.md "Recover facts, not execution".
- **The recording and entrants redesigns are approved.** `redo/pass-and-recording.md` (one eval request read and
  evaluated form by form, recorded in ONE transaction, a recording failure never hides values), with the transaction
  census folded in (`b48082ca9`: agent, eval request and task carried on the transaction); `redo/entrants.md` (tests
  and other entrances on the one path, a test's evidence on its result, a test request commits twice). Lives in:
  agent-loop.md §3.1, §3.2; ledger §1, RP, TX and EN rows.
- **A recording failure never hides values.** The value always returns; a later stage's failure is an error beside
  it. Lives in: AGENTS.md "The path is total"; agent-loop.md §3.1 FINISH, §3.6; ledger EN-9.
- **Seon's own diagnostics are stored like any REPL result**: the full value cached, the printed value stored (inline
  or as a blob, §6 below). Lives in: agent-loop.md §3.4 storage roles; ledger SR-D, SR-I.
- **A definition's pointer to the evaluation that made it is dropped at merge**; the source file becomes the record.
  Lives in: merge.md §4.5; ledger NK-8a, MA-11.
- **One shared base per cluster.** "The base in a cluster is a shared base that should be incrementally built up and
  forked as agents need different envs." Never rebuilt per eval request; after a reload it advances in place,
  measured first, rebuilt only as a fallback. Lives in: agent-loop.md §2; ledger PB-01 (landed), PB-03.

## 3. Context

- **Context is dynamic, not an infinite append.** "This is not a system where we just infinitely append everything.
  So the task is always current." Context = static prefix (`(help)`) + append-only history of only the agent's own
  forms and results + one current-state block rendered fresh at every model call from stored facts, never stored,
  each item once. No opening pass, no input pass. Lives in: agent-loop.md §1.4, §3.4; ledger §3.7 (S4, CS rows).
- **Agents see the actual proof tests** (current source) and their status, plus broken tests, in the block. Lives in:
  agent-loop.md §3.4; ledger CS-01b (landed), CS-01d.
- **The block is built from what the task names**: Seon symbols, library symbols and namespaces, and
  `reference-code/` paths, resolved with the pinned revision. Lives in: agent-loop.md §3.4; ledger CS-01c1..c3.
- **Unread messages extend `my.message/inbox`** (no new `my.agent/inputs`). Lives in: agent-loop.md §3.4; ledger
  S4-05a, CS-01c4.
- **One debug view**: the context exactly as sent, from the same assembly function, printed by the value printer's
  HTML face. Lives in: agent-loop.md §3.4; ledger DBG-01.
- **Wire formats are experiments**: transcript, chat turns and prefix continuation are adapters over stored exchanges
  chosen by configuration; measure provider token counts, never assume. Lives in: agent-loop.md §3.4; ledger CS-03a-c.

- **Every REPL evaluation is its own context unit; no block budget (owner 2026-10-08).** "each eval can output 2000
  tokens btw not the entire context ... a hairy task should pull in a lot of helpful references" and "No block budget.
  Stick with REPL evals all being individual context." The 2,000-token default is one evaluation's display budget. The
  current-state block is an ordinary evaluation under that budget (with the usual override); a task needing more
  context gets it through more evaluations (`doc`, `source`, its references), and the context is their sum. Lives in:
  agent-loop.md §2 "Context tokens"; card CS-02.

## 4. Tasks and tests

- **Tasks replace plan/steps.** Steps are shelved; their best, simplest parts may move into tasks later. Now: tasks
  running, with the block built automatically from what the task names. Lives in: agent-loop.md §3.4; ledger F8 (shelved).
- **Assignment refuses a task with no proofs or whose proofs are all green.** Tests get proofs in their setup.
  Lives in: agent-loop.md §1.7; AGENTS.md "Derive work and completion"; ledger S6-06a.
- **Tests run automatically, all relevant ones.** "I'd prefer if we just auto ran all relevant tests so the agent
  understands they need to fix the other tests if it's an intentional refactoring." After any eval request that
  evaluated a form, the task's proofs, then the tests reaching the change, run on the agent's branch as a serial step
  inside the eval request, recorded with it; unchanged results are answered from their record; the last version wins;
  a broken test is a blocker the agent sees and fixes; nobody calls `my.test/check` by hand. The step's time limit is
  a dial defaulting to 10 s; what it leaves continues at the next eval request. A same-request check sees the new
  definition. Lives in: agent-loop.md §1.7, §3.5; ledger F4a-d, MS-11b.
- **Replies and ending.** A reply is `my.message/send` then `my.turn/wait`; `my.turn/complete`, `my.agent/done` and
  `seon.run/complete` are deleted; `my.task/complete!` only gives a task back, with a required reason (an impossible
  task bails early). `(help)` and the static prefix say plainly how to end a session. Lives in: agent-loop.md §3.5,
  §3.7; ledger F7, H-01.
- **Answers are specced data.** A question task declares its answer's Malli schema; `my.agent/answer!` validates the
  answer and stores it as ordinary Datahike data; the proof reads it; done when the proof passes, nothing merges.
  Scratch values stay live in the agent's Vars; no blob path until a task needs one. Lives in: agent-loop.md §1.7,
  §3.5; competency-spec.md §4.5; ledger F6, A1.
- **A code task with no change** (proofs green, no change against main) is done after checking main still holds the
  tested program; nothing merges. Lives in: agent-loop.md §3.5; ledger F5.
- **A call row stores its assignment key as a value**, read once per eval request. Lives in: ledger S4-02a.
- **The competency suite is the whole-system test.** Every run ends in the real merge flow that root reviews; it
  includes an impossible task (early bail with a reason), a research question and an unanswerable one, and realistic
  refactors that conflict with the moving target (rename plus new caller, contract vs body change, schema evolution
  vs target data, two agents in one namespace). Lives in: competency-spec.md §1-§8; agent-loop.md §3.10; ledger §3.11.

- **References (owner 2026-10-08).** Code is referenced by a fully qualified symbol, a schema by a fully qualified
  keyword, any other file by a relative path whose first segment names its base (the checkout, or
  `reference-code/<project>` at its pinned revision), with `#heading` anchors; no custom EDN tag or reader. File and line
  are always derived (Var metadata, clj-kondo analysis, the stored schema), never written. Ingestion reports an
  unresolved reference as a problem on the task, never a refusal. A task's proofs are the deftests carrying it in
  `:seon/tasks`, never prose. Lives in: `references/references.md`; cards CS-01c1..3; task census migration.

- **One reference rule everywhere; Markdown for docs (owner 2026-10-08).** Docs are Markdown. Task intents, Markdown
  docs (code spans and relative links) and docstrings are parsed for references by ONE resolver under the same rules;
  the edit hook checks every edited doc, task file and docstring for references that don't resolve. Lives in: cards
  REF-1, REF-2, CS-01c5, TASK-CHECK.

- **Cost and refactor tasks are proven by measured cost (owner 2026-10-08).** A proof may assert measured cost through
  one test-visible measure (reads, armed calls, allocated bytes, ms as data, from JVM counters so it sees compiled
  code), so a refactor or cost task has a red proof before the fix and is assigned like any other. Lives in: lane
  eval-alloc (the measure); pilot note `dogfood/pilot.md`.

- **A same-submission check runs right after recording (owner 2026-10-08).** A `my.test/check` naming definitions made
  earlier in the same eval request answers that it runs after this request records; the automatic test step runs those
  tests right after the one recording transaction and puts their results in the same reply. One transaction stays
  one; nothing tests uncommitted values. Supersedes "a same-pass check reads the live world" (row MS-11b). Lives in:
  agent-loop.md §3.5; my.test check; F4 step.

## 5. Merge

- **Root keeps only new behavior (owner 2026-10-08).** A task's proof first tags an existing failing test or adds a row
  to an existing table test; a new deftest only when no test covers the behavior. At merge, root keeps a new deftest
  only if it guards a behavior class no existing test covers (one regression per behavior class); otherwise it is
  folded into a table row or dropped, decided in the merge review and recorded on the merge request. Suite growth
  follows distinct behaviors, not task count. Lives in: AGENTS.md "Write tasks as data"; merge.md §4.

- **Git's words.** Base merge and branch concepts, verbs, functions and data on git, so every agent understands them
  at once: main, branch, checked-out branch, merge request (`request-merge!`, `merge-requests`,
  `close-merge-request!`), source and target branch, merge base, merge branch, ours and theirs, unmerged, behind,
  norm. No invented acronyms (S, MA-, RP-) in anything agent- or owner-facing. Lives in: merge.md "Words";
  vocabulary.md git rows (GV-1 landed `22910a03a`); ledger §3.16 (GV-2..6).
- **One `merge!`, like `git merge`.** It commits itself when clean and every test on the merge branch is green;
  otherwise it stops with the merge branch kept; resolve with `checkout!` or `defn`, then `merge!` again or
  `abort-merge!`. A merge always lands on the request's target branch (default: the main it branched from). Lives in:
  merge.md §1, §3, §4; ledger MA-3, MA-5a/b, MA-11, MA-14a.
- **A test already red on main before the source branch existed stops the automatic merge and asks root.** Lives in:
  merge.md §4; agent-loop.md §3.4 broken tests.
- **A newer green commit updates the same open merge request**, as a push updates an open pull request. Lives in:
  merge.md §1; ledger F5.
- **Norms** (Datahike's word) carry data conversions: a contracted pure function `db → tx-data`, named on the merge
  request in a fixed order as a vector, run once inside the merge, recorded; bounded like any operation (over 1 s a
  finding, over 10 s the merge stops for root). Lives in: merge.md §4 Norms; ledger MA-8a-c.
- **Automatic merging** runs on root's ordinary agent path, entered from a schedule; root is just an agent with a merge
  task, and a Seon agent must find root's job easy. Lives in: merge.md §5; agent-loop.md §3.10; ledger MA-12, MA-17.

## 6. Storage, cache and blob

- **Storage roles.** The cache holds the full live value; a database fact holds small facts plus the printed value up
  to 4,096 bytes; a blob holds anything larger, the fact keeping its digest and size; no second printed copy is ever
  stored. Lives in: agent-loop.md §3.4; ledger §3.17 (SR rows).
- **Live results live in `seon.cache`** as an LRU under its configured byte budget, with no count limit. Lives in:
  agent-loop.md §3.4; ledger SR-H.
- **Datahike's node cache is byte-bounded.** Its soft-referenced node retention is replaced by a byte-bounded cache
  whose size is a configuration dial. Lives in: architecture.md §2; ledger DH-4a, DH-4b.
- **A finished agent's branch is deleted** once its task is done or given back and its merge request is closed, so
  history and blobs can be collected. Lives in: merge.md §4.5; clusters-branches-contexts.md item 9; ledger
  SR-BRANCH, MA-15, LR-RETIRE.
- **Cross-branch revisions** for content-keyed caches come from our Datahike fork. Lives in: ledger LR-CACHEb.
- **Datahike subtree counts are kept on every write** in our fork's sorted set (after verifying that writes drop them).
  Lives in: ledger DH-1, DH-2.
- **Ordered data is stored as vector cells**, never by transaction order; no new fork value type now. Lives in:
  merge.md §4 Norms; ledger §3.20 (VEC).
- **Function source stays in datoms**; an oversized function is the defect. Lives in: ledger §3.22 (AU answers).
- **Count UTF-8 bytes with Guava** (`com.google.common.base.Utf8/encodedLength`, Guava a direct dependency pinned to
  the classpath version); no byte array built to measure. Lives in: ledger DEPS-1.

## 7. Memory, cost and dials

- **No arbitrary targets.** "Use common sense and ask whether we are likely doing something wrong rather than
  arbitrary limits for both time, cpu and memory." Every cost is judged against what the work must do (what it is
  proportional to); 10× over that expectation means something is wrong and must be found; invented MB or ms targets
  are deleted. Every regression is reported with its numbers (this replaces the old "20% or 50 ms" rule). The owner's
  own numbers stay: 20-40 MB transient per model eval request is fine, never GB; a 16 GB machine; a 2,000-token raw
  return; AGENTS.md's 1 s and 10 s. Lives in: AGENTS.md "Make ordinary work sub-second", "Make cost follow changed
  inputs"; agent-loop.md §1.8, §2; merge.md §8; errors.md §6.
- **Never park a budget breaker.** "There is no world where it's acceptable for a 945MB per model pass cost." A known
  breaker on the main path is stop-and-fix in the same turn, pulling a deleting slice forward if that is the fix.
  Lives in: agent-loop.md §1.8.
- **Memory.** "I mostly just don't want GB of memory." Transient allocation may exceed 20-40 MB when it is garbage
  collected; never GB per operation; small retained memory per agent and per cluster base. Lives in: agent-loop.md §2.
- **Size for a 16 GB machine; no hardcoded limits.** "We should have anything like that be config numbers and sized
  for a 16GB machine." Every size, count, byte budget, heap cap, timeout and threshold is a configuration dial whose
  shipped default fits 16 GB with many agents beside the user's work; nothing derives a default from host RAM; a
  hardcoded policy number is a defect. Approved defaults: heap cap 4 GB; `seon.cache` 512 MB with live results
  inside it; Datahike node cache byte-bounded; inline/blob threshold 4,096 bytes; blob compression floor 1 KB;
  storage I/O on virtual threads; timeouts dials at their current values. Lives in: AGENTS.md "Boot and
  configuration"; architecture.md §2; ledger §3.24 (DIAL-0 in flight, MEM-01 `c62a1d6b1`), MEM-02.
- **Aero for config files.** The config FILE layer adopts Aero (`#profile`, `#env`, `#include`; one file read by the
  JVM and the Babashka operator); Malli validation and the database reconcile of `seon.config` stay. Lives in:
  AGENTS.md "Boot and configuration"; architecture.md §2; ledger DIAL-1.
- **Output bounds.** 2,000 estimated tokens is the defect bound for a core function's raw return in ordinary use;
  declared bulk-data functions are exempt. The display budget's one override: the dynamic var `seon.print/*budget*`
  (`set!` or `binding`, like `*print-length*`) and `:seon.print/budget` metadata, which a render may also put on its
  data; a hard ceiling follows the model's window; a large override adds a short finding with its token count.
  Lives in: agent-loop.md §2 Output; ledger PR-11, §3.13 (RO rows).
- **Reviews judge reasonableness**: "don't do arbitrary limits if it's reasonable, stop the unreasonable stuff."
  Reviews cover landed work and measure. Lives in: ledger §3.14 (LR rows).

## 8. Printing and messages

- **The display budget is tuned by feedback, not guessed (owner 2026-10-08).** "We guessed at the budget. I want agents
  to tune it via feedback." The budget stays a dial with the per-form override; the system records, per reply, what
  was shown and cut and whether the agent then dug in (ran an elision form, re-read the value, raised the budget) or
  ignored it, and reports per value schema where the budget is too small or too large; agents report budget friction
  as tasks; the dial's default and any per-schema adjustment follow that evidence. Lives in: reply census; row PR-30.

- **One value printer; no clipping anywhere else.** "No clipping outside of the one value printer. I know agents are
  always doing this to cheat on budgets." A printer gap is fixed in the printer with general rules, never
  special-cased; review refuses `subs`/`take`/truncate/`pr-str` display outside the printer. Lives in: AGENTS.md
  "Presentation"; agent-loop.md §1.9; ledger §3.3 (PR rows, PR-E1..E4 enforcement).
- **Errors are printed by the value printer** as flat data maps (fixed message plus data keys); a Throwable becomes a
  cause-chain error map first; no `pr-str`-built messages, no `printStackTrace`, the agent's exception never mutated.
  Lives in: errors.md §1; AGENTS.md "Errors are ordinary values of one shape"; ledger PR-04, E-SRENDERS.
- **Elision forms are plain clojure.core** (`drop`, `take`, `nth`, `get-in`, `subs`, `keys`, `count`) on the live
  result symbol, lazy first, no Seon helpers, no database re-pull unless the live value is gone ("dig form" retired).
  Lives in: AGENTS.md "Presentation"; agent-loop.md §1.9; vocabulary.md; ledger PR-02, PR-10.
- **Lean values; renders are the cliffs notes.** Core functions return only what their contract says; renders strip
  garbage and return pruned data for the top-level value; the full value is one step away through `result/e…`, and
  sub-values dug from it print plainly; intentional bulk-data functions declare it. Lives in: AGENTS.md
  "Presentation"; agent-loop.md §1.9; ledger PR-12, PR-13, RO-8, RO-12.
- **Terse Clojure messages.** Every agent-facing message: data first, one short fixed message per error variant,
  Clojure's wording style, fixes as runnable clojure.core forms, no boilerplate, no internal names the agent cannot
  act on. Lives in: agent-loop.md §1.9; ledger §3.4 (MS rows), PR-20a-d.

## 9. Vocabulary and libraries

- **Fork freely, upstream what's general (owner 2026-10-08).** Where a library lacks a seam we need, change our fork
  instead of building around it. Each fork change is an upstream-quality commit with the library's own tests; general
  ones (e.g. Malli's `-strument-each!`, SCI's alias rule, Datahike's norm seam) are offered upstream once there is a
  community; each fork keeps a short divergence list. Lives in: `docs/seon/architecture/reference-code.md` (fork
  divergence lists); FK-* cards.

- **The Clojure way.** "Do not pretend you are writing ruby/python/whatever and reuse those concepts." Use Clojure's
  and each library's names and seams (clojure.main, nREPL/prepl, clojure.test, Datomic/Datahike, Malli,
  core.async.flow, core.cache, clj-reload, clj-kondo, SCI, git). Lives in: AGENTS.md "Use grounded vocabulary" and its
  words table; vocabulary.md.
- **Renames approved.** One submission of forms is an **eval request** (nREPL's word); `seon.batch` folds into
  `seon.repl` with clojure.main's stages `:read :eval :print :caught`; "pass" retires; the other renames of the two
  Clojure-way audits (recording transaction, reply, printed value, error, test var, checked-out branch, reload,
  registry …) follow, one scripted sweep per slice converting every caller, after the tasks-running work. The living
  documents use the new words now; code keeps its spelling until its rename row lands. Lives in: vocabulary.md;
  agent-loop.md §1.2; ledger §3.18 (CN-1..24).
- **No kinds.** "We aren't doing kinds... We need positive data recorded in the database not a type system." What a
  thing is follows from its positive data, validated by a base Malli schema plus specific schemas composed over it,
  as errors are. Lives in: AGENTS.md "Declare facts at their producer"; agent-loop.md §3.4 History; merge.md §1;
  ledger §3.15 (NK rows).
- **Stop reinventing.** "Please stop reinventing shit that already exists." Where a dependency does the job, use it
  and delete ours; keeping ours is an owner decision per case, with the gap stated. Lives in: AGENTS.md "Read the
  dependency first"; ledger §3.22 (UL rows).
- **The library audit answers** (`cards/README-audits.md` AU-Q1..Q14, Q16, Q18..Q24) are accepted as recommended;
  AU-Q15 and AU-Q17 are asked after their deleting rows land. Lives in: `docs/seon/schedule/cards/README-audits.md`;
  ledger §3.22, FK-* and DEPS-1 rows, §6 item 2.

## 10. Atoms

- **A handful of atoms at most.** "Atoms are about managing access atomically and we should be storing fully map
  specced data that is validated on every write or rejected." One validated state map per process and one per cluster
  (`set-validator!` with a Malli validator); agent state in its Flow proc and SCI context. Kept mutable counters: the
  per-function profile counters, the SCI interrupt flag, the running Thread, clojure.test's report counter. Seon's
  own cache counters are replaced by Caffeine's `recordStats`/`CacheStats`. A lint refuses new atoms. Any other atom
  needs a seriously good justification reported to the owner in plain words. Lives in: AGENTS.md "Prefer values to
  places"; ledger §3.19 (AT-1..8).

## 11. Process and lanes

- **Good solutions, not fast ones (owner 2026-10-08).** "We need good solutions not fast solutions for all of these so we
  need to either iterate and find solutions from experience and/or research agents and thinking longer ... Identify
  square peg and round hole issues and when we need to step back and ask me." Hard problems get iteration from real
  use, research lanes and independent outside checks (Astra/Sol) before a design is fixed; when a fix fights the
  design (workarounds, special cases, a dependency bent against its grain), root stops and asks the owner with
  options. Lives in: lane templates; root's process.

- **Lanes work as Seon agents (owner 2026-10-08).** "instruct agents working on a task to generate the context in the
  repl and then work in the repl on solving it just as a seon agent will be." Root files each slice as a task with a
  red proving deftest and assigns it; the lane's first form reads its current-state block at the REPL, it works by
  `defn` on its branch with tests running by themselves, writes back with `my.program/write-branch!`, commits, and files
  every friction as a task. Lives in: lane template `repl-slice.md` (pilot lane dogfood-pilot rewrites it).


- **REPL-first proving.** "We are supposed to have one main java process with the ability to run shared tests which
  are only incremental and we don't re-run what hasn't changed." Lanes change ordinary functions by `defn` on their
  own branch in the main JVM, the reaching tests run incrementally there, then write-back; a throwaway JVM only for
  host-bound platform code (evaluator, writer, test runner, boot, schema publication, dependency bumps); the commit
  gate accepts recorded green branch evidence for exactly the committed definitions. Lives in: AGENTS.md "Prove each
  slice before it lands"; ledger RF-1, RF-2, RF-4 (landed), RF-3, RF-6 (§3.27).
- **Small, fully specified slices.** "If the agents are taking this long the amount of work spec'ed is too large
  AND/OR it's too complicated and not well spec'ed." A spec-and-split pass cuts each slice to about 15 minutes with
  exact files, contracts, named proof tests and budget rows; no design decision is left to the implementer. Lives in:
  AGENTS.md "Pick the model by risk"; ledger "How to read a row" (`ready`) and cards.
- **Fix in the optimal planned order**; the end state is fast and lightweight. Lives in: ledger §5.
