# A task chain in one transaction (probe, 2026-10-14)

Method: JVM-mode `eval_clj` on default, every write a `datahike.api/with` or `seon.db/with` on a held db value
(commit `6ace0523`, basis-t 536871344). No branch was created, nothing was transacted to main, no file edited.
Scratch Vars (`user/mk`, `user/ctx`, ...) live in the JVM `user` ns until restart. Anything not run is marked inferred.

## 1. Tempids through a ref attribute: works, both kinds

Entity shape used (`mk`): `{:db/id e :seon.task/ns 'probe.chain :seon.task/name n :seon.task/id (seon.task/task-handle ns n)
:seon.task/sym ns/n :seon.task/execution :held :seon.task/intent "x"}`.

```clojure
[(mk "a" -1) (assoc (mk "b" -2) :seon.task/needs #{-1}) (assoc (mk "c" -3) :seon.task/needs #{-2})]   ; or "a" "b" "c" strings
(d/with dbv tx) => :tempids {-1 659074, -2 659075, -3 659076, :db/current-tx 536871347}
(d/pull db-after '[:seon.task/sym {:seon.task/needs ...}] [:seon.task/sym 'probe.chain/c])
=> {:seon.task/sym probe.chain/c, :seon.task/needs [{:seon.task/sym probe.chain/b, :seon.task/needs [{:seon.task/sym probe.chain/a}]}]}
```

Negative ints and string tempids give the same result. Forward and backward references both resolve (a ref to a tempid
declared later in the vector is fine).

`:seon.task/next` scratch install (it does not exist on default: `(contains? (:schema dbv) :seon.task/next)` is false):

- Schema datom in an earlier `with`, then the chain: works. `[{:db/ident :seon.task/next :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}]`
  then `[(assoc (mk "a" -1) :seon.task/next -2) (assoc (mk "b" -2) :seon.task/next -3) (mk "c" -3)]` pulls
  `a -> b -> c` with `{:seon.task/next ...}`.
- Schema datom in the SAME tx vector as the data (`(into next-schema tx-next)`): also works.
- No schema: `Bad entity attribute :seon.task/next ... not defined in current schema` (raw Datahike).
- Lookup refs to an entity created earlier in the same tx work (`[:seon.task/sym 'probe.chain/a]` inside b's `:needs`,
  `[:seon.task/id handle]` too). They are ORDER-DEPENDENT: the same two entities reversed refuse
  `Nothing found for entity id [:seon.task/sym probe.chain/a]`. Tempids have no such constraint.

## 2. What Seon's writers require

Read: `seon.task/task-handle` = `seon.id/id` over `[ns name]` (ex. `(task-handle 'probe.chain "a")` => `"2dd94dfc1cb9"`);
`task-sym` = `(symbol (name ns) name)`; `:seon.task/id` is `:seon.db/identity`, `:seon.task/sym` is `:seon.db/unique`.
`:seon.task/task` requires id, sym, ns, name; `:seon.task/declaration` (the revision-compared members, from
`declaration-members`) lists ns, name, step, intent, instructions, children, needs, product, proofs. `next` is in neither.
`:seon.task/children` is `:seon.db/component`; `:seon.task/needs` is an indexed ref set.

Two writer paths, different rules:

| Path | Tempid refs | Lookup refs | needs-cycle refusal | identity check |
|---|---|---|---|---|
| `seon.db/with` / `transact!` (checked writer) | accepted, pulls as in section 1 | accepted, in-tx order only | NOT refused (`cyc` of a<->b via tempids: `:ok`) | none: id `"000000000000"` accepted; name `"bad name"` accepted |
| `seon.task/adopt-tasks-tx` (used by `chain!`, `create!`) | broken: it does `(dissoc % :db/id)` and keys entities by `:seon.task/id`, so `-2` in a needs set becomes a stray `[:db/add [:seon.task/id h] :seon.task/needs -2]` | required: `refs-last` moves `tree-attributes` (`[:seon.task/children :seon.task/needs]`) to trailing `:db/add`s so lookups resolve | refused: `"probe.chain is refused: needs-cycle."` offending `[a b a]` | handle derived from [ns name] by the caller (`identified` in `chain!`) |

Checked-writer refusals observed (the writer validates entity shape and attributes, not task semantics):
- missing `:seon.task/ns`: `refused ... expected the required key :seon.task/ns with a symbol`.
- missing `:seon.task/id`: `Complete component validation refused: unowned-entity` (a task with no identity is an unowned component).
- `:seon.task/next` with no installed attribute: `expected an installed attribute, got an undeclared attribute :seon.task/next`.
  With the raw `:db/ident` datom installed through `d/with` first, `seon.db/with` then ACCEPTED `next` (the projection did not need a published schema entity to admit it; inferred that a real addition still goes through `resources/seon/schemas/seon.task.edn`).
