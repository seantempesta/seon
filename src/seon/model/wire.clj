(ns seon.model.wire
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [jolt.http-client :as http]
            [jolt.http.sse :as sse]
            [seon.store :as store]
            [seon.render :as render]
            [seon.render.fit :as fit]
            [seon.schema :as schema]
            [seon.schema.index :as six]
            [seon.model.media :as media]))

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
  {:malli/schema [:=> [:cat :any] :string], :cost/ticks 5000, :cost/bytes 32768}
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

(defn prefix-sha256
  "Returns a 16-char lowercase hex SHA-256 digest of string `s` for deterministic KV-cache prefix hashing."
  {:malli/schema [:=> {:examples [[["hello"] "2cf24dba5fb0a30e"]]}
                  [:cat :string] :string]
   :cost/ticks 200000
   :cost/bytes 131072}
  [s]
  (store/sha256-hex (str s) 16))

(defn- edn-tag-val [& args] (apply media/edn-tag-val args))

(defn- safe-read-eval-edn [& args] (apply media/safe-read-eval-edn args))

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
    (let [has-exp? (str/includes? s "(user/expand! 'result/")
          tail (str "\n... (" (- (count s) (max 100 (- cap 140))) " chars omitted"
                    (when-not has-exp? (str "; (user/expand! '" h-str ")")) ")")
          keep-n (max 0 (min (count s) (- cap (count tail))))]
      (if (pos? keep-n)
        (str (subs s 0 keep-n) tail)
        (subs tail 0 (min cap (count tail)))))))

