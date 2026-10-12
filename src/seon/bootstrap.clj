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
  "The REPL instructions: literal lines teaching only installed forms."
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
   :seon.help/lines
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
    "Gotcha: an error is a map; read :seon.error/type, :seon.error/message and :seon.error/problems, then change the form; never resend it unchanged."]})

(defn render-help-ai
  "Show the help lines, as data."
  {:malli/schema [:=> [:cat :seon.help/help] :seon.help/lines]}
  [instructions]
  (:seon.help/lines instructions))

(defn render-help-html
  "Show the same help lines as a list."
  {:malli/schema [:=> [:cat :seon.help/help] :seon.render/hiccup]}
  [instructions]
  (let [line-html
        (fn [line]
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
                    (conj children remaining)))))))]
    (into [:ul {:class "seon-help seon-help-instructions"}]
          (map line-html (:seon.help/lines instructions)))))

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
