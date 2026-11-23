(ns seon.vector.flow
  "The `:vectors` Static Flow proc (`:io` workload) — backpressured, coalescing semantic indexing
   and interactive query embedding over an isolated `embed-worker` subprocess:
   - ZERO blocking inside Flow `transition`/`transform` (all subprocess/IPC runs on `a/thread`).
   - Bounded pending dirty set (default 512) coalesces duplicate entity edits.
   - Single in-flight batch gate (default 16); interactive `:embed-search`/`:embed-query` ALWAYS preempt backfill.
   - Sole-writer invariant: emits `:seon.vector/*` tx-data strictly via `:tx` -> `:transactor :tx`.
   - Auto-closes `embed-worker` on idle drain (0 MB held at rest) and degrades cleanly when disabled."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.vector :as vec]))

(def default-batch-size 16)
(def default-max-pending 512)
(def default-idle-close-ms 15000)

(defn- reply! [ch val]
  (when ch
    (a/put! ch val)
    (a/close! ch)))

(defn- stored-val [db e a] (:v (first (d/datoms db :eavt e a))))

;; ---- DB helpers: indexed count, incremental dirty extraction & hit formatting -

(defn indexed-count
  "Counts stored `:seon.vector/of` entities on `db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :int] :cost/ticks 2000000}
  [db]
  (if (and db (contains? (:schema db) :seon.vector/of))
    (count (d/datoms db :aevt :seon.vector/of))
    0))

(defn extract-entity-text
  "Extracts `{:eid :kind :text :digest}` for `eid` via O(1) `:eavt`/`:avet` seeks, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe :map]] :cost/ticks 500000}
  [db eid]
  (let [sch (:schema db)
        av (fn [a] (when (contains? sch a) (stored-val db eid a)))
        mk (fn [kind text] (when-not (str/blank? text) {:eid eid :kind kind :text text :digest (vec/text-digest text)}))]
    (if-let [vn (av :seon.code.var/name)]
      (let [fk (av :seon.code.var/form)
            fe (when (and fk (contains? sch :seon.code.form/key)) (:e (first (d/datoms db :avet :seon.code.form/key fk))))
            src (or (when fe (stored-val db fe :seon.code.form/src)) (av :seon.code.var/doc))]
        (when-not (str/blank? (str src)) (mk :var (vec/document-text (str vn) (str src)))))
      (if-let [body (av :seon.code.doc/body)]
        (when-not (str/blank? (str body))
          (mk :doc (vec/document-text (some-> (or (av :seon.code.doc/heading) (av :seon.code.doc/id)) str) (str body))))
        (if-let [sk (av :seon.schema/key)]
          (let [sd (av :seon.schema/doc) sf (av :seon.schema/form)
                b (str/trim (str (when-not (str/blank? (str sd)) (str sd "\n")) (when (some? sf) (pr-str sf))))]
            (when-not (str/blank? b) (mk :schema (vec/document-text (str sk) b))))
          (if-let [tid (av :seon.task/id)]
            (let [b (str/trim (str (or (av :seon.task/title) (str tid))
                                   (when-let [pl (av :seon.task/plan)] (when-not (str/blank? (str pl)) (str "\n" pl)))))]
              (when-not (str/blank? b) (mk :task (vec/query-text b))))
            (when-let [msg (av :seon.error/message)]
              (when-not (str/blank? (str msg))
                (mk :error (vec/document-text (str (or (av :seon.error/kind) :seon.error/entity)) (str msg)))))))))))

(def ^:private tracked-text-attrs
  #{:seon.code.form/src :seon.code.var/name :seon.code.var/form :seon.code.var/doc
    :seon.code.doc/body :seon.code.doc/heading :seon.schema/doc :seon.schema/form
    :seon.task/title :seon.task/plan :seon.error/message :seon.error/kind})

