(ns seon.render.status
  "Pure uniform `ns-map -> ns-map` Hiccup renderers for `:seon.ui.status/entity`,
   safe inline SVG sanitization, wait-time nudge calculation, and `user/set-status!` /
   `user/clear-status!` live progress helpers."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.fork.calls :as fcalls]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.links :as rlinks]
            [seon.store :as store]))

(defn- nonblank [x]
  (when (and (string? x) (not (str/blank? x)))
    (str/trim x)))

(defn- one-line [x n]
  (let [s (-> (if (nil? x) "" (str x)) (str/replace #"\s+" " ") str/trim)]
    (if (> (count s) n) (str (subs s 0 (max 1 (dec n))) "…") s)))

(defn- clamp-pct [x]
  (when (number? x)
    (let [dv (double x)]
      (when (and (not (Double/isNaN dv)) (not (Double/isInfinite dv)))
        (max 0 (min 100 (long (Math/round dv))))))))

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
        p (clamp-pct pct)
        lbl (one-line (or (nonblank label) "Working…") 64)]
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
        lbl (or (nonblank (or (:seon.ui.status/label m) (:label m))) "Working…")
        det (nonblank (or (:seon.ui.status/detail m) (:detail m)))
        p (clamp-pct (if (contains? m :seon.ui.status/pct) (:seon.ui.status/pct m) (:pct m)))
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
  "Indexed `:avet` lookup for the newest `:seon.ui.status/entity` owned by `agent-id` across
   `main-db` and `branch-db`, or `nil` when none exists."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] [:maybe :map]]
   :cost/ticks 2000000 :cost/bytes 262144}
  [main-db branch-db agent-id]
  (when agent-id
    (let [pull-in (fn [db]
                    (when (and db (contains? (rlinks/db-schema db) :seon.ui.status/agent))
                      (->> (d/datoms db :avet :seon.ui.status/agent agent-id)
                           (map (fn [dt]
                                  (let [m (d/pull db '[:seon.ui.status/id :seon.ui.status/agent
                                                       :seon.ui.status/label :seon.ui.status/detail
                                                       :seon.ui.status/pct :seon.ui.status/stage
                                                       :seon.ui.status/svg :seon.ui.status/updated-ms]
                                                  (:e dt))]
                                    (when (:seon.ui.status/label m)
                                      (assoc m ::tx (long (:tx dt 0)))))))
                           (remove nil?)
                           vec)))
          same-db? (or (identical? main-db branch-db)
                       (and main-db branch-db
                            (= (:max-tx main-db) (:max-tx branch-db))
                            (= (get-in main-db [:config :branch]) (get-in branch-db [:config :branch]))))
          b-ents (pull-in branch-db)
          m-ents (when (and main-db (not same-db?)) (pull-in main-db))
          all (concat b-ents m-ents)]
      (when (seq all)
        (dissoc (last (sort-by (juxt #(long (or (:seon.ui.status/updated-ms %) 0)) #(long (or (::tx %) 0))) all))
                ::tx)))))

(defn- prose-reply-eval? [e]
  (and (str/blank? (str (:seon.agent.eval/code e)))
       (not (str/blank? (str (:seon.agent.eval/thought e))))))

(defn- tx-instant-ms [db tx]
  (when (and db (integer? tx))
    (when-let [inst (:db/txInstant (d/pull db '[:db/txInstant] (long tx)))]
      (.getTime ^java.util.Date inst))))

(defn compute-wait-nudge-data
  "Computes `{:elapsed-s :eval-count :prompt :has-status?}` when `ag` has an unanswered
   `:from :user :type :chat` message and has been working (`>= 2` evals OR `>= 5s` elapsed)
   without a recent (`< 15s`) `:seon.ui.status/*` update; otherwise returns `nil`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :map [:sequential :map] [:int {:min 0}]]
                  [:maybe [:map [:elapsed-s [:int {:min 0}]] [:eval-count [:int {:min 0}]]
                           [:prompt [:maybe :string]] [:has-status? :boolean]]]]
   :cost/ticks 5000000 :cost/bytes 524288}
  [main-db branch-db ag evals now-ms]
  (let [aid (:seon.agent/id ag)]
    (when (and main-db aid (contains? (rlinks/db-schema main-db) :seon.agent.message/to))
      (let [msgs (rdata/agent-messages-in main-db aid)
            umsg (last (filter #(and (contains? #{:user :user/default} (:seon.agent.message/from %))
                                     (= :chat (:seon.agent.message/type %)))
                               msgs))]
        (when umsg
          (let [s1 (long (or (:seon.agent.message/seq umsg) 0))
                mid (:seon.agent.message/id umsg)
                out-dts (when (contains? (rlinks/db-schema main-db) :seon.agent.message/from)
                          (d/datoms main-db :aevt :seon.agent.message/from))
                outbound-replied?
                (boolean
                 (some (fn [dt]
                         (when (= aid (:v dt))
                           (let [s2 (:seon.agent.message/seq (d/pull main-db '[:seon.agent.message/seq] (:e dt)))]
                             (and (integer? s2) (> (long s2) s1)))))
                       out-dts))]
            (when-not outbound-replied?
              (let [evs (vec evals)
                    seen-idx (first (keep-indexed #(when (contains? (set (:seon.agent.eval/seen-messages %2)) mid) %1) evs))
                    last-prose-idx (last (keep-indexed #(when (prose-reply-eval? %2) %1) evs))
                    work-evs (cond
                               (some? seen-idx) (subvec evs seen-idx)
                               (some? last-prose-idx) (subvec evs (inc last-prose-idx))
                               :else evs)
                    prose-replied? (boolean (some prose-reply-eval? work-evs))]
                (when-not prose-replied?
                  (let [ec (count work-evs)
                        msg-ms (tx-instant-ms main-db (:seon.agent.message/tx umsg))
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
                       :prompt (when-let [b (nonblank (:seon.agent.message/body umsg))] (one-line b 100))
                       :has-status? (some? st)})))))))))))

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

(defn ^:seon.flow/edge set-status!
  "Upserts the active agent's live visual `:seon.ui.status/entity` on its branch and mirrors to `:main`
   so the browser UI updates immediately via SSE."
  {:malli/schema [:=> [:cat :map] :map]
   :cost/ticks 5000000}
  [spec]
  (let [{:keys [conn agent-id]} fcalls/*agent-ctx*
        aid (or (:agent spec) (:seon.ui.status/agent spec) agent-id :agent/repl)
        now-ms (System/currentTimeMillis)
        ent (build-status-tx spec aid now-ms)
        local-r (when (and conn (contains? (rlinks/db-schema @conn) :seon.ui.status/id))
                  (let [r (d/transact conn {:tx-data [ent]})]
                    (ctx/refresh-active! (:db-after r) (:tx-data r))
                    r))
        main-r (sync-main-tx! [ent] [:ui-status aid] spec)]
    {:ok? true
     :status ent
     :tx (when local-r (:max-tx (:db-after local-r)))
     :main-synced? (boolean (:ok? main-r))}))

(defn ^:seon.flow/edge clear-status!
  "Clears the active agent's live `:seon.ui.status/entity` from its branch and `:main`."
  {:malli/schema [:function
                  [:=> [:cat] :map]
                  [:=> [:cat [:maybe :map]] :map]]
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

(defn ^:seon.flow/edge install-user-helpers!
  "Installs `user/set-status!` and `user/clear-status!` into `u`."
  {:malli/schema [:=> [:cat :any] :any] :cost/ticks 500000}
  [u]
  (when (instance? clojure.lang.Namespace u)
    (let [add! (fn [sym f meta-m] (let [v (intern u sym f)] (alter-meta! v merge meta-m) v))]
      [(add! 'set-status! (fn [spec] (set-status! spec))
             {:arglists '([spec])
              :doc "Upsert live visual status card {:label :detail :pct :stage :svg} for the user."})
       (add! 'clear-status! (fn ([] (clear-status!)) ([opts] (clear-status! opts)))
             {:arglists '([] [opts])
              :doc "Clear the active agent's live visual status card."})])))

(def ^:private svg-open-re #"(?i)^<svg(?:\s|>|/)")

(def ^:private svg-script-re #"(?is)<script\b[^>]*>.*?</script>")

(def ^:private svg-script-self-re #"(?is)<script\b[^>]*/\s*>")

(def ^:private svg-active-re #"(?is)<(?:foreignObject|iframe|object|embed)\b[^>]*>.*?</(?:foreignObject|iframe|object|embed)>")

(def ^:private svg-active-self-re #"(?is)<(?:foreignObject|iframe|object|embed)\b[^>]*/\s*>")

(def ^:private svg-on-attr-re #"(?i)\s+on[a-z]+\s*=\s*(?:\"[^\"]*\"|'[^']*'|[^\s>/]+)")

(def ^:private svg-proto-re #"(?i)(?:javascript|vbscript)\s*:")

(def ^:private svg-data-html-re #"(?i)data\s*:\s*text/html")

(defn sanitize-svg
  "Sanitizes an inline `<svg ...>` string by stripping `<script>`, active embedded elements,
   `on*` event attributes, and `javascript:`/`vbscript:`/`data:text/html` URIs. Returns `nil`
   if `s` does not start with `<svg`."
  {:malli/schema [:=> {:examples [[["<svg width=\"40\"><script>alert(1)</script><circle onclick=\"evil()\" cx=\"10\" cy=\"10\" r=\"5\"/></svg>"]
                                   "<svg width=\"40\"><circle cx=\"10\" cy=\"10\" r=\"5\"/></svg>"]
                                  [["not-svg"] nil]]}
                  [:cat [:maybe :string]] [:maybe :string]]
   :cost/ticks 500000 :cost/bytes 2097152}
  [s]
  (when-let [trimmed (nonblank s)]
    (when (and (re-find svg-open-re trimmed) (str/includes? trimmed ">"))
      (let [lo (str/lower-case trimmed)
            dirty? (or (str/includes? lo "<script") (str/includes? lo "<foreignobject")
                       (str/includes? lo "<iframe") (str/includes? lo "<object")
                       (str/includes? lo "<embed") (str/includes? lo " on")
                       (str/includes? lo "javascript:") (str/includes? lo "vbscript:")
                       (str/includes? lo "text/html"))
            cleaned (if-not dirty?
                      trimmed
                      (-> trimmed
                          (str/replace svg-script-re "")
                          (str/replace svg-script-self-re "")
                          (str/replace svg-active-re "")
                          (str/replace svg-active-self-re "")
                          (str/replace svg-on-attr-re "")
                          (str/replace svg-proto-re "")
                          (str/replace svg-data-html-re "")
                          str/trim))]
        (when (and (re-find svg-open-re cleaned) (str/includes? cleaned ">"))
          cleaned)))))

(defn render-status-card
  "Polished visual progress card (`:data-min-width 320`) for `:seon.ui.status/entity` with stage
   badge, label, detail, smooth progress bar (`pct%`), and sanitized inline SVG graphic."
  {:malli/schema [:=> [:cat [:map [:seon.ui.status/entity :seon.ui.status/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]], :cost/ticks 1000000, :cost/bytes 2097152}
  [{{:seon.ui.status/keys [id label detail pct stage svg]} :seon.ui.status/entity}]
  (let [[bcls slbl] (stage-meta stage)
        p (clamp-pct pct)
        done? (or (= :done stage) (= 100 p))
        lbl (one-line (or (nonblank label) "Working…") 120)
        det (when-let [d (nonblank detail)] (one-line d 240))
        safe-svg (sanitize-svg svg)
        fill-color (if done? "var(--success,#34d399)" "var(--signal,#f0b429)")]
    {:seon.ui/hiccup
     (into
      [:div {:class "card ent-card ent-status status-live-card"
             :data-min-width 320
             :data-status-id (when id (str id))
             :style {:display "flex" :flex-direction "column" :gap "10px"
                     :border-left (str "3px solid " fill-color)}}
       [:div {:class "card-head ent-head"
              :style {:display "flex" :align-items "center" :justify-content "space-between" :gap "8px"}}
        [:div {:class "pill-row"}
         (when-not done? [:span {:class "dot pulsing"}])
         (rlinks/badge bcls slbl)
         [:strong {:class "sm-copy"} lbl]]
        (when (some? p)
          [:span {:class "mono tiny muted" :style {:font-variant-numeric "tabular-nums"}} (str p "%")])]]
      (remove nil?
              [(when det
                 [:p {:class "sm-copy muted" :style {:margin "0"}} det])
               (when (some? p)
                 [:div {:class "status-progress-track"
                        :role "progressbar"
                        :aria-valuenow p :aria-valuemin 0 :aria-valuemax 100
                        :style {:width "100%" :height "6px" :border-radius "999px"
                                :background "var(--base-850,rgba(128,128,128,.22))"
                                :overflow "hidden"}}
                  [:div {:class "status-progress-fill"
                         :style {:width (str p "%") :height "100%" :border-radius "999px"
                                 :background fill-color
                                 :transition "width .3s ease"}}]])
               (when safe-svg
                 [:div {:class "status-svg-wrap"
                        :style {:display "flex" :justify-content "center" :align-items "center"
                                :padding "6px 0" :max-width "100%" :overflow "hidden"}}
                  (render/raw-html safe-svg)])]))}))