- `symbol-name?` is only called by `seon.task/create!`; the checked writer never enforces it.

`next` through `adopt-tasks-tx` (ran): the key passes through the delta untouched (it is not a declaration member, so it is
neither compared nor retracted). Written as a lookup ref it lands in the entity map, in `sort` order not chain order, and
`seon.db/with` then refuses `Nothing found for entity id [:seon.task/sym probe.chain/prove-demo]` because design is asserted
before prove. So `next` must either be added to `tree-attributes` (so `refs-last` moves it to a trailing `:db/add`), or the chain
must be written with tempids in one `seon.db/transact!`.

## 3. Schema defaults

No task schema declares a Malli `:default` (`:seon.task/declaration`, `:seon.task/task`: map of defaults is `{}`). The only
`:default` properties in `resources/seon/schemas` are print/test/schema ones. `rg default-value-transformer src` finds nothing: Seon
does not use it; the one task default is hard-coded (`held-tx`/`task-delta` write `:execution :held`).
Malli works with the registry, once a default is declared:

```clojure
(m/decode [:map [:a {:default 1} :int] [:b {:optional true} :int]] {} (mt/default-value-transformer))   ; => {:a 1}
(def s (m/schema [:map [:seon.task/execution {:default :held :optional true} :seon.task/execution]] {:registry reg}))
(m/decode s {} (mt/default-value-transformer))                                  ; => {}   optional keys are skipped!
(m/decode s {} (mt/default-value-transformer {::mt/add-optional-keys true}))    ; => {:seon.task/execution :held}
(m/decode s {:seon.task/execution :running} (mt/default-value-transformer {::mt/add-optional-keys true})) ; caller wins
```

Caveat: every task member is `:optional true`, so defaults need `::mt/add-optional-keys true`. Decoding the real
`:seon.task/declaration` today returns the input unchanged (no defaults declared). `reg` =
`(:seon.schema.projection/registry (seon.db/carried-projection dbv))`.

## 4. Template merge, 3-task chain

The templates exist in the db (`seon.step/survey define design prove implement`, from `seon.step.edn`) with `:execution :held`,
a WORKED-EXAMPLE `:intent` (about `seon.fs/under-path?`; must never be copied), `:instructions`, and `:step` on prove/implement.
They hold no stored `:proofs` (chain-copy derives those with `obligations`; not exercised here).

```clojure
(def identity-keys #{:db/id :seon.task/id :seon.task/sym :seon.task/ns :seon.task/name :seon.task/references :seon.task/intent :seon.task/execution})
(def general-defaults {:seon.task/execution :held})
(defn template-values [dbv tsym] (apply dissoc (d/pull dbv '[*] [:seon.task/sym tsym]) identity-keys))
(defn chain-task-tx [defaults tvals {:keys [ns name tempid next plan] :as o}]
  (let [t (cond-> (merge defaults tvals (dissoc o :ns :name :tempid :next :plan)
                         {:db/id tempid :seon.task/ns ns :seon.task/name name
                          :seon.task/id (seon.task/task-handle ns name) :seon.task/sym (symbol (clojure.core/name ns) name)})
            plan (assoc :seon.task/intent plan) next (assoc :seon.task/next next))
        refs (into #{} (mapcat seon.ref/names) (keep t [:seon.task/intent :seon.task/instructions]))]
    (cond-> t (seq refs) (assoc :seon.task/references refs))))
(defn chain-tx [dbv ns subject plan templates]
  (let [n (count templates)]
    (vec (map-indexed (fn [i t] (chain-task-tx general-defaults (template-values dbv t)
                                  (cond-> {:ns ns :name (str (name t) "-" subject) :tempid (- (inc i)) :plan plan}
                                    (< (inc i) n) (assoc :next (- (+ i 2))))))
                      templates))))
```

