(ns seon.web.reactive.transform-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.web.reactive.transform :as transform]))

(deftest transform-attrs-test
  (testing "event attributes transform to Datastar @post (no contentType for plain events)"
    (is (= {:data-on:click "@post('/ns/seon.test/increment')"}
           (transform/transform-attrs 'seon.test {:on:click :increment})))

    (is (= {:data-on:submit "@post('/ns/seon.trading/create-order')"}
           (transform/transform-attrs 'seon.trading {:on:submit :create-order}))))

  (testing "field attributes produce name with printed keyword"
    (is (= {:name ":user-name"}
           (transform/transform-attrs 'seon.test {:field :user-name}))))

  (testing "qualified field produces full keyword string"
    (is (= {:name ":seon.getting-started/exercise"}
           (transform/transform-attrs 'seon.test {:field :seon.getting-started/exercise}))))

  (testing "field preserves other attributes"
    (is (= {:name ":price" :type "number" :placeholder "Enter price"}
           (transform/transform-attrs 'seon.test {:field :price :type "number" :placeholder "Enter price"}))))

  (testing "regular attributes pass through unchanged"
    (is (= {:class "btn" :id "submit-btn" :disabled true}
           (transform/transform-attrs 'seon.test {:class "btn" :id "submit-btn" :disabled true}))))

  (testing "nil and non-map inputs handled gracefully"
    (is (nil? (transform/transform-attrs 'seon.test nil)))
    (is (= "not-a-map" (transform/transform-attrs 'seon.test "not-a-map")))))

(deftest transform-hiccup-test
  (testing "simple element with event"
    (is (= [:button {:data-on:click "@post('/ns/seon.test/increment')"} "Add"]
           (transform/transform-hiccup 'seon.test
             [:button {:on:click :increment} "Add"]))))

  (testing "input with field produces name attribute"
    (is (= [:input {:name ":email" :type "email"}]
           (transform/transform-hiccup 'seon.test
             [:input {:field :email :type "email"}]))))

  (testing "nested structure transforms correctly"
    (let [result (transform/transform-hiccup 'seon.trading
                   [:div {:class "container"}
                    [:h1 {} "Title"]
                    [:form {:on:submit :submit}
                     [:input {:field :symbol}]
                     [:button {:on:click :submit} "Go"]]])]
      ;; Root element keeps class
      (is (= :div (first result)))
      (is (= "container" (:class (second result))))
      ;; No data-signals anywhere (removed)
      (is (not (some #(and (vector? %) (map? (second %))
                           (:data-signals (second %)))
                     (tree-seq sequential? seq result))))))

  (testing "elements without attrs unchanged"
    (is (= [:div [:span "hello"]]
           (transform/transform-hiccup 'seon.test
             [:div [:span "hello"]]))))

  (testing "preserves non-hiccup content"
    (is (= [:ul {}
            [:li {} "a"]
            [:li {} "b"]]
           (transform/transform-hiccup 'seon.test
             [:ul {}
              [:li {} "a"]
              [:li {} "b"]])))))

(deftest make-transformer-test
  (testing "creates bound transformer function"
    (let [tx (transform/make-transformer 'seon.example)]
      (is (fn? tx))
      (is (= [:button {:data-on:click "@post('/ns/seon.example/click')"}]
             (tx [:button {:on:click :click}]))))))

(deftest edge-cases-test
  (testing "empty hiccup"
    (is (= [:div {}]
           (transform/transform-hiccup 'seon.test [:div {}]))))

  (testing "deeply nested"
    (is (= [:div {}
            [:div {}
             [:div {}
              [:button {:data-on:click "@post('/ns/seon.test/deep')"} "Deep"]]]]
           (transform/transform-hiccup 'seon.test
             [:div {}
              [:div {}
               [:div {}
                [:button {:on:click :deep} "Deep"]]]]))))

  (testing "multiple events on same element"
    (is (= [:input {:data-on:focus "@post('/ns/seon.test/focused')"
                    :data-on:blur "@post('/ns/seon.test/blurred')"}]
           (transform/transform-hiccup 'seon.test
             [:input {:on:focus :focused :on:blur :blurred}])))))

(deftest qualified-field-names-test
  (testing "qualified keywords produce printed keyword strings as name attrs"
    (let [result (transform/transform-hiccup 'seon.getting-started
                   [:div
                    [:input {:field :seon.getting-started/exercise}]
                    [:input {:field :seon.ctx/user-input}]])]
      ;; Find input elements and check name attrs
      (let [nodes (tree-seq sequential? seq result)
            inputs (filter #(and (vector? %) (= :input (first %)) (map? (second %)))
                           nodes)]
        (is (= 2 (count inputs)))
        (is (some #(= ":seon.getting-started/exercise" (:name (second %))) inputs))
        (is (some #(= ":seon.ctx/user-input" (:name (second %))) inputs))))))

(deftest instance-id-in-urls-test
  (testing "instance-id appended to action URLs"
    (let [result (transform/transform-hiccup 'seon.test
                   [:button {:on:click :action}]
                   "inst-123")]
      (is (str/includes?
           (get-in result [1 :data-on:click])
           "instance=inst-123")))))
