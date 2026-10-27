# Verbatim merges: what breaks if merged entities keep the branch's eid (and tx)

Read-only audit, 2026-10-23, tree `next-global-ids` at b39cef5. Question: if a merge
(branch -> main, writer; main -> branch, `seon.agent/merge-main!`) transacts the source's own
datoms for the merged keys' entities with their original e (and tx), instead of
`seon.merge/apply-tx`'s upsert by identity, what in src/, test/, resources/ breaks.

## The verdict first

- **Keeping e is sound, with three rules Seon must add.** Entity ids already come from one
  store counter (`datahike.writing/eid-allocator`, writing.cljc:851; agent blocks
  `seon.db/lease-block!` db.clj:334, ceilings `lease!` db.clj:284). Data datoms already merge by
  eid (merge.clj:219-227). The rules: (a) an identity value both sides minted (same
  `:form/id`, `:var/name`, `:ns/name`, `:method/key`, `:impl/key`, `:schema/key`, `:db/ident`)
  keeps main's entity, and the source's refs to its own are remapped (today's upsert, kept
  for exactly that case); (b) carry the ref closure (an entity a carried ref names must exist on
  the target, or be remapped by (a)); (c) set an entity by diff (retract the target's datoms the
  source lacks), not by assertion alone.
- **Keeping tx is not sound.** Tx ids are per branch, not store-wide: Datahike's
  `current-tx` is `(inc (:max-tx db-before))` (reference-code/datahike/src/datahike/db/transaction.cljc:55-56),
  and `transact-add` accepts any tx in a 5-tuple without a range check (transaction.cljc:835).
  A branch's tx T and main's tx T are different transactions with one id. History is kept
  (`:seon.config.store/keep-history? true`, resources/seon/config/default.edn:108), so a carried
  tx makes `d/as-of`/`d/history` and the tx entity's facts (`:db/txInstant`, `:seon.tx/runtime`)
  lie. **Smallest correct change: carry e, a, v; let the merge commit's own tx stamp them.**
  The source's tx stays reachable through the merge commit's parent (Datahike's commit graph).
  Making tx ids store-wide is a Datahike change (`current-tx` from a store counter) and buys
  nothing Seon reads today (nothing in src compares a datom's tx across branches; see 3).

## 1. Places that assume a form's eid on main is independent of the branch's

| Where | What it assumes | Smallest change |
|---|---|---|
| src/seon/merge.clj:18-21 (ns doc) | only data entities keep their id | say every merged entity keeps its id, but for rule (a) |
| src/seon/merge.clj:166-178 `def-ref` | a definition new to the target is upserted under a tempid (`"var sym"`) | keep for rule (a) only: when the target holds the identity under another eid, use the target's eid; else the source's eid |
| src/seon/merge.clj:182-201 `set-form-tx` | form upserted by `:form/id` with `program/form-tid`; `:form/ns` via `ns-tid`; `:form/defines`/`:form/declares` via `def-ref` | replace by `set-entity-tx src-db e`: the source entity's datoms (e, a, v), refs remapped by (a), plus the closure of (b) |
| src/seon/merge.clj:203-217 `set-def-tx` | definition found on target by identity (`def-entity`), pointer set to the form's tempid | same `set-entity-tx`; its retract loop (216-217) becomes the general diff of rule (c) |
| src/seon/merge.clj:229-237 `set-schema-tx` | entry upserted by `:schema/key`, no eid | eid-carry fine; keep the `:schema/source` retract (237) inside the diff |
| src/seon/merge.clj:239-256 `apply-tx [db m]` | doc: "entity ids never matter"; it needs only the target db | signature gains the source: `(apply-tx target src m)`; the octopus needs a source per key: `{k [src-db content]}` |
| src/seon/merge.clj:254-255 | attribute declarations (`:db/ident` entities) re-derived and minted on the target | keep: never carry attribute entities (rule (a) on `:db/ident`; Datahike's rschema needs its own install path) |
| src/seon/writer.clj:96-100 `octopus-merge` | `changed` is one key->content map across members, applied against `main` | carry each member's head with its keys (`branch-merge` writer.clj:22-30 already returns `head`) |
| src/seon/agent.clj:205-207 `merge-main!` | `(pm/apply-tx head ...)` reads main's content by value | pass `main` as source |
| src/seon/program.clj:343-345, 366-423 (`ns-tid`, `form-tid`, `account-tx`) | recording on a branch mints by tempid/upsert | unchanged: this is minting, not merging |
| src/seon/program.clj:716-739 `divergence` (doc 718-722) | "entity ids are a branch's own and a merge applies main's forms by content under the branch's ids" | the doc changes; the algorithm (eid diff, then content compare by name) stays correct and gets cheaper; content compare must stay for rule (a) entities |
| src/seon/program.clj:741-746 `changed-vars` | compares by `:form/id` via divergence | unchanged |
| src/seon/program.clj:677-689 `load-diff!` | compares live forms by `:id` (text), definitions by key | unchanged (content-keyed) |
| src/seon/program.clj:691-714 `load-changes!` | reads resolved ops' eids against db-after | unchanged (eids are the target's either way) |
| src/seon/merge.clj:77-95 `program`, :97-101 `same?` | content per key; per-db eids | unchanged |
| src/seon/task.clj:173-190 `carry-tx` | keys by `[e a]` across base/branch/main, writes by lookup ref `[:seon.task/id id]` | unchanged: tasks already share eids (from the base); tasks only the branch made are left (186) |
| src/seon/db.clj:319-323 `lease-use` | `:used = max-eid - lease`: the branch's max-eid is its own minting | **wrong once merge-main brings main's later ids** (another block, root's counter): read used from this process's allocator (`(.get (datahike.writing/eid-allocator db))` - lease), shown by agent.clj:499 |
| docs/storage.md:93 ("Compiled code") | "`changed-vars` compares forms by `:form/id`, since a merge applies main's forms by content under the branch's own entity ids" | rewrite in the same commit |
| docs/storage.md:30 (leases) | "also after a merge brings an agent's ids onto main" | still true; add: merge-main brings main's ids onto the branch, the agent still mints in its block (transaction.cljc:84-100) |

A current defect verbatim merging fixes: `set-datum-tx` (merge.clj:219-227) carries a data
attribute's ref value verbatim, while `apply-tx` re-mints the program entity it may name. Root
merges with `:data-attrs :all` by default (root.clj:834, agent_proc.clj:189), and `:task/refs` is
a set of refs to var entities (resources/seon/schemas/seon.facts.edn:98; seed db.clj:43). A branch
that adds a var and a datum naming it lands on main naming the branch's var eid, which main does
not have. Under rule (b) the ref and its target arrive together.

## 2. Two branches minting one unique-identity value

Where Datahike refuses: `validate-datom-upsert` (transaction.cljc:574-581) and `validate-datom`
(transaction.cljc:27-32): `[:db/add e_B :var/name s]` when main holds `s` on `e_A != e_B` throws
`:transact/unique`; the whole transaction is refused.

Where it reaches Seon:
- **Writer, octopus merge** (writer.clj:100): the throw is caught by `writer-one`
  (writer.clj:219-223), which answers the whole request `refused` with no `:results`; the
  reconciler's `:octopus-merged` (reconcile.clj:660) maps members over `(:results r)`, so no member
  attempt is ended (they stay `:running`). Today only a store failure takes this path; verbatim
  makes it ordinary.
- **Agent merge-main!** (agent.clj:206): the same throw goes into the agent's eval as an error;
  nothing is merged, the branch stays behind forever.

When it happens (all reachable today):
- `:ns/name`: any two branches that put forms in one new namespace (every recorded form
  upserts its ns entity: merge.clj:189, program.clj:398). Most frequent.
- `:var/name`: both add one new var. Different content: a `:both-added` conflict
  (merge.clj:122-125); resolved on the branch by keeping ours (merge.clj:313-319), the branch
  then merges `e_B` onto main, which holds `e_A`. Identical content: no key is carried (`same?`),
  but the branch keeps `e_B` and main `e_A` forever, so eids are not "one per identity" after it.
- `:form/id`: identical text added on both (a `declare`, a `(comment ...)`, the same defn).
- `:method/key`, `:impl/key`, `:schema/key`: same as vars.
- `:db/ident`: both register the same new schema key (merge.clj:254-255 installs its attribute).
- Kept branches merged by content before the change already hold diverged eids for main's forms
  (program_test.clj:389-412 builds exactly that), so the first verbatim merge of any old branch
  collides.

The rule Seon needs, in one place (`seon.merge/apply-tx`, rule (a)): for each identity datom the
source carries, look the value up on the target (`seon.db/eid`, db.clj:17-22); if the target
holds it under another eid, use the target's eid for the entity and remap every carried ref
to it. This is today's `def-ref`/`set-form-tx` upsert, kept for the colliding case only. For
merge-main the remap also has to retarget the branch's own refs (its forms' `:form/defines`, its
vars' `:var/ns`) and retract `e_B`, or the branch keeps two entities for one identity and the
next `program` reads one of them. Without the rule, check collisions in `merge-refusal`
(writer.clj:46-71) as a typed refusal per member, before the transaction, so the octopus still
answers per member.

## 3. tx ids and t across branches

Every t Seon compares is a main t (the merge commit's own tx), which a carried datom tx does not
change, because `:max-tx` advances by one per transaction (transaction.cljc:1346, 1422):
- `:seon.attempt/merged-t` = writer `:tx` = `(:max-tx (:db-after rep))` (writer.clj:101, 106;
  reconcile.clj:668); `:seon.attempt/basis-t` = `:base-t` = `(:max-tx base)` (writer.clj:198;
  reconcile.clj:564); compared at reconcile.clj:289 (`(<= mt basis)`) and sorted at
  reconcile.clj:422. Safe.
- `:seon.merge/main-t` (merge.clj:295-304, agent.clj:207): main's t recorded on the branch, read
  only as data (merge.clj:283, dissoc'd at reconcile.clj:557). Safe.
- Fork server replica: skip/mismatch by t (fork_server.clj:52, 57); `seon.agent/misaligned`
  (agent.clj:567-574) compares commit id and image t; check.clj:1150-1152; render
  (render/proc.clj:144, 426). All main's t. Safe.
- `seon.db/report->tx` (db.clj:371-377) drops the tx column, so the replica stamps its own tx.
  With carried tx the replica's datoms differ from main's in tx. Harmless with tx not carried; if
  tx is carried, emit `[op e a v tx]` for datoms whose tx is not the report's.
- `seon.e2e` (e2e.clj:34) compares a datom's tx with a main t (`> t`) for `:seon.error/last-ms`:
  platform records, never carried. Safe unless a carried datom's attribute is read the same way.

What a carried tx id would break (and so: do not carry it):
- a carried T lower than the merge tx M: `d/as-of main t` for T <= t < M shows the datom before
  it reached main; a T above M: `d/as-of main M` hides a datom main holds at M, and main's own
  later tx T reuses the id.
- `(d/entity main T)` (`:db/txInstant`, `:seon.tx/runtime`) names main's transaction T, not the
  one that wrote the datom; procs_test.clj:61, 79 and agent_test.clj:333-334 read the runtime of
  `(:max-tx ...)`, still the merge's, so they stay green, but the claim "each commit names its
  runtime" stops covering carried datoms.
- `seon.program/record-tx` uses `(inc (:max-tx db))` as a new form's `:form/order`
  (program.clj:429): a branch tx id as an order. Already per-branch today; verbatim changes nothing.

## 4. Card-one replacement and retraction in the merge path

- `set-def-tx` relies on Datahike's card-one upsert to replace `:var/form`, `:var/kind`,
  `:method/form`, `:impl/form`, `:ns/form` in the merge tx (merge.clj:210-215), and retracts
  explicitly what the new content lacks (`:var/contract`, `:var/doc`, `:ns/file`, a pointer:
  merge.clj:216-217). Branch datoms only assert what is present, so verbatim must keep a diff:
  retract each target datom of the entity the source lacks.
- `set-form-tx` never retracts card-many values (`:form/defines`, `:form/declares`,
  `:form/refs`, `:form/requires`, `:form/banned`, `:form/inline-defs`, `:form/timeouts`,
  `:form/catches`: merge.clj:192-199); one form id with differing card-many content becomes the
  union. The entity diff fixes that too.
- `set-datum-tx` card one (merge.clj:224-227) relies on the upsert; card many retracts
  explicitly. Unchanged.
- `set-schema-tx` retracts `:schema/source` (merge.clj:237): keep it in the diff.
- A form the source retracted (superseded: program.clj:423; unmapped) merges as
  `[:db/retractEntity e]` found by `:form/id` (merge.clj:186-187); with shared eids that is the
  same e, unless rule (a) remapped it.
- `unmerged-tx` replaces earlier entries by `retractEntity` (merge.clj:301-304), in the same
  merge-main tx (agent.clj:206-207). Unchanged.
- Tx order: the upsert's implicit retraction of the old value takes the transaction's tx; if the
  new assertion carried an older tx, the temporal index would show the retraction after the
  assertion that replaced it. Another reason not to carry tx. (Datahike's current-index upsert
  matches by e, a: persistent_set.cljc:130-149, so the current value is right either way.)

## 5. Tests that turn red

By signature (apply-tx gains its source db): every 2-argument call:
- test/seon/merge_test.clj:94 `unmap-merges-as-delete` (call at :97)
- test/seon/merge_test.clj:124 `apply-tx-sets-each-key` (:126, :127)
- test/seon/merge_test.clj:193 `a-schema-entry-merges-per-key` (:216)
- test/seon/program_test.clj:190 `defmethod-extend-and-defrecord-merge-and-load-on-main` (:201)
- test/seon/source_test.clj:91 helper `merged` (:96), so :98 `a-merged-change-rewrites-only-its-form`,
  :163 `a-merge-is-committed-on-the-merges-branch` and every other deftest using it (:101, :107,
  :114, :172, :180)
- test/seon/source_test.clj:378 helper `merge-loaded` (:387), so :390
  `a-merge-to-imported-source-loads-in-place`, `a-changed-macro-reloads-the-forms-it-expanded-in`,
  :404 `a-merged-protocol-keeps-its-records`, :410 `a-merged-record-is-the-one-its-callers-see`
Smallest change: pass the branch value they already hold (`branch`, `ours`) as the source.

By behaviour:
- test/seon/program_test.clj:389 `a-branch-that-merged-main-carries-only-its-own-changes`: stays
  green (it builds the diverged ids by hand), but its premise (comment :390-392, assertions
  :409-410 "f's form is another entity on each side") states the old rule; rewrite it to merge
  main verbatim and assert the ids are equal, keeping :411-412.
- test/seon/merge_test.clj:87 `identical-defs-on-two-branches-merge-clean`: green (content
  three-way), and it is the rule (a) case; add a verbatim-apply step that must not throw
  `:transact/unique`.
- d/db-with tests mint eids from the db's own `max-eid` ("speculation has no counter",
  writing.cljc:866-867), so two values from one base mint the same eid for different entities.
  Verbatim apply in such a test (merge_test `with`, :31; ts/test-db) merges two unrelated entities
  into one. Tests that move both sides past the base must use `ts/test-branch!` with
  `d/transact`, or the proof proves nothing.
- test/seon/db_test.clj:59-60 (`lease-use` exact `:used`): green; add the merge-main case that
  `lease-use` gets wrong today (1).
- Green, unchanged: agent_test.clj:309 `after-merge-main-the-agent-judges-against-the-new-base`
  (its `:runtime` read is the merge commit's), procs_test.clj:64 `a-writers-merge-names-its-runtime`,
  procs_test.clj:83 `a-fork-at-another-commit-is-recorded-misaligned`, db_test.clj:89 and :426
  (leases after a merge), agent_test.clj:372 `no-entity-of-main-is-inside-an-agents-lease`,
  reconcile_test merge tests (t's are main's).

## Proofs the change owes (red first)

1. Two branches each record a form in new ns `app.n`, both merge (octopus and in turn): no
   `:transact/unique`, main holds one `:ns/name app.n` entity, both forms point to it.
2. Branch adds var v and a `:task/refs` datum naming it; after merge, main's datum names main's v.
3. merge-main then merge back: the merged form's eid on main equals the branch's; `divergence`
   of base and branch is `{}`.
4. A merged datom's tx is the merge commit's (`d/history` shows it at merged-t, not earlier).
