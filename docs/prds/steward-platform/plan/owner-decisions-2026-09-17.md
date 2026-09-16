---
type: decision
status: open (owner rulings requested)
created: 2026-09-17
tags: [decision, steward, adoption, issue, turn, render, seon.db, store, vocabulary]
---

# Owner decisions, 2026-09-17 — read Part 1 first

This is one document for every decision left open after the overnight
steward-platform run. It is written for a reader who did not watch the night
and does not want to open twenty notes to answer a question. **Part 1 is the
background: four mechanisms every decision below depends on. Read it first.**
Part 2 is the decisions, one section each, in the same shape every time: what
the decision is about, what actually happened (with the real bytes or the
measured number and where it came from), the options, and the recommendation
with its reason. Part 3 is vocabulary corrections. Part 4 lists the questions
in one place.

Every `file:line` was opened by the researcher who cited it, on branch
`steward-platform` today. Three read-only research passes produced the
evidence; their full write-ups, with more detail than this page, are
[batch A (adoption, opening, generated issues, docstring scope)](decisions/batch-a-issue-and-adoption-2026-09-17.md),
[batch B (write admission, the prompt's budget, the undisposed turn, render selection)](decisions/batch-b-admission-render-turn-2026-09-17.md), and
[batch C (store reclamation from Datahike and konserve state, parked items, vocabulary)](decisions/batch-c-store-and-parked-2026-09-17.md).

The vocabulary used here is the dependency's: a Datahike **entity** with
**attributes** and **refs**; a database **value**; a **transaction**; an
agent's **turn** and its **evaluations**; SCI's **ctx** and **fork**; konserve's
**keys**. Where a note or test still says "receipt", "row", "run" or "worker",
Part 3 says what it means.

---

## Part 1 — Background: the four mechanisms

### 1.1 How code becomes program facts, and the two kinds of code in one process

Seon indexes source into the database as **program facts**: one `:seon.fn`
entity per function (its qualified name in `:seon.fn/sym`, its Malli contract
in `:seon.fn/spec`, its source in `:seon.fn/source`, its call edges in
`:seon.fn/calls`), one `:seon.ns` entity per namespace, one `:seon.schema`
entity per declared schema key, one `:seon.test` entity per deftest. The
indexer reads the files under the checkout's roots (`src`, `test`), analyses
every form, and writes those entities to the non-executing `:current-src`
branch. A cluster forks that branch's exact commit and so starts with the
whole program as facts. This is "publication".

There are **two kinds of code in one process**, and the difference is the whole of decision 1 (earlier drafts called them "layers"; that word is retired — it named nothing in Clojure, SCI or Datahike):

**JVM-loaded first-party code (`src/`).** This is JVM Clojure. The running
JVM loaded it at boot with `require`. When the base SCI context is built for a
cluster, each first-party function enters SCI by **reference** to its JVM Var —
`sci/copy-var*` (`src/seon/sci/eval.clj:197`, `:1168`) — so when an agent calls
`seon.db/transact!` from SCI, the JVM's function runs. If an agent evaluates a
new `defn seon.db/transact!` in its own SCI fork, the fork's Var is shadowed
**for that agent only**; the JVM, the other agents, and the base are unchanged.
To change JVM-loaded behaviour for everyone, the **file on disk** has to change
and the JVM has to reload the namespace, and the program facts have to be
re-published so the database describes the new code. The operator's command
for that is `bin/seon init --dev default --changed PATH`, which is what the
edit hook runs after every file edit in this repository, and what a lane runs
after a shell write. That reload-and-republish is what the notes call
"adoption".

**Agent-authored definitions (evaluated in SCI, persisted as program facts).** When an agent evaluates a `defn` **with a
`:malli/schema` contract** in its turn, the evaluation is settled by the turn
writer and the definition becomes a program fact in the same transaction:
`install-evaluated-rows!` (`src/seon/sci/eval.clj:938`) installs the function,
its contract and, for a schema, the declaration into the projection, and the
turn's settlement writes the `:seon.program/row` (`src/seon/turn.clj:1775`).
Without a contract the definition is kept only in the agent's private SCI
context and the agent is told so — the exact text is at
`src/seon/repl.clj:127-129`: `"<name> was not installed: every function needs
a :malli/schema contract to become part of the program."` So your model —
"if an agent wants to update a function they run defn and we index and record
it if it evals" — **is the implemented behaviour for agent-authored definitions**, persisted to
the database, no disk involved.

What an agent-authored definition cannot do is change JVM-loaded code. Last night's issue trials assigned
agents an issue whose defect was in `seon.sci.eval/read-arglists`, a JVM-loaded
function. The agent could redefine it in its own fork, but that would not fix
the platform. So the trial's opening taught the agent to edit the **file**
(`my.edit/exact!`, `src/my/edit.clj`, a digest-guarded write to a source path)
and then to run the operator's adoption through `my.shell`. Decision 1 is
about whether agents should be doing that at all yet, and if so under what
gate.

### 1.2 Turns, evaluations, history, and where text gets cut

An agent's context is its **history**: every evaluation it has made, in turn
order, rendered as REPL text. Each evaluation entity stores the source form,
the **shown text** (the exact text the agent saw, produced once at evaluation
time by the value renderer under the agent's **render profile**), the printed
output, and any error. The value renderer is the ONE place presentation is
elided: it applies the profile's token budget, depth, and child-count limits to
one result and records an **elision value** (count omitted, path, how to
requery) where it cut.

Two budgets exist in config today (`config/default.edn`, read live on
`default`):

| dial | value | what it is declared to bound |
|---|---|---|
| `:seon.config.render.agent/token-budget` (line 91) | 15000 estimated tokens (was 1024 until the owner's ruling at 09:45Z) | one shown result, in the value renderer |
| `:seon.config.ai/prompt-token-budget` (line 387) | 1000000 estimated tokens (was 32768) | the whole prompt sent to the provider |

Decision 7 is about which of these actually cuts the history today (the wrong
one does), and decision 8b is about one line of text the history used to
carry.

A **turn** opens on a wake (a message, an issue assignment, a schedule), runs
evaluations, gets a provider reply, and closes. `open?` means the entity has
no `:seon.turn/closed-tx`. A **system turn** is an ordinary turn with a reply
and no provider attempt: it holds the generated opening and any re-evaluated
reads. The PRD's session-continuation ruling of 2026-09-10 says a closed turn
whose last form returned neither `my.turn/complete` nor `my.turn/wait` simply
re-opens the session — the agent gets another turn.

### 1.3 Write admission and Datahike's `:db.fn/call`

Every write goes through `seon.db/transact!`, which validates the transaction
data against the declared entity schemas before handing it to Datahike. For a
map that carries an identity attribute (say `:seon.schedule/id`), admission
finds every entity schema that requires that attribute and validates the
**whole map** against each of them (`write-map-error`, `src/seon/db.clj:2849`;
the entity-schema pass at `:2871-2889`). That check runs against the database
value **before** the transaction (`src/seon/db.clj:3030` uses `(d/db
connection)`).

Datahike decides, inside the transaction, whether that map creates a new
entity or updates an existing one: `upsert-eid`
(`reference-code/datahike/src/datahike/db/transaction.cljc:640-671`) resolves
the identity through the AVET index, and if it hits, the existing entity id is
reused. Datahike also offers `[:db.fn/call f & args]`: it calls `f` with the
database **as it stands at that point in the transaction** (`:1152-1153`, with
`db` bound to `(:db-after report)` at `:1238-1240`) and splices whatever
transaction data `f` returns (`:1306-1307`). This repository already uses that
for exactly this kind of question — `src/seon/schedule.clj:72-78` says,
verbatim: "This function runs through `:db.fn/call`, so absence is decided by
the serial writer rather than by a caller pre-read."

AGENTS.md §2's owner law of 2026-08-29 is the standing rule: no seam may act
on a pre-read that its authority will re-decide. Decision 6 is one instance of
it.

### 1.4 The store: branches, konserve keys, and what `gc-storage!` does

One process root owns one Datahike store at `data/store`. It is a konserve
file store: **a single flat directory of `.ksv` files**, one per stored
object. Measured today: 1,881 files, 305 MB, average 164 KB, zero
subdirectories. Each cluster is a **branch** (a head pointer onto a commit
chain), plus `:current-src` for the published program and `:db` for genesis.
Datahike's indexes are persistent sorted sets with copy-on-write nodes at
branching factor 4096; `keep-history?` is true, so there are six indexes. One
changed datom rewrites a leaf node in each index; the old leaf stays in the
store until something collects it. That is why the store grows at roughly
1 GB per hour under gates and lanes (measured: 1,881 → 3,359 → 3,497 keys over
about fifteen minutes today), while a commit itself costs only the delta.

Datahike's collector is `datahike.api/gc-storage!`
(`reference-code/datahike/src/datahike/gc.cljc:83-117`). It marks everything
reachable from every branch in the roster, then sweeps unreachable objects
written before the store's **safe point** (an in-flight-commit guard,
`gc_guard.cljc:1-44`, computed automatically). Its one argument that matters
is `remove-before`, an instant: commits **older** than it are not marked, so
their superseded nodes become collectable. With the default (epoch), the mark
walks every commit's complete ancestry (`gc.cljc:41-42`, `:72`), so a plain
call collects almost nothing. This was measured on 2026-08-02 in a retained
script: plain call 90 → 90 objects; with a cutoff 90 → 4, 95.3 % of bytes.
One of our own notes claims the opposite and is wrong; batch C corrects it.

The collector is safe to run with live writers and many branches when it runs
in the JVM that writes (`gc.cljc:108-117`; our config is `:writer :self`).
The "single-branch only" caveat in another note belongs to a different
Datahike collector, `datahike.online-gc`, not to `gc-storage!`.

---

## Part 2 — The decisions

### Decision 1 — Should agents change first-party source on disk, and under what gate?

**What this is about.** Section 1.1: an agent can already extend the program
by evaluating a `defn` with a contract; that is agent-authored definitions and it persists to
the database. It cannot fix JVM-loaded code that way. Last night's issue trials
taught issue-assigned agents to edit `src/` files and adopt. You asked what
edits we are talking about: **these ones, and only these** — file edits to
first-party source, made because the assigned defect lived in the JVM.

**What actually happened.** Seven trial sessions ran on an isolated cluster
against one real issue in `seon.sci.eval`. The one session that wrote a
correct fix — a two-site change to `read-arglists`, applied by two successful
`my.edit/exact!` calls — then ran the adoption exactly as taught:

```clojure
(my.shell/run! {:my.shell/argv ["bin/seon" "--root" "/Users/sean/src/seon/tmp/issue-trials-root"
                                "init" "--dev" "trials" "--changed"
                                "/Users/sean/src/seon/tmp/issue-trials-wt/src/seon/sci/eval.clj"]
                :my.shell/cwd "/Users/sean/src/seon/tmp/issue-trials-wt"})
```

and got back

```
The foreign process was terminated when its evaluation reached its time limit.
```

The bounds and the times, from batch A:

| what | value | source |
|---|---|---|
| shell time limit | 30,000 ms | `config/default.edn:197` |
| evaluation time limit, which also reaps the child | 30,000 ms | `config/default.edn:127`; `src/seon/shell/jvm.clj:272-296` |
| one measured development adoption, edit to converged | 221,788 ms | `research/complex-issues-as-schema-spec-2026-09-16.md:237` |
| one adoption queued behind two other publications on the root's lifecycle lock | 244,051 ms, exit 143 | `research/arming-includes-referenced-schemas-2026-09-16.md:258-260` |

Note also what `bin/seon init --dev` is: a Babashka process that takes the
root's lifecycle lock, connects to the running JVM's prepl, and evaluates
`(seon.cluster/refresh-source! …)` **inside that same JVM**
(`script/seon/fresh_operator.clj:2404-2406`). The agent was being taught to
shell out of its JVM in order to call back into it.

**Your position, as I read your answer.** Agents change the program by
evaluating definitions that we record. Writing source to disk is a later
concern that needs a plan and must be gated on checks passing first. I agree,
and it changes the question from "which adoption path" to "what is the gate,
and what do issue-assigned agents do until it exists".

**Options.**

1. **Agent-authored definitions only, until the gated write-back is designed (recommended).**
   Issue-assigned agents fix what they can fix by evaluation: agent-authored
   functions, schemas, tests, and data. Issues whose defect is in `src/` are
   not assigned to agents yet; the generator can still open them and the
   detector can still close them when a human lands the fix. The design work
   for the disk path becomes its own PRD chunk with a stated gate. *Guarantee:*
   no agent write reaches first-party source until the gate exists. *Cost:*
   the issue trials cannot be re-run on a `src/` defect until then; the one
   measured success stays unrepeatable. *What we give up:* nothing that works
   today.
2. **Build the gated path now, as one declared request.** A successful
   `my.edit` write to a `src/` path does not adopt by itself; it produces a
   **candidate**: the changed file is analysed, the tests reaching the changed
   functions (`seon.fn/tests-reaching` over the program graph) are run in a
   candidate SCI context, and only a green candidate is adopted through the
   existing public owner `seon.cluster/refresh-source!`
   (`src/seon/cluster.clj:2344`) and committed. The whole thing runs detached
   through `my.background` (600 s bound, `config/default.edn:36`), returning an
   effect id the agent polls. *Guarantee:* the gate is the same reach-selected
   tests the cold gate uses; a red candidate never touches the JVM. *Cost:*
   a design and an implementation slice on the effect owner and the adoption
   owner; concurrency between two agents' candidates is the same monitor two
   lanes contend on today. *What we give up:* adoption stops being
   operator-only.
3. **Keep the shell path and raise its bounds.** Rejected on the evidence:
   two process-wide deadlines would have to move for one caller, and neither
   covers the 244 s lock queue.

**Recommendation and why.** Option 1 now, with option 2 as the design to
write. Your instinct that this "needs to be gated on passing a bunch of checks
first" is the design law in §2.3: the bound and the check belong at the seam
that admits the work, and today no seam admits an agent's disk write to the
JVM — the write simply happens, and the adoption is a separate, unbounded
shell call. The pieces of option 2 all exist (candidate SCI contexts, reach
selection, `refresh-source!`, the detached effect arm); what does not exist
is the ONE request that composes them with a refusal on red. That is worth
designing properly rather than assembling overnight.

**Question 1.** Do you confirm: agents stay on agent-authored definitions now; `src/` defects
are not assigned to agents; and the next design chunk is the gated candidate
path of option 2? If yes, what is the gate you want — reach-selected tests
green in a candidate context, the platform tier too, or something else?

---

### Decision 2 — Which generated first turn does an issue-assigned agent get?

**What this is about.** When an issue is assigned to an agent, the agent's
first turn is generated: a short block of REPL text that tells it what it is
looking at. Last night seven different blocks were tried, one per session, on
the same issue, with `deepseek-flash` and a 20-provider-turn budget each. The
block is selected by a per-agent config dial, `:seon.config.render/issue-opening`,
whose value on `default` today is `:bare`.

**What actually happened.** The measured table, verbatim from
`research/issue-context-trials-2026-09-16.md:79-90`. "Fix written" means the
agent produced a correct source change; "eval errors" counts evaluations that
returned an error value.

| opening | bytes | provider turns | evaluations | eval errors | first edit call at evaluation | fix written |
|---|---|---|---|---|---|---|
| bare: two lines naming the issue | 84 | 22 | 37 | 9 | 34 | no (called a function that does not exist) |
| plan-first: a plan block plus two exact completing calls | 829 | 21 | 40 | 13 | 23 | no (same wrong function) |
| evidence-first: the cited functions' docs and spans inline | 2,229 | 22 | 42 | 6 | never | no |
| walkthrough: a numbered procedure | 1,795 | 23 | 44 | 12 | never | no |
| questions: prompts the agent to ask itself | 863 | 27 | 45 | 9 | never | no |
| namespace-picture: four lines, below | 160 | 21 | 32 | **5** | 25 | **yes** |
| minimal-retrieval: offers a retrieval function | 231 | 21 | 32 | 4 | never | no |

The namespace-picture block, complete:

```clojure
;; I am the steward of these namespaces. The picture first.
(dir seon.sci.eval)
;; One of its issues is mine.
(my.issue/status {:seon.issue/id "6effaf777327"})
```

The two completing calls from the plan-first block, which no other block
put in front of the model:

```clojure
;; The one call that proves it finished:
;;   (my.test/check {:seon.test/changed ["seon.sci.eval/directory-value" …]})
;; Change source with the digest you just read:
;;   (my.edit/form! {:my.edit/path "<path>" :my.edit/expected-digest "<digest>"
;;                   :my.edit/form {:my.edit.form/head 'defn :my.edit.form/name '<name>}
;;                   :my.edit/operation :replace :my.edit/source "<the whole new form>"})
```

Three of seven sessions called `my.edit/edit`, which does not exist, before
finding `my.edit/exact!`; 15 of the 58 evaluation errors across all sessions
were unresolved symbols. The trials note's own conclusion: one round of seven
cannot separate the openings, because the platform ceiling (decision 1) was
lower than the differences between them; the namespace-picture agent died one
step from green.

**Options.**

1. **Ship the namespace-picture block with the two completing calls appended;
   keep `:bare` as the default; delete the other four (recommended).** The dial
   shrinks from seven members to three. *Guarantee:* the shipped opening is
   the smallest block that produced work plus the only exact completing calls
   any block offered. *Cost:* the merged block has never run as one; four
   `defmethod`s and four enum members are deleted (git is the archive). *What
   we give up:* re-running the dropped blocks without `git show`.
2. **Ship the namespace-picture block unchanged and keep all seven** until
   decision 1's gate exists and a second round runs. *Cost:* four dead
   renderings stay in a config enum, which is a hand-maintained mirror of a
   decision already made.
3. **Keep `:bare` only and delete the candidate machinery.** Takes "within
   noise" at face value and throws away the trial apparatus a second round
   needs.

**Recommendation and why.** Option 1. One mechanism, accreted in place
(§2.5): the dial stays because it is how the next round runs, but its members
should be ones a decision stands behind. Independently of which block ships,
the cheapest measured win is a `my.edit/edit` entry point or `(dir my.edit)`
in the opening — it addresses 15 of 58 errors.

Note the dependency on decision 1: under option 1 of decision 1, the
completing calls in this opening must not teach `my.edit` on `src/` paths;
they teach `my.test/check` and evaluation instead.

**Question 2.** Ship the namespace-picture block plus completing calls and
delete the other four? And do you want `my.edit/edit` added as the plain name?

---

### Decision 4 — May an issue start when a detector, not a test, decides it is done?

**What this is about.** There are two kinds of issue entity. An **authored**
issue cites the tests that prove it fixed (`:seon.issue/tests`). A
**generated** issue is written by a detector function over the program facts
— for example "public function X has no docstring" — and carries a ref to
that detector (`:seon.issue/detector`). The schema declares what the detector
ref means (`resources/seon/schemas/seon.issue.edn:35`): "With the subject's
own identity value it is the issue's identity, so a detector run upserts its
issues instead of duplicating them." A generated issue resolves when the
detector stops naming its subject; that already works and was measured (the
same entity resolves and re-opens across runs with hand-edited prose intact).

A generated issue deliberately has **no test entity**: minting a `:seon.test`
fact for a deftest nobody wrote would be a lie on the very identity the test
runner selects by. But `start!`, which assigns an issue to an agent, refuses
an issue with no tests (`src/seon/issue.clj:781`: "Starting an issue requires
at least one test."). So generated issues cannot be started. The read side
already made the choice the writer has not: `check-form`
(`src/seon/issue.clj:564-573`) uses the tests when there are any, otherwise
calls the detector, and does **nothing** when there is neither — its docstring
says an empty `{:seon.test/changed []}` "promised a verification that would
pass by being empty".

**What actually happened.** The first generator run on `default`
(2026-09-16) wrote 63 issues: 32 entity maps without a render pair, 31 public
functions without a docstring; the immediate second run wrote zero transaction
data (basis `:t` unchanged). One of them, pulled live at the time:

```clojure
{:seon.fn/sym "my.agents.root/largest"
 :seon.issue/_functions [{:seon.issue/id "7cf1077d99bb"
                          :seon.issue/status :open
                          :seon.issue/title "Public function my.agents.root/largest carries no docstring"
                          :seon.issue/detector {:seon.fn/sym "seon.issue.detect/public-without-doc"}}]}
```

and the opening it renders:

```clojure
;; My issue. Its detector decides done: it resolves on the run after
;; (seon.issue.detect/public-without-doc (seon.db/db)) stops naming this subject.
(my.issue/status {:seon.issue/id "7cf1077d99bb"})
```

There are **zero** generated issues on `default` right now: the 63 were
written before the store was reset, and data is disposable by ruling. One
`generate!` call recreates them.

**Options.**

1. **`start!` admits an issue that has either tests or a detector, and
   refuses one with neither, by name (recommended).** This is the writer
   making the same two-branch choice `check-form` already makes, inside the
   `[:db.fn/call #'start-tx …]` it already uses (`src/seon/issue.clj:831`), so
   a detector retracted between the agent's read and the write cannot slip
   through. Settlement then also reads the detector when it decides
   `resolved-tx`. *Cost:* one condition and two regressions.
2. **Require the agent to write the acceptance test first.** Starting is what
   refuses, so the loop has no entry; someone else would author 63 deftests.
3. **Mint a placeholder test entity per generated issue.** No writer change,
   at the cost of a program fact for a deftest that does not exist.

**Recommendation and why.** Option 1. Facts over inference (§2.2): the
detector ref is the declared done-query, and the resolution semantics already
exist and are proven. Total boundaries (§2.4): an issue with no way to decide
"done" must stay refused, because admitting it is the absence-as-health class.

**Question 4.** Confirm option 1?

---

### Decision 5 — Which functions does the docstring detector's default run cover?

**What this is about.** `seon.issue.detect/public-without-doc`
(`src/seon/issue/detect.clj:100-144`) yields every public function with no
docstring. Its one-argument arity covers the whole program, test helpers
included, on purpose: filtering by name (`-test`, `-fixture`) would be a
name rule, one of the three banned substitutes. Instead the missing fact was
declared: every file entity carries the root the indexer walked it under
(`:seon.fn.file/relative-root`, `resources/seon/schemas/seon.fn.file.edn:5`,
renamed today from `:seon.fn.file/root` when identities became
root-relative), and the detector's two-argument arity **joins** on that fact.

**What actually happened.** Run live on `default` today:

| call | subjects |
|---|---|
| `(public-without-doc db)` — unscoped | 30 |
| `(public-without-doc db {:seon.fn.file/relative-root "src"})` | **2** |
| `(public-without-doc db {:seon.fn.file/relative-root "test"})` | 28 |

The two production subjects are `seon.flow/->CountedDroppingBuffer` and
`seon.flow/->RefusingBuffer` (constructor functions a `defrecord` emits; the
researcher flags that an earlier note listed one of them as excluded, and the
live detector names it — worth one probe when the slice lands). Ten of the 28
test helpers, for flavour: `seon.background-blob-test/binary-capability`,
`seon.cluster.source-test/activation`, `…/populate!`, `…/populate-blocked!`,
`seon.contracts-fixture/install-orders!`, `…/request`, `…/submit`,
`…/with-agent`.

**Options.**

1. **Scope the default run to `src`; the unscoped arity stays available on
   request (recommended).** Two real findings instead of thirty of which
   twenty-eight are fixtures. Same detector; the scope is a query argument.
2. **Unscoped by default.** Absolute honesty, and a ranked queue that is 93 %
   test helpers, which readers learn to ignore.
3. **`src` by default plus a separate lower-severity run for `test`.** Two
   standing invocations and two severities for one finding.

**Recommendation and why.** Option 1. The scoping is a declared fact and a
Datalog join, exactly the shape §2.2 asks for; a generated population is
supposed to be a steward's ranked queue, and a queue that is mostly noise is
worse than none.

**Question 5.** `src` by default?

---

### Decision 6 — Validate a partial update against the entity as it will stand, inside the transaction?

**What this is about.** Section 1.3. Today, a map that names an existing
entity by its identity and supplies one changed attribute is refused for
every other required key that entity already has, because admission validates
the whole map against the whole entity schema, before the transaction, without
knowing whether Datahike will create or update.

**What actually happened.** Three sightings once fixtures stopped hiding
refusals. The first, verbatim from
`tmp/orchestrator/gate-results/batch-67/named.log:206`:

```
seon.db/transact! refused transaction data at [0 :seon.schedule/zone-id]:
expected the required key :seon.schedule/zone-id with a string, got a map
missing :seon.schedule/zone-id. Fix: Supply :seon.schedule/zone-id with a
string. Offending row 0: #:seon.schedule{:id "root/maintenance/footprint-schedule", :expression "7 4 * * *"}.
```

That schedule entity already existed with a zone; the caller was changing its
cron expression. The perverse consequence recorded in the issue: writers work
around the refusal with `[:db/add …]` datoms, which admission validates only
per attribute, so the rule pushes every update onto the less validated
grammar.

**Options.**

1. **Move the entity-schema pass into a transaction function
   (recommended).** For a map whose identity resolves to an existing entity on
   the mid-transaction database, validate `(merge existing supplied)`; for one
   that resolves to nothing, validate exactly as today. The decision is made by
   the same serial writer that decides upsert-versus-create, so it cannot
   disagree with it. Per-attribute validation stays where it is. *Cost:* one
   AVET lookup and one pull per upserted identity inside the transaction; a
   refusal becomes a Datahike abort classified back to a `:seon.error` value
   at `src/seon/db.clj:3040-3055`. *What we give up:* admission is no longer a
   pure function of the pre-transaction database.
2. **Validate only supplied keys when the identity resolves, still as a
   pre-read.** Cheap, and exactly the race the owner law forbids: the entity
   can be retracted between the read and the write and left incomplete.
3. **Keep whole-schema validation; callers pull and merge first.** Every
   update site grows its own pre-read with the same race, and in practice
   writers keep using `[:db/add …]`.

**Recommendation and why.** Option 1. Malli cannot express "required unless
the entity already has it" — its map explainer checks required keys with
`find` on the value it is handed (`reference-code/malli/src/malli/core.cljc:1294-1299`)
— so the merge must precede the schema, and Datahike's `:db.fn/call` is the
dependency's own place for it. The sentence justifying this is already written
in this repository for the schedule entity (`src/seon/schedule.clj:72-78`).

**Question 6.** Confirm option 1?

---

### Decision 7 — Which budget cuts the agent's history?

**What this is about.** Section 1.2. The prompt's declared budget
(32,768 tokens) only produces a **verdict** today — `budget-report`
(`src/seon/ai/tokens.cljc:195-210`) classifies the assembled prompt as over,
near, or within, and nothing trims. The history is rendered as ONE string and
that string is fitted as a single text node by `seon.print/fit-text`
(`src/seon/print.cljc:1212-1237`) under the **value** profile's 1,024-token
budget. So the budget meant for one shown result cuts the whole history, at a
character offset, naming no turn.

**What actually happened.** Verbatim from
`tmp/orchestrator/gate-results/batch-70/named.log:1659`, a concurrency test
asking whether the agent's own history contains a message payload it was
sent:

```
expected: (str/includes? rendered (:seon.concurrency-independence-test/payload incoming))
actual: (not (str/includes? {:seon.ai.tokens/estimate 492,
  :seon.print/bound-by :seon.render.profile/token-budget,
  :seon.print/elision-unit :characters, :seon.print/omitted 1575,
  :seon.print/prefix ";; I should read this message and decide how to respond.\n(my.message/read #:my.message{:id \"9f9f0d0b\"})\n\nmy.agents.concurrency.s0-n5.a0=> (seon.db/transact! {:tx-data [#:seon.test.run{:id \"stress-s0-n5-row-0-0\", …}]})\n#:seon.repl{:valu
```

Five hundred characters shown, 1,575 omitted, ending mid-token at
`#:seon.repl{:valu`, bound by the value profile. That test reports 199
failures of 2,826 assertions in batch 70 and 154 in batch 75; a second test,
`seon.render.transcript-run-test`, shows the AI render answering the empty
string where the HTML render answers a turn header. **An honest caveat from
batch B:** the same log block also shows the agent having **no messages
attached at all** in some cases, so the 199 are at least two failure families;
the cut is proven by the bytes above, its share of the count is not yet
apportioned. The issue's own A/B in one JVM shows the current floor slice
strictly increased what these assertions can see and that relaxing them
would hide the defect.

**Options.**

1. **The prompt's own budget selects which evaluations are included, oldest
   dropped first; the value profile keeps bounding each result
   (recommended).** The prompt owner decides how many whole evaluations fit
   under 32,768 before the history is joined, and the omission is one elision
   value naming the dropped count and the oldest surviving ordinal. This is
   selection of evaluations, not a second clipping spot: the value renderer
   still does all the clipping, on each result. *Cost:* the prompt owner
   gains a selection step; the budget stops being purely a verdict.
2. **Raise the value budget on the history path.** One number; the cut stays
   character-shaped and mid-form, it just fires later. A tuned constant in
   place of the observable event.
3. **Relax the tests.** Buries the only checks that notice an agent's history
   losing its message payloads.

**Recommendation and why.** Option 1. §2.4 requires that omitted detail be an
elision value carrying count and path; today's cut carries an empty path and
names no turn, so it fails on its own terms. §2.3 puts the bound at the seam
that admits the work: the prompt's assembly is what admits history into a
paid provider call, and its declared dial currently enforces nothing.

**Ruled in part (owner, 2026-09-17 09:45Z):** the numbers were too low to be
useful; the result budget is now **15,000** tokens per evaluation and the prompt
budget **1,000,000** tokens for now (`config/default.edn:91`, `:387`, applied
live and read back). Still open: whether the prompt budget should *select* whole
evaluations (option 1) rather than remain a verdict.

**Question 7.** Confirm option 1's shape: whole evaluations, oldest dropped
first, under the prompt budget, with the value profile bounding each result?

---

### Decision 8b — When a turn ends without `complete` or `wait`, does the agent read anything about it? And may a turn with zero evaluations continue a paid session?

**What this is about.** Section 1.2's continuation ruling. Before 2026-09-09
such a turn asserted an attribute and the turn's render carried the line "It
ended without my.turn/complete or my.turn/wait". Commit `ae0e54841` deleted
the attribute and that render branch together, executing a PRD row about the
stamp. The 2026-09-10 continuation ruling then made exactly this turn the one
that re-opens the session. The peer read the turn PRD end to end and found it
does not rule where, or whether, such a notice is written; §15 says nothing
assembles the history but the walk.

**What actually happened.** One assertion still asks for the old line,
`test/seon/turn_loop_test.clj:1130-1136`:

```clojure
(is (str/includes? rendered "ended without my.turn/complete or my.turn/wait")
    "the following history carries the system-authored notice")
```

and the code that would produce it is dead: `undisposed-run-text`
(`src/seon/render/transcript.clj:537`) and the `:run` entry branch (`:569`)
are never constructed. The ruling text itself, PRD lines 979-986: "A reply is
one turn, not the end of a session. With no open turn, the session remains
open exactly when the latest closed turn has an accepted provider reply
(reply-size present and a successful attempt), its last evaluated form did
not return `:completed` or `:wait`, and turns remain under the existing
bound."

**Options for the notice.**

- **Dissolve it (recommended).** Delete the assertion and the dead transcript
  code. The agent is not told; the absent `:seon.turn/disposition` is a fact
  it can query, and continuation gives it the turn.
- **One evaluation in the next system turn**, on the precedent of a reply
  with no forms becoming one evaluation carrying a flat error. The agent reads
  the reason in its history through the one walk; the opening algorithm gains
  a non-read emitter.
- **Restore the turn render line.** Contradicts §15 directly.

**The sub-question, which matters more.** The predicate that decides
continuation, `continuing-reply?` (`src/seon/turn.clj:2856-2878`), requires a
closed turn, a reply size, an attempt without error, and no disposition. It
does **not** require that the turn settled any evaluation. So a turn that
evaluated nothing — closed by boot recovery after a JVM death, say —
continues a **paid** session. The property test's counterexample, from batch
85's log, is exactly that shape: `[true true true true nil []]`, an empty
evaluation vector. The property clause it breaks
(`test/seon/turn_work_test.clj:499-502`, "every answered closed turn is
idle") predates the continuation ruling and is stale on its own.

- **Require at least one settled, non-interrupted evaluation to continue
  (recommended).** One more `:where` clause on the same Datalog query; repair
  the property's fourth clause to allow the `:open` continuation derives.
- **Keep as is** and repair only the property. Leaves an empty derived set
  failing open onto a provider call — the write-storm shape in miniature.
- **Require an explicit disposition to continue.** Inverts the default and
  stores a flag §14 rules out.

**Recommendation and why.** Dissolve the notice (one mechanism; the
information is already a fact) and require one settled evaluation (a check
must not read absence as health, §2.4, and this one spends money when it
does).

**Question 8.** Dissolve the notice? Require one settled evaluation to
continue?

---

### Decision 9 — May a request for one attribute's render fall back to the entity's declared form?

**What this is about.** Render selection is per call: explicit value,
explicit request, namespace, schema, floor (`src/seon/render.clj:430`). At the
schema stage, `attribute-declared-producers` (`:366-387`) has a guard,
`attribute-value?`, meant for one case: the walk stamps an attribute on a
**neighbour** it reached through that attribute, and a neighbour lacking the
attribute must render by its own shape. The same guard fires when the owning
entity simply does not carry the attribute, and then a request about one
attribute silently becomes a request about the entity.

**What actually happened.** One regression pins this,
`seon.render.history-test/form-is-the-third-output-of-the-existing-selection-chain`,
red since `ae0e54841` and first surfaced when a gate finally named its
namespace. Of its nine assertions, four are expectation drift with
declarations already in the tree (the `my.*` request-map change, the inbox
edge, a message attribute with no pair). Four need this ruling, in two cases:

- An agent entity pulled with `'[*]` renders through the generic form; the
  test expects `seon.cluster.agent/situation-form`. But that form is declared
  on the **derived situation map** (`resources/seon/schemas/seon.agent.edn:122-126`),
  a different value, and the fixture seeds no agent schema key at all. Getting
  `situation-form` here would be the fallback lying about which schema it
  matched.
- A namespace with no `:seon.ns/requires` renders through the namespace form
  when the requires attribute was requested; the test expects the generic
  listing. `:seon.ns/requires` is declared with no render pair
  (`resources/seon/schemas/seon.ns.edn:23`, `:32`).

**Options.**

1. **No fallback: an attribute-scoped request with no declared pair resolves
   to the generic printer (recommended).** Keep the neighbour escape only for a
   value the walk did not pull from that entity. *Guarantee:* asking about an
   attribute never silently answers about the entity.
2. **Fall back to the nearest declared pair**, ruled rather than accidental.
   Convenient output, and the distinction between "no pair" and "absent
   attribute" disappears.
3. **Declare the missing pairs** and leave selection alone. The §2.2 answer
   for the agent case if a curated agent form is wanted; answers only these
   two sightings.

**Recommendation and why.** Option 1, and option 3 alongside for the agent
entity if you want it curated. §2.4: an unavailable observation is the typed
unknown, never absence answered as success. Then update the four drift
assertions in the same pass, not before — updating all nine to match current
behaviour would erase the only check on this chain.

**Question 9.** No fallback? And do you want a declared render pair on the
agent entity's own map?

---

### Decision 3 — What triggers store reclamation, and which cutoff?

**What this is about.** Section 1.4. The store grows about 1 GB per hour
under load because superseded index leaves are never collected; we reset the
store eight times yesterday to keep it small, at roughly ten minutes per
reset once the reseed and adoption are counted, and each reset destroys the
day's agent facts. Datahike's collector reclaims exactly what we want **only
when given a `remove-before` cutoff**, and it needs a trigger.

**What actually happened.** Measured today on `default` after reset 8, through
the connection and the shell (batch C, part 1):

| measurement | value |
|---|---|
| `data/store` | 305 MB, 1,881 `.ksv` files, single flat directory |
| konserve keys, two probes minutes apart | 3,359 → 3,497 (≈100 keys/min ≈ 1 GB/h at 164 KB average) |
| branch roster | `:db`, `:current-src` (545,650 datoms), `:cluster-default` (441,662), and, for a few minutes, a publication scratch branch carrying 430,846 datoms |
| commit chain, live | `cluster-default 6aaac88f-… parent 6aaac88a-…`; `current-src 6aaac88d-… parent 6aaac84f-…` |
| config | branching factor 4096, `keep-history? true`, `:writer :self` |

Two of our own notes were wrong and are corrected in batch C: the one that
said a plain `gc-storage!` would reclaim ~67 GB (it marks every commit's
ancestry with the default cutoff, `gc.cljc:41-42`, `:72`, so it reclaims only
debris), and the one that said the collector is single-branch only (that is
`online-gc`). The retained 2026-08-02 reproduction is right: 90 → 90 objects
plain, 90 → 4 with a cutoff. The peer's accidental real collection yesterday
with a 24-hour cutoff took the store from 107,072 keys / 12,043 MB to 93,202
keys / 10,301 MB while still running.

**The signal, keyed to numbers the dependency already computes.**

1. **The unreachable-key ratio (recommended).** Numerator:
   `konserve.filestore/count-konserve-keys`
   (`reference-code/konserve/src/konserve/filestore.clj:221-227`), konserve's
   own directory-stream count, already used by `store-exists?`. Denominator:
   the retained-file count that our collector's dry run already returns from
   Datahike's own mark (`:seon.cluster.registry/retained-files`); the real
   collection path currently throws that inventory away and returns only a
   count — returning the same map is the one code change. The existing daily
   `root/maintenance/footprint` schedule (`src/seon/schedule.clj:46-50`)
   compares the two and calls the collector when keys exceed the last retained
   count by a declared multiple. No prior collection means "never collected",
   a typed answer the maintenance facts already give, whose right response is
   to collect once. *Cost:* the mark walks reachable nodes and holds the
   reachability permit; its duration is already reported. *What we give up:*
   it is a lagging signal and cannot catch a write storm, which is already
   bounded at its own seam.
2. **Datahike's `start-background-gc!`** behind our collector (never directly:
   our collector extends reachability with blob digests). A full walk every
   five minutes whether or not anything changed, with a duration constant for
   the window.
3. **Reset as reclamation, as today.** Ten minutes per boundary, eight times
   a day, and the store is not a durable substrate.

**The cutoff is derived, not tuned.** `remove-before` risks one thing: a
non-head commit that a stored fact still names. Branch heads are always
retained. The only namer is the published source commit a new cluster forks
from (`:seon.source/commit-id`). So the safe cutoff is the creation instant of
the oldest commit any live fact names, resolved through the commit record's
`:datahike/created-at`; with no such fact, the heads alone.

**First step before any code:** one dry-run collection on `default`. It
deletes nothing and returns the candidate bytes, retained files and the
mark's duration — the honest denominator and the real cost at today's size.

**One defect to file regardless:** `gc-storage!` ignores unknown option keys,
so `{:dry-run? true}` from a caller unaware of the `:datahike.gc/*` family
performs a **real** collection. That happened once yesterday.

**Question 3.** Approve the dry run now, the unreachable-key ratio as the
trigger on the existing daily schedule, and the derived cutoff? What multiple
of the last retained count feels right as the declared dial — two, three?

---

### Decision 10 — The parked items, each with a grounded answer

**Identity strings → symbols.** `:seon.fn/sym` is stored as a string with a
schema tag saying it is a symbol (`resources/seon/schemas/seon.fn.edn:150-153`,
`:seon.search/index :symbol`), and `src/seon/fn.clj:318-332` builds a real
symbol and immediately `str`s it, three times in fifteen lines. Datahike has
`:db.type/symbol` (`reference-code/datahike/src/datahike/schema.cljc:31`) and
our bridge already maps to it (`src/seon/schema/datahike.clj:66-67`);
`seon.fn.binding/symbol` already uses it. *Recommendation:* switch at a
refork boundary, keeping the key name, deleting the `str` sites and the
mirror tag. *Probe first:* `:db.type/symbol` under `:db.unique/identity` with a
lookup ref, on a scratch cluster. **Question 10a:** approve the switch after
that probe?

**Call-arities: tuple or interned family.** Keep the tuple. An entity per
(caller, callee, arity) triples the datoms on exactly the axis decision 3 is
fighting; the join cannot dangle because of the population invariant; and it
composes with the symbol switch through `:db/tupleTypes`. **No question.**

**A `seon.commit` entity.** These are **git** shas; Datahike's commit log
names database values and is not a substitute. `abbrev`, `at`, `subject` are
`git show` output, so an entity holding them is a cache of git. Store the full
sha as a value where it is cited, as `:seon.source/commit-id` already does;
mint an entity only when a query needs to join on commits. **No question.**

**Retention removal.** Already done: there is no `:seon.config.*retention*`
family in the tree, the per-minute blob-retention sweep is gone from the
portfolio (five rows remain, `src/seon/schedule.clj:45-70`), `keep-history?`
owns fact retention and the derived `remove-before` owns snapshot retention.
**No question**, except: refuse any future Seon-side retention dial on sight.

**Cold page slice 2.** Stopped at a design gate with three priced options
already written (`docs/prds/context-generation/research/cold-page-kills-2026-09-16.md`):
constrain narrowing (4–8 h, the recommended interim), extend the dependency's
existence evidence (1–2 days, cross-owner), or land slice 1 only. Slice 1's
gate is pending. **Question 10b:** which of the three?

---

## Part 2b — Ruling added 2026-09-17 10:00Z: curated render pairs are the first agent task

The owner: "One of the tasks I want agents to do is for us to find all outputs
that do not have render functions specified for both AI and HTML and to
ensure that the data is only high quality and is curated. So we strip away all
the garbage and synthesize a better clearer response. We do not rely on the
value renderer for most things. We think about what data we have in the
system and how to display it properly."

**What exists for it.** `seon.issue.detect/entity-map-without-pair`
(`src/seon/issue/detect.clj`) already yields every declared entity map with
no `:seon.render/ai` / `:seon.render/html` pair; the first generator run
opened 32 such issues. A second population is function **outputs**: every
`:seon.fn/spec` whose output names a schema key with no pair, derivable from
the same facts (`seon.fn/output-path-report` and the projection-boundary
facts). Both are queries over facts we store; neither needs a roster.

**What the task is.** For each subject, an issue-assigned agent reads the
entity's actual data on `default`, decides what a reader needs, and authors
a contracted `render-ai` and `render-html` pair as forms in its turn (R1:
agent-authored definitions, persisted as program facts, live for every agent). The pair is
the curated response; the value renderer stays the floor for what nobody has
curated yet. The detector closes the issue when the pair is declared on the
schema. Quality is judged by reading the rendered output on the agent page
and the namespace page, per the standing order that ugly output is a defect.

**What it depends on, and therefore why these two rulings come first:**
decision 4 (start admits a detector as the done-query, otherwise no generated
issue can be assigned) and decision 9 (no render fallback, so every uncurated
attribute shows as the generic printer and is findable, instead of borrowing
a neighbour's form). Decision 2's opening then teaches the agent
`(dir seon.render)` and the schema declaration form rather than `my.edit`.

**Question 11.** Confirm decisions 4 and 9 as recommended so this task can
start, and confirm the scope: entity maps first, function outputs second?

## Part 3 — Vocabulary corrections

The law (AGENTS.md §3): Clojure's name, else the dependency's, else coin once
and record it. A coordination word with no system referent is fine in a
ledger and never in code, schema, docstring or architecture document. The
researchers met these; each is retired when its file is next in scope.

| met | where | use instead |
|---|---|---|
| "receipt" | `config/default.edn:21`, `:131`; `test/seon/turn_work_test.clj:111`; `test/seon/concurrency_independence_test.clj:2`, `:286`; a turn-test name | **evaluation** / **result** (the vocabulary table already rules this) |
| "row" for a Datahike entity in prose and in an agent-visible refusal | `test/seon/test_support.clj:323` ("Offending row"); `research/issue-generator-2026-09-16.md:173`, `:198` | **entity map** / **transaction data**; "row" stays only for program-graph and config declarations |
| "run" for a turn | `test/seon/turn_work_test.clj:39`, `:68`; `src/seon/render/transcript.clj:537` (`undisposed-run-text`, dead) | **turn**, **turn id** |
| "worker" for the agent an issue starts | `src/seon/issue.clj:762`, `:781`, and the issue notes | **agent** (`:seon.issue/agent` is the declared attribute); flagged for you rather than asserted |
| `:seon.cluster.eval/id` as the evaluation entity's identity | `resources/seon/schemas/seon.eval.edn:10` | either mint `:seon.eval/id` or record in the table that the legacy family name is kept during the cut; a live selector by `:seon.eval/id` was refused today |
| `:seon.fn.file/root` | `research/source-root-fact-2026-09-16.md:74`, `:92` | `:seon.fn.file/relative-root` since `28f1a761e` (dated notes stay, but a copied key silently matches nothing) |
| "prober" / "probers = 4" | my ledger entries | the count of concurrent sessions on `default`, mechanically io-prepl connections accepted by `seon.cluster/mcp-io-prepl` (`src/seon/cluster.clj:480`) |
| "heartbeat" | my ledger entries; AGENTS.md "commits are the heartbeat" | "periodic status entry"; "a lane's progress is its `git commit` sequence" |
| "sweep" for a status check or an edit pass | my ledger entries | reserve **sweep** for `konserve.gc/sweep!`; say "status check", "edit pass" |
| "gate line" | my messages | **gate request** (the file under `tmp/orchestrator/gate-requests/`) and **gate** (a `bin/test` invocation, identified by its run root) |
| "reclamation signal" | the report | **the unreachable-key ratio** — grounded on both sides after decision 3 |
| "churn" | ledger | "uncollected copy-on-write index nodes" |
| "write storm" | ledger, report | "unbounded write retry"; the dial `:seon.config.agent/write-refusal-bound` is the durable name |
| "slot", "lane", "working edge", "class", "reset #N", "prepl NNNN" | everywhere | **keep** — each names its mechanism (`bin/_test-slot`, `bin/codex-agent run`, `unsettled.md`, the issue query tag, `bin/seon reset --force`, the io-prepl port) |

One observation from batch C worth keeping: the three genuinely unmoored
words — prober, heartbeat, reclamation signal — are all counts of things
nobody measures. A word standing in for a number that is never derived is the
absence-as-health shape in prose.

---

## Part 4 — The questions, in one place

1. Agents stay on agent-authored definitions now; `src/` defects are not assigned to agents;
   the next design chunk is the gated candidate path. Confirm, and name the
   gate you want.
2. Ship the namespace-picture opening with the two completing calls, `:bare`
   as the floor, delete the other four; add `my.edit/edit` as the plain name?
3. Approve one dry-run collection on `default` now; the unreachable-key ratio
   on the existing daily schedule as the trigger; the derived cutoff; and the
   multiple (two? three?).
4. `start!` admits tests or a detector, refuses neither, inside its
   transaction function. Confirm?
5. Docstring detector scoped to `src` by default. Confirm?
6. Partial upserts validated against the merged entity inside `:db.fn/call`.
   Confirm?
7. The prompt budget selects whole evaluations, oldest dropped first; the
   value profile bounds each result. Confirm?
8. Dissolve the undisposed-turn notice; require at least one settled
   evaluation to continue a session. Confirm both?
9. No render fallback for an attribute-scoped request; declare an agent
   entity pair if you want one curated. Confirm?
10. (a) Switch `:seon.fn/sym` to `:db.type/symbol` after the identity probe?
    (b) Cold page slice 2: constrain narrowing, extend existence evidence, or
    slice 1 only?

Answer by number, in any order. Anything you want more background on, name
it and I will extend the relevant section rather than the question.
