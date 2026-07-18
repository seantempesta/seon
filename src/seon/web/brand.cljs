(ns seon.web.brand
  "Downstream brand surface (fix-everything PRD C-17) — the product
   name, tagline, and theme the web UI renders are DATA, not compiled
   constants. The thesis is customize-with-data; the product name is
   the most basic customization.

   One singleton row (identity `::id` = \"brand\") carries up to three
   attrs: `::name`, `::tagline`, `::theme`. Render fns (titles, the
   cluster h1, `data-theme`) read it at render time via [[info]] —
   reactive-context: no cached atom, absent row/attr = the shipped
   seon defaults, byte-identical to the pre-C-17 output.

   ENV OWNS THE ROW. [[sync!]] (awaited directly by `seon.client` at boot)
   syncs the row to the
   `SEON_BRAND_NAME` / `SEON_BRAND_TAGLINE` / `SEON_BRAND_THEME` env
   vars: set → asserted, unset → retracted. Like the identity files'
   live read (`seon.agent.ctx/identity-files-text`), the brand is deployment
   CONFIGURATION rather than the
   store's memory — booting WITHOUT the env vars must return the
   defaults. A runtime edit survives within a pod run; the next boot
   re-syncs from env.

   `SEON_BRAND_CSS=<abs path>` is the stylesheet hook: [[css-text]]
   reads the file fresh per render and the web UI inlines it AFTER
   output.css so token overrides (--color-base-*, --color-amber-*,
   fonts) win. Missing/unreadable file = loud log line, page still
   renders (degrade, don't break)."
  (:require
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.log :as log]
    [seon.schema :as schema]))

;; --- Attribute schemas — pure data, concrete types, optional = absent.

(schema/register! ::id [:string {:seon.db/identity true}])  ; always "brand"
(schema/register! ::name [:string {:min 1}])
(schema/register! ::tagline [:string {:min 1}])
(schema/register! ::theme [:string {:min 1}])

;; The brand attrs a row (or the env) may carry — shared shape for
;; [[sync-tx-data]]'s two inputs and the row read.
(schema/register! ::row
  [:map
   [::name    {:optional true} ::name]
   [::tagline {:optional true} ::tagline]
   [::theme   {:optional true} ::theme]])

(schema/register! ::brand
  [:map {:seon.db/entity true}
   [::id       ::id]
   [::name     {:optional true} ::name]
   [::tagline  {:optional true} ::tagline]
   [::theme    {:optional true} ::theme]])

;; The EFFECTIVE brand a render fn consumes — defaults merged, so
;; every key is present.
(schema/register! ::info
  [:map
   [::name    ::name]
   [::tagline ::tagline]
   [::theme   ::theme]])

(schema/register! ::synced? :boolean)
(schema/register! ::sync-request
  [:map
   [::row {:optional true} ::row]
   [::env ::row]])
(schema/register! ::sync-response [:map [::synced? ::synced?]])

;; --- Defaults — the shipped seon brand. Absent env + absent row
;; --- renders EXACTLY this (byte-identical to pre-C-17 output).

(def defaults
  "The shipped brand: what every surface renders when no brand row and
   no SEON_BRAND_* env exist. The tagline is the mission-control
   subtitle line."
  {::name    "seon"
   ::theme   "phosphor"
   ::tagline "live agents on a shared core — everything below is derived from the DB right now"})

;; --- Env reads.

(def ^:private env-var-names
  {::name    "SEON_BRAND_NAME"
   ::tagline "SEON_BRAND_TAGLINE"
   ::theme   "SEON_BRAND_THEME"})

(defn env-row
  "The brand attrs set in the environment, as a `::row` map.

   Only the keys whose SEON_BRAND_* var is set and non-blank (read through
   `seon.config`, the ONE typed env surface)."
  {:malli/schema [:=> [:cat] ::row]}
  []
  (reduce-kv (fn [m attr var-name]
               (if-let [v (config/env-string var-name)] (assoc m attr v) m))
             {}
             env-var-names))

;; --- The row read + effective brand.

(defn info
  "Merge one already-acquired ordinary brand row onto the shipped defaults."
  {:malli/schema [:=> [:catn [::row [:maybe ::row]]] ::info]}
  [row]
  (merge defaults row))

;; --- Render helpers (pure).

(schema/register! ::suffix [:string {:min 1}])

(defn page-title
  "A page <title>/<h1> string: \"<brand name> · <suffix>\" — e.g.
   (page-title b \"agents\") → \"seon · agents\"."
  {:malli/schema [:=> [:catn [::info ::info] [::suffix ::suffix]] :string]}
  [brand suffix]
  (str (::name brand) " · " suffix))

;; --- The CSS hook.

(schema/register! ::css-path [:string {:min 1}])

(defn css-text
  "The downstream brand stylesheet's text, or nil when unset.

   0-arity reads the path from SEON_BRAND_CSS (nil when unset); 1-arity
   takes an explicit path (nil-safe). Read FRESH per call — a css edit
   shows on the next page load. An unreadable file logs LOUDLY and returns
   nil: the page renders unbranded rather than breaking."
  {:malli/schema [:function
                  [:=> [:cat] [:or :nil :string]]
                  [:=> [:catn [::css-path [:or :nil ::css-path]]]
                       [:or :nil :string]]]}
  ([] (css-text (config/env-string "SEON_BRAND_CSS")))
  ([path]
   (when path
     (try
       (.readFileSync (js/require "fs") path "utf8")
       (catch :default e
         (log/error-console!
           "seon.web.brand"
           (str "SEON_BRAND_CSS is set but unreadable — pages render "
                "WITHOUT the brand stylesheet: " path)
           e)
         nil)))))

(defn css-style-tag
  "The brand stylesheet as a raw `<style>…</style>` HTML string.

   Returns \"\" when SEON_BRAND_CSS is unset/unreadable. The
   raw-string sibling of the web UI's hiccup `brand-css-style` — both
   delegate to [[css-text]] — for surfaces (the datastar agent-view shim) that
   build their <head> as a string rather than hiccup. Inlined AFTER
   output.css so its token overrides (--color-base-*, --color-amber-*,
   fonts) win the cascade."
  {:malli/schema [:=> [:cat] :string]}
  []
  (if-let [css (css-text)]
    (str "<style>" css "</style>")
    ""))

;; --- Boot sync — env owns the row.

(defn sync-tx-data
  "Tx-data syncing the brand row to the environment (pure).

   Both inputs are passed in. For each brand attr:
     - env has a value ≠ the row's        → assert (identity upsert);
     - env lacks it but the row has it    → retract;
     - equal, or absent on both           → nothing.
   `::row` absent/nil = no brand entity exists (retracts impossible).
   Empty result = nothing to do."
  {:malli/schema [:=> [:cat ::sync-request] :seon.db/tx-data]}
  [{existing ::row env ::env}]
  (let [attrs    [::name ::tagline ::theme]
        asserts  (reduce (fn [m attr]
                           (if-some [v (get env attr)]
                             (if (= v (get existing attr)) m (assoc m attr v))
                             m))
                         {} attrs)
        retracts (when (some? existing)
                   (keep (fn [attr]
                           (when-some [v (get existing attr)]
                             (when-not (contains? env attr)
                               [:db/retract [::id "brand"] attr v])))
                         attrs))]
    (cond-> (vec retracts)
      (seq asserts) (conj (assoc asserts ::id "brand")))))

(defn ^:async sync!
  "Sync the brand row to the `SEON_BRAND_*` environment.

   See ns doc: env OWNS the row across boots. Called from the web UI's
   `install!` at boot; idempotent — a second call with the same env
   transacts nothing. Failures log LOUDLY and resolve `{::synced? false}`
   — branding must never take the boot down."
  {:malli/schema [:=> [:cat] ::sync-response]}
  []
  (try
    (let [database (await (db/db))
          acquired
          (await
            (db/execute-many
              {::db/db database
               ::db/members
               [{::protocol/operation protocol/pull-operation
                 ::protocol/selector [::id ::name ::tagline ::theme]
                 ::protocol/entity-id [::id "brand"]
                 :datahike.resource/max-work 10000
                 :datahike.resource/max-results 1
                 :datahike.resource/max-result-weight 65536}]}))
          member (first (::db/results acquired))
          _ (when (:seon.error/message acquired)
              (throw (ex-info "Brand acquisition failed."
                              {:seon.db/error acquired
                               :seon.error/kind :core-bug})))
          _ (when-not (true? (::protocol/success? member))
              (throw (ex-info "Brand acquisition failed."
                              {:seon.db/error member
                               :seon.error/kind :core-bug})))
          existing (some-> (::protocol/result member)
                           (select-keys [::name ::tagline ::theme]))
          tx (sync-tx-data (cond-> {::env (env-row)}
                             (some? existing) (assoc ::row existing)))]
      (if (empty? tx)
        {::synced? false}
        (let [report
              (await (db/transact! {::db/tx-data tx
                                    ::db/expected-db database}))]
          (if-not (:seon.error/message report)
            (log/info-console! "seon.web.brand" "brand row synced from env"
                               {:tx-ops (count tx)})
            (log/error-console! "seon.web.brand"
                                "brand env sync transact FAILED — pages render the prior/default brand"
                                report))
          {::synced? (not (:seon.error/message report))})))
    (catch :default e
      (log/error-console! "seon.web.brand" "brand env sync threw" e)
      {::synced? false})))
