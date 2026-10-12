---
title: "Round 2 block, 3-merge, before"
type: research
status: complete
created: 2026-10-12
---

# 3-merge, before (task handle c6bf2eb4561e)

The current-state block value as the agent's REPL reply shows it (display budget 2,000 on default af0a94471); the finding's value-tokens is the whole block. Method: README.md.

```clojure
#:seon.repl{:findings [{:seon.repl/finding :large-value,
  :seon.eval/value-tokens 12638,
  :seon.eval/shown-tokens 1930,
  :seon.repl/fix
    "A value this big usually means a query or function returns more than it should: select what you need."}
 {:seon.repl/finding :large-allocation,
  :seon.eval/allocated-bytes 569932568,
  :seon.repl/fix
    "Past ten times what an ordinary evaluation allocates: something realized far more than it returned (an unbounded lazy sequence, a whole-store read, a copied collection); realize only what you need. (seon.profile/measure (fn [] ...)) breaks a suspect call down into allocation, GC, armed calls and reads."}], :result result/ebf762ca805b4, :ms 271, :alloc-mb 569.9, :value #:seon.context{:turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.merge},
 :task
   #:seon.task{:id "c6bf2eb4561e",
    :sym seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts,
    :intent
      "(m6-notes.md, measured with `seon.profile/measure` in a check JVM) A facts merge through `my.root/merge!` costs 6.4 ms and 1 MB warm, but as a fresh JVM's first merge 417 ms and 1.0 GB, 359 ms of it in `seon.db/vector-query-call` (the first three-way and projection reads in `seon.merge/merge!`). The work must follow the changed facts, not the first call: find which first-call derivation (projection read, squash three-way) allocates the gigabyte, "…+272 (subs (get-in result/ebf762ca805b4 [:seon.context/task :seon.task/intent]) 450),
    :must-read
      #{seon.db/vector-query-call my.root/merge! seon.merge/merge!}},
 :proofs
   #:seon.context{:counts {:pending 1},
    :proof-list
      [{:seon.test/sym
          my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts,
        :seon.context/proof-status :pending,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.merge/a-jvms-first-facts-merge-fo"…+2359 (subs (get-in result/ebf762ca805b4 [:seon.context/proofs :seon.context/proof-list 0 :seon.test/source]) 64)}]},
 :step
   {:seon.task/step :implement,
    :seon.task/status :working,
    :seon.context/start
      {:in-ns seon.merge,
       :source
         (my.program/source
          'seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts)}},
 :must-reads
   [{:seon.ref/name my.root/merge!,
     :seon.fn/spec
       "[:=> [:cat :my.root/merge-request-request] [:or :seon.merge/merged :seon.source/conflict-error :seon.error/value]]",
     :doc
       "Merge the open assignment `:seon.task/assignment` into my cluster (`seon.merge/merge!`): its branch's facts, and, when its agent"…+219 (subs (get-in result/ebf762ca805b4 [:seon.context/must-reads 0 :doc]) 128),
     :seon.render/operations
       [(doc my.root/merge!)
        (my.program/source 'my.root/merge!)
        (my.program/callers {:seon.program/subject 'my.root/merge!})
        (…2 (get-in result/ebf762ca805b4 [:seon.context/must-reads 0 :seon.render/operations 3]))],
     :arglists
       ([{context :my.program/context, key :seon.task/assignment}]),
     :seon.ref/tests
       [my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts
        my.root-test/a-merge-is-keyed-by-the-assignment
        my.root-test/merging-an-assignment-that-is-not-open-answers-the-refusal-naming-it]}
    {:seon.ref/name seon.db/vector-query-call,
     :seon.ref/callers [seon.db/q seon.db/replay-read],
     :seon.fn/spec
       "[:=> [:cat :map :map :map :map [:vector :map]] [:or [:tuple :map :seon.schema/value] :seon.db/error-result]]",
     :doc
       "Run one encoded Datalog `request`: lowered for vector storage on"…+93 (subs (get-in result/ebf762ca805b4 [:seon.context/must-reads 1 :doc]) 64),
     :seon.render/operations
       [(doc seon.db/vector-query-call)
        (my.program/source 'seon.db/vector-query-call)
        (…2 (get-in result/ebf762ca805b4 [:seon.context/must-reads 1 :seon.render/operations 2]))
        (my.program/tests-reaching
         {:seon.program/subject 'seon.db/vector-query-call})],
     :arglists
       ([declarations logical parsed-query input-values patterns]),
     :seon.ref/tests
       [my.agent-test/settings!-declares-the-dials-it-takes
        my.edit-test/public-entries-declare-resolvable-io-capabilities
        my.fs-test/public-entries-declare-resolvable-io-capabilities
        …395 (drop 3 (get-in result/ebf762ca805b4 [:seon.context/must-reads 1 :seon.ref/tests]))]}
    {:seon.ref/name seon.merge/merge!,
     :seon.fn/spec
       "[:=> [:cat :seon.merge/execution :seon.task/assignment [:maybe :seon.fn/root]] [:or :seon.merge/merged :seon.source/conflict-error :seon.error/value]]",
     :doc
       "Merge assignment `key`'s branch head C onto main through `execut"…+931 (subs (get-in result/ebf762ca805b4 [:seon.context/must-reads 2 :doc]) 64),
     :seon.render/operations
       [(doc seon.merge/merge!)
        (my.program/source 'seon.merge/merge!)
        (…2 (get-in result/ebf762ca805b4 [:seon.context/must-reads 2 :seon.render/operations 2]))
        (my.program/tests-reaching
         {:seon.program/subject 'seon.merge/merge!})],
     :arglists ([execution key root]),
     :seon.ref/callers
       [my.root/merge! seon.cluster.agent/apply-lifecycle!
        seon.merge-test/merged!],
     :seon.ref/tests
       [my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts
        my.root-test/a-merge-is-keyed-by-the-assignment
        my.root-test/merging-an-assignment-that-is-not-open-answers-the-refusal-naming-it
        …8 (drop 3 (get-in result/ebf762ca805b4 [:seon.context/must-reads 2 :seon.ref/tests]))]}
    {:seon.ref/name seon.profile/measure,
     :seon.ref/line 1,
     :seon.fn/spec
       "[:=> [:cat :seon.profile/thunk] :seon.profile/measured-call]",
     :doc
       "Call `f` once and return its val"…+394 (subs (get-in result/ebf762ca805b4 [:seon.context/must-reads 3 :doc]) 32),
     :seon.render/operations
       [(doc seon.profile/measure)
        …3 (drop 1 (get-in result/ebf762ca805b4 [:seon.context/must-reads 3 :seon.render/operations]))],
     :arglists ([f]),
     :seon.ref/callers
       [astra-cost-handoff/preparation astra-cost-handoff/squash-costs
        …8 (drop 2 (get-in result/ebf762ca805b4 [:seon.context/must-reads 3 :seon.ref/callers]))],
     :seon.ref/tests
       [my.program-mutation-test/ns-unalias-of-an-empty-namespace-computes-the-deletion-report-once
        …60 (drop 1 (get-in result/ebf762ca805b4 [:seon.context/must-reads 3 :seon.ref/tests]))]}
    {:seon.ref/name
       my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts,
     :seon.ref/line 1,
     :seon.render/operations
       [(doc
         my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts)
        (my.program/source
         'my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts)]}
    #:seon.ref{:problem-kind :unresolved,
     :path "bin/seon check",
     :line 1}]}}
```
