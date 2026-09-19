---
type: research
status: complete
created: 2026-09-19
tags: [isolation, sci, branch, merge, write-back]
---

# Isolation, merge, and write-back: what exists, what is missing, what to build

Read-only investigation. No JVM, no `bin/test`, no `bin/seon`, no lane. The
only write is this note.

**The question.** Namespace agents launched IN PARALLEL, each with a SEPARATE
SCI context and possibly a separate database branch, fixing their namespace in
isolation; rigorous tests gate merging accepted definitions into the shared
program graph; stricter tests gate writing those definitions to disk.

**The answer in one paragraph.** Far more of this exists than the plan
documents say. SCI isolation is sound and proven by construction; the
candidate-context gate already runs a definition's reaching tests before it
installs and before it is written; branch forking is O(1) and the registry
already owns it; file and byte-span provenance landed; and the pure
source-splice plus a doubly-fenced compare-and-swap writer already exist.
Four things are genuinely missing: a **changed-entity projection since a fork
basis**, a **merge writer with a basis-aware refusal**, an **operator verb for
N clusters and their cleanup**, and the **entity → file direction** of the
write-back arrow. Everything else is composition.

---

## Verification boundary

- Branch `steward-platform`, HEAD `b9c4cfa58`, read at 2026-09-19.
- The working tree carries other agents' uncommitted edits. `src/seon/test.clj`
  and `src/seon/test/runner.clj` are held by the `test-system-stage1` lane;
  `src/seon/db.clj`, `src/seon/schema.clj` and their schemas by the db-contracts
  agent. **Line numbers in `seon.test` below are working-tree lines.**
- Specifically: `seon.test/select` (`src/seon/test.clj:613`),
  `selection-admission` (`:856`) and `check-admission` (`:1201`) **do not exist
  at HEAD** — they are the stage-1 lane's in-flight work. `reaching` and
  `check` do exist at HEAD (`HEAD:src/seon/test.clj:561`, `:936`).
- Every other citation is from a file the lanes are not holding.
- No number below is estimated: measured numbers are quoted with their source.

---

## Broken or stale, first

1. **The program-facts PRD's §2.2 defect is fixed but the PRD still states it.**
   PRD §2.2 says "the evaluation seam does not analyse, so an agent-authored
   function has no `:seon.fn/calls` edges". It does now:
   `seon.fn/analyze-forms` (`src/seon/fn.clj:947`) runs one kondo batch over
   submitted forms, and `seon.fn/source-rows` (`:1168`) builds the row through
   `program/canonical-row` and stamps it `:agent` (`:1197-1201`). S1 landed.
   The PRD paragraph is stale and should be corrected in the same commit as the
   next slice that touches it.

2. **The PRD's §2.5 claim "There is no merge operation" is wrong as written.**
   `datahike.versioning/merge!` exists
   (`reference-code/datahike/src/datahike/versioning.cljc:735-748`). The claim
   the PRD *meant* is true and sharper: `merge!` is a **merge commit, not a
   merge algorithm**. Its own docstring: *"It is the responsibility of the
   caller to make sure that tx-data contains the data to be merged into the
   branch from the parents."* It routes to `datahike.writing/merge-writer!`
   (`writing.cljc:860-870`), which applies a plain `core/with` transaction and
   then `assoc-in [:meta :datahike/merge-parents]`. See §4 — this changes the
   design, because it means merge-by-replay gets correct commit ancestry for
   free instead of forging it.

3. **`source-root-fact-2026-09-16.md` names attributes that were renamed.**
   The note says `:seon.fn.file/root` and `:seon.fn.file/path`; current source
   declares `:seon.fn.file/relative-root` and `:seon.fn.file/relative-path`
   (`resources/seon/schemas/seon.fn.file.edn:1`, `:5`). The note's rule is
   unchanged and still correct: *"Absence is meaningful and must never be read
   as `src`."* This is a docs drift to retire on sight.

4. **`seon.program/unresolved-callers` does not exist under that name.** The
   implemented Var is `seon.fn/unresolved-callers` (`src/seon/fn.clj:1453`).
   The `seon.program/` spelling is used as a detector identity in prose and in
   `src/my/program.clj:215`. Either the Var moves or the detector identity is
   corrected; a detector naming a Var that does not exist is exactly the
   absence-read-as-health class.

5. **`seon.env/scope` cannot rebind a connection**, which contradicts the
   PRD's S4a sketch. See §2 — this is a design finding, not a defect.

---

## 1. Per-agent SCI isolation

### Each agent holds its own fork; they do not share one context

One cluster carries exactly one base context (`:seon.sci.eval/ctx` on the
cluster handle, `src/seon/cluster.clj:3144`). Per-agent forks live in a
separate atom, `:seon.agent/context-state (atom {})`
(`src/seon/cluster.clj:3140`), keyed by agent id and created only through
`seon.cluster.agent/acquire-context!` (`src/seon/cluster/agent.clj:684-704`),
which is guarded by `locking contexts` and calls
`seon.sci.eval/fork-for-turn` (`src/seon/sci/eval.clj:2059`).

The context handle is **process memory only**. `regenerate-agent-context!`
states it (`src/seon/sci/eval.clj:1999`): *"A JVM restart has no entering
context and consequently preserves no private objects. No database read
restores them."*

### What a fork copies and what it shares — read from the dependency

