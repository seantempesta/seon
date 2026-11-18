(ns seon.core-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [jolt.async.remote :as remote]
            [jolt.ffi :as ffi]
            [jolt.scheme :as scm]
            [seon.code :as code]
            [seon.merge :as merge]
            [seon.store :as store]))

(ffi/load-library)
(ffi/defcfn c-waitpid "waitpid" [:int :pointer :int] :int)

(def ticks-fn (scm/eval-string "(lambda () (jolt-ticks))"))

(defn rm-rf! [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf! c)))
    (.delete f)))

(defmacro with-temp-store [[sym] & body]
  `(let [dir# (str "/tmp/seon-test-" (System/nanoTime))
         ~sym (store/open! dir#)]
     (try
       ~@body
       (finally
         (store/close! ~sym)
         (rm-rf! (java.io.File. dir#))))))

(deftest lossless-clj-roundtrip-and-semantic-hash-test
  (with-temp-store [{:keys [conn]}]
    (let [sample (str ";; Leading file header comment\n"
                      "(ns demo.math\n"
                      "  \"Docstring for math ns.\"\n"
                      "  (:require [clojure.string :as str]))\n\n"
                      ";; Keep this comment byte-for-byte!\n"
                      "(defn add\n"
                      "  \"Adds x and y.\"\n"
                      "  [x y]\n"
                      "  (+ x y))\n\n"
                      "(defn loud [s]\n"
                      "  {:tag :: local-kw :s (str/upper-case s)})\n"
                      ";; Trailing comment\n")
          ;; Fix valid auto-resolved kw syntax
          sample (clojure.string/replace sample ":: local-kw" "::local-kw")
          tx (code/file-import-tx nil "src/demo/math.clj" sample false)
          _ (d/transact conn {:tx-data tx})
          rendered (code/render-path @conn "src/demo/math.clj")]
      (testing "import -> render is 100% byte-for-byte identical"
        (is (= sample rendered)))

      (testing "editing comments, whitespace, and docstrings does NOT change semantic :form/hash"
        (let [h1 (:form/hash (d/pull @conn '[:form/hash] [:form/key "demo.math/add"]))
              edited (clojure.string/replace sample "\"Adds x and y.\"" "\"Updated docs!\" ;; extra comment")
              _ (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/math.clj" edited false)})
              h2 (:form/hash (d/pull @conn '[:form/hash] [:form/key "demo.math/add"]))]
          (is (= h1 h2)))))))

(deftest malli-write-seam-rail-test
  (with-temp-store [{:keys [conn]}]
    (testing "malformed attribute values are rejected at transact! before entering the store"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Schema rail rejected"
           (d/transact conn {:tx-data [{:task/id :unqualified-bad-id :task/title "Bad"}]}))))))

(deftest three-way-merge-per-function-and-conflict-test
  (with-temp-store [{:keys [conn cfg]}]
    (let [src-v0 "(ns demo.app)\n\n(defn alpha [x] (+ x 1))\n\n(defn beta [x] (* x 2))\n"
          _ (d/transact conn {:tx-data (code/file-import-tx nil "src/demo/app.clj" src-v0 false)})
          _ (store/branch! conn :main :branch/a)
          _ (store/branch! conn :main :branch/b)
          ca (store/connect cfg :branch/a)
          cb (store/connect cfg :branch/b)]
      (try
        ;; Branch A edits `alpha`; Branch B edits `beta` in the SAME file!
        (d/transact ca {:tx-data [{:form/key "demo.app/alpha"
                                   :form/ns 'demo.app
                                   :form/order 1
                                   :form/lead "\n\n"
                                   :form/src "(defn alpha [x] (+ x 100))"
                                   :form/hash "aaaaaaaaaaaaaaaa"
                                   :form/deps ['demo.app/#ns]
                                   :form/test? false}]})
        (d/transact cb {:tx-data [{:form/key "demo.app/beta"
                                   :form/ns 'demo.app
                                   :form/order 2
                                   :form/lead "\n\n"
                                   :form/src "(defn beta [x] (* x 999))"
                                   :form/hash "bbbbbbbbbbbbbbbb"
                                   :form/deps ['demo.app/#ns]
                                   :form/test? false}]})

        ;; Merge A -> :main
        (let [plan-a (merge/three-way conn cfg :branch/a)]
          (is (:ok? plan-a))
          (d/transact conn {:tx-data (:tx-data plan-a)}))

        ;; Merge B -> :main (same file, different function -> 0 conflicts!)
        (let [plan-b (merge/three-way conn cfg :branch/b)]
          (is (:ok? plan-b))
          (is (empty? (:conflicts plan-b)))
          (d/transact conn {:tx-data (:tx-data plan-b)}))

        (let [merged-file (code/render-path @conn "src/demo/app.clj")]
          (is (clojure.string/includes? merged-file "(defn alpha [x] (+ x 100))"))
          (is (clojure.string/includes? merged-file "(defn beta [x] (* x 999))")))

        ;; Now if Branch B ALSO edits `alpha` differently from `:main`, 3-way catches the exact conflict!
        (d/transact cb {:tx-data [{:form/key "demo.app/alpha"
                                   :form/ns 'demo.app
                                   :form/order 1
                                   :form/lead "\n\n"
                                   :form/src "(defn alpha [x] (- x 50))"
                                   :form/hash "cccccccccccccccc"
                                   :form/deps ['demo.app/#ns]
                                   :form/test? false}]})
        (let [plan-conflict (merge/three-way conn cfg :branch/b)]
          (is (false? (:ok? plan-conflict)))
          (is (= [[:form/key "demo.app/alpha"]] (mapv :entity (:conflicts plan-conflict)))))
        (finally
          (d/release ca)
          (d/release cb))))))

(defn- seed-scale-forms! [conn n]
  (doseq [batch (partition-all 200 (range n))]
    (d/transact
     conn
     {:tx-data
      (vec
       (for [i batch
             :let [nsym (symbol (str "scale.m" (quot i 25)))
                   fsym (str "f" i)]]
         {:form/key (str nsym "/" fsym)
          :form/ns nsym
          :form/order (long (mod i 25))
          :form/lead "\n"
          :form/src (str "(defn " fsym " [x] (+ x " i "))")
          :form/hash (format "%016x" (+ 500000 i))
          :form/deps []
          :form/test? false}))})))

(defn- measure-delta-ops-ticks [n]
  (with-temp-store [{:keys [conn cfg]}]
    (seed-scale-forms! conn n)
    (let [g0 (code/build-reach-graph @conn)
          _ (store/branch! conn :main :branch/edit)
          bc (store/connect cfg :branch/edit)]
      (try
        (d/transact bc {:tx-data [{:form/key "scale.m0/f0"
                                   :form/ns 'scale.m0
                                   :form/order 0
                                   :form/lead "\n"
                                   :form/src "(defn f0 [x] (* x 777))"
                                   :form/hash "fedcba9876543210"
                                   :form/deps []
                                   :form/test? false}]})
        (let [k0 (ticks-fn)
              _ (code/update-reach-graph g0 bc)
              _ (merge/three-way conn cfg :branch/edit)
              dk (- (ticks-fn) k0)]
          dk)
        (finally
          (d/release bc))))))

(deftest scale-invariance-rail-test
  (testing "O(|Delta|) rail: 10x larger DB (N=100 vs N=1,000) must cost < 1.5x ticks for a 1-form edit!"
    ;; Warm once
    (measure-delta-ops-ticks 25)
    (let [ticks-100  (measure-delta-ops-ticks 100)
          ticks-1000 (measure-delta-ops-ticks 1000)
          ratio (/ (double ticks-1000) (double (max 1 ticks-100)))]
      (is (< ratio 1.5)
          (str "Detected O(N) regression! ticks-100=" ticks-100 " ticks-1000=" ticks-1000 " ratio=" ratio)))))
