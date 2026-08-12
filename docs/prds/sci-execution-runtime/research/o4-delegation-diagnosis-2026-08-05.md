---
type: research
status: complete
tags: [bootstrap, agents, delegation, runtime, test]
---

# O4 delegation diagnosis

## Verdict

O4 is a **bootstrap/evaluation-driver defect**, not a runtime messaging defect
and not a grader misjudgment of a completed delegation.

The embedded failed transcript shows that the main agent sent the message and
returned `my.run/wait`; the peer's transcript contains the committed incoming
message but no peer form. The drive then graded that incomplete database basis.
The immediate cause is that `seon.eval.drive/terminal-state` scopes an episode
to runs directly triggered by the original message and to the initiating
agent's current work. Current runtime semantics make `my.run/wait` close that
run. The driver therefore returns `:stopped` as soon as the initiating agent's
wait receipt closes, even while the causally reached peer has an open run.
`run-episode!` immediately freezes that commit for grading.

A fresh live DeepSeek reproduction completed the same three-hop delegation:

1. `o4-main` sent an assignment and waited;
2. `o4-peer` opened a run, committed a permanent contracted function, and
   completed with its qualified symbol; and
3. the reply opened a new `o4-main` run, which called the function on `21` and
   completed with `"42"`.

The live facts contain no error after the objective transaction. This rules
out a regression in `my.message`, message commitment, listener routing, peer
wake delivery, peer execution, automatic reply, or continuation execution.

## Authority and dependency ledger

I read the repository `AGENTS.md` and
[`bootstrap-baseline-2026-08-04.md`](bootstrap-baseline-2026-08-04.md) end to
end before diagnosing the failure. I also read the P9 section of
[`state-of-the-program-2026-08-05.md`](../plan/state-of-the-program-2026-08-05.md),
the current working edge, the localized runtime and issue instructions, and
the relevant architecture targets.

The exact mechanisms at this boundary are:

| Mechanism | Selected revision / source | First-party owner and proof |
|---|---|---|
| Datahike transaction listeners | `c15272730e74`; `reference-code/datahike/src/datahike/writer.cljc:393-417` invokes listeners with the committed transaction report before delivering the report to the caller | `src/seon/cluster/wake.clj:197-246`; `test/seon/cluster/wake_test.clj` |
| core.async sliding buffer | `dc35f3e0d7bc2eef502e77982f48641f025c8051`; `reference-code/core.async/src/main/clojure/clojure/core/async/impl/buffers.clj:60-81` replaces the oldest value and never reports full | `src/seon/cluster/agent.clj:147-173,290-318`; `test/seon/cluster/agent_test.clj` |
| Message value and durable delivery | first-party current source | `src/my/message.clj:20-63`; `src/seon/cluster/message.clj:268-414`; `src/seon/cluster/loop.clj:1588-1653` |
| Wait disposition | first-party current source | `src/seon/cluster/loop.clj:290-344`; `test/seon/cluster/agent_test.clj:1590-1660` proves that wait closes the run and a later trigger opens a new run |
| Episode termination and grading | first-party current source | `src/seon/eval/drive.clj:110-119,223-274,295-356`; `src/seon/bootstrap_drive.clj:224-258`; neither `test/seon/eval/drive_test.clj` nor `test/seon/bootstrap_drive_test.clj` contains a multi-agent episode proof |

The SCI pin was `2db3358cba91`, but no SCI
fork or reader behavior was implicated. The relevant production and test files
were clean in the shared worktree after the live proof.

## Embedded transcript classification

The complete Arm B attempt 1 pair in
[`bootstrap-baseline-2026-08-04.md`](bootstrap-baseline-2026-08-04.md) answers
the requested hop classification:

- **Did the sender send? Yes.** The main receipt is
  `(my.message/send "...-peer" "Please author ...")`, its admitted value is a
  `#:my.message{...}` message, and the transcript renders the corresponding
  `Agent ... said to ...-peer` sentence.
