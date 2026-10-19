# Cross-branch references: can root point from main at chain-branch entities and pull live?

Experiment lane, 2026-10-14, default JVM mode, `(seon.cluster.boot/connection "default")`. Main branch is `:cluster-default`,
store id `#uuid "224560ae-b0ba-3d5c-b6db-6d7415ad66e7"` (`(get-in (:config @conn) [:store :id])`), schema-flexibility `:write`.
Nothing was transacted to real main (only `d/with` on a held value). Branches `:probe-xref-a`/`:probe-xref-b` created and deleted
(`d/release` + `d/delete-branch!`; verified none left).

## Answer in one line
Yes, with caveats. A plain ref from main to a branch-born eid is accepted by Datahike (dangling, no integrity check) and
`datahike.reference` resolves it live against the branch connection in ~0.05 ms (eid) / ~1.7 ms (lookup ref). But Seon's checked
writer refuses `:dh.ref/*` today (undeclared attribute), so a Seon-side declaration is needed first.

## 1. Eids are unique across branches (confirmed)
Raw `d/branch!` on the main connection (44 ms for two), `d/connect (assoc cfg :branch b)`, raw `d/transact`
`[{:seon.schedule/id "probe-xref-a-1"}]` on each branch connection (16 ms and 15 ms; I used raw datahike on the disposable
branches because the checked writer would demand a complete schedule entity; `:seon.schedule/id` is a unique string attr).
- main max-eid before: 659475, after: 659475 (it later rose to ~659492 from background main activity, unrelated).
- branch a minted 659476, branch b minted 659477: differ, both above main's max-eid.
- main does not hold 659476; b does not hold a's eid; a does not hold b's. Each is visible only on its own branch.
- `d/with` on main of a new entity later minted 659493, above the branch eids (no collision in this run). INFERRED: `d/with` mints
  from the db's own max-eid (it has no allocator), so a `with` on main could in principle mint an eid a branch already used if main's
  max-eid lags the allocator; a real `transact` uses the allocator (commit 61bca1ed). Not tested: would need a real main write.

## 2. Plain ref on main to a branch-born eid
`(d/with main [{:db/id -1 :seon.schedule/id "probe-xref-holder" :seon.listen/entity 659476}])` (`:seon.listen/entity` is
`:db.type/ref`, cardinality one): ACCEPTED in 0.37 ms, no refusal. The datom `[holder :seon.listen/entity 659476]` exists in the AVET
index. Main has zero datoms for 659476.
- `d/pull` on main: `{:seon.schedule/id "probe-xref-holder" :db/id 659493 :seon.listen/entity [:db/id 659476]}`; with a nested
  pattern `{:seon.listen/entity [*]}` it is still just `{:db/id 659476}`: a dangling ref, nothing to expand.
- `d/pull` of 659476 on the branch: `{:seon.schedule/id "probe-xref-a-1" :db/id 659476}`; the reverse ref
  (`:seon.listen/_entity`) is empty on the branch because the holder lives only on main.
So a plain ref is a pointer main can store but cannot dereference; the dereference must go through the branch connection.

## 3. `datahike.reference`
- `:dh.ref/db :dh.ref/attr :dh.ref/value :dh.ref/type :dh.ref/temporal` are in `datahike.schema/implicit-schema-spec`, NOT in
  `(d/schema db)` (it returned none), but raw `d/with` on main accepts them with no declaration.
- Built `(r/reference sid 659476 {:branch "probe-xref-a"})` and `(r/reference sid [:seon.schedule/id "probe-xref-a-1"] {:branch ...})`.
  `reference->tx-map` gives `{:dh.ref/db sid :dh.ref/attr :db/id :dh.ref/value "long:659476" :dh.ref/temporal "branch=probe-xref-a"
  :dh.ref/type :progress}`; the lookup form has `:dh.ref/attr :seon.schedule/id :dh.ref/value "str:probe-xref-a-1"`. Both stored via
  `d/with` on main and pulled back; `tx-map->reference` round-trips equal. URI: `dh://224560ae-.../seon.schedule/id/str:probe-xref-a-1?branch=probe-xref-a`.