`sci/fork` (`reference-code/sci/src/sci/core.cljc:345-351`) is three lines:

```clojure
(defn fork [ctx]
  (update ctx :env (fn [env] (atom (assoc @env :sci/generation (utils/next-generation))))))
```

- **Copied:** the atom *box* only, plus a fresh generation gensym
  (`sci/impl/utils.cljc:356-357`).
- **Shared initially:** the namespaces map value and therefore every
  `sci.lang.Var` **object** in it, plus `:classes`, `:imports`, and the
  `:interrupt-fn`. Two forks start out resolving to identical Var objects.
- **Not shared:** any later `swap!` on either env atom.

Isolation is enforced at the moment of mutation, by generation:

`sci/impl/utils.cljc:362-379` (`bind-root!`):

```clojure
(if (= (:sci/generation (meta sci-var)) generation)
  (do (vars/bindRoot sci-var val) sci-var)              ; owned -> mutate
  (let [copied-var (new-var var-name (vars/getRawRoot sci-var) var-meta)]
    (vars/bindRoot copied-var val)
    (swap! env assoc-in [:namespaces ns-name intern-name] copied-var)
    copied-var))                                        ; inherited -> copy
```

`sci/impl/evaluator.cljc:40-52` (`eval-def`) does the same for a `defn`: an
inherited Var (generation differs, and not a built-in) is rebuilt as a new
`lang/->Var` from `@prev` and `assoc-in`'d into **this fork's** env only.

**So: agent A's `defn` of a name agent B can see is invisible to B's fork.**
This is real copy-on-write of Var containers, and Seon routes every install
through it (`transfer-evaluated-roots!`, `src/seon/sci/eval.clj:974`;
`install-jvm-root!`, `:798-800`).

### What genuinely leaks between forks

Three things, and they should be stated plainly rather than assumed away:

1. **Values, not containers.** A mutable object reachable from a shared Var
   root — an atom created in the base, a `deftype` field, a `java.util`
   collection — is the *same object* in both forks. Copy-on-write copies the
   box, never the contents.
2. **SCI built-ins.** `eval-def` skips the copy when
   `vars/built-in-var?` (`sci/impl/vars.cljc:279-280`, i.e. `:sci/built-in` in
   the Var meta) is true. Seon's first-party functions enter through
   `sci/copy-var*` (`sci/core.cljc:112-137`), which does **not** set
   `:sci/built-in`, so they are ordinary and copy correctly. Only genuine
   clojure.core built-ins are exempt, and those are separately guarded by
   `with-writeable-var` (`sci/impl/vars.cljc:284-290`).
3. **The database branch underneath.** This is the real leak, and it is not an
   SCI property at all — see §1's last subsection.

### Can an agent's `defn` overwrite another agent's view before any gate?

**No, on three independent counts** — and this is the single most important
finding for the mission, because it means the gate the plan wants at *merge*
already exists at *definition*:

1. The form is evaluated in a **candidate fork**, not the agent's context.
   `fork-candidate-ctx` (`src/seon/sci/eval.clj:2963-2971`) — *"candidate code
   never calls plain `sci/fork`; the candidate receives a separate context and
   cannot modify the live agent's private bindings."*
2. **The reaching tests run before install.**
   `seon.turn/gate-function-install` (`src/seon/turn.clj:3182-3256`):
   re-analyse (`:3188`) → `seon.fn/gate-set database function-symbol` (`:3194`)
   → `sci.eval/evaluate-candidate` in the candidate fork (`:3197`) →
   `accretion/gate-report` (`:3221`) → `accept-candidate!` only when
   `(:seon.test.accretion/install? report)` (`:3248`), otherwise
   `refuse-install` (`:3252`). On green, `accept-candidate!`
   (`src/seon/sci/eval.clj:3015-3029`) transfers only the evaluated **root**
   into the agent's own retained context.
3. **Nothing is transacted until settlement.** `settle-batch!`
   (`src/seon/turn.clj:3512-3561`) is one `db/transact!` on the cluster's
   connection; the row enters through `row-tx` (`src/seon/turn.clj:1214`).

Another agent sees the definition only at **its next** turn, when
`fork-for-turn` → `regenerate-agent-context!` (`src/seon/sci/eval.clj:1996`)
re-forks from the advanced base.

**But they do share one database branch.** Two agents in one cluster write into
the same program graph, and there the collision rule is
`declaration-diverged-since-open?` (`src/seon/turn.clj:1147-1177`): if the
identity's declared content differs from what the turn *opened* on, and this
turn did not write it, `row-tx` refuses `::program-row-changed-after-open`
(`src/seon/turn.clj:1284-1288`). **This is E3's merge-refusal rule already
implemented, at turn granularity, as a writer call rather than a pre-read.**
The merge version is the same function with the fork commit as its basis.

### `regenerate-agent-context!` — how the private layer survives

`src/seon/sci/eval.clj:1996-2057`. A Var is private iff its `:sci/generation`
equals the agent's **and** its root is not `same-program-root?` with the old or
new base (`:2003-2015`; `same-program-root?` at `:1990-1994` looks through
`::instrument/interpreted-original` so contract wrappers are not mistaken for
new definitions). The fork then **restores the agent's own generation**
(`:2036`) so private closures stay attached to their private Vars, reapplies
the actual Var objects via `sci/add-namespace!` (`:2037-2038`), and mutates the
retained handle in place (`:2050-2056`) so the agent's context **object
identity is stable across turns**.

