(ns seon.signals.cache
  "Production KV-cache prefix/delta anatomy (`analyze-wire-cache`) and incremental UI
   `datahike.reads` walk cache analyzer (`analyze-walk-cache`)."
  (:require [clojure.string :as str]
            [datahike.api :as d]))

(defn- round1 [x]
  (if (number? x)
    (let [v (double x)]
      (if (and (== v v) (not (Double/isInfinite v)))
        (/ (double (Math/round (* v 10.0))) 10.0)
        0.0))
    0.0))

(defn- round4 [x]
  (if (number? x)
    (let [v (double x)]
      (if (and (== v v) (not (Double/isInfinite v)))
        (/ (double (Math/round (* v 10000.0))) 10000.0)
        0.0))
    0.0))

(defn- nonneg-long [x]
  (cond
    (integer? x) (max 0 (long x))
    (number? x) (let [v (double x)]
                  (if (and (== v v) (>= v 0.0) (<= v 9.0e18)) (long v) 0))
    :else 0))

(defn- pct1 [part total]
  (if (pos? (long total))
    (max 0.0 (min 100.0 (round1 (* 100.0 (/ (double part) (double total))))))
    0.0))

(defn- str-tokens [s]
  (if (and (string? s) (not (str/blank? s)))
    (max 1 (quot (+ (count s) 3) 4))
    0))

