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
      (cap-eval-text parsed (if (:expanded? eff-opts) (max cap 16000) cap) h-str)

      (and (string? parsed)
           (<= (count parsed) (min cap 2000))
           (<= (count (str/split-lines parsed)) 20))
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

(defn- media-frame-map? [x]
  (and (map? x)
       (or (contains? x :seon.media/base64)
           (and (contains? x :seon.media/width) (contains? x :seon.media/height))
           (and (contains? x :seon.media/id) (contains? x :seon.media/label)))))

(defn- dom-overflow-x? [dom]
  (boolean (when (map? dom) (or (:overflow-x? dom) (:overflowX dom)))))

(defn- format-media-frame-line [m]
  (let [w (if (number? (:seon.media/width m)) (max 0 (long (:seon.media/width m))) 0)
        h (if (number? (:seon.media/height m)) (max 0 (long (:seon.media/height m))) 0)
        lbl (or (when (and (string? (:seon.media/label m)) (not (str/blank? (:seon.media/label m))))
                  (str/trim (:seon.media/label m)))
                (str w "px"))
        ov-x? (dom-overflow-x? (:seon.media/dom m))
        id (:seon.media/id m)]
    (str "Image frame [" lbl "]: " w "×" h "px · overflow-x=" ov-x?
         (when id (str " · [:seon.media/id " (pr-str id) "]")))))

