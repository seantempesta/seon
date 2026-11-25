(ns seon.transactor.user
  "Extracted child namespace of `seon.transactor`."
  (:require [datahike.api :as d]
            [seon.render.data :as rdata]))

(defn ^{:seon.code/reexport (quote seon.render.data/normalize-inspect-ref)} norm-ref
  "Re-exports `seon.render.data/normalize-inspect-ref`."
  [r]
  (rdata/normalize-inspect-ref r))

(defn- cap16 [xs]
  (let [v (vec (remove nil? xs))]
    (if (> (count v) 16) (subvec v (- (count v) 16)) v)))

(defn- next-inspect-state [cur msg clear-nav?]
  (let [cr (norm-ref (:seon.user/inspect-ref cur))
        ct (vec (keep norm-ref (:seon.user/inspect-trail cur)))
        clp (if (map? (:seon.user/lab-prefs cur)) (:seon.user/lab-prefs cur) {})
        cf (vec (keep norm-ref (:inspect-forward clp)))
        pr (norm-ref (:push-inspect msg)) dr (norm-ref (:inspect-ref msg))
        [nr nt nf ret?]
        (cond
          (:clear-inspect msg) [nil [] [] (some? cr)]
          pr (if (= pr cr) [cr ct cf false] [pr (if cr (cap16 (conj ct cr)) ct) [] false])
          (:pop-inspect msg) (if (seq ct) [(peek ct) (pop ct) (if cr (cap16 (conj cf cr)) cf) false] [nil [] [] (some? cr)])
          (:forward-inspect msg) (if (seq cf) [(peek cf) (if cr (cap16 (conj ct cr)) ct) (pop cf) false] [cr ct cf false])
          (integer? (:jump-trail msg))
          (let [i (long (:jump-trail msg))]
            (if (and (>= i 0) (< i (count ct)))
              [(nth ct i) (subvec ct 0 i) (cap16 (concat cf (reverse (concat (subvec ct (inc i)) (when cr [cr]))))) false]
              [cr ct cf false]))
          (contains? msg :inspect-ref) (if dr [dr ct [] false] [nil [] [] (some? cr)])
          clear-nav? [nil [] [] (some? cr)]
          :else [cr ct cf false])
        lp1 (cond
              (:reset-lab-prefs msg) {}
              (map? (:lab-prefs msg)) (into {} (remove #(nil? (val %)))
                                            (if (:replace-lab-prefs? msg) (:lab-prefs msg) (merge clp (:lab-prefs msg))))
              :else clp)
        lp2 (if-let [rid (when (qualified-keyword? (:toggle-lab-rule msg)) (:toggle-lab-rule msg))]
              (let [s (set (:disabled-rules lp1)) s' (if (s rid) (disj s rid) (conj s rid))]
                (if (seq s') (assoc lp1 :disabled-rules (vec (sort s'))) (dissoc lp1 :disabled-rules)))
              lp1)
        lp3 (if (seq nf) (assoc lp2 :inspect-forward nf) (dissoc lp2 :inspect-forward))]
    {:ref nr :trail nt :lp lp3 :ret? ret? :cur-ref (:seon.user/inspect-ref cur)
     :ref-ch? (not= nr cr) :tr-ch? (not= nt ct) :lp-ch? (not= lp3 clp)}))

(defn- cap-n [n xs]
  (let [v (vec (remove nil? xs))]
    (if (> (count v) n) (subvec v (- (count v) n)) v)))