- **Did a durable delivery exist for the recipient? Yes.** The peer transcript
  renders the incoming assignment. A transcript proves the message fact was
  visible at the frozen database basis; by itself it does not prove that the
  peer mailbox consumed its wake.
- **Did the recipient run? No, not in the frozen basis.** The peer transcript
  has the bootstrap material and incoming message but no peer-authored form,
  receipt, reply, or function.
- **Did a reply return? No, not in the frozen basis.** The main transcript ends
  at `my.run/wait`.
- **Did the grader misjudge an existing success? No.** At that basis there was
  no reverse message, peer function, main function call, or `"42"` completion,
  so P4a-P4d correctly evaluated false. The false negative was created earlier
  when the drive chose an incomplete ending commit.

The transcript therefore localizes the failure between committed outbound
message and completed recipient episode. The live reproduction below proves
that the runtime crosses that seam and that the drive stops observing too
soon.

## Live reproduction

### Setup

The isolated operator root and cluster were:

```text
/Users/sean/src/seon/tmp/o4-delegation-diagnosis-root
o4-delegation-diagnosis
```

The first start correctly refused because an isolated root had no published
`current-src`. `bin/seon --root ... init` published source commit
`6a7354ca-67bd-5a20-8210-e7a49ce0abdc`, digest
`7c1c6508e4d59f190a4fc0c82b6381130a96115e77a5461ae2e135bcd7ba40eb`;
the next start reached web readiness. Effective model settings queried from
the live database were:

```clojure
#:seon.config.ai{:model "deepseek-v4-flash", :thinking :disabled}
```

The repository MCP client was unavailable before evaluation because its
offline discovery could not locate `seon/operator/state` after that owner
moved to `resources/seon/operator/state.clj`. The live proof therefore used
the repository-sanctioned `io-prepl` terminal at the cluster's advertised
port. It mutated no production source or runtime Var.

After both fresh agents completed their 13-form bootstrap runs, their Flow
mailbox `:seon.cluster.agent/deliveries` counters were both `2`. The objective
was committed from outside the population to `o4-main`.

### Messages and wakes

The initial-message query was:

```clojure
(db/q '[:find ?message-id ?to-id ?content ?tx
        :in $ ?message-id
        :where
        [?message :seon.cluster.message/id ?message-id]
        [?message :seon.cluster.message/to ?to]
        [?to :seon.cluster.agent/id ?to-id]
        [?message :seon.cluster.message/content ?content ?tx]]
      database "inbound-536871038-0")
```

It returned:

```clojure
#{["inbound-536871038-0"
   "o4-main"
   "Ask agent o4-peer to author a permanent contracted function that doubles an integer and tell you its qualified symbol. Wait if needed. After the reply, call that function on 21 and complete with only 42."
   536871039]}
```

The two agent-generated message rows, ordered by assertion transaction, were:

```clojure
[["8415746c-548c-43d4-8e61-5c99c6316d5d-0-message-0"
  "o4-main" "o4-peer"
  "Please author a permanent contracted function that doubles an integer and reply with its qualified symbol."
  "inbound-536871038-0"
  536871045]
 ["da71d43f-e453-42e5-b2d7-c00eb007fb08-2-message-0"
  "o4-peer" "o4-main"
  "my.agents.o4-peer/double-int"
  "8415746c-548c-43d4-8e61-5c99c6316d5d-0-message-0"
  536871057]]
```

Those rows carry the complete causality chain in
`:seon.cluster.message/caused-by`; no hop or reply flag is needed. The
corresponding triggered runs opened at transactions `536871040`, `536871047`,
and `536871058`, immediately after the three incoming-message transactions
`536871039`, `536871045`, and `536871057`. That is durable evidence that each
message wake was consumed and re-derived as recipient work, not merely
rendered.

Flow's process-local counters independently advanced from
`{"o4-main" 2, "o4-peer" 2}` after bootstrap to:

```clojure
{"o4-main" {:mailbox 8, :passes 8, :turns 8},
 "o4-peer" {:mailbox 5, :passes 5, :turns 5}}
```

The deltas include self-wakes as well as routed message wakes, so they are
confirmation of live proc activity rather than a one-counter-per-message
claim.

