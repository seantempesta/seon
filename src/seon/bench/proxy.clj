(ns seon.bench.proxy
  "Shared local OpenAI-compat model quota & telemetry proxy for parallel isolated Seon instances.
   Enforces a fiber-parking global concurrency cap (`max-in-flight`) and optional hard request/token
   ceilings (`max-requests`, `max-tokens`) across N instances while transparently forwarding SSE streams."
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [jolt.http.core :as http]
            [ring-chez.adapter :as adapter]
            [seon.model :as model]))

(defn stop!
  "Stops a running `seon.bench.proxy` server cleanly."
  {:malli/schema [:=> [:cat [:map [:stop! :seon.host/fn]]] :nil]}
  [proxy]
  (when-let [f (:stop! proxy)] (f))
  nil)

(defn stats
  "Returns the current proxy usage & quota metrics map."
  {:malli/schema [:=> [:cat [:map [:stats-atom :seon.host/atom]]] :map]}
  [proxy]
  @(:stats-atom proxy))

(defn- extract-usage-counts
  "Pure helper extracting normalized `{:prompt :completion :cache-hit}` token counts from an OpenAI/DeepSeek usage map."
  [usage]
  (when (map? usage)
    {:prompt (long (or (:prompt_tokens usage) 0))
     :completion (long (or (:completion_tokens usage) 0))
     :cache-hit (long (or (:prompt_cache_hit_tokens usage)
                          (get-in usage [:prompt_tokens_details :cached_tokens])
                          0))}))

(defn- record-usage! [stats-atom usage-map]
  (when-let [{:keys [prompt completion cache-hit]} (extract-usage-counts usage-map)]
    (swap! stats-atom
           (fn [s]
             (-> s
                 (update :prompt-tokens + prompt)
                 (update :completion-tokens + completion)
                 (update :cache-hit-tokens + cache-hit)
                 (update :total-tokens + prompt completion))))))

