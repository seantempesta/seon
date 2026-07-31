(ns seon.cluster.instruction
  "Cluster-owned instruction facts and their verbatim family renders."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(def instruction-ids
  "The cluster-owned instruction identities seeded at initialization."
  [:getting-started])

(def superseded-instruction-ids
  "The instruction rows replaced by the context walk's getting-started row."
  [:reply-grammar :messaging :declining :global])

(def getting-started-text
  "The owner-editable starting instruction installed in new source branches."
  (str "This is a live Clojure REPL. Everything above is the output of "
       "`(seon.render/walk)` — run it yourself with `:depth`/`:root` to "
       "see more. Your reply is read as forms and evaluated in your "
       "namespace. A `defn` with `:malli/schema` becomes permanent; "
       "anything else is scratch. Talk to other agents with "
       "`(my.message/send! …)`. Prose lines are kept as `;;` comments."))

(defn toolkit-namespaces
  "Public contracted `my.*` namespaces in one database program graph."
  {:malli/schema
   [:=> [:cat :seon.db/database-value]
    :seon.cluster/toolkit-namespaces]}
  [db]
  (->> (d/q '[:find ?namespace-name ?private
              :where
              [?namespace :seon.ns/name ?namespace-name]
              [?function :seon.fn/ns ?namespace]
              [?function :seon.fn/spec _]
              [(get-else $ ?function :seon.fn/private? false) ?private]]
            db)
       (keep (fn [[namespace-name private?]]
               (when (and (not private?)
                          (str/starts-with? (str namespace-name) "my."))
                 namespace-name)))
       distinct
       sort
       vec))

(defn seed-rows
  "Instruction rows installed only when absent from the source database."
  {:malli/schema
   [:=> [:cat]
    :seon.cluster.instruction/seed-rows]}
  []
  [{:seon.cluster.instruction/id :getting-started
    :seon.cluster.instruction/text getting-started-text}])

(defn instruction-ai
  "The instruction's text, verbatim."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (:seon.cluster.instruction/text unit))

(defn instruction-html
  "The instruction's text in a minimal HTML family render."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  [:article {:class "seon-family-entry seon-instruction-entry"}
   [:p (instruction-ai unit)]])
