---
type: prd
status: ruled direction, slices not started
created: 2026-09-17
owner: Sean (rulings in the design dialogue of 2026-09-17 morning)
tags: [prd, steward, program-graph, indexing, sci, acquisition, datahike, branches, write-back]
---

# Program facts are the runtime

**The rule this document exists to make true:** the database holds everything
needed to run agents. Source files are one projection *into* it (indexing) and,
later, one projection *out of* it (write-back). An agent authors the shared
cluster environment with ordinary Clojure forms that go through our reader,
analysis and SCI evaluation and become program facts in the cluster's
database; every agent on that branch sees them. An agent may instead work on a
fork of the SCI context and of the database, throw the fork away, or merge it.
One set of Malli schemas, attributes, values and refs describes code no matter
which seam produced it; the seam is a provenance attribute and nothing else.

This is the precondition for asking agents to improve the system from inside
the REPL, so the indexing and updating of code has to be exact before that
work starts.

Vocabulary is the dependency's: Datahike **entity / attribute / ref /
transaction / branch / commit**, SCI **ctx / fork / Var**, clj-kondo
**analysis**, `seon.schema.admission/source` **provenance**. No new nouns are
introduced here. Read [AGENTS.md](../../../../AGENTS.md) §2–§3 first; every
slice below is an application of §2.1 (values carry their world), §2.2 (facts
over inference, derive or die), §2.4 (total boundaries) and §2.5 (one
mechanism).

---

## 1. The rulings (owner, 2026-09-17, in his words where they were words)

R1. Agents author the shared cluster environment with pure Clojure forms,
    evaluated through our parser and eval system, written to the shared
    database; every agent on the main branch gets them. Agents may also live
    in their own experimental SCI-context world, and ideally on experimental
    database branches too, which can be thrown away or merged into main.

R2. The same Malli schemas, attributes, values and refs describe code whether
    it was indexed from a file or added during an agent's turn. Provenance is
    an additional attribute, never a different shape.

R3. Indexing source code and later updating that code in the database are the
    same operation on the same entity by the same identity.

R4. The shared base runs the faster compiled JVM definition of first-party
    code; an agent that overrides a first-party function runs the database
    (interpreted) version, and that fact is tracked in the database. Loading
    is decided per identity by the admitted row's provenance.

R5. Writing agent-authored or agent-overridden definitions back to the `.clj`
    files is the other direction of indexing. It is planned, and it is gated
    on checks passing first. Until it lands, an agent's override of a
    platform function is recorded and loaded in SCI, but the JVM's own code
    paths keep running the compiled definition.

---

## 1b. Task-loop rulings (owner, 2026-09-17 10:20Z)

The point of structured tasks is that the agent always knows what it is
supposed to be doing and the system, not the agent, decides when it is done.

T1. **Done is a query.** An issue's done condition is its cited tests
    verifying or its detector no longer naming the subject.
    `:seon.issue/resolved-tx` is written by settlement only. `start!` admits
    an issue with either tests or a detector and refuses one with neither
    (decision 4 of `owner-decisions-2026-09-17.md`, now ruled by
    implication of T1).

T2. **Continuation derives from the issue.** An issue-assigned agent takes
    another turn while its issue is open and its provider-turn budget
    remains, and stops when settlement writes `resolved-tx` or the budget is
    spent. `my.turn/complete` and `my.turn/wait` have no effect on an
    issue-assigned agent's session; they remain the disposition of a
    conversational agent answering a message. The undisposed-turn notice
    (decision 8b) is therefore dissolved for issue agents, and the
    continuation predicate fails closed by construction because T1 refuses
    an issue with no done condition.

T3. **The agent sees its tests and their results every turn.** The opening
    names the exact tests (or the detector) that will run after every turn.
    After each turn the system appends one concise evaluation to the agent's
    history with the results of those tests — which passed, which failed
    with the failure's shown text, what is still open — so the agent always
    knows where it is and what remains. This is an ordinary generated read
    (`my.issue/status`) re-evaluated by the system turn when its evidence
    changed, not a new render path; its render pair is curated (Part 2b of
    the decisions document).

