(ns seon.health.efficiency.eval
  "Commit-anchored agent eval efficiency warnings (:low-cache-hit, :prefix-cache-bust, :heavy-eval)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [datahike.datom :as dd]
            [datahike.index.interface :as di]
            [seon.ctx.diff :as diff]
            [seon.store :as store]))

(defonce ^:private ^:seon.flow/edge eval-eff-cache (store/lru-cache 16))

(defonce ^:private ^:seon.flow/edge eval-eff-anchor (atom {}))

(defn rec-reads!
  {:malli/schema [:=> [:cat [:sequential :keyword]] :any], :seon.flow/edge true}
  [attrs]
  (when di/*reads*
    (doseq [a attrs] (di/record-read! (dd/datom 0 a nil 0) (dd/datom Long/MAX_VALUE a nil Long/MAX_VALUE)))))

(defn eav1
  {:malli/schema [:=> [:cat :seon.host/db :int :keyword] :any]}
  [db eid attr]
  (:v (first (d/datoms db :eavt eid attr))))

(def ^:private code-edit-attrs
  #{:seon.code.form/key :seon.code.form/src :seon.code.form/hash :seon.code.var/name :seon.code.ns/name})

(defn- suppress-heavy-eval?
  {:seon.flow/edge true}
  [branch-db br aid opts]
  (let [cdb (or (:main-db opts) branch-db)
        res? (when (and cdb aid (contains? (:schema cdb) :seon.agent.call/callee))
               (let [sts (mapv #(eav1 cdb (:e %) :seon.agent.call/status) (d/datoms cdb :avet :seon.agent.call/callee aid))]
                 (and (seq sts) (some #(= :resolved %) sts) (not-any? #(= :pending %) sts))))
        no-ed? (when (not= br :main)
                 (if (contains? opts :branch-edited)
                   (empty? (:branch-edited opts))
                   (if-not (and (instance? datahike.db.DB branch-db) (:store branch-db))
                     true
                     (let [mdb (or (:main-db opts) (try (store/head (:store branch-db) :main) (catch Throwable _ nil)))
                           base (when mdb (diff/fast-merge-base mdb branch-db)) cid (store/live-cid branch-db)]
                       (not (and base cid (not= base cid)
                                 (some #(contains? code-edit-attrs (:a %)) (diff/branch-changes branch-db base cid))))))))]
    (boolean (or res? no-ed?))))

(def ^:private eval-pull-pat
  '[:seon.agent.eval/agent :seon.agent.eval/handle :seon.agent.eval/turn :seon.agent.eval/call-index
    :seon.agent.eval/prefix-hash :seon.agent.eval/ms :seon.agent.eval/ticks :seon.agent.eval/bytes
    :seon.agent.eval/error :seon.agent.eval/cache-hit-tokens :seon.agent.eval/cache-miss-tokens])

(defn- attr-long [m k]
  (long (or (get m k) 0)))

(defn- eval-warnings-for-agent [branch-db br aid es opts]
  (let [mk (fn [cls subj msg extra]
             (merge {:class cls :severity :warning :kind :live :agent aid :subject subj :branch br :message msg} extra))
        hit-fn #(attr-long % :seon.agent.eval/cache-hit-tokens)
        miss-fn #(attr-long % :seon.agent.eval/cache-miss-tokens)
        ces (filter #(or (pos? (hit-fn %)) (pos? (miss-fn %))) es)
        hits (reduce + 0 (map hit-fn ces))
        tot (+ hits (reduce + 0 (map miss-fn ces)))
        cw (when (and (>= (count ces) 2) (>= tot 2000) (< (/ (double hits) tot) 0.60))
             [(mk :low-cache-hit (str aid)
                  (str "Low KV-cache hit on " aid ": " (Math/round (* 100.0 (/ (double hits) tot))) "% (" hits "/" tot " tok).") nil)])
        trows (->> es
                   (filter (fn [{:seon.agent.eval/keys [call-index prefix-hash cache-hit-tokens]}]
                             (and (or (nil? call-index) (zero? (long call-index))) (or prefix-hash cache-hit-tokens))))
                   (sort-by #(attr-long % :seon.agent.eval/turn)))
        busts (vec (keep (fn [[a b]]
                           (let [n1 (attr-long a :seon.agent.eval/turn) n2 (attr-long b :seon.agent.eval/turn)
                                 p1 (:seon.agent.eval/prefix-hash a) p2 (:seon.agent.eval/prefix-hash b)
                                 h1 (:seon.agent.eval/cache-hit-tokens a) h2 (:seon.agent.eval/cache-hit-tokens b)
                                 hc? (and (some? p1) (some? p2) (not= p1 p2))
                                 hd? (and (number? h1) (number? h2) (> (long h1) 512) (< (long h2) (- (long h1) 256)))]
                             (when (and (= n2 (inc n1)) (or hc? hd?))
                               {:turn n2 :prev-hash p1 :cur-hash p2 :prev-hit h1 :cur-hit h2
                                :reason (if hc? :prefix-hash-changed :cache-hit-drop)})))
                         (partition 2 1 trows)))
        bw (when (seq busts)
             (let [{:keys [turn reason prev-hash cur-hash prev-hit cur-hit]} (peek busts)]
               [(mk :prefix-cache-bust (str aid "/t" turn)
                    (str "KV-cache prefix bust on " aid " (" (count busts) "x, t" turn
                         (if (= :prefix-hash-changed reason) (str ": " prev-hash " -> " cur-hash) (str ": " prev-hit " -> " cur-hit " tok")) ").")
                    {:bust-count (count busts)})]))
        heavy (filterv #(or (> (attr-long % :seon.agent.eval/bytes) 134217728)
                            (> (attr-long % :seon.agent.eval/ticks) 50000000)
                            (str/includes? (str (:seon.agent.eval/error %)) "Eval exceeded")) es)
        hw (when (and (seq heavy) (not (suppress-heavy-eval? branch-db br aid opts)))
             (let [n (count heavy) mb (quot (reduce max 0 (map #(attr-long % :seon.agent.eval/bytes) heavy)) 1048576)]
               [(mk :heavy-eval (str aid)
                    (str "Telemetry notice (non-blocking, immutable eval ledger — do not retry or retract): " n " heavy evals on branch (max " mb " MB)")
                    {:count n :max-mb mb})]))]
    (vec (concat cw bw hw))))

(defn- scan-eval-attr! [db sm ^java.util.HashSet eids attr pred]
  (when (contains? sm attr)
    (doseq [dt (d/datoms db :aevt attr) :when (pred (:v dt))]
      (.add eids (:e dt)))))

(defn- scan-interesting-evals [db]
  (let [sm (:schema db)
        inactive-aids (let [id-by-e (when (contains? sm :seon.agent/id)
                                      (into {} (map (fn [dt] [(:e dt) (:v dt)])) (d/datoms db :aevt :seon.agent/id)))
                            inact (java.util.HashSet. 64)]
                        (when (seq id-by-e)
                          (when (contains? sm :seon.agent/archived?)
                            (doseq [dt (d/datoms db :aevt :seon.agent/archived?) :when (true? (:v dt))]
                              (when-let [aid (get id-by-e (:e dt))] (.add inact aid))))
                          (when (contains? sm :seon.agent/merged-tx)
                            (doseq [dt (d/datoms db :aevt :seon.agent/merged-tx) :when (and (integer? (:v dt)) (pos? (long (:v dt))))]
                              (when-let [aid (get id-by-e (:e dt))] (.add inact aid)))))
                        inact)
        eids (java.util.HashSet. 128)
        pos-l? #(pos? (long (or % 0)))]
    (scan-eval-attr! db sm eids :seon.agent.eval/cache-hit-tokens pos-l?)
    (scan-eval-attr! db sm eids :seon.agent.eval/cache-miss-tokens pos-l?)
    (scan-eval-attr! db sm eids :seon.agent.eval/prefix-hash some?)
    (scan-eval-attr! db sm eids :seon.agent.eval/bytes #(> (long (or % 0)) 134217728))
    (scan-eval-attr! db sm eids :seon.agent.eval/ticks #(> (long (or % 0)) 50000000))
    (scan-eval-attr! db sm eids :seon.agent.eval/error #(str/includes? (str %) "Eval exceeded"))
    (if (zero? (.size inactive-aids))
      (mapv #(d/pull db eval-pull-pat %) eids)
      (into []
            (keep (fn [e]
                    (let [aid (eav1 db e :seon.agent.eval/agent)]
                      (when-not (and aid (or (= "repl" (namespace aid)) (.contains inactive-aids aid)))
                      (d/pull db eval-pull-pat e)))))
            eids))))

(defn ^:seon.flow/edge anchored-branch-cache!
  {:malli/schema [:=> [:cat :seon.host/db :any :any [:sequential :keyword] :seon.host/fn :seon.host/fn :keyword] :any], :seon.flow/edge true}
  [db cache-atom anchor-atom attrs advance-fn full-fn res-key]
  (let [sid (store/store-id db) br (get-in db [:config :branch] :main)
        cid (store/live-cid db) mtx (long (:max-tx db 0))]
    (rec-reads! attrs)
    (store/lru-get! cache-atom 16 [sid br cid mtx]
      (fn []
        (binding [di/*reads* nil]
          (let [ak [sid br] anc (get @anchor-atom ak)
                res (or (when (and anc (:cid anc) (not= cid (:cid anc))
                                   (< 0 (- mtx (long (:max-tx anc 0))) 256)
                                   (diff/ancestor-within? db (:cid anc) cid 16))
                          (advance-fn db br anc cid))
                        (full-fn db br))
                entry (assoc res :cid cid :max-tx mtx)]
            (swap! anchor-atom #(let [b (if (> (count %) 16) {} %)]
                                  (if (>= mtx (long (:max-tx (get b ak) 0))) (assoc b ak entry) b)))
            (get entry res-key)))))))

(defn- pull-agent-evals [db aid]
  (mapv #(d/pull db eval-pull-pat (:e %)) (d/datoms db :avet :seon.agent.eval/agent aid)))

(defn- ^:seon.flow/edge try-advance-eval-eff [bdb br anc cid]
  (try
    (let [dirty (volatile! #{}) full? (volatile! false)]
      (doseq [dt (d/changes bdb (:cid anc) cid)
              :let [ra (:a dt) attr (if (keyword? ra) ra (or (get-in bdb [:ref-ident-map ra]) ra))]]
        (cond
          (and (keyword? attr) (= "seon.agent.eval" (namespace attr)))
          (if (:added dt) (vswap! dirty conj (:e dt)) (vreset! full? true))
          (or (= :seon.agent.call/status attr) (contains? code-edit-attrs attr))
          (vreset! full? true)))
      (when-not @full?
        (if (empty? @dirty)
          anc
          (let [aids (into #{} (keep #(eav1 bdb % :seon.agent.eval/agent)) @dirty)
                by-ag (reduce (fn [m aid]
                                (let [es (pull-agent-evals bdb aid)
                                      ws (eval-warnings-for-agent bdb br aid es nil)]
                                  (if (seq ws) (assoc m aid ws) (dissoc m aid))))
                              (:by-ag anc) aids)]
            {:by-ag by-ag :out (vec (mapcat val by-ag))}))))
    (catch Throwable _ nil)))

(defn eval-efficiency-warnings
  "Inspects agent evals on `branch-db` for `:low-cache-hit`, `:prefix-cache-bust`, and `:heavy-eval`."
  {:malli/schema [:function [:=> [:cat :any] [:vector :seon.health/problem]] [:=> [:cat :any :any] [:vector :seon.health/problem]] [:=> [:cat :any :any :any] [:vector :seon.health/problem]] [:=> [:cat :any :any :any [:maybe :map]] [:vector :seon.health/problem]]]}
  ([branch-db] (eval-efficiency-warnings branch-db nil nil nil))
  ([branch-db agent-id] (eval-efficiency-warnings branch-db agent-id nil nil))
  ([branch-db agent-id prefetched-evals] (eval-efficiency-warnings branch-db agent-id prefetched-evals nil))
  ([branch-db agent-id prefetched-evals opts]
   (if-not (or (seq prefetched-evals) (and branch-db (contains? (:schema branch-db) :seon.agent.eval/agent)))
     []
     (if (and (nil? agent-id) (nil? prefetched-evals) (empty? opts) branch-db (store/live-cid branch-db))
       (let [full-fn (fn [db br]
                       (let [by-ag (reduce-kv (fn [m aid es]
                                                (if-let [ws (seq (eval-warnings-for-agent db br aid es nil))]
                                                  (assoc m aid (vec ws)) m))
                                              {} (group-by :seon.agent.eval/agent (scan-interesting-evals db)))]
                         {:by-ag by-ag :out (vec (mapcat val by-ag))}))]
         (anchored-branch-cache! branch-db eval-eff-cache eval-eff-anchor
                                 [:seon.agent.eval/agent :seon.agent.call/status :seon.code.form/src]
                                 try-advance-eval-eff full-fn :out))
       (let [br (if branch-db (get-in branch-db [:config :branch] :main) :main)
             evals (cond
                     (some? prefetched-evals)
                     (if agent-id (filterv #(or (= agent-id (:seon.agent.eval/agent %)) (nil? (:seon.agent.eval/agent %))) prefetched-evals) (vec prefetched-evals))
                     agent-id (pull-agent-evals branch-db agent-id)
                     :else (scan-interesting-evals branch-db))]
         (vec (mapcat (fn [[aid es]] (eval-warnings-for-agent branch-db br aid es opts))
                      (group-by #(or (:seon.agent.eval/agent %) agent-id) evals))))))))