---
type: research
status: complete
tags: [bootstrap, agents, experiment, rendering]
---

# Bootstrap baseline: shipped vector versus help-only discovery seed

## Verdict

The complete 100-attempt matrix ran successfully: two arms, five objectives,
and ten fresh attempts per objective. Arm A used the shipped vector; Arm B
used only form 0, `(help)`, with its context prose. The raw reports and every
database workspace are under the fresh, uniquely named root
[`tmp/bootstrap-drives-rerun-20260805T000202Z/`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/).

Arm A won 8/10 O1 attempts, 10/10 O2 attempts, 2/10 O3 attempts, and 0/10
O4 and O5 attempts. Arm B won 10/10 O1 attempts, 0/10 O2 attempts, 3/10 O3
attempts, and 0/10 O4 and O5 attempts. O4 reproduced its pre-deletion 0/10
result on all four predicates in both arms.

## Grounding and candidate vectors

I read
[`docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md`](../plan/bootstrap-vector-design-2026-08-01.md)
and `src/seon/bootstrap_drive.clj` end to end before implementing and running
the original experiment, and I reread `src/seon/bootstrap_drive.clj` end to
end before this successful rerun. I also read the replaced blocked report and
the live-root issue end to end before updating them.

The shipped resource contains 13 database plan forms. The design's 14-entry
description includes the separate banner plus those 13 forms. Arm A supplied
all 13 resource forms through the landed candidate-vector request. Arm B
supplied a one-element vector containing the exact first form map, including
`:seon.bootstrap.plan.form/context`.

Every objective batch used `:runs 10`, the default run cap of 6,
`deepseek-v4-flash`, and `:seon.config.ai/thinking :disabled`. Each attempt
created fresh agents; each objective batch used a distinct database workspace
under its arm directory.

## Platform state

The following fixes were live in the source bytes loaded by every drive:

- `dbef794ab` plus Datahike fork `c15272730e74fb3f8bba91f6361c268492a99ba7`
  bounded expected-rejection logging.
- `e34eea186`, `aaaaf856b`, `e35e7b27f`, and `de8e32c11` supplied the landed
  universal-floor work: structural nested rendering, database identity faces,
  count-and-requery elisions, and profile composition.
- `3a6264724` fixed eval namespace assignment; `6087e1fd1` closed its issue.
- `7cfb2435f` ordered messages by numeric ordinal facts; `38660235e` closed
  the corresponding issue.
- `5e5f28fb1` fixed transcript rendering's time-limit config key,
  `8763b4b17` fixed bootstrap declared-content comparison, and `2e6f1344e`
  made same-instant rendered order equal plan order.

Launch HEAD was `dbef794ab`. During the matrix, `ce099ce79` committed the
already-present open-map worktree bytes and later commits changed only docs or
tests relevant to this lane. The experiment inputs did not change: their
SHA-256 values were identical before and after all 100 attempts:

| Input | SHA-256 |
|---|---|
| `resources/seon/bootstrap.edn` | `91b94dc65ee5605ca952303c2a94ba2818bae6718f1e98b96bb3b366d7b25e40` |
| `src/seon/bootstrap_drive.clj` | `1f012b31d09b20c3b3110aa5617369a3581089cab33b34405e155f3b652dae5e` |
| `src/seon/eval/drive.clj` | `af3174d5e3782356e1786d4566618d6ea97f9c913a20f0406579e98420cb58a7` |
| `config/default.edn` | `da66cfaf10273d95b3776b4626bfa432bee474b3600385896df3eedd392c6b7b` |

## Objective by arm by predicate

Each cell is passing attempts out of 10. “Winner” means every predicate for
that objective passed in the same attempt.

| Objective | Arm | Predicate results | Winners |
|---|---|---|---:|
| O1 | A — shipped | P1a 8/10; P1b 8/10; P1c 8/10 | 8/10 |
| O1 | B — help only | P1a 10/10; P1b 10/10; P1c 10/10 | 10/10 |
| O2 | A — shipped | P2a 10/10; P2b 10/10 | 10/10 |
| O2 | B — help only | P2a 0/10; P2b 0/10 | 0/10 |
| O3 | A — shipped | P3 2/10 | 2/10 |
| O3 | B — help only | P3 3/10 | 3/10 |
| O4 | A — shipped | P4a 0/10; P4b 0/10; P4c 0/10; P4d 0/10 | 0/10 |
| O4 | B — help only | P4a 0/10; P4b 0/10; P4c 0/10; P4d 0/10 | 0/10 |
| O5 | A — shipped | P5 0/10 | 0/10 |
| O5 | B — help only | P5 0/10 | 0/10 |

