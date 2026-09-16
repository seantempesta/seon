---
type: research
status: active
created: 2026-09-16
tags: [program-graph, refactoring, deletion, contracts, schema, agents, refusal]
---

# The inventory of connections, what severs them, and what an agent does with the refusal

Date 2026-09-16. Branch `steward-platform`, working tree at `dd2f2f493` plus
other lanes' uncommitted edits (verification boundary, §7). Read-only: no
source edit but this note, no test JVM, no `bin/seon` state change. Three
read-only `mcp__seon__eval_clj` calls against cluster `default` (mode `jvm`,
custody `(seon.operator/connection "default")`), all pure `seon.db/q`
bundles; nothing was transacted. Every count below comes from those three
calls.

The mission this serves, the owner's words:

> *"The entire program graph is in the database and it's queryable and we know
> every function and call edge and what each input and output is and what tests
> exist and on and on. We need to leverage this to make it easy to refactor and
> impossible to cause certain software failures … Help me find all of these
> connections and make them unbreakable AND easy to teach agents how to
> refactor with the data."*

And, ruled the same evening: *a function with live callers is not deletable
until the callers are fixed, or the breaking call graph becomes the
refactoring work handed to agents and the retraction applies after. Every
connection is important. History keeps everything, so weigh loss against
elision.*

---

## 0. The three sentences

**One.** The graph already knows almost every connection the owner named — 63,469
call edges, 3,693 contract→schema-key edges, 4,527 schema→schema edges, 3,215
namespace require edges, 1,052 function→written-key edges, 100 declared render
pairs, 10 capabilities, 5 schedule tasks, all on 5,114 functions and 1,861
tests — and it enforces **almost none of them at the write**.

**Two.** The dial that decides *refuse* versus *silent sweep* already exists and
nobody chose it per attribute: it is `{:optional true}` in the entity map. A
required ref makes `retract-entity`'s incoming sweep leave the surviving
referrer failing its own entity schema, so `seon.db`'s final-report validator
refuses the whole transaction; an optional ref makes the same sweep silent
(mechanism opened end to end in
[the agent/turn deletion note](deletion-semantics-agents-and-turns-2026-09-16.md)
§0, correction carried into
[the program-graph deletion note](deletion-semantics-program-graph-2026-09-16.md)
§1 row 2). **Every program-graph edge on the function entity is optional**
(`resources/seon/schemas/seon.fn.edn:133-137`). That single fact is why the
flagship failure — delete a function with five live callers — is silent today.

**Three.** There is no agent-facing refactoring operation at all. `src/my/` holds
thirteen namespaces (`agent background edit fs issue message note plan shell
test turn web`, plus `issue`); none of them can rename a function, delete one,
change a contract, or ask what would break. The only edit surface an agent has
is `my.edit/form!` / `exact!` / `lines!` — digest-guarded **text** editing of a
file (`src/my/edit.clj:35`, `:59`, `:82`) — and ordinary SCI evaluation. The
refusal payload the owner wants is not merely unimplemented; there is no
function for it to come back from.

---

## 1. Authorities read end to end

