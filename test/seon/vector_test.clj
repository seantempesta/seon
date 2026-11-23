(ns seon.vector-test
  "Unit & Flow integration tests for `seon.vector`, `seon.vector.flow`, `seon.server` vector routing,
   and holistic `:seon.user/*` + `:seon.vector/*` Malli schemas."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.server :as server]
            [seon.store :as store :refer [rm-rf!]]
            [seon.transactor :as transactor]
            [seon.vector :as vec]
            [seon.vector.flow :as vflow])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn- unit-vec
  "Deterministic normalized vector of length `dims` peaked at `axis` (with optional blend)."
  ([dims axis] (unit-vec dims axis {}))
  ([dims axis extras]
   (let [raw (mapv (fn [i]
                     (cond
                       (= i axis) 1.0
                       (contains? extras i) (double (get extras i))
                       :else 0.0))
                   (range dims))
         norm (Math/sqrt (reduce + 0.0 (map #(* % %) raw)))]
     (if (pos? norm) (mapv #(/ % norm) raw) raw))))

(defn- cosine [a b]
  (let [dot (reduce + 0.0 (map * a b))
        na (Math/sqrt (reduce + 0.0 (map #(* % %) a)))
        nb (Math/sqrt (reduce + 0.0 (map #(* % %) b)))]
    (if (and (pos? na) (pos? nb)) (/ dot (* na nb)) 0.0)))

(defn- await-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (if-let [v (pred)]
        v
        (if (> (System/currentTimeMillis) deadline)
          nil
          (do (Thread/sleep 15) (recur)))))))

(deftest pack-unpack-simd-roundtrip-test
  (testing "real libprobe.dylib seon_vec_pack & seon_vec_f32 round-trip at 128 and 768 dims"
    (doseq [dims [128 768]]
      (let [raw (mapv #(Math/sin (* 0.17 (inc %))) (range dims))
            norm (Math/sqrt (reduce + 0.0 (map #(* % %) raw)))
            u (mapv #(/ % norm) raw)
            packed (vec/pack u)
            f16 (:seon.vector/f16 packed)
            coarse (:seon.vector/coarse packed)
            unpacked (vec/unpack f16)]
        (is (bytes? f16))
        (is (bytes? coarse))
        (is (= (* 2 dims) (alength ^bytes f16)))
        (is (= (+ (min vec/default-coarse-dims dims) 4) (alength ^bytes coarse)))
        (is (= dims (count unpacked)))
        (is (> (cosine u unpacked) 0.9995)))))
  (testing "handles small dims, non-finite sanitization, and empty unpack cleanly"
    (let [p (vec/pack [1.0 ##NaN ##Inf 0.0] 128)
          u (vec/unpack (:seon.vector/f16 p))]
      (is (= 8 (alength ^bytes (:seon.vector/f16 p))))
      (is (= 8 (alength ^bytes (:seon.vector/coarse p))))
      (is (= [1.0 0.0 0.0 0.0] u))
      (is (= [] (vec/unpack (byte-array 0))))
      (is (= [] (vec/unpack (byte-array 1)))))))

(deftest pyramid-coarse-fine-datalog-near-and-semantic-scores-test
  (let [dir (str "/tmp/seon-vec-pyr-" (System/nanoTime))
        opened (store/open! dir)
        conn (:conn opened)]
    (try
      (let [dims 16
            v0 (unit-vec dims 0)
            v1 (unit-vec dims 0 {1 0.35})
            v2 (unit-vec dims 1)
            v3 (unit-vec dims 2)
            _ (d/transact conn
                          {:tx-data [{:seon.code.form/key "demo.a/alpha" :seon.code.form/ns 'demo.a
                                      :seon.code.form/order 1 :seon.code.form/src "(defn alpha [] :alpha)"
                                      :seon.code.form/hash "aaaa000000000001"}
                                     {:seon.code.var/name 'demo.a/alpha :seon.code.var/ns 'demo.a
                                      :seon.code.var/form "demo.a/alpha"}
                                     {:seon.code.form/key "demo.a/beta" :seon.code.form/ns 'demo.a
                                      :seon.code.form/order 2 :seon.code.form/src "(defn beta [] :beta)"
                                      :seon.code.form/hash "bbbb000000000002"}
                                     {:seon.code.var/name 'demo.a/beta :seon.code.var/ns 'demo.a
                                      :seon.code.var/form "demo.a/beta"}
                                     {:seon.code.form/key "demo.a/gamma" :seon.code.form/ns 'demo.a
                                      :seon.code.form/order 3 :seon.code.form/src "(defn gamma [] :gamma)"
                                      :seon.code.form/hash "cccc000000000003"}
                                     {:seon.code.var/name 'demo.a/gamma :seon.code.var/ns 'demo.a
                                      :seon.code.var/form "demo.a/gamma"}
                                     {:seon.code.doc/id "docs/demo.md#top" :seon.code.doc/path "docs/demo.md"
                                      :seon.code.doc/order 0 :seon.code.doc/heading "# Top"
                                      :seon.code.doc/body "Alpha overview"}]})
            db1 @conn
            ea (:e (first (d/datoms db1 :avet :seon.code.var/name 'demo.a/alpha)))
            eb (:e (first (d/datoms db1 :avet :seon.code.var/name 'demo.a/beta)))
            eg (:e (first (d/datoms db1 :avet :seon.code.var/name 'demo.a/gamma)))
            ed (:e (first (d/datoms db1 :avet :seon.code.doc/id "docs/demo.md#top")))
            tx (vec/tx-data-for-batch [{:eid ea :digest "da"} {:eid eb :digest "db"}
                                       {:eid eg :digest "dg"} {:eid ed :digest "dd"}]
                                      [v0 v1 v2 v0]
                                      [12 14 9 8]
                                      "synth+v1")
            _ (d/transact conn {:tx-data tx})
            db @conn]
        (testing "coarse-top & fine-scores directly via libprobe.dylib"
          (let [crows (mapv :seon.vector/coarse tx)
                frows (mapv :seon.vector/f16 tx)
                top2 (vec/coarse-top crows v0 2)
                fs (vec/fine-scores v0 [(first frows) nil (nth frows 1) (nth frows 2)] 8)]
            (is (= 2 (count top2)))
            (is (= #{0 3} (set (map first top2))))
            (is (> (first fs) 0.999))
            (is (nil? (second fs)))
            (is (> (nth fs 2) 0.9))
            (is (< (Math/abs (double (nth fs 3))) 0.01))))

        (testing "Datalog [(seon.vector/near $ ?q 5 {:of :seon.code.var/name}) [[?e ?score]]]"
          (let [dq-hits (sort-by (comp - second)
                                 (d/q '[:find ?e ?score
                                        :in $ ?q
                                        :where [(seon.vector/near $ ?q 5 {:of :seon.code.var/name}) [[?e ?score]]]]
                                      db v0))]
            (is (= [ea eb eg] (mapv first dq-hits)))
            (is (> (second (first dq-hits)) 0.999))
            ;; doc entity `ed` excluded by {:of :seon.code.var/name}
            (is (not (some #(= ed (first %)) dq-hits))))
          ;; Self-exclusion when querying by entity ID or lookup ref
          (let [by-self (vec/near db ea 5 {:of :seon.code.var/name})]
            (is (= [eb eg] (mapv first by-self))))
          (let [by-lref (vec/near db [:seon.code.var/name 'demo.a/alpha] 5 {:of :seon.code.var/name})]
            (is (= [eb eg] (mapv first by-lref)))))

        (testing "semantic-scores-for & describe-hits"
          (let [sc (vec/semantic-scores-for db v0 [ea eb eg 999999])
                desc (vflow/describe-hits db (vec/near db v0 2 {:of :seon.code.var/name}))]
            (is (= #{ea eb eg} (set (keys sc))))
            (is (> (get sc ea) (get sc eb) (get sc eg)))
            (is (= :var (:kind (first desc))))
            (is (= [:seon.code.var/name 'demo.a/alpha] (:ref (first desc))))))

        (testing "vector-of round-trips stored f16 vector"
          (is (> (cosine v0 (vec/vector-of db ea)) 0.999))
          (is (nil? (vec/vector-of db 999999)))))
      (finally
        (store/close! opened)
        (rm-rf! (io/file dir))))))

(deftest content-hash-coalescing-and-due-entities-test
  (let [dir (str "/tmp/seon-vec-due-" (System/nanoTime))
        opened (store/open! dir)
        conn (:conn opened)]
    (try
      (testing "EmbeddingGemma-2 prompt formatting & SHA-256 digest"
        (is (= "title: seon.x/f | text: (defn f [] 1)" (vec/document-text "seon.x/f" "(defn f [] 1)")))
        (is (= "title: none | text: body" (vec/document-text nil "body")))
        (is (= "task: search result | query: find cache" (vec/query-text "find cache")))
        (is (= 64 (count (vec/text-digest "hello"))))
        (is (= "abc123+v1-titled" (vec/model-id "/tmp/abc123.gguf"))))

      (testing "due-entities discovers all 5 kinds, skips unchanged hashes, re-queues mutated text"
        (let [_ (d/transact conn
                            {:tx-data [{:seon.code.form/key "demo.h/f1" :seon.code.form/ns 'demo.h
                                        :seon.code.form/order 1 :seon.code.form/src "(defn f1 [] :v1)"
                                        :seon.code.form/hash "ffff000000000001"}
                                       {:seon.code.var/name 'demo.h/f1 :seon.code.var/ns 'demo.h
                                        :seon.code.var/form "demo.h/f1"}
                                       {:seon.code.doc/id "docs/h.md#sec" :seon.code.doc/path "docs/h.md"
                                        :seon.code.doc/order 0 :seon.code.doc/heading "## Sec"
                                        :seon.code.doc/body "Original body"}
                                       {:seon.task/id :demo.h/task1 :seon.task/title "Fix vectors"
                                        :seon.task/plan "Wire pyramid"}
                                       {:seon.error/id (random-uuid) :seon.error/kind :test-err
                                        :seon.error/severity :warning :seon.error/source :test
                                        :seon.error/status :open :seon.error/message "Something failed"}]})
              db1 @conn
              ve (:e (first (d/datoms db1 :avet :seon.code.var/name 'demo.h/f1)))
              de (:e (first (d/datoms db1 :avet :seon.code.doc/id "docs/h.md#sec")))
              te (:e (first (d/datoms db1 :avet :seon.task/id :demo.h/task1)))
              our-eids #{ve de te}
              due1 (vec/due-entities db1 "m1+v1" 2000)
              our-due1 (filterv #(contains? our-eids (:eid %)) due1)]
          (is (= 3 (count our-due1)))
          (is (= #{:var :doc :task} (set (map :kind our-due1))))
          ;; Embed them once -> none of the 3 remain due under same model
          (let [tx (vec/tx-data-for-batch our-due1
                                          (mapv (fn [i] (unit-vec 8 i)) (range (count our-due1)))
                                          [10 12 8]
                                          "m1+v1")]
            (d/transact conn {:tx-data tx}))
          (let [due2 (filterv #(contains? our-eids (:eid %)) (vec/due-entities @conn "m1+v1" 2000))]
            (is (empty? due2)))
          ;; Mutate ONLY the form source -> incremental dirty extraction finds var `ve`, and ONLY `ve` becomes due!
          (let [r (d/transact conn {:tx-data [{:seon.code.form/key "demo.h/f1"
                                               :seon.code.form/src "(defn f1 [] :v2-changed)"}]})
                dirty (vflow/dirty-eids-from-tx-data (:db-after r) (:tx-data r))
                due3 (filterv #(contains? our-eids (:eid %)) (vec/due-entities (:db-after r) "m1+v1" 2000))]
            (is (contains? (set dirty) ve))
            (is (= [ve] (mapv :eid due3))))
          ;; Changing model-id makes all 3 due again
          (let [due4 (filterv #(contains? our-eids (:eid %)) (vec/due-entities @conn "m2+v1" 2000))]
            (is (= 3 (count due4))))))
      (finally
        (store/close! opened)
        (rm-rf! (io/file dir))))))

(deftest backpressure-state-machine-and-flow-proc-e2e-test
  (testing "pure backpressure coalescing, bounded cap, and interactive preemption"
    (let [st0 {:enabled? true :max-pending 4 :batch-size 2 :pending {} :interactive-queue [] :in-flight nil}
          st1 (vflow/enqueue-dirty st0 [1 2 1 3 4 5 6])]
      (is (= 4 (count (:pending st1))))
      (is (true? (:overflow? st1)))
      (is (true? (:more-backfill? st1)))
      (is (true? (vflow/backpressure-active? st1)))
      ;; Busy gate: nothing dispatches while in-flight
      (is (nil? (vflow/select-next-work (assoc st1 :in-flight {:id 1 :kind :backfill}))))
      ;; Interactive query ALWAYS preempts backfill
      (let [st2 (assoc st1 :interactive-queue [{:op :embed-search :query "q1"}])
            w1 (vflow/select-next-work st2)
            w2 (vflow/select-next-work (:st w1))]
        (is (= :interactive (:kind w1)))
        (is (= "q1" (get-in w1 [:req :query])))
        (is (= :backfill (:kind w2)))
        (is (= 2 (count (:eids w2)))))))

  (testing "live static Flow proc: sole-writer :tx, interactive preemption, idle worker release & clean degradation"
    (let [dir (str "/tmp/seon-vec-flow-" (System/nanoTime))
          opened (store/open! dir)
          conn (:conn opened)
          cfg (:cfg opened)
          server-in (a/chan 32)
          call-op! (fn [req]
                     (let [rch (a/chan 1)]
                       (a/>!! server-in {:req req :reply-ch rch})
                       (first (a/alts!! [rch (a/timeout 5000)]))))
          batch1-entered (promise)
          batch1-release (promise)
          call-log (atom [])
          alive-flag (atom false)
          close-count (atom 0)
          dims 8
          embed-fn (fn [texts]
                     (reset! alive-flag true)
                     (let [is-query? (and (= 1 (count texts))
                                          (clojure.string/starts-with? (first texts) vec/query-prompt))
                           n (swap! call-log conj (if is-query? :query :backfill))]
                       (when (= 1 (count n))
                         (deliver batch1-entered true)
                         (deref batch1-release 3000 true))
                       {:ok? true
                        :dims dims
                        :vectors (mapv (fn [t]
                                         (cond
                                           (clojure.string/includes? t "needle") (unit-vec dims 0)
                                           :else (unit-vec dims (inc (mod (Math/abs (hash t)) (dec dims))))))
                                       texts)
                        :tokens (mapv (constantly 6) texts)}))
          g (flow/create-flow
             {:procs {:server {:proc (flow/process #'server/server-proc)
                               :args {:in-ch server-in :conn conn :cfg cfg}}
                      :transactor {:proc (flow/process #'transactor/transactor-proc)
                                   :args {:conn conn :cfg cfg :commit-git? false}
                                   :chan-opts {:tx {:buf-or-n 64}}}
                      :vectors (vflow/proc-spec conn
                                                {:disable-vectors? false
                                                 :auto-backfill? true
                                                 :vectors-opts {:embed-fn embed-fn
                                                                :alive-fn #(deref alive-flag)
                                                                :close-fn #(do (reset! alive-flag false)
                                                                               (swap! close-count inc))
                                                                :skip-boot-scan? true
                                                                :model-id "synth+v1"
                                                                :batch-size 2
                                                                :max-pending 6
                                                                :idle-close-ms 30}})}
              :conns [[[:server :tx] [:transactor :tx]]
                      [[:server :vectors] [:vectors :req]]
                      [[:transactor :db] [:vectors :db]]
                      [[:vectors :tx] [:transactor :tx]]]})]
      (try
        (flow/start g)
        (flow/resume g)
        ;; Seed 6 docs (3 batches at batch-size=2) via :transactor -> incremental :db notice drives :vectors!
        (let [docs (mapv (fn [i]
                           {:seon.code.doc/id (str "docs/f.md#d" i)
                            :seon.code.doc/path "docs/f.md"
                            :seon.code.doc/order i
                            :seon.code.doc/heading (str "## D" i)
                            :seon.code.doc/body (if (= i 0) "needle target text" (str "filler doc " i))})
                         (range 6))
              _ (is (:ok? (call-op! {:op :transact :tx-data docs :reason :seed-docs})))]
          ;; Wait for batch #1 to enter embed-fn, verify live telemetry shows in-flight + backpressure
          (is (true? (deref batch1-entered 3000 false)))
          (let [st-mid (call-op! {:op :vector-status})]
            (is (:ok? st-mid))
            (is (true? (:enabled? st-mid)))
            (is (true? (:in-flight? st-mid)))
            (is (= 4 (:pending-count st-mid)))
            (is (true? (:backpressure-active? st-mid))))
          ;; Send interactive :embed-search WHILE batch #1 is in-flight & 4 backfill docs are queued!
          (let [search-ch (a/chan 1)]
            (a/>!! server-in {:req {:op :embed-search :query "needle" :k 3 :opts {:of :seon.code.doc/id}}
                              :reply-ch search-ch})
            (Thread/sleep 30)
            (deliver batch1-release true)
            (let [s-ans (first (a/alts!! [search-ch (a/timeout 4000)]))]
              (is (:ok? s-ans))
              (is (= "needle" (:query s-ans)))
              (is (= dims (count (:vector s-ans)))))
            ;; Prove interactive query preempted batch #2 (call order: [:backfill :query :backfill :backfill])
            (is (= :query (second @call-log)))
            ;; Wait for all 6 docs to land via sole-writer :tx & worker to auto-close on idle drain!
            (let [drained (await-until #(let [st (call-op! {:op :vector-status})]
                                          (when (and (= 6 (:indexed-count st))
                                                     (not (:in-flight? st))
                                                     (zero? (:pending-count st))
                                                     (pos? @close-count))
                                            st))
                                       4000)]
              (is (some? drained))
              (is (false? (:worker-alive? drained)))
              (is (= 3 (:batches-done drained)))
              (is (= 1 (:queries-done drained)))
              (is (zero? (:errors drained))))))
        (finally
          (try (flow/stop g) (catch Throwable _))
          (store/close! opened)
          (rm-rf! (io/file dir))))))

  (testing "clean degradation when disabled or model file is missing"
    (let [dir (str "/tmp/seon-vec-off-" (System/nanoTime))
          opened (store/open! dir)
          conn (:conn opened)
          cfg (:cfg opened)
          server-in (a/chan 16)
          call-op! (fn [req]
                     (let [rch (a/chan 1)]
                       (a/>!! server-in {:req req :reply-ch rch})
                       (first (a/alts!! [rch (a/timeout 3000)]))))
          g (flow/create-flow
             {:procs {:server {:proc (flow/process #'server/server-proc)
                               :args {:in-ch server-in :conn conn :cfg cfg}}
                      :vectors (vflow/proc-spec conn {:disable-vectors? false
                                                      :vectors-opts {:model-path "/nonexistent/model.gguf"}})}
              :conns [[[:server :vectors] [:vectors :req]]]})]
      (try
        (flow/start g)
        (flow/resume g)
        (let [st (call-op! {:op :vector-status})
              sr (call-op! {:op :embed-search :query "hello" :k 3})]
          (is (:ok? st))
          (is (false? (:enabled? st)))
          (is (false? (:ok? sr)))
          (is (= :vectors-disabled (:error sr))))
        (finally
          (try (flow/stop g) (catch Throwable _))
          (store/close! opened)
          (rm-rf! (io/file dir)))))))

(deftest embed-worker-framing-and-serve-loop-test
  (testing "length-prefixed EDN stream framing & serve-loop! handshake, embed, ping, and EOF close"
    (let [req-buf (ByteArrayOutputStream.)
          _ (vec/write-frame! req-buf {:op :ping})
          _ (vec/write-frame! req-buf {:op :embed :texts ["alpha" "beta"]})
          in (ByteArrayInputStream. (.toByteArray req-buf))
          out (ByteArrayOutputStream.)
          closed? (atom false)
          embedder {:dims 4
                    :model-id "mock+v1"
                    :embed-fn (fn [texts]
                                {:ok? true :dims 4
                                 :vectors (mapv (fn [_] [1.0 0.0 0.0 0.0]) texts)
                                 :tokens (mapv count texts)})
                    :close-fn #(reset! closed? true)}
          fin (vec/serve-loop! in out embedder)
          resp-in (ByteArrayInputStream. (.toByteArray out))
          ready (vec/read-frame! resp-in)
          pong (vec/read-frame! resp-in)
          ans (vec/read-frame! resp-in)
          eof (vec/read-frame! resp-in)]
      (is (= {:ok? true :served 1 :reason :eof} fin))
      (is (true? @closed?))
      (is (true? (:ok? ready)))
      (is (true? (:ready? ready)))
      (is (= 4 (:dims ready)))
      (is (= "mock+v1" (:model-id ready)))
      (is (true? (:ready? pong)))
      (is (true? (:ok? ans)))
      (is (= 2 (count (:vectors ans))))
      (is (= [5 4] (:tokens ans)))
      (is (= :eof eof)))))

(deftest user-holistic-ui-schemas-test
  (let [dir (str "/tmp/seon-vec-user-" (System/nanoTime))
        opened (store/open! dir)
        conn (:conn opened)]
    (try
      (doseq [m [:session :context :system :inspect :canvas :workspace :debug]]
        (d/transact conn {:tx-data [{:seon.user/id :user/default
                                     :seon.user/view-mode m}]}))
      (d/transact conn {:tx-data [{:seon.user/id :user/default
                                   :seon.user/view-mode :inspect
                                   :seon.user/inspect-ref [:seon.code.var/name 'seon.vector/near]
                                   :seon.user/inspect-trail [[:seon.code.var/name 'seon.vector/pack]
                                                             [:seon.code.var/name 'seon.vector/near]]
                                   :seon.user/lab-prefs {:semantic-weight 0.4 :show-trace? true}}]})
      (let [u (d/pull @conn '[:seon.user/view-mode :seon.user/inspect-ref
                              :seon.user/inspect-trail :seon.user/lab-prefs]
                      [:seon.user/id :user/default])]
        (is (= :inspect (:seon.user/view-mode u)))
        (is (= [:seon.code.var/name 'seon.vector/near] (:seon.user/inspect-ref u)))
        (is (= 2 (count (:seon.user/inspect-trail u))))
        (is (= {:semantic-weight 0.4 :show-trace? true} (:seon.user/lab-prefs u))))
      (finally
        (store/close! opened)
        (rm-rf! (io/file dir))))))
