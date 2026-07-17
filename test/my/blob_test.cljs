(ns my.blob-test
  "Behavioral tests for the content-addressed blob archive."
  (:require
   ["node:crypto" :as crypto]
   ["node:fs" :as nfs]
   ["node:path" :as npath]
   [cljs.test :refer [async deftest is use-fixtures]]
   [clojure.string :as str]
   [my.blob :as blob]
   [seon.ai.tokens :as tokens]
   [seon.db :as db]
   [seon.schema :as schema]))

(def ^:private fixture-dir
  (.resolve npath (str "tmp/blob-test-" (.-pid js/process))))

(def ^:private absent-hash (apply str (repeat 64 "0")))

(def ^:private database
  {:db-name "blob-test"
   :t 536870913
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "10000000-0000-4000-8000-000000000001"})

(def ^:private target
  {:seon.db.coordinate/database-id
   #uuid "20000000-0000-4000-8000-000000000001"
   :seon.db.coordinate/branch :db
   :seon.db.coordinate/commit-id (:datahike/commit-id database)
   :seon.db.coordinate/t (:t database)})

(defonce ^:private !saved-storage-view (atom nil))
(defonce ^:private !projections (atom {}))

(def ^:private put-with-publication-effects!
  @#'blob/put-with-publication-effects!)

(def ^:private node-publication-effects
  @#'blob/node-publication-effects)

(def ^:private materialize-retained-with-effects!
  @#'blob/materialize-retained-with-effects!)

(def ^:private concat-with-effects!
  @#'blob/concat-with-effects!)

(def ^:private stat-with-effects!
  @#'blob/stat-with-effects!)

(def ^:private text-with-effects!
  @#'blob/text-with-effects!)

(defn- storage-view [writable-dir & read-only-dirs]
  {:my.blob/writable-dir writable-dir
   :my.blob/read-only-dirs (vec read-only-dirs)})

(use-fixtures
 :once
 {:before #(reset! !saved-storage-view @blob/!storage-view)
  :after #(reset! blob/!storage-view @!saved-storage-view)})

(use-fixtures
 :each
 {:before
  (fn []
    (.rmSync nfs fixture-dir #js {:recursive true :force true})
    (reset! blob/!storage-view (storage-view fixture-dir))
    (reset! !projections {}))
  :after
  (fn []
    (.rmSync nfs fixture-dir #js {:recursive true :force true}))})

(defn- content-hash [content]
  (-> (.createHash crypto "sha256")
      (.update content "utf8")
      (.digest "hex")))

(defn- retained-set-digest [hashes]
  (content-hash (pr-str (vec (sort (distinct hashes))))))

(defn- fake-transact! [{:seon.db/keys [tx-data]}]
  (doseq [{:my.blob/keys [hash tokens media at]} tx-data]
    (swap! !projections assoc hash
           {:my.blob/tokens tokens :my.blob/media media :my.blob/at at}))
  (js/Promise.resolve
   {:db-before database
    :db-after database
    :tx-data []
    :tempids {}
    :tx-meta {}}))

(defn- fake-query [{:seon.db/keys [args]}]
  (let [{:my.blob/keys [tokens media at]} (get @!projections (first args))]
    (js/Promise.resolve (when tokens [tokens media at]))))

(defn- test-publication-effects []
  (assoc node-publication-effects :my.blob/transact! fake-transact!))

(defn- test-database-effects []
  {:my.blob/current-db! (fn [] (js/Promise.resolve database))
   :my.blob/query! fake-query})

(defn- put! [request]
  (put-with-publication-effects! request (test-publication-effects)))

(defn- stat! [hash]
  (stat-with-effects!
   {:my.blob/hash hash :seon.db/db database}
   (test-database-effects)))

(defn- text!
  ([hash] (text! hash {}))
  ([hash options]
   (text-with-effects!
    (merge {:my.blob/hash hash :seon.db/db database} options)
    (test-database-effects))))

(defn- concat! [hashes]
  (concat-with-effects!
   {:my.blob/hashes hashes}
   (test-publication-effects)))

(defn- finish! [promise done]
  (-> promise
      (.then (fn [_] (done)))
      (.catch (fn [error]
                (is false (str "threw: " error))
                (done)))))

(defn- write-content! [dir content]
  (let [hash (content-hash content)
        shard (.join npath dir (subs hash 0 2))
        path (.join npath shard hash)]
    (.mkdirSync nfs shard #js {:recursive true})
    (.writeFileSync nfs path content "utf8")
    {:my.blob/hash hash :my.blob/path path}))

(defn- materialization-dirs [label]
  (let [root (.join npath fixture-dir label)
        overlay (.join npath root "overlay")
        main (.join npath root "main")]
    {:overlay overlay :main main}))

(defn- materialization-request [overlay main hashes]
  {:my.blob/target-coordinate target
   :my.blob/retained-hashes hashes
   :my.blob/source-storage-view (storage-view overlay main)
   :my.blob/destination-storage-view (storage-view main)
   :my.blob/reachable-hash-digest (retained-set-digest hashes)})

(deftest put-stat-text-and-get-roundtrip
  (async done
    (let [content "# Report\n\nline three\nline four\n"]
      (finish!
       (-> (put! {:my.blob/content content :my.blob/media :markdown})
           (.then
            (fn [{:my.blob/keys [ok? hash tokens]}]
              (is ok?)
              (is (= (content-hash content) hash))
              (is (= (tokens/estimate content) tokens))
              (is (.existsSync nfs (.join npath fixture-dir
                                          (subs hash 0 2) hash)))
              (-> (stat! hash)
                  (.then
                   (fn [stat]
                     (is (:my.blob/exists? stat))
                     (is (= :markdown (:my.blob/media stat)))
                     (is (= content
                            (:my.blob/content
                             (blob/get {:my.blob/hash hash}))))
                     (text! hash)))
                  (.then
                   (fn [page]
                     (is (= 4 (:my.blob/total-lines page)))
                     (is (str/includes? (:my.blob/content page)
                                       "line three"))))))))
       done))))

(deftest identical-content-is-idempotent
  (async done
    (let [content "same bytes"]
      (finish!
       (-> (put! {:my.blob/content content})
           (.then (fn [first]
                    (-> (put! {:my.blob/content content})
                        (.then
                         (fn [second]
                           (is (= (:my.blob/hash first)
                                  (:my.blob/hash second)))
                           (is (= 1 (count @!projections)))))))))
       done))))

(deftest failed-rename-does-not-project-and-retry-converges
  (async done
    (let [content "retryable publication"
          hash (content-hash content)
          failing-effects
          (assoc node-publication-effects
                 :my.blob/atomic-rename!
                 (fn [& _] (throw (js/Error. "injected rename failure"))))]
      (finish!
       (-> (js/Promise.resolve
            (put-with-publication-effects!
             {:my.blob/content content} failing-effects))
           (.then
            (fn [failed]
              (is (false? (:my.blob/ok? failed)))
              (is (empty? @!projections))
              (is (not (.existsSync nfs (.join npath fixture-dir
                                               (subs hash 0 2) hash))))
              (put! {:my.blob/content content})))
           (.then
            (fn [retried]
              (is (:my.blob/ok? retried))
              (is (= hash (:my.blob/hash retried))))))
       done))))

(deftest text-pages-with-honest-totals
  (async done
    (let [content (str/join "\n" (map #(str "line " %) (range 1 151)))]
      (finish!
       (-> (put! {:my.blob/content content})
           (.then
            (fn [{:my.blob/keys [hash]}]
              (text! hash {:my.blob/from-line 96 :my.blob/max-lines 10})))
           (.then
            (fn [page]
              (is (= 150 (:my.blob/total-lines page)))
              (is (= 10 (:my.blob/lines-returned page)))
              (is (str/starts-with? (:my.blob/content page) "line 96")))))
       done))))

(deftest text-refuses-recorded-binary-content
  (async done
    (let [content (js/String.fromCharCode 0 1 2 3)]
      (finish!
       (-> (put! {:my.blob/content content :my.blob/media :png})
           (.then (fn [{:my.blob/keys [hash]}] (text! hash)))
           (.then
            (fn [result]
              (is (false? (:my.blob/ok? result)))
              (is (= :png (get-in result [:seon.error/data :my.blob/media]))))))
       done))))

(deftest concat-produces-one-canonical-blob
  (async done
    (finish!
     (-> (js/Promise.all
          #js [(put! {:my.blob/content "alpha\n"})
               (put! {:my.blob/content "beta\n"})])
         (.then
          (fn [parts]
            (concat! (mapv :my.blob/hash (array-seq parts)))))
         (.then
          (fn [{:my.blob/keys [hash]}]
            (is (= (content-hash "alpha\nbeta\n") hash))
            (is (= "alpha\nbeta\n"
                   (:my.blob/content (blob/get {:my.blob/hash hash})))))))
     done)))

(deftest missing-and-malformed-hashes-are-values
  (async done
    (let [malformed "../../../etc/passwd"]
      (is (false? (:my.blob/ok? (blob/get {:my.blob/hash malformed}))))
      (is (false? (:my.blob/ok? (blob/get {:my.blob/hash absent-hash}))))
      (finish!
       (-> (stat! absent-hash)
           (.then
            (fn [result]
              (is (:my.blob/ok? result))
              (is (false? (:my.blob/exists? result))))))
       done))))

(deftest retained-observation-is-canonical-and-bounded
  (let [a (content-hash "a")
        b (content-hash "b")
        result
        (blob/observe-retained
         {:my.blob/target-coordinate target
          :my.blob/retained-hashes [b a b]})]
    (is (:my.blob/ok? result))
    (is (= 2 (:my.blob/hash-count result)))
    (is (= (retained-set-digest [a b])
           (:my.blob/reachable-hash-digest result)))
    (is (schema/valid-candidate-value?
         :my.blob/retained-observation-result result))))

(deftest malformed-retained-hash-is-refused
  (let [result
        (blob/observe-retained
         {:my.blob/target-coordinate target
          :my.blob/retained-hashes ["not-a-hash"]})]
    (is (false? (:my.blob/ok? result)))
    (is (str/includes? (:my.blob/error result) "malformed"))))

(deftest empty-retained-set-materializes-without-files
  (let [{:keys [overlay main]} (materialization-dirs "empty")
        result
        (blob/materialize-retained!
         (materialization-request overlay main []))]
    (is (:my.blob/ok? result))
    (is (zero? (:my.blob/hash-count result)))
    (is (zero? (:my.blob/newly-materialized-count result)))
    (is (schema/valid-candidate-value? :my.blob/materialization-result result))))

(deftest only-retained-overlay-content-is-materialized
  (let [{:keys [overlay main]} (materialization-dirs "retained")
        retained (write-content! overlay "retained")
        orphan (write-content! overlay "orphan")
        request (materialization-request overlay main [(:my.blob/hash retained)])
        first-result (blob/materialize-retained! request)
        retry-result (blob/materialize-retained! request)]
    (is (:my.blob/ok? first-result))
    (is (= 1 (:my.blob/newly-materialized-count first-result)))
    (is (zero? (:my.blob/newly-materialized-count retry-result)))
    (is (.existsSync nfs (.join npath main
                                (subs (:my.blob/hash retained) 0 2)
                                (:my.blob/hash retained))))
    (is (not (.existsSync nfs (.join npath main
                                     (subs (:my.blob/hash orphan) 0 2)
                                     (:my.blob/hash orphan)))))))

(deftest missing-retained-source-reports-every-searched-path
  (let [{:keys [overlay main]} (materialization-dirs "missing")
        result
        (blob/materialize-retained!
         (materialization-request overlay main [absent-hash]))]
    (is (false? (:my.blob/ok? result)))
    (is (= :my.blob.materialization.operation/verify-source
           (:my.blob/materialization-operation result)))
    (is (= 2 (count (:my.blob/searched-source-paths result))))))

(deftest corrupt-main-is-repaired-from-overlay
  (let [{:keys [overlay main]} (materialization-dirs "repair")
        source (write-content! overlay "correct bytes")
        hash (:my.blob/hash source)
        main-path (.join npath main (subs hash 0 2) hash)]
    (.mkdirSync nfs (.dirname npath main-path) #js {:recursive true})
    (.writeFileSync nfs main-path "corrupt" "utf8")
    (let [result
          (blob/materialize-retained!
           (materialization-request overlay main [hash]))]
      (is (:my.blob/ok? result))
      (is (= 1 (:my.blob/repaired-count result)))
      (is (= "correct bytes" (.readFileSync nfs main-path "utf8"))))))

(deftest frozen-digest-mismatch-prevents-publication
  (let [{:keys [overlay main]} (materialization-dirs "digest")
        source (write-content! overlay "retained")
        request
        (assoc (materialization-request overlay main [(:my.blob/hash source)])
               :my.blob/reachable-hash-digest
               (retained-set-digest [absent-hash]))
        result (blob/materialize-retained! request)]
    (is (false? (:my.blob/ok? result)))
    (is (= :my.blob.materialization.operation/derive-retained-set
           (:my.blob/materialization-operation result)))
    (is (not (.existsSync nfs main)))))

(deftest publication-failure-remains-retryable
  (let [{:keys [overlay main]} (materialization-dirs "retry")
        source (write-content! overlay "retry bytes")
        request (materialization-request overlay main [(:my.blob/hash source)])
        failed
        (materialize-retained-with-effects!
         request
         (assoc node-publication-effects
                :my.blob/atomic-rename!
                (fn [& _] (throw (js/Error. "injected failure")))))
        retried (blob/materialize-retained! request)]
    (is (false? (:my.blob/ok? failed)))
    (is (:my.blob/ok? retried))
    (is (= 1 (:my.blob/newly-materialized-count retried)))))
