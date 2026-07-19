(ns seon.config-test
  "Unit tests for the config-read layer (`seon.config`).

   Pure-data tests — no conn, no pod boot: the resolvers take a manifest map
   + the raw seed data and return the curated data. Covers schema validity,
   the config-absent identity (the `{}` manifest = byte-identical to a
   no-config boot), route curation, the render-bounds section, and the env
   accessors.

   Run via bin/test-cljs, or interactively via MCP eval:
     (require 'seon.config-test :reload)
     (cljs.test/run-tests 'seon.config-test)"
  (:require
    [cljs.test :refer [deftest is testing]]
    [malli.core :as m]
    [seon.agent]
    [seon.agent.message]
    [seon.agent.web]
    [seon.config :as config]
    [seon.db :as db]
    [seon.schema :as schema]))

(def ^:private routes
  [{:seon.route/name :seon.route/root  :seon.route/pattern "/"}
   {:seon.route/name :seon.route/legacy-page :seon.route/pattern "/legacy"}])

(defn- selected-configuration
  []
  (config/resolve-config-singleton (or (config/load-manifest) {})))

(deftest manifest-schema-validity
  (testing "a representative manifest validates against :seon.config/manifest"
    (is (m/validate :seon.config/manifest
                    {:seon.config/skills        {:seon.config/dirs ["seon-skills"]}
                     :seon.config/routes        [{:seon.config/removes [:seon.route/agent-call]}]
                     :seon.config/run           {:seon.config.run/batch-turn-limit 100
                                                 :seon.config.run/stream-form-limit 300
                                                 :seon.config.run/deadline-ms 1800000}
                     :seon.config/model-transport
                     {:seon.config.model-transport/response-identity-cap 53
                      :seon.config.model-transport/endpoint-cap 257}
                     :seon.config/model-variants
                     {:planning
                      {:seon.ai/agent-provider :openai-compat
                       :seon.ai/agent-model "kimi-k3"
                       :seon.ai/agent-base-url "https://api.moonshot.ai/v1"
                       :seon.ai/agent-api-key-env "MOONSHOT_API_KEY"}}
                     :seon.config/agent-context
                     {:seon.agent/ctx [{:seon.agent.ctx/name :transcript
                                        :seon.agent.ctx/priority 100}]}})))
  (testing "the empty manifest (config absent) is valid — every key optional"
    (is (m/validate :seon.config/manifest {})))
  (testing "the render-bounds section validates"
    (is (m/validate :seon.config/manifest
                    {:seon.config/render {:seon.config.render/value-width 72
                                          :seon.config.render/database-edn-cap 16384}})))
  (testing "a minimal-cluster-shaped manifest validates (system-text + repl-mode + explicit ctx)"
    (is (m/validate :seon.config/manifest
                    {:seon.config/system-text "; ── system ──\n; the minimal prompt"
                     :seon.config/repl-mode   :batch
                     :seon.config/agent-context
                     {:seon.agent/ctx [{:seon.agent.ctx/name :transcript
                                        :seon.agent.ctx/priority 100}]}
                     :seon.config/root-context {}}))))

(deftest config-function-schemas-are-pure-data
  (doseq [v [#'config/namespaces-policy #'config/database-edn-cap]]
    (let [form (:malli/schema (meta v))]
      (is (some? (m/schema form)))
      (is (not-any? #(and (seq? %) (= 'quote (first %)))
                    (tree-seq coll? seq form))
          "runtime indexing must not receive an unevaluated quoted predicate"))))

(deftest every-config-singleton-attribute-has-a-datahike-shape
  (let [form  (schema/schema-definition :seon.config/singleton)
        attrs (->> (rest form)
                   (remove map?)
                   (mapv first))
        facets (db/malli->datahike-schema attrs)]
    (is (= (set attrs) (into #{} (map :db/ident) facets))
        "every config fact written at cold boot bridges to one database attr")
    (is (= {:db/ident :seon.agent.web/allowed-domains
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/many}
           (first (db/malli->datahike-schema
                    [:seon.agent.web/allowed-domains])))
        "the existing web allowlist remains cardinality-many strings"))
  (is (= {:db/ident :seon.config/model-variants
          :db/valueType :db.type/string
          :db/cardinality :db.cardinality/one}
         (first (db/malli->datahike-schema [:seon.config/model-variants])))
      "named model maps use the existing cardinality-one EDN slot bridge"))

(deftest config-absent-is-identity
  (testing "the {} manifest leaves the route seed untouched"
    (is (= routes (config/resolve-routes routes {})))))

(deftest render-explicit-char-knobs-validate-and-default
  ;; transcript-render redesign: the new whitespace/tabs/trailing-ws/layout/
  ;; line-number knobs validate, and an ABSENT section reproduces today's
  ;; bytes — every accessor defaults off.
  (testing "the knobs validate in the manifest"
    (is (m/validate :seon.config/manifest
                    {:seon.config/render
                     {:seon.config.render/whitespace     :visible
                      :seon.config.render/tabs           :arrow
                      :seon.config.render/trailing-ws    :dot
                      :seon.config.render/content-layout :single-line
                      :seon.config.render/line-numbers   true}})))
  (testing "an absent section defaults to today's byte-identical render"
    ;; The redefed empty manifest drives the pre-attach defaults.
    (let [configuration (config/resolve-config-singleton {})]
      (is (= :raw        (config/render-whitespace configuration)))
      (is (= :literal    (config/render-tabs configuration)))
      (is (= :off        (config/render-trailing-ws configuration)))
      (is (= :structured (config/render-content-layout configuration)))
      (is (false?        (config/render-line-numbers? configuration))))))

(defn- ctx-block-names [id override]
  (into #{} (map :seon.agent.ctx/name)
        (:seon.agent/ctx
         (config/resolve-agent-context id override (selected-configuration)))))

(deftest absent-config-has-no-hidden-context-default
  (with-redefs [config/load-manifest (fn [] {})]
    (is (empty? (ctx-block-names "worker-x" nil)))
    (is (empty? (ctx-block-names "root" nil)))))

;;; Explicit `:seon.agent/ctx` = the COMPLETE tree (agent-ctx Phase 3) — the
;;; documented replaces-wholesale contract extends to the identity file-blocks:
;;; an on-disk AGENTS.md/SOUL.md must not smuggle a block into a cluster that
;;; enumerated its tree (config/minimal.edn depends on this).

(deftest explicit-ctx-declares-the-complete-tree
  (let [transcript-only [{:seon.agent.ctx/name :transcript
                          :seon.agent.ctx/priority 100
                          :seon.render/ai 'seon.agent.ctx.transcript/transcript-block}]]
    (testing "manifest agent-context with explicit ctx → exactly that tree, no identity blocks"
      (with-redefs [config/load-manifest
                    (fn [] {:seon.config/agent-context
                            {:seon.agent/ctx transcript-only}})]
        (is (= #{:transcript} (ctx-block-names "worker-x" nil)))
        (is (= #{:transcript} (ctx-block-names "root" nil))
            "root with an absent root-context gets the same explicit tree")))
    (testing "a per-mint override with explicit ctx → exactly that tree"
      (with-redefs [config/load-manifest (fn [] {})]
        (is (= #{:transcript} (ctx-block-names "worker-x"
                                               {:seon.agent/ctx transcript-only})))))
    (testing "no explicit ctx → no hidden code or file-backed block tree"
      (with-redefs [config/load-manifest (fn [] {})]
        (is (empty? (ctx-block-names "worker-x" nil)))))))

;;; Persisted agent-level dials — `:seon.agent.runtime/wake?` / `:seon.eval/home-requires`
;;; carry NO schema default (the CONSUMER owns the default), so a no-config agent
;;; gets NO datom (byte-parity) and the manifest sets the key only to OVERRIDE.

(deftest agent-level-dials-are-override-only
  (with-redefs [config/load-manifest (fn [] {})]
    (testing "a default agent-context carries NEITHER wake? nor home-requires (consumer owns the default)"
      (let [ctx (config/resolve-agent-context
                 "worker-x" nil (selected-configuration))]
        (is (not (contains? ctx :seon.agent.runtime/wake?))
            "no wake? datom on a default agent → seed transacts nothing → parity")
        (is (not (contains? ctx :seon.eval/home-requires))
            "no home-requires datom on a default agent → home-requires-for uses the const")))
    (testing "a per-mint override carries the key into the atomic birth map"
      (let [ctx (config/resolve-agent-context
                 "worker-x"
                 {:seon.agent.runtime/wake? false
                  :seon.eval/home-requires '[[seon.db :as db]]}
                 (selected-configuration))]
        (is (false? (:seon.agent.runtime/wake? ctx)))
        (is (= '[[seon.db :as db]] (:seon.eval/home-requires ctx)))))))

(deftest per-agent-model-config-resolves-through-the-birth-context
  (let [base {:seon.ai/agent-provider :openai-compat
              :seon.ai/agent-model "kimi-k3"
              :seon.ai/agent-temperature :inherit
              :seon.ai/agent-max-tokens 16384
              :seon.ai/agent-thinking "false"
              :seon.ai/agent-timeout-ms 180000
              :seon.ai/agent-base-url "https://api.moonshot.ai/v1"
              :seon.ai/agent-api-key-env "MOONSHOT_API_KEY"
              :seon.ai/agent-dg-backend :inherit
              :seon.ai/agent-extra-body-edn "{:planner true}"
              :seon.ai/agent-max-retries 1}
        configuration {:seon.config/id config/cluster-config-id
                       :seon.config/agent-context base
                       :seon.config/root-context
                       {:seon.ai/agent-model "muse-spark-1.1"
                        :seon.ai/agent-base-url "https://api.meta.ai/v1"
                        :seon.ai/agent-api-key-env "META_API_KEY"}}
        ordinary (config/resolve-agent-context "worker-x" nil configuration)
        root (config/resolve-agent-context "root" nil configuration)]
    (is (m/validate :seon.config/manifest
                    (dissoc configuration :seon.config/id)))
    (is (= base (dissoc ordinary :seon.agent/ctx))
        "ordinary births retain every logical agent model value")
    (is (= "kimi-k3" (:seon.ai/agent-model ordinary)))
    (is (= "https://api.moonshot.ai/v1"
           (:seon.ai/agent-base-url ordinary)))
    (is (= "muse-spark-1.1" (:seon.ai/agent-model root)))
    (is (= "https://api.meta.ai/v1" (:seon.ai/agent-base-url root)))
    (is (= "META_API_KEY" (:seon.ai/agent-api-key-env root)))
    (is (= 180000 (:seon.ai/agent-timeout-ms root))
        "a sparse root override inherits the remaining model fields")
    (is (= "kimi-k3"
           (:seon.ai/agent-model
            (config/resolve-agent-context
             "worker-x" {:seon.ai/agent-model "kimi-k3"} configuration)))
        "per-mint model attributes use the same resolver")))

(deftest named-model-variants-are-sparse-closed-launch-overrides
  (let [planning
        {:seon.ai/agent-provider :openai-compat
         :seon.config/repl-mode :batch
         :seon.ai/agent-model "kimi-k3"
         :seon.ai/agent-max-tokens 16384
         :seon.ai/agent-timeout-ms 180000
         :seon.ai/agent-base-url "https://api.moonshot.ai/v1"
         :seon.ai/agent-api-key-env "MOONSHOT_API_KEY"}
        manifest {:seon.config/agent-context
                  {:seon.ai/agent-thinking "false"
                   :seon.ai/agent-max-retries 2}
                  :seon.config/model-variants {:planning planning}}
        configuration (config/resolve-config-singleton manifest)
        selected (get (config/model-variants configuration) :planning)
        resolved (config/resolve-agent-context "planner" selected configuration)]
    (is (= {:planning planning} (config/model-variants configuration)))
    (is (= {} (config/model-variants
               (config/resolve-config-singleton {}))))
    (is (= "kimi-k3" (:seon.ai/agent-model resolved)))
    (is (= :batch (:seon.config/repl-mode resolved))
        "a named planning variant selects multi-namespace batch grammar")
    (is (= "false" (:seon.ai/agent-thinking resolved))
        "a sparse variant inherits ordinary agent-context values")
    (is (= 2 (:seon.ai/agent-max-retries resolved)))
    (is (not (m/validate
              :seon.config/manifest
              {:seon.config/model-variants
               {:planning (assoc planning :unrelated/value true)}}))
        "variant maps reject attributes outside the existing agent model surface")))

;;; Block-override MERGES by name — a manifest overriding a block need only name
;;; the sub-keys it changes; the default block's other attrs survive (the
;;; third-party-first contract). Proven via the root-context `:canvas` block,
;;; which by default carries `:seon.agent.ctx/priority` + `:seon.render/ai` +
;;; `:seon.render.canvas/content`.

(deftest block-override-merges-preserving-sub-keys
  (testing "root-context overriding ONE :canvas sub-key keeps the default block's other attrs"
    (with-redefs [config/load-manifest
                  (fn [] {:seon.config/agent-context
                          {:seon.agent/ctx
                           [{:seon.agent.ctx/name :canvas
                             :seon.agent.ctx/priority 35
                             :seon.render/ai 'probe/canvas}]}
                          :seon.config/root-context
                          {:seon.agent/ctx
                           [{:seon.agent.ctx/name :canvas
                             :seon.render.canvas/content [:div "ACME-CUSTOM"]}]}})]
      (let [blocks (:seon.agent/ctx
                    (config/resolve-agent-context
                     "root" nil (selected-configuration)))
            lt     (first (filter #(= :canvas (:seon.agent.ctx/name %)) blocks))]
        (is (= [:div "ACME-CUSTOM"] (:seon.render.canvas/content lt))
            "the overridden sub-key wins")
        (is (contains? lt :seon.agent.ctx/priority)
            "the default block's priority survives a sparse override")
        (is (contains? lt :seon.render/ai)
            "the default block's render fn survives a sparse override"))))
  (testing "a root-context block whose name is NOT in the base is appended as new"
    (with-redefs [config/load-manifest
                  (fn [] {:seon.config/agent-context
                          {:seon.agent/ctx
                           [{:seon.agent.ctx/name :canvas
                             :seon.agent.ctx/priority 35
                             :seon.render/ai 'probe/canvas}]}
                          :seon.config/root-context
                          {:seon.agent/ctx
                           [{:seon.agent.ctx/name :acme-extra
                             :seon.agent.ctx/priority 99
                             :seon.render/ai 'my.ui/extra}]}})]
      (let [names (ctx-block-names "root" nil)]
        (is (contains? names :acme-extra) "the new block is seeded")
        (is (contains? names :canvas) "default blocks remain")))))

(deftest root-home-requires-extend-the-complete-base-toolbelt
  (let [manifest
        {:seon.config/agent-context
         {:seon.agent/ctx []
          :seon.eval/home-requires
          '[[seon.db :as db]
            [seon.agent.message :as message]
            [acme.brand :as brand]]}
         :seon.config/root-context
         {:seon.eval/home-requires
          '[[seon.agent :as agent]
            [seon.db :as database]]}}]
    (let [configuration (config/resolve-config-singleton manifest)]
      (is (= '[[seon.db :as db]
               [seon.agent.message :as message]
               [acme.brand :as brand]]
             (:seon.eval/home-requires
               (config/resolve-agent-context
                "worker-x" nil configuration)))
          "an ordinary agent keeps the exact configured toolbelt")
      (is (= '[[seon.db :as database]
               [seon.agent.message :as message]
               [acme.brand :as brand]
               [seon.agent :as agent]]
             (:seon.eval/home-requires
               (config/resolve-agent-context "root" nil configuration)))
          "root inherits downstream capabilities, refines by ns, and appends"))))

;;; The `#merge` COMPOSITION trap (config-merge, 2026-07-11) — a per-cluster
;;; manifest composes as `#merge [#include "base" {overrides}]`. Aero's shipped
;;; `#merge` is a SHALLOW map merge, so a sparse override that sets only
;;; `:seon.eval/home-requires` USED to silently DROP the base's `:seon.agent/ctx`
;;; block tree (the schema `:default` then quietly filled the LEGACY tree — acme
;;; ran the wrong context for a day, the 1bd1d21d cutover regression). The
;;; manifest-aware `#merge` override in `seon.config` (loaded by this ns's
;;; require) applies `resolve-agent-context`'s replaces-wholesale rule to the
;;; `:seon.config/agent-context` key: a sparse override INHERITS `:seon.agent/ctx`,
;;; an explicit one REPLACES it wholesale. Pinned hermetically via temp edn files
;;; read through the same aero seam `load-manifest` uses (temp dir is gitignored).

(defn- write-tmp!
  "Write `content` to `tmp/config-merge-test/rel`, return the path."
  [rel content]
  (let [fs   (js/require "fs")
        path (js/require "path")
        dir  "tmp/config-merge-test"]
    (.mkdirSync fs dir #js {:recursive true})
    (let [p (.join path dir rel)]
      (.writeFileSync fs p content)
      p)))

(defn- manifest-via-config
  "Drive `config/load-manifest` at `path` through a `SEON_CONFIG` swap — the real
   read+validate seam (so the `#merge` reader override is exercised), env restored."
  [path]
  (let [env (.. js/globalThis -process -env) old (aget env "SEON_CONFIG")]
    (try (aset env "SEON_CONFIG" path) (config/load-manifest)
         (finally (if (nil? old) (js-delete env "SEON_CONFIG") (aset env "SEON_CONFIG" old))))))

(deftest merge-agent-context-inherits-or-replaces-block-tree
  (let [base-blocks [{:seon.agent.ctx/name :namespaces :seon.agent.ctx/priority 20}
                     {:seon.agent.ctx/name :transcript :seon.agent.ctx/priority 100}]]
    (write-tmp! "base.edn"
                (pr-str {:seon.config/agent-context
                         {:seon.agent/ctx           base-blocks
                          :seon.eval/home-requires  '[[a :as a]]}}))
    (testing "a SPARSE override (only home-requires) INHERITS the base :seon.agent/ctx (the trap)"
      (let [p  (write-tmp! "sparse.edn"
                           (str "#merge\n[#include \"base.edn\"\n"
                                " {:seon.config/agent-context"
                                "  {:seon.eval/home-requires [[b :as b]]}}]"))
            ac (:seon.config/agent-context (manifest-via-config p))]
        (is (= (mapv :seon.agent.ctx/name base-blocks)
               (mapv :seon.agent.ctx/name (:seon.agent/ctx ac)))
            "the base :seon.agent/ctx block tree survives a sparse override")
        (is (= '[[a :as a] [b :as b]] (:seon.eval/home-requires ac))
            "the sparse manifest adds capabilities without copying the base")))
    (testing "an override that DECLARES :seon.agent/ctx replaces the tree WHOLESALE"
      (let [p  (write-tmp! "explicit.edn"
                           (str "#merge\n[#include \"base.edn\"\n"
                                " {:seon.config/agent-context"
                                "  {:seon.agent/ctx [{:seon.agent.ctx/name :plan"
                                "                     :seon.agent.ctx/priority 45}]}}]"))
            ac (:seon.config/agent-context (manifest-via-config p))]
        (is (= [:plan] (mapv :seon.agent.ctx/name (:seon.agent/ctx ac)))
            "the explicit tree wins wholesale (base blocks dropped)")
        (is (not (contains? ac :seon.eval/home-requires))
            "wholesale replace drops the base's other keys (consumer-default fallback)")))))

(defn- with-env
  "Set process.env[k]=v, run f, restore — so the env-reading accessors/config
   get a known value without touching the ambient pod env."
  [k v f]
  (let [env (.. js/globalThis -process -env) old (aget env k)]
    (try (if (nil? v) (js-delete env k) (aset env k v)) (f)
         (finally (if (nil? old) (js-delete env k) (aset env k old))))))

(deftest root-context-resolves-one-ordered-block-tree
  (with-env
    "SEON_CONFIG" "config/system.edn"
    (fn []
      (let [configuration (selected-configuration)
              ordinary (config/resolve-agent-context
                        "worker-x" nil configuration)
              root (config/resolve-agent-context "root" nil configuration)
              order (fn [context]
                      (->> (:seon.agent/ctx context)
                           (sort-by (juxt :seon.agent.ctx/priority
                                          (comp str :seon.agent.ctx/name)))
                           (mapv (juxt :seon.agent.ctx/name
                                       :seon.agent.ctx/priority))))]
          (is (= [[:namespaces 20]
                  [:canvas 35]
                  [:plan 45]
                  [:transcript 100]]
                 (order ordinary))
              "ordinary agents keep the shared context tree")
          (is (= [[:root-role 15]
                  [:namespaces 20]
                  [:core-faults 41]
                  [:instrumentation-gaps 42]
                  [:orphaned-agents 43]
                  [:plan 45]
                  [:canvas 90]
                  [:transcript 100]]
                 (order root))
              "root is one additive tree with its dynamic fleet canvas near the tail")))))

(deftest acme-manifest-inherits-context-and-adds-only-product-tools
  (with-env
    "SEON_CONFIG" "config/acme.edn"
    (fn []
      (let [configuration (selected-configuration)
              ordinary (config/resolve-agent-context
                        "acme-worker" nil configuration)
              root     (config/resolve-agent-context "root" nil configuration)
              targets  (fn [context]
                         (into #{} (map first)
                               (:seon.eval/home-requires context)))
              blocks   (into #{} (map :seon.agent.ctx/name)
                             (:seon.agent/ctx ordinary))]
          (is (= #{:namespaces :canvas :plan :transcript} blocks)
              "experimental function-menu/typeahead blocks stay off")
          (is (every? (targets ordinary) '[acme.brand acme.widget my.ns my.skills]))
          (is (not-any? (targets ordinary) '[acme.helpers acme.notes]))
          (is (every? (targets root)
                      '[acme.brand acme.widget my.ns my.skills
                        seon.agent seon.agent.shell seon.agent.web])
              "root inherits the complete ordinary/downstream capability set")))))

(deftest route-removes
  (testing "a route spec drops the named seeded routes"
    (let [m {:seon.config/routes [{:seon.config/removes [:seon.route/legacy-page]}]}]
      (is (= [:seon.route/root]
             (mapv :seon.route/name (config/resolve-routes routes m)))))))

;;; RENDER BOUNDS — the global display caps live in the manifest's
;;; :seon.config/render section (#46). The accessor reads the section with a
;;; literal fallback equal to the manifest default, so an absent section is
;;; byte-identical to the shipped value.

(deftest render-caps-read-the-manifest
  ;; These are pure pre-attach manifest resolver tests. Authority-backed
  ;; config reads belong to the database facade contract, not a local conn.
  (testing "an absent :seon.config/render section → the accessor's literal fallback"
    (let [configuration (config/resolve-config-singleton {})]
      (is (= 72 (config/value-width configuration)))
      (is (= 16384 (config/database-edn-cap configuration)))
      (is (= 3 (config/value-max-depth configuration)))))
  (testing "a manifest value overrides the fallback"
    (let [configuration
          (config/resolve-config-singleton
           {:seon.config/render
            {:seon.config.render/value-width 40
             :seon.config.render/database-edn-cap 999}})]
      (is (= 40 (config/value-width configuration)))
      (is (= 999 (config/database-edn-cap configuration)))
      ;; an unset key in a present section still falls back to the literal
      (is (= 3 (config/value-max-depth configuration))))))

(deftest reactive-policy-is-resolved-into-the-config-singleton
  (let [defaults (config/resolve-config-singleton {})
        configured
        (config/resolve-config-singleton
         {:seon.config/reactive
          {:seon.config/reactive-settle-ms 7
           :seon.config/reactive-structural-settle-ms 70
           :seon.config/reactive-max-latency-ms 700}})]
    (is (= {:seon.config/reactive-settle-ms 16
            :seon.config/reactive-structural-settle-ms 300
            :seon.config/reactive-max-latency-ms 500}
           (config/reactive-policy defaults)))
    (is (= {:seon.config/reactive-settle-ms 7
            :seon.config/reactive-structural-settle-ms 70
            :seon.config/reactive-max-latency-ms 700}
           (config/reactive-policy configured)))))

(deftest reactive-environment-overrides-resolve-before-database-seeding
  (with-env
    "SEON_REACTIVE_SETTLE_MS" "8"
    (fn []
      (with-env
        "SEON_REACTIVE_STRUCTURAL_SETTLE_MS" "80"
        (fn []
          (with-env
            "SEON_REACTIVE_MAX_LATENCY_MS" "800"
            (fn []
              (let [manifest (manifest-via-config "config/system.edn")]
                (is (= {:seon.config/reactive-settle-ms 8
                        :seon.config/reactive-structural-settle-ms 80
                        :seon.config/reactive-max-latency-ms 800}
                       (:seon.config/reactive manifest)))))))))))

;;; ENV KNOBS — the few knobs that stay env-only (launch/process). These tests
;;; pin the COERCION + the :seon.config/dirs precedence, not live env values.
;;; ([[with-env]] is defined above with the soul-block tests.)

(deftest env-int-coerces-positive-or-default
  (testing "positive int parses; blank/non-numeric/non-positive fall to default"
    (with-env "SEON_TEST_CAP" "350"
      #(is (= 350 (config/env-int "SEON_TEST_CAP" 99))))
    (with-env "SEON_TEST_CAP" "0"
      #(is (= 99 (config/env-int "SEON_TEST_CAP" 99))))   ; non-positive → default
    (with-env "SEON_TEST_CAP" "abc"
      #(is (= 99 (config/env-int "SEON_TEST_CAP" 99))))   ; non-numeric → default
    (with-env "SEON_TEST_CAP" nil
      #(is (= 99 (config/env-int "SEON_TEST_CAP" 99)))))) ; unset → default

(deftest env-string-nil-when-blank
  (with-env "SEON_TEST_STR" "  "
    #(is (nil? (config/env-string "SEON_TEST_STR"))))    ; blank → nil
  (with-env "SEON_TEST_STR" "x"
    #(is (= "x" (config/env-string "SEON_TEST_STR")))))

(deftest manifest-input-is-explicit
  (with-env "SEON_CONFIG" nil
    #(is (nil? (config/load-manifest))))
  (with-env "SEON_CONFIG" "tmp/no-such-config.edn"
    #(is (thrown? js/Error (config/load-manifest)))))

(deftest skills-dir-precedence
  (testing "manifest :seon.config/dirs wins over env, which wins over the default"
    (is (= "from/manifest"
           (config/skills-dir
            {:seon.config/skills
             {:seon.config/dirs ["from/manifest"]}})))
    (with-env "SEON_SKILLS_DIR" "from/env"
      #(is (= "from/env" (config/skills-dir {}))))
    (with-env "SEON_SKILLS_DIR" nil
      #(is (= ".claude/skills" (config/skills-dir {}))))))

;;; ============================================================
;;; CONFIG → DB (config-db-migration 2026-07-10). `resolve-config-singleton` is
;;; the ONE resolver (seed source + pre-conn fallback); the boot reconcile seeds
;;; it as the `:seon.config` singleton; every accessor reads it back from the db.
;;; ============================================================

(deftest resolve-config-singleton-defaults-and-overrides
  (testing "the {} manifest resolves every knob to its byte-parity default"
    (let [s (config/resolve-config-singleton {})]
      (is (= "cluster" (:seon.config/id s)))
      ;; repl-mode's default is per-MODEL (env-derived) — pinned by its own
      ;; deftest below, not here (this test runs under the suite's ambient env).
      (is (= 1500      (:seon.config.render/eval-cap s)))
      (is (= 100       (:seon.config.run/batch-turn-limit s)))
      (is (= 300       (:seon.config.run/stream-form-limit s)))
      (is (= 1800000   (:seon.config.run/deadline-ms s)))
      (is (not (contains? s
                          :seon.config.model-transport/response-identity-cap))
          "config-free history preserves absence")
      (is (= :symbols  (:seon.config.repair/level s)))
      (is (= :public-only (:seon.agent.web/policy s)))
      (is (= 1         (:seon.config/spawn-depth-cap s)))
      (is (= 1200000   (:seon.config.watchdog/stale-ms s)))
      (is (= 12        (:seon.config.root/recent-limit s)))
      (is (= 16        (:seon.config/reactive-settle-ms s)))
      (is (= 300       (:seon.config/reactive-structural-settle-ms s)))
      (is (= 500       (:seon.config/reactive-max-latency-ms s)))
      (is (= {}        (:seon.config.repair/classes s)))
      (is (= []        (:seon.agent.web/allowed-domains s)))
      (is (not (contains? s :seon.config/current-ns))
          "namespace render selection belongs to the namespaces block")
      ;; system-text has NO default — absent from a bare manifest
      (is (not (contains? s :seon.config/system-text)))))
  (testing "the namespace manifest has one source-storage option"
    (is (m/validate :seon.config/manifest
                    {:seon.config/namespaces
                     {:seon.config/always '[my.kb seon.agent.message]}}))
    (is (not (m/validate :seon.config/manifest
                         {:seon.config/namespaces
                          {:seon.config/current-ns :off}}))
        "the removed duplicate render switch fails instead of being ignored"))
  (testing "an absent cardinality-many allowlist reads as an empty vector"
    (is (= {:seon.agent.web/policy :public-only
            :seon.agent.web/allowed-domains []}
           (config/web-policy {}))))
  (testing "a manifest value overrides the resolved knob"
    (let [agent-context {:seon.agent/ctx
                         [{:seon.agent.ctx/name :transcript}]}
          root-context {:seon.agent/ctx
                        [{:seon.agent.ctx/name :canvas}]}
          skills {:seon.config/dirs ["seon-skills"]}
          s (config/resolve-config-singleton
              {:seon.config/render {:seon.config.render/eval-cap 42}
               :seon.config/run {:seon.config.run/batch-turn-limit 7
                                 :seon.config.run/stream-form-limit 19
                                 :seon.config.run/deadline-ms 123456}
               :seon.config/root {:seon.config.root/recent-limit 9}
               :seon.config/model-transport
               {:seon.config.model-transport/response-identity-cap 17
                :seon.config.model-transport/endpoint-cap 29}
               :seon.config/on-core-error :log
               :seon.config/system-text "you are a helpful agent"
               :seon.config/skills skills
               :seon.config/agent-context agent-context
               :seon.config/root-context root-context})]
      (is (= 42 (:seon.config.render/eval-cap s)))
      (is (= 7 (:seon.config.run/batch-turn-limit s)))
      (is (= 19 (:seon.config.run/stream-form-limit s)))
      (is (= 123456 (:seon.config.run/deadline-ms s)))
      (is (= 9 (:seon.config.root/recent-limit s)))
      (is (= 17 (:seon.config.model-transport/response-identity-cap s)))
      (is (= 29 (:seon.config.model-transport/endpoint-cap s)))
      (is (= :log (:seon.config/on-core-error s)))
      (is (= "you are a helpful agent" (:seon.config/system-text s)))
      (is (= skills (:seon.config/skills s)))
      (is (= agent-context (:seon.config/agent-context s)))
      (is (= root-context (:seon.config/root-context s))))))

(deftest agent-context-is-derived-from-explicit-config-data
  (let [stored {:seon.config/id config/cluster-config-id
                :seon.config/agent-context
                {:seon.agent/ctx [{:seon.agent.ctx/name :transcript}]}
                :seon.config/root-context
                {:seon.agent/ctx [{:seon.agent.ctx/name :canvas}]}}]
    (with-redefs [config/load-manifest
                  (fn [] (throw (js/Error. "external config must not be read")))]
      (is (= #{:transcript}
             (into #{} (map :seon.agent.ctx/name)
                   (:seon.agent/ctx
                    (config/resolve-agent-context "worker-x" nil stored)))))
      (is (= #{:transcript :canvas}
             (into #{} (map :seon.agent.ctx/name)
                   (:seon.agent/ctx
                    (config/resolve-agent-context "root" nil stored))))))))

(deftest repl-mode-default-is-per-model
  ;; The manifest-absent repl-mode default is computed from the model
  ;; identity the :seon.ai/config row seeds from (measured 2026-07-10:
  ;; DeepSeek fabricates in :batch, :stream removes it structurally;
  ;; Spark-class models are ~0-fab in :batch and :stream only costs them
  ;; latency). Env is stashed/restored — the suite's ambient values differ.
  (let [env     (.-env js/process)
        saved-p (.-SEON_AI_PROVIDER env)
        saved-m (.-SEON_AI_MODEL env)
        mode-of (fn [m] (:seon.config/repl-mode (config/resolve-config-singleton m)))]
    (try
      (testing "env unset (the shipped :deepseek default) → :stream"
        (js-delete env "SEON_AI_PROVIDER")
        (js-delete env "SEON_AI_MODEL")
        (is (= :stream (mode-of {}))))
      (testing "a non-deepseek gateway model → :batch"
        (set! (.-SEON_AI_PROVIDER env) "openai-compat")
        (set! (.-SEON_AI_MODEL env) "muse-spark-1.1")
        (is (= :batch (mode-of {}))))
      (testing "a deepseek MODEL through a generic gateway → :stream"
        (set! (.-SEON_AI_MODEL env) "deepseek-v4-flash")
        (is (= :stream (mode-of {}))))
      (testing "an explicit manifest value always wins"
        (js-delete env "SEON_AI_PROVIDER")
        (js-delete env "SEON_AI_MODEL")
        (is (= :batch (mode-of {:seon.config/repl-mode :batch}))))
      (finally
        (if (some? saved-p)
          (set! (.-SEON_AI_PROVIDER env) saved-p)
          (js-delete env "SEON_AI_PROVIDER"))
        (if (some? saved-m)
          (set! (.-SEON_AI_MODEL env) saved-m)
          (js-delete env "SEON_AI_MODEL"))))))
