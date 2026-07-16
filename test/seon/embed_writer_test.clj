(ns seon.embed-writer-test
  "Token-reporting contract for the optional JVM embedding writer."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datahike.api :as d]
   [datahike.db :as datahike-db]
   [seon.ai.tokens :as tokens]
   [seon.embed :as embed]
   [taoensso.timbre :as log])
  (:import
   [java.util.concurrent ArrayBlockingQueue CountDownLatch ExecutionException
    ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy TimeUnit]))

(defn- one-batch-text
  [index]
  (str index "|" (apply str (repeat (tokens/estimate-chars 7900) "x"))))

(defn- text-index
  [text]
  (Long/parseLong (subs text 0 (.indexOf ^String text "|"))))

(defn- wait-for
  [pred]
  (loop [remaining 100]
    (cond
      (pred) true
      (zero? remaining) false
      :else (do (Thread/sleep 10)
                (recur (dec remaining))))))

(defn- embedding-db
  [entities]
  (let [one-string {:db/valueType :db.type/string
                    :db/cardinality :db.cardinality/one}
        schema {:seon.fn/sym one-string
                :seon.fn/doc one-string
                :seon.fn/source one-string
                :seon.embed/source-hash one-string}]
    (d/db-with (datahike-db/empty-db schema {:schema-flexibility :write})
               entities)))

