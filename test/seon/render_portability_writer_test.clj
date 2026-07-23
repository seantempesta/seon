(ns seon.render-portability-writer-test
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.host.guard :as guard]
            [seon.render :as render]))

(defn- policy [fuel]
  {::guard/fuel fuel
   ::guard/mode :enforce
   ::guard/invocation-class :authored-render
   ::guard/fuel-config-key :seon.config.guard/authored-render-fuel
   ::guard/deadline-config-key :seon.config.guard/deadline-ms
   ::guard/output-config-key :seon.config.guard/output-cap})

(defn- authored-door [context holder fuel]
  (fn [{::render/keys [function-symbol arguments]}]
    (try
      (guard/call!
       {::guard/holder holder
        ::guard/policy (policy fuel)
        ::guard/evaluate!
        #(apply @(sci/resolve context function-symbol) arguments)})
      (catch Throwable throwable
        (or (guard/steering-error! holder throwable)
            (throw throwable))))))

(deftest stored-render-symbols-cross-their-structural-trust-boundary
  (let [trusted-sym 'seon.render-portability-writer-test/core-render
        authored-called? (atom false)
        base {:seon.config/configuration {}
              ::render/trusted-renderers
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
             :seon.config/configuration {}}
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
        (is (false? @authored-called?))
        (is (re-find #"does not resolve" result))))))

(deftest authored-infinite-render-stops-inside-the-guarded-door
  (let [holder (guard/holder)
        context (sci/init {:interrupt-fn (guard/interrupt-fn holder)})
        _ (sci/eval-string*
           context
           "(ns my.render) (defn forever [_] (while true))")
        sibling-result (promise)
        worker
        (future
          (deliver
           sibling-result
           (render/render
            :seon.render/ai
            {:seon.config/configuration {}
             ::render/trusted-renderers {}
             ::render/invoke-authored! (authored-door context holder 20)}
            {:seon.agent.ctx/name :canvas
             :seon.render/ai 'my.render/forever})))]
    (is (= :served (deref (future :served) 1000 :wedged))
        "the host can schedule independent work while SCI is bounded")
    (let [result (deref sibling-result 2000 ::wedged)]
      (is (not= ::wedged result))
      (is (= :budget (:seon.error/kind result)))
      (is (= :authored-render
             (get-in result [:seon.error/data ::guard/invocation-class]))))
    @worker))