### Runs and receipts

The triggered-run query returned this timeline:

```clojure
[["8415746c-548c-43d4-8e61-5c99c6316d5d"
  "o4-main" "inbound-536871038-0" 536871040
  #inst "2026-08-05T15:23:59.549-00:00"
  "68b6309f84b602b2023ae497025c587c02b183405ac0c6163e9fd219fee441ef"]
 ["da71d43f-e453-42e5-b2d7-c00eb007fb08"
  "o4-peer" "8415746c-548c-43d4-8e61-5c99c6316d5d-0-message-0"
  536871047 #inst "2026-08-05T15:24:49.296-00:00"
  "13a04c839fad112b754bf3ed446ff29c463a93d1f1f14af23462fbb39e102ca9"]
 ["d8a0da44-b6bb-4ec3-8b92-18ab7c397eed"
  "o4-main" "da71d43f-e453-42e5-b2d7-c00eb007fb08-2-message-0"
  536871058 #inst "2026-08-05T15:25:22.803-00:00"
  "af8bbac8b855c7e6073fe999fcd1cbef2d583131c1539d3cd634d837efa48016"]]
```

The decisive receipt sources and admitted values were:

```clojure
;; first main run
(my.message/send "o4-peer" "Please author ...")
=> #:my.message{:to "o4-peer", :content "Please author ..."}
(my.run/wait "Waiting for o4-peer ...")
=> #:my.run{:disposition :wait, :note "Waiting for o4-peer ..."}

;; peer run
(defn double-int
  "Double an integer."
  {:malli/schema [:=> [:cat :int] :int]}
  [n]
  (* 2 n))
(double-int 21)
=> 42
(my.run/complete "my.agents.o4-peer/double-int")
=> #:my.run{:disposition :completed,
            :result "my.agents.o4-peer/double-int"}

;; continuation main run
(my.agents.o4-peer/double-int 21)
=> 42
(my.run/complete "42")
=> #:my.run{:disposition :completed, :result "42"}
```

The permanent program row was present before the peer reply:

```clojure
["my.agents.o4-peer/double-int" "[:=> [:cat :int] :int]" 536871053]
```

The live wait for the causal completion returned after 56,565 ms:

```clojure
{:run-ids ["8415746c-548c-43d4-8e61-5c99c6316d5d"
           "d8a0da44-b6bb-4ec3-8b92-18ab7c397eed"],
 :completed "42",
 :receipt-count 4}
```

No error fact had an assertion transaction greater than the initial objective
basis:

```clojure
(db/q '[:find ?kind ?message ?tx
        :where
        [?error :seon.error/kind ?kind ?tx]
        [?error :seon.error/message ?message]
        [(> ?tx 536871038)]]
      database)
=> #{}
```

### Exact false terminal

The shortest falsifier is the driver's own public predicate evaluated at the
basis immediately after the initiating wait closed. The peer run had already
opened, but the peer had not replied:

```clojure
(let [basis (db/as-of database 536871049)]
  {:terminal
   (drive/terminal-state basis "o4-main" process
                         "inbound-536871038-0" 6)
   :peer-runs
   (or (db/q '[:find (count ?run) .
               :where
               [?peer :seon.cluster.agent/id "o4-peer"]
               [?run :seon.cluster.run/agent ?peer]
               [?run :seon.cluster.run/trigger _]] basis)
       0)
   :reply-count
   (or (db/q '[:find (count ?message) .
               :where
               [?from :seon.cluster.agent/id "o4-peer"]
               [?message :seon.cluster.message/from ?from]] basis)
       0)})
```

Result:

```clojure
{:terminal
 #:seon.eval.drive{:outcome :stopped,
                   :run-ids
                   ["8415746c-548c-43d4-8e61-5c99c6316d5d"]},
 :peer-runs 1,
 :reply-count 0}
```

This is the broken seam in one value: the drive declares the episode stopped
while a causally reached peer run is live.

## Root cause

The root cause is **non-causal episode scoping in `seon.eval.drive`**:

