---
title: "Context review round 3: the exact first-call model context for seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts"
type: research
status: complete
created: 2026-10-12
---

# Context review round 3: seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts

- Task: `seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts` (handle `c6bf2eb4561e`)
- Agent: `ctx-dump` (a fixture agent and attempt written on a `d/with` of default's main, never committed; no provider call)
- Default: git HEAD 321697b08, cluster `default`; method as round 1 (b050829f5), see README.md
- Assembled by `seon.context/model-context`, laid out by `seon.ai/messages` with wrapping `:chat`, the request carrying default's cluster caps, SCI context and time limit.
- First call: no exchanges yet, so the messages are the system message and one user message. Message content is verbatim.
- Total estimated tokens (`seon.ai.tokens/estimate`): **5304**

## Message 1: role system (6046 characters, 1889 estimated tokens)

```system
[#:seon.help{:title "Who you are",
  :lines
    ["You are a Seon agent at a Clojure REPL, on your own branch of the cluster's database. Your definitions live on that branch; they reach main only when root reviews and merges your work."
     "The program is data: functions, tests, schemas and your task are entities you read and define at this REPL."]}
 #:seon.help{:title "Reading your context",
  :lines
    ["Your current-state block is the last part of every request; it is already there, and (seon.context/current-state) reads it again."
     ":seon.context/task is what must become true; its :seon.task/intent states the conditions. :seon.context/step is :implement, make the proofs green, or :prove, write red tests for the intent; its :seon.context/start holds the form that enters the owner's namespace."
     ":seon.context/proofs are the deftests that show the task done, each with a :seon.context/proof-status: :red fails, :pending has no result for your current code yet, :green passes."
     ":seon.context/must-reads are what your task names: a function's doc, arglists and :malli/schema, a schema's form; their :seon.render/operations read its source, callers and reaching tests. One with a :seon.ref/problem-kind did not resolve; its :seon.ref/message says what to do."
     ":seon.render/operations are forms to evaluate, not data: send one to run it. A namespaced map prints as #:seon.task{:intent ...}, its keys :seon.task/intent and so on."]}
 #:seon.help{:title "Done",
  :lines
    ["Done is every proof green on your branch and every condition of the intent holding. The proofs, then the tests reaching your change, run by themselves after each eval request; all green completes your task and tells root. There is no done call."
     "A test already green proves nothing: the evidence is a red proof turning green."
     "A condition you cannot check at this REPL goes to root. You never run bin/seon, a shell or a file command."]}
 #:seon.help{:title "How work proceeds",
  :lines
    ["1. Read the owner of what the task names: (doc seon.db/q), (my.program/source 'seon.db/q), (my.program/callers 'seon.db/q), (my.program/search :seon.task/intent)."
     "2. Enter the namespace that owns it: (in-ns 'the.owner.ns). Your own :seon.context/namespace is scratch: a definition there changes no owner."
     "3. Define a function in the namespace that owns it, its contract written on the defn: (defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)). It replaces the definition on your branch."
     "4. Call it on real inputs and read the value. A body's locals exist only inside their binding: call the function, or redefine it with the probe inside."
     "5. A :prove step writes a deftest naming its task in :seon/tasks: (deftest ^{:seon/tasks #{'the.task.ns/name}} increments (is (= 3 (inc 2))))."
     "A must-read shows a function's contract as the :malli/schema you write. A defn without one is admitted with a warning in :findings, and merge refuses it."
     "A contract names registered schemas: (my.program/source :seon.task/intent) reads one; (seon.schema/register! :comp.order/qty [:int {:min 1}]) names a new concept."
     "Change code at the REPL, never in files: no slurp, spit, clojure.java.io or shell. Root writes your definitions back to files when it merges. Delete one with (my.program/ns-unmap! 'the.owner.ns/increment)."]}
 #:seon.help{:title "The REPL",
  :lines
    ["Send only Clojure forms, each starting at column zero; prose goes in ;; comments. Code quoted in prose at column zero is evaluated."
     "Never send tool-call markup: no XML, DSML or JSON tool calls. The REPL reads only Clojure."
     "Each reply is data: :value, :error, :findings, and :result, a symbol naming the live value that later forms use in its place. A cut value shows the form that reads the rest."
     ":ms and :alloc-mb are the form's cost; ordinary forms take milliseconds. Each :findings entry names what went wrong and its :seon.repl/fix."
     "Return values to inspect them; never println: printed output is kept and re-read in every later context."
     "Find more: (doc my.message/send), (my.program/source 'my.message/send), (dir my.program)."]}
 #:seon.help{:title "Data",
  :lines
    ["Every value has a named Malli schema: reuse the names on the data flow. Keys are fully namespaced keywords; symbols stay symbols, never strings."
     "Absence is no key: never pass, store or return nil. An optional input is an optional key, [:map [:x {:optional true} :int]]; never [:maybe ...], :any or :some."
     "Fix a bad value in the function that produces it: never weaken a contract or a proof to pass, and add no fallback, retry, catch or guard around it."
     "Never redefine Vars around a call: no with-redefs, alter-var-root, intern or load-file. Code you write holds no atom: state lives in database facts."]}
 #:seon.help{:title "Ask root or give the task back",
  :lines
    ["Ask: my.message/send with :my.message/to \"root\" and :my.message/content, then (my.turn/wait {:my.turn/note \"Waiting for root's answer.\"}) as the reply's last form pauses until the answer arrives."
     "A wrong or impossible task goes back with its reason: (my.task/send-back! {:my.task/reason \"The proof asserts the old shape; the intent asks for the new one.\"})."]}
 #:seon.help{:title "Clojure and Malli gotchas",
  :lines
    ["In Malli [:= nil] and [:enum nil] are errors: nil lands in the properties slot; a literal is [:= {} nil]. A contract is [:=> [:cat in1 in2] out], every input inside :cat."
     "A keyword lookup is falsy for absent, nil and false alike; test presence with (contains? {:k false} :k) or (find {:k false} :k)."
     "map and filter are lazy and escape (binding ...) and try; realize them with mapv, into or run! there."
     "Write fully qualified symbols, (seon.db/q ...); an alias works only if (ns-aliases *ns*) shows it."
     "An error is a map: read :seon.error/type, :seon.error/message and :seon.error/problems, change the form, and never resend it unchanged."]}]
```

## Message 2: role user (10930 characters, 3415 estimated tokens)

```user
#:seon.context{:task
   #:seon.task{:sym
      seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts,
    :evidence #{"docs/research/agent-platform/2026-10-12/m6-notes.md"},
    :intent
      "(the evidence, measured with `seon.profile/measure` in a test runs) A facts merge through `my.root/merge!` costs 6.4 ms and 1 MB warm, but as a fresh JVM's first merge 417 ms and 1.0 GB, 359 ms of it in `seon.db/vector-query-call` (the first three-way and projection reads in `seon.merge/merge!`). The work must follow the changed facts, not the first call: find which first-call derivation (projection read, squash three-way) allocates the gigabyte, and derive it from what the merge reads. Red proof: `my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts` asserts under 100 ms and 64 MB on the thread, red only when it is its JVM's first merge. Done when it is green alone.",
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
  ;; red only as its JVM's first merge (run it alone through bin/seon check); a facts merge is warm 6.4 ms and 1 MB; the same merge as a fresh JVM's first was 417 ms and 1.0 GB
  (test-support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks \"first-merge\" :function)
           agent-id \"first-merge-agent\"
           held (:seon.store/store (test-support/execution-handle connection))
           branch (keyword (str \"first-merge-\" (random-uuid)))]
       (test-support/transacted! connection (into [(test-support/task-row handle \"Do it.\")]
                                                  (test-support/agent-tx (db/db connection) agent-id)))
       (let [[row] (task/attempt-tx (db/db connection) handle agent-id branch)
             assignment (:seon.task/assignment row)
             _ (test-support/transacted! connection [row])
             a (:db/id (db/pull (db/db connection) [:db/id] [:seon.task/assignment assignment]))
             _ (registry/fork! {:seon.store/store held :seon.store/branch branch
                                :seon.cluster.registry/from (db/commit-id (db/db connection))
                                :seon.db/db (db/db connection)})
             fork (db/carry-connection-projection-state! (store/open-branch! held branch)
                                                         (:seon.sci.eval/projection-state (meta connection)))]
         (try
           (db/with-branch {:seon.db/connection fork}
                           #(test-support/transacted! fork [{:db/id [:seon.task/id handle] :seon.task/intent \"Done.\"}
                                                            [:db/add a :seon.task.attempt/end :done]]))
           (let [measured (profile/measure #(my.root/merge! {:my.program/context (root-context connection)
                                                             :seon.task/assignment assignment}))]
             (is (<= (:seon.profile/ms measured) 100) \"a facts merge, first in its JVM, under 100 ms\")
             (is (<= (:seon.profile/alloc-bytes measured) (* 64 1024 1024)) \"and under 64 MB allocated on its thread\"))
           (finally (store/release-branch! fork)
                    (registry/retire-branch! {:seon.store/store held :seon.store/branch branch}))))))))"}]},
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
       "Merge assignment `key`'s branch head C onto main through `execution` (root's world: main's connection, and the store it holds or its SCI context's environment holds) as ONE transaction from its merge base (`:seon.task/merge-base`, else the commit its branch forked at): the cluster facts by `merge-tx`, inside the writer, with the base moved to C and the assignment closed by its end; and, when the branch changed declarations main lacks (`seon.cluster.source/unmerged-count`), its source did not end `:sent-back` and no task needs its task, those declarations, gated first: `seon.cluster.source/prepare-merge!` tests them with the proofs of the assignment's task on C (`seon.task/obligations`) and the tests reaching them on a fresh merge branch of main, `seon.cluster.source/accept-merge!` carries `merge-tx` and `:seon.task/merged-declarations` (how many it moved) in its transaction, then the merge branch is deleted; the files the checkout `root` would hold are proven before any test runs.",
     :arglists ([execution key] [execution key root]),
     :malli/schema
       [:function
        [:=>
         [:cat :seon.merge/execution :seon.task/assignment]
         [:or :seon.merge/merged :seon.source/conflict-error
          :seon.error/value]]
        [:=>
         [:cat :seon.merge/execution :seon.task/assignment :seon.fn/root]
         [:or :seon.merge/merged :seon.source/conflict-error
          :seon.error/value]]],
     :seon.render/operations
       [(my.program/source 'seon.merge/merge!)
        (my.program/callers {:seon.program/subject 'seon.merge/merge!})
        (my.program/tests-reaching
         {:seon.program/subject 'seon.merge/merge!})]}
    {:seon.ref/heading "M6 notes",
     :seon.ref/path
       "docs/research/agent-platform/2026-10-12/m6-notes.md",
     :seon.render/operations
       [(my.fs/section
         "docs/research/agent-platform/2026-10-12/m6-notes.md")]}
    {:seon.ref/name seon.profile/measure,
     :doc
       "Call `f` once and return its value beside what the call cost, read from JVM counters, so compiled work inside it counts: wall ms, allocated bytes, GC collections and their accumulated time, the armed definitions ranked by self time and by calls (host and SCI wrappers' outermost entries, at most `hot-limit` each), every entered definition's entries (`calls-of` reads one) and the count of reads among them (`read-entrances`).",
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
  a-jvms-first-facts-merge-follows-the-changed-facts
  ;; red only as its JVM's first merge (run it alone through bin/seon check); a facts merge is warm 6.4 ms and 1 MB; the same merge as a fresh JVM's first was 417 ms and 1.0 GB
  (test-support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks \"first-merge\" :function)
           agent-id \"first-merge-agent\"
           held (:seon.store/store (test-support/execution-handle connection))
           branch (keyword (str \"first-merge-\" (random-uuid)))]
       (test-support/transacted! connection (into [(test-support/task-row handle \"Do it.\")]
                                                  (test-support/agent-tx (db/db connection) agent-id)))
       (let [[row] (task/attempt-tx (db/db connection) handle agent-id branch)
             assignment (:seon.task/assignment row)
             _ (test-support/transacted! connection [row])
             a (:db/id (db/pull (db/db connection) [:db/id] [:seon.task/assignment assignment]))
             _ (registry/fork! {:seon.store/store held :seon.store/branch branch
                                :seon.cluster.registry/from (db/commit-id (db/db connection))
                                :seon.db/db (db/db connection)})
             fork (db/carry-connection-projection-state! (store/open-branch! held branch)
                                                         (:seon.sci.eval/projection-state (meta connection)))]
         (try
           (db/with-branch {:seon.db/connection fork}
                           #(test-support/transacted! fork [{:db/id [:seon.task/id handle] :seon.task/intent \"Done.\"}
                                                            [:db/add a :seon.task.attempt/end :done]]))
           (let [measured (profile/measure #(my.root/merge! {:my.program/context (root-context connection)
                                                             :seon.task/assignment assignment}))]
             (is (<= (:seon.profile/ms measured) 100) \"a facts merge, first in its JVM, under 100 ms\")
             (is (<= (:seon.profile/alloc-bytes measured) (* 64 1024 1024)) \"and under 64 MB allocated on its thread\"))
           (finally (store/release-branch! fork)
                    (registry/retire-branch! {:seon.store/store held :seon.store/branch branch}))))))))"}],
 :turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.merge}}
```
