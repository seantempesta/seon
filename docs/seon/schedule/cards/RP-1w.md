---
title: "Card RP-1w: An agent's branch never refuses a valid change"
type: reference
status: active
created: 2026-10-08
---

# RP-1w An agent's branch never refuses a valid change

Ledger row RP-1w (group E). Ruling: "agent branches do anything" (`review/owner-rulings.md`); agent-loop.md §1.1.
Design: `redo/pass-and-recording.md` §2 ("What the recording may and may not refuse"). Lane: Sonnet. Cited at HEAD
`e7f120672`.

## What changes, in one paragraph

The checked writer's final-report validator (`validate-write-report`, `src/seon/db.clj:5411`, installed as
`:datahike/validate-report` by `prepared-write` `:5649`) runs `write-report-error` (`:5324`). Three of its checks
judge program integrity between entities: the render-target check (`write-render-target-error` `:4754`), the
deletion check (`write-deletion-error` `:4912` → `removed-definition-error` `:4809`) and the arity verdict
(`:5365-5380`). On an agent's branch those three answer **findings** instead of refusing; every other check (attribute
validation, `retention-report-check`, `write-agent-retraction-error`, owned values, `write-digest-error`) is unchanged.
On the cluster branch (main) and every other branch (merge branches, checkpoints, test overlays) nothing changes: a
merge writes main through the same writer, so `merge!` keeps every check.

## Files

- `src/seon/db.clj`
- `resources/seon/schemas/seon.program.finding.edn` (new)
- `resources/seon/schemas/seon.db.edn` (`:transaction-result` and `:transaction-report` gain an optional member)
- `test/seon/db_test.clj`

## Schema (new file `seon.program.finding.edn`, the errors' base-plus-variant pattern, as `seon.schema.finding.edn`)

```edn
#:seon.program.finding
{:type [:qualified-keyword {:description "The finding's variant: its own schema key."}]
 :base [:map {:description "A program-integrity fact the branch writer or the eval request found and did not refuse: delivered beside the value, judged at merge."}
        [:seon.program.finding/type :seon.program.finding/type]]
 :dangling [:and :seon.program.finding/base
            [:map [:seon.program.finding/type [:= :seon.program.finding/dangling]]
             [:seon.program/subject :seon.schema/value]
             [:seon.program/relation :qualified-keyword]
             [:seon.program/referrer [:map-of :qualified-keyword :seon.schema/value]]]]
 :render-target [:and :seon.program.finding/base
                 [:map [:seon.program.finding/type [:= :seon.program.finding/render-target]]
                  [:seon.schema/key :qualified-keyword]
                  [:seon.render/property :qualified-keyword]
                  [:seon.render/function :qualified-symbol]]]
 :arity [:and :seon.program.finding/base
         [:map [:seon.program.finding/type [:= :seon.program.finding/arity]]
          [:seon.fn/arity-mismatches :seon.fn/arity-mismatches]]]
 :any [:multi {:dispatch :seon.program.finding/type}
       [:seon.program.finding/dangling :seon.program.finding/dangling]
       [:seon.program.finding/render-target :seon.program.finding/render-target]
       [:seon.program.finding/arity :seon.program.finding/arity]]}
```

Later rows add variants to `:any` by accretion (RP-1 `:swept`, RP-6d `:unjoined-write`, RP-7 `:overwrote`, RP-8
`:alias-retargeted`, RP-9 `:live-only`). `:seon.fn/arity-mismatches` is registered (`resources/seon/schemas/seon.fn.edn:107`).
`removed-definition-error` already writes (`:4842-4855`).

In `seon.db.edn`, add `[:seon.db/findings {:optional true} [:vector {:min 1} :seon.program.finding/any]]` to
`:transaction-result` (`:225`) and `:transaction-report` (`:302`).

## Functions

