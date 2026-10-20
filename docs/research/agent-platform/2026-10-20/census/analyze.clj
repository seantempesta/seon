;; Census analysis. Input: raw dumps in tmp/census (see dump-forms.clj), verdicts.edn, resources, one stored operator-check log.
;; Run from the checkout root: clojure -M docs/research/agent-platform/2026-10-20/census/analyze.clj
(require 'clojure.set)
(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str] '[clojure.pprint :as pp])
(def dir "docs/research/agent-platform/2026-10-20/census/")
(defn read-all [f] (with-open [r (java.io.PushbackReader. (io/reader f))] (vec (take-while #(not= % ::eof) (repeatedly #(edn/read {:eof ::eof} r))))))
(def fns (read-all "tmp/census/raw-fns.edn"))
(def tests (read-all "tmp/census/raw-tests.edn"))
(def runs (first (read-all "tmp/census/raw-runs.edn")))
(def verdicts (edn/read-string (slurp (str dir "verdicts.edn"))))
(def full-run-log "data/clusters/default/logs/operator-check-20261019T230731.687.edn")
(def stored-reds (set (map #(symbol (second %)) (re-seq #"red ([a-z0-9.\-]+-test/[^\s:]+):" (slurp full-run-log)))))
;; ---- graph over every fn entity (src, script, test helpers) and test entities
(set! *warn-on-reflection* true)
(def fn-by-sym (into {} (map (juxt :seon.fn/sym identity)) fns))
(def src? #(re-find #"^(src|script)/" (or (:file %) "")))
(def idx (let [all (concat (map :seon.fn/sym fns) (map :seon.test/sym tests))] (zipmap all (range))))
(def ^long n (count idx))
(def ^"[Ljava.lang.Object;" sym-of (let [a (object-array n)] (doseq [[s i] idx] (aset a (int i) s)) a))
(def ^"[Ljava.lang.Object;" adj (let [a (object-array n)]
           (doseq [m (concat fns tests)]
             (let [s (or (:seon.fn/sym m) (:seon.test/sym m))]
               (aset a (int (idx s)) (int-array (keep idx (:seon.fn/calls m))))))
           a))
(def ^"[Z" src-flag (let [a (boolean-array n)] (doseq [m fns :when (src? m)] (aset a (int (idx (:seon.fn/sym m))) true)) a))
(def test-ids (mapv (comp idx :seon.test/sym) tests))
(def ^"[I" stamp (int-array n))
(def ^"[I" order (int-array n))
(defn closure!
  "Marks everything reachable from t with mark; returns the count, visited ids in `order`."
  ^long [^long t ^long mark]
  (let [mark (int mark)]
    (aset stamp t mark) (aset order 0 (int t))
    (loop [head 0 tail 1]
      (if (< head tail)
        (let [u (aget order head) ^ints es (aget adj u)
              tail (loop [j 0 tail tail]
                     (if (< j (alength es))
                       (let [v (aget es j)]
                         (if (== (aget stamp v) mark) (recur (inc j) tail)
                             (do (aset stamp v mark) (aset order tail v) (recur (inc j) (inc tail)))))
                       tail))]
          (recur (inc head) tail))
        tail))))
(def ^"[I" reach-count (int-array n))   ; tests whose closure contains the fn
(def ^"[I" direct-count (int-array n))  ; tests calling the fn directly
(def ^"[I" red-reach (int-array n)) (def ^"[I" green-reach (int-array n))
(def test-reach (volatile! {}))   ; test sym -> [src-size total]
(def ns-hits (java.util.HashMap.))
(def run-by-sym (into {} (map (juxt :seon.test.member/symbol identity)) runs))
(def result-of
  (fn [sym] (cond (run-by-sym sym) (if (pos? (+ (:seon.test.member/fail-count (run-by-sym sym)) (:seon.test.member/error-count (run-by-sym sym)))) :red :green)
                  (stored-reds sym) :red :else :never-run)))
(def test-result (into {} (map (fn [t] [(:seon.test/sym t) (result-of (:seon.test/sym t))])) tests))
(doseq [[k t] (map-indexed vector test-ids)]
  (let [t (int t) cnt (closure! t (inc k)) r (test-result (aget sym-of t))
        nss (java.util.HashSet.) src-size (volatile! 0)]
    (dotimes [j cnt]
      (let [u (aget order j)]
        (aset reach-count u (inc (aget reach-count u)))
        (case r :red (aset red-reach u (inc (aget red-reach u))) :green (aset green-reach u (inc (aget green-reach u))) nil)
        (when (aget src-flag u) (vswap! src-size inc) (.add nss (namespace (aget sym-of u))))))
    (doseq [ns nss] (.add ^java.util.Set (.computeIfAbsent ns-hits ns (reify java.util.function.Function (apply [_ _] (java.util.HashSet.)))) k))
    (doseq [v (aget adj t)] (aset direct-count (int v) (inc (aget direct-count (int v)))))
    (vswap! test-reach assoc (aget sym-of t) [@src-size cnt])))
(def tests-reaching-ns (into {} (map (fn [[k v]] [(symbol k) (count v)])) ns-hits))

;; ---- callers
(def ^"[I" callers-src (int-array n)) (def ^"[I" callers-test (int-array n))
(doseq [m fns :let [u (idx (:seon.fn/sym m))] v (distinct (aget adj u)) :when (not= u v)]
  (if (src? m) (aset callers-src v (inc (aget callers-src v))) (aset callers-test v (inc (aget callers-test v)))))

;; ---- entrants: qualified symbols named in resources, bin, deps.edn; plus public script/my/main
(def sym-re #"[a-z][A-Za-z0-9.\-_]*/[A-Za-z0-9\-?!*<>=+_.]+")
(def resource-syms
  (->> (concat (filter #(.isFile %) (file-seq (io/file "resources"))) (filter #(.isFile %) (file-seq (io/file "bin"))) [(io/file "deps.edn") (io/file "bb.edn")])
       (filter #(re-find #"\.(edn|clj|cljc|md)$|^[^.]*$" (.getName %)))
       (mapcat #(try (re-seq sym-re (slurp %)) (catch Exception _ nil)))
       (map symbol) set))
(def mcp-entrance #{'seon.mcp/tools 'seon.mcp/call})
(defn entrant-reason [m]
  (let [s (:seon.fn/sym m) nm (name s)]
    (cond (= nm "-main") :main
          (resource-syms s) :referenced-by-resource
          (mcp-entrance s) :mcp-entrance
          (str/starts-with? (namespace s) "my.") (when-not (:seon.fn/private? m) :agent-verb)
          (str/starts-with? (or (:file m) "") "script/") (when-not (:seon.fn/private? m) :script-public)
          (:seon.fn/macro? m) :macro)))

;; ---- classification
(def lookup-names '{clojure.core/requiring-resolve requiring-resolve clojure.core/resolve resolve clojure.core/ns-resolve ns-resolve
                    clojure.core/find-var find-var clojure.core/find-ns find-ns clojure.core/intern intern clojure.core/var-get var-get
                    clojure.core/the-ns the-ns clojure.core/ns-publics ns-publics clojure.core/ns-interns ns-interns clojure.core/ns-map ns-map})
(defn kind [m] (let [d (name (:seon.fn/defined-by m))]
                 (cond (:seon.fn/macro? m) :macro (#{"defn" "defn-"} d) :fn (#{"defrecord" "deftype" "definterface"} d) :type
                       (:seon.fn/constant? m) :constant :else :def)))
(defn contract [m k]
  (let [sp (:seon.fn/spec m)]
    (cond (not (#{:fn :macro} k)) :not-applicable
          (:seon.fn/malformed-spec m) :malformed
          (nil? sp) :none
          (or (re-find #"\[:fn\b" sp) (re-find #":any\b" sp) (re-find #":some\b" sp) (>= (count (re-seq #":map(?![-\w])" sp)) 2)) :weak
          :else :complete)))
(defn ns-verdict [ns]
  (let [e (get-in verdicts [:namespaces ns])
        p (when-not e (some (fn [[pre v]] (when (str/starts-with? ns pre) v)) (sort-by (comp - count key) (:prefixes verdicts))))
        v (or e p)]
    (when-not v (println "NO VERDICT" ns))
    (when v (zipmap [:verdict :jolt-status :rows :reason :replaced-parts] v))))

;; ---- dead code: reachable from entrants+tests?
(def entrant-ids (vec (for [m fns :when (and (src? m) (entrant-reason m))] (idx (:seon.fn/sym m)))))
(def ^"[Z" live (let [seen (boolean-array n)]
            (doseq [[k i] (map-indexed vector (concat entrant-ids test-ids))]
              (let [cnt (closure! (long i) (+ 500000 k))] (dotimes [j cnt] (aset seen (aget order j) true))))
            seen))

(def lookups-by-sym (into {} (for [m fns :let [ls (vec (keep lookup-names (:seon.fn/calls m)))] :when (seq ls)] [(:seon.fn/sym m) ls])))

(def function-rows
  (vec (for [m (sort-by :seon.fn/sym fns) :when (src? m)
             :let [s (:seon.fn/sym m) i (idx s) ns (namespace s) k (kind m) v (ns-verdict ns)
                   rt (aget reach-count i) er (entrant-reason m)
                   lk (get lookups-by-sym s)
                   dead (when-not er (cond (and (zero? (aget callers-src i)) (zero? (aget callers-test i)) (zero? rt)) :direct
                                           (not (aget live i)) :transitive))]]
         (cond-> {:census/sym s :census/ns (symbol ns) :census/file (:file m) :census/kind k :census/lines (:lines m)
                  :census/private? (boolean (:seon.fn/private? m)) :census/host-bound? (boolean (:seon.fn/host-bound? m))
                  :census/doc? (:doc? m)
                  :census/contract (contract m k)
                  :census/callers (aget callers-src i) :census/test-callers (aget callers-test i)
                  :census/reaching-tests rt :census/direct-tests (aget direct-count i)
                  :census/red-tests (aget red-reach i) :census/green-tests (aget green-reach i)
                  :census/last-result (cond (zero? rt) :no-tests (pos? (aget red-reach i)) :reached-by-red (pos? (aget green-reach i)) :reached-by-green-only :else :never-run)
                  :census/verdict (:verdict v)}
           lk (assoc :census/lookups lk)
           (some #{'clojure.core/try} (:seon.fn/calls m)) (assoc :census/try? true)
           er (assoc :census/entrant er)
           dead (assoc :census/dead dead)))))

(defn test-ns [s] (namespace s))
(def test-rows
  (vec (for [t (sort-by :seon.test/sym tests)
             :let [s (:seon.test/sym t) [ss tot] (@test-reach s) r (test-result s) mem (run-by-sym s)]]
         (cond-> {:census/sym s :census/ns (symbol (namespace s)) :census/file (:file t) :census/lines (:lines t)
                  :census/reach-src ss :census/reach-total tot
                  :census/last-result r
                  :census/result-source (cond mem :stored-member-run-on-default (stored-reds s) :operator-check-20261019T230731.687-red-list :else :none)
                  :census/asserts (:asserts t)
                  :census/long? (boolean (:seon.test/long t))
                  :census/platform? (boolean (:seon.test/platform t))
                  :census/time-bound-ms (or (:seon.test/long-ms t) (:seon.test/check-time-limit-ms t) 5000)
                  :census/time-bound-source (cond (:seon.test/long-ms t) :long-ms (:seon.test/check-time-limit-ms t) :check-time-limit-ms :else :default-5000)}
           (:seon.test/long t) (assoc :census/long-reason (:seon.test/long t))
           mem (assoc :census/pass (:seon.test.member/pass-count mem) :census/fail (:seon.test.member/fail-count mem)
                      :census/error (:seon.test.member/error-count mem) :census/run-ms (:seon.test.member/run-ms mem))))))

;; ---- namespaces
(def by-ns (group-by :census/ns function-rows))
(def tests-by-ns-named (frequencies (map #(let [x (str (:census/ns %))] (symbol (str/replace x #"-test$" ""))) test-rows)))
(def rr-sites ; enclosing functions of the 19-Oct inventory's production call sites (class other than prose), parsed from its JSON
  (let [j (slurp "tmp/rr-inventory/final.json")
        blocks (re-seq #"(?s)\{\s*\"file\": \"([^\"]+)\",\s*\"line\": (\d+),\s*\"ns\": \"[^\"]*\",\s*\"fn\": \"([^\"]*)\".*?\"class\": \"([^\"]+)\"" j)]
    (->> blocks (remove #(= "prose" (nth % 4))) (map #(symbol (nth % 3))) set)))
(def namespace-rows
  (vec (for [[ns rows] (sort-by key by-ns)
             :let [v (ns-verdict (str ns)) fnrows (filter #(#{:fn :macro} (:census/kind %)) rows)
                   c (frequencies (map :census/contract fnrows))]]
         {:census/ns ns :census/file (:census/file (first rows)) :census/functions (count rows) :census/lines (reduce + 0 (keep :census/lines rows))
          :census/fns-contractable (count fnrows) :census/contract-counts c
          :census/lookup-fns (count (filter :census/lookups rows)) :census/try-fns (count (filter :census/try? rows))
          :census/dead-direct (count (filter #(= :direct (:census/dead %)) rows)) :census/dead-transitive (count (filter #(= :transitive (:census/dead %)) rows))
          :census/entrants (count (filter :census/entrant rows))
          :census/tests-named (get tests-by-ns-named ns 0) :census/tests-reaching (get tests-reaching-ns ns 0)
          :census/verdict (:verdict v) :census/jolt-status (:jolt-status v) :census/rows (:rows v) :census/reason (:reason v)
          :census/replaced-parts (:replaced-parts v)})))

(defn out [f data] (spit (str dir f) (with-out-str (println "[") (doseq [r data] (prn r)) (println "]"))))
(out "functions.edn" function-rows) (out "tests.edn" test-rows) (out "namespaces.edn" namespace-rows)
(spit (str dir "tmp-summary.edn") (pr-str {:n n :nfn (count function-rows) :ntest (count test-rows) :stored-reds (count stored-reds) :resource-syms (count resource-syms)}))
(let [mine (set (map :census/sym (filter :census/lookups function-rows)))
      mine-strict (set (map :census/sym (filter #(some #{'requiring-resolve 'resolve 'ns-resolve 'find-var 'find-ns 'intern 'var-get} (:census/lookups %)) function-rows)))
      src-rr (set (filter #(contains? (set (map :census/sym function-rows)) %) rr-sites))]
  (spit (str dir "rr-crosscheck.edn")
        (pr-str {:census-lookup-fns (count mine) :census-strict-fns (count mine-strict) :inventory-enclosing-fns (count rr-sites) :inventory-fns-found-in-census (count src-rr)
                 :both (count (clojure.set/intersection mine-strict src-rr))
                 :inventory-only (vec (sort (clojure.set/difference src-rr mine-strict)))
                 :census-only (vec (sort (clojure.set/difference mine-strict src-rr)))})))
(println {:fns (count function-rows) :tests (count test-rows) :nss (count namespace-rows) :stored-reds (count stored-reds) :entrants (count entrant-ids)})
