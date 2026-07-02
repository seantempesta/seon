(ns seon.agent.web.internal
  "Plumbing behind `seon.agent.web` — the SSRF/private-range guard, the
   redirect-following capped-body transport (built-in `fetch`/undici, zero
   new transport deps), the readability + regex HTML→markdown extraction
   pipeline (ported from openclaw's `web-fetch-utils.ts`), the `SEON_WEB`
   grant read, and the errors-as-values envelope helpers.

   This namespace is INTERNAL: the `*.internal` ns name IS the render
   filter — agents call the public verbs in `seon.agent.web`, never these.
   All map keys stay in the `:seon.agent.web/*` namespace (via
   `:as-alias`): the keyword namespace tracks the OWNING DATA namespace
   (`seon.agent.web`), not the file the code lives in — the same rule
   fs/shell follow (the root 'keyword namespaces = real code namespaces').

   ## Testing seams

   [[!fetch-impl]] and [[!lookup-impl]] are override atoms so a hermetic
   test injects a fake transport / DNS resolver and never touches the
   network. The live pod leaves both nil (real `js/fetch` + node DNS)."
  (:require
    ["node:dns/promises" :as dns]
    ["node:net" :as net]
    [clojure.string :as str]
    [goog.object :as gobj]
    [my.blob :as blob]
    [seon.agent.web :as-alias web]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.platform :as platform]))

;; ============================================================
;; Hard defaults + caps.
;; ============================================================

(def default-timeout-ms      "AbortController deadline when unspecified." 30000)
(def default-max-bytes       "Streamed-body byte cap (openclaw's number)." 2000000)
(def default-max-preview-tokens "Preview cap — deliberately small; the full doc is one blob/text away." 2000)
(def default-max-redirects   "Redirect-hop cap; every hop re-passes the SSRF guard." 5)
(def default-links-cap       "Max link rows carried in the response." 25)

(def max-html-chars     "Skip the DOM parse above this HTML size (fall back to regex)." 1000000)
(def max-nesting-depth  "Skip the DOM parse above this estimated tag nesting." 3000)

;; ============================================================
;; The SEON_WEB grant — host-owned, read live, default-deny.
;; ============================================================

(defn granted?
  "True when the HOST granted web access via SEON_WEB (any value but
   \"0\"). Read live from the env — the host owns the knob; nothing inside
   the pod can flip it."
  []
  (let [v (platform/env-val "SEON_WEB")]
    (boolean (and v (not= "0" v)))))

(defn locked?
  "True when the HOST locked the grant via SEON_WEB_LOCK (any value but
   \"0\")."
  []
  (let [v (platform/env-val "SEON_WEB_LOCK")]
    (boolean (and v (not= "0" v)))))

