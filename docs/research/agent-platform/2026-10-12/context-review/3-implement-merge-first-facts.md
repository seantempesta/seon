---
title: "Context review 3: the exact first-call model context for seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts"
type: research
status: complete
created: 2026-10-12
---

# Context review 3: seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts

- Task: `seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts` (handle `c6bf2eb4561e`)
- Agent: `ctx-dump` (a fixture agent and attempt written on a `d/with` of default's main, never committed; no provider call; `no-provider` untouched)
- Default: git HEAD 214c61330, cluster `default`, basis-t 536871283 (commit-id `6acce7dd-6423-5f97-8e18-dd61d5d403d5`)
- Assembled by `seon.context/model-context`, laid out by `seon.ai/messages` with wrapping `:chat` (config default), the request carrying default's cluster caps, SCI context and time limit.
- First call: no exchanges yet, so the messages are the system message and one user message.
- Total estimated tokens (`seon.ai.tokens/estimate`, chars/token calibration): **2668**

## Message 1: role system (2290 characters, 715 estimated tokens)

```system
["You are an agent at a REPL on your own branch; only what root merges reaches the cluster."
 "Your task and proofs are in your block: (seon.context/current-state). Send forms; prose goes in ;; comments."
 "Your block names your :seon.context/namespace. Define a function in the namespace that owns it: (defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)); every function has a contract."
 "Change code at the REPL, never in files (no slurp, spit or io): (in-ns 'the.owner.ns) then (defn ...) replaces the function there; root writes files back when it merges."
 "A body's locals exist only inside their binding: call the function on inputs, or redefine it with the probe inside."
 "A test proves a task by naming it in :seon/tasks: (deftest ^{:seon/tasks #{'the.task.ns/name}} increments (is (= 3 (inc 2))))."
 "Register a schema with (seon.schema/register! :comp.order/qty [:int {:min 1}])."
 "Your task's proofs and the tests reaching your change run after each eval request; green proofs end your task and tell root."
 "Find more: (doc my.message/send), (my.program/source 'my.message/send), (dir my.program)."
 "Ask root with my.message/send, :my.message/to \"root\" and :my.message/content."
 "A reply is data: :value, :error, :findings and :result, a live symbol for later forms."
 "Gotcha: never pass or store nil; absence is no key. Fix the caller, not the contract: an optional input is an optional key, [:map [:x {:optional true} :int]], never [:maybe ...]."
 "Gotcha: in Malli [:= nil] and [:enum nil] are errors (nil lands in the properties slot); a literal is [:= {} nil]. A contract is [:=> [:cat in1 in2] out], inputs inside :cat."
 "Gotcha: a keyword lookup is falsy for absent, nil and false alike; test presence with (contains? m :k) or (find m :k)."
 "Gotcha: (some f xs) returns f's first truthy result, not the element: (some (fn [x] (when (even? x) x)) [1 2 3]) is 2."
 "Gotcha: map and filter are lazy and escape (binding ...) and try; use mapv, into or run! there."
 "Gotcha: write fully qualified symbols, (seon.db/q ...); an alias works only if (ns-aliases *ns*) shows it. #() literals do not nest."
 "Gotcha: an error is a map; read :seon.error/type, :seon.error/message and :seon.error/problems, then change the form; never resend it unchanged."]
```

## Message 2: role user (6251 characters, 1953 estimated tokens)

```user
#:seon.context{:task
   #:seon.task{:sym
      seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts,
    :intent
      "(m6-notes.md, measured with `seon.profile/measure` in a check JVM) A facts merge through `my.root/merge!` costs 6.4 ms and 1 MB warm, but as a fresh JVM's first merge 417 ms and 1.0 GB, 359 ms of it in `seon.db/vector-query-call` (the first three-way and projection reads in `seon.merge/merge!`). The work must follow the changed facts, not the first call: find which first-call derivation (projection read, squash three-way) allocates the gigabyte, and derive it from what the merge reads. Red proof: `my.root-t"…+210,
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
  ;; red only as its JVM's first merge (run it alone through bin/seon check); a facts merge is warm 6.4 ms and 1 "…+2167}]},
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
       "Merge the open assignment `:seon.task/assignment` into my cluster (`seon.merge/merge!`): its branch's facts, and, when its agent requested the merge, its declarations, gated by the proofs and the tests reaching the change on a fresh merge branch of the hea"…+91,
     :seon.render/operations
       [(doc my.root/merge!)
        (my.program/source 'my.root/merge!)
        (my.program/callers …1)
        (my.program/tests-reaching …1)],
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
       "Run one encoded Datalog `request`: lowered for vector storage once ([[lowered-query-request]]), executed with evidence, decoded once (`decode-query-result`).",
     :seon.render/operations
       [(doc seon.db/vector-query-call)
        (my.program/source …1)
        (my.program/callers …1)
        …1],
     :arglists
       ([declarations logical parsed-query input-values patterns]),
     :seon.ref/tests
       [my.agent-test/settings!-declares-the-dials-it-takes
        my.edit-test/public-entries-declare-resolvable-io-capabilities
        my.fs-test/public-entries-declare-resolvable-io-capabilities
        my.message-test/inbox-lists-this-agents-messages-newest-last
        my.message-test/received!-acknowledges-once-and-leaves-the-unread-set
        my.program-mutation-test/namespace-and-alias-retraction-write-before-native-mutation
        my.program-query-test/predicate-arguments-are-bindings-not-nested-clojure-evaluations
        …390]}
    {:seon.ref/name seon.merge/merge!,
     :seon.fn/spec
       "[:=> [:cat :seon.merge/execution :seon.task/assignment [:maybe :seon.fn/root]] [:or :seon.merge/merged :seon.source/conflict-error :seon.error/value]]",
     :doc
       "Merge assignment `key`'s branch head C onto main through `execution` (root's world: main's connection, and the store it holds or"…+770,
     :seon.render/operations
       [(doc seon.merge/merge!)
        (my.program/source …1)
        (my.program/callers …1)
        …1],
     :arglists ([execution key root]),
     :seon.ref/callers
       [my.root/merge! seon.cluster.agent/apply-lifecycle!
        seon.merge-test/merged!],
     :seon.ref/tests
       [my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts
        my.root-test/a-merge-is-keyed-by-the-assignment
        my.root-test/merging-an-assignment-that-is-not-open-answers-the-refusal-naming-it
        seon.cluster.db-flow-test/a-merged-assignment-whose-graph-survived-a-crash-is-stopped-and-deleted
        seon.cluster.db-flow-test/an-error-end-copies-nothing-and-writes-roots-problem-task
        seon.cluster.registry-test/a-deleted-attempts-storage-is-reclaimed-by-the-next-collection
        seon.merge-test/a-facts-only-merge-is-never-due-for-publication
        …4]}
    {:seon.ref/name seon.profile/measure,
     :seon.ref/line 1,
     :seon.fn/spec
       "[:=> [:cat :seon.profile/thunk] :seon.profile/measured-call]",
     :doc
       "Call `f` once and return its value beside what the call cost, read from JVM counters, so compiled work inside it counts: wall ms"…+298,
     :seon.render/operations [(doc seon.profile/measure) …3],
     :arglists ([f]),
     :seon.ref/callers
       [astra-cost-handoff/preparation astra-cost-handoff/squash-costs
        seon.cluster.store-test/measured-small-commit …7],
     :seon.ref/tests
       [my.program-mutation-test/ns-unalias-of-an-empty-namespace-computes-the-deletion-report-once
        my.program-mutation-test/ns-unmap-of-a-one-function-namespace-computes-the-deletion-report-once
        my.program-test/breaks-on-a-namespace-walks-the-program-once …58]}
    {:seon.ref/name
       my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts,
     :seon.ref/line 1,
     :seon.render/operations
       [(doc
         my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts)
        …1]}
    #:seon.ref{:problem-kind :unresolved,
     :path "bin/seon check",
     :line 1}],
 :turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.merge}}
```

