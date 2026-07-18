(ns seon.eval.require-test
  "Wave B4 (fix-everything-prd-2026-06-11 §3): `(:require [my.kb …])`
   in an agent eval must resolve for STORE-INDEXED, HOST-BUNDLED
   namespaces. The prompt renders core namespaces as code, so
   agents reasonably require them — but `my.kb` / `seon.db` / … are
   compiled into the host bundle (out/client/main.js), NOT into the
   bootstrap bundle's index, so shadow's `boot/load` threw
   `ns X not available` and the require died as a
   `:cljs/analysis-error`, at define time AND on every replay of a
   stored `(ns …)` row.

   The fix is `seon.eval/guarded-load`'s host-bundle fallback: when
   the bundle index misses but the namespace is already loaded
   ([[seon.eval/ns-loaded?]]), the load is
   answered with an empty `:js` source — the JS is already loaded by
   construction. Genuinely-absent namespaces still error legibly.

   Run via `bin/test-cljs`, or interactively:
     (require 'seon.eval.require-test :reload)
     (cljs.test/run-tests 'seon.eval.require-test)"
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [my.kb]
    [seon.config :as config]
    [seon.error]
    [seon.eval :as seval]
    [seon.repl :as repl]))

(def configuration (config/resolve-config-singleton {}))

(defn- err-chain
  "All `:seon.error/message` strings in a failed eval result's cause
   chain, joined with ` <- ` — the same view `seon.client`'s
   replay-failure warn surfaces."
  [r]
  (->> (iterate :seon.error/cause (:seon/error r))
       (take-while some?)
       (keep :seon.error/message)
       (str/join " <- ")))

(deftest ns-loaded?-separates-host-bundled-from-absent
  (is (true? (seval/ns-loaded? 'my.kb))
      "my.kb is compiled into this build")
  (is (true? (seval/ns-loaded? 'seon.eval))
      "core nses resolve the same way")
  (is (false? (seval/ns-loaded? 'no.such.namespace))
      "an absent namespace is not loaded"))

(deftest require-of-host-bundled-ns-succeeds-and-alias-resolves
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            (-> (seval/eval cs
                            (str "(ns scratch.require-b4 "
                                 "(:require [my.kb :as kb] "
                                 "[seon.error :as err]))")
                            {:seon.config/configuration configuration
                             :seon.eval/starting-ns 'cljs.user
                             :seon.eval/analyze-deps? false})
                (.then
                  (fn [r]
                    (is (:seon.eval/ok? r)
                        (str "require of host-bundled nses succeeds — "
                             (err-chain r)))
                    ;; The alias must actually WORK — wired from the ns
                    ;; form's parse, resolving at runtime through the
                    ;; munged globalThis path.
                    (seval/eval cs
                                "(err/->message (js/Error. \"b4-alias\"))"
                                {:seon.config/configuration configuration
                                 :seon.eval/starting-ns 'scratch.require-b4
                                 :seon.eval/analyze-deps? false})))
                (.then
                  (fn [r]
                    (is (:seon.eval/ok? r)
                        (str "alias call evals — " (err-chain r)))
                    (is (= "b4-alias" (:seon.eval/value r))
                        "aliased var resolves to the live host fn"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest bare-require-form-of-host-bundled-ns-succeeds
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            (-> (seval/eval cs "(require '[my.kb])"
                            {:seon.config/configuration configuration
                             :seon.eval/starting-ns 'cljs.user
                             :seon.eval/analyze-deps? false})
                (.then
                  (fn [r]
                    (is (:seon.eval/ok? r)
                        (str "bare (require '[my.kb]) succeeds — "
                             (err-chain r))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest require-of-genuinely-absent-ns-still-errors-legibly
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            ;; A genuinely-absent ns fails cljs.js analysis → :agent fault
            ;; (the agent's own require typo — classified by
            ;; seon.instrument/wrapper-fault, never gates, never crashes).
            (-> (seval/eval cs
                            (str "(ns scratch.require-absent "
                                 "(:require [no.such.namespace :as nope]))")
                            {:seon.config/configuration configuration
                             :seon.eval/starting-ns 'cljs.user
                             :seon.eval/analyze-deps? false})
                (.then
                  (fn [r]
                    (is (false? (:seon.eval/ok? r))
                        "an absent ns is still a real error")
                    (is (str/includes? (err-chain r) "no.such.namespace")
                        "the error names the missing namespace"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest supplied-program-loads-transitive-authored-source-without-a-db
  (async done
    (let [suffix (str (rand-int 1000000000))
          dep (symbol (str "my.authority.dep" suffix))
          target (symbol (str "my.authority.target" suffix))
          caller (symbol (str "my.authority.caller" suffix))
          sources
          (seval/authored-sources
           [{:seon.ns/name (keyword dep)
             :seon.ns/source (str "(ns " dep ")")
             :seon.ns/require-edges []
             :seon.fn/_ns
             [{:seon.fn/sym (symbol (str dep "/base"))
               :seon.fn/source "(defn base [] 41)"}]
             :seon.test/_ns []}
            {:seon.ns/name (keyword target)
             :seon.ns/source
             (str "(ns " target " (:require [" dep " :as dep]))")
             :seon.ns/require-edges []
             :seon.fn/_ns
             [{:seon.fn/sym (symbol (str target "/answer"))
               :seon.fn/source "(defn answer [] (inc (dep/base)))"}]
             :seon.test/_ns []}])]
      (-> (repl/ensure-bootstrap!)
          (.then
           (fn [compile-state]
             (seval/eval
              compile-state
              (str "(ns " caller " (:require [" target " :as target])) "
                   "(target/answer)")
              {:seon.config/configuration configuration
               :seon.eval/starting-ns 'cljs.user
               :seon.eval/analyze-deps? true
               :seon.eval/authored-sources sources})))
          (.then
           (fn [result]
             (is (:seon.eval/ok? result) (err-chain result))
             (is (= 42 (:seon.eval/value result)))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest absent-authored-dependency-does-not-fall-back-to-a-db
  (async done
    (let [suffix (str (rand-int 1000000000))
          missing (symbol (str "my.authority.missing" suffix))
          target (symbol (str "my.authority.incomplete" suffix))
          caller (symbol (str "my.authority.consumer" suffix))
          sources
          (seval/authored-sources
           [{:seon.ns/name (keyword target)
             :seon.ns/source
             (str "(ns " target " (:require [" missing " :as missing]))")
             :seon.ns/require-edges []
             :seon.fn/_ns []
             :seon.test/_ns []}])]
      (-> (repl/ensure-bootstrap!)
          (.then
           (fn [compile-state]
             (seval/eval
              compile-state
              (str "(ns " caller " (:require [" target " :as target]))")
              {:seon.config/configuration configuration
               :seon.eval/starting-ns 'cljs.user
               :seon.eval/analyze-deps? true
               :seon.eval/authored-sources sources})))
          (.then
           (fn [result]
             (is (false? (:seon.eval/ok? result)))
             (is (str/includes? (err-chain result) (str missing))
                 (err-chain result))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