;; The configurable domain allowlist. Empty = all domains allowed (when
;; SEON_WEB is granted). Seeded from SEON_WEB_DOMAINS (comma-separated).
(defn- env-domains []
  (when-let [v (platform/env-val "SEON_WEB_DOMAINS")]
    (->> (str/split v #",") (map str/trim) (remove str/blank?) vec)))

(defonce !config (atom {::web/allowed-domains (or (env-domains) [])}))

(defn allowed-domains [] (vec (::web/allowed-domains @!config)))

(defn domain-allowed?
  "True iff `hostname` is permitted by the allowlist — always true when
   the allowlist is empty (allow-all-when-granted). A domain matches its
   exact host or any subdomain of it."
  [hostname]
  (let [allow (allowed-domains)]
    (or (empty? allow)
        (boolean (some (fn [d]
                         (let [d (str/lower-case d)]
                           (or (= hostname d)
                               (str/ends-with? hostname (str "." d)))))
                       allow)))))

;; ============================================================
;; Error / denial envelopes — errors are values, never a throw.
;; ============================================================

(defn err
  "ok?-false envelope on the shared :seon.error/* shape (the shell/fs
   template). `data` (optional) carries structured detail."
  ([url msg] (err url msg nil))
  ([url msg data]
   (cond-> {::web/ok?           false
            ::web/url           url
            :seon.error/message msg}
     (seq data) (assoc :seon.error/data data))))

(defn ungranted
  "The guiding default-deny envelope naming the host grant."
  [url]
  (err url (str "web access is not granted (default-deny) — the host must "
                "set the SEON_WEB env var (any value but \"0\") before the "
                "pod starts; nothing inside the pod can grant it. Inspect "
                "with (seon.agent.web/grants).")))

;; ============================================================
;; SSRF guard — block private / loopback / link-local targets. Always on,
;; un-configurable; the soft boundary against LLM-emitted accidents
;; (DNS-rebinding-grade evasion is out of scope — process isolation is the
;; real boundary). Checked on the resolved address of EVERY redirect hop.
;; ============================================================

(def blocked-hostnames #{"localhost" "localhost.localdomain" "metadata.google.internal"})

(defn ip-literal? [h] (not= 0 (.isIP net h)))

(defn private-ipv4?
  "True iff `s` is a loopback / RFC-1918 / link-local / CGNAT / 0.0.0.0
   IPv4 literal. Fails CLOSED (true) on a malformed literal."
  [s]
  (let [parts (str/split s #"\.")]
    (if (not= 4 (count parts))
      true
      (let [[a b] (map #(js/parseInt % 10) parts)]
        (boolean
          (or (= a 0) (= a 10) (= a 127)
              (and (= a 169) (= b 254))
              (and (= a 172) (<= 16 b 31))
              (and (= a 192) (= b 168))
              (and (= a 100) (<= 64 b 127))))))))

(defn private-ipv6?
  "True iff `s` is loopback (::1), unspecified (::), link-local
   (fe80::/10), ULA (fc00::/7), or an IPv4-mapped private address."
  [s]
  (let [x (-> s str/lower-case (str/replace #"^\[" "") (str/replace #"\]$" ""))]
    (boolean
      (or (= x "::1") (= x "::")
          (str/starts-with? x "fe80")
          (str/starts-with? x "fc")
          (str/starts-with? x "fd")
          (when-let [m (re-find #"::ffff:(\d+\.\d+\.\d+\.\d+)$" x)]
            (private-ipv4? (second m)))))))

(defn private-ip? [addr]
  (if (str/includes? addr ":") (private-ipv6? addr) (private-ipv4? addr)))

;; Override the DNS resolver (a hostname->#js[{:address ..}] fn returning a
;; Promise) in a hermetic test; nil = real node DNS.
(defonce !lookup-impl (atom nil))

(defn ^:async resolve-addrs
  "Resolve `hostname` to a vector of address strings (all A/AAAA records),
   or nil on failure. Uses [[!lookup-impl]] when set (tests)."
  [hostname]
  (try
    (let [res (if-let [f @!lookup-impl]
                (await (f hostname))
                (await (.lookup dns hostname #js {:all true})))]
      (vec (map #(.-address ^js %) (array-seq res))))
    (catch :default _ nil)))

(defn ^:async host-block-reason
  "A reason string if the parsed URL's host is a blocked (private) target;
   `:seon.agent.web.internal/dns-fail` when the host cannot be resolved;
   nil when the host is a public, reachable address."
  [^js parsed]
  (let [hostname (-> (.-hostname parsed) str/lower-case
                     (str/replace #"^\[" "") (str/replace #"\]$" ""))]
    (cond
      (contains? blocked-hostnames hostname)
      (str "blocked host name: " hostname)

      (ip-literal? hostname)
      (when (private-ip? hostname)
        (str "private/loopback IP address: " hostname))

      :else
      (let [addrs (await (resolve-addrs hostname))]
        (cond
          (empty? addrs)          ::dns-fail
          (some private-ip? addrs) (str "host " hostname
                                         " resolves to a private/loopback address")
          :else                    nil)))))

;; ============================================================
;; HTML → markdown — the regex converter ported from openclaw's
;; web-fetch-utils.ts (readability's markdown pass + the standalone
;; fallback). No turndown, no cheerio.
;; ============================================================

(defn- decode-entities [s]
  (-> s
      (str/replace #"(?i)&nbsp;" " ")
      (str/replace #"(?i)&amp;" "&")
      (str/replace #"(?i)&quot;" "\"")
      (str/replace #"(?i)&#39;" "'")
      (str/replace #"(?i)&lt;" "<")
      (str/replace #"(?i)&gt;" ">")
      (str/replace #"(?i)&#x([0-9a-f]+);" (fn [[_ hex]] (js/String.fromCharCode (js/parseInt hex 16))))
      (str/replace #"&#(\d+);" (fn [[_ d]] (js/String.fromCharCode (js/parseInt d 10))))))

(defn- strip-tags [s]
  (decode-entities (str/replace s #"<[^>]+>" "")))

(defn- normalize-ws [s]
  (-> s
      (str/replace #"\r" "")
      (str/replace #"[ \t]+\n" "\n")
      (str/replace #"\n{3,}" "\n\n")
      (str/replace #"[ \t]{2,}" " ")
      str/trim))

(defn html->markdown
  "Regex HTML→markdown: strip script/style, links→[label](href),
   headings→#, <li>→- , block-closers→newline, then decode + normalize.
   Returns {:text md :title t}."
  [html]
  (let [tm    (re-find #"(?i)<title[^>]*>([\s\S]*?)</title>" html)
        title (when tm (normalize-ws (strip-tags (second tm))))
        text  (-> html
                  (str/replace #"(?i)<script[\s\S]*?</script>" "")
                  (str/replace #"(?i)<style[\s\S]*?</style>" "")
                  (str/replace #"(?i)<noscript[\s\S]*?</noscript>" ""))
        text  (str/replace text #"(?i)<a\s+[^>]*href=[\"']([^\"']+)[\"'][^>]*>([\s\S]*?)</a>"
                           (fn [[_ href body]]
                             (let [label (normalize-ws (strip-tags body))]
                               (if (str/blank? label) href (str "[" label "](" href ")")))))
        text  (str/replace text #"(?i)<h([1-6])[^>]*>([\s\S]*?)</h\1>"
                           (fn [[_ level body]]
                             (let [n     (max 1 (min 6 (js/parseInt level 10)))
                                   pfx   (apply str (repeat n "#"))
                                   label (normalize-ws (strip-tags body))]
                               (str "\n" pfx " " label "\n"))))
        text  (str/replace text #"(?i)<li[^>]*>([\s\S]*?)</li>"
                           (fn [[_ body]]
                             (let [label (normalize-ws (strip-tags body))]
                               (if (str/blank? label) "" (str "\n- " label)))))
        text  (-> text
                  (str/replace #"(?i)<(?:br|hr)\s*/?>" "\n")
                  (str/replace #"(?i)</(?:p|div|section|article|header|footer|table|tr|ul|ol)>" "\n"))
        text  (normalize-ws (strip-tags text))]
    {:text text :title title}))

(def ^:private void-tags
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta"
    "param" "source" "track" "wbr"})

(defn exceeds-nesting?
  "Cheap heuristic (a char scan, NOT an HTML parser) that flags
   attacker-controlled deep nesting so the DOM parse is skipped — a DOM
   blowup blanks the single-threaded pod. Ported from openclaw."
  [html max-depth]
  (let [len (count html)]
    (loop [i 0 depth 0]
      (if (>= i len)
        false
        (if (not= 60 (.charCodeAt html i))     ; not '<'
          (recur (inc i) depth)
          (let [nxt (.charCodeAt html (inc i))]
            (if (or (= nxt 33) (= nxt 63))     ; <! ...> or <? ...>
              (recur (inc i) depth)
              (let [closing? (= 47 (.charCodeAt html (inc i)))
                    j0       (if closing? (+ i 2) (+ i 1))
                    j1       (loop [j j0] (if (and (< j len) (<= (.charCodeAt html j) 32)) (recur (inc j)) j))
                    jend     (loop [j j1]
                               (if (< j len)
                                 (let [c (.charCodeAt html j)]
                                   (if (or (<= 65 c 90) (<= 97 c 122) (<= 48 c 57) (= c 58) (= c 45))
                                     (recur (inc j)) j))
                                 j))
                    tag      (str/lower-case (subs html j1 jend))]
                (cond
                  (= "" tag)               (recur (inc i) depth)
                  closing?                 (recur (inc i) (max 0 (dec depth)))
                  (contains? void-tags tag) (recur (inc i) depth)
                  :else
                  (let [self? (loop [k jend]
                                (if (and (< k len) (< k (+ jend 200)))
                                  (if (= 62 (.charCodeAt html k))     ; '>'
                                    (= 47 (.charCodeAt html (dec k)))
                                    (recur (inc k)))
                                  false))]
                    (if self?
                      (recur (inc i) depth)
                      (let [d (inc depth)]
                        (if (> d max-depth) true (recur (inc i) d))))))))))))))

;; ============================================================
;; Readability extraction — lazily require the two MIT npm deps. If they
;; are missing, extraction degrades to the regex fallback (extractor :raw)
;; rather than throwing — the pod is never wedged by an absent dep.
;; ============================================================

(defonce ^:private !rdeps (atom nil))

(defn- readability-deps []
  (or @!rdeps
      (try
        (let [r (js/require "@mozilla/readability")
              l (js/require "linkedom")]
          (reset! !rdeps #js {:Readability (.-Readability r) :parseHTML (.-parseHTML l)}))
        (catch :default _ nil))))

(defn extract
  "Extract main content from `html` as markdown, honest about how.
   Returns {:text md :title t :extractor :readability|:raw}. Guards
   (size + nesting) and any readability failure fall back to the regex
   converter (extractor :raw) — never a throw."
  [html final-url]
  (let [fallback (fn [] (let [{:keys [text title]} (html->markdown html)]
                          {:text text :title title :extractor :raw}))]
    (if (or (> (count html) max-html-chars)
            (exceeds-nesting? html max-nesting-depth))
      (fallback)
      (if-let [deps (readability-deps)]
        (try
          (let [parse-html   (.-parseHTML ^js deps)
                readability  (.-Readability ^js deps)
                ^js parsed-h (parse-html html)
                ^js doc      (.-document parsed-h)
                _            (try (gobj/set doc "baseURI" final-url) (catch :default _ nil))
                ^js reader   (new readability doc #js {:charThreshold 0})
                ^js parsed   (.parse reader)]
            (if (and parsed (.-content parsed))
              (let [{:keys [text title]} (html->markdown (.-content parsed))]
                {:text      text
                 :title     (or (not-empty (.-title parsed)) title)
                 :extractor :readability})
              (fallback)))
          (catch :default _ (fallback)))
        (fallback)))))

(defn extract-links
  "Pull `[label](href)` links out of markdown, absolutize each href
   against `base-url`, keep only http(s), dedupe, and cap at `cap`."
  [markdown base-url cap]
  (->> (re-seq #"\[([^\]]+)\]\(([^)]+)\)" markdown)
       (map (fn [[_ label href]]
              (let [abs (try (.toString (js/URL. href base-url)) (catch :default _ href))]
                {::web/href abs ::web/label label})))
       (filter (fn [{h ::web/href}]
                 (or (str/starts-with? h "http://") (str/starts-with? h "https://"))))
       (distinct)
       (take cap)
       vec))

;; ============================================================
;; Transport — the redirect-following, SSRF-re-checked, capped-body
;; fetch. Returns a clj map, never throws; the public verb decides the
;; output discipline on top of it.
;; ============================================================

;; Override `js/fetch` (a (url, init-#js) -> Promise<Response> fn) in a
;; hermetic test; nil = real global fetch (undici).
(defonce !fetch-impl (atom nil))

(defn- fetch-fn [] (or @!fetch-impl js/fetch))

(def ^:private redirect-statuses #{301 302 303 307 308})

(defn- text-ish
  "Classify a content-type header into an extraction lane keyword."
  [ct]
  (let [ct (str/lower-case (or ct ""))]
    (cond
      (or (str/includes? ct "text/html") (str/includes? ct "application/xhtml")) :html
      (str/includes? ct "text/markdown")                                          :markdown
      (or (str/includes? ct "application/json") (str/includes? ct "+json"))       :json
      (str/starts-with? ct "text/")                                               :text
      :else                                                                       :binary)))

(defn ^:async read-body-capped
  "Read `response`'s body as text, stopping at `max-bytes` (streamed —
   never buffers an unbounded body). Returns #js {:text s :truncated b}."
  [^js response max-bytes]
  (let [body (.-body response)]
    (if (nil? body)
      #js {:text "" :truncated false}
      (let [reader (.getReader body)
            dec    (js/TextDecoder. "utf-8")]
        (loop [acc "" total 0]
          (let [chunk (await (.read reader))]
            (if (.-done chunk)
              #js {:text acc :truncated false}
              (let [value (.-value chunk)
                    len   (.-byteLength value)]
                (if (> (+ total len) max-bytes)
                  (let [slice (.slice value 0 (max 0 (- max-bytes total)))]
                    (.cancel reader)
                    #js {:text (str acc (.decode dec slice)) :truncated true})
                  (recur (str acc (.decode dec value #js {:stream true})) (+ total len)))))))))))

(defn ^:async transport
  "Follow redirects (capped, SSRF-re-checked per hop) to a final 2xx
   response and read its body (text-ish content only, capped). Returns a
   clj map: an ok?-false [[err]] envelope for a blocked host, DNS failure,
   timeout, bad scheme, redirect problem, or non-2xx; an ok?-true map
   {::status ::final-url ::content-type ::lane ::body ::truncated?} for
   a text-ish 2xx; the same with ::binary? true (no body) for binary."
  [url timeout-ms max-bytes max-redirects]
  (loop [current url, hops 0, visited #{}]
    (let [parsed (try (js/URL. current) (catch :default _ nil))]
      (cond
        (nil? parsed)
        (err url (str "not a valid URL: " (pr-str current)))

        (not (contains? #{"http:" "https:"} (.-protocol parsed)))
        (err url (str "only http/https URLs are supported (got "
                      (.-protocol parsed) ") — file: is seon.agent.fs's job."))

        (not (domain-allowed? (str/lower-case (.-hostname parsed))))
        (err url (str "host " (.-hostname parsed) " is not in the configured "
                      "domain allowlist — inspect it with (seon.agent.web/grants).")
             {::web/allowed-domains (allowed-domains)})

        :else
        (let [block (await (host-block-reason parsed))]
          (cond
            (= block ::dns-fail)
            (err url (str "could not resolve host " (.-hostname parsed)
                          " (DNS lookup failed)."))

            (string? block)
            (err url (str "blocked by the private-range guard: " block
                          ". Loopback/RFC-1918/link-local/ULA targets are "
                          "always refused (SSRF soft boundary).")
                 {::web/final-url current})

            :else
            (let [ctrl   (js/AbortController.)
                  tid    (js/setTimeout #(.abort ctrl) timeout-ms)
                  result (try
                           #js {:resp (await ((fetch-fn) (.toString parsed)
                                              #js {:redirect "manual"
                                                   :signal   (.-signal ctrl)
                                                   :headers  #js {"Accept"          "text/markdown, text/html;q=0.9, */*;q=0.8"
                                                                  "User-Agent"      "seon-agent/1.0"
                                                                  "Accept-Language" "en-US,en;q=0.9"}}))}
                           (catch :default e #js {:error e}))
                  _      (js/clearTimeout tid)]
              (if-let [e (.-error result)]
                (if (= "AbortError" (.-name ^js e))
                  (err url (str "timed out after " timeout-ms
                                " ms — raise :seon.agent.web/timeout-ms if the host is just slow."))
                  (err url (str "transport error: " (or (some-> ^js e .-message) (str e)))))
                (let [resp   (.-resp result)
                      status (.-status resp)]
                  (cond
                    (contains? redirect-statuses status)
                    (let [loc (some-> (.-headers resp) (.get "location"))]
                      (do (some-> (.-body resp) .cancel)
                          (cond
                            (nil? loc)
                            (err url (str "redirect (" status ") with no location header."))

                            (>= (inc hops) max-redirects)
                            (err url (str "too many redirects (cap " max-redirects ")."))

                            :else
                            (let [nxt (try (.toString (js/URL. loc (.toString parsed)))
                                           (catch :default _ nil))]
                              (cond
                                (nil? nxt)             (err url (str "unfollowable redirect location: " (pr-str loc)))
                                (contains? visited nxt) (err url "redirect loop detected.")
                                :else                   (recur nxt (inc hops) (conj visited nxt)))))))

                    (not (<= 200 status 299))
                    (err url (str "server returned HTTP " status " — the fetch reached "
                                  "the server but it did not return a success status.")
                         {::web/status status ::web/final-url current})

                    :else
                    (let [ct   (some-> (.-headers resp) (.get "content-type"))
                          lane (text-ish ct)]
                      (if (= :binary lane)
                        (do (some-> (.-body resp) .cancel)
                            {::web/ok?          true ::web/binary? true
                             ::web/status       status ::web/final-url current
                             ::web/content-type (or ct "application/octet-stream")})
                        (let [^js b (await (read-body-capped resp max-bytes))]
                          {::web/ok?          true
                           ::web/status       status
                           ::web/final-url    current
                           ::web/content-type (or ct "")
                           ::web/lane         lane
                           ::web/body         (.-text b)
                           ::web/truncated?   (.-truncated b)})))))))))))))

;; ============================================================
;; max-age cache — the fetch-projection entities ARE the cache
;; (derive-don't-store). No TTL store, no eviction.
;; ============================================================

(defn fresh-projection
  "The newest fetch-projection entity for `url` younger than `max-age-ms`,
   or nil. A DB query at call time — no cache subsystem."
  [url max-age-ms]
  (let [rows (db/query '[:find ?e ?at
                         :in $ ?u
                         :where [?e :seon.agent.web/url ?u]
                                [?e :seon.agent.web/fetched-at ?at]]
                       url)]
    (when (seq rows)
      (let [[e at] (apply max-key #(.getTime ^js (second %)) rows)]
        (when (< (- (.now js/Date) (.getTime ^js at)) max-age-ms)
          (db/entity e))))))
