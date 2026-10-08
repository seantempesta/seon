---
type: research
status: findings
created: 2026-10-08
lane: research-vectors
basis: Seon 756b73410; Datahike fork gitlink e5453d967b74 (reference-code/datahike); live default, basis-t 536871976
---

# Vectors in Datahike: why there is no vector value, and what Seon should use

**Answer.** Yes. A vector design landed and is installed. Seon stores every `[:vector T]` attribute as **cells**:
identity-less component entities, each holding a position and one element, plus one presence fact per parent
(`resources/seon/schemas/seon.db.vector.edn`). This is Datomic's documented "positional list", with a codec so
agents put in a vector and get the same vector back. Datahike has no vector value for good reasons, and forcing
one would break several of its guarantees. Keep cells. Convert the remaining hand-written ordinals onto cells. Do
not add a fork value type for ordered data now.

## 1. What is installed today

- **Bridge.** `compiled-storage` keeps `[:vector T]` cardinality-many and marks it `::vector?`
  (`src/seon/schema/datahike.clj:114-119`). `vector-attribute-in?` (`:426`) switches the cell codec on. Design:
  `vector-storage-design-2026-09-27.md` rev 2, reviewed (V1–V6), landed (`18ce1b1a4`, `71af4f638`, review `79c10321d`).
- **Live (probe, 29 ms).** There are 28 vector attributes and 12,514 cells, each with one position datom. There are
  6,681 presence datoms, out of 575,816 current datoms in total. Each cell adds 2 datoms (link and position) on top
  of the element datom it carries, and each present vector adds 1 more. That is about 31.7k datoms, **5.5% of the
  store**. Most of it is `:seon.fn/arities` (3,135), `:seon.fn.arity/arguments` (6,057) and
  `:seon.cluster.eval/read-evidence` (1,812).
- **Hand ordinals that are still vectors in disguise.** Each of these should become a `[:vector …]` on its owner:
  - `:seon.step/position`: a task's steps.
  - `:seon.context.contribution/position`.
  - `:seon.error.location.segment/ordinal`, which `error.clj:2587-2599` checks for contiguity by hand.
  - `:seon.instrument.arity/ordinal`.
  - `:seon.test.accretion.group/ordinal` and `:seon.test.accretion.failure/ordinal`.
  - `:seon.fn.argument/index`, which has 0 datoms and is dead.
- **Ordinals that are not vectors. Keep them.** These are occurrence numbers and identity inputs:
  `:seon.cluster.eval/ordinal` (an input of the evaluation id, `src/seon/id.clj:279-289`), `:seon.ai.attempt/ordinal`,
  `:seon.test.failure/ordinal`, `:seon.test.seed/ordinal` and `:seon.effect/ordinal`.

## 2. Why Datahike, Datomic and DataScript have no vector value

