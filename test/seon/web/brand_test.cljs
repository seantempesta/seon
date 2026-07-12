(ns seon.web.brand-test
  "seon.web.brand contract (C-17 downstream brand surface) — the
   product name/tagline/theme are DATA: absent env + absent row →
   the shipped seon defaults (byte-identical output); SEON_BRAND_*
   env vars own the row across boots (set → asserted, unset →
   retracted); render helpers are pure over the effective brand;
   the SEON_BRAND_CSS hook degrades loudly, never breaks the page.

   Placeholder brand values use \"Acme\" — never a real product name."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [seon.db :as db]
    [seon.web.brand :as brand]))

;; ============================================================
;; Pure — sync-tx-data (env owns the row).
;; ============================================================

(deftest sync-tx-data-covers-the-four-env-row-cases
  (is (= [] (brand/sync-tx-data {::brand/env {}}))
      "no env + no row → nothing to do (defaults at render)")
  (is (= [{::brand/id "brand" ::brand/name "Acme"}]
         (brand/sync-tx-data {::brand/env {::brand/name "Acme"}}))
      "env set + no row → one identity-upsert assert")
  (is (= [] (brand/sync-tx-data {::brand/row {::brand/name "Acme"}
                                 ::brand/env {::brand/name "Acme"}}))
      "env equals row → idempotent, transacts nothing")
  (is (= [{::brand/id "brand" ::brand/name "Beta"}]
         (brand/sync-tx-data {::brand/row {::brand/name "Acme"}
                              ::brand/env {::brand/name "Beta"}}))
      "env changed → re-assert (last-write-wins upsert)")
  (is (= [[:db/retract [::brand/id "brand"] ::brand/name "Acme"]]
         (brand/sync-tx-data {::brand/row {::brand/name "Acme"}
                              ::brand/env {}}))
      "env unset but row present → retract — defaults return next render")
  (is (= [[:db/retract [::brand/id "brand"] ::brand/tagline "ship it"]
          {::brand/id "brand" ::brand/name "Acme" ::brand/theme "midnight"}]
         (brand/sync-tx-data
           {::brand/row {::brand/name "old" ::brand/tagline "ship it"}
            ::brand/env {::brand/name "Acme" ::brand/theme "midnight"}}))
      "mixed: retracts first, then one assert map for the set attrs"))

;; ============================================================
;; Pure — render helpers.
;; ============================================================

(deftest page-title-renders-default-and-branded
  (is (= "seon · agents" (brand/page-title brand/defaults "agents"))
      "default brand → today's exact title")
  (is (= "Acme · agent a1 · debug"
         (brand/page-title (assoc brand/defaults ::brand/name "Acme")
                           "agent a1 · debug"))
      "branded name flows into every title"))

(deftest defaults-are-the-shipped-seon-brand
  (is (= "seon" (::brand/name brand/defaults)))
  (is (= "phosphor" (::brand/theme brand/defaults)))
  (is (string? (::brand/tagline brand/defaults))
      "the mission-control subtitle line ships as the default tagline"))

;; ============================================================
;; env-row — reads SEON_BRAND_* (set/cleaned around the assertion).
;; ============================================================

(deftest env-row-reads-only-set-nonblank-vars
  (let [env (.. js/process -env)]
    (try
      (aset env "SEON_BRAND_NAME" "Acme")
      (aset env "SEON_BRAND_TAGLINE" "")          ; blank = unset
      (is (= {::brand/name "Acme"} (brand/env-row))
          "set var present; blank var and unset var absent")
      (finally
        (js-delete env "SEON_BRAND_NAME")
        (js-delete env "SEON_BRAND_TAGLINE")))))

;; ============================================================
;; css-text — the SEON_BRAND_CSS hook degrades, never breaks.
;; ============================================================

(deftest css-text-reads-a-file-and-degrades-on-missing
  (is (nil? (brand/css-text nil)) "no path configured → nil, no log")
  (is (nil? (brand/css-text "tmp/brand-test-does-not-exist.css"))
      "missing file → nil (loud log), page still renders")
  (let [fs   (js/require "fs")
        path "tmp/brand-test.css"]
    (try
      (.writeFileSync fs path ":root{--color-amber-400:#f0f;}")
      (is (= ":root{--color-amber-400:#f0f;}" (brand/css-text path))
          "existing file → its text, inlined after output.css by the debug view")
      (finally
        (try (.unlinkSync fs path) (catch :default _ nil))))))

;; ============================================================
;; Store roundtrip — info reads the row at render time; sync tx-data
;; transacted on a FRESH :memory conn (never the live agent conn).
;; ============================================================

(defn- fresh-conn
  "Promise of a fresh :memory conn carrying the brand attrs + tx-meta
   schema — no brand row yet."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact!
                       conn
                       {:tx-data (into (db/malli->datahike-schema
                                         [::brand/id ::brand/name
                                          ::brand/tagline ::brand/theme])
                                       (db/tx-meta-datahike-schema))})
                     (.then (fn [_] conn))))))))

(defn- with-conn
  "Fresh conn, `set!` as the ROOT db/*conn* for `body` (conn →
   Promise), prior root restored after."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(deftest info-defaults-then-branded-then-defaults-again
  (async done
    (-> (with-conn
          (fn [conn]
            ;; 1. Empty store → the shipped defaults, every key present.
            (is (= brand/defaults (brand/info @conn))
                "absent env + absent row → seon defaults")
            ;; 2. \"Boot with env\": transact the sync tx-data for
            ;;    SEON_BRAND_NAME=Acme SEON_BRAND_THEME=midnight.
            (-> (db/transact!
                  {:seon.db/tx-data
                   (brand/sync-tx-data
                     {::brand/env {::brand/name  "Acme"
                                   ::brand/theme "midnight"}})})
                (.then (fn [{ok? :seon.db/ok?}]
                         (is (true? ok?) "brand sync transact lands")
                         (let [b (brand/info @conn)]
                           (is (= "Acme" (::brand/name b)))
                           (is (= "midnight" (::brand/theme b)))
                           (is (= (::brand/tagline brand/defaults)
                                  (::brand/tagline b))
                               "unset attrs keep their defaults")
                           (is (= "Acme · agents" (brand/page-title b "agents"))))
                         ;; 3. \"Reboot WITHOUT env\": sync against the
                         ;;    now-branded row retracts — defaults return.
                         (db/transact!
                           {:seon.db/tx-data
                            (brand/sync-tx-data
                              {::brand/row {::brand/name  "Acme"
                                            ::brand/theme "midnight"}
                               ::brand/env {}})})))
                (.then (fn [{ok? :seon.db/ok?}]
                         (is (true? ok?) "unset-env sync transact lands")
                         (is (= brand/defaults (brand/info @conn))
                             "env removed → the seon defaults return"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
