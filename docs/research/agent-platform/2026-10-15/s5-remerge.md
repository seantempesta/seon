---
type: research
title: "s5-remerge: S5's declarations re-applied through one :outside chain task, merged and published"
status: complete (lane s5-remerge, 2026-10-15, default pid 46001 on 2a246886e; publication 48528db79)
---

# s5-remerge: S5's change reaches main through the ordinary path

Default on 2a246886e, `:seon.config.ai/no-provider` true on the new chain branch (read there, no model call possible).
No lane held `src/seon/error.clj` or `test/seon/error_test.clj` (`bin/lane status`: no live claims).

## Result

- Chain `:chain-90f79da9f61f`, task `seon.error/an-unresolved-alias-name-offers-its-nearest-core-name-redo`
  (handle `90f79da9f61f`), agent `attempt/04a402abac2a`, worked through MCP `eval_clj` in SCI mode.
- The attempt ended `:done` and root merged it with no hand act: assignment `04a402abac2a`,
  `:seon.task/outcome :merged`, `:seon.task/merged-declarations 10`, merge commit
  `#uuid "6ad09c58-f416-5b63-9e0e-3f29b9c82cf4"`, publication `48528db79` ("Merge assignment 04a402abac2a ..."),
  touching `src/seon/error.clj` (+81 -5) and `test/seon/error_test.clj` (+25).
- Main holds all eight S5 declarations at the S5 commit's definition digests (six new functions, the changed
  `seon.error/nearest-calls`, the test). `my.program/source` of the task shows its proof `:pass`.
