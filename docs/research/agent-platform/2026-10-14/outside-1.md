---
title: "outside-1: the first :outside chain task worked through MCP eval_clj (S3f-2 dogfood)"
status: complete (lane outside-1, 2026-10-14, agent attempt/c4424e366887 on :chain-e439df9798c6; bail path broken, merge not reached)
---

# outside-1: the first `:outside` chain task, worked through MCP `eval_clj`

Lane outside-1, 2026-10-14, default on HEAD 12a602b8c (S3f-2 c2eab5ab5 live), `:seon.config.ai/no-provider` true
(verified: no model call possible). One Implement task, worked as its agent id in SCI mode.

## Broken first

1. **An `:outside` agent cannot end its task except by green proofs.** `my.task/send-back!` as agent
   `attempt/c4424e366887` answered `{:my.turn/disposition :completed, :my.turn/delivered-to :outside, ...}`, but no
   end was recorded: 12 s and several passes later the attempt held no `:seon.task.attempt/end`, the task stayed
   `:running`, no problem task reached main, and the block still read `:seon.task/status :working`. Where: the
   `seon.turn` proc step calls `end-task!` only `(when (and (nil? submission) (#{:closed :error} outcome)))`, a
   model pass; an MCP eval request is a submission, so it never observes `seon.turn/task-end`; the `:prove` close
   ends only a done (green) task. So the bail path (TASK-CHAIN §2.7) is unreachable for `:outside` agents, and the
   reply says `:delivered-to :outside` as if the end had happened. The chain is left as it stands for root:
   branch `:chain-e439df9798c6`, task `e439df9798c6`, attempt `c4424e366887` (close it with `my.root/close!`).
   The merge path (S3b-2) was not reached: the task cannot be done (next item).
