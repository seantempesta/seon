(ns seon.config-test
  "Unit tests for the config-driven context/skill loadout (`seon.config`).

   Pure-data tests — no conn, no pod boot: the resolvers take a manifest map
   + the raw seed data and return the curated data. Covers schema validity,
   the config-absent identity (the `{}` manifest = byte-identical to a
   no-config boot), the skill exclude/include curation, and the default-load
   expansion into a priority-16 `:skill/<name>` block.

   Run via bin/test-cljs, or interactively via MCP eval:
     (require 'seon.config-test :reload)
     (cljs.test/run-tests 'seon.config-test)"
  (:require
    [cljs.test :refer [deftest is testing]]
    [malli.core :as m]
    [seon.config :as config]))

;; A representative scanned skill corpus (the shape my.skills/seed-skills-tx-data
;; returns — each row carries :my.skills/name + a file path).
(def ^:private rows
  [{:my.skills/name :browser-automation :my.skills/description "b" :seon.agent.ctx/file-path ".claude/skills/browser-automation/SKILL.md"}
   {:my.skills/name :clojure-testing    :my.skills/description "c" :seon.agent.ctx/file-path ".claude/skills/clojure-testing/SKILL.md"}
   {:my.skills/name :datahike           :my.skills/description "d" :seon.agent.ctx/file-path ".claude/skills/datahike/SKILL.md"}
   {:my.skills/name :repl               :my.skills/description "r" :seon.agent.ctx/file-path ".claude/skills/repl/SKILL.md"}])

(def ^:private base-blocks
  [{:seon.agent.ctx/name :namespaces :seon.agent.ctx/priority 20}
   {:seon.agent.ctx/name :transcript :seon.agent.ctx/priority 100}])

(def ^:private routes
  [{:seon.route/name :seon.route/root  :seon.route/pattern "/"}
   {:seon.route/name :seon.route/world :seon.route/pattern "/world"}])

(deftest manifest-schema-validity
  (testing "the first concrete payload validates against :seon.config/manifest"
    (is (m/validate :seon.config/manifest
                    {:seon.config/skills   {:seon.config/exclude [:browser-automation :clojure-testing]}
                     :seon.config/loadouts [{:seon.config/role :default :seon.config/default-load [:repl]}]})))
  (testing "the empty manifest (config absent) is valid — every key optional"
    (is (m/validate :seon.config/manifest {})))
  (testing "an unknown role value is rejected (loud, not silently ignored)"
    (is (not (m/validate :seon.config/manifest
                         {:seon.config/loadouts [{:seon.config/role :nonsense}]})))))

(deftest config-absent-is-identity
  (testing "the {} manifest leaves the skill scan untouched"
    (is (= rows (config/resolve-skill-rows rows {}))))
  (testing "the {} manifest leaves the default block seed untouched"
    (is (= base-blocks (config/resolve-loadout base-blocks :worker {}))))
  (testing "the {} manifest leaves the route seed untouched"
    (is (= routes (config/resolve-routes routes {})))))

(deftest skill-exclude-and-include
  (testing "exclude drops the named skills from the catalog"
    (let [m {:seon.config/skills {:seon.config/exclude [:browser-automation :clojure-testing]}}]
      (is (= [:datahike :repl]
             (mapv :my.skills/name (config/resolve-skill-rows rows m))))))
  (testing "include is an allowlist — only named skills survive"
    (let [m {:seon.config/skills {:seon.config/include [:repl]}}]
      (is (= [:repl] (mapv :my.skills/name (config/resolve-skill-rows rows m))))))
  (testing "exclude wins over include when both name a skill"
    (let [m {:seon.config/skills {:seon.config/include [:repl :datahike]
                                  :seon.config/exclude [:datahike]}}]
      (is (= [:repl] (mapv :my.skills/name (config/resolve-skill-rows rows m)))))))

(deftest default-load-expands-to-skill-block
  (let [m   {:seon.config/loadouts [{:seon.config/role :default :seon.config/default-load [:repl]}]}
        out (config/resolve-loadout base-blocks :root m)
        blk (first (filter #(= :skill/repl (:seon.agent.ctx/name %)) out))]
    (testing "a default-load skill becomes a :skill/<name> block"
      (is (some? blk)))
    (testing "at the cached-prefix priority 16"
      (is (= 16 (:seon.agent.ctx/priority blk))))
    (testing "reusing the shipped my.skills/skill-block render symbol"
      (is (= 'my.skills/skill-block (:seon.render/ai blk))))
    (testing "the base blocks are preserved (override, not replace)"
      (is (= #{:namespaces :transcript :skill/repl}
             (into #{} (map :seon.agent.ctx/name) out))))))

(deftest per-role-merge-and-removes
  (let [m {:seon.config/loadouts
           [{:seon.config/role :default :seon.config/default-load [:repl]}
            {:seon.config/role :root
             :seon.config/blocks  [{:seon.agent.ctx/name :supervision :seon.agent.ctx/priority 14
                                    :seon.render/ai "watch the fleet"}]
             :seon.config/removes [:transcript]}]}]
    (testing "root gets :default + role blocks merged, :removes dropped"
      (let [out   (config/resolve-loadout base-blocks :root m)
            names (into #{} (map :seon.agent.ctx/name) out)]
        (is (contains? names :skill/repl))     ; from :default
        (is (contains? names :supervision))    ; from :root :blocks
        (is (not (contains? names :transcript))))) ; :root :removes
    (testing "a worker gets only the :default loadout (no :root blocks)"
      (let [names (into #{} (map :seon.agent.ctx/name) (config/resolve-loadout base-blocks :worker m))]
        (is (contains? names :skill/repl))
        (is (not (contains? names :supervision)))
        (is (contains? names :transcript))))))

(deftest replace-strategy-starts-fresh
  (let [m {:seon.config/loadouts
           [{:seon.config/role :root :seon.config/strategy :replace
             :seon.config/blocks [{:seon.agent.ctx/name :only :seon.agent.ctx/priority 5}]}]}]
    (testing ":replace discards the default seed, keeping only the loadout blocks"
      (is (= [:only] (mapv :seon.agent.ctx/name (config/resolve-loadout base-blocks :root m)))))))

(deftest route-removes
  (testing "a route spec drops the named seeded routes"
    (let [m {:seon.config/routes [{:seon.config/strategy :override
                                   :seon.config/removes  [:seon.route/world]}]}]
      (is (= [:seon.route/root]
             (mapv :seon.route/name (config/resolve-routes routes m)))))))

(deftest agent-role-selector
  (testing "root id selects :root, everything else :worker"
    (is (= :root   (config/agent-role "root")))
    (is (= :worker (config/agent-role "iCg-2606101519")))))

;;; ENV KNOBS — the consolidated env surface (#54b). The accessors sit on the
;;; single low-level reader (platform/env-val); these tests pin the COERCION +
;;; the :seon.config/dirs precedence, not specific live env values.

(defn- with-env
  "Set process.env[k]=v, run f, restore — so the env-reading accessors get a
   known value without touching the ambient pod env."
  [k v f]
  (let [env (.. js/globalThis -process -env)
        old (aget env k)]
    (try (if (nil? v) (js-delete env k) (aset env k v))
         (f)
         (finally (if (nil? old) (js-delete env k) (aset env k old))))))

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
