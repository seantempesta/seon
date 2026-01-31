(ns seon.web.reactive.transform-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.web.reactive.transform :as transform]))

(deftest transform-attrs-test
  (testing "event attributes transform to Datastar format"
    (is (= {:data-on:click "@post('/action/seon.test/increment')"}
           (transform/transform-attrs 'seon.test {:on:click :increment})))

    (is (= {:data-on:submit "@post('/action/seon.trading/create-order')"}
           (transform/transform-attrs 'seon.trading {:on:submit :create-order}))))

  (testing "field attributes transform to data-bind (value syntax to avoid camelCase)"
    (is (= {:name "user-name" :data-bind "user-name"}
           (transform/transform-attrs 'seon.test {:field :user-name}))))

  (testing "field preserves other attributes"
    (is (= {:name "price" :data-bind "price" :type "number" :placeholder "Enter price"}
           (transform/transform-attrs 'seon.test {:field :price :type "number" :placeholder "Enter price"}))))

  (testing "regular attributes pass through unchanged"
    (is (= {:class "btn" :id "submit-btn" :disabled true}
           (transform/transform-attrs 'seon.test {:class "btn" :id "submit-btn" :disabled true}))))

  (testing "nil and non-map inputs handled gracefully"
    (is (nil? (transform/transform-attrs 'seon.test nil)))
    (is (= "not-a-map" (transform/transform-attrs 'seon.test "not-a-map")))))

(deftest transform-hiccup-test
  (testing "simple element with event"
    (is (= [:button {:data-on:click "@post('/action/seon.test/increment')"} "Add"]
           (transform/transform-hiccup 'seon.test
             [:button {:on:click :increment} "Add"]))))

  (testing "input with field"
    (is (= [:input {:name "email" :data-bind "email" :type "email"}]
           (transform/transform-hiccup 'seon.test
             [:input {:field :email :type "email"}]))))

  (testing "nested structure"
    (is (= [:div {:class "container"}
            [:h1 {} "Title"]
            [:form {:data-on:submit "@post('/action/seon.trading/submit')"}
             [:input {:name "symbol" :data-bind "symbol"}]
             [:button {:data-on:click "@post('/action/seon.trading/submit')"} "Go"]]]
           (transform/transform-hiccup 'seon.trading
             [:div {:class "container"}
              [:h1 {} "Title"]
              [:form {:on:submit :submit}
               [:input {:field :symbol}]
               [:button {:on:click :submit} "Go"]]]))))

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
    (let [tx (transform/make-transformer 'seon.demo)]
      (is (fn? tx))
      (is (= [:button {:data-on:click "@post('/action/seon.demo/click')"}]
             (tx [:button {:on:click :click}]))))))

(deftest edge-cases-test
  (testing "empty hiccup"
    (is (= [:div {}]
           (transform/transform-hiccup 'seon.test [:div {}]))))

  (testing "deeply nested"
    (is (= [:div {}
            [:div {}
             [:div {}
              [:button {:data-on:click "@post('/action/seon.test/deep')"} "Deep"]]]]
           (transform/transform-hiccup 'seon.test
             [:div {}
              [:div {}
               [:div {}
                [:button {:on:click :deep} "Deep"]]]]))))

  (testing "multiple events on same element"
    (is (= [:input {:data-on:focus "@post('/action/seon.test/focused')"
                    :data-on:blur "@post('/action/seon.test/blurred')"}]
           (transform/transform-hiccup 'seon.test
             [:input {:on:focus :focused :on:blur :blurred}])))))
