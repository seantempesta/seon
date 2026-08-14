(ns seon.render.lint-test
  "Browser-free page judgement: every defect class fires, and none of them
  fires on the honest counterpart shape.

  The honesty regression is the important one. A transcript view may color and
  space what it shows, but `text-content` of the rendered block must equal the
  stored capture byte for byte — a view that reflows, inserts, or drops a
  character inside the captured content cannot pass it."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [seon.render.lint :as lint]))

(def ^:private capture
  "One stored capture's exact bytes, including its trailing newline."
  (str "my.agents.scout=> (db/pull db '[*] [:seon.cluster/name \"default\"])\n"
       "#:seon.cluster{:name \"default\", :agents [{:db/id 747}]}\n"
       "; two spaces here ->  <- and a \"quoted ( paren\"\n"))

(defn- highlighted
  "A presentation that adds spans and turn chrome but, INSIDE the addressed
  verbatim element, no characters of its own."
  [text]
  [:section {:class "seon-turn"}
   [:header {:class "seon-turn-header"}
    [:span {:class "seon-turn-ordinal"} "3"]
    [:span {:class "seon-turn-basis"} "t=536871133"]]
   [:pre {:id "surface-capture-3" :class "seon-turn-body"}
    [:code {:class "seon-print-root"}
     (into [:span {:class "seon-print-content"}]
           (map (fn [line] [:span {:class "seon-print-symbol"} line]))
           (interpose "\n" (string/split text #"\n" -1)))]]])

(defn- verbatim-text
  [rendered]
  (lint/text-content
   (lint/element-with-id {:seon.render.lint/hiccup rendered
                          :seon.render.lint/id "surface-capture-3"})))

(deftest rendered-turn-text-is-byte-identical-to-the-capture
  (is (= capture (verbatim-text (highlighted capture)))
      "spans and classes add no characters inside the verbatim element")
  (is (= capture
         (verbatim-text
          [:div (highlighted capture) [:img {:alt "chrome"}] [:br]]))
      "void-element children are elided exactly as the serializer elides them")
  (is (not= capture
            (verbatim-text
             (highlighted (string/replace capture "  " " "))))
      "a view that reflows the captured bytes fails this comparison")
  (is (not= capture (lint/text-content (highlighted capture)))
      "turn chrome lives strictly OUTSIDE the addressed element, which is why
       the falsifier addresses it rather than reading the whole turn"))

(deftest an-unaddressable-capture-region-refuses-rather-than-comparing-empty
  (let [refusal (lint/element-with-id
                 {:seon.render.lint/hiccup [:section [:pre "text"]]
                  :seon.render.lint/id "surface-capture-3"})]
    (is (= :seon.render.lint/absent-element (:seon.error/kind refusal)))
    (is (= "surface-capture-3"
           (get-in refusal [:seon.error/data
                            :seon.error/diagnostic-expected])))))

(deftest placeholder-nodes-are-findings-and-honest-blocks-are-not
  (let [page [[:article [:div {:class "seon-render-unavailable"}
                         "renderer unavailable"]]
              [:article [:div {:class "seon-data-panel"} "a real value"]]
              [:article [:div.seon-render-unavailable "renderer unavailable"]]]
        report (lint/check {:seon.render.lint/hiccup page})]
    (is (= {:seon.render.lint/renderer-unavailable 2}
           (:seon.render.lint/counts report))
        "both the attribute and the shorthand spelling of the class count")
    (is (= [[0 0] [2 0]]
           (mapv :seon.render.lint/path (:seon.render.lint/findings report))))
    (is (= #{"seon-render-unavailable"}
           (:seon.render.lint/placeholder-classes report))
        "the report states the policy that produced it"))
  (is (empty? (:seon.render.lint/findings
               (lint/check {:seon.render.lint/hiccup
                            [:div {:class "seon-data-panel"} "value"]})))))

(deftest a-fence-cut-mid-form-is-a-truncated-form
  (let [report (lint/check
                {:seon.render.lint/hiccup
                 [:pre [:code "(defn largest [rows]\n  (apply max-key :a"]]})
        [found] (:seon.render.lint/findings report)]
    (is (= :seon.render.lint/truncated-form
           (:seon.render.lint/defect found)))
    (is (= ["(" "("] (get-in found [:seon.render.lint/detail
                                    :seon.render.lint/unclosed]))
        "the `[rows]` vector closed; two lists are still open"))
  (is (empty? (:seon.render.lint/findings
               (lint/check {:seon.render.lint/hiccup
                            [:pre [:code "(defn f [x] (inc x))"]]})))
      "a complete form is not a finding")
  (is (empty? (:seon.render.lint/findings
               (lint/check {:seon.render.lint/hiccup
                            [:pre [:code "(str \"( [ {\" \\( \\[) ; ]\n"]]})))
      "strings, character literals, and comments carry no delimiters"))

(deftest an-unexpected-close-and-an-open-string-are-named
  (is (= ")" (:seon.render.lint/unexpected-close (lint/balance "(a))"))))
  (is (true? (:seon.render.lint/unterminated-string
              (lint/balance "(str \"open")))))

(deftest a-large-repeated-sibling-subtree-is-a-duplicated-block
  (let [block [:section {:class "seon-walk-unit"}
               [:h2 "Cluster default"]
               [:dl [:dt "name"] [:dd "default"]
                [:dt "agents"] [:dd "1"]
                [:dt "instructions"] [:dd "1"]]]
        report (lint/check {:seon.render.lint/hiccup [:main block block]})
        [found] (:seon.render.lint/findings report)]
    (is (= :seon.render.lint/duplicated-block (:seon.render.lint/defect found)))
    (is (= [0] (:seon.render.lint/path found)) "the first occurrence")
    (is (= 2 (get-in found [:seon.render.lint/detail
                            :seon.render.lint/repeats])))
    (is (empty? (:seon.render.lint/findings
                 (lint/check {:seon.render.lint/hiccup [:main block block]
                              :seon.render.lint/duplicate-node-floor 100})))
        "the size floor is a declared input, not a hidden constant"))
  (is (empty? (:seon.render.lint/findings
               (lint/check {:seon.render.lint/hiccup
                            [:ul [:li "a"] [:li "a"] [:li "a"]]})))
      "small repeated leaves are ordinary markup"))

(deftest a-printed-value-dumped-as-page-text-is-soup
  (let [soup (pr-str (into {} (map (fn [index]
                                     [(keyword "seon.example" (str "k" index))
                                      {:db/id (+ 700 index)}]))
                           (range 24)))
        report (lint/check {:seon.render.lint/hiccup [:div soup]})
        [found] (:seon.render.lint/findings report)]
    (is (< 240 (count soup)) "the fixture exceeds the declared floor")
    (is (= :seon.render.lint/pr-str-soup (:seon.render.lint/defect found)))
    (is (= (count soup) (get-in found [:seon.render.lint/detail
                                       :seon.render.lint/characters])))
    (is (empty? (:seon.render.lint/findings
                 (lint/check {:seon.render.lint/hiccup [:pre [:code soup]]})))
        "the same bytes inside a fence are a transcript, not soup"))
  (is (empty? (:seon.render.lint/findings
               (lint/check {:seon.render.lint/hiccup
                            [:p (apply str (repeat 40 "ordinary prose "))]})))
      "long prose is not a printed value"))

(deftest a-missing-required-region-is-louder-than-an-empty-one
  (let [report (lint/check {:seon.render.lint/hiccup
                            [:main [:div {:id "surface-transcript"} " "]]
                            :seon.render.lint/required-regions
                            #{"surface-transcript" "surface-plan"}})
        by-region (into {} (map (fn [finding]
                                  [(get-in finding [:seon.render.lint/detail
                                                    :seon.render.lint/region])
                                   finding]))
                        (:seon.render.lint/findings report))]
    (is (= #{"surface-transcript" "surface-plan"} (set (keys by-region))))
    (is (true? (get-in by-region ["surface-plan"
                                  :seon.render.lint/detail
                                  :seon.render.lint/region-absent]))
        "a region nobody rendered never reads as a region that is fine")
    (is (nil? (get-in by-region ["surface-transcript"
                                 :seon.render.lint/detail
                                 :seon.render.lint/region-absent])))
    (is (= #{"surface-transcript" "surface-plan"}
           (:seon.render.lint/required-regions report))))
  (is (empty? (:seon.render.lint/findings
               (lint/check {:seon.render.lint/hiccup
                            [:main [:div {:id "surface-transcript"} "content"]]
                            :seon.render.lint/required-regions
                            #{"surface-transcript"}})))))

(deftest a-report-states-its-own-size-and-policy
  (let [report (lint/check {:seon.render.lint/hiccup [:div "abc" [:span "de"]]})]
    (is (= 5 (:seon.render.lint/characters report)))
    (is (= 4 (:seon.render.lint/nodes report)))
    (is (= {:seon.render.lint/duplicate-node-floor 16
            :seon.render.lint/soup-character-floor 240}
           (:seon.render.lint/floors report)))
    (is (= #{"pre" "code"} (:seon.render.lint/fence-tags report)))
    (is (= {} (:seon.render.lint/counts report))
        "a clean page reports an empty tally, never a missing one")))
