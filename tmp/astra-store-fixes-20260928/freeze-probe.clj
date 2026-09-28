;; Evaluate forms in an MCP JVM scratch namespace; no test requests.
(require '[seon.db :as db] '[seon.cluster.boot :as boot] '[seon.cluster.store :as store]
 '[seon.cluster.registry :as registry] '[seon.batch :as batch] '[seon.id :as id]
 '[seon.turn.batch :as turn.batch] '[seon.turn.world :as world]
 '[seon.sci.eval :as sci.eval] '[seon.sci.observe :as observe] '[seon.turn])
(def last-entry #'seon.batch/last-entry)
(def counters #'seon.batch/counters)
(def executed seon.batch/executed)
(def call-profile seon.batch/call-profile)
(def new-adder @#'seon.batch/new-adder)
(def repeated-calls seon.batch/repeated-calls)
(defn freeze-before
  "The form's evidence, frozen after its own work (evaluation, print,
  publication): its reads, the definitions it executed, and whether work it
  owns is still in flight. In flight is :pending, never complete: the value is
  published and nothing waits for the future. The form's counts join the
  batch world's sink."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch] :seon.batch/batch]}
  [request batch]
  (let [{s :seon.batch/form-sink reads :seon.batch/reads ^java.util.concurrent.atomic.AtomicLong owned :seon.batch/in-flight}
        (:seon.batch/form-world batch)
        in-flight (.get owned)
        executed-now (executed s)]
    (when-let [^java.util.concurrent.ConcurrentHashMap batch-sink (:seon.batch/sink (:seon.batch/world request))]
      (doseq [[k [n t]] (counters s)
              :let [^java.util.concurrent.atomic.AtomicLongArray c (.computeIfAbsent batch-sink k new-adder)]]
        (.addAndGet c 0 (long n))
        (.addAndGet c 1 (long t))))
    (last-entry batch
                #(cond-> (assoc % :seon.batch/reads @reads :seon.batch/read-sink reads :seon.batch/executed executed-now
                                :seon.batch/call-profile (call-profile s)
                                :seon.batch/evidence (if (pos? in-flight) :pending :complete)
                                :seon.batch/owned owned)
                   (pos? in-flight) (assoc :seon.batch/in-flight in-flight)))))

(defn call-profile-counts
  "One evaluation's call profile from its captured counters: the armed calls it made, and
  each function it called more than `repeated-calls` times with its calls and
  milliseconds, most calls first."
  {:malli/schema [:=> [:cat [:map-of :seon.batch/executed-key [:tuple nat-int? nat-int?]]] :seon.batch/call-profile]}
  [counts]
  (let [by-sym (reduce (fn [m [[sym _] [n t]]] (update m sym (fn [[a b]] [(+ (or a 0) n) (+ (or b 0) t)])))
                       {} counts)]
    (cond-> {:seon.batch/call-count (reduce + 0 (map first (vals by-sym)))}
      (some (fn [[n _]] (> n repeated-calls)) (vals by-sym))
      (assoc :seon.batch/repeated
             (vec (sort-by (comp - second)
                           (keep (fn [[sym [n t]]] (when (> n repeated-calls) [sym n (quot t 1000000)])) by-sym)))))))

(defn freeze-after
  "The form's evidence, frozen after its own work (evaluation, print,
  publication): its reads, the definitions it executed, and whether work it
  owns is still in flight. In flight is :pending, never complete: the value is
  published and nothing waits for the future. The form's counts join the
  batch world's sink."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch] :seon.batch/batch]}
  [request batch]
  (let [{s :seon.batch/form-sink reads :seon.batch/reads ^java.util.concurrent.atomic.AtomicLong owned :seon.batch/in-flight}
        (:seon.batch/form-world batch)
        in-flight (.get owned)
        captured (counters s)]
    (when-let [^java.util.concurrent.ConcurrentHashMap batch-sink (:seon.batch/sink (:seon.batch/world request))]
      (doseq [[k [n t]] captured
              :let [^java.util.concurrent.atomic.AtomicLongArray c (.computeIfAbsent batch-sink k new-adder)]]
        (.addAndGet c 0 (long n))
        (.addAndGet c 1 (long t))))
    (last-entry batch
                #(cond-> (assoc % :seon.batch/reads @reads :seon.batch/read-sink reads :seon.batch/executed (update-vals captured first)
                                :seon.batch/call-profile (call-profile-counts captured)
                                :seon.batch/evidence (if (pos? in-flight) :pending :complete)
                                :seon.batch/owned owned)
                   (pos? in-flight) (assoc :seon.batch/in-flight in-flight)))))


(let [held (:seon.store/store (#'boot/selected-instance "default")) base (db/db (boot/connection "default")) branch (keyword (str "astra-batch-shrink-" (id/id))) _ (registry/branch! {:seon.store/store held :seon.store/branch branch :seon.cluster.registry/from (:datahike.value/commit-id (db/committed-value-identity base))}) conn (store/open-branch! held branch)] (try (let [w (world/agent-world {:seon.db/connection conn :seon.cluster/name "default" :seon.sci.eval/base-ctx (sci.eval/base-ctx base)} {:seon.agent/id "astra-batch-shrink"}) request (dissoc (turn.batch/code-request {:seon.batch/world w :seon.batch/observe observe/observe :seon.turn.batch/row-tx @#'seon.turn/row-tx :seon.batch/first-ordinal 17 :seon.batch/start-ns 'scratch.astra.pending :seon.batch/text "(defn chosen {:malli/schema [:=> [:cat] :int]} [] 1)"}) :seon.batch/settle) result (batch/evaluate-batch request)] (def pending-real-batch result) (def pending-real-request request) (def pending-sample (select-keys result [:seon.batch/entries :seon.batch/program])) {:entries (count (:seon.batch/entries result)) :diagnostics (:seon.batch/diagnostics result) :entry-keys (mapv (comp vec keys) (:seon.batch/entries result))}) (finally (store/release-branch! conn) (registry/retire-branch! {:seon.store/store held :seon.store/branch branch}))))

(mapv (fn [n] (let [source (get-in pending-real-request [:seon.batch/world :seon.batch/sink]) s (batch/sink) _ (.putAll ^java.util.concurrent.ConcurrentHashMap s (into {} (take n source))) b (assoc pending-real-batch :seon.batch/form-world {:seon.batch/form-sink s :seon.batch/reads (atom []) :seon.batch/in-flight (java.util.concurrent.atomic.AtomicLong. 0)}) request (assoc-in pending-real-request [:seon.batch/world :seon.batch/sink] (batch/sink)) results (mapv (fn [f] (let [counted (batch/sink) start (System/nanoTime) answer (binding [batch/*world* {:seon.batch/sink counted}] (f request b)) ms (/ (- (System/nanoTime) start) 1e6)] {:answer answer :ms ms :snapshots (get (batch/calls counted) 'seon.batch/counters 0)})) [freeze-before freeze-after])] (assert (= (:answer (first results)) (:answer (second results)))) {:n n :results (mapv #(dissoc % :answer) results)})) [1 10 100])
