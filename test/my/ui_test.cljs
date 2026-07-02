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

(deftest badge-mirrors-label-and-tone
  (let [r (ui/badge {:my.ui/label "passing" :my.ui/tone :success})
        s (pr-str (:seon.render/hiccup r))]
    (testing "ai is `[tone] label`"
      (is (= "[success] passing" (:seon.render/ai r))))
    (testing "hiccup is a keyword-head pill carrying label + tone class"
      (is (keyword? (first (:seon.render/hiccup r))))
      (is (str/includes? s "passing"))
      (is (str/includes? s "text-success"))
      (is (str/includes? s "rounded")))))

(deftest badge-defaults-to-info
  (let [r (ui/badge {:my.ui/label "idle"})]
    (is (= "[info] idle" (:seon.render/ai r)))
    (is (str/includes? (pr-str (:seon.render/hiccup r)) "text-info"))))

(deftest bullets-mirrors-every-item
  (let [r (ui/bullets {:my.ui/title "Next" :my.ui/items ["deploy" "verify"]})
        s (pr-str (:seon.render/hiccup r))]
    (testing "ai lists title then `- item` lines"
      (is (= "Next\n- deploy\n- verify" (:seon.render/ai r))))
    (testing "hiccup renders a semantic list item per element"
      (is (str/includes? s ":ul"))
      (is (str/includes? s ":li"))
      (is (str/includes? s "deploy"))
      (is (str/includes? s "verify")))))

(deftest progress-mirrors-ratio-and-percent
  (let [r (ui/progress {:my.ui/label "Steps" :my.ui/current 7 :my.ui/total 10})
        s (pr-str (:seon.render/hiccup r))]
    (testing "ai is `label: current/total (pct%)`"
      (is (= "Steps: 7/10 (70%)" (:seon.render/ai r))))
    (testing "hiccup carries the same ratio text + a fill width"
      (is (str/includes? s "7/10 (70%)"))
      (is (str/includes? s "70%")))))

(deftest progress-guards-zero-total
  (let [r (ui/progress {:my.ui/label "Empty" :my.ui/current 0 :my.ui/total 0})]
    (is (= "Empty: 0/0 (0%)" (:seon.render/ai r)))))

(deftest table-mirrors-rows-aligned
  (let [r (ui/table {:my.ui/columns [[:name "Name"] [:cost "Cost"]]
                     :my.ui/table-data [{:name "Adobe" :cost "$45"}
                                        {:name "Netflix" :cost "$18"}]})
        s (pr-str (:seon.render/hiccup r))]
    (testing "ai is monospace-aligned header + data rows, same info"
      (is (= "Name     Cost\nAdobe    $45\nNetflix  $18"
             (:seon.render/ai r))))
    (testing "hiccup renders a table with a header + a row per map"
      (is (str/includes? s ":table"))
      (is (str/includes? s ":th"))
      (is (str/includes? s "Name"))
      (is (str/includes? s "Adobe"))
      (is (str/includes? s "$18")))))

(deftest section-holds-mixed-compose-pieces
  (testing "a section composes badge + table + progress (the COMPOSABLE claim)"
    (let [b   (ui/badge {:my.ui/label "live" :my.ui/tone :success})
          tbl (ui/table {:my.ui/columns [[:k "K"] [:v "V"]]
                         :my.ui/table-data [{:k "a" :v "1"}]})
          pg  (ui/progress {:my.ui/label "Done" :my.ui/current 1 :my.ui/total 2})
          sec (ui/section {:my.ui/title "Mixed" :my.ui/blocks [b tbl pg]})]
      (is (= (str "Mixed\n"
                  (:seon.render/ai b) "\n"
                  (:seon.render/ai tbl) "\n"
                  (:seon.render/ai pg))
             (:seon.render/ai sec)))
      (is (some #{(:seon.render/hiccup b)} (:seon.render/hiccup sec)))
      (is (some #{(:seon.render/hiccup pg)} (:seon.render/hiccup sec))))))

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
