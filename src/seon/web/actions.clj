(ns seon.web.actions
  "Stateless, `^:seon/callable` UI actions dispatched from Datastar `?form=` calls
   (holistic navigation, fractal inspector back/forward/trail, context lab prefs,
   live semantic search, branch ops, chat/eval forms) plus `/call` & `/mcp` dispatch."
  (:require [clojure.core.async :as a]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.data :as rdata]
            [seon.render.ui :as ui]
            [seon.store :as store]
            [seon.web.router :as router]
            [multipart.core :as multipart]
            [seon.web.mcp :as wmcp]))

(defn async-action
  {:malli/schema [:=> [:cat :map :map :any :any] :map]}
  [st req slot format-fn]
  {:st st :async {:req req :slot slot :format-fn format-fn}})

(defn ^:seon/callable toggle-sidebar!
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (async-action st {:op :show-user :toggle-sidebar? true} nil nil))

(defn ^:seon/callable toggle-theme!
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (async-action st {:op :show-user :toggle-theme? true} nil nil))

(defn ^:seon/callable toggle-expand!
  {:malli/schema [:=> [:cat :map :any] :map]}
  [st agent-id]
  (async-action st {:op :show-user :toggle-expand agent-id} nil nil))

(defn ^:seon/callable view-agent!
  {:malli/schema [:=> [:cat :map [:maybe :keyword] [:maybe :keyword]] :map]}
  [st agent-id mode]
  (async-action (assoc st :branch-feedback nil)
                {:op :show-user :viewing agent-id :mode (or mode :session) :clear-inspect true}
                nil nil))

(defn ^:seon/callable set-view-mode!
  {:malli/schema [:=> [:cat :map :keyword] :map]}
  [st mode]
  (async-action (assoc st :branch-feedback nil)
                {:op :show-user :mode mode :clear-inspect true}
                nil nil))

(defn ^:seon/callable switch-tab!
  {:malli/schema [:=> [:cat :map :keyword] :map]}
  [st tab]
  (set-view-mode! st (case tab (:schemas :health :branches) :system :agents :system :context)))

(defn ^:seon/callable inspect-entity!
  {:malli/schema [:=> [:cat :map :any] :map] :cost/ticks 200000}
  [st lookup-ref]
  (if-let [r (rdata/normalize-inspect-ref lookup-ref)]
    (async-action (assoc st :branch-feedback nil) {:op :show-user :push-inspect r} nil nil)
    {:st st}))

(defn ^:seon/callable inspect-back!
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 200000}
  [st]
  (async-action (assoc st :branch-feedback nil) {:op :show-user :pop-inspect true} nil nil))

(defn ^:seon/callable inspect-forward!
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 200000}
  [st]
  (async-action (assoc st :branch-feedback nil) {:op :show-user :forward-inspect true} nil nil))

(defn ^:seon/callable jump-inspect-trail!
  {:malli/schema [:=> [:cat :map :int] :map] :cost/ticks 200000}
  [st idx]
  (async-action (assoc st :branch-feedback nil) {:op :show-user :jump-trail (long idx)} nil nil))

(defn ^:seon/callable update-lab-prefs!
  {:malli/schema [:=> [:cat :map :map] :map] :cost/ticks 200000}
  [st patch]
  (async-action st {:op :show-user :lab-prefs (if (map? patch) patch {})} nil nil))

(defn- parse-d [s fb]
  (try (let [v (Double/parseDouble (str/trim (str s)))]
         (if (and (== v v) (not (Double/isInfinite v))) (max 0.0 (min 5.0 v)) (double fb)))
       (catch NumberFormatException _ (double fb))))

(defn ^:seon/callable update-lab-weights-form!
  {:malli/schema [:=> [:cat :map :map] :map] :cost/ticks 200000}
  [st form]
  (let [ws {:urgency (parse-d (:urgency form) 1.0) :recency (parse-d (:recency form) 0.75)
            :convergence (parse-d (:convergence form) 0.5) :semantic (parse-d (:semantic form) 0.5)}]
    (async-action st {:op :show-user :lab-prefs {:weights ws :weight-preset :custom}} nil nil)))

