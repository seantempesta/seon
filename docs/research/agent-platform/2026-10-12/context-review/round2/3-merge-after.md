---
title: "Round 2 block, 3-merge, after"
type: research
status: complete
created: 2026-10-12
---

# 3-merge, after (task handle c6bf2eb4561e)

The current-state block value as the agent's REPL reply shows it (display budget 2,000 on default af0a94471); the finding's value-tokens is the whole block. Method: README.md.

```clojure
#:seon.repl{:findings [{:seon.repl/finding :large-value,
  :seon.eval/value-tokens 3469,
  :seon.eval/shown-tokens 1888,
  :seon.repl/fix
    "A value this big usually means a query or function returns more than it should: select what you need."}
 {:seon.repl/finding :large-allocation,
  :seon.eval/allocated-bytes 537938288,
  :seon.repl/fix
    "Past ten times what an ordinary evaluation allocates: something realized far more than it returned (an unbounded lazy sequence, a whole-store read, a copied collection); realize only what you need. (seon.profile/measure (fn [] ...)) breaks a suspect call down into allocation, GC, armed calls and reads."}], :result result/e67abbc4abc8c, :ms 238, :alloc-mb 537.9, :value #:seon.context{:turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.merge},
 :task
   #:seon.task{:id "c6bf2eb4561e",
    :sym seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts,
    :intent
      "(m6-notes.md, measured with `seon.profile/measure` in a check JVM) A facts merge through `my.root/merge!` costs 6.4 ms and 1 MB warm, but as a fresh JVM's first merge 417 ms and 1.0 GB, 359 ms of it in `seon.db/vector-query-call` (the first three-way and projection reads in `seon.merge/merge!`). The work must follow the changed facts, not the first call: find which first-call derivation (projection read, squash three-way) allocates the gigabyte, and derive it from what the merge reads. Red proof: `my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts` asserts under 100 ms and 64 MB on the thread, red only when it is its JVM's first merge (run alone through `bin/seon check`). Done when it is green alone.",
    :must-read
      #{seon.db/vector-query-call my.root/merge! seon.merge/merge!}},
 :proofs
   #:seon.context{:counts {:pending 1},
    :proof-list
      [{:seon.test/sym
          my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts,
        :seon.context/proof-status :pending,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts}}
  a-jvms-first-facts-merge-follows-the-changed-facts
  ;; red only as its JVM's first merge (run it alone through bin/seon check); a facts merge is warm 6.4 ms and 1 "…+2167 (subs (get-in result/e67abbc4abc8c [:seon.context/proofs :seon.context/proof-list 0 :seon.test/source]) 256)}]},
 :step
   {:seon.task/step :implement,
    :seon.task/status :working,
    :seon.context/start {:seon.render/operations [(in-ns 'seon.merge)]}},
 :must-reads
   [{:seon.ref/name my.root/merge!,
     :doc
       "Merge the open assignment `:seon.task/assignment` into my cluster (`seon.merge/merge!`): its branch's facts, and, when its agent requested the merge, its declarations, gated by the proofs and the tests reaching the change on a fresh merge branch of the head I hold, the files proven against the cluster's checkout (`:seon.config.fs/working-root`).",
     :arglists
       ([{context :my.program/context, key :seon.task/assignment}]),
     :malli/schema
       [:=>
        [:cat :my.root/merge-request-request]
        [:or :seon.merge/merged :seon.source/conflict-error
         :seon.error/value]],
     :seon.render/operations
       [(my.program/source 'my.root/merge!)
        (my.program/callers {:seon.program/subject 'my.root/merge!})
        (my.program/tests-reaching
         {:seon.program/subject 'my.root/merge!})]}
    {:seon.ref/name seon.db/vector-query-call,
     :doc
       "Run one encoded Datalog `request`: lowered for vector storage once ([[lowered-query-request]]), executed with evidence, decoded once (`decode-query-result`).",
     :arglists
       ([declarations logical parsed-query input-values patterns]),
     :malli/schema
       [:=>
        [:cat :map :map :map :map [:vector :map]]
        [:or [:tuple :map :seon.schema/value] :seon.db/error-result]],
     :seon.render/operations
       [(my.program/source 'seon.db/vector-query-call)
        (my.program/callers
         {:seon.program/subject 'seon.db/vector-query-call})
        (my.program/tests-reaching
         {:seon.program/subject 'seon.db/vector-query-call})]}
    {:seon.ref/name seon.merge/merge!,
     :doc
       "Merge assignment `key`'s branch head C onto main through `execution` (root's world: main's connection, and the store it holds or its SCI context's environment holds) as ONE transaction from its merge base (`:seon.task/merge-base`, else the commit its branch forked at): the cluster facts by `merge-tx`, inside the writer, with the base moved to C and the assignment closed by its end; and, when the branch changed declarations main lacks (`seon.cluster.source/unmerged-count`), its source did not end `:sent-back` and no task needs its task, those declarations, gated first: `seon.cluster.source/prepare-merge!` tests them with the proofs of the assignment's task on C (`seon.task/obligations`) an"…+298 (subs (get-in result/e67abbc4abc8c [:seon.context/must-reads 2 :doc]) 697),
     :arglists ([execution key root]),
     :malli/schema
       [:=>
        [:cat
         :seon.merge/execution
         :seon.task/assignment
         [:maybe :seon.fn/root]]
        [:or :seon.merge/merged :seon.source/conflict-error
         :seon.error/value]],
     :seon.render/operations
       [(my.program/source 'seon.merge/merge!)
        (my.program/callers {:seon.program/subject 'seon.merge/merge!})
        (my.program/tests-reaching
         {:seon.program/subject 'seon.merge/merge!})]}
    {:seon.ref/name seon.profile/measure,
     :doc
       "Call `f` once and return its value beside what the call cost, read from JVM counters, so compiled work inside it counts: wall ms"…+298 (subs (get-in result/e67abbc4abc8c [:seon.context/must-reads 3 :doc]) 128),
     :arglists ([f]),
     :malli/schema
       [:=> [:cat :seon.profile/thunk] :seon.profile/measured-call],
     :seon.render/operations
       [(my.program/source 'seon.profile/measure)
        (my.program/callers
         {:seon.program/subject 'seon.profile/measure})
        (my.program/tests-reaching
         {:seon.program/subject 'seon.profile/measure})]}
    {:seon.ref/name
       my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts,
     :seon.test/source
       "(deftest ^{:seon/tasks #{'seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts}}
  a-jvms-first-facts-merge-follows-the"…+2295 (subs (get-in result/e67abbc4abc8c [:seon.context/must-reads 4 :seon.test/source]) 128)}
    #:seon.ref{:problem-kind :unresolved,
     :path "bin/seon check",
     :message
       "Nothing of this name or path exists on your branch. If the task means one to create, create it; else tell root with my.message/send that the task names it."}]}
}
```
