(ns seon.embed-test
  "Embeddings keyed by content: each text reaches the embed server once, across caches and restarts; the nearest
  texts by them."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.cache]
            [seon.embed :as embed]
            [seon.id :as id])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- settings
  "Dials for a test: a model name of its own, so its content keys are new to the process cache."
  {:malli/schema [:=> [:cat] :seon.embed/settings]}
  []
  #:seon.config.embed{:endpoint "http://127.0.0.1:8701/v1/embed" :model (str "test-model-" (id/id)) :dimension 4
                      :batch-size 2 :timeout-ms 2000})

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

(deftest a-text-is-requested-once-across-a-restart
  ;; The request is the external effect, passed as a value that counts its texts at the edge.
  (let [dir (temp-dir)
        dials (settings)
        requested (atom [])
        request (fn [_settings _task texts] (swap! requested into texts) (mapv (comp unit axes) texts))
        texts ["seon.db/transact!" "seon.db/pull" "seon.db/transact!" "seon.cache/lookup"]]
    (try
      (let [first-run (embed/embed! dials (embed/open-store dir) request "similarity" texts)
            warm (embed/embed! dials (embed/open-store dir) request "similarity" texts)
            ;; a restart: the process cache holds nothing, the durable store everything
            _ (seon.cache/evict-kinds! #{:seon.embed/embedding})
            restarted (embed/embed! dials (embed/open-store dir) request "similarity" texts)]
        (testing "the first run requests each distinct text once, in batches of the dial"
          (is (= ["seon.db/transact!" "seon.db/pull" "seon.cache/lookup"] @requested))
          (is (= 3 (:seon.embed/requested first-run))))
        (testing "a warm process reads neither the store nor the server"
          (is (= {:seon.embed/hot 3 :seon.embed/durable 0 :seon.embed/requested 0} (dissoc warm :seon.embed/vectors))))
        (testing "a restart makes zero embed calls for unchanged text"
          (is (= {:seon.embed/hot 0 :seon.embed/durable 3 :seon.embed/requested 0} (dissoc restarted :seon.embed/vectors)))
          (is (= 3 (count @requested)))
          (is (= (mapv vec (:seon.embed/vectors first-run)) (mapv vec (:seon.embed/vectors restarted)))))
        (testing "another task is another content key"
          (is (= 3 (:seon.embed/requested (embed/embed! dials (embed/open-store dir) request "code" texts))))))
      (finally
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))

(deftest a-misspelled-name-is-nearest-the-name-it-misspells
  (let [dir (temp-dir)
        request (fn [_settings _task texts] (mapv (comp unit axes) texts))]
    (try
      (is (= ["seon.db/transact!" "seon.db/q"]
             (mapv :seon.embed/text (embed/similar (settings) (embed/open-store dir) request "similarity"
                                                   ["seon.db/pull" "seon.db/transact!" "seon.cache/lookup" "seon.db/q"]
                                                   "similarity" "seon.db/transcat!" 2))))
      (finally
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))

(deftest an-unreachable-embed-server-is-the-declared-error
  (let [dir (temp-dir)]
    (try
      (let [answer (embed/similar (assoc (settings) :seon.config.embed/endpoint "http://127.0.0.1:9/v1/embed")
                                  (embed/open-store dir) embed/request!
                                  "similarity" ["seon.db/pull"] "similarity" "seon.db/pul" 3)]
        (is (= :seon.embed/unreachable-error (:seon.error/type answer)))
        (is (= "http://127.0.0.1:9/v1/embed" (:seon.config.embed/endpoint answer))))
      (finally
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))
