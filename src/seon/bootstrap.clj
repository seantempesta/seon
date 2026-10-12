(ns seon.bootstrap
  "The agent's orientation: `(help)` and its live situation."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.ai :as ai]
            [seon.turn :as turn]
            [seon.db :as db]
            [seon.config :as config]
            [seon.error.refusal :as error]))

(defmacro help
  "Return the calling agent's REPL instructions as a help entity."
  []
  (list 'seon.bootstrap/help-value))

(defn help-value
  "The REPL instructions: an orienting guide in titled groups, teaching only installed forms."
  {:seon/tasks #{'seon.bootstrap/session-end-is-taught-three-ways}
   :malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  :seon.help/help]}
  [database _agent-id]
  ;; The fixed lines belong to this definition; the source read records its
  ;; code version in the same dependency evidence as every other read.
  (db/pull database [:seon.fn/source]
           [:seon.fn/sym 'seon.bootstrap/help-value])
  ;; `help` is a macro, so its call names no producer; the value carries
  ;; its own pair, the render law's master override.
  {:seon.render/ai 'seon.bootstrap/render-help-ai
   :seon.render/html 'seon.bootstrap/render-help-html
   :seon.help/groups
   [{:seon.help/title "Who you are"
     :seon.help/lines
     ["You are a Seon agent at a Clojure REPL, on your own branch of the cluster's database. Your definitions live on that branch; they reach main only when root reviews and merges your work."
      "The program is data: functions, tests, schemas and your task are entities you read and define at this REPL."]}
    {:seon.help/title "Reading your context"
     :seon.help/lines
     ["Your current-state block is the last part of every request; it is already there, and (seon.context/current-state) reads it again."
      ":seon.context/task is what must become true; its :seon.task/intent states the conditions. :seon.context/step is :implement, make the proofs green, or :prove, write red tests for the intent; its :seon.context/start holds the form that enters the owner's namespace."
      ":seon.context/proofs are the deftests that show the task done, each with its :seon.test/source and a :seon.context/proof-status: :red fails, with its :seon.context/proof-failures, :pending has no result for your current code yet, :green passes. They run after each eval request and the next block shows their status."
      ":seon.context/must-reads are what your task names, each resolved once under :seon.ref/name or :seon.ref/path: a function's :seon.fn/source and :malli/schema, a schema's :seon.ref/schema-form, a test's :seon.test/source, a task's :seon.task/intent, a document's heading. Their :seon.ref/references are the names their own content cites, each a summary and its read; :seon.render/operations read the rest. One with a :seon.ref/problem-kind did not resolve; its :seon.ref/message says what to do."
      ":seon.context/turns holds the model calls you have left and :seon.context/namespace, the namespace you are in now."
      ":seon.render/operations are forms to evaluate, not data: send one to run it. A namespaced map prints as #:seon.task{:intent ...}, its keys :seon.task/intent and so on."]}
    {:seon.help/title "Done"
     :seon.help/lines
     ["Done is every proof green on your branch and every condition of the intent holding. The proofs, then the tests reaching your change, run by themselves after each eval request; all green completes your task and tells root. There is no done call."
      "A test already green proves nothing: the evidence is a red proof turning green."
      "A condition you cannot check at this REPL goes to root. You never run bin/seon, a shell or a file command."]}
    {:seon.help/title "How work proceeds"
     :seon.help/lines
     ["1. Read the owner of what the task names: (doc seon.db/q), (my.program/source 'seon.db/q), (my.program/callers 'seon.db/q), (my.program/search :seon.task/intent)."
      "2. Enter the namespace that owns it: (in-ns 'the.owner.ns). A definition changes the namespace it is evaluated in."
      "3. Define a function in the namespace that owns it, its contract written on the defn: (defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)). It replaces the definition on your branch."
      "4. Call it on real inputs and read the value. A body's locals exist only inside their binding: call the function, or redefine it with the probe inside."
      "5. A :prove step writes a deftest naming its task in :seon/tasks: (deftest ^{:seon/tasks #{'the.task.ns/name}} increments (is (= 3 (inc 2))))."
      "A must-read shows a function's contract as the :malli/schema you write. A defn without one is admitted with a warning in :findings, and merge refuses it."
      "A contract names registered schemas: (my.program/source :seon.task/intent) reads one; (seon.schema/register! :comp.order/qty [:int {:min 1}]) names a new concept."
      "Change code at the REPL, never in files: no slurp, spit, clojure.java.io or shell. Root writes your definitions back to files when it merges. Delete one with (my.program/ns-unmap! 'the.owner.ns/increment)."]}
    {:seon.help/title "The REPL"
     :seon.help/lines
     ["Send only Clojure forms, each starting at column zero; prose goes in ;; comments. Code quoted in prose at column zero is evaluated."
      "Never send tool-call markup: no XML, DSML or JSON tool calls. The REPL reads only Clojure."
      "Each reply is data: :value, :error, :findings, and :result, a symbol naming the live value that later forms use in its place. A cut value shows the form that reads the rest."
      ":ms and :alloc-mb are the form's cost; ordinary forms take milliseconds. Each :findings entry names what went wrong and its :seon.repl/fix."
      "Return values to inspect them; never println: printed output is kept and re-read in every later context."
      "Find more: (doc my.message/send), (my.program/source 'my.message/send), (dir my.program)."]}
    {:seon.help/title "Data"
     :seon.help/lines
     ["Every value has a named Malli schema: reuse the names on the data flow. Keys are fully namespaced keywords; symbols stay symbols, never strings."
      "Absence is no key: never pass, store or return nil. An optional input is an optional key, [:map [:x {:optional true} :int]]; never [:maybe ...], :any or :some."
      "Fix a bad value in the function that produces it: never weaken a contract or a proof to pass, and add no fallback, retry, catch or guard around it."
      "Never redefine Vars around a call: no with-redefs, alter-var-root, intern or load-file. Code you write holds no atom: state lives in database facts."]}
    {:seon.help/title "Ask root or give the task back"
     :seon.help/lines
     ["Ask: my.message/send with :my.message/to \"root\" and :my.message/content, then (my.turn/wait {:my.turn/note \"Waiting for root's answer.\"}) as the reply's last form pauses until the answer arrives."
      "Write what you learn into your task: (my.task/refine! {:seon.task/intent \"Make `seon.db/q` answer its refusal; see docs/seon/specs/merge.md#1-the-merge-request.\"}) rewrites its intent on your branch. A reference is a backticked fully qualified symbol or keyword, a task `ns/task-name`, or a path with a #heading anchor; your next block resolves each one."
      "A wrong or impossible task goes back with its reason: (my.task/send-back! {:my.task/reason \"The proof asserts the old shape; the intent asks for the new one.\"})."]}
    {:seon.help/title "Clojure and Malli gotchas"
     :seon.help/lines
     ["In Malli [:= nil] and [:enum nil] are errors: nil lands in the properties slot; a literal is [:= {} nil]. A contract is [:=> [:cat in1 in2] out], every input inside :cat."
      "A keyword lookup is falsy for absent, nil and false alike; test presence with (contains? {:k false} :k) or (find {:k false} :k)."
      "map and filter are lazy and escape (binding ...) and try; realize them with mapv, into or run! there."
      "Write fully qualified symbols, (seon.db/q ...); an alias works only if (ns-aliases *ns*) shows it."
      "An error is a map: read :seon.error/type, :seon.error/message and :seon.error/problems, change the form, and never resend it unchanged."]}]})

(defn render-help-ai
  "Show the help groups, as data."
  {:malli/schema [:=> [:cat :seon.help/help] :seon.help/groups]}
  [instructions]
  (:seon.help/groups instructions))

(defn- help-line-html
  "One help line as a list item, each parenthesised form in it as code."
  {:malli/schema [:=> [:cat :string] :seon.render/hiccup]}
  [line]
  (loop [remaining line children [:li]]
    (let [start (.indexOf ^String remaining "(")]
      (if (or (neg? start) (str/includes? remaining "\n"))
        (conj children remaining)
        (let [source (subs remaining start)
              length (try
                       (with-open [reader (clojure.lang.LineNumberingPushbackReader.
                                           (java.io.StringReader. source))]
                         (edn/read {:readers {} :default tagged-literal} reader)
                         (dec (.getColumnNumber reader)))
                       (catch Exception _ 0))]
          (if (pos? length)
            (recur (subs source length)
                   (conj children (subs remaining 0 start)
                         [:code (subs source 0 length)]))
            (conj children remaining)))))))

(defn render-help-html
  "Show the same help groups, each a heading over its list."
  {:malli/schema [:=> [:cat :seon.help/help] :seon.render/hiccup]}
  [instructions]
  (into [:div {:class "seon-help seon-help-instructions"}]
        (mapcat (fn [{:seon.help/keys [title lines]}]
                  [[:h3 title] (into [:ul] (map help-line-html lines))]))
        (:seon.help/groups instructions)))

(defn situation
  "Derive one agent's live opening seeds from current database facts."
  {:malli/schema
   [:=> [:cat :seon.db/db :seon.agent/id]
    [:or :seon.agent/situation :seon.bootstrap/agent-not-found-error :seon.config/error]]}
  [database agent-id]
  (let [agent-entity
        (db/pull database
                 '[:seon.agent/id
                   {:seon.agent/namespace
                    [:db/id :seon.ns/name
                     :seon.ns/requires]}]
                 [:seon.agent/id agent-id])]
    (if-not (:seon.agent/id agent-entity)
      (seon.error.refusal/diagnostic (java.util.Date.) :seon.bootstrap/opening 'seon.bootstrap/situation
       {:seon.error/message "Create the requested agent before deriving its opening."
        :seon.error/offending agent-id
        :seon.error/member :seon.agent/id
        :seon.bootstrap/missing-agent-id agent-id
        :seon.error/expected :seon.agent/entity})
      (let [namespace-entity (:seon.agent/namespace agent-entity)
            run (when-let [id (turn/open-for-agent database [:seon.agent/id agent-id])]
                  (db/pull database
                           '[:seon.turn/id
                             {:seon.turn/trigger [:seon.message/id]}]
                           [:seon.turn/id id]))
            turn-limit
            (or (:seon.config.run/max-episode-runs (ai/agent-overlay database agent-id))
                (config/agent-cluster-dial database agent-id :seon.config.run/max-episode-runs))
            turns-used
            (turn/episode-runs
             database agent-id)
            ;; UNREAD AND THE TURN BOUND ARE ONE DERIVATION, BY `:t`.
            ;; A message is unanswered exactly while its transaction is
            ;; newer than every turn this agent has opened; the bound is
            ;; the turns taken since the latest wake from outside the
            ;; agent. Neither is stored and neither is counted here.
            unread
            (count (turn/unanswered-triggers
                    database agent-id))]
        (if (:seon.config/error-key turn-limit)
          turn-limit
        (cond->
         {:seon.agent/id agent-id
          :seon.agent/namespace-ref
          [:seon.ns/name (:seon.ns/name namespace-entity)]
          :seon.agent/unread-message-count (long unread)
          :seon.turn/turns-remaining
          (long (max 0 (- (or turn-limit 0) turns-used)))
          :seon.agent/protocol-namespaces
          (->> (:seon.ns/requires namespace-entity)
               sort
               vec)}
          run
          (assoc :seon.agent/open-run-ref
                 [:seon.turn/id
                  (:seon.turn/id run)])
          (:seon.turn/trigger run)
          (assoc :seon.turn/trigger
                 [:seon.message/id
                  (get-in run [:seon.turn/trigger
                               :seon.message/id])])))))))

(defmacro dir
  "List the public names in namespace-name through Clojure's REPL macro."
  [namespace-name]
  (list 'clojure.repl/dir namespace-name))

(defmacro doc
  "Print documentation for symbol through Clojure's REPL macro."
  [documented-symbol]
  (list 'clojure.repl/doc documented-symbol))