(defn- format-media-eval-value [parsed sch]
  (cond
    (and (map? parsed)
         (or (= :seon.media/frames sch)
             (and (contains? parsed :seon.media/frames) (sequential? (:seon.media/frames parsed)))))
    (let [fs (:seon.media/frames parsed)
          sum (when (and (string? (:summary parsed)) (not (str/blank? (:summary parsed))))
                (:summary parsed))
          lines (keep #(when (map? %) (format-media-frame-line %)) fs)]
      (if (or sum (seq lines))
        (str/join "\n" (concat (when sum [sum]) lines))
        "Image frames: 0"))

    (and (map? parsed) (or (= :seon.media/image sch) (media-frame-map? parsed)))
    (format-media-frame-line parsed)

    (and (sequential? parsed) (seq parsed) (every? media-frame-map? parsed))
    (str/join "\n" (map format-media-frame-line parsed))

    :else nil))

(defn- strip-raw-base64-text [^String s]
  (if (and (string? s) (str/includes? s ":seon.media/base64"))
    (str/replace s #":seon\.media/base64\s+\"[^\"]*\"" ":seon.media/base64 \"<omitted>\"")
    s))

(defn- valid-db? [db]
  (and db (instance? datahike.db.interface.ISearch db) (map? (:schema db))))

(defn- pull-media-in-dbs [dbs ref]
  (let [pat '[:seon.media/id :seon.media/mime :seon.media/base64 :seon.media/width
              :seon.media/height :seon.media/label :seon.media/dom]
        try-db (fn [db]
                 (let [dh (:schema db)
                       ent (cond
                             (integer? ref) (d/pull db pat ref)
                             (and (map? ref) (integer? (:db/id ref))) (d/pull db pat (:db/id ref))
                             (and (vector? ref) (= 2 (count ref)) (= :seon.media/id (first ref)) (dh :seon.media/id))
                             (when-let [e (:e (first (d/datoms db :avet :seon.media/id (second ref))))]
                               (d/pull db pat e))
                             (and (uuid? ref) (dh :seon.media/id))
                             (when-let [e (:e (first (d/datoms db :avet :seon.media/id ref)))]
                               (d/pull db pat e))
                             :else nil)]
                   (when (and (map? ent) (not (str/blank? (str (:seon.media/base64 ent)))))
                     ent)))]
    (some try-db dbs)))

(defn- normalize-media-image [m]
  (when (and (map? m) (not (str/blank? (str (:seon.media/base64 m)))))
    (let [w (if (number? (:seon.media/width m)) (max 0 (long (:seon.media/width m))) 0)
          h (if (number? (:seon.media/height m)) (max 0 (long (:seon.media/height m))) 0)
          mime (or (when (and (string? (:seon.media/mime m)) (not (str/blank? (:seon.media/mime m))))
                     (str/trim (:seon.media/mime m)))
                   "image/jpeg")
          lbl (or (when (and (string? (:seon.media/label m)) (not (str/blank? (:seon.media/label m))))
                    (str/trim (:seon.media/label m)))
                  (str w "px"))]
      (cond-> {:mime mime
               :base64 (str (:seon.media/base64 m))
               :label lbl
               :width w
               :height h
               :dom (if (map? (:seon.media/dom m)) (:seon.media/dom m) {})}
        (:seon.media/id m) (assoc :id (:seon.media/id m))))))

(defn- eval-entity-image-refs [dbs c]
  (let [ev-id (or (:eval-id c) (:seon.agent.eval/id c))
        hdl (:handle c)
        cid (:id c)
        code (:code c)]
    (or (some (fn [db]
                (let [dh (:schema db)]
                  (when (dh :seon.agent.eval/images)
                    (cond
                      (and (uuid? ev-id) (dh :seon.agent.eval/id))
                      (when-let [e (:e (first (d/datoms db :avet :seon.agent.eval/id ev-id)))]
                        (seq (:seon.agent.eval/images (d/pull db '[:seon.agent.eval/images] e))))

                      (and (qualified-symbol? hdl) (dh :seon.agent.eval/handle))
                      (some (fn [dt]
                              (let [p (d/pull db '[:seon.agent.eval/images :seon.agent.eval/call-id :seon.agent.eval/code] (:e dt))]
                                (when (and (seq (:seon.agent.eval/images p))
                                           (or (nil? cid) (= cid (:seon.agent.eval/call-id p)) (= code (:seon.agent.eval/code p))))
                                  (:seon.agent.eval/images p))))
                            (reverse (vec (d/datoms db :avet :seon.agent.eval/handle hdl))))
                      :else nil))))
              dbs)
        [])))

(defn- collect-media-candidates-from-val [v]
  (let [parsed (cond
                 (string? v)
                 (if (and (< (count v) 2000000)
                          (or (str/includes? v ":seon.media/")
                              (str/includes? v "seon.media")))
                   (let [r (safe-read-eval-edn edn-tag-val v)]
                     (if (:ok? r) (:val r) v))
                   nil)
                 (coll? v) v
                 :else nil)
        from-tree (when (coll? parsed)
                    (keep (fn [node]
                            (cond
                              (and (vector? node) (= 2 (count node)) (= :seon.media/id (first node)) (uuid? (second node)))
                              node
                              (and (map? node) (not (str/blank? (str (:seon.media/base64 node)))))
                              node
                              (and (map? node) (uuid? (:seon.media/id node)))
                              [:seon.media/id (:seon.media/id node)]
                              :else nil))
                          (tree-seq coll? seq parsed)))
        from-re (when (and (string? v) (str/includes? v ":seon.media/id"))
                  (map (fn [[_ us]] [:seon.media/id (java.util.UUID/fromString us)])
                       (re-seq #":seon\.media/id\s+#uuid\s+\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\"" v)))]
    (concat from-tree from-re)))

(defn extract-recent-eval-images
  "Looks at the latest completed turn in `history` (via `:seon.agent.eval/images`, `:images`,
   or `:seon.media/id` / inline media maps in `:value`), resolves up to `max-images` (default 4)
   `:seon.media/image` entities from `eval-db` (and optional `fallback-dbs`), and returns
   `[{:mime :base64 :label :width :height :dom ...}]`. Older turns automatically age out."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :any] [:vector :map]]
                  [:=> [:cat [:maybe :seon.host/db] :any :int] [:vector :map]]
                  [:=> [:cat [:maybe :seon.host/db] :any :int :any] [:vector :map]]]
   :cost/ticks 10000000}
  ([eval-db history] (extract-recent-eval-images eval-db history 4 nil))
  ([eval-db history max-images] (extract-recent-eval-images eval-db history max-images nil))
  ([eval-db history max-images fallback-dbs]
   (let [cap (max 0 (long (or max-images 4)))
         last-turn (when (sequential? history) (last history))
         calls (when (map? last-turn) (:calls last-turn))]
     (if (or (zero? cap) (empty? calls))
       []
       (let [dbs (vec (distinct (filter valid-db? (cons eval-db (when (sequential? fallback-dbs) fallback-dbs)))))
             raw-refs (mapcat
                       (fn [c]
                         (when (map? c)
                           (concat (when (coll? (:seon.agent.eval/images c)) (:seon.agent.eval/images c))
                                   (when (coll? (:images c)) (:images c))
                                   (eval-entity-image-refs dbs c)
                                   (collect-media-candidates-from-val (:value c)))))
                       calls)
             resolve-one (fn [x]
                           (cond
                             (and (map? x) (not (str/blank? (str (:seon.media/base64 x)))))
                             (normalize-media-image x)

                             (and (map? x) (uuid? (:seon.media/id x)))
                             (normalize-media-image (pull-media-in-dbs dbs [:seon.media/id (:seon.media/id x)]))

                             :else
                             (normalize-media-image (pull-media-in-dbs dbs x))))]
         (loop [xs raw-refs seen #{} acc []]
           (if (or (empty? xs) (>= (count acc) cap))
             acc
             (if-let [img (resolve-one (first xs))]
               (let [dk (or (:id img) [(:label img) (:width img) (:height img) (hash (:base64 img))])]
                 (if (contains? seen dk)
                   (recur (rest xs) seen acc)
                   (recur (rest xs) (conj seen dk) (conj acc img))))
               (recur (rest xs) seen acc)))))))))

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

(defn format-eval-output
  "Formats a single REPL eval outcome via `seon.render.fit/discover-renderers` +
   `seon.render/render-value -> seon.render/hiccup->text` (single Hiccup -> text pipeline).
   Bounds large outputs structurally pointing to the live `result/e<n>` handle."
  {:malli/schema [:=> {:examples [[[{:handle 'result/e1 :ok? true :ms 1.5 :ticks 500 :value "42"}]
                                   "; result/e1 · ok · 1.5 ms · 500 ticks\n42"]]}
                  [:cat :map] :string]
   :cost/ticks 500000
   :cost/bytes 1048576}
  [{:keys [handle ok? value out error ms ticks result-schema schema branch branch-tx max-chars db main-db fallback-db code]}]
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
                (let [os (str out)
                                      n (count os)]
                                  (if (> n cap)
                                    (let [budget (min n (max 120 (- cap 400)))
                                          head-n (quot (* budget 3) 4)
                                          tail-n (max 0 (- budget head-n))
                                          omitted (max 0 (- n head-n tail-n))]
                                      (str "; stdout (" n " chars; full stdout in (:seon.eval/out (meta #'" h-str "))):
                "
                                           (subs os 0 head-n)
                                           "
                ... (" omitted " chars omitted; full stdout in (:seon.eval/out (meta #'" h-str "))) ...
                "
                                           (when (pos? tail-n) (subs os (- n tail-n)))))
                                    (str "; stdout:
                " os))))
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
                              (and (string? code) (str/includes? code "expand!")) (assoc :expanded? true)
                              sch (assoc :schema sch)
                              eff-db (assoc :render-root? true :main-db (or main-db eff-db)))
                     media-txt (when parsed-ok? (format-media-eval-value parsed sch))
                     safe-val-str (strip-raw-base64-text val-str)
                     val-body (cond
                                media-txt (cap-eval-text media-txt cap h-str)
                                parsed-ok? (render-eval-val-text eff-db parsed sch eff-opts cap h-str)
                                (<= (count safe-val-str) cap) safe-val-str
                                :else (render-eval-val-text eff-db safe-val-str nil eff-opts cap h-str))
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