- ADD `seon.db/agent-branch?` (public: RP-1b's `my.program` deletion tools read it too)
  `{:malli/schema [:=> [:cat :seon.db/database-value] :boolean]}` `[database]`.
  True when the database's branch (`(get-in database [:config :branch])`) differs from
  `(seon.cluster.branch/cluster-branch name)` for the cluster name stored on it
  (`(:v (first (d/datoms database :aevt :seon.cluster/name)))`) **and** some `:seon.agent/branch` datom
  (`(d/datoms database :aevt :seon.agent/branch)`, not indexed: one datom per agent) has that branch as its value.
  Probed on default: an agent branch's value has `:config :branch` `:agent-1db09424edbd`, the cluster entity
  `"default"`, and the agent row naming that branch; root's `:seon.agent/branch` is `:cluster-default`. A database with
  no cluster entity (a fresh store before boot) is not an agent branch. `seon.cluster.branch` is required lazily
  (`requiring-resolve`) only if `seon.db` cannot require it directly without a cycle; check with
  `clj-kondo --lint src/seon/db.clj` and a load at the REPL.
- ADD `seon.db/integrity-findings` (private)
  `{:malli/schema [:=> [:cat [:maybe :seon.error/base]] [:vector :seon.program.finding/any]]}` `[refusal]`.
  Converts one integrity refusal into findings: a `:seon.program/deletion-refused-error` → one `:dangling` per entry
  of its `:seon.program/referrers` (`select-keys` of subject, relation, referrer); a render-target refusal → one
  `:render-target` per entry of `(get-in refusal [:seon.error/data :seon.render/declarations])`; an arity refusal →
  one `:arity` with `(get-in refusal [:seon.error/data :seon.fn/arity-mismatches])`; `nil` → `[]`.
- CHANGE `seon.db/write-report-error` (`:5324`): arity `[projection report findings]`, contract
  `[:=> [:cat :seon.schema/projection :seon.db/transaction-report [:maybe :seon.db/findings-sink]] [:or :nil :seon.db/error-result]]`.
  When `findings` (a `volatile!` created per write, see below) is non-nil **and** `(agent-branch? database)`, each of
  the three integrity checks runs as today, and its non-nil result is appended with
  `(vswap! findings into (integrity-findings result))` instead of being returned; the `or` chain continues. Otherwise
  the chain is unchanged. Note the arity branch's success value (`(assoc result ::transaction-refused true)` when
  `mismatches` is absent) is a refusal of another shape: keep returning it on every branch.
- CHANGE `seon.db/validate-write-report` (`:5411`): arity `[prepared report findings]`, passes `findings` through.
- CHANGE `seon.db/prepared-write` (`:5649`): arity `[projection database transaction findings]`; the installed
  validator is `#(validate-write-report projection % findings)`.
- CHANGE `seon.db/transact-call` (`:5706`): creates `findings (volatile! [])` per call, passes it to
  `prepared-write`, and on a committed report returns `(cond-> report (seq @findings) (assoc :seon.db/findings @findings))`.
  This is the "local accumulator for a callback the code does not own" that the atoms ruling allows (ledger AT-6):
  Datahike's validator answers only nil or a refusal (`reference-code/datahike/src/datahike/db/transaction.cljc:1225-1234`).
- CHANGE `seon.db/transaction-result` (`:5931`): copies `:seon.db/findings` from the report onto the elided result.
- Register `:seon.db/findings-sink` in `seon.db.edn` as
  `[:fn {:error/message "must be a volatile holding a vector of findings"} seon.db/volatile?]` (no registered
  predicate fits: the schemas name no volatile), with the new public predicate `seon.db/volatile?`
  `{:malli/schema [:=> [:cat :any] :boolean]}` `[x]` = `(instance? clojure.lang.Volatile x)`.

- CHANGE `seon.db/with` (`:5781`, contract unchanged): passes `(volatile! [])` to `prepared-write` and discards it, so
  a speculative value on an agent branch is judged exactly as its commit will be; the commit (through `transact-call`)
  is what reports the findings. `transact-call` and `with` are the only callers of `prepared-write` in `src`.

## Data

