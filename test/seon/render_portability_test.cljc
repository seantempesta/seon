(ns seon.render-portability-test
  (:require [clojure.test :refer [deftest is]]
            [seon.agent.ctx :as ctx]
            [seon.ns.source :as ns-source]
            [seon.render :as render]
            [seon.render.value :as value]))

(def pinned-database-value
  {:db-name "u7-byte-parity"
   :t 42
   :datahike/commit-id "u7-commit"})

(def core-function-block
  {:seon.agent.ctx/name :portable-function
   :seon.render/ai 'seon.render.handlers.fn/render-ai
   :seon.fn/sym "my.demo/add"
   :seon.fn/arglists "([x y])"
   :seon.fn/spec "[:=> [:cat :int :int] :int]"
   :seon.fn/doc "Add two integers."})

(deftest compiled-core-block-is-byte-identical-at-one-database-value
  (is (= (str "[fn my.demo/add]  (my.demo/add [x y])\n"
              ";; ✓ specced\n"
              ";; spec: [:=> [:cat :int :int] :int]\n"
              ";; Add two integers.")
         (render/render
          :seon.render/ai
          {:seon.db/db pinned-database-value
           :seon.config/configuration {}
           :seon.schema/projection
           {:seon.schema.projection/function-source-admissions {}
            :seon.schema.projection/artifact-exports
            #{'seon.render.handlers.fn/render-ai}}}
          core-function-block))))

(deftest eval-seam-helpers-are-portable
  (is (= "plain result" (value/sanitize-result-edn "plain result")))
  (is (= "" (ns-source/scratch-def-note "(defn durable [] 1)")))
  (is (re-find #"won't persist"
               (ns-source/scratch-def-note "(def temporary 1)")))
  (is (= {::ns-source/aliases {'x 'example.lib}
          ::ns-source/nses #{'example.lib}
          ::ns-source/refers {'example.lib #{'f}}
          ::ns-source/refer-all #{}}
         (ns-source/edges->require-info
          #{{:seon.ns.require/target 'example.lib
             :seon.ns.require/alias 'x
             :seon.ns.require/refers #{'f}}}))))

(deftest core-context-block-is-byte-identical-at-one-database-value
  (let [input {::ctx/seed-specs [":my.demo/name"]
               ::ctx/own-keys #{}
               ::ctx/schema-rows
               [{:seon.schema/key :my.demo/name
                 :seon.schema/form ":string"}]}]
    (is (= "(register! :my.demo/name :string)"
           (ctx/referenced-schema-rows-block input)))))
