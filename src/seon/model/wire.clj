(ns seon.model.wire
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [jolt.http.core :as http]
            [seon.render :as render]
            [seon.render.fit :as fit]
            [seon.schema :as schema]
            [seon.schema.index :as six]))

(def tool-name "repl")

(defn make-stream-state
  "Fresh mutable SSE accumulation state (`StringBuilder`s avoid `O(n^2)` concat on long streams)."
  {:malli/schema [:=> [:cat] :map] :cost/ticks 5000 :cost/bytes 4096}
  []
  {:content (StringBuilder.)
   :reasoning (StringBuilder.)
   :tool-calls {}
   :finish-reason nil
   :usage nil})

(defn parse-sse-line
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :map]], :cost/ticks 200, :cost/bytes 400}
  [^String line]
  (when (and line (str/starts-with? line "data:"))
    (let [payload (str/trim (subs line 5))]
      (when (and (not (str/blank? payload)) (not= payload "[DONE]"))
        (try (json/read-str payload :key-fn keyword)
             (catch Throwable _ nil))))))

(defn process-sse-chunk
  "Purely accumulates one decoded SSE JSON chunk into `state`, returning `[state' delta-event-or-nil]`."
  {:malli/schema [:=> [:cat [:maybe :map] :map] [:tuple :map [:maybe :map]]] :cost/ticks 500000 :cost/bytes 1048576}
  [chunk state]
  (let [state0 (-> state
                   (update :content #(or % (StringBuilder.)))
                   (update :reasoning #(or % (StringBuilder.)))
                   (update :tool-calls #(or % {})))
        choice (first (:choices chunk))
        delta (:delta choice)
        state1 (cond-> state0
                 (:usage chunk) (assoc :usage (:usage chunk))
                 (:finish_reason choice) (assoc :finish-reason (:finish_reason choice)))
        state2 (if-let [tcs (:tool_calls delta)]
                 (assoc state1 :tool-calls
                        (reduce
                         (fn [acc tc]
                           (let [idx (or (:index tc) (:id tc) (count acc))
                                 prev (get acc idx {:id nil :name "" :args (StringBuilder.)})
                                 sb (or (:args prev) (StringBuilder.))]
                             (when-let [a (get-in tc [:function :arguments])]
                               (.append ^StringBuilder sb ^String a))
                             (assoc acc idx
                                    {:id (or (:id tc) (:id prev))
                                     :name (str (:name prev) (or (get-in tc [:function :name]) ""))
                                     :args sb})))
                         (:tool-calls state1)
                         tcs))
                 state1)
        rtext (not-empty (:reasoning_content delta))
        ctext (not-empty (:content delta))]
    (when rtext (.append ^StringBuilder (:reasoning state2) ^String rtext))
    (when ctext (.append ^StringBuilder (:content state2) ^String ctext))
    [state2
     (cond
       ctext {:kind :token :text ctext}
       rtext {:kind :reasoning-token :text rtext}
       :else nil)]))

(defn parse-tool-args
  "Parses JSON tool-call argument text into a keywordized map, wrapping non-map JSON or raw strings as `{:code s}`."
  {:malli/schema [:=> {:examples [[["{\"code\":\"(+ 1 2)\"}"] {:code "(+ 1 2)"}]
                                  [["4"] {:code "4"}]
                                  [[""] {}]
                                  [["raw"] {:code "raw"}]]}
                  [:cat :string] :map]
   :cost/ticks 50000
   :cost/bytes 131072}
  [^String s]
  (if (str/blank? s)
    {}
    (try
      (let [v (json/read-str s :key-fn keyword)]
        (if (map? v) v {:code s}))
      (catch Throwable _ {:code s}))))

(defn- sb-str
  "Nil-tolerant string coercion for `StringBuilder` accumulators (also accepts plain strings)."
  {:malli/schema [:=> [:cat :any] :string], :cost/ticks 5000, :cost/bytes 4096}
  [x]
  (if (nil? x) "" (str x)))

(defn finalize-stream-state
  "Materializes the accumulated `StringBuilder` SSE state into our clean internal completion map."
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 500000 :cost/bytes 1048576}
  [{:keys [content reasoning tool-calls finish-reason usage]}]
  (let [tcs (->> (sort-by key (or tool-calls {}))
                 (mapv (fn [[idx {:keys [id name args]}]]
                         (let [parsed (parse-tool-args (sb-str args))]
                           {:id (or id (str "call-" idx))
                            :tool (or (not-empty name) tool-name)
                            :intent (:intent parsed)
                            :code (or (:code parsed) "")}))))
        thought (sb-str content)
        reasoning-str (sb-str reasoning)
        norm-usage (when usage
                     (cond-> {}
                       (:prompt_tokens usage) (assoc :prompt-tokens (:prompt_tokens usage))
                       (:completion_tokens usage) (assoc :completion-tokens (:completion_tokens usage))
                       (:prompt_cache_hit_tokens usage) (assoc :cache-hit-tokens (:prompt_cache_hit_tokens usage))
                       (:prompt_cache_miss_tokens usage) (assoc :cache-miss-tokens (:prompt_cache_miss_tokens usage))
                       (get-in usage [:prompt_tokens_details :cached_tokens])
                       (assoc :cache-hit-tokens (get-in usage [:prompt_tokens_details :cached_tokens]))))]
    (cond-> {:thought thought
             :finish-reason finish-reason
             :tool-calls tcs}
      (pos? (count reasoning-str)) (assoc :reasoning reasoning-str)
      (seq norm-usage) (assoc :usage norm-usage))))

(defn http-post!
  "Pure fiber-parking HTTP POST via `jolt.http.core` (`headers` is `[[k v] ...]`)."
  {:malli/schema [:function [:=> [:cat :string [:sequential [:tuple :string :string]] :string] :map]]}
  [url headers ^String body]
  (let [u (http/parse-url url)
        s (http/connect-stream (http/tget u :host) (http/effective-port u) (= "https" (http/tget u :protocol)) false nil nil)]
    (try
      (http/s-write s (http/build-request "POST" u headers body))
      (let [r (http/read-response s nil "POST" nil true false)
            bs (if-let [pull (:body-pull r)]
                 (http/concat-bas (loop [acc []] (if-let [b (pull)] (recur (conj acc b)) acc)))
                 (or (:body r) (byte-array 0)))]
        {:status (:status r) :headers (:header-pairs r) :body (String. ^bytes bs "UTF-8")})
      (finally
        (http/s-close s)))))

(defn prefix-sha256
  "Returns a 16-char lowercase hex SHA-256 digest of string `s` for deterministic KV-cache prefix hashing."
  {:malli/schema [:=> {:examples [[["hello"] "2cf24dba5fb0a30e"]]}
                  [:cat :string] :string]
   :cost/ticks 200000
   :cost/bytes 131072}
  [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes (str s) "UTF-8"))
        sb (StringBuilder. 16)]
    (dotimes [i 8]
      (let [b (bit-and 0xff (aget bs i))]
        (when (< b 16) (.append sb \0))
        (.append sb (Integer/toHexString b))))
    (.toString sb)))