(defn- source-hash
  [text]
  (#'embed/sha-256-hex text))

(deftest oversized-input-log-reports-only-canonical-token-estimates
  (let [events    (atom [])
        source    (apply str (repeat (+ (tokens/estimate-chars
                                         embed/max-text-tokens)
                                        8)
                                     "x"))
        result    (atom nil)
        config    (assoc log/default-config
                         :appenders
                         {:capture {:enabled? true
                                    :async?   false
                                    :fn       #(swap! events conj %)}})]
    (log/with-config config
      (reset! result (#'embed/truncate-to-token-cap source)))
    (let [event    (first @events)
          vargs    (vec (:vargs event))
          reported (filterv number? vargs)
          rendered (str/join " " vargs)]
      (is (= embed/max-text-tokens (tokens/estimate @result))
          "the internal substring boundary honors the model token cap")
      (is (= [(tokens/estimate source) (tokens/estimate @result)] reported)
          "the log's before/after values come from the canonical estimator")
      (is (not (re-find #"(?i)\d+\s*(?:chars?|characters?|bytes?|[kmg]b)\b"
                        rendered))
          "the generated log does not expose raw text-size units"))))

(deftest committed-eids-prepare-only-current-full-document-mismatches
  (let [current-text "alpha/f\nold doc\n(source)"
        current-hash (source-hash current-text)
        db (embedding-db
            [{:db/id 100
              :seon.fn/sym "alpha/f"
              :seon.fn/doc "new doc"
              :seon.fn/source "(source)"}
             {:db/id 101
              :seon.fn/sym "alpha/f"
              :seon.fn/doc "old doc"
              :seon.fn/source "(source)"
              :seon.embed/source-hash current-hash}])
        inputs
        (:seon.embed/inputs
         (embed/embedding-inputs-for-eids
          {:seon.embed/embeddables (embed/default-embeddables)
           :seon.embed/db-value db
           :seon.embed/eids [100 101 100 999]}))]
    (is (= [100] (mapv :seon.embed/id-ref inputs))
        "only a distinct committed eid whose full document changed is prepared")
    (is (= "alpha/f\nnew doc\n(source)"
           (:seon.embed/text (first inputs)))
        "preparation composes from the current full entity, not one trigger value")
    (is (= (source-hash "alpha/f\nnew doc\n(source)")
           (:seon.embed/source-hash (first inputs))))))

(deftest prepared-vector-rows-are-revalidated-against-current-document
  (let [hash-a (source-hash "alpha/f\ndoc a\n(source)")
        assertion {:db/id 100
                   :seon/embedding [1.0]
                   :seon.embed/source-hash hash-a}
        request (fn [db]
                  {:seon.embed/embeddables (embed/default-embeddables)
                   :seon.embed/db-value db
                   :seon.embed/assertions [assertion]})
        current-a (embedding-db
                   [{:db/id 100
                     :seon.fn/sym "alpha/f"
                     :seon.fn/doc "doc a"
                     :seon.fn/source "(source)"}])
        installed-a (d/db-with
                     current-a
                     [{:db/id 100 :seon.embed/source-hash hash-a}])
        changed-b (embedding-db
                   [{:db/id 100
                     :seon.fn/sym "alpha/f"
                     :seon.fn/doc "doc b"
                     :seon.fn/source "(source)"}])
        removed (embedding-db
                 [{:db/id 100
                   :seon.fn/sym "alpha/f"
                   :seon.fn/doc "doc a"
                   :seon.embed/source-hash hash-a}])]
    (is (= [assertion]
           (:seon.embed/assertions
            (embed/revalidate-embedding-assertions (request current-a))))
        "a row matching the latest full composition may commit")
    (is (= []
           (:seon.embed/assertions
            (embed/revalidate-embedding-assertions (request installed-a))))
        "an equivalent row already installed by another completion is a no-op")
    (is (= []
           (:seon.embed/assertions
            (embed/revalidate-embedding-assertions (request changed-b))))
        "a stale vector is discarded when any composed attribute changed")
    (is (= [[:db.fn/retractAttribute 100 :seon/embedding]
            [:db.fn/retractAttribute 100 :seon.embed/source-hash]]
           (:seon.embed/assertions
            (embed/revalidate-embedding-assertions (request removed))))
        "removing the trigger cleans up both derived embedding attributes")))

(deftest embedding-executor-is-process-wide-and-bounded
  (#'embed/reset-embedding-executor!)
  (try
    (let [active  (atom 0)
          peak    (atom 0)
          started (CountDownLatch. 3)
          texts   (mapv one-batch-text (range 12))]
      (with-redefs-fn
        {#'embed/gemini-client (constantly ::client)
         #'embed/embed-batch!
         (fn [_ batch]
           (let [running (swap! active inc)]
             (swap! peak max running)
             (try
               (Thread/sleep 15)
               (mapv (fn [text] [(float (text-index text))]) batch)
               (finally
                 (swap! active dec)))))}
        (fn []
          (let [calls (mapv (fn [_]
                              (future
                                (.countDown started)
                                (.await started 1 TimeUnit/SECONDS)
                                (embed/embed-texts {:seon.embed/texts texts})))
                            (range 3))
                expected (mapv (fn [index] [(float index)]) (range 12))]
            (doseq [call calls]
              (is (= expected (:seon.embed/vectors @call))
                  "every concurrent caller retains its input order"))
            (let [^ThreadPoolExecutor executor
                  (deref (var-get #'embed/!embedding-executor))]
              (testing "one process-wide executor has explicit resource bounds"
                (is (= embed/max-embed-concurrency (.getMaximumPoolSize executor)))
                (is (instance? ArrayBlockingQueue (.getQueue executor)))
                (is (= 64 (+ (.size (.getQueue executor))
                             (.remainingCapacity (.getQueue executor)))))
                (is (instance? ThreadPoolExecutor$AbortPolicy
                               (.getRejectedExecutionHandler executor))))
              (is (<= @peak embed/max-embed-concurrency)
                  "simultaneous callers share the same concurrency ceiling")
              (is (> @peak 1) "independent batches still execute in parallel"))))))
    (finally
      (#'embed/reset-embedding-executor!))))

(deftest failed-batch-cancels-running-siblings
  (#'embed/reset-embedding-executor!)
  (try
    (let [workers-entered (CountDownLatch. embed/max-embed-concurrency)
          interrupted     (atom 0)
          texts           (mapv one-batch-text (range 12))]
      (with-redefs-fn
        {#'embed/gemini-client (constantly ::client)
         #'embed/embed-batch!
         (fn [_ [text]]
           (.countDown workers-entered)
           (if (zero? (text-index text))
             (do
               (.await workers-entered 1 TimeUnit/SECONDS)
               (throw (ex-info "expected batch failure" {})))
             (try
               (Thread/sleep 10000)
               [[(float (text-index text))]]
               (catch InterruptedException interrupted-ex
                 (swap! interrupted inc)
                 (.interrupt (Thread/currentThread))
                 (throw interrupted-ex)))))}
        (fn []
          (is (thrown? ExecutionException
                       (embed/embed-texts {:seon.embed/texts texts})))
          (is (wait-for #(pos? @interrupted))
              "a failed batch interrupts already-running siblings")
          (let [^ThreadPoolExecutor executor
                (deref (var-get #'embed/!embedding-executor))]
            (is (wait-for #(.isEmpty (.getQueue executor)))
                "cancelled queued siblings are purged")))))
    (finally
      (#'embed/reset-embedding-executor!))))

(deftest one-large-call-does-not-reject-itself-at-the-shared-queue-bound
  (#'embed/reset-embedding-executor!)
  (try
    (let [texts (mapv one-batch-text (range 160))
          expected (mapv (fn [index] [(float index)]) (range 160))]
      (with-redefs-fn
        {#'embed/gemini-client (constantly ::client)
         #'embed/embed-batch!
         (fn [_ batch]
           (mapv (fn [text] [(float (text-index text))]) batch))}
        (fn []
          (is (= expected
                 (:seon.embed/vectors
                  (embed/embed-texts {:seon.embed/texts texts})))
              "large calls submit bounded windows instead of filling their own queue"))))
    (finally
      (#'embed/reset-embedding-executor!))))