T4. **Budget exhaustion is loud and resumable.** When an issue-assigned agent
    exhausts its budget with the issue still open, the session closes with a
    typed outcome recorded on the issue (`:seon.issue/budget-exhausted-tx`
    or an equivalent fact chosen at implementation with a docstring), and
    the root agent receives a message naming the issue, the turns spent and
    the last status. Root may resume the issue with a larger budget through
    the same `start!`/resume path; nothing about exhaustion is silent and
    nothing resumes on its own.

T5. **Waking is out of scope for now** (owner: "I don't think we have a good
    system for waking right now, but we can work on that later"). Slices in
    this document use the existing wake mechanics unchanged.

## 2. What exists today, with the seams named

Verified on `steward-platform` at `a36d55c3b`/`849bbce0b` on 2026-09-17.

### 2.1 Indexing (files → facts)

- The edit hook (`.claude/seon-hook.edn:25-29`, root `.`, cluster `default`)
  runs `bin/seon init --dev default --changed PATH` after a tracked edit; the
  operator evaluates `(seon.cluster/refresh-source! …)` inside the running
  JVM (`script/seon/fresh_operator.clj:2404-2406`). `refresh-source!` is
  public (`src/seon/cluster.clj:2355`).
- Analysis is `seon.fn.analyzer/analyze` (`src/seon/fn/analyzer.clj`),
  clj-kondo analysis over paths. `seon.fn/build-artifact`
  (`src/seon/fn.clj:1580`) analyses a file; `src/seon/fn.clj:712-730` analyses
  a **source string** on stdin (`paths ["-"]`) after prefixing the namespace
  form so aliases resolve — the same analysis a file gets.
- Rows are built from analysis by `seon.program/canonical-row` under the
  declared shapes (`src/seon/program.cljc:863`; shapes are now a threaded
  value, `:seon.program/shapes`, since `849bbce0b`), and written by
  `seon.fn/reconcile-tx` (`src/seon/fn.clj:2352`) with exact replacement
  (`program/exact-replacement-tx-in`, `src/seon/program.cljc:1023`).
- A function entity carries `:seon.fn/sym` (identity), `:seon.fn/ns` (ref),
  `:seon.fn/source`, `:seon.fn/arglists`, `:seon.fn/doc`, `:seon.fn/spec`,
  `:seon.fn/private?`, `:seon.fn/calls` (`[:set :seon.db/ref]`,
  `resources/seon/schemas/seon.fn.edn:21`), `:seon.fn/call-arities`
  (`:35`), declared keys, and its file coordinates (`:seon.fn.file/*`,
  root-relative since `28f1a761e`). Provenance is
  `:seon.schema.admission/source :core`.
- Identities are never retracted: deletion retracts definition facts and the
  identity survives as a tombstone (ruling 47).

### 2.2 Evaluation (an agent's form → facts)

- An agent's turn evaluates forms in its SCI fork (`fork-for-turn`,
  `src/seon/sci/eval.clj:1790`). After evaluation, for each interned Var that
  changed, the seam builds the program row **by hand from the Var's
  metadata** — `src/seon/sci/eval.clj:396-430`: `:seon.fn/sym`, `:seon.fn/ns`,
  `:seon.fn/source`, `:seon.fn/arglists` (printed), `:seon.fn/private?`,
  `:seon.fn/doc`, `:seon.fn/macro?`, `:seon.fn/spec` (normalised through
  `accretion/data-contract!`), `:seon.fn/workload`; tests get `:seon.test/*`
  and the test markers.
- That map is validated by `program/declaration-row row :all :agent`
  (`src/seon/program.cljc:906`), installed into the live base by
  `install-evaluated-rows!` / `install-row!` (`src/seon/sci/eval.clj:938`,
  `:791`), and written by the turn writer's `row-tx`
  (`src/seon/turn.clj:1277`) in the transaction that settles the evaluation.
- A `defn` without a `:malli/schema` contract is not installed and the agent
  is told so (`src/seon/repl.clj:127-129`).

**The defect (R2/R3 broken today):** the evaluation seam does not analyse, so
an agent-authored function has **no `:seon.fn/calls` edges, no declared keys,
and no analyzer-derived attributes**. It validates anyway because maps are
open and those attributes are optional. Reach selection, `tests-reaching`,
`changed-since-green` and the population invariant are all weaker for agent
code than for indexed code, silently.