### "Candidate context" is implemented, not vocabulary

`AGENTS.md:691` defines it; the code is
`fork-candidate-ctx` (`:2963`), `install-candidate-function!` (`:2973`),
`evaluate-for-install` (`:2994`), `accept-candidate!` (`:3015`),
`refuse-install` (`:3031`), `evaluate-candidate` (`:3107`),
`auto-check-candidate` (`:3162`), consumed at `src/seon/turn.clj:3198`, `:3204`,
`:3248`, `:3252`, `:4522`.

**Verdict for step 1: nothing is missing.** Per-agent SCI isolation is done,
and so is the per-definition test gate.

---

## 2. Per-namespace database isolation

### Forking a cluster is O(1) and already owned

A Datahike branch is exactly **two konserve keys**: the head record keyed by
the branch keyword, written as
`(k/assoc store new-branch updated-db store-opts)`
(`reference-code/datahike/src/datahike/versioning.cljc:270`, where `updated-db`
is just `(assoc-in stored-db [:config :branch] new-branch)`, `:268`), and the
`:branches` roster set, published last because it is the GC discovery pointer
(`:272-275`). **No datoms are copied.** Index roots are structurally shared,
copy-on-write thereafter; secondary indices branch natively (`:103-158`).

Seon's own measurement, recorded in the registry's namespace docstring
(`src/seon/cluster/registry.clj:7-9`): **"Branch-off is 17 ms and one blob."**

The one owner is `seon.cluster.registry`, and its docstring states the
containment rule (`src/seon/cluster/registry.clj:15-20`): *"THIS NAMESPACE
HOLDS THE ONLY CONNECTION THAT CALLS `branch!`, `delete-branch!`, OR
`gc-storage` … A cluster receives a branch connection and never the branch API,
so cluster A holds no handle that can delete cluster B."*

- `registry/branch!` (`:176-222`) — idempotent on roster membership; a lost
  race caught as `:branch-already-exists` is idempotence, not failure (`:212`).
- `registry/ensure-cluster!` (`:224-249`) — forks from an **exact immutable
  commit id**, so publication may advance while the fork lands.
- `registry/retire-branch!` (`:291-322`) — roster removal; bytes go at GC.
- `seon.operator/cleanup-cluster!` (`src/seon/operator.clj:630-647`) — stop,
  retire branch, delete directory, collect.

GC isolation is **structural, not policy**: the mark unions
`reachable-in-branch` over every roster branch (registry docstring `:24-31`,
citing `reference-code/datahike/src/datahike/gc.cljc:26,60-70,136-143`), so
collecting after one batch cluster is retired cannot take a sibling's data.

### Two clusters in one JVM: yes, by construction

`seon.cluster/start!` docstring (`src/seon/cluster.clj:3654-3657`): *"Two
instances in one JVM share the root store and executors, nothing else. Refuses
a second `start!` for a cluster this JVM already has running."*

- `seon.operator.runtime/running-instances` — an atom of
  `{cluster-name → instance}` (`resources/seon/operator/runtime.clj:11`).
- `root-store-holder` is refcounted (`runtime.clj:13`; acquire/release at
  `src/seon/cluster.clj:905-958`), so the last instance out releases the flock.
- Per-cluster selection: `seon.operator/connection`
  (`src/seon/operator.clj:154-164`), with ambiguity across >1 live cluster a
  typed refusal telling the caller to name one (`:139-145`).
- `seon.cluster.store/open-branch!` refuses `::branch-already-open`
  (`src/seon/cluster/store.clj:565-569`), so two connections to one branch is
  structurally impossible.

### The design finding: isolation is cluster-shaped, not agent-shaped

The PRD's S4a sketch says to "rebind the agent's custody so its elided
`seon.db` arities resolve to a connection on the branch". **`seon.env/scope`
refuses to do that.** `:seon.db/connection` is declared
`:seon.env/layer :branch` (`resources/seon/schemas/seon.env.edn:19-23`), and
`scope` (`src/seon/env.clj:253-276`) admits only `:turn`-layer members —
*"only members the schema places in the `:turn` layer may be supplied, so no
consumer can quietly replace a connection, a projection, or a work launcher on
its way across a boundary"* (`:256-259`).

There is a second, thread-local custody path — `seon.db/call-with-custody`
(`src/seon/db.clj:337-358`) binds `*conn*` directly — but an agent whose
*environment* still carries the old connection while its *thread* carries
another is precisely the "seam acting on a mirror its authority will re-decide"
that AGENTS.md forbids.

**Therefore: do not put an agent on a branch. Put a cluster on a branch and
the agent in it.** This is cheaper anyway (17 ms), reuses `start!`'s whole
boot, gives each batch its own wake listeners and turn loop with no new
plumbing, and keeps one authority for the connection. It also means **S4a as
written should be withdrawn in favour of E1**; they are two designs for one
thing, and E1 is the one the runtime already supports.

### What the operator lacks for E1

`bin/seon` is a 26-line shim (`bin/seon:25-26`) onto
`seon.fresh-operator/-main`, whose `case command`
(`script/seon/fresh_operator.clj:3615-3629`) has verbs
`start config export init status open stop down reset logs help`.

Missing, precisely:

1. **No multi-name or count argument.** `parse-init-arguments`
   (`script/seon/fresh_operator.clj:687-728`) accepts at most one NAME; `:707-727`
   refuses more than two arguments. There is no `init N1 N2`, no `--count`.
