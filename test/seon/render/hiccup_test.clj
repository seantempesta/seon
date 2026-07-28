(ns seon.render.hiccup-test
  "Sealed acceptance draft for the ONE hiccup serializer.

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27, N4 package 1). The
  implementation lane makes these green by implementing
  `seon.render.hiccup` ONLY — schemas and tests are byte-sealed, and
  friction is reported rather than resolved by weakening.

  PROPERTIES FIRST, and the reason is the failure class: a serializer's
  bugs are not in the cases anybody thinks to write down. Escaping,
  determinism, well-formedness and totality are STANDING properties over
  the generated grammar; the examples below teach the shorthand and the
  attribute rules and prove nothing the properties do not.

  Seeds are fixed and every generated input is a function of its seed
  (`research/testing-story-2026-07-27.md`), so a failure replays exactly
  and the complete `quick-check` result — including the shrunk value —
  rides in the failure message. Isolation is free: every function here
  is pure, so a trial's only state is the value it built."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.render.hiccup :as hiccup]
            [seon.schema]))

(def ^:private void-tags
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link"
    "meta" "param" "source" "track" "wbr"})

(defn- check!
  [label result]
  (is (true? (:result result))
      (str label " failed: " (pr-str result))))

;;; ---------------------------------------------------------------------------
;;; The grammar is total, and the generator is honest
;;; ---------------------------------------------------------------------------

(deftest the-grammar-never-throws-for-any-value
  ;; `hiccup?` is the admission predicate on a path that carries
  ;; agent-authored values. A predicate that threw would turn a bad
  ;; block into a broken page, which is the one thing the isolation
  ;; contract forbids.
  (check!
   "hiccup? totality"
   (tc/quick-check
    300
    (prop/for-all [value gen/any-printable]
      (boolean? (hiccup/hiccup? value)))
    :seed 202607280101)))

(deftest the-generator-is-honest
  ;; Malli never validates a generator override. A dishonest one would
  ;; green-wash every property below, so this is the property the
  ;; others rest on.
  (check!
   "generator honesty"
   (tc/quick-check
    200
    (prop/for-all [value hiccup/hiccup-generator]
      (true? (hiccup/hiccup? value)))
    :seed 202607280102)))

(deftest the-grammar-refuses-the-two-mistakes-it-exists-to-catch
  (testing "a bare map in child position — a sibling key put inside the hiccup"
    (is (false? (hiccup/hiccup? [:div {:class "a"} {:seon.render/ai "oops"}])))
    (is (false? (hiccup/hiccup? [:div nil {:a 1}]))))
  (testing "a vector whose head is not a tag — children returned unwrapped"
    (is (false? (hiccup/hiccup? [[:p "a"] [:p "b"]])))
    (is (false? (hiccup/hiccup? [1 2 3]))))
  (testing "but a SEQ of hiccup is a fragment, because (for …) must compose"
    (is (true? (hiccup/hiccup? (list [:p "a"] [:p "b"]))))
    (is (true? (hiccup/hiccup? [:ul (map (fn [x] [:li x]) [1 2])])))))

(deftest an-attribute-map-is-not-a-child
  ;; The second position is attributes; the same map deeper is a child
  ;; and therefore a refusal. Without this the grammar would accept the
  ;; exact mistake it exists to catch.
  (is (true? (hiccup/hiccup? [:div {:class "a"} "text"])))
  (is (false? (hiccup/hiccup? [:div "text" {:class "a"}]))))

(deftest a-raw-value-is-content-and-never-attributes
  ;; `raw` is a record, so `map?` is true of it. A serializer that
  ;; detected attributes with `map?` would swallow the first raw child
  ;; of every element.
  (is (true? (hiccup/raw? (hiccup/raw "<b>x</b>"))))
  (is (false? (hiccup/raw? {:class "a"})))
  (is (true? (hiccup/hiccup? [:div (hiccup/raw "<b>x</b>") "after"]))))

;;; ---------------------------------------------------------------------------
;;; Serialization is total, deterministic, and escapes
;;; ---------------------------------------------------------------------------