- connect-fn: `(fn [db-id {:keys [branch]}] (when (= db-id sid) (seon.db/branch-connection main-db (keyword branch))))`.
  `seon.db/branch-connection` (src/seon/db.clj) borrows Datahike's registered connection (`connections/active-connection`), nil if this
  process has none open; verified it returns the identical object I opened. Note the reference's branch is a string; the connection
  key is a keyword. Seon production would use `seon.cluster.registry/active-branch-connection`
  (takes `{:seon.store/store :seon.store/branch}`) instead.
- `resolve-reference` timings: eid selector 0.048 ms; lookup-ref 1.9 ms cold, 1.7 ms warm (5 runs 1.67 to 1.89 ms). INFERRED cause of
  the 1.7 ms: `indexed-attr?`/`unique-attr?` each call `(d/schema db)`, which is per call work, not the AVET seek; an eid selector skips it.
- Live: after `d/transact` on the branch (`{:db/id 659476 :dh.ref/type :progress-1}`), resolving again returned the new datom
  immediately (both selectors). The reference is a living pointer: it derefs the branch connection's current db each time.
- Branch connection absent (`{:branch "nope"}`) or retired: resolves to `nil`, a typed dangling reference, no throw.
- Limits: needs a connection open in THIS process (branch-connection nil otherwise); `{:commit ...}` temporal pins a record instead.
  The retired-branch case is nil; after merge the eid exists on main (INFERRED: merge carries the same eid, so the same eid selector
  resolves on main with no change to the pointer, but I did not merge).

## 4. Seon's checked writer
`seon.db/with` on main REFUSES the reference entity map:
`seon.db/transact! refused transaction data at [0 :dh.ref/attr]: expected an installed attribute, got an undeclared attribute :dh.ref/attr`
(checked writer validates attributes against Seon's installed registry, not Datahike's implicit schema). Same for `{:tx-data [...]}`.
- A schema must declare the `:dh.ref/*` attributes (or Seon wraps them in its own attrs, see recommendation) and be published.
  `:seon.program/partition` (schema.clj, entity schemas such as `resources/seon/schemas/seon.message.edn`) decides what merges as
  program, so a reference entity should be declared non-program (INFERRED: data like evaluations/tasks, never merged as program).
- Plain ref to a branch eid via the checked writer: not conclusively tested; my probes were refused on unrelated required keys
  (`:seon.schedule/expression`, `:seon.schedule/zone-id`, and "unowned-entity" for a bare `:seon.listen/entity`). So whether Seon's
  completeness validation refuses a dangling ref target is UNKNOWN (INFERRED: it may, "refuse missing children" in CLAUDE.md).

## Recommendation: one living reference per running chain on main
1. Prefer a reified reference entity to a bare `:db.type/ref`: a ref attr invites the checked writer and `pull` to expect the target on
   main (dangling, unvalidated, and the merge of the branch could later make it silently valid); `datahike.reference` is purpose-built
   and carries the branch in the pointer.
2. Selector: use the bare eid (0.05 ms, branch-born eids are unique per store) of the chain's subject task entity, with temporal
   `{:branch "<chain-branch>"}`. A lookup ref on `:seon.task/...` identity is robust across re-materialization but costs ~1.7 ms.
3. Work needed first: declare `:dh.ref/*` (or a `:seon.chain/reference` entity wrapping `reference->tx-map`) in Seon's schema,
   non-program partition, produced by root's `db<->flow`, with the consumer being the monitor render function. Write it as ONE fact when the
   chain starts, and retract it when the branch is retired (else it reads as a typed nil).
4. Monitor: a read-only render function (reads main only) resolves with a connect-fn built on
   `seon.cluster.registry/active-branch-connection`, and shows the branch entity. It must not hold or release the connection (borrowed).
   Cross-process or unopened branches resolve nil; show that as "unknown", not failure.
5. Do not store a reference per progress change: one per chain is enough because the pointer is live. Resolution is a direct
   index read on the branch head, so it follows edits at no write cost to main.