2. **No destroy verb at all.** Nothing in the `case` reaches
   `seon.operator/cleanup-cluster!`. Today removal is `reset --force` (which
   destroys the *entire* root, `:3484-3512`) or `init NAME --force` (which
   reforks, not removes).
3. **Per-invocation lock and preflight.** `with-operator-lock` (`:440-458`) and
   `source-preflight!` (`:327`) run per command, so N invocations pay N
   preflights; one batch verb amortises them to one.
4. **No group label.** Nothing records "these N clusters are one batch", so
   cleanup would re-enumerate the roster by name convention
   (`registry/roster`, `src/seon/cluster/registry.clj:105`).

Note the underlying primitive blocks none of this: forks are executed by
prepl-evaluating a generated form at the live anchor JVM
(`script/seon/fresh_operator.clj:2832-2857`), and `ensure-cluster!` is
idempotent and O(1), so a batch is a loop inside one form. **This is CLI
surface, not mechanism.**

---

## 3. Changed-entity projection since the fork basis (E2)

### Does `seon.db/since` plus program identities give it for free? Almost.

`seon.db/since` (`src/seon/db.clj:2650-2662`) is a thin wrapper on `d/since`,
two arities, error-passing. `basis-t` (`:410-422`) is `(long (dbi/-max-tx db))`.
`database-value-identity` (`:389-408`) is the one place branch + `t` + commit id
appear together: `{:db-name <branch> :t <max-tx> :datahike/commit-id <uuid>}`.

The `(d/since (d/history db) t)` idiom is **already used twice internally** —
`read-evidence-changes` (`src/seon/db.clj:1010-1037`, at `:1034`) and
`index-evidence-current` (`:1039-1055`, at `:1054`) — but only pattern-wise, to
witness cache validity. There is **no `changed-entities-since(db, t) → #{eids}`
helper**, and no entity-map reconstruction.

The measured cost is known and small. The selection-efficiency research quoted
in the test PRD (`docs/prds/steward-platform/plan/test-system-is-the-database-prd-2026-09-17.md:213-217`):
*"The change half is answered by `(db/since (db/history db) basis)` over
`:seon.fn/source`, `:seon.fn/spec` and `:seon.fn/calls`: **24–25 ms flat at 1 to
200 transactions back**."*

The stage-1 lane has already written the function-level version of this:
`seon.test/changed-definition-symbols` (working tree,
`src/seon/test.clj:572-611`). E2 is its entity-level sibling.

### The query sketch

E2 is a pure projection `(db, basis-t) → [row]`. Following stage 1's algorithm
(`docs/prds/steward-platform/plan/test-system-stage1-3-design-2026-09-17.md:206-240`),
the load-bearing rules are: read **both additions and retractions**; join
changed eids to identities **in history, not the current db**, so a
delete/recreate retains both observations.

```clojure
(defn changed-declarations
  "Program identities whose declaration facts changed since `basis`.

   Reads additions and retractions; joins eids to identities through history so
   a retracted identity still names itself. Absence of the source digest is the
   typed unknown, never an empty answer."
  [database basis]
  (let [history (seon.db/history database)
        delta   (seon.db/since history basis)]
    (seon.db/q
     '[:find ?identity-attribute ?identity-value ?added
       :in $delta $history [?attribute ...] [?identity-attribute ...]
       :where
       [$delta ?entity ?attribute _ _ ?added]
       [$history ?entity ?identity-attribute ?identity-value]]
     delta history
     ;; declaration attributes whose change matters
     [:seon.fn/source :seon.fn/spec :seon.fn/calls :seon.fn/references
      :seon.fn/sym :seon.test/source :seon.test/sym :seon.ns/source]
     ;; the identity families — DERIVED, per S6, never this literal vector
     (seon.program/identity-attributes))))
```

Two notes that are not optional:

- **The identity-attribute list must be derived, not literal.** S6
  (`program-facts PRD:1092-1106`) records that
  `seon.program/identity-attributes` being a literal vector refused every
  in-process test run for an hour when one lane renamed a key
  (`docs/seon/issues/live-resources-outrun-the-loaded-program-identity-list.md`).
  E2 must not add a second copy of that mirror.
- **The result is a projection, not the merge payload.** To merge you need the
  current entity map for each changed identity, which is an ordinary pull on
  the branch db under the row's declared shape — `seon.program/shapes`
  (`src/seon/program.cljc:248`) already owns which attributes belong to a row.

**Price: ½ lane-day.** It is one function, one regression (edit three entities
on a fork; the projection returns exactly those three, including one deleted
and one recreated), and the whole substrate exists.

---

## 4. Merge into the shared branch (E3)

### Datahike cannot merge, and says so

Exhaustively: there is no 3-way merge, no datom-level diff/union, no conflict
resolution anywhere in `reference-code/datahike/src`. What exists is
`datahike.versioning/merge!` (`versioning.cljc:735-748`) →
`datahike.writer/merge-db!` (`writer.cljc:441-457`) →
`datahike.writing/merge-writer!` (`writing.cljc:860-870`), which does
`(complete-db-update old (core/with old tx-data tx-meta))` and then
`assoc-in [:meta :datahike/merge-parents] all-parents`.

