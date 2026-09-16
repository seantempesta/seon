---
type: research
status: active
created: 2026-09-16
tags: [datahike, program-graph, schema, deletion, refs, tests, refusal]
---

# Deletion semantics for the program graph and the test families

Date 2026-09-16. Branch `steward-platform`, HEAD `6a2201f29`. Read-only. No
production edit, no test JVM, no `bin/seon` state change. Two read-only
`mcp__seon__eval_clj` calls against cluster `default` (mode `jvm`, custody
`(seon.operator/connection "default")`), both pure queries; nothing was
transacted.

The owner's direction, verbatim, which sets the goal:

> *"Call edges in what scenario? a function gets deleted then there should be
> alarm bells ringing if we still have 'call edges' to the function or we
> shouldn't allow the function to be deleted until the call edges are first
> fixed and pointed to a different function or whatever. These are good
> problems to have. We want bulletproof code being written so we want to know
> about all breaks and to STOP AGENTS from breaking things until a fix is in
> place. … Retraction shouldn't be allowed until a fix is also proffered (in
> the same transaction?) or we use the breaking call graph to ask a bunch of
> agents to do the refactoring before we apply the retraction."*

> *"We have history on the db so nothing is ever lost."*

And earlier, on the schemas themselves: *"Doesn't it depend? … WE OWN THE
SCHEMAS. WE ARE ADAPTABLE. DO NOT LOCK US INTO BAD PRIOR DECISIONS."*

## 0. The one sentence

**There are only two admissible behaviors — cascade for containment, refuse
for every other connection — and the ref-versus-value question is no longer
about protecting an edge from deletion but about whether the refusal can tell
a REPAIR from an ERASURE.** It can with values and it cannot with refs,
because `retract-entity`'s incoming sweep removes the caller's edge from
`:db-after` before the check runs, leaving a repaired caller and a swept
caller byte-identical (§2.3). Everything else follows.

Authorities read end to end before writing: AGENTS.md §2 and §3;
[the datahike skill](../../../../.claude/skills/datahike/SKILL.md);
[the data-modeling skill](../../../../.claude/skills/data-modeling/SKILL.md);
`reference-code/datahike/src/datahike/db/transaction.cljc`,
`reference-code/datahike/src/datahike/pull_api.cljc`,
`reference-code/datahike/src/datahike/query.cljc`;
[the deletion study](datahike-deletion-and-the-program-graph-2026-09-16.md);
[the schema design review](schema-design-review-2026-09-17.md) §C.1 and N1–N11;
[the eval-path and deletion-contract review](design-review-eval-path-and-deletion-contract-2026-09-17.md)
Design 2; [program-facts PRD §1f](../plan/program-facts-are-the-runtime-prd-2026-09-17.md);
[the reset batch](../plan/reset-batch-2026-09-17.md).

---

## 1. The menu, verified against the Datahike source

| # | Behavior | What the source does | Where it is declared | file:line |
|---|---|---|---|---|
| **1** | **cascade** — the referrer dies with the target | `retract-entity` maps every **component-valued datom of the entity being retracted** to `[:db.fn/retractEntity v]`. Direction is **parent → child only**: retracting a child never retracts its parent. | `:seon.db/component true` | `reference-code/datahike/src/datahike/db/transaction.cljc:831-834`, dispatch `:1080-1082`; `retractAttribute` does the same for one attribute `:1073-1078` |
| **2** | **sweep** — the referrer's ref datom is retracted silently | `retract-entity` scans `(dbi/search db [nil a e])` for **every** attribute in `(dbi/-attrs-by db :db.type/ref)` and retracts each incoming datom. The surviving referrer is **not re-validated**; a required key can vanish from an entity that stays alive. | the default for every plain `:seon.db/ref`; nothing declares it | `transaction.cljc:998-1014` |
| **3** | **refuse** — the writer rejects the whole transaction unless the break is fixed in it | Ours. `validate-report` runs the final-report validator **once, after all expansion**, with `:db-before`, `:db-after` and `:datahike/attempted-tx-data`; **any non-nil return throws and the transaction is rejected atomically**. `seon.db/write-report-error` already occupies that seam. | a declared property, or by construction | `transaction.cljc:1206-1216`; `src/seon/db.clj:3009-3041`, `:3043-3050` |
| **4** | **value** — the fact stores the target's identity value | No ref exists, so the sweep cannot see it, and the fact is still **present in `:db-after`** where the refusal check can read it. | the attribute's Malli type | measured in [deletion study E4–E6](datahike-deletion-and-the-program-graph-2026-09-16.md) |
| **5** | **purge** — the fact is removed from history too | `:db/purge`, `:db.purge/entity`, `:db.purge/attribute` operate on a `HistoricalDB` and refuse outright without `keep-history?`. The only behavior that makes `as-of` stop answering. | the operation, never the schema | `transaction.cljc:1084-1117`, `:836-840` |

**The anticipated fifth — "keep the referrer fact and answer through history
only" — is real but is behavior 2 plus a temporal reader**, and under the
owner's direction it is now disqualified outright: it answers correctly only to
a reader that knows to ask, and the point of this design is that nobody should
have to know.

### 1.1 Why behavior 2 is the enemy and retraction is not

The owner's *"we have history on the db so nothing is ever lost"* settles the
half of this question that the earlier documents worried about. Retraction is
history-preserving (`transaction.cljc:813-819`; `as-of` / `since` reconstruct a
swept edge exactly, deletion study E2), so **retracting a definition loses
nothing**. The defect in behavior 2 was never the lost datom. It is that **a
surviving entity now asserts something different and nobody was told** — A's
row silently stops saying it calls B. That is the project's named failure
class, and it is orthogonal to whether the bytes survive in the temporal index.

The same distinction answers "losing information versus eliding it". The
database elides nothing: every edge, every caller, every retraction is a datom,
and history keeps all of it. **Elision is a presentation decision and lives in
the one clipping spot** — the AI render functions and the value renderer. So a
refusal diagnostic carries the *complete* caller list as data, and a long list
is elided at render under the profile as an elision value naming the count,
never truncated in the fact. Losing information is a database defect; eliding
it is a render contract. They never trade against each other here.

### 1.2 What a reader sees after a sweep — why "silent" is the right word

| Read | Result when the ref target has no datoms | file:line |
|---|---|---|
| Datalog join | the clause fails to match; the referrer disappears from the result. A numeric eid is bound verbatim with no existence check. | `query.cljc:1394-1401` |
| pull with a sub-selector | the member is **dropped from the collection** (empty `kvps` → `nil`, and a nil subpattern result is not kept) | `pull_api.cljc:483-486`, `:209-217` |
| wildcard pull, plain ref | `{:db/id n}` with nothing behind it | `pull_api.cljc:351-357`, `:299-302` |
| wildcard pull, component ref | the full nested child map, auto-expanded | `pull_api.cljc:345-349` |
| `[:db/id]` on a dangling eid | omitted; the map can come back empty | `pull_api.cljc:370-376` |
| top-level `pull` of a dangling eid | `nil` | `pull_api.cljc:508-515` |
| an empty cardinality-many value at write | **no datoms at all** | `transaction.cljc:739-770`, `:718-737` |
| re-asserting a retracted identity | a **new eid**; `upsert-eid` resolves through the AVET datom that no longer exists | `transaction.cljc:641-712`, `:659` |
| a lookup ref to a retracted identity | **raises** `"Nothing found for entity id"` and aborts the transaction | `reference-code/datahike/src/datahike/db/utils.cljc:141-148`; asserted first-party at `src/seon/cluster/source.clj:358-363` |
| a thrown transaction function | aborts the transaction; `[:db.fn/call …]` sees the mid-transaction database | `transaction.cljc:1153-1154` |

