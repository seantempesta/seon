(ns scratch-rows
  "Scratch JVM only (the patched SCI clone plus clj-kondo): the SCI fork proofs
  of the one-path proof suite, written to tmp/one-path/scratch-results.edn,
  and every corpus bind's producer origin to tmp/one-path/origins.edn.

  cd tmp/one-path/sci-clone && clojure -Sdeps '{:deps {clj-kondo/clj-kondo {:mvn/version \"2026.07.24\"}}}' \\
    -M:test -i ../scratch_rows.clj -e '(scratch-rows/-main)'"
  (:require [clj-kondo.core :as kondo]
            [clojure.string :as str]
            [sci.addons.future :as sci.future]
            [sci.core :as sci]
            [sci.lang]))

(def dir "/Users/sean/src/seon/tmp/one-path/")

(defn- observed
  "Evaluate `src` in `ctx` under one write observer: [value writes expansions]."
  [ctx src]
  (let [writes (atom []) expansions (atom [])
        rdr (sci/source-reader src)
        value (binding [sci.lang/*write-observer*
                        (fn [e] (swap! writes conj
                                       (cond-> (dissoc e :var)
                                         (:var e) (assoc :sym (let [m (meta (:var e))]
                                                                (when (:name m) (symbol (str (:ns m)) (str (:name m)))))
                                                         :v (:var e)))))
                        sci.lang/*expansion-observer* (fn [call exp] (swap! expansions conj [call exp]))]
                (loop [ret nil]
                  (let [f (sci/parse-next ctx rdr {:end-location true})]
                    (if (= ::sci/eof f) ret (recur (sci/eval-form ctx f))))))]
    [value @writes @expansions]))

(defn- binds [ws] (filterv #(= :bind (:op %)) ws))

(defn- suite [file]
  (let [t (slurp (str dir file))
        [_ tests assertions] (re-find #"Ran (\d+) tests containing (\d+) assertions" t)
        [_ fails errors] (re-find #"(\d+) failures, (\d+) errors" t)]
    {:tests (parse-long tests) :assertions (parse-long assertions)
     :fail-count (parse-long fails) :errors (parse-long errors)
     :failures (vec (sort (distinct (re-seq #"FAIL in \([^)]*\) \([^)]*\)" t))))}))

(defmacro row [& body] `(try ~@body (catch Throwable t# {:pass? false :threw (str (.getName (class t#)) ": " (ex-message t#))})))

(defn proofs []
  {:v2-1-inherited
   (row (let [base (sci/init {})
              _ (sci/eval-string* base "(def f (fn [] 1))")
              ctx (sci/fork base) seen (atom [])
              v (binding [sci.lang/*write-observer*
                          (fn [e] (when (= :bind (:op e))
                                    (let [v (:var e)]
                                      (swap! seen conj {:same-var? (identical? v (sci/resolve ctx 'user/f)) :observed ((:root e))})
                                      (binding [sci.lang/*write-observer* nil]
                                        (sci/bind-root! ctx v (let [f (:root e)] (fn [] (+ 10 (f)))))))))]
                  (sci/eval-string* ctx "(def f (fn [] 2)) (f)"))]
          {:pass? (and (= 12 v) (= [{:same-var? true :observed 2}] @seen) (= 1 (sci/eval-string* base "(f)")))
           :value v :events @seen}))
   :v2-1-nested-callback
   (row (let [ctx (sci/init {}) seen (atom []) once (atom false)]
          (binding [sci.lang/*write-observer*
                    (fn [e] (swap! seen conj (str (:name (meta (:var e)))))
                      (when (compare-and-set! once false true)
                        (binding [sci.lang/*write-observer* nil] (sci/eval-string* ctx "(def nested 9)"))))]
            (sci/eval-string* ctx "(def outer 1)"))
          {:pass? (and (= ["outer"] @seen) (= 9 (sci/eval-string* ctx "nested"))) :events @seen}))
   :v2-1-throwing-watch
   (row (let [ctx (sci/init {}) seen (atom [])]
          (sci/eval-string* ctx "(def w 1) (add-watch #'w :bad (fn [& _] (throw (ex-info \"watch failed\" {}))))")
          (let [answer (binding [sci.lang/*write-observer* #(swap! seen conj (select-keys % [:op :root]))]
                         (try (sci/eval-string* ctx "(alter-var-root #'w inc)") (catch Throwable t (ex-message t))))]
            {:pass? (and (= "watch failed" answer) (= 2 (sci/eval-string* ctx "w")) (= [{:op :bind :root 2}] @seen))
             :answer answer :events @seen})))
   :r1-every-write
   (row (let [ctx (sci/init {})
              _ (sci/eval-string* ctx "(def f 1)")
              [v1 w1] (observed ctx "(def f 2)\n(intern 'user 'f 3)\n(alter-var-root #'f inc)\nf")
              [_ w2] (observed (sci/init {}) "(intern 'user 'g 5)\n(defonce o 1)\n(defonce o 2)\n(declare d)")
              [v3 w3] (observed (sci/init {}) "(do (eval '(def h 1)) h)")
              [_ w4] (observed (sci/init {}) "(def u 1)\n(ns-unmap *ns* 'u)\n(create-ns 'gone)\n(remove-ns 'gone)")
              base (sci/init {}) _ (sci/eval-string* base "(def k 1)")
              [v5 w5] (observed (sci/fork base) "(alter-var-root #'k inc)\nk")]
          {:pass? (and (= 4 v1) (= [2 3 4] (mapv :root (binds w1)))
                       (= [{:line 1 :column 1 :end-line 1 :end-column 10} nil nil] (mapv :origin (binds w1)))
                       (= '[user/g user/o] (mapv :sym (binds w2)))
                       (= 1 v3) (= '[user/h] (mapv :sym (binds w3)))
                       (= [:bind :unmap :remove-ns] (mapv :op w4))
                       (= 2 v5) (= '[user/k] (mapv :sym (binds w5))) (= 1 (sci/eval-string* base "k")))
           :retained (mapv #(select-keys % [:root :origin]) (binds w1))}))
   :v2-2-single-def-macro
   (row (let [[v ws] (observed (sci/init {}) "(defmacro single [] (list 'def 'made 7))\n(single)\nmade")]
          {:pass? (and (= 7 v) (= {:line 2 :column 1 :end-line 2 :end-column 9 :expanded-by 'single} (:origin (last (binds ws)))))
           :origin (:origin (last (binds ws)))}))
   :v2-2-generated-in-let
   (row (let [[_ ws] (observed (sci/init {}) "(defmacro pair [] `(do (def ~'px 1) (def ~'py 2)))\n(let [z 0]\n  (pair))")]
          {:pass? (= [{:line 3 :column 3 :end-line 3 :end-column 9 :expanded-by 'pair}
                      {:line 3 :column 3 :end-line 3 :end-column 9 :expanded-by 'pair}]
                     (mapv :origin (rest (binds ws))))
           :origins (mapv :origin (rest (binds ws)))}))
   :extensions-marked
   (row (let [[_ ws] (observed (sci/init {}) "(defprotocol P (pm [x]))\n(extend-protocol P String (pm [x] 1))\n(defrecord R [a] P (pm [_] a))")
              by (group-by :sym (binds ws))]
          {:pass? (and (= [:root :root :extend :extend] (mapv :kind (get by 'user/P)))
                       (= '->R (some #(:sci.impl/member-of (meta (:v %))) (binds ws))))
           :protocol-writes (mapv :kind (get by 'user/P))}))
   :expansion-observer
   (row (let [[_ _ es] (observed (sci/init {}) "(defmacro single [] (list 'def 'made 7))\n(single)")]
          {:pass? (= '(def made 7) (some (fn [[call exp]] (when (= 'single (first call)) exp)) es))}))
   :future-conveys
   (row (let [ctx (sci/init (sci.future/install {}))
              [v ws] (observed ctx "@(future (def fut 1))\nfut")]
          {:pass? (and (= 1 v) (= '[user/fut] (mapv :sym (binds ws)))) :events (mapv :sym (binds ws))}))
   :two-threads-one-context
   (row (let [ctx (sci/init {})
              run (fn [prefix] (let [seen (atom [])]
                                 (binding [sci.lang/*write-observer* #(swap! seen conj (str (:name (meta (:var %)))))]
                                   (dotimes [i 50] (sci/eval-string* ctx (str "(def " prefix i " " i ")"))))
                                 @seen))
              a (future (run "a")) b (future (run "b"))
              [sa sb] [@a @b]]
          {:pass? (and (= 50 (count sa)) (= 50 (count sb))
                       (every? #(str/starts-with? % "a") sa) (every? #(str/starts-with? % "b") sb))
           :counts [(count sa) (count sb)]}))
   :q1-single-def-kondo
   (row (let [src "(defmacro single [] (list 'def 'made 7))\n(single)"
              [_ ws es] (observed (sci/init {}) src)
              exp (some (fn [[call exp]] (when (= 'single (first call)) exp)) es)
              hook (str "(require '[clj-kondo.hooks-api :as api])\n(fn [{:keys [node]}] {:node (api/coerce '" (pr-str exp) ")})")
              a (:analysis (with-in-str src (kondo/run! {:lint ["-"] :cache false
                                                         :config {:output {:format :none}
                                                                  :hooks {:__dangerously-allow-string-hooks__ true
                                                                          :analyze-call {'user/single hook}}
                                                                  :analysis true}})))
              made (first (filter #(= 'made (:name %)) (:var-definitions a)))]
          {:pass? (and made (= [2 1] [(:row made) (:col made)])) :made (select-keys made [:name :row :col :defined-by])}))})

(defn origins
  "Every corpus bind's producer origin, the forms' spans and the macro
  expansions, per item."
  []
  (load-file "/Users/sean/src/seon/tmp/valid-clojure-corpus/runner.clj")
  (into (sorted-map)
        (for [{:keys [id source]} ((resolve 'runner/corpus))]
          (let [ctx (sci/init {:features #{:clj}})
                writes (atom []) forms (atom []) n (atom -1)
                rdr (sci/source-reader source)]
            (binding [sci.lang/*write-observer*
                      (fn [e] (when (and (= :bind (:op e)) (:ns (meta (:var e))))
                                (swap! writes conj {:sym (symbol (str (:ns (meta (:var e)))) (str (:name (meta (:var e)))))
                                                    :origin (:origin e) :kind (:kind e) :form @n
                                                    :member-of (:sci.impl/member-of (meta (:var e)))})))]
              (sci/binding [sci/ns @sci/ns]
                (loop []
                  (let [f (try (sci/parse-next ctx rdr {:end-location true}) (catch Throwable _ ::sci/eof))]
                    (when-not (= ::sci/eof f)
                      (swap! n inc)
                      (swap! forms conj (select-keys (meta f) [:line :column :end-row :end-col]))
                      (try (sci/eval-form ctx f) (catch Throwable _ nil))
                      (recur))))))
            [id {:writes @writes :forms @forms :source source}]))))

(defn -main []
  (spit (str dir "origins.edn") (pr-str (origins)))
  (spit (str dir "scratch-results.edn")
        (pr-str {:suite {:patched (suite "sci-patched-tests.txt") :base (suite "sci-base-tests.txt")}
                 :proofs (proofs)}))
  (shutdown-agents)
  (System/exit 0))