(defn- entry-aid [db]
  (let [dh (or (:schema db) {})]
    (or (when (dh :seon.agent/entry?)
          (some-> (first (d/datoms db :avet :seon.agent/entry? true))
                  :e (#(:seon.agent/id (d/pull db (quote [:seon.agent/id]) %)))))
        (when (and (dh :seon.agent/id) (first (d/datoms db :avet :seon.agent/id :agent/root)))
          :agent/root))))

(defn- view-snap [viewing mode inspect-ref as-of-tx]
  {:viewing viewing
   :mode (or mode :canvas)
   :inspect-ref (norm-ref inspect-ref)
   :as-of-tx (when (integer? as-of-tx) (long as-of-tx))})

(defn- trailing-inspect-refs
  ([stack] (trailing-inspect-refs stack nil))
  ([stack cur-ref]
   (loop [i (dec (count stack)) prev (norm-ref cur-ref) acc ()]
     (if (neg? i)
       (vec acc)
       (if-let [r (norm-ref (:inspect-ref (nth stack i)))]
         (recur (dec i) r (if (= r prev) acc (cons r acc)))
         (vec acc))))))

(defn- prev-non-inspect-mode [stack]
  (or (some (fn [e]
              (let [m (:mode e)]
                (when (and m (not= m :inspect) (nil? (:inspect-ref e))) m)))
            (rseq (vec stack)))
      :canvas))

(defn- unwind-stack [stack target cur-snap other-stack]
  (loop [i (dec (count stack))]
    (when (>= i 0)
      (if (= target (nth stack i))
        [(subvec stack 0 i)
         (cap-n 32 (concat other-stack (reverse (concat (subvec stack (inc i)) [cur-snap]))))]
        (recur (dec i))))))

(defn- find-trail-idx [stack target-ref]
  (loop [i (dec (count stack))]
    (when (>= i 0)
      (let [r (norm-ref (:inspect-ref (nth stack i)))]
        (cond
          (nil? r) nil
          (= r target-ref) i
          :else (recur (dec i)))))))

(defn- next-nav-state [cur cur-snap dst msg]
  (let [clp (if (map? (:seon.user/lab-prefs cur)) (:seon.user/lab-prefs cur) {})
        nb (vec (filter map? (:nav-back clp)))
        nf (vec (filter map? (:nav-forward clp)))
        cseq (long (or (:nav-seq clp) 0))
        cop (or (:nav-op clp) :push)
        ct (vec (keep norm-ref (:seon.user/inspect-trail cur)))
        cf (vec (keep norm-ref (:inspect-forward clp)))
        cr (:inspect-ref cur-snap)
        nav-op-req (:nav-op msg)
        nav
        (cond
          (or (:nav-back msg) (:pop-inspect msg))
          (cond
            (seq nb)
            (let [t (peek nb) nb2 (pop nb) nf2 (cap-n 32 (conj nf cur-snap)) tr (norm-ref (:inspect-ref t))]
              {:snap t :nb nb2 :nf nf2 :seq (inc cseq) :op (or nav-op-req :back)
               :trail (if tr (trailing-inspect-refs nb2 tr) []) :ifwd (trailing-inspect-refs nf2 nil)})
            (seq ct)
            (let [nr (peek ct)]
              {:snap (assoc cur-snap :inspect-ref nr :mode :inspect) :nb [] :nf [] :seq (inc cseq) :op (or nav-op-req :back)
               :trail (pop ct) :ifwd (if cr (cap-n 16 (conj cf cr)) cf)})
            (some? cr)
            {:snap (assoc cur-snap :inspect-ref nil :mode (prev-non-inspect-mode nb)) :nb [] :nf [] :seq (inc cseq) :op (or nav-op-req :back)
             :trail [] :ifwd []}
            :else {:snap cur-snap :nb nb :nf nf :seq cseq :op cop :trail ct :ifwd cf})

          (or (:nav-forward msg) (:forward-inspect msg))
          (cond
            (seq nf)
            (let [t (peek nf) nf2 (pop nf) nb2 (cap-n 32 (conj nb cur-snap)) tr (norm-ref (:inspect-ref t))]
              {:snap t :nb nb2 :nf nf2 :seq (inc cseq) :op (or nav-op-req :forward)
               :trail (if tr (trailing-inspect-refs nb2 tr) []) :ifwd (trailing-inspect-refs nf2 nil)})
            (seq cf)
            (let [nr (peek cf)]
              {:snap (assoc cur-snap :inspect-ref nr :mode :inspect) :nb [] :nf [] :seq (inc cseq) :op (or nav-op-req :forward)
               :trail (if cr (cap-n 16 (conj ct cr)) ct) :ifwd (pop cf)})
            :else {:snap cur-snap :nb nb :nf nf :seq cseq :op cop :trail ct :ifwd cf})

          (integer? (:jump-trail msg))
          (let [i (long (:jump-trail msg))]
            (if (and (>= i 0) (< i (count ct)))
              (let [tref (nth ct i)]
                (if-let [tidx (find-trail-idx nb tref)]
                  (let [t (nth nb tidx)
                        nb2 (subvec nb 0 tidx)
                        nf2 (cap-n 32 (concat nf (reverse (concat (subvec nb (inc tidx)) [cur-snap]))))]
                    {:snap t :nb nb2 :nf nf2 :seq (inc cseq) :op :back
                     :trail (trailing-inspect-refs nb2 tref) :ifwd (trailing-inspect-refs nf2 nil)})
                  {:snap (assoc cur-snap :inspect-ref tref :mode :inspect) :nb nb :nf nf :seq (inc cseq) :op :back
                   :trail (subvec ct 0 i)
                   :ifwd (cap-n 16 (concat cf (reverse (concat (subvec ct (inc i)) (when cr [cr])))))}))
              {:snap cur-snap :nb nb :nf nf :seq cseq :op cop :trail ct :ifwd cf}))

          (= dst cur-snap)
          {:snap dst :nb nb :nf nf :seq cseq :op cop
           :trail (if (:clear-inspect msg) [] ct)
           :ifwd (if (:clear-inspect msg) [] cf)}

          (= :replace nav-op-req)
          {:snap dst :nb nb :nf nf :seq (inc cseq) :op :replace
           :trail (if (:inspect-ref dst) ct []) :ifwd cf}

          (= :pop nav-op-req)
          (let [[nb2 nf2] (or (unwind-stack nb dst cur-snap nf)
                              (when-let [[nf3 nb3] (unwind-stack nf dst cur-snap nb)] [nb3 nf3])
                              [nb nf])
                dr (:inspect-ref dst)]
            {:snap dst :nb nb2 :nf nf2 :seq (inc cseq) :op :pop
             :trail (if dr (trailing-inspect-refs nb2 dr) []) :ifwd (trailing-inspect-refs nf2 nil)})

          :else
          (let [nb2 (cap-n 32 (conj nb cur-snap))
                dr (:inspect-ref dst)]
            {:snap dst :nb nb2 :nf [] :seq (inc cseq) :op (or nav-op-req :push)
             :trail (if dr (trailing-inspect-refs nb2 dr) []) :ifwd []}))
        lp1 (cond
              (:reset-lab-prefs msg) (select-keys clp [:nav-back :nav-forward :nav-seq :nav-op :inspect-forward])
              (map? (:lab-prefs msg)) (into {} (remove #(nil? (val %)))
                                            (if (:replace-lab-prefs? msg)
                                              (merge (select-keys clp [:nav-back :nav-forward :nav-seq :nav-op :inspect-forward]) (:lab-prefs msg))
                                              (merge clp (:lab-prefs msg))))
              :else clp)
        lp2 (if-let [rid (when (qualified-keyword? (:toggle-lab-rule msg)) (:toggle-lab-rule msg))]
              (let [s (set (:disabled-rules lp1)) s2 (if (s rid) (disj s rid) (conj s rid))]
                (if (seq s2) (assoc lp1 :disabled-rules (vec (sort s2))) (dissoc lp1 :disabled-rules)))
              lp1)
        lp3 (cond-> (dissoc lp2 :nav-back :nav-forward :inspect-forward)
              (seq (:nb nav)) (assoc :nav-back (:nb nav))
              (seq (:nf nav)) (assoc :nav-forward (:nf nav))
              (seq (:ifwd nav)) (assoc :inspect-forward (:ifwd nav))
              (or (pos? (:seq nav)) (contains? clp :nav-seq)) (assoc :nav-seq (:seq nav) :nav-op (:op nav)))]
    (assoc nav :lp lp3 :tr-ch? (not= (:trail nav) ct) :lp-ch? (not= lp3 clp))))

(defn show-user-tx
  {:doc "Computes the `:seon.user/*` view/inspect/universal-nav/lab-prefs tx data for a `:show-user` op, returning `{:uid :tx-data}`."
   :malli/schema [:=> [:cat :seon.host/db :map] [:map [:uid :keyword] [:tx-data [:vector :any]]]]
   :seon.flow/edge true}
  [db msg]
  (let [{:keys [user-id viewing viewing-ref mode sidebar? toggle-sidebar? toggle-expand expand
                selected-branch as-of-tx theme toggle-theme? keep-inspect?]} msg
        uid (or user-id :user/default) dh (or (:schema db) {})
        insp? (every? dh [:seon.user/inspect-ref :seon.user/inspect-trail :seon.user/lab-prefs])
        pat (cond-> (quote [:seon.user/id :seon.user/view-mode :seon.user/sidebar-open? :seon.user/expanded-agents
                            :seon.user/as-of-tx :seon.user/theme {:seon.user/viewing [:seon.agent/id]}])
              insp? (into [:seon.user/inspect-ref :seon.user/inspect-trail :seon.user/lab-prefs]))
        cur (if (first (d/datoms db :avet :seon.user/id uid)) (d/pull db pat [:seon.user/id uid]) {})
        cur-v (or (get-in cur [:seon.user/viewing :seon.agent/id]) (entry-aid db))
        cur-mode (or (:seon.user/view-mode cur) :canvas)
        cur-ref (norm-ref (:seon.user/inspect-ref cur))
        cur-tx (:seon.user/as-of-tx cur)
        cur-snap (view-snap cur-v cur-mode cur-ref cur-tx)
        vref (or viewing-ref
                 (when viewing
                   (let [aid (cond (= viewing :entry) (or (entry-aid db) :agent/root)
                                   (vector? viewing) (second viewing)
                                   :else viewing)]
                     (when (first (d/datoms db :avet :seon.agent/id aid)) [:seon.agent/id aid]))))
        sw? (and vref (or viewing-ref (not= (second vref) cur-v)))
        clear-as-of? (and cur-tx
                          (or (and (contains? msg :as-of-tx) (or (nil? as-of-tx) (= :live as-of-tx)))
                              (and sw? (not (contains? msg :as-of-tx)))))
        clear-nav? (and (not keep-inspect?) (or (and mode (not= mode :inspect)) sw?))
        pr (norm-ref (:push-inspect msg))
        dr (norm-ref (:inspect-ref msg))
        clp (if (map? (:seon.user/lab-prefs cur)) (:seon.user/lab-prefs cur) {})
        nb0 (vec (filter map? (:nav-back clp)))
        nxt-v (or (when vref (second vref)) cur-v)
        nxt-ref (cond (:clear-inspect msg) nil
                      pr pr
                      (contains? msg :inspect-ref) dr
                      clear-nav? nil
                      :else cur-ref)
        nxt-mode (cond mode mode
                       pr :inspect
                       (and (or (:clear-inspect msg) clear-nav?) (= :inspect cur-mode)) (prev-non-inspect-mode nb0)
                       :else cur-mode)
        nxt-tx (cond (integer? as-of-tx) (long as-of-tx)
                     clear-as-of? nil
                     :else cur-tx)
        dst0 (view-snap nxt-v nxt-mode nxt-ref nxt-tx)
        nav (when insp? (next-nav-state cur cur-snap dst0 msg))
        dst (if nav (:snap nav) dst0)
        final-v (:viewing dst)
        final-vref (cond
                     (and viewing-ref (= final-v (second viewing-ref))) viewing-ref
                     (and final-v (not= final-v (get-in cur [:seon.user/viewing :seon.agent/id]))
                          (first (d/datoms db :avet :seon.agent/id final-v)))
                     [:seon.agent/id final-v]
                     :else nil)
        final-ref (:inspect-ref dst)
        final-tx (:as-of-tx dst)
        final-mode (:mode dst)
        retracts (cond-> []
                   (and toggle-expand (contains? (set (:seon.user/expanded-agents cur)) toggle-expand))
                   (conj [:db/retract [:seon.user/id uid] :seon.user/expanded-agents toggle-expand])
                   (and cur-tx (nil? final-tx))
                   (conj [:db/retract [:seon.user/id uid] :seon.user/as-of-tx cur-tx])
                   (and insp? (some? (:seon.user/inspect-ref cur)) (nil? final-ref))
                   (conj [:db/retract [:seon.user/id uid] :seon.user/inspect-ref (:seon.user/inspect-ref cur)]))
        add-exp (cond-> #{}
                  (and toggle-expand (not (contains? (set (:seon.user/expanded-agents cur)) toggle-expand))) (conj toggle-expand)
                  (seq expand) (into expand))
        nxt-th (cond (#{:auto :dark :light} theme) theme
                     toggle-theme? (if (= :light (:seon.user/theme cur)) :dark :light) :else nil)
        mode-ch? (or (some? mode) (not= final-mode (:seon.user/view-mode cur)))
        ent (cond-> {:seon.user/id uid}
              final-vref (assoc :seon.user/viewing final-vref)
              mode-ch? (assoc :seon.user/view-mode final-mode)
              (some? sidebar?) (assoc :seon.user/sidebar-open? (boolean sidebar?))
              toggle-sidebar? (assoc :seon.user/sidebar-open? (not (boolean (:seon.user/sidebar-open? cur))))
              (seq add-exp) (assoc :seon.user/expanded-agents add-exp)
              selected-branch (assoc :seon.user/selected-branch selected-branch)
              (and (integer? final-tx) (not= final-tx cur-tx)) (assoc :seon.user/as-of-tx (long final-tx))
              nxt-th (assoc :seon.user/theme nxt-th)
              (and insp? final-ref (not= final-ref (:seon.user/inspect-ref cur))) (assoc :seon.user/inspect-ref final-ref)
              (and nav (:tr-ch? nav)) (assoc :seon.user/inspect-trail (:trail nav))
              (and nav (:lp-ch? nav)) (assoc :seon.user/lab-prefs (:lp nav)))]
    {:uid uid :tx-data (vec (concat retracts [ent]))}))