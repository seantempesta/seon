(ns seon.condemned-paths-test
  "Prevents condemned pod code from growing or gaining surviving callers.

   Remove inventory rows and their require baselines with each deletion wave.
   Delete this test with the last condemned path."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io File)
           (java.util.regex Pattern)))

(def ^:private condemned
  [{::path "src/seon/client.cljs"
    ::namespace 'seon.client
    ::baseline-lines 2879
    ::dies-at "Group 5 / Step 6"}
   {::path "src/seon/config.cljs"
    ::namespace 'seon.config
    ::baseline-lines 1185
    ::dies-at "Group 5 / Step 6"}
   {::path "src/seon/db/fiber.cljs"
    ::namespace 'seon.db.fiber
    ::baseline-lines 69
    ::dies-at "Group 5 / Step 6"}
   {::path "src/seon/db/session.cljs"
    ::namespace 'seon.db.session
    ::baseline-lines 772
    ::dies-at "Group 5 / Step 6"}
   {::path "src/seon/db/transport/uds.cljs"
    ::namespace 'seon.db.transport.uds
    ::baseline-lines 999
    ::dies-at "Group 5 / Step 6"}
   {::path "src/seon/demo.cljs"
    ::namespace 'seon.demo
    ::baseline-lines 14
    ::dies-at "Group 5 / Step 6"}
   {::path "src/seon/log.cljs"
    ::namespace 'seon.log
    ::baseline-lines 459
    ::dies-at "Group 5 / Step 6"}
   {::path "src/seon/platform.cljs"
    ::namespace 'seon.platform
    ::baseline-lines 62
    ::dies-at "Group 5 / Step 6"}
   {::path "src/seon/runtime/admission.cljs"
    ::namespace 'seon.runtime.admission
    ::baseline-lines 949
    ::dies-at "Group 5 / Step 6"}
   {::path "src/seon/runtime/state.cljs"
    ::namespace 'seon.runtime.state
    ::baseline-lines 600
    ::dies-at "Group 5 / Step 6"}
   {::path "src/seon/test/runner.cljs"
    ::namespace 'seon.test.runner
    ::baseline-lines 823
    ::dies-at "Group 5 / Step 6"}
   {::path "src/seon/agent/ctx/driver.cljs"
    ::namespace 'seon.agent.ctx.driver
    ::baseline-lines 605
    ::dies-at "Group 6 / Step 7"}
   {::path "src/seon/agent/debug.cljs"
    ::namespace 'seon.agent.debug
    ::baseline-lines 529
    ::dies-at "Group 6 / Step 7"}
   {::path "src/seon/derive.cljs"
    ::namespace 'seon.derive
    ::baseline-lines 505
    ::dies-at "Group 6 / Step 7"}
   {::path "src/seon/render/system.cljs"
    ::namespace 'seon.render.system
    ::baseline-lines 130
    ::dies-at "Group 6 / Step 7"}
   {::path "src/seon/route.cljs"
    ::namespace 'seon.route
    ::baseline-lines 115
    ::dies-at "Group 6 / Step 7"}
   {::path "src/seon/ui/agent_view.cljs"
    ::namespace 'seon.ui.agent-view
    ::baseline-lines 93
    ::dies-at "Group 6 / Step 7"}
   {::path "src/seon/ui/header.cljs"
    ::namespace 'seon.ui.header
    ::baseline-lines 47
    ::dies-at "Group 6 / Step 7"}
   {::path "src/seon/web/brand.cljs"
    ::namespace 'seon.web.brand
    ::baseline-lines 238
    ::dies-at "Group 6 / Step 7"}
   {::path "src/seon/web/datastar.cljs"
    ::namespace 'seon.web.datastar
    ::baseline-lines 1224
    ::dies-at "Group 6 / Step 7"}
   {::path "src/seon/web/debug.cljs"
    ::namespace 'seon.web.debug
    ::baseline-lines 304
    ::dies-at "Group 6 / Step 7"}
   {::path "src/seon/web/reactive/call.cljs"
    ::namespace 'seon.web.reactive.call
    ::baseline-lines 301
    ::dies-at "Group 6 / Step 7"}
   {::path "src/seon/web/reactive/transform.cljs"
    ::namespace 'seon.web.reactive.transform
    ::baseline-lines 267
    ::dies-at "Group 6 / Step 7"}
   {::path "src/seon/web/router.cljs"
    ::namespace 'seon.web.router
    ::baseline-lines 486
    ::dies-at "Group 6 / Step 7"}
   {::path "src/seon/web/serve.cljs"
    ::namespace 'seon.web.serve
    ::baseline-lines 2018
    ::dies-at "Group 6 / Step 7"}
   {::path "src/seon/web/value.cljs"
    ::namespace 'seon.web.value
    ::baseline-lines 58
    ::dies-at "Group 6 / Step 7"}])

(def ^:private inbound-require-baseline
  #{["src/my/plan/internal.cljc" 'seon.log]
    ["src/seon/db.cljc" 'seon.db.session]
    ["src/seon/db/host.clj" 'seon.db.transport.uds]
    ["src/seon/db/server.clj" 'seon.db.transport.uds]
    ["src/seon/db/writer.clj" 'seon.db.transport.uds]
    ["src/seon/error.cljc" 'seon.config]
    ["src/seon/instrument.cljc" 'seon.config]
    ["src/seon/launch.cljc" 'seon.platform]
    ["src/seon/reactive.cljc" 'seon.config]
    ["src/seon/reactive.cljc" 'seon.log]
    ["src/seon/render.cljc" 'seon.config]
    ["src/seon/web/server.clj" 'seon.db.transport.uds]})

(defn- root []
  (io/file (System/getProperty "user.dir")))

(defn- relative-path [^File file]
  (-> (.toPath (root))
      (.relativize (.toPath file))
      str
      (str/replace File/separator "/")))

(defn- line-count [{::keys [path]}]
  (let [file (io/file (root) path)]
    (if (.isFile file)
      (with-open [reader (io/reader file)]
        (count (line-seq reader)))
      0)))

(defn- code-file? [^File file]
  (and (.isFile file)
       (boolean (re-find #"\.(?:clj|cljc|cljs)$" (.getName file)))))

(defn- require-pattern []
  (let [namespaces (->> condemned
                        (map (comp str ::namespace))
                        (map #(Pattern/quote %))
                        (str/join "|"))]
    (re-pattern (str "\\[\\s*(" namespaces ")(?=[\\s\\]])"))))

(defn- inbound-requires []
  (let [condemned-paths (into #{} (map ::path) condemned)
        pattern (require-pattern)]
    (into #{}
          (comp
           (filter code-file?)
           (remove #(contains? condemned-paths (relative-path %)))
           (mapcat
            (fn [file]
              (map (fn [[_ namespace]]
                     [(relative-path file) (symbol namespace)])
                   (re-seq pattern (slurp file))))))
          (file-seq (io/file (root) "src")))))

(deftest condemned-files-only-shrink
  (doseq [{::keys [path baseline-lines dies-at] :as entry} condemned]
    (testing (str path " dies at " dies-at)
      (let [current-lines (line-count entry)]
        (is (<= current-lines baseline-lines)
            (str path " grew from its condemned baseline of " baseline-lines
                 " lines to " current-lines " lines"))))))

(deftest surviving-code-adds-no-condemned-requires
  (let [current (inbound-requires)
        added (set/difference current inbound-require-baseline)]
    (is (empty? added)
        (str "Surviving source added requires into condemned namespaces: "
             (pr-str (sort added))))))
