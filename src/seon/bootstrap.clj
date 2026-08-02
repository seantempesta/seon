(ns seon.bootstrap
  "The system-authored bootstrap run shared by every new agent."
  (:require [seon.cluster.run :as run]
            [seon.schema :as schema]))

(def help-text
  (str
   "You are an agent in a Seon cluster. This is a real Clojure REPL and it is\n"
   "yours: what you evaluate here runs, and what you define here stays.\n\n"
   "Your reply is read as Clojure forms and evaluated in order, one at a time,\n"
   "in your own namespace. Everything a form prints and everything it returns\n"
   "comes back to you as the session you are reading now. Round trips are\n"
   "expensive, so plan the most forms you can usefully run together and send\n"
   "them in one reply; when a later form depends on what an earlier one\n"
   "returned, split the batch there.\n\n"
   "The cluster is one graph database. `seon.db/q` and `seon.db/pull` read it\n"
   "at the current basis, `dir` lists a namespace, `doc` explains a function\n"
   "from the graph's own facts. Every function in this cluster is callable by\n"
   "you.\n\n"
   "A `defn` with a complete `:malli/schema` becomes a durable fact other\n"
   "agents can find and call; without one it lives only in this session. The\n"
   "contract is checked, so write it honestly: input maps must say\n"
   "`{:closed true}`, and a return may not be a bare `[:maybe ...]`.\n\n"
   "Other agents and the human reach you by message and you reach them with\n"
   "`(my.message/send \"id\" \"text\")`. End your run with\n"
   "`(my.run/complete \"the reply you want delivered\")`, or\n"
   "`(my.run/wait \"what you are waiting for\")` when you need someone else\n"
   "first — your next run starts fresh, so put everything it will need into\n"
   "that note.\n"))

(defmacro help
  "Print the one prose guide to the agent REPL."
  []
  (list 'clojure.core/print help-text))

(defmacro dir
  "List the public names in `namespace-name` through Clojure's REPL macro."
  [namespace-name]
  (list 'clojure.repl/dir namespace-name))

(defmacro doc
  "Print documentation for `symbol` through Clojure's REPL macro."
  [documented-symbol]
  (list 'clojure.repl/doc documented-symbol))

(defn run-id
  "The deterministic id of `agent-id`'s system-authored bootstrap run."
  {:malli/schema [:=> [:cat :seon.cluster.agent/id]
                  :seon.cluster.run/id]}
  [agent-id]
  (str "bootstrap:" agent-id))

(defn sources
  "The thirteen ordered forms in a new agent's bootstrap plan."
  {:malli/schema [:=> [:cat :seon.ns/name]
                  :seon.cluster.reply/sources]}
  [namespace-name]
  (let [user-form (fn [source]
                    {:seon.cluster.run.form/source source
                     :seon.ns/name 'user})
        agent-form (fn [source]
                     {:seon.cluster.run.form/source source
                      :seon.ns/name namespace-name})
        function-symbol (str namespace-name "/largest")]
    [(agent-form "(help)")
     (user-form (str "(in-ns '" namespace-name ")"))
     (agent-form "(dir my.run)")
     (agent-form "(doc my.run/complete)")
     (agent-form "(dir my.message)")
     (agent-form
      "(seon.db/q '[:find (count ?f) . :where [?f :seon.fn/sym _]])")
     (agent-form
      "(seon.db/q '[:find ?sym :where [?s :seon.schema/key :my.run/result] [?a :seon.fn.arity/input-refs ?s] [?f :seon.fn/arities ?a] [?f :seon.fn/sym ?sym]])")
     (agent-form
      (str
       "(defn largest\n"
       "  \"The row with the largest :amount.\"\n"
       "  {:malli/schema [:=> [:cat [:sequential [:map [:label :string] [:amount :int]]]]\n"
       "                  [:map [:label :string] [:amount :int]]]}\n"
       "  [rows]\n"
       "  (last (sort-by :amount rows)))"))
     (agent-form
      (str
       "(defn largest\n"
       "  \"The row with the largest :amount; {} when there are none.\"\n"
       "  {:malli/schema [:=> [:cat [:sequential [:map {:closed true} [:label :string] [:amount :int]]]]\n"
       "                  [:map {:closed true} [:label {:optional true} :string] [:amount {:optional true} :int]]]}\n"
       "  [rows]\n"
       "  (or (last (sort-by :amount rows)) {}))"))
     (agent-form
      "(largest [{:label \"a\" :amount 3} {:label \"b\" :amount 9}])")
     (agent-form "(largest)")
     (agent-form "(largest [])")
     (agent-form
      (str
       "(seon.db/q '[:find ?spec . :in $ ?sym :where [?f :seon.fn/sym ?sym] [?f :seon.fn/spec ?spec]] \""
       function-symbol
       "\")"))]))

(defn plan-digest
  "The stable digest of one agent's ordered bootstrap sources."
  {:malli/schema [:=> [:cat :seon.cluster.reply/sources]
                  :seon.cluster.run/plan-digest]}
  [ordered-sources]
  (schema/sha-256 [(.getBytes (pr-str ordered-sources) "UTF-8")]))

(defn seed-tx
  "Transaction data opening, claiming, and freezing one bootstrap run."
  {:malli/schema
   [:=>
    [:cat
     [:map {:closed true}
      [:seon.cluster.agent/id :seon.cluster.agent/id]
      [:seon.ns/name :seon.ns/name]
      [:seon.cluster.run/process :seon.cluster.run/process]
      [:seon.cluster.run/opened-at :seon.cluster.run/opened-at]]]
    :seon.store/transaction-data]}
  [{agent-id :seon.cluster.agent/id
    namespace-name :seon.ns/name
    process :seon.cluster.run/process
    opened-at :seon.cluster.run/opened-at}]
  (let [id (run-id agent-id)
        ordered-sources (sources namespace-name)
        namespace-row
        {:seon.ns/name namespace-name
         :seon.ns/requires
         [[:seon.ns/name 'my.run]
          [:seon.ns/name 'my.message]
          [:seon.ns/name 'seon.bootstrap]]
         :seon.ns/refers
         [{:seon.ns.refer/local 'help
           :seon.ns.refer/target-ns 'seon.bootstrap
           :seon.ns.refer/target-name 'help}
          {:seon.ns.refer/local 'dir
           :seon.ns.refer/target-ns 'seon.bootstrap
           :seon.ns.refer/target-name 'dir}
          {:seon.ns.refer/local 'doc
           :seon.ns.refer/target-ns 'seon.bootstrap
           :seon.ns.refer/target-name 'doc}]}]
    (into
     []
     cat
     [[namespace-row]
      (run/open-tx
       {:seon.cluster.run/id id
        :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
        :seon.cluster.run/opened-at opened-at})
      (run/claim-tx
       {:seon.cluster.run/id id
        :seon.cluster.run/process process
        :seon.cluster.run/live-processes #{process}
        :seon.cluster.run/now opened-at})
      (run/plan-tx
       {:seon.cluster.run/id id
        :seon.cluster.run/process process
        :seon.cluster.run/plan-digest (plan-digest ordered-sources)
        :seon.cluster.run/sources ordered-sources})])))
