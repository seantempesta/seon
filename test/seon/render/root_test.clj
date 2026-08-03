(ns seon.render.root-test
  "Regressions for root's ordinary block projections."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.render.hiccup :as hiccup]
            [seon.render.root :as root]
            [seon.test-support :as test-support]))

(deftest each-facts-link-names-its-own-agent-as-the-drill-root
  (test-support/with-database
    (fn [connection]
      (db/transact! connection
                  [{:seon.cluster.agent/id "alice"}
                   {:seon.cluster.agent/id "bob"}])
      (let [html (hiccup/->string
                  (root/agents-html {:seon.db/db @connection}))]
        (testing "each link selects the database entity, then starts at its root"
          (is (str/includes?
               html
               "/data?entity=%5B%3Aseon.cluster.agent%2Fid+%22alice%22%5D&amp;path=%5B%5D&amp;offset=0"))
          (is (str/includes?
               html
               "/data?entity=%5B%3Aseon.cluster.agent%2Fid+%22bob%22%5D&amp;path=%5B%5D&amp;offset=0")))
        (testing "an identity lookup ref is not misused as a get-in path"
          (is (not (str/includes?
                    html
                    "/data?path=%5B%3Aseon.cluster.agent%2Fid"))))))))