(defn dirty-eids-from-tx-data
  "Extracts embeddable entity IDs affected by incremental `tx-data` datoms in O(|tx-data|)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :any]] [:vector :int]] :cost/ticks 2000000}
  [db tx-data]
  (let [sch (:schema db)
        has-vf? (and (contains? sch :seon.code.var/form) (contains? sch :seon.code.form/key))]
    (vec
     (persistent!
      (reduce (fn [acc dt]
                (let [a (:a dt) e (:e dt)]
                  (cond
                    (not (contains? tracked-text-attrs a)) acc
                    (= :seon.code.form/src a)
                    (if-let [fk (when has-vf? (stored-val db e :seon.code.form/key))]
                      (reduce #(conj! %1 (:e %2)) acc (d/datoms db :avet :seon.code.var/form fk))
                      acc)
                    (int? e) (conj! acc e)
                    :else acc)))
              (transient #{}) tx-data)))))

(defn describe-hits
  "Enriches `[[eid score] ...]` into `{:eid :score :kind :ref :label}` maps."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential [:tuple :int number?]]] [:vector :map]]
   :cost/ticks 10000000}
  [db hits]
  (mapv (fn [[e s]]
          (let [p (d/pull db '[:seon.code.var/name :seon.code.doc/id :seon.code.doc/heading
                               :seon.schema/key :seon.task/id :seon.task/title
                               :seon.error/id :seon.error/kind :seon.error/message] e)
                [kind ref label]
                (cond
                  (:seon.code.var/name p) [:var [:seon.code.var/name (:seon.code.var/name p)] (str (:seon.code.var/name p))]
                  (:seon.code.doc/id p) [:doc [:seon.code.doc/id (:seon.code.doc/id p)]
                                         (str (:seon.code.doc/id p) (when (:seon.code.doc/heading p) (str " — " (:seon.code.doc/heading p))))]
                  (:seon.schema/key p) [:schema [:seon.schema/key (:seon.schema/key p)] (str (:seon.schema/key p))]
                  (:seon.task/id p) [:task [:seon.task/id (:seon.task/id p)] (str (:seon.task/id p) " — " (:seon.task/title p))]
                  (:seon.error/id p) [:error [:seon.error/id (:seon.error/id p)]
                                      (let [m (str (:seon.error/message p))]
                                        (str (:seon.error/kind p :error) ": " (if (> (count m) 80) (str (subs m 0 80) "…") m)))]
                  :else [:entity [:db/id e] (str "entity/" e)])]
            {:eid e :score (double s) :kind kind :ref ref :label label}))
        hits))

(defn ^:seon.flow/edge remote-embed-search!
  "Queries the standing Seon daemon's `:op :embed-search` over loopback HTTP (safe from forked agents)."
  {:malli/schema [:function [:=> [:cat :string pos-int? :map] :map]] :cost/ticks 5000000}
  [query k opts]
  (let [port (or (:port opts) (System/getenv "SEON_PORT") 9998)
        url (str "http://127.0.0.1:" port "/op")
        payload (pr-str {:op :embed-search :query query :k (long k) :opts (dissoc (or opts {}) :port)})
        post-fn (try (requiring-resolve 'seon.model/http-post!) (catch Throwable _ nil))]
    (if-not post-fn
      {:ok? false :error :http-client-unavailable :results []}
      (try
        (let [resp (post-fn url [["Content-Type" "application/edn"]] payload)]
          (if (= 200 (:status resp))
            (edn/read-string (:body resp))
            {:ok? false :error :http-error :status (:status resp) :results []}))
        (catch Throwable t
          {:ok? false :error :daemon-unreachable :message (or (.getMessage t) (str t)) :results []})))))

;; ---- pure backpressure & coalescing state machine ---------------------------

(defn enqueue-dirty
  "Coalesces `eids` into `st`'s bounded `:pending` map (`{eid seq}` capped at `:max-pending`)."
  {:malli/schema [:=> [:cat :map [:sequential :int]] :map] :cost/ticks 5000000}
  [st eids]
  (let [cap (max 1 (long (:max-pending st default-max-pending)))]
    (reduce (fn [s eid]
              (let [pend (:pending s {}) ord (inc (long (:pending-order s 0)))]
                (cond
                  (contains? pend eid) (-> s (assoc :pending-order ord) (assoc-in [:pending eid] ord))
                  (< (count pend) cap) (-> s (assoc :pending-order ord) (assoc-in [:pending eid] ord))
                  :else (assoc s :overflow? true :more-backfill? true))))
            st eids)))

(defn backpressure-active?
  "True when `:vectors` is actively backpressuring (overflowed cap, >=50% full, or busy with queued work)."
  {:malli/schema [:=> [:cat :map] :boolean] :cost/ticks 200000}
  [st]
  (let [pend-n (count (:pending st))
        inter-n (count (:interactive-queue st))
        cap (max 1 (long (:max-pending st default-max-pending)))]
    (boolean
     (or (:overflow? st)
         (>= pend-n (max 1 (quot cap 2)))
         (and (some? (:in-flight st)) (pos? (+ pend-n inter-n)))))))

(defn select-next-work
  "Pure scheduler: returns nil when busy/disabled/empty, else pops next work (interactive preempts backfill)."
  {:malli/schema [:=> [:cat :map] [:maybe :map]] :cost/ticks 2000000}
  [st]
  (cond
    (some? (:in-flight st)) nil
    (not (:enabled? st)) nil

    (seq (:interactive-queue st))
    (let [q (vec (:interactive-queue st))]
      {:kind :interactive :req (first q) :st (assoc st :interactive-queue (subvec q 1))})

    (and (seq (:pending st)) (< (long (:consecutive-errors st 0)) 3))
    (let [bsz (max 1 (long (:batch-size st default-batch-size)))
          ordered (mapv key (sort-by val (:pending st)))
          batch (subvec ordered 0 (min bsz (count ordered)))]
      {:kind :backfill :eids batch :st (update st :pending #(apply dissoc % batch))})

    :else nil))

(defn status-snapshot
  "Builds the live `:op :vector-status` backpressure and indexing telemetry map."
  {:malli/schema [:=> [:cat :map [:maybe :seon.host/db]] :map] :cost/ticks 3000000}
  [st db]
  {:ok? true
   :enabled? (boolean (:enabled? st))
   :model-path (:model-path st)
   :model-id (:model-id st)
   :indexed-count (if db (indexed-count db) 0)
   :pending-count (count (:pending st))
   :interactive-pending (count (:interactive-queue st))
   :in-flight? (boolean (:in-flight st))
   :in-flight-kind (:kind (:in-flight st))
   :worker-alive? (boolean (:worker-alive? st))
   :batches-done (long (:batches-done st 0))
   :queries-done (long (:queries-done st 0))
   :errors (long (:errors st 0))
   :last-error (:last-error st)
   :backpressure-active? (backpressure-active? st)})

;; ---- async IO edge helpers (ZERO blocking in Flow steps!) -------------------

(defn- ^:seon.flow/edge dispatch-embed-async! [embed-fn alive-fn worker-res-ch req-id kind texts]
  (a/thread
    (let [res (try (embed-fn texts)
                   (catch Throwable t {:ok? false :error :embed-exception :message (or (.getMessage t) (str t))}))]
      (a/put! worker-res-ch {:id req-id :kind kind :result res
                             :worker-alive? (boolean (when alive-fn (alive-fn)))}))))

(defn- ^:seon.flow/edge close-worker-async! [close-fn]
  (when close-fn (a/thread (try (close-fn) (catch Throwable _ nil)))))

(defn- schedule-idle-close [st]
  (let [idle-ms (long (:idle-close-ms st default-idle-close-ms))
        tok (inc (long (:idle-token st 0)))
        st' (assoc st :idle-token tok)]
    (if (not (:worker-alive? st'))
      st'
      (if (<= idle-ms 0)
        (do (close-worker-async! (:close-fn st')) (assoc st' :worker-alive? false))
        (do (when-let [tch (get-in st' [::flow/in-ports :idle-tick])]
              (a/go (a/<! (a/timeout idle-ms)) (a/put! tch {:token tok})))
            st')))))

(defn- fresh-due-items [db cur-model eids]
  (let [has-vec? (contains? (:schema db) :seon.vector/of)]
    (vec
     (keep (fn [eid]
             (when-let [it (extract-entity-text db eid)]
               (let [ve (when has-vec? (:e (first (d/datoms db :avet :seon.vector/of eid))))
                     cur-m (when ve (stored-val db ve :seon.vector/model))
                     cur-h (when ve (stored-val db ve :seon.vector/text))]
                 (when-not (and (= cur-model cur-m) (= (:digest it) cur-h)) it))))
           eids))))

(defn- maybe-dispatch [st]
  (loop [cur st]
    (if-let [work (select-next-work cur)]
      (let [res-ch (get-in cur [::flow/in-ports :worker-res])
            rid (inc (long (:req-seq cur 0)))
            base (-> (:st work) (assoc :req-seq rid :idle-token (inc (long (:idle-token cur 0)))))]
        (case (:kind work)
          :interactive
          (let [req (:req work)]
            (dispatch-embed-async! (:embed-fn base) (:alive-fn base) res-ch rid :interactive [(vec/query-text (str (:query req)))])
            [(assoc base :in-flight {:id rid :kind :interactive :req req}) nil])

          :backfill
          (let [items (fresh-due-items @(:conn base) (:model-id base) (:eids work))]
            (if (empty? items)
              (recur base)
              (do (dispatch-embed-async! (:embed-fn base) (:alive-fn base) res-ch rid :backfill (mapv :text items))
                  [(assoc base :in-flight {:id rid :kind :backfill :items items}) nil])))))
      (let [idle? (and (nil? (:in-flight cur)) (empty? (:interactive-queue cur))
                       (empty? (:pending cur)) (not (:more-backfill? cur)))]
        [(if idle? (schedule-idle-close cur) cur) nil]))))

(defn- queue-due-scan [st]
  (if-not (and (:enabled? st) (< (long (:consecutive-errors st 0)) 3))
    st
    (let [cap (max 1 (long (:max-pending st default-max-pending)))
          items (vec/due-entities @(:conn st) (:model-id st) cap)]
      (-> (enqueue-dirty st (mapv :eid items))
          (assoc :backfill-scanned? true :overflow? false :more-backfill? (>= (count items) cap))))))

(defn- handle-db [st msg]
  (if-not (:enabled? st)
    [st nil]
    (let [reason (:reason msg)]
      (cond
        (and (vector? reason) (= :vectors-batch (first reason)))
        (let [st1 (if (and (empty? (:pending st)) (:more-backfill? st) (nil? (:in-flight st)))
                    (queue-due-scan st) st)]
          (maybe-dispatch st1))

        (or (= :boot-init reason) (not (:backfill-scanned? st)))
        (maybe-dispatch (if (:auto-backfill? st) (queue-due-scan st) (assoc st :backfill-scanned? true)))

        :else
        (let [dirty (when (:auto-backfill? st) (dirty-eids-from-tx-data @(:conn st) (:tx-data msg)))]
          (maybe-dispatch (if (seq dirty) (enqueue-dirty st dirty) st)))))))

(defn- handle-req [st msg]
  (let [op (:op msg) reply-ch (:reply-ch msg) db @(:conn st)]
    (case op
      :vector-status
      (do (reply! reply-ch (status-snapshot st db)) [st nil])

      :backfill-now
      (if-not (:enabled? st)
        (do (reply! reply-ch {:ok? false :enabled? false :error :vectors-disabled :queued 0}) [st nil])
        (let [st1 (queue-due-scan (assoc st :consecutive-errors 0))]
          (reply! reply-ch {:ok? true :queued (count (:pending st1)) :more-backfill? (boolean (:more-backfill? st1))})
          (maybe-dispatch st1)))

      (:embed-search :embed-query)
      (let [q (:query msg) k (long (or (:k msg) 10)) opts (or (:opts msg) {})]
        (cond
          (and (= op :embed-search)
               (or (and (vector? q) (seq q) (number? (first q)))
                   (int? q)
                   (and (vector? q) (= 2 (count q)) (keyword? (first q)))))
          (let [hits (vec/near db q k opts)]
            (reply! reply-ch {:ok? true :query q :hits hits :results (describe-hits db hits) :model-id (:model-id st)})
            [st nil])

          (not (:enabled? st))
          (do (reply! reply-ch {:ok? false :enabled? false :error :vectors-disabled :results []}) [st nil])

          :else
          (maybe-dispatch (update st :interactive-queue conj {:op op :query (str q) :k k :opts opts :reply-ch reply-ch}))))

      (do (reply! reply-ch {:ok? false :error (str "Unknown vectors op: " op)}) [st nil]))))

(defn- handle-worker-res [st msg]
  (let [inflight (:in-flight st)]
    (if (or (nil? inflight) (not= (:id msg) (:id inflight)))
      [st nil]
      (let [res (:result msg) ok? (boolean (:ok? res))
            st0 (assoc st :in-flight nil :worker-alive? (boolean (:worker-alive? msg)))]
        (case (:kind inflight)
          :interactive
          (let [{:keys [op query k opts reply-ch]} (:req inflight)
                st1 (if ok? (-> st0 (update :queries-done inc) (assoc :consecutive-errors 0))
                        (-> st0 (update :errors inc) (assoc :last-error (or (:error res) :embed-failed))))]
            (if (and ok? (seq (:vectors res)))
              (let [qv (first (:vectors res))]
                (reply! reply-ch
                        (if (= op :embed-query)
                          {:ok? true :query query :vector qv :dims (:dims res) :model-id (:model-id st1)}
                          (let [db @(:conn st1) hits (vec/near db qv k opts)]
                            {:ok? true :query query :vector qv :hits hits
                             :results (describe-hits db hits) :model-id (:model-id st1)}))))
              (reply! reply-ch {:ok? false :error (or (:error res) :embed-failed) :results []}))
            (maybe-dispatch st1))

          :backfill
          (let [items (:items inflight)]
            (if ok?
              (let [tx-data (vec/tx-data-for-batch items (:vectors res) (:tokens res) (:model-id st0))
                    [st2 _] (maybe-dispatch (-> st0 (update :batches-done inc) (assoc :consecutive-errors 0)))]
                [st2 (when (seq tx-data) {:tx [{:op :transact :tx-data tx-data :reason [:vectors-batch (count items)]}]})])
              (maybe-dispatch (-> st0 (update :errors inc) (update :consecutive-errors (fnil inc 0))
                                  (assoc :last-error (or (:error res) :embed-failed)))))))))))

(defn- handle-idle-tick [st msg]
  (if (and (= (:token msg) (:idle-token st)) (nil? (:in-flight st))
           (empty? (:interactive-queue st)) (empty? (:pending st)) (:worker-alive? st))
    (do (close-worker-async! (:close-fn st)) [(assoc st :worker-alive? false) nil])
    [st nil]))

(defn vectors-proc
  "Static `core.async.flow` `:io` proc for backpressured semantic indexing and interactive search."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map]
                  [:=> [:cat :map :any] :map] [:=> [:cat :map :any :any] [:tuple :map :any]]]
   :cost/ticks 5000000}
  ([] {:params {:conn "Datahike :main connection (read-only; writes go through :tx)"
                :enabled? "Optional boolean flag (false disables worker cleanly)"
                :auto-backfill? "Optional boolean flag enabling background backfill on :db notices"
                :model-path "Optional GGUF path override (defaults to SEON_EMBED_MODEL)"
                :model-id "Optional model id override"
                :batch-size "Max texts per backfill batch (default 16)"
                :max-pending "Bounded dirty queue cap (default 512)"
                :idle-close-ms "Idle timeout ms before reaping embed-worker (default 15000)"
                :embed-fn "Optional injectable (fn [texts] {:ok? :dims :vectors :tokens}) for tests"
                :close-fn "Optional injectable worker close callback"
                :alive-fn "Optional injectable worker alive? callback"}
       :ins {:db "Commit notices from :transactor (sliding buffer)"
             :req "Interactive :embed-search, :embed-query, :vector-status, :backfill-now from :server"}
       :outs {:tx "Upsert :seon.vector/* transactions sent solely to :transactor :tx"}
       :workload :io
       :doc "Backpressured semantic embedding backfill and interactive vector search."
       :ping-map-fn (fn [st] {:enabled? (boolean (:enabled? st)) :pending (count (:pending st))
                              :in-flight? (boolean (:in-flight st)) :worker-alive? (boolean (:worker-alive? st))
                              :batches-done (:batches-done st 0) :queries-done (:queries-done st 0)
                              :errors (:errors st 0) :backpressure-active? (backpressure-active? st)})})
  ([args]
   (let [worker-res-ch (a/chan 32) idle-tick-ch (a/chan 16)
         custom-embed (:embed-fn args)
         req-enabled? (if (contains? args :enabled?) (boolean (:enabled? args)) true)
         mpath (when (and req-enabled? (nil? custom-embed))
                 (if (contains? args :model-path) (vec/resolve-model-path (:model-path args)) (vec/resolve-model-path)))
         client (when (and req-enabled? (nil? custom-embed) mpath) (vec/embed-client {:model-path mpath}))
         eff-enabled? (boolean (and req-enabled? (or custom-embed client)))
         eff-model-id (or (:model-id args) (:model-id client) (when custom-embed "synthetic+v1") "disabled")]
     {:conn (:conn args) :enabled? eff-enabled?
      :auto-backfill? (if (contains? args :auto-backfill?) (boolean (:auto-backfill? args)) eff-enabled?)
      :model-path mpath :model-id eff-model-id
      :batch-size (long (or (:batch-size args) default-batch-size))
      :max-pending (long (or (:max-pending args) default-max-pending))
      :idle-close-ms (long (or (:idle-close-ms args) default-idle-close-ms))
      :embed-fn (or custom-embed (:embed! client))
      :close-fn (or (:close-fn args) (:close! client))
      :alive-fn (or (:alive-fn args) (:alive? client))
      :pending {} :pending-order 0 :overflow? false :more-backfill? false :backfill-scanned? (boolean (:skip-boot-scan? args))
      :interactive-queue [] :in-flight nil :req-seq 0 :batches-done 0 :queries-done 0
      :errors 0 :consecutive-errors 0 :last-error nil :worker-alive? false :idle-token 0
      ::flow/in-ports {:worker-res worker-res-ch :idle-tick idle-tick-ch}}))
  ([st transition]
   (when (= transition ::flow/stop) (close-worker-async! (:close-fn st)))
   st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (case cid
       :db (handle-db st msg)
       :req (handle-req st msg)
       :worker-res (handle-worker-res st msg)
       :idle-tick (handle-idle-tick st msg)
       [st nil]))))

(defn proc-spec
  "Builds the `:vectors` Flow proc entry map for `seon.main/start-flow-instance!`."
  {:malli/schema [:=> [:cat :seon.host/conn :map] :map] :cost/ticks 500000}
  [conn {:keys [disable-vectors? auto-backfill? vectors-opts]}]
  {:proc (flow/process #'vectors-proc)
   :args (merge {:conn conn
                 :enabled? (not disable-vectors?)
                 :auto-backfill? (boolean (and (not disable-vectors?) auto-backfill?))}
                vectors-opts)
   :chan-opts {:db {:buf-or-n (a/sliding-buffer 16)} :req {:buf-or-n 32}}})