Order is defaults < template < caller overrides (the owner's order). `clojure.core/merge` with later maps winning is the
whole algorithm; `:plan` stands for the renamed `:seon.task/intent`; references are derived because the writer does it today
(`with-references`); with the one-transaction design that derivation moves into this function or a tx fn.

Result for `(chain-tx d1 'probe.chain "demo" "## Problem ... `seon.fs/under-path?` ..." '[seon.step/design seon.step/prove seon.step/implement])`
(d1 = dbv + the scratch `next` attribute): 3 maps, tempids -1 -2 -3, `:next` -2, -3, (none); ids `d5e27d0dac35`, `decec36d2329`,
`c616937e2968`; names `design-demo prove-demo implement-demo`; `:step :prove`/`:implement` carried from the templates; instructions
921 / 1654 / 890 chars; per-task derived `:references` e.g. design `#{seon.fs/under-path? my.task/refine! seon.contracts/gaps}`.

Timings (`seon.profile/measure`, warm JVM, one run each):

| Stage | ms | alloc |
|---|---|---|
| build tx (3 pulls + merge + ref derivation) | 2.74 | 0.46 MB |
| `datahike.api/with` | 1.10 | 0.81 MB |
| `seon.db/with` (checked writer, validation) | 3.59 | 2.39 MB |

Both accepted the chain; pull `{:seon.task/next ...}` from design gives `design -> prove -> implement`.

## 5. Readiness via `next`

`done` is the existing `leaf-done` rule from `seon.task/ready-rules` (an attempt of the task with outcome `:merged`). In the probe
I made a task finished by `d/with`-ing `{:seon.task/attempt-of [:seon.task/sym s] :seon.task/outcome :merged}`.

```clojure
(def rules (into seon.task/ready-rules
  '[[(waiting ?t) [?p :seon.task/next ?t] [?p :seon.task/id] (not (leaf-done ?p))]
    [(chain-ready ?t) [?t :seon.task/id] (not (leaf-done ?t)) (not (waiting ?t))]
    [(downstream ?h ?t) [?h :seon.task/next ?t]]
    [(downstream ?h ?t) [?h :seon.task/next ?m] (downstream ?m ?t)]]))
;; ready tasks
(d/q '[:find [?s ...] :in $ % :where (chain-ready ?t) [?t :seon.task/sym ?s]] db rules)
;; first unfinished task of the chain headed by ?hs: the head or anything downstream of it that is ready
(d/q '[:find ?s . :in $ % ?hs :where [?h :seon.task/sym ?hs] (or [(identity ?h) ?t] (downstream ?h ?t))
       (chain-ready ?t) [?t :seon.task/sym ?s]] db rules 'probe.chain/design-demo)
```

Observed: t0 `[design-demo]`; after design merged `[prove-demo]`; after prove `[implement-demo]`; after implement `[]` and first = nil.
The query costs 1.92 ms on the 3-task chain (whole db, filtered by nothing; main has hundreds of tasks).

Datahike gotcha reproduced (same one `seon.task/ready`'s docstring notes): a `not-join`/`not` over a rule call whose variable is
bound only by another rule call fails with `Insufficient bindings: none of #{?t} is bound`. Fix: bind the variable with a
plain clause first (`[?t :seon.task/id]`, `[?p :seon.task/id]`), and put the "predecessor unfinished" test in its own rule
(`waiting`) so the negation is `(not (waiting ?t))`. A `not-join [?t]` containing a rule call did not work even so.

## Recommended tx shape

One `seon.db/transact!` of N plain entity maps with negative-int tempids, `:seon.task/next` as a tempid ref, defaults, template
values and overrides merged in a pure function (section 4). Reasons: the checked writer takes tempids as is (section 1/2), it is
order-free, it does one write, and it costs about 4 ms and 2.4 MB for three tasks. Do not route through `adopt-tasks-tx`
unchanged: it strips `:db/id`, so tempid refs dangle; if that path is kept, add `:seon.task/next` to `tree-attributes` so
`refs-last` moves it to trailing `:db/add`s with lookup refs.

```clojure
[{:db/id -1 :seon.task/ns 'work :seon.task/name "design-fix" :seon.task/id "..." :seon.task/sym work/design-fix
  :seon.task/plan "..." :seon.task/instructions "..." :seon.task/execution :held :seon.task/next -2 :seon.task/references #{...}}
 {:db/id -2 ... :seon.task/step :prove :seon.task/proofs #{...} :seon.task/next -3}
 {:db/id -3 ... :seon.task/step :implement}]
```

Things the new path must still own (the checked writer does not): `symbol-name?` on each name; `:needs-cycle` (and a
`next` cycle: a<->b via tempids was accepted by `seon.db/with`, so a `next`-cycle check is needed or a tx fn); id = `task-handle`
of [ns name] (a wrong id is accepted); derived `:references`; `:created-by`. Execution: `held-tx`'s rule is "a root is :held,
a child inherits"; with a flat `next` chain each task would need its own `:execution`, so decide whether the first task alone is
`:running`-able (inferred, not run). Defaults: declare `{:default ...}` on the schema members and decode with
`mt/default-value-transformer {::mt/add-optional-keys true}` (section 3), otherwise keep the one constant map.

Not reproduced (inferred): a real publish of `:seon.task/next` (schema resource + reload), `obligations`-derived proofs on the
copies, behavior of `ready`/`assign!` for flat `next` chains, a committed (non-`with`) write. No branch was created, so none to delete.
