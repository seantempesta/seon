(ns mcp-dogfood.probe
  "Disposable stage timer for one MCP eval_clj run (lane mcp-dogfood)."
  (:require [seon.operator.runtime :as rt]))

(defn- ms [s] (/ (Math/round (/ (- (System/nanoTime) s) 1e4)) 100.0))

(defmacro tm [acc k form] `(let [s# (System/nanoTime) v# ~form] (vswap! ~acc assoc ~k (ms s#)) v#))

(defn world [agent-id]
  (let [found (#'seon.mcp/instance @rt/running-instances nil)
        handle (:seon.turn.loop/cluster (#'seon.mcp/execution found agent-id nil))]
    [handle (seon.turn.world/jvm-world "default" (:seon.db/connection handle)
                                       (cond-> {:seon.agent/id agent-id :seon.turn/id (seon.id/id)
                                                :seon.sci.eval/ctx (:seon.sci.eval/ctx (seon.cluster.agent/acquire-context! handle agent-id))}
                                         (seon.env/of handle) (assoc :seon.env/environment (seon.env/of handle))))]))

(defn stages [agent-id code]
  (let [acc (volatile! {})
        [handle world] (tm acc :world (world agent-id))
        req (tm acc :request (seon.turn.batch/jvm-request {:seon.batch/world world :seon.batch/text code :seon.batch/start-ns 'user
                                                           :seon.turn.batch/record-call #'seon.turn/record-evaluated-call}))
        b (tm acc :batch (seon.batch/evaluate-batch req))
        after (:seon.batch/after (:seon.batch/commit b))
        _ (tm acc :observed (#'seon.mcp/observed (:seon.turn/id world) after agent-id "default"))]
    (assoc @acc :timings (:seon.batch/timings b))))

(defn call-ms [mode n]
  (vec (repeatedly n #(let [s (System/nanoTime)]
                        (seon.mcp/call (str "{\"name\":\"eval_clj\",\"arguments\":{\"agent\":\"mcp-dogfood\",\"mode\":\"" mode "\",\"code\":\"(+ 1 2)\"}}"))
                        (ms s)))))

(defn observed-parts [agent-id]
  (let [acc (volatile! {})
        [handle world] (world agent-id)
        b (seon.batch/evaluate-batch (seon.turn.batch/jvm-request {:seon.batch/world world :seon.batch/text "(+ 1 2)" :seon.batch/start-ns 'user
                                                                   :seon.turn.batch/record-call #'seon.turn/record-evaluated-call}))
        db (:seon.batch/after (:seon.batch/commit b))
        run-id (:seon.turn/id world)
        caps (tm acc :caps (seon.config/result-caps (seon.config/effective db "default")))
        _ (tm acc :history (@@#'seon.cluster.agent/transcript-history-entries
                            {:seon.db/db db :seon.agent/id agent-id :seon.sci.admit/caps caps
                             :seon.render.transcript/selected-run-id run-id}))
        _ (tm acc :receipts (@@#'seon.cluster.agent/drive-run-receipts db [run-id]))]
    @acc))

(defn settle-parts [agent-id code]
  (let [acc (volatile! {})
        [_ world] (world agent-id)
        req (seon.turn.batch/jvm-request {:seon.batch/world world :seon.batch/text code :seon.batch/start-ns 'user
                                          :seon.turn.batch/record-call #'seon.turn/record-evaluated-call})
        b (tm acc :unsettled (seon.batch/evaluate-batch (dissoc req :seon.batch/settle)))
        conn (seon.turn.world/settlement-connection world)
        writer (:seon.db/writer world)
        head (tm acc :head (seon.db/db conn))
        measured (tm acc :measure (seon.profile.measurement/significant-tx head (into #{} (mapcat (comp keys :seon.batch/executed)) (:seon.batch/entries b))))
        tx (tm acc :facts (into (vec (seon.turn.batch/record-facts req b)) (seon.turn.batch/program-facts req head (:seon.batch/program b))))
        tx (cond-> tx (and (vector? tx) (seq tx) (vector? measured)) (into measured))
        r (tm acc :write (writer conn tx))]
    (assoc @acc :tx-count (count tx) :measured (if (vector? measured) (count measured) measured)
           :writer (str writer) :tx-data (count (:tx-data r)))))

(defn- totals []
  (into {} (map (fn [c] [(:seon.profile/sym c) [(.sum ^java.util.concurrent.atomic.LongAdder (:seon.profile/total-counter c))
                                                (.sum ^java.util.concurrent.atomic.LongAdder (:seon.profile/entry-counter c))]]))
        (seon.profile/cells)))

(defn profiled
  "Inclusive armed-function time (ms) and calls spent during (f), top n."
  [n f]
  (let [before (totals) _ (f) after (totals)]
    (->> after
         (keep (fn [[s [t c]]] (let [[t0 c0] (before s [0 0]) dt (- t t0)]
                                 (when (pos? (- c c0)) [s (/ (Math/round (/ dt 1e4)) 100.0) (- c c0)]))))
         (sort-by second >) (take n) vec)))

(defn sampled
  "Run (f) on a thread, sampling its stack every ms; the top n seon frames
  directly above a frame matching `needle`, counted."
  [n needle f]
  (let [done (promise)
        t (Thread. (fn [] (try (f) (finally (deliver done true)))))
        counts (java.util.HashMap.)]
    (.start t)
    (loop []
      (when-not (realized? done)
        (let [st (.getStackTrace t)
              names (mapv #(str (.getClassName ^StackTraceElement %)) st)
              i (first (keep-indexed (fn [i s] (when (.contains ^String s needle) i)) names))]
          (when i
            (let [callers (->> (subvec names (inc i)) (filter #(and (.startsWith ^String % "seon") (not (.contains ^String % needle)))) (take 3) (mapv #(clojure.string/replace % #"\$.*?(\$|$)" "/")))]
              (.merge counts callers 1 +))))
        (Thread/sleep 1)
        (recur)))
    (->> counts (sort-by val >) (take n) vec)))

(defn sci-stages [agent-id code]
  (let [acc (volatile! {})
        found (tm acc :instance (#'seon.mcp/instance @rt/running-instances nil))
        custody (tm acc :execution (#'seon.mcp/execution found agent-id nil))
        answer (tm acc :evaluate-source (seon.cluster.agent/evaluate-source!
                                         (assoc custody :seon.agent/segmenter 'seon.turn/code-sources :seon.cluster.reply/text code)))
        _ (tm acc :observed (#'seon.mcp/observed (:seon.turn/id answer) (:seon.db/db answer) agent-id "default"))]
    (assoc @acc :answer-keys (vec (keys answer)) :timings (:seon.batch/timings answer))))

(defn- fn-name [^StackTraceElement e]
  (let [c (.getClassName e) m (clojure.string/split c #"\$")]
    (when (and (> (count m) 1) (.startsWith c "seon")) (str (first m) "/" (second m)))))

(defn hot
  "Run (f) on a thread sampling its stack every 0.5 ms; answers, per seon fn,
  the samples it was on the stack (inclusive), top n, and the sample count."
  [n f]
  (let [done (promise)
        t (Thread. (fn [] (try (f) (finally (deliver done true)))))
        counts (java.util.HashMap.)
        total (volatile! 0)]
    (.start t)
    (loop []
      (when-not (realized? done)
        (vswap! total inc)
        (doseq [s (distinct (keep fn-name (.getStackTrace t)))] (.merge counts s 1 +))
        (java.util.concurrent.locks.LockSupport/parkNanos 500000)
        (recur)))
    {:samples @total :top (->> counts (map (fn [[k v]] [k (Math/round (* 100.0 (/ v @total)))])) (sort-by second >) (take n) vec)}))

(defn hot-all
  "As `hot`, but samples every other thread whose stack holds a frame
  containing `needle` (the proc thread a submission waits on)."
  [n needle f]
  (let [me (Thread/currentThread) done (promise)
        t (Thread. (fn [] (try (f) (finally (deliver done true)))))
        counts (java.util.HashMap.)
        total (volatile! 0)]
    (.start t)
    (loop []
      (when-not (realized? done)
        (doseq [[th st] (Thread/getAllStackTraces)
                :when (and (not= th t) (not= th me))
                :let [names (keep fn-name st)]
                :when (some #(.contains ^String % needle) names)]
          (vswap! total inc)
          (doseq [s (distinct names)] (.merge counts s 1 +)))
        (java.util.concurrent.locks.LockSupport/parkNanos 500000)
        (recur)))
    {:samples @total :top (->> counts (map (fn [[k v]] [k (Math/round (* 100.0 (/ v (max 1 @total))))])) (sort-by second >) (take n) vec)}))

(defn hot-proc
  "Sample (every 0.5 ms) the threads found running `needle` frames without
  seon.mcp frames, during (f); inclusive percent per seon fn."
  [n needle f]
  (let [me (Thread/currentThread) done (promise)
        t (Thread. (fn [] (try (f) (finally (deliver done true)))))
        counts (java.util.HashMap.) total (volatile! 0)
        watched (volatile! #{}) tick (volatile! 0)]
    (.start t)
    (loop []
      (when-not (realized? done)
        (when (zero? (mod (vswap! tick inc) 50))
          (doseq [[th st] (Thread/getAllStackTraces)
                  :when (and (not= th t) (not= th me))
                  :let [names (keep fn-name st)]
                  :when (and (some #(.contains ^String % needle) names) (not (some #(.startsWith ^String % "seon.mcp") names)))]
            (vswap! watched conj th)))
        (doseq [^Thread th @watched
                :let [names (distinct (keep fn-name (.getStackTrace th)))]
                :when (seq names)]
          (vswap! total inc)
          (doseq [s names] (.merge counts s 1 +)))
        (java.util.concurrent.locks.LockSupport/parkNanos 500000)
        (recur)))
    {:samples @total :threads (mapv #(.getName ^Thread %) @watched)
     :top (->> counts (map (fn [[k v]] [k (Math/round (* 100.0 (/ v (max 1 @total))))])) (sort-by second >) (take n) vec)}))

(defn- caller-of [needle names]
  (when-let [i (first (keep-indexed (fn [i s] (when (= s needle) i)) names))]
    (->> (drop (inc i) names)
         (remove #(or (.startsWith ^String % "seon.db/") (.startsWith ^String % "seon.schema") (.startsWith ^String % "seon.instrument")
                      (.startsWith ^String % "seon.call_preparation") (.startsWith ^String % "seon.cache")))
         (take 2) vec)))

(defn callers-proc
  "Like hot-proc, but counts the two nearest non-db callers of `target` frames."
  [n needle target f]
  (let [me (Thread/currentThread) done (promise)
        t (Thread. (fn [] (try (f) (finally (deliver done true)))))
        counts (java.util.HashMap.) watched (volatile! #{}) tick (volatile! 0)]
    (.start t)
    (loop []
      (when-not (realized? done)
        (when (zero? (mod (vswap! tick inc) 50))
          (doseq [[th st] (Thread/getAllStackTraces)
                  :when (and (not= th t) (not= th me))
                  :let [names (keep fn-name st)]
                  :when (and (some #(.contains ^String % needle) names) (not (some #(.startsWith ^String % "seon.mcp") names)))]
            (vswap! watched conj th)))
        (doseq [^Thread th @watched
                :let [c (caller-of target (vec (keep fn-name (.getStackTrace th))))]
                :when (seq c)]
          (.merge counts c 1 +))
        (java.util.concurrent.locks.LockSupport/parkNanos 300000)
        (recur)))
    (->> counts (map (fn [[k v]] [k v])) (sort-by second >) (take n) vec)))

(defn sci-batch
  "The MCP SCI run as jvm-run does its JVM run: the fold over the SCI stages
  (turn.batch/code-request) in the agent's own world, settled in one transaction."
  [agent-id code]
  (let [acc (volatile! {})
        [_ world] (tm acc :world (world agent-id))
        b (tm acc :batch (seon.batch/evaluate-batch
                          (seon.turn.batch/code-request {:seon.batch/world world :seon.batch/text code :seon.batch/start-ns 'user
                                                         :seon.turn.batch/record-call #'seon.turn/record-evaluated-call})))
        after (:seon.batch/after (:seon.batch/commit b))
        obs (when after (tm acc :observed (#'seon.mcp/observed (:seon.turn/id world) after agent-id "default")))]
    (assoc @acc :timings (:seon.batch/timings b) :obs obs
           :diag (mapv :seon.error/message (:seon.batch/diagnostics b))
           :commit (select-keys (:seon.batch/commit b) [:seon.batch/applied :seon.batch/tx-count :seon.batch/writer-refusal :seon.batch/storage-error]))))

(defn- frame [^StackTraceElement e]
  (let [c (.getClassName e)] (str (first (clojure.string/split c #"\$")) "/" (second (clojure.string/split c #"\$")) ":" (.getMethodName e))))

(defn hot-lib
  "Sample every thread whose stack mentions `needle` during (f) (excluding
  the caller); inclusive percent per frame whose class starts with a prefix."
  [n needle prefixes f]
  (let [me (Thread/currentThread) done (promise)
        t (Thread. (fn [] (try (f) (finally (deliver done true)))))
        counts (java.util.HashMap.) total (volatile! 0)]
    (.start t)
    (loop []
      (when-not (realized? done)
        (doseq [[th st] (Thread/getAllStackTraces)
                :when (and (not= th me) (not= th t))
                :let [cls (map #(.getClassName ^StackTraceElement %) st)]
                :when (some #(.contains ^String % needle) cls)]
          (vswap! total inc)
          (doseq [s (distinct (keep #(let [c (.getClassName ^StackTraceElement %)]
                                       (when (some (fn [p] (.startsWith c p)) prefixes) (frame %))) st))]
            (.merge counts s 1 +)))
        (java.util.concurrent.locks.LockSupport/parkNanos 200000)
        (recur)))
    {:samples @total :top (->> counts (map (fn [[k v]] [k (Math/round (* 100.0 (/ v (max 1 @total))))])) (sort-by second >) (take n) vec)}))

(defn jfr
  "Record CPU and wall-clock samples (jdk.ExecutionSample 1 ms plus
  jdk.NativeMethodSample) while (f) runs; answers inclusive percent per frame
  (matching one of `prefixes`) over samples from threads whose stack holds a
  frame containing `needle`."
  [n needle prefixes f]
  (let [rec (jdk.jfr.Recording.)
        path (java.nio.file.Paths/get "/Users/sean/src/seon/tmp/mcp-dogfood/run.jfr" (make-array String 0))]
    (doto rec
      (-> (.enable "jdk.ExecutionSample") (.withPeriod (java.time.Duration/ofMillis 1)))
      (-> (.enable "jdk.NativeMethodSample") (.withPeriod (java.time.Duration/ofMillis 1)))
      (.start))
    (try (f) (finally (.stop rec) (.dump rec path) (.close rec)))
    (let [counts (java.util.HashMap.) total (volatile! 0)]
      (with-open [rf (jdk.jfr.consumer.RecordingFile. path)]
        (while (.hasMoreEvents rf)
          (let [e (.readEvent rf)
                st (.getStackTrace e)]
            (when st
              (let [frames (map (fn [^jdk.jfr.consumer.RecordedFrame fr]
                                  (let [m (.getMethod fr)] (str (.getName (.getType m)) "." (.getName m))))
                                (.getFrames st))]
                (when (some #(.contains ^String % needle) frames)
                  (vswap! total inc)
                  (doseq [s (distinct (filter (fn [s] (some #(.startsWith ^String s %) prefixes)) frames))]
                    (.merge counts s 1 +))))))))
      {:samples @total
       :top (->> counts (map (fn [[k v]] (str (Math/round (* 100.0 (/ v (max 1 @total)))) "% " k))) (sort-by #(- (Long/parseLong (first (clojure.string/split % #"%"))))) (take n) vec)})))
