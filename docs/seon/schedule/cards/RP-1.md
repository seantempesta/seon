---
title: "Card RP-1: Unmapping a called function stays unmapped"
type: reference
status: active
created: 2026-10-08
---

# RP-1 Unmapping a called function stays unmapped

Ledger row RP-1 (alias S5-04i; task `seon.turn.batch/a-core-unmap-with-a-surviving-caller-is-silently-undone`,
group B). Ruling: "agent branches do anything"; agent-loop.md §1.1. Design: `redo/pass-and-recording.md` §1 "Refusals
no REPL makes", §2. Lane: Sonnet. Cited at HEAD `e7f120672`. Two lettered slices: **RP-1a** (the eval request's removal
facts, group B) and **RP-1b** (`my.program`'s deletion tools, group M).

## What exists

`seon.turn.batch/removal-facts` (`src/seon/turn/batch.clj:493-510`) asks `my.program`'s deletion report
(`#'my.program/deletion-report`, `src/my/program.clj:644`) and, when a referrer survives (`blocking?`, `:641`) or a
subject is a schema key, answers `my.program`'s refusal (`#'my.program/refusal`, `:625`). `form-facts` (`:512-526`)
returns that refusal, `derive-step` (`src/seon/batch.clj:543-585`) stores it as the form's `:seon.batch/facts-error`,
the Var stays unmapped in SCI but the program row stays in the database, and the next acquisition reinstalls the Var
(redesign §1: `(ns-unmap *ns* 'pa)` with caller `pb`; next eval request `(resolve 'pa)` answers the Var).
`my.program/ns-unmap!` and `remove-ns!` (`:710`, `:726`, through `retract-operation!` `:672-708`) refuse the same way
before touching anything.

## Measured at HEAD (default, agent `cards-recording-3`, 2026-10-08) and RED-1's task
- `(defn pa [] 1) (defn pb [] (pa))`, then `(ns-unmap 'rp1probe.a 'pa)`: the reply is `nil` with no error, diagnostic
  or finding; the next eval request finds the row (`{:db/id 80591}`) and `(resolve 'rp1probe.a/pa)` true: silently
  undone.
- `(defn lonely [] 1)`, then `(ns-unmap 'rp1probe.a 'lonely)`: clean here (row gone, Var gone). In RED-1's hosted run
  the same uncalled deletion was refused with "Program deletion leaves 1 surviving referrers" naming no referrer:
  task `seon.cluster.host/a-refused-deletion-reaches-the-deleting-forms-receipt`
  (`resources/seon/tasks/seon.cluster.host.edn:11`, commit `4e307ec54`). That task is folded into this row: its
  defect (a refusal misattributed or missing on the deleting form) disappears with the refusal; nobody repairs the old
  refusal path.

## The rule (decided here)

On an agent's branch a removal retracts the removed declarations and nothing else; referrers stay as they are
(dangling), and the writer reports them as `:seon.program.finding/dangling` / `:render-target` findings (RP-1w: the
one producer of integrity findings; this row adds no second one). A schema key among the affected subjects (a
namespace that owns schema declarations) is **not** retracted: SCI's `remove-ns` leaves Malli's registry as it was, so
the stored schema row still matches the agent's world. On every other branch the deletion tools keep refusing (merge
judges integrity on main). The pass's removal (`removal-facts`) never refuses on any branch: it only runs for agent
eval requests on their own branch, and RP-1w's writer refuses a dangling deletion on main anyway.

## RP-1a: the eval request's removal facts

### Files

- `src/seon/turn/batch.clj`
- `resources/seon/schemas/seon.batch.edn` (no change expected; verify `:seon.batch/facts-error`'s readers still fit)
- `test/seon/repl_differential_p8_test.clj`
- `test/seon/cluster/host_test.clj`, `test/seon/turn_test.clj`, `test/seon/cluster/evaluate_source_test.clj`
  (convert the assertions that expect the refusal; list below)

### Functions

- CHANGE `seon.turn.batch/removal-facts` (private, `:493`): contract
  `[:=> [:cat :seon.db/database-value :symbol] :seon.store/transaction-data]`. Body: when the subject is stored, take
  `subjects` from `(#'my.program/deletion-report db subject)` and answer
  `(mapv (fn [t] [:db/retractEntity (:seon.program/identity (#'my.program/locate db t))]) (sort-by str (filter symbol? subjects)))`.
  Delete the `blocking?`/`refusal` branch. Docstring: "Retraction of a removed Var's or namespace's stored
  declarations on `db`; referrers are left as they are (the writer reports them as findings). A schema key a removed
  namespace owns stays, as SCI's registry keeps it."
- CHANGE `seon.turn.batch/form-facts` (`:512`): contract loses the `:seon.program/mutation-refused-error` branch
  (`[:=> [:cat :seon.batch/request :seon.db/database-value :seon.batch/change] :seon.store/transaction-data]`); delete
  `(first (filter :seon.error/at removed))`.
- CHANGE `seon.turn.batch/program-facts` (`:599`): contract keeps `:seon.error/value` (the `changed-since-basis`
  refusal, until RP-7) and drops `:seon.program/mutation-refused-error`.

### Data

```edn
;; eval request "(ns-unmap *ns* 'pa)" on the agent's branch, pb calling pa; the recording carries
[[:db/retractEntity [:seon.fn/sym rp1.a/pa]] …]
;; and its commit report (RP-1w) carries
{:seon.db/findings [{:seon.program.finding/type :seon.program.finding/dangling
                     :seon.program/subject rp1.a/pa :seon.program/relation :seon.fn/calls
                     :seon.program/referrer {:seon.fn/sym rp1.a/pb}}]}
```

### Proof

`seon.repl-differential-p8-test/a-core-unmap-with-a-surviving-caller-stays-unmapped` (new, the p3 idiom over
`seon.mcp/call`):

```clojure
(reply "(ns rp1.a) (defn pa [] 1) (defn pb [] (pa))" agent-id running)
(reply "(ns-unmap *ns* 'pa)" agent-id running)
(is (nil? (:seon.repl/value (reply "(resolve 'rp1.a/pa)" agent-id running))))
(is (nil? (:seon.repl/value (reply "(seon.db/pull (seon.db/db) [:db/id] [:seon.fn/sym 'rp1.a/pa])" agent-id running))))
(is (some? (:seon.repl/value (reply "(seon.db/pull (seon.db/db) [:db/id] [:seon.fn/sym 'rp1.a/pb])" agent-id running))))
```

and the uncalled case in the same deftest:
```clojure
(reply "(defn lonely [] 1)" agent-id running)
(let [r (reply "(ns-unmap *ns* 'lonely)" agent-id running)]
  (is (nil? (:seon.repl/error r)))
  (is (nil? (:seon.repl/diagnostics r))))
(is (nil? (:seon.repl/value (reply "(seon.db/pull (seon.db/db) [:db/id] [:seon.fn/sym 'rp1.a/lonely])" agent-id running))))
```
Both cases run as a hosted agent too (`seon.cluster.host-test`, the converted test below), which is where RED-1 saw the
misattributed refusal. The deftest carries
`^{:seon/tasks #{'seon.turn.batch/a-core-unmap-with-a-surviving-caller-is-silently-undone 'seon.cluster.host/a-refused-deletion-reaches-the-deleting-forms-receipt}}`.
The `:dangling` finding beside the dangling-caller deletion is asserted by RP-6a2's
`a-dangling-caller-is-a-finding-beside-the-value` (the FINISH stage); this row proves the state, and the uncalled
deletion's reply carries no finding there either (add that assertion to RP-6a2's proof).

Converted in the same commit (they assert today's refusal on an agent branch):
- `seon.cluster.host-test/a-refused-deletion-is-its-forms-refusal-and-the-run-closes` (`test/seon/cluster/host_test.clj:487`):
  rename to `an-unmap-with-a-surviving-caller-deletes-and-the-run-closes`; assert the deleting form's error is nil,
  the helper's row is absent after the run, siblings keep `"3"`/`"7"`, and `(refused-caller 1)` answers (a value or an error
  value: whether the caller's compiled body still holds the unmapped Var depends on reacquisition, as in Clojure, so
  assert only that the run closes with one answer).
- `rg -n 'removal-facts|mutation-refused-error|repair the named referrers' test/seon/turn_test.clj test/seon/cluster/evaluate_source_test.clj`
  lists the others; each assertion of a refusal on an agent branch becomes the deletion plus the finding-free state
  above. The task `seon.turn.batch/a-core-unmap-with-a-surviving-caller-is-silently-undone` names the new proof in its
  `:seon/tasks` metadata (`^{:seon/tasks #{'seon.turn.batch/a-core-unmap-with-a-surviving-caller-is-silently-undone}}`).

### Reaching tests

`seon.repl-differential-p8-test`, `seon.cluster.host-test`, `seon.turn-test`, `seon.cluster.evaluate-source-test`,
`seon.turn.batch-test`.

### Cost

Expected: one fewer branch per removal; `deletion-report` is still read once per removed subject (follows its
referrers). No change to O beyond noise; report the probe's unmap row (redesign §1: 100 ms / 171 MB).

### Deleted

`removal-facts`' refusal branch; the mutation-refused branch of two contracts.

### Commit

`bin/lane commit <lane> -m "RP-1a: an unmap or namespace removal on an agent branch deletes its declarations; referrers dangle as writer findings" --test seon.repl-differential-p8-test --test seon.cluster.host-test --test seon.turn-test --test seon.cluster.evaluate-source-test src/seon/turn/batch.clj test/seon/repl_differential_p8_test.clj test/seon/cluster/host_test.clj test/seon/turn_test.clj test/seon/cluster/evaluate_source_test.clj`

## RP-1b: `my.program`'s deletion tools on an agent branch

### Files

- `src/my/program.clj`
- `test/my/program_test.clj`, `test/seon/cluster/host_test.clj`

### Functions

- CHANGE `my.program/retract-operation!` (private, `:672`), contract unchanged (its success value
  `:seon.program/change` gains an optional `:seon.db/findings`; add
  `[:seon.db/findings {:optional true} [:vector :seon.program.finding/any]]` to `:seon.program/change` in its schema
  file, `rg -n ':change \[' resources/seon/schemas/seon.program.edn`). When `(db/agent-branch? database)` (RP-1w):
  skip the `blocking?` refusal both in the pre-read and inside the `:db.fn/call`, retract only the symbol subjects
  (`(filter symbol? subjects)`), run `native!` as today, and answer the change with
  `(:seon.db/findings transaction)` when present. Otherwise unchanged.
- The docstrings of `ns-unmap!` (`:710`) and `remove-ns!` (`:726`) gain one sentence: "On my own branch it always
  deletes; surviving referrers come back as findings."

### Proof

`my.program-test/ns-unmap-on-an-agent-branch-deletes-and-names-the-dangling-caller` (new, through `seon.mcp/call`):
define `pa`, `pb` calling it; `(my.program/ns-unmap! 'rp1b.a/pa)` answers a change whose `:seon.db/findings` holds one
`:dangling` naming `rp1b.a/pb`; the row is absent. Convert
`seon.cluster.host-test/a-refused-deletion-names-its-surviving-caller-and-a-plan-proposes-only-installed-forms`
(`:650`) to assert that finding instead of `:seon.error/offending`.

### Commit

`bin/lane commit <lane> -m "RP-1b: my.program deletion tools delete on an agent branch and answer findings" --test my.program-test --test seon.cluster.host-test src/my/program.clj resources/seon/schemas/seon.program.edn test/my/program_test.clj test/seon/cluster/host_test.clj`

## Dependencies

RP-1w (both slices: the findings and `seon.db/agent-branch?`). RP-1a also EN-8a in the ledger (one RECORD owner);
RP-1a does not need it to be correct (the removal facts are the same in both recording owners), so RP-1a may land
before EN-8a if group B is free. RP-1b: `src/my/program.clj` is held by led-nk1 at writing; serialize with group M.

## Reset needs

None.
