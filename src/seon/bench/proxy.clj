(ns seon.bench.proxy
  "Shared local OpenAI-compat model quota & telemetry proxy for parallel isolated Seon instances.
   Enforces a fiber-parking global concurrency cap (`max-in-flight`) and optional hard request/token
   ceilings (`max-requests`, `max-tokens`) across N instances while transparently forwarding SSE streams."
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [jolt.http-client :as http]
            [jolt.http.sse :as sse]
            [jolt.io-poller :as poller]
            [ring-chez.sse :as rsse]
            [ring-chez.adapter :as adapter]
            [seon.model.wire :as mwire]
            [clojure.core.async.flow :as flow]))

(defn stop!
  "Stops a running `seon.bench.proxy` server cleanly."
  {:malli/schema [:=> [:cat [:map [:stop! :seon.host/fn]]] :nil]}
  [proxy]
  (when-let [f (:stop! proxy)] (f))
  nil)

(defn stats
  "Returns the current proxy usage & quota metrics map."
  {:malli/schema [:=> [:cat [:map [:stats-fn :seon.host/fn]]] :map]}
  [proxy]
  ((:stats-fn proxy)))

(defn- extract-usage-counts
  "Pure helper extracting normalized `{:prompt :completion :cache-hit}` token counts from an OpenAI/DeepSeek usage map."
  [usage]
  (when (map? usage)
    {:prompt (long (or (:prompt_tokens usage) 0))
     :completion (long (or (:completion_tokens usage) 0))
     :cache-hit (long (or (:prompt_cache_hit_tokens usage)
                          (get-in usage [:prompt_tokens_details :cached_tokens])
                          0))}))

