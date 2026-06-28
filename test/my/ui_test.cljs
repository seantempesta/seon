(ns my.ui-test
  "my.ui is the canvas COMPOSITION surface — small dual-render pieces an
   agent stacks into a tile. Two contracts, both pure (no db, no async):

     1. EVERY helper returns the `:seon.render/html-response` envelope —
        `:seon.render/hiccup` (safelisted, keyword-head) AND
        `:seon.render/ai` (compact text). Instrumentation already validates
        the output shape on every call; these tests pin the MIRROR: the
        ai-text and the hiccup carry the SAME data, so they can't drift.

     2. `section` COMPOSES child envelopes — its hiccup contains each
        block's hiccup and its ai joins each block's ai. The mirror holds
        through nesting."
  (:require
    [cljs.test :refer [deftest is testing]]
    [clojure.string :as str]
    [my.ui :as ui]))

(deftest status-line-mirrors-label-and-value
  (let [r (ui/status-line {:my.ui/label "State"
                           :my.ui/value "green"
                           :my.ui/tone  :success})]
    (testing "ai is the compact `label: value` line"
      (is (= "State: green" (:seon.render/ai r))))
    (testing "hiccup is a keyword-head vector carrying both texts + tone class"
      (let [h (:seon.render/hiccup r)
            s (pr-str h)]
        (is (keyword? (first h)))
        (is (str/includes? s "State"))
        (is (str/includes? s "green"))
        (is (str/includes? s "text-success"))))))

(deftest status-line-untoned-defaults-to-cream
  (let [r (ui/status-line {:my.ui/label "X" :my.ui/value "y"})]
    (is (str/includes? (pr-str (:seon.render/hiccup r)) "text-text-100"))
    (is (= "X: y" (:seon.render/ai r)))))

(deftest kv-table-mirrors-every-row
  (let [r (ui/kv-table {:my.ui/title "Costs"
                        :my.ui/rows  [["Adobe" "$45"] ["Netflix" "$18"]]})
        s (pr-str (:seon.render/hiccup r))]
    (testing "ai lists title then each k: v"
      (is (= "Costs\nAdobe: $45\nNetflix: $18" (:seon.render/ai r))))
    (testing "hiccup renders a table row per pair"
      (is (str/includes? s ":table"))
      (is (str/includes? s "Adobe"))
      (is (str/includes? s "$45"))
      (is (str/includes? s "Netflix")))))

(deftest section-composes-children-faithfully
  (let [sl  (ui/status-line {:my.ui/label "Total" :my.ui/value "$101/mo"
                             :my.ui/tone :signal})
        kv  (ui/kv-table {:my.ui/rows [["Adobe" "$45"]]})
        sec (ui/section {:my.ui/title "Subscriptions"
                         :my.ui/blocks [sl kv]})
        ai  (:seon.render/ai sec)
        h   (:seon.render/hiccup sec)]
    (testing "composed ai = title + each child's ai, joined"
      (is (= (str "Subscriptions\n"
                  (:seon.render/ai sl) "\n"
                  (:seon.render/ai kv))
             ai)))
    (testing "composed hiccup embeds each child's hiccup verbatim"
      (is (some #{(:seon.render/hiccup sl)} h))
      (is (some #{(:seon.render/hiccup kv)} h))
      (is (str/includes? (pr-str h) "Subscriptions")))))
