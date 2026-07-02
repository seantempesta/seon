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
    [seon.config :as config]))

(def ^:private routes
  [{:seon.route/name :seon.route/root  :seon.route/pattern "/"}
   {:seon.route/name :seon.route/world :seon.route/pattern "/world"}])

(deftest manifest-schema-validity
  (testing "a representative manifest validates against :seon.config/manifest"
    (is (m/validate :seon.config/manifest
                    {:seon.config/skills        {:seon.config/dirs ["seon-skills"]}
                     :seon.config/routes        [{:seon.config/removes [:seon.route/agent-call]}]
                     :seon.config/agent-context {:my.skills/load [:repl]}})))
  (testing "the empty manifest (config absent) is valid — every key optional"
    (is (m/validate :seon.config/manifest {})))
  (testing "the render-bounds section validates"
    (is (m/validate :seon.config/manifest
                    {:seon.config/render {:seon.config.render/value-width 72
                                          :seon.config.render/store-edn-cap 16384}}))))

(deftest config-absent-is-identity
  (testing "the {} manifest leaves the route seed untouched"
    (is (= routes (config/resolve-routes routes {})))))

;;; :my.skills/load — the always-on skill-body presence-set expands into
;;; :skill/<name> blocks on :seon.agent/ctx (live-proof the dial is consumed:
;;; a non-default value observably changes the seeded block set).

(defn- ctx-block-names [id override]
  (into #{} (map :seon.agent.ctx/name)
        (:seon.agent/ctx (config/resolve-agent-context id override))))

