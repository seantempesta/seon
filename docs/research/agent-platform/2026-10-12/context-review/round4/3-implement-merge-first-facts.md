---
title: "Context review round 4: the exact first-call model context for seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts"
type: research
status: complete
created: 2026-10-12
---

# Context review round 4: seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts

- Task: `seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts` (handle `d0e5e8bfa338`)
- Agent: `ctx-dump` (a fixture agent and attempt written on a `d/with` of default's main, never committed; no provider call)
- Default: git HEAD 8d14faeee, cluster `default`; method as round 3 (a8d4a784e), see README.md
- Assembled by `seon.context/model-context`, laid out by `seon.ai/messages` with wrapping `:chat`, the request carrying default's cluster caps, SCI context and time limit.
- First call: no exchanges yet, so the messages are the system message and one user message. Message content is verbatim.
- Total estimated tokens (`seon.ai.tokens/estimate`): **8255**

## Message 1: role system (6920 characters, 2162 estimated tokens)

```system
[#:seon.help{:title "Who you are",
  :lines
    ["You are a Seon agent at a Clojure REPL, on your own branch of the cluster's database. Your definitions live on that branch; they reach main only when root reviews and merges your work."
     "The program is data: functions, tests, schemas and your task are entities you read and define at this REPL."]}
 #:seon.help{:title "Reading your context",
  :lines
    ["Your current-state block is the last part of every request; it is already there, and (seon.context/current-state) reads it again."
     ":seon.context/task is what must become true; its :seon.task/intent states the conditions. :seon.context/step is :implement, make the proofs green, or :prove, write red tests for the intent; its :seon.context/start holds the form that enters the owner's namespace."
     ":seon.context/proofs are the deftests that show the task done, each with its :seon.test/source and a :seon.context/proof-status: :red fails, with its :seon.context/proof-failures, :pending has no result for your current code yet, :green passes. They run after each eval request and the next block shows their status."
     ":seon.context/must-reads are what your task's intent cites in backticks, each resolved once under :seon.ref/name or :seon.ref/path: a function's :seon.fn/source and :malli/schema, a schema's :seon.ref/schema-form, a test's :seon.test/source, a task's :seon.task/intent, a document's heading. Their :seon.ref/references are the names their own content cites, each a summary and its read; :seon.render/operations read the rest. One with a :seon.ref/problem-kind did not resolve; its :seon.ref/message says what to do."
     ":seon.context/turns holds :my.agent/turns-left, the model calls you have left, and :seon.context/namespace, the namespace you are in now."
     ":seon.render/operations are forms to evaluate, not data: send one to run it. A namespaced map prints as #:seon.task{:intent ...}, its keys :seon.task/intent and so on."]}
 #:seon.help{:title "Done",
  :lines
    ["Done is every proof green on your branch and every condition of the intent holding. The proofs, then the tests reaching your change, run by themselves after each eval request; all green completes your task and tells root. There is no done call."
     "A test already green proves nothing: the evidence is a red proof turning green."
     "A condition you cannot check at this REPL goes to root. You never run bin/seon, a shell or a file command."]}
 #:seon.help{:title "How work proceeds",
  :lines
    ["1. Read the owner of what the task names: (doc seon.db/q), (my.program/source 'seon.db/q), (my.program/callers 'seon.db/q), (my.program/search :seon.task/intent)."
     "2. Enter the namespace that owns it: (in-ns 'the.owner.ns). A definition changes the namespace it is evaluated in."
     "3. Define a function in the namespace that owns it, its contract written on the defn: (defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)). It replaces the definition on your branch."
     "4. Call it on real inputs and read the value. A body's locals exist only inside their binding: call the function, or redefine it with the probe inside."
     "5. A :prove step writes a deftest naming its task in :seon/tasks: (deftest ^{:seon/tasks #{'the.task.ns/name}} increments (is (= 3 (inc 2))))."
     "A resolved function reference shows a function's contract as the :malli/schema you write. A defn without one is admitted with a warning in :findings, and merge refuses it."
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
     "Write what you learn into your task: (my.task/refine! {:seon.task/intent \"Make `seon.db/q` answer its refusal; see docs/seon/specs/merge.md#1-the-merge-request.\"}) rewrites its intent on your branch. A reference is a backticked fully qualified symbol or keyword, a task `ns/task-name`, or a path with a #heading anchor; your next block resolves each one."
     "A wrong or impossible task goes back with its reason: (my.task/send-back! {:my.task/reason \"The proof asserts the old shape; the intent asks for the new one.\"})."]}
 #:seon.help{:title "Clojure and Malli gotchas",
  :lines
    ["In Malli [:= nil] and [:enum nil] are errors: nil lands in the properties slot; a literal is [:= {} nil]. A contract is [:=> [:cat in1 in2] out], every input inside :cat."
     "A keyword lookup is falsy for absent, nil and false alike; test presence with (contains? {:k false} :k) or (find {:k false} :k)."
     "map and filter are lazy and escape (binding ...) and try; realize them with mapv, into or run! there."
     "Write fully qualified symbols, (seon.db/q ...); an alias works only if (ns-aliases *ns*) shows it."
     "An error is a map: read :seon.error/type, :seon.error/message and :seon.error/problems, change the form, and never resend it unchanged."]}]
```

## Message 2: role user (19500 characters, 6093 estimated tokens)

```user
#:seon.context{:task
   #:seon.task{:sym
      seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts,
    :intent
      "(the evidence, measured with `seon.profile/measure` in a test runs) A facts merge through `my.root/merge!` costs 6.4 ms and 1 MB warm, but as a fresh JVM's first merge 417 ms and 1.0 GB, 359 ms of it in `seon.db/vector-query-call` (the first three-way and projection reads in `seon.merge/merge!`). The work must follow the changed facts, not the first call: find which first-call derivation (projection read, squash three-way) allocates the gigabyte, and derive it from what the merge reads. Red proof: `my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts` asserts under 100 ms and 64 MB on the thread, red only when it is its JVM's first merge. Done when it is green alone. Read `docs/research/agent-platform/2026-10-12/m6-notes.md`."},
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
     (let [handle (task/task-handle 'fixture.tasks \"first-merge\")
           agent-id \"first-merge-agent\"
           held (:seon.store/store (test-support/execution-handle connection))
           branch (keyword (str \"first-merge-\" (random-uuid)))]
       (test-support/transacted! connection (into [(test-support/task-row handle \"Do it.\")]
                                                  (test-support/agent-tx (db/db connection) agent-id)))
       (let [[row] (task/attempt-tx (db/db connection) handle agent-id branch)
             assignment (:seon.task/assignment row)
             _ (test-support/transacted! connection [row])
             a (:db"…+1389,
        :seon.ref/references
          [{:seon.ref/name my.root-test/root-context,
            :doc
              "Root's supplied world on `connection`, as call preparation hands it to my.root.",
            :seon.render/operations
              [(my.program/source 'my.root-test/root-context)]}
           {:seon.ref/name my.root/merge!,
            :doc
              "Merge the open assignment `:seon.task/assignment` into my cluster (`seon.merge/merge!`): its branch's facts, and, when its agent requested the merge, its declarations, gated by the proofs and the tests reaching the change on a fresh merge branch of the hea"…+91,
            :seon.render/operations
              [(my.program/source 'my.root/merge!)]}
           {:seon.ref/name seon.cluster.registry/fork!,
            :doc
              "Create a work branch as `branch!` does and, when this call created it, retain its immutable base commit in `base-branch` and write its fork entity (`branch-fork-entity`): its merge base toward `:seon.branch/target` (default: the parent) until a merge advan"…+7,
            :seon.render/operations
              [(my.program/source 'seon.cluster.registry/fork!)]}
           {:seon.ref/name seon.cluster.registry/retire-branch!,
            :doc
              "Remove a branch and its retained base ref from the store's branches.",
            :seon.render/operations [(my.program/source …1)]}
           {:seon.ref/name seon.cluster.store/open-branch!,
            :doc
              "A connection to one branch of this already-open, flock-held store.",
            :seon.render/operations [(my.program/source …1)]}
           {:seon.ref/name seon.cluster.store/release-branch!,
            :doc
              "Release one proof branch connection before its branch is retired.",
            :seon.render/operations [(my.program/source …1)]}
           {:seon.ref/name seon.db/carry-connection-projection-state!,
            :doc
              "Attach the owning projection state to a live connection at construction.",
            :seon.render/operations [(my.program/source …1)]}
           {:seon.ref/name seon.db/commit-id,
            :doc "Commit ID of an explicit or current database value.",
            :seon.render/operations [(my.program/source …1)]}
           {:seon.ref/name seon.db/db,
            :doc
              "Current immutable database value for an explicit or ambient conn"…+7,
            :seon.render/operations [(my.program/source …1)]}
           {:seon.ref/name seon.db/pull,
            :doc
              "Pull one entity over an explicit or current database value.",
            :seon.render/operations [(my.program/source …1)]}
           {:seon.ref/name seon.db/with-branch,
            :doc
              "Call `f` with EXACTLY the checked-out branch its caller handed o"…+4,
            :seon.render/operations [(my.program/source …1)]}
           {:seon.ref/name seon.profile/measure,
            :doc
              "Call `f` once and return its value beside what the call cost, re"…+362,
            :seon.render/operations [(my.program/source …1)]}
           {:seon.ref/name seon.task/attempt-tx,
            :doc
              "The transaction data writing the next attempt of task `handle` b"…+321,
            :seon.render/operations [(…2)]}
           {:seon.ref/name seon.task/task-handle,
            :doc
              "The task handle of an identifying aspect: seon.id/id over [ns na"…+11,
            :seon.render/operations [(…2)]}
           {:seon.ref/name seon.test-support/agent-tx,
            :doc
              "Create one fixture agent through `seon.cluster.agent/creation-tx`.",
            :seon.render/operations [(…2)]}
           {:seon.ref/name seon.test-support/execution-handle,
            :doc "The executing test's handle: the"…+141,
            :seon.render/operations […1]}
           {:seon.ref/name seon.test-support/task-row,
            :doc "A complete task entity for fixtu"…+113,
            :seon.render/operations […1]}
           {:seon.ref/name seon.test-support/transacted!,
            :doc "Transact fixture data through th"…+37,
            :seon.render/operations […1]}
           {:seon.ref/name seon.test-support/with-database,
            :doc "Run `body` on an isolated branch"…+34,
            :seon.render/operations […1]}]}]},
 :step
   {:seon.task/step :implement,
    :seon.task/status :working,
    :seon.context/start {:seon.render/operations [(in-ns 'seon.merge)]}},
 :must-reads
   [{:seon.ref/name seon.profile/measure,
     :seon.fn/source
       "(defn measure
  \"Call `f` once and return its value beside what the call cost, read from
  JVM counters, so compiled work inside it counts: wall ms, allocated bytes,
  GC collections and their accumulated time, the armed definitions ranked by
  self time and by calls (host and SCI wrappers' outermost entries, at most
  `hot-limit` each), every entered definition's entries (`calls-of` reads one)
  and the count of reads among them (`read-entrances`). A thrown exception
  propagates.

  Every counter but the allocation is JVM-wide over the call's window, so
  concurrent unrelated work counts too. The allocation is the calling platform
  thread's own (`:seon.profile/alloc-scope :thread`) and misses work it hands
  to other threads; on a virtual thread, which has no count, it is absent.
  `:seon.cost/jvm-window` also reports all-thread allocation and process CPU,
  explicitly including unrelated concurrent work, GC and compilation.\"
  {:malli/schema [:=> [:cat :seon.profile/thunk] :seon.profile/measured-call]}
  [f]
  (let [;; local functions, not armed ones: the second reading's own calls
        ;; would otherwise count as the measured call's
        mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
        allocated (fn [] (.getCurrentThreadAllocatedBytes mx))
        collected (fn [] (reduce (fn [[n ms] ^java.lang.management.GarbageCollectorMXBean gc-bean]
                                   [(+ n (max 0 (.getCollectionCount gc-bean))) (+ ms (max 0 (.getCollectionTime gc-bean)))])
                                 [0 0] (java.lang.management.ManagementFactory/getGarbageCollectorMXBeans)))
        ;; symbol -> [entries self-nanos], summed over the symbol's cells
        entries (fn [] (persistent!
                        (reduce (fn [m ^java.util.Set members]
                                  (reduce (fn [m held]
                                            (let [n (.sum ^LongAdder (:seon.profile/entry-counter held))
                                                  sym (:seo"…+2381,
     :malli/schema
       [:=> [:cat :seon.profile/thunk] :seon.profile/measured-call],
     :seon.render/operations
       [(my.program/callers 'seon.profile/measure)
        (my.program/tests-reaching 'seon.profile/measure)],
     :seon.ref/references
       [{:seon.ref/name :seon.cost/jvm-window,
         :seon.render/operations
           [(my.program/source :seon.cost/jvm-window)]}
        {:seon.ref/name seon.batch/jvm-counters,
         :doc
           "JVM-wide heap allocation and process CPU counters; unavailable readings stay negative.",
         :seon.render/operations
           [(my.program/source 'seon.batch/jvm-counters)]}
        {:seon.ref/name seon.batch/jvm-window,
         :doc
           "Counter deltas across all threads, including unrelated work, GC and compilation.",
         :seon.render/operations
           [(my.program/source 'seon.batch/jvm-window)]}
        {:seon.ref/name seon.profile/cells-by-key,
         :seon.render/operations
           [(my.program/source 'seon.profile/cells-by-key)]}
        {:seon.ref/name seon.profile/hot-limit,
         :doc "Definitions `measure` ranks by self time and by calls.",
         :seon.render/operations
           [(my.program/source 'seon.profile/hot-limit)]}
        {:seon.ref/name seon.profile/read-entrances,
         :doc
           "The `seon.db` functions that read a database value: the armed calls `measure` counts as reads.",
         :seon.render/operations
           [(my.program/source 'seon.profile/read-entrances)]}]}
    {:seon.ref/name my.root/merge!,
     :seon.fn/source
       "(defn merge!
  \"Merge the open assignment `:seon.task/assignment` into my cluster (`seon.merge/merge!`): its branch's facts,
  and, when its agent requested the merge, its declarations, gated by the proofs and the tests reaching the
  change on a fresh merge branch of the head I hold, the files proven against the cluster's checkout
  (`:seon.config.fs/working-root`). The assignment closes `:merged` in the merge's transaction; the next
  lifecycle pass publishes its declarations into the checkout in one git commit (`seon.merge/publish!`), then
  deletes its branch. Answers the merge, or the first refusal as data; a red gate leaves the assignment open.

  Example:
  (my.root/merge! {:seon.task/assignment \\\"0123456789ab\\\"})\"
  {:malli/schema [:=> [:cat :my.root/merge-request-request]
                  [:or :seon.merge/merged :seon.source/conflict-error :seon.error/value]]}
  [{context :my.program/context key :seon.task/assignment}]
  (let [database (db/db (:seon.db/connection context))
        open (open-assignment database key 'my.root/merge!)
        checkout (when-not (:seon.error/at open) (some->> (:seon.cluster/name context) (merge/checkout database)))]
    (if (:seon.error/at open)
      open
      (if (string? checkout) (merge/merge! context key checkout) (merge/merge! context key)))))",
     :malli/schema
       [:=>
        [:cat :my.root/merge-request-request]
        [:or :seon.merge/merged :seon.source/conflict-error
         :seon.error/value]],
     :seon.render/operations
       [(my.program/callers 'my.root/merge!)
        (my.program/tests-reaching 'my.root/merge!)],
     :seon.ref/references
       [{:seon.ref/name :seon.task/assignment,
         :seon.render/operations
           [(my.program/source :seon.task/assignment)]}
        {:seon.ref/name seon.merge/merge!,
         :doc
           "Merge assignment `key`'s branch head C onto main through `execution` (root's world: main's connection, and the store it holds or its SCI context's environment holds) as ONE transaction from its merge base (`:seon.task/merge-base`, else the commit its branc"…+739,
         :seon.render/operations
           [(my.program/source 'seon.merge/merge!)]}
        {:seon.ref/name :seon.config.fs/working-root,
         :seon.render/operations
           [(my.program/source :seon.config.fs/working-root)]}
        {:seon.ref/name seon.merge/publish!,
         :doc
           "Publish assignment `key`'s merged declarations into the cluster's checkout (`checkout`) through `execution` (root's world: main'"…+421,
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name my.root/open-assignment,
         :doc
           "Assignment `key` on my cluster when it records no outcome, else the refusal naming it.",
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.db/db,
         :doc
           "Current immutable database value for an explicit or ambient connection.",
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.merge/checkout,
         :doc
           "The checkout cluster `cluster-name` publishes merged declarations into, as a canonical path: its `:seon.config.fs/working-root` "…+59,
         :seon.render/operations [(my.program/source …1)]}]}
    {:seon.ref/name seon.db/vector-query-call,
     :seon.fn/source
       "(defn- vector-query-call
  \"Run one encoded Datalog `request`: lowered for vector storage once
   ([[lowered-query-request]]), executed with evidence, decoded once
   (`decode-query-result`). Answers `[response result]`, or the refusal of
   `get-else`/`get-some` over a vector attribute. `q` and `replay-read` both
   run through it.\"
  {:malli/schema [:=> [:cat :map :map :map :map [:vector :map]]
                  [:or [:tuple :map :seon.schema/value] :seon.db/error-result]]}
  [declarations logical parsed-query input-values patterns]
  (let [variable-attributes (query-variable-attributes patterns input-values)
        encoded (encode-query-request declarations logical parsed-query input-values variable-attributes patterns)
        projection (when (may-read-vectors? (::installed-schema declarations) parsed-query encoded patterns)
                     @(::read-projection declarations))
        lowered (if-not projection
                  [encoded {}]
                  (try
                    (lowered-query-r"…+1133,
     :malli/schema
       [:=>
        [:cat :map :map :map :map [:vector :map]]
        [:or [:tuple :map :seon.schema/value] :seon.db/error-result]],
     :seon.render/operations
       [(my.program/callers 'seon.db/vector-query-call)
        (my.program/tests-reaching 'seon.db/vector-query-call)],
     :seon.ref/references
       [{:seon.ref/name seon.db/decode-query-result,
         :doc
           "`result` decoded column by column: EDN slots, and each find element in `pulls` (index -> logical pull spec) to its logical vectors.",
         :seon.render/operations
           [(my.program/source 'seon.db/decode-query-result)]}
        {:seon.ref/name seon.db/diagnostic,
         :seon.render/operations
           [(my.program/source 'seon.db/diagnostic)]}
        {:seon.ref/name seon.db/encode-query-request,
         :doc
           "Encode values at parsed Datalog value positions, using the write codec.",
         :seon.render/operations
           [(my.program/source 'seon.db/encode-query-request)]}
        {:seon.ref/name seon.db/lowered-query-request,
         :doc
           "`encoded` read through vector storage, from `parsed-query`, the parse of the logical query: every data pattern over a vector att"…+167,
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.db/may-read-vectors?,
         :doc
           "Whether a query can read vector storage, judged from the installed schema alone, so a query over cardinality-one attributes neve"…+221,
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.db/query-variable-attributes,
         :seon.render/operations [(my.program/source …1)]}]}
    {:seon.ref/name seon.merge/merge!,
     :seon.fn/source
       "(defn merge!
  \"Merge assignment `key`'s branch head C onto main through `execution` (root's world: main's connection, and
  the store it holds or its SCI context's environment holds) as ONE
  transaction from its merge base (`:seon.task/merge-base`, else the commit its branch forked at): the cluster
  facts by `merge-tx`, inside the writer, with the base moved to C and the assignment closed by its end; and,
  when the branch changed declarations main lacks (`seon.cluster.source/unmerged-count`), its source"…+1488,
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
       [(my.program/callers 'seon.merge/merge!)
        (my.program/tests-reaching 'seon.merge/merge!)],
     :seon.ref/references
       [{:seon.ref/name :seon.task/merge-base,
         :seon.render/operations
           [(my.program/source :seon.task/merge-base)]}
        {:seon.ref/name seon.cluster.source/unmerged-count,
         :doc
           "How many declarations `branch` changed that main `database` lacks (`compare-branch` against main's head); a refused comparison i"…+9,
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.cluster.source/prepare-merge!,
         :doc
           "Test `candidate`'s function and test changes since its merge base with `source`'s head H on a fresh branch S of H through the sa"…+252,
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.task/obligations,
         :doc
           "The step kind, own proofs and successors' product tests for task"…+21,
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.cluster.source/accept-merge!,
         :doc
           "Merge exactly the program tested by `prepare-merge!`'s prepared "…+225,
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name :seon.task/merged-declarations,
         :seon.render/operations
           [(my.program/source :seon.task/merged-declarations)]}
        {:seon.ref/name :seon.task/merge-commit,
         :seon.render/operations
           [(my.program/source :seon.task/merge-commit)]}
        {:seon.ref/name seon.merge/merge-onto,
         :doc "`merge!`'s work: assignment `key"…+83,
         :seon.render/operations [(…2)]}]}
    {:seon.ref/heading "M6 notes",
     :seon.ref/path
       "docs/research/agent-platform/2026-10-12/m6-notes.md",
     :seon.render/operations
       [(my.fs/section
         "docs/research/agent-platform/2026-10-12/m6-notes.md")]}],
 :turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.merge}}
```
