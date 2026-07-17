(ns seon.agent.web
  "Fetch a web page — URL in, extracted markdown + a capped preview out.

   The lightweight, browserless read of the open web (the `curl` /
   WebFetch class): `fetch` GETs a URL, extracts the main content to
   markdown, stores the FULL text in the content-addressed blob store, and
   hands back a small projection — url, final-url, status, title,
   extractor, total TOKENS, blob-hash — plus a token-capped preview and a
   capped link list. Page through the whole document with `my.blob/text`
   on the returned `:seon.agent.web/blob-hash`; search it with
   `seon.agent.search/grep` over the blob — this function adds NO paging or
   search mechanism of its own.

   ## The honest limitation

   Fetch-only cannot render JavaScript: an SPA shell extracts to
   near-nothing and the response says so (`:seon.agent.web/hint`). A
   stateful browser tier is a separate, later tool.

   ## Security model

   **Default-deny + a host-owned reachability policy.** Two host-owned
   knobs, distinct concerns: the `SEON_WEB` env var gates WHETHER web fetch
   is available at all (default-deny); the cluster CONFIG
   (`config/system.edn`'s `:seon.config/web`) shapes which TARGETS are
   reachable via `:seon.agent.web/policy` — `:open` (anything),
   `:public-only` (block loopback/RFC-1918/link-local/ULA on every redirect
   hop — the SSRF-safe fallback), or `:allowlist` (only
   `:seon.agent.web/allowed-domains`). The agent READS its policy via
   [[grants]] but nothing inside the pod can widen it. Soft boundaries
   against LLM accidents, not security boundaries.

   ## Output discipline

   Full extracted markdown → `my.blob` (content-addressed). The immediate
   result carries the projection + a preview capped at
   `:seon.agent.web/max-preview-tokens` (default 2000) with HONEST totals
   (`:seon.agent.web/total-tokens`, `:seon.agent.web/preview-tokens`,
   `:seon.agent.web/truncated?`) — a partial read never looks complete.
   All sizes are TOKENS via seon.ai.tokens/estimate, never chars.

   ## Worked examples

     (seon.agent.web/grants {}) ; the SEON_WEB grant + reachability policy
     (await (seon.agent.web/fetch {:seon.agent.web/url \"https://example.com\"}))
     ; ⟹ «map: ::ok? true, ::status 200, ::title \"Example Domain\", ::extractor :readability, ::total-tokens 84, ::preview \"# …\", ::blob-hash \"9f86d0…\", …»
     (my.blob/text {:my.blob/hash \"9f86d0…\"})   ; page the FULL document

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

;; grant + policy.
(schema/register! ::enabled?        :boolean)
;; The web-access policy modes (the AUTHORITATIVE enum — seon.config's manifest
;; references this keyword; internal/policy resolves to it):
;;   :open        — no restriction (public AND private/loopback reachable)
;;   :public-only — refuse internal targets (loopback/RFC-1918/link-local/ULA)
;;   :allowlist   — reachable ONLY if the host matches ::allowed-domains
(schema/register! ::policy          [:enum :open :public-only :allowlist])
(schema/register! ::allowed-domains [:vector :string])

(schema/register! ::fetch-request
  [:map
   [::url                ::url]
   [:seon.config/configuration {:optional true} :seon.config/singleton]
   [::timeout-ms         {:optional true} ::timeout-ms]
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

;; ── search ─────────────────────────────────────────────────────────────
;; Backend-agnostic rows + an optional grounded ::answer, so :gemini-grounding
;; (ships) and :serper (documented second backend) sit behind ONE schema.
(schema/register! ::query        [:string {:min 1}])
(schema/register! ::max-results  [:int {:min 1 :max 20}])   ; hard cap — safety constraint
(schema/register! ::snippet      :string)
(schema/register! ::rank         [:int {:min 0}])           ; 0-based row position
(schema/register! ::result
  [:map
   [::url     ::url]
   [::title   {:optional true} ::title]
   [::snippet {:optional true} ::snippet]
   [::rank    ::rank]])
(schema/register! ::results       [:vector ::result])
;; ::backend = which provider produced a RESPONSE; ::search-backend = the
;; configured/effective backend surfaced by grants (:none when no key).
(schema/register! ::backend        [:enum :gemini-grounding :serper])
(schema/register! ::search-backend [:enum :gemini-grounding :serper :none])
(schema/register! ::search-model   :string)
(schema/register! ::answer         :string)             ; grounded synthesis (gemini)
(schema/register! ::answer-tokens  [:int {:min 0}])     ; tokens/estimate — never chars
(schema/register! ::queries        [:vector ::query])   ; webSearchQueries executed
(schema/register! ::result-count   [:int {:min 0}])     ; honest pre-cap total

(schema/register! ::search-request
  [:map
   [::query       ::query]
   [:seon.config/configuration {:optional true} :seon.config/singleton]
   [::max-results {:optional true} ::max-results]
   [::timeout-ms  {:optional true} ::timeout-ms]])

(schema/register! ::search-response
  [:or
   [:map
    [::ok?           [:= true]]
    [::query         ::query]
    [::backend       ::backend]
    [::results       ::results]
    [::result-count  ::result-count]          ; honest total, pre-cap
    [::answer        {:optional true} ::answer]
    [::answer-tokens {:optional true} ::answer-tokens]
    [::queries       {:optional true} ::queries]
    [::hint          {:optional true} ::hint]]
   ;; COULD-NOT-SEARCH — the shared :seon.error/* shape, matching fetch.
   [:map
    [::ok?               [:= false]]
    [::query             ::query]
    [:seon.error/message :string]
    [:seon.error/data    {:optional true} :map]]])

(schema/register! ::grants-response
  [:map
   [::enabled?        ::enabled?]
   [::policy          ::policy]
   [::allowed-domains ::allowed-domains]
   [::search-backend  ::search-backend]])

(schema/register! ::grants-request
  [:map {:closed true}
   [:seon.config/configuration {:optional true} :seon.config/singleton]])

;; ============================================================
;; Grant + policy — inspect what web access I have (read-only; the policy
;; is host-owned config the agent CANNOT widen at runtime).
;; ============================================================

(defn ^:seon.fn/agent-facing? grants
  "What web access do I have? The SEON_WEB grant, reachability, search.

   Returns the live truth every function enforces: `:seon.agent.web/enabled?`
   (SEON_WEB granted at all), `:seon.agent.web/policy` (the host-owned
   config mode — `:open` = anything, `:public-only` = block internal/
   loopback (the SSRF-safe default), `:allowlist` = only listed domains),
   `:seon.agent.web/allowed-domains` (the hosts reachable under
   `:allowlist`), and `:seon.agent.web/search-backend` (the EFFECTIVE
   `search` backend — the configured provider, or `:none` when its API key
   is absent from the env so no search can run). The policy + backend are
   cluster CONFIG (`config/system.edn`'s `:seon.config/web`); nothing inside
   the pod can loosen them."
  {:malli/schema [:=> [:cat ::grants-request] ::grants-response]}
  [{configuration :seon.config/configuration}]
  (let [p (int/policy configuration)
        {::keys [search-backend]} (int/search-config configuration)
        has-key? (case search-backend
                   :gemini-grounding (some? (int/gemini-key))
                   :serper           (some? (int/serper-key))
                   false)]
    {::enabled?        (int/granted?)
     ::policy          (::policy p)
     ::allowed-domains (::allowed-domains p)
     ::search-backend  (if has-key? search-backend :none)}))

;; ============================================================
;; fetch — the one ^:async function.
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

(defn ^{:async true :seon.fn/agent-facing? true} fetch
  "Fetch a web page as markdown: a preview now, the full text as a blob.

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
   :seon.agent.web/max-preview-tokens (2000),
   :seon.agent.web/max-age-ms (0 = always refetch; >0 returns a
   young-enough prior fetch from the DB, :seon.agent.web/cached? true).

   Worked example:

     (await (seon.agent.web/fetch {:seon.agent.web/url \"https://example.com\"}))
     ; ⟹ «map: ::ok? true, ::status 200, ::extractor :readability, ::total-tokens 84, ::blob-hash \"9f86d0…\", ::preview \"# …\"»"
  {:malli/schema [:=> [:cat ::fetch-request] ::fetch-response]}
  [{::keys [url timeout-ms max-preview-tokens max-age-ms]
    configuration :seon.config/configuration
    :or {timeout-ms         int/default-timeout-ms
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
      (let [policy (int/policy configuration)]
        (if-let [cached (and (pos? max-age-ms) (int/fresh-projection url max-age-ms))]
          (projection->response cached max-preview-tokens)
          (let [res (await (int/transport policy url timeout-ms
                                          int/default-max-bytes
                                          int/default-max-redirects))]
          (cond
            (not (::ok? res))
            res

            (::binary? res)
            (int/err url (str "refusing binary content (" (::content-type res)
                              ") — this function extracts text; a blob-tier binary "
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
                                                        "needed for JS-built content.")))))))))))
    (catch :default e
      (int/err url (str "unexpected error in seon.agent.web/fetch: "
                        (or (some-> e .-message) (str e)))))))

;; ============================================================
;; search — the one ^:async grounded-search function.
;; ============================================================

(def ^:private redirect-hint
  "Standing note on the grounded-URL shape — the URLs are Google
   grounding-redirect URIs (fetchable NOW via seon.agent.web/fetch, but
   ephemeral ~30 days); fetch's :seon.agent.web/final-url recovers the
   canonical page."
  (str "the ::url values are Google grounding-redirect URIs — fetchable now "
       "with (seon.agent.web/fetch), but ephemeral (~30 days); fetch's "
       ":seon.agent.web/final-url recovers the canonical page."))

(defn- empty-results-hint
  "Truthful hint for an ok? true search that grounded NOTHING — ::results is
   empty, so there are NO urls to fetch (never advertise the redirect-hint
   here). The backend commonly declines to search conceptual/how-to queries
   and answers from its own knowledge; occasionally the answer is filtered."
  [answer]
  (str "no web sources were returned — the grounded backend attached no "
       "results for this query"
       (if (str/blank? answer)
         " and produced no ::answer (the response may have been filtered — "
         "; the ::answer is the model's direct (UNGROUNDED) synthesis — ")
       "there are NO ::url values to fetch. Rephrase toward a concrete "
       "fact-lookup query (or retry) if you need citable web sources."))

(defn ^{:async true :seon.fn/agent-facing? true} search
  "Search the web; ranked result rows plus a grounded answer.

   The request map's keys live in THIS ns: :seon.agent.web/query (required,
   non-blank), :seon.agent.web/max-results (default 10, capped 20),
   :seon.agent.web/timeout-ms (default 30000).

   `^:async` — resolves to a :seon.agent.web/search-response, NEVER rejects
   (errors are values). ok? true carries backend-agnostic :seon.agent.web/
   results — each row a {::url ::title ::snippet ::rank} (rank 0-based) —
   the HONEST pre-cap :seon.agent.web/result-count, the executed
   :seon.agent.web/queries, and (grounded backend) a synthesized
   :seon.agent.web/answer with :seon.agent.web/answer-tokens. ok? false =
   COULD NOT SEARCH AT ALL (SEON_WEB default-deny — search rides the SAME
   grant as fetch; no backend API key in the env; HTTP/timeout/quota
   failure) — a guiding :seon.error/message, never a throw.

   Backend is host-owned config (config/system.edn's :seon.config/web); the
   first wire is Gemini \"Grounding with Google Search\". The ::url values
   are grounding-redirect URIs — the intended loop is search → pick a ::url
   → (seon.agent.web/fetch {:seon.agent.web/url …}) (full page → blob) →
   (my.blob/text …) / (seon.agent.search/grep …). Inspect the live backend
   with (seon.agent.web/grants {}).

   NOT every ok? true search grounds: conceptual/how-to queries often return
   an ::answer the model wrote WITHOUT searching, so ::results is EMPTY (no
   urls to fetch) — the ::hint says so honestly. For citable web sources
   phrase a concrete fact-lookup query; ::result-count is the honest total.

   Worked example:

     (await (seon.agent.web/search {:seon.agent.web/query \"current stable Clojure version\"}))
     ; ⟹ «map: ::ok? true, ::backend :gemini-grounding, ::results [«::url, ::title, ::snippet, ::rank» …], ::result-count 2, ::answer \"…\", ::answer-tokens 18, ::queries [\"…\"], …»
     ; then fetch a row's ::url to page the real page into a blob."
  {:malli/schema [:=> [:cat ::search-request] ::search-response]}
  [{::keys [query max-results timeout-ms]
    configuration :seon.config/configuration
    :or {max-results int/default-search-results
         timeout-ms  int/default-timeout-ms}}]
  (try
    (cond
      (not= :node (platform/host))
      (int/search-err query "seon.agent.web/search requires the :node host (no :wasi fetch yet).")

      (not (int/granted?))
      (int/search-ungranted query)

      (or (nil? query) (str/blank? query))
      (int/search-err query ":seon.agent.web/query is required and must be non-blank.")

      :else
      (let [{backend ::search-backend model ::search-model}
            (int/search-config configuration)
            n (max 1 (min max-results int/max-search-results))]
        (case backend
          :gemini-grounding
          (let [key (int/gemini-key)]
            (if (or (nil? key) (str/blank? key))
              (int/search-err query
                              (str "no search backend key — GEMINI_API_KEY is unset "
                                   "in the pod's env; the :gemini-grounding backend "
                                   "cannot run. Inspect with (seon.agent.web/grants {})."))
              (let [res (await (int/gemini-request query model key timeout-ms))]
                (if-not (::ok? res)
                  res
                  (let [{::keys [results result-count queries answer]}
                        (int/parse-grounding (::body res) n)
                        now  (js/Date.)
                        ;; HONEST hint: the redirect-hint promises fetchable
                        ;; ::url values — only true when rows exist. An empty
                        ;; result set (grounding declined — common for
                        ;; conceptual queries) must NOT advertise urls it
                        ;; doesn't have.
                        hint (if (seq results)
                               redirect-hint
                               (empty-results-hint answer))]
                    ;; Best-effort projection — grep-graph/forensics can see what
                    ;; was searched; a rejected tx must not fail the search.
                    (await (db/transact!
                             {:seon.db/tx-data
                              [(cond-> {::query        query
                                        ::backend      :gemini-grounding
                                        ::result-count result-count
                                        ::fetched-at   now}
                                 (seq queries) (assoc ::queries queries))]}))
                    (cond-> {::ok?           true
                             ::query         query
                             ::backend       :gemini-grounding
                             ::results       results
                             ::result-count  result-count
                             ::hint          hint}
                      (seq queries)            (assoc ::queries queries)
                      (not (str/blank? answer))
                      (-> (assoc ::answer answer)
                          (assoc ::answer-tokens (tokens/estimate answer)))))))))

          :serper
          (let [key (int/serper-key)]
            (if (or (nil? key) (str/blank? key))
              (int/search-err query
                              (str "no search backend key — SERPER_API_KEY is unset "
                                   "in the pod's env; the :serper backend cannot run. "
                                   "Inspect with (seon.agent.web/grants {})."))
              (let [res (await (int/serper-request query n timeout-ms))]
                (if-not (::ok? res)
                  res
                  (let [{::keys [results result-count]} (int/parse-serper (::body res) n)
                        now  (js/Date.)
                        ;; HONEST hint: redirect-hint only when rows exist;
                        ;; serper returns real page urls (not redirect URIs) —
                        ;; an empty set just means no SERP matches.
                        hint (if (seq results)
                               "the ::url values are the real page urls the SERP returned — fetch a row's ::url with (seon.agent.web/fetch)."
                               "no web results matched this query — rephrase toward a concrete fact-lookup query, or retry.")]
                    ;; Best-effort projection — a rejected tx must not fail search.
                    (await (db/transact!
                             {:seon.db/tx-data
                              [{::query        query
                                ::backend      :serper
                                ::result-count result-count
                                ::fetched-at   now}]}))
                    {::ok?          true
                     ::query        query
                     ::backend      :serper
                     ::results      results
                     ::result-count result-count
                     ::hint         hint})))))

          ;; A configured-but-unwired backend — legible refusal.
          (int/search-err query
                          (str "search backend " (pr-str backend) " is not wired yet "
                               "(only :gemini-grounding ships) — set "
                               ":seon.agent.web/search-backend in config/system.edn.")))))
    (catch :default e
      (int/search-err query (str "unexpected error in seon.agent.web/search: "
                                 (or (some-> e .-message) (str e)))))))