(defn- render-eval-val-text [eff-db parsed sch eff-opts cap h-str]
  (let [exp-str? (and (string? parsed)
                      (or (:expanded? eff-opts)
                          (str/includes? parsed "(user/expand!")
                          (boolean (re-find #"(?m)^(?:Vector|Map|Set|Seq|Large map|Large string) \(" parsed))))]
    (cond
      (and (string? parsed) exp-str?)
      (cap-eval-text parsed (if (:expanded? eff-opts) (max cap 24000) cap) h-str)

      (and (string? parsed) (<= (count parsed) cap))
      parsed

      :else
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
        (cap-eval-text (render/hiccup->text hiccup) cap h-str)))))

(defn- dom-overflow-x? [& args] (apply media/dom-overflow-x? args))

(defn- format-media-eval-value [& args] (apply media/format-media-eval-value args))

(defn- strip-raw-base64-text [& args] (apply media/strip-raw-base64-text args))

(defn ^{:seon.code/reexport 'seon.model.media/extract-recent-eval-images} extract-recent-eval-images [& args] (apply media/extract-recent-eval-images args))

(defn build-ephemeral-tail-msg
  "Builds the trailing ephemeral OpenAI `user` message, attaching optional viewport screenshot and
   native multimodal `image_url` blocks + captions for `eval-images` when `vision?` is true."
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :map] [:maybe [:sequential :map]] :boolean] [:maybe :map]]
   :cost/ticks 2000000}
  [tail-text eff-img eval-images vision?]
  (let [vp-b64 (when (and (map? eff-img) (not (str/blank? (str (:base64 eff-img)))))
                 (str (:base64 eff-img)))
        imgs (if (and vision? (sequential? eval-images))
               (vec (filter #(and (map? %) (not (str/blank? (str (:base64 %))))) eval-images))
               [])
        has-text? (not (str/blank? (str tail-text)))]
    (cond
      (or vp-b64 (seq imgs))
      (let [parts (cond-> []
                    has-text? (conj {:type "text" :text (str tail-text)})
                    (and (not has-text?) vp-b64) (conj {:type "text" :text "Current live viewport screenshot:"})
                    vp-b64 (conj {:type "image_url"
                                  :image_url {:url (str "data:" (or (not-empty (str (:mime eff-img ""))) "image/jpeg")
                                                        ";base64," vp-b64)
                                              :detail "auto"}}))
            img-parts (mapcat
                       (fn [{:keys [mime base64 label width height dom]}]
                         (let [ov-x? (dom-overflow-x? dom)
                               w (if (number? width) (max 0 (long width)) 0)
                               h (if (number? height) (max 0 (long height)) 0)
                               lbl (or (when (and (string? label) (not (str/blank? label))) (str/trim label))
                                       (str w "px"))
                               cap (str "Rendered Hiccup preview [" lbl " · " w "×" h
                                        " · overflowX=" ov-x? "]:")]
                           [{:type "text" :text cap}
                            {:type "image_url"
                             :image_url {:url (str "data:" (or (not-empty (str mime)) "image/jpeg")
                                                   ";base64," base64)
                                         :detail "auto"}}]))
                       imgs)]
        {:role "user" :content (into parts img-parts)})

      has-text?
      {:role "user" :content (str tail-text)}

      :else nil)))

(defn decode-sse-data
  "Decodes an SSE event `data` payload into a keywordized JSON map, returning nil for blank/`[DONE]`/non-map JSON."
  {:malli/schema [:=> {:examples [[["{\"choices\":[]}"] {:choices []}] [["[DONE]"] nil] [[""] nil] [[nil] nil]]}
                  [:cat [:maybe :string]] [:maybe :map]]
   :cost/ticks 100000 :cost/bytes 131072}
  [payload]
  (let [s (some-> payload str/trim)]
    (when (and (seq s) (not= s "[DONE]"))
      (try (let [v (json/read-str s :key-fn keyword)]
             (when (map? v) v))
           (catch Throwable _ nil)))))

(defn parse-sse-line
"Parses a single SSE `data:` line via `jolt.http.sse/parse-line` into a keywordized JSON map (or nil)."
{:malli/schema [:=> {:examples [[["data: {\"a\":1}"] {:a 1}] [["data: [DONE]"] nil] [[": keepalive"] nil] [[nil] nil]]}
                [:cat [:maybe :string]] [:maybe :map]]
 :cost/ticks 100000 :cost/bytes 131072}
[^String line]
(when (string? line)
  (let [[st _] (sse/parse-line sse/initial-state line)
        [_ ev] (sse/parse-line st "")]
    (when ev (decode-sse-data (:data ev))))))

(defn- headers->map [headers]
  (cond
    (map? headers) headers
    (sequential? headers) (into {} (keep (fn [[k v]] (when (and k (some? v)) [(str k) (str v)]))) headers)
    :else {}))

(defn http-get!
"Fiber-aware HTTP GET via `jolt.http-client` (keep-alive pooled, gzip/deflate, redirects)."
{:malli/schema [:function [:=> [:cat :string [:or :map [:sequential [:tuple :string :string]]]] :map]]
 :seon.gen/skip true}
[url headers]
(let [r (http/get url {:headers (headers->map headers) :throw false})]
  {:status (:status r) :headers (:headers r) :body (or (:body r) "")}))

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

(defn http-post!
"Fiber-aware HTTP POST via `jolt.http-client` (keep-alive pooled, gzip/deflate, redirects)."
{:malli/schema [:function [:=> [:cat :string [:or :map [:sequential [:tuple :string :string]]] :string] :map]]
 :seon.gen/skip true}
[url headers ^String body]
(let [r (http/post url {:headers (headers->map headers) :body body :throw false})]
  {:status (:status r) :headers (:headers r) :body (or (:body r) "")}))

(defn- edn-multiline-str [^String s]
  (let [n (count s)
        sb (StringBuilder. (+ n 16))]
    (.append sb \")
    (dotimes [i n]
      (let [ch (.charAt s i)]
        (case ch
          \\ (.append sb "\\\\")
          \" (.append sb "\\\"")
          \return (.append sb "\\r")
          (.append sb ch))))
    (.append sb \")
    (.toString sb)))

(defn format-eval-output
  "Formats a single REPL eval outcome as structured Clojure EDN data (`{:seon.eval/turn ... :seon.eval/handle result/e<n> :seon.eval/ok? ... :seon.eval/value ...}` — zero fake `;` comments), using `seon.render.fit/discover-renderers` + `seon.render/render-value` for custom entity renderers or oversized collections."
  {:malli/schema [:=> {:examples [[[{:handle 'result/e1 :ok? true :ms 1.5 :ticks 500 :value "42"}]
                                   "{:seon.eval/handle result/e1, :seon.eval/ok? true, :seon.eval/ms 1.5, :seon.eval/ticks 500, :seon.eval/value 42}"]]}
                  [:cat :map] :string]
   :cost/ticks 800000
   :cost/bytes 1048576}
  [{:keys [turn handle ok? value out error ms ticks result-schema schema branch branch-tx max-chars out-max-chars db main-db fallback-db code]}]
  (let [eff-ok? (if (some? ok?) (boolean ok?) (nil? error))
        cap (long (max 256 (if (number? max-chars) max-chars 24000)))
        h-str (if handle (str handle) "result/e?")
        sch (or result-schema schema)
        out-s (when (and (some? out) (not (str/blank? (str out))))
                (let [os (str out)
                      n (count os)
                      ocap (long (max 256 (if (number? out-max-chars) out-max-chars cap)))]
                  (if (> n ocap)
                    (let [budget (min n (max 120 (- ocap 260)))
                          head-n (quot (* budget 3) 4)
                          tail-n (max 0 (- budget head-n))
                          omitted (max 0 (- n head-n tail-n))]
                      (str (subs os 0 head-n)
                           "\n... (" omitted " chars omitted; (user/expand! '" h-str " {:out? true}) or (:seon.eval/out (meta #'" h-str "))) ...\n"
                           (when (pos? tail-n) (subs os (- n tail-n)))))
                    os)))
        val-res (when eff-ok?
                  (let [val-str (if (nil? value) "nil" (str value))
                        tagged? (volatile! false)
                        pr (if (and (string? value) (not (str/blank? val-str)) (< (count val-str) 2000000))
                             (safe-read-eval-edn (fn [t v] (vreset! tagged? true) (edn-tag-val t v)) val-str)
                             {:ok? false})
                        parsed-ok? (boolean (:ok? pr))
                        parsed (:val pr)
                        eff-db (choose-eval-db (or db main-db) fallback-db (when parsed-ok? parsed) sch)
                        eff-opts (cond-> {:handle (symbol h-str) :max-chars cap
                                          :budget (min 1200 (quot cap 4)) :mode :model}
                                   (not (string? parsed)) (assoc :limit 20)
                                   (and (string? code) (str/includes? code "expand!")) (assoc :expanded? true)
                                   sch (assoc :schema sch)
                                   eff-db (assoc :render-root? true :main-db (or main-db eff-db)))
                        media-txt (when parsed-ok? (format-media-eval-value parsed sch))
                        safe-val-str (strip-raw-base64-text val-str)
                        target (when (and parsed-ok? (not (string? parsed))) (detect-schema-target eff-db parsed sch))
                        disc (when (and eff-db (:schema target) (map? (:ent target)))
                               (fit/discover-renderers eff-db [(:schema target)]))
                        schema-rsym (when (and eff-db (:schema target))
                                      (render/schema-render-sym eff-db (:schema target)))
                        plain-edn? (and parsed-ok?
                                        (not @tagged?)
                                        (not media-txt)
                                        (not (string? parsed))
                                        (empty? disc)
                                        (nil? schema-rsym)
                                        (<= (count safe-val-str) (min cap 4000))
                                        (not (and (coll? parsed) (> (count parsed) 20))))
                        val-edn (cond
                                  media-txt (edn-multiline-str (cap-eval-text media-txt cap h-str))
                                  plain-edn? safe-val-str
                                  parsed-ok? (edn-multiline-str (render-eval-val-text eff-db parsed sch eff-opts cap h-str))
                                  (<= (count safe-val-str) cap) (edn-multiline-str safe-val-str)
                                  :else (edn-multiline-str (render-eval-val-text eff-db safe-val-str nil eff-opts cap h-str)))
                        crumb-s (when (and eff-db parsed-ok?)
                                  (when-let [bh (render/render-schema-breadcrumbs eff-db sch parsed (assoc eff-opts :mode :model :main-db (or main-db eff-db) :fn-limit 5 :doc-limit 2))]
                                    (let [bt (render/hiccup->text bh)]
                                      (when-not (str/blank? bt) bt))))]
                    {:val-edn val-edn :related crumb-s}))
        entries (cond-> []
                  (integer? turn) (conj (str ":seon.eval/turn " turn))
                  handle (conj (str ":seon.eval/handle " handle))
                  true (conj (str ":seon.eval/ok? " eff-ok?))
                  (number? ms) (conj (str ":seon.eval/ms " ms))
                  (and (number? ticks) (pos? (long ticks))) (conj (str ":seon.eval/ticks " (long ticks)))
                  sch (conj (str ":seon.eval/schema " (pr-str sch)))
                  branch (conj (str ":seon.eval/branch " (pr-str branch)))
                  branch-tx (conj (str ":seon.eval/branch-tx " branch-tx))
                  out-s (conj (str ":seon.eval/out " (edn-multiline-str out-s)))
                  (not eff-ok?) (conj (str ":seon.eval/error " (edn-multiline-str (str (or error "Unknown error")))))
                  eff-ok? (conj (str ":seon.eval/value " (:val-edn val-res)))
                  (:related val-res) (conj (str ":seon.eval/related " (edn-multiline-str (:related val-res)))))]
    (str "{" (str/join ", " entries) "}")))