(ns seon.render.status
  "Pure uniform `ns-map -> ns-map` Hiccup renderers for `:seon.ui.status/entity`,
   safe inline SVG sanitization, wait-time nudge calculation, and `user/set-status!` /
   `user/clear-status!` live progress helpers."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.ctx.diff :as diff]
            [seon.fork.calls :as fcalls]
            [seon.model.messages :as messages]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.links :as rlinks]
            [seon.render.sanitize :as sanitize]
            [seon.render.text :as rtext :refer [clamp-pct-int one-line]]
            [seon.store :as store]))

(defn format-forked-eval-manifest
  "Formats a compact turn/eid/handle/size/status table of `parent-id`'s live evals on `branch-db`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :keyword]] [:maybe :string]] :cost/ticks 5000000}
  [branch-db parent-id]
  (when (and branch-db parent-id)
    (let [evs (rdata/agent-evals-in branch-db parent-id)]
      (when (seq evs)
        (let [turns (count (distinct (keep :seon.agent.eval/turn evs)))
              rows (mapv (fn [e]
                           (let [ch (+ (count (str (:seon.agent.eval/code e)))
                                       (count (str (:seon.agent.eval/value e)))
                                       (count (str (:seon.agent.eval/out e)))
                                       (count (str (:seon.agent.eval/error e))))
                                 st (if (:seon.agent.eval/error e) "ERR" "ok")
                                 intent (let [s (str/trim (str (or (:seon.agent.eval/intent e)
                                                                   (when (str/blank? (str (:seon.agent.eval/code e))) "(prose)")
                                                                   "")))]
                                          (if (> (count s) 48) (str (subs s 0 45) "...") s))]
                             (str "| " (or (:seon.agent.eval/turn e) 0) " | " (:db/id e) " | "
                                  (or (:seon.agent.eval/handle e) "-") " | " ch " | " st " | " intent " |")))
                         evs)]
          (str "### [forked-agent-evals · " parent-id " (" turns " turns, " (count evs) " evals)]\n"
               "| turn | eid | handle | chars | status | intent |\n"
               (str/join "\n" rows)
               "\nCompact noisy turns: `(user/compact! {:agent-id " parent-id " :turns #{...} :summary \"...\"})` or `:through-turn N`."))))))

