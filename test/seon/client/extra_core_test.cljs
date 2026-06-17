(ns seon.client.extra-core-test
  "Extra-core registration (task #36 — SEON_EXTRA_SRC): downstream
   vars registered into `seon.client/!extra-core-vars` index, render
   full-source, replay-skip like the core's own; reserved-prefix
   (seon.* / my.*) extras are refused LOUDLY at boot-index time; vars the
   downstream's macro expansion shares with the core dedup silently.

   Uses the committed `acme.extra-fixture` ns (under seon's test/ root)
   as the stand-in downstream namespace — no env var or external
   checkout needed; the registration call is exactly what a downstream
   entry ns does with `(seon.indexing/specced-fn-vars)`.

   Spec: docs/prds/agent-runtime/research/extra-src-research-2026-06-12.md §d."
  (:require [acme.extra-fixture]
            [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [seon.client :as client]
            [seon.db]))

(defn guard-bait
  "A specced fn in a `seon.*` ns that is NOT in `core-vars` (test
   nses are outside seon.client's require closure) — registering it as
   an extra var must trip the reserved-prefix refusal."
  {:malli/schema [:=> [:cat :string] :string]}
  [s]
  s)

(deftest extra-core-vars-join-the-boot-index
  (let [before @client/!extra-core-vars]
    (try
      (reset! client/!extra-core-vars [#'acme.extra-fixture/echo-greeting])
      (let [rows   (client/index-core!)
            fn-row (some #(when (= "acme.extra-fixture/echo-greeting"
                                   (:seon.fn/sym %)) %)
                         rows)
            ns-row (some #(when (= :acme.extra-fixture (:seon.ns/name %)) %)
                         rows)]
        (testing "fn-row built by REAL runtime introspection (source + spec)"
          (is (some? fn-row))
          (is (str/includes? (:seon.fn/source fn-row "") "defn echo-greeting"))
          (is (some? (:seon.fn/spec fn-row))))
        (testing "owning ns-row carries the FULL file source, not the (ns x) stub"
          (is (some? ns-row))
          (is (str/includes? (:seon.ns/source ns-row "")
                             "COMMITTED test fixture")))
        (testing "replay-skip: the extra ns joins core-ns-set"
          (is (contains? (client/core-ns-set) :acme.extra-fixture))))
      (finally
        (reset! client/!extra-core-vars before)))))

(deftest reserved-prefix-extra-registration-refused-at-boot-index
  (let [before @client/!extra-core-vars]
    (try
      (reset! client/!extra-core-vars [#'guard-bait])
      (let [err (try (client/index-core!) nil
                     (catch :default e e))]
        (is (some? err)
            "index-core! must THROW on a reserved-prefix extra ns")
        (is (str/includes? (str (ex-message err))
                           "seon.client.extra-core-test")
            "the refusal names the offending ns")
        (is (= ["seon.client.extra-core-test"]
               (:seon.client/reserved-extra-nses (ex-data err)))))
      (finally
        (reset! client/!extra-core-vars before)))))

(deftest core-overlap-dedups-silently
  ;; A downstream entry's (specced-fn-vars) expansion sees the seon
  ;; surface its require closure pulls in. Those vars dedup away by
  ;; fully-qualified sym BEFORE the reserved-prefix guard runs — no
  ;; throw, no duplicate rows.
  (let [before @client/!extra-core-vars]
    (try
      (reset! client/!extra-core-vars [#'seon.db/transact!])
      (let [rows (client/index-core!)]
        (is (= 1 (count (filter #(= "seon.db/transact!" (:seon.fn/sym %))
                                rows)))
            "exactly one row for the overlapping var — and no guard throw"))
      (finally
        (reset! client/!extra-core-vars before)))))
