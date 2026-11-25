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
            [seon.store :as store]
            [seon.health.efficiency.eval :as eeval]))

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
(defonce ^:private ^:seon.flow/edge test-snap-cache (store/lru-cache 16))
(defonce ^:private ^:seon.flow/edge test-snap-anchor (atom {}))

(defn- rec-reads! [& args] (apply eeval/rec-reads! args))

(defn- safe-read-form [s] (try (read-string s) (catch RuntimeException _ nil)))

(defn- resolve-src-value [db v]
  (if (dd/lazy-value? v)
    (let [st (:store db)]
      (or (when st (let [r (k/get st (dd/lazy-key v) ::missing {:sync? true})] (when-not (identical? ::missing r) (str r))))
          (str (dd/value-of v))))
    (str v)))

(defn- eav1 [& args] (apply eeval/eav1 args))

(defn- fetch-missing-srcs [db ^java.util.HashSet needed]
  (let [hm (java.util.HashMap. (max 16 (.size needed)))]
    (when (pos? (.size needed))
      (if (<= (.size needed) 150)
        (doseq [e needed] (when-let [v (eav1 db e :seon.code.form/src)] (.put hm e (str v))))
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

(defn- aevt-hm [db attr cap]
  (let [hm (java.util.HashMap. (int cap))]
    (when (contains? (:schema db) attr)
      (doseq [dt (d/datoms db :aevt attr)]
        (.put hm (:e dt) (:v dt))))
    hm))

(defn- collect-forms-by-ns [db scope]
  (if scope
    (let [pat (quote [:seon.code.form/key :seon.code.form/hash :seon.code.form/chars :seon.code.form/lines :seon.code.form/nested-scan?])
          row (fn [dt]
                (let [{:seon.code.form/keys [key hash chars lines nested-scan?]} (d/pull db pat (:e dt))]
                  {:e (:e dt) :key key :hash hash :chars chars :lines lines :nested-scan? nested-scan?}))]
      (into {}
            (for [nsym (map :v (d/datoms db :aevt :seon.code.ns/name))
                  :when (and (not (str/ends-with? (name nsym) "-test")) (ns-in-scope? scope nsym))
                  :let [fs (mapv row (d/datoms db :avet :seon.code.form/ns nsym))]
                  :when (seq fs)]
              [nsym fs])))
    (let [ek (aevt-hm db :seon.code.form/key 1400)
          eh (aevt-hm db :seon.code.form/hash 1400)
          by-ns (java.util.HashMap. 160)]
      (doseq [dt (d/datoms db :aevt :seon.code.form/ns)
              :let [nsym (:v dt) e (:e dt)]
              :when (and nsym (not (str/ends-with? (name nsym) "-test")))]
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

(defn- anchored-branch-cache! [& args] (apply eeval/anchored-branch-cache! args))

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
    (if (store/live-cid db)
      (anchored-branch-cache! db test-snap-cache test-snap-anchor
                              [:seon.test/var :seon.test/pass?]
                              (fn [d _ anc cid] (try-advance-test-snap d anc cid))
                              (fn [d _] (build-full-test-snap d))
                              :snap)
      (:snap (build-full-test-snap db)))))

(defn- metrics->info [chars lines nested-scan?]
  (let [ln (long (or lines 0))]
    {:ns-hdr? false :chars (long (or chars 0)) :defn? (pos? ln) :lines ln :nested-scan? (true? nested-scan?)}))

(defn- analyze-form-static [ns-hdr? ^String s]
  (if ns-hdr?
    {:ns-hdr? true :chars (.length s) :decl (when-let [f (safe-read-form s)] (parse/parse-ns-decl f))}
    (let [{:seon.code.form/keys [chars lines nested-scan?]} (parse/form-static-metrics s nil)]
      (metrics->info chars lines nested-scan?))))

(defn- load-persisted-metrics [db scope forms-by-ns ^java.util.HashSet miss]
  (let [pinfo (java.util.HashMap. (max 16 (.size miss))) sm (:schema db)]
    (when (and (pos? (.size miss)) (contains? sm :seon.code.form/chars))
      (cond
        scope
        (doseq [[_ fs] forms-by-ns {:keys [e chars lines nested-scan?]} fs :when (and (some? chars) (.contains miss e))]
          (.put pinfo e (metrics->info chars lines nested-scan?)))
        (<= (.size miss) 150)
        (let [hl? (contains? sm :seon.code.form/lines) hn? (contains? sm :seon.code.form/nested-scan?)]
          (doseq [e miss :let [ch (eav1 db e :seon.code.form/chars)] :when ch]
            (.put pinfo e (metrics->info ch (when hl? (eav1 db e :seon.code.form/lines)) (when hn? (eav1 db e :seon.code.form/nested-scan?))))))
        :else
        (let [el (aevt-hm db :seon.code.form/lines 1400) en (aevt-hm db :seon.code.form/nested-scan? 1400)]
          (doseq [dt (d/datoms db :aevt :seon.code.form/chars) :let [e (:e dt)] :when (.contains miss e)]
            (.put pinfo e (metrics->info (:v dt) (.get el e) (.get en e)))))))
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
  {})

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
            get-src! #(or (.get src-hm %) (let [s (str (eav1 db % :seon.code.form/src))] (.put src-hm % s) s))
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

(defn- eval-test-pair-alerts [br vsym cur prev-fn]
  (when (or (> (:ms cur) 200.0) (> (:ticks cur) 5000000) (> (:bytes cur) 33554432))
    (let [nsym (when (namespace vsym) (symbol (namespace vsym)))
          base {:severity :warning :kind :live :ns nsym :var vsym :subject (str vsym) :branch br}
          slow (when (> (:ms cur) 1000.0)
                 (assoc base :class :slow-test :message (str "Slow test " vsym ": " (Math/round (:ms cur)) " ms, " (quot (:bytes cur) 1048576) " MB (> 1000 ms).")))
          prev (when prev-fn (prev-fn))
          regr? (when (and prev (> (:ms cur) 150.0))
                (some (fn [[k fl dl]]
                        (let [pv (double (get prev k)) cv (double (get cur k))]
                          (and (> pv fl) (> (/ cv pv) 2.5) (> (- cv pv) dl))))
                      [[:ticks 50000.0 5000000.0] [:bytes 524288.0 16777216.0] [:ms 10.0 200.0]]))]
      (cond-> []
        slow (conj slow)
        regr? (conj (assoc base :class :cost-regression :message (str "Cost regression in " vsym ": " (Math/round (:ms prev)) " ms -> " (Math/round (:ms cur)) " ms.")))))))

(defn- test-cost-reader [db]
  (let [sm (:schema db)
        hm? (contains? sm :seon.test/ms)
        ht? (contains? sm :seon.test/ticks)
        hb? (contains? sm :seon.test/bytes)]
    (fn [eid]
      {:ms (double (or (when hm? (eav1 db eid :seon.test/ms)) 0.0))
       :ticks (long (or (when ht? (eav1 db eid :seon.test/ticks)) 0))
       :bytes (long (or (when hb? (eav1 db eid :seon.test/bytes)) 0))})))

(defn- compute-test-eff [db br top2]
  (let [rd (test-cost-reader db)
        tsym? #(and (symbol? %) (or (str/ends-with? (name %) "-test") (and (namespace %) (str/ends-with? (namespace %) "-test"))))
        ordered (->> (.entrySet ^java.util.HashMap top2) (filter #(tsym? (.getKey ^java.util.Map$Entry %))) (sort-by #(aget ^longs (.getValue ^java.util.Map$Entry %) 0)))
        out (volatile! [])]
    (doseq [^java.util.Map$Entry en ordered]
      (let [vsym (.getKey en) ^longs slot (.getValue en) pe (aget slot 2)]
        (when-let [al (eval-test-pair-alerts br vsym (rd (aget slot 0)) (when (pos? pe) #(rd pe)))]
          (vswap! out into al))))
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

(defn- scoped-test-alerts-for-var [db br rd [vsym runs]]
  (let [recent (vec (take 2 (filter #(true? (eav1 db (first %) :seon.test/pass?)) (rseq (vec (sort-by second runs))))))]
    (when (seq recent)
      (eval-test-pair-alerts br vsym (rd (ffirst recent))
                             (when (= 2 (count recent)) #(rd (first (second recent))))))))

(defn scoped-test-efficiency-warnings
  "Fast O(log N + K) scoped `:slow-test` and `:cost-regression` alerts via `:avet :seon.test/var` seek."
  {:malli/schema [:=> [:cat :any :any] [:vector :seon.health/problem]]
   :cost/ticks 10000000}
  [db scope]
  (if-not (and (map? db) (symbol? scope) (get-in (:schema db) [:seon.test/var :db/index]))
    []
    (let [sc (name scope) pfx (str sc ".") br (get-in db [:config :branch] :main) rd (test-cost-reader db)
          seek-ns (fn [start pred]
                    (into [] (comp (take-while #(and (= :seon.test/var (:a %)) (symbol? (:v %)) (pred (namespace (:v %)))))
                                   (map (juxt :v :e :tx)))
                          (d/seek-datoms db :avet :seon.test/var start)))
          by-var (reduce (fn [acc [vsym eid tx]] (update acc vsym (fnil conj []) [eid (long (or tx 0))])) {}
                         (into (seek-ns (symbol sc "") #(= % sc)) (seek-ns (symbol pfx "") #(and % (str/starts-with? % pfx)))))]
      (vec (sort-by :subject (mapcat #(scoped-test-alerts-for-var db br rd %) by-var))))))

(defn ^{:seon.code/reexport 'seon.health.efficiency.eval/eval-efficiency-warnings} eval-efficiency-warnings [& args] (apply eeval/eval-efficiency-warnings args))