**In the dependency's own vocabulary:** a *branch* is a named head pointer into
a commit DAG; *fork* creates a new head at an existing commit (`branch!`,
`:212`) or forces one (`force-branch!`, `:323`); *head* is what `:branches`
rosters; `branch-history` (`:191-210`) backtracks
`[:meta :datahike/parents]` exactly like git. `merge!` writes a **merge commit
with multiple parents** — it tracks ancestry, it does not compute content.

**So merge is a re-transaction of entity maps onto the shared branch,** and
`merge!` is the right call to make it with, because it gives the resulting
commit the branch's head as a second parent. That is strictly better than the
PRD's S4b sketch (which proposed replaying `tx-range` without mentioning
ancestry): the history stays readable, `branch-history` shows where the work
came from, and GC's reachability mark keeps the batch branch's data alive while
it is a parent.

### What exists for the writer

`seon.program/exact-replacement-tx` (`src/seon/program.cljc:943-948`) returns
**transaction data, not a write**: for every changed *owned* attribute present
on `current`, one `[:db.fn/retractAttribute eid attribute value]` (sorted,
deterministic), then `(assoc desired :db/id eid)` (`replacement-tx`, `:908-925`).
"Owned" is derived (`changed-attributes-in`, `:874-892`): an attribute another
writer owns via `:seon.program/written-by` is never named, *"so an exact
re-index can never retract a fact the indexer did not write"* (`:895-898`).
Its refusals: multiple identity families (`canonical-row-in`, `:753-760`), no
source on the row (`declaration-row`, `:865-872`), and four shape-derivation
refusals (`derived-shape`, `:131-160`).

### What is missing: the basis-aware refusal

`exact-replacement-tx` takes `current` and `desired` and has **no notion of a
basis**. The refusal rule E3 wants exists one layer up, at turn granularity:
`declaration-diverged-since-open?` (`src/seon/turn.clj:1147-1177`) pulls the
identity from `opening-db` and compares `declared-content`, and `row-tx`
refuses `::program-row-changed-after-open` (`:1284-1288`).

**The merge writer is that function with an explicit basis instead of a turn's
opening.** Concretely, as a `[:db.fn/call ...]` so the writer decides and there
is no pre-read the authority re-decides:

```clojure
(defn merge-declaration-call
  "Replace one identity on the shared branch, refusing a divergence since `basis`.

   Refuses `:seon.program/diverged-since-fork` naming the identity, the fork
   basis, and both declared contents. The identity is not repaired here: the
   refusal is the refactoring input."
  [db {identity-attribute :seon.program/identity-attribute
       identity-value     :seon.program/identity-value
       desired            :seon.program/row
       basis              :seon.db/basis-t}]
  (let [current (seon.db/pull db '[*] [identity-attribute identity-value])
        at-fork (seon.db/pull (seon.db/as-of db basis) '[*]
                              [identity-attribute identity-value])]
    (if (not= (declared-content db at-fork) (declared-content db current))
      (refuse! :seon.program/diverged-since-fork …)
      (seon.program/exact-replacement-tx current desired))))
```

Two properties this must keep, both already law here:

- **The refusal names both sources**, because a refusal that does not carry the
  evidence is not refactoring input (AGENTS.md §2.4).
- **Surviving named referrers still refuse the deletion half.** A merge that
  removes an identity other declarations still name refuses; the retraction and
  its repair land in one transaction or not at all (AGENTS.md §3).

Every write from an agent's turn already carries agent and turn in `tx-meta`
(PRD S4-deferred groundwork, `:1114-1116`), so the replay carries provenance.

**Price: 1–1½ lane-days** — one transaction function, one `merge!` call site,
one regression (clean merge lands; a conflicting identity refuses with both
sources named; the resulting commit has two parents).

---

## 5. The two gates

### The merge gate (E4)

The gate selection has landed. `seon.fn/gate-sets`
(`src/seon/fn.clj:1439-1451`) has two arities:

```clojure
([{database :seon.db/db seeds :seon.fn/seeds}] (gate-sets-in database (vec seeds) true))  ; union
([database function-symbols]                   (gate-sets-in database function-symbols false))
```

The **map arity is the union frontier** stage 1 specified (one shared `seen`
set seeded by all changed symbols, not per-symbol calls) and returns a flat
`[:seon.test/sym]` vector. `gate-set` (`:1489`) and `tests-reaching` (`:1499`)
are one-seed adapters over it — literally the same function.

`gate-sets-in` (`:1396-1437`) builds the reverse edge graph once per operation
from identity rows, the test population, declared reference edges
(`:1358`), file references (`[?file :seon.fn/unresolved-references ?symbol]
[?test :seon.fn/file ?file]` — the `:seon.fn/file` ref earning its keep), and
effect handlers (`[?caller :seon.effect/capability ?symbol]`). Any read refusal
short-circuits (`:1418-1420`) — absence never reads as an empty gate set.

Measured (test PRD `:211-213`): **14.181 ms for the worst seed (1,009 tests),
1.562 ms for a leaf**; the recursive Datalog rule it replaced measured
**6,753 ms** on the same seed.

**So the exact merge-gate invocation is:**

```clojure
;; 1. what changed on the batch branch since the fork
(def changed (changed-declarations branch-db fork-basis-t))          ; §3

;; 2. the tests those changes reach, on the SHARED branch after the merge tx
(def members (seon.fn/gate-sets {:seon.db/db shared-db
                                 :seon.fn/seeds (set (map :seon.fn/sym changed))}))

;; 3. run each under the shared cluster's custody, recording facts
(doseq [test-symbol members]
  (seon.test/run-owned {:seon.db/connection shared-connection
                        :seon.test/var (seon.test/resolve-test shared-db test-symbol …)}))
```