1. `objective-run-ids` at `src/seon/eval/drive.clj:110-119` returns only runs
   whose trigger is the original outside message.
2. `terminal-state` at `src/seon/eval/drive.clj:235-272` checks completions and
   closure only for those run ids and asks `next-agent-work` only for the
   initiating agent.
3. `my.run/wait` intentionally closes the current run at
   `src/seon/cluster/loop.clj:328-344`; the next incoming message must open a
   new run.
4. `run-episode!` at `src/seon/eval/drive.clj:327-336` freezes the database as
   soon as that main-only predicate returns `:stopped`. It does not ask whether
   an outbound message caused another agent's open run.

The grader has a second blind spot produced by the same scope. Even if the
ending commit were delayed, `run-episode!` reports receipts only from the
original main run (`src/seon/eval/drive.clj:331-352`). `grade-o4` searches
those receipts for the peer function call (`src/seon/bootstrap_drive.clj:242-256`),
but the call and final `"42"` necessarily occur in a **new main run triggered
by the peer reply**. A timing-only delay would therefore make P4a/P4b possible
but leave P4c/P4d falsely red.

The defect classification is:

- runtime defect: **no**;
- bootstrap teaching/objective defect: **no** — the objective correctly uses
  the current close-on-wait continuation model;
- episode-driver/grading defect: **yes**.

Changing `my.run/wait` back to an open-run release is not an admissible fix. It
would contradict the settled runtime contract and recreate the unheld,
open, fully planned feeder state that `test/seon/cluster/agent_test.clj:1590-1660`
proves unrepresentable.

## Ranked fix shapes

1. **Derive one complete causal episode from message/run facts (recommended).**
   Starting from the outside objective message, follow
   `:seon.cluster.run/trigger` and `:seon.cluster.message/caused-by` to collect
   every causally reached message and run. Terminal success is the initiating
   agent's completed result anywhere in that closure. Terminal failure/cap is
   allowed only when the complete closure has no live run or unhandled causal
   message. Report receipts, attempts, and transcripts for the closure, so the
   same data grades P4a-P4d. This strengthens the existing fact-space driver
   and matches the runtime's recorded causality without a hand-maintained agent
   roster.
2. **Give `run-episode!` an objective-supplied terminal and run-selection
   query.** O4 can supply the same causal success predicate while single-agent
   objectives retain their narrow predicate. This is lower regression risk but
   adds a generalized extension seam and permits objective predicates to drift
   from one another.
3. **Wait for all named agents and concatenate all their runs.** This is the
   smallest local patch, but it is not causally honest: unrelated work on a
   named agent can delay or satisfy the episode, and a later dynamically
   created recipient falls outside the roster. Keep only as a temporary
   diagnostic, not the maintained fix.

## Issue and acceptance boundary

The open issue is
[`bootstrap-o4-stops-before-causal-delegation-settles.md`](../../../seon/issues/bootstrap-o4-stops-before-causal-delegation-settles.md).
Its acceptance criteria require a recurring scripted two-agent proof, a
causal ending commit, complete run aggregation, and all four O4 predicates
true from facts. No production fix was made in this investigation.

## Ugly output encountered

- Repository MCP `eval_clj` and `runtime_status` returned only
  `Could not locate seon/operator/state.bb, seon/operator/state.clj or
  seon/operator/state.cljc on classpath.` The actual owner is
  `resources/seon/operator/state.clj`. The face omits the owning discovery
  operation, searched classpath, and recovery instruction.
- `seon.eval.drive/read-result` rendered the peer's valid durable defn receipt
  as `:seon.eval.drive/unreadable`, while the raw result was the valid face
  `#:seon.print{:face :seon.print/var,
  :name "my.agents.o4-peer/double-int"}`. The grader happened not to depend on
  that parsed value, but the raw-report face falsely presents a successful
  definition as unreadable.
- The raw `io-prepl` envelopes include the complete submitted form under
  `:form`, duplicating long Datalog queries beside their results. That is
  faithful transport evidence but noisy for a human investigation report.
