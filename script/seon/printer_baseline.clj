(ns seon.printer-baseline
  "Measurement harness of the printer cleanup: the probes every phase's cost proof runs, parent against change.

  A probe is one value printed through one entry under one budget; `measure-print` answers what the print cost
  (`seon.profile/measure`: wall ms and the calling thread's allocation) and what it showed (tokens, the cuts at
  depth one, tuple placeholders). The three probes are a small reply, the function entity of
  `seon.sci.eval/shown-result` at 1,024 tokens and the relation of every documented function at 6,144;
  `transcript` prints stored evaluations through their pair directly and through the print entry.
  `baseline` runs them all over one custody; `write-baseline!` records the answer as the parent figures
  `docs/research/agent-platform/2026-10-16/printer-design/baseline.edn` holds."
  (:require [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.db :as db]
            [seon.fs :as fs]
            [seon.profile :as profile]
            [seon.render :as render]
            [seon.render.value :as value]
            [seon.repl :as repl]))

(def baseline-path
  "The file the recorded parent figures live in."
  "docs/research/agent-platform/2026-10-16/printer-design/baseline.edn")

(def ^:private function-symbol
  "The function entity the second probe prints: a 1,050-character docstring and a 7,000-character source."
  'seon.sci.eval/shown-result)

(def ^:private budgets
  "Each probe's token budget; the small reply keeps the profile's own."
  {:function-entity 1024
   :function-entity-small 512
   :documented-functions 6144})

(def ^:private transcript-size "How many stored evaluations the transcript probe prints." 40)

(def ^:private samples "Measured calls per probe after the warm calls." 5)

(def ^:private warm-calls "Unmeasured calls before the samples." 2)

(def ^:private evaluation-selector
  "An evaluation as the print entry selects its pair for it, with its run's attempts that age it."
  '[* {:seon.cluster.eval/ns [:db/id :seon.ns/name]}
    {:seon.cluster.eval/run [:db/id :seon.turn/id {:seon.turn/attempts [:db/id]}]}])

(defn unit
  "The print request of `value` under `custody`, its token budget `budget` when given.
  Custody holds `:seon.db/db`, `:seon.sci.eval/ctx` and `:seon.boot/cluster-name`."
  {:malli/schema [:function
                  [:=> [:cat :map :any] :map]
                  [:=> [:cat :map :any [:maybe pos-int?]] :map]]}
  ([custody value] (unit custody value nil))
  ([custody value budget]
   (let [render-profile (render/request-profile (select-keys custody [:seon.db/db :seon.boot/cluster-name]))]
     (merge (select-keys custody [:seon.db/db :seon.sci.eval/ctx])
            {:seon.agent/id "root"
             :seon.render.call/id [:seon.printer-baseline/probe]
             :seon.render/value value
             :seon.render/profile (cond-> render-profile budget (assoc :seon.render.profile/token-budget budget))
             :seon.sci.admit/caps (config/result-caps config/defaults)
             :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)}))))

(defn- tree-cuts
  "The elided nodes of a projection's tree."
  {:malli/schema [:=> [:cat :map] [:sequential :map]]}
  [projection]
  (filter #(and (map? %) (= :seon.print/elided (:seon.print/face %)))
          (tree-seq coll? seq (:seon.render.value/tree projection))))

