(ns seon.agent.web.host
  "Implement the JVM java.net.http leaf for the web capability."
  (:refer-clojure :exclude [send])
  (:require
   [clojure.string :as str]
   [seon.agent.web :as web]
   [seon.agent.web.core :as internal]
   [seon.ai.tokens :as tokens])
  (:import
   (java.io ByteArrayOutputStream InputStream)
   (java.net InetAddress URI)
   (java.net.http HttpClient HttpClient$Redirect HttpRequest
                  HttpRequest$Builder HttpRequest$BodyPublishers
                  HttpResponse$BodyHandlers)
   (java.nio.charset StandardCharsets)
   (java.time Duration)
   (com.fasterxml.jackson.databind ObjectMapper)))

(defonce ^:private client
  (-> (HttpClient/newBuilder)
      (.followRedirects HttpClient$Redirect/NEVER)
      (.connectTimeout (Duration/ofSeconds 30))
      .build))
(defonce ^:private json-mapper (ObjectMapper.))

(def ^:private redirect-statuses #{301 302 303 307 308})
(declare ^:dynamic *services*)

(defn- enabled-from-env?
  []
  (let [value (System/getenv "SEON_WEB")]
    (boolean (and value (not= "" value) (not= "0" value)))))

(defn- default-services
  []
  {::enabled? enabled-from-env?
   ::put! (fn [_]
            {:my.blob/ok? false
             :my.blob/error "No JVM blob leaf is installed."})
   ::transact! (constantly {:seon.db/ok? false})
   ::now #(java.util.Date.)})

(defn- policy
  [configuration]
  {::web/policy (or (::web/policy configuration) :public-only)
   ::web/allowed-domains (vec (or (::web/allowed-domains configuration) []))})

(defn- search-backend
  [configuration]
  (or (::web/search-backend configuration) :gemini-grounding))

(defn- api-key?
  [backend]
  (case backend
    :gemini-grounding (not (str/blank? (System/getenv "GEMINI_API_KEY")))
    :serper (not (str/blank? (System/getenv "SERPER_API_KEY")))
    false))

(defn- java-data
  [value]
  (cond
    (instance? java.util.Map value)
    (into {}
          (map (fn [[key child]]
                 [(keyword (str key)) (java-data child)]))
          value)

    (instance? java.util.List value)
    (mapv java-data value)

    :else value))

(defn- json-body
  [value]
  (.writeValueAsString json-mapper value))

(defn- parse-json
  [text]
  (try
    (java-data (.readValue json-mapper text Object))
    (catch Throwable _ nil)))

(defn- post-json
  [url headers body timeout-ms]
  (try
    (let [builder
          (reduce-kv
           (fn [request name value]
             (.header ^HttpRequest$Builder request name value))
           (-> (HttpRequest/newBuilder (URI/create url))
               (.timeout (Duration/ofMillis (long timeout-ms)))
               (.header "Content-Type" "application/json"))
           headers)
          request
          (-> builder
              (.POST (HttpRequest$BodyPublishers/ofString (json-body body)))
              .build)
          response
          (.send client request (HttpResponse$BodyHandlers/ofString))]
      {::web/status (.statusCode response)
       ::web/body (.body response)})
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      {:seon.error/message
       "search was interrupted by the invocation watchdog"})
    (catch java.net.http.HttpTimeoutException _
      {:seon.error/message
       (str "search timed out after " timeout-ms " ms")})
    (catch Throwable throwable
      {:seon.error/message
       (str "search transport error: "
            (or (ex-message throwable) throwable))})))

(defn- addresses
  [hostname]
  (try
    (mapv #(.getHostAddress ^InetAddress %)
          (InetAddress/getAllByName hostname))
    (catch Throwable _ [])))

(defn- admitted?
  [original-url current policy]
  (try
    (let [uri (URI/create current)
          scheme (.getScheme uri)
          hostname (.getHost uri)]
      (cond
        (not (contains? #{"http" "https"} scheme))
        (internal/err original-url
                      (str "only http/https URLs are supported (got "
                           scheme ") — file: is seon.agent.fs's job."))

        (str/blank? hostname)
        (internal/err original-url (str "not a valid URL: " (pr-str current)))

        :else
        (if-let [reason
                 (internal/host-policy-decision
                  policy hostname (addresses hostname))]
          (if (= reason ::internal/dns-fail)
            (internal/err original-url
                          (str "could not resolve host " hostname
                               " (DNS lookup failed)."))
            (internal/err
             original-url
             (str "web policy refused this target: " reason
                  " — inspect the policy with (seon.agent.web/grants {}).")
             {::web/final-url current ::web/policy (::web/policy policy)}))
          uri)))
    (catch Throwable _
      (internal/err original-url (str "not a valid URL: " (pr-str current))))))

(defn- text-lane
  [content-type]
  (let [content-type (str/lower-case (or content-type ""))]
    (cond
      (or (str/includes? content-type "text/html")
          (str/includes? content-type "application/xhtml")) :html
      (str/includes? content-type "text/markdown") :markdown
      (or (str/includes? content-type "application/json")
          (str/includes? content-type "+json")) :json
      (str/starts-with? content-type "text/") :text
      :else :binary)))

(defn- read-capped
  [^InputStream stream cap]
  (with-open [input stream
              output (ByteArrayOutputStream.)]
    (let [buffer (byte-array 8192)]
      (loop [total 0]
        (let [read (.read input buffer)]
          (cond
            (neg? read)
            {::web/body (.toString output StandardCharsets/UTF_8)
             ::web/truncated? false}

            (>= total cap)
            {::web/body (.toString output StandardCharsets/UTF_8)
             ::web/truncated? true}

            :else
            (let [accepted (min read (- cap total))]
              (.write output buffer 0 accepted)
              (if (< accepted read)
                {::web/body (.toString output StandardCharsets/UTF_8)
                 ::web/truncated? true}
                (recur (+ total accepted))))))))))

(defn- response-header
  [response name]
  (some-> response .headers (.firstValue name) (.orElse nil)))

(defn- transport
  [url timeout-ms]
  (loop [current url
         redirects 0
         visited #{}]
    (let [target (admitted? url current
                            (::policy *services*))]
      (if (map? target)
        target
        (let [step
              (try
                (let [request (-> (HttpRequest/newBuilder ^URI target)
                                  (.timeout (Duration/ofMillis (long timeout-ms)))
                                  (.header "Accept"
                                           "text/markdown, text/html;q=0.9, */*;q=0.8")
                                  (.header "User-Agent" "seon-agent/1.0")
                                  (.header "Accept-Language" "en-US,en;q=0.9")
                                  .GET
                                  .build)
                      response (.send client request
                                      (HttpResponse$BodyHandlers/ofInputStream))
                      status (.statusCode response)]
                  (if (contains? redirect-statuses status)
                    (let [location (response-header response "location")]
                      (.close ^InputStream (.body response))
                      (cond
                        (nil? location)
                        (internal/err
                         url (str "redirect (" status
                                  ") with no location header."))

                        (>= (inc redirects) (::maximum-redirects *services*))
                        (internal/err
                         url (str "too many redirects (cap "
                                  (::maximum-redirects *services*)
                                  " from :seon.config.web/maximum-redirects).")
                         {:seon.config/key
                          :seon.config.web/maximum-redirects})

                        :else
                        (let [next (str (.resolve ^URI target location))]
                          (if (contains? visited next)
                            (internal/err url "redirect loop detected.")
                            {::redirect next}))))
                    (let [content-type
                          (or (response-header response "content-type") "")
                          lane (text-lane content-type)]
                      (if (= :binary lane)
                        (do
                          (.close ^InputStream (.body response))
                          {::web/ok? true
                           ::web/binary? true
                           ::web/status status
                           ::web/final-url current
                           ::web/content-type
                           (if (str/blank? content-type)
                             "application/octet-stream" content-type)})
                        (merge {::web/ok? true
                                ::web/status status
                                ::web/final-url current
                                ::web/content-type content-type
                                ::web/lane lane}
                               (read-capped
                                (.body response)
                                (::maximum-response-bytes *services*)))))))
                (catch InterruptedException interrupted
                  (.interrupt (Thread/currentThread))
                  (internal/err
                   url "web fetch was interrupted by the invocation watchdog"))
                (catch java.net.http.HttpTimeoutException _
                  (internal/err
                   url (str "timed out after " timeout-ms
                            " ms — raise :seon.agent.web/timeout-ms if the host is slow.")))
                (catch Throwable throwable
                  (internal/err
                   url (str "transport error: "
                            (or (ex-message throwable) throwable)))))]
          (if-let [next (::redirect step)]
            (recur next (inc redirects) (conj visited next))
            step))))))

(def ^:dynamic *services* (default-services))

(defn- html-title
  [body]
  (some-> (re-find #"(?is)<title[^>]*>(.*?)</title>" body)
          second
          str/trim
          not-empty))

(defn- extract
  [lane body]
  (case lane
    :html {:text (-> body
                     (str/replace #"(?is)<script.*?</script>" " ")
                     (str/replace #"(?is)<style.*?</style>" " ")
                     (str/replace #"(?s)<[^>]+>" " ")
                     (str/replace #"\s+" " ")
                     str/trim)
           :title (html-title body)
           :extractor :raw}
    :markdown {:text body :extractor :markdown-passthrough}
    :json {:text body :extractor :json}
    {:text body :extractor :text}))

(defn grants
  "Return the JVM web grant and resolved target policy."
  [{configuration :seon.config/configuration}]
  (let [policy (policy configuration)
        backend (search-backend configuration)]
    {::web/enabled? ((::enabled? *services*))
     ::web/policy (::web/policy policy)
     ::web/allowed-domains (::web/allowed-domains policy)
     ::web/search-backend (if (api-key? backend) backend :none)}))

(defn fetch
  "Fetch and blob one web resource through java.net.http."
  [{::web/keys [url timeout-ms max-preview-tokens]
    configuration :seon.config/configuration}]
  (let [timeout-ms
        (or timeout-ms
            (:seon.config.web/default-timeout-ms configuration))
        max-preview-tokens
        (or max-preview-tokens
            (:seon.config.web/default-preview-tokens configuration))]
    (cond
      (not ((::enabled? *services*))) (internal/ungranted url)
      (str/blank? url)
      (internal/err url
                    ":seon.agent.web/url is required and must be non-blank.")
      :else
      (binding [*services*
                (assoc *services*
                       ::policy (policy configuration)
                       ::maximum-response-bytes
                       (:seon.config.web/maximum-response-bytes configuration)
                       ::maximum-redirects
                       (:seon.config.web/maximum-redirects configuration))]
        (let [response (transport url timeout-ms)]
          (cond
            (not (::web/ok? response)) response
            (::web/binary? response)
            (internal/err
             url
             (str "refusing binary content (" (::web/content-type response)
                  ") — this function extracts text; a blob-tier binary "
                  "fetch is a later capability.")
             {::web/status (::web/status response)
              ::web/final-url (::web/final-url response)
              ::web/content-type (::web/content-type response)})
            :else
            (let [{:keys [text title extractor]}
                  (extract (::web/lane response) (::web/body response))
                  total (tokens/estimate text)
                  blob ((::put! *services*)
                        {:my.blob/content text :my.blob/media :markdown})]
              (if-not (:my.blob/ok? blob)
                (internal/err
                 url
                 (str "extracted content but the blob store rejected it: "
                      (:my.blob/error blob)))
                (let [preview (if (> total max-preview-tokens)
                                (str (subs text 0
                                           (min (count text)
                                                (tokens/estimate-chars
                                                 max-preview-tokens)))
                                     "…")
                                text)
                      now ((::now *services*))
                      projection
                      (cond-> {::web/url url
                               ::web/final-url (::web/final-url response)
                               ::web/status (::web/status response)
                               ::web/content-type (::web/content-type response)
                               ::web/extractor extractor
                               ::web/total-tokens total
                               ::web/blob-hash (:my.blob/hash blob)
                               ::web/fetched-at now}
                        title (assoc ::web/title title))]
                  ((::transact! *services*) {:seon.db/tx-data [projection]})
                  (cond-> {::web/ok? true
                           ::web/url url
                           ::web/final-url (::web/final-url response)
                           ::web/status (::web/status response)
                           ::web/content-type (::web/content-type response)
                           ::web/extractor extractor
                           ::web/preview preview
                           ::web/preview-tokens (tokens/estimate preview)
                           ::web/total-tokens total
                           ::web/truncated? (boolean (::web/truncated? response))
                           ::web/blob-hash (:my.blob/hash blob)
                           ::web/fetched-at now}
                    (::web/truncated? response)
                    (assoc :seon.config/key
                           :seon.config.web/maximum-response-bytes)
                    (> total max-preview-tokens)
                    (assoc :seon.config/preview-key
                           :seon.config.web/default-preview-tokens)
                    title (assoc ::web/title title)))))))))))

(defn search
  "Search through the configured JVM provider and portable interpreter."
  [{::web/keys [query max-results timeout-ms]
    configuration :seon.config/configuration}]
  (cond
    (not ((::enabled? *services*))) (internal/search-ungranted query)
    (str/blank? query)
    (internal/search-err
     query ":seon.agent.web/query is required and must be non-blank.")
    :else
    (let [backend (search-backend configuration)
          timeout-ms
          (or timeout-ms
              (:seon.config.web/default-timeout-ms configuration))
          n (max 1
                 (min
                  (or max-results
                      (:seon.config.web/default-search-results configuration))
                  (:seon.config.web/maximum-search-results configuration)))
          key (case backend
                :gemini-grounding (System/getenv "GEMINI_API_KEY")
                :serper (System/getenv "SERPER_API_KEY")
                nil)]
      (if (str/blank? key)
        (internal/search-err
         query
         (str "no search backend key is configured for " backend
              "; inspect with (seon.agent.web/grants {})."))
        (let [response
              (case backend
                :gemini-grounding
                (post-json
                 (str "https://generativelanguage.googleapis.com/v1beta/models/"
                      (or (::web/search-model configuration)
                          "gemini-2.5-flash")
                      ":generateContent")
                 {"x-goog-api-key" key}
                 {"contents" [{"parts" [{"text" query}]}]
                  "tools" [{"google_search" {}}]}
                 timeout-ms)

                :serper
                (post-json "https://google.serper.dev/search"
                           {"X-API-KEY" key}
                           {"q" query "num" n}
                           timeout-ms)

                {:seon.error/message
                 (str "search backend " backend " is not wired.")})]
          (if-let [message (:seon.error/message response)]
            (internal/search-err query message)
            (let [status (::web/status response)
                  body (parse-json (::web/body response))]
              (cond
                (>= status 400)
                (internal/search-err
                 query
                 (str (name backend) " HTTP " status
                      (when-let [provider-message
                                 (or (get-in body [:error :message])
                                     (:message body))]
                        (str " — " provider-message)))
                 {::web/status status})

                (nil? body)
                (internal/search-err
                 query (str (name backend) " returned a non-JSON body."))

                :else
                (let [parsed
                      (case backend
                        :gemini-grounding
                        (internal/parse-grounding body n)
                        :serper
                        (internal/parse-serper body n))
                      results (::web/results parsed)
                      answer (::web/answer parsed)
                      hint
                      (if (seq results)
                        (if (= :gemini-grounding backend)
                          (str "the ::url values are Google grounding-redirect "
                               "URIs — fetch a row to recover the canonical page.")
                          "the ::url values are real page urls — fetch a row.")
                        "no web sources were returned for this query.")]
                  (cond-> {::web/ok? true
                           ::web/query query
                           ::web/backend backend
                           ::web/results results
                           ::web/result-count (::web/result-count parsed)
                           ::web/hint hint}
                    (seq (::web/queries parsed))
                    (assoc ::web/queries (::web/queries parsed))
                    (not (str/blank? answer))
                    (assoc ::web/answer answer
                           ::web/answer-tokens (tokens/estimate answer))))))))))))

(defn services
  "Build one JVM web leaf over blob and database callbacks."
  [overrides]
  (let [services (merge (default-services) overrides)]
    {::web/grants
     (fn [request] (binding [*services* services] (grants request)))
     ::web/fetch
     (fn [request] (binding [*services* services] (fetch request)))
     ::web/search
     (fn [request] (binding [*services* services] (search request)))}))