The complete attempt reports, including model settings, provider attempt
facts, grades, receipts, and transcripts, are in:

- Arm A:
  [`tmp/bootstrap-drives-rerun-20260805T000202Z/arm-a/reports/`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-a/reports/)
- Arm B:
  [`tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/)

Files are named `oN-attempt-id.edn`, so each table row maps directly to ten
raw reports. O4 raw reports additionally carry
`:seon.bootstrap-drive/transcripts`, keyed by both agent IDs.

## Tokens, provider attempts, and cost

Cost uses the configured off-peak Flash rates: $0.14/M cache-miss input,
$0.0028/M cache-hit input, and $0.28/M output.

| Objective | Arm | Provider attempts | Usage docs | Input miss | Input hit | Output | Total | USD |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| O1 | A | 10 | 9 | 279,670 | 0 | 36,077 | 315,747 | $0.049255 |
| O2 | A | 10 | 10 | 284,751 | 0 | 18,592 | 303,343 | $0.045071 |
| O3 | A | 10 | 7 | 208,792 | 0 | 53,628 | 262,420 | $0.044247 |
| O4 | A | 10 | 10 | 386,304 | 0 | 8,155 | 394,459 | $0.056366 |
| O5 | A | 10 | 10 | 291,861 | 0 | 31,079 | 322,940 | $0.049563 |
| **Arm A** |  | **50** | **46** | **1,451,378** | **0** | **147,531** | **1,598,909** | **$0.244502** |
| O1 | B | 10 | 10 | 220,167 | 0 | 34,474 | 254,641 | $0.040476 |
| O2 | B | 10 | 7 | 141,707 | 0 | 62,499 | 204,206 | $0.037339 |
| O3 | B | 10 | 10 | 213,142 | 0 | 66,531 | 279,673 | $0.048469 |
| O4 | B | 10 | 10 | 237,965 | 0 | 5,722 | 243,687 | $0.034917 |
| O5 | B | 10 | 10 | 205,625 | 0 | 37,817 | 243,442 | $0.039376 |
| **Arm B** |  | **50** | **47** | **1,018,606** | **0** | **207,043** | **1,225,649** | **$0.200577** |
| **Total** |  | **100** | **93** | **2,469,984** | **0** | **354,574** | **2,824,558** | **$0.445078** |

Seven attempts have no provider usage document, so $0.445078 is the exact
cost computed from recorded usage, not a claim about unrecorded billing. Eight
attempt facts recorded `:seon.ai/unparseable-body` after HTTP 200 responses
whose bodies ended as `closed`; one of those eight retained usage and seven
did not. No HTTP 402 occurred.

The first provider attempt was at 00:03:23 UTC and the last at 00:32:22 UTC.
The whole matrix therefore ran outside the stated 2× windows of 01:00–04:00
and 06:00–10:00 UTC.

## O4 reproduced transcript pair

This is the complete Arm B attempt 1 pair from
[`tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o4-1-c81f62d8.edn`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o4-1-c81f62d8.edn).
Its grade was P4a false, P4b false, P4c false, P4d false.

### Main agent

```text
my.agents.bootstrap-o4-1-c81f62d8=> (help)
You are an agent in a Seon cluster. This is a real Clojure REPL and it is
yours: what you evaluate here runs, and what you define here stays.

Your reply is read as Clojure forms and evaluated in order, one at a time,
in your own namespace. Everything a form prints and everything it returns
comes back to you as the session you are reading now. Round trips are
expensive, so plan the most forms you can usefully run together and send
them in one reply; when a later form depends on what an earlier one
returned, split the batch there.

The session displays each form followed by its actual computed value, like a
real REPL. You may write `;;` comments before your own forms; comments are
input-side notes, never modeled results or annotations around output.

The cluster is one graph database. `seon.db/q` and `seon.db/pull` read it
at the current basis, `dir` lists a namespace, `doc` explains a function
from the graph's own facts. Every function in this cluster is callable by
you.

A `defn` with a complete `:malli/schema` becomes a durable fact other
agents can find and call; without one it lives only in this session. The
contract is checked, so write it honestly: declared map keys are rigorously
validated, extra keys are ignored, and a return may not be a bare
`[:maybe ...]`.

Other agents and the human reach you by message and you reach them with
`(my.message/send "id" "text")`. End your run with
`(my.run/complete "the reply you want delivered")`, or
`(my.run/wait "what you are waiting for")` when you need someone else
first — your next run starts fresh, so put everything it will need into
that note.
nil

From outside this cluster to bootstrap-o4-1-c81f62d8: Ask agent bootstrap-o4-1-c81f62d8-peer to author a permanent contracted function that doubles an integer and tell you its qualified symbol. Wait if needed. After the reply, call that function on 21 and complete with only 42.

Agent bootstrap-o4-1-c81f62d8 said to bootstrap-o4-1-c81f62d8-peer: Please author a permanent contracted function that doubles an integer and tell me its qualified symbol.

my.agents.bootstrap-o4-1-c81f62d8=> (my.message/send "bootstrap-o4-1-c81f62d8-peer"
                 "Please author a permanent contracted function that doubles an integer and tell me its qualified symbol.")
#:my.message{:to "bootstrap-o4-1-c81f62d8-peer", :content "Please author a permanent contracted function that doubles an integer and tell me its qualified symbol."}

my.agents.bootstrap-o4-1-c81f62d8=> (my.run/wait "Waiting for bootstrap-o4-1-c81f62d8-peer to reply with the qualified symbol of the permanent doubling function.")
#:my.run{:disposition :wait, :note "Waiting for bootstrap-o4-1-c81f62d8-peer to reply with the qualified symbol of the permanent doubling function."}
```

### Peer agent

```text
my.agents.bootstrap-o4-1-c81f62d8-peer=> (help)
You are an agent in a Seon cluster. This is a real Clojure REPL and it is
yours: what you evaluate here runs, and what you define here stays.

Your reply is read as Clojure forms and evaluated in order, one at a time,
in your own namespace. Everything a form prints and everything it returns
comes back to you as the session you are reading now. Round trips are
expensive, so plan the most forms you can usefully run together and send
them in one reply; when a later form depends on what an earlier one
returned, split the batch there.

The session displays each form followed by its actual computed value, like a
real REPL. You may write `;;` comments before your own forms; comments are
input-side notes, never modeled results or annotations around output.

The cluster is one graph database. `seon.db/q` and `seon.db/pull` read it
at the current basis, `dir` lists a namespace, `doc` explains a function
from the graph's own facts. Every function in this cluster is callable by
you.

A `defn` with a complete `:malli/schema` becomes a durable fact other
agents can find and call; without one it lives only in this session. The
contract is checked, so write it honestly: declared map keys are rigorously
validated, extra keys are ignored, and a return may not be a bare
`[:maybe ...]`.

Other agents and the human reach you by message and you reach them with
`(my.message/send "id" "text")`. End your run with
`(my.run/complete "the reply you want delivered")`, or
`(my.run/wait "what you are waiting for")` when you need someone else
first — your next run starts fresh, so put everything it will need into
that note.
nil

Agent bootstrap-o4-1-c81f62d8 said to bootstrap-o4-1-c81f62d8-peer: Please author a permanent contracted function that doubles an integer and tell me its qualified symbol.
```

## Arm B winner mining extract

The following section is a faithful extraction of source from every receipt
in every Arm B winning attempt. It adds no analysis.

### O1 attempt 1

Raw report:
[`o1-1-1bd769c3.edn`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o1-1-1bd769c3.edn)

Receipt 0:

```clojure
(defn total-by-label
  "Sum amounts by label string."
  {:malli/schema [:=> [:cat [:vector [:map [:label :string] [:amount :int]]]] [:map-of :string :int]]}
  [rows]
  (reduce (fn [acc {:keys [label amount]}]
            (update acc label (fnil + 0) amount))
          {}
          rows))
```

Receipt 1:

```clojure
(total-by-label [{:label "agent", :amount 0} {:label "user", :amount 1} {:label "agent", :amount 2} {:label "agent", :amount 3} {:label "agent", :amount 4} {:label "agent", :amount 5} {:label "agent", :amount 6} {:label "agent", :amount 7} {:label "agent", :amount 8} {:label "agent", :amount 9} {:label "agent", :amount 10} {:label "agent", :amount 11} {:label "agent", :amount 12}])
```

Receipt 2:

```clojure
(my.run/complete "{\"user\" 1, \"agent\" 77}")
```

### O1 attempt 2

Raw report:
[`o1-2-6cad9564.edn`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o1-2-6cad9564.edn)

Receipt 0:

```clojure
(defn total-by-label
  "Sum amounts by label string."
  {:malli/schema [:=> [:cat [:vector [:map [:label :string] [:amount :int]]]] [:map-of :string :int]]}
  [rows]
  (reduce (fn [acc {:keys [label amount]}]
            (update acc label (fnil + 0) amount))
          {}
          rows))
```

Receipt 1:

```clojure
(total-by-label [{:label "agent", :amount 0} {:label "user", :amount 1} {:label "agent", :amount 2} {:label "agent", :amount 3} {:label "agent", :amount 4} {:label "agent", :amount 5} {:label "agent", :amount 6} {:label "agent", :amount 7} {:label "agent", :amount 8} {:label "agent", :amount 9} {:label "agent", :amount 10} {:label "agent", :amount 11} {:label "agent", :amount 12}])
```

Receipt 2:

```clojure
(my.run/complete "{\"user\" 1, \"agent\" 77}")
```

### O1 attempt 3

Raw report:
[`o1-3-cdcb38e3.edn`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o1-3-cdcb38e3.edn)

Receipt 0:

```clojure
(defn total-by-label
  "Sum amounts by label string."
  {:malli/schema [:=> [:cat [:vector [:map [:label :string] [:amount :int]]]] [:map-of :string :int]]}
  [rows]
  (reduce (fn [acc {:keys [label amount]}]
            (update acc label (fnil + 0) amount))
          {}
          rows))
```

Receipt 1:

```clojure
(total-by-label [{:label "agent", :amount 0} {:label "user", :amount 1} {:label "agent", :amount 2} {:label "agent", :amount 3} {:label "agent", :amount 4} {:label "agent", :amount 5} {:label "agent", :amount 6} {:label "agent", :amount 7} {:label "agent", :amount 8} {:label "agent", :amount 9} {:label "agent", :amount 10} {:label "agent", :amount 11} {:label "agent", :amount 12}])
```

Receipt 2:

```clojure
(my.run/complete "{\"user\" 1, \"agent\" 77}")
```

### O1 attempt 4

Raw report:
[`o1-4-f8c336f1.edn`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o1-4-f8c336f1.edn)

Receipt 0:

```clojure
;; Define permanent contracted function
(defn total-by-label
  "Sum amounts by label string."
  {:malli/schema [:=> [:cat [:vector [:map [:label :string] [:amount :int]]]] [:map-of :string :int]]}
  [rows]
  (reduce (fn [acc {:keys [label amount]}]
            (update acc label (fnil + 0) amount))
          {}
          rows))
```

Receipt 1:

```clojure
(total-by-label [{:label "agent", :amount 0} {:label "user", :amount 1} {:label "agent", :amount 2} {:label "agent", :amount 3} {:label "agent", :amount 4} {:label "agent", :amount 5} {:label "agent", :amount 6} {:label "agent", :amount 7} {:label "agent", :amount 8} {:label "agent", :amount 9} {:label "agent", :amount 10} {:label "agent", :amount 11} {:label "agent", :amount 12}])
```

Receipt 2:

```clojure
(my.run/complete "{\"user\" 1, \"agent\" 77}")
```

### O1 attempt 5

Raw report:
[`o1-5-f888caf4.edn`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o1-5-f888caf4.edn)

Receipt 0:

```clojure
(defn total-by-label
  "Sum amount values by label string."
  {:malli/schema [:=> [:cat [:vector [:map [:label :string] [:amount :int]]]] [:map-of :string :int]]}
  [rows]
  (reduce (fn [acc {:keys [label amount]}]
            (update acc label (fnil + 0) amount))
          {}
          rows))
```

Receipt 1:

```clojure
(total-by-label [{:label "agent", :amount 0} {:label "user", :amount 1} {:label "agent", :amount 2} {:label "agent", :amount 3} {:label "agent", :amount 4} {:label "agent", :amount 5} {:label "agent", :amount 6} {:label "agent", :amount 7} {:label "agent", :amount 8} {:label "agent", :amount 9} {:label "agent", :amount 10} {:label "agent", :amount 11} {:label "agent", :amount 12}])
```

Receipt 2:

```clojure
(my.run/complete "{\"user\" 1, \"agent\" 77}")
```

### O1 attempt 6

Raw report:
[`o1-6-ff8fe9b5.edn`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o1-6-ff8fe9b5.edn)

Receipt 0:

```clojure
(defn total-by-label
  "Sum amounts by label string."
  {:malli/schema [:=> [:cat [:vector [:map [:label :string] [:amount :int]]]] [:map-of :string :int]]}
  [rows]
  (reduce (fn [acc {:keys [label amount]}]
            (update acc label (fnil + 0) amount))
          {}
          rows))
```

Receipt 1:

```clojure
(total-by-label [{:label "agent" :amount 0}
                 {:label "user" :amount 1}
                 {:label "agent" :amount 2}
                 {:label "agent" :amount 3}
                 {:label "agent" :amount 4}
                 {:label "agent" :amount 5}
                 {:label "agent" :amount 6}
                 {:label "agent" :amount 7}
                 {:label "agent" :amount 8}
                 {:label "agent" :amount 9}
                 {:label "agent" :amount 10}
                 {:label "agent" :amount 11}
                 {:label "agent" :amount 12}])
```

Receipt 2:

```clojure
(my.run/complete "{\"user\" 1, \"agent\" 77}")
```

### O1 attempt 7

Raw report:
[`o1-7-00ed645b.edn`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o1-7-00ed645b.edn)

Receipt 0:

```clojure
(defn total-by-label
  {:malli/schema [:=> [:cat [:vector [:map [:label :string] [:amount :int]]]] [:map-of :string :int]]}
  [rows]
  (reduce (fn [acc {:keys [label amount]}]
            (update acc label (fnil + 0) amount))
          {}
          rows))
```

Receipt 1:

```clojure
(total-by-label [{:label "agent", :amount 0} {:label "user", :amount 1} {:label "agent", :amount 2} {:label "agent", :amount 3} {:label "agent", :amount 4} {:label "agent", :amount 5} {:label "agent", :amount 6} {:label "agent", :amount 7} {:label "agent", :amount 8} {:label "agent", :amount 9} {:label "agent", :amount 10} {:label "agent", :amount 11} {:label "agent", :amount 12}])
```

Receipt 2:

```clojure
(my.run/complete "{\"user\" 1, \"agent\" 77}")
```

### O1 attempt 8

Raw report:
[`o1-8-801d78c6.edn`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o1-8-801d78c6.edn)

Receipt 0:

```clojure
(defn total-by-label
  "Sum amounts by label string."
  {:malli/schema [:=> [:cat [:vector [:map [:label :string] [:amount :int]]]] [:map-of :string :int]]}
  [rows]
  (reduce (fn [acc {:keys [label amount]}]
            (update acc label (fnil + 0) amount))
          {}
          rows))
```

Receipt 1:

```clojure
(total-by-label [{:label "agent", :amount 0}
                 {:label "user", :amount 1}
                 {:label "agent", :amount 2}
                 {:label "agent", :amount 3}
                 {:label "agent", :amount 4}
                 {:label "agent", :amount 5}
                 {:label "agent", :amount 6}
                 {:label "agent", :amount 7}
                 {:label "agent", :amount 8}
                 {:label "agent", :amount 9}
                 {:label "agent", :amount 10}
                 {:label "agent", :amount 11}
                 {:label "agent", :amount 12}])
```

Receipt 2:

```clojure
(my.run/complete "{\"user\" 1, \"agent\" 77}")
```

### O1 attempt 9

Raw report:
[`o1-9-d1f21427.edn`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o1-9-d1f21427.edn)

Receipt 0:

```clojure
(defn total-by-label
  "Sum amounts by label string."
  {:malli/schema [:=> [:cat [:vector [:map [:label :string] [:amount :int]]]] [:map-of :string :int]]}
  [rows]
  (reduce (fn [acc {:keys [label amount]}]
            (update acc label (fnil + 0) amount))
          {}
          rows))
```

Receipt 1:

```clojure
(total-by-label [{:label "agent", :amount 0} {:label "user", :amount 1} {:label "agent", :amount 2} {:label "agent", :amount 3} {:label "agent", :amount 4} {:label "agent", :amount 5} {:label "agent", :amount 6} {:label "agent", :amount 7} {:label "agent", :amount 8} {:label "agent", :amount 9} {:label "agent", :amount 10} {:label "agent", :amount 11} {:label "agent", :amount 12}])
```

Receipt 2:

```clojure
(my.run/complete "{\"user\" 1, \"agent\" 77}")
```

### O1 attempt 10

Raw report:
[`o1-10-5a8c9a86.edn`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o1-10-5a8c9a86.edn)

Receipt 0:

```clojure
(defn total-by-label
  "Sum amounts by label string."
  {:malli/schema [:=> [:cat [:vector [:map [:label :string] [:amount :int]]]] [:map-of :string :int]]}
  [rows]
  (reduce (fn [acc {:keys [label amount]}]
            (update acc label (fnil + 0) amount))
          {}
          rows))
```

Receipt 1:

```clojure
(total-by-label [{:label "agent", :amount 0}
                 {:label "user", :amount 1}
                 {:label "agent", :amount 2}
                 {:label "agent", :amount 3}
                 {:label "agent", :amount 4}
                 {:label "agent", :amount 5}
                 {:label "agent", :amount 6}
                 {:label "agent", :amount 7}
                 {:label "agent", :amount 8}
                 {:label "agent", :amount 9}
                 {:label "agent", :amount 10}
                 {:label "agent", :amount 11}
                 {:label "agent", :amount 12}])
```

Receipt 2:

```clojure
(my.run/complete "{\"user\" 1, \"agent\" 77}")
```

### O3 attempt 8

Raw report:
[`o3-8-4f222ce8.edn`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o3-8-4f222ce8.edn)

Receipt 0:

```clojure
(require '[seon.db :as db] '[my.run :as run])
```

Receipt 1:

```clojure
(let [ns-e (ffirst (db/q '[:find ?e :in $ ?ns :where [?e :seon.ns/name ?ns]] 'my.message))
      public-fns (db/q '[:find ?sym :in $ ?ns :where
                         [?f :seon.fn/ns ?ns]
                         [?f :seon.fn/sym ?sym]
                         (not [?f :seon.fn/private? true])]
                       ns-e)]
  (run/complete (str (count public-fns))))
