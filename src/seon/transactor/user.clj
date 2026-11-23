(ns seon.transactor.user
  "Extracted child namespace of `seon.transactor`."
  (:require [datahike.api :as d]))

(defn norm-ref
  {:doc "Normalizes a 2-element `[attr value]` ref (unwrapping `(quote x)` values), or nil when malformed.", :malli/schema [:=> [:cat :any] [:maybe [:tuple :keyword :any]]], :cost/ticks 5000}
  [r]
  (when (and (vector? r) (= 2 (count r)) (qualified-keyword? (first r)) (some? (second r)))
    (let [v (second r)]
      [(first r) (if (and (seq? v) (= 'quote (first v))) (second v) v)])))

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

(defn show-user-tx
  {:doc "Computes the `:seon.user/*` view/inspect/lab-prefs tx data for a `:show-user` op, returning `{:uid :tx-data}`.", :malli/schema [:=> [:cat :seon.host/db :map] [:map [:uid :keyword] [:tx-data [:vector :any]]]], :seon.flow/edge true}
  [db msg]
  (let [{:keys [user-id viewing viewing-ref mode sidebar? toggle-sidebar? toggle-expand expand
                selected-branch as-of-tx theme toggle-theme? keep-inspect?]} msg
        uid (or user-id :user/default) dh (or (:schema db) {})
        insp? (every? dh [:seon.user/inspect-ref :seon.user/inspect-trail :seon.user/lab-prefs])
        pat (cond-> '[:seon.user/id :seon.user/view-mode :seon.user/sidebar-open? :seon.user/expanded-agents
                      :seon.user/as-of-tx :seon.user/theme {:seon.user/viewing [:seon.agent/id]}]
              insp? (into [:seon.user/inspect-ref :seon.user/inspect-trail :seon.user/lab-prefs]))
        cur (if (first (d/datoms db :avet :seon.user/id uid)) (d/pull db pat [:seon.user/id uid]) {})
        cur-v (get-in cur [:seon.user/viewing :seon.agent/id])
        vref (or viewing-ref
                 (when viewing
                   (let [aid (cond (= viewing :entry)
                                   (or (when (dh :seon.agent/entry?)
                                         (some-> (first (d/datoms db :avet :seon.agent/entry? true))
                                                 :e (#(:seon.agent/id (d/pull db '[:seon.agent/id] %)))))
                                       :agent/root)
                                   (vector? viewing) (second viewing)
                                   :else viewing)]
                     (when (first (d/datoms db :avet :seon.agent/id aid)) [:seon.agent/id aid]))))
        sw? (and vref (or viewing-ref (not= (second vref) cur-v)))
        clear-as-of? (and (:seon.user/as-of-tx cur)
                          (or (and (contains? msg :as-of-tx) (or (nil? as-of-tx) (= :live as-of-tx)))
                              (and sw? (not (contains? msg :as-of-tx)))))
        clear-nav? (and (not keep-inspect?) (or (and mode (not= mode :inspect)) sw?))
        ins (when insp? (next-inspect-state cur msg clear-nav?))
        retracts (cond-> []
                   (and toggle-expand (contains? (set (:seon.user/expanded-agents cur)) toggle-expand))
                   (conj [:db/retract [:seon.user/id uid] :seon.user/expanded-agents toggle-expand])
                   clear-as-of? (conj [:db/retract [:seon.user/id uid] :seon.user/as-of-tx (:seon.user/as-of-tx cur)])
                   (and ins (:ret? ins) (some? (:cur-ref ins)))
                   (conj [:db/retract [:seon.user/id uid] :seon.user/inspect-ref (:cur-ref ins)]))
        add-exp (cond-> #{}
                  (and toggle-expand (not (contains? (set (:seon.user/expanded-agents cur)) toggle-expand))) (conj toggle-expand)
                  (seq expand) (into expand))
        nxt-th (cond (#{:auto :dark :light} theme) theme
                     toggle-theme? (if (= :light (:seon.user/theme cur)) :dark :light) :else nil)
        ent (cond-> {:seon.user/id uid}
              vref (assoc :seon.user/viewing vref)
              (or mode (when (and (:push-inspect msg) (contains? #{:context :debug} (:seon.user/view-mode cur))) :inspect))
              (assoc :seon.user/view-mode (or mode :inspect))
              (some? sidebar?) (assoc :seon.user/sidebar-open? (boolean sidebar?))
              toggle-sidebar? (assoc :seon.user/sidebar-open? (not (boolean (:seon.user/sidebar-open? cur))))
              (seq add-exp) (assoc :seon.user/expanded-agents add-exp)
              selected-branch (assoc :seon.user/selected-branch selected-branch)
              (integer? as-of-tx) (assoc :seon.user/as-of-tx (long as-of-tx))
              nxt-th (assoc :seon.user/theme nxt-th)
              (and ins (:ref ins) (:ref-ch? ins)) (assoc :seon.user/inspect-ref (:ref ins))
              (and ins (:tr-ch? ins)) (assoc :seon.user/inspect-trail (:trail ins))
              (and ins (:lp-ch? ins)) (assoc :seon.user/lab-prefs (:lp ins)))]
    {:uid uid :tx-data (vec (concat retracts [ent]))}))