(defn ^:seon.flow/edge forward-completion!
  "Forwards one `/chat/completions` HTTP POST to `upstream-url`, streaming SSE bytes through when
   the upstream returns `text/event-stream` and recording token usage into `stats-atom`."
  {:malli/schema [:=> [:cat :string :map :string :seon.host/atom :seon.host/chan] :map]}
  [upstream-url req-headers body-str stats-atom sem-ch]
  (let [u (http/parse-url upstream-url)
        auth (or (get req-headers "authorization") (get req-headers "Authorization"))
        accept (or (get req-headers "accept") (get req-headers "Accept") "text/event-stream")
        hdrs (cond-> [["Content-Type" "application/json"]
                      ["Accept" accept]]
               auth (conj ["Authorization" auth]))
        s (http/connect-stream (http/tget u :host) (http/effective-port u) (= "https" (http/tget u :protocol)) false nil nil)]
    (try
      (http/s-write s (http/build-request "POST" u hdrs body-str))
      (let [r (http/read-response s nil "POST" nil true true)
            status (long (or (:status r) 502))
            ct (or (some (fn [[k v]] (when (= "content-type" (str/lower-case (str k))) (str v)))
                         (:header-pairs r))
                   "application/json")
            sse? (and (= 200 status) (str/includes? (str/lower-case ct) "text/event-stream"))]
        (if sse?
          (let [out-ch (a/chan 64)
                pull (or (:body-pull r)
                         (let [b (volatile! (:body r))]
                           (fn [] (let [x @b] (vreset! b nil) x))))]
            (a/go
              (try
                (loop [carry ""]
                  (if-let [chunk-bytes (pull)]
                    (let [text (String. ^bytes chunk-bytes "UTF-8")
                          combined (str carry text)
                          lines (str/split combined #"\r?\n" -1)
                          complete (pop lines)
                          rem-carry (peek lines)]
                      (doseq [line complete]
                        (when-let [parsed (model/parse-sse-line line)]
                          (when-let [usage (:usage parsed)]
                            (record-usage! stats-atom usage))))
                      (a/>! out-ch text)
                      (recur rem-carry))
                    (when-let [parsed (model/parse-sse-line carry)]
                      (when-let [usage (:usage parsed)]
                        (record-usage! stats-atom usage)))))
                (catch Throwable _
                  (swap! stats-atom update :errors inc))
                (finally
                  (http/s-close s)
                  (a/close! out-ch)
                  (swap! stats-atom update :in-flight #(max 0 (dec %)))
                  (a/put! sem-ch :permit))))
            {:status 200
             :headers {"Content-Type" "text/event-stream"
                       "Cache-Control" "no-cache"}
             :body out-ch})
          (let [bs (if-let [pull (:body-pull r)]
                     (http/concat-bas (loop [acc []] (if-let [b (pull)] (recur (conj acc b)) acc)))
                     (or (:body r) (byte-array 0)))
                resp-str (String. ^bytes bs "UTF-8")]
            (http/s-close s)
            (swap! stats-atom update :in-flight #(max 0 (dec %)))
            (a/put! sem-ch :permit)
            (when (= 200 status)
              (when-let [parsed (try (json/read-str resp-str :key-fn keyword) (catch Throwable _ nil))]
                (record-usage! stats-atom (:usage parsed))))
            {:status status
             :headers {"Content-Type" ct}
             :body resp-str})))
      (catch Throwable ex
        (try (http/s-close s) (catch Throwable _))
        (swap! stats-atom #(-> % (update :errors inc) (update :in-flight (fn [n] (max 0 (dec n))))))
        (a/put! sem-ch :permit)
        {:status 502
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:error {:message (str "Upstream proxy error: " (or (.getMessage ex) (str ex)))}})}))))

(defn- read-req-body [req]
  (let [b (:body req)]
    (cond
      (nil? b) ""
      (string? b) b
      :else (slurp b))))

(defn- budget-exceeded-reason
  "Pure budget check returning nil when under limits or a human-readable refusal reason string."
  [stats {:keys [max-requests max-tokens]}]
  (cond
    (and (number? max-requests) (pos? (long max-requests))
         (>= (:requests stats 0) (long max-requests)))
    (str "Proxy max-requests cap reached (" (:requests stats) "/" max-requests ")")

    (and (number? max-tokens) (pos? (long max-tokens))
         (>= (:total-tokens stats 0) (long max-tokens)))
    (str "Proxy max-tokens cap reached (" (:total-tokens stats) "/" max-tokens ")")

    :else nil))

(defn ^:seon.flow/edge start!
  "Starts a local shared quota/telemetry OpenAI-compat proxy bound to `127.0.0.1:<port>` (default `0` = ephemeral).
   Options: `{:upstream-url \"https://api.deepseek.com\" :port 0 :max-in-flight 8 :max-requests nil :max-tokens nil}`."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map]]}
  ([] (start! {}))
  ([{:keys [upstream-url port max-in-flight max-requests max-tokens]
     :or {upstream-url (or (System/getenv "SEON_AI_BASE_URL") "https://api.deepseek.com")
          port 0
          max-in-flight 8}}]
   (let [clean-base (str/replace (str upstream-url) #"/+$" "")
         target-url (str clean-base "/chat/completions")
         cap (max 1 (long max-in-flight))
         sem-ch (a/chan cap)
         _ (dotimes [_ cap] (a/put! sem-ch :permit))
         stats (atom {:requests 0
                      :in-flight 0
                      :rejected 0
                      :errors 0
                      :prompt-tokens 0
                      :completion-tokens 0
                      :cache-hit-tokens 0
                      :total-tokens 0
                      :max-in-flight cap
                      :max-requests max-requests
                      :max-tokens max-tokens
                      :upstream-url clean-base})
         limits {:max-requests max-requests :max-tokens max-tokens}
         handler (fn [req]
                   (let [m (:request-method req)
                         uri (str (:uri req))]
                     (cond
                       (and (= m :get) (= uri "/stats"))
                       {:status 200
                        :headers {"Content-Type" "application/json"}
                        :body (json/write-str @stats)}

                       (and (= m :post) (str/ends-with? uri "/chat/completions"))
                       (if-let [reason (budget-exceeded-reason @stats limits)]
                         (do (swap! stats update :rejected inc)
                             {:status 429
                              :headers {"Content-Type" "application/json"}
                              :body (json/write-str {:error {:type "proxy_budget_exceeded" :message reason}})})
                         (do
                           (a/<!! sem-ch)
                           (swap! stats #(-> % (update :requests inc) (update :in-flight inc)))
                           (forward-completion! target-url (:headers req) (read-req-body req) stats sem-ch)))

                       :else
                       {:status 404
                        :headers {"Content-Type" "application/json"}
                        :body (json/write-str {:error {:message (str "Not found: " uri)}})})))
         srv (adapter/run-server handler {:port (long port) :host "127.0.0.1"})
         bound-port (:port srv)]
     {:server srv
      :port bound-port
      :base-url (str "http://127.0.0.1:" bound-port)
      :stats-atom stats
      :sem-ch sem-ch
      :stop! (fn [] (try (adapter/stop-server srv {:drain-timeout-ms 500}) (catch Throwable _)))})))
