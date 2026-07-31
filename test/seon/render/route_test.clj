(ns seon.render.route-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reitit.core :as reitit]
            [seon.render.route :as route]))

(def ^:private path-examples
  {::route/root [{} "/"]
   ::route/namespace [{:namespace "seon.flow"} "/ns/seon.flow"]
   ::route/namespace-debug
   [{:namespace "seon.flow"} "/ns/seon.flow/debug"]
   ::route/agent [{:id "agent/one"} "/agent/agent%2Fone"]
   ::route/agent-debug [{:id "agent one"} "/agent/agent%20one/debug"]
   ::route/agent-message [{:id "root"} "/agent/root/message"]
   ::route/feed [{:id "root"} "/feed/root"]
   ::route/data [{} "/data"]
   ::route/css [{:path "output.css"} "/css/output.css"]
   ::route/js [{:path "datastar.js"} "/js/datastar.js"]})

(deftest every-route-name-reverses-and-round-trips
  (is (= (set (keys path-examples)) (set (reitit/route-names route/router))))
  (doseq [[route-name [params expected]] path-examples]
    (let [path (route/path route-name params)
          match (reitit/match-by-path route/router path)]
      (is (= expected path) (str "reverse path for " route-name))
      (is (= route-name (get-in match [:data :name]))
          (str "round-trip name for " route-name))))
  (is (= "/data?entity=%5B%3Ax%2Fid+%22a+b%22%5D&offset=0"
         (route/path ::route/data
                     {}
                     {:entity "[:x/id \"a b\"]" :offset "0"}))))

(deftest route-conflicts-and-unknown-names-fail-loudly
  (testing "Reitit's default name-conflict check remains enabled"
    (is (thrown? clojure.lang.ExceptionInfo
                 (reitit/router
                  (conj route/routes
                        ["/conflict" {:name ::route/root
                                      :get {:handler ::route/root}}])))))
  (testing "the public path function refuses an unknown route name"
    (is (thrown? clojure.lang.ExceptionInfo
                 (route/path ::unknown)))))

(def ^:private forbidden-url-builders
  ["(str \"/agent/\""
   "(str \"/feed/\""
   "\"/data?entity=\""
   ":href \"/\""])

(deftest render-code-contains-no-hand-built-route-urls
  (let [route-file (.getCanonicalFile (io/file "src/seon/render/route.clj"))
        files (cons (io/file "src/seon/render.clj")
                    (->> (file-seq (io/file "src/seon/render"))
                         (filter #(.isFile %))
                         (filter #(or (str/ends-with? (.getName %) ".clj")
                                      (str/ends-with? (.getName %) ".cljc")))
                         (remove #(= route-file (.getCanonicalFile %)))))]
    (doseq [file files
            forbidden forbidden-url-builders]
      (is (not (str/includes? (slurp file) forbidden))
          (str (.getPath file) " contains " forbidden)))))
