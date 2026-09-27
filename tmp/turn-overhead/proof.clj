;; turn-overhead-opus workbench: named proofs on default, read-only (writes go
;; through d/with or nowhere). Evaluate in a JVM scratch namespace:
;;   (binding [*ns* (create-ns 'turn-overhead.proof)] (load-file "tmp/turn-overhead/proof.clj"))
;;   (turn-overhead.proof/proofs)
;; Each proof returns {:pass? ... :before ... :after ...} with milliseconds.
(ns turn-overhead.proof
  (:require [datahike.api :as d]
            [malli.core :as m]
            [malli.instrument :as mi]
            [seon.db :as db]
            [seon.eval]
            [seon.render :as render]
            [seon.cluster.prompt]
            [seon.render.walk]
            [seon.error]))

(defn ms-of [f] (let [t (System/nanoTime) v (f)] [(/ (Math/round (/ (- (System/nanoTime) t) 1e4)) 100.0) v]))

(defn instance [] (get @@(requiring-resolve 'seon.operator.runtime/running-instances) "default"))
(defn connection [] (:seon.db/connection (:seon.turn.loop/cluster (instance))))
(defn live-db [] (db/db (connection)))

(defn prompt-request [database run-id]
  (let [handle (:seon.turn.loop/cluster (instance))]
    (cond-> (#'seon.render.web/debug-turn-request database (connection) "root"
                                                  (:seon.sci.admit/caps handle) (dissoc handle :seon.turn/id))
      run-id (assoc :seon.turn/id run-id))))

(defn latest-run [database]
  (->> (d/q '[:find ?id ?t :in $ ?agent-id
              :where [?a :seon.agent/id ?agent-id] [?a :seon.agent/runtime ?r]
              [?r :seon.runtime/turns ?turn] [?turn :seon.turn/id ?id ?t]]
            database "root")
       (sort-by second) last first))

;;; --- 1. what the stored plans are ------------------------------------------

(defn stored-plans [database]
  (->> (d/datoms database :aevt :datahike.read/dependency-plan)
       (mapv (fn [datom] {:e (:e datom) :chars (count (:v datom))}))))

(defn plan-census [database]
  (let [plans (stored-plans database)
        largest (apply max-key :chars plans)
        value (clojure.edn/read-string {:default tagged-literal}
                                       (:v (first (d/datoms database :eavt (:e largest) :datahike.read/dependency-plan))))
        source (first (:datahike.query.dependency/sources value))
        patterns (or (:seon.db/read-index-patterns source)
                     (mapcat :seon.db/read-index-patterns (:seon.db.vector/cells source)))]
    {:plans (count plans)
     :total-chars (reduce + (map :chars plans))
     :over-16k (count (filter #(< 16384 (:chars %)) plans))
     :over-16k-chars (reduce + (keep #(when (< 16384 (:chars %)) (:chars %)) plans))
     :largest {:chars (:chars largest)
               :source-keys (vec (sort (keys source)))
               :attributes (:datahike.query.source/attributes source)
               :patterns (count patterns)
               :entity-only-patterns (count (remove :seon.db/pattern-attribute patterns))
               :by-shape (frequencies (map (comp vec sort keys) patterns))}}))

;;; --- 2. the producer bound ---------------------------------------------------

(def bound 128)

(defn capture [database f]
  (let [sink (atom [])]
    (binding [db/*read-evidence-sink* sink] (f))
    @sink))

(defn bounded [captured]
  (mapv #(cond-> % (< bound (count (:seon.db/read-index-patterns %)))
           (dissoc :seon.db/read-index-patterns))
        captured))

(defn capture-for-agent
  "Phase one: capture root's opening fault read on the live committed value."
  []
  (let [database (live-db)
        [capture-ms captured] (ms-of #(capture database (fn [] (seon.error/for-agent database "root"))))]
    {:captured captured :capture-ms capture-ms :basis (db/basis-t database)}))

(defn proof-producer-bound
  "Phase two, after at least one later commit: the bounded evidence decides
  freshness and the generated-read check exactly as the unbounded one does."
  [{:keys [captured capture-ms basis]}]
  (let [later (live-db)
        before (db/read-evidence captured)
        after (db/read-evidence (bounded captured))
        chars (fn [evidence] (reduce + (map (comp count pr-str :datahike.read/dependency-plan) evidence)))
        fault (fn [evidence] (#'seon.turn/generated-read-fault later {:seon.cluster.eval/source "(seon.error/for-agent (seon.db/db) \"root\")"}
                              {:seon.cluster.eval/read-evidence evidence}))
        bounded-schema (m/schema [:vector {:max bound} :map])
        [current-before-ms current-before] (ms-of #(db/read-evidence-current? later before))
        [current-after-ms current-after] (ms-of #(db/read-evidence-current? later after))
        [fault-before-ms fault-before] (ms-of #(fault before))
        [fault-after-ms fault-after] (ms-of #(fault after))]
    {:pass? (and (< basis (db/basis-t later))
                 (< (chars after) 16384)
                 (= current-before current-after)
                 (= fault-before fault-after)
                 (every? #(m/validate bounded-schema (:seon.db/read-index-patterns % []))
                         (mapcat (comp :datahike.query.dependency/sources :datahike.read/dependency-plan) after)))
     :commits-between [basis (db/basis-t later)]
     :capture-ms capture-ms
     :patterns-captured (mapv #(count (:seon.db/read-index-patterns %)) captured)
     :plan-chars {:before (chars before) :after (chars after)}
     :current? {:before current-before :after current-after :ms [current-before-ms current-after-ms]}
     :generated-read-fault {:before fault-before :after fault-after :ms [fault-before-ms fault-after-ms]}}))

;;; --- 3. decode only the kept evaluations' evidence -------------------------

(defn fold-latest
  "seon.turn/latest-evaluations's fold, verbatim, over pulled rows."
  [rows]
  (reduce
   (fn [latest [basis _ evaluation]]
     (let [source (assoc evaluation :seon.ns/name
                         (get-in evaluation [:seon.cluster.eval/ns :seon.ns/name])
                         :seon.turn/basis-t basis)
           shown (seon.repl/shown-value (:seon.eval/shown evaluation ""))
           previous (get latest (#'seon.turn/source-key source))
           shown (if (and previous (map? shown) (:seon.repl/changes shown)
                          (= :system (:seon.cluster.eval/author evaluation))
                          (not= :call (get-in evaluation [:seon.cluster.eval/run
                                                          :seon.turn.work/situation])))
                   (db/apply-diff (:seon.repl/shown-value previous)
                                  (:seon.repl/changes shown))
                   shown)
           source (assoc source :seon.repl/shown-value shown)]
       (assoc latest (#'seon.turn/source-key source) source)))
   {}
   (sort-by (juxt first second) rows)))

(defn latest-kept-evidence
  "The patched seon.turn/latest-evaluations (tmp/turn-overhead/turn.patch):
  every evaluation with its evidence references only, the latest per source
  kept by the same fold, then only the kept evaluations' evidence decoded."
  [database agent-id]
  (let [latest (fold-latest
                (db/q '[:find ?t ?ordinal
                        (pull ?evaluation
                              [* {:seon.cluster.eval/ns [:seon.ns/name]}
                               {:seon.cluster.eval/run [:db/id :seon.turn.work/situation]}
                               {:seon.cluster.eval/read-evidence [:db/id]}])
                        :in $ ?id
                        :where [?agent :seon.agent/id ?id]
                        [?agent :seon.agent/runtime ?runtime]
                        [?runtime :seon.runtime/turns ?turn]
                        [?turn :seon.turn/id _ ?t]
                        [?evaluation :seon.cluster.eval/run ?turn]
                        [?evaluation :seon.cluster.eval/ordinal ?ordinal]
                        [?evaluation :seon.cluster.eval/source]]
                      database agent-id))
        evidence (into {}
                       (db/q '[:find ?evaluation (pull ?evaluation [{:seon.cluster.eval/read-evidence [*]}])
                               :in $ [?evaluation ...]]
                             database (into [] (comp (filter (comp seq :seon.cluster.eval/read-evidence))
                                                     (map :db/id))
                                            (vals latest))))]
    (update-vals latest
                 (fn [evaluation]
                   (if-let [held (get-in evidence [(:db/id evaluation) :seon.cluster.eval/read-evidence])]
                     (assoc evaluation :seon.cluster.eval/read-evidence held)
                     evaluation)))))

(defn proof-kept-evidence []
  (let [database (live-db)
        [before-ms before] (ms-of #(#'seon.turn/latest-evaluations database "root"))
        [after-ms after] (ms-of #(latest-kept-evidence database "root"))]
    {:pass? (= before after)
     :kept (count after)
     :ms {:before before-ms :after after-ms}}))

;;; --- 4. history decodes no plan ---------------------------------------------

(def history-selector
  '[* {:seon.cluster.eval/ns [:db/id :seon.ns/name]} {:seon.cluster.eval/read-evidence [:db/id]}])

(defn render-history
  "seon.render.walk/history's per-evaluation render over `evaluations`."
  [request evaluations]
  (mapv (fn [saved]
          (render/render-call
           (assoc request :seon.render/value (dissoc saved :t)
                  :seon.render/output :seon.render/ai
                  :seon.render.call/id [[:seon.cluster.eval/id (:seon.cluster.eval/id saved)]])))
        evaluations))

(defn proof-history-selector []
  (let [database (live-db)
        of-agent (mi/-f->original seon.eval/of-agent)
        [full-ms full] (ms-of #(seon.eval/of-agent database "root"))
        [lean-ms lean] (ms-of #(of-agent database "root" history-selector))
        request (assoc (prompt-request database nil) :seon.db/db database)
        [full-render-ms full-text] (ms-of #(render-history request full))
        [lean-render-ms lean-text] (ms-of #(render-history request lean))
        ;; this replica has no evaluator, so four renders fail alike; their
        ;; diagnostics carry the instant they were built
        instant #"#inst \"[^\"]*\""
        same-text? (= (map #(clojure.string/replace (str %) instant "#inst") full-text)
                      (map #(clojure.string/replace (str %) instant "#inst") lean-text))]
    {:pass? (and (= (count full) (count lean)) same-text?
                 (= (map #(update % :seon.cluster.eval/read-evidence (partial mapv :db/id)) full)
                    (map #(update % :seon.cluster.eval/read-evidence (partial mapv :db/id)) lean)))
     :evaluations (count lean)
     :of-agent-ms {:before full-ms :after lean-ms}
     :render-ms {:before full-render-ms :after lean-render-ms}}))

;;; --- 5. call reach keyed for the as-of value a turn renders at -------------

(defn call-reach-key
  "seon.render/call-reach-key, verbatim."
  [database]
  (if (instance? datahike.db.AsOfDB database)
    (let [origin (datahike.db.interface/-origin database)
          time-point (datahike.db.interface/-time-point database)
          {:datahike.cache/keys [connection-id generation]} (:cache-context origin)]
      (when (and connection-id generation (some? (datahike.db/committed-value-identity origin))
                 (integer? time-point) (<= (long time-point) (long (datahike.db.interface/-max-tx origin))))
        [connection-id generation time-point]))
    (let [{:datahike.cache/keys [connection-id generation conservative-revision attribute-revisions]}
          (:cache-context database)]
      (when (and connection-id generation)
        [connection-id generation conservative-revision
         (get attribute-revisions :seon.fn/sym)
         (get attribute-revisions :seon.fn/calls)]))))

(defn proof-call-reach
  "The history of one cold prompt calls the reach once per rendered
  evaluation, all with seon.repl/render-ai, at the run's as-of opening."
  []
  (let [database (live-db)
        opening (seon.turn/opening-db database (latest-run database))
        n 147
        selected 'seon.repl/render-ai
        [before-ms before] (ms-of #(vec (repeatedly n (fn [] (#'seon.render/call-reach opening selected)))))
        key (call-reach-key opening)
        [after-ms after] (ms-of #(vec (repeatedly n (fn [] (seon.cache/lookup ::proof-call-reach (into [selected] key)
                                                                               (fn [v] (* 80 (count v)))
                                                                               (fn [] (db/stored-call-reach opening selected)))))))]
    {:pass? (and (some? key) (= before after) (< (* 10 after-ms) before-ms))
     :as-of? (instance? datahike.db.AsOfDB opening)
     :key key
     :reach (count (first after))
     :ms {:before before-ms :after after-ms}}))

;;; --- 6. the walk's pull lowering, once per projection and selector ---------

(defn proof-vector-pull
  "A cold turn's walk lowers the same root selector per pulled entity (x63 in
  one cold system turn). The patched seon.schema.datahike/vector-pull derives
  it once per projection content and selector."
  []
  (let [database (live-db)
        caps (:seon.sci.admit/caps (:seon.turn.loop/cluster (instance)))
        selector (#'seon.render.walk/root-selector database 1 caps)
        projection (db/carried-projection database)
        n 63
        derive (mi/-f->original seon.schema.datahike/vector-pull)
        [before-ms before] (ms-of #(vec (repeatedly n (fn [] (derive projection selector)))))
        key [(seon.schema/projection-identity projection) (true? (:seon.schema.datahike/cells-installed? projection)) selector]
        [after-ms after] (ms-of #(vec (repeatedly n (fn [] (first (seon.cache/lookup ::proof-vector-pull key
                                                                                         (fn [v] (* 48 (count (tree-seq coll? seq v))))
                                                                                         (fn [] [(derive projection selector)])))))))]
    {:pass? (and (= before after) (< (* 10 after-ms) before-ms))
     :selector-chars (count (pr-str selector))
     :ms {:before before-ms :after after-ms}}))

(defn proofs
  "Every proof that needs no commit between its phases."
  []
  {:plan-census (plan-census (live-db))
   :kept-evidence (proof-kept-evidence)
   :history-selector (proof-history-selector)
   :call-reach (proof-call-reach)
   :vector-pull (proof-vector-pull)})