```edn
;; (seon.db/transact! [[:db/retractEntity [:seon.fn/sym 'rp1w.a/pa]]]) on the agent's branch, pb calling pa:
{:seon.db/tx 536872200
 :seon.db/datoms [...]
 :seon.db/findings [{:seon.program.finding/type :seon.program.finding/dangling
                     :seon.program/subject rp1w.a/pa
                     :seon.program/relation :seon.fn/calls
                     :seon.program/referrer {:seon.fn/sym rp1w.a/pb}}]}
```

## Proof

`seon.db-test/a-branch-write-keeps-integrity-as-findings-and-main-refuses` (new; the ledger's name ends
"merge-refuses": `merge!` writes main through this same writer, so main's refusal is merge's refusal until MA-11
gives `merge!` its own test). Sketch, in the `seon.repl-differential-p3-test` idiom (a private `reply` over
`seon.mcp/call`):

```clojure
(support/with-running-agent
 (fn [running agent-id _]
   (reply "(ns rp1w.a) (defn pa [] 1) (defn pb [] (pa))" agent-id running)
   (let [r (reply "(seon.db/transact! [[:db/retractEntity [:seon.fn/sym 'rp1w.a/pa]]])" agent-id running)]
     (is (= [:seon.program.finding/dangling] (mapv :seon.program.finding/type (:seon.db/findings (:seon.repl/value r))))))
   (is (nil? (:seon.repl/value (reply "(seon.db/pull (seon.db/db) [:db/id] [:seon.fn/sym 'rp1w.a/pa])" agent-id running))))
   ;; root on main: the same shape of deletion refuses
   (reply "(ns rp1w.m) (defn ma [] 1) (defn mb [] (ma))" "root" running)
   (is (:seon.db/transaction-refused
        (:seon.repl/value (reply "(seon.db/transact! [[:db/retractEntity [:seon.fn/sym 'rp1w.m/ma]]])" "root" running))))))
```

If `with-running-agent`'s cluster has no root agent reachable by `"root"`, write the main-branch half in JVM mode with
the member connection passed explicitly (`seon.db/transact! connection …`), which is the same writer.
Add one row for a render pair: on the branch, retracting the renderer function a schema's `:seon.render/ai` names
answers one `:render-target` finding and commits.

## Reaching tests (run in `--test`)

`seon.db-test`; the existing refusal tests stay green on main:
`seon.db-test/removing-a-renderer-refuses-by-the-declarations-it-breaks` (`test/seon/db_test.clj:2962`),
`…/arity-components-and-callers-are-checked-in-the-final-state` (`:2170`); and `seon.fault-test`,
`seon.cluster.host-test` (both assert `:seon.program/deletion-refused-error` today; if either asserts a refusal on an
agent branch, it is converted to assert the finding in this commit).

## Cost

Expected from the work: one `:aevt` read of `:seon.cluster/name` and one of `:seon.agent/branch` (one datom per agent)
per write on a non-main branch, microseconds; the integrity checks themselves run exactly as today. Measured parent:
the census's MCP SCI eval request on default, 1 commit, 253 KB; no change in commits or bytes is expected. Report the
O probe row (`seon.pass-probe/run-mcp` SCI) before and after.

## Deleted

Nothing yet (RP-1 deletes `removal-facts`' refusal branch in the pass).

## Commit

`bin/lane commit <lane> -m "RP-1w: an agent's branch writer answers integrity findings instead of refusing" --test seon.db-test --test seon.fault-test --test seon.cluster.host-test src/seon/db.clj resources/seon/schemas/seon.program.finding.edn resources/seon/schemas/seon.db.edn test/seon/db_test.clj`

## Dependencies

None in the ledger. `src/seon/db.clj` is held by led-cacheb: wait for its release. Schema change: the new finding
keys are non-entity schemas (no Datahike attribute), so publication installs no attribute; prove adoption on a branch
of the live store with `bin/lane commit` (its check publishes and adopts).

## Reset needs

None.
