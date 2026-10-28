(ns seon.results-test
  "Verdicts are a cache by digest outside the store (docs/merging.md#verdicts-are-a-cache): one file a
  key, digest and status, flaky kept across writers, a red never evicted, the cost a test spent per
  function kept with its verdict, and nothing written to the store; each test on a cache directory of
  its own under target/tmp."
  (:require [clojure.test :refer [deftest is testing]] [clojure.java.shell :as sh] [seon.config :as cfg]
            [seon.fx :as fx] [seon.proofs :as proofs]))

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
     (rec! dir {'a/t {:test 'a/t :digest "dc" :pass 1 :fail 0 :error 0}})
     (spit (str dir "/a/t/dc.pass.edn") "{:status :pass")
     (let [r (fx/stored-results dir '{a/t "dc"})]
       (is (= :seon.fx/io-error (:seon.error/type r)) (pr-str r))))))

;; See docs/merging.md#verdicts-are-a-cache
(deftest a-test-holds-at-most-its-bound-of-digests-and-a-red-is-never-evicted
  (with-dir
   (fn [dir]
     (let [bound (cfg/value :seon.config.proofs/results-per-test)
           ;; "d000" sorts lowest, so it is the first evicted were it green: a red never is
           _ (rec! dir {'a/t {:test 'a/t :digest "d000" :pass 0 :fail 1 :error 0}} {'a/t [{:type :fail :message "red"}]})
           _ (doseq [i (range 1 (+ bound 3))]
               (rec! dir {'a/t {:test 'a/t :digest (format "d%03d" i) :pass 1 :fail 0 :error 0}}))
           kept (fx/stored-results dir (into {} (for [i (range 0 (+ bound 3))] [(symbol "a" (str "t" i)) (format "d%03d" i)])))
           held (->> (.listFiles (java.io.File. (str dir "/a/t"))) (map #(re-find #"^d\d+" (.getName %))) distinct sort)]
       (is (= bound (count held)) (pr-str held))
       (is (some #{"d000"} held) "the red stays")
       (is (some #{(format "d%03d" (+ bound 2))} held) "the digest just written stays")
       (is (map? kept))))))

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
(deftest a-tests-last-ms-is-the-most-any-digest-measured
  (with-dir
   (fn [dir]
     (rec! dir {'a/t {:test 'a/t :digest "ms-1" :pass 1 :fail 0 :error 0 :ms 40}})
     (rec! dir {'a/t {:test 'a/t :digest "ms-2" :pass 1 :fail 0 :error 0 :ms 900}
                'a/u {:test 'a/u :digest "ms-3" :pass 1 :fail 0 :error 0 :ms 7}})
     (is (= '{a/t 900 a/u 7} (fx/last-ms dir '[a/t a/u a/none])) "by test; a test never run is left out"))))

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