### 2.3 Acquisition (facts → a running SCI context)

- `build-base-ctx` (`src/seon/sci/eval.clj:183`) builds the cluster's base
  SCI context. First-party functions enter **by reference to the JVM Var**
  with `sci/copy-var*` (`:197`, `:1168`); agent-authored rows are interpreted
  from their database source. Each agent's persistent context is a fork
  (`fork-cluster-ctx`, `:1877`) that receives accepted base changes before
  later turns; a candidate context (`fork-candidate-ctx`, `:2572`) tests a
  definition before `accept-candidate!` (`:2629`) installs it.
- The choice "reference the JVM Var" vs "interpret the database source" is
  made **by namespace kind**, not by the admitted row's provenance. An agent's
  override of a first-party identity is therefore only a shadow in that
  agent's fork; nothing records it as a state, and the base never loads it.

### 2.4 Platform code and the JVM

First-party namespaces run on the JVM (loaded by `require` at boot). Compiled
callers inside the JVM call compiled functions directly; they never consult
SCI Vars. So an override recorded in the database and loaded in SCI changes
what agents run, not what the turn loop, the writer or the web server run,
until the source file is rewritten from the database and the namespace is
reloaded (`refresh-source!`). That is R5's gap and the write-back closes it.

### 2.5 Branches

Datahike's versioning namespace in our fork
(`reference-code/datahike/src/datahike/versioning.cljc`) provides `branch!`
(`:212`), `delete-branch!` (`:279`), `force-branch!` (`:323`), `branch-as-db`
(`:499`), `commit-as-db` (`:469`), `branch-history` (`:191`), `commit-id` and
`parent-commit-ids`. There is **no merge operation**. Seon forks a cluster
from the published commit with `force-branch!`. A cluster is one connection;
custody, wake listeners and publication all hang off it. The agent-facing
branch functions are a target row in the vocabulary table, not landed.

---

## 3. Invariants every slice must leave true

I1. **One shape per kind of code.** A function, namespace, schema key or test
    is one entity with the attributes declared in `resources/seon/schemas/`,
    whichever seam wrote it. The only attributes allowed to differ between
    the two seams are `:seon.schema.admission/source` and the file
    coordinates — `:seon.fn/file` (a ref to the file entity), `:seon.fn/form-span`
    (byte offsets) and any `:seon.fn.file/*` fact — which an agent form does
    not have until it is written back. Absence of file coordinates is
    meaningful, never defaulted. (Amended 2026-09-17 10:30Z after the S1
    lane's probe: the coordinate attributes are the two above.)

    **Schema keys.** Schemas are declared once under `resources/seon/schemas/`
    (AGENTS.md §3). An agent declares one by evaluating
    `(seon.schema/register! key form)`, which the SCI reader recognises as a
    schema declaration event (`src/seon/sci/reader.cljc:347-374`); the
    indexer does NOT read `register!` from `.clj` source and must not learn
    to. Parity for a schema key is therefore between the resource seam and
    the evaluation seam: the same `:seon.schema` entity results from a
    declaration in a schema resource and from an agent's `register!`. S5
    writes an agent-declared schema back into the schema resources, never
    into a `.clj` file.

I2. **Derivable means required.** Any attribute the analyzer derives for every
    declaration of a kind — `:seon.fn/calls` first — is a required key of
    that entity schema, with the empty set as a legitimate value. A producer
    that skips analysis is then refused by the schema instead of validating
    a smaller map.

I3. **Provenance decides loading, per identity.** At acquisition, an identity
    whose admitted row is `:core` loads by reference to the JVM Var; one
    whose admitted row is `:agent` loads by interpreting the database source.
    The override set is a query, never a flag.

I4. **Population invariant and tombstones** (ruling 47) hold on both seams:
    every name the SCI context can resolve has a program entity; identities
    never retract.

I5. **Absence is typed.** "No calls", "no file", "never written back", "no
    overrides" are values or typed unknowns, never silence.

I6. **One analyzer, one row constructor, one writer per direction.** No second
    analysis, no hand-built rows, no compatibility arity.

---

## 4. Slices

Each slice names its owner files, its acceptance regression on the canonical
harness (`seon.test-support/with-database`, armed contracts), its live proof
on `default`, and its verification boundary. Lanes follow AGENTS.md §7
launching rules: cite this section, carry raw evidence, one class per lane.

### S1 — Analysis on both seams (R2, R3; I1, I6)

**Change.** The evaluation seam stops building rows from Var metadata. For
each accepted top-level form in a turn, it hands the form's **source text
and the agent's namespace context** (name, requires, aliases — the SCI
reader already tracks them, `namespace-info`,
`src/seon/sci/reader.cljc:181`) to the same source-string analysis the
indexer uses (`src/seon/fn.clj:712-730`, made a public function with a
contract if it is not one), and builds rows with `program/canonical-row`
under the threaded shapes. Runtime-only facts the analyzer cannot know are
**merged onto** the analysed row, never used instead of it: the normalised
contract data (`accretion/data-contract!`), the workload tag, the test
markers. The hand-built map at `src/seon/sci/eval.clj:396-430` is deleted.
`program/declaration-row` continues to stamp provenance `:agent`.