(defn ^:seon.flow/edge resolve-prefix-agents
  "ONE self-assembling resolver for `:zone :agent-prefix` projections via `:seon.agent/forked-from` graph edges."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :any :map [:sequential :map] :int :seon.host/fn] :map]
   :cost/ticks 25000000}
  [main-db branch-db conn ag prefix-items depth build-fn]
  (let [sc (or conn (:store main-db) (:store branch-db))
        pat (quote [:seon.agent/id :seon.agent/branch :seon.agent/router? :seon.agent/goal])
        forked-aid (when-let [ff (:seon.agent/forked-from ag)]
                     (let [fe (if (map? ff) (:db/id ff) ff)]
                       (cond (qualified-keyword? fe) fe
                             (integer? fe) (or (:seon.agent/id (d/pull branch-db (quote [:seon.agent/id]) fe))
                                               (:seon.agent/id (d/pull main-db (quote [:seon.agent/id]) fe))))))
        items (->> prefix-items
                   (keep (fn [it]
                           (let [ent (:data it) eid (:entity it)
                                 aid (or (:seon.agent/id ent) (when (and (vector? eid) (= :seon.agent/id (first eid))) (second eid)))
                                 a (cond (:seon.agent/id ent) ent
                                         (qualified-keyword? aid) (let [b (d/pull branch-db pat [:seon.agent/id aid])]
                                                                    (if (:seon.agent/id b) b (d/pull main-db pat [:seon.agent/id aid]))))]
                             (when (:seon.agent/id a) {:agent a :cont? (and (some? forked-aid) (= (:seon.agent/id a) forked-aid))}))))
                   (sort-by (fn [{:keys [agent cont?]}] [(if cont? 0 1) (str (:seon.agent/id agent))]))
                   (reduce (fn [{:keys [seen out]} item]
                             (let [aid (:seon.agent/id (:agent item))]
                               (if (contains? seen aid) {:seen seen :out out} {:seen (conj seen aid) :out (conj out item)})))
                           {:seen #{} :out []})
                   :out)
        cont (when (< depth 4) (first (filter :cont? items)))
        ex-items (filterv (comp not :cont?) items)
        cont-res (when cont
                   (let [pa (:agent cont) pid (:seon.agent/id pa) pbr (:seon.agent/branch pa :main) fc (:seon.agent/fork-commit ag)
                         fdb (or (when (and fc sc) (try (d/commit-as-db sc fc) (catch Throwable _ nil)))
                                 (when (and (not= pbr :main) sc) (try (store/head sc pbr) (catch Throwable _ nil)))
                                 (when (= pbr (get-in branch-db [:config :branch])) branch-db) main-db)
                         p-main (cond (= pbr :main) fdb
                                      (and fc sc) (or (try (when-let [mb (diff/fast-merge-base main-db fdb)]
                                                             (d/commit-as-db sc mb))
                                                           (catch Throwable _ nil))
                                                      fdb)
                                      :else main-db)
                         pw (build-fn p-main fdb pid {:conn conn :vision? false :include-tail? false :depth (inc depth)})]
                     {:forked-from pid :parent-shown (:shown pw) :entry {:mode :continue :id pid :messages (vec (rest (:messages pw)))}}))
        ex-entries (vec (for [{:keys [agent]} ex-items
                              :let [eid (:seon.agent/id agent) ebr (:seon.agent/branch agent :main)
                                    edb (or (when (and (not= ebr :main) sc) (try (store/head sc ebr) (catch Throwable _ nil))) main-db)]]
                          {:mode :example :id eid :goal (:seon.agent/goal agent) :db edb :turns (rdata/evals->turns (rdata/agent-evals-in edb eid))}))]
    {:forked-from (:forked-from cont-res) :parent-shown (:parent-shown cont-res)
     :entries (vec (concat (when (:entry cont-res) [(:entry cont-res)]) ex-entries))}))

(defn- stage-meta [stage]
  (case stage
    :thinking    ["amber" "Thinking"]
    :researching ["blue" "Researching"]
    :editing     ["purple" "Editing"]
    :testing     ["blue" "Testing"]
    :delegating  ["purple" "Delegating"]
    :summarizing ["green" "Summarizing"]
    :done        ["green" "Done"]
    (if (ident? stage)
      ["blue" (str/capitalize (name stage))]
      ["blue" "Working"])))

(defn status-id-for-agent
  "Derives deterministic `:seon.ui.status/id` keyword (`:status/<slug>`) for `agent-id`."
  {:malli/schema [:=> {:examples [[[:agent/root] :status/root]
                                  [[:ns/seon.render] :status/ns-seon-render]]}
                  [:cat :keyword] :qualified-keyword]
   :cost/ticks 50000 :cost/bytes 32768}
  [agent-id]
  (let [ns-p (namespace agent-id)
        nm (name agent-id)
        raw (if (and ns-p (not= ns-p "agent")) (str ns-p "-" nm) nm)
        slug (-> (str/lower-case raw)
                 (str/replace #"[^a-z0-9-]+" "-")
                 (str/replace #"^-+|-+$" ""))]
    (keyword "status" (if (str/blank? slug) "agent" slug))))

(defn render-status-chip
  "Compact status chip (`:data-min-width 180`) for `:seon.ui.status/entity` showing stage badge,
   label, and optional percentage."
  {:malli/schema [:=> {:examples [[[{:seon.ui.status/entity {:seon.ui.status/id :status/root
                                                             :seon.ui.status/agent :agent/root
                                                             :seon.ui.status/label "Checking schemas"
                                                             :seon.ui.status/pct 50
                                                             :seon.ui.status/stage :researching}}]
                                   {:seon.ui/hiccup [:span {:class "ent-chip ent-status" :data-min-width 180}
                                                     [:span {:class "badge blue"} "Researching"]
                                                     [:span {:class "strong"} "Checking schemas"]
                                                     [:span {:class "mono tiny muted"} "50%"]]}]]}
                  [:cat [:map [:seon.ui.status/entity :seon.ui.status/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000 :cost/bytes 131072}
  [{{:seon.ui.status/keys [label pct stage]} :seon.ui.status/entity}]
  (let [[bcls slbl] (stage-meta stage)
        p (clamp-pct-int pct)
        lbl (one-line (or (rtext/nonblank label true) "Working…") 64)]
    {:seon.ui/hiccup
     (into [:span {:class "ent-chip ent-status" :data-min-width 180}
            (rlinks/badge bcls slbl)
            [:span {:class "strong"} lbl]]
           (when (some? p)
             [[:span {:class "mono tiny muted"} (str p "%")]]))}))

(defn build-status-tx
  "Builds a validated `:seon.ui.status/entity` map from `spec`, `agent-id`, and `now-ms`."
  {:malli/schema [:=> {:examples [[[{:label "Reading docs" :pct 40 :stage :researching} :agent/root 1700000000000]
                                   {:seon.ui.status/id :status/root
                                    :seon.ui.status/agent :agent/root
                                    :seon.ui.status/label "Reading docs"
                                    :seon.ui.status/pct 40
                                    :seon.ui.status/stage :researching
                                    :seon.ui.status/updated-ms 1700000000000}]]}
                  [:cat :map :keyword [:int {:min 0}]] :seon.ui.status/entity]
   :cost/ticks 200000 :cost/bytes 131072}
  [spec agent-id now-ms]
  (let [m (if (map? spec) spec {})
        sid (let [raw (or (:seon.ui.status/id m) (:id m))]
              (if (keyword? raw) raw (status-id-for-agent agent-id)))
        lbl (or (rtext/nonblank (or (:seon.ui.status/label m) (:label m)) true) "Working…")
        det (rtext/nonblank (or (:seon.ui.status/detail m) (:detail m)) true)
        p (clamp-pct-int (if (contains? m :seon.ui.status/pct) (:seon.ui.status/pct m) (:pct m)))
        stg (let [s (or (:seon.ui.status/stage m) (:stage m))] (when (keyword? s) s))
        svg (let [sv (or (:seon.ui.status/svg m) (:svg m))] (when (string? sv) sv))]
    (cond-> {:seon.ui.status/id sid
             :seon.ui.status/agent agent-id
             :seon.ui.status/label lbl
             :seon.ui.status/updated-ms (max 0 (long now-ms))}
      det (assoc :seon.ui.status/detail det)
      (some? p) (assoc :seon.ui.status/pct p)
      stg (assoc :seon.ui.status/stage stg)
      (some? svg) (assoc :seon.ui.status/svg svg))))

(defn latest-agent-status
  "Indexed `:avet` lookup for the newest `:seon.ui.status/entity` owned by `agent-id` across `main-db` and `branch-db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] [:maybe :map]]
   :cost/ticks 2000000 :cost/bytes 262144}
  [main-db branch-db agent-id]
  (when agent-id
    (let [pat (quote [:seon.ui.status/id :seon.ui.status/agent :seon.ui.status/label :seon.ui.status/detail
                      :seon.ui.status/pct :seon.ui.status/stage :seon.ui.status/svg :seon.ui.status/updated-ms])
          pull-in (fn [db]
                    (when (and db (contains? (rlinks/db-schema db) :seon.ui.status/agent))
                      (vec (keep #(let [m (d/pull db pat (:e %))] (when (:seon.ui.status/label m) (assoc m ::tx (long (:tx % 0)))))
                                 (d/datoms db :avet :seon.ui.status/agent agent-id)))))
          same? (or (identical? main-db branch-db)
                    (and main-db branch-db (= (:max-tx main-db) (:max-tx branch-db))
                         (= (get-in main-db [:config :branch]) (get-in branch-db [:config :branch]))))
          all (concat (pull-in branch-db) (when (and main-db (not same?)) (pull-in main-db)))]
      (when (seq all)
        (dissoc (last (sort-by (juxt #(long (or (:seon.ui.status/updated-ms %) 0)) #(long (or (::tx %) 0))) all)) ::tx)))))

(defn- prose-reply-eval? [e]
  (and (str/blank? (str (:seon.agent.eval/code e)))
       (not (str/blank? (str (:seon.agent.eval/thought e))))))

(defn- tx-instant-ms [db tx]
  (when (and db (integer? tx))
    (when-let [inst (:db/txInstant (d/pull db '[:db/txInstant] (long tx)))]
      (.getTime ^java.util.Date inst))))

(defn- ^:seon.flow/edge sync-main-tx! [tx-data reason opts]
  (let [actx fcalls/*agent-ctx*]
    (cond
      (and (:in actx) (:out actx))
      (try (fcalls/tx-main-sync! {:op :transact :tx-data tx-data :reason reason})
           (catch Throwable _ nil))

      (or (:port opts) (:sync-main? opts))
      (try
        (let [port (or (:port opts) (System/getenv "SEON_PORT") 9998)
              post-fn (requiring-resolve 'seon.model.wire/http-post!)
              resp (post-fn (str "http://127.0.0.1:" port "/op")
                            [["Content-Type" "application/edn"]]
                            (pr-str {:op :transact :tx-data tx-data :reason reason}))]
          (when (= 200 (:status resp))
            (edn/read-string (:body resp))))
        (catch Throwable _ nil))

      :else nil)))

(defn ^:seon.flow/edge clear-status!
  "Clears the active agent's live `:seon.ui.status/entity` from its branch and `:main`."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:maybe :map]] :map]]
   :cost/ticks 5000000}
  ([] (clear-status! {}))
  ([opts]
   (let [o (if (map? opts) opts {})
         {:keys [conn agent-id]} fcalls/*agent-ctx*
         aid (or (:agent o) agent-id :agent/repl)
         sid (or (:id o) (status-id-for-agent aid))
         retract-op [:db/retractEntity [:seon.ui.status/id sid]]
         local-r (when (and conn (contains? (rlinks/db-schema @conn) :seon.ui.status/id)
                            (first (d/datoms @conn :avet :seon.ui.status/id sid)))
                   (let [r (d/transact conn {:tx-data [retract-op]})]
                     (ctx/refresh-active! (:db-after r) (:tx-data r))
                     r))
         main-r (when (or (:in fcalls/*agent-ctx*) (:port o) (:sync-main? o))
                  (let [mdb (when conn (try (store/head conn :main) (catch Throwable _ nil)))]
                    (when (or (nil? mdb)
                              (and (contains? (rlinks/db-schema mdb) :seon.ui.status/id)
                                   (first (d/datoms mdb :avet :seon.ui.status/id sid))))
                      (sync-main-tx! [retract-op] [:clear-ui-status aid] o))))]
     {:ok? true
      :cleared sid
      :tx (when local-r (:max-tx (:db-after local-r)))
      :main-synced? (boolean (:ok? main-r))})))

(defn branch-reset-tx
  "Purely derives the latest session-reset transaction on `branch-db` for `agent-id`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] :int]
   :cost/ticks 5000000}
  [branch-db agent-id]
  (let [dh (or (:schema branch-db) {})
        keep-hist? (not= false (get-in branch-db [:config :keep-history?]))
        retract-tx (if (and branch-db agent-id keep-hist? (contains? dh :seon.agent.eval/agent))
                     (reduce (fn [m dt] (if-not (:added dt) (max m (long (:tx dt))) m))
                             0 (d/datoms (d/history branch-db) :avet :seon.agent.eval/agent agent-id))
                     0)
        sync-tx (if (and branch-db (contains? dh :seon.tx/merged))
                  (reduce (fn [m dt] (max m (long (:tx dt))))
                  0 (concat (d/datoms branch-db :avet :seon.tx/merged :main)
                            (d/datoms branch-db :avet :seon.tx/merged :fork-review)))
                  0)]
    (max retract-tx sync-tx)))

(defonce ^:private ^:seon.flow/edge tool-format-cache (store/lru-cache 4096))

(defn ^:seon.flow/edge cached-format-tool-content
  "Memoizes `seon.model.messages/format-tool-content` via `store/lru-get!` (4096-slot LRU keyed on `code-db-sig`)."
  {:malli/schema [:=> [:cat :map :map] :string]}
  [opts c]
  (let [p-db (or (:eval-db opts) (:main-db opts))
        m-db (:main-db opts)
        fb-db (:fallback-db opts)
        p-sig (when p-db (diff/code-db-sig p-db))
        m-sig (when m-db (diff/code-db-sig m-db))
        fb-sig (when fb-db (diff/code-db-sig fb-db))
        rsig (when-let [rd (or p-db fb-db m-db)] (:reg-sig (fit/cached-registry-info rd)))
        vnil? (nil? (:value c))
        vs (if vnil? "" (str (:value c)))
        os (if (nil? (:out c)) "" (str (:out c)))
        ck (when (and (or (nil? p-db) p-sig) (or (nil? m-db) m-sig) (or (nil? fb-db) fb-sig))
             [(:turn c) (:handle c) (:ok? c) (:error c) (:ms c) (:ticks c)
              (or (:result-schema c) (:schema c)) (:branch c) (:branch-tx c)
              (:max-chars c) (or (:out-max-chars opts) (:out-max-chars c))
              (boolean (and (string? (:code c)) (str/includes? (:code c) "expand!")))
              vnil? (count vs) (hash vs) (count os) (hash os) p-sig m-sig fb-sig rsig])]
    (if ck
      (store/lru-get! tool-format-cache 4096 ck #(messages/format-tool-content opts c))
      (messages/format-tool-content opts c))))

(defn- unanswered-user-chat-info
  "Shared O(M_agent + E_agent) unanswered `:from :user :type :chat` detector returning `{:umsg :work-evs :tx}` or nil."
  [main-db branch-db aid evals]
  (when (and main-db aid (contains? (rlinks/db-schema main-db) :seon.agent.message/to)
             (first (d/datoms main-db :avet :seon.agent.message/to aid)))
    (let [umsg (->> (rdata/agent-messages-in main-db aid)
                    (filter #(and (contains? #{:user :user/default} (:seon.agent.message/from %))
                                  (= :chat (:seon.agent.message/type %))))
                    last)]
      (when umsg
        (let [s1 (long (or (:seon.agent.message/seq umsg) 0))
              mid (:seon.agent.message/id umsg)
              out-dts (when (contains? (rlinks/db-schema main-db) :seon.agent.message/from)
                        (d/datoms main-db :avet :seon.agent.message/from aid))
              outbound-replied?
              (boolean
               (some (fn [dt]
                       (let [s2 (:seon.agent.message/seq (d/pull main-db '[:seon.agent.message/seq] (:e dt)))]
                         (and (integer? s2) (> (long s2) s1))))
                     out-dts))]
          (when-not outbound-replied?
            (let [evs (if (some? evals)
                        (vec evals)
                        (let [bdb (or branch-db main-db)]
                          (if (and bdb (contains? (rlinks/db-schema bdb) :seon.agent.eval/agent))
                            (->> (d/datoms bdb :avet :seon.agent.eval/agent aid)
                                 (map #(d/pull bdb '[:seon.agent.eval/turn :seon.agent.eval/code
                                                     :seon.agent.eval/thought :seon.agent.eval/seen-messages] (:e %)))
                                 (sort-by #(long (or (:seon.agent.eval/turn %) 0)))
                                 vec)
                            [])))
                  seen-idx (first (keep-indexed #(when (contains? (set (:seon.agent.eval/seen-messages %2)) mid) %1) evs))
                  last-prose-idx (last (keep-indexed #(when (prose-reply-eval? %2) %1) evs))
                  work-evs (cond
                             (some? seen-idx) (subvec evs seen-idx)
                             (some? last-prose-idx) (subvec evs (inc last-prose-idx))
                             :else evs)]
              (when-not (some prose-reply-eval? work-evs)
                {:umsg umsg :work-evs work-evs :tx (long (or (:seon.agent.message/tx umsg) 0))}))))))))

(defn compute-wait-nudge-data
  "Computes `{:elapsed-s :eval-count :prompt :has-status?}` when `ag` has an unanswered user chat message."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :map [:sequential :map] [:int {:min 0}]]
                  [:maybe [:map [:elapsed-s [:int {:min 0}]] [:eval-count [:int {:min 0}]]
                           [:prompt [:maybe :string]] [:has-status? :boolean]]]]
   :cost/ticks 5000000 :cost/bytes 524288}
  [main-db branch-db ag evals now-ms]
  (let [aid (:seon.agent/id ag)]
    (when-let [{:keys [umsg work-evs tx]} (unanswered-user-chat-info main-db branch-db aid evals)]
      (let [ec (count work-evs)
            msg-ms (tx-instant-ms main-db tx)
            elapsed-s (if (and msg-ms (>= (long now-ms) (long msg-ms)))
                        (long (quot (- (long now-ms) (long msg-ms)) 1000))
                        0)
            st (latest-agent-status main-db branch-db aid)
            st-ms (:seon.ui.status/updated-ms st)
            recent-st? (boolean (and (integer? st-ms)
                                     (>= (long st-ms) (long (or msg-ms 0)))
                                     (< (- (long now-ms) (long st-ms)) 15000)))]
        (when (and (not recent-st?) (or (>= ec 2) (>= elapsed-s 5)))
          {:elapsed-s elapsed-s
           :eval-count ec
           :prompt (when-let [b (rtext/nonblank (:seon.agent.message/body umsg) true)] (one-line b 100))
           :has-status? (some? st)})))))

(defn unanswered-user-chat-tx
  "O(M_agent + E_agent) check returning the tx of the latest unanswered `:from :user :type :chat` message to `agent-id`, or nil."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] [:maybe :int]]
   :cost/ticks 5000000}
  [main-db branch-db agent-id]
  (:tx (unanswered-user-chat-info main-db branch-db agent-id nil)))

(defn compacted-turns-info
  "Purely derives compacted (retracted) turn summary for `agent-id` from `(d/history branch-db)`; nil when none retracted."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] [:maybe :map]]
   :cost/ticks 8000000}
  [branch-db agent-id]
  (when (and branch-db agent-id (contains? (:schema branch-db) :seon.agent.eval/agent))
    (let [hdb (d/history branch-db)
          dts (vec (d/datoms hdb :avet :seon.agent.eval/agent agent-id))
          retracted (reduce (fn [acc dt]
                              (if-not (:added dt)
                                (update acc (:e dt) #(max (long (or % 0)) (long (or (:tx dt) 0))))
                                acc))
                            {} dts)]
      (when (seq retracted)
        (let [live-eids (into #{} (map :e) (d/datoms branch-db :avet :seon.agent.eval/agent agent-id))
              gone-eids (remove live-eids (keys retracted))]
          (when (seq gone-eids)
            (let [pull-turn (fn [e] (some #(when (:added %) (long (:v %))) (d/datoms hdb :eavt e :seon.agent.eval/turn)))
                  gone-turns (->> gone-eids (keep pull-turn) distinct sort vec)
                  live-turns (->> live-eids (keep pull-turn) distinct sort vec)
                  last-tx (reduce max 0 (map #(get retracted %) gone-eids))]
              {:seon.agent/id agent-id :retracted-turns gone-turns :retracted-evals (count gone-eids)
               :live-turns live-turns :last-retract-tx last-tx})))))))

(defn latest-ctx-friction-tx
  "Returns the tx of the latest eval (within last 2 turns) where `agent-id` touched `ctx` and hit an error or oversized return; nil otherwise."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] [:maybe :int]]
   :cost/ticks 4000000}
  [branch-db agent-id]
  (when (and branch-db agent-id (contains? (:schema branch-db) :seon.agent.eval/agent))
    (let [evs (rdata/agent-evals-in branch-db agent-id)
          recent (take-last 2 evs)
          mentions-ctx? (fn [^String s]
                          (or (str/includes? s "@ctx") (str/includes? s "user/ctx")
                              (str/includes? s "seon.ctx/ctx") (str/includes? s "swap! ctx")
                              (str/includes? s "reset! ctx")))]
      (some (fn [e]
              (let [code (str (or (:seon.agent.eval/code e) ""))]
                (when (mentions-ctx? code)
                  (let [err (:seon.agent.eval/error e)
                        vlen (count (str (or (:seon.agent.eval/value e) "")))]
                    (when (or (some? err) (> vlen 6000))
                      (let [eid (:db/id e)]
                        (or (when (integer? eid) (:tx (first (d/datoms branch-db :eavt eid :seon.agent.eval/code))))
                            (:max-tx branch-db) 1)))))))
            (reverse recent)))))

(defn sanitize-svg
  "Sanitizes an inline `<svg ...>` string via `seon.render.sanitize/sanitize-svg`. Returns `nil` if `s` does not start with `<svg`."
  {:malli/schema [:=> {:examples [[["<svg width=\"40\"><script>alert(1)</script><circle onclick=\"evil()\" cx=\"10\" cy=\"10\" r=\"5\"/></svg>"]
                                   "<svg width=\"40\"><circle cx=\"10\" cy=\"10\" r=\"5\"/></svg>"]
                                  [["not-svg"] nil]]}
                  [:cat [:maybe :string]] [:maybe :string]]
   :cost/ticks 500000 :cost/bytes 2097152}
  [s]
  (sanitize/sanitize-svg s))

(defn ^:seon.flow/edge set-status!
  "Upserts the active agent's live `:seon.ui.status/entity` on its branch, mirrors to `:main`,
   and sanitizes/normalizes optional `:svg`."
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 15000000}
  [spec]
  (let [{:keys [conn agent-id]} fcalls/*agent-ctx*
        aid (or (:agent spec) (:seon.ui.status/agent spec) agent-id :agent/repl)
        raw-svg (let [sv (or (:seon.ui.status/svg spec) (:svg spec))] (when (string? sv) sv))
        san-svg (when (and raw-svg (not (str/blank? raw-svg))) (sanitize-svg raw-svg))
        eff-spec (cond (nil? raw-svg) spec
                       san-svg (assoc (dissoc spec :seon.ui.status/svg) :svg san-svg)
                       :else (dissoc spec :svg :seon.ui.status/svg))
        ent (build-status-tx eff-spec aid (System/currentTimeMillis))
        local-r (when (and conn (contains? (rlinks/db-schema @conn) :seon.ui.status/id))
                  (let [r (d/transact conn {:tx-data [ent]})]
                    (ctx/refresh-active! (:db-after r) (:tx-data r))
                    r))
        main-r (sync-main-tx! [ent] [:ui-status aid] spec)]
    {:ok? true :status ent :tx (when local-r (:max-tx (:db-after local-r))) :main-synced? (boolean (:ok? main-r))}))

(defn ^:seon.flow/edge install-user-helpers!
  "Installs `user/set-status!` and `user/clear-status!` into `u`."
  {:malli/schema [:=> [:cat :any] :any] :cost/ticks 500000}
  [u]
  (when (instance? clojure.lang.Namespace u)
    (let [add! (fn [sym f meta-m] (let [v (intern u sym f)] (alter-meta! v merge meta-m) v))]
      [(add! 'set-status! (fn [spec] (set-status! spec))
             {:arglists '([spec]) :doc "Upsert live visual status card {:label :detail :pct :stage :svg} for the user."})
       (add! 'clear-status! (fn ([] (clear-status!)) ([opts] (clear-status! opts)))
             {:arglists '([] [opts]) :doc "Clear the active agent's live visual status card."})])))

(defn render-status-card
  "Polished visual progress card (`:data-min-width 320`) for `:seon.ui.status/entity` with stage badge, label, detail, progress bar, and sanitized inline SVG."
  {:malli/schema [:=> [:cat [:map [:seon.ui.status/entity :seon.ui.status/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000 :cost/bytes 2097152}
  [{{:seon.ui.status/keys [id label detail pct stage svg]} :seon.ui.status/entity}]
  (let [[bcls slbl] (stage-meta stage)
        p (clamp-pct-int pct) done? (or (= :done stage) (= 100 p))
        lbl (one-line (or (rtext/nonblank label true) "Working…") 120)
        det (when-let [d (rtext/nonblank detail true)] (one-line d 240))
        safe-svg (sanitize-svg svg)
        fc (if done? "var(--success,#34d399)" "var(--signal,#f0b429)")]
    {:seon.ui/hiccup
     (into
      [:div {:class "card ent-card ent-status status-live-card" :data-min-width 320 :data-status-id (when id (str id))
             :style {:display "flex" :flex-direction "column" :gap "10px" :border-left (str "3px solid " fc)}}
       [:div {:class "card-head ent-head" :style {:display "flex" :align-items "center" :justify-content "space-between" :gap "8px"}}
        [:div {:class "pill-row"} (when-not done? [:span {:class "dot pulsing"}]) (rlinks/badge bcls slbl) [:strong {:class "sm-copy"} lbl]]
        (when (some? p) [:span {:class "mono tiny muted" :style {:font-variant-numeric "tabular-nums"}} (str p "%")])]]
      (remove nil?
              [(when det [:p {:class "sm-copy muted" :style {:margin "0"}} det])
               (when (some? p)
                 [:div {:class "status-progress-track" :role "progressbar" :aria-valuenow p :aria-valuemin 0 :aria-valuemax 100
                        :style {:width "100%" :height "6px" :border-radius "999px" :background "var(--base-850,rgba(128,128,128,.22))" :overflow "hidden"}}
                  [:div {:class "status-progress-fill" :style {:width (str p "%") :height "100%" :border-radius "999px" :background fc :transition "width .3s ease"}}]])
               (when safe-svg
                 [:div {:class "status-svg-wrap" :style {:display "flex" :justify-content "center" :align-items "center" :padding "6px 0" :max-width "100%" :overflow "hidden"}}
                  (render/raw-html safe-svg)])]))}))
