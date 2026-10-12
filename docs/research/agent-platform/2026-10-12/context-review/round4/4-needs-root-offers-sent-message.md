---
title: "Context review round 4: the exact first-call model context for seon.cluster.message/root-offers-a-sent-message-once-and-merges-its-record"
type: research
status: complete
created: 2026-10-12
---

# Context review round 4: seon.cluster.message/root-offers-a-sent-message-once-and-merges-its-record

- Task: `seon.cluster.message/root-offers-a-sent-message-once-and-merges-its-record` (handle `c4080860638e`)
- Agent: `ctx-dump` (a fixture agent and attempt written on a `d/with` of default's main, never committed; no provider call)
- Default: git HEAD 8d14faeee, cluster `default`; method as round 3 (a8d4a784e), see README.md
- Assembled by `seon.context/model-context`, laid out by `seon.ai/messages` with wrapping `:chat`, the request carrying default's cluster caps, SCI context and time limit.
- First call: no exchanges yet, so the messages are the system message and one user message. Message content is verbatim.
- Total estimated tokens (`seon.ai.tokens/estimate`): **8157**

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

## Message 2: role user (19184 characters, 5995 estimated tokens)

```user
#:seon.context{:task
   #:seon.task{:sym
      seon.cluster.message/root-offers-a-sent-message-once-and-merges-its-record,
    :intent
      "(hybrid-done-and-merge M16, reused U5) The sender only transacts: `seon.cluster.message/send!` writes the message on the sender's own branch with `seon.cluster.message/send-call` (the sender's `offer!` and `:my.message/keep?` go). Root's `seon.cluster.agent/db<->flow` records it on main by `:seon.message/id` before its one `offer!` to the recipient's `:seon.agent/inbox` channel (`seon.cluster.agent/channels`); a crash between leaves 'unknown', never a second offer (in-flight loss accepted); an offer that cannot be made is `:seon.message/undeliverable` (recipient, intent, cause) on main's message entity, never a write to the sender's branch. Red proofs in `seon.cluster.message-test`: the deftest tagged to this task (one offer; main holds the record once by id; a second `db<->flow` run offers nothing) and `an-undeliverable-message-is-answered-as-data` (no graph starts). Cost: under 25 ms per message. Files: `src/seon/cluster/message.clj`, `src/my/message.clj`, `src/seon/cluster/agent.clj`, schemas. After M13 (`seon.turn/a-held-task-opens-no-model-turn`), else a record on main would start a stopped recipient. Read `seon.cluster.agent/mailbox-step`, `seon.cluster.wake/delivery` and `docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md`."},
 :step
   {:seon.task/step :implement,
    :seon.task/status :working,
    :seon.context/start
      {:seon.render/operations [(in-ns 'seon.cluster.message)]}},
 :must-reads
   [{:seon.ref/name seon.turn/a-held-task-opens-no-model-turn,
     :seon.task/intent
       "Phase P10 of hybrid-step-semantics.md (verify-astra #8), as filed: three starters exist (`seon.cluster.agent/plan`, `seon.cluster.agent/agents-to-start`, `seon.cluster.host/start!`'s graph start) and `seon.turn/next-agent-work`'s held clause opens model turns for a :held task; every graph starts from plan over an assignment of a :running task; :held means no assignment and no model turn; an MCP entrant declares ROOT or AGENT. (hybrid-done-and-merge M13) Delete `seon.cluster.agent/agents-to-start`, `seon.cluster.host/start!`'s graph start and the held clause; `src/seon/mcp.clj` declares the entrant kind; `src/seon/turn.clj` as a patch from lane program-context. Red proof: this task's deftest in `test/seon/turn_test.clj` (a :held task with a wake gets no assignment and no model turn after one `db<->flow` run); `test/seon/cluster/lazy_agents_test.clj` converts. After M12. Read `docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md`.",
     :seon.ref/references
       [{:seon.ref/name seon.cluster.agent/plan,
         :doc "The actions db<->flow takes next, as a vector.",
         :seon.render/operations
           [(my.program/source 'seon.cluster.agent/plan)]}
        {:seon.ref/name seon.cluster.agent/agents-to-start,
         :doc
           "Unstarted agents with work, or schedules whose timers need a running proc.",
         :seon.render/operations
           [(my.program/source 'seon.cluster.agent/agents-to-start)]}
        {:seon.ref/name seon.cluster.host/start!,
         :doc
           "Admit the agent as homed on the execution's branch, on the cluster and on that branch (each writer decides, `home-call`), then start its graph there through `seon.cluster.agent/start-branch-flow!`, which registers the branch's route listener; db<->flow is the only other starter, and no graph runs per branch.",
         :seon.render/operations
           [(my.program/source 'seon.cluster.host/start!)]}
        {:seon.ref/name seon.turn/next-agent-work,
         :doc
           "The ONE thing to do next for `agent-id` on `db`, or nil when idle.",
         :seon.render/operations
           [(my.program/source 'seon.turn/next-agent-work)]}]}
    {:seon.ref/name seon.cluster.message/send!,
     :seon.fn/source
       "(defn send!
  \"Offer a message without parking; keep a sender copy only under `:my.message/keep?`.\"
  {:malli/schema [:=> [:cat :my.message/message :seon.db/connection :seon.agent/id]
                  [:or :seon.message/accepted-result :seon.error/value]]}
  [request connection agent-id]
  (let [candidate (candidate request)
        database (db/db connection)]
    (if (:seon.error/at candidate)
      candidate
      (let [message (prepared-message database candidate agent-id)
            recipient (:my.message/to candidate)
            port (inbox-port (:seon.agent/routing request) recipient)]
        (if (:seon.error/at message)
          (if (:seon.message/unknown-recipient message)
            (undeliverable database recipient :no-graph)
            message)
          (let [offered (when port (async/offer! port message))]
            (if (true? offered)
              (let [kept (when (:my.message/keep? request)
                           (db/transact! connection
                                         {:"…+462,
     :malli/schema
       [:=>
        [:cat :my.message/message :seon.db/connection :seon.agent/id]
        [:or :seon.message/accepted-result :seon.error/value]],
     :seon.render/operations
       [(my.program/callers 'seon.cluster.message/send!)
        (my.program/tests-reaching 'seon.cluster.message/send!)],
     :seon.ref/references
       [{:seon.ref/name :my.message/keep?,
         :seon.render/operations [(my.program/source :my.message/keep?)]}
        {:seon.ref/name seon.cluster.message/candidate,
         :doc
           "The message `request` writes, with its minted id and the members it carries (assignment, reason, a task's end and its merge task), or the refusal naming its missing recipient, content or subject.",
         :seon.render/operations
           [(my.program/source 'seon.cluster.message/candidate)]}
        {:seon.ref/name seon.cluster.message/inbox-port,
         :doc
           "The recipient's inbox in the execution registry's tree: its root and every branch child below it, at any depth.",
         :seon.render/operations
           [(my.program/source 'seon.cluster.message/inbox-port)]}
        {:seon.ref/name seon.cluster.message/prepared-message,
         :doc
           "The message admitted against the sender's database, or its declared refusal.",
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.cluster.message/send-call,
         :doc
           "Write an admitted message once by identity, or prepare a legacy candidate at the writer.",
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.cluster.message/undeliverable,
         :doc
           "The sender's undeliverable value, naming recipient intent and channel outcome.",
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.db/db,
         :doc
           "Current immutable database value for an explicit or ambient connection.",
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.db/transact!,
         :doc
           "Validate authored transaction data and commit through the callin"…+13,
         :seon.render/operations [(my.program/source …1)]}]}
    {:seon.ref/name seon.cluster.message/send-call,
     :seon.fn/source
       "(defn send-call
  \"Write an admitted message once by identity, or prepare a legacy candidate at the writer.\"
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :seon.message/message] :seon.db/tx-data]
                  [:=> [:cat :seon.db/database-value :my.message/message :seon.agent/id] :seon.db/tx-data]]}
  ([database message]
   (if (db/q '[:find ?m . :in $ ?id :where [?m :seon.message/id ?id]]
             database (:seon.message/id message))
     []
     [message]))
  ([database candidate agent-id]
   (let [message (prepared-message database candidate agent-id)]
     (if (:seon.error/at message)
       (throw (ex-info (:seon.error/message message) message))
       (send-call database message)))))",
     :malli/schema
       [:function
        [:=>
         [:cat :seon.db/database-value :seon.message/message]
         :seon.db/tx-data]
        [:=>
         [:cat :seon.db/database-value :my.message/message
          :seon.agent/id]
         :seon.db/tx-data]],
     :seon.render/operations
       [(my.program/callers 'seon.cluster.message/send-call)
        (my.program/tests-reaching 'seon.cluster.message/send-call)],
     :seon.ref/references
       [{:seon.ref/name seon.cluster.message/prepared-message,
         :doc
           "The message admitted against the sender's database, or its declared refusal.",
         :seon.render/operations
           [(my.program/source 'seon.cluster.message/prepared-message)]}
        {:seon.ref/name seon.db/q,
         :doc
           "Run a Datalog query over explicit inputs or the current database value.",
         :seon.render/operations [(my.program/source 'seon.db/q)]}]}
    #:seon.ref{:name :my.message/keep?, :schema-form :boolean}
    {:seon.ref/name seon.cluster.agent/db<->flow,
     :seon.fn/source
       "(defn db<->flow
  \"db<->flow transform, in Flow's four arities.
  Derive unstarted agents with work or schedules under a payload-free wake,
  then start those agents, sorted for determinism. A COMMITTED AGENT CREATION IS
  AN START WAKE, and that is a declaration rather than a claim:
  `:seon.agent/id` carries `:seon.wake/starts true`, which
  `wake/starting-attributes` derives and `wake/route!` offers here on
  every assertion — so an agent created with work while the cluster runs is
  started by this same"…+4611,
     :malli/schema
       [:function
        [:=> [:cat] [:map]]
        [:=> [:cat :map] :map]
        [:=> [:cat :map :keyword] :map]
        [:=> [:cat :map :keyword :seon.schema/value] [:tuple …2]]],
     :seon.render/operations
       [(my.program/callers 'seon.cluster.agent/db<->flow)
        (my.program/tests-reaching 'seon.cluster.agent/db<->flow)],
     :seon.ref/references
       [{:seon.ref/name :seon.agent/id,
         :seon.render/operations [(my.program/source :seon.agent/id)]}
        #:seon.ref{:name wake/starting-attributes,
         :problem-kind :unresolved,
         :message
           "Nothing of this name or path exists on your branch. If the task means one to create, create it; else tell root with my.message/s"…+27}
        #:seon.ref{:name wake/route!,
         :problem-kind :unresolved,
         :message
           "Nothing of this name or path exists on your branch. If the task means one to create, create it; else tell root with my.message/s"…+27}
        {:seon.ref/name seon.cluster.agent/failed-pass!,
         :doc
           "db<->flow's one catch, the Flow proc's boundary, for its pass's "…+10,
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.cluster.agent/run-pass!,
         :doc
           "One db<->flow pass over cluster `handle` and its `routing`: the "…+234,
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.cluster.wake/route!,
         :doc
           "Register the ROUTING wake handler on a connection (F1 4).",
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.cluster.wake/unlisten!,
         :doc "Remove the wake handler.",
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.env/of,
         :doc "The environment carried by a ctx"…+36,
         :seon.render/operations [(…2)]}
        {:seon.ref/name seon.fault/fault!,
         :doc "Record one core fault on the cal"…+32,
         :seon.render/operations [(…2)]}]}
    #:seon.ref{:name :seon.message/id,
     :schema-form
       [:string
        {:min 1,
         :seon.db/identity true,
         :seon.program/row-schema :seon.message/message}]}
    #:seon.ref{:name :seon.agent/inbox,
     :problem-kind :unresolved,
     :message
       "Nothing of this name or path exists on your branch. If the task means one to create, create it; else tell root with my.message/send that the task names it."}
    {:seon.ref/name seon.cluster.agent/channels,
     :seon.fn/source
       "(defn channels
  \"The current entity-id → mailbox-channel map, for `wake/route!`.\"
  {:malli/schema [:=> [:cat :seon.agent/routing]
                  [:map-of :int :seon.flow/channel]]}
  [routing]
  (:seon.agent/channels @routing))",
     :malli/schema
       [:=>
        [:cat :seon.agent/routing]
        [:map-of :int :seon.flow/channel]],
     :seon.render/operations
       [(my.program/callers 'seon.cluster.agent/channels)
        (my.program/tests-reaching 'seon.cluster.agent/channels)],
     :seon.ref/references
       [#:seon.ref{:name wake/route!,
         :problem-kind :unresolved,
         :message
           "Nothing of this name or path exists on your branch. If the task means one to create, create it; else tell root with my.message/s"…+27}]}
    #:seon.ref{:name :seon.message/undeliverable,
     :schema-form
       [:and
        :seon.error/base
        [:map
         [:seon.message/recipient :seon.message/recipient]
         [:seon.message/intent :seon.message/intent]
         …1]]}
    {:seon.ref/path "src/seon/cluster/message.clj",
     :seon.ref/heading "src/seon/cluster/message.clj",
     :seon.render/operations
       [(my.fs/section "src/seon/cluster/message.clj")],
     :seon.ref/references
       [{:seon.ref/name :seon.render/html,
         :seon.render/operations [(my.program/source :seon.render/html)]}
        {:seon.ref/name :seon.message/to,
         :seon.render/operations [(my.program/source :seon.message/to)]}
        #:seon.ref{:name :seon.message/_to,
         :problem-kind :unresolved,
         :message
           "Nothing of this name or path exists on your branch. If the task "…+91}
        {:seon.ref/name my.message/inbox,
         :doc "Read my unread messages, oldest "…+6,
         :seon.render/operations [(…2)]}
        {:seon.ref/name :seon.message/received-tx,
         :seon.render/operations […1]}
        #:seon.ref{:name str/blank?,
         :problem-kind :unresolved,
         :message "Nothing of this name or path exi"…+123}
        {:seon.ref/name my.message/send,
         :doc "Offer a message to the recipient"…+47,
         :seon.render/operations [(…2)]}
        …1]}
    {:seon.ref/path "src/my/message.clj",
     :seon.ref/heading "src/my/message.clj",
     :seon.render/operations [(my.fs/section "src/my/message.clj")],
     :seon.ref/references
       [{:seon.ref/name :seon.message/id,
         :seon.render/operations [(my.program/source :seon.message/id)]}]}
    {:seon.ref/path "src/seon/cluster/agent.clj",
     :seon.ref/heading "src/seon/cluster/agent.clj",
     :seon.render/operations
       [(my.fs/section "src/seon/cluster/agent.clj")],
     :seon.ref/references
       [{:seon.ref/name seon.flow/var-process,
         :doc
           "Build one Flow proc launcher from a step VAR and a pinned workload.",
         :seon.render/operations [(my.program/source …1)]}
        #:seon.ref{:name :seon.agent/mailbox,
         :problem-kind :unresolved,
         :message
           "Nothing of this name or path exists on your branch. If the task "…+91}
        #:seon.ref{:name :seon.agent/episode,
         :problem-kind :unresolved,
         :message
           "Nothing of this name or path exists on your branch. If the task "…+91}
        #:seon.ref{:name :seon.agent/turn,
         :problem-kind :unresolved,
         :message "Nothing of this name or path exi"…+123}
        {:seon.ref/name seon.turn/turn,
         :doc "Run one turn to its next durable"…+35,
         :seon.render/operations [(…2)]}
        #:seon.ref{:name :seon.agent/schedule,
         :problem-kind :unresolved,
         :message "Nothing of this name or path exi"…+123}
        #:seon.ref{:name seon.flow/submit!!,
         :problem-kind :unresolved,
         :message "Nothing of this name or path exi"…+123}
        …42]}
    {:seon.ref/name seon.cluster.agent/mailbox-step,
     :seon.fn/source
       "(defn mailbox-step
  \"The mailbox transform, in Flow's four arities.
  Total and instant: one payload-free `:seon.agent/episode` signal downstream per
  wake, deliveries counted in the ping map. The wake channel arrives as
  an in-port — the same channel the routing entry names — so listener
  routing, the start prime, and the self-rewake all target ONE edge.\"
  {:malli/schema [:function [:=> [:cat] [:map]] [:=> [:cat :map] :map] [:=> [:cat :map :keyword] :map] [:=> [:cat :map :keyword [:any {:seon.schema.a"…+1548,
     :malli/schema
       [:function
        [:=> [:cat] [:map]]
        [:=> [:cat :map] :map]
        [:=> …2]
        [:=> …2]],
     :seon.render/operations
       [(my.program/callers 'seon.cluster.agent/mailbox-step)
        (my.program/tests-reaching 'seon.cluster.agent/mailbox-step)],
     :seon.ref/references
       [#:seon.ref{:name :seon.agent/episode,
         :problem-kind :unresolved,
         :message
           "Nothing of this name or path exists on your branch. If the task means one to create, create it; else tell root with my.message/s"…+27}
        {:seon.ref/name seon.cluster.agent/receive-message!,
         :doc
           "Write a received message once on this mailbox's connection; retu"…+22,
         :seon.render/operations [(my.program/source …1)]}]}
    {:seon.ref/name seon.cluster.wake/delivery,
     :seon.fn/source
       "(defn delivery
  \"`offer!`'s answers plus the route owner's derived fence, named.

  core.async reports whether a non-blocking offer delivered, met a
  closed channel, or would have parked:

  - `true` — `::delivered`. The wake is in the buffer.
  - `false"…+2302,
     :malli/schema
       [:=>
        [:cat :seon.cluster.wake/offer-result :boolean]
        :seon.cluster.wake/delivery],
     :seon.render/operations
       [(my.program/callers 'seon.cluster.wake/delivery)
        (my.program/tests-reaching 'seon.cluster.wake/delivery)],
     :seon.ref/references
       [#:seon.ref{:name agent/stop-flow!,
         :problem-kind :unresolved,
         :message
           "Nothing of this name or path exists on your branch. If the task means one to create, create it; else tell root with my.message/s"…+27}
        #:seon.ref{:name agent/fenced-route?,
         :problem-kind :unresolved,
         :message
           "Nothing of this name or path exists on your branch. If the task "…+91}
        #:seon.ref{:name agent/fenced?,
         :problem-kind :unresolved,
         :message
           "Nothing of this name or path exists on your branch. If the task "…+91}
        #:seon.ref{:name impl/buffers.clj,
         :problem-kind :unresolved,
         :message "Nothing of this name or path exi"…+123}]}
    {:seon.ref/path
       "docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md",
     :seon.ref/heading
       "Done is derived from proofs; root merges the merge request from main",
     :seon.render/operations
       [(my.fs/section
         "docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md")]}],
 :turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.cluster.message}}
```
