(ns seon.render-portability-test
  (:require [clojure.test :refer [deftest is]]
            [seon.render :as render]))

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
           :seon.config/configuration {}}
          core-function-block))))