(defn ^:seon/callable toggle-lab-rule!
  {:malli/schema [:=> [:cat :map :qualified-keyword] :map] :cost/ticks 200000}
  [st rule-id]
  (async-action st {:op :show-user :toggle-lab-rule rule-id} nil nil))

(defn ^:seon/callable reset-lab-prefs!
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 200000}
  [st] (async-action st {:op :show-user :reset-lab-prefs true} nil nil))

(defn ^:seon/callable semantic-search-form!
  "Runs live vector search (`:op :embed-search`) across embedded entities."
  {:malli/schema [:=> [:cat :map :map] :map] :cost/ticks 500000}
  [st form]
  (let [raw (str/trim (str (:query form "")))
        of-s (str/trim (str (:of form "")))
        of-kw (when-not (str/blank? of-s)
                (try (let [k (edn/read-string of-s)] (when (qualified-keyword? k) k))
                     (catch RuntimeException _ nil)))
        q (if (str/starts-with? raw "[")
            (or (try (rdata/normalize-inspect-ref (edn/read-string raw))
                     (catch RuntimeException _ nil)) raw)
            raw)]
    (if (str/blank? raw)
      {:st (assoc st :semantic-search nil)}
      (async-action (assoc st :branch-feedback {:ok? true :text (str "Searching vectors for \"" raw "\"...")})
                    {:op :embed-search :query q :k 12 :opts (if of-kw {:of of-kw} {})}
                    :semantic-search #(assoc % :query raw)))))

(defn ^:seon/callable refresh-vector-status!
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 200000}
  [st] (async-action st {:op :vector-status} :vector-status identity))

(defn ^:seon/callable power-loss-reboot!
  {:malli/schema [:=> [:cat [:map [:conn {:optional true} :seon.host/conn]]] :map]}
  [st]
  (when-let [pl (:on-power-loss st)] (a/thread (pl)))
  {:st st})

(defn ^:seon/callable run-tests!
  {:malli/schema [:=> [:cat :map :any] :map]}
  [st branch]
  (async-action (assoc st :branch-feedback {:ok? true :text (str "Running reach tests on " branch "...")})
                {:op :test :branch branch} :branch-feedback
                (fn [ans]
                  {:ok? (:ok? ans)
                   :text (str "Tests on " branch ": " (if (:ok? ans) "PASS" "FAIL") " (" (:ran ans 0) " ran, " (:cached ans 0) " cached"
                              (when (:short-circuited? ans) (str ", fail-fast skipped " (:skipped-slow ans) " slow")) ")")})))

(defn ^:seon/callable merge-branch!
  {:malli/schema [:=> [:cat :map :keyword] :map]}
  [st branch]
  (let [srv (:server-in-ch st) ev-ch (or (:ui-ch st) (:event-ch st))]
    (when (and srv ev-ch)
      (a/go
        (let [ask! (fn [req]
                     (let [rc (a/chan 1)]
                       (a/>! srv {:req req :reply-ch rc})
                       (or (first (a/alts! [rc (a/timeout (router/op-timeout-ms req))])) {:ok? false :error :timeout})))
              t-ans (ask! {:op :test :branch branch})
              fb (if-not (:ok? t-ans)
                   {:ok? false :text (str "Merge blocked on " branch ": tests failed ("
                                          (or (:error t-ans) (str (:ran t-ans 0) " ran, " (count (remove :seon.test/pass? (:results t-ans))) " failed")) ")")}
                   (let [m-ans (ask! {:op :merge :branch branch})]
                     {:ok? (:ok? m-ans)
                      :text (if (:ok? m-ans)
                              (str "Tested & merged " branch " -> :main @ tx " (:tx m-ans) " (" (count (:touched-paths m-ans)) " files)")
                              (str "Blocked / Conflict: " (pr-str (or (:conflicts m-ans) (:error m-ans)))))}))]
          (a/>! ev-ch {:ev :action-done :slot :branch-feedback :val fb}))))
    (assoc st :branch-feedback {:ok? true :text (str "Running reach tests before merging " branch " -> :main...")})))

(defn ^:seon/callable assign-health-fix!
  {:malli/schema [:=> [:cat :map :keyword :any :any] :map]}
  [st cls subject message]
  (let [tid (keyword "seon.health" (str "fix-" (mod (System/currentTimeMillis) 100000)))
        scope-ns (try (let [s (str subject)] (if (str/includes? s "/") (symbol (first (str/split s #"/" 2))) 'seon)) (catch Throwable _ 'seon))]
    (async-action
     (assoc st :branch-feedback {:ok? true :text (str "Spawned worker for health issue " cls " on " subject)})
     {:op :create-task :id tid :scope scope-ns :title (str "Fix " (name cls) " on " subject)
      :plan (str message) :spawn-agent? true :role :worker}
     nil nil)))

(defn ^:seon/callable spawn-agent-form!
  {:malli/schema [:function [:=> [:cat [:map [:conn :seon.host/conn]] :map] :map] [:=> [:cat [:map [:conn {:optional true} :seon.host/conn]] :any :map] :map]]}
  ([st form] (spawn-agent-form! st (ui/entry-agent-id @(:conn st)) form))
  ([st parent-id form]
   (let [aid (wmcp/parse-kw-input (:agent_id form) (keyword "agent" (str "w" (mod (System/currentTimeMillis) 10000))))
         branch (keyword "branch" (str (or (namespace aid) "agent") "." (name aid)))
         scope (when-not (str/blank? (:scope form)) (symbol (str/trim (:scope form))))
         goal (str/trim (str (:goal form "")))]
     (async-action
      (assoc st :branch-feedback {:ok? true :text (str "Spawned " aid " under " parent-id " on " branch)})
      (cond-> {:op :spawn-agent :id aid :parent parent-id :branch branch :goal goal}
        scope (assoc :scope scope))
      nil nil))))

(defn ^:seon/callable jump-canvas-live!
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (async-action st {:op :show-user :as-of-tx :live} nil nil))

(defn ^:seon/callable dismiss-feedback!
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  {:st (assoc st :branch-feedback nil)})

(def mcp-tools wmcp/mcp-tools)

(defn ^:seon.flow/edge ^{:seon.code/reexport 'seon.web.mcp/call-server-edge!} call-server-edge! [& args] (apply wmcp/call-server-edge! args))

(defn ^:seon.flow/edge ^{:seon.code/reexport 'seon.web.mcp/handle-mcp-jsonrpc!} handle-mcp-jsonrpc! [& args] (apply wmcp/handle-mcp-jsonrpc! args))

(defn ^:seon/callable toggle-vision!
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]]] :map]}
  [st]
  (let [db @(:conn st) {:keys [viewing-id]} (ui/user-view-state db)
        cur (boolean (store/config-val db viewing-id :seon.config/viewport-screenshot?))]
    (async-action st {:op :set-config :agent-id viewing-id :key :seon.config/viewport-screenshot? :value (not cur)} nil nil)))

(defn ^:seon/callable set-viewport!
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] number? number?] :map]}
  [st w h]
  (let [cw (long (max 200 (min 10000 (Math/round (double w)))))
        ch (long (max 200 (min 10000 (Math/round (double h)))))
        db @(:conn st)
        cur (when (and db (contains? (or (:schema db) (d/schema db) {}) :seon.user/id))
              (d/pull db (quote [:seon.user/viewport-w :seon.user/viewport-h]) [:seon.user/id :user/default]))]
    (if (and (= cw (:seon.user/viewport-w cur)) (= ch (:seon.user/viewport-h cur)))
      {:st st}
      (async-action st {:op :transact :tx-data [{:seon.user/id :user/default :seon.user/viewport-w cw :seon.user/viewport-h ch}]} nil nil))))

(defn ^:seon/callable pin-to-agent!
  "Pins any Datahike lookup ref onto target agent's `:seon.agent/pinned` walk."
  {:malli/schema [:function [:=> [:cat [:map [:conn :seon.host/conn]] :keyword [:vector :any]] :map]
                  [:=> [:cat [:map [:conn :seon.host/conn]] :keyword [:vector :any] :boolean] :map]]}
  ([st target-agent-id lookup-ref] (pin-to-agent! st target-agent-id lookup-ref false))
  ([st target-agent-id lookup-ref jump?]
   (let [db @(:conn st)
         ref (or (rdata/normalize-inspect-ref lookup-ref) lookup-ref)
         cur (vec (or (:seon.agent/pinned (d/pull db (quote [:seon.agent/pinned]) [:seon.agent/id target-agent-id])) []))
         nxt (if (some #(= % ref) cur) cur (conj cur ref))
         tx (cond-> [{:seon.agent/id target-agent-id :seon.agent/pinned nxt}]
              jump? (conj {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id target-agent-id] :seon.user/view-mode :session}))]
     (async-action (assoc st :branch-feedback {:ok? true :text (str "Pinned " (pr-str ref) " onto " target-agent-id "'s walk.")})
                   {:op :transact :tx-data tx} nil nil))))

(defn ^:seon/callable pin-ref-form!
  {:malli/schema [:=> [:cat :map :keyword :map] :map]}
  [st agent-id form]
  (let [parsed (try (rdata/normalize-inspect-ref (edn/read-string (str/trim (str (:ref form "")))))
                    (catch RuntimeException _ nil))]
    (if-not parsed
      {:st (assoc st :branch-feedback {:ok? false :text "Pin expects a lookup ref vector like [:seon.code.ns/name (quote seon.web)]"})}
      (pin-to-agent! st agent-id parsed false))))

(defn parse-kv-pairs
  {:malli/schema [:=> [:cat [:maybe :string]] :map], :cost/ticks 500000, :cost/bytes 262144}
  [s]
  (if (str/blank? s) {}
      (into {} (keep (fn [part]
                       (when (str/includes? part "=")
                         (let [[k v] (str/split part #"=" 2)]
                           (when (seq k)
                           (let [dec (fn [x] (try (java.net.URLDecoder/decode (or x "") "UTF-8")
                                             (catch IllegalArgumentException _ (or x ""))
                                             (catch IndexOutOfBoundsException _ (or x ""))))]
                             [(keyword (dec k)) (dec v)]))))))
            (str/split s #"&"))))

(defn parse-multipart-fields
{:malli/schema [:=> [:cat [:maybe :string] :any] :map], :cost/ticks 2000000, :cost/bytes 2097152}
[ct body]
(if (or (str/blank? (str ct)) (nil? body))
  {}
  (let [b (if (string? body) (.getBytes ^String body "UTF-8") body)
        {:keys [params]} (multipart/parse-form-data {:content-type (str ct) :body b} {:check-complete false :ignore-errors true})]
    (into {} (map (fn [[k v]] [(keyword k) v])) params))))

(defn parse-form-body
{:malli/schema [:=> [:cat :map] :map]}
[req]
(let [hdrs (:headers req)
      raw-ct (when (map? hdrs)
               (some (fn [[k v]] (when (= "content-type" (str/lower-case (str k))) v)) hdrs))
      ct (str/lower-case (str (or raw-ct "")))
      raw-body (:body req)]
  (cond
    (nil? raw-body) {}
    (str/includes? ct "multipart/form-data") (parse-multipart-fields raw-ct raw-body)
    :else
    (let [body-str (if (string? raw-body) raw-body (slurp raw-body))
          trimmed (str/trim body-str)]
      (cond (str/blank? trimmed) {}
            (str/includes? ct "application/x-www-form-urlencoded") (parse-kv-pairs body-str)
            (or (str/includes? ct "application/json") (str/starts-with? trimmed "{") (str/starts-with? trimmed "[")) {}
            :else (parse-kv-pairs body-str))))))

(defn ^:seon/callable spawn-on-entity-form!
  "Spawns a worker focused on `focus-ref` with optional prompt."
  {:malli/schema [:function [:=> [:cat :map :any] :map] [:=> [:cat :map :any :map] :map]] :cost/ticks 500000}
  ([st focus-ref] (spawn-on-entity-form! st focus-ref {}))
  ([st focus-ref form]
   (if-let [fref (rdata/normalize-inspect-ref focus-ref)]
     (async-action
      (assoc st :branch-feedback {:ok? true :text (str "Starting agent focused on " (rdata/entity-short-label fref) "...")})
      {:op :spawn-on-entity :focus-ref fref :prompt (str/trim (str (or (:prompt form) (:text form) ""))) :role :worker :parent :user}
      :branch-feedback #(when-not (:ok? %) {:ok? false :text (str "Spawn failed: " (or (:error %) "unknown error"))}))
     {:st (assoc st :branch-feedback {:ok? false :text "Cannot spawn agent: invalid entity reference."})})))

(defn ^:seon/callable navigate-url!
  "Navigates to bookmarkable URL `path` via Reitit, with optional `opts` (e.g. `{:nav-op :pop}`)."
  {:malli/schema [:function [:=> [:cat :map :string] :map] [:=> [:cat :map :string [:maybe :map]] :map]]
   :cost/ticks 500000}
  ([st path] (navigate-url! st path nil))
  ([st path opts]
   (if-let [op (router/path->show-user-op (when-let [c (:conn st)] @c) path)]
     (let [op2 (if (and (map? opts) (:nav-op opts)) (assoc op :nav-op (:nav-op opts)) op)]
       (async-action (assoc st :branch-feedback nil) op2 nil nil))
     {:st st})))

(defn ^:seon/callable toggle-expert-mode!
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]]] :map] :cost/ticks 200000}
  [st]
  (let [cur (boolean (:expert-mode? (ui/user-view-state @(:conn st))))]
    (async-action st {:op :transact :reason [:toggle-expert-mode (not cur)]
                      :tx-data [{:seon.user/id :user/default :seon.user/expert-mode? (not cur)}]} nil nil)))

(defn- viewing-history-txs [conn]
  (let [db @conn {:keys [viewing-id as-of-tx]} (ui/user-view-state db)
        branch (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id viewing-id])) :main)
        bdb (or (try (store/head conn branch) (catch Throwable _ nil)) db)]
    {:as-of-tx as-of-tx :txs (ui/canvas-history-txs bdb db viewing-id)}))

(defn ^:seon/callable jump-canvas-tx!
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] [:maybe :int]] :map]}
  [st tx]
  (let [{:keys [as-of-tx txs]} (viewing-history-txs (:conn st))
        tgt (if (or (nil? tx) (= tx (last txs))) :live (long tx))]
    (async-action st (cond-> {:op :show-user :as-of-tx tgt}
                       (and as-of-tx (integer? tgt)) (assoc :nav-op :replace)) nil nil)))

(defn ^:seon/callable step-canvas-history!
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] :int] :map]}
  [st delta]
  (let [{:keys [as-of-tx txs]} (viewing-history-txs (:conn st)) n (count txs)]
    (if (< n 2)
      {:st st}
      (let [cur-idx (if as-of-tx (or (first (keep-indexed #(when (= %2 as-of-tx) %1) txs)) (dec n)) (dec n))
            nxt-idx (max 0 (min (dec n) (+ cur-idx (long delta))))
            tgt (if (= nxt-idx (dec n)) :live (nth txs nxt-idx))]
        (async-action st (cond-> {:op :show-user :as-of-tx tgt}
                           (and as-of-tx (integer? tgt)) (assoc :nav-op :replace)) nil nil)))))

(defn- clj-expr? [s]
  (or (boolean (re-find #"^[\(\[\{'#@]" s))
      (and (str/starts-with? s ":") (not (str/includes? s " ")))))

(defn ^:seon/callable eval-snippet!
  "Evaluates Clojure snippet `code` on the viewed agent branch via `:op :eval` into `:branch-feedback`."
  {:malli/schema [:=> [:cat :map :string] :map] :cost/ticks 500000}
  [st code]
  (let [s (str/trim (str (or code "")))]
    (if (str/blank? s)
      {:st (assoc st :branch-feedback {:ok? false :text "Empty snippet."})}
      (let [db (when-let [c (:conn st)] @c)
            aid (when db (:viewing-id (ui/user-view-state db)))
            br (or (when (and db aid) (:seon.agent/branch (d/pull db (quote [:seon.agent/branch]) [:seon.agent/id aid]))) :branch/repl)
            clip (fn [t n] (if (> (count t) n) (str (subs t 0 (- n 3)) "...") t))]
        (async-action
         (assoc st :branch-feedback {:ok? true :text (str "Evaluating snippet on " br "...")})
         {:op :eval :branch br :agent-id (or aid :agent/repl) :code s :keep-warm? true}
         :branch-feedback
         (fn [ans]
           (if (:ok? ans)
             (let [o (not-empty (str/trim (str (or (:out ans) ""))))]
               {:ok? true :text (str (when (:handle ans) (str (:handle ans) " ")) "=> "
                                     (clip (str (or (:value ans) "nil")) 240)
                                     (when o (str " | out: " (clip o 160))))})
             {:ok? false :text (str "Eval error on " br ": " (or (:error ans) "unknown error"))})))))))

(defn- prev-view-mode [vs exclude-modes]
  (or (some (fn [e]
              (let [m (rdata/canonical-view-mode (:mode e))]
                (when (and (not (contains? exclude-modes m)) (nil? (:inspect-ref e))) m)))
            (rseq (vec (:nav-back vs))))
      :canvas))

(defn ^:seon/callable set-explain-lens!
  "Switches between Explore (`explain? false`) and Explain (`explain? true`, `:context`), preserving previous mode."
  {:malli/schema [:function [:=> [:cat :map :boolean] :map] [:=> [:cat :map :boolean :any] :map]] :cost/ticks 200000}
  ([st explain?] (set-explain-lens! st explain? nil))
  ([st explain? focus-ref]
   (let [nr (when focus-ref (rdata/normalize-inspect-ref focus-ref))
         cur-vs (when-let [c (:conn st)] (ui/user-view-state @c))
         cur-ref (:inspect-ref cur-vs)
         req (cond
               explain?
               (cond-> {:op :show-user :mode :context :keep-inspect? true} nr (assoc :inspect-ref nr))
               (and (nil? nr) (= [:seon.system/id :root] cur-ref))
               {:op :show-user :mode :system :clear-inspect true}
               (or nr cur-ref)
               (cond-> {:op :show-user :mode :inspect :keep-inspect? true} nr (assoc :inspect-ref nr))
               :else
               {:op :show-user :mode (prev-view-mode cur-vs #{:context :inspect}) :clear-inspect true})]
     (async-action (assoc st :branch-feedback nil) req nil nil))))

(defn ^:seon/callable inspect-close!
  "Exits Inspector back to the last non-inspect mode (default `:canvas`)."
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 200000}
  [st]
  (let [vs (when-let [c (:conn st)] (ui/user-view-state @c))
        m (prev-view-mode vs #{:inspect})]
    (async-action (assoc st :branch-feedback nil) {:op :show-user :clear-inspect true :mode m} nil nil)))

(defn- expert-enabled? [vs]
  (boolean (or (:expert-mode? vs) (get-in vs [:lab-prefs :expert-mode?]))))

(defn ^:seon/callable chat-agent-form!
  {:malli/schema [:function [:=> [:cat :map :map] :map] [:=> [:cat :map [:maybe :keyword] :map] :map]], :seon.flow/edge true}
  ([st form] (chat-agent-form! st (ui/entry-agent-id @(:conn st)) form))
  ([st agent-id form]
   (let [db (when-let [c (:conn st)] @c)
         vs (when db (ui/user-view-state db))
         txt (str/trim (str (or (:text form) (:code form) "")))
         aid (or agent-id (when db (ui/entry-agent-id db)))
         br (or (when (and db aid) (:seon.agent/branch (d/pull db (quote [:seon.agent/branch]) [:seon.agent/id aid]))) :branch/repl)]
     (cond
       (str/blank? txt) {:st st}
       (and (expert-enabled? vs) (clj-expr? txt))
       (async-action (assoc st :branch-feedback nil) {:op :eval :branch br :agent-id aid :code txt :keep-warm? true}
                     :branch-feedback #(when-not (:ok? %) {:ok? false :text (str "Eval error: " (:error %))}))
       :else
       (do (when (and (:as-of-tx vs) (:server-in-ch st)) (a/put! (:server-in-ch st) {:req {:op :show-user :as-of-tx :live}}))
           (async-action (assoc st :branch-feedback nil) {:op :send-message :from :user :to aid :type :chat :body txt} nil nil))))))

(defn ^:seon/callable eval-agent-form!
  {:malli/schema [:=> [:cat :map :any :any :map] :map]}
  [st agent-id branch form]
  (let [s (str/trim (str (:code form "")))
        vs (when-let [c (:conn st)] (ui/user-view-state @c))
        exp? (if vs (expert-enabled? vs) true)]
    (cond
      (str/blank? s) {:st st}
      (and exp? (clj-expr? s))
      (async-action (assoc st :branch-feedback nil) {:op :eval :branch branch :agent-id agent-id :code s :keep-warm? true}
                    :branch-feedback #(when-not (:ok? %) {:ok? false :text (str "Error: " (:error %))}))
      :else
      (async-action (assoc st :branch-feedback nil) {:op :send-message :from :user :to agent-id :type :chat :body s} nil nil))))

(defn invoke-call!
"Dispatches Reagent closure or `^:seon/callable` form; always passes `{}` form map to `-form!` handlers."
{:malli/schema [:=> [:cat [:map [:conn {:optional true} :seon.host/conn]] :map] :map]}
[st {:keys [hkey call-form form-fields]}]
(let [wrap (fn [res] (if (and (map? res) (contains? res :st)) (assoc res :ok? true)
                         {:ok? true :st (if (and (map? res) (contains? res :conn)) res st)}))]
  (try
    (cond
      hkey
      (if-let [hfn (get-in st [:walk :handlers hkey])]
        (wrap (if (seq form-fields) (hfn st form-fields) (hfn st)))
        {:ok? false :error :seon.web/stale-handler :handler hkey :st st})
      (and (seq? call-form) (qualified-symbol? (first call-form)))
      (let [sym (first call-form) v (resolve sym)]
        (if-not (and v (:seon/callable (meta v)))
          {:ok? false :error :seon.web/not-callable :message (str sym " is not marked ^:seon/callable") :fn sym :st st}
          (let [base-args (vec (rest call-form))
                pass-form? (or (seq form-fields) (str/ends-with? (name sym) "-form!"))
                args (if pass-form? (conj base-args (if (map? form-fields) form-fields {})) base-args)]
            (wrap (apply @v st args)))))
      :else {:ok? false :error :seon.web/malformed-call :form call-form :st st})
    (catch Throwable ex
      {:ok? false :error :seon.web/handler-error :message (or (.getMessage ex) (str ex)) :st st}))))