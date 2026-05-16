(ns seon.podhost.datahike-harness.metrics
  "Timing primitives + result formatting. Results emit as EDN for diffing
   across runs.")

(defmacro time-ms
  "Returns [result elapsed-ms]. Use a macro so the expression is evaluated
   in the caller's lexical scope without an extra closure."
  [expr]
  `(let [t0# (System/nanoTime)
         r#  ~expr
         dt# (/ (- (System/nanoTime) t0#) 1e6)]
     [r# dt#]))

(defmacro time-block
  "Same as time-ms but for a body of forms."
  [& body]
  `(let [t0# (System/nanoTime)
         r#  (do ~@body)
         dt# (/ (- (System/nanoTime) t0#) 1e6)]
     [r# dt#]))

(defn fmt-ms [ms]
  (cond
    (nil? ms)   "—"
    (< ms 0)    "ERR"
    (< ms 1)    (format "%.2fms" (double ms))
    (< ms 100)  (format "%.1fms" (double ms))
    (< ms 1000) (format "%dms"   (long ms))
    :else       (format "%.2fs"  (/ ms 1000.0))))

(defn eps
  "entities per second"
  [n ms]
  (long (/ n (/ ms 1000.0))))

(defn pp-row
  "Format one bench result as a fixed-width row."
  [{:keys [backend size load-ms load-eps queries]}]
  (let [pad (fn [s n] (subs (str s (apply str (repeat n " "))) 0 n))
        q-ms (fn [k] (some-> queries k :ms double))]
    (str (pad (name backend) 8)
         " " (pad (str size) 8)
         " " (pad (fmt-ms load-ms) 10)
         " " (pad (str load-eps " eps") 14)
         " " (pad (fmt-ms (q-ms :scan-all)) 8)
         " " (pad (fmt-ms (q-ms :scan-by-tag)) 8)
         " " (pad (fmt-ms (q-ms :indexed-by-id)) 8)
         " " (pad (fmt-ms (q-ms :pull-by-path)) 8)
         " " (pad (fmt-ms (q-ms :range-by-time)) 8))))

(def header
  (let [pad (fn [s n] (subs (str s (apply str (repeat n " "))) 0 n))]
    (str (pad "backend" 8) " " (pad "size" 8) " " (pad "load" 10) " "
         (pad "throughput" 14) " "
         (pad "scan" 8) " " (pad "by-tag" 8) " " (pad "by-id" 8) " "
         (pad "pull" 8) " " (pad "range" 8))))
