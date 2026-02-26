(ns seon.web.reactive.transform-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.web.reactive.transform :as transform]))

(deftest transform-attrs-test
  (testing "event attributes transform to Datastar format"
    (is (= {:data-on:click "@post('/ns/seon.test/increment')"}
           (transform/transform-attrs 'seon.test {:on:click :increment})))

    (is (= {:data-on:submit "@post('/ns/seon.trading/create-order')"}
           (transform/transform-attrs 'seon.trading {:on:submit :create-order}))))

  (testing "field attributes transform to data-bind key syntax"
    ;; Key syntax: data-bind:signalName="true" renders as attribute with colon in name
    (is (= {:name "user-name" :data-bind:user-name true}
           (transform/transform-attrs 'seon.test {:field :user-name}))))

  (testing "field preserves other attributes"
    (is (= {:name "price" :data-bind:price true :type "number" :placeholder "Enter price"}
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

  (testing "input with field gets data-signals injected"
    ;; Key syntax: data-bind:email renders as attribute with colon in name
    (is (= [:input {:name "email" :data-bind:email true :type "email"
                    :data-signals "{\"email\": \"\"}"}]
           (transform/transform-hiccup 'seon.test
             [:input {:field :email :type "email"}]))))

  (testing "nested structure gets data-signals on root"
    (is (= [:div {:class "container"
                  :data-signals "{\"symbol\": \"\"}"}
            [:h1 {} "Title"]
            [:form {:data-on:submit "@post('/ns/seon.trading/submit')"}
             [:input {:name "symbol" :data-bind:symbol true}]
             [:button {:data-on:click "@post('/ns/seon.trading/submit')"} "Go"]]]
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
