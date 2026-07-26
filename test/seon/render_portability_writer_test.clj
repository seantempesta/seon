(ns seon.render-portability-writer-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.render :as render]))

(deftest stored-render-symbols-cross-their-structural-trust-boundary
  (let [trusted-sym 'seon.render-portability-writer-test/core-render
        authored-called? (atom false)
        base {:seon.config/configuration {}
              :seon.schema/projection
              {:seon.schema.projection/function-source-admissions
               {trusted-sym {:seon.schema.admission/source :core}}
               :seon.schema.projection/artifact-exports #{}}
              ::render/compiled-renderers
              {trusted-sym (fn [_] "compiled core")}}]
    (testing "a compiled symbol resolves only from the immutable trusted table"
      (is (= "compiled core"
             (render/render :seon.render/ai base
                            {:seon.agent.ctx/name :core
                             :seon.render/ai trusted-sym}))))
    (testing "the built-in table is compiled and available on the JVM"
      (is (re-find
           #"Core welcome canvas"
           (render/render
            :seon.render/ai
            {:seon.agent/id "jvm-render"
             :seon.agent/entity {:seon.agent/id "jvm-render"}
             :seon.config/configuration {}
             :seon.schema/projection
             {:seon.schema.projection/function-source-admissions {}
              :seon.schema.projection/artifact-exports
              #{'seon.render.canvas/welcome}}}
            {:seon.agent.ctx/name :welcome
             :seon.render/ai 'seon.render.canvas/welcome}))))
    (testing "a hostile core-looking symbol cannot fall through to SCI"
      (let [result
            (render/render
             :seon.render/ai
             (assoc base ::render/invoke-authored!
                    (fn [_] (reset! authored-called? true)))
             {:seon.agent.ctx/name :hostile
              :seon.render/ai 'seon.render.fake/not-in-artifact})]
        (is (true? @authored-called?))
        (is (true? result))))))
