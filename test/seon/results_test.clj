(ns seon.results-test
  "Verdicts are a cache by digest outside the store (docs/merging.md#verdicts-are-a-cache): one file a
  key, digest and status, flaky kept across writers, the least recently used evicted past a capacity, the cost a test spent per
  function kept with its verdict, and nothing written to the store; each test on a cache directory of
  its own under target/tmp."
  (:require [clojure.test :refer [deftest is testing]] [clojure.java.shell :as sh] [seon.config :as cfg]
            [seon.fx :as fx] [seon.proofs :as proofs] [seon.cost :as cost]))

(defn- fc [v calls ticks self bytes] {:var v :calls calls :ticks ticks :self-ticks self :bytes bytes :self-bytes bytes})

(defn- with-dir
  "f called with a fresh cache directory under this checkout's target/tmp, removed after."
  [f]
  (let [dir (str (cfg/checkout) "/target/tmp/verdicts-" (random-uuid))]
    (try (f dir) (finally (sh/sh "rm" "-rf" dir)))))

(defn- rec!
  ([dir ran] (rec! dir ran {}))
  ([dir ran failures] (fx/write-verdicts! dir {:id (str "run " (random-uuid)) :tree "t"} ran failures)))

;; See docs/health.md#a-flaky-test
(deftest a-digest-written-green-and-red-by-two-writers-is-flaky
  (with-dir
   (fn [dir]
     (rec! dir {'a/t {:test 'a/t :digest "d-flip" :pass 1 :fail 0 :error 0}
                'a/u {:test 'a/u :digest "d-green" :pass 1 :fail 0 :error 0}})
     (is (= {"d-flip" {:digest "d-flip" :pass 1 :fail 0 :error 0} "d-green" {:digest "d-green" :pass 1 :fail 0 :error 0}}
            (fx/stored-results dir '{a/t "d-flip" a/u "d-green" a/none "d-none"}))
         "a green is reused; a digest with no verdict is none")
     ;; a second writer of the same digest, red: its own file, so neither verdict is lost
     (rec! dir {'a/t {:test 'a/t :digest "d-flip" :pass 0 :fail 1 :error 0}} {'a/t [{:type :fail :message "boom" :expected "1" :actual "2"}]})
     (let [r (get (fx/stored-results dir '{a/t "d-flip"}) "d-flip")]
       (is (:flaky r) (pr-str r))
       (is (not (proofs/green? r)) "flaky is red, never reused as green")
       (is (some #(= "boom" (:message %)) (:failures r)) "with the failures its red run recorded"))
     (rec! dir {'a/t {:test 'a/t :digest "d-flip" :pass 1 :fail 0 :error 0}})
     (is (:flaky (get (fx/stored-results dir '{a/t "d-flip"}) "d-flip")) "a green after the red leaves it flaky"))))

;; See docs/merging.md#verdicts-are-a-cache
(deftest a-red-is-reused-with-its-failures
  (with-dir
   (fn [dir]
     (rec! dir {'a/r {:test 'a/r :digest "d-red" :pass 0 :fail 0 :error 1}} {'a/r [{:type :error :message "thrown"}]})
     (let [r (get (fx/stored-results dir '{a/r "d-red"}) "d-red")]
       (is (= [1 false] [(:error r) (boolean (:flaky r))]) (pr-str r))
       (is (= ["thrown"] (map :message (:failures r))))))))

;; See docs/merging.md#verdicts-are-a-cache
(deftest flaky-and-unreported-results-write-nothing
  (with-dir
   (fn [dir]
     (is (= {:recorded 0}
            (rec! dir {'a/f {:test 'a/f :digest "d1" :pass 0 :fail 1 :error 0 :flaky true}
                       'a/u {:test 'a/u :digest "d2" :pass 0 :fail 0 :error 1 :unreported true}
                       'a/n {:test 'a/n :pass 1 :fail 0 :error 0}})))
     (is (= {} (fx/stored-results dir '{a/f "d1" a/u "d2"}))))))

;; See docs/failure-points.md
(deftest a-corrupt-verdict-file-is-an-error-never-a-miss
  (with-dir
   (fn [dir]
     (rec! dir {'a/t {:test 'a/t :digest "dc" :pass 0 :fail 0 :error 1}} {'a/t [{:type :error :message "thrown"}]})
     (spit (str dir "/a/t/dc.red.edn") "{:status :red")
     (let [r (fx/stored-results dir '{a/t "dc"})]
       (is (= :seon.fx/io-error (:seon.error/type r)) (pr-str r)))
     (is (nil? (fx/read-verdict (str dir "/a/t/gone.pass.edn"))) "a file that is not there (never written, or evicted meanwhile) is a miss"))))

;; A green's file is about this big (two lines of EDN); the proofs below size their cache in these.
(defn- file-of [dir k dg st] (java.io.File. (str dir "/" (namespace k) "/" (name k) "/" dg "." (name st) ".edn")))

(defn- age!
  "Set the mtime of key k's verdict at digest dg and status st to age-s seconds ago."
  [dir k dg st age-s]
  (.setLastModified (file-of dir k dg st) (- (System/currentTimeMillis) (* 1000 age-s))))

(defn- bytes-in
  "Bytes of the verdict files under dir."
  [dir] (reduce + (map #(.length ^java.io.File %) (filter #(and (.isFile ^java.io.File %) (.endsWith (.getName ^java.io.File %) ".edn")) (file-seq (java.io.File. ^String dir))))))

(defn- size-of [dir k dg st] (.length (file-of dir k dg st)))

(defn- present [dir ks dgs]
  (set (for [k ks dg dgs :when (or (.exists (file-of dir k dg :pass)) (.exists (file-of dir k dg :red)))] dg)))

;; See docs/merging.md#verdicts-are-a-cache
(deftest past-capacity-the-least-recently-used-goes
  (with-dir
   (fn [dir]
     (let [red {'a/r {:test 'a/r :digest "old-red" :pass 0 :fail 1 :error 0}}
           one (fn [dg] (rec! dir {'a/t {:test 'a/t :digest dg :pass 1 :fail 0 :error 0}}))
           _ (rec! dir red {'a/r [{:type :fail :message "red"}]})
           _ (doseq [dg ["g1" "g2" "g3"]] (one dg))
           [r g1 g2 g3] [(size-of dir 'a/r "old-red" :red) (size-of dir 'a/t "g1" :pass) (size-of dir 'a/t "g2" :pass) (size-of dir 'a/t "g3" :pass)]
           _ (doseq [[k dg st s] [['a/r "old-red" :red 400] ['a/t "g1" :pass 300] ['a/t "g2" :pass 100] ['a/t "g3" :pass 200]]] (age! dir k dg st s))
           ;; a bound whose low water (results-evict-percent under it) the new green (about g3's size) fits under only once two are
           ;; evicted: the red (oldest), then g1
           cap (inc (quot (* 100 (+ g2 g3 g3 4)) (- 100 (cfg/value :seon.config.proofs/results-evict-percent))))
           _ (is (= (+ r g1 g2 g3) (bytes-in dir)) "the four files are all the cache holds")
           w (fx/write-verdicts! dir {:id "w" :tree "t"} {'a/t {:test 'a/t :digest "new" :pass 1 :fail 0 :error 0}} {} cap)
           held (present dir ['a/t 'a/r] ["old-red" "g1" "g2" "g3" "new"])]
       (is (= {:recorded 1} w))
       (is (= #{"g2" "g3" "new"} held) "the oldest, a red among them, went first; the one just written stays")
       (is (<= (bytes-in dir) cap) "and the cache is under its bound")))))

;; See docs/merging.md#verdicts-are-a-cache
(deftest under-capacity-nothing-goes
  (with-dir
   (fn [dir]
     (doseq [i (range 5)]
       (fx/write-verdicts! dir {:id "w"} {'a/t {:test 'a/t :digest (str "u" i) :pass 1 :fail 0 :error 0}} {} 1000000))
     (is (= (set (map #(str "u" %) (range 5))) (present dir ['a/t] (map #(str "u" %) (range 5)))))
     ;; exactly at the bound is not past it
     (let [now (bytes-in dir)]
       (fx/write-verdicts! dir {:id "w"} {'a/t {:test 'a/t :digest "u5" :pass 1 :fail 0 :error 0}} {} (+ now (size-of dir 'a/t "u0" :pass)))
       (is (= 6 (count (present dir ['a/t] (map #(str "u" %) (range 6))))))))))

;; See docs/merging.md#verdicts-are-a-cache
(deftest a-read-keeps-a-verdict
  (with-dir
   (fn [dir]
     (doseq [dg ["k1" "k2" "k3"]] (rec! dir {'a/t {:test 'a/t :digest dg :pass 1 :fail 0 :error 0}}))
     (doseq [[dg s] [["k1" 300] ["k2" 200] ["k3" 100]]] (age! dir 'a/t dg :pass s))
     (is (contains? (fx/stored-results dir '{a/t "k1"}) "k1"))
     ;; k1 was the oldest, but the read made it the newest: k2 goes first
     (fx/write-verdicts! dir {:id "w"} {'a/t {:test 'a/t :digest "k4" :pass 1 :fail 0 :error 0}} {}
                         (inc (bytes-in dir)))
     (is (= #{"k1" "k3" "k4"} (present dir ['a/t] ["k1" "k2" "k3" "k4"]))))))

(defn cache-write
  "write-verdicts!'s arguments at n: a cache directory (under target/tmp) holding n verdicts of one
  test each, a new verdict to write, and the bound: with room for it (over? false) or one verdict
  short (over? true), so the write evicts."
  {:malli/schema [:=> [:cat pos-int? :boolean] [:tuple :string :map :map :map :int]]}
  [n over?]
  (let [dir (str (cfg/checkout) "/target/tmp/verdicts-cost-" (random-uuid))
        t (fn [i] (symbol "a" (str "t" i)))
        rec (fn [i] {(t i) {:test (t i) :digest (str "d" i) :pass 1 :fail 0 :error 0}})]
    (doseq [i (range n)] (fx/write-verdicts! dir {:id "w"} (rec i) {} Long/MAX_VALUE))
    [dir {:id "w"} (rec n) {} (if over? (bytes-in dir) Long/MAX_VALUE)]))

(defn cache-write-under [n] (cache-write n false))
(defn cache-write-over [n] (cache-write n true))

(defn cache-read
  "stored-results' arguments at n: a cache directory (under target/tmp) holding a green verdict for each of n tests, and the
  candidates ({test digest}) that ask for all of them."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :string :map]]}
  [n]
  (let [dir (str (cfg/checkout) "/target/tmp/verdicts-cost-" (random-uuid))
        t (fn [i] (symbol "a" (str "t" i)))]
    (fx/write-verdicts! dir {:id "w"} (into {} (for [i (range n)] [(t i) {:test (t i) :digest (str "d" i) :pass 1 :fail 0 :error 0}])) {} Long/MAX_VALUE)
    [dir (into {} (for [i (range n)] [(t i) (str "d" i)]))]))

(defn- clean-costs! [] (sh/sh "sh" "-c" (str "rm -rf " (cfg/checkout) "/target/tmp/verdicts-cost-*")))

;; See docs/merging.md#verdicts-are-a-cache
(deftest a-write-under-capacity-costs-nothing-of-the-cache
  (try (cost/proof {:of 'seon.fx/write-verdicts! :input 'seon.results-test/cache-write-under
                    :n "verdicts already in the cache"
                    :work "the one verdict written, and the size file read and rewritten; no verdict already there is looked at"
                    :growth :constant
                    :fixed {:calls 16 :bytes 60000}
                    :sizes [10 100 1000]})
       (finally (clean-costs!))))

;; See docs/merging.md#verdicts-are-a-cache
(deftest a-write-past-capacity-counts-the-cache-once
  (try (cost/proof {:of 'seon.fx/write-verdicts! :input 'seon.results-test/cache-write-over
                    :n "verdicts in the cache"
                    :work "one count of the verdict files by metadata, one removal of the least recently used"
                    :growth :linear
                    :per-n {:bytes 11500}
                    :fixed {:calls 20 :bytes 60000}
                    :sizes [10 100 1000]})
       (finally (clean-costs!))))

;; See docs/merging.md#verdicts-are-a-cache
(deftest a-green-is-found-by-its-file-never-read
  ;; measured 2026-10-29 on one scripted turn (docs/machine.md#what-a-turn-costs): a turn's plan asked for every test's
  ;; verdict, and read each green's file (2,280 reads, 76.8 MB and 25.1 M ticks) for an answer its existence gives
  (try (cost/proof {:of 'seon.fx/stored-results :input 'seon.results-test/cache-read
                    :n "tests asked for, each with a green"
                    :work "two file checks and one touch a test; no verdict file is read"
                    :growth :linear
                    :per-n {:bytes 5600 :calls 6}
                    :fixed {:calls 10 :bytes 6000}
                    :sizes [10 100 1000]})
       (finally (clean-costs!))))

;; See docs/merging.md#verdicts-are-a-cache
(deftest a-verdict-keeps-what-its-test-spent-per-function
  (with-dir
   (fn [dir]
     (rec! dir {'a/t1 {:test 'a/t1 :digest "d1" :pass 1 :fail 0 :error 0 :fns (into [(fc 'a/f 10 1000 900 64)] (for [i (range 40)] (fc (symbol "a" (str "g" i)) 1 50 50 0)))}
                'a/t2 {:test 'a/t2 :digest "d2" :pass 1 :fail 0 :error 0 :fns [(fc 'a/f 2 300 100 8)]}})
     (let [details (fx/details dir '{a/t1 "d1" a/t2 "d2"})]
       (testing "which tests exercise a function, and how much"
         (is (= [['a/t1 10 1000 900] ['a/t2 2 300 100]] (mapv (juxt :test :calls :ticks :self-ticks) (proofs/exercising details 'a/f))))
         (is (= ['a/t2] (mapv :test (proofs/exercising (fx/details dir '{a/t2 "d2"}) 'a/f))) "only the tests at the digests asked"))
       (testing "its cost per call over them"
         (is (= {:calls 12 :ticks 108 :self-ticks 83 :bytes 6 :self-bytes 6} (get (proofs/per-call details) 'a/f))))))))

;; See docs/merging.md#verdicts-are-a-cache
;; See docs/merging.md#verdicts-are-a-cache
(deftest a-test-history-is-read-from-its-newest-verdicts
  (with-dir
   (fn [dir]
     (let [keep-n (cfg/value :seon.config.proofs/recent-verdicts)
           rec-at (fn [dg ms ticks age]
                    (rec! dir {'a/t {:test 'a/t :digest dg :pass 1 :fail 0 :error 0 :ms ms :ticks ticks}})
                    (age! dir 'a/t dg :pass age))]
       ;; older verdicts of a slower test: read, they would move the median
       (doseq [i (range (inc keep-n))] (rec-at (str "old-" i) 9000 9000000 (+ 1000 i)))
       (doseq [i (range keep-n)] (rec-at (str "new-" i) 40 2000000 (inc i)))
       (is (= '{a/t 40} (fx/typical-ms dir '[a/t])) "its typical ms is its newest verdicts'")
       (is (= '{a/t 6000000} (fx/test-budgets dir '[a/t] 100000000 1 3)) "and so is its tick budget")))))

(deftest a-write-past-capacity-leaves-room-for-the-next
  (with-dir
   (fn [dir]
     (doseq [i (range 20)] (rec! dir {'a/t {:test 'a/t :digest (str "r" i) :pass 1 :fail 0 :error 0}}))
     (let [before (bytes-in dir)
           _ (fx/write-verdicts! dir {:id "w"} {'a/t {:test 'a/t :digest "over" :pass 1 :fail 0 :error 0}} {} before)
           after (bytes-in dir)
           ledger (parse-long (clojure.string/trim (slurp (str dir "/.size"))))
           low (- before (quot (* before (cfg/value :seon.config.proofs/results-evict-percent)) 100))]
       (is (<= after low) "the cache ends the eviction's percent under its bound")
       (is (= after ledger) "and the ledger holds the true size, so the next write counts nothing")))))

(deftest a-tests-typical-ms-is-the-median-of-its-digests
  (with-dir
   (fn [dir]
     (rec! dir {'a/t {:test 'a/t :digest "ms-1" :pass 1 :fail 0 :error 0 :ms 40}})
     (rec! dir {'a/t {:test 'a/t :digest "ms-2" :pass 1 :fail 0 :error 0 :ms 900}
                'a/u {:test 'a/u :digest "ms-3" :pass 1 :fail 0 :error 0 :ms 7}})
     (rec! dir {'a/t {:test 'a/t :digest "ms-4" :pass 1 :fail 0 :error 0 :ms 50}})
     (is (= '{a/t 50 a/u 7} (fx/typical-ms dir '[a/t a/u a/none])) "by test, the median not the largest; a test never run is left out"))))

;; See docs/merging.md#a-tests-tick-budget
(deftest a-tests-tick-budget-is-its-median-green-ticks-between-the-floor-and-the-cap
  (with-dir
   (fn [dir]
     (doseq [[i tk] (map-indexed vector [10 20 30])]
       (rec! dir {'a/t {:test 'a/t :digest (str "b-" i) :pass 1 :fail 0 :error 0 :ticks tk}}))
     (rec! dir {'a/r {:test 'a/r :digest "r-1" :pass 0 :fail 1 :error 0 :ticks 1000000}})
     (rec! dir {'a/big {:test 'a/big :digest "g-1" :pass 1 :fail 0 :error 0 :ticks 1000}})
     (is (= '{a/t 200 a/r 100 a/none 100 a/big 10000} (fx/test-budgets dir '[a/t a/r a/none a/big] 100000 100 10)) "factor x the median green, never under the floor; a red's ticks count for nothing; none: the floor")
     (is (= '{a/t 50 a/big 50} (fx/test-budgets dir '[a/t a/big] 50 100 10)) "and never over the cap")
     (is (nil? (fx/test-budgets dir '[a/t] 0 100 10)) "a cap of 0: no budget"))))

(def ^:private red-cost
  "A red cost proof's report, as seon.cost/proof's :actual carries it."
  {:rows [{:n 10 :measured {:ticks 900.0 :bytes 100.0} :expected {:ticks 100.0 :bytes 100.0}}]
   :verdict {:ok? false :over [{:dim :ticks :n 10 :ratio 9.0}] :steeper [] :under [] :unmeasured []}})

;; See docs/merging.md#verdicts-are-a-cache
(deftest a-reused-red-cost-proof-keeps-its-verdict
  ;; root via one-wait, 2026-10-27: a reused red cost proof lost its {:rows :verdict}, so the problems
  ;; cost class stayed empty and bin/check problems waited forever. The verdict is kept with the red
  ;; and comes back as its fresh run reported it
  (with-dir
   (fn [dir]
     (let [answer {:digests '{a.c-test/cost "dc"}
                   :reports [{:type :fail :var "#'a.c-test/cost" :message "over" :cost red-cost}]}
           {:keys [ran failures]} (proofs/answer-ran answer)
           fresh (proofs/costs {:reports (:reports answer)})]
       (fx/write-verdicts! dir {:id "r"} ran failures)
       (let [r (get (fx/stored-results dir '{a.c-test/cost "dc"}) "dc")]
         (is (= (get fresh 'a.c-test/cost) (:cost r)) (pr-str r))
         (is (= fresh (proofs/costs {:reports [(assoc (first (:failures r)) :var "#'a.c-test/cost" :cost (:cost r))]}))
             "the reused report (seon.agent/run-tests-forked's) answers proofs/costs as the fresh one did"))))))

(defn- run-of
  "A test run's answer of n green tests and reds red ones, each red with three failing assertions."
  [n reds]
  {:pass n :fail reds :error 0 :ran (+ n reds) :key "b@d"
   :reports (vec (concat (for [i (range n)] {:type :pass :var (str "#'a.g-test/t" i)})
                         (for [i (range reds) j (range 3)] {:type :fail :var (str "#'a.r-test/t" i) :message (str "boom " j) :expected "1" :actual "2"})))})

;; See docs/merging.md#verdicts-are-a-cache
(deftest a-tests-answer-costs-its-reds-not-its-tests
  ;; root, 2026-10-28: an agent's :tests answer of about 3 MB in one frame (every test's reports)
  (let [size #(count (pr-str (proofs/answer % '#{a.g-test/t0})))]
    (testing "green tests past the proofs cost nothing: n and 2n answer the same bytes but for the counts' digits"
      (is (< (Math/abs (- (size (run-of 100 0)) (size (run-of 200 0)))) 5)))
    (testing "the done rule reads it as it reads the whole run: the proof's pass and every red's failures"
      (let [a (proofs/answer (run-of 50 3) '#{a.g-test/t0})]
        (is (= '{a.g-test/t0 :pass a.r-test/t0 :fail a.r-test/t1 :fail a.r-test/t2 :fail} (proofs/results a)))
        (is (= 3 (count (get (proofs/failures a) 'a.r-test/t1))))))
    (testing "every red is carried whole, however many (no bound)"
      (let [a (proofs/answer (run-of 10 50) #{})]
        (is (= 50 (count (proofs/results a))))
        (is (every? #(= 3 (count (get (proofs/failures a) (symbol "a.r-test" (str "t" %))))) (range 50)))
        (is (not (contains? a :reds-left)))))))

;; See docs/merging.md#verdicts-are-a-cache
(deftest a-tests-answer-keeps-an-error-that-names-no-test
  ;; scale, 2026-10-30: {:pass 1 :error 1 :reports []}: the answer kept the counts and dropped the
  ;; run's own error (it names no test, so no test was red), and no one could read why
  (let [e {:type :error :seon.error/type :seon.test/unreadable-line :message "a line the test process sent does not read"}
        a (proofs/answer {:pass 1 :fail 0 :error 1 :reports [{:type :pass :var "#'a.g-test/t0"} e]} #{})]
    (is (= [e] (:reports a)))
    (is (= [(:message e)] (proofs/test-run-errors a)))))