(defn http-get!
  "Pure fiber-parking HTTP GET via `jolt.http.core` (`headers` is `[[k v] ...]`)."
  {:malli/schema [:function [:=> [:cat :string [:sequential [:tuple :string :string]]] :map]], :seon.gen/skip true}
  [url headers]
  (let [u (http/parse-url url)
        s (http/connect-stream (http/tget u :host) (http/effective-port u) (= "https" (http/tget u :protocol)) false nil nil)]
    (try
      (http/s-write s (http/build-request "GET" u headers nil))
      (let [r (http/read-response s nil "GET" nil true false)
            bs (if-let [pull (:body-pull r)]
                 (http/concat-bas (loop [acc []] (if-let [b (pull)] (recur (conj acc b)) acc)))
                 (or (:body r) (byte-array 0)))]
        {:status (:status r) :headers (:header-pairs r) :body (String. ^bytes bs "UTF-8")})
      (finally
        (http/s-close s)))))

(defn fetch-model-context-window!
  "Queries OpenAI-compat `GET <base-url>/models` to dynamically discover the active model's
   `context_window` / `context_length` (returns positive long or nil; never hardcoded)."
  {:malli/schema [:=> [:cat :map] [:maybe :int]], :seon.gen/skip true}
  [{:keys [base-url model api-key-env]}]
  (when (and (string? base-url) (not (str/blank? base-url)) (string? model))
    (try
      (let [api-key (when api-key-env (System/getenv (str api-key-env)))
            url (str (str/replace base-url #"/+$" "") "/models")
            hdrs (cond-> [["Accept" "application/json"]]
                   (and api-key (not (str/blank? api-key)))
                   (conj ["Authorization" (str "Bearer " api-key)]))
            resp (http-get! url hdrs)]
        (when (= 200 (:status resp))
          (let [parsed (json/read-str (:body resp) :key-fn keyword)
                models (:data parsed)
                m (some #(when (= model (:id %)) %) models)
                cw (or (:context_window m) (:context_length m) (:max_context_length m) (:input_token_limit m))]
            (when (and (number? cw) (pos? (long cw)))
              (long cw)))))
      (catch Throwable _ nil))))

(defn- edn-tag-val [tag v]
  (case tag
    datahike/DB (with-meta (if (map? v) v {:value v}) {:seon.print/kind :datahike/db})
    datahike/AsOfDB (with-meta (if (map? v) v {:value v}) {:seon.print/kind :datahike/as-of-db})
    datahike/HistoricalDB (with-meta (if (map? v) v {:value v}) {:seon.print/kind :datahike/historical-db})
    datahike/Connection (with-meta (if (vector? v) {:store-id (first v) :branch (second v) :writer (nth v 2 nil)} {:value v})
                                   {:seon.print/kind :datahike/conn})
    (if (instance? clojure.lang.IObj v)
      (with-meta v {:seon.print/tag tag})
      {:seon.print/tag tag :value v})))

(defn- safe-read-eval-edn [tag-fn val-str]
  (try {:ok? true :val (edn/read-string {:default tag-fn} val-str)}
       (catch Throwable ex {:ok? false :error (or (.getMessage ex) (str ex))})))

(defn- choose-eval-db [db fallback-db parsed sch]
  (let [ok? #(and (instance? datahike.db.interface.ISearch %) (map? (:schema %)))]
    (cond
      (and (ok? db) (ok? fallback-db) (not= db fallback-db))
      (let [ix (schema/entity-index db)
            known? (or (and (qualified-keyword? sch) (contains? (:forms ix) sch))
                       (and (vector? parsed) (= 2 (count parsed)) (qualified-keyword? (first parsed))
                            (boolean (get-in ix [:identity-schemas (first parsed)])))
                       (and (map? parsed) (boolean (seq (six/facets-of ix parsed)))))]
        (if known? db fallback-db))
      (ok? db) db
      (ok? fallback-db) fallback-db
      :else nil)))

(defn- detect-schema-target [db parsed sch]
  (when (and db (instance? datahike.db.interface.ISearch db) (map? (:schema db)))
    (let [ix (schema/entity-index db)]
      (cond
        (and (vector? parsed) (= 2 (count parsed)) (qualified-keyword? (first parsed)))
        (when-let [sk (or (when (qualified-keyword? sch) sch)
                          (get-in ix [:identity-schemas (first parsed)]))]
          (let [pulled (when (contains? (:schema db) (first parsed))
                         (d/pull db '[*] parsed))]
            {:schema sk :ent (if (and (map? pulled) (seq (dissoc pulled :db/id))) pulled parsed)}))

        (map? parsed)
        (let [ms (six/most-specific ix parsed)
              sk (or (when (qualified-keyword? sch) sch)
                     (when (instance? clojure.lang.IMeta parsed)
                       (let [m (:seon/schema (meta parsed))]
                         (when (qualified-keyword? m) m)))
                     (when (qualified-keyword? ms) ms))]
          (when sk {:schema sk :ent parsed}))

        :else nil))))

(defn- cap-eval-text [^String s cap h-str]
  (if (<= (count s) cap)
    s
    (let [tail (str "\n... (" (- (count s) (max 100 (- cap 140))) " chars omitted; (user/expand! '" h-str "))")
          keep-n (max 0 (min (count s) (- cap (count tail))))]
      (if (pos? keep-n)
        (str (subs s 0 keep-n) tail)
        (subs tail 0 (min cap (count tail)))))))

(defn- render-eval-val-text [eff-db parsed sch eff-opts cap h-str]
  (if (and (string? parsed)
           (<= (count parsed) (min cap 2000))
           (<= (count (str/split-lines parsed)) 20))
    parsed
    (let [target (detect-schema-target eff-db parsed sch)
          sk (:schema target)
          ent (:ent target)
          disc (when (and eff-db sk (map? ent)) (fit/discover-renderers eff-db [sk]))
          hiccup (cond
                   (seq disc)
                   (let [fp (fit/fit-projections eff-db
                                                 [{:entity (or (:db/id ent) :eval) :schema sk :data ent}]
                                                 {:target :wire :token-budget (max 128 (min 1500 (quot cap 4)))})]
                     (or (get-in fp [:selected 0 :hiccup])
                         (render/render-value eff-db (with-meta ent {:seon/schema sk}) eff-opts)))

                   (and eff-db sk (map? ent))
                   (render/render-value eff-db (with-meta ent {:seon/schema sk}) eff-opts)

                   :else
                   (render/render-value eff-db parsed eff-opts))]
      (cap-eval-text (render/hiccup->text hiccup) cap h-str))))

(defn format-eval-output
  "Formats a single REPL eval outcome via `seon.render.fit/discover-renderers` +
   `seon.render/render-value -> seon.render/hiccup->text` (single Hiccup -> text pipeline).
   Bounds large outputs structurally pointing to the live `result/e<n>` handle."
  {:malli/schema [:=> {:examples [[[{:handle 'result/e1 :ok? true :ms 1.5 :ticks 500 :value "42"}]
                                   "; result/e1 · ok · 1.5 ms · 500 ticks\n42"]]}
                  [:cat :map] :string]
   :cost/ticks 500000
   :cost/bytes 1048576}
  [{:keys [handle ok? value out error ms ticks result-schema schema branch branch-tx max-chars db main-db fallback-db]}]
  (let [eff-ok? (if (some? ok?) (boolean ok?) (nil? error))
        cap (long (max 256 (if (number? max-chars) max-chars 8000)))
        h-str (if handle (str handle) "result/e?")
        sch (or result-schema schema)
        ticks-str (when (and (number? ticks) (pos? (long ticks)))
                    (let [t (long ticks)]
                      (if (>= t 1000) (str (quot t 1000) "k ticks") (str t " ticks"))))
        hdr (str "; "
                 (str/join " · "
                           (remove nil?
                                   [(when handle (str handle))
                                    (if eff-ok? "ok" "error")
                                    (when (number? ms) (str ms " ms"))
                                    ticks-str
                                    (when sch (str sch))
                                    (when branch (str branch (when branch-tx (str " @ tx " branch-tx))))])))
        out-s (when (and (some? out) (not (str/blank? (str out))))
                (let [os (str out)]
                  (if (> (count os) cap)
                    (str "; stdout (" (count os) " chars; full stdout in (:seon.eval/out (meta #'" h-str "))):\n"
                         (subs os 0 (min 2000 (count os))) "\n... (" (- (count os) 2000) " chars omitted) ...")
                    (str "; stdout:\n" os))))
        body (if-not eff-ok?
               (str "ERROR: " (or error "Unknown error"))
               (let [val-str (if (nil? value) "nil" (str value))
                     pr (if (and (string? value) (not (str/blank? val-str)) (< (count val-str) 2000000))
                          (safe-read-eval-edn edn-tag-val val-str)
                          {:ok? false})
                     parsed-ok? (boolean (:ok? pr))
                     parsed (:val pr)
                     eff-db (choose-eval-db (or db main-db) fallback-db (when parsed-ok? parsed) sch)
                     eff-opts (cond-> {:handle (symbol h-str) :max-chars cap :limit 20
                                       :budget (min 1200 (quot cap 4)) :mode :model}
                                sch (assoc :schema sch)
                                eff-db (assoc :render-root? true :main-db (or main-db eff-db)))
                     val-body (cond
                                parsed-ok? (render-eval-val-text eff-db parsed sch eff-opts cap h-str)
                                (<= (count val-str) cap) val-str
                                :else (render-eval-val-text eff-db val-str nil eff-opts cap h-str))
                     crumb-s (when (and eff-db parsed-ok?)
                               (when-let [bh (render/render-schema-breadcrumbs eff-db sch parsed (assoc eff-opts :mode :model :main-db (or main-db eff-db) :fn-limit 5 :doc-limit 2))]
                                 (let [bt (render/hiccup->text bh)]
                                   (when-not (str/blank? bt)
                                     (str "; " (str/replace bt "\n" "\n; "))))))]
                 (if (and crumb-s (not (str/blank? crumb-s)))
                   (str val-body "\n" crumb-s)
                   val-body)))]
    (str hdr
         (when out-s (str "\n" out-s))
         "\n" body)))