(deftest skills-load-expands-to-skill-blocks
  (with-redefs [config/load-manifest (fn [] {})]
    (testing "default :my.skills/load [:repl] seeds exactly the :skill/repl body"
      (let [names (ctx-block-names "worker-x" nil)]
        (is (contains? names :skill/repl))
        (is (not (contains? names :skill/datahike)))))
    (testing "a NON-default :my.skills/load seeds a body block per member"
      (let [names (ctx-block-names "worker-x" {:my.skills/load [:repl :datahike]})]
        (is (contains? names :skill/repl))
        (is (contains? names :skill/datahike))))
    (testing "an explicit empty :my.skills/load seeds NO skill body"
      (let [names (ctx-block-names "worker-x" {:my.skills/load []})]
        (is (not (some #(= "skill" (namespace %)) names))
            "no :skill/<name> block when load is empty")))
    (testing "the expanded body block carries the shipped render symbol + priority 16"
      (let [ctx (config/resolve-agent-context "worker-x" {:my.skills/load [:datahike]})
            blk (first (filter #(= :skill/datahike (:seon.agent.ctx/name %))
                               (:seon.agent/ctx ctx)))]
        (is (= 'my.skills/skill-block (:seon.render/ai blk)))
        (is (= 16 (:seon.agent.ctx/priority blk)))))))

;;; Persisted agent-level dials — `:seon.client/wake?` / `:seon.eval/home-requires`
;;; carry NO schema default (the CONSUMER owns the default), so a no-config agent
;;; gets NO datom (byte-parity) and the manifest sets the key only to OVERRIDE.

(deftest agent-level-dials-are-override-only
  (with-redefs [config/load-manifest (fn [] {})]
    (testing "a default agent-context carries NEITHER wake? nor home-requires (consumer owns the default)"
      (let [ctx (config/resolve-agent-context "worker-x" nil)]
        (is (not (contains? ctx :seon.client/wake?))
            "no wake? datom on a default agent → seed transacts nothing → parity")
        (is (not (contains? ctx :seon.eval/home-requires))
            "no home-requires datom on a default agent → home-requires-for uses the const")))
    (testing "a per-mint override carries the key so seed-default-ctx! transacts it"
      (let [ctx (config/resolve-agent-context "worker-x"
                                              {:seon.client/wake? false
                                               :seon.eval/home-requires '[[seon.db :as db]]})]
        (is (false? (:seon.client/wake? ctx)))
        (is (= '[[seon.db :as db]] (:seon.eval/home-requires ctx)))))))

;;; Block-override MERGES by name — a manifest overriding a block need only name
;;; the sub-keys it changes; the default block's other attrs survive (the
;;; third-party-first contract). Proven via the root-context `:live-tile` block,
;;; which by default carries `:seon.agent.ctx/priority` + `:seon.render/ai` +
;;; `:seon.render.live-tile/content`.

(deftest block-override-merges-preserving-sub-keys
  (testing "root-context overriding ONE :live-tile sub-key keeps the default block's other attrs"
    (with-redefs [config/load-manifest
                  (fn [] {:seon.config/root-context
                          {:seon.agent/ctx
                           [{:seon.agent.ctx/name :live-tile
                             :seon.render.live-tile/content [:div "ACME-CUSTOM"]}]}})]
      (let [blocks (:seon.agent/ctx (config/resolve-agent-context "root" nil))
            lt     (first (filter #(= :live-tile (:seon.agent.ctx/name %)) blocks))]
        (is (= [:div "ACME-CUSTOM"] (:seon.render.live-tile/content lt))
            "the overridden sub-key wins")
        (is (contains? lt :seon.agent.ctx/priority)
            "the default block's priority survives a sparse override")
        (is (contains? lt :seon.render/ai)
            "the default block's render fn survives a sparse override"))))
  (testing "a root-context block whose name is NOT in the base is appended as new"
    (with-redefs [config/load-manifest
                  (fn [] {:seon.config/root-context
                          {:seon.agent/ctx
                           [{:seon.agent.ctx/name :acme-extra
                             :seon.agent.ctx/priority 99
                             :seon.render/ai 'my.ui/extra}]}})]
      (let [names (ctx-block-names "root" nil)]
        (is (contains? names :acme-extra) "the new block is seeded")
        (is (contains? names :live-tile) "default blocks remain")))))

;;; Soul/agents identity file-blocks — migrated from the retired
;;; `seon.agent.ctx/default-seed-blocks` into the config path
;;; (`identity-file-blocks`). Present only when the file EXISTS and SEON_SOUL is
;;; not off — so a soul-off (or file-absent) cluster gets none = byte-parity.

(defn- with-env
  "Set process.env[k]=v, run f, restore — so the env-reading accessors/config
   get a known value without touching the ambient pod env."
  [k v f]
  (let [env (.. js/globalThis -process -env) old (aget env k)]
    (try (if (nil? v) (js-delete env k) (aset env k v)) (f)
         (finally (if (nil? old) (js-delete env k) (aset env k old))))))

(deftest soul-block-gated-by-env-and-file-presence
  (with-redefs [config/load-manifest (fn [] {})]
    (testing "SEON_SOUL=false → NO :soul block (byte-parity with the soul-off default cluster)"
      (with-env "SEON_SOUL" "false"
        #(let [names (ctx-block-names "x" nil)]
           (is (not (contains? names :soul))))))
    (testing "SEON_SOUL on + SOUL.md present → the :soul block at priority 5, file-block render"
      ;; SOUL.md exists in the repo; the default cluster just runs SEON_SOUL=false.
      (with-env "SEON_SOUL" "true"
        #(let [blocks (:seon.agent/ctx (config/resolve-agent-context "x" nil))
               soul   (first (filter (fn [b] (= :soul (:seon.agent.ctx/name b))) blocks))]
           (is (some? soul) "soul block present when SEON_SOUL on + SOUL.md exists")
           (is (= 5 (:seon.agent.ctx/priority soul)))
           (is (= 'seon.agent.ctx/file-block-ai (:seon.render/ai soul))))))))

(deftest route-removes
  (testing "a route spec drops the named seeded routes"
    (let [m {:seon.config/routes [{:seon.config/removes [:seon.route/world]}]}]
      (is (= [:seon.route/root]
             (mapv :seon.route/name (config/resolve-routes routes m)))))))

;;; RENDER BOUNDS — the global display caps live in the manifest's
;;; :seon.config/render section (#46). The accessor reads the section with a
;;; literal fallback equal to the manifest default, so an absent section is
;;; byte-identical to the shipped value.

(deftest render-caps-read-the-manifest
  (testing "an absent :seon.config/render section → the accessor's literal fallback"
    (with-redefs [config/load-manifest (fn [] {})]
      ;; bust the memoized read so the redef takes
      (config/reset-render-cache!)
      (is (= 72 (config/value-width)))
      (is (= 16384 (config/store-edn-cap)))
      (is (= 3 (config/value-max-depth)))))
  (testing "a manifest value overrides the fallback"
    (with-redefs [config/load-manifest
                  (fn [] {:seon.config/render {:seon.config.render/value-width 40
                                               :seon.config.render/store-edn-cap 999}})]
      (config/reset-render-cache!)
      (is (= 40 (config/value-width)))
      (is (= 999 (config/store-edn-cap)))
      ;; an unset key in a present section still falls back to the literal
      (is (= 3 (config/value-max-depth)))))
  ;; restore the live cache for the rest of the run
  (config/reset-render-cache!))

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

(deftest skills-dir-precedence
  (testing "manifest :seon.config/dirs wins over env, which wins over the default"
    (with-redefs [config/load-manifest
                  (fn [] {:seon.config/skills {:seon.config/dirs ["from/manifest"]}})]
      (is (= "from/manifest" (config/skills-dir))))
    (with-redefs [config/load-manifest (fn [] {})]
      (with-env "SEON_SKILLS_DIR" "from/env"
        #(is (= "from/env" (config/skills-dir))))
      (with-env "SEON_SKILLS_DIR" nil
        #(is (= ".claude/skills" (config/skills-dir)))))))