(defn- placeholders
  "How many lines of `text` are a collapsed tuple, `[…N]`."
  {:malli/schema [:=> [:cat :string] :int]}
  [text]
  (count (filter #(re-matches #"\s*\[…\d+\]" %) (str/split-lines text))))

(defn figures
  "What one projection showed: tokens, lines, cuts and collapsed tuples.
  A cut is `[path omitted total]`, in tree order."
  {:malli/schema [:=> [:cat :map] :map]}
  [projection]
  (let [text (:seon.render.value/text projection)
        cuts (tree-cuts projection)]
    {:tokens (tokens/estimate text)
     :lines (count (str/split-lines text))
     :cut-count (count cuts)
     :cuts (mapv (juxt :seon.render.data/path :seon.print/omitted :seon.render.data/total) cuts)
     :placeholders (placeholders text)
     :producers (some-> (:seon.render.call/selected-producer projection) vector)}))

(defn- median-sample
  "The sample of `measured` with the median wall time."
  {:malli/schema [:=> [:cat [:vector :map]] :map]}
  [measured]
  (nth (vec (sort-by :seon.profile/ms measured)) (quot (dec (count measured)) 2)))

(defn measure-print
  "Print `value` through `entry`, the cost's subject, `samples` times after warming.
  Answers the median sample's wall ms and thread allocation, every sample's ms,
  the first call's cost and the `figures` of its projection."
  {:malli/schema [:=> [:cat :map [:fn ifn?] :any [:maybe pos-int?]] :map]}
  [custody entry value budget]
  (let [request (unit custody value budget)
        first-call (profile/measure #(entry request))
        _ (dotimes [_ (dec warm-calls)] (entry request))
        measured (vec (repeatedly samples #(profile/measure (fn [] (entry request)))))
        median (median-sample measured)]
    (assoc (figures (:seon.profile/value median))
           :ms (:seon.profile/ms median)
           :alloc-bytes (:seon.profile/alloc-bytes median)
           :samples-ms (mapv :seon.profile/ms measured)
           :first-ms (:seon.profile/ms first-call)
           :first-alloc-bytes (:seon.profile/alloc-bytes first-call))))

(defn probes
  "The probes' values read from `custody`'s database.
  A small reply, the function entity and every documented function's docstring."
  {:malli/schema [:=> [:cat :map] :map]}
  [{database :seon.db/db}]
  {:small-reply {:a 1}
   :function-entity (db/pull database '[*] [:seon.fn/sym function-symbol])
   :documented-functions (db/q '[:find ?symbol ?doc :where [?e :seon.fn/sym ?symbol] [?e :seon.fn/doc ?doc]] database)})

(defn- measured-probes
  "Each probe printed through `entry` at its budget.
  The values print as data, with no context to select a pair for them; the function entity also prints
  under its small budget and, with the context, through the pair it selects."
  {:malli/schema [:=> [:cat :map [:fn ifn?]] :map]}
  [custody entry]
  (let [data (dissoc custody :seon.sci.eval/ctx)
        {:keys [small-reply function-entity documented-functions]} (probes custody)]
    {:small-reply (measure-print data entry small-reply nil)
     :function-entity (assoc (measure-print data entry function-entity (:function-entity budgets))
                             :doc-characters (count (:seon.fn/doc function-entity)))
     :function-entity-small (measure-print data entry function-entity (:function-entity-small budgets))
     :function-entity-pair (measure-print custody entry function-entity (:function-entity budgets))
     :documented-functions (assoc (measure-print data entry documented-functions (:documented-functions budgets))
                                  :tuples (count documented-functions))}))

(defn transcript
  "The first stored evaluations printed directly and through `entry`, one batch each.
  Directly is `seon.repl/render-ai`, the evaluation's pair; `entry` selects it."
  {:malli/schema [:=> [:cat :map [:fn ifn?]] :map]}
  [{database :seon.db/db :as custody} entry]
  (let [ids (->> (db/q '[:find [?e ...] :where [?e :seon.cluster.eval/id _] [?e :seon.cluster.eval/source _]
                         [?e :seon.eval/shown _]] database)
                 sort (take transcript-size) vec)
        evaluations (mapv #(db/pull database evaluation-selector %) ids)
        batch (fn [print-one]
                (let [measure #(profile/measure (fn [] (mapv print-one evaluations)))
                      _ (dotimes [_ warm-calls] (measure))
                      measured (vec (repeatedly samples measure))
                      median (median-sample measured)]
                  {:ms (:seon.profile/ms median)
                   :alloc-bytes (:seon.profile/alloc-bytes median)
                   :samples-ms (mapv :seon.profile/ms measured)}))
        direct (batch #(repl/render-ai (unit custody %)))
        printed (batch #(entry (unit custody %)))]
    {:evaluations (count evaluations)
     :direct direct
     :entry (assoc printed :producers (frequencies (map #(:seon.render.call/selected-producer (entry (unit custody %)))
                                                        evaluations)))}))

(defn baseline
  "Every probe over `custody` through `entry`, as `:probes` and `:transcript`."
  {:malli/schema [:=> [:cat :map [:fn ifn?]] :map]}
  [custody entry]
  {:probes (measured-probes custody entry)
   :transcript (transcript custody entry)})

(defn write-baseline!
  "Record `baseline` through `value/prepare`, the entry before the cleanup.
  The parent figures go to `path` (default `baseline-path`) beside their commit and date."
  {:malli/schema [:=> [:cat :map [:map [:commit :string] [:date :string] [:path {:optional true} :string]]] :map]}
  [custody {:keys [commit date path]}]
  (let [recorded (assoc (baseline custody value/prepare)
                        :commit commit :date date
                        :entry 'seon.render.value/prepare
                        :budgets budgets)]
    (fs/write-edn-atomically! (or path baseline-path) recorded)
    recorded))
