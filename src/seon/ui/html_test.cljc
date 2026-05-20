(ns seon.ui.html-test
  "Tests for the pod-side hiccup → HTML-string renderer (spec-05 A-3).

   Green criterion from spec-05 §10.2 A-3:
     `(seon.ui.html/->string [:div.foo \"bar\"])`
       returns `\"<div class=\\\"foo\\\">bar</div>\"`

   Run interactively via CLJS REPL on the running pod:
     (require 'seon.ui.html-test :reload)
     (cljs.test/run-tests 'seon.ui.html-test)"
  (:require
    [clojure.test :as t :refer [deftest is testing]]
    [seon.ui.html :as h]))

;; ============================================================
;; Tag shorthand — id + classes parsed from tag keyword.
;; ============================================================

(deftest tag-shorthand-class
  (is (= "<div class=\"foo\">bar</div>"
         (h/->string [:div.foo "bar"]))))

(deftest tag-shorthand-multiple-classes
  ;; Classes from the tag come BEFORE attr-map classes; both space-joined.
  (is (= "<div class=\"foo bar baz\">x</div>"
         (h/->string [:div.foo.bar.baz "x"]))))

(deftest tag-shorthand-id
  (is (= "<div id=\"main\">x</div>"
         (h/->string [:div#main "x"]))))

(deftest tag-shorthand-id-and-classes
  ;; Attribute order is sorted by key — id ahead of class.
  (is (= "<div class=\"foo bar\" id=\"main\">x</div>"
         (h/->string [:div#main.foo.bar "x"]))))

(deftest tag-symbol-and-string-accepted
  (is (= "<div>x</div>" (h/->string ['div "x"])))
  (is (= "<div>x</div>" (h/->string ["div" "x"]))))

;; ============================================================
;; Attribute rendering — escaping, true/false, omission.
;; ============================================================

(deftest attribute-value-escaped
  (is (= "<a href=\"/foo?x=1&amp;y=2\"></a>"
         (h/->string [:a {:href "/foo?x=1&y=2"}]))))

(deftest attribute-with-quote-escaped
  (is (= "<a title=\"&quot;quote&quot;\"></a>"
         (h/->string [:a {:title "\"quote\""}]))))

(deftest attribute-with-apostrophe-escaped
  (is (= "<a title=\"it&#39;s\"></a>"
         (h/->string [:a {:title "it's"}]))))

(deftest attribute-true-emits-bare
  (is (= "<input checked>"
         (h/->string [:input {:checked true}]))))

(deftest attribute-false-omitted
  (is (= "<input>" (h/->string [:input {:checked false}]))))

(deftest attribute-nil-omitted
  (is (= "<input>" (h/->string [:input {:checked nil}]))))

(deftest attribute-class-from-collection
  (is (= "<div class=\"foo bar\"></div>"
         (h/->string [:div {:class ["foo" "bar"]}]))))

(deftest attribute-class-merged-with-tag-shorthand
  (is (= "<div class=\"a b c\"></div>"
         (h/->string [:div.a {:class ["b" "c"]}]))))

(deftest attribute-style-from-map
  (is (= "<div style=\"color: red; font-size: 12px\"></div>"
         (h/->string [:div {:style {:color "red" :font-size "12px"}}]))))

(deftest attribute-style-from-string
  (is (= "<div style=\"color: red;\"></div>"
         (h/->string [:div {:style "color: red;"}]))))

(deftest attribute-order-is-stable
  ;; Same attrs in different map literal order produce identical HTML.
  (let [a (h/->string [:input {:type "text" :name "x" :id "i"}])
        b (h/->string [:input {:id "i" :type "text" :name "x"}])]
    (is (= a b))
    (is (= "<input id=\"i\" name=\"x\" type=\"text\">" a))))

(deftest attribute-data-and-extras
  ;; Datastar + arbitrary attributes flow through unchanged.
  (is (= "<button data-on-click__post=\"/chat\" title=\"go\">x</button>"
         (h/->string [:button {:data-on-click__post "/chat"
                               :title "go"}
                      "x"]))))

;; ============================================================
;; Text content escaping — the XSS-safe default.
;; ============================================================

(deftest text-content-escaped-by-default
  (is (= "<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>"
         (h/->string [:p "<script>alert(1)</script>"]))))

(deftest text-ampersand-escaped
  (is (= "<p>A &amp; B</p>"
         (h/->string [:p "A & B"]))))

(deftest non-string-content-escaped
  ;; Numbers and other non-string children are stringified, then escaped.
  (is (= "<span>42</span>" (h/->string [:span 42]))))

(deftest text-with-all-five-specials
  (is (= "<p>&amp;&lt;&gt;&quot;&#39;</p>"
         (h/->string [:p "&<>\"'"]))))

;; ============================================================
;; Raw escape hatch — for pre-serialized HTML / inline scripts.
;; ============================================================

(deftest raw-content-not-escaped
  (is (= "<script>console.log('hi');</script>"
         (h/->string [:script (h/raw "console.log('hi');")]))))

(deftest raw-content-renders-html-fragment
  (is (= "<div><strong>bold</strong></div>"
         (h/->string [:div (h/raw "<strong>bold</strong>")]))))

(deftest raw-predicate
  (is (true? (h/raw? (h/raw "x"))))
  (is (false? (h/raw? "x")))
  (is (false? (h/raw? nil))))

;; ============================================================
;; Void elements — self-closing, no </tag>.
;; ============================================================

(deftest void-element-img
  (is (= "<img alt=\"\" src=\"/a.png\">"
         (h/->string [:img {:src "/a.png" :alt ""}]))))

(deftest void-element-br
  (is (= "<br>" (h/->string [:br]))))

(deftest void-element-meta
  (is (= "<meta charset=\"utf-8\">"
         (h/->string [:meta {:charset "utf-8"}]))))

(deftest non-void-element-always-closes
  ;; Empty <div> still emits a closing tag so Datastar morph keeps
  ;; the element-id targetable.
  (is (= "<div></div>" (h/->string [:div])))
  (is (= "<div></div>" (h/->string [:div {}]))))

;; ============================================================
;; Children — nesting, nil elision, seq flattening.
;; ============================================================

(deftest nested-children
  (is (= "<div><p>hi</p><p>bye</p></div>"
         (h/->string [:div [:p "hi"] [:p "bye"]]))))

(deftest nil-children-elided
  (is (= "<div><p>hi</p></div>"
         (h/->string [:div nil [:p "hi"] nil]))))

(deftest false-children-elided
  (is (= "<div><p>hi</p></div>"
         (h/->string [:div false [:p "hi"] false]))))

(deftest seq-children-flattened
  ;; The classic `(for [x xs] [:li x])` idiom — seq inside vector.
  (is (= "<ul><li>a</li><li>b</li><li>c</li></ul>"
         (h/->string [:ul (for [x ["a" "b" "c"]] [:li x])]))))

(deftest nested-seqs-recurse
  (is (= "<ul><li>1</li><li>2</li><li>3</li></ul>"
         (h/->string [:ul (list [:li "1"] (list [:li "2"] [:li "3"]))]))))

;; ============================================================
;; Top-level shapes — seq, nil, raw, plain string.
;; ============================================================

(deftest top-level-nil-renders-empty
  (is (= "" (h/->string nil))))

(deftest top-level-false-renders-empty
  (is (= "" (h/->string false))))

(deftest top-level-string-escaped
  (is (= "&lt;hi&gt;" (h/->string "<hi>"))))

(deftest top-level-raw
  (is (= "<custom>" (h/->string (h/raw "<custom>")))))

(deftest top-level-seq-of-elements
  (is (= "<p>1</p><p>2</p>"
         (h/->string (list [:p "1"] [:p "2"])))))

;; ============================================================
;; A practical round-trip — the Datastar-style fragment a renderer
;; would produce for an agent tile.
;; ============================================================

(deftest realistic-agent-tile
  (let [tile [:div#agent-seon {:class "p-3 bg-base-900"}
              [:header {:class "flex gap-2"}
               [:span {:class "h-2 w-2 rounded-full bg-signal animate-pulse"}]
               [:span {:class "font-mono"} "seon"]]
              [:section
               (for [m [{:role :user :content "hi"}
                        {:role :assistant :content "<hello>"}]]
                 [:div [:span (name (:role m)) ": "]
                       [:span (:content m)]])]]]
    (is (= (str "<div class=\"p-3 bg-base-900\" id=\"agent-seon\">"
                "<header class=\"flex gap-2\">"
                "<span class=\"h-2 w-2 rounded-full bg-signal animate-pulse\"></span>"
                "<span class=\"font-mono\">alice</span>"
                "</header>"
                "<section>"
                "<div><span>user: </span><span>hi</span></div>"
                "<div><span>assistant: </span><span>&lt;hello&gt;</span></div>"
                "</section>"
                "</div>")
           (h/->string tile)))))

(deftest spec-green-criterion
  ;; Spec-05 §10.2 A-3 green: byte-for-byte.
  (is (= "<div class=\"foo\">bar</div>"
         (h/->string [:div.foo "bar"]))))
