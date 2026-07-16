(ns seon.embed-writer-test
  "Token-reporting contract for the optional JVM embedding writer."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datahike.api :as d]
   [datahike.db :as datahike-db]
   [seon.ai.tokens :as tokens]
   [seon.embed :as embed]
   [taoensso.timbre :as log]))

(defn- one-batch-text
  [index]
  (str index "|" (apply str (repeat (tokens/estimate-chars 7900) "x"))))

(defn- text-index
  [text]
  (Long/parseLong (subs text 0 (.indexOf ^String text "|"))))

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

(deftest bulk-embedding-has-no-hidden-executor
  (let [texts (mapv one-batch-text (range 12))
        calls (atom [])
        threads (atom #{})
        expected (mapv (fn [index] [(float index)]) (range 12))]
    (with-redefs-fn
      {#'embed/gemini-client (constantly ::client)
       #'embed/embed-batch!
       (fn [_ batch]
         (swap! calls conj (mapv text-index batch))
         (swap! threads conj (Thread/currentThread))
         (mapv (fn [text] [(float (text-index text))]) batch))}
      (fn []
        (is (= expected
               (:seon.embed/vectors
                (embed/embed-texts {:seon.embed/texts texts}))))
        (is (= 6 (count @calls)))
        (is (= (range 12) (mapcat identity @calls)))
        (is (= 1 (count @threads)))))))

(deftest failed-batch-does-not-start-later-batches
  (let [calls (atom [])
        texts (mapv one-batch-text (range 3))]
    (with-redefs-fn
      {#'embed/gemini-client (constantly ::client)
       #'embed/embed-batch!
       (fn [_ [text]]
         (swap! calls conj (text-index text))
         (throw (ex-info "expected batch failure" {})))}
      (fn []
        (is (thrown? clojure.lang.ExceptionInfo
                     (embed/embed-texts {:seon.embed/texts texts})))
        (is (= [0] @calls))))))