(defn- msg-content-text [m]
  (let [c (:content m)]
    (cond
      (string? c) c
      (sequential? c) (str/join "\n" (keep #(when (and (map? %) (= "text" (:type %))) (:text %)) c))
      :else "")))

(defn- msg-vision-info [m ephem-ss]
  (let [c (:content m)
        img-part (when (sequential? c)
                   (some #(when (and (map? %) (= "image_url" (:type %))) %) c))
        url (get-in img-part [:image_url :url])
        has-img? (boolean (or img-part (and (map? ephem-ss) (or (:base64 ephem-ss) (:data-url ephem-ss)))))
        b64-len (cond (string? url) (count url)
                      (string? (:base64 ephem-ss)) (count (:base64 ephem-ss))
                      (string? (:data-url ephem-ss)) (count (:data-url ephem-ss))
                      :else 0)
        img-toks (if has-img? (max 85 (min 2048 (quot b64-len 64))) 0)
        dims (when (and (map? ephem-ss) (:width ephem-ss) (:height ephem-ss))
               (str (:width ephem-ss) "x" (:height ephem-ss)))]
    (cond-> {:present? has-img? :tokens (long img-toks) :bytes (nonneg-long (:bytes ephem-ss))}
      dims (assoc :dimensions dims))))

(defn- est-msg-tokens [m]
  (let [txt-t (str-tokens (msg-content-text m))
        vis-t (:tokens (msg-vision-info m nil))]
    (+ txt-t vis-t)))

(defn- find-prefix-end-idx [msgs]
  (let [n (count msgs)]
    (if (zero? n)
      -1
      (or (first (keep-indexed
                  (fn [i m]
                    (let [txt (msg-content-text m)]
                      (when (and (= "user" (:role m))
                                 (or (str/includes? txt "<agent ")
                                     (str/includes? txt "<walk-context>")))
                        i)))
                  msgs))
          (if (and (>= n 2) (= "system" (:role (first msgs))) (= "user" (:role (second msgs))))
            1
            0)))))

(defn- tail-user-message? [m wire-req]
  (when (and (map? m) (= "user" (:role m)))
    (let [txt (msg-content-text m)]
      (boolean (or (sequential? (:content m))
                   (str/includes? txt "ephemeral=\"true\"")
                   (str/includes? txt "<ephemeral-tail>")
                   (str/includes? txt "<message ")
                   (str/includes? txt "<walk-diff>")
                   (str/includes? txt "<live-viewport")
                   (seq (:tail-blocks wire-req))
                   (seq (:unread wire-req))
                   (some? (:ephemeral-screenshot wire-req)))))))

(defn classify-wire-zones
  "Pure breakdown of `wire-req` into `:cached-prefix` (`#0 SYSTEM` + `#1 USER <walk-context>`)
   and `:uncached-delta` (conversation history, `<ephemeral-tail>`, unread messages, optional vision)."
  {:malli/schema [:=> [:cat [:maybe :map]]
                  [:map [:total-tokens :int] [:cached-prefix :map] [:uncached-delta :map]]]
   :cost/ticks 5000000}
  [wire-req]
  (let [w (if (map? wire-req) wire-req {})
        msgs (if (sequential? (:messages w)) (vec (filter map? (:messages w))) [])
        raw-toks (when (sequential? (:message-tokens w)) (vec (:message-tokens w)))
        indexed (vec (map-indexed
                      (fn [i m]
                        (let [tok (if (and raw-toks (< i (count raw-toks)) (number? (nth raw-toks i)))
                                    (nonneg-long (nth raw-toks i))
                                    (est-msg-tokens m))]
                          {:index i :role (str (or (:role m) "unknown")) :msg m :tokens tok}))
                      msgs))
        total-tok (long (or (when (number? (:total-tokens w)) (nonneg-long (:total-tokens w)))
                            (reduce + 0 (map :tokens indexed))))
        open-idx (find-prefix-end-idx msgs)
        pref-rows (if (neg? open-idx) [] (subvec indexed 0 (inc open-idx)))
        delta-rows (if (neg? open-idx) [] (subvec indexed (inc open-idx)))
        pref-tok (long (reduce + 0 (map :tokens pref-rows)))
        delta-tok (long (reduce + 0 (map :tokens delta-rows)))
        last-d (peek delta-rows)
        has-tail? (boolean (and last-d (tail-user-message? (:msg last-d) w)))
        hist-rows (if has-tail? (pop delta-rows) delta-rows)
        hist-tok (long (reduce + 0 (map :tokens hist-rows)))
        tail-txt (if has-tail? (msg-content-text (:msg last-d)) "")
        vis-info (msg-vision-info (when has-tail? (:msg last-d)) (:ephemeral-screenshot w))
        unread-v (if (sequential? (:unread w)) (vec (:unread w)) [])
        unread-matches (re-seq #"(?s)<message\b[^>]*>.*?</message>" tail-txt)
        unread-cnt (long (max (count unread-v) (count unread-matches)))
        unread-tok (long (if (seq unread-matches)
                           (reduce + 0 (map str-tokens unread-matches))
                           (reduce + 0 (map #(str-tokens (str (or (:seon.agent.message/body %) (:body %) ""))) unread-v))))
        tblocks (if (sequential? (:tail-blocks w)) (vec (filter map? (:tail-blocks w))) [])
        ephem-matches (re-seq #"(?s)<[a-zA-Z0-9_-]+[^>]*ephemeral=\"true\"[^>]*>.*?</[a-zA-Z0-9_-]+>" tail-txt)
        ephem-tok (long (cond
                          (seq ephem-matches) (reduce + 0 (map str-tokens ephem-matches))
                          (seq tblocks) (reduce + 0 (map #(str-tokens (str (:text %))) tblocks))
                          has-tail? (max 0 (- (long (:tokens last-d 0)) unread-tok (long (:tokens vis-info 0))))
                          :else 0))
        pref-msgs (mapv (fn [{:keys [index role tokens]}]
                          {:index index :role role :tokens tokens
                           :label (cond (zero? index) "SYSTEM"
                                        (= index open-idx) "USER <walk-context>"
                                        :else (str (str/upper-case role) " example"))})
                        pref-rows)]
    {:total-tokens total-tok
     :cached-prefix {:prefix-hash (:prefix-hash w)
                     :message-indices (mapv :index pref-rows)
                     :tokens pref-tok
                     :pct-of-total (pct1 pref-tok total-tok)
                     :messages pref-msgs}
     :uncached-delta {:tokens delta-tok
                      :pct-of-total (pct1 delta-tok total-tok)
                      :message-indices (mapv :index delta-rows)
                      :zones {:history {:tokens hist-tok :message-count (count hist-rows)
                                        :indices (mapv :index hist-rows)}
                              :ephemeral-tail {:tokens ephem-tok
                                               :blocks (mapv #(str (or (:tag %) "tail")) tblocks)}
                              :unread {:tokens unread-tok :count unread-cnt}
                              :vision vis-info}}}))

(defn analyze-already-shown
  "Summarizes `already-shown` zero-token deduplicated items vs fresh/rewarmed/upgraded items in `wire-req`."
  {:malli/schema [:=> [:cat [:maybe :map]] :map]
   :cost/ticks 2000000}
  [wire-req]
  (let [w (if (map? wire-req) wire-req {})
        items (vec (filter map? (or (seq (get-in w [:fit-trace :items])) (seq (:tiles w)) [])))
        shown (if (map? (:shown w)) (:shown w) {})
        zero? (fn [it]
                (or (= :unchanged (:status it))
                    (and (zero? (nonneg-long (:tokens it)))
                         (pos? (nonneg-long (:measured-tokens it))))))
        z-items (filterv zero? items)
        act-items (filterv (complement zero?) items)
        rew-items (filterv #(= :rewarmed (:status %)) act-items)
        upg-items (filterv #(= :upgraded (:status %)) act-items)
        fresh-items (filterv #(not (#{:rewarmed :upgraded} (:status %))) act-items)]
    {:total-shown (long (max (count shown) (count items)))
     :zero-token-count (long (count z-items))
     :saved-tokens (long (reduce + 0 (map #(nonneg-long (or (:measured-tokens %) (:tokens %))) z-items)))
     :fresh-count (long (count fresh-items))
     :fresh-tokens (long (reduce + 0 (map #(nonneg-long (:tokens %)) fresh-items)))
     :rewarmed-count (long (count rew-items))
     :rewarmed-tokens (long (reduce + 0 (map #(nonneg-long (:tokens %)) rew-items)))
     :upgraded-count (long (count upg-items))
     :upgraded-tokens (long (reduce + 0 (map #(nonneg-long (:tokens %)) upg-items)))
     :zero-token-items (mapv #(select-keys % [:entity :schema :chosen-fn :measured-tokens]) z-items)
     :active-items (mapv #(select-keys % [:entity :schema :chosen-fn :status :tokens]) act-items)}))

(defn analyze-prefix-turns
  "Pure per-turn KV-cache hit/miss and prefix-bust timeline over eval maps."
  {:malli/schema [:=> [:cat [:sequential :map]]
                  [:map [:turns [:vector :map]] [:raw-busts [:vector :map]]
                   [:prompt-tokens :int] [:cache-hit-tokens :int] [:cache-miss-tokens :int]
                   [:overall-hit-pct number?]]]
   :cost/ticks 5000000}
  [evals]
  (let [with-tel (->> evals
                      (filter map?)
                      (filter #(or (some? (:seon.agent.eval/prefix-hash %))
                                   (pos? (nonneg-long (:seon.agent.eval/prompt-tokens %)))
                                   (pos? (nonneg-long (:seon.agent.eval/cache-hit-tokens %)))))
                      (sort-by (juxt #(nonneg-long (:seon.agent.eval/turn %))
                                     #(nonneg-long (or (:_tx %) (:tx %) (:seon.agent.eval/tx %)))))
                      (take-last 32)
                      vec)
        {:keys [turns busts]}
        (reduce (fn [{:keys [prev turns busts]} ev]
                  (let [turn (nonneg-long (:seon.agent.eval/turn ev))
                        tx (nonneg-long (or (:_tx ev) (:tx ev) (:seon.agent.eval/tx ev)))
                        ph (:seon.agent.eval/prefix-hash ev)
                        hit (nonneg-long (:seon.agent.eval/cache-hit-tokens ev))
                        miss (nonneg-long (:seon.agent.eval/cache-miss-tokens ev))
                        prompt (max (nonneg-long (:seon.agent.eval/prompt-tokens ev)) (+ hit miss))
                        prev-ph (:prefix-hash prev)
                        busted? (boolean (and (some? prev-ph) (some? ph) (not= prev-ph ph)))
                        row {:turn turn :tx tx :prefix-hash ph
                             :prompt-tokens prompt :cache-hit-tokens hit
                             :cache-miss-tokens (if (pos? miss) miss (max 0 (- prompt hit)))
                             :hit-pct (pct1 hit prompt) :busted? busted?}
                        bust (when busted?
                               {:from-turn (:turn prev) :to-turn turn
                                :from-tx (:tx prev) :to-tx tx
                                :from-hash prev-ph :to-hash ph
                                :lost-cache-tokens (:cache-miss-tokens row)})]
                    {:prev (if (some? ph) row prev)
                     :turns (conj turns row)
                     :busts (if bust (conj busts bust) busts)}))
                {:prev nil :turns [] :busts []}
                with-tel)
        tot-p (long (reduce + 0 (map :prompt-tokens turns)))
        tot-h (long (reduce + 0 (map :cache-hit-tokens turns)))
        tot-m (long (reduce + 0 (map :cache-miss-tokens turns)))]
    {:turns turns :raw-busts busts
     :prompt-tokens tot-p :cache-hit-tokens tot-h :cache-miss-tokens tot-m
     :overall-hit-pct (pct1 tot-h tot-p)}))

(defn- db-schema [db]
  (or (:schema db) (when db (d/schema db)) {}))

(defn- pull-cache-evals [db agent-id]
  (if-not (and db (qualified-keyword? agent-id) ((db-schema db) :seon.agent.eval/agent))
    []
    (mapv #(assoc (d/pull db '[:seon.agent.eval/turn :seon.agent.eval/prefix-hash
                               :seon.agent.eval/prompt-tokens :seon.agent.eval/cache-hit-tokens
                               :seon.agent.eval/cache-miss-tokens :seon.agent.eval/error]
                          (:e %))
                  :_tx (nonneg-long (:tx %)))
          (d/datoms db :avet :seon.agent.eval/agent agent-id))))

(defn- collect-history-changes [dbs eid]
  (when (integer? eid)
    (vec (mapcat (fn [db]
                   (when (and db (instance? datahike.db.DB db))
                     (try
                       (mapv (fn [dt] {:a (:a dt) :tx (nonneg-long (:tx dt))})
                             (d/datoms (d/history db) :eavt eid))
                       (catch Throwable _ []))))
                 (distinct (remove nil? dbs))))))

(defn- collect-rule-txs [dbs]
  (into #{}
        (mapcat (fn [db]
                  (let [dh (db-schema db)]
                    (when (and db (dh :seon.rule/id))
                      (mapcat #(when (dh %) (map (fn [dt] (nonneg-long (:tx dt))) (d/datoms db :aevt %)))
                              [:seon.rule/query :seon.rule/assert :seon.rule/enabled?])))))
        (distinct (remove nil? dbs))))

(defn- diagnose-bust-reason [ag-changes task-changes rule-txs {:keys [from-turn to-turn from-tx to-tx]}]
  (let [in-win? (fn [tx] (and (pos? to-tx) (> tx from-tx) (<= tx to-tx)))
        ag-attrs (into #{} (keep #(when (in-win? (:tx %)) (:a %))) ag-changes)
        task-moved? (boolean (some #(in-win? (:tx %)) task-changes))
        rule-moved? (boolean (some in-win? rule-txs))
        causes (cond-> []
                 (contains? ag-attrs :seon.agent/pinned) (conj "pins updated (:seon.agent/pinned)")
                 (or (contains? ag-attrs :seon.agent/task) (contains? ag-attrs :seon.agent/tasks))
                 (conj "active task changed (:seon.agent/task)")
                 (or (contains? ag-attrs :seon.agent/scope) (contains? ag-attrs :seon.agent/context-prefs))
                 (conj "scope/context-prefs changed")
                 (or (contains? ag-attrs :seon.agent/goal) (contains? ag-attrs :seon.agent/system-prompt))
                 (conj "system prompt or goal changed")
                 (or (contains? ag-attrs :seon.agent/memory) (contains? ag-attrs :seon.agent/curated)
                     (contains? ag-attrs :seon.agent/examples))
                 (conj "agent memory/curated/examples updated")
                 task-moved? (conj "active task content updated")
                 rule-moved? (conj "context rules modified (:seon.rule/*)"))]
    (cond
      (seq causes) (str/capitalize (str/join "; " causes))
      (and (<= from-turn 1) (<= to-turn 2)) "Cold start / initial <walk-context> warmup"
      (and (pos? to-tx) (= from-tx to-tx)) (str "<walk-context> changed between turns " from-turn " and " to-turn " (tx " to-tx ")")
      (pos? to-tx) (str "<walk-context> changed between tx " from-tx " and tx " to-tx)
      :else "<walk-context> prefix changed")))

(defn analyze-wire-cache
  "Breaks down an agent's live wire request into `:cached-prefix`, `:uncached-delta`,
   per-turn `:turns`, diagnosed `:busts`, `:already-shown` 0-token dedup summary, and 1-line `:summary`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :qualified-keyword [:maybe :map]] :map]
   :cost/ticks 20000000}
  [main-db branch-db agent-id wire-req]
  (let [w (if (map? wire-req) wire-req {})
        {:keys [total-tokens cached-prefix uncached-delta]} (classify-wire-zones w)
        already (analyze-already-shown w)
        bdb (or branch-db main-db)
        evals (or (seq (pull-cache-evals bdb agent-id))
                  (seq (when (not= main-db bdb) (pull-cache-evals main-db agent-id)))
                  (seq (:evals w))
                  [])
        {:keys [turns raw-busts overall-hit-pct]} (analyze-prefix-turns evals)
        dbs (vec (distinct (remove nil? [main-db bdb])))
        ag-eid (some #(when ((db-schema %) :seon.agent/id)
                        (:e (first (d/datoms % :avet :seon.agent/id agent-id))))
                     dbs)
        tid (or (get-in w [:agent :seon.agent/task])
                (when ag-eid (:seon.agent/task (d/pull (first dbs) '[:seon.agent/task] ag-eid))))
        task-eid (when tid
                   (some #(when ((db-schema %) :seon.task/id)
                            (:e (first (d/datoms % :avet :seon.task/id tid))))
                         dbs))
        ag-ch (when (seq raw-busts) (collect-history-changes dbs ag-eid))
        t-ch (when (seq raw-busts) (collect-history-changes dbs task-eid))
        r-txs (when (seq raw-busts) (collect-rule-txs dbs))
        busts (mapv #(assoc % :reason (diagnose-bust-reason ag-ch t-ch r-txs %)) raw-busts)
        summary (str "Prefix " (:tokens cached-prefix) " tok (" (:pct-of-total cached-prefix) "%)"
                     " · Delta " (:tokens uncached-delta) " tok"
                     " · KV hit " overall-hit-pct "% (" (count busts) " bust" (when (not= 1 (count busts)) "s") ")"
                     " · " (:zero-token-count already) " already-shown (0 tok)")]
    {:agent agent-id
     :total-tokens total-tokens
     :cached-prefix cached-prefix
     :uncached-delta uncached-delta
     :turns turns
     :busts busts
     :bust-count (long (count busts))
     :overall-hit-pct overall-hit-pct
     :already-shown already
     :summary summary}))

(defn analyze-walk-cache
  "Classifies mounted UI walk units into `:hits`, `:misses`, `:total-reads`, `:hit-rate`,
   and per-unit breakdown (`:id`, `:reads-count`, `:hit?`, `:ms`)."
  {:malli/schema [:=> [:cat :any]
                  [:map [:hits :int] [:misses :int] [:unit-count :int]
                   [:total-reads :int] [:hit-rate number?] [:units [:vector :map]] [:summary :string]]]
   :cost/ticks 2000000}
  [walk-summary]
  (let [raw (cond
              (sequential? walk-summary) (vec (filter map? walk-summary))
              (and (map? walk-summary) (map? (get-in walk-summary [:walk :units])))
              (let [us (get-in walk-summary [:walk :units])
                    ord (or (seq (get-in walk-summary [:walk :order])) (keys us))]
                (mapv #(assoc (get us % {}) :id %) ord))
              (and (map? walk-summary) (map? (:units walk-summary)))
              (let [us (:units walk-summary)
                    ord (or (seq (:order walk-summary)) (keys us))]
                (mapv #(assoc (get us % {}) :id %) ord))
              :else [])
        units (mapv (fn [u]
                      (let [rc (nonneg-long (or (:reads-count u)
                                                (when (coll? (:reads-idx u)) (count (:reads-idx u)))))
                            ms (round1 (or (:ms u) 0.0))
                            hit? (cond
                                   (boolean? (:hit? u)) (:hit? u)
                                   (boolean? (:skipped? u)) (:skipped? u)
                                   (boolean? (:rerendered? u)) (not (:rerendered? u))
                                   :else (or (zero? rc) (and (number? (:ms u)) (zero? (double (:ms u))))))]
                        {:id (str (or (:id u) "unknown"))
                         :reads-count rc
                         :hit? (boolean hit?)
                         :ms ms}))
                    raw)
        n (count units)
        hits (long (count (filter :hit? units)))
        misses (long (- n hits))
        total-reads (long (reduce + 0 (map :reads-count units)))
        total-ms (round1 (reduce + 0.0 (map :ms units)))
        hit-rate (if (pos? n) (round4 (/ (double hits) (double n))) 1.0)
        hit-pct (if (pos? n) (pct1 hits n) 100.0)
        summary (str hits "/" n " hits (" hit-pct "%) · " total-reads " reads · " total-ms " ms")]
    {:hits hits :misses misses :unit-count (long n)
     :total-reads total-reads :total-ms total-ms
     :hit-rate hit-rate :hit-pct hit-pct
     :units units :summary summary}))
