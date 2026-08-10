(ns seon.render.web-performance-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.render :as render]
            [seon.render.walk :as render.walk]
            [seon.render.web :as web]
            [seon.test-support :as support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(deftest one-pass-derives-one-profile-for-every-page-unit
  (support/with-database
    (fn [connection]
      (let [effective-calls (atom 0)
            observed-profiles (atom [])
            effective (config/defaults)
            expected-profile (render/agent-render-profile effective)
            handle {:seon.cluster/name "web-performance-test"
                    :seon.db/connection connection
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/ctx nil
                    :seon.config.eval/time-limit-ms 1000
                    :seon.config/on-core-error :panic
                    :seon.cluster.run/process "web-performance-test"}
            state {:seon.cluster.loop/cluster handle
                   :seon.render.web/registration
                   (atom {"agent-a" 1 "agent-b" 1})
                   :seon.render.web/latest-packages (atom {})
                   :seon.render.web/root-agent-id "root"
                   ::web/streams {}
                   ::web/packages {}
                   ::web/fragments {}
                   ::web/calls {}
                   ::web/passes 0}]
        (with-redefs [config/effective
                      (fn [_database _cluster-name]
                        (swap! effective-calls inc)
                        effective)
                      render.walk/neighborhood
                      (fn [request]
                        ;; A real page asks for the profile from many render
                        ;; calls. Repeating the request here proves every unit
                        ;; receives the pass-derived value without re-reading
                        ;; config facts.
                        (swap! observed-profiles conj
                               (#'render/request-profile request)
                               (#'render/request-profile request))
                        [])]
          (let [[next-state _published] (#'web/render-pass state)]
            (testing "one database value supplies one profile derivation"
              (is (= 1 @effective-calls))
              (is (= 4 (count @observed-profiles)))
              (is (every? #(= expected-profile %) @observed-profiles)))
            (testing "both registered pages still settle in the same pass"
              (is (= #{"agent-a" "agent-b"}
                     (set (keys (::web/packages next-state))))))))))))
