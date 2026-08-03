(ns seon.web.jvm
  "Protected JVM implementation of the `my.web` capability family."
  (:refer-clojure :exclude [fetch])
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.web.extract])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream FilterInputStream
            InputStream SequenceInputStream]
           [java.net InetAddress URI UnknownHostException]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$Builder
            HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util Optional]))

(set! *warn-on-reflection* true)

(defonce ^{:private true :tag HttpClient} client
  (-> (HttpClient/newBuilder)
      (.followRedirects HttpClient$Redirect/NEVER)
      .build))

(def ^:private redirect-statuses #{301 302 303 307 308})
(def ^:private blocked-hostnames
  #{"localhost" "localhost.localdomain" "metadata.google.internal"})
(def ^:private io-buffer-bytes 65536)

(defn- error-value
  [marker message data]
  (merge {marker true :seon.error/message message} data))

(defn- classified-error
  [error marker message data]
  (let [classified (ex-data error)]
    (if (and (map? classified) (:seon.error/message classified))
      classified
      (error-value marker message data))))

(defn- unsigned-byte
  [octets index]
  (bit-and 0xff (aget ^bytes octets index)))

(defn- private-address?
  [^InetAddress address]
  (let [octets (.getAddress address)
        size (alength ^bytes octets)]
    (or (.isAnyLocalAddress address)
        (.isLoopbackAddress address)
        (.isLinkLocalAddress address)
        (.isSiteLocalAddress address)
        (.isMulticastAddress address)
        (and (= 4 size)
             (let [a (unsigned-byte octets 0)
                   b (unsigned-byte octets 1)]
               (or (= 0 a)
                   (= 10 a)
                   (= 127 a)
                   (and (= 100 a) (<= 64 b 127))
                   (and (= 169 a) (= 254 b))
                   (and (= 172 a) (<= 16 b 31))
                   (and (= 192 a) (= 168 b)))))
        (and (= 16 size)
             (= 0xfc (bit-and 0xfe (unsigned-byte octets 0)))))))

(defn- resolve-addresses
  [hostname]
  (vec (InetAddress/getAllByName hostname)))

(defn- normalized-hostname
  [hostname]
  (let [lower (str/lower-case hostname)]
    (if (str/ends-with? lower ".")
      (subs lower 0 (dec (count lower)))
      lower)))

(defn- admitted-uri
  [url]
  (try
    (let [uri (URI/create url)
          scheme (some-> (.getScheme uri) str/lower-case)
          hostname (some-> (.getHost uri) normalized-hostname)]
      (cond
        (not (contains? #{"http" "https"} scheme))
        (error-value :my.web/invalid-url
                     "Only absolute HTTP(S) URLs can be fetched."
                     {:my.web/url url})

        (or (str/blank? hostname) (.getUserInfo uri))
        (error-value :my.web/invalid-url
                     "The URL must name a host and cannot contain user info."
                     {:my.web/url url})

        (contains? blocked-hostnames hostname)
        (error-value :my.web/target-refused
                     "The web target is not a public hostname."
                     {:my.web/url url})

        :else
        (try
          (let [addresses (resolve-addresses hostname)]
            (cond
              (empty? addresses)
              (error-value :my.web/dns-failed
                           "The web target did not resolve to an address."
                           {:my.web/url url})

              (some private-address? addresses)
              (error-value :my.web/target-refused
                           "The web target resolves to a non-public address."
                           {:my.web/url url})

              :else uri))
          (catch UnknownHostException _
            (error-value :my.web/dns-failed
                         "The web target did not resolve to an address."
                         {:my.web/url url})))))
    (catch Throwable _
      (error-value :my.web/invalid-url
                   "The URL is not a valid absolute HTTP(S) URI."
                   {:my.web/url url}))))

(defn- response-header
  [^HttpResponse response name]
  (let [value ^Optional (.firstValue (.headers response) name)]
    (when (.isPresent value) (.get value))))

(defn- request-builder
  ^HttpRequest$Builder
  [^URI uri timeout-ms headers]
  (reduce-kv
   (fn [builder name value]
     (.header ^java.net.http.HttpRequest$Builder builder name value))
   (-> (HttpRequest/newBuilder uri)
       (.timeout (Duration/ofMillis (long timeout-ms))))
   headers))

(defn- send-fetch
  [^URI uri method timeout-ms]
  (let [builder ^HttpRequest$Builder
        (request-builder
         uri timeout-ms
         {"Accept" "text/markdown, text/html;q=0.9, */*;q=0.8"
          "Accept-Language" "en-US,en;q=0.9"
          "User-Agent" "seon/1.0"})
        request ^HttpRequest
        (if (= :head method)
          (-> builder
              (.method "HEAD" (HttpRequest$BodyPublishers/noBody))
              .build)
          (-> builder .GET .build))]
    (.send ^HttpClient client request
           (HttpResponse$BodyHandlers/ofInputStream))))

(defn- send-search
  [^URI uri timeout-ms key body]
  (let [request ^HttpRequest
        (-> (request-builder
             uri timeout-ms
             {"Content-Type" "application/json"
              "X-API-KEY" key})
            (.POST (HttpRequest$BodyPublishers/ofString body))
            .build)]
    (.send ^HttpClient client request
           (HttpResponse$BodyHandlers/ofInputStream))))

(defn- content-length
  [response]
  (some-> (response-header response "content-length") parse-long))

(defn- ensure-declared-length!
  [response limit url]
  (when-let [declared (content-length response)]
    (when (> declared limit)
      (throw
       (ex-info
        "The web response exceeds the configured byte ceiling."
        {:my.web/response-limit true
         :seon.error/message
         "The web response exceeds the configured byte ceiling."
         :my.web/url url})))))

(defn- bounded-input
  [^InputStream input limit url]
  (let [read-count (volatile! 0)
        refuse (fn []
                 (throw
                  (ex-info
                   "The web response exceeds the configured byte ceiling."
                   {:my.web/response-limit true
                    :seon.error/message
                    "The web response exceeds the configured byte ceiling."
                    :my.web/url url})))]
    (proxy [FilterInputStream] [input]
      (read
        ([]
         (if (= @read-count limit)
           (let [next-byte (.read input)]
             (if (neg? next-byte) -1 (refuse)))
           (let [next-byte (.read input)]
             (when-not (neg? next-byte) (vswap! read-count inc))
             next-byte)))
        ([buffer offset length]
         (if (= @read-count limit)
           (let [next-byte (.read input)]
             (if (neg? next-byte) -1 (refuse)))
           (let [allowed (int (min length (- limit @read-count)))
                 observed (.read input ^bytes buffer offset allowed)]
             (when (pos? observed) (vswap! read-count + observed))
             observed)))))))

(defn- inline-body
  [octets]
  {:my.web.body/bytes (long (alength ^bytes octets))
   :my.web.body/digest (schema/sha-256 [octets])
   :my.web.body/octet-values (mapv #(bit-and 0xff %) ^bytes octets)})

(defn- capture-body!
  [connection ^InputStream input max-inline max-response url force-blob?]
  (with-open [^InputStream bounded (bounded-input input max-response url)]
    (let [prefix (.readNBytes bounded (int (inc max-inline)))]
      (if (and (not force-blob?) (<= (alength ^bytes prefix) max-inline))
        {:seon.web.jvm/body (inline-body prefix)
         :seon.web.jvm/octet-array prefix}
        (let [source (SequenceInputStream.
                      (ByteArrayInputStream. prefix) bounded)
              stored (blob/put-binary! connection source)]
          {:seon.web.jvm/body
           {:my.web.body/bytes (:seon.blob/size stored)
            :my.web.body/digest (:seon.blob/digest stored)
            :my.web.body/blob (:seon.blob/digest stored)}})))))

(defn- read-blob
  [connection digest size]
  (let [output (ByteArrayOutputStream.)]
    (loop [offset 0]
      (when (< offset size)
        (let [octets (blob/read-chunk connection digest offset io-buffer-bytes)]
          (when-not octets
            (throw
             (ex-info "The captured response blob is unavailable."
                      {:my.web/transport-failed true
                       :seon.error/message
                       "The captured response blob is unavailable."})))
          (.write output ^bytes octets)
          (recur (+ offset (alength ^bytes octets))))))
    (.toByteArray output)))

(defn- captured-octets
  [connection captured]
  (or (:seon.web.jvm/octet-array captured)
      (let [body (:seon.web.jvm/body captured)]
        (read-blob connection (:my.web.body/blob body)
                   (:my.web.body/bytes body)))))

(defn- split-once
  [text separator]
  (let [index (.indexOf ^String text ^String separator)]
    (if (neg? index)
      [text]
      [(subs text 0 index)
       (subs text (+ index (count separator)))])))

(defn- split-all
  [text separator]
  (loop [remaining text
         parts []]
    (let [[part tail] (split-once remaining separator)
          next-parts (conj parts part)]
      (if tail
        (recur tail next-parts)
        next-parts))))

(defn- content-type-base
  [content-type]
  (some-> content-type (split-once ";") first str/trim str/lower-case))

(defn- unquote-parameter
  [value]
  (if (and (<= 2 (count value))
           (= \" (first value))
           (= \" (last value)))
    (subs value 1 (dec (count value)))
    value))

(defn- content-charset
  [content-type]
  (some
   (fn [part]
     (let [[name value] (split-once (str/trim part) "=")]
       (when (and value (= "charset" (str/lower-case name)))
         (unquote-parameter (str/trim value)))))
   (rest (split-all (or content-type "") ";"))))

(declare handler-var)

(defn- extraction
  [octets content-type final-url]
  (when (contains? #{"text/html" "application/xhtml+xml"}
                   (content-type-base content-type))
    ((handler-var 'seon.web.extract 'html)
     octets (content-charset content-type) final-url)))

(defn- handler-var
  [namespace symbol]
  (deref (ns-resolve namespace symbol)))

(defn- fetch
  {:malli/schema
   [:=> [:cat :my.web/fetch-request :seon.config/effective]
    [:or :my.web/fetch-result :my.web/error]]}
  [request effective]
  (let [original-url (:my.web/url request)
        method (or (:my.web/method request) :get)
        timeout-ms (:seon.config.web/timeout-ms effective)
        max-response (:seon.config.web/max-response-bytes effective)
        max-inline (:seon.config.web/max-inline-bytes effective)
        max-redirects (:seon.config.web/max-redirects effective)]
    (try
      (loop [current-url original-url
             redirects []
             visited #{original-url}]
        (let [target (admitted-uri current-url)]
          (if (map? target)
            target
            (let [response (send-fetch target method timeout-ms)
                  status (.statusCode ^HttpResponse response)
                  body ^InputStream (.body ^HttpResponse response)]
              (if (contains? redirect-statuses status)
                (let [location (response-header response "location")]
                  (.close body)
                  (cond
                    (nil? location)
                    (error-value :my.web/missing-location
                                 "The redirect response has no Location header."
                                 {:my.web/url current-url
                                  :my.web/status status})

                    (>= (count redirects) max-redirects)
                    (error-value :my.web/redirect-limit
                                 "The redirect chain exceeds its configured bound."
                                 {:my.web/url original-url})

                    :else
                    (let [next-url (str (.resolve ^URI target ^String location))]
                      (if (contains? visited next-url)
                        (error-value :my.web/redirect-loop
                                     "The redirect chain repeats a URL."
                                     {:my.web/url next-url})
                        (recur
                         next-url
                         (conj redirects
                               {:my.web.redirect/from current-url
                                :my.web.redirect/to next-url
                                :my.web.redirect/status status})
                         (conj visited next-url))))))
                (let [content-type (response-header response "content-type")
                      final-url (str (.uri ^HttpResponse response))]
                  (ensure-declared-length! response max-response final-url)
                  (let [captured
                        (capture-body! db/*conn* body max-inline max-response
                                       final-url false)
                        octets (captured-octets db/*conn* captured)
                        base
                        (cond->
                         {:my.web/url original-url
                          :my.web/final-url final-url
                          :my.web/status status
                          :my.web/redirects redirects
                          :my.web/body (:seon.web.jvm/body captured)}
                          content-type
                          (assoc :my.web/content-type content-type))]
                    (if content-type
                      (try
                        (if-let [derived
                                 (extraction octets content-type final-url)]
                          (assoc base :my.web/extraction derived)
                          base)
                        (catch Throwable error
                          (assoc base :my.web/extraction-error
                                 (or (ex-message error)
                                     "HTML extraction failed."))))
                      base))))))))
      (catch java.net.http.HttpTimeoutException _
        (error-value :my.web/timeout
                     "The remote web request exceeded its configured deadline."
                     {:my.web/url original-url}))
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))
        (error-value :my.web/transport-failed
                     "The remote web request was interrupted."
                     {:my.web/url original-url}))
      (catch Throwable error
        (classified-error
         error :my.web/transport-failed
         "The remote web request failed in transport."
         {:my.web/url original-url})))))

(defn- credential
  [variable]
  (when (string? variable) (System/getenv variable)))

(defn- projection
  [symbol]
  (some-> symbol requiring-resolve deref))

(defn- search
  {:malli/schema
   [:=> [:cat :my.web/search-request :seon.config/effective]
    [:or :my.web/search-result :my.web/error]]}
  [request effective]
  (let [query (:my.web/query request)
        endpoint (:seon.config.web/search-endpoint effective)
        credential-variable
        (:seon.config.web/search-api-key-variable effective)
        key (credential credential-variable)
        max-results
        (min (or (:my.web/max-results request)
                 (:seon.config.web/max-search-results effective))
             (:seon.config.web/max-search-results effective))
        target (admitted-uri endpoint)]
    (cond
      (nil? key)
      (error-value :my.web/no-credential
                   "The configured search credential variable is not set."
                   {:my.web/query query})

      (map? target)
      (assoc target :my.web/query query)

      :else
      (try
        (let [request-body (json/write-str {"q" query "num" max-results})
              response (send-search
                        target (:seon.config.web/timeout-ms effective)
                        key request-body)
              status (.statusCode ^HttpResponse response)
              final-url (str (.uri ^HttpResponse response))
              _ (ensure-declared-length!
                 response (:seon.config.web/max-response-bytes effective)
                 final-url)
              captured
              (capture-body!
               db/*conn* (.body ^HttpResponse response)
               (:seon.config.web/max-inline-bytes effective)
               (:seon.config.web/max-response-bytes effective)
               final-url true)
              body (:seon.web.jvm/body captured)
              raw (captured-octets db/*conn* captured)]
          (if-not (<= 200 status 299)
            (error-value :my.web/provider-failed
                         "The configured search provider returned a non-success status."
                         {:my.web/query query :my.web/status status})
            (let [document
                  (try
                    (json/read-str (String. ^bytes raw StandardCharsets/UTF_8))
                    (catch Throwable _ nil))
                  project
                  (projection
                   (:seon.config.web/search-result-projection effective))
                  projected (when (and document project)
                              (project document max-results))]
              (cond
                (nil? document)
                (error-value :my.web/unparseable-response
                             "The successful search response is not readable JSON."
                             {:my.web/query query})

                (nil? projected)
                (error-value :my.web/projection-failed
                             "The configured search result projection failed."
                             {:my.web/query query})

                :else
                (merge {:my.web/query query
                        :my.web/raw-response (:my.web.body/blob body)
                        :my.web/raw-response-bytes (:my.web.body/bytes body)}
                       projected)))))
        (catch java.net.http.HttpTimeoutException _
          (error-value :my.web/timeout
                       "The remote search request exceeded its configured deadline."
                       {:my.web/query query}))
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))
          (error-value :my.web/transport-failed
                       "The remote search request was interrupted."
                       {:my.web/query query}))
        (catch Throwable error
          (classified-error
           error :my.web/transport-failed
           "The remote search request failed in transport."
           {:my.web/query query}))))))