Every consequence of behavior 2 arrives as **fewer results**, never as an
error. Every consequence of behaviors 3 and 4 arrives as a refusal or a
positive query row.

---

## 2. The deletion admission contract

### 2.1 The rule

> For every identity value **v** whose row this transaction retracts, and for
> every declared edge attribute **a**, if any entity that is still alive in
> `:db-after` names **v** through **a**, the transaction is **refused**, and
> the refusal names every such entity and attribute.

Three consequences fall out without further design:

1. **The fix rides in the same transaction, for free.** A caller re-analyzed in
   the same publication simply does not name `v` in `:db-after`, so the check
   passes. There is no "was it repaired?" flag, no ordering rule, no pre-read:
   the authority reads the finished world and decides.
2. **Otherwise the refusal IS the work list.** The named callers are exactly
   the refactoring assignment, and they are already the shape the issue family
   cites — `:seon.issue/functions` (retyped to a qualified-symbol set by the
   reset batch) takes the list verbatim.
3. **Nothing is ever half-applied.** `validate-report` rejects by throwing, and
   the transaction is atomic (`transaction.cljc:1206-1216`), so a refused
   deletion leaves the database exactly as it was.

### 2.2 It is implementable in the seam we already have

`seon.db/write-report-error` (`src/seon/db.clj:3009-3041`) already binds, in
this order, everything the rule needs:

- `before` = `:db-before`, `database` = `:db-after` (`:3011-3012`);
- `attempted` = `:datahike/attempted-tx-data` (`:3013`);
- `affected` = `(distinct (map :e (concat attempted (:tx-data report))))`
  (`:3014`) — **the candidate deletion set**;
- `prior-identities`, read as `(d/datoms before :eavt entity-id)` filtered to
  the identity attributes (`:3034-3039`) — **the deleted entity's identity
  value, recovered from `:db-before`**.

So the deletion detection is: *an affected eid with datoms in `before` and none
in `database`*, and its identity values are already computed three lines away.
The only new work is one AVET lookup per (deleted identity × declared edge
attribute) against `:db-after`, and the flat `:seon.error` constructor. **No
fork change is required for the value-edge design.**

The fork's proposed `:datahike/retracted-entities` ephemeral set
([design review](design-review-eval-path-and-deletion-contract-2026-09-17.md):335-344)
remains worth having — it distinguishes a deletion from a deliberate unlinking
and covers transaction-function expansion — but it is an accuracy improvement,
not a precondition.

### 2.3 Why the representation decides whether the refusal is honest

The ordering is not the problem: the sweep happens during
`transact-tx-data` and the validator runs afterwards
(`transaction.cljc:1206-1216`), but the transaction is still rejected
atomically, so a ref-typed edge *can* be protected. The problem is **what
evidence survives to the check**.

| | ref edge (`[:set :seon.db/ref]`, today) | value edge (`[:set :qualified-symbol]`) |
|---|---|---|
| where the caller's edge is in `:db-after` | **gone** — swept by `retract-entity` (`:998-1014`) | present, untouched |
| how the check finds the callers | reconstruct from `:db-before`: for every ref attribute, AVET `[s a d]` on the pre-transaction database | one AVET lookup per deleted identity on `:db-after`: `(datoms after :avet :seon.fn/calls v)` |
| a caller repaired in this transaction | **indistinguishable from a swept one** — both have no edge in `:db-after`. The only available test is the proxy "was its edge attribute re-asserted in this tx?", which does not answer "does it still name v" | expressed directly: the repaired caller's new symbol set does not contain `v` |
| a caller re-analyzed in this tx that **still** calls the deleted function | the lookup ref `[:seon.fn/sym v]` is unresolvable and Datahike raises (`db/utils.cljc:141-148`) — a correct refusal, but a *transaction syntax* error, not a break report naming the work | the symbol is stored; the check reports it as a caller, with the attribute and the list |
| a caller **not** in this transaction | silently swept unless the `:db-before` reconstruction runs | reported by the same one lookup, with no reconstruction |
| what the refusal can say | "entity 4711 pointed at the deleted entity" | "`seon.turn/recover-call` still calls `seon.turn/open?` through `:seon.fn/calls`" |

**The refusal the owner asked for — refused until the callers are fixed, and
the fix may ride in the same transaction — is expressible with value edges and
is only approximable with ref edges.** That is now the reason for G2's retype.
G2's own stated reason ("deleting a function touches only its own datoms") is
no longer the goal; the goal is the opposite, and the retype serves it better.

### 2.4 The alarm bell is already half-built

`seon.fn/assert-clean-analysis!` (`src/seon/fn.clj:1054-1070`) **already
refuses a publication** whose analysis carries a blocking finding, and
`:unresolved-var` is in the blocking set (`src/seon/fn.clj:1009-1015`),
downgraded to a warning only for a genuinely external target
(`src/seon/fn.clj:1039-1043`, `:1017-1035`). So when a caller inside the
analyzed set names a function nobody defines, the alarm already rings and the
publication is refused.

The gap is exactly one direction: **the analyzer sees the files it analyzed;
the program graph sees every caller in the cluster.** A function deleted from
`src/seon/turn.clj` while `src/seon/render/web.clj` still calls it produces no
finding at all, because `render/web.clj` was not analyzed. §2.1's rule closes
that gap using the facts the database already holds, and the two mechanisms are
the same alarm at two scopes — the analyzer for the publication's own files,
the writer for everything else.