`seon.test/run` (`src/seon/test.clj:412`) commits result facts and returns the
value pulled from `:db-after`, so it cannot disagree with what was committed.
`run-owned` (`:489`) is the agent entry and hands its own connection to the
test body; **an unchanged, previously green bare request returns the recorded
result without executing** (`runner/reusable-result`, `:512-517`), reporting
`:seon.test/unchanged`. `seon.test/reaching` (working tree `:846`, and at
HEAD `:561`) is the honest read-only "which tests would run".

Once the stage-1 lane lands, this collapses to one call to `seon.test/select`
with `:seon.test.run/change-basis-t` set to the fork basis, plus its recorded
membership — which is strictly better, because the members and their reasons
are admitted as facts **before** anything executes
(`test-system-stage1-3-design:290-330`).

**Missing for E4: only the composition, plus the fork basis as a fact on the
batch cluster.** Price: **½ lane-day** on top of stage 1.

### The write-back gate (F2/F3): what "more rigorous" must mean

The merge gate asks "does the shared branch stay green". The disk gate must
additionally answer "will the *repository* still be the same program". Three
things the merge gate does not check, each of which is a real failure mode:

1. **The full reaching closure plus the platform tier.** The merge gate runs
   the tests whose reach *changed*. The disk gate runs the complete
   `gate-sets` closure of every written identity, and then the declared
   `:seon.test/platform` tier — because writing to disk changes what the **JVM**
   runs after reload, not merely what SCI runs
   (`program-facts PRD:633-641`: compiled callers never consult SCI Vars).
2. **The indexer round-trip, byte for byte.** Index the candidate file with the
   same analysis the indexer uses (`seon.fn/analyze-forms`, `src/seon/fn.clj:947`,
   or `build-artifact`, `:1580`) and require the resulting rows to equal the
   database rows for those identities **apart from file coordinates** — which
   is invariant I1 (`program-facts PRD:655-670`), mechanically checkable. This
   is the check that catches a definition whose stored `:seon.fn/source` does
   not re-read as itself.
3. **`seon.fn/unresolved-callers` must not grow.** `src/seon/fn.clj:1453-1487`
   returns `:seon.program/analyzed-count` alongside the unresolved vectors
   precisely so "no unresolved callers" cannot be read from an empty
   population. The disk gate compares before and after; any new unresolved name
   is a refusal, not a warning. `seon.cluster.source/unresolved-report!`
   (`src/seon/cluster/source.clj:178-189`) already does this at publication and
   refuses `::publish-readback-failed`.

A red at any of the three writes nothing and names the failing test or identity.

---

## 6. Write-back (F1/F2)

### F1 has landed

Both attributes exist and are populated at index time.

`resources/seon/schemas/seon.fn.edn:8-9`:

```clojure
:file [:and {:description "Indexed source file shared by function and test declarations; absent on agent-admitted definitions."} :seon.db/ref],
:form-span [:tuple {:description "Half-open UTF-8 byte offsets of this declaration's exact source within its indexed file."} :int :int],
```

Optional on the function row (`:122-123`) and the test row
(`resources/seon/schemas/seon.test.edn:99`, `:100`).

A file is identified by its **publication-root-relative path**
(`resources/seon/schemas/seon.fn.file.edn:1-3`, `:seon.db/identity true`), and
carries `:seon.fn.file/digest` — SHA-256 hex, `:4` — and optionally
`:seon.fn.file/relative-root` (`:5-8`).

Written by the indexer at `src/seon/fn.clj:599` (the lookup ref
`[:seon.fn.file/relative-path (::analyzer/filename entry)]`), attached to test
rows at `:622-623` and function rows at `:656-657` alongside the span; the file
row itself at `:1253-1256`. Spans are computed by `exact-form-span`
(`:205-223`) from per-line cumulative UTF-8 byte offsets, and a row the captured
text lacks is the typed refusal `:seon.fn/source-span-absent`
(`resources/seon/schemas/seon.fn.edn:190-191`), never a guess.

Agent-admitted rows deliberately carry neither: `seon.fn/source-rows`
`(dissoc % :seon.fn/file :seon.fn/form-span)` (`src/seon/fn.clj:1201`), and the
docstring says why — *"A submitted form has no file coordinates"* (`:1173`).
Absence is meaningful, which is exactly what makes `seon.program/overrides`
(`src/seon/program.cljc:43-77`) derivable.

### The smallest pure function, and why it is small

The arrow currently runs **edit → file → re-index → entity**. Every piece of
the reverse arrow exists; nothing composes them.

- **Exact text:** `:seon.fn/source` (`resources/seon/schemas/seon.fn.edn:173`).
- **Exact bytes:** `:seon.fn/form-span`, half-open UTF-8.
- **The file and its digest:** `:seon.fn/file` → `:seon.fn.file/relative-path`
  + `:seon.fn.file/digest`.
- **The lossless splice:** `seon.edit` (`src/seon/edit.clj`, 482 lines,
  `rewrite-clj.zip` at `:7`), deliberately dependency-free *"so the edit hook
  can load it while Seon is down"* (`:4-6`). `lossless-candidate` (`:254`) and
  `splice` (`:222`) fence rewrite-clj's normalised render against an exact
  splice of the **original** bytes.
