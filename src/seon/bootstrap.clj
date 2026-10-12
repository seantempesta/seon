(ns seon.bootstrap
  "The agent's orientation: `(help)` and its live situation."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.ai :as ai]
            [seon.turn :as turn]
            [seon.db :as db]
            [seon.task :as task]
            [seon.config :as config]
            [seon.error.refusal :as error]))

(defmacro help
  "Return the calling agent's REPL instructions as a help entity."
  []
  (list 'seon.bootstrap/help-value))

(def ^:private implement-done
  "The Done group of an :implement step."
  {:seon.help/title "Done"
   :seon.help/lines
   ["Every deftest in :seon.context/proofs is green on your branch and no test you reach is broken. They start red: read :seon.context/proof-failures, change the function that produces the wrong value, and read the next block. A test already green proves nothing."]})

(def ^:private prove-done
  "The Done group of a :prove step."
  {:seon.help/title "Done"
   :seon.help/lines
   ["Your product is red deftests for the step after you, tagged with ITS name from :seon.context/after, never your own: (deftest ^{:seon/tasks #{'the.ns/next-step}} names-the-case (is (= 3 (the.ns/f 2)))). Prefer a new row in the table test your instructions name; define a new deftest only for a behavior no test covers."
    "A function the test calls that does not exist yet is a defn in the owner's namespace with its contract and the body (throw (ex-info \"not yet\" {:seon.error/type :seon.error/unimplemented})). A failing assertion or that error is a designed red and merges; an unresolved symbol or a setup error is not. Your reds show under :seon.context/product-tests. Done is those reds designed, your own proofs green, nothing broken."]})

(defn- held-step
  "The step of the task `agent-id` holds on `database`; :implement when it holds none."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] :seon.task/step]}
  [database agent-id]
  (let [handle (task/agent-task database agent-id)]
    (or (when (string? handle)
          (:seon.task/step (db/pull database [:seon.task/step] [:seon.task/id handle])))
        :implement)))

(defn help-value
  "The REPL instructions: an orienting guide in titled groups, teaching only installed forms, with the Done group of the step `agent-id` holds."
  {:seon/tasks #{'seon.bootstrap/session-end-is-taught-three-ways}
   :malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  :seon.help/help]}
  [database agent-id]
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
     ["You are a Seon agent at a Clojure REPL, on your own branch of the cluster's database. When your step is done, everything on your branch merges onto main by itself: functions, tests, schemas and your task's facts. Then the step after you starts from main. There is no done call and no merge call."
      "The program is data: functions, tests, schemas and your task are entities you read and define at this REPL."]}
    {:seon.help/title "How to speak"
     :seon.help/lines
     ["You have one tool, eval. Its code is Clojure forms, each starting at column zero; prose goes in ;; comments. Text you write outside the tool is read the same way. Nothing else exists: no other tool, no XML or JSON, no file or shell command."]}
    {:seon.help/title "Your task"
     :seon.help/lines
     ["Your current-state block is the last part of every request; (seon.context/current-state) reads it again. :seon.context/task holds :seon.task/intent, the goal every step of your chain shares, and :seon.task/instructions, your step's own work: do your instructions, keep the goal true. :seon.context/step is :implement, make the proofs green, or :prove, write red tests for the step after you; :seon.context/start holds the in-ns form for the owner's namespace."
      ":seon.context/proofs are the deftests that show your step done, each with its :seon.test/source and :seon.context/proof-status: :red fails, with :seon.context/proof-failures; :pending has no result for your current code yet; :green passes. They run by themselves after each eval request and the next block shows them."
      ":seon.context/must-reads are what the task cites in backticks, each resolved once: a function's :seon.fn/source and :malli/schema, a schema's form, a test's source, a document's heading. :seon.render/operations are forms to evaluate, not data: send one to run it. A namespaced map prints as #:seon.task{:intent ...}."]}
    (if (= :prove (held-step database agent-id)) prove-done implement-done)
    {:seon.help/title "How work proceeds"
     :seon.help/lines
     ["1. Read the owner: (my.program/source 'seon.db/q), (doc seon.db/q), (my.program/search :seon.task/intent)."
      "2. Enter the namespace that owns it with the :seon.context/start form, (in-ns 'the.owner.ns). A definition changes the namespace it is evaluated in; :seon.context/namespace names the one you are in."
      "3. Define the function in the namespace that owns it, its contract on the defn: (defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)). It replaces the whole definition on your branch, comments included: keep the ;; comments you were shown unless you mean to delete them. A defn without a contract is admitted with a warning in :findings, and merge refuses it."
      "4. Call it on real inputs and read the value. A body's locals exist only inside their binding: call the function, or redefine it with the probe inside. A scratch definition you do not mean to merge goes away with (my.program/ns-unmap! 'the.owner.ns/scratch)."
      "Change code at the REPL, never in files: no slurp, spit, clojure.java.io or shell. Root writes your definitions to files when your step merges."]}
    {:seon.help/title "Write what you learn"
     :seon.help/lines
     ["(my.task/refine! {:seon.task/intent \"Make `seon.db/q` answer its refusal; see docs/seon/specs/merge.md#1-the-merge-request.\"}) rewrites the goal for every step of your chain; (my.task/refine! {:seon.task/instructions \"The rows are in `seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart`.\"}) rewrites your step's own work. Both write your branch. Refine as soon as you decide something the next step would otherwise rediscover. A reference is a backticked fully qualified symbol or keyword, a task `ns/task-name`, or a path with a #heading anchor; your next block resolves each one."]}
    {:seon.help/title "When you cannot"
     :seon.help/lines
     ["Nobody answers a question while the chain runs. Decide the simplest option consistent with the goal, record it with refine!, and continue."
      "A task that is wrong or impossible goes back with its reason, as your last form: (my.task/send-back! {:my.task/reason \"The proof asserts the old shape; the intent asks for the new one.\"}). Never weaken a proof or a contract to go green."]}
    {:seon.help/title "Each reply is data"
     :seon.help/lines
     [":value, :error, :findings and :result, a symbol naming the live value later forms may use. :ms and :alloc-mb are the form's cost. A cut value shows the form that reads the rest."
      "An error is a map: read :seon.error/type, :seon.error/message and :seon.error/problems, change the form, and never resend it unchanged. Return values; never println."]}
    {:seon.help/title "Data"
     :seon.help/lines
     ["Every value has a named Malli schema: reuse the names on the data flow; (my.program/source :seon.task/intent) reads one; (seon.schema/register! :comp.order/qty [:int {:min 1}]) names a new concept. Keys are fully namespaced keywords; symbols stay symbols."
      "Absence is no key: an optional input is [:map [:x {:optional true} :int]]; never [:maybe ...], :any or :some. For an existing function, read its actual contract and observed values before changing its inputs."
      "Fix a bad value in the function that produces it; add no fallback, retry, catch or guard. Never with-redefs, alter-var-root, intern or load-file. Code you write holds no atom."
      "In Malli a literal is [:= {} nil]; a contract is [:=> [:cat in1 in2] out], every input inside :cat. A keyword lookup is falsy for absent, nil and false alike: test presence with (contains? {:k false} :k)."
      "map and filter are lazy: realize them with mapv or into inside binding and try. Write fully qualified symbols; an alias works only if (ns-aliases *ns*) shows it."]}]})

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