- **A datom is one atomic fact, and cardinality-many is a set of such facts.** Datomic documents cardinality-many as
  associating "a set of values" ([schema reference](https://docs.datomic.com/schema/schema-reference.html)). Each
  element is asserted and retracted by itself, with its own transaction, and history records it the same way. Order
  and duplicates are not facts about `(e a v)`. The fork reflects this:
  - `maybe-wrap-multival` explodes a collection into separate values (`transaction.cljc:719-737`).
  - `transact-add` (`:787-810`) treats an existing datom as a no-op.
- **Every value must be totally ordered in the index.** `compare-value` relies on `compare`
  (`datom.cljc:271-315`). A Clojure vector compares by length first, then element by element. A vector holding a
  map, or holding mixed types at the same slot, cannot be compared. `safe-compare` then falls back to comparing class
  names (`:317-321`). That comparison **returns 0 for two different vectors of the same class**. See Finding below.
- **A vector in value position already means something else.** The search guard (`search.cljc:47-56`) and the query
  lowering (`query/lower.cljc:447`) accept a vector value only in two cases: as a 2-element lookup ref, or as a value
  of a declared tuple attribute. Measured: a 3-element vector on an undeclared attribute is refused in the pattern
  search. A 2-element vector is read as a lookup ref.
- **Value types are closed.** `:db.type/value` (`schema.cljc:35-55`) does not include `:db.type/any`, which `:87`
  defines for internal use only (`:db.secondary/config`, `:105`). Measured: installing an attribute with
  `:db.type/any` is refused with "Bad entity value :db.type/any".
- **Datomic's guidance** comes from Stuart Halloway on the Datomic forum
  ([Handling ordered lists](https://forum.datomic.com/t/handling-ordered-lists/305)). The deciding question is
  whether you need to query inside the list:
  - If you do, model a linked list or a positional list.
  - If you don't, Datomic would add a first-class list type. It never shipped.
  - Tuples are "2-8 scalar values" ([schema reference](https://docs.datomic.com/schema/schema-reference.html)).
  - The fork enforces the cap of 8 (`transaction.cljc:1020-1024`; measured refusal at 9 elements).
  - The 8-element limit is undocumented. *Inference:* tuples are index keys, compared slot by slot and stored whole
    in every index that covers them, so a fixed small arity bounds key size and comparison cost.

**Verdict.** The absence is principled. A vector as one value gives up per-element facts, per-element history,
element queries and reference semantics. Supporting it would also need a total comparator over arbitrary EDN.

## 3. Options

| Option | Guarantees | Cost: datoms, query, history, diff and merge | Sacrifice |
|---|---|---|---|
| **a. Homogeneous tuple** (`:db/tupleType`) | Order and duplicates. One value, so empty is distinct from absent if `[]` were allowed, but Datomic requires at least 2 slots. | 1 datom. No element query, except by whole value or tuple functions. A reorder is 1 retraction plus 1 assertion, and the diff shows both whole values. A merge conflict covers the whole value. | Up to 8 scalar elements of one type. No refs resolved from tempids (`transaction.cljc:1338-1341` resolves only refs). Fixed shape. |
| **b. Cells (installed)** | Order, duplicates, empty distinct from absent, native element refs (sweep and cascade), membership queries. | 2 datoms per element plus 1 per present vector, on top of the elements (+5.5% of the store today). A reorder rewrites only position datoms. The diff and history are per element. Positions conflict at merge, which root resolves by value. | A codec at every read and write seam (pull, q, replay, encoder, validator). The cells are visible in raw storage. |
| **c. Opaque fork value type** (a whole EDN vector, compared by value) | Order, duplicates, empty distinct from absent. One fact. | 1 datom. The diff and history see whole old and new values. A merge conflict is the whole value, which for authored order is the true conflict. The fork must add all of the following:<br>• the type to `schema.cljc:35-55`<br>• a **total** comparator to `datom.cljc:271-321` (Seon already has one for canonical EDN, `print/compare-values`, used at `schema/datahike.clj:512-521`)<br>• exemptions in `search.cljc:47-56` and `lower.cljc:447`<br>• a check that every konserve backend serializes nested values (not tested here). | No element query. No refs inside the value, so no tempid resolution, sweep or cascade. AVET on it is meaningless. `:db.type/any` cannot be used: it is not admissible, and `compare` throws or collapses values. |
| **d. Blob reference** | Arbitrary size and shape; content-addressed. | 1 datom (the digest). A diff sees only that the digest changed. | Opaque to Datalog. Suitable for large, read-whole payloads only. |
| **e. Transaction order** (assertion tx or tx metadata) | **Recorded order only, and only across transactions.** | 0 extra datoms. See §3.1. | Not authored order. It does not survive a merge or a reorder, and has no duplicates. |
| **f. Linked list** (`next` ref) | Order. | 1 ref per element, but reading it is O(n) with recursive rules, and a reorder rewrites neighbouring links. | No duplicates without cells. Strictly worse than b for Seon. |

### 3.1 Transaction order, measured in a disposable in-memory store

1. **It recovers recorded order, not authored order.** Each current datom carries the tx of its *surviving*
   assertion. `:db/txInstant` is strictly monotonic per database (`next-tx-instant`, `transaction.cljc:160-175`), so
   it orders transactions and nothing else.
2. **There is no order within one transaction.** `{:n/norms [:c :a :b]}` stored all three values with the same tx
   (536870914). The index then returns them in value order, `:a :b :c`, and the authored order is gone.
3. **A re-add is a no-op; a reorder needs a retraction and an assertion.** Re-adding `:c` kept its old tx.
   Retracting and re-adding `:c` gave it a new tx (536870918), so it moved to the end. Duplicates cannot exist at all.
4. **Merge rewrites the order.** `merge!` re-transacts caller-supplied tx-data (`versioning.cljc:738-756`).
   - A value from the feature branch took the **merge's** tx (536870919), not its original one.
   - Values the target already held kept the target's tx.
   - So a merge interleaves by when the target learned each value. It is not the branch's order.
5. **It is sound only for logs where each item is its own transaction and the order means "recorded".** Even that
   fails for Seon:
   - A pass writes all of its evaluations in **one** settlement transaction, which is why
     `:seon.cluster.eval/ordinal` exists and feeds the evaluation id.
   - Messages copied by a merge would be reordered by merge time.
   - For authored order (norm order, arglists) it is wrong in every case.
   - Datomic users are advised to model domain time as an attribute rather than read it from `:db/txInstant`
     ([forum: timestamps](https://forum.datomic.com/t/timestamps/1099)). The same reasoning applies to order. This
     is forum guidance, not official documentation.

## 4. Recommendation for Seon

1. **Use cells for any ordered data that refers to entities or must be queried by element.** This covers arities,
   arguments, binding entries, and a merge request's norm order when the norms are entities: `[:vector :seon.db/ref]`
   on the merge request. Cells are installed, reviewed and transparent. Building a second ordered mechanism would be
   a parallel system.
2. **Convert the hand ordinals listed in §1 to `[:vector …]` on their owners**, so that each owner's hand-written
   sort and contiguity check is deleted. Delete the dead `:seon.fn.argument/index` as well. Keep the occurrence and
   identity ordinals.
3. **Keep sets wherever order carries no meaning.** That covers tags, `:seon/tasks`, proofs, read-target sets,
   `:seon.db.vector/present` itself, and probably `:seon.cluster.eval/read-evidence` (1,812 cells). Its owner should
   verify whether any consumer reads it in order. If none does, making it `[:set …]` saves about 3.6k datoms.
4. **Defer an opaque fork type.** Its real payoff would be retiring the printed-EDN string codec for heterogeneous
   unions (`schema/datahike.clj:129-137`, decoded at `:1125-1140`), not vectors. It is worth building only when that
   codec, or the cell overhead on small scalar vectors, shows measured cost. It needs the total comparator first.
5. **Use a blob for large vectors that are only read whole.** Never use transaction order as authored order.

## Finding (out of scope): the fork silently collapses distinct values

`safe-compare` (`datom.cljc:317-321`) returns `class-order`, which is 0 for two values of the same class that `compare`
cannot order.

**Reproduction.** Use `:schema-flexibility :read` with cardinality-many `:t/m`, then transact
`[[:db/add 101 :t/m [1 2]] [:db/add 101 :t/m [1 "x"]]]`.

- **Result:** the report lists 2 added datoms, but the index holds only `[[1 2]]`. The second value is lost with no
  error.
- **Related:** retracting a stored vector that contains a map throws a `ClassCastException`.

**Reach.** Seon's write-mode schema type-checks each slot, so no Seon attribute is known to reach this today. Any
opaque or `:db.type/any` value would reach it.

**Fix at the producer.** `class-order` must not return 0 for distinct values: break ties with a total EDN order or a
value hash. Alternatively, refuse unordered values at `validate-val`.

**Owner and status.** This belongs to the Datahike fork. The lane was research-only, so the durable record is this
section. A task still needs to be filed.
