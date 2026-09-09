(ns turn-schema-identity-probe-2026-09-09
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.walk :as walk]))

; Dated structural comparison against the accepted entering HEAD.
(doseq [family ["loop" "work"]]
  (let [before-ns (str "seon.cluster." family)
        after-ns (str "seon.turn." family)
        historical (shell/sh "git" "show"
                             (str "4584f8cd3:resources/seon/schemas/" before-ns ".edn"))
        _ (assert (zero? (:exit historical)) (:err historical))
        before (edn/read-string (:out historical))
        after (edn/read-string (slurp (str "resources/seon/schemas/" after-ns ".edn")))
        rename-value
        (fn [x]
          (cond
            (qualified-keyword? x)
            (if-let [target ({"seon.cluster.loop" "seon.turn.loop"
                              "seon.cluster.work" "seon.turn.work"} (namespace x))]
              (keyword target (name x)) x)
            (string? x)
            (-> x (str/replace ":seon.cluster.loop/" ":seon.turn.loop/")
                (str/replace ":seon.cluster.work/" ":seon.turn.work/"))
            :else x))]
    (assert (= (walk/postwalk rename-value before) after) family)
    (prn {:seon.test/family family :seon.test/declarations (count after)
          :seon.test/only-identities-changed true})))