2. **The proof could not be made green by the change the plan asked for.** The filed task
   `seon.render.test/render-coverage-reads-the-retired-render-unknown-refusal-key` says the test reads a retired key.
   It does, but the recorded red is something else: the runner's "did not exit within
   `:seon.test/check-time-limit-ms` remainder 4396 ms; thread 241 remains live". The first row renders
   `my.render-probe/slow` (an interpreted 1e11-iteration loop) with `:seon.sci.eval/time-limit-ms` 50, and
   `seon.sci.kernel/with-arm` ("Nested work in any context inherits the governing arm") ignores the nested bound, so
   inside a test body or an eval request the render runs until the outer arm's limit. Reproduced at the REPL: still
   running after 3,000 ms under a 50 ms bound; the same probe with no deadline wrapper hit the request's 30 s limit.
   The agent sent the task back with the counterexample (below). This is a design question at `seon.render` /
   `seon.sci.kernel/with-arm` (a nested producer's own, smaller deadline), and needs its own task.

## Setup (as root, JVM mode)

- Picked the filed defect above (test-only, `test/seon/render_coverage_test.clj`, held by no lane; its proof is an
  existing deftest with no result on main). Chain created with a fresh identity
  `seon.render.test/outside-1-render-coverage-reads-the-current-typed-unknown` (the filed entry's deletion at merge,
  S3f-1 second half, is open, so the filed identity was not reused), Implement template, proofs
  `#{seon.render-coverage-test/a-refused-render-producer-contributes-a-stable-typed-unknown}`, `:seon.task/model
  :outside`. `chain!` 174 ms. Root started agent `attempt/c4424e366887` on `:chain-e439df9798c6` within seconds;
  its start test step ran the proof (run e8604e55d142, red by timeout).
- Setup calls: 11. Wasted on guesses: `seon.config/current`, `seon.cluster.boot/running-instances` (it lives in
  `seon.operator.runtime`), `seon.schema/registry`, `seon.db/registry` (no such Vars). One unexplained refusal:
  `(seon.task/chain! {:seon.db/connection conn :seon.store/store (seon.cluster.agent/handle-store inst)} ...)` over
  the boot instance was refused at `[:seon.store/store :seon.store/dir]` "got a map missing :seon.store/dir" (every
  store key listed), while the same call with `(:seon.store/store inst)` succeeded; not isolated further.
- Root-side observation: `:seon.task/model` reads `":outside"` (a string) through raw `datahike.api/pull`, and
  `:outside` through `seon.db/pull`: the stored value is not the native keyword (AGENTS "Preserve native values").

## The first block, verbatim

`(seon.context/current-state)`, call 1: 324 ms evaluate, 2,347 ms request (test step 87 ms, 1,819 ms
unattributed), 300.8 MB. Findings: `:large-value`, `:seon.eval/value-tokens 5577`, shown 5,286 estimated tokens.

```clojure
#:seon.context{:task
   #:seon.task{:sym
      seon.render.test/outside-1-render-coverage-reads-the-current-typed-unknown,
    :plan
      "## Problem

`seon.render-coverage-test/a-refused-render-producer-contributes-a-stable-typed-unknown` reads the retired `:seon.render.unknown/refusal` key. Done when the test follows the current typed unknown (`seon.render/unknown` and its `:seon.render.unknown/*` members) and is green. Read `seon.render.test/render-ai`.",
    :instructions
      "## Work

Read the plan and your proofs first: they start red; your block shows each failure. Change the producer the Approach
names, and convert its callers in the same change; read each owner with `my.program/source` before changing it.
Every def and defn you evaluate is a program definition on your branch and merges: unmap what you do not keep with
`my.program/ns-unmap!`.
A proof that contradicts the plan, or a row you believe is wrong, is not yours to satisfy: send it back with
`my.task/send-back!` and the row and why. Never special-case one row to pass.

## Done

Every proof is green, no test that is green on main is red here, and every function you changed has a complete
contract."},
 :proofs
   #:seon.context{:counts {:red 1},
    :proof-list
      [{:seon.test/sym
          seon.render-coverage-test/a-refused-render-producer-contributes-a-stable-typed-unknown,
        :seon.context/proof-status :red,
        :seon.test/source
          "(deftest a-refused-render-producer-contributes-a-stable-typed-unknown
  ;; THE CLASS: every producer runs under `:seon.sci.eval/time-limit-ms`, and
  ;; a refusal used to contribute ABSENCE — the walk kept a unit only when its
  ;; output was a non-empty string with no error. So a slow machine silently
  ;; moved the prompt bytes a fast one produced, which is why \"same database
  ;; value, same adopted commit, same profile ⇒ same bytes\" was unreachable
  ;; (PRD §5, review B5), and it was a standing §2.4 violation besides: an
  ;; unavailable observation is the typed unknown, never absence.
  (support/with-database
   (fn [connection]
     (seed-entities! connection)
     (let [database @connection
           ctx (probe-ctx connection)
           value (pulled database [:seon.agent/id agent-id])]
       (testing \"a producer that runs past the limit names itself and the bound\"
         (let [refused (probe-render database ctx value
                                     'my.render-probe/slow 50)]
           (is (some? (:seon.render.unknown/reason refused)))
           (is (= :time-limit (:seon.render.unknown/reason refused)))
           (is (= 'my.render-probe/slow
                  (:seon.render.unknown/producer refused)))
           (is (= probe-call-id (:seon.render.unknown/call refused)))
           (is (= :seon.render/ai (:seon.render.unknown/output refused)))
           (is (= :seon.sci.kernel/time-limit
                  (:seon.render.unknown/refusal refused)))))

       (testing \"a producer that throws carries the throwable's class\"
         (let [refused (probe-render database ctx value
                                     'my.render-probe/boom 2000)]
           (is (= :refused (:seon.render.unknown/reason refused)))
           (is (= 'my.render-probe/boom
                  (:seon.render.unknown/producer refused)))
           (is (= :seon.sci.kernel/invocation-failed
                  (:seon.render.unknown/refusal refused)))
           (is (string? (:seon.render.unknown/throwable refused)))))

       (testing \"a producer whose declared contract refuses carries it\"
         (let [refused (probe-render database ctx value
                                     'my.render-probe/contracted 2000)]
           (is (= :refused (:seon.render.unknown/reason refused)))
           (is (= 'my.render-probe/contracted
                  (:seon.render.unknown/producer refused)))
           (is (= :seon.instrument/contract-violated
                  (:seon.render.unknown/refusal refused))
               \"the contract's own refusal survives the render boundary\")))

       (testing \"the unknown reads as ONE line of data the agent can act on\"
         (let [refused (probe-render database ctx value
                                     'my.render-probe/slow 50)
               line (render/unknown-output :seon.render/ai refused)]
           (is (= 1 (count (str/split-lines line))))
           (is (not (str/starts-with? (str/triml line) \";\"))
               \"never comment-shaped (ruling 45)\")
           (is (str/includes? line \"my.render-probe/slow\"))
           (is (str/includes? line \":time-limit\"))))

       (testing \"and as one labeled block for a person\"
         (let [refused (probe-render database ctx value
                                     'my.render-probe/boom 2000)
               block (render/unknown-output :seon.render/html refused)
               text (hiccup/->string block)]
           (is (hiccup/hiccup? block))
           (is (str/includes? text \"renderer unavailable\"))
           (is (str/includes? text \"my.render-probe/boom\"))))

       (testing \"a producer that RETURNS an error value is not a refusal\"
         (let [returned (probe-render database ctx value
                                      'my.render-probe/steady 2000)]
           (is (= \"steady bytes\" returned))))))))",
        :seon.context/proof-failures
          [{:type :error,
            :message
              "Test seon.render-coverage-test/a-refused-render-producer-contributes-a-stable-typed-unknown did not exit within :seon.test/check-time-limit-ms remainder 4396 ms; thread 241 remains live and keeps its branch until it exits. This request starts no further body; later requests hold only this test until it exits."}]}]},
 :step
   {:seon.task/status :working,
    :seon.source/refusals
      [{:seon.error/at #inst "2026-10-14T14:26:44.966Z",
        :seon.error/layer :seon.source/merge,
        :seon.error/operation seon.cluster.source/accept-merge!,
        :seon.test.run/branch :chain-e439df9798c6,
        :seon.source/refused-test-run "e8604e55d142",
        :seon.error/type :seon.source/red-test-error,
        :seon.error/message
          "A test of the named run is not green; a merge commits only when every test is.",
        :seon.source/red-tests
          [{:seon.test/sym
              seon.render-coverage-test/a-refused-render-producer-contributes-a-stable-typed-unknown,
            :seon.test.run/id "e8604e55d142",
            :seon.source/verdict :red}]}],
    :seon.context/start
      {:seon.render/operations [(in-ns 'seon.render.test)]}},
 :must-reads
   [#:seon.ref{:name :seon.render.unknown/refusal,
     :problem-kind :unresolved,
     :message
       "Nothing of this name or path exists on your branch. If the task means one to create, create it; else tell root with my.message/send that the task names it."}
    {:in [:cat :seon.render/unknown-request],
     :out :seon.render/unknown,
     :seon.ref/name seon.render/unknown,
     :seon.ref/level :doc,
     :summary
       "The ONE stable typed unknown a refused render producer contributes.",
     :body
       "`:seon.render.unknown/reason` records the invocation outcome: `:time-limit` when the
producer ran past the request's `:seon.sci.eval/time-limit-ms`, `:refused`
when the guarded invocation ended in a throwable or a declared-contract
refusal, and `:unselected` when no producer ran at all. The refusal's observed operation and the throwable's class ride along when the boundary
observed them, because they are what a repair starts from and both are
stable spellings.",
     :arglists
       ([{producer-symbol :seon.render.unknown/producer,
          reason :seon.render.unknown/reason,
          :as request}]),
     :schemas
       #:seon.render{:unknown
          {:form
             [:and
              :seon.error/base
              [:map
              {:seon.db/attributes true,
              :seon.render/ai seon.render/unknown-ai,
              :seon.render/html seon.render/unknown-html}
              [:seon.render.unknown/reason :seon.render/unknown-reason]
              [:seon.render.unknown/producer {…1} :qualified-symbol]
              [:seon.render.unknown/output {…1} :seon.render/output]
              [:seon.render.unknown/refused-operation
              {…1}
              :qualified-symbol]
              [:seon.render.unknown/throwable {…1} :string]]]},
        :unknown-request
          {:form
             [:map
              [:seon.render.unknown/reason :seon.render/unknown-reason]
              [:seon.render.unknown/producer
              {:optional true}
              :qualified-symbol]
              [:seon.render/output {:optional true} :seon.render/output]
              [:seon.render.call/id
              {:optional true}
              :seon.render.call/id]
              [:seon.error/value {:optional true} :seon.error/value]]}}}
    #:seon.ref{:name :seon.render.unknown/*,
     :problem-kind :unresolved,
     :message
       "Nothing of this name or path exists on your branch. If the task means one to create, create it; else tell root with my.message/send that the task names it."}
    {:out :seon.render.test/view,
     :supplied [:seon.db/db],
     :seon.ref/level :doc,
     :seon.ref/name seon.render.test/render-ai,
     :body
       "## Costs

At distance 0 one pull and one recorded-result read (about 3 ms); the host
report walks the call closure (14-73 ms), so only a nearer view reads it
(unarmed, default, 2026-09-28).",
     :summary
       "A test as data: its name and, once it has run, its state; beyond distance zero also its source as its lines (what it asserts), where it runs, its failed assertions and, when red, the definitions changed since it last passed.",
     :arglists ([unit]),
     :in
       [:cat
        [:map
         {:description
            "The open render unit: the value under :seon.render/value with the render inputs its function declares. The database is a required input: omitted, call preparation supplies it (the invocation's scoped database); a caller rendering with no evaluation world passes the database it has."}]],
     :schemas
       {:seon.render.test/view
          {:description
             "A test as its AI face shows it: state once it has run (absent otherwise), beyond distance zero its source lines, host, failed assertions and, unless green, the definitions changed since it last passed."}}}
    ;; then the doc-level must-reads of my.program/source, my.program/ns-unmap! and my.task/send-back!
    ;; (summary, body, example, arglists, in/out schemas), about 1,900 of the shown tokens
    ],
 :cost
   [#:seon.cost.comparison{:subject seon.task/obligations,
     :measure :cold-self-us,
     :live 1013.0,
     :flags #{:changed},
     :main 1233.09,
     :ratio 0.82}],
 :turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.render.test}}
```

(The three tool must-reads are elided above only in this note; the agent received them whole.)

## Every agent call (SCI, as `attempt/c4424e366887`)

| # | Form | Request ms / alloc | Spent on something the context should have said? |
|---|---|---|---|
| 1 | `(seon.context/current-state)` | 2,347 / 301 MB | No: the entrance. |
| 2 | `(my.program/source 'seon.render/unknown)` | 3,640 (test step 3,532) / 6.5 GB step | Partly: the must-read showed its doc and schema; the body was needed to learn which member replaced `:refusal`. |
| 3 | `(clojure.test/run-test-var #'seon.render-coverage-test/...)` | 4,036 / 6.1 GB step | Yes: "Unable to resolve var" until the namespace is required (filed: `seon.sci.lookup/an-agent-runs-its-proofs-with-clojure-test`). |
| 4 | `(require ...)` then `run-test-var` | 4,791 / 8.1 GB | Yes: answered the reused timeout red (`:seon.test/reused-count 1`), which the block already showed; the assertion failures are unreachable while the timeout result holds the reach digest. |
| 5 | `(my.program/source 'seon.render/unknown-stable-evidence)` | 4,907 / 8.1 GB step | Yes: the plan's pointer (`seon.render.test/render-ai`, copied from the filed task) names an unrelated function; the producer is `seon.render/unknown-stable-evidence`. |
| 6 | `(my.program/source 'seon.render-coverage-test/probe-render)` | 3,687 | No: test-internal helper, needed to probe. |
| 7 | probe all three producers in `support/with-database` | 34,772 (hit the 30 s limit) | Partly: the failure named no row; the runner report could name the `testing` context in flight. |
| 8 | probe `boom` and `contracted` only | 4,066 / 335 MB | No: real REPL work; gave the replacement values. |
| 9 | probe `slow` with a 3 s `deref` bound | 4,838 | No: confirmed the 50 ms bound never fires. |
| 10 | `(my.program/source '...-test/seed-entities!)` | 3,174 | Wasted: looking for the producers' definitions. |
| 11 | `(my.program/source '...-test/probe-ctx)` | 3,735 | Partly. |
| 12 | `probe-source` | 3,064 | No. |
| 13 | `(my.program/search "time-limit-ms render producer invocation")` | 3,242 | Yes: a multi-word search answered `{:count 0}`. |
| 14 | `(my.program/search "time-limit")` | 444 | No: found `seon.sci.kernel/with-arm`. |
| 15 | `(my.program/source 'seon.sci.kernel/with-arm)` | 3,770 | No: its docstring states the inheritance. |
| 16 | `(my.task/send-back! {...})` | 627 | No; but it did not end the task (Broken 1). |
| 17-18 | `current-state` after the send-back (18 my own key mistake) | 3,580 / 6.1 GB; 3,076 / 6.1 GB | Checking the end that never came. |

18 agent calls, about 7 minutes wall (14:26:50 to 14:33 UTC). Calls 2-13 each paid a 2.9-3.7 s, 5-8 GB test step:
the runaway proof thread 241 held the step ("later requests hold only this test until it exits") until about call 14,
after which the step took 70-260 ms. After the send-back, `current-state` itself took 2.9 s and 6.1 GB in its
evaluate stage (call 1's evaluate was 356 ms).

## Misleading or missing context (exact excerpts)

1. The proof's only failure is the timeout ("did not exit within :seon.test/check-time-limit-ms remainder 4396
   ms"); no row or `testing` context is named, and the plan says the cause is the retired key. A red by timeout is
   not "red for the plan's reason", yet nothing in the block says so.
2. Must-read `:seon.render.unknown/refusal`: "Nothing of this name or path exists on your branch. If the task means
   one to create, create it". The plan cites it as retired; the message invites recreating it.
3. Must-read `:seon.render.unknown/*`: the same unresolved message for a wildcard the plan author (root, me) wrote.
   An authoring error, but the reference rule gives no hint that a wildcard is not a reference.
4. `:seon.context/start {:seon.render/operations [(in-ns 'seon.render.test)]}` sends the agent to the task's
   namespace, while every proof and the work are in `seon.render-coverage-test`.
5. `:step :seon.source/refusals` holds an `accept-merge!` `:seon.source/red-test-error` stamped
   `:seon.error/at` with the read time (14:26:44 at the first read, 14:31:43 at a later one). As root I first read it
   as root having attempted a merge 12 s after creation; it is a preview recomputed per read, and an event-shaped
   error map for a preview misleads.
6. Call 2's reply carried `:errors` with an error recorded on the agent's chain branch 13 s after creation:
   `:seon.error/operation seon.cluster.registry/retire-branch!`, "Connection has been released.",
   `:seon.error.occurrence/branch :chain-e439df9798c6`, with no task. It belongs to root's machinery; the agent
   cannot act on it, and nothing says whether the branch is healthy (it was).
7. The plan's "Read `seon.render.test/render-ai`" came from the filed task and was rendered as a must-read (about 300
   tokens) for a function unrelated to the work.
8. The send-back reply `:my.turn/delivered-to :outside` reads as delivered, while the end was never recorded.

## For root

- **RESET NEEDED: no.** Default is healthy; no leaked loop was left (JVM at 0% CPU at 21:32).
- Root must decide the held work: the chain `:chain-e439df9798c6` is open with a running attempt nobody will end;
  close it (`my.root/close!`) or keep it for the follow-up.
- Tasks to file (no tasks file was claimed by this lane):
  1. `seon.turn`: an `:outside` agent's send-back or give-back ends its task (the `end-task!` call that requires
     `(nil? submission)`); proof: a row in `seon.cluster.db-flow-test/an-outside-task-takes-no-model-turn-and-is-worked-through-the-repl`
     where the eval request's last form is `my.task/send-back!` and root records the bail (§2.7).
  2. `seon.sci.kernel`/`seon.render`: a nested render producer's `:seon.sci.eval/time-limit-ms` fires under a
     governing arm (`seon.sci.kernel/with-arm`); the existing red proof is the `slow` row of
     `seon.render-coverage-test/a-refused-render-producer-contributes-a-stable-typed-unknown`. The key rows are a test
     edit, values measured here: `boom` gives `:seon.render.unknown/refused-operation seon.sci.kernel/failure-value`,
     `:throwable "java.lang.ArithmeticException"`; `contracted` gives `refused-operation my.render-probe/contracted`,
     `:throwable "clojure.lang.ExceptionInfo"`; both `:reason :refused`, `:output :seon.render/ai`.
  3. Rewrite the filed `seon.render.test/render-coverage-reads-the-retired-render-unknown-refusal-key`: its diagnosis
     is incomplete (item 2) and its "Read" pointer is wrong (`seon.render/unknown-stable-evidence`).
  4. The runner's timeout report names the `testing` context in flight; a timed-out result reused by reach digest
     leaves an agent no way to see the assertions short of editing the test.
  5. `my.program/search` with several words answers nothing.
  6. The block's step preview carries a fresh `:seon.error/at`; the start operation names the task's namespace rather
     than the proofs'.
  7. `retire-branch!` "Connection has been released." recorded on a just-created chain branch.
  8. `:seon.task/model` stored as the string ":outside" (raw Datahike pull).
