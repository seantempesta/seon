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
    [cljs.test :refer [deftest is testing async]]
    [datahike.api :as d]
    [malli.core :as m]
    [seon.config :as config]
    [seon.db :as db]))

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
    ;; `*conn*` nil ⇒ the db-view seam returns nil ⇒ the accessors take the
    ;; PRE-conn manifest-resolve path (`resolve-config-singleton`), so the
    ;; redefed `{}` manifest drives the defaults (config-db-migration).
    (with-redefs [config/load-manifest (fn [] {})]
      (binding [db/*conn* nil]
        (is (= :raw        (config/render-whitespace)))
        (is (= :literal    (config/render-tabs)))
        (is (= :off        (config/render-trailing-ws)))
        (is (= :structured (config/render-content-layout)))
        (is (false?        (config/render-line-numbers?)))))))

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
  ;; `*conn*` nil ⇒ the db-view seam returns nil ⇒ the accessors resolve from
  ;; the redefed manifest (the pre-conn sliver path); post-conn they read the
  ;; seeded singleton datom (proven live, not here — this is the pure resolver).
  (testing "an absent :seon.config/render section → the accessor's literal fallback"
    (with-redefs [config/load-manifest (fn [] {})]
      (binding [db/*conn* nil]
        (is (= 72 (config/value-width)))
        (is (= 16384 (config/store-edn-cap)))
        (is (= 3 (config/value-max-depth))))))
  (testing "a manifest value overrides the fallback"
    (with-redefs [config/load-manifest
                  (fn [] {:seon.config/render {:seon.config.render/value-width 40
                                               :seon.config.render/store-edn-cap 999}})]
      (binding [db/*conn* nil]
        (is (= 40 (config/value-width)))
        (is (= 999 (config/store-edn-cap)))
        ;; an unset key in a present section still falls back to the literal
        (is (= 3 (config/value-max-depth)))))))

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

;;; ============================================================
;;; CONFIG → DB (config-db-migration 2026-07-10). `resolve-config-singleton` is
;;; the ONE resolver (seed source + pre-conn fallback); the boot reconcile seeds
;;; it as the `:seon.config` singleton; every accessor reads it back from the db.
;;; ============================================================

(deftest resolve-config-singleton-defaults-and-overrides
  (testing "the {} manifest resolves every knob to its byte-parity default"
    (let [s (config/resolve-config-singleton {})]
      (is (= "cluster" (:seon.config/id s)))
      (is (= :batch    (:seon.config/repl-mode s)))
      (is (= 1500      (:seon.config.render/eval-cap s)))
      (is (= :symbols  (:seon.config.repair/level s)))
      (is (= :public-only (:seon.agent.web/policy s)))
      (is (= 1         (:seon.config/spawn-depth-cap s)))
      (is (= 1200000   (:seon.config.watchdog/stale-ms s)))
      (is (= {}        (:seon.config.repair/classes s)))
      (is (= []        (:seon.agent.web/allowed-domains s)))
      (is (= :full     (:seon.config/current-ns s)))
      ;; system-text has NO default — absent from a bare manifest
      (is (not (contains? s :seon.config/system-text)))))
  (testing "a manifest value overrides the resolved knob"
    (let [s (config/resolve-config-singleton
              {:seon.config/render {:seon.config.render/eval-cap 42}
               :seon.config/on-core-error :log
               :seon.config/system-text "you are a helpful agent"})]
      (is (= 42 (:seon.config.render/eval-cap s)))
      (is (= :log (:seon.config/on-core-error s)))
      (is (= "you are a helpful agent" (:seon.config/system-text s))))))

(deftest stale-singleton-retractions-heals-optional-attrs
  (testing "an attr present in the stored singleton but absent from desired is retracted"
    (let [current  {:db/id 7 :seon.config/id "cluster"
                    :seon.config.render/eval-cap 1500
                    :seon.config/system-text "stale"}
          desired  (config/resolve-config-singleton {})    ; no system-text
          retracts (config/stale-singleton-retractions current desired)]
      ;; only system-text is stale (eval-cap is in desired; :db/id is ignored)
      (is (= [[:db/retract [:seon.config/id "cluster"] :seon.config/system-text "stale"]]
             retracts))))
  (testing "no retractions when the stored map matches desired"
    (let [d (config/resolve-config-singleton {})]
      (is (empty? (config/stale-singleton-retractions d d))))))

(defn- config-scratch-conn
  "Promise of a fresh :memory conn with tx-meta + the `:seon.config` singleton
   attrs installed — for the db-backed accessor reads."
  []
  (let [attrs [:seon.config/id :seon.config/repl-mode :seon.config/current-ns
               :seon.config/on-core-error :seon.config/spawn-depth-cap
               :seon.config/always :seon.config/system-text
               :seon.config.render/eval-cap :seon.config.render/store-edn-cap
               :seon.config.render/value-width :seon.config.render/line-numbers
               :seon.config.repair/level :seon.config.repair/classes
               :seon.agent.web/policy :seon.agent.web/allowed-domains
               :seon.agent.web/search-backend :seon.agent.web/search-model
               :seon.config.watchdog/stale-ms :seon.config.breaker/crash-count]
        cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact! conn {:tx-data (into (db/malli->datahike-schema attrs)
                                                       (db/tx-meta-datahike-schema))})
                     (.then (fn [_] conn))))))))

(deftest accessors-read-the-seeded-singleton-datom
  ;; Seed the singleton into a scratch conn (the boot path), pin `*conn*`, and
  ;; prove the accessors read the DATOM — config-through-DB. The three collection
  ;; knobs round-trip through the EDN-slot bridge (set / map / vector).
  (async done
    (-> (config-scratch-conn)
        (.then
          (fn [conn]
            (let [manifest {:seon.config/render {:seon.config.render/eval-cap 4321}
                            :seon.config/on-core-error :log
                            :seon.config/repair {:seon.config.repair/classes {:foo false}}
                            :seon.config/web {:seon.agent.web/policy :allowlist
                                              :seon.agent.web/allowed-domains ["a.example.com"]}
                            :seon.config/namespaces {:seon.config/always '[my.kb my.plan]}
                            :seon.config/system-text "SYS"}
                  singleton (config/resolve-config-singleton manifest)
                  prev db/*conn*]
              (-> (db/with-tx-context
                    {:seon.db/origin :config}
                    (fn [] (db/transact! {:seon.db/conn conn :seon.db/tx-data [singleton]})))
                  (.then
                    (fn [_]
                      (set! db/*conn* conn)
                      (try
                        ;; scalar caps + dials read from the datom
                        (is (= 4321 (config/eval-render-cap)))
                        (is (= :log (config/on-core-error)))
                        (is (= 1 (config/spawn-depth-cap)))      ; default, seeded
                        ;; collection knobs decoded off the EDN slot
                        (is (= #{:my.kb :my.plan} (:seon.config/always (config/namespaces-policy))))
                        (is (= :allowlist (:seon.agent.web/policy (config/web-policy))))
                        (is (= ["a.example.com"] (:seon.agent.web/allowed-domains (config/web-policy))))
                        (finally (set! db/*conn* prev) (done)))))
                  (.catch (fn [e] (set! db/*conn* prev) (is false (str e)) (done)))))))
        (.catch (fn [e] (is false (str e)) (done))))))
