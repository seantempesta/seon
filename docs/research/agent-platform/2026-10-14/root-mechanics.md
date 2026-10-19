# Root mechanics for TASK-CHAIN: chain reads, the advance, `update!`

Evidence run 2026-10-14 against `default` (HEAD 0ede2a0c5 loaded), JVM mode as `root` (JVM mode refuses any other agent
id, so no agent-named branch was created). Disposable branches `:rm-chain-0..19` and `:rm-update` (plus their
`:base/…`) were forked with `seon.cluster.registry/fork!` and retired with `retire-branch!`; confirmed gone. Nothing was
transacted to main; the only main writes are the eval tool's own run records. Branch data was written with raw
`datahike.api/transact` on the disposable branches (schema `:seon.task/next` added there: it is not declared today;
`:seon.chain/branch` and `:seon.chain/first` were added only on a `d/with` value). Timings are warm JVM, small data.

The store value is reachable at `(:seon.store/store (get @seon.operator.runtime/running-instances "default"))`; the
cluster branch is `:cluster-default`.

## 1. Root's per-pass read of chains

Chain = 3 tasks linked by `:seon.task/next` (ref, card. one) on each branch, first `:running`. Reading each branch:

```clojure
(db/branch-database main branch)            ; no connection; (db/branch-connection main branch) => nil, checked for all 20
;; pull [:seon.task/id :seon.task/execution {:seon.task/next [:seon.task/id]}] [:seon.task/id id], walk next,
;; open attempt = [?a :seon.task/attempt-of ?t] (not [?a :seon.task/outcome _]); first :running task without one
(source/release! [head])                    ; finally
```

Result per branch: `{:path ["rmc0a" "rmc0b" "rmc0c"] :due "rmc0a"}`.

| Branches | Uncached pass, 1st run | Uncached pass, repeat | Cached by head commit id (warm) |
|---|---|---|---|
| 1 | 1.7 ms, 1.9 MB | 0.45 ms, 1.4 MB | ~1 ms |
| 5 | 4.2 ms, 8.7 MB | 2.2 ms, 6.8 MB | |
| 20 | 13.9 ms, 35 MB | 8.7 ms, 27 MB | 1.7-2.4 ms, 7.4 MB |

So about 0.43-0.7 ms and 1.4-1.8 MB per branch uncached. "Cold" here means first read after the branches were just
written (store cache warm); a truly cold store (after restart) was not measured (not reproducible without a restart).

Caching by head commit id: the key is `(seon.cluster.registry/branch-commit-id {:seon.store/store S :seon.store/branch b})`,
a head-record read costing 0.085 ms and 0.37 MB per branch (20 branches: 1.7 ms, 7.4 MB). A warm cached pass over 20
chains is that cost alone (1.7-2.4 ms), about 5x cheaper than the uncached 8.7 ms and it is dominated by the id read
itself, not by cache lookups. After one write to one branch the next pass was 2.8 ms: exactly one miss (about 1 ms).
Not "nearly free", but linear in chains at 0.1 ms each and 3x less allocation than the uncached read. The stable
alternative is a root-held per-pass fold with no cache at all: 20 chains cost 9-14 ms, well inside any pass budget, so
the cache is optional for the expected chain counts (inferred: a cache pays only above roughly 100 live chains).

Two surprises:
- `db/branch-database` returns a materialized value that must be released (`seon.cluster.source/release!`); the draft
  step 2 does not say so. Every existing caller (`branch-end`, `branch-admission-end`, `close!`) releases in `finally`.
- An open attempt must be defined as "no `:seon.task/outcome`", not "no `:seon.task.attempt/end`": a bailed attempt
  has an end and no outcome until root closes it, and step 7 must not start another agent for it. The draft's
  "`:running` task with no open attempt" holds only with that definition.

### What a chain fact must look like to be a claim

`seon.cluster.registry/cluster-claims` (private) returns, for a cluster branch only (a branch whose own
`:seon.cluster/name` row names it), `#{branch}` plus every `:seon.agent/branch` of an agent row without
`:seon.agent/admission-closed-tx`. Today's answer for `:cluster-default`: 4 branches, none of mine. A fact
`{:seon.chain/first "…" :seon.chain/branch :rm-chain-0}` counts as a claim only if `cluster-claims` queries it:

