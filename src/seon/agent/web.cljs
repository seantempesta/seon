(ns seon.agent.web
  "Fetch a web page — URL in, extracted markdown + a capped preview out.

   The lightweight, browserless read of the open web (the `curl` /
   WebFetch class): `fetch` GETs a URL, extracts the main content to
   markdown, stores the FULL text in the content-addressed blob store, and
   hands back a small projection — url, final-url, status, title,
   extractor, total TOKENS, blob-hash — plus a token-capped preview and a
   capped link list. Page through the whole document with `my.blob/text`
   on the returned `:seon.agent.web/blob-hash`; search it with
   `seon.agent.search/grep` over the blob — this verb adds NO paging or
   search mechanism of its own.

   ## The honest limitation

   Fetch-only cannot render JavaScript: an SPA shell extracts to
   near-nothing and the response says so (`:seon.agent.web/hint`). A
   stateful browser tier is a separate, later tool.

   ## Security model

   **Default-deny.** The whole capability is gated by the host-owned
   `SEON_WEB` env var (inspect with [[grants]]); a private-range guard
   refuses loopback / RFC-1918 / link-local / ULA targets on every
   redirect hop (the SSRF soft boundary) — on by default, never
   agent-configurable, host-releasable ONLY via the `SEON_WEB_ALLOW_PRIVATE`
   env grant (loopback-fixture deployments). An OPTIONAL domain allowlist
   rides [[configure!]]. Soft boundaries against LLM accidents, not
   security boundaries.

   ## Output discipline

   Full extracted markdown → `my.blob` (content-addressed). The immediate
   result carries the projection + a preview capped at
   `:seon.agent.web/max-preview-tokens` (default 2000) with HONEST totals
   (`:seon.agent.web/total-tokens`, `:seon.agent.web/preview-tokens`,
   `:seon.agent.web/truncated?`) — a partial read never looks complete.
   All sizes are TOKENS via seon.ai.tokens/estimate, never chars.

   ## Worked examples

     (seon.agent.web/grants)   ;; the SEON_WEB grant + domain allowlist
     (await (seon.agent.web/fetch {:seon.agent.web/url \"https://example.com\"}))
     ;; => {:seon.agent.web/ok? true :seon.agent.web/status 200
     ;;     :seon.agent.web/title \"Example Domain\"
     ;;     :seon.agent.web/extractor :readability
     ;;     :seon.agent.web/total-tokens 84 :seon.agent.web/preview \"# …\"
     ;;     :seon.agent.web/blob-hash \"9f86d0…\" …}
     (my.blob/text {:my.blob/hash \"9f86d0…\"})   ;; page the FULL document

   Plumbing (SSRF guard, transport, extraction, grant read) lives in
   [[seon.agent.web.internal]]."
  (:refer-clojure :exclude [fetch])
  (:require
    [clojure.string :as str]
    [my.blob :as blob]
    [seon.agent.web.internal :as int]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.platform :as platform]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — every key registered; shared shapes referenced, not inlined.
;; ============================================================

;; The canonical URL shape — final-url + href reference it (no dup).
(schema/register! ::url             [:string {:min 1}])
(schema/register! ::final-url       ::url)
(schema/register! ::href            ::url)

;; request dials.
(schema/register! ::timeout-ms         :int)  ; default 30000
(schema/register! ::max-bytes          :int)  ; default 2000000
(schema/register! ::max-preview-tokens :int)  ; default 2000
(schema/register! ::max-age-ms         :int)  ; default 0 = always refetch

;; response / projection scalars.
(schema/register! ::ok?            :boolean)
(schema/register! ::status         :int)
(schema/register! ::content-type   :string)
(schema/register! ::title          :string)
(schema/register! ::extractor      [:enum :readability :raw :json :text :markdown-passthrough])
(schema/register! ::preview        :string)   ; response-only, NEVER a datom
(schema/register! ::preview-tokens :int)
(schema/register! ::total-tokens   :int)      ; tokens/estimate — never chars
(schema/register! ::blob-hash      :string)   ; -> my.blob/text
(schema/register! ::fetched-at     :inst)
(schema/register! ::cached?        :boolean)
(schema/register! ::hint           :string)
(schema/register! ::truncated?     :boolean)

;; link items — shape-compatible with a future search-result row.
(schema/register! ::label :string)
(schema/register! ::link
  [:map
   [::href  ::href]
   [::label {:optional true} ::label]])
(schema/register! ::links [:vector ::link])

;; grant.
(schema/register! ::enabled?        :boolean)
(schema/register! ::locked?         :boolean)
(schema/register! ::allowed-domains [:vector :string])

(schema/register! ::fetch-request
  [:map
   [::url                ::url]
   [::timeout-ms         {:optional true} ::timeout-ms]
   [::max-bytes          {:optional true} ::max-bytes]
   [::max-preview-tokens {:optional true} ::max-preview-tokens]
   [::max-age-ms         {:optional true} ::max-age-ms]])

(schema/register! ::fetch-response
  [:or
   [:map
    [::ok?            [:= true]]
    [::url            ::url]
    [::final-url      ::final-url]
    [::status         ::status]
    [::content-type   ::content-type]
    [::title          {:optional true} ::title]
    [::extractor      ::extractor]
    [::preview        ::preview]
    [::preview-tokens ::preview-tokens]
    [::total-tokens   ::total-tokens]
    [::truncated?     ::truncated?]
    [::blob-hash      ::blob-hash]
    [::fetched-at     ::fetched-at]
    [::cached?        {:optional true} ::cached?]
    [::links          {:optional true} ::links]
    [::hint           {:optional true} ::hint]]
   ;; COULD-NOT-FETCH — the shared :seon.error/* shape (never a bare string).
   [:map
    [::ok?               [:= false]]
    [::url               ::url]
    [:seon.error/message :string]
    [:seon.error/data    {:optional true} :map]]])

(schema/register! ::private-allowed? :boolean)

(schema/register! ::grants-response
  [:map
   [::enabled?         ::enabled?]
   [::allowed-domains  ::allowed-domains]
   [::locked?          ::locked?]
   [::private-allowed? ::private-allowed?]])

(schema/register! ::configure-response
  [:map
   [::ok?             ::ok?]
   [::allowed-domains {:optional true} ::allowed-domains]
   [::locked?         {:optional true} ::locked?]
   [:seon.error/message {:optional true} :string]])

;; ============================================================
;; Grant — inspect + configure the (optional) domain allowlist.
;; ============================================================

(defn grants
  "What web access do I have? — the SEON_WEB grant + domain allowlist.

   Returns the live truth every fetch enforces: `:seon.agent.web/enabled?`
   (SEON_WEB granted), `:seon.agent.web/allowed-domains` (empty = all
   domains allowed when enabled), `:seon.agent.web/locked?` (SEON_WEB_LOCK
   — [[configure!]] is a no-op), `:seon.agent.web/private-allowed?` (the
   host-owned SEON_WEB_ALLOW_PRIVATE grant). Unless the host set that
   grant, the private-range guard refuses loopback/RFC-1918/link-local/ULA
   targets; nothing inside the pod can change it."
  {:malli/schema [:=> [:cat] ::grants-response]}
  []
  {::enabled?         (int/granted?)
   ::allowed-domains  (int/allowed-domains)
   ::locked?          (int/locked?)
   ::private-allowed? (int/private-allowed?)})

(defn configure!
  "Set the OPTIONAL domain allowlist (empty = allow all when granted).

     (configure! {:seon.agent.web/allowed-domains [\"example.com\"]})

   A no-op error when the host set SEON_WEB_LOCK. Does NOT grant access —
   SEON_WEB is host-owned; narrowing the allowlist only tightens it."
  {:malli/schema [:=> [:cat :map] ::configure-response]}
  [{::keys [allowed-domains]}]
  (if (int/locked?)
    {::ok?                false
     ::locked?            true
     :seon.error/message "the domain allowlist is locked by the host (SEON_WEB_LOCK)."}
    (do (swap! int/!config assoc ::allowed-domains (vec (or allowed-domains [])))
        {::ok? true ::allowed-domains (int/allowed-domains)})))

;; ============================================================
;; fetch — the one ^:async verb.
;; ============================================================

(defn- extract-content
  "Lane dispatch over the transport's body → {:md s :title t :extractor k
   :links v}. HTML runs the readability/regex pipeline; json/text/markdown
   pass through with honest extractor provenance."
  [lane body final-url]
  (case lane
    :html (let [{:keys [text title extractor]} (int/extract body final-url)]
            {:md text :title title :extractor extractor
             :links (int/extract-links text final-url int/default-links-cap)})
    :markdown {:md body :extractor :markdown-passthrough
               :links (int/extract-links body final-url int/default-links-cap)}
    :json {:md (try (.stringify js/JSON (.parse js/JSON body) nil 2)
                    (catch :default _ body))
           :extractor :json}
    :text {:md body :extractor :text}))

(defn- projection->response
  "Re-derive a full fetch-response from a stored projection entity + its
   blob (the max-age cache path) — the preview re-derives, never stored."
  [e max-preview-tokens]
  (let [hash    (::blob-hash e)
        content (or (:my.blob/content (blob/get {:my.blob/hash hash})) "")
        total   (::total-tokens e)
        preview (if (> total max-preview-tokens)
                  (str (subs content 0 (tokens/estimate-chars max-preview-tokens)) "…")
                  content)]
    (cond-> {::ok?            true
             ::url            (::url e)
             ::final-url      (::final-url e)
             ::status         (::status e)
             ::content-type   (::content-type e)
             ::extractor      (::extractor e)
             ::preview        preview
             ::preview-tokens (tokens/estimate preview)
             ::total-tokens   total
             ::truncated?     false
             ::blob-hash      hash
             ::fetched-at     (::fetched-at e)
             ::cached?        true}
      (::title e) (assoc ::title (::title e)))))

(defn ^:async fetch
  "Fetch the page at `:seon.agent.web/url` — markdown preview + blob.

   The request map's keys live in THIS ns: the URL key is
   :seon.agent.web/url (a :seon.web/url or bare :url is NOT a request
   key and fails input validation).

   `^:async` — resolves to a :seon.agent.web/fetch-response, NEVER rejects
   (errors are values). ok? true = the fetch RAN to a final response and
   content was extracted + blobbed — a non-2xx (404, 500, …) is a
   legitimate result: read :seon.agent.web/status yourself. The preview is
   capped at :seon.agent.web/max-preview-tokens (default 2000) with HONEST
   totals, so page the whole doc via (my.blob/text {:my.blob/hash
   blob-hash}). ok? false = COULD NOT FETCH AT ALL (default-deny/SEON_WEB,
   blocked private range on any hop, DNS failure, timeout, redirect cap,
   or binary content refused) — a guiding :seon.error/message, never a
   throw.

   Dials (all optional): :seon.agent.web/timeout-ms (30000),
   :seon.agent.web/max-bytes (2000000, streamed cap → :truncated? true),
   :seon.agent.web/max-preview-tokens (2000),
   :seon.agent.web/max-age-ms (0 = always refetch; >0 returns a
   young-enough prior fetch from the DB, :seon.agent.web/cached? true).

   Worked example:

     (await (seon.agent.web/fetch {:seon.agent.web/url \"https://example.com\"}))
     ;; => {:seon.agent.web/ok? true :seon.agent.web/status 200
     ;;     :seon.agent.web/extractor :readability :seon.agent.web/total-tokens 84
     ;;     :seon.agent.web/blob-hash \"9f86d0…\" :seon.agent.web/preview \"# …\"}"
  {:malli/schema [:=> [:cat ::fetch-request] ::fetch-response]}
  [{::keys [url timeout-ms max-bytes max-preview-tokens max-age-ms]
    :or {timeout-ms         int/default-timeout-ms
         max-bytes          int/default-max-bytes
         max-preview-tokens int/default-max-preview-tokens
         max-age-ms         0}}]
  (try
    (cond
      (not= :node (platform/host))
      (int/err url "seon.agent.web requires the :node host (no :wasi fetch yet).")

      (not (int/granted?))
      (int/ungranted url)

      (or (nil? url) (str/blank? url))
      (int/err url ":seon.agent.web/url is required and must be non-blank.")

      :else
      (if-let [cached (and (pos? max-age-ms) (int/fresh-projection url max-age-ms))]
        (projection->response cached max-preview-tokens)
        (let [res (await (int/transport url timeout-ms max-bytes int/default-max-redirects))]
          (cond
            (not (::ok? res))
            res

            (::binary? res)
            (int/err url (str "refusing binary content (" (::content-type res)
                              ") — this verb extracts text; a blob-tier binary "
                              "fetch is a later capability.")
                     {::status (::status res) ::final-url (::final-url res)
                      ::content-type (::content-type res)})

            :else
            (let [final-url (::final-url res)
                  {:keys [md title extractor links]}
                  (extract-content (::lane res) (::body res) final-url)
                  md        (or md "")
                  total     (tokens/estimate md)
                  {bok? :my.blob/ok? hash :my.blob/hash berr :my.blob/error}
                  (await (blob/put! {:my.blob/content md :my.blob/media :markdown}))]
              (if-not bok?
                (int/err url (str "extracted content but the blob store rejected it: " berr))
                (let [preview (if (> total max-preview-tokens)
                                (str (subs md 0 (tokens/estimate-chars max-preview-tokens)) "…")
                                md)
                      now     (js/Date.)
                      proj    (cond-> {::url          url
                                       ::final-url    final-url
                                       ::status       (::status res)
                                       ::content-type (::content-type res)
                                       ::extractor    extractor
                                       ::total-tokens total
                                       ::blob-hash    hash
                                       ::fetched-at   now}
                                title (assoc ::title title))]
                  ;; Best-effort projection — the blob is the durable record;
                  ;; a rejected projection tx must not fail the fetch.
                  (await (db/transact! {:seon.db/tx-data [proj]}))
                  (cond-> {::ok?            true
                           ::url            url
                           ::final-url      final-url
                           ::status         (::status res)
                           ::content-type   (::content-type res)
                           ::extractor      extractor
                           ::preview        preview
                           ::preview-tokens (tokens/estimate preview)
                           ::total-tokens   total
                           ::truncated?     (boolean (::truncated? res))
                           ::blob-hash      hash
                           ::fetched-at     now}
                    title            (assoc ::title title)
                    (seq links)      (assoc ::links links)
                    (< total 40)     (assoc ::hint (str "extracted only ~" total
                                                        " tokens — the page may be "
                                                        "script-rendered; a browser tier is "
                                                        "needed for JS-built content."))))))))))
    (catch :default e
      (int/err url (str "unexpected error in seon.agent.web/fetch: "
                        (or (some-> e .-message) (str e)))))))
