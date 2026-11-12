(ns seon.learn
  "Waste, charged to the producer of what an agent was shown (session R4's seon.learn, v1 survey
  idea 14; ported by session D2). At a run's end the agent folds its
  turns: each turn's forms, and what that turn's context block showed, by its producer.
  A form that looks for code instead of changing it is waste, charged to whoever should
  have shown that code; an error repeating an earlier turn's is charged to the render that
  delivered it. Stored as one map per end (:seon.agent/waste on the branch, carried to
  :seon.task/waste by the judge). R4's template rows and trials are not ported (next)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [seon.error :as err]
            [seon.reader :as reader] [datahike.api :as d]))

(def ^:private explore-ops
  "Heads of forms that look for code instead of changing it (the REPL's and the agent's tools, my.agent: grep, lines, search,
  callers, callees; and a file read past them, slurp)."
  '#{source doc dir ns-publics ns-interns ns-map meta resolve find-var all-ns apropos find-doc source-fn
     grep lines search callers callees slurp})

(defn turns-of
  "Agent k's run on db (its branch) as waste folds it: each model call's turn in order, {:n :shown :items [{:form :error :value :out :wrote}]},
  each eval of that call (:seon.eval/call) with its code and its result's error, value and printed output (:seon.eval/result), the vars
  its answer says it wrote (:wrote: :recorded, a method as its multi, and :edited's var, :seon.eval/response), and shown, what its
  context showed by producer ({producer #{symbol}}). Read from the evals the REPL recorded, so a run cut by a restart is read
  whole. O(the run's evals): one pull an eval."
  {:malli/schema [:=> [:cat :any :string [:map-of :any [:set :any]]] [:vector [:map [:n :int] [:items [:vector :map]]]]]}
  [db k shown]
  (let [calls (sort-by :seon.model-call/n (d/q '[:find [(pull ?m [:db/id :seon.model-call/n]) ...] :in $ ?k
                                                 :where [?a :seon.agent/key ?k] [?m :seon.model-call/agent ?a]] db k))]
    (vec (for [c calls]
           {:n (:seon.model-call/n c) :shown shown
            :items (vec (for [e (sort (d/q '[:find [?e ...] :in $ ?m :where [?e :seon.eval/call ?m]] db (:db/id c)))
                              :let [{code :seon.eval/code resp :seon.eval/response {:seon.result/keys [ex value out]} :seon.eval/result}
                                    (d/pull db [:seon.eval/code :seon.eval/response {:seon.eval/result [:seon.result/ex :seon.result/value :seon.result/out]}] e)
                                    wrote (cond-> (mapv #(if (vector? %) (first %) %) (:recorded resp)) (:edited resp) (conj (:var (:edited resp))))]]
                          (cond-> {:form code :error ex :value value}
                            (seq (:seon.print/code out)) (assoc :out (:seon.print/code out))
                            (seq wrote) (assoc :wrote wrote))))}))))

(defn aliases
  "The aliases the forms of a run set up with (require '[ns :as a]): a -> ns."
  {:malli/schema [:=> [:cat [:sequential :string]] [:map-of :symbol :symbol]]}
  [forms]
  (into {} (for [f forms n (mapcat #(tree-seq coll? seq %) (or (reader/read-text f 'user) []))
                 :when (and (vector? n) (symbol? (first n)) (some #{:as} n))
                 :let [a (second (drop-while #(not= :as %) n))] :when (symbol? a)]
             [a (first n)])))

(defn form-reads
  "What one evaluated form reads, by its shape: :explore? when a head in it looks for code,
  and the qualified symbols and namespaces it names (aliases resolved through as)."
  {:malli/schema [:=> [:cat :string [:map-of :symbol :symbol]] [:map [:explore? :boolean] [:targets [:set :symbol]] [:head [:maybe :symbol]]]]}
  [s as]
  (let [nodes (mapcat #(tree-seq coll? seq %) (or (reader/read-text s 'user) []))
        expand (fn [sym] (if-let [a (and (namespace sym) (as (symbol (namespace sym))))] (symbol (str a) (name sym)) sym))
        head (some #(when (and (seq? %) (symbol? (first %)) (explore-ops (symbol (name (first %))))) (first %)) nodes)]
    {:explore? (boolean head) :head head
     :targets (into #{} (comp (filter symbol?) (map expand)
                              (filter #(or (namespace %) (str/includes? (str %) ".")))
                              (remove #(#{"clojure.core" "clojure.repl" "clojure.string"} (or (namespace %) (str %)))))
                    nodes)}))

(defn waste
  "The waste of a run's turns, each form charged to a producer. turns: [{:n :shown
  {producer #{symbol}} :items [{:form :error}]}]; final: the qualified symbols the
  run's error-free definitions use. An error repeating an earlier turn's signature is :repeats-error
  (charged to seon.ctx.status/status, which delivered it); an error first seen is :error, charged to what answered it (its
  :seon.error/operation, else :template); a look-up that answered nothing (nil, or an empty collection) is :empty, charged to
  the look-up as written (its head), when it printed nothing either. A form looking for code a producer showed already is :re-read (charged to that
  producer: its render did not satisfy); looking for code the final definitions use but nothing showed is :missing, and
  anything else :search (both charged to :template)."
  {:malli/schema [:=> [:cat [:sequential :map] [:set :symbol]] [:vector [:map [:turn :int] [:rule :keyword] [:producer :any] [:form :string]]]]}
  [turns final]
  (let [;; read only for a form whose reads its caller did not give (`run` reads each form once)
        as (delay (aliases (for [t turns it (:items t)] (:form it))))
        final-ns (into #{} (keep #(some-> (namespace %) symbol)) final)]
    (loop [[t & more] turns seen #{} acc []]
      (if-not t
        acc
        (let [shown-by (into {} (for [[p syms] (:shown t) s syms] [s p]))
              sig #(some-> % err/signature)
              charged (for [{:keys [form error value out] :as it} (:items t)
                            :let [{:keys [explore? targets head]} (or (::reads it) (form-reads (or form "") @as))]
                            r [(cond
                                 (and error (seen (sig error))) {:rule :repeats-error :producer 'seon.ctx.status/status}
                                 error {:rule :error :producer (or (:seon.error/operation error) :template)}
                                 (not explore?) nil
                                 ;; a look-up that printed its answer (source, doc) answered, though its value is nil
                                 (and (nil? out) (or (nil? value) (and (coll? value) (empty? value)))) {:rule :empty :producer head}
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
  "The change site of turns, read on db (the run's branch) from what its evals wrote, never from their text: the vars each error-free
  eval's answer says it wrote (:wrote), and the program vars their forms use (:form/refs). See docs/tasks.md#a-refine-curates-its-own-task."
  {:malli/schema [:=> [:cat :any [:sequential :map]] [:set :symbol]]}
  [db turns]
  (let [wrote (into #{} (comp (mapcat :items) (remove :error) (mapcat :wrote)) turns)
        var-e #(some-> (first (d/datoms db :avet :var/name %)) :e)]
    (into wrote (for [s wrote :let [e (var-e s)] :when e r (some-> (d/entity db e) :var/form :form/refs) :when (var-e r)] r))))

(defn run
  "Agent k's run on db (its branch), folded for a reader that improves its task: each turn with what its forms read (form-reads'
  qualified symbols), the vars its final definitions used (final-symbols: the change site), and the waste rows and their summary
  (waste, summary). The one read a refine and root's report take (my.task/run, my.root/run-losses). O(the run's evals), as turns-of.
  See docs/tasks.md#a-refine-curates-its-own-task."
  {:malli/schema [:=> [:cat :any :string] [:map [:turns [:vector [:map [:n :int] [:reads [:set :symbol]]]]] [:final [:set :symbol]]
                                         [:waste [:vector :map]] [:summary :map]]]}
  [db k]
  (let [turns (turns-of db k {})
        as (aliases (for [t turns it (:items t)] (:form it)))
        ;; each form read once, for the turn's reads and the waste alike
        turns (mapv (fn [t] (update t :items (partial mapv #(assoc % ::reads (form-reads (or (:form %) "") as))))) turns)
        final (final-symbols db turns)
        rows (waste turns final)]
    {:turns (mapv (fn [{:keys [n items]}] {:n n :reads (into #{} (mapcat #(:targets (::reads %))) items)}) turns)
     :final final :waste rows :summary (summary rows)}))
