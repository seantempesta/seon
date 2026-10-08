(ns seon.render.value-law-test
  "Laws of the value printer's selection: the laid-out text stays inside the
  charge and the budget, and every string cut continues exactly."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.print :as print]
            [seon.render :as render]
            [seon.render.value :as value]
            [seon.test-support :as support]))

(defn- prepared
  "`raw` prepared under the agent profile at `budget` tokens."
  {:malli/schema [:=> [:cat :seon.schema/value :int] :map]}
  [raw budget]
  (value/prepare {:seon.agent/id "root"
                  :seon.render.call/id [:seon.render.value-law-test/floor]
                  :seon.render/value raw
                  :seon.sci.admit/caps (config/result-caps config/defaults)
                  :seon.render/profile (assoc (render/agent-render-profile config/defaults)
                                              :seon.render.profile/token-budget budget)
                  :seon.render.value/root 'result/e0123456789ab}))

(defn- cuts
  "The elided nodes of a prepared projection's tree."
  {:malli/schema [:=> [:cat :map] [:sequential :map]]}
  [projection]
  (filter #(and (map? %) (= :seon.print/elided (:seon.print/face %)))
          (tree-seq coll? seq (:seon.render.value/tree projection))))

(defn- lawful?
  "True when `raw` printed under `budget` has no diagnostic, fits the budget,
  continues every string cut exactly and, uncut, reads back as `raw`."
  {:malli/schema [:=> [:cat :seon.schema/value :int] :boolean]}
  [raw budget]
  (let [projection (prepared raw budget)
        text (:seon.render.value/text projection)
        cut (cuts projection)]
    (boolean
     (and (empty? (:seon.render.value/diagnostics projection))
          (<= (tokens/estimate text) budget)
          (every? (fn [c]
                    (let [s (print/value-at raw (:seon.render.data/path c))]
                      (or (not (:seon.print/prefix c))
                          (= s (str (:seon.print/prefix c) (subs s (:seon.render.data/next-offset c)))))))
                  cut)
          (or (seq cut) (= raw (edn/read-string text)))))))

(def ^:private line-gen
  (gen/fmap str/join (gen/vector (gen/elements [\a \space \" \\ \tab \return \( \ü \日]) 0 90)))

(def ^:private value-gen
  (gen/recursive-gen
   (fn [inner] (gen/one-of [(gen/vector inner 0 12) (gen/list inner) (gen/map gen/keyword inner {:max-elements 8})]))
   (gen/one-of [gen/small-integer gen/keyword-ns (gen/return nil)
                (gen/fmap #(str/join "\n" %) (gen/vector line-gen 0 40))])))

(deftest the-text-stays-inside-its-charge-and-budget
  ;; A sequence's collapsed tail was folded into a tail cut longer than the
  ;; members it replaced, so the text outgrew its charge and printed whole.
  (is (lawful? [(list 0 [(apply str "aaaaaa" (repeat 82 "a"))])
                {:A (apply str (repeat 66 "\"a")) :B {:A nil :B 0}}]
               70))
  (support/assert-check!
   (tc/quick-check 150 (prop/for-all [raw value-gen budget (gen/elements [60 200 800 1500])] (lawful? raw budget))
                   :seed 202610071 :max-size 40)
   "A printed value broke its charge, budget or a cut's continuation."))

(deftest escaped-strings-stay-printable-under-a-small-budget
  ;; A string is admitted whole only when its escaped literal costs no more
  ;; than its cut; comparing raw length refused these values outright.
  (doseq [raw [(.repeat "\"" 64) (.repeat "\\" 64) (.repeat "\t" 64) [(.repeat "\"" 64)]]]
    (let [projection (prepared raw 40)
          [cut] (cuts projection)
          s (if (vector? raw) (first raw) raw)]
      (is (string? (:seon.render.value/text projection)) "the value prints rather than refusing the budget")
      (is (= s (str (:seon.print/prefix cut) (subs s (:seon.render.data/next-offset cut))))
          "the cut continues exactly"))))