**Owner files.** `src/seon/sci/eval.clj` (the row seam), `src/seon/fn.clj`
(exposing the source-string analysis), `src/seon/program.cljc` only if a
constructor signature must widen (accretion, never a second arity kept for
compatibility).

**Acceptance regression (the standing one, keep forever).**
`seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities`:
take a fixture namespace file with functions that call each other, declare a
schema key, and hold a deftest; index it through the publication path onto a
fixture branch; evaluate the same forms as an agent through the turn writer
onto another fixture branch; pull every declaration entity from both; assert
the pulled maps are equal after removing `:db/id`,
`:seon.schema.admission/source` and `:seon.fn.file/*`. Assert
`:seon.fn/calls` is non-empty on the evaluated side.

**Live proof.** On `default`: evaluate a two-function namespace as Juniper,
then `(seon.fn/tests-reaching db "<the callee>")` and a `:seon.fn/calls`
pull show the edges. Compare with `bin/seon init --dev default --changed` of
the same source written to a scratch file under `test/`.

**Boundary.** In-process on `default` plus the cold gate on
`seon.program-test seon.fn-test seon.turn-test seon.sci.eval-test`.

### S2 — Derivable attributes are required (I2, I5)

**Change.** In `resources/seon/schemas/seon.fn.edn`, `:seon.fn/calls` becomes
a required key of the function entity map (empty set allowed). Do the same
for any other attribute S1 shows the analyzer always produces (candidates:
`:seon.fn/arglists`, `:seon.fn/private?`; decide from the S1 diff, not from
taste). Refusals must name the producing seam
(`seon.error/diagnostic`). Requiring a previously optional key is breakage
under §2.5, admissible here because database data is disposable by ruling:
the slice records RESET NEEDED and the orchestrator reforks `default`.

**Acceptance.** A hand-built function row without `:seon.fn/calls` is refused
by `seon.db/transact!` with a refusal naming the key; the S1 regression still
passes; `bin/test --platform` green.

**Boundary.** Cold gate on `seon.fn-test seon.program-test seon.schema-test
seon.db-test` plus the reset-boundary live proof (fresh `default`, Juniper
reseeded, adoption converged).

### S3 — Acquisition decides by provenance (R4; I3)

**Change.** `build-base-ctx` and the fork/diff path resolve each identity by
its admitted row's `:seon.schema.admission/source`: `:core` → `sci/copy-var*`
of the JVM Var (as today); `:agent` → interpret `:seon.fn/source` from the
database (as agent namespaces do today), **including when the identity is
first-party**. `accept-candidate!` applies to first-party identities exactly
as to agent ones, so an accepted override reaches every agent's fork through
the base diff. `dir` and `doc` (`src/seon/sci/eval.clj:1194`, `:1222`) render
the source of record, i.e. the database row, so an agent sees what it will
run.

**The override set is a query, declared once as a public read** in the
program namespace: identities whose current admitted row is `:agent` and
whose namespace has a file entity under the `src` root
(`:seon.fn.file/relative-root "src"`). The indexed definition it replaced is
readable through `seon.db/history`; no shadow copy is stored.

