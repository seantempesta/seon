(ns seon.health.efficiency
  "Efficiency & telemetry advisories for System Health (static, test, and eval efficiency gates)."
  (:require [clojure.core.cache :as cache]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.datom :as dd]
            [datahike.index.interface :as di]
            [konserve.core :as k]
            [seon.code.parse :as parse]
            [seon.ctx.diff :as diff]
            [seon.store :as store]))

(defn ns-in-scope?
  "True when `nsym` belongs to `scope` (exact match or descendant `scope.*`)."
  {:malli/schema [:=> [:cat [:maybe :symbol] [:maybe :symbol]] [:maybe :boolean]] :cost/ticks 50000}
  [scope nsym]
  (when (and scope nsym)
    (let [sc (name scope) ns-s (name nsym)]
      (or (= ns-s sc) (str/starts-with? ns-s (str sc "."))))))

(defonce ^:private ^:seon.flow/edge form-static-cache (store/lru-cache 2048))
(defonce ^:private ^:seon.flow/edge ns-unused-req-cache (store/lru-cache 512))
(defonce ^:private ^:seon.flow/edge stat-eff-cache (store/lru-cache 16))
(defonce ^:private ^:seon.flow/edge test-eff-cache (store/lru-cache 16))
(defonce ^:private ^:seon.flow/edge eval-eff-cache (store/lru-cache 16))
(defonce ^:private ^:seon.flow/edge eval-eff-anchor (atom {}))
(defonce ^:private ^:seon.flow/edge test-snap-cache (store/lru-cache 16))
(defonce ^:private ^:seon.flow/edge test-snap-anchor (atom {}))