```clojure
'[:find [?b ...] :where [_ :seon.chain/branch ?b]]    ; into the returned set
```

Verified on a `d/with` value of main (schema added, two facts): the query answers `#{:rm-chain-0 :rm-chain-1}`.
`retire-unclaimed!` then holds them and, through `held (into held (map base-branch) held)`, their `:base/…` branches;
without the fact all 20 chains and their 20 bases were in the `unclaimed` set (boot would have retired them, so a
chain branch is not safe today). Forks inherit the main fact but `cluster-claims` returns nil for a non-cluster
branch, so a chain branch carrying a copy of the fact (any branch forked after the fact) claims nothing extra.

## 2. The advance as a writer decision

Pattern copied from `seon.task/adopt-tasks-tx` (`[:db.fn/call #'adopt-tasks-tx entities handles basis]`, a function of
the held `database` plus plain-data arguments, throwing or returning tx data). Prototype (`user/advance-tx`, in the
JVM only):

```clojure
(defn advance-tx [database key next-id]      ; key: the attempt's :seon.task/assignment
  (let [[[attempt task outcome]] (seq (d/q '[:find ?a ?t ?o :in $ ?k
                                             :where [?a :seon.task/assignment ?k] [?a :seon.task/attempt-of ?t]
                                                    [(get-else $ ?a :seon.task/outcome :none) ?o]] database key))]
    (cond (nil? attempt)    (throw (ex-info "no such attempt" {:seon.task/assignment key}))
          (not= :none outcome) []                                   ; already advanced: derive nothing
          :else (let [finished (d/pull database flowing task)       ; flowing = #{:seon.task/intent} standing in for plan
                      next-e   (:db/id (d/pull database [:db/id] [:seon.task/id next-id]))
                      merged   (#'seon.task/with-references (merge (d/pull database flowing next-e) finished))]
                  (into [[:db/add attempt :seon.task/outcome :done]
                         [:db/add next-e :seon.task/execution :running]]
                        (concat (for [[k v] (select-keys merged flowing)] [:db/add next-e k v])
                                (for [r (:seon.task/references merged)] [:db/add next-e :seon.task/references r])))))))
;; applied as [:db.fn/call advance-tx "rm-att-1" "rmc0b"] on a d/with value
```