**Honest boundary, documented in the render:** the JVM's own compiled callers
still run the compiled definition until S5 writes it back and reloads. The
`doc` of an overridden first-party function says so in one line, derived
from the same query (I5: this is a typed state, not silence).

**Acceptance.** `seon.sci.eval-test/an-accepted-override-of-a-first-party-function-loads-from-the-database`:
as an agent, define a contracted replacement for a small first-party function
that agents call (pick one with no Java interop); accept it; a second agent's
next-turn fork calls the replacement; the JVM Var is unchanged (compare
`(deref #'the-fn)` identity before and after); the override query names the
identity; `doc` carries the not-yet-loaded line. A second test: a `:core`
identity still resolves to the identical JVM function object.

**Boundary.** In-process on `default`; cold gate on `seon.sci.eval-test
seon.cluster.agent-test seon.turn-test`.

### S4 — Experimental database branches (R1)

Two slices, because fork/discard is cheap and merge is a design.

**S4a — fork and discard.** An agent-facing request creates a branch of the
cluster's database from its current commit (`datahike.api/branch!`, or
`force-branch!` from an exact commit), forks the agent's SCI context, and
rebinds the agent's custody so its elided `seon.db` arities resolve to a
connection on the branch (the connection is carried as a value on the agent's
scoped environment, §2.1; `seon.db/call-with-custody`,
`src/seon/db.clj:259`, is the one scope). Wakes: the agent's turn-loop
listener moves to the branch connection; messages addressed to it on main are
visible as a read of main and are not answered until merge or discard
(document this in the message render). Discard is `delete-branch!` plus
dropping the fork. Every live experimental branch roots its ancestry for the
collector, so a branch carries its creating agent and instant as facts on
main, and `root/maintenance` reports branches older than a declared age
(I5: an abandoned branch is a typed finding, not silent growth).

