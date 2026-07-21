(ns seon.host-shared-var-writer-test
  "Shared SCI base and registry vars are read-only to agent evals."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.host :as host]
            [seon.host.context :as context]
            [seon.host.eval :as host.eval]
            [seon.host.graduate :as graduate]))

(defn- unconnected-writer []
  (context/writer-session
   {::context/writer-socket-path "tmp/unused-shared-var-test.sock"
    ::context/database-name "shared-var-test"}))

(defn- build-base []
  (context/build-base! (unconnected-writer)))

(defn- eval-form!
  [ctx home-ns source]
  ((var-get #'host.eval/eval-form!)
   {:seon.host.session/interrupt-lock (Object.)
    :seon.host.session/interrupt-fired? (atom false)
    :seon.host.session/worker-phase (atom :idle)}
   ctx home-ns source))

(defn- refusal?
  [envelope home-ns]
  (let [error (:seon/error envelope)]
    (and (map? error)
         (str/includes? (:seon.error/message error) (str home-ns)))))

(deftest shared-base-and-registry-vars-refuse-agent-root-mutation
  (let [base (build-base)
        attacker (context/fork-context base)
        bystander (context/fork-context base)
        home-ns 'my.agent.attacker]
    (is (true?
         (sci/eval-string*
          attacker
          (str "(every? (fn [v] (:sci/built-in (meta v))) "
               "(for [n (all-ns) [_ v] (ns-interns n) "
               ":when (var? v)] v))")))
        "every var present in the shared base is stamped")
    (doseq [ctx [attacker bystander]]
      (sci/eval-string* ctx "(require 'seon.ai.tokens)"))
    (let [original (sci/eval-string*
                    bystander "(seon.ai.tokens/estimate-chars 20)")
          refusal (eval-form!
                   attacker home-ns
                   (str "(in-ns 'seon.ai.tokens) "
                        "(defn estimate-chars [& _] :evil)"))]
      (is (refusal? refusal home-ns) (pr-str refusal))
      (is (= original
             (sci/eval-string*
              bystander "(seon.ai.tokens/estimate-chars 20)")))
      (is (= original
             (sci/eval-string*
              attacker "(seon.ai.tokens/estimate-chars 20)"))))
    (let [refusal
          (eval-form!
           attacker home-ns
           "(alter-var-root #'clojure.core/reduce (constantly nil))")]
      (is (refusal? refusal home-ns) (pr-str refusal))
      (is (= 6 (sci/eval-string* bystander "(reduce + [1 2 3])"))))))

(deftest privileged-registry-upgrades-reach-already-required-contexts
  (let [base (build-base)
        ctx (context/fork-context base)
        register!
        (fn [implementation]
          (context/register-host-wrappers!
           {::context/registry (::context/registry base)
            ::context/lib 'seon.shared-upgrade
            ::context/wrappers
            {'answer {::context/wrapper-fn implementation
                      ::context/arglists '([x])
                      ::context/doc "Return the shared upgrade probe."}}}))]
    (register! (fn [x] [:v1 x]))
    (is (= [:v1 1]
           (sci/eval-string*
            ctx "(require '[seon.shared-upgrade :as shared])
                 (shared/answer 1)")))
    (register! (fn [x] [:v2 x]))
    (is (= [:v2 1] (sci/eval-string* ctx "(shared/answer 1)")))
    (is (true?
         (sci/eval-string*
          ctx "(:sci/built-in (meta #'seon.shared-upgrade/answer))")))))

(deftest graduation-keeps-agent-authored-corpus-vars-writable
  (let [base (build-base)
        registry (::context/registry base)
        caller (context/fork-context base)
        source (str "(defn answer\n"
                    "  {:malli/schema [:=> [:cat :int] :int]\n"
                    "   :test (fn [] (assert (= 42 (answer 41))))}\n"
                    "  [x]\n"
                    "  (inc x))")
        row {:seon.fn/sym "my.agent.shared-graduation/answer"
             :seon.fn/source source
             :seon.fn/source-fingerprint (graduate/fingerprint source)
             :seon.fn/execution-tier :nursery
             :seon.fn/spec "[:=> [:cat :int] :int]"}
        request {::context/base base
                 ::context/registry registry
                 ::graduate/function-row row
                 ::graduate/contexts [caller]}
        nursery (graduate/install-nursery! request)]
    (is (::graduate/ok? nursery) (pr-str nursery))
    (is (= 42
           (sci/eval-string*
            caller "(require '[my.agent.shared-graduation :as shared])
                    (shared/answer 41)")))
    (is (not
         (sci/eval-string*
          caller
          "(:sci/built-in (meta #'my.agent.shared-graduation/answer))")))
    (let [graduated
          (with-redefs [context/transact-writer!
                        (fn [_writer _transaction-data]
                          {:seon.db/ok? true})]
            (graduate/graduate!
             (assoc request ::context/writer (unconnected-writer))))]
      (is (::graduate/ok? graduated) (pr-str graduated))
      (is (= :graduated (::graduate/tier graduated)))
      (is (= 42 (sci/eval-string* caller "(shared/answer 41)")))
      (let [edited
            (eval-form!
             caller 'my.agent.shared-graduation
             (str "(in-ns 'my.agent.shared-graduation) "
                  "(defn answer [x] (+ x 2))"))]
        (is (:seon.eval/ok? edited) (pr-str edited))
        (is (= 43 (sci/eval-string* caller "(shared/answer 41)")))))))

(deftest agent-owned-vars-remain-redefinable
  (let [base (build-base)
        ctx (context/fork-context base)]
    (context/ensure-context-ns! ctx 'my.agent.owner)
    (is (= 2
           (sci/eval-string*
            ctx "(in-ns 'my.agent.owner)
                 (defn g [] 1)
                 (defn g [] 2)
                 (g)")))
    (is (not
         (sci/eval-string*
          ctx "(:sci/built-in (meta #'my.agent.owner/g))")))))

(deftest post-boot-registration-is-callable-stamped-and-read-only
  (let [base (build-base)
        attacker (context/fork-context base)
        bystander (context/fork-context base)
        home-ns 'my.agent.post-boot]
    (context/register-host-wrappers!
     {::context/registry (::context/registry base)
      ::context/lib 'seon.post-boot
      ::context/wrappers
      {'answer {::context/wrapper-fn (fn [x] [:original x])
                ::context/arglists '([x])
                ::context/doc "Return the post-boot probe."}}})
    (doseq [ctx [attacker bystander]]
      (is (= [:original 7]
             (sci/eval-string*
              ctx "(require '[seon.post-boot :as post]) (post/answer 7)"))))
    (is (true?
         (sci/eval-string*
          attacker "(:sci/built-in (meta #'seon.post-boot/answer))")))
    (let [refusal
          (eval-form!
           attacker home-ns
           "(in-ns 'seon.post-boot) (defn answer [& _] :evil)")]
      (is (refusal? refusal home-ns) (pr-str refusal))
      (is (= [:original 8]
             (sci/eval-string* attacker "(seon.post-boot/answer 8)")))
      (is (= [:original 9]
             (sci/eval-string* bystander "(seon.post-boot/answer 9)"))))))