- Wall time: chain written 09:25:4x UTC, two agent eval requests, publication 09:26:53 UTC (about 70 s end to end,
  about 10 s from the proof's green eval request to the git commit).

## Step 1: root setup (JVM mode)

```clojure
(def conn (seon.cluster.boot/connection "default"))
(def s5 (datahike.api/commit-as-db conn #uuid "6ad09661-834c-542e-8e7c-871c333a5277"))
(def inst (get @seon.operator.runtime/running-instances "default"))
(def tmpl (seon.task/template (datahike.api/db conn) 'seon.step.implement/template))
(seon.task/chain! {:seon.db/connection conn :seon.store/store (:seon.store/store inst)}
                  [(merge tmpl {:seon.task/ns 'seon.error
                                :seon.task/name "an-unresolved-alias-name-offers-its-nearest-core-name-redo"
                                :seon.task/plan plan :seon.task/model :outside
                                :seon.task/proofs #{'seon.error-test/an-unresolved-alias-name-is-answered-with-its-nearest-core-name}})])
;; => {:seon.chain/first "90f79da9f61f", :seon.chain/branch :chain-90f79da9f61f, ...}  99 ms
```

- A proof naming a test the branch has not defined yet is accepted by `chain!` and the task writer. The block shows
  it `:pending` with `:seon.ref/problem-kind :unresolved`, the start test step answers
  `:seon.test/selection-refusal :seon.test/identity-unresolved` ("define it there"), and the done rule refuses with
  "Every proof needs a current recorded result". So no filed-task detour is needed for a new proof.
- The S5 delta was read by definition digest, S5 commit against main: the eight declarations above, the
  `seon.error` ns form (two added requires, `[seon.config :as config]` and `[clojure.pprint :as pp]`; the
  `seon.error-test` ns form is identical), plus `seon.error/config-expectation-present-generator`, whose source is
  identical and whose digest moves with the ns form. Everything else that differed is main's newer work.
- Root's reconciler started `attempt/04a402abac2a` on the chain branch within 4 s
  (`:seon.task/attempts-made 1`, `:execution :running`).

## Step 2: as the :outside agent (SCI, `attempt/04a402abac2a`)

| # | Eval request | Request ms / alloc | Notes |
|---|---|---|---|
| 1 | `(seon.context/current-state)` | 399 / 747 MB | 4,248 tokens; proof `:pending`, every new name a must-read `:unresolved`, start `(in-ns 'seon.error)` |
| 2 | in `seon.error`: the S5 ns form, then `core-namespace`, `library-names`, `library-calling-form`, `alias-target`, `alias-candidates`, `candidate-calling-form`, `nearest-calls`, each the S5 commit's source unchanged | 5,434 / 6.2 GB | each form 4-56 ms; reaching tests green; the platform tier named `seon.error/config-expectation-present-generator` host-bound with `(my.test/check-platform {})`; the proof still `:identity-unresolved` |
| 3 | in `seon.error-test`: the S5 test's source unchanged | 1,356 / 2.2 GB | reaching green; the reply named no proof result |
| 4 | `(seon.context/current-state)` | 4 | `:seon.turn/refused-error` "run transition refused: admission-closed": the task had ended done and been merged |

Sources were taken from the commit in JVM mode (`:seon.fn/source`, `:seon.test/source`, `:seon.ns/source` on
`s5`) and sent verbatim; dependency order came from each S5 entity's `:seon.fn/calls`.

## Steps 3 and 4: root's merge and verification

No gate refusal. Root's `db<->flow` pass merged the done chain from the chain branch, published `48528db79`,
retracted the chain fact (main tx 536873593) and deleted the branch. Verified on main: the eight digests equal the
S5 commit's; the assignment entity reads `:merged` with its git commit; `git log` shows the publication commit.
The default JVM does not load the change until root's next reload (`(resolve 'seon.error/alias-candidates)` nil).

## Frictions and findings

1. **`:seon.task/model` is stringified again by each merge.** On the chain branch the raw pull reads `":outside"`
   (outside-1 finding 8); on main after the merge it reads `"\":outside\""`. The merge re-prints a string-held value,
   so each merge adds a layer of quoting (AGENTS "Preserve native values"; the stored value should be the keyword).
2. **The merged task reads `:seon.task/execution :running` on main** while its assignment reads `:done` /
   `:merged`; the assignment's `:seon.task/agent` names an agent entity main does not hold.
3. **A merged chain vanishes from `/chains`** (its chain fact is retracted), so "the chain reads merged" is visible
   only on the assignment entity; `my.root/ended` and `my.root/merge-requests` do not show it either.
4. **`my.root/merge-requests` still lists S5's old attempts** (`04ef4a5f23d3`, `0653826d8ff7`, `0ffc3778f3a4`, ...)
   as open merge requests on the deleted `:merge-3298699a379e`, with `my.root/merge!` forms that cannot work.
5. **Write-back reordered the ns requires.** The agent evaluated `... [seon.config :as config] [clojure.pprint :as pp]))`;
   the published file and main's `:seon.ns/source` hold `[clojure.pprint :as pp] [seon.config :as config]`, so the ns
   entity's digest differs from S5's. It also placed the new definitions in an order other than evaluation order.
6. **S5's change carries two lint warnings, now on HEAD**: `clojure.pprint` required but never used
   (`src/seon/error.clj` ns form), and `seon.error/calling-forms` an unused private var (S5's `nearest-calls` no longer
   calls it). The gate merged with them; re-applied verbatim per the assignment, not fixed here.
7. **The ns form's re-evaluation cost 5.4 s and 6.2 GB** in one eval request (the forms themselves took 4-56 ms):
   over one second, unattributed in the reply beyond `:calls 1399527`.
8. **The proof's green result was not in any reply.** Request 3's reply carried only the reaching tier; the next
   reply was the admission-closed refusal. An outside agent learns its task ended done only by that refusal, whose
   message names neither the end nor the merge.
9. Root-side lookup guesses: `:seon.task/status`, `:seon.task/running`, `:seon.agent/task` are not attributes
   (raw pulls refused them); the attempt is found through `:seon.task/assignment` on main.

## For root

- RESET NEEDED: no. Default healthy; the change is published but not loaded (root's next batched reload).
- Tasks to file: items 1, 2, 4, 5, 6 (lint at the gate), 7 and 8 above.