Results on a `d/with` value of `:rm-chain-0` (attempt `rm-att-1` on task A, A's plan "plan A, designed `seon.db/q`"):

| Application | Datoms in tx-data | ms |
|---|---|---|
| 1st (attempt `rm-att-1`) | 5: outcome, B `:running`, B plan, B reference, tx instant | 7.2 (first call, JIT), 0.2-0.6 warm |
| 2nd, same call on the result | 1 (only Datahike's `:db/txInstant`; no task datom) | 0.24 |
| Rerun: A's plan revised, new attempt `rm-att-2`, same call shape | 4 | 0.63 |
| Rerun again | 1 | 0.09 |

After the 1st: attempt outcome `:done`; B = `{:intent "plan A, designed `seon.db/q`", :execution :running, :references [seon.db/q]}`.
After the rerun: attempt 1 still `:done`, attempt 2 `:done`, B carries "plan A revised, see `seon.db/with`" and is
`:running` again; A was not touched (`:running`). "Writes nothing" is a transaction with only the transaction-instant
datom; a root caller that wants "wrote nothing" should test `(<= (count tx-data) 1)` or, better, call the pure
function first as `task-call` does (`(empty? (adopt-tasks-tx …))`).

Surprises and defects of the prototype that the real design must handle:
- References went stale: after the rerun B held `[seon.db/q seon.db/with]` although the revised plan cites only
  `with`. `:seon.task/references` is cardinality many, so `[:db/add]` accumulates. The real advance must write through
  the task writer's replacement (`seon.task/replacement-tx`, which retracted `q` correctly in the `update!` runs below),
  not hand `:db/add`s. This is the concrete reason step 5 says "through the task writer".
- The function should take only the attempt key: the next task is the finished task's own `:seon.task/next`
  (`[?t :seon.task/next ?n]`), read inside the writer. Passing `next-id` allows a wrong id; deriving it makes the
  stale/wrong id unwritable (matches §2.1's "nothing checks them"). No next means the task is last: write only the
  outcome; step 6 then merges (inferred, not prototyped).
- "Finished value winning" needs the rule for an absent finished member: the prototype keeps the next task's own value
  (`merge next finished`). State it in §1.
- The flowing set is data, not a constant: it is the declared members whose schema lacks `:seon.task/owned`
  (and are not identity, link or control). `seon.task/declaration-members` already lists declared members in schema
  order (today `ns name step intent instructions children needs product proofs`), so deriving it needs one schema
  property read, not code per member (inferred, not prototyped).
- A running A and a rerun need no special case: B was `:running` with a `:done` attempt, and the new advance made a
  new open-attempt state, which the pass starts as a new agent.

## 3. `update!` with update semantics

Prototype (`user/update!`, JVM only), on `:rm-update` forked from `:cluster-default` (53.5 ms), connection opened with
`store/open-branch!`, state carried with `db/carry-connection-projection-state!` copied from main's connection
metadata, calls under `db/with-branch`:

```clojure
(defn read-member [conn sym k f args]     ; handle by [:seon.task/sym sym]; held = (get (task/task-basis db {"resources/seon/tasks/<ns>.edn" "[]"}) handle)
  … {:handle h :held declaration :old (get held k) :new (apply f old args)})
(defn write-member [conn agent-id sym k {:keys [handle held new]}]
  ;; validate: (m/explain schema-of-k new {:registry (:seon.schema.projection/registry projection)}), schema from
  ;; (m/children (m/deref-all (mr/schema registry :seon.task/declaration)))
  ;; write:   entity = (#'task/task-entity (assoc held k new) agent-id)
  ;;          tx = (#'task/refusal-data #(#'task/task-call conn entity {handle held})) ; basis = the read declaration
  ;;          (#'task/written conn tx)
  )
```

`task-call` is exactly what `create!` and `refine!` use: it builds `[[:db.fn/call #'adopt-tasks-tx [entity] handles basis]
[:db.fn/call #'advance-assignments-tx entity]]`, so the basis is checked twice (once before, once inside the writer).
`task-declaration` (public, per handle) is a better read than `task-basis` with a fake file name, which `refine!` uses
today (inferred: `task-basis` is keyed by task files, a wart for runtime chain tasks).

Real writer results (task created first with `seon.task/create!` on the branch: 44 ms, 52 MB):

| Case | Form | Result | ms |
|---|---|---|---|
| `into` on a set | `(update! c "root" sym :seon.task/proofs into '[rm.update-test/d])` | proofs gained `d`, `{:changed? true}` | 33 (21 MB) |
| same again | `into '[…/b]` already present | `{:changed? false}`, no tx | 7.5-9 |
| string edit | `(update! … :seon.task/intent edit-text "fix `seon.db/q`" "fix `seon.db/with` and `seon.merge/merge-tx`")` | intent edited; `:references` re-derived to `[seon.db/with seon.merge/merge-tx seon.ref/names]` (`q` retracted); `:execution` stayed `:running` | 36 (20 MB) |
| stale basis | read proofs, another `update!` adds `f`, then write the read result | `{:seon.task/refused :revision-conflict :seon.task/base … :seon.task/current {proofs incl. f} :seon.task/proposed …}`, no new commit | 4.6 |
| stale across members | read `intent`, another `update!` changes `proofs`, write `intent` | also `:revision-conflict` | 36 total |
| invalid value | `(update! … :seon.task/proofs conj "not a symbol")` | refused before writing: `{:refused :invalid-value :explain {:path [0 0] :schema :symbol}}`, commit id unchanged | 3.1 |
| invalid value | `(update! … :seon.task/intent (constantly ""))` | refused: `[:string {:min 1 …}]`, commit id unchanged | 3.1 |

Surprises that change §3a:
1. The stale refusal carries `:seon.task/refused :revision-conflict`, `:seon.error/at`, `:seon.error/operation`,
   `:seon.task/holder` but no `:seon.error/type`; the draft calls it `:seon.task/conflict-error` and "refused with the
   current value". The current value is there (`:seon.task/current`) but whole-declaration, not the member.
2. The basis is the whole declaration, so a concurrent change to another member also conflicts (verified). The draft
   says "a member changed in between is refused"; the true behavior is stricter. Decide: keep (simple, one writer) and
   say "the task changed in between", or compare the member alone (more code in the writer; not recommended).
3. `seon.edit/exact` is not a string core: its contract needs a request with `:my.edit/path` and
   `:my.edit/expected-digest` (calls without them fail at the contract), and it returns a candidate map with
   `:seon.edit/source` plus byte/line/context evidence (`context-byte-limit` argument) and error maps with
   `:seon.error/offending` and a `:my.edit/path` member. `my.edit/text` therefore has to be a new pure function that
   `exact` calls (positions, `replace-occurrences`), as §3a says; a wrapper that fakes a path/digest works (used here)
   but is not the design.
4. When `f` returns an error map (no match) the prototype validated it as the new value and answered
   `:invalid-value`, hiding `:my.edit/no-match-error`. Add to §3a: if `f`'s answer is an error value
   (`:seon.error/at`), `update!` returns it unchanged and writes nothing.
5. `:execution` survives an update of a running task (task-entity's `:held` default is dropped by
   `replacement-tx`; execution is not a declaration member). Good, but a deftest should pin it because a chain task is
   `:running` while its agent edits `proofs`.
6. The member schema for `intent` still describes "the goal every step of a chain shares"; the rename to `plan` must
   rewrite that description (agents see it in `:invalid-value` explains).

## Concrete edits

TASK-CHAIN §1, "The chain fact on main": add that `seon.cluster.registry/cluster-claims` gains the query
`[:find [?b ...] :where [_ :seon.chain/branch ?b]]` over the cluster branch's database, unioned into the claim set,
and that `retire-unclaimed!` then keeps `:base/<branch>` through its existing `base-branch` rule; chain branches are
retired at boot today (40 of 40 test branches/bases were unclaimed). Add that a fact copied into a fork claims nothing
(only a cluster branch is asked).

TASK-CHAIN §2 step 2: state that the read releases its value (`seon.cluster.source/release!`), define an open attempt
as "no `:seon.task/outcome`", and replace "cached by head commit id" with the measured shape: head id
`seon.cluster.registry/branch-commit-id` is 0.085 ms/branch, an uncached chain read 0.45-0.7 ms and 1.4-1.8 MB; 20
chains cost 9-14 ms uncached and 2 ms cached, so cache by head id (a `seon.cache` kind keyed by `[branch head-id]`) or
omit it; do not claim "free".

TASK-CHAIN §2 step 5 / §1 writer: the advance is `[:db.fn/call #'seon.task/advance-tx attempt-key]` (one argument; the
next task is read from the finished task's `:seon.task/next` inside the writer), it returns `[]` when the attempt has
an outcome, and it goes through the task writer's replacement so references are retracted and re-derived (the
`:db/add` prototype accumulated a stale reference). Define: flowing = declared members without `:seon.task/owned`
that are not identity, link or control; an absent finished member leaves the next task's own value. Measured: 0.2-0.6 ms
warm, and 0.1-0.24 ms for the repeat (a tx holding only the transaction instant). A repeated pass should test for the
empty result before transacting, as `task-call` does, so it commits nothing.

TASK-CHAIN §3a: say the basis is the task's whole declaration (any member changed since the read conflicts), the refusal
is `:seon.task/refused :revision-conflict` with `:seon.task/base`, `:current`, `:proposed` (add `:seon.error/type` to
make `:seon.task/conflict-error` a real variant, or name the existing shape); an `f` that answers an error value
returns it unchanged; read the declaration with `seon.task/task-declaration` (not `task-basis` with a file name);
validation uses the member schema under the projection registry and refuses before writing (3 ms, commit id unchanged).
Costs: a changing `update!` 33-36 ms and about 20 MB, a no-change one 7.5-9 ms, a refusal 3-5 ms.
`my.edit/text` is a new pure function in `seon.edit` that `exact` calls; `exact`'s current contract (path, digest,
context window) is not the string core.

## Verification limits

Raw `d/transact` built the chain data (not the task writer), so the chain read measures the read path on small data;
main's inherited data affects only index depth. The advance prototype and `update!` run in the JVM, not in an agent's
SCI context with its instrumentation and reply overhead (the `update!` figures are the writer's, without eval-request
recording). Cold-store timings, a real `chain!` durable create, and the `cluster-claims` change against the live
`retire-unclaimed!` (only its set arithmetic was run, read-only) were not run. Items marked "inferred" are not
prototyped.
