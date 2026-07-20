(ns seon.agent.ctx.ns-name
  "Pure structural namespace-name selection rules."
  (:require [clojure.string :as str]))

(defn hidden-ns-name?
  "True when a namespace is `*.internal` or a child of one."
  {:malli/schema [:=> [:cat [:or :string :symbol]] :boolean]}
  [ns-name]
  (let [s (str ns-name)]
    (boolean (or (str/ends-with? s ".internal")
                 (str/includes? s ".internal.")))))

(defn test-ns-name?
  "True when a namespace name ends in `-test`."
  {:malli/schema [:=> [:cat [:or :string :symbol]] :boolean]}
  [ns-name]
  (str/ends-with? (str ns-name) "-test"))

(defn included-ns?
  "True when a namespace may enter ordinary rendered sections."
  {:malli/schema [:=> [:cat [:or :string :symbol]] :boolean]}
  [ns-name]
  (boolean (and (not (hidden-ns-name? ns-name))
                (not (test-ns-name? ns-name)))))
