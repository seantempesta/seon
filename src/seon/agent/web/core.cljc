(ns seon.agent.web.core
  "Own portable web request policy and response interpretation."
  (:require
   [clojure.string :as str]
   [seon.agent.web :as-alias web]))

(declare err search-err)

(def fetch-limit-keys
  [:seon.config.web/default-timeout-ms
   :seon.config.web/maximum-response-bytes
   :seon.config.web/default-preview-tokens
   :seon.config.web/maximum-redirects])

(def search-limit-keys
  [:seon.config.web/default-timeout-ms
   :seon.config.web/default-search-results
   :seon.config.web/maximum-search-results])

(defn missing-limit-key
  "Return the first absent positive portable web-limit config key."
  [configuration keys]
  (some (fn [key]
          (when-not (pos-int? (get configuration key)) key))
        keys))

(defn fetch-config-error
  "Build a flat fetch error naming one unavailable config fact."
  [url key]
  (err url
       (str "The web limit " key
            " is unavailable; apply the governing config.")
       {:seon.config/key key}))

(defn search-config-error
  "Build a flat search error naming one unavailable config fact."
  [query key]
  (search-err query
              (str "The web limit " key
                   " is unavailable; apply the governing config.")
              {:seon.config/key key}))

(defn domain-allowed?
  "Return whether a hostname is covered by an allowed domain."
  [hostname allowed-domains]
  (boolean
   (some (fn [domain]
           (let [domain (str/lower-case domain)]
             (or (= hostname domain)
                 (str/ends-with? hostname (str "." domain)))))
         allowed-domains)))

(defn err
  "Build one flat fetch error value."
  ([url message] (err url message nil))
  ([url message data]
   (cond-> {::web/ok? false
            ::web/url url
            :seon.error/message message}
     (seq data) (assoc :seon.error/data data))))

(defn ungranted
  "Build the guiding default-deny fetch error value."
  [url]
  (err url (str "web access is not granted (default-deny) — the host must "
                "set the SEON_WEB env var (any value but \"0\") before the "
                "pod starts; nothing inside the pod can grant it. Inspect "
                "with (seon.agent.web/grants {}).")))

(defn search-err
  "Build one flat search error value."
  ([query message] (search-err query message nil))
  ([query message data]
   (cond-> {::web/ok? false
            ::web/query query
            :seon.error/message message}
     (seq data) (assoc :seon.error/data data))))

(defn search-ungranted
  "Build the guiding default-deny search error value."
  [query]
  (search-err query
              (str "web access is not granted (default-deny) — search rides "
                   "the SAME SEON_WEB grant as fetch; the host must set "
                   "SEON_WEB (any value but \"0\") before the pod starts. "
                   "Inspect with (seon.agent.web/grants {}).")))

(def blocked-hostnames
  #{"localhost" "localhost.localdomain" "metadata.google.internal"})

(defn private-ipv4?
  "Return whether an IPv4 literal names a private range."
  [address]
  (let [parts (str/split address #"\.")]
    (if (not= 4 (count parts))
      true
      (let [[a b] (map parse-long parts)]
        (boolean
         (or (nil? a) (nil? b)
             (= a 0) (= a 10) (= a 127)
             (and (= a 169) (= b 254))
             (and (= a 172) (<= 16 b 31))
             (and (= a 192) (= b 168))
             (and (= a 100) (<= 64 b 127))))))))

(defn private-ipv6?
  "Return whether an IPv6 literal names a private range."
  [address]
  (let [address (-> address str/lower-case
                    (str/replace #"^\[" "")
                    (str/replace #"\]$" ""))]
    (boolean
     (or (= address "::1") (= address "::")
         (str/starts-with? address "fe80")
         (str/starts-with? address "fc")
         (str/starts-with? address "fd")
         (when-let [match (re-find #"::ffff:(\d+\.\d+\.\d+\.\d+)$" address)]
           (private-ipv4? (second match)))))))

(defn private-ip?
  "Return whether an IP literal names a private range."
  [address]
  (if (str/includes? address ":")
    (private-ipv6? address)
    (private-ipv4? address)))

(defn host-policy-decision
  "Decide target admission from policy, hostname, and DNS answers."
  [policy hostname addresses]
  (let [hostname (-> hostname str/lower-case
                     (str/replace #"^\[" "")
                     (str/replace #"\]$" ""))
        mode (::web/policy policy)
        allowed-domains (::web/allowed-domains policy)]
    (case mode
      :allowlist
      (when-not (domain-allowed? hostname allowed-domains)
        (str "host " hostname " is not in the web allowlist (policy :allowlist)"))
      :open
      (when (empty? addresses) ::dns-fail)
      (cond
        (contains? blocked-hostnames hostname)
        (str "blocked host name: " hostname)
        (empty? addresses) ::dns-fail
        (some private-ip? addresses)
        (str "host " hostname " resolves to a private/loopback address")
        :else nil))))

(defn- support-snippets [supports]
  (reduce
   (fn [acc support]
     (let [text (get-in support [:segment :text])]
       (if (str/blank? text)
         acc
         (reduce (fn [result index]
                   (update result index (fnil conj []) text))
                 acc
                 (:groundingChunkIndices support)))))
   {}
   supports))

(defn parse-grounding
  "Interpret a Gemini grounding response into portable result rows."
  [body max-results]
  (let [candidate (get-in body [:candidates 0])
        metadata (:groundingMetadata candidate)
        answer (->> (get-in candidate [:content :parts]) (keep :text) (apply str))
        queries (vec (:webSearchQueries metadata))
        chunks (vec (:groundingChunks metadata))
        snippets (support-snippets (:groundingSupports metadata))
        rows (->> chunks
                  (map-indexed
                   (fn [index chunk]
                     (let [url (get-in chunk [:web :uri])
                           title (get-in chunk [:web :title])
                           snippet (some->> (get snippets index)
                                            distinct
                                            (str/join " "))]
                       (when-not (str/blank? url)
                         (cond-> {::web/url url ::web/rank index}
                           (not (str/blank? title)) (assoc ::web/title title)
                           (not (str/blank? snippet)) (assoc ::web/snippet snippet))))))
                  (remove nil?)
                  vec)]
    (cond-> {::web/results (vec (take max-results rows))
             ::web/result-count (count rows)
             ::web/queries queries}
      (not (str/blank? answer)) (assoc ::web/answer answer))))

(defn parse-serper
  "Interpret a Serper response into portable result rows."
  [body max-results]
  (let [rows (->> (:organic body)
                  (map-indexed
                   (fn [index result]
                     (let [url (:link result)
                           title (:title result)
                           snippet (:snippet result)
                           position (:position result)
                           rank (if (number? position)
                                  (max 0 (dec position))
                                  index)]
                       (when-not (str/blank? url)
                         (cond-> {::web/url url ::web/rank rank}
                           (not (str/blank? title)) (assoc ::web/title title)
                           (not (str/blank? snippet)) (assoc ::web/snippet snippet))))))
                  (remove nil?)
                  vec)]
    {::web/results (vec (take max-results rows))
     ::web/result-count (count rows)}))

(defn retry-decision
  "Return the web retry decision for one effect and failure."
  [{:seon.capability/keys [effect]}]
  ;; Web performs no automatic retries today. External POSTs and projection
  ;; publication must never be repeated by generic recovery.
  {:seon.agent.web/retry? false
   :seon.capability/effect effect})