```

### O3 attempt 9

Raw report:
[`o3-9-65fc652a.edn`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o3-9-65fc652a.edn)

Receipt 0:

```clojure
(require '[seon.db :as db])
```

Receipt 1:

```clojure
(let [ns-e (ffirst (db/q '[:find ?e :in $ ?ns :where [?e :seon.ns/name ?ns]] 'my.message))
      public-fns (db/q '[:find ?sym :in $ ?ns :where
                         [?f :seon.fn/ns ?ns]
                         [?f :seon.fn/sym ?sym]
                         (not [?f :seon.fn/private? true])]
                       ns-e)]
  (my.run/complete (str (count public-fns))))
```

### O3 attempt 10

Raw report:
[`o3-10-6e888f74.edn`](../../../../tmp/bootstrap-drives-rerun-20260805T000202Z/arm-b/reports/o3-10-6e888f74.edn)

Receipt 0:

```clojure
(require '[seon.db :as db] '[my.run :as run])
```

Receipt 1:

```clojure
(let [ns-e (ffirst (db/q '[:find ?e :in $ ?ns :where [?e :seon.ns/name ?ns]] 'my.message))
      public-fns (db/q '[:find ?sym :in $ ?ns :where
                         [?f :seon.fn/ns ?ns]
                         [?f :seon.fn/sym ?sym]
                         (not [?f :seon.fn/private? true])]
                       ns-e)]
  (run/complete (str (count public-fns))))
```

## Agent-visible and raw-report ugly output

- `(in-ns ...)` rendered an implementation face such as
  `#object[sci.lang.Namespace 0x3930e12f "my.agents.bootstrap-o1-7-29ee4764"]`
  directly to agents.
- The deliberate `:any` refusal rendered the cryptic header
  `Execution error () at (REPL:1).` before its useful explanation; the empty
  parentheses are especially ugly.
- The deliberate zero-arity call rendered a deeply nested, multi-line
  `:seon.cluster.loop/lint-rejected` map. It is accurate, but noisy in the
  agent transcript.
- `(help)`, `dir`, and `doc` printed useful output and then rendered a separate
  `nil`, adding repetitive low-value transcript lines.
- Failed provider turns left agents with only the incoming objective and no
  agent form or visible failure. The raw attempt fact was itself ugly:
  HTTP 200 plus `:seon.ai/unparseable-body`, message “response was not readable
  JSON: closed”, and a large serialized print-face error map.
- One O4 boot logged that its preferred web port was occupied and printed a
  long bookmark warning. This was operator-visible, not agent-visible.
- No `render-receipt-ai failed`, renderer invocation failure, namespace
  mismatch, or same-instant transcript reordering appeared.
