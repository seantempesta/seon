(ns seon.error
  "First-class `:seon.error/*` entities, queries, classifier, renderer, and rules."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [datahike.api :as d]))

(def ^:private field-checks
  {:seon.error/kind [keyword? nil 1] :seon.error/message [string? nil 1]
   :seon.error/severity [#{:error :warning :fatal} :error] :seon.error/source [keyword? :system]
   :seon.error/status [#{:open :resolved :suppressed} :open] :seon.error/id [uuid?]
   :seon.error/at [integer?] :seon.error/agent [keyword?] :seon.error/branch [keyword?]
   :seon.error/data [map?] :seon.error/eval [uuid?] :seon.error/var [qualified-symbol?]})

(defn error-tx
  "Pure constructor returning a validated `:seon.error/*` entity map."
  {:malli/schema [:=> [:cat [:or [:map [:kind :keyword] [:message :string] [:severity {:optional true} :seon.error/severity]
                                  [:status {:optional true} :seon.error/status]] [:map [:seon.error/kind :keyword] [:seon.error/message :string]]]]
                  :seon.error/entity] :cost/ticks 1000000}
  [spec]
  (when-not (map? spec) (throw (ex-info "error-tx requires a map" {:spec spec})))
  (let [out (reduce-kv (fn [a nk [p d req]]
                         (let [k (keyword (name nk))
                               v (cond (contains? spec nk) (spec nk) (contains? spec k) (spec k) :else d)]
                           (cond (some? v) (do (when-not (p v) (throw (ex-info (str "Bad " nk) {:val v})))
                                               (assoc a nk (if (= nk :seon.error/at) (long v) v)))
                                 req (throw (ex-info (str "Missing " nk) {:key nk})) :else a)))
                       {} field-checks)]
    (with-meta (cond-> out
                 (not (:seon.error/id out)) (assoc :seon.error/id (random-uuid))
                 (not (:seon.error/at out)) (assoc :seon.error/at (System/currentTimeMillis)))
      {:seon/schema :seon.error/entity})))

(defn resolve-error-tx
  "Pure tx map marking `[:seon.error/id err-id]` `:resolved`."
  {:malli/schema [:=> [:cat :uuid] [:map [:seon.error/id :uuid] [:seon.error/status [:= :resolved]]]] :cost/ticks 200000}
  [err-id]
  {:seon.error/id err-id :seon.error/status :resolved})

(defn open-errors
  "Indexed `:avet :seon.error/status :open` query, newest-first."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] [:vector :seon.error/entity]]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :map]] [:vector :seon.error/entity]]] :cost/ticks 5000000}
  ([db] (open-errors db nil))
  ([db opts]
   (if-not (and (instance? clojure.lang.ILookup db) (map? (:schema db)) (contains? (:schema db) :seon.error/status))
     []
     (let [fs (select-keys (if (map? opts) opts {}) [:agent :source :kind :branch])]
       (->> (d/datoms db :avet :seon.error/status :open)
            (keep (fn [dt]
                    (let [e (dissoc (d/pull db '[*] (:e dt)) :db/id)]
                      (when (and (:seon.error/id e) (every? (fn [[k v]] (= v (get e (keyword "seon.error" (name k))))) fs))
                        [(with-meta e {:seon/schema :seon.error/entity}) (:tx dt)]))))
            (sort-by (fn [[e tx]] [(- (long (or (:seon.error/at e) 0))) (- (long tx)) (str (:seon.error/id e))]))
            (mapv first))))))

(defn- parse-retry-ms [m]
  (let [hs (:headers m) ra? #(= "retry-after" (str/lower-case (if (ident? %) (name %) (str %))))
        hv (cond (map? hs) (some (fn [[k v]] (when (ra? k) v)) hs)
                 (sequential? hs) (some #(when (and (sequential? %) (ra? (first %))) (second %)) hs))
        ok? #(and (number? %) (not (Double/isNaN (double %))))
        ms #(cond (ok? %) (max 0 (long (Math/ceil (* 1000.0 (double %)))))
                  (string? %) (try (let [d (Double/parseDouble (str/trim %))] (when-not (Double/isNaN d) (max 0 (long (Math/ceil (* 1000.0 d)))))) (catch Throwable _ nil)))]
    (or (when (ok? (:retry-after-ms m)) (max 0 (long (:retry-after-ms m)))) (ms (:retry-after m)) (ms hv))))

(def ^:private model-error-rules
  [[:model/rate-limit 1 5000 #{429} #{:seon.model/rate-limited :model/rate-limit :rate-limited}
    ["rate limit" "rate_limit" "too many requests" "proxy_budget_exceeded" "budget_exceeded" "quota"]]
   [:model/auth nil 0 #{401 403} #{:seon.model/missing-api-key :model/auth :unauthorized :forbidden}
    ["api key" "api_key" "invalid_api_key" "unauthorized" "authentication" "forbidden"]]
   [:model/timeout 1 1000 #{408 504} #{:timeout :model/timeout} ["timeout" "timed out" "sockettimeout"]]
   [:model/api-error 1 1000 #{} #{:transport :network :connection-refused}
    ["connectexception" "unknownhost" "connection refused" "connection reset" "broken pipe" "eof"]]])

(defn classify-model-error
  "Pure classifier for HTTP 429/quota/auth/timeout/network failures."
  {:malli/schema [:=> [:cat [:maybe :map]] [:map [:kind [:enum :model/rate-limit :model/auth :model/timeout :model/api-error]]
                                            [:retryable? :boolean] [:retry-after-ms [:int {:min 0}]] [:message :string]]] :cost/ticks 2000000}
  [err-map]
  (let [em (if (map? err-map) err-map {}) ex (:exception em) exd (when (instance? Throwable ex) (ex-data ex))
        sr (or (:status em) (:status exd) (when (map? (:data em)) (:status (:data em)))) st (when (number? sr) (long sr))
        ekw (or (:error em) (:error exd)) body (if (contains? em :body) (:body em) (:body exd))
        bm (when (and (string? body) (not (str/blank? body)))
             (let [p (try (json/read-str body :key-fn keyword) (catch Throwable _ nil)) s (str/trim body)]
               (or (when (map? p) (or (get-in p [:error :message]) (:message p))) (if (> (count s) 200) (str (subs s 0 200) "…") s))))
        raw (or (when (string? (:message em)) (not-empty (str/trim (:message em))))
                (when (map? (:error em)) (not-empty (str (:message (:error em) ""))))
                (when (instance? Throwable ex) (not-empty (.getMessage ^Throwable ex)))
                bm (when (keyword? ekw) (name ekw)) (when st (str "Model HTTP " st)) "Upstream model error")
        msg (if (and bm (not (str/includes? raw bm)) (< (count raw) 120)) (str raw " (" bm ")") raw)
        low (str/lower-case (str msg " " (or body "") " " (or ekw "") " " (when (instance? Throwable ex) (.getName (class ex)))))
        [kind r? def-ms] (or (some (fn [[k r d sts kws ts]] (when (or (sts st) (kws ekw) (some #(str/includes? low %) ts)) [k (boolean r) d])) model-error-rules)
                             (if (and st (>= st 500)) [:model/api-error true 1000] [:model/api-error false 0]))]
    (cond-> {:kind kind :retryable? r? :message msg
             :retry-after-ms (long (or (parse-retry-ms (if (map? exd) (merge exd em) em)) def-ms))} st (assoc :status st))))

(defn- clip [s n] (let [t (str s)] (if (> (count t) n) (str (subs t 0 n) "…") t)))
(defn- kv-dl [pairs]
  (when-let [rows (seq (mapcat (fn [[k v]] (when (some? v) [[:dt k] [:dd (str v)]])) pairs))]
    (into [:dl {:class "kv-grid"}] rows)))

(defn render
  "Co-located `:seon.render/hiccup` renderer (`:human` and `:model`/`:agent`)."
  {:malli/schema [:function [:=> [:cat [:maybe :map]] [:vector :any]] [:=> [:cat [:maybe :map] [:maybe :map]] [:vector :any]]] :cost/ticks 2000000}
  ([ent] (render ent {:mode :human}))
  ([a b]
   (let [ent? #(and (map? %) (some (fn [k] (and (keyword? k) (= "seon.error" (namespace k)))) (keys %)))
         [ent opts] (cond (ent? a) [a (if (map? b) b {})] (ent? b) [b (if (map? a) a {})]
                          (and (map? a) (:mode a)) [(if (map? b) b {}) a] :else [(if (map? a) a {}) (if (map? b) b {})])
         model? (#{:model :agent} (:mode opts))
         {:seon.error/keys [kind severity source status message agent branch at data var eval id]} ent
         sev (or severity :error) st (or status :open) msg (str (or message "")) nm #(if (ident? %) (name %) (str %))
         dstr (when (and (map? data) (seq data)) (pr-str data))
         head [:div {:class "card-head"}
               [:div {:class "pill-row"}
                [:span {:class (str "badge " (if (= :warning sev) "amber" "red"))} (nm sev)]
                [:strong {:class "mono"} (str (or kind :error/unknown))] [:span {:class "badge blue"} (nm (or source :system))]
                [:span {:class (str "badge " (case st :resolved "green" :suppressed "muted" "amber"))} (nm st)]
                (when (and (not model?) (coll? (:tags opts))) (for [tg (sort-by str (:tags opts))] [:span {:class "badge amber mono"} (nm tg)]))]
               (when (or agent (and (not model?) at))
                 [:span {:class "muted tiny mono"} (str/join " · " (remove nil? [(when agent (str agent)) (when (and (not model?) at) (str "at " at))]))])]]
     (if model?
       [:div {:class "card tile-card compact-tile"} head (when-not (str/blank? msg) [:p {:class "sm-copy"} msg])
        (kv-dl [["agent" agent] ["branch" branch] ["var" var] ["retry-after-ms" (when (map? data) (:retry-after-ms data))] ["data" (when dstr (clip dstr 200))]])]
       [:div {:class "card tile-card"} head
        [:div {:class (if (= :warning sev) "notice sm-copy" "notice red sm-copy")} (if (str/blank? msg) "(no message)" msg)]
        (kv-dl (map (fn [k v] [(str ":seon.error/" (name k)) v]) [:agent :branch :var :eval :id] [agent branch var eval id]))
        (when dstr [:pre {:class "code-box tiny"} [:code (clip dstr 1200)]])
        (when (seq (:why opts)) [:p {:class "tiny muted"} (if (string? (:why opts)) (:why opts) (str/join " · " (:why opts)))])]))))

(defn error-rules
  "Declarative `:seon.rule/*` rules for open errors and 429 backpressure."
  {:malli/schema [:=> [:cat] [:vector :seon.rule/entity]] :cost/ticks 1000000}
  []
  (let [r (fn [id c s d w a] {:seon.rule/id id :seon.rule/doc d :seon.rule/category c :seon.rule/stratum s :seon.rule/enabled? true :seon.rule/target :any :seon.rule/when w :seon.rule/assert a})]
    [(r :rule.error/surface-open-agent-error :health 15 "Open agent errors."
        '[[?v :ctx/viewer ?aid] [:db :main [?err :seon.error/status :open]] [:db :main [?err :seon.error/agent ?aid]]
          [:db :main [?err :seon.error/kind ?kind]] [:db :main [?err :seon.error/severity ?sev]] [:db :main [?err :seon.error/message ?msg]]]
        '[[?v :rel/open-error ?err] [?v :error/has-open-error? true] [?err :ctx/kind :seon.error/entity] [?err :ctx/source :rule]
          [?err :seon.error/agent ?aid] [?err :seon.error/kind ?kind] [?err :seon.error/severity ?sev] [?err :seon.error/message ?msg]
          [?err :ctx/score 0.99] [?err :view/tag :tag/open-error] [?err :ctx/why (str "Open error on " ?aid ": " ?kind)]])
     (r :rule.error/rate-limit-backpressure :health 25 "429 backpressure."
        '[[?v :ctx/viewer ?aid] [:db :main [?err :seon.error/status :open]] [:db :main [?err :seon.error/kind :model/rate-limit]]
          [:optional [:db :main [?err :seon.error/data ?data]]] [:bind ?retry-ms (or (get ?data :retry-after-ms) 0)]]
        '[[?v :error/backpressure? true] [?v :error/backpressure-kind :model/rate-limit] [?v :error/backpressure-error ?err]
          [?v :error/retry-after-ms ?retry-ms] [?err :view/tag :tag/rate-limit-backpressure] [?err :ctx/why "Model 429 backpressure active"]])
     (r :rule.view/default-error :present 40 "Error card layout."
        '[[?e :ctx/kind :seon.error/entity] [?e :ctx/score _]]
        '[[?e :view/fn seon.error/render] [?e :view/zone :ephemeral-tail] [?e :view/layout :expanded-card] [?e :view/priority 98]])]))

(defn model-error-response
  "Builds structured `:response` with `:seon.error/*` entity on model failure."
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 2000000}
  [{:keys [err-map exception provider agent-id branch req-id]}]
  (let [d (when (instance? Throwable exception) (ex-data exception))
        cl (classify-model-error (cond-> (if (map? err-map) err-map {}) (map? d) (merge d) exception (assoc :exception exception)))
        data (cond-> {:retryable? (:retryable? cl) :retry-after-ms (:retry-after-ms cl)}
               req-id (assoc :req-id (str req-id)) (:status cl) (assoc :status (:status cl))
               (and (map? provider) (:model provider)) (assoc :model (str (:model provider)))
               (and (map? d) (:error d)) (assoc :raw-error (:error d)))
        ent (error-tx (cond-> {:kind (:kind cl) :severity :error :source :model :message (:message cl) :data data}
                        (keyword? agent-id) (assoc :agent agent-id) (keyword? branch) (assoc :branch branch)))]
    {:thought (str "Model error [" (name (:kind cl)) "]: " (:message cl)) :error (:kind cl)
     :seon.error/id (:seon.error/id ent) :seon.error/kind (:kind cl) :retryable? (:retryable? cl)
     :retry-after-ms (:retry-after-ms cl) :error-entity ent :tool-calls []}))

(defn model-error-outputs
  "Builds Flow `:response`/`:stream`/`:tx`/`::flow/error`/`::flow/report` on model error."
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 2000000}
  [msg]
  (let [resp (if (map? (:response msg)) (:response msg) {}) aid (:agent-id msg) req-id (:req-id msg)
        ent (or (when (and (map? (:error-entity resp)) (uuid? (:seon.error/id (:error-entity resp)))) (:error-entity resp))
                (:error-entity (model-error-response {:err-map resp :agent-id aid :branch (:branch msg) :req-id req-id})))
        kind (or (:seon.error/kind ent) (:seon.error/kind resp) (:error resp) :model/api-error)
        rms (long (or (first (filter number? [(:retry-after-ms resp) (get-in ent [:seon.error/data :retry-after-ms])])) 0))
        emsg (str (or (:seon.error/message ent) (:thought resp) "Upstream model error")) eid (:seon.error/id ent)
        ev (cond-> {:agent-id aid :req-id req-id :seon.error/kind kind :retry-after-ms rms :message emsg} eid (assoc :seon.error/id eid))]
    {:response [(assoc msg :response (assoc resp :error kind :seon.error/id eid :seon.error/kind kind :retry-after-ms rms :error-entity ent))]
     :stream [(assoc ev :status :error)] :tx [{:op :transact :tx-data [ent] :reason [:model-error aid kind]}]
     :clojure.core.async.flow/error [(assoc ev :proc :model)]
     :clojure.core.async.flow/report [(str "[:model] error on " aid " [" (name kind) "]" (when (pos? rms) (str " retry-after=" rms "ms")) ": " emsg)]}))
