(ns seon.agent.web
  "Fetch web content through a gated, browserless capability.

   The public surface retrieves allowed URLs, stores full extracted text in the
   content-addressed blob tier, and returns a token-bounded preview with honest
   metadata and links. It does not render JavaScript or create another paging
   mechanism; transport, redirects, and reachability checks are internal."
  (:refer-clojure :exclude [fetch])
  (:require
    [seon.agent.web.core :as int]
    [seon.schema :as schema]))

(def ^:dynamic *leaf* nil)

(declare grants fetch search)

(defn- bind-leaf
  "Return the public web functions closed over one JVM platform leaf."
  [platform-leaf]
  (into {}
        (map (fn [v]
               [(:name (meta v))
                (fn [& args]
                  (binding [*leaf* platform-leaf]
                    (apply @v args)))])
             [#'grants #'fetch #'search])))

(defn- leaf-fn
  [key]
  (or (get *leaf* key)
      (fn [request]
        (let [url (::url request)
              query (::query request)]
          (if query
            (int/search-err query "No web platform leaf is installed.")
            (int/err url "No web platform leaf is installed."))))))

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
;; `::allowed-domains` is registered once by `seon.config`, which loads before
;; this capability and owns the database configuration entity.

(schema/register! ::fetch-request
  [:map {:closed true}
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
  [:map {:closed true}
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

(defn ^{:seon.capability/effect :read} grants
  "What web access do I have? The SEON_WEB grant, reachability, search.

   Returns the live truth every function enforces: `:seon.agent.web/enabled?`
   (SEON_WEB granted at all), `:seon.agent.web/policy` (the host-owned
   config mode — `:open` = anything, `:public-only` = block internal/
   loopback (the SSRF-safe default), `:allowlist` = only listed domains),
   `:seon.agent.web/allowed-domains` (the hosts reachable under
   `:allowlist`), and `:seon.agent.web/search-backend` (the EFFECTIVE
   `search` backend — the configured provider, or `:none` when its API key
   is absent from the env so no search can run). The policy + backend are
   cluster CONFIG (`config/system.edn`'s `:seon.config/web`); agent code
   cannot loosen them."
  {:malli/schema [:=> [:cat ::grants-request] ::grants-response]}
  [{configuration :seon.config/configuration}]
  ((leaf-fn ::grants) {:seon.config/configuration configuration}))

;; ============================================================
;; fetch — the external JVM leaf function.
;; ============================================================

(defn ^{:async false
        :seon.capability/effect :external} fetch
  "Fetch a web page as markdown: a preview now, the full text as a blob.

   The request map's keys live in THIS ns: the URL key is
   :seon.agent.web/url (a :seon.web/url or bare :url is NOT a request
   key and fails input validation).

   Returns a :seon.agent.web/fetch-response and never throws into the agent
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

     (seon.agent.web/fetch {:seon.agent.web/url \"https://example.com\"})
     ; Returns a successful response with status, extractor, blob hash,
     ; token totals, and a bounded preview."
  {:malli/schema [:=> [:cat ::fetch-request] ::fetch-response]}
  [{::keys [url timeout-ms max-preview-tokens max-age-ms]
    configuration :seon.config/configuration
    :or {max-age-ms 0}}]
  (if-let [missing (int/missing-limit-key configuration
                                           int/fetch-limit-keys)]
    (int/fetch-config-error url missing)
    (let [timeout-ms
          (or timeout-ms
              (:seon.config.web/default-timeout-ms configuration))
          max-preview-tokens
          (or max-preview-tokens
              (:seon.config.web/default-preview-tokens configuration))]
      ((leaf-fn ::fetch)
       (cond-> {::url url
                ::timeout-ms timeout-ms
                ::max-preview-tokens max-preview-tokens
                ::max-age-ms max-age-ms}
         configuration
         (assoc :seon.config/configuration configuration))))))

;; ============================================================
;; search — the grounded-search function.
;; ============================================================

(defn ^{:async false
        :seon.capability/effect :external} search
  "Search the web; ranked result rows plus a grounded answer.

   The request map's keys live in THIS ns: :seon.agent.web/query (required,
   non-blank), :seon.agent.web/max-results (default 10, capped 20),
   :seon.agent.web/timeout-ms (default 30000).

   Returns a :seon.agent.web/search-response and never throws into the agent
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

     (seon.agent.web/search {:seon.agent.web/query \"current stable Clojure version\"})
     ; Returns ranked rows and an honest result count; fetch a row's
     ; ::url to page the real page into a blob."
  {:malli/schema [:=> [:cat ::search-request] ::search-response]}
  [{::keys [query max-results timeout-ms]
    configuration :seon.config/configuration}]
  (if-let [missing (int/missing-limit-key configuration
                                           int/search-limit-keys)]
    (int/search-config-error query missing)
    (let [timeout-ms
          (or timeout-ms
              (:seon.config.web/default-timeout-ms configuration))
          max-results
          (or max-results
              (:seon.config.web/default-search-results configuration))]
      ((leaf-fn ::search)
       (cond-> {::query query
                ::max-results max-results
                ::timeout-ms timeout-ms}
         configuration
         (assoc :seon.config/configuration configuration))))))
