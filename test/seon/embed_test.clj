(ns seon.embed-test
  "Embeddings keyed by content: each text reaches the embed server once, across caches and restarts; the nearest
  texts by them."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [konserve.core :as k]
            [konserve.filestore :as filestore]
            [seon.cache]
            [seon.db :as db]
            [seon.embed :as embed]
            [seon.id :as id]
            [seon.test-support :as test-support])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files]
           [java.util.concurrent CountDownLatch TimeUnit]
           [java.nio.file.attribute FileAttribute]))

(defn- settings
  "Dials for a test: a model name of its own, so its content keys are new to the process cache."
  {:malli/schema [:=> [:cat] :seon.embed/settings]}
  []
  #:seon.config.embed{:endpoint "http://127.0.0.1:8701/v1/embed" :model (str "test-model-" (id/id)) :dimension 4
                      :request-bytes 40 :timeout-ms 2000 :wait-ms 2000})

(defn- unit
  "The little-endian float32 octets of `xs` scaled to unit length."
  {:malli/schema [:=> [:cat [:vector number?]] :seon.embed/octets]}
  [xs]
  (let [norm (Math/sqrt (reduce + (map #(* % %) xs)))
        buffer (.order (ByteBuffer/allocate (* 4 (count xs))) ByteOrder/LITTLE_ENDIAN)]
    (run! #(.putFloat buffer (float (/ % norm))) xs)
    (.array buffer)))

(defn- temp-dir
  "A new empty directory for one test's durable store."
  {:malli/schema [:=> [:cat] :string]}
  []
  (str (Files/createTempDirectory "seon-embed-" (make-array FileAttribute 0))))

(def ^:private axes
  "Each test text's embedding direction: what a counting request answers for it."
  {"seon.db/transact!" [1 0 0 0] "seon.db/transcat!" [0.9 0.1 0 0] "seon.db/pull" [0 1 0 0]
   "seon.cache/lookup" [0 0 1 0] "seon.db/q" [0.1 0.9 0 0]})

(defn- restart!
  "What a process restart leaves of the embeddings: nothing held in the process cache."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (seon.cache/evict-kinds! #{:seon.embed/embedding :seon.embed/present :seon.embed/store})
  nil)

(deftest unchanged-text-costs-no-request-and-no-write-after-restart
  ;; The request is the external effect, passed as a value that counts its texts at the edge.
  (let [dir (temp-dir)
        dials (settings)
        requested (atom [])
        request (fn [_settings _task texts] (swap! requested into texts) (mapv (comp unit axes) texts))
        texts ["seon.db/transact!" "seon.db/pull" "seon.db/transact!" "seon.cache/lookup"]]
    (try
      (let [first-run (embed/embed! dials (embed/open-store dir) request "similarity" texts)
            warm (embed/embed! dials (embed/open-store dir) request "similarity" texts)
            segments (k/get (embed/open-store dir) :seon.embed/segments nil {:sync? true})
            _ (restart!)
            restarted (embed/embed! dials (embed/open-store dir) request "similarity" texts)]
        (testing "the first run requests each distinct text once"
          (is (= {"seon.db/transact!" 1 "seon.db/pull" 1 "seon.cache/lookup" 1} (frequencies @requested)))
          (is (= 3 (:seon.embed/requested first-run))))
        (testing "a warm process reads neither the store nor the server"
          (is (= {:seon.embed/hot 3 :seon.embed/durable 0 :seon.embed/requested 0} (dissoc warm :seon.embed/vectors))))
        (testing "a restart makes zero embed calls and zero writes for unchanged text"
          (is (= {:seon.embed/hot 0 :seon.embed/durable 3 :seon.embed/requested 0} (dissoc restarted :seon.embed/vectors)))
          (is (= 3 (count @requested)))
          (is (= segments (k/get (embed/open-store dir) :seon.embed/segments nil {:sync? true})))
          (is (= (mapv vec (:seon.embed/vectors first-run)) (mapv vec (:seon.embed/vectors restarted)))))
        (testing "another task is another content key"
          (is (= 3 (:seon.embed/requested (embed/embed! dials (embed/open-store dir) request "code" texts))))))
      (finally
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))

(deftest a-fill-pass-sends-its-backlog-in-byte-budget-requests
  (let [dir (temp-dir)
        batches (atom [])
        request (fn [_settings _task texts] (swap! batches conj texts) (mapv (fn [_] (unit [1 0 0 0])) texts))
        ;; ten 10-byte texts and one 50-byte text, under a 30-byte budget
        texts (conj (mapv #(format "text-%05d" %) (range 10)) (apply str (repeat 50 "x")))]
    (try
      (embed/embed! (assoc (settings) :seon.config.embed/request-bytes 30) (embed/open-store dir) request "similarity" texts)
      (is (= [3 3 3 1 1] (mapv count @batches)) "shortest first, cut at the budget, a longer text alone")
      (is (= #{(apply str (repeat 50 "x"))} (set (peek @batches))))
      (finally
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))

(deftest a-fill-pass-writes-one-segment
  (let [dir (temp-dir)
        dials (settings)
        request (fn [_settings _task texts] (mapv (comp unit axes) texts))
        segments #(k/get (embed/open-store dir) :seon.embed/segments [] {:sync? true})]
    (try
      (embed/embed! dials (embed/open-store dir) request "similarity" (vec (keys axes)))
      (is (= 1 (count (segments))) "five texts in three requests are one segment")
      (embed/embed! dials (embed/open-store dir) request "similarity" ["seon.db/pull" "seon.db/q"])
      (is (= 1 (count (segments))) "a pass with nothing to request writes nothing")
      (finally
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))

(deftest a-store-of-one-value-per-embedding-converts-to-one-segment-as-it-opens
  (let [dir (temp-dir)
        dials (settings)
        texts ["seon.db/pull" "seon.db/q"]
        request (fn [_settings _task _texts] (throw (ex-info "no request expected" {})))]
    (try
      (let [legacy (filestore/connect-fs-store dir :opts {:sync? true})]
        (run! #(k/assoc legacy (embed/embedding-key dials "similarity" %) ((comp unit axes) %) {:sync? true}) texts))
      (let [store (embed/open-store dir)
            converted (embed/embed! dials store request "similarity" texts)]
        (is (= {:seon.embed/hot 0 :seon.embed/durable 2 :seon.embed/requested 0} (dissoc converted :seon.embed/vectors)))
        (is (= (mapv (comp vec unit axes) texts) (mapv vec (:seon.embed/vectors converted))))
        (is (= [:seon.embed/segments [:seon.embed/segment (first (k/get store :seon.embed/segments nil {:sync? true}))]]
               (sort-by str (map :key (k/keys store {:sync? true}))))
            "the per-embedding values are gone, one segment and its manifest remain"))
      (finally
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))

(deftest a-conversion-cut-after-its-segment-resumes-without-packing-again
  ;; The cut of 2026-10-19: a conversion interrupted while deleting left its segment and 10,986 per-key values,
  ;; and the next open packed those again into a second segment.
  (let [dir (temp-dir)
        dials (settings)
        texts ["seon.db/pull" "seon.db/q"]
        request (fn [_settings _task _texts] (throw (ex-info "no request expected" {})))]
    (try
      (let [legacy (filestore/connect-fs-store dir :opts {:sync? true})
            found (into {} (map (fn [text] [(embed/embedding-key dials "similarity" text) ((comp unit axes) text)])) texts)]
        (run! (fn [[content-key octets]] (k/assoc legacy content-key octets {:sync? true})) found)
        (embed/write-segment! legacy found))
      (let [store (embed/open-store dir)
            segments (k/get store :seon.embed/segments nil {:sync? true})]
        (is (= 1 (count segments)) "the segment the cut conversion wrote is the only one")
        (is (= [:seon.embed/segments [:seon.embed/segment (first segments)]]
               (sort-by str (map :key (k/keys store {:sync? true}))))
            "the per-embedding values it left are deleted")
        (is (= {:seon.embed/hot 0 :seon.embed/durable 2 :seon.embed/requested 0}
               (dissoc (embed/embed! dials store request "similarity" texts) :seon.embed/vectors))))
      (finally
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))

(deftest a-call-while-the-store-converts-answers-in-progress-and-never-converts-it
  ;; A call waits for the store at most its wait: the conversion of a per-key store runs on its own thread or the
  ;; background fill's (`seon.embed/open-store`), never in the call.
  (let [dir (temp-dir)
        dials (settings)]
    (try
      (let [legacy (filestore/connect-fs-store dir :opts {:sync? true})]
        (run! #(k/assoc legacy (embed/embedding-key dials "similarity" (str "text-" %)) (unit [1 0 0 0]) {:sync? true})
              (range 64)))
      (is (= :seon.embed/store (:seon.cache/pending-kind (embed/store-within dir 0)))
          "the call answers in progress while the store converts")
      (let [store (embed/open-store dir)]
        (is (= 1 (count (k/get store :seon.embed/segments nil {:sync? true}))) "the fill's open joins that conversion")
        (is (identical? store (embed/store-within dir 0)) "a later call holds the converted store at once"))
      (finally
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))

(deftest a-misspelled-name-is-nearest-the-name-it-misspells
  (let [dir (temp-dir)
        request (fn [_settings _task texts] (mapv (comp unit axes) texts))]
    (try
      (is (= ["seon.db/transact!" "seon.db/q"]
             (mapv :seon.embed/text (embed/similar (settings) (embed/open-store dir) request "similarity"
                                                   (embed/held-texts ["seon.db/pull" "seon.db/transact!" "seon.cache/lookup" "seon.db/q"])
                                                   "similarity" "seon.db/transcat!" 2))))
      (finally
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))

(deftest an-unreachable-embed-server-is-the-declared-error
  (let [dir (temp-dir)]
    (try
      (let [answer (embed/similar (assoc (settings) :seon.config.embed/endpoint "http://127.0.0.1:9/v1/embed")
                                  (embed/open-store dir) embed/request!
                                  "similarity" (embed/held-texts ["seon.db/pull"]) "similarity" "seon.db/pul" 3)]
        (is (= :seon.embed/unreachable-error (:seon.error/type answer)))
        (is (= "http://127.0.0.1:9/v1/embed" (:seon.config.embed/endpoint answer))))
      (finally
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))

(deftest a-call-whose-index-is-still-derived-answers-in-progress-and-never-waits-for-it
  ;; The request is held until the test releases it: the cold embedding a call must not pay in its turn.
  (let [dir (temp-dir)
        release (CountDownLatch. 1)
        request (fn [_settings _task texts] (.await release 5 TimeUnit/SECONDS) (mapv (comp unit axes) texts))
        dials (assoc (settings) :seon.config.embed/wait-ms 10)
        corpus (embed/held-texts ["seon.db/pull" "seon.db/transact!" "seon.cache/lookup" "seon.db/q"])
        ask #(embed/similar-now dials (embed/open-store dir) request "similarity" corpus "similarity" "seon.db/transcat!" 2)]
    (try
      (is (= {:seon.embed/pending 4} (ask)) "the index still being derived answers embedding in progress")
      (.countDown release)
      ;; the derivation the first call started is the one the background fill waits for
      (embed/index dials (embed/open-store dir) request "similarity" corpus)
      (is (= ["seon.db/transact!" "seon.db/q"] (mapv :seon.embed/text (ask))) "once derived, the call ranks")
      (finally
        (.countDown release)
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))

(deftest a-filled-index-leaves-a-call-only-its-query-to-embed
  (let [dir (temp-dir)
        requested (atom [])
        request (fn [_settings _task texts] (swap! requested into texts) (mapv (comp unit axes) texts))
        dials (settings)
        corpus (embed/held-texts ["seon.db/pull" "seon.db/transact!" "seon.cache/lookup" "seon.db/q"])]
    (try
      (embed/index dials (embed/open-store dir) request "similarity" corpus)
      (reset! requested [])
      (is (= ["seon.db/transact!" "seon.db/q"]
             (mapv :seon.embed/text (embed/similar-now dials (embed/open-store dir) request "similarity" corpus
                                                   "similarity" "seon.db/transcat!" 2))))
      (is (= ["seon.db/transcat!"] @requested) "the call embeds its query and nothing of the corpus")
      (finally
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))

(def ^:private documents
  "Each synthetic document text's embedding direction, and the query nearest the first."
  {"parse Clojure text into forms" [1 0 0 0] "a parser of Clojure source" [0.9 0.1 0 0]
   "store bytes on disk" [0 1 0 0] "a note nobody embeds" [0 0 1 0] "a corpus only queried" [0 0 0 1]})

(defn- direction
  "A text's embedding direction: a synthetic document's own, any other text (the program's corpora on a test
  branch) one shared direction away from them."
  {:malli/schema [:=> [:cat :seon.embed/text] [:vector number?]]}
  [text]
  (get documents text [0 0 1 1]))

(def ^:private document-attributes
  "Synthetic attributes: an identity, one declaring its embedding, one declaring none, one embedded on first query."
  [[:seon.embed-test/id [:string {:seon.db/identity true}]]
   [:seon.embed-test/title [:string {:seon.embed/task "document"}]]
   [:seon.embed-test/note :string]
   [:seon.embed-test/topic [:string {:seon.embed/task "query" :seon.embed/when :query}]]])

(def ^:private document-options
  "A test database installing the synthetic attributes, and `:seon.embed/when`, which a schema row declaring it
  stores as a property of that row: the attribute its first publication installs."
  {:seon.test-support/extra-schema
   (conj (mapv (fn [[ident]] (cond-> {:db/ident ident :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                                 (= :seon.embed-test/id ident) (assoc :db/unique :db.unique/identity)))
               document-attributes)
         {:db/ident :seon.embed/when :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one})})

(defn- declare-documents!
  "Declare the synthetic attributes' schemas on `connection`'s branch."
  {:malli/schema [:=> [:cat :seon.db/connection] :map]}
  [connection]
  (test-support/transacted! connection (test-support/schema-entities (db/db connection) document-attributes)))

(defn- titled!
  "Write an entity titled `title` on `connection`'s branch; its entity id."
  {:malli/schema [:=> [:cat :seon.db/connection :string] :int]}
  [connection title]
  (get-in (test-support/transacted! connection [{:db/id "doc" :seon.embed-test/id (str (random-uuid))
                                                  :seon.embed-test/title title}])
          [:tempids "doc"]))

(deftest an-attribute-declaring-an-embed-task-is-embedded-without-a-hand-list
  (test-support/with-database
   document-options
   (fn [connection]
     (let [dir (temp-dir)
           dials (settings)
           requested (atom [])
           request (fn [_settings _task texts] (swap! requested into texts) (mapv (comp unit direction) texts))
           wake (async/chan (async/sliding-buffer 1))]
       (try
         (declare-documents! connection)
         (let [state (embed/fill-step (embed/fill-step {:seon.turn.loop/cluster {:seon.db/connection connection}
                                                        :seon.embed/channel wake})
                                      ::flow/resume)]
           (async/poll! wake)
           (let [declared (test-support/transacted! connection [{:db/id "doc" :seon.embed-test/id (str (random-uuid))
                                                                  :seon.embed-test/title "parse Clojure text into forms"
                                                                  :seon.embed-test/note "a note nobody embeds"}])
                 entity (get-in declared [:tempids "doc"])
                 ;; the connection's listeners are called after the transaction answers
                 declared-wake (first (async/alts!! [wake (async/timeout 5000)]))
                 undeclared (test-support/transacted! connection [{:db/id entity :seon.embed-test/note "a changed note"}
                                                                  {:seon.embed-test/id (str (random-uuid))
                                                                   :seon.embed-test/note "store bytes on disk"}])
                 _ (embed/fill-step state ::flow/stop)
                 database (db/db connection)
                 filled (embed/fill-with dials (embed/open-store dir) request database)
                 found (embed/entities-now dials (embed/open-store dir) request (embed/corpus database "document")
                                           "document" "a parser of Clojure source" 3)]
             (testing "the background fill is woken by a write of the declared attribute, not of another"
               (is (= :seon.embed/wake declared-wake))
               (is (true? (embed/embedding-report? declared)))
               (is (false? (embed/embedding-report? undeclared))))
             (testing "the declared attribute's text is embedded, the undeclared one's never"
               (is (= #{:seon.embed-test/title :seon.embed-test/topic}
                      (set (filter #(= "seon.embed-test" (namespace %))
                                   (keys (embed/embedded-attributes (db/carried-projection database)))))))
               (is (some #{"parse Clojure text into forms"} @requested))
               (is (not-any? #{"a note nobody embeds" "a changed note" "store bytes on disk"} @requested)))
             (testing "a corpus embedded on query is not filled"
               (is (contains? filled "document"))
               (is (not (contains? filled "query"))))
             (testing "kNN finds it as its entity, usable in Datalog"
               (is (= [entity] (mapv :db/id found)))
               (is (= "parse Clojure text into forms"
                      (db/q '[:find ?t . :in $ ?e :where [?e :seon.embed-test/title ?t]] database (:db/id (first found))))))))
         (finally
           (run! io/delete-file (reverse (file-seq (io/file dir))))))))))

(deftest an-entity-only-another-branch-holds-is-not-returned
  (let [dir (temp-dir)
        dials (settings)
        request (fn [_settings _task texts] (mapv (comp unit direction) texts))
        ask #(embed/entities-now dials (embed/open-store dir) request (embed/corpus % "document")
                                 "document" "a parser of Clojure source" 3)]
    (try
      (test-support/with-database
       document-options
       (fn [ours]
         (declare-documents! ours)
         (let [kept (titled! ours "store bytes on disk")]
           (test-support/with-database
            document-options
            (fn [theirs]
              (declare-documents! theirs)
              (titled! theirs "parse Clojure text into forms")
              (embed/fill-with dials (embed/open-store dir) request (db/db theirs))
              (is (= ["parse Clojure text into forms"] (mapv :seon.embed/text (ask (db/db theirs))))
                  "their branch ranks its own entity")))
           (is (= [{:db/id kept :seon.embed/text "store bytes on disk"}]
                  (mapv #(dissoc % :seon.embed/score) (ask (db/db ours))))
               "ours ranks only what ours holds, though their text is embedded and shared"))))
      (finally
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))
