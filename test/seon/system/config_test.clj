(ns seon.system.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.system.config :as config]))

;; M-1 (2026-05-15): the legacy `:seon.db.datalevin/server`,
;; `:seon.db.datalevin/connections`, `:seon/runtime-db`, and
;; `:seon.graph/scanner` config schemas were removed alongside their dead
;; Integrant defmethods. The `:seon.flow/pool` schema dropped
;; `:datalevin-server`. Tests now exercise the surviving schemas.

(deftest validate-valid-configs-test
  (testing "valid configs return nil"
    (is (nil? (config/validate :seon.schema/registry {})))
    (is (nil? (config/validate :seon.dev/nrepl
                               {:enabled? true :port 7888 :bind "127.0.0.1"})))
    (is (nil? (config/validate :seon.web.server/http-server
                               {:port 8080 :bind "0.0.0.0"})))
    (is (nil? (config/validate :seon.web/tailwind
                               {:enabled? true :input "a.css" :output "b.css"})))
    (is (nil? (config/validate :seon.web/caddy
                               {:enabled? false})))
    (is (nil? (config/validate :seon.flow/pool
                               {:size 3 :base-port 7900 :enabled? true})))
    (is (nil? (config/validate :seon.orchestrator/sessions
                               {:connection-manager :mock :pool :mock})))
    (is (nil? (config/validate :seon.ai.claude/sdk
                               {:cli-path "/usr/bin/claude"})))))

(deftest validate-invalid-configs-test
  (testing "invalid port type"
    (is (some? (config/validate :seon.web.server/http-server
                                {:port "not-a-number" :bind "0.0.0.0"}))))
  (testing "missing required key"
    (is (some? (config/validate :seon.web.server/http-server
                                {:bind "0.0.0.0"}))))
  (testing "port out of range"
    (is (some? (config/validate :seon.web.server/http-server
                                {:port 99999 :bind "0.0.0.0"})))))

(deftest validate-unknown-key-test
  (testing "unknown component returns nil (no schema)"
    (is (nil? (config/validate :unknown/component {:anything true})))))

(deftest describe-test
  (testing "returns schema info for known component"
    (let [desc (config/describe :seon.web.server/http-server)]
      (is (= :seon.web.server/http-server (:key desc)))
      (is (some? (:schema desc)))
      (is (some? (:schema-form desc)))))
  (testing "returns nil for unknown component"
    (is (nil? (config/describe :unknown/component))))
  (testing "returns nil for removed component (M-1)"
    (is (nil? (config/describe :seon.db.datalevin/server)))
    (is (nil? (config/describe :seon/runtime-db)))
    (is (nil? (config/describe :seon.graph/scanner)))))