(defn ^:seon.flow/edge forward-completion!
  "Forwards one `/chat/completions` HTTP POST to `upstream-url`, streaming SSE bytes through on `a/io-thread`
   and reporting usage/release to the serialized Flow gate `req-ch`."
  {:malli/schema [:=> [:cat :string :map :string :seon.host/chan] :map]}
  [upstream-url req-headers body-str req-ch]
  (let [auth (or (get req-headers "authorization") (get req-headers "Authorization"))
        accept (or (get req-headers "accept") (get req-headers "Accept") "text/event-stream")
        hdrs (cond-> {"Content-Type" "application/json" "Accept" accept}
               auth (assoc "Authorization" auth))]
    (try
      (let [resp (http/post upstream-url {:headers hdrs :body body-str :as :stream :throw false})
            status (long (or (:status resp) 502))
            ct (or (some (fn [[k v]] (when (= "content-type" (str/lower-case (str k))) (str v)))
                         (:headers resp))
                   "application/json")
            body (:body resp)
            sse? (and (= 200 status) (str/includes? (str/lower-case ct) "text/event-stream"))]
        (if sse?
          (let [out-ch (a/chan 64)
                reg-ch (a/chan 1)
                _ (a/put! req-ch {:op :register-stream :stream body :reply-ch reg-ch})
                sid (first (a/alts!! [reg-ch (a/timeout 2000)]))]
            (a/io-thread
              (let [client-gone? (volatile! false)
                    err? (volatile! false)]
                (try
                  (when (and body sid)
                    (with-open [rdr (io/reader body :encoding "UTF-8")]
                      (sse/read-events!
                       rdr
                       {:on-event (fn [{:keys [event data id]}]
                                    (when-let [parsed (mwire/decode-sse-data data)]
                                      (when-let [usage (:usage parsed)]
                                        (a/put! req-ch {:op :usage :usage usage})))
                                    (let [ev-map (cond-> {:data data}
                                                   (and event (not= event "message")) (assoc :event event)
                                                   id (assoc :id id))]
                                      (when-not (a/>!! out-ch ev-map)
                                        (vreset! client-gone? true)
                                        (try (.close ^java.io.InputStream body) (catch Throwable _)))))})))
                  (catch Throwable _
                    (when-not @client-gone? (vreset! err? true)))
                  (finally
                    (a/close! out-ch)
                    (a/put! req-ch {:op :release :error? @err? :stream-id sid})))))
            (rsse/event-response out-ch))
          (let [resp-str (try
                           (cond
                             (nil? body) ""
                             (string? body) body
                             :else (with-open [in ^java.io.InputStream body] (slurp in :encoding "UTF-8")))
                           (finally
                             (a/put! req-ch {:op :release :error? false})))]
            (when (= 200 status)
              (when-let [parsed (try (json/read-str resp-str :key-fn keyword) (catch Throwable _ nil))]
                (when-let [u (:usage parsed)]
                  (a/put! req-ch {:op :usage :usage u}))))
            {:status status :headers {"Content-Type" ct} :body resp-str})))
      (catch Throwable ex
        (a/put! req-ch {:op :release :error? true})
        {:status 502 :headers {"Content-Type" "application/json"}
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

(defn- apply-usage-to-stats [s usage-map]
  (if-let [{:keys [prompt completion cache-hit]} (extract-usage-counts usage-map)]
    (-> s
        (update :prompt-tokens + prompt)
        (update :completion-tokens + completion)
        (update :cache-hit-tokens + cache-hit)
        (update :total-tokens + prompt completion))
    s))

(defn- drain-gate-waiters [st]
  (loop [cur st]
    (if (and (seq (:waiting cur)) (< (long (:in-flight cur)) (long (:cap cur))))
      (let [rch (peek (:waiting cur))
            cur1 (update cur :waiting pop)]
        (if-let [reason (budget-exceeded-reason (:stats cur1) (:limits cur1))]
          (do (a/put! rch {:ok? false :status 429 :reason reason})
              (a/close! rch)
              (recur (update-in cur1 [:stats :rejected] inc)))
          (do (a/put! rch {:ok? true})
              (a/close! rch)
              (recur (-> cur1
                         (update :in-flight inc)
                         (update-in [:stats :requests] inc)
                         (update-in [:stats :in-flight] inc))))))
      cur)))

(defn- proxy-gate-proc
  ([] {:params {:req-ch "Gate command channel" :cap "Max in-flight" :limits "Budget limits" :init-stats "Initial stats" :final-stats "Final stats promise"}
       :ins {} :outs {} :workload :compute
       :doc "Serialized Flow gate enforcing atomic concurrency + request/token budgets and upstream stream teardown."})
  ([{:keys [req-ch cap limits init-stats final-stats]}]
   {:cap (long cap) :limits limits :stats init-stats :in-flight 0
    :waiting clojure.lang.PersistentQueue/EMPTY :streams {} :stream-seq 0
    :stopped? false :final-stats final-stats :req-ch req-ch
    ::flow/in-ports {:req req-ch}})
  ([st transition]
   (if (= transition ::flow/stop)
     (do
       (deliver (:final-stats st) (:stats st))
       (doseq [rch (:waiting st)]
         (a/put! rch {:ok? false :status 503 :reason "Proxy stopped"})
         (a/close! rch))
       (doseq [[_ ^java.io.InputStream s] (:streams st)]
         (try (.close s) (catch Throwable _ nil)))
       (a/close! (:req-ch st))
       (assoc st :stopped? true :waiting clojure.lang.PersistentQueue/EMPTY :streams {}))
     st))
  ([st _ msg]
   (if (nil? msg)
     [st nil]
     (case (:op msg)
       :stats
       (do (when-let [rch (:reply-ch msg)] (a/put! rch (:stats st)) (a/close! rch))
           [st nil])

       :acquire
       (let [rch (:reply-ch msg)]
         (if (:stopped? st)
           (do (a/put! rch {:ok? false :status 503 :reason "Proxy stopped"}) (a/close! rch) [st nil])
           [(drain-gate-waiters (update st :waiting conj rch)) nil]))

       :usage
       [(update st :stats apply-usage-to-stats (:usage msg)) nil]

       :register-stream
       (let [sid (inc (long (:stream-seq st)))
             rch (:reply-ch msg)]
         (if (:stopped? st)
           (do (when-let [^java.io.InputStream s (:stream msg)] (try (.close s) (catch Throwable _ nil)))
               (when rch (a/put! rch nil) (a/close! rch))
               [st nil])
           (do (when rch (a/put! rch sid) (a/close! rch))
               [(-> st (assoc :stream-seq sid) (assoc-in [:streams sid] (:stream msg))) nil])))

       :release
       (let [sid (:stream-id msg)
             st1 (-> st
                     (update :in-flight #(max 0 (dec (long %))))
                     (update-in [:stats :in-flight] #(max 0 (dec (long %))))
                     (cond-> (:error? msg) (update-in [:stats :errors] inc)
                             sid (update :streams dissoc sid)))]
         [(drain-gate-waiters st1) nil])

       [st nil]))))

(defn ^:seon.flow/edge start!
  "Starts a local shared Flow-gated OpenAI-compat proxy bound to `127.0.0.1:<port>` (default `0` = ephemeral)."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map]]}
  ([] (start! {}))
  ([{:keys [upstream-url port max-in-flight max-requests max-tokens]
     :or {upstream-url (or (System/getenv "SEON_AI_BASE_URL") "https://api.deepseek.com")
          port 0 max-in-flight 8}}]
   (let [clean-base (str/replace (str upstream-url) #"/+$" "")
         target-url (str clean-base "/chat/completions")
         cap (max 1 (long max-in-flight))
         init-stats {:requests 0 :in-flight 0 :rejected 0 :errors 0
                     :prompt-tokens 0 :completion-tokens 0 :cache-hit-tokens 0 :total-tokens 0
                     :max-in-flight cap :max-requests max-requests :max-tokens max-tokens :upstream-url clean-base}
         limits {:max-requests max-requests :max-tokens max-tokens}
         req-ch (a/chan 256)
         final-stats (promise)
         g (flow/create-flow {:procs {:gate {:proc (flow/process proxy-gate-proc)
                                             :args {:req-ch req-ch :cap cap :limits limits
                                                    :init-stats init-stats :final-stats final-stats}}}
                              :conns []})
         _ (do (flow/start g) (flow/resume g))
         get-stats! (fn []
                      (if (realized? final-stats)
                        @final-stats
                        (let [rch (a/chan 1)]
                          (if (a/put! req-ch {:op :stats :reply-ch rch})
                            (or (first (a/alts!! [rch (a/timeout 2000)]))
                                (when (realized? final-stats) @final-stats)
                                init-stats)
                            (if (realized? final-stats) @final-stats init-stats)))))
         handler (fn [req]
                   (let [m (:request-method req) uri (str (:uri req))]
                     (cond
                       (and (= m :get) (= uri "/stats"))
                       {:status 200 :headers {"Content-Type" "application/json"} :body (json/write-str (get-stats!))}

                       (and (= m :get) (str/ends-with? uri "/models"))
                       (let [req-hdrs (:headers req)
                             auth (or (get req-hdrs "authorization") (get req-hdrs "Authorization"))
                             hdrs (cond-> [["Accept" "application/json"]] auth (conj ["Authorization" auth]))
                             resp (try (mwire/http-get! (str clean-base "/models") hdrs)
                                       (catch Throwable ex {:status 502 :body (json/write-str {:error {:message (.getMessage ex)}})}))]
                         {:status (long (or (:status resp) 502)) :headers {"Content-Type" "application/json"} :body (str (:body resp ""))})

                       (and (= m :post) (str/ends-with? uri "/chat/completions"))
                       (let [rch (a/chan 1)
                             _ (a/put! req-ch {:op :acquire :reply-ch rch})
                             grant (or (a/<!! rch) {:ok? false :status 503 :reason "Proxy stopped"})]
                         (if-not (:ok? grant)
                           {:status (long (:status grant 429))
                            :headers {"Content-Type" "application/json"}
                            :body (json/write-str {:error {:type "proxy_budget_exceeded" :message (:reason grant)}})}
                           (let [body-str (try (read-req-body req)
                                               (catch Throwable ex
                                                 (a/put! req-ch {:op :release :error? true})
                                                 (throw ex)))]
                             (forward-completion! target-url (:headers req) body-str req-ch))))

                       :else
                       {:status 404 :headers {"Content-Type" "application/json"}
                        :body (json/write-str {:error {:message (str "Not found: " uri)}})})))
         srv (adapter/run-server handler (cond-> {:port (long port) :host "127.0.0.1"} (poller/available?) (assoc :strategy :fibers)))
         bound-port (:port srv)]
     {:server srv :port bound-port :base-url (str "http://127.0.0.1:" bound-port)
      :stats-fn get-stats! :req-ch req-ch
      :stop! (fn []
               (try (flow/stop g) (catch Throwable _ nil))
               (try (adapter/stop-server srv {:drain-timeout-ms 500}) (catch Throwable _ nil)))})))