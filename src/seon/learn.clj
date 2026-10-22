(ns seon.learn
  "Waste, charged to the producer of what an agent was shown (session R4's seon.learn, census
  idea 14; ported by session D2). At an attempt's end the agent folds its
  turns: each turn's forms, and what that turn's context block showed, by its producer.
  A form that looks for code instead of changing it is waste, charged to whoever should
  have shown that code; an error repeating an earlier turn's is charged to the render that
  delivered it. Stored as one map per attempt (:seon.end/waste on the branch, carried to
  :seon.attempt/waste by the judge). R4's template rows and trials are not ported (next)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [seon.error :as err]
            [seon.reader :as reader]))

(def ^:private explore-ops
  "Heads of forms that look for code instead of changing it."
  '#{source doc dir ns-publics ns-interns ns-map meta resolve find-var all-ns apropos find-doc source-fn})

(defn aliases
  "The aliases the forms of an attempt set up with (require '[ns :as a]): a -> ns."
  {:malli/schema [:=> [:cat [:sequential :string]] [:map-of :symbol :symbol]]}
  [forms]
  (into {} (for [f forms n (mapcat #(tree-seq coll? seq %) (or (reader/read-text f 'user) []))
                 :when (and (vector? n) (symbol? (first n)) (some #{:as} n))
                 :let [a (second (drop-while #(not= :as %) n))] :when (symbol? a)]
             [a (first n)])))

(defn form-reads
  "What one evaluated form reads, by its shape: :explore? when a head in it looks for code,
  and the qualified symbols and namespaces it names (aliases resolved through as)."
  {:malli/schema [:=> [:cat :string [:map-of :symbol :symbol]] [:map [:explore? :boolean] [:targets [:set :symbol]]]]}
  [s as]
  (let [nodes (mapcat #(tree-seq coll? seq %) (or (reader/read-text s 'user) []))
        expand (fn [sym] (if-let [a (and (namespace sym) (as (symbol (namespace sym))))] (symbol (str a) (name sym)) sym))]
    {:explore? (boolean (some #(and (seq? %) (symbol? (first %)) (explore-ops (symbol (name (first %))))) nodes))
     :targets (into #{} (comp (filter symbol?) (map expand)
                              (filter #(or (namespace %) (str/includes? (str %) ".")))
                              (remove #(#{"clojure.core" "clojure.repl" "clojure.string"} (or (namespace %) (str %)))))
                    nodes)}))

(defn waste
  "The waste of an attempt's turns, each form charged to a producer. turns: [{:n :shown
  {producer #{symbol}} :items [{:form :error}]}]; final: the qualified symbols the
  attempt's error-free definitions use. A form looking for code a producer showed already
  is :re-read (charged to that producer: its render did not satisfy); looking for code the
  final definitions use but nothing showed is :missing, and anything else :search (both
  charged to :template); an error repeating an earlier turn's signature is :repeats-error
  (charged to seon.context/current-state, which delivered it)."
  {:malli/schema [:=> [:cat [:sequential :map] [:set :symbol]] [:vector [:map [:turn :int] [:rule :keyword] [:producer :any] [:form :string]]]]}
  [turns final]
  (let [as (aliases (for [t turns it (:items t)] (:form it)))
        final-ns (into #{} (keep #(some-> (namespace %) symbol)) final)]
    (loop [[t & more] turns seen #{} acc []]
      (if-not t
        acc
        (let [shown-by (into {} (for [[p syms] (:shown t) s syms] [s p]))
              sig #(some-> % err/signature)
              charged (for [{:keys [form error]} (:items t)
                            :let [{:keys [explore? targets]} (form-reads (or form "") as)]
                            r [(cond
                                 (and error (seen (sig error))) {:rule :repeats-error :producer 'seon.context/current-state}
                                 (not explore?) nil
                                 (and (seq targets) (every? shown-by targets)) {:rule :re-read :producer (shown-by (first targets))}
                                 (some #(or (final %) (final-ns %)) targets) {:rule :missing :producer :template}
                                 :else {:rule :search :producer :template})]
                            :when r]
                        (assoc r :turn (:n t) :form (or form "")))]
          (recur more (into seen (keep #(sig (:error %)) (:items t))) (into acc charged)))))))

(defn summary
  "Waste by producer: {producer {:forms n :turns n}}; a turn counts for a producer when any
  of its forms was charged to it."
  {:malli/schema [:=> [:cat [:sequential :map]] :seon.learn/waste]}
  [ws]
  (into {} (for [[p xs] (group-by :producer ws)] [p {:forms (count xs) :turns (count (distinct (map :turn xs)))}])))

(defn final-symbols
  "The qualified symbols the error-free definitions of turns use (what the attempt kept)."
  {:malli/schema [:=> [:cat [:sequential :map]] [:set :symbol]]}
  [turns]
  (let [items (for [t turns it (:items t) :when (and (nil? (:error it)) (str/starts-with? (str/triml (or (:form it) "")) "(def"))] it)
        as (aliases (map :form items))]
    (into #{} (mapcat #(:targets (form-reads (:form %) as))) items)))