(defn- rec-reads! [attrs]
  (when di/*reads*
    (doseq [a attrs] (di/record-read! (dd/datom 0 a nil 0) (dd/datom Long/MAX_VALUE a nil Long/MAX_VALUE)))))

(defn- safe-read-form [s] (try (read-string s) (catch RuntimeException _ nil)))

(defn- resolve-src-value [db v]
  (if (dd/lazy-value? v)
    (let [st (:store db)]
      (or (when st (let [r (k/get st (dd/lazy-key v) ::missing {:sync? true})] (when-not (identical? ::missing r) (str r))))
          (str (dd/value-of v))))
    (str v)))

(defn- fetch-missing-srcs [db ^java.util.HashSet needed]
  (let [hm (java.util.HashMap. (max 16 (.size needed)))]
    (when (pos? (.size needed))
      (if (<= (.size needed) 150)
        (doseq [e needed] (when-let [dt (first (d/datoms db :eavt e :seon.code.form/src))] (.put hm e (str (:v dt)))))
        (binding [di/*read-key-attrs* #{}]
          (doseq [dt (d/datoms db :aevt :seon.code.form/src) :let [e (:e dt)] :when (.contains needed e)]
            (.put hm e (resolve-src-value db (:v dt)))))))
    hm))

(defn- emit-form-advisories! [br nsym k {:keys [chars defn? lines nested-scan?]} nested large-fns]
  (let [base (cond-> {:kind :static :ns nsym :subject k :branch br} (and k (not (str/includes? k "#"))) (assoc :var (symbol k)))]
    (when (and k defn?)
      (when (and (= 1 lines) (> chars 280))
        (vswap! large-fns conj (assoc base :class :minified-form :severity :error :message (str "Minified 1-line fn " k " (" chars " chars)."))))
      (when (or (> lines 120) (> chars 7200))
        (vswap! large-fns conj (assoc base :class :large-function :severity :warning :message (str "Large fn " k " (" lines "L, " chars " chars > 120L/7.2k).")))))
    (when (and k nested-scan? (not (contains? #{"seon.health/schema-violations" "seon.health.static/schema-violations"} k)))
      (vswap! nested conj (assoc base :class :nested-db-scan :severity :warning :message (str "Nested full DB scan in " k ". Hoist or use `:avet`."))))))

(defn- collect-forms-by-ns [db scope]
  (if scope
    (into {}
          (for [nsym (->> (d/datoms db :aevt :seon.code.ns/name) (map :v) (filter #(and (not (str/ends-with? (name %) "-test")) (ns-in-scope? scope %))))
                :let [fs (mapv #(let [p (d/pull db '[:seon.code.form/key :seon.code.form/hash :seon.code.form/chars :seon.code.form/lines :seon.code.form/nested-scan?] (:e %))]
                                  {:e (:e %) :key (:seon.code.form/key p) :hash (:seon.code.form/hash p) :chars (:seon.code.form/chars p) :lines (:seon.code.form/lines p) :nested-scan? (:seon.code.form/nested-scan? p)})
                               (d/datoms db :avet :seon.code.form/ns nsym))]
                :when (seq fs)]
            [nsym fs]))
    (let [ek (java.util.HashMap. 1400) eh (java.util.HashMap. 1400) by-ns (java.util.HashMap. 160)]
      (when (contains? (:schema db) :seon.code.form/key) (doseq [dt (d/datoms db :aevt :seon.code.form/key)] (.put ek (:e dt) (:v dt))))
      (when (contains? (:schema db) :seon.code.form/hash) (doseq [dt (d/datoms db :aevt :seon.code.form/hash)] (.put eh (:e dt) (:v dt))))
      (doseq [dt (d/datoms db :aevt :seon.code.form/ns) :let [nsym (:v dt) e (:e dt)] :when (and nsym (not (str/ends-with? (name nsym) "-test")))]
        (let [arr (or (.get by-ns nsym) (let [a (java.util.ArrayList. 16)] (.put by-ns nsym a) a))]
          (.add ^java.util.ArrayList arr {:e e :key (.get ek e) :hash (.get eh e)})))
      (into {} (map (fn [^java.util.Map$Entry en] [(.getKey en) (vec ^java.util.ArrayList (.getValue en))])) (.entrySet by-ns)))))

(defn- batch-cache-put! [ca thr entries]
  (when (seq entries)
    (swap! ca #(reduce-kv cache/miss (if (satisfies? cache/CacheProtocol %) % (cache/lru-cache-factory {} :threshold (long thr))) entries))))

(defn- compute-unused-requires [forms decl get-src!]
  (let [bodies (into [] (keep #(when-not (and (string? (:key %)) (str/ends-with? (:key %) "#ns")) (get-src! (:e %)))) forms)
        am (reduce-kv (fn [a al lib] (update a lib (fnil conj []) (name al))) {} (:aliases decl))
        rm (reduce-kv (fn [a rf lib] (update a lib (fnil conj []) (name rf))) {} (:refers decl))]
    (vec (sort (keep (fn [lib]
                       (let [als (get am lib) rfs (get rm lib)]
                         (when (or (seq als) (seq rfs))
                           (let [ls (str (name lib) "/") ld (str (name lib) ".")]
                             (when-not (some (fn [^String b] (or (.contains b ls) (.contains b ld) (some #(.contains b (str % "/")) als) (some #(.contains b ^String %) rfs))) bodies)
                               lib)))))
                     (:requires decl))))))

(defn- suppress-heavy-eval? [branch-db br aid opts]
  (let [cdb (or (:main-db opts) branch-db)
        res? (when (and cdb aid (contains? (:schema cdb) :seon.agent.call/callee))
               (let [sts (mapv #(:v (first (d/datoms cdb :eavt (:e %) :seon.agent.call/status))) (d/datoms cdb :avet :seon.agent.call/callee aid))]
                 (and (seq sts) (some #(= :resolved %) sts) (not-any? #(= :pending %) sts))))
        no-ed? (when (not= br :main)
                 (if (contains? opts :branch-edited)
                   (empty? (:branch-edited opts))
                   (if-not (and (instance? datahike.db.DB branch-db) (:store branch-db))
                     true
                     (let [mdb (or (:main-db opts) (try (store/head (:store branch-db) :main) (catch Throwable _ nil)))
                           base (when mdb (diff/fast-merge-base mdb branch-db)) cid (store/live-cid branch-db)]
                       (not (and base cid (not= base cid)
                                 (some #(contains? #{:seon.code.form/key :seon.code.form/src :seon.code.form/hash :seon.code.var/name :seon.code.ns/name} (:a %))
                                       (diff/branch-changes branch-db base cid))))))))]
    (boolean (or res? no-ed?))))

(def ^:private eval-pull-pat
  '[:seon.agent.eval/agent :seon.agent.eval/handle :seon.agent.eval/turn :seon.agent.eval/call-index
    :seon.agent.eval/prefix-hash :seon.agent.eval/ms :seon.agent.eval/ticks :seon.agent.eval/bytes
    :seon.agent.eval/error :seon.agent.eval/cache-hit-tokens :seon.agent.eval/cache-miss-tokens])

(defn- eval-warnings-for-agent [branch-db br aid es opts]
  (let [ces (filter #(or (pos? (long (or (:seon.agent.eval/cache-hit-tokens %) 0))) (pos? (long (or (:seon.agent.eval/cache-miss-tokens %) 0)))) es)
        hits (reduce + 0 (map #(long (or (:seon.agent.eval/cache-hit-tokens %) 0)) ces))
        tot (+ hits (reduce + 0 (map #(long (or (:seon.agent.eval/cache-miss-tokens %) 0)) ces)))
        cw (when (and (>= (count ces) 2) (>= tot 2000) (< (/ (double hits) tot) 0.60))
             [{:class :low-cache-hit :severity :warning :kind :live :agent aid :subject (str aid) :branch br
               :message (str "Low KV-cache hit on " aid ": " (Math/round (* 100.0 (/ (double hits) tot))) "% (" hits "/" tot " tok).")}])
        trows (->> es (filter #(let [ci (:seon.agent.eval/call-index %)] (or (nil? ci) (zero? (long ci)))))
                   (filter #(or (:seon.agent.eval/prefix-hash %) (:seon.agent.eval/cache-hit-tokens %)))
                   (sort-by #(long (or (:seon.agent.eval/turn %) 0))))
        busts (vec (keep (fn [[a b]]
                           (let [t1 (long (or (:seon.agent.eval/turn a) 0)) t2 (long (or (:seon.agent.eval/turn b) 0))
                                 p1 (:seon.agent.eval/prefix-hash a) p2 (:seon.agent.eval/prefix-hash b)
                                 h1 (:seon.agent.eval/cache-hit-tokens a) h2 (:seon.agent.eval/cache-hit-tokens b)
                                 hc? (and (some? p1) (some? p2) (not= p1 p2))
                                 hd? (and (number? h1) (number? h2) (> (long h1) 512) (< (long h2) (- (long h1) 256)))]
                             (when (and (= t2 (inc t1)) (or hc? hd?))
                               {:turn t2 :prev-hash p1 :cur-hash p2 :prev-hit h1 :cur-hit h2 :reason (if hc? :prefix-hash-changed :cache-hit-drop)})))
                         (partition 2 1 trows)))
        bw (when (seq busts)
             (let [b (peek busts)]
               [{:class :prefix-cache-bust :severity :warning :kind :live :agent aid :subject (str aid "/t" (:turn b)) :branch br :bust-count (count busts)
                 :message (str "KV-cache prefix bust on " aid " (" (count busts) "x, t" (:turn b)
                               (if (= :prefix-hash-changed (:reason b)) (str ": " (:prev-hash b) " -> " (:cur-hash b)) (str ": " (:prev-hit b) " -> " (:cur-hit b) " tok")) ").")}]))
        heavy (filterv #(or (> (long (or (:seon.agent.eval/bytes %) 0)) 134217728)
                            (> (long (or (:seon.agent.eval/ticks %) 0)) 50000000)
                            (str/includes? (str (:seon.agent.eval/error %)) "Eval exceeded")) es)
        hw (when (and (seq heavy) (not (suppress-heavy-eval? branch-db br aid opts)))
             (let [n (count heavy) mb (quot (reduce max 0 (map #(long (or (:seon.agent.eval/bytes %) 0)) heavy)) 1048576)]
               [{:class :heavy-eval :severity :warning :kind :live :agent aid :subject (str aid) :branch br :count n :max-mb mb
                 :message (str "Telemetry notice (non-blocking, immutable eval ledger — do not retry or retract): " n " heavy evals on branch (max " mb " MB)")}]))]
    (vec (concat cw bw hw))))

(defn- ^:seon.flow/edge try-advance-eval-eff [bdb br anc cid]
  (try
    (let [dirty (volatile! #{}) full? (volatile! false)]
      (doseq [dt (d/changes bdb (:cid anc) cid) :let [a (:a dt)]]
        (cond (and (keyword? a) (= "seon.agent.eval" (namespace a)))
              (if (:added dt) (vswap! dirty conj (:e dt)) (vreset! full? true))
              (= :seon.agent.call/status a) (vreset! full? true)))
      (when-not @full?
        (if (empty? @dirty) anc
            (let [aids (into #{} (keep #(:v (first (d/datoms bdb :eavt % :seon.agent.eval/agent)))) @dirty)
                  by-ag' (reduce (fn [m aid]
                                   (let [es (mapv #(d/pull bdb eval-pull-pat (:e %)) (d/datoms bdb :avet :seon.agent.eval/agent aid))
                                         ws (eval-warnings-for-agent bdb br aid es nil)]
                                     (if (seq ws) (assoc m aid ws) (dissoc m aid))))
                                 (:by-ag anc) aids)]
              {:by-ag by-ag' :out (vec (mapcat val by-ag'))}))))
    (catch Throwable _ nil)))

(defn- scan-interesting-evals [db]
  (let [sm (:schema db)
        eids (java.util.HashSet. 128)]
    (when (contains? sm :seon.agent.eval/cache-hit-tokens)
      (doseq [dt (d/datoms db :aevt :seon.agent.eval/cache-hit-tokens) :when (pos? (long (or (:v dt) 0)))] (.add eids (:e dt))))
    (when (contains? sm :seon.agent.eval/cache-miss-tokens)
      (doseq [dt (d/datoms db :aevt :seon.agent.eval/cache-miss-tokens) :when (pos? (long (or (:v dt) 0)))] (.add eids (:e dt))))
    (when (contains? sm :seon.agent.eval/prefix-hash)
      (doseq [dt (d/datoms db :aevt :seon.agent.eval/prefix-hash) :when (some? (:v dt))] (.add eids (:e dt))))
    (when (contains? sm :seon.agent.eval/bytes)
      (doseq [dt (d/datoms db :aevt :seon.agent.eval/bytes) :when (> (long (or (:v dt) 0)) 134217728)] (.add eids (:e dt))))
    (when (contains? sm :seon.agent.eval/ticks)
      (doseq [dt (d/datoms db :aevt :seon.agent.eval/ticks) :when (> (long (or (:v dt) 0)) 50000000)] (.add eids (:e dt))))
    (when (contains? sm :seon.agent.eval/error)
      (doseq [dt (d/datoms db :aevt :seon.agent.eval/error) :when (str/includes? (str (:v dt)) "Eval exceeded")] (.add eids (:e dt))))
    (mapv #(d/pull db eval-pull-pat %) eids)))

(defn eval-efficiency-warnings
  "Inspects agent evals on `branch-db` for `:low-cache-hit`, `:prefix-cache-bust`, and `:heavy-eval`."
  {:malli/schema [:function [:=> [:cat :any] [:vector :seon.health/problem]] [:=> [:cat :any :any] [:vector :seon.health/problem]] [:=> [:cat :any :any :any] [:vector :seon.health/problem]] [:=> [:cat :any :any :any [:maybe :map]] [:vector :seon.health/problem]]]}
  ([branch-db] (eval-efficiency-warnings branch-db nil nil nil))
  ([branch-db agent-id] (eval-efficiency-warnings branch-db agent-id nil nil))
  ([branch-db agent-id prefetched-evals] (eval-efficiency-warnings branch-db agent-id prefetched-evals nil))
  ([branch-db agent-id prefetched-evals opts]
   (if-not (or (seq prefetched-evals) (and branch-db (contains? (:schema branch-db) :seon.agent.eval/agent)))
     []
     (let [br (if branch-db (get-in branch-db [:config :branch] :main) :main)
           cid (when (and (nil? agent-id) (nil? prefetched-evals) (empty? opts) branch-db) (store/live-cid branch-db))]
       (if cid
         (let [sid (store/store-id branch-db) mtx (long (:max-tx branch-db 0))]
           (rec-reads! [:seon.agent.eval/agent])
           (store/lru-get! eval-eff-cache 16 [sid br cid mtx]
             (fn []
               (binding [di/*reads* nil]
                 (let [ak [sid br] anc (get @eval-eff-anchor ak)
                       res (or (when (and anc (:cid anc) (not= cid (:cid anc)) (< 0 (- mtx (long (:max-tx anc 0))) 256))
                                 (try-advance-eval-eff branch-db br anc cid))
                               (let [evs (scan-interesting-evals branch-db)
                                     by-ag (reduce-kv (fn [m a es] (let [ws (eval-warnings-for-agent branch-db br a es nil)] (if (seq ws) (assoc m a ws) m))) {} (group-by :seon.agent.eval/agent evs))]
                                 {:by-ag by-ag :out (vec (mapcat val by-ag))}))
                       entry (assoc res :cid cid :max-tx mtx)]
                   (swap! eval-eff-anchor #(let [b (if (> (count %) 16) {} %)] (if (>= mtx (long (:max-tx (get b ak) 0))) (assoc b ak entry) b)))
                   (:out entry))))))
         (let [evals (if (some? prefetched-evals)
                       (if agent-id (filterv #(or (= agent-id (:seon.agent.eval/agent %)) (nil? (:seon.agent.eval/agent %))) prefetched-evals) (vec prefetched-evals))
                       (if agent-id
                         (mapv #(d/pull branch-db eval-pull-pat (:e %)) (d/datoms branch-db :avet :seon.agent.eval/agent agent-id))
                         (scan-interesting-evals branch-db)))]
           (vec (mapcat (fn [[aid es]] (eval-warnings-for-agent branch-db br aid es opts)) (group-by #(or (:seon.agent.eval/agent %) agent-id) evals)))))))))

(defn- top2-map->hm [m]
  (let [hm (java.util.HashMap. (max 16 (* 2 (count m))))]
    (doseq [[k [e1 t1 e2 t2]] m] (.put hm k (long-array [(long e1) (long t1) (long e2) (long t2)])))
    hm))

(defn- update-top2-slot [cur e tx]
  (if (nil? cur) [e tx -1 -1]
      (let [[e1 t1 e2 t2] cur]
        (cond (or (> tx t1) (and (= tx t1) (> e e1))) [e tx e1 t1]
              (or (> tx t2) (and (= tx t2) (> e e2))) [e1 t1 e tx] :else cur))))

(defn- fold-test-datoms [{:keys [vmap top2-m vg ft vc vm pc pm]} var-dts pass-dts]
  (let [[vmap' vc' vm'] (reduce (fn [[m c mx] dt] (let [t (long (:tx dt))] [(assoc m (:e dt) (:v dt)) (inc c) (max mx t)])) [vmap vc vm] var-dts)
        [t2' vg' ft' pc' pm']
        (reduce (fn [[t2 g f c mx] dt]
                  (let [e (:e dt) tx (long (:tx dt))]
                    (if (:v dt)
                      (if-let [v (get vmap' e)]
                        [(assoc t2 v (update-top2-slot (get t2 v) e tx))
                         (let [og (get g v)] (if (or (nil? og) (> tx (long og))) (assoc g v tx) g)) f (inc c) (max mx tx)]
                        [t2 g f (inc c) (max mx tx)])
                      [t2 g (assoc f e tx) (inc c) (max mx tx)])))
                [top2-m vg ft pc pm] pass-dts)]
    {:snap {:top2 (top2-map->hm t2') :var-sig [vc' vm'] :pass-sig [pc' pm'] :supersession {:var->green-tx vg' :eid->pass-tx ft'}}
     :state {:vmap vmap' :top2-m t2' :vg vg' :ft ft' :vc vc' :vm vm' :pc pc' :pm pm'}}))

(defn- build-full-test-snap [db]
  (fold-test-datoms {:vmap {} :top2-m {} :vg {} :ft {} :vc 0 :vm 0 :pc 0 :pm 0}
                    (d/datoms db :aevt :seon.test/var)
                    (when (contains? (:schema db) :seon.test/pass?) (d/datoms db :aevt :seon.test/pass?))))

(defn- ^:seon.flow/edge try-advance-test-snap [db anc cid]
  (try
    (let [vr (get-in db [:ident-ref-map :seon.test/var]) pr (get-in db [:ident-ref-map :seon.test/pass?])
          va? #(or (= % :seon.test/var) (and vr (= % vr))) pa? #(or (= % :seon.test/pass?) (and pr (= % pr)))
          vars (volatile! []) passes (volatile! []) ret? (volatile! false)]
      (doseq [dt (d/changes db (:cid anc) cid) :let [a (:a dt)] :when (or (va? a) (pa? a))]
        (if-not (:added dt) (vreset! ret? true) (if (va? a) (vswap! vars conj dt) (vswap! passes conj dt))))
      (when-not @ret? (if (and (empty? @vars) (empty? @passes)) anc (fold-test-datoms (:state anc) @vars @passes))))
    (catch Throwable _ nil)))

(defn test-snapshot
  "Commit-anchored incremental snapshot over `:seon.test/var` + `:seon.test/pass?`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :map] :cost/ticks 25000000}
  [db]
  (if-not (and db (contains? (:schema db) :seon.test/var))
    {:top2 (java.util.HashMap.) :var-sig [0 0] :pass-sig [0 0] :supersession {:var->green-tx {} :eid->pass-tx {}}}
    (if-let [cid (store/live-cid db)]
      (let [sid (store/store-id db) br (get-in db [:config :branch] :main) mtx (long (:max-tx db 0))]
        (rec-reads! [:seon.test/var :seon.test/pass?])
        (store/lru-get! test-snap-cache 16 [sid br cid mtx]
          (fn []
            (binding [di/*reads* nil]
              (let [ak [sid br] anc (get @test-snap-anchor ak)
                    res (or (when (and anc (:cid anc) (not= cid (:cid anc)) (< 0 (- mtx (long (:max-tx anc 0))) 256))
                              (try-advance-test-snap db anc cid))
                            (build-full-test-snap db))
                    entry (assoc res :cid cid :max-tx mtx)]
                (swap! test-snap-anchor #(let [b (if (> (count %) 16) {} %)] (if (>= mtx (long (:max-tx (get b ak) 0))) (assoc b ak entry) b)))
                (:snap entry))))))
      (:snap (build-full-test-snap db)))))

(defn- compute-test-eff [db br top2]
  (let [sm (:schema db)
        hm? (contains? sm :seon.test/ms) ht? (contains? sm :seon.test/ticks) hb? (contains? sm :seon.test/bytes)
        rd #(hash-map :ms (double (or (when hm? (:v (first (d/datoms db :eavt % :seon.test/ms)))) 0.0))
                      :ticks (long (or (when ht? (:v (first (d/datoms db :eavt % :seon.test/ticks)))) 0))
                      :bytes (long (or (when hb? (:v (first (d/datoms db :eavt % :seon.test/bytes)))) 0)))
        ordered (->> (.entrySet ^java.util.HashMap top2)
                     (filter (fn [^java.util.Map$Entry en] (let [s (.getKey en)] (and (symbol? s) (or (str/ends-with? (name s) "-test") (and (namespace s) (str/ends-with? (namespace s) "-test")))))))
                     (sort-by (fn [^java.util.Map$Entry en] (aget ^longs (.getValue en) 0))))
        cands (volatile! []) out (volatile! [])]
    (doseq [^java.util.Map$Entry en ordered]
      (let [vs (.getKey en) ^longs sl (.getValue en) cur (rd (aget sl 0))]
        (when (or (> (:ms cur) 200.0) (> (:ticks cur) 5000000) (> (:bytes cur) 33554432))
          (let [nsym (when (namespace vs) (symbol (namespace vs))) e2 (aget sl 2)]
            (when (> (:ms cur) 1000.0)
              (vswap! out conj {:class :slow-test :severity :warning :kind :live :ns nsym :var vs :subject (str vs) :branch br
                                :message (str "Slow test " vs ": " (Math/round (:ms cur)) " ms, " (quot (:bytes cur) 1048576) " MB (> 1000 ms).")}))
            (when (pos? e2) (vswap! cands conj [e2 vs nsym cur]))))))
    (doseq [[e2 vs nsym cur] (sort-by first @cands)]
      (let [prev (rd e2)
            tr (when (pos? (:ticks prev)) (/ (double (:ticks cur)) (:ticks prev)))
            bratio (when (pos? (:bytes prev)) (/ (double (:bytes cur)) (:bytes prev)))
            mr (when (> (:ms prev) 20.0) (/ (double (:ms cur)) (:ms prev))) dms (- (:ms cur) (:ms prev))]
        (when (or (and tr (> tr 2.5) (> (- (:ticks cur) (:ticks prev)) 5000000))
                  (and bratio (> bratio 2.5) (> (- (:bytes cur) (:bytes prev)) 33554432))
                  (and mr (> mr 2.5) (> dms 200.0)))
          (vswap! out conj {:class :cost-regression :severity :warning :kind :live :ns nsym :var vs :subject (str vs) :branch br
                            :message (str "Cost regression in " vs ": " (Math/round (:ms prev)) " ms -> " (Math/round (:ms cur)) " ms.")}))))
    (vec (sort-by :subject @out))))

(defn test-efficiency-warnings
  "Checks latest passing test verdicts per `:seon.test/var` for `:slow-test` and `:cost-regression`."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :seon.health/problem]] [:=> [:cat :seon.host/db [:maybe :map]] [:vector :seon.health/problem]]]}
  ([db] (test-efficiency-warnings db nil))
  ([db test-snap]
   (if-not (and db (contains? (:schema db) :seon.test/var))
     []
     (let [br (get-in db [:config :branch] :main) snap (or test-snap (test-snapshot db)) top2 (:top2 snap)
           ck (when (and (store/live-cid db) (:var-sig snap) (:pass-sig snap)) [(store/store-id db) br (:var-sig snap) (:pass-sig snap)])]
       (if ck
         (do (rec-reads! [:seon.test/ms :seon.test/ticks :seon.test/bytes])
             (store/lru-get! test-eff-cache 16 ck #(binding [di/*reads* nil] (compute-test-eff db br top2))))
         (compute-test-eff db br top2))))))

(defn- metrics->info [{:seon.code.form/keys [lines chars nested-scan?]}]
  (let [ln (long (or lines 0))]
    {:ns-hdr? false :chars (long (or chars 0)) :defn? (pos? ln) :lines ln :nested-scan? (true? nested-scan?)}))

(defn- analyze-form-static [ns-hdr? ^String s]
  (if ns-hdr? {:ns-hdr? true :chars (.length s) :decl (when-let [f (safe-read-form s)] (parse/parse-ns-decl f))}
      (metrics->info (parse/form-static-metrics s nil))))

(defn- load-persisted-metrics [db scope forms-by-ns ^java.util.HashSet miss]
  (let [pinfo (java.util.HashMap. (max 16 (.size miss))) sm (:schema db)]
    (when (and (pos? (.size miss)) (contains? sm :seon.code.form/chars))
      (cond
        scope
        (doseq [[_ fs] forms-by-ns {:keys [e chars lines nested-scan?]} fs :when (and (some? chars) (.contains miss e))]
          (.put pinfo e (metrics->info {:seon.code.form/chars chars :seon.code.form/lines lines :seon.code.form/nested-scan? nested-scan?})))
        (<= (.size miss) 150)
        (let [hl? (contains? sm :seon.code.form/lines) hn? (contains? sm :seon.code.form/nested-scan?)]
          (doseq [e miss]
            (when-let [ch (:v (first (d/datoms db :eavt e :seon.code.form/chars)))]
              (.put pinfo e (metrics->info {:seon.code.form/chars ch
                                            :seon.code.form/lines (when hl? (:v (first (d/datoms db :eavt e :seon.code.form/lines))))
                                            :seon.code.form/nested-scan? (when hn? (:v (first (d/datoms db :eavt e :seon.code.form/nested-scan?))))})))))
        :else
        (let [el (java.util.HashMap. 1400) en (java.util.HashMap. 1400)]
          (when (contains? sm :seon.code.form/lines) (doseq [dt (d/datoms db :aevt :seon.code.form/lines)] (.put el (:e dt) (:v dt))))
          (when (contains? sm :seon.code.form/nested-scan?) (doseq [dt (d/datoms db :aevt :seon.code.form/nested-scan?)] (.put en (:e dt) (:v dt))))
          (doseq [dt (d/datoms db :aevt :seon.code.form/chars) :let [e (:e dt)] :when (.contains miss e)]
            (.put pinfo e (metrics->info {:seon.code.form/chars (:v dt) :seon.code.form/lines (.get el e) :seon.code.form/nested-scan? (.get en e)}))))))
    pinfo))

(defn ns-forms-hash
  "Fast deterministic SHA-256 hex16 signature of sorted `[key hash]` pairs for namespace `nsym`."
  {:malli/schema [:=> [:cat :symbol [:sequential :map]] [:maybe :string]] :cost/ticks 500000}
  [nsym forms]
  (when (and (seq forms) (every? :hash forms))
    (let [sb (StringBuilder. 256)]
      (.append sb (name nsym))
      (doseq [{:keys [key hash]} (sort-by :key forms)] (.append sb \|) (.append sb (str key)) (.append sb \:) (.append sb (str hash)))
      (store/sha256-hex (.toString sb) 16))))

(defn- load-persisted-ns-unused [db]
  (let [sm (:schema db)]
    (if-not (and (contains? sm :seon.code.ns/forms-hash) (contains? sm :seon.code.ns/unused-requires) (contains? sm :seon.code.ns/name))
      {}
      (let [en (java.util.HashMap. 160) eh (java.util.HashMap. 160) out (java.util.HashMap. 160)]
        (doseq [dt (d/datoms db :aevt :seon.code.ns/name)] (.put en (:e dt) (:v dt)))
        (doseq [dt (d/datoms db :aevt :seon.code.ns/forms-hash)] (.put eh (:e dt) (:v dt)))
        (doseq [dt (d/datoms db :aevt :seon.code.ns/unused-requires) :let [e (:e dt)]]
          (when-let [nsym (.get en e)] (when-let [h (.get eh e)] (.put out nsym {:hash h :unused (:v dt)}))))
        (into {} (map (fn [^java.util.Map$Entry e] [(.getKey e) (.getValue e)])) (.entrySet out))))))

(defn- compute-static-eff [db br scope form-snap]
  (let [forms-by-ns (if (and (nil? scope) form-snap) (:src-forms-by-ns form-snap) (collect-forms-by-ns db scope))
        fc @form-static-cache rc @ns-unused-req-cache hrc? (pos? (count rc))
        miss (java.util.HashSet. 64) rsigs (java.util.HashMap. 160) npns? (volatile! false)
        hdr? #(boolean (and (string? %) (str/ends-with? % "#ns")))]
    (doseq [[nsym forms] forms-by-ns]
      (let [rsig (when (every? :hash forms) [nsym (mapv (juxt :key :hash) (sort-by :key forms))])]
        (when rsig (.put rsigs nsym rsig))
        (when-not (and hrc? rsig (contains? rc rsig)) (vreset! npns? true))
        (doseq [{:keys [e key hash]} forms] (when-not (and hash (contains? fc [hash (hdr? key)])) (.add miss e)))))
    (let [pinfo (load-persisted-metrics db scope forms-by-ns miss)
          pns (if @npns? (load-persisted-ns-unused db) {})
          p-ok (java.util.HashMap. 160) needed (java.util.HashSet. (max 16 (.size miss)))]
      (doseq [[nsym forms] forms-by-ns]
        (let [rsig (.get rsigs nsym) rcached? (and hrc? rsig (contains? rc rsig))
              pu? (boolean (when (and (not rcached?) (seq pns) rsig) (when-let [p (get pns nsym)] (= (ns-forms-hash nsym forms) (:hash p)))))
              _ (when pu? (.put p-ok nsym (:unused (get pns nsym))))
              hf (when-not (or rcached? pu?) (some #(when (hdr? (:key %)) %) forms))]
          (doseq [{:keys [e key]} forms :when (.contains miss e)]
            (when-not (and (or (not (hdr? key)) pu?) (.containsKey pinfo e)) (.add needed e)))
          (when hf
            (let [hc (when-let [hh (:hash hf)] (get fc [hh true]))]
              (when (or (nil? hc) (let [d (:decl hc)] (or (seq (:aliases d)) (seq (:refers d)))))
                (doseq [{:keys [e key]} forms :when (not (hdr? key))] (.add needed e)))))))
      (let [src-hm (fetch-missing-srcs db needed)
            get-src! #(or (.get src-hm %) (let [s (str (:v (first (d/datoms db :eavt % :seon.code.form/src))))] (.put src-hm % s) s))
            new-f (volatile! {}) new-r (volatile! {}) ov (volatile! []) ur (volatile! []) ns (volatile! []) lf (volatile! [])]
        (doseq [[nsym forms] forms-by-ns]
          (let [hd (volatile! nil) tc (volatile! 0) ph? (.containsKey p-ok nsym)]
            (doseq [{:keys [e key hash]} forms]
              (let [h? (hdr? key) ck (when hash [hash h?]) hit (if ck (get fc ck ::miss) ::miss)
                    info (if-not (identical? ::miss hit) hit
                                 (let [v (or (when (or (not h?) ph?) (.get pinfo e)) (analyze-form-static h? (get-src! e)))]
                                   (when ck (vswap! new-f assoc ck v)) v))]
                (vswap! tc + (long (:chars info 0)))
                (if h? (when-not ph? (vreset! hd (:decl info))) (emit-form-advisories! br nsym key info ns lf))))
            (let [toks (quot @tc 4)]
              (when (> toks 8000)
                (vswap! ov conj {:class :oversized-namespace :severity :warning :kind :static :ns nsym :subject (str nsym) :branch br
                                 :message (str "Namespace " nsym " is ~" toks " tokens (> 8k cap). Split into 2k-6k tok child namespaces.")})))
            (let [rsig (.get rsigs nsym)
                  unused (if ph? (let [u (.get p-ok nsym)] (when rsig (vswap! new-r assoc rsig u)) u)
                             (when-let [decl @hd]
                               (when (or (seq (:aliases decl)) (seq (:refers decl)))
                                 (if rsig
                                   (let [h (get rc rsig ::miss)]
                                     (if-not (identical? ::miss h) h
                                             (let [v (compute-unused-requires forms decl get-src!)] (vswap! new-r assoc rsig v) v)))
                                   (compute-unused-requires forms decl get-src!)))))]
              (when (seq unused)
                (vswap! ur conj {:class :unused-require :severity :warning :kind :static :ns nsym :subject (str nsym) :branch br :unused unused
                                 :fix {:fn (quote seon.code.edit/prune-requires!) :args [nsym]}
                                 :message (str "Unused :require(s) in " nsym ": " (str/join ", " unused) ". Fix via (user/prune-requires! (quote " nsym ")).")})))))
        (batch-cache-put! form-static-cache 2048 @new-f)
        (batch-cache-put! ns-unused-req-cache 512 @new-r)
        (vec (concat @ov @ur @ns @lf))))))

(defn static-efficiency-warnings
  "Checks non-test namespaces for `:oversized-namespace`, `:minified-form`, `:large-function`, `:nested-db-scan`, and `:unused-require`."
  {:malli/schema [:function [:=> [:cat :any] [:vector :seon.health/problem]] [:=> [:cat :any [:maybe :symbol]] [:vector :seon.health/problem]] [:=> [:cat :any [:maybe :symbol] [:maybe :map]] [:vector :seon.health/problem]]]}
  ([db] (static-efficiency-warnings db nil nil))
  ([db scope] (static-efficiency-warnings db scope nil))
  ([db scope form-snap]
   (if-not (and db (contains? (:schema db) :seon.code.form/src))
     []
     (let [br (get-in db [:config :branch] :main) csig (diff/code-db-sig db)]
       (if csig
         (do (rec-reads! [:seon.code.form/src])
             (store/lru-get! stat-eff-cache 16 [csig scope br] #(binding [di/*reads* nil] (compute-static-eff db br scope form-snap))))
         (compute-static-eff db br scope form-snap))))))
