(ns seon.model.wire
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [jolt.http.core :as http]))

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
  "Parses JSON tool-call argument text into a keywordized map, falling back to `{:code s}` on raw/non-JSON strings."
  {:malli/schema [:=> {:examples [[["{\"code\":\"(+ 1 2)\"}"] {:code "(+ 1 2)"}] [[""] {}] [["raw"] {:code "raw"}]]} [:cat :string] :map], :cost/ticks 50000, :cost/bytes 131072}
  [^String s]
  (if (str/blank? s)
    {}
    (try (json/read-str s :key-fn keyword)
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