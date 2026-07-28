(ns seon.render.data-test
  "The `/data` drill: paths, windows, and the property that matters —
  the cost of a page does not depend on the size of the value.

  A pager is easy to write and easy to write WRONG in one specific way:
  an unstable order shows a row twice and never shows another. So the
  ordering property is generative and the paging property is stated as
  a partition — every entry appears exactly once across all pages."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.render.data :as data]
            [seon.render.hiccup :as hiccup]
            [seon.schema]))

(def ^:private caps
  {:seon.config.eval.result/max-depth 12
   :seon.config.eval.result/max-collection 4
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

(defn- cursor
  ([] (cursor [] 0))
  ([path offset] {:seon.render.data/path path
                  :seon.render.data/offset offset}))

(defn- check!
  [label result]
  (is (true? (:result result)) (str label " failed: " (pr-str result))))

;;; ---------------------------------------------------------------------------
;;; The cursor
;;; ---------------------------------------------------------------------------

(deftest a-cursor-parses-from-ordinary-query-parameters
  (is (= (cursor [:a 3 "b"] 12)
         (data/parse-cursor "[:a 3 \"b\"]" "12")))
  (testing "a drilled position is a link, so its path survives a round trip"
    (let [path [:seon.error/data 2 "key"]]
      (is (= path (:seon.render.data/path
                   (data/parse-cursor (pr-str path) "0")))))))

(deftest a-broken-link-shows-the-root-rather-than-an-error-page
  ;; Totality at the URL boundary: a mangled cursor is somebody's stale
  ;; bookmark, not a fault.
  (doseq [[path offset] [[nil nil] ["" ""] ["{not a vector}" "-5"]
                         ["((((" "abc"] ["\"a string\"" "9999999999999999999999"]]]
    (let [parsed (data/parse-cursor path offset)]
      (is (vector? (:seon.render.data/path parsed)))
      (is (nat-int? (:seon.render.data/offset parsed))
          (str "must be a usable cursor: " (pr-str [path offset]))))))

(deftest parsing-is-total-over-arbitrary-strings
  (check!
   "cursor parsing totality"
   (tc/quick-check
    300
    (prop/for-all [path (gen/one-of [gen/string-ascii (gen/return nil)])
                   offset (gen/one-of [gen/string-ascii (gen/return nil)])]
      (seon.schema/valid-candidate-value?
       :seon.render.data/cursor (data/parse-cursor path offset)))
    :seed 202607280401)))

;;; ---------------------------------------------------------------------------
;;; Navigating
;;; ---------------------------------------------------------------------------

(def ^:private value
  {:agents [{:id "root" :runs [1 2 3]} {:id "b"}]
   :counts {:a 1 :b 2}
   :tags #{:x :y}
   :note "hello"})

(deftest a-path-reaches-into-maps-vectors-and-sets
  (is (= "root" (:seon.render.data/value
                 (data/at value (cursor [:agents 0 :id] 0)))))
  (is (= 2 (:seon.render.data/value (data/at value (cursor [:counts :b] 0)))))
  (is (= :x (:seon.render.data/value (data/at value (cursor [:tags :x] 0)))))
  (testing "the empty path is the whole value"
    (is (= value (:seon.render.data/value (data/at value (cursor)))))))

(deftest a-path-that-leaves-the-value-is-a-refusal-naming-where
  ;; Never nil: nil is also a legitimate value to have navigated TO, and
  ;; the two must not look the same.
  (let [refused (data/at value (cursor [:agents 99] 0))]
    (is (seon.schema/valid-candidate-value? :seon.error/value refused))
    (is (= :seon.render.data/no-such-path (:seon.error/kind refused))))
  (testing "and navigating to a real nil is a success carrying nil"
    (let [found (data/at {:present nil} (cursor [:present] 0))]
      (is (nil? (:seon.render.data/value found)))
      (is (nil? (:seon.error/kind found))))))

;;; ---------------------------------------------------------------------------
;;; The window
;;; ---------------------------------------------------------------------------

(deftest a-window-is-a-page-plus-where-to-resume
  (let [page (data/window (vec (range 10)) (cursor [] 0) caps)]
    (is (= 4 (count (:seon.render.data/entries page))))
    (is (= 10 (:seon.render.data/total page)) "the honest count")
    (is (= 4 (:seon.render.data/next-offset page)))
    (is (nil? (:seon.render.data/previous-offset page))
        "key presence answers 'is there a page that way', not arithmetic")))

(deftest the-last-page-offers-no-next
  (let [page (data/window (vec (range 10)) (cursor [] 8) caps)]
    (is (= 2 (count (:seon.render.data/entries page))))
    (is (nil? (:seon.render.data/next-offset page)))
    (is (= 4 (:seon.render.data/previous-offset page)))))

(deftest paging-partitions-the-value-exactly-once
  ;; THE pager property. An unstable order shows a row twice and never
  ;; shows another, and no example test finds that reliably.
  (check!
   "pages partition"
   (tc/quick-check
    200
    (prop/for-all [entries (gen/vector gen/small-integer 0 40)]
      (let [collected (loop [offset 0 seen []]
                        (let [page (data/window entries (cursor [] offset) caps)]
                          (if-let [next-offset (:seon.render.data/next-offset page)]
                            (recur next-offset
                                   (into seen (:seon.render.data/entries page)))
                            (into seen (:seon.render.data/entries page)))))]
        (= (data/entries entries) collected)))
    :seed 202607280402)))

(deftest ordering-is-stable-across-derivations
  ;; Two derivations of one value are the same order, or the pager lies.
  (check!
   "stable order"
   (tc/quick-check
    200
    (prop/for-all [entries (gen/map gen/keyword gen/small-integer)]
      (= (data/entries entries) (data/entries entries)))
    :seed 202607280403)))

(deftest an-offset-past-the-end-is-an-empty-page-not-a-crash
  (let [page (data/window [1 2 3] (cursor [] 999) caps)]
    (is (= [] (:seon.render.data/entries page)))
    (is (= 3 (:seon.render.data/total page)))))

(deftest a-scalar-has-no-entries
  (doseq [leaf ["text" 42 :keyword nil true]]
    (is (= [] (data/entries leaf)))))

(deftest the-window-is-total
  (check!
   "window totality"
   (tc/quick-check
    200
    (prop/for-all [any-value gen/any-printable
                   offset (gen/choose 0 50)]
      (seon.schema/valid-candidate-value?
       :seon.render.data/window
       (data/window any-value (cursor [] offset) caps)))
    :seed 202607280404)))

;;; ---------------------------------------------------------------------------
;;; It pays only for what is opened
;;; ---------------------------------------------------------------------------

(deftest a-page-costs-the-same-whatever-it-links-to
  ;; The architecture's /data rule applied to an ordinary value: a
  ;; million-element vector must cost the same to DISPLAY as a ten-
  ;; element one, because nothing walks what nobody opened. A summary
  ;; that described its child by walking it would defeat exactly this.
  (let [small {:items (vec (range 10))}
        huge {:items (vec (range 1000000))}
        render (fn [subject]
                 (let [start (System/nanoTime)
                       html (hiccup/->string
                             (data/drill-html
                              {:seon.render/value subject
                               :seon.sci.admit/caps caps
                               :seon.render.data/cursor (cursor)}))]
                   [(/ (- (System/nanoTime) start) 1e6) html]))
        [_ _] (render small)
        [small-ms _] (render small)
        [huge-ms html] (render huge)]
    (is (str/includes? html "1000000 items")
        "it knows the size without paging through it")
    (is (< huge-ms (+ 5 (* 20 small-ms)))
        (str "displaying a huge value took " huge-ms "ms against " small-ms
             "ms for a small one — something is walking what nobody opened"))))

;;; ---------------------------------------------------------------------------
;;; The surface
;;; ---------------------------------------------------------------------------

(deftest the-drill-renders-breadcrumbs-entries-and-a-pager
  (let [html (hiccup/->string
              (data/drill-html {:seon.render/value {:items (vec (range 10))}
                                :seon.sci.admit/caps caps
                                :seon.render.data/cursor (cursor [:items] 4)}))]
    (is (str/includes? html "showing 5–8 of 10"))
    (is (str/includes? html "previous"))
    (is (str/includes? html "next"))
    (testing "breadcrumbs are the path's own prefixes — the path IS the trail"
      (is (str/includes? html ">root</a>"))
      (is (str/includes? html ":items")))))

(deftest a-navigable-child-is-a-link-and-a-leaf-is-not
  (let [html (hiccup/->string
              (data/drill-html {:seon.render/value value
                                :seon.sci.admit/caps caps
                                :seon.render.data/cursor (cursor)}))]
    (is (str/includes? html "seon-data-step") "the nested values are links")
    (is (str/includes? html "hello") "and the scalar shows its value")))

(deftest the-drill-is-total-and-legible-when-it-cannot-proceed
  (testing "a broken path renders a card rather than a blank page"
    (let [rendered (data/drill-html {:seon.render/value value
                                     :seon.sci.admit/caps caps
                                     :seon.render.data/cursor (cursor [:nope] 0)})]
      (is (hiccup/hiccup? rendered))
      (is (str/includes? (hiccup/->string rendered) "seon-error-card"))))
  (testing "missing caps or cursor says which"
    (is (str/includes? (hiccup/->string
                        (data/drill-html {:seon.render/value value}))
                       "caps")))
  (testing "any value at all still renders hiccup"
    (check!
     "drill totality"
     (tc/quick-check
      100
      (prop/for-all [any-value gen/any-printable]
        (hiccup/hiccup? (data/drill-html {:seon.render/value any-value
                                          :seon.sci.admit/caps caps
                                          :seon.render.data/cursor (cursor)})))
      :seed 202607280405))))