AGENTS.md §1–§3 (the copy in this session's instruction context, verified
against the file's §1 opening);
[program-facts-are-the-runtime PRD](../plan/program-facts-are-the-runtime-prd-2026-09-17.md)
in full, §0a through §7;
[the data-modeling skill](../../../../.claude/skills/data-modeling/SKILL.md);
[deletion semantics — program graph](deletion-semantics-program-graph-2026-09-16.md);
[deletion semantics — agents and turns](deletion-semantics-agents-and-turns-2026-09-16.md);
[reset schema recommendations](reset-schema-recommendations-2026-09-16.md).

Resources opened: `seon.fn.edn`, `seon.fn.file.edn`, `seon.fn.arity.edn`,
`seon.test.edn`, `seon.ns.edn`, `seon.schema.edn`, `seon.render.edn`,
`seon.effect.edn`, `seon.schedule.task.edn`, `seon.config.edn`,
`seon.instrument.edn`, `seon.flow.edn`.

Source opened: `src/seon/fn/analyzer.clj`, `src/seon/program.cljc`,
`src/seon/fn.clj`, `src/seon/sci/eval.clj`, `src/seon/turn.clj` (the row and
gate seams), `src/seon/instrument.clj`, `src/seon/db.clj` (the write-admission
seam), `src/seon/render.clj`, `src/seon/schedule.clj`, `src/seon/cluster/agent.clj`,
every file in `src/my/`.

**One PRD claim is now stale and this note supersedes it.** PRD §2.2 says the
evaluation seam "does not analyse, so an agent-authored function has no
`:seon.fn/calls` edges". S1 has landed: `definition-row`
(`src/seon/sci/eval.clj:392`) now calls `seon.fn/source-rows`
(`src/seon/sci/eval.clj:406`; `src/seon/fn.clj:1135`), which runs the indexer's
own analysis over the submitted source through `runtime-analysis-batch`
(`src/seon/fn.clj:778`) and builds rows with `program/canonical-row`
(`src/seon/fn.clj:1166-1169`). Commit `6312fcef0`, "Unify indexed and evaluated
declaration analysis". Everything below assumes both seams analyse.

---

## 2. The four seams where an invariant can be enforced

Every row in §3 names one of these. There is no fifth, and nothing in this
note proposes inventing one.

| Seam | What it sees | file:line | Fires when |
|---|---|---|---|
| **A. The analyzer at index time** | only the files or forms in *this* operation, plus a prelude of database-declared stubs | `seon.fn/assert-clean-analysis!` `src/seon/fn.clj:1062`, blocking set `:1011-1017`, prelude `src/seon/fn/analyzer.clj:559` fed from the database at `src/seon/fn.clj:788-793` | a publication or an agent form is analysed |
| **B. Write admission, final-report validator** | the **whole finished world**: `:db-before`, `:db-after`, `:datahike/attempted-tx-data`, and every entity the sweep touched | `seon.db/write-report-error` `src/seon/db.clj:3009`, entity check `:2958`, wired at `:3178` | every `seon.db/transact!` |
| **C. A `:db.fn/call` inside the transaction** | the mid-transaction database | precedent `src/seon/turn.clj:1088` (`assert-schema-data-unused!`), `src/seon/db.clj:3144-3153` (the retention rule's before/after pair) | the writer chooses |
| **D. Contract arming** | the loaded JVM Var's `:malli/schema` plus the projection's function contracts and their transitive declarations | `seon.instrument/arm-var!` `src/seon/instrument.clj:638`, `current-wrapper?` `:623`, `contract-definitions` `:601` | adoption, publication, boot |

Seam B is the one the owner's ruling needs, and it is already sitting in the
right place with the right inputs: `affected` is every entity the transaction
or the sweep touched (`src/seon/db.clj:3014`), and `prior-identities` recovers
a deleted entity's identity value from `:db-before` three lines later
(`:3015-3019`). The deletion check is one AVET lookup per (deleted identity ×
declared edge attribute) away.

---

## 3. The inventory

For each connection: **(1)** the fact, **(2)** the failure, **(3)** its state
today, **(4)** the invariant as one sentence and its seam, **(5)** the
refactoring operation and the data its refusal hands back.

### C1. Caller → callee (the call edge)

1. **Fact.** `:seon.fn/calls [:set :seon.db/ref]`
   (`resources/seon/schemas/seon.fn.edn:23`), optional in the entity map
   (`:133`). Written from clj-kondo `:var-usages` by `seon.fn/var-row`
   (`src/seon/fn.clj:625` test arm, `:663` function arm) and, for agent code,
   by the same constructor through `source-rows` (`src/seon/fn.clj:1135`).
   Live: **63,469** edges. Its docstring-level sibling `:seon.fn/references`
   (`seon.fn.edn:42`) carries `#'f` var-quotes and unshaped mentions, which is
   how a flow proc's step-fn is recorded at all (§C12).
2. **Failure.** `seon.turn/open?` is deleted. Its five live callers —
   `seon.turn/recover-call`, `seon.turn/receipt-run`, `seon.turn/render-ai`,
   `seon.turn/require-open-run`, `seon.turn/open-run-tx-call` — now call a name
   with no definition. 54 tests carry it in their recorded reach.
3. **Today: SILENT, twice over.** The agent origin (`ns-unmap` in a turn)
   reaches `seon.turn/row-tx`'s deletion arm (`src/seon/turn.clj:1301-1340`),
   which calls `program/exact-replacement-tx` with the identity alone
   (`src/seon/program.cljc:1021` → `replacement-tx` `:1016`): every definition
   attribute is retracted and the **identity datom stays**, so the callers'
   lookup refs still resolve and nothing anywhere notices. The file origin
   (the edit hook's publication) would `retractEntity` and the sweep
   (`transaction.cljc:998-1014`) would silently drop each caller's edge,
   because the key is optional and the survivor still validates
   (`src/seon/db.clj:2958`). Seam A does refuse — but only when the caller is
   inside the analysed file set; a caller in another file is invisible to it.
4. **Invariant.** *No transaction may retract a declaration identity while any
   entity alive in `:db-after` still names that identity through a declared
   edge attribute.* Seam **B**, on the value form of the edge (§6).
5. **Operation.** `(my.program/delete! 'seon.turn/open?)`. Input: one qualified
   symbol (or a set). Refusal data: `{:seon.program/callers #{seon.turn/recover-call
   seon.turn/receipt-run seon.turn/render-ai seon.turn/require-open-run
   seon.turn/open-run-tx-call} :seon.program/attribute :seon.fn/calls
   :seon.test/stale-reach <54 test symbols> :seon.test/gating <the 4 tests
   `seon.fn/gate-set` selects through those callers>}`. That map **is** the
   refactoring assignment and is already the shape `:seon.issue/functions`
   takes. The same call with the repairs in the same transaction commits,
   because seam B reads `:db-after` and finds no caller — no "was it fixed?"
   flag exists or is needed.

### C2. Call-site arity → the callee's declared arities

1. **Fact.** Both halves are stored. The call site:
   `:seon.fn/call-arities [:set [:tuple [:string] [:int]]]`
   (`seon.fn.edn:57-72`), written at `src/seon/fn.clj:638`/`:676` from
   `call-arities-by-caller` (`:484`) — e.g. `seon.turn/require-open-run` carries
   `["seon.turn/refuse!" 3] ["seon.turn/open?" 1] ["seon.turn/current-run" 2]`.
   The declaration: `:seon.fn.arity/min`, `/max`, `/argument-count`
   (`seon.fn.arity.edn:11-12`, `:8`), component rows under `:seon.fn/arities`.
   Live: **31,020** recorded call sites with a known argument count; 3,412 of
   them name a first-party callee that carries arity rows.
2. **Failure.** `seon.turn/open?` changes from `([run])` to `([db run])`. Every
   one-argument call site is now an arity error that appears at runtime, per
   call, in whichever agent's turn hits it first.
3. **Today: DETECTED at seam A only, and only within the analysed set.**
   `:invalid-arity` is in `load-refusal-finding-types` (`src/seon/fn.clj:1011-1017`)
   and `assert-clean-analysis!` (`:1062`) throws the publication. For an agent's
   form the same check runs, and better than one might expect: the runtime
   batch builds a kondo prelude of stubs **from the database's own
   `:seon.fn/arglists` and `:seon.fn/private?`**
   (`src/seon/fn.clj:788-793` → `analyzer/program-prelude`
   `src/seon/fn/analyzer.clj:559`), so an agent calling a database-declared
   function with the wrong arity is caught before the row is written. What is
   **SILENT** is the other direction: changing a callee's arity never
   re-examines its existing call sites, which live in files this operation did
   not analyse. Measured baseline: **0** of the 3,412 resolvable call sites is
   currently outside its callee's declared `[min, max]` — the graph is
   coherent right now, so the check can be turned on without a cleanup lane.
4. **Invariant.** *After any transaction that writes a function's arity rows,
   every recorded `:seon.fn/call-arities` tuple naming that function must fall
   within some declared `[min, max]`.* Seam **B** (it is a pure join over
   `:db-after`; no pre-read).
5. **Operation.** `(my.program/change-arity! 'seon.turn/open? '([db run]))` —
   in practice this is just a redefinition, so the check belongs to
   `my.program/define!` and to the publication writer rather than to its own
   verb. Refusal data: `{:seon.program/call-sites [{:seon.fn/sym
   seon.turn/require-open-run :seon.fn/call-arity 1 :seon.fn.arity/min 2
   :seon.fn.arity/max 2} …]}` — caller, the arity it uses, and the arity it now
   needs, which is enough for an agent to rewrite each site without reading a
   file.

### C3. Call-site argument shapes → the callee's input contract; return → output contract

1. **Fact.** `:seon.fn/spec` (the canonical `:malli/schema` form as a string,
   `src/seon/fn.clj:654-659`) plus the decomposed
   `:seon.fn.arity/input`, `/output`, `/return-schema`,
   `:seon.fn.argument/schema` component rows. Live: **1,204** public functions,
   **79** of them with no `:seon.fn/spec`; 3,069 private functions carry none
   (private functions are not required to). What is **NOT** a fact: the
   argument *shape* at a call site. clj-kondo reports the argument count, never
   the value's schema.
2. **Failure.** `seon.db/transact!`'s input contract narrows to require a key
   its 40 call sites do not pass. Nothing refuses at the write; every caller
   breaks at its first armed invocation, as a runtime contract violation
   attributed to the callee.
3. **Today: DETECTED late, at seam D, one call at a time.** Contracts are armed
   from the loaded Var's own `:malli/schema` and the projection's contracts
   (`src/seon/instrument.clj:638`, `:580-582`); a violation is a typed value
   naming the function and the offending argument, which is exactly right *for
   that one call* and says nothing about the other 39. For an agent's own
   definition there is one genuinely strong gate: `gate-function-install`
   (`src/seon/turn.clj:3285`) re-analyses the form, derives the reaching test
   set with `seon.fn/gate-set` (`src/seon/fn.clj:1407`) and runs those tests in
   a candidate context before the definition is installed. That is the closest
   thing in the tree to the owner's "impossible to break", and it applies only
   to agent-authored functions.
4. **Invariant.** Two, and only the first is cheap. (a) *A contract change that
   narrows an input or weakens an output must re-run every test reaching the
   function, and refuse on red* — seam **C** or the publication writer, reusing
   `gate-function-install`'s machinery. (b) *Every call site's argument shape
   must satisfy the callee's input contract* — **not derivable from any fact we
   store or could cheaply store**; clj-kondo's `:type-mismatch` is explicitly
   downgraded to a warning because it "is not a sound admission proof for
   database pulls and branch-sensitive Malli contracts"
   (`src/seon/fn/analyzer.clj:20-26`). State (b) as a known gap (§8), not as a
   target.
5. **Operation.** `(my.program/change-contract! 'seon.db/transact! '[:=> …])`.
   Refusal data: the accretion verdict (widening = free; narrowing an input,
   requiring an optional key, or promising less in an output = breakage, per
   AGENTS.md §2.5) plus `{:seon.test/reaching <test symbols> :seon.test/red
   <the failures with their shown text> :seon.fn/callers <every caller>}`. The
   caller list is honest about being the *suspect* set, not the *broken* set —
   an unknown typed as such, never a silence.

### C4. Function → the schema keys it reads and writes

1. **Fact.** `:seon.fn/writes [:set :seon.db/ref]` to `:seon.schema/key` rows
   (`seon.fn.edn:44-56`), written at `src/seon/fn.clj:636`/`:674` — its own
   docstring says it is "a qualified keyword whose position lies inside the
   span of a `seon.db/transact!` usage", i.e. a token observation wearing a
   ref. Live: **1,052** edges; the top written keys are `:seon.agent/id` (86),
   `:seon.ns/name` (56), `:seon.turn/id` (48). `:seon.fn/keywords`
   (`seon.fn.edn:105-109`) is the same observation already stored as a value and
   is declared honest membership only — never a claim, so never a break.
2. **Failure.** `:seon.db/connection` is deleted or renamed. Four functions
   declare they write it (`seon.issue/add!`, `seon.issue/tests!`,
   `seon.issue/start!`, `seon.turn/close-turn`); 32 schemas reference it; 146
   arities name it in their input refs; 177 functions mention the keyword.
3. **Today: HALF-DETECTED.** `seon.turn/row-tx` already builds the candidate
   projection without the key and calls `assert-schema-data-unused!`
   (`src/seon/turn.clj:1319`, owner at `:1088`) — seam C applied to *data*,
   refusing when the attribute still has datoms. It then falls through to the
   same identity-only tombstone, so the 182 **declaration-level** referrers are
   retracted in silence.
4. **Invariant.** *A schema key may not be retracted while any function
   declares it written, any schema references it, or any arity names it in
   `input-refs`/`output-refs`/`guard-refs`.* Seam **B**; `assert-schema-data-unused!`
   stays exactly as it is and covers the orthogonal data question.
5. **Operation.** `(my.program/delete-schema-key! :seon.db/connection)` and
   `(my.program/rename-schema-key! :old :new)`. Refusal data, grouped by
   attribute: `{:seon.fn/writes #{4 symbols} :seon.schema/references #{32 keys}
   :seon.fn.arity/input-refs #{146 arities, as their owning function symbols}}`.
   A rename is the interesting case: under AGENTS.md §2.5 a key's semantics
   never change, so `rename!` is *declare the new key, rewrite the 182
   referrers, retract the old* — and the refusal on the last step is what
   proves the middle step finished.

### C5. Schema key → every function whose contract references it

1. **Fact.** `:seon.fn.arity/input-refs`, `/output-refs`, `/guard-refs`
   (`seon.fn.arity.edn:5`, `:15`, `:3`), `[:set :seon.db/ref]` to
   `:seon.schema/key` rows, written by `src/seon/program.cljc:734-736`. Live:
   **3,693** edges. Fan-out is steep: `:seon.db/database-value` 700,
   `:seon.agent/id` 239, `:seon.render/unit` 219, `:seon.db/connection` 146.
   Plus `:seon.schema/references` between schemas (`seon.schema.edn:50-53`),
   **4,527** edges.
2. **Failure.** Renaming `:seon.db/database-value` silently strips 700 arities'
   declared input reference, and the contracts they were compiled from stop
   resolving at the next arming — a failure that surfaces as `arm-var!`
   throwing at adoption, far from the edit.
3. **Today: SILENT at the write, LOUD but late at seam D.** `contract-definitions`
   (`src/seon/instrument.clj:601`) walks the contract's transitive declarations
   and `current-wrapper?` (`:623`) compares them against the projection, so a
   changed declaration re-arms the right wrappers and an unresolvable one
   fails there. That is a good detector of *its own* breakage; it is not a
   refusal of the change that caused it.
4. **Invariant.** Same sentence as C4 — these attributes are simply the largest
   member of the referrer set. Seam **B**.
5. **Operation.** Covered by `my.program/rename-schema-key!`. The refusal's
   700-member list is the one place §0's elision ruling bites: the **fact**
   carries every member, and the AI render function elides it under the
   profile as an elision value naming the count and the requery form
   (AGENTS.md §2.4, one clipping spot). Losing a member is a database defect;
   eliding it is a render contract; they never trade.

### C6. Namespace → its requires, aliases, refers

1. **Fact.** `:seon.ns/requires [:set :seon.db/ref]` (`seon.ns.edn:32`,
   optional in the entity map at `:23`), written at `src/seon/fn.clj:303`;
   `:seon.ns/aliases` / `/imports` / `/refers` are **components**
   (`seon.ns.edn:2`, `:6`, `:30`) and die with their namespace, correctly.
   Live: **3,215** require edges over 438 namespaces; **67** namespaces require
   `seon.turn` alone. Its own family already stores
   `:seon.ns.alias/target-ns` and `:seon.ns.refer/target-ns` as **symbols**,
   which is the in-family precedent for the retype.
2. **Failure.** `src/seon/turn.clj` is deleted. 176 declarations lose their
   namespace; 67 namespaces' require edges are swept; the reload ordering that
   reads them (`src/seon/cluster.clj:2203-2209`) quietly computes a different
   order.
3. **Today: PARTLY IMPOSSIBLE, mostly SILENT.** `:seon.fn/ns` is a **required**
   ref in the function entity, so retracting a namespace with live declarations
   already refuses at seam B — the one place the existing schema accidentally
   does the right thing, and the proof that required-ness is the dial. The 67
   requires are optional and sweep in silence.
4. **Invariant.** *A namespace may not be retracted while any declaration names
   it or any namespace requires it.* Seam **B**. This is the highest-fanout name
   edge in the graph and the one with the loudest failure, because a require
   edge that vanishes changes load order rather than raising.
5. **Operation.** `(my.program/delete-namespace! 'seon.turn)` — refusal data:
   the 176 owned declarations (which must be retracted in the same transaction)
   and the 67 requiring namespaces, each of which is then its own deletion
   subject with its own caller check. That cascade **is** the work packet the
   owner described handing to a set of agents, and it is computed by one query.

### C7. Test → the functions it reaches, and test → its declared subject

1. **Fact.** `:seon.test/reach [:vector {:cardinality :many} :seon.db/ref]`
   (`seon.test.edn:2`), recorded per run by the runner;
   `:seon.test/subject :seon.db/ref` (`:10`) from the Var's metadata;
   `:seon.test/reach-unknown [:string]` (`:4`), a string sentinel whose own
   docstring confesses that "absence of both membership and this marker is
   legacy unknown, never proof of an empty closure". Live: 1,861 tests, of
   which **1,274 have no recorded reach at all** and **194** carry the unknown
   marker; **1,693 functions are reached by no test**.
2. **Failure.** Two different ones, and they must not be conflated. Deleting a
   function named in 54 tests' recorded reach makes those tests' green basis
   stale — they must re-run. Deleting a function that is some test's declared
   **subject** leaves a test that tests nothing.
3. **Today: SILENT for both.** `seon.test/changed-since-green` re-reads the
   survivors as if the closure had always been smaller — absence read as
   health, at the gate, which is this project's named failure class.
4. **Invariant.** Two sentences. *Reach is evidence about a past run and never
   refuses a deletion; the deletion's committed report names every test whose
   recorded closure contained the deleted symbol, and those tests' green basis
   is void.* *A test's declared subject is a claim and does refuse.* Seam **B**
   for the subject; a positive fact on the deletion transaction for reach. A
   rule refusing on reach would make deletion impossible: 54 tests reach
   `seon.turn/open?`.
5. **Operation.** `my.program/delete!` returns both sets separately —
   `:seon.test/stale-reach` (advisory, re-run these) and
   `:seon.test/subject-of` (blocking, fix these). The distinction is the whole
   teachable point: the agent must learn that evidence about the past and
   claims about the present are different data.

### C8. Entity schema → its render pair functions

1. **Fact.** `:seon.render/ai` and `:seon.render/html` as `:qualified-symbol`
   **Malli properties** on the entity map, not datoms — e.g.
   `#:seon.render{:ai seon.render.value/render-ai, :html
   seon.render.value/render-html}` (`resources/seon/schemas/seon.render.edn:22-23`),
   `:seon.render/ai seon.effect/render-ai` (`seon.effect.edn:69`). **391**
   `:seon.render/ai` declarations across 81 schema resources; **100** distinct
   pair symbols; **0** of them currently name a function with no row. This is
   the cleanest name-observation in the population and the precedent for every
   other retype.
2. **Failure.** A render pair names a function that is deleted or renamed. The
   entity silently falls back to the floor producer
   (`seon.render/floor-producer`, `src/seon/render.clj:474-481`) and every
   agent quietly sees the default attribute-map printer instead of the curated
   render — ugly output, which is a defect by standing order, arriving with no
   error at all.
3. **Today: SILENT at the write; a typed unknown at render.** Selection
   resolves the symbol through `sci/resolve` at call time
   (`src/seon/render.clj:727`, `:760`) and an unresolvable one becomes
   `:unselected` (`:978`) or falls to the floor. The typed unknown exists and
   is well-built; nothing stops the break.
4. **Invariant.** *A function named by a live `:seon.render/ai` or
   `:seon.render/html` property may not be retracted, and a declaration
   naming a symbol with no row is refused at admission.* Seam **B**, reading
   the pair symbols out of the projection's forms exactly as this note's live
   query did.
5. **Operation.** `my.program/delete!` and `rename!` report
   `{:seon.render/declared-by #{<schema keys>} :seon.render/output
   :seon.render/ai}` — which schema loses its render and in which direction.

### C9. Capability → its handler function

1. **Fact.** Two encodings of one fact, by design and by the writer's own
   admission: `:seon.effect/capability :qualified-symbol` (the metadata
   marker) and `:seon.fn/capability-fn`, a ref to the handler's row
   (`seon.fn.edn:5-11`), both written at `src/seon/fn.clj:697-700`, where the
   comment says the ref exists so the question is "a join instead of a symbol
   every reader re-resolves". Live: **10** pairs, e.g. `my.fs/read` →
   `seon.fs.jvm/read`, `my.edit/form!` → `seon.edit.jvm/edit` (three `my.edit`
   functions share that one handler).
2. **Failure.** `seon.fs.jvm/read` is deleted. `my.fs/read` declares a
   capability nobody implements; the effect owner resolves the handler in one
   pull (`src/seon/effect.clj:252-254`) and finds a husk.
3. **Today: IMPOSSIBLE in one direction only.** `assert-capability-contracts!`
   (`src/seon/fn.clj:1776`, asserted at `:1902`) refuses a publication whose
   capability marker names a handler with no row — so the marker can never be
   written dangling. The reverse — deleting the handler afterwards — is the
   ordinary silent sweep, and `var-row` also adds the handler to the caller's
   `:seon.fn/calls` (`src/seon/fn.clj:700`), so once C1's rule exists this is
   covered by C1 for free.
4. **Invariant.** *A function named by a live capability marker may not be
   retracted.* Seam **B**, as a member of C1's edge set. The prior note prices
   deleting the `:seon.fn/capability-fn` ref as redundant with
   `:seon.effect/capability`; this inventory agrees for one reason it adds: with
   the ref gone the break is reported by the same sentence as every other
   caller, instead of being reconstructed from `:db-before`.
5. **Operation.** `my.program/delete!` reports
   `{:seon.effect/capability-of #{my.fs/read}}`.

### C10. Schedule task → its function

1. **Fact.** `:seon.schedule.task/function`, a ref carrying the tell —
   `:seon.fn/reference-to :seon.fn/sym`, a Malli property whose entire job is
   telling a reader how to recover the name from the ref
   (`resources/seon/schemas/seon.schedule.task.edn:3`). Written as a lookup ref
   from a literal portfolio (`src/seon/schedule.clj:106`). Live: **5** tasks —
   `seon.operator/observe-footprint!`, `reap-dead-roots!`, `rotate-logs!`,
   `census-processes!`, `collect!`.
2. **Failure.** `seon.operator/rotate-logs!` is renamed. The task's ref is
   swept; the firing query (`src/seon/schedule.clj:210-217`) joins
   `[?task :seon.schedule.task/function ?f] [?f :seon.fn/sym ?function]` and
   the task **disappears from the result**. Log rotation stops. Nothing logs,
   nothing raises, and the monitor that would notice is the thing that stopped.
   This is the purest instance of the project's named failure class in the
   whole inventory.
3. **Today: REFUSED, by accident.** `:seon.schedule.task/function` is a
   **required** key of the task entity map (`seon.schedule.task.edn:9-10`), so
   the swept referrer fails its own schema at `src/seon/db.clj:2958` and the
   deletion is refused. The earlier review's claim that the task "persists in a
   state its own schema forbids" is false and was corrected in
   [the agent/turn note](deletion-semantics-agents-and-turns-2026-09-16.md) §0.
   Keep it required; it is the worked example of the dial set correctly.
4. **Invariant.** *A function a live schedule task names may not be retracted
   without the task.* Already true. Write the regression that proves it and
   name the reason in the schema docstring, so the next reset does not
   "simplify" it to optional.
5. **Operation.** `my.program/rename!` must rewrite the task row; the refusal
   otherwise names the task id.

### C11. Config dial → its declared schema and its readers

1. **Fact.** A dial is an ordinary schema key in a `seon.config.*` family, with
   display metadata attached to the composite
   (`resources/seon/schemas/seon.config.edn:6-16`); composites derive from leaf
   declarations (`src/seon/schema/edn.clj:66`). Its readers are recorded as
   ordinary `:seon.fn/keywords` membership and, when it appears in a contract,
   as `:seon.fn.arity/input-refs`.
2. **Failure.** A dial is renamed; `config/effective` returns nothing for the
   old key; the reader takes its ordinary default and the system runs at a
   bound nobody chose. Silent by construction, because a missing dial is
   indistinguishable from an unset one.
3. **Today: SILENT**, and one step worse than C4 — `:seon.fn/keywords` is
   declared honest membership only, never a claim, so it correctly cannot be
   used as a refusal input. The dial's readers are genuinely not recorded as a
   connection.
4. **Invariant.** *A config dial's declaration and the default that backs it
   are one fact, and a reader of a key with no declaration is refused at
   admission.* The missing fact is **`:seon.config/read-by`** — or, better and
   cheaper, nothing new: make dial reads go through a declared accessor whose
   contract names the key in `input-refs`, which turns C11 into C5. Prefer the
   dissolution.
5. **Operation.** `(my.program/rename-schema-key! :seon.config.eval/time-limit-ms
   :seon.config.eval/deadline-ms)` refuses with the contract referrers it can
   see and **states plainly that keyword mentions are not in the answer** —
   a typed partial answer, never a confident one.

### C12. Flow proc → its step-fn Var

1. **Fact.** **NONE as a proc fact.** Graph definitions name step-fns as
   literal Vars in Clojure source — `#'mailbox-step`, `#'turn/step`,
   `#'schedule/schedule-step` (`src/seon/cluster/agent.clj:463`, `:469`,
   `:476`). The only trace in the graph is indirect and real: the analyzer
   records a var-quote as a **reference**, not a call
   (`src/seon/fn/analyzer.clj:324-347`, the `:var-quote` arm), so the
   graph-building function carries a `:seon.fn/references` edge to the step-fn.
2. **Failure.** A step-fn is renamed. The graph definition no longer compiles —
   which is loud, because it is ordinary Clojure. The quiet failure is the
   other one: a step-fn's *arity or return shape* changes and the proc's
   lifecycle arities silently mismatch at the next graph build.
3. **Today: DETECTED** by the reference edge (so C1's rule covers deletion once
   references join the refusal set) and otherwise by the compiler.
4. **Invariant.** *A function referenced by `#'f` anywhere may not be retracted*
   — the same sentence as C1, with `:seon.fn/references` in the edge set. No
   new proc fact is needed, and inventing one would be a second registry for
   something `create-flow` already owns.
5. **Operation.** Nothing specific. Noted here because "flow proc → step-fn"
   reads like a missing fact and is not one.

### C13. Protocol / multimethod → its implementations

1. **Fact.** **NONE.** The analyzer requests `:protocol-impls`
   (`src/seon/fn/analyzer.clj:30`) and normalises them (`:453-457`), but the
   result is consumed only to **attribute a body to its dispatch identity**:
   `attributed-usages` (`:324`) merges an implementation's span into
   `{:from protocol-ns :from-var method-name}` (`:341-344`), so calls made
   inside a `defmethod` or protocol implementation are recorded as calls **from
   the multimethod's own name**. No row exists for an implementation, and
   nothing records that `seon.render.value/render-ai` implements anything.
2. **Failure.** A protocol method is removed or its signature changes; the
   implementations are invisible to every query. Equally, an implementation is
   deleted and the dispatch silently falls through to `:default` or throws at
   the first value that needs it.
3. **Today: SILENT**, and unrepresented rather than merely unenforced.
4. **Invariant.** *Every protocol or multimethod implementation is a
   declaration with its own row, naming the dispatch identity it implements.*
   Seam **A** — this is a **new fact**, and the analyzer already computes
   everything it needs: `:protocol-ns`, `:protocol-name`, `:method-name`,
   `:impl-ns`, `:defined-by` are all normalised and then discarded.
5. **Operation.** `(my.program/implementations 'seon.print/Sink)` — a read
   before it is a refusal. Until the fact exists, `my.program/delete!` must
   say, in its result, that dispatch is not in the answer (§8).

### C14. Agent-authored definition → its provenance and the base definition it overrides

1. **Fact.** `:seon.schema.admission/source`, `:core` or `:agent`, stamped by
   `program/declaration-row row :all :agent` for the evaluation seam
   (`src/seon/fn.clj:1166-1169`) and derived from the asserting transaction at
   read time (`src/seon/sci/eval.clj:993-996`). Live: **5,087** `:core`
   functions and **3** `:agent`. What is **NOT** a fact: that an `:agent` row
   replaced a `:core` one. The prior definition is readable through
   `seon.db/history` and nothing records the override as a state.
2. **Failure.** An agent overrides a first-party function. Its SCI fork runs the
   new definition; every compiled JVM caller — the turn loop, the writer, the
   web server — keeps running the old one, and nothing in the graph says so.
   Two programs, one name, and no query distinguishes them.
3. **Today: SILENT, and by construction.** Acquisition chooses "reference the
   JVM Var" versus "interpret the database source" **by namespace kind**, not by
   the admitted row's provenance (`build-base-ctx`, `src/seon/sci/eval.clj:183`,
   `copy-var*` at `:197`; PRD §2.3 states the same). PRD S3 is exactly this
   invariant and is not started.
4. **Invariant.** *Loading is decided per identity by the admitted row's
   provenance, and the override set — identities whose current row is `:agent`
   under a `src` file root — is a declared public query whose answer appears in
   that function's `doc`.* Seam: acquisition, plus a required fact. PRD §1f G4
   already requires the **producing identity** on every definition row (the
   file entity/digest for an indexed declaration, the evaluation for an
   agent-authored one) — that is the fact, and it is required, so "we never
   looked" stops being spellable.
5. **Operation.** `(my.program/overrides)` as a read; `(my.program/write-back!
   'seon.turn/open?)` as the S5 operation that dissolves the state. The
   refusal for the latter is the gate's red tests by name.

### C15. File → the declarations it owns (write-back)

1. **Fact.** `:seon.fn/file`, a ref to the file entity (`seon.fn.edn:12`), and
   `:seon.fn/form-span [:tuple :int :int]`, half-open UTF-8 byte offsets of the
   declaration's exact source within that file (`seon.fn.edn:13`). Live: **339**
   files, 5,114 declarations. The file's identity is its relative path
   (`seon.fn.file.edn:1-3`).
2. **Failure.** A file is renamed. Because the path is the identity, a rename
   is a **new entity, not an update** — `upsert-eid` cannot follow it — so the
   old row is tombstoned and its declarations' `:seon.fn/file` refs survive
   pointing at a row with no digest. Spans then index into bytes that are not
   there, and write-back writes into the wrong file or the wrong offsets.
3. **Today: SILENT**, with a machine built specifically to survive it:
   `result-preservation-tx` strips and re-resolves `:seon.test.failure/file` by
   lookup across a publication (`src/seon/cluster/source.clj:471-477`). That
   preservation pass is the sighting — it exists only because a name-observation
   was filed as a ref.
4. **Invariant.** *A file entity may not be retracted while a declaration names
   it, and a rename re-points every declaration in the same transaction.* Seam
   **B** — make `:seon.fn/file` **required** on the declaration and it holds
   with no new code, exactly as `:seon.schedule.task/function` already does
   (C10). This also makes G4's analysis-provenance requirement enforceable,
   because the digest lives on the file row.
5. **Operation.** `(my.program/move! 'seon.turn "src/seon/turn/core.clj")`.
   Refusal data: the declarations whose file ref would dangle and the lint
   findings attached to the old path.

### C16. Agent / steward and the other cross-family connections

1. **Fact.** `:seon.ns/steward`, a ref to the agent that owns a namespace's
   faults (`seon.ns.edn:34-37`). Live: **3** of 438 namespaces stewarded.
2. **Failure.** The steward agent is deleted; the ref sweeps; and
   `src/seon/problems.clj:278` reads **absence** as "unowned", so a namespace
   whose steward vanished is indistinguishable from one that never had one.
3. **Today: SILENT**, and blocker-class precisely because of that absence read.
4. **Invariant.** *Deleting an agent must reassign or explicitly retract its
   stewardships.* Seam **B**.
5. **Operation.** Out of the program-graph refactoring scope; it belongs to the
   agent family, inventoried in
   [the agent/turn note](deletion-semantics-agents-and-turns-2026-09-16.md) §3.1.
   Listed here only so "every connection" means every connection.

---

## 4. The live graph, in one table

Cluster `default`, three read-only queries, 2026-09-16.

| Connection | Fact | Live count | State |
|---|---|---|---|
| caller → callee | `:seon.fn/calls` | 63,469 edges | SILENT |
| call-site arity → declared arity | `:seon.fn/call-arities` ↔ `:seon.fn.arity/min`/`max` | 31,020 sites; 3,412 resolvable; **0 violating** | DETECTED at index only |
| contract → schema key | `:seon.fn.arity/*-refs` | 3,693 | SILENT at write |
| schema → schema | `:seon.schema/references` | 4,527 | SILENT |
| function → written key | `:seon.fn/writes` | 1,052 | SILENT (data half refused) |
| namespace → requires | `:seon.ns/requires` | 3,215 (67 → `seon.turn`) | SILENT |
| declaration → namespace | `:seon.fn/ns` (required) | 5,114 | **REFUSED** |
| entity schema → render pair | `:seon.render/ai` / `/html` properties | 391 declarations, 100 symbols, 0 dangling | SILENT at write, typed unknown at render |
| capability → handler | `:seon.effect/capability` + `:seon.fn/capability-fn` | 10 | REFUSED forward, SILENT backward |
| schedule task → function | `:seon.schedule.task/function` (required) | 5 | **REFUSED** |
| test → reach | `:seon.test/reach` | 1,861 tests; 1,274 with none; 194 unknown | SILENT |
| function → file + span | `:seon.fn/file`, `/form-span` | 339 files | SILENT |
| provenance | `:seon.schema.admission/source` | 5,087 `:core`, 3 `:agent` | no override state |
| protocol → implementations | — | **no fact** | SILENT |
| config dial → readers | — | **no fact** | SILENT |
| contract coverage | `:seon.fn/spec` | 79 of 1,204 public functions carry none | the first agent task (PRD F7) |
| test coverage | reach | 1,693 functions no test reaches | the second agent task (PRD F7) |

---

## 5. What can be made impossible, in order

**Tier 1 — cheap, with facts we already store, no new attribute.**

1. **Make `:seon.fn/file` required** on the declaration entity. One resource
   line; C15 becomes REFUSED; costs nothing because every indexed declaration
   already has one and an agent-authored one legitimately has none — which
   means this is required *only* for `:core` provenance, so it lands with the
   provenance work, not before it.
2. **Write the regression for `:seon.schedule.task/function` and `:seon.fn/ns`**
   (C10, C6) asserting the refusal that already happens, so the next reset
   cannot quietly relax it. One test, two assertions, zero production change.
3. **Turn on the arity check** (C2) at seam B. Pure join over `:db-after`; the
   measured violation count today is 0, so it goes green on arrival.
4. **Refuse a render-pair symbol with no row at admission** (C8). The query in
   this note is the check; today's answer is already 0 missing.

**Tier 2 — needs the edge retype, which needs the reset.**

5. **The deletion admission contract** (C1, C4, C5, C6, C7-subject, C9): the
   edges become values (`[:set :qualified-symbol]` / `[:set :qualified-keyword]`,
   PRD §1f G2 plus the extensions the prior note lists), the tombstone
   machinery is deleted (G3), and `write-report-error` grows one AVET lookup per
   (deleted identity × declared edge attribute). The value form is not an
   exemption from the refusal — it is what lets the refusal tell a **repair**
   from an **erasure**, because a repaired caller's new symbol set simply does
   not contain the deleted name, while a swept ref leaves the two
   byte-identical.
6. **G4's required producing-identity fact** on every definition row. Without
   it, a value-edge set makes "A calls nothing" and "A was never analysed" the
   same zero datoms, and the refusal inherits the defect it exists to remove:
   a never-analysed caller looks fixed.

**Tier 3 — genuinely new facts, each named.**

7. **`:seon.fn.impl/*` — implementations as declarations** (C13). The analyzer
   already computes `:protocol-ns`, `:protocol-name`, `:method-name`,
   `:impl-ns` and discards them.
8. **Dial readers** (C11) — and the recommendation is to *dissolve* rather than
   add: route dial reads through declared accessors so `input-refs` answers it.
9. **Provenance decides loading** (C14) — PRD S3, no new attribute, a changed
   decision at `build-base-ctx` plus one declared public query.

---

## 6. The four operations to teach first

These four cover the overwhelming majority of refactors, and each one's
**refusal is its teaching material**. All live in one new agent-facing
namespace, `my.program`, over `seon.program` / `seon.fn` / `seon.db` — never a
second writer.

1. **`(my.program/delete! 'sym)`** — retract a declaration. Input: a qualified
   symbol or a set of them. Success: the retraction plus the advisory stale-reach
   report. Refusal: `{:seon.fn/callers … :seon.fn/references … :seon.test/subject-of …
   :seon.render/declared-by … :seon.effect/capability-of … :seon.schedule.task/of …}`,
   each group naming the attribute that carries it, plus
   `:seon.test/gating` (the tests that will prove the repair). The set version
   is what makes coordinated deletion expressible: delete the function and its
   namespace together and the refusal never fires.
2. **`(my.program/rename! 'old 'new)`** — the operation an AI-first environment
   should make trivial and a file-first one makes terrifying. It is not a text
   substitution: it is *define `new`, rewrite every caller's source, retract
   `old`*, in one transaction. Success: the changed entity set. Refusal: the
   call sites it could not rewrite, **with their spans**
   (`:seon.fn/form-span`, so an agent edits bytes it can locate without
   searching) and the reason each one resisted — a dynamic call, a name built
   at runtime, a caller outside the program graph.
3. **`(my.program/define! …)` / `change-contract!`** — install or change a
   declaration. It already has the strongest gate in the tree in
   `gate-function-install` (`src/seon/turn.clj:3285`): analyse, derive the
   reaching tests with `seon.fn/gate-set`, run them in a candidate context,
   install only on green. Refusal: the red tests with their shown text, and —
   for a contract change — the accretion verdict naming which clause narrowed.
4. **`(my.program/breaks 'sym)`** — the read that returns exactly what
   `delete!` would refuse with, without attempting anything. This is the one
   that makes the other three teachable: an agent asked to refactor starts by
   asking what it would break, gets a data answer, plans from it, and meets no
   surprise. It is also the honest detector for PRD F7's first two agent tasks
   ("functions without a contract", "functions no test reaches"), because both
   are the same query shape.

The teaching sentence for all four: **the refusal is not an obstacle, it is the
assignment.** An agent that receives a caller list has been handed a work
packet, and the same operation run again with the repairs in the same
transaction commits with no flag, no override, and no ordering rule.

---

## 7. The honest gaps

Each one needs a **typed unknown** in the operation's result, never a silence.
The rule: if a reader could mistake "we cannot see this" for "there is nothing
here", the operation says so in its own data.

| Gap | Why the analyzer cannot see it | The typed unknown |
|---|---|---|
| **Dynamic dispatch** (`resolve`, `requiring-resolve`, `ns-resolve`, a symbol in a map) | the callee's name exists only at runtime | `:seon.program/unresolvable-callers` with the call sites whose target is computed — the analyzer *does* see the `resolve` call itself, so this is a positive fact, not an absence |
| **`apply`** | the argument count is unknown, so the arity tuple cannot be formed | `:seon.fn/call-arities` simply omits the site; the operation reports `:seon.program/arity-unknown-sites` rather than claiming all sites checked |
| **Macros** | a macro's expansion produces calls clj-kondo attributes to the macro or to nobody; `:seon.fn/macro?` is recorded but the expansion is not | `:seon.program/macro-mediated` naming the macro |
| **`defmethod` / protocol bodies** | attributed to the dispatch identity, so the implementation has no row (C13) | `:seon.program/dispatch-not-modelled` — today this must be said out loud on every `delete!` of anything that might be an implementation |
| **`#'f` var-quotes** | recorded as `:seon.fn/references`, deliberately weaker than a call: "no invocation or arity is asserted" (`seon.fn.edn:42`) | report references as their own group, never merged into callers |
| **Keyword mentions** | `:seon.fn/keywords` is honest membership only and is not a read claim (`seon.fn.edn:105-109`) | never in a refusal; reported as `:seon.fn/mentions` when an agent asks for it |
| **Callers outside the program graph** | a name used by something never indexed | the population is the boundary; say which branch answered |
| **A complete republish** | the rule is vacuous there — every row is written in one transaction, so a function absent from source simply has no row | the equivalent check is **positive**: report every edge symbol with no row as the unresolved set. Refusing a reset would leave the operator with no cluster, the one outcome worse than a dangling name |

Two further honest limits, stated because they are the ones most likely to be
over-promised:

- **Argument shapes are not checkable** (C3b). We know each call site's argument
  *count*, never its *shape*. clj-kondo's type inference is explicitly
  downgraded because it "is not a sound admission proof for database pulls and
  branch-sensitive Malli contracts" (`src/seon/fn/analyzer.clj:20-26`). The
  reachable guarantee is the test gate, not a static one. An inventory that
  claimed otherwise would be the same absence-as-health defect in a new suit.
- **The JVM gap** (C14). Until write-back lands, an accepted override changes
  what agents run and not what the turn loop, the writer or the web server run.
  The `doc` of an overridden function must say so in one line, derived from the
  same query.

---

## 8. Verification boundary

- Working tree at `dd2f2f493` with several lanes' uncommitted edits present
  (`git status` names `src/seon/instrument.clj`, `src/seon/render.clj`,
  `src/seon/schema.clj`, `src/seon/schedule.clj`, `src/seon/sci/eval.clj`,
  `src/seon/cluster.clj`, `resources/seon/schemas/seon.config.edn` and others).
  **Every `file:line` in this note is a working-tree line I opened in this
  session**, not a HEAD line; re-check any citation in those files before using
  it in a spec.
- Three read-only `mcp__seon__eval_clj` calls against `default` (mode `jvm`,
  custody `(seon.operator/connection "default")`), all pure `seon.db/q`
  bundles. Nothing was transacted. Every count in §3 and §4 comes from them.
- No gate was run, no test JVM launched, no `bin/seon` state changed.
- **Not established, and each is a probe before the contract lands:** (a) the
  cost of the seam-B lookup at 63,469 edges — argued from the AVET index, not
  measured; (b) whether a deletion refusal surfaces cleanly through `seon.db`'s
  flat-error extraction, which I read but did not exercise; (c) how often the
  edit hook's incremental publication would refuse in practice — one day's hook
  logs would settle it; (d) the arity-check baseline of 0 violations covers
  only the 3,412 call sites whose callee carries arity rows, not the other
  27,608, most of which name `clojure.core` and other external targets.