**S4b — merge by replay.** Our fork of Datahike has no merge. Merge is
defined as: read the branch's transactions since its fork commit
(`datahike.api/tx-range` on the branch connection), and transact them onto
main in order, under a gate. Program facts merge by identity upsert;
evaluations and turns are agent-scoped and cannot collide. A collision (two
branches replacing the same identity since the fork) is detected by comparing
the identity's main-branch `:t` with the fork commit's basis and is a typed
refusal naming the identities, resolved by re-running the gate on a rebased
branch (replay main's transactions since the fork onto the branch first).
The gate before a merge is the S5 gate (reach-selected tests plus the
platform tier) run against the branch.

**Acceptance.** S4a: an agent on a branch transacts and defines; main is
byte-identical before and after; discard deletes the branch and the fork; the
maintenance report names an aged branch. S4b: a branch that defines a
function and a test merges onto main and both entities exist there with
provenance `:agent`; a conflicting branch is refused by name; the replay's
transactions carry the branch's provenance in `tx-meta`.

**Boundary.** Own scratch cluster for the destructive parts; cold gate on
`seon.db-test seon.cluster.agent-test seon.turn-test` and the new namespace.

### S5 — Write-back, database → files, gated (R5)

**Change.** One declared request (owned by the effect owner, executed on the
detached arm `my.background` with its 600 s bound, `config/default.edn:36`)
takes the override set from S3 and, for each namespace with overridden or
agent-added first-party identities:

1. **Candidate.** Build the namespace's new source: the file's current forms
   with each overridden identity's `:seon.fn/source` substituted and each new
   identity appended in a deterministic position (after its last caller or
   at the end). Analyse the candidate source with S1's analysis; the
   candidate's rows must equal the database rows for those identities apart
   from file coordinates (I1, mechanically checked).
2. **Gate.** In a candidate SCI context, run the tests reaching every changed
   identity (`seon.fn/tests-reaching`), then `bin/test --platform`'s
   declared set in process. Red is a typed refusal naming the tests; nothing
   is written.
3. **Write.** Through the one editor (`seon.edit`, digest-guarded on the
   file's current digest), write the candidate source.
4. **Reload and republish.** `seon.cluster/refresh-source!` for the written
   paths; the identities' admitted rows become `:core` again by the ordinary
   indexing path (the override set shrinks to empty by construction), and
   the JVM now runs the definition.
5. **Commit.** A path-limited `git commit` naming the agent and the issue.

The gate's exact composition is the owner's ruling (see §6); the default
above is the one the cold gate already uses.

**Acceptance.** An agent overrides a first-party function; the request writes
the file; the S1 diff regression holds between the rewritten file's index and
the agent's row; the override query is empty afterwards; a red candidate
writes nothing and names the failing test. Plant a symlinked sentinel in any
cleanup path (AGENTS.md §6).

**Boundary.** Own scratch checkout and cluster for the write; cold gate on
the effect, edit, cluster and fn namespaces.

### S7 — The issue task loop (T1–T4)

**Change.** In `src/seon/issue.clj` and `src/seon/turn.clj`: `start-tx`
admits tests-or-detector and refuses neither by name (decision 4);
`next-agent-work` / the continuation predicate for an agent with an open
issue derives from `issue open ∧ budget remaining` (T2) and ignores
dispositions; settlement runs the issue's done-query at the ordinary close
(tests today, detector added) and writes `resolved-tx`; the system turn
appends the concise per-turn status evaluation (T3) whenever the done-query's
evidence changed; on exhaustion the typed outcome is written on the issue and
one message is sent to root (T4); the resume path accepts a larger budget.
The opening (decision 2) states the done condition and the tests in one
block and drops any `my.turn/complete` teaching for issue agents.

**Acceptance.** (1) an issue with a detector and no tests starts; one with
neither is refused by name; (2) an issue agent whose last form returns
neither disposition takes another turn while the issue is open; (3) the
agent's history after turn *n* contains the status evaluation naming the
failing test's shown text; after the fix lands it names the pass and
settlement wrote `resolved-tx`; (4) a budget of two turns exhausts, the issue
carries the typed outcome, root has one message naming it, and a resume with
budget four continues from the same issue. All on the canonical harness
through the virtual-turn helpers already in `test/seon/turn_test.clj`.

**Boundary.** In-process on `default` with Juniper; cold gate on
`seon.issue-test seon.turn-test seon.turn-loop-test seon.issue-settlement-test`.

### S6 — The identity list derives from the declarations (I6; open issue)

`seon.program/identity-attributes` is a literal vector while
`authored-shapes` derives from the schema resources on disk; one lane's
uncommitted rename of an identity key refused every in-process test run on
`default` for an hour
(`docs/seon/issues/live-resources-outrun-the-loaded-program-identity-list.md`).
An identity attribute is one declaring `:seon.program/row-schema`; derive the
list from the same declaration forms and keep the admission order as an
explicit `:seon.program/admission-order` property on those declarations so the
order is also a fact. **Acceptance:** renaming an identity key in the resource
alone yields a typed refusal naming both the resource and the loaded list, or
the list follows automatically; the S1 diff regression passes.

---

### S4 is deferred (owner, 2026-09-17): groundwork only

The owner does not need experimental database branches now and wants the
feature later. No slice implements S4a or S4b yet. Every other slice keeps
the groundwork so S4 remains a bounded addition later, not a refactor:

- The agent's connection is carried as a **value** on its scoped environment
  and bound through the one custody scope (`seon.db/call-with-custody`);
  nothing new reads "the" cluster connection from a global.
- Every write from an agent's turn carries the agent and turn in `tx-meta`
  (provenance the replay in S4b will need); S1 and S5 must not drop it.
- Queries that answer "the program" take the database value they are handed
  and never assume the main branch (§2.1); the override query in S3 and the
  write-back in S5 are written against a `db` argument.
- Nothing introduces a per-cluster singleton keyed by cluster name where a
  connection or database value would do.

---

## 4b. Lane rules for every slice in this document (owner, 2026-09-17)

These are in addition to AGENTS.md §0–§10 and §7's launching rules.

1. **Read the integration seams' source end to end before designing**, and
   say so in the landing note with the paths: for S1 the analyzer
   (`src/seon/fn/analyzer.clj`), the indexer's source-string path
   (`src/seon/fn.clj:700-760`), `seon.program/canonical-row` and
   `declaration-row`, the SCI reader's namespace handling
   (`src/seon/sci/reader.cljc`), and clj-kondo's analysis output shape in
   `reference-code/clj-kondo` for the keys it consumes; for S3 SCI's
   `copy-var*`, `fork` and `intern` in `reference-code/sci/src/sci/core.cljc`
   and `build-base-ctx`; for S5 `seon.edit`, `refresh-source!` and the hook.
   A lane that has not read the seam does not edit it.
2. **Never build a testing harness.** The canonical fixture is
   `seon.test-support/with-database`; program rows come from
   `program-fn-row`; config from `apply-config!`; every fixture write through
   `transacted!`; awaits bounded by `event-backstop-seconds`. A new fixture
   function is admissible only when the landing note shows the existing one
   cannot express the case, and it lands in `test/seon/test_support.clj`
   with a docstring, never in a test namespace.
3. **Do everything through the existing owners.** Analysis through
   `seon.fn.analyzer/analyze`; rows through `seon.program`; writes through
   `seon.db/transact!`; adoption through `seon.cluster/refresh-source!`;
   edits through `seon.edit`. A second path for any of these is refused at
   review.
4. **The orchestrator personally reviews every slice's diff before it
   gates.** The lane reports the commit; the orchestrator reads the full
   diff, the landing note and the regression, writes a short review note
   under `docs/prds/steward-platform/research/` naming what it checked and
   what it rejected, and only then requests the gate. A slice is not landed
   until that note exists.
5. **Report the boundary honestly**: in-process proofs are named as such;
   the cold gate is the proof of record; RESET NEEDED is recorded when a
   key's meaning changes.

## 5. Sequencing and cost

| order | slice | why here | estimate |
|---|---|---|---|
| 1 | S1 analysis on both seams | everything else reads the facts it makes exact | 1 lane-day |
| 2 | S2 required derivables | flushes any remaining hand-built producer; needs a reset boundary | ½ day + reset |
| 3 | S6 derived identity list | closes the one-lane-refuses-everyone class before more lanes touch schemas | ½ day |
| 4 | S3 acquisition by provenance | makes R4 real; depends on S1 so overrides carry edges | 1 day |
| 5 | S4a fork/discard | cheap; unblocks experimental work | 1 day |
| 6 | S5 write-back gated | closes the JVM gap; depends on S1, S3 | 2–3 days |
| 7 | S4b merge by replay | DEFERRED with S4a | — |
| 2b | S7 issue task loop | the first structured task (render pairs) needs it; independent of S1 | 1–2 days |
| 2c | decision 9, no render fallback | the render-pair task needs every uncurated attribute visible | ½ day |

S1 goes to one astra lane (design-sensitive); S2, S6 to Opus; S3 to astra;
S4a Opus; S5 and S4b astra with design review at `high` effort. No slice
starts before the owner rules §6.

---

## 6. Owner rulings still needed for this document

1. **The write-back gate (S5).** Default proposed: tests reaching every
   changed identity green in a candidate context, plus the platform tier.
   Add anything? (An issue's cited tests when the agent works an issue? A
   human approval step before the `git commit`?)
2. **Which first-party namespaces may agents override at all (S3).** Default
   proposed: any, with interop failures surfacing as typed evaluation errors.
   If you want a declared exclusion, it is a schema property on the namespace
   entity, never a name list.
3. **Merge collision policy (S4b).** Default proposed: refuse by name and
   require a rebase; never last-writer-wins on a merge.
4. **Where an agent on an experimental branch is addressable (S4a).**
   Default proposed: messages on main are readable from the branch and
   unanswered until merge or discard.

---

## 7. What is deliberately not in this document

- The hook's own coalescing, timeouts, and the adoption retry
  (`src/seon/cluster.clj:2042`) are unchanged; they remain the file→facts
  path for human edits.
- The issue system's opening, detectors and scope are decisions 2, 4 and 5 of
  [owner-decisions-2026-09-17.md](owner-decisions-2026-09-17.md); they
  consume these facts and do not change them.
- Store reclamation (decision 3 there) is independent, except that S4a's
  branches must be visible to the collector's roster, which they are by
  construction.
