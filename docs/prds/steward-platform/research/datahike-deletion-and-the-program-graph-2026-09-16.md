---
type: research
status: active
tags: [datahike, program-graph, schema, deletion]
---

# Datahike deletion and the program graph — what a deleted function costs

Date 2026-09-16. Branch `steward-platform`, HEAD `bd76a97af`. Source read at
that commit; all measurements from two throwaway in-memory Datahike databases
created and deleted inside the `default` JVM (mode `jvm`). Nothing was
transacted against the cluster's connection or store.

Owner's question, verbatim: *"We are not doing retractions correctly. Go through
the Datahike source and play around in the REPL and find a solution where we can
delete a function and not lose the whole graph for everything else. That sounds
like we fucked up the schema."*

Second owner question, verbatim: *"Datahike drops an empty set at write time, so
after the fact 'the analyzer ran and found zero calls' and 'the analyzer never
ran' look identical in the database. The whole absence of something isn't the
same as it being deleted seems like a logic failure on our part rather than a
database failure. Reframe the logic and what we are storing based on what we
want to retrieve later."* — §8.

---

## 1. Defects first

| # | Defect | Evidence | Severity |
|---|---|---|---|
| **D1** | `[:db/retractEntity B]` silently retracts every caller's `:seon.fn/calls` datom pointing at B. Test selection derives from that edge set (`src/seon/fn.clj:1328-1373`), so callers quietly stop selecting tests. This is the project's named failure class — absence read as health. | `reference-code/datahike/src/datahike/db/transaction.cljc:998-1014`; Experiment 1 (`[9 fn/calls 6]` retracted with B) | blocker |
| **D2** | The tombstone that avoids D1 is **indistinguishable from a live function**. A join `[?a :fn/calls ?c] [?c :fn/sym ?s]` returns the deleted name exactly as it returns a live one. "Deleted" is inferred from the absence of `:seon.fn/source`. | Experiment 2 `:join-through-dead-eid` returns `x/d` after `x/d`'s definition was retracted | blocker |
| **D3** | Three code paths mint three different retired shapes, and a second validator (`seon.db/write-tombstone-validator`, `src/seon/db.clj:2913-2940`) exists solely to admit a shape the schema declares invalid. | `evaluation-write-path-and-retired-identities-2026-09-16.md` §3.2, §3.4; `src/seon/cluster/source.clj:305-337`, `src/seon/program.cljc:1007-1049` | blocker |
| **D4** | A ref to an eid with **no datoms** is admitted at write time (`validate-val` checks the value's type only), survives in the index, is returned by a wildcard pull as `{:db/id n}`, and is **silently dropped** by both a sub-selector pull and every Datalog join. A dangling edge is invisible, not loud. | `db/transaction.cljc:786-790`; Experiment 2: `a-pull-star` shows `{:db/id 11}`, `a-pull-subselector` and `join-through-dead-eid` omit it entirely | blocker |
| **D5** | Re-defining a name whose identity datom was retracted mints a **new eid**; callers' edges keep pointing at the dead one. `upsert-eid` resolves through the AVET datom, which no longer exists. | `db/transaction.cljc:641-712` (`:659`); Experiment 2: `x/e` redefined as eid 12, `A` still calls 11 | blocker |
| **D6** | `:seon.fn/calls [:set :seon.db/ref]` (`resources/seon/schemas/seon.fn.edn:23`) and `:seon.test/reach [:vector {:seon.db/cardinality :many} :seon.db/ref]` (`resources/seon/schemas/seon.test.edn:2`) declare **a relation between two entities** where the program actually has **a name mentioned in source text**. That declaration is what forces D1→D5 and the whole population invariant. | §7 | blocker |
| **D7** | An empty cardinality-many value stores **zero datoms**, so "analyzed, calls nothing" and "never analyzed" are byte-identical rows. | `db/transaction.cljc:718-737`, `:739-770`; Experiment 3: `zero-datoms` = `never-datoms` | blocker |
| **D8** | 15+ component map schemas are never selected by the whole-entity write validator, because selection keys on a unique identity attribute the component has none of. | `docs/prds/steward-platform/research/schema-key-audit-2026-09-16.md:17`, `:89-95`; `src/seon/db.clj:2942-2985` | friction |

---

## 2. What Datahike actually does — read from the source

| Operation | Behaviour | file:line |
|---|---|---|
| `[:db/retractEntity e]` / `[:db.fn/retractEntity e]` | retracts the entity's own datoms AND **every incoming ref datom** — it scans `(dbi/search db [nil a e])` for every attribute in `(dbi/-attrs-by db :db.type/ref)` — then cascades `retractEntity` into component values | `db/transaction.cljc:998-1014`, `:831-836`, dispatch `:1080-1082` |
| `[:db.fn/retractAttribute e a]` | retracts only that entity's datoms for `a`, cascading components of `a`. **No incoming refs touched.** | `:1073-1078` |
| `[:db/retract e a v]` / `[:db/retract e a]` | retracts matching datoms only. No cascade, no incoming refs. | `:1060-1071` |
| retraction and history | every retraction is `(datom e a v tx false)` — the fact stays in the temporal index | `:813-819`; `doc/time_variance.md:303` "Normal retractions preserve data in history" |
| `:db/purge`, `:db.purge/entity`, `:db.history.purge/before` | the only true deletion; requires `keep-history?`. Datahike's own advice: "Use retractions for normal data lifecycle — reserve purging for compliance" | `:1084-1117`; `doc/time_variance.md:360` |
| `upsert-eid` | resolves a unique-identity attribute by `(:e (first (dbi/datoms db :avet [a v])))`. Once the identity datom is retracted, the AVET entry is gone and a re-assert mints a fresh eid. | `:641-712`, `:659` |
| `validate-val` on a ref | checks the value's **type** only; it never checks that the target has datoms | `:786-790` (`transact-add`), `:33-52` |
| `explode` / `maybe-wrap-multival` | a cardinality-many value is expanded to one `[:db/add e a v]` per member. **An empty collection expands to nothing.** There is no empty-set datom. | `:739-770`, `:718-737` |
| pull of a ref, no sub-selector | `{:db/id n}` (plus `:db/ident` if the target has one) regardless of whether the target has datoms | `pull_api.cljc:351-357`, `:299-302` |
| pull of a ref, component, no sub-selector | the full nested entity map, auto-expanded | `pull_api.cljc:345-349` |
| `as-of` / `since` | `as-of` includes the time point, `since` excludes it; both filter the temporal index by `:db/txInstant` | `db.cljc:143-152` |

**Nothing in Datahike requires a ref target to exist.** Our "identity rows never
retract" rule (AGENTS.md §2 / ruling 47) is a **choice we made to work around
D1**, not a dependency requirement. Datomic-style modelling agrees with
Datahike here: a ref is an eid, the eid persists, and an entity with no datoms
is legal — what Datomic does *not* do is make that state queryable for you.

---

## 3. Experiments

Two mem databases, `keep-history? true`, `schema-flexibility :write`, schema
mirroring ours: `:fn/sym` (`:db.type/symbol`, `:db.unique/identity`),
`:fn/source` (string), `:fn/calls` (ref, many, indexed), `:fn/call-syms`
(symbol, many, indexed), `:fn/ast` (ref, one, `:db/isComponent`). Both deleted
at the end; `(d/database-exists? …)` returned `false` for both, and `user` was
left with no vars.

### E1 — build the graph

```clojure
(d/transact conn [{:db/id -1 :fn/sym 'x/b :fn/source "(defn b [])" :fn/ast -9}
                  {:db/id -9 :fn/source "ast-of-b"}
                  {:db/id -2 :fn/sym 'x/c :fn/source "(defn c [])"}
                  {:db/id -3 :fn/sym 'x/a :fn/source "(defn a [])"
                   :fn/calls [-1 -2] :fn/call-syms ['x/b 'x/c]}])
```

Result: `x/b` = eid 6 (component ast = 7), `x/c` = 8, `x/a` = 9 with
`[9 :fn/calls 6]`, `[9 :fn/calls 8]`, `[9 :fn/call-syms x/b]`,
`[9 :fn/call-syms x/c]`.

### E2 — `[:db/retractEntity [:fn/sym 'x/b]]` while A calls B

```clojure
(d/transact conn [[:db/retractEntity [:fn/sym 'x/b]]])
```

`:tx-data` (retractions):

```clojure
[6 :fn/ast    7              536870915 false]
[6 :fn/source "(defn b [])"  536870915 false]
[6 :fn/sym    x/b            536870915 false]
[9 :fn/calls  6              536870915 false]   ;; <- A's edge, silently
[7 :fn/source "ast-of-b"     536870915 false]   ;; <- component cascade
```

After:

```clojure
(d/pull after '[*] [:fn/sym 'x/a])
;; {:db/id 9 :fn/sym x/a :fn/source "(defn a [])"
;;  :fn/calls [{:db/id 8}] :fn/call-syms [x/b x/c]}
```

Temporal answers, all intact:

```clojure
(d/q '[:find ?call ?tx ?added
       :where [?a :fn/sym x/a] [?a :fn/calls ?call ?tx ?added]] (d/history after))
;; ([6 536870914 true] [6 536870915 false] [8 536870914 true])

(d/pull (d/as-of after t0) '[*] [:fn/sym 'x/a])
;; :fn/calls [{:db/id 6} {:db/id 8}]   — "what did A call at t-1" answers correctly

(d/q '[:find ?e ?a ?v ?added :where [?e ?a ?v _ ?added]] (d/since (d/history after) t0))
;; ([6 :fn/ast 7 false] [6 :fn/source … false] [6 :fn/sym x/b false]
;;  [7 :fn/source … false] [9 :fn/calls 6 false] [tx :db/txInstant … true])
```

**Answer to Q1.** Nothing but the edges into B is lost. A keeps `:fn/source`,
`:fn/sym`, its other call edge to `x/c`, and — decisively — its
`:fn/call-syms x/b`, because that is a value, not a ref. B's own datoms and its
component are retracted, and its whole history survives. `history`/`as-of`
answer "what did A call at t-1" exactly; `since` over `history` gives the
precise list of caller edges that vanished at `t`, one datom each. **"The whole
graph for everything else" is not lost.** What is lost is the *current-value*
statement "A calls B", with no record in the present database that A's source
still mentions `x/b`. A query at the current basis cannot tell "A never called
B" from "B was deleted"; only a temporal query can, and no consumer runs one.

### E3 — tombstone vs. retracting the identity, and re-definition

```clojure
;; d: retract only the definition attribute, keep the identity datom
(d/transact conn [[:db.fn/retractAttribute eid-d :fn/source]])
;; e: retract EVERY datom including the identity, while A still refs it
(d/transact conn [[:db/retract eid-e :fn/source "(defn e [])"]
                  [:db/retract eid-e :fn/sym 'x/e]])
```

| Question | Result |
|---|---|
| tombstoned `x/d` row | `{:db/id 10 :fn/sym x/d}` — the current tombstone |
| lookup ref after tombstoning | `(d/pull mid '[*] [:fn/sym 'x/d])` → `{:db/id 10 :fn/sym x/d}` — still resolves |
| A's edge to `x/d` | intact |
| join `[?a :fn/calls ?c] [?c :fn/sym ?s]` | `(["x/d"] ["x/c"])` — **the deleted function is returned like a live one (D2)** |
| `x/e` after retracting its identity | `(d/datoms mid :eavt 11)` → `[]`, eid 11 has no datoms |
| A's `:fn/calls` datom to 11 | **survives** — retracting datoms one at a time touches no incoming ref |
| wildcard pull of A | `:fn/calls [{:db/id 8} {:db/id 10} {:db/id 11}]` — `{:db/id 11}` with nothing behind it |
| sub-selector pull `{:fn/calls [:db/id :fn/sym :fn/source]}` | `[{:db/id 8 …} {:db/id 10 …}]` — **11 silently omitted (D4)** |
| Datalog join | same silent omission |
| re-define `x/e` | `(d/transact conn [{:fn/sym 'x/e :fn/source "(defn e [] :again)"}])` → `:tx-data` `[[12 :fn/sym x/e true] [12 :fn/source … true]]` — **new eid 12** |
| A after re-definition | still calls `[8 10 11]`; `x/e` is 12. **A's edge points at a dead eid forever (D5)** |

**Answer to Q2.** Datahike allows a dangling eid without complaint; the ref is
admitted, stored and returned by a wildcard pull, and is invisible to every
join and sub-selector pull. Re-assertion mints a new eid, so the old edge is
permanently wrong. That is strictly worse than either alternative: it is a
silent lie rather than a loud failure. The current tombstone avoids the lie but
buys D2 — the deleted name answers every query as a live function.

### E4 — scale: 5,000 functions, 29,986 edges, stored both ways

Synthetic graph (seed 42, 6 random callees each), every edge stored **twice**:
as `:fn/calls` (ref) and as `:fn/call-syms` (symbol value).

```clojure
{:entities 5000 :ref-edges 29986 :sym-edges 29986}
```

Reverse-reach frontier walk, reproducing `seon.fn/gate-set-in`'s shape
(`src/seon/fn.clj:1338-1352`), 50 targets, warmed, whole graph reachable
(4,998 callers per target):

```clojure
;; ref form — exactly gate-set-in
(d/datoms db :avet :fn/calls e)                     ; incoming callers, by eid

;; symbol form — AVET on the symbol, then one eid->sym lookup per edge
(into [] (comp (map :e) (keep #(:v (first (d/datoms db :eavt % :fn/sym)))))
      (d/datoms db :avet :fn/call-syms s))
```

| Walk | ms / target (full 5,000-node reach) | vs. ref |
|---|---|---|
| ref AVET (`gate-set-in` shape) | **24.9** | 1.0× |
| symbol AVET + `:eavt` eid→sym per edge | **84.0** | 3.4× |
| symbol AVET + `d/pull` per edge (naive) | 152 | 6.1× |

The 3.4× is entirely the eid→sym lookup per traversed edge. `gate-sets`
(`src/seon/fn.clj:1375-1404`) already acquires its relations **once per
operation** and hands them to each walk; one AEVT scan of `:fn/sym` (5,000
datoms) builds the eid→sym map once and removes the difference for a whole
gate selection. Both numbers are tens of milliseconds for the complete reach of
the entire program graph.

### E5 — deletion cost both ways

```clojure
;; ref model
(d/transact conn2 [[:db/retractEntity [:fn/sym 'f/fn1234]]])
;; symbol model: retract only the function's own facts
(d/transact conn2 [[:db.fn/retractAttribute [:fn/sym 'f/fn2345] :fn/source]
                   [:db.fn/retractAttribute [:fn/sym 'f/fn2345] :fn/calls]
                   [:db.fn/retractAttribute [:fn/sym 'f/fn2345] :fn/call-syms]
                   [:db/retract [:fn/sym 'f/fn2345] :fn/sym 'f/fn2345]])
```

| | `retractEntity` | symbol model |
|---|---|---|
| wall time | 1.62 ms | 1.14 ms |
| datoms retracted | 21 | 15 |
| the function's own datoms | 14 | 14 |
| **other entities' datoms destroyed** | **6 caller `:fn/calls` edges** | **0** |
| callers' symbol edges to it after | 6, intact | 1, intact |
| "A calls a name with no row" derivable after | no | **yes** — `(d/q '[:find ?caller ?s :where [?caller :fn/call-syms ?s] (not-join [?s] [_ :fn/sym ?s])] post)` returned **7** rows: the 6 callers of `f/fn1234` and the 1 caller of `f/fn2345` |

### E6 — storage size

| | value | 29,986 edge values (`pr-str` bytes) |
|---|---|---|
| eid (`:fn/calls`) | `4711` = 4 bytes | 143,294 |
| symbol (`:fn/call-syms`) | `f/fn4711` = 8 bytes | 263,121 |

**1.84×, i.e. +120 KB per 30,000 edges** at this name length. Real Seon names
are longer (`seon.cluster.message/send!` ≈ 26 chars), so expect ≈ 4–6× the
value bytes for the edge attribute, on an attribute that is a small fraction of
the program graph (the AST component trees dominate). This is the honest cost
of Option B and is not a reason to reject it.

### E7 — the empty set

```clojure
(d/transact conn [{:fn/sym 'x/zero  :fn/source "(defn zero [])" :fn/calls #{} :fn/call-syms #{}}
                  {:fn/sym 'x/never :fn/source "(defn never [])"}])

(d/datoms small :eavt eid-zero)  ;; [[fn/source "(defn zero [])"] [fn/sym x/zero]]
(d/datoms small :eavt eid-never) ;; [[fn/source "(defn never [])"] [fn/sym x/never]]
```

Identical. `explode` (`db/transaction.cljc:739-770`) iterates
`(maybe-wrap-multival db a-ident vs)` and an empty collection yields no
`[:db/add …]`. There is no empty-set datom in Datahike, by construction.

### Cleanup

```clojure
(d/release conn) (d/release conn2) (d/delete-database cfg) (d/delete-database cfg2)
;; {:exists {:probe1 false :probe2 false}} ; user vars unmapped, ns-publics = ()
```

---

## 4. Q3 — call edges and reach as VALUES

Declare `:seon.fn/calls [:set :qualified-symbol]` and `:seon.test/reach
[:set :qualified-symbol]` instead of ref sets.

| Property | ref today | symbol value |
|---|---|---|
| deleting B | destroys 6 caller datoms (E5) | touches no caller datom |
| "A calls a name with no row" | unrepresentable (population invariant forbids it) | one `not-join` clause, **measured** (E5: 7 rows) |
| reverse reach | 24.9 ms / target | 84.0 ms naive, ≈ ref with one eid→sym map per operation |
| forward "what does A call" | pull expands to `{:db/id n}`, then a join | the symbols, directly, no join |
| dangling edge | silent (D4) | impossible — a symbol always denotes itself |
| edge bytes | 143 KB / 30k | 263 KB / 30k |
| tombstones, minted identities, `write-tombstone-validator` | required | **dissolved** |

**The population invariant it dissolves.** AGENTS.md §2 / ruling 47 exists *for
the refs*: "every name the SCI context can resolve has a program row, minted
where the context learns it, so call edges cannot dangle by construction." With
symbol edges there is nothing to dangle, and an unresolved callee becomes a
symbol with no row — which is **the honest representation**: the analyzer saw
the name in the source text; whether a row exists is a separate, derivable
question. The analyzer already records exactly this shape one level up:
`:seon.fn/unresolved-references [:set … :seon.fn/sym]`
(`resources/seon/schemas/seon.fn.edn:43`) holds **symbols**, is consumed as
symbols by `gate-sets` (`src/seon/fn.clj:1385-1389`), and
`:seon.fn/pending-calls` (`:99`) and `:seon.fn/call-arities` (`:57`) already
carry callee identity **as a value, not a ref** — the `call-arities` docstring
says so explicitly, because Datahike stores tuple members verbatim. So the
schema already speaks both dialects; `:seon.fn/calls` is the odd one out.

---

## 5. Q4 — identity + definition component

`:seon.fn/sym` on the identity row, a `:seon.db/component` `:seon.fn/definition`
holding `source`, `ns`, `arities`, `spec`, `admission/source`. Retirement
retracts the definition; Datahike cascades it (`db/transaction.cljc:831-836`,
confirmed in E2 where `[7 :fn/source "ast-of-b"]` went with B).

It is **cleaner than the tombstone** — no entity is ever half a function, one
validator, no escape hatch — and strictly **worse than symbols** on the owner's
actual question, because the identity row is still the ref target, so D2 (a
retired identity answers a join like a live one) survives verbatim, and D5
survives for anything that retracts the identity. Its cost is real and paid by
every reader: `dir`, `doc`, test selection, the namespace pages and every pull
pattern that reads `:seon.fn/source` off the identity hops one ref, and
`seon.fn/reconcile-tx`'s exact-replacement machinery is rewritten. It buys
correctness for the *definition*, not for the *edges*.

---

## 6. Q5 — what Datahike intends

- Retraction is the normal lifecycle operation and is **history-preserving**;
  purge is the compliance escape hatch and needs `keep-history?`
  (`doc/time_variance.md:303`, `:360`). We have `:seon.config.db/keep-history?
  true` (`config/default.edn:4`), so every retraction we make is recoverable
  through `history`/`as-of`/`since` — E2 proves it end to end.
- `retractEntity`'s incoming-ref sweep is deliberate and is the **only**
  Datahike behaviour that motivates our rule. It is referential-integrity
  cleanup: Datahike's position is that a ref names an entity, so when the
  entity goes, statements about it go.
- Nothing requires a ref target to exist (`db/transaction.cljc:786-790`), and
  Datomic-style modelling says the same: a ref to a retracted entity is legal
  and its eid persists.

**"Program identity rows never retract" is a choice we made, not a Datahike
requirement** — and it is the right choice *given* a ref-typed edge. Change the
edge and the rule loses its reason to exist.

---

## 7. Did we fuck up the schema?

**Yes, and the declaration is namable.**

```clojure
;; resources/seon/schemas/seon.fn.edn:23
:calls [:set :seon.db/ref],
;; resources/seon/schemas/seon.test.edn:2
:reach [:vector {:seon.db/cardinality :many} :seon.db/ref],
```

A ref asserts **a relation between two entities**. What clj-kondo actually
reports, and what the indexer actually knows, is **a qualified name appearing in
a piece of source text**. Those are different facts. Declaring the weaker fact
as the stronger one forced every downstream contortion:

- the population invariant (a row must exist for every resolvable name), because
  a ref must have a target;
- minting fake identities at the writer when evidence names a function this
  database was never built with (`src/seon/cluster/source.clj:305-370`);
- tombstones, because `retractEntity` would take the callers' edges (D1);
- a second validator to admit a row the schema declares invalid
  (`src/seon/db.clj:2913-2940`);
- three disagreeing retirement writers (D3);
- and "deleted" being unqueryable (D2).

Every one of those dissolves when the edge stores what the analyzer saw.
`:seon.fn/call-arities`, `:seon.fn/pending-calls` and
`:seon.fn/unresolved-references` already store callee identity as a value, so
the change makes the schema **more** internally consistent, not less.

The ref is still right where a genuine entity relation exists:
`:seon.fn/ns`, `:seon.fn/file`, `:seon.fn/ast`, `:seon.fn/arities`,
`:seon.fn/capability-fn`.

---

## 8. The empty set, and what we actually want to retrieve

**Grounding.** A cardinality-many attribute with no members has no datoms:
`explode` emits one `[:db/add e a v]` per member of
`(maybe-wrap-multival db a-ident vs)` (`db/transaction.cljc:739-770`, `:718-737`),
so `#{}` emits nothing. E7 proves the two rows are byte-identical. The
schema-key audit found the same thing and proposed validating at submission
before Datahike erases the empty collection
(`schema-key-audit-2026-09-16.md:17`, citing `src/seon/fn.clj:584`,
`src/seon/db.clj:2895`, `:2942`). That proposal is a **pre-read the authority
re-decides**: `write-entity-error` reconstructs the entity from the resulting
EAVT datoms (`src/seon/db.clj:2895-2905`, `write-entity-value`), so the empty set is gone by the time
the one authority looks. A submission-time-only check is exactly the seam
AGENTS.md's owner law forbids, and it cannot be re-derived at recovery, at a
later read, or by an agent asking "was this analyzed?".

**The owner is right: this is our logic failure, not Datahike's.** Datahike is
consistent — it stores facts, and "the set is empty" is not a fact, it is the
absence of facts. The failure is that we asked a collection attribute to carry
**two** questions at once: *what does A call* and *did anyone ever look*.

**The reframed storage rule, in one paragraph.** Store one positive fact per
question you intend to ask, and never encode an event in the cardinality of a
collection. "What does A call" is the edge set, and its emptiness is the
ordinary absence of edges — legitimate, because it is read only under a row that
provably was analyzed. "Was A analyzed, and against what" is a **separate
positive fact written by the analysis onto the row**: the source it was derived
from. That fact is already half-built — `:seon.fn/file` (`seon.fn.edn:12`) refs
the file entity, which carries `:seon.fn.file/digest`, a 64-char digest of the
exact bytes walked (`resources/seon/schemas/seon.fn.file.edn:4`) — but it is
declared `{:optional true}` on `:seon.fn/fn` (`seon.fn.edn:121`) precisely
because agent-admitted definitions have no file. So make the *analysis
provenance* required and total instead: every `:seon.fn` row carries the
identity of what produced its definition facts — the file digest for an indexed
declaration, the evaluation for an agent-admitted one — and `analyzed?` is the
presence of that fact, `calls-nothing?` is `analyzed? AND no :seon.fn/calls
datom`, `never-analyzed?` is its absence. `:seon.schema.admission/source` is
**not** that fact: it is a two-value enum (`:core` / `:agent`,
`resources/seon/schemas/seon.schema.admission.edn:1`) naming who may write, not
what was read. The same rule kills the tombstone question in §7's terms:
"deleted" is a positive fact (`:seon.fn/retired-tx`, a transaction ref, exactly
like `:seon.turn/closed-tx`), never the absence of `:seon.fn/source`. One rule,
one sentence: **if a reader will ever need to distinguish "we looked and found
nothing" from "we never looked", the looking is an event and the event is a
datom.**

### 8.1 Component trees — the right validation unit

`:seon.fn/arities`, `:seon.fn/ast`, `:seon.fn.argument/row`,
`:seon.fn.binding/row` and 11 more are `:seon.db/component` children with no
unique identity, so `write-entity-error`'s selector — which keys on identity
attributes present in the resulting datoms (`src/seon/db.clj:2960-2973`) —
never selects them (D8, audit `:89-95`). Datahike answers the question for us:
a component **is part of its parent's value**. Its pull auto-expands components
even under a wildcard with no sub-selector (`pull_api.cljc:345-349`), and
`retractEntity` cascades into them (`db/transaction.cljc:831-836`, E2's
`[7 :fn/source "ast-of-b"]`). So the validation unit is **the parent entity
pulled with its components expanded, validated as one value** against the
parent's entity schema, whose component entries name the child schemas. That is
one seam, derives from the same declarations, needs no invented identities, and
matches how the data is read and destroyed. Inventing identity attributes to
satisfy the selector is the wrong fix — it would be a `:type` stamp in
disguise.

---

## 9. Options

Simplest first. All three keep history; all three can be applied with a reset —
**database data is disposable by ruling**, no migration.

### Option A — retract entities honestly, re-analyze known callers in the same publication

Retire a function with `[:db/retractEntity …]`. In the **same publication
transaction**, re-run analysis for every known caller so their `:seon.fn/calls`
sets are re-asserted (minus the deleted name) and the name lands in
`:seon.fn/unresolved-references` / `:seon.fn/pending-calls`. Make a ref to an
entity with no datoms **refuse at the writer** (`seon.db/write-ref-error`
already walks ref values, `src/seon/db.clj:2754`). Delete the tombstone
machinery, the minting (`src/seon/cluster/source.clj:305-370`) and
`write-tombstone-validator` (`src/seon/db.clj:2913-2940`).

- *Guarantee:* the current database never contains a ref to a nonexistent
  entity, and an edge that disappears disappears because analysis said so.
- *Cost:* the publication must know every caller of a deleted name **before**
  it retracts (a reverse AVET scan, ~1 ms at our scale, E5) and re-analyze them
  in the same transaction — real work, and it is exactly a pre-read the writer
  re-decides unless the decision is moved inside `[:db.fn/call …]`. Deletion in
  a REPL (`ns-unmap`) must take the same path as publication. Evidence written
  across a publication boundary (recorded `:seon.test/reach` members, failure
  rows) now refuses instead of minting — those writers need the typed unknown.
- *Gives up:* the population invariant is kept (refs still cannot dangle), so
  "A's source mentions a name with no row" is still only representable as the
  file-level `:seon.fn/unresolved-references`, not per-caller. Deleted-ness is
  still not queryable — a deleted function is simply gone.
- *Reset:* yes (retires every existing tombstone row).

### Option B — call edges and test reach as SYMBOLS, plus honest retraction ✅ **RECOMMENDED**

```clojure
:calls [:set :seon.fn/sym]      ; resources/seon/schemas/seon.fn.edn:23
:reach [:set :seon.test/sym]    ; resources/seon/schemas/seon.test.edn:2 (already a symbol type)
```

Retire a function with `[:db/retractEntity …]`: no caller datom moves (E5).
`gate-set-in` walks `(d/datoms db :avet :seon.fn/calls sym)` and maps eid→sym
once per operation in `gate-sets`, which already acquires relations once
(`src/seon/fn.clj:1375-1404`). "A calls a name with no row" is one `not-join`
(E5, 7 rows). Tombstones, identity minting and the second validator are
**deleted**. Pair it with §8's provenance fact so "analyzed, calls nothing" is
distinguishable.

- *Guarantee:* deleting a function touches only that function's datoms; no
  caller edge is ever silently lost (D1 dead), no dangling eid is possible (D4,
  D5 dead), re-definition reconnects by name automatically, unresolved calls
  become first-class queryable facts, and `history`/`as-of`/`since` continue to
  answer the temporal questions (E2).
- *Cost:* 3.4× on the naive reverse walk (84 ms vs 24.9 ms for the complete
  5,000-node reach), removable with one eid→sym map per operation; 1.84× edge
  value bytes at these name lengths (E6, +120 KB / 30k edges — more for real
  names); every consumer of `:seon.fn/calls` / `:seon.test/reach` that expects
  an eid changes to expect a symbol (`src/seon/fn.clj:1328-1404` and the reach
  writers), and pull patterns that expanded the ref stop doing so.
- *Gives up:* the database no longer *enforces* that a callee exists — an
  unresolved callee is admitted and must be **found by a query**, which is the
  honest representation but does move a guarantee from the writer to a check.
  That check must be written, and by §8's rule it must report the unresolved
  set positively, never silence.
- *Reset:* yes (attribute type change, no migration by ruling).

### Option C — identity row + definition component

As §5. Identity keeps `:seon.fn/sym` and the incoming refs; a
`:seon.db/component` definition child holds the definition facts; retirement
retracts the child and Datahike cascades.

- *Guarantee:* no entity is ever half a function; one validator; the tombstone
  shape is impossible rather than merely wrong.
- *Cost:* the largest. Every reader of `:seon.fn/source` / `/ns` / `/spec` hops
  a ref (`dir`, `doc`, test selection, the namespace pages, every pull pattern);
  `exact-replacement-tx` (`src/seon/program.cljc:1007-1049`) is rewritten.
- *Gives up:* the flat one-pull `:seon.fn` row — and it still does **not** solve
  the owner's question: a retired identity still answers a join like a live
  function (D2), and its edges still break if the identity is ever retracted
  (D5).
- *Reset:* yes.

**Recommendation: B.** It is the only option that makes the ruled invariant
unnecessary instead of defending it, it deletes three mechanisms (tombstones,
identity minting, the second validator) rather than adding one, and its
measured costs are tens of milliseconds and ~120 KB. A and C both keep the
ref-typed edge and therefore keep either the pre-read or the tombstone. B plus
§8's provenance fact plus §8.1's component validation unit is one coherent
publication-side change.

If B lands, AGENTS.md §2's two ruled corollaries (PROGRAM IDENTITY ROWS NEVER
RETRACT, THE POPULATION INVARIANT) must be rewritten in the same commit: both
are statements about a ref-typed edge that would no longer exist.

---

## Verification boundary

- Source read at `steward-platform` HEAD `bd76a97af`. `src/seon/repl.clj`,
  `src/seon/render/transcript.clj`, `src/seon/test/runner.clj` and two schema
  resources carry another lane's uncommitted edits; none of the lines cited
  here are in them.
- Eight evaluations against `default` in mode `jvm`, all against two throwaway
  `:memory` Datahike databases created inside that JVM. Nothing was transacted
  against the cluster's connection or store. Both databases were deleted and
  every `user` var unmapped; `(d/database-exists? …)` returned `false` for both.
- Timings are single wall-clock runs after one warm-up pass, in a JVM that is
  also running the development cluster — treat the **ratios** as the finding,
  not the absolute milliseconds.
- No gate was run. No production file was edited.
- Not established: how much of the 3.4× walk cost survives a per-operation
  eid→sym map (argued from `gate-sets`' existing shape, not measured), and the
  real-name storage multiplier (extrapolated from an 8-byte synthetic name).
