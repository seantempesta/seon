(ns seon.model.wire
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [jolt.http.core :as http]
            [clojure.edn :as edn]))

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

(defn- preview-snippet [s max-len]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) max-len)
      (str (subs s 0 max-len) "…")
      s)))

(defn- format-large-value [h-str val-str parsed cap]
  (cond
    (string? parsed)
    (let [lines (str/split-lines parsed)
          n-lines (count lines)]
      (if (<= n-lines 60)
        (let [head-n (min (quot cap 2) (count parsed))
              tail-n (min (quot cap 4) (max 0 (- (count parsed) head-n)))]
          (str (subs parsed 0 head-n)
               "\n... (" (- (count parsed) head-n tail-n) " chars omitted; full string in " h-str
               " — use (subs " h-str " start end)) ...\n"
               (subs parsed (- (count parsed) tail-n))))
        (let [head-ls (take 40 lines)
              tail-ls (take-last 15 lines)
              omitted (- n-lines 55)]
          (str "; Large string (" n-lines " lines, " (count parsed) " chars) — full value in " h-str
               " (use (subs " h-str " a b) or (user/source 'ns/fn {:lines [a b]}))\n"
               (str/join "\n" head-ls)
               "\n... (" omitted " lines omitted) ...\n"
               (str/join "\n" tail-ls)))))

    (map? parsed)
    (let [ks (keys parsed)
          shown (take 40 ks)
          more (- (count ks) (count shown))
          rows (map (fn [k]
                      (let [v (get parsed k)
                            desc (cond
                                   (string? v) (str "string(" (count v) " chars) " (pr-str (preview-snippet v 100)))
                                   (map? v)    (str "map(" (count v) " keys) " (preview-snippet (pr-str (take 6 (keys v))) 100))
                                   (vector? v) (str "vec(" (count v) ") " (preview-snippet (pr-str (take 3 v)) 100))
                                   (set? v)    (str "set(" (count v) ") " (preview-snippet (pr-str (take 3 v)) 100))
                                   (seq? v)    (str "seq(" (count v) ") " (preview-snippet (pr-str (take 3 v)) 100))
                                   :else       (preview-snippet (pr-str v) 120))]
                        (str "  " (pr-str k) " => " desc)))
                    shown)]
      (str "; Large map (" (count ks) " keys, " (count val-str) " chars) — full live map in " h-str
           " (inspect via (:key " h-str "))\n"
           (str/join "\n" rows)
           (when (pos? more) (str "\n  ... +" more " more keys (run (keys " h-str "))"))))

    (or (vector? parsed) (set? parsed) (seq? parsed))
    (let [kind (cond (vector? parsed) "vector" (set? parsed) "set" :else "seq")
          n (count parsed)
          items (map-indexed
                 (fn [i item]
                   (str "  [" i "] " (preview-snippet (pr-str item) 400)))
                 (take 5 parsed))]
      (str "; Large " kind " (" n " items, " (count val-str) " chars) — full live coll in " h-str
           " (inspect via (nth " h-str " i) or (take 20 (drop 5 " h-str ")))\n"
           (str/join "\n" items)
           (when (> n 5) (str "\n  ... +" (- n 5) " more items"))))

    :else
    (let [head-n (min 2500 (count val-str))
          tail-n (min 1000 (max 0 (- (count val-str) head-n)))]
      (str (subs val-str 0 head-n)
           "\n... (" (- (count val-str) head-n tail-n) " chars omitted; full value in " h-str ") ...\n"
           (subs val-str (- (count val-str) tail-n))))))

(defn format-eval-output
  "Formats a single REPL eval outcome cleanly without double `pr-str` escaping.
   When `:value` exceeds `:max-chars` (default 8000), returns a type-aware structural summary
   pointing to the live `result/e<n>` handle instead of dumping raw KB into context."
  {:malli/schema [:=> {:examples [[[{:handle 'result/e1 :ok? true :ms 1.5 :ticks 500 :value "42"}]
                                   "; result/e1 · ok · 1.5 ms · 500 ticks\n42"]]}
                  [:cat :map] :string]
   :cost/ticks 500000
   :cost/bytes 1048576}
  [{:keys [handle ok? value out error ms ticks result-schema schema branch branch-tx max-chars]}]
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
                     parsed (if (and (string? value) (not (str/blank? val-str)) (< (count val-str) 500000))
                              (try (edn/read-string {:default (fn [_ v] v)} val-str)
                                   (catch Throwable _ ::unreadable))
                              ::unreadable)]
                 (cond
                   (and (string? parsed) (<= (count parsed) cap)) parsed
                   (and (string? parsed) (> (count parsed) cap))  (format-large-value h-str val-str parsed cap)
                   (<= (count val-str) cap)                        val-str
                   :else                                           (format-large-value h-str val-str parsed cap))))]
    (str hdr
         (when out-s (str "\n" out-s))
         "\n" body)))