The stale-cache caveat AGENTS.md §5 records ("a kondo *Unresolved var* on a
protocol or a dependency name is a stale dependency cache until proven
otherwise") applies to the analyzer arm and **not** to the writer arm: the
writer's refusal is derived from `:seon.fn/sym` rows in the same database, so
it has no cache to be stale about.

### 2.5 Test reach is a break to surface, never a member to drop

A test whose recorded `:seon.test/reach` names a deleted function is **not** a
reason to refuse the deletion — 54 tests on `default` have `seon.turn/open?` in
their closure, and a rule that refuses on recorded evidence makes every
deletion impossible. Reach is evidence about a **past run**, not a claim about
the present.

But it is a break, and it must be reported. Today the sweep drops those 54
members silently and `seon.test/changed-since-green` (`src/seon/test.clj:62`,
`:98-101`) re-reads the survivors as if the closure had always been smaller —
"absence read as health" at the gate. Under the recommendations:

- reach is a **symbol set**, so nothing is dropped;
- the deletion's committed report names the tests whose recorded closure
  contains the deleted symbol — those tests' green basis is stale and they must
  re-run;
- `changed-since-green` resolves each member symbol and treats an unresolvable
  one as a **changed dependency**, which selects the test.

`:seon.test/subject` is the opposite case and **does** participate in the
refusal: a test whose declared subject is deleted tests nothing, and that is
refactoring work exactly like a caller's.

### 2.6 The three deletion origins, each with its contract

| Origin | Path today | What the contract does | Consequence to name |
|---|---|---|---|
| **(a) An agent's SCI evaluation** — `ns-unmap`, `seon.schema/unregister!`, redefinition | `seon.program/deletion-row` (`src/seon/program.cljc:1058`) → `seon.sci.eval` (`src/seon/sci/eval.clj:299`, `:2024-2029`) → `seon.turn/row-tx` (`src/seon/turn.clj:1304`), which today writes the identity-only tombstone (`src/seon/turn.clj:1336-1339`) | The write is **refused** and the agent receives a flat `:seon.error` naming every caller and the attribute — precisely the agent-boundary error value AGENTS §2.4 requires. The agent is mid-turn and can repair the callers and re-evaluate, or open the work. | This is the ideal surface: nothing is committed, the agent is the one who broke it, and it is told in the same turn. **Redefinition is not deletion** — `upsert-eid` keeps the identity's eid (`transaction.cljc:641-712`) and, with symbol edges, callers reconnect by name with no transaction at all. |
| **(b) The edit hook's file-driven publication** — a function removed from source while callers elsewhere still name it | `.claude/seon-hook.edn:25-29` → `bin/seon init --dev default --changed PATH` → `seon.cluster/refresh-source!` (`src/seon/cluster.clj:2355`) → `seon.fn/reconcile-tx` with `program/exact-replacement-tx-in` (`src/seon/program.cljc:1023`) | The analyzer arm already refuses an unresolved var **within the published files** (§2.4). The writer arm refuses when a caller **outside** the publication still names the symbol, and the refusal names those callers. A multi-file edit that deletes and repairs together publishes cleanly, because both files are in the same `--changed` set and the repaired caller no longer names the symbol. | **The cluster stops adopting until the tree is coherent**, staying on its previous `:seon.source/commit-id` with the reason in `logs/current-source-failure.log` (AGENTS.md §6; AGENTS.md §1 records the single adoption retry at `src/seon/cluster.clj:2042`). That is correct — a half-deleted program should not be the development environment — but it makes the refusal's legibility load-bearing: it must name every caller, or a lane loses an hour to a publication that "just fails". |
| **(c) The complete republish at a reset** — `bin/seon init` | a fresh `:current-src` branch built from the whole tree | **The rule is vacuous here and must not fire.** A complete publication has no surviving referrers to protect: every row is written in the same transaction, so a function absent from source simply has no row, and its callers' symbol edges name it with no row. | The equivalent check at a complete publish is **positive, not a refusal**: report every edge symbol with no row as the unresolved set. Refusing a reset would leave the operator with no cluster at all, which is the one outcome worse than a dangling name. This asymmetry is the reason the rule is stated over *surviving* referrers rather than over the finished graph. |

---

## 3. The inventory — one row per declared ref-typed or symbol-shaped attribute

Enumerated from `resources/seon/schemas/`, not from memory. **`L`** is the
recommended behavior from §1. Under §2.1 the admissible set collapses to
**1 (containment)** and **3 (refuse)**; **4 (value)** appears wherever the fact
is an observation of a token, and it is there *so that the refusal in 3 can name
the break honestly*, not to exempt the fact from the refusal.

### 3.1 `seon.fn.edn` — the function and test declaration row

| Attribute | Declared today | Who writes it | What the fact MEANS in the writer's terms | What a reader needs when the target is deleted | L + schema form | Prior ruling |
|---|---|---|---|---|---|---|
| `:seon.fn/sym` | `[:string {:seon.db/identity true, :seon.search/index :symbol}]` (`seon.fn.edn:181-185`) | `seon.fn/var-row` (`src/seon/fn.clj:585`, `:642`); agent seam `seon.sci.eval/definition-row` (`src/seon/sci/eval.clj:392`) | the declaration's identity | nothing — it IS the thing deleted | retract the whole entity; `:qualified-symbol` | **Overturns ruling 47.** A bare identity row is the tombstone G3 deletes — and under §2.1 it is worse than useless: it makes the break *unrefusable*, because the caller's lookup ref still resolves |
| `:seon.fn/calls` | `[:set :seon.db/ref]` (`:23`), written as lookup refs `[:seon.fn/sym target]` | `seon.fn/var-row` (`src/seon/fn.clj:625` test arm, `:663` function arm, `:702` capability arm) | **an observation of a token**: clj-kondo reported this qualified name inside this declaration's source span | the name, in `:db-after`, so the refusal can say who still calls it. Readers: `seon.fn/gate-set-in` AVET walk (`src/seon/fn.clj:1343`), reach rules (`:1281-1287`), `seon.test/destructive-path` (`src/seon/test.clj:247-252`), `src/seon/run.clj:99`, the runner's closure (`src/seon/test/runner.clj:1991`) | **4 + 3** — `[:set {:seon.db/index true} :qualified-symbol]`, participating in §2.1's refusal | Keeps G2's retype, **for a new reason** (§2.3). Overturns the **population invariant** and G2's stated rationale |
| `:seon.fn/references` | `[:set :seon.db/ref]` (`:42`) | `src/seon/fn.clj:630`, `:668`, `:900` | its docstring: "Function references without a resolved call shape … no invocation or arity is asserted" — a token | same as `calls`; same walk (`src/seon/fn.clj:1345`), same selection (`src/seon/test/selection.clj:121`) | **4 + 3** — `[:set {:seon.db/index true} :qualified-symbol]` | Extends G2 (review N1) |
| `:seon.fn/writes` | `[:set :seon.db/ref]` to `:seon.schema/key` rows (`:44-56`) | `src/seon/fn.clj:636`, `:674`, `:907` | its docstring: "a qualified keyword whose position lies inside the span of a `seon.db/transact!` usage" — a keyword observed in text | the keyword; `:seon.fn/keywords` (`:105-109`) already stores the identical observation as a **value**, in the same file | **4 + 3** — `[:set {:seon.db/index true} :qualified-keyword]` | Extends G2 (review N2) |
| `:seon.fn/keywords` | `[:set :qualified-keyword]` (`:105-109`) | `src/seon/fn.clj:634`, `:672`, `:904` | already behavior 4 | unchanged | **4**, **not** in the refusal set: its docstring says it is "honest membership only, never a read-versus-write claim", so a keyword mention is not a broken connection | — |
| `:seon.fn/call-arities` | `[:set [:tuple [:string] [:int]]]` (`:57-72`) | `src/seon/fn.clj:638`, `:676`, `:909` | already behavior 4, and its docstring says why: "Datahike stores tuple members verbatim — it resolves neither a lookup ref nor a tempid inside a tuple (probed 2026-09-16)" | retype the callee member | **4** — `[:tuple :qualified-symbol [:int {:min 0}]]`; the refusal reads `calls`, not this | — |
| `:seon.fn/pending-calls` | `[:set [:string {:min 1}]]` (`:99-101`) | the declaration writer | the resolution-failure half of `calls` | nothing — an unresolved call becomes a symbol with no row | **delete** | Reset batch already schedules this |
| `:seon.fn/unresolved-references` | `[:set :seon.fn/sym]` (`:43`) | `seon.fn/artifact` (`src/seon/fn.clj:1127`, `:1226`) | already behavior 4, at file granularity | unchanged; redundant once per-declaration symbol edges land (read at `src/seon/fn.clj:1288`, `:1388`, `src/seon/test/selection.clj:171`) | **4** — keep for now; candidate for dissolution | — |
| `:seon.fn/ns` | `[:and … :seon.db/ref]` (`:178`) | `src/seon/fn.clj:645`; error path `src/seon/error.clj:1417` | **a statement about a living entity** | the namespace must exist. Readers: `src/seon/error.clj:1283`, `:1679`; `src/seon/fn.clj:791`; `src/seon/problems.clj:310` | **3** — keep the ref; a namespace deletion refuses unless its declarations are retracted in the same transaction | Keeps G2 |
| `:seon.fn/file` | `[:and … :seon.db/ref]` (`:12`) | `src/seon/fn.clj:615`, `:647` | **a statement about a living entity**: these are the bytes the analyzer walked | the file row, because its digest is the analysis provenance G4 makes required. Readers: `src/seon/fn.clj:1290`, `:1390`; `src/seon/test/runner.clj:2152-2154`; `src/seon/issue/detect.clj:96`, `:128` | **3** — keep the ref | Keeps G2; **required** by G4 |
| `:seon.fn/ast` | `[:and {:seon.db/component true} :seon.db/ref]` (`:22`) | `seon.program/ast-node` (`src/seon/program.cljc:533`, `:798`) | **containment** | nothing. 1,140 roots live on `default` | **1** unchanged, if the family survives (review C.2) | — |
| `:seon.fn/arities` | `[:vector {:seon.db/component true} :seon.db/ref]` (`:21`) | `seon.program/arity-row` (`src/seon/program.cljc:692`) | **containment** | nothing. 3,015 components on `default` | **1** unchanged | — |
| `:seon.fn/capability-fn` | `[:and {:seon.db/index true, :seon.fn/reference-to :seon.fn/sym} :seon.db/ref]` (`:5-11`) | `src/seon/fn.clj:700` | the docstring concedes it: "a ref to the handler's own declaration, **beside** the `:seon.effect/capability` symbol the metadata carries" | the handler's name; reader `src/seon/effect.clj:252-254`. Live: **2** pairs | **4 + 3** — delete the ref, keep `:seon.effect/capability`, and put that symbol in the refusal set. Deleting a capability handler with a live declaration is exactly the break the owner wants refused | **Overturns G2's explicit "refs stay … `/capability-fn`"**; priced in §5.2 |
| `:seon.fn/reference-to` | `[:qualified-keyword]` (`:4`) | a Malli property | exists only to recover the name from a ref | nothing | **delete** with the refs it annotates | Its existence is the evidence for behavior 4 |

### 3.2 `seon.fn.arity.edn`, `seon.fn.argument.edn`, `seon.fn.ast.edn` — the contract components

| Attribute | Declared today | Who writes it | What the fact MEANS | Reader need on deletion | L + form | Ruling |
|---|---|---|---|---|---|---|
| `:seon.fn.arity/arguments` | `[:vector {:seon.db/component true} :seon.db/ref]` (`seon.fn.arity.edn:8-9`) | `src/seon/program.cljc:692` | containment | nothing | **1** unchanged | — |
| `:seon.fn.arity/input`, `/output`, `/guard` | plain `:seon.db/ref` (`seon.fn.arity.edn:4`, `:16`, `:2`) | `src/seon/program.cljc:692`; values are `component-id`s **inside the sibling `:seon.fn/ast` tree** | a pointer into another attribute's component forest on the same parent | `/input` and `/output` are **required** (`seon.fn.arity.edn:31-32`); reconciliation retracts `:seon.fn/ast` with `[:db.fn/retractAttribute …]` (`src/seon/fn.clj:2311-2314`), which cascades into the tree and **sweeps these required refs off the arity**. Safe today only because the same code retracts `:seon.fn/arities` in the same breath | **1** — make them components of the arity, or delete them with the AST family. A required key a sibling's retraction can remove is a defect either way | New finding; not in G1–G6 or N1–N11 |
| `:seon.fn.arity/return-schema`, `/guard-schema` | plain `:seon.db/ref` (`seon.fn.arity.edn:10-11`) | `src/seon/program.cljc:725`, `:731` | a ref to a **shared, content-addressed** shape row — `seon.fn.schema-shape/shape-row`'s docstring: "Shared content-addressed row for one compiled Malli schema" (`src/seon/fn/schema_shape.clj:299-300`), upserted on `:seon.schema.shape/fingerprint` (`seon.schema.shape.edn:2`) | the shape; `/return-schema` is **required** (`seon.fn.arity.edn:34-35`) | **3** — and note that **nothing retracts shape rows today, so they leak**: 2,781 schemas carry one, 3,015 arities reference them, no reclamation path | New finding; §5.3 |
| `:seon.fn.arity/input-refs`, `/output-refs`, `/guard-refs` | `[:set :seon.db/ref]` to `:seon.schema/key` rows (`seon.fn.arity.edn:5`, `:15`, `:3`) | `src/seon/program.cljc:734-736` | **a keyword named in the contract form** — AGENTS §2.2's own illustration reads them as a keyword: `[?f :seon.fn.arity/input-refs :seon.db/connection]` | the keyword. Readers `src/seon/db.clj:2308-2310`, `:2473`; `src/seon/sci/eval.clj:1162-1163`, `:1215`; `src/seon/bootstrap_drive.clj:188`. Live: **146** arities name `:seon.db/connection` | **4 + 3** — `[:set {:seon.db/index true} :qualified-keyword]`. Deleting a schema key a live contract declares is a break, and the 146 arities are the work list | Extends G2 (review N4) |
| `:seon.fn.argument/binding` | component ref (`seon.fn.argument.edn:5`) | `src/seon/program.cljc:683` | containment | nothing | **1** unchanged | — |
| `:seon.fn.argument/schema`, `/rest-tail-schema`, `/rest-element-schema` | plain `:seon.db/ref` (`seon.fn.argument.edn:6-8`) | `src/seon/program.cljc:685`, `:686`, `:688` | ref to the shared shape row | `/schema` is **required** (`:26`); readers `src/seon/call_preparation.clj:179`, `:315`, `:557` | **3** | New finding |
| `:seon.fn.ast/child`, `/children`, `/guard`, `/input`, `/key`, `/keys`, `/output`, `/properties`, `/registry`, `/value`, `/values` | component refs (`seon.fn.ast.edn:2-8`, `:30-38`) | `src/seon/program.cljc:533` | containment | nothing | **1** unchanged | — |
| `:seon.fn.ast/ref` | plain `:seon.db/ref` (`seon.fn.ast.edn:29`) | `src/seon/program.cljc:576`, written as `[:seon.schema/key (:value ast)]` | **a schema key named inside a contract form** | the keyword | **4 + 3** — `:qualified-keyword` | Extends G2; not in N1–N11 |
| `:seon.fn.argument/label-symbol` | `:symbol` (`seon.fn.argument.edn:11`) | `src/seon/program.cljc:690` | a literal in source, denoting nothing | — | **4**, not in the refusal set | — |

### 3.3 `seon.test.edn` and `seon.test.failure.edn` — the test families

| Attribute | Declared today | Who writes it | What the fact MEANS | Reader need on deletion | L + form | Ruling |
|---|---|---|---|---|---|---|
| `:seon.test/sym` | `[:string {:seon.db/identity true}]` (`seon.test.edn:70-74`) | `src/seon/fn.clj:612` | identity | — | retract the entity; `:qualified-symbol` | Overturns ruling 47 |
| `:seon.test/reach` | `[:vector {:seon.db/cardinality :many} :seon.db/ref]` (`seon.test.edn:2`) | `seon.test.runner/record-tx` (`src/seon/test/runner.clj:2384-2386`), members as `[:seon.fn/sym s]` lookup refs (`:2003-2005`) | **evidence about a past run**: these names were in the closure the run exercised | the names, kept. Reader `seon.test/changed-since-green` (`src/seon/test.clj:62`, `:98-101`) | **4, reported but NOT refused** (§2.5) — `[:set {:seon.db/index true} :qualified-symbol]`. A deletion names the tests whose closure is now stale; those tests must re-run | Keeps G2. This is the attribute the whole tombstone apparatus exists for (`src/seon/cluster/source.clj:337-370`) |
| `:seon.test/reach-unknown` | `[:string {:min 1}]` (`seon.test.edn:4`) | `src/seon/test/runner.clj:2387-2390` | a string sentinel standing in for a fact | — | **delete** once G4's required analysis-provenance digest lands. Its own docstring confesses the defect: "absence of both membership and this marker is legacy unknown, never proof of an empty closure" | Implements G4 (review N15) |
| `:seon.test/subject` | `:seon.db/ref` (`seon.test.edn:10`) | `src/seon/fn.clj:641`, `:679`, from the Var's metadata | **a name the author wrote in metadata** | the symbol. Readers `src/seon/fn.clj:1297`, `:1312`, `:1365`; `src/seon/test.clj:248-251`; `src/seon/test/runner.clj:1986` | **4 + 3** — `:qualified-symbol`, **in the refusal set**: a test whose subject is deleted tests nothing, and that is refactoring work | Extends G2 (review N7) |
| `:seon.test/pending-subject` | `[:string]` (`seon.test.edn:11`) | `seon.turn/relation-assertions` | the resolution-failure half of `subject` | — | **delete** with `pending-calls` | Review N7 |
| `:seon.test/ns` | `:seon.db/ref` (`seon.test.edn:1`) | `src/seon/fn.clj:613`; runner tempid `src/seon/test/runner.clj:2400` | statement about a living namespace | the namespace. Readers `src/seon/test.clj:516`, `:594`, `:1059`; `src/seon/bootstrap.clj:244` | **3** | Keeps G2 |
| `:seon.test/run` | `:seon.db/ref` to `:seon.test.run/run` (`seon.test.edn:38`) | `src/seon/test/runner.clj:2378-2380` | statement about a living run record | the run must exist. `changed-since-green` decides greenness from the **presence of a `:seon.test/run` assertion in history** (`src/seon/test.clj:87`) — a sweep here silently un-greens every test | **3** — a run record is append-only evidence and may not be deleted while a result cites it | New finding |
| `:seon.test/failures` | `[:vector {:seon.db/component true} :seon.db/ref]` (`seon.test.edn:39`) | `src/seon/test/runner.clj:2392-2399` | containment — the failures ARE part of this result's value | nothing | **1**, with G5's caveat: the components are never selected by the whole-entity validator, so they must be validated as part of the parent pulled with components expanded | Implements G5 |
| `:seon.test/failing-assertions` | `[:vector {:cardinality :many} :seon.test.failure/id]` (`seon.test.edn:36-38`) | `src/seon/test/runner.clj:2378` | the same failures, as values, beside the component refs | — | **delete** the duplicate | Review N36/B.3; in the reset batch |
| `:seon.test/adoption-identities` | `[:set :seon.db/ref]` | the adoption writer | which program identities an adoption installed — **an observation about a past event** | the identities, unchanged by a later deletion | **4, reported not refused** — `[:set :seon.program/identity]` (the tuple type exists) | New finding |
| `:seon.test/adoption-cluster` | `:seon.db/ref` | the adoption writer | statement about a living cluster | the cluster | **3** | — |
| `:seon.test.failure/test` | `:seon.db/ref` (`seon.test.failure.edn:3`) | `src/seon/test/runner.clj:2206`; preserved as a lookup ref at `src/seon/cluster/source.clj:433` | a **back-pointer** from a component to its parent | nothing: a component's parent is never deleted without it | **delete the back-pointer** (`:seon.test/_failures` answers it), or document the redundancy | New finding |
| `:seon.test.failure/file` | `:seon.db/ref` (`seon.test.failure.edn:13`) | `src/seon/test/runner.clj:2178`, as `[:seon.fn.file/relative-path path]` | **a path the reporter printed**, stored twice — `/reported-file` (`:21`) is the same fact as a string | the path. Readers `src/seon/test.clj:32`; `src/seon/problems.clj:358`; `src/seon/test/runner.clj:2441` | **4, reported not refused** — keep `reported-file`, delete the ref. A failure is evidence about a past run and must survive its file | Extends G2 (review N11) |
| `:seon.test.failure/first-run`, `/last-run` | `:seon.db/ref` (`seon.test.failure.edn:17-18`), both **required** (`:44-45`) | `src/seon/test/runner.clj:2207-2208` | statement about living run records | the run | **3**, same as `:seon.test/run` | New finding |
| `:seon.test.failure/throwable` | `:symbol` (`seon.test.failure.edn:19`) | the runner | already behavior 4 | — | **4** — the in-family precedent for `/file` | — |

### 3.4 `seon.ns.edn`, `seon.schema.edn`, `seon.lint.edn`, `seon.fn.file.edn`, `seon.program.edn`, `seon.instrument.edn`, `seon.render.edn`

| Attribute | Declared today | Who writes it | What the fact MEANS | Reader need on deletion | L + form | Ruling |
|---|---|---|---|---|---|---|
| `:seon.ns/name` | `[:symbol {:seon.db/identity true}]` (`seon.ns.edn:8-12`) | `src/seon/fn.clj:303` | identity, already a symbol | — | retract the entity | Overturns ruling 47 |
| `:seon.ns/requires` | `[:set :seon.db/ref]` (`seon.ns.edn:31`) | `src/seon/fn.clj:303`, `:748` | **a libspec the ns form names** — a token in source text, exactly like `calls` | the namespace symbol. Readers: reload ordering `src/seon/cluster.clj:2203-2209`; `src/seon/fn.clj:2363`; `src/seon/turn.clj:995`, `:3643`. Live: **67** namespaces require `seon.turn` | **4 + 3** — `[:set {:seon.db/index true} :symbol]`. Its own family already stores `:seon.ns.alias/target-ns` and `:seon.ns.refer/target-ns` as symbols (`seon.ns.alias.edn:13-16`, `seon.ns.refer.edn:19-22`) | Extends G2; **not in N1–N11**, and it is the highest-fanout name-edge in the graph |
| `:seon.ns/aliases`, `/imports`, `/refers` | component sets (`seon.ns.edn:2`, `:6`, `:30`) | `src/seon/fn.clj:303` | containment | nothing | **1** unchanged | — |
| `:seon.ns/steward` | `[:and … :seon.db/ref]` (`seon.ns.edn:33-38`) | `seon.cluster.agent/steward-call` (`seon.ns.edn:26-29`) | **a statement about a living agent** | the agent. Readers `src/seon/error.clj:1284`, `:1678`, `:1730`; `src/seon/agent.clj:18-30`; `src/seon/problems.clj:278` reads **absence** as unowned, so a swept steward is indistinguishable from an unstewarded namespace | **3** — deleting an agent must reassign or explicitly retract stewardship | Keeps G2; the `problems.clj:278` absence read makes this blocker-class |
| `:seon.schema/key` | `[:keyword {:seon.db/identity true}]` (`seon.schema.edn:43-46`) | `src/seon/schema.clj:2928`, `:1333` | identity | — | retract the entity | Overturns ruling 47 |
| `:seon.schema/references` | `[:set :seon.db/ref]` (`seon.schema.edn:50-53`) | `src/seon/schema.clj:2980` | **registry keys named in a Malli form** | the keyword. Reader `src/seon/ai.clj:327-332` maps straight back to `:seon.schema/key`, which is the proof the ref buys nothing. Live: **32** schemas reference `:seon.db/connection` | **4 + 3** — `[:set :qualified-keyword]` | Extends G2 (review N3) |
| `:seon.schema/ns` | `[:and … :seon.db/ref]` (`seon.schema.edn:49`) | `src/seon/sci/reader.cljc:410`; `src/seon/program.cljc:964-965` | statement about a living namespace | the namespace. Reader `src/seon/render/ns.clj:893` | **3** | Keeps G2 |
| `:seon.schema/shape` | `:seon.db/ref` (`seon.schema.edn:48`) | `src/seon/program.cljc:838` | a ref to the shared content-addressed shape row | the shape. Readers `src/seon/call_preparation.clj:161`, `:201`; `src/seon/issue/detect.clj:20-23`. Live: **2,781** | **3**; reclamation in §5.3 | New finding |
| `:seon.schema/predicate`, `/identity-projection` | `:qualified-symbol` (`seon.schema.edn:41`, `:38`) | schema admission | already behavior 4, naming a function | **4 + 3** — deleting a predicate a live schema names is a break | in-family precedent for `/references` |
| `:seon.schema.shape/children`, `/entries` | component vectors (`seon.schema.shape.edn:9-12`) | `src/seon/fn/schema_shape.clj:299` | containment **inside a row that is itself shared by fingerprint** | nothing today, because nothing retracts a shape row; if one were, its cascade would destroy a subtree several parents point at | **1**, with the sharing recorded in the docstring | New finding |
| `:seon.fn.file/relative-path` | `[:string {:seon.db/identity true}]` (`seon.fn.file.edn:1-3`) | `seon.fn/artifact` (`src/seon/fn.clj:1217`) | identity: the file path | — | retract; the **rename** case is §4(e) | — |
| `:seon.lint/fn` | `[:and … :seon.db/ref]` (`seon.lint.edn:2`) | `src/seon/fn.clj:1201` | **the declaration whose span contains this finding** | today a sweep drops the link and the finding survives attributed to nothing. Reader `src/seon/render/ns.clj:845` uses the reverse `:seon.lint/_fn`. Live: **546** | **4** — `:qualified-symbol`, **reported not refused**: a finding is re-derived wholesale by the next analysis, so refusing a deletion over one is noise | New finding; not in N1–N11 |
| `:seon.lint/file` | `:seon.db/ref` (`seon.lint.edn:3`), **required** (`:16`) | `src/seon/fn.clj:1196` | the file the finding is in | a sweep removes a **required** key from a surviving finding. Live: 34 findings on `src/seon/turn.clj`, 4 on `src/seon/id.clj` | **3** — keep the ref and let the publication retract the findings in the same transaction; §5.4 prices the alternative | New finding |
| `:seon.program/identity` | `[:tuple :seon.program/identity-attribute :seon.schema/value]` | `seon.program/deletion-row` (`src/seon/program.cljc:1058`) | already behavior 4 | — | **4** — the shape `:seon.test/adoption-identities` should use | — |
| `:seon.program/written-by` | `:qualified-symbol` property | declared on entity-map entries | already behavior 4, naming a function with no ref | — | **4 + 3** — the precedent that a program-graph fact names a function by symbol | — |
| `:seon.instrument/fn` | `[:string {:min 1}]` | the instrumentation error constructors | a function name in a diagnostic | the name must survive the function — that is the point of a fault record | **4, reported not refused** — `:qualified-symbol`. Its sibling `:seon.error/fn` is a **ref** to the same fact on the same occurrence map (`seon.error.occurrence.edn:26`, `:36`) — delete the ref | Review N13; in the reset batch |
| `:seon.render/ai`, `/html`, `/form` | `:qualified-symbol` **Malli properties**, not datoms (`seon.fn.edn:114-115`, `seon.ns.edn:15-18`) | declared on the entity map | already behavior 4: a render pair names its function by symbol | the symbol; an unresolvable render function is a typed unknown at selection | **4 + 3** — **the cleanest existing precedent in the population**, and deleting a function a live render pair names is a break worth refusing | — |

---

## 4. The five deletion events, worked with real identities from `default`

All identities and counts from the two permitted read-only queries at HEAD
`6a2201f29` (population: 5,114 functions, 1,861 tests, 2,790 schema keys, 438
namespaces, 339 files).

### (a) A function is deleted from source — `seon.turn/open?`

**Today** the deletion path is `seon.turn/row-tx` (`src/seon/turn.clj:1304`),
which calls `program/exact-replacement-tx declaration {identity-attribute
identity-value}` (`:1336-1339`): every owned attribute is retracted and the
identity datom stays. The tombstone means the callers' lookup refs still
resolve, so **nothing refuses and nothing is reported** — the deleted function
answers a join exactly like a live one (the study's D2).

**Under the contract**, the attempted transaction is
`[:db/retractEntity [:seon.fn/sym 'seon.turn/open?]]`, and the writer refuses
it, naming:

| Break | Live count and identities | In the refusal? |
|---|---|---|
| callers through `:seon.fn/calls` | **5** — `seon.turn/recover-call`, `seon.turn/receipt-run`, `seon.turn/render-ai`, `seon.turn/require-open-run`, `seon.turn/open-run-tx-call` | **yes** — this is the refactoring assignment |
| callers through `:seon.fn/references` | **0** | yes, when non-empty |
| tests declaring it as `:seon.test/subject` | **0** | yes, when non-empty |
| tests whose recorded `:seon.test/reach` contains it | **54** | **no — reported** (§2.5). Their green basis is stale; they must re-run |
| tests selected through the 5 callers | **4** — `seon.turn-test/one-run-lifecycle-teaches-the-call-shapes`, `seon.turn-test/state-is-derived-from-primitives`, `seon.fn.analyzer-test/ordered-forms-use-existing-context-and-original-row-numbers`, `seon.fn.analyzer-test/reply-analysis-does-not-contaminate-build-analysis` | reported — they are the proof the fix works |

When the fix rides along — the publication also re-analyzes the 5 callers and
none of them names `seon.turn/open?` any more — the same transaction commits,
because the check reads `:db-after` and finds no caller. With ref edges the
same transaction is **indistinguishable from the unfixed one** (§2.3).

Retracting the row also **cascades** its `:seon.fn/arities` and `:seon.fn/ast`
(`transaction.cljc:831-834`), leaves its `:seon.fn/ns` and `:seon.fn/file`
untouched (the sweep is incoming-only), orphans the shared shape rows its
arities referenced (§5.3), and leaves the whole history intact.

### (b) A namespace file is deleted — `src/seon/turn.clj` / namespace `seon.turn`

Live: the file carries **176 declarations**; the namespace owns **176
functions**, **0 tests**, **0 schema keys**, is **required by 67 other
namespaces**, has **no steward**, and the file carries **34 lint findings** and
**0 failure rows**.

- The 176 functions' `:seon.fn/ns` refs (behavior 3) mean the namespace
  deletion **refuses** unless those 176 retractions are in the same
  transaction. Correct and loud.
- Each of those 176 retractions then faces its own caller check. **This is the
  event where the contract earns its cost**: the refusal is the complete
  cross-namespace break list for deleting `seon.turn`, which is exactly the
  work packet the owner described handing to a set of agents.
- The **67 requiring namespaces** keep their `:seon.ns/requires` symbol edges
  and appear in the refusal. Today those are **67 silently retracted datoms**
  and a reload order (`src/seon/cluster.clj:2203-2209`) that quietly changes
  shape.
- The 34 lint findings' `:seon.lint/file` refs must be retracted in the same
  transaction; the publication that removed the file is the writer that does
  it.
- `:seon.ns/aliases` / `/imports` / `/refers` cascade.

### (c) A test is deleted — `seon.turn-test/a-refused-generated-form-records-its-refusal`

Live: **1** `:seon.test/failures` component, whose
`:seon.test.failure/last-run` points at a run record.

`[:db/retractEntity [:seon.test/sym 'seon.turn-test/…]]` commits cleanly:

- the failure component **cascades** — correct, a failure is part of a result's
  value;
- the failure's `/first-run` and `/last-run` are **outgoing**, so the run
  records are untouched;
- `:seon.test/run`, `/ns`, `/subject` are outgoing;
- **nothing incoming exists**. No attribute in the population refs a test row
  except `:seon.test.failure/test`, the component's own back-pointer, which
  dies with it. **A test is the cleanest deletion in the graph**, and the
  refusal correctly does not fire.

The inverse is the dangerous one: deleting the **run** record.
`:seon.test/run` and `:seon.test.failure/first-run` / `/last-run` are required
refs into it, and `changed-since-green` decides greenness from the presence of
a `:seon.test/run` assertion in history (`src/seon/test.clj:87`). A sweep there
converts every test's green history into "no green result is retained". Hence
behavior 3 on all three, and run records are append-only in practice.

### (d) A schema key is deleted — `:seon.db/connection`

Live referrers: **4** functions declare they write it (`seon.issue/add!`,
`seon.issue/tests!`, `seon.issue/start!`, `seon.turn/close-turn`); **32**
schemas reference it; **146** arities name it in `:seon.fn.arity/input-refs`;
**177** functions mention the keyword in `:seon.fn/keywords`.

Today `seon.turn/row-tx` already does half the right thing for schema keys
(`src/seon/turn.clj:1304-1326`): it builds the candidate projection without the
key and calls `assert-schema-data-unused!` — **behavior 3 applied to data**,
refusing when the attribute still has datoms. It then falls through to the
tombstone, so the 182 declaration-level referrers are silently retracted.

Under the contract the deletion is refused and the refusal names **4 + 32 + 146
= 182 breaks** grouped by attribute: the writers that would write a
nonexistent attribute, the schemas whose forms would not compile, and the
contracts that declare an input nobody can validate. The **177**
`:seon.fn/keywords` mentions are **not** breaks — that attribute is declared
honest membership only, never a claim.

`:seon.schema/shape` is outgoing, so the shared shape row is orphaned, not
destroyed (§5.3). `assert-schema-data-unused!` stays exactly as it is.

### (e) A file is renamed — `src/seon/id.clj` → some other path

Live: **6** declarations (`seon.id/digest`, `seon.id/evaluation`, `seon.id/id`,
`seon.id/sha-256`, `seon.id/symbol-in`, `seon.id/valid?`), **4** lint findings,
**0** failure rows.

`:seon.fn.file/relative-path` is the identity, so a rename is **a new entity,
not an update** — `upsert-eid` cannot follow it
(`transaction.cljc:641-712`).

- Today the old file row is tombstoned by `exact-replacement-tx`, so the 6
  `:seon.fn/file` refs survive **pointing at a row with no digest** — a husk,
  and under G4 a husk that would defeat the required analysis-provenance fact.
- Under behavior 3 the same transaction must re-point all 6 and retract the 4
  lint findings. **A rename becomes one atomic, loud operation** instead of ten
  quiet ones, which is what the publication is already doing anyway.
- The machinery that exists only because refs cannot survive a rebuild —
  `result-preservation-tx` stripping and re-resolving
  `:seon.test.failure/file` by lookup across a publication
  (`src/seon/cluster/source.clj:471-477`) — deletes once that attribute becomes
  the `reported-file` string.

**The rename is the event that most clearly separates the two kinds of fact.**
Every attribute that needed special preservation code to survive it is a
name-observation wearing a ref; every attribute that legitimately had to be
re-pointed is a real relation.

---

## 5. What genuinely depends on a policy choice

Everything above except the following is decided by what the writer observed.
These are real decisions. Two priced options each, in prose, recommendation
marked.

### 5.1 How far the refusal extends, and what an agent does with it

The owner's direction settles *that* deletion is refused. It does not settle
the scope or the escape.

*The first option is the complete rule, with no escape.* Every declared
connection — refs and the symbol edges in the refusal set — is checked on every
transaction that retracts an identity, and there is no override: the only way
to delete is to fix first or fix in the same transaction. The guarantee is
total and the refusal is always the work list. The cost is that a lane which
genuinely wants to delete a function with 40 callers must produce all 41 edits
as one transaction, and the edit hook's incremental publication (§2.6b) cannot
do that across files it was not asked to analyze — so the lane runs a complete
publication or stages the deletion behind the refactor. That is real friction,
and it lands on the slowest path we have.

*The second option is the same rule with a declared, recorded override.* A
deletion may carry an explicit acknowledgement that names the breaks it is
accepting; the writer admits it and **records the accepted break list as
facts**, so "which edges currently name a function with no row" stays a query
and the issue family can pick it up. The guarantee weakens from "never broken"
to "never broken silently", which is the actual failure class the project
names. The cost is a second path, which §2.5 of AGENTS.md rules against, and
the certainty that the override becomes the default the first time someone is
in a hurry.

**Recommendation: the first option, with the refusal's payload written as
issue work.** The owner asked for agents to be stopped, not warned, and the
override's failure mode is exactly the one that produced this whole document.
The friction is real but bounded: the refusal names every caller, so the
refactor is mechanical, and §2.6c means a reset never hits it.

### 5.2 Whether `:seon.fn/capability-fn` keeps its ref

G2 says refs stay at `/capability-fn`; §3.1 recommends deleting it. This is a
direct disagreement with a ruling and the owner should settle it.

*Keep the ref.* `seon.effect` resolves the handler in one pull
(`src/seon/effect.clj:252-254`), and the indexer already refuses a capability
marker whose handler has no row, so it cannot dangle. The cost is a second
encoding of a fact the row already carries as `:seon.effect/capability`, and
under §2.1 it is the one connection in the function family whose break the
refusal would have to reconstruct from `:db-before`.

*Delete the ref and put the symbol in the refusal set.* One fact, one
encoding; deleting a capability handler that a live declaration names is
refused by the same rule as any other caller, and the refusal can say which
capability. The cost is one extra lookup on the effect path.

**Recommendation: delete the ref.** The live population is **two** pairs, and
the attribute's own docstring already calls the symbol the primary fact.

### 5.3 Shared content-addressed shape rows — reclamation

`seon.fn.schema-shape/shape-row` produces rows deduplicated by fingerprint
(`src/seon/fn/schema_shape.clj:299-300`), referenced from `:seon.schema/shape`
(2,781 live), `:seon.fn.arity/return-schema`, `/guard-schema`,
`:seon.fn.argument/schema` and its rest variants (3,015 arities). **Nothing
retracts them.**

*Leave them.* Zero work, zero risk; the store is disposable by ruling and a
reset reclaims everything. The cost is unbounded growth between resets in a
family nobody can count without walking every referrer.

*Reclaim them.* A maintenance task retracts shape rows with no incoming ref.
Under §5.1's complete rule this is safe by construction — a concurrent
publication that adds a referrer to a shape being reclaimed is refused — which
is a genuine simplification the refusal buys. The cost is one more maintenance
owner.

**Recommendation: leave them and record the count at each reset.** If it grows
faster than the program does, reclaim it then; the [TARGET] root maintenance
portfolio is where it belongs. Inventing an owner for an unmeasured leak is
the addition this project prefers to dissolve.

### 5.4 Whether `:seon.lint/file` is a ref or a path string

*Keep the ref.* Findings join to the file entity, so "every finding under this
source root" is one clause, the shape `src/seon/issue/detect.clj:128` already
uses. Under behavior 3 a file deletion must retract its findings in the same
transaction — one line in the publication, since findings are rebuilt wholesale
anyway.

*Store the path.* The finding records where it was reported, like
`:seon.test.failure/reported-file`, and survives any file event. The cost is
that the root-relative grouping query becomes a string prefix over paths, which
is close enough to a regex to want the owner's eye.

**Recommendation: keep the ref.** The coordinated-deletion cost is trivial for
a re-derived family, and the grouping query stays a join instead of string
arithmetic.

### 5.5 What an unresolvable `:seon.test/reach` member means

Reach is excluded from the refusal (§2.5), so `changed-since-green` must decide.

*Treat it as a changed dependency.* The test is selected and re-runs. Correct
in the only direction that matters, and cheap; a large deletion selects
broadly.

*Treat it as a typed unknown that fails the verdict.* The test reports that its
closure cannot be evaluated. Louder, but an ordinary deletion turns a green
test red until it re-runs, which is a worse signal than a re-run.

**Recommendation: the first.** Nothing failed to arrive — a dependency went
away — so AGENTS §2.3's "a bound firing is a bug report" does not apply.

### 5.6 Not policy choices, stated so they are not mistaken for one

- **`:seon.fn.arity/input` and `/output` pointing into the sibling AST
  component tree** (§3.2) is a defect either way: a required key removable by a
  sibling attribute's retraction.
- **`:seon.test.failure/test`** is a redundant back-pointer;
  `:seon.test/_failures` answers it.
- **`:seon.ns/requires`** as a symbol set is not a choice: the same family
  already stores alias and refer targets as symbols.
- **G4's required analysis-provenance fact** is what makes every "no edges"
  read legitimate. Without it, a value edge set turns "A calls nothing" and "A
  was never analyzed" into the same zero datoms
  (`transaction.cljc:739-770`), and the refusal inherits the defect it exists to
  remove: a never-analyzed caller looks fixed.
- **Behavior 2 has no admissible use** anywhere in this scope. Wherever it
  appears today it is either a break nobody is told about or a fact that should
  have been a value.

---

## 6. Verification boundary

- Source read at `steward-platform` HEAD `6a2201f29`. Every `file:line` cited
  is a line I opened in this session.
- **`src/seon/test/runner.clj`, `src/seon/test/selection.clj`,
  `src/seon/schema/edn.clj`, `src/seon/schema/datahike.clj` and several schema
  resources carry another lane's uncommitted edits.** Lines cited in those
  files are working-tree lines, not HEAD lines; the `runner.clj` citations
  (`:1991`, `:2003-2005`, `:2152-2154`, `:2178`, `:2206-2208`, `:2378-2400`,
  `:2441`) must be re-checked at the reset's HEAD before use in a spec.
- Two read-only `mcp__seon__eval_clj` calls against `default` (mode `jvm`,
  custody `(seon.operator/connection "default")`), both pure `seon.db/q`
  bundles. Nothing was transacted. Every count in §4 comes from those two calls.
- No gate was run; no test JVM was launched; no `bin/seon` state changed.
- **Not established, and each one is a probe before the contract lands:**
  (1) the cost of §2.1's check — one AVET lookup per (deleted identity ×
  edge attribute) is argued from the index structure, not measured, and the
  reverse-walk timings in the deletion study's E4 (24.9 ms ref vs 84.0 ms naive
  symbol for a full 5,000-node reach) are the closest evidence;
  (2) whether `validate-report`'s refusal path surfaces cleanly through
  `seon.db`'s flat-error extraction for a **deletion** refusal specifically —
  `src/seon/db.clj:3193-3205` is cited by the design review, which I did not
  re-run;
  (3) how often the edit hook's incremental publication would refuse in
  practice (§2.6b) — the friction in §5.1 is argued, not measured, and one
  day's hook logs would settle it;
  (4) whether any production reader of `seon.fn.ast` survives, which review
  C.2 makes a precondition for that family's deletion.