(deftest serialization-is-total-over-the-grammar
  (check!
   "->string totality"
   (tc/quick-check
    200
    (prop/for-all [value hiccup/hiccup-generator]
      (string? (hiccup/->string value)))
    :seed 202607280103)))

(deftest a-refused-value-serializes-to-nothing-rather-than-to-edn
  ;; Arriving here with a refused value is OUR bug — the caller admits
  ;; first. Emitting nothing is the one answer that cannot leak raw EDN
  ;; into a human's page, which is exactly what the quarry's `str`
  ;; fallback did.
  (doseq [refused [{:seon.error/kind :a/b} [1 2 3] #{:a}]]
    (is (= "" (hiccup/->string refused))
        (str "a refused value must serialize to nothing: " (pr-str refused)))))

(deftest serialization-is-deterministic
  ;; Byte identity is a DESIGN PROPERTY, not a convenience: equality
  ;; suppression, the SSE diff, and "the same value in a replacement
  ;; process yields the same bytes" all rest on it.
  (check!
   "determinism"
   (tc/quick-check
    200
    (prop/for-all [value hiccup/hiccup-generator]
      (= (hiccup/->string value) (hiccup/->string value)))
    :seed 202607280104)))

(deftest attribute-order-does-not-change-the-bytes
  ;; The same attributes written in a different literal order must
  ;; produce identical output, or a re-render that only reordered a map
  ;; would send a morph.
  (let [forward [:div {:class "a" :id "b" :title "c"} "x"]
        reverse [:div (into (sorted-map-by (fn [a b] (compare b a)))
                            {:class "a" :id "b" :title "c"})
                 "x"]]
    (is (= (hiccup/->string forward) (hiccup/->string reverse)))))

(deftest text-is-escaped-and-only-raw-escapes-that
  (check!
   "no unescaped angle bracket from generated text"
   (tc/quick-check
    200
    (prop/for-all [text (gen/one-of [gen/string-ascii
                                     (gen/return "<script>alert(1)</script>")
                                     (gen/return "a & b \" c ' d")])]
      (let [out (hiccup/->string [:p text])]
        ;; the only `<` and `>` in the output are the two the element
        ;; itself contributes
        (and (= 2 (count (filter #{\<} out)))
             (= 2 (count (filter #{\>} out))))))
    :seed 202607280105))
  (testing "the five characters, by name"
    (is (= "<p>&amp; &lt; &gt; &quot; &#39;</p>"
           (hiccup/->string [:p "& < > \" '"]))))
  (testing "raw is the one exit, and it is explicit"
    (is (= "<div><b>bold</b></div>"
           (hiccup/->string [:div (hiccup/raw "<b>bold</b>")]))))
  (testing "attribute values are escaped too"
    (is (str/includes? (hiccup/->string [:div {:title "a \" b"} ""])
                       "&quot;"))))

(deftest every-non-void-element-closes-and-every-void-element-does-not
  ;; Well-formedness as a counted property rather than a parser: the
  ;; number of closing tags equals the number of non-void elements in
  ;; the tree. A morph target that failed to close would swallow its
  ;; siblings in the browser.
  (letfn [(non-void-count [value]
            (cond
              (hiccup/raw? value) 0
              (vector? value)
              (let [[head & body] value
                    tag (name head)
                    children (if (and (map? (first body))
                                      (not (hiccup/raw? (first body))))
                               (rest body)
                               body)]
                (if (void-tags tag)
                  0
                  (inc (reduce + 0 (map non-void-count children)))))
              (and (sequential? value) (not (vector? value)))
              (reduce + 0 (map non-void-count value))
              :else 0))]
    (check!
     "closing tags balance"
     (tc/quick-check
      200
      (prop/for-all [value hiccup/hiccup-generator]
        (let [out (hiccup/->string value)]
          (= (non-void-count value)
             (count (re-seq #"</" out)))))
      :seed 202607280106))
    (testing "a void element emits no closing tag and drops children"
      (is (= "<br>" (hiccup/->string [:br])))
      (is (= "<input disabled>" (hiccup/->string [:input {:disabled true}])))
      (is (= "<br>" (hiccup/->string [:br "children are the author's error"]))))
    (testing "an empty non-void element still closes, so its id stays a target"
      (is (= "<div id=\"surface-x\"></div>"
             (hiccup/->string [:div {:id "surface-x"}]))))))

;;; ---------------------------------------------------------------------------
;;; The attribute rules — examples, because they are a vocabulary
;;; ---------------------------------------------------------------------------

(deftest tag-shorthand
  (is (= {:seon.render.hiccup/tag "div"
          :seon.render.hiccup/id "main"
          :seon.render.hiccup/classes ["card" "wide"]}
         (hiccup/shorthand :div.card.wide#main)))
  (testing "no id yields an ABSENT key, never a stored nil"
    (is (= {:seon.render.hiccup/tag "div"
            :seon.render.hiccup/classes ["card"]}
           (hiccup/shorthand :div.card))))
  (testing "no shorthand at all"
    (is (= {:seon.render.hiccup/tag "span"
            :seon.render.hiccup/classes []}
           (hiccup/shorthand :span))))
  (testing "a head the grammar refuses is a value, never nil and never a throw"
    (doseq [refused [42 nil {:a 1}]]
      (is (seon.schema/valid-candidate-value?
           :seon.error/value (hiccup/shorthand refused))
          (str "a refused head must name itself: " (pr-str refused))))))

(deftest classes-merge-shorthand-first
  (is (= "<div class=\"card flex gap-2\"></div>"
         (hiccup/->string [:div.card {:class "flex gap-2"}])))
  (testing "a collection joins, and nils in it drop"
    (is (= "<div class=\"card flex gap-2\"></div>"
           (hiccup/->string [:div.card {:class ["flex" nil "gap-2"]}])))))

(deftest an-attribute-map-id-wins-over-the-shorthand
  (is (= "<div id=\"explicit\"></div>"
         (hiccup/->string [:div#shorthand {:id "explicit"}]))))

(deftest boolean-attributes
  (is (= "<input disabled>" (hiccup/->string [:input {:disabled true}])))
  (testing "false and nil omit the attribute entirely — never disabled=\"false\""
    (is (= "<input>" (hiccup/->string [:input {:disabled false :hidden nil}])))))

(deftest style-maps-normalize-react-camelcase
  ;; Models carry React priors and write `:fontSize` forever. Without
  ;; normalization those render verbatim and are silently dead in the
  ;; browser — a silent failure, which is the class we refuse.
  (is (= "<div style=\"color: red; font-size: 12px\"></div>"
         (hiccup/->string [:div {:style {:fontSize "12px" :color "red"}}])))
  (testing "a leading capital is a vendor prefix"
    (is (str/includes? (hiccup/->string [:div {:style {:WebkitMask "x"}}])
                       "-webkit-mask: x")))
  (testing "a custom property passes through untouched"
    (is (str/includes? (hiccup/->string [:div {:style {:--i "3"}}]) "--i: 3")))
  (testing "a string style passes through"
    (is (= "<div style=\"top:0\"></div>"
           (hiccup/->string [:div {:style "top:0"}])))))

(deftest datastar-attributes-are-ordinary-attributes
  ;; Nothing here knows what Datastar is, and that is the point: the
  ;; serializer restricts no attribute name, so the live-update
  ;; vocabulary needs no serializer change.
  (is (= "<button data-on:click=\"$selected = &#39;canvas&#39;\"></button>"
         (hiccup/->string [:button {:data-on:click "$selected = 'canvas'"}]))))

(deftest children-elide-and-fragments-flatten
  (is (= "<ul><li>1</li><li>2</li></ul>"
         (hiccup/->string [:ul (map (fn [x] [:li x]) [1 2])])))
  (is (= "<div>kept</div>"
         (hiccup/->string [:div nil false "kept"]))))

(deftest the-serializer-emits-no-doctype
  ;; A shell prepends it. Emitting one here would make every nested
  ;; render a document.
  (is (not (str/includes? (hiccup/->string [:html [:body "x"]]) "DOCTYPE"))))