- **The coordinate bridge already exists.** `seon.edit/byte-span`
  (`src/seon/edit.clj:20-29`), whose docstring is exactly this design:
  *"`:seon.fn/form-span` addresses exact disk bytes, and these indices count
  UTF-16 chars… Converting here, once, is what keeps an edit's recorded region
  joinable with the declaration spans the indexer wrote."*
- **The reverse join is wired.** `seon.edit.jvm` attaches
  `:seon.effect/provenance {:seon.effect/file … :seon.effect/form-span …}`
  (`src/seon/edit/jvm.clj:66-75`), and `seon.effect/write-back-adds`
  (`src/seon/effect.clj:272-299`) resolves it at the **writer**: pull the file
  entity, pull every declaration span on it, `program/declaration-at` on the
  start byte. `declaration-at` (`src/seon/program.cljc:262-287`) is half-open
  `[start, end)` and **never nil** — "no declaration contains this byte" is the
  flat `:seon.program/no-declaration-at` value naming the position and
  `:seon.program/declarations-examined`.

So the pure function is a **projection to an edit request**, not a printer:

```clojure
(defn write-back-request
  "The edit that puts one accepted declaration into its file.

   Pure: no filesystem, no database. Replacement needs only the identity and
   the file's current digest, because `:seon.fn/form-span` already addresses
   the exact bytes. A declaration with no span is an APPEND, not a refusal."
  {:malli/schema [:=> [:cat :seon.program/row :my.fs/digest]
                  [:or :my.edit/form-request :seon.error/value]]}
  [{function-symbol :seon.fn/sym source :seon.fn/source
    span :seon.fn/form-span file :seon.fn/file} digest]
  {:my.edit/path           (second file)            ; the relative-path lookup value
   :my.edit/expected-digest digest
   :my.edit/form           {:my.edit.form/head 'defn
                            :my.edit.form/name (symbol (name function-symbol))}
   :my.edit/operation      (if span :replace :insert-after)
   :my.edit/source         source})
```

and the effect is `my.edit/form!` (`src/my/edit.clj:35-57`), which is **fenced
twice**: `seon.edit.jvm/edit*` (`src/seon/edit/jvm.clj:95-122`) refuses
`:my.edit/stale-source` unless the expected digest matches (`:104-105`), then
calls `fs.jvm/write` with `{:my.fs/precondition {:my.fs/expected-digest …}}`
(`:112-119`), where `precondition-state` (`src/seon/fs/jvm.clj:593-620`)
re-digests and refuses `:my.fs/stale-digest`, and `stage-content!` /`write`
(`:622-712`) do a `SecureDirectoryStream` `CREATE_NEW` + atomic `move`, refusing
`:my.fs/atomic-write-unsupported` rather than falling back.

Note the selector is `{:head :name}`, not a byte span — `seon.edit/form`
(`src/seon/edit.clj:303`) refuses `:my.edit/no-match` and
`:my.edit/ambiguous-match` **with the candidate evidence attached**, which is a
better refusal than a stale offset would give. The span's job is to decide
*replace vs append* and to prove the round trip, not to address the write.

### The round-trip proof

Three assertions, all mechanical, none needing new machinery:

1. **Byte identity.** Re-read the written file; the bytes at the *new* span
   equal `:seon.fn/source` exactly. `:seon.effect/form-span` is declared in the
   same unit for exactly this
   (`resources/seon/schemas/seon.effect.edn:14-17`: *"the same unit as
   `:seon.fn/form-span`"*).
2. **Row identity.** Index the written file with `seon.fn/build-artifact`
   (`src/seon/fn.clj:1580`); the resulting row equals the database row apart
   from `:seon.fn/file` and `:seon.fn/form-span` (invariant I1).
3. **The override set empties.** `seon.program/overrides`
   (`src/seon/program.cljc:43-77`) returns `[]` afterwards **by construction**
   — the re-index stamps the identity `:core` again. An override set that does
   not shrink is the write-back silently not having happened, which is this
   project's named failure class.

**Price: 1½–2 lane-days.** One pure projection, one effect composition, the
append and new-file cases, and the three-part round-trip regression.

---

## 7. The proposed sequence

Ten steps. Steps 1–3 are **pure data and schema work and must land before any
namespace agent runs**; steps 4–10 are mechanism. Prices are lane-days at the
default lane agent, and assume the orchestrator owns every cold gate.

| # | Step | Kind | Owned files | Proof | Price |
|---|---|---|---|---|---|
| 1 | **S6: derive the identity-attribute list** from the declaration forms; add `:seon.program/admission-order` as a property | data | `src/seon/program.cljc`, `resources/seon/schemas/seon.program.edn` | renaming an identity key in the resource alone either follows automatically or refuses naming both sides | ½ |
| 2 | **Stage 1 selection** (`seon.test/select`, run + member entities, recorded membership before execution) | data + mechanism | `src/seon/test.clj`, `src/seon/test/runner.clj`, `resources/seon/schemas/seon.test.{run,member}.edn` | `selection-is-one-function-on-both-hosts`; a second unchanged green request selects zero members | 1½–2 (**in flight**) |
| 3 | **Fork basis as a fact.** A batch cluster records its fork commit id, fork basis `t`, creating agent, subject namespace and creation instant, on the shared branch | data | `resources/seon/schemas/seon.cluster.edn` (or a new `seon.batch.edn`), `src/seon/cluster.clj` | pull a batch cluster → its basis; `root/maintenance` names a batch older than a declared age (I5: an abandoned branch is a typed finding) | ½ |
| 4 | **E2: `changed-declarations (db, basis)`** as a pure projection, over history, additions and retractions | mechanism | `src/seon/program.cljc`, `test/seon/program_test.clj` | edit three entities on a fork, one deleted, one recreated; the projection returns exactly those | ½ |
| 5 | **E1: operator batch verbs.** `bin/seon batch start <ns>…` forking N clusters in one locked invocation, and `bin/seon batch down <label>` reaching `cleanup-cluster!` | mechanism | `script/seon/fresh_operator.clj`, `src/seon/operator.clj`, `bin/seon` | two clusters running two namespaces' agents concurrently in one JVM; `batch down` leaves the roster and the disk clean; a symlinked sentinel survives cleanup | 1½ |
| 6 | **E3: the merge writer.** `merge-declaration-call` as a `[:db.fn/call …]` with the basis-aware refusal, landed through `datahike.versioning/merge!` so the commit carries both parents | mechanism | `src/seon/program.cljc`, `src/seon/cluster/source.clj` | clean merge lands and `branch-history` shows two parents; a conflicting identity refuses naming both contents | 1–1½ |
| 7 | **E4: the merge gate.** Compose step 4 → `gate-sets` union arity → stage 1 selection on the shared branch; merge only on green | mechanism | `src/seon/cluster/source.clj` or a new `seon.batch` | after a merge the gate executes only the affected tests, and a red merge lands nothing | ½ |
| 8 | **E5: re-fork on refusal.** A refused merge re-forks from the new shared head and the task re-runs to green | mechanism | `src/seon/operator.clj` | live: a deliberately conflicted batch is re-forked and lands | ½ |
| 9 | **F2: write-back.** `write-back-request` + the append and new-file cases + the effect composition | mechanism | `src/seon/program.cljc`, `src/my/edit.clj` or a new `seon.writeback` | the three-part round trip of §6; `overrides` empties | 1½–2 |
| 10 | **F3: the disk gate and the commit.** Full reaching closure + platform tier + indexer round trip + unresolved-callers non-growth; then `refresh-source!` and a path-limited `git commit` | mechanism | `src/seon/operator.clj` | a worker-authored change lands in the repository with its tests, and the JVM runs it after reload | 1 |

**Total: 9–11 lane-days**, of which 2½–3 is the data-and-schema prefix that
must precede any live namespace agent.

**The shortest path to the owner's demo** — two namespace agents in two
isolated contexts, fixing their namespaces in parallel, merged through the test
gate — is steps 1, 3, 4, 5, 6, 7: **4½–5½ lane-days** once stage 1 lands. Disk
write-back (9, 10) is a further 2½–3 and should not gate the demo, because §1
shows the definitions are already gated by their reaching tests before they are
stored.

**Two things NOT in this sequence, deliberately.** S4a ("an agent on a branch")
should be **withdrawn** in favour of E1 — §2 shows the environment cannot
rebind a connection, and a cluster costs 17 ms. And no step adds a second
selection, a second replacement writer, or a second analysis; every step
accretes at the existing owner.

---

## 8. The three decisions the owner must make

**1. Is the merge refusal rule right, and is re-fork the whole recovery?**
The proposal (E3, step 6) refuses when an identity's declared content changed
on *both* sides since the fork basis, names both contents, and recovers by
re-forking from the new head and re-running to green (E5). There is no
three-way merge and no last-writer-wins. The rule already exists in miniature
and works (`declaration-diverged-since-open?`, `src/seon/turn.clj:1147`). The
cost of "yes" is that two agents editing one function serialise; the cost of
anything else is a merge algorithm this project would own forever. **Recommend
yes.** This is the PRD's §6 question 3, still unanswered.

**2. Does the disk gate need a human, and does it commit?**
Step 10 as proposed runs the full reaching closure, the platform tier, the
indexer round trip and the unresolved-callers check, then writes, reloads, and
makes a **path-limited `git commit` with no human in the loop**. The
alternative is to write the files and stop, leaving the commit to the
orchestrator or the owner. This is the PRD's §6 question 1 and it is the one
decision that changes what the system *is*: an assistant that proposes diffs,
or a system that commits its own repairs. **Recommend: commit automatically,
but never push**, so recovery is always `git reset` on an unpushed commit.

**3. Which namespaces may a batch agent override at all?**
The PRD's §6 question 2 proposes "any, with interop failures surfacing as typed
evaluation errors". The sharper question now is whether a batch agent may
override the namespaces the *merge machinery itself* runs on — `seon.db`,
`seon.program`, `seon.cluster.registry`, `seon.turn`. A batch cluster that
redefines its own merge writer and then merges is a real hazard, and the
honest options are: (a) any namespace, and the platform tier in the disk gate
is the only protection; (b) a declared `:seon.ns/` schema property marking a
namespace as merge-critical, refused at the merge writer — never a name list
(AGENTS.md §2.2). **Recommend (b)**, because it is a fact, queryable, and costs
one optional attribute.

---

## Note path

`docs/prds/steward-platform/research/isolation-merge-writeback-2026-09-19.md`
