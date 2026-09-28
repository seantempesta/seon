(ns seon.cluster.derivation-keys-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(deftest file-keys-follow-transitive-schema-content
  (let [forms (assoc (schema.edn/packaged-forms)
                     :derivation.probe/leaf :int
                     :derivation.probe/root :derivation.probe/leaf
                     :derivation.probe/unrelated :string)
        code (apply str (repeat 64 "a"))
        inputs {"left.clj" code "right.clj" code}
        selected {"left.clj" #{:derivation.probe/root} "right.clj" #{}}
        keys-for (fn [population files]
                   (#'cluster/derivation-keys code (schema/declaration-projection population)
                                             files selected (set (keys files))))
        original (keys-for forms inputs)
        schema-change (keys-for (assoc forms :derivation.probe/leaf :string) inputs)
        file-change (keys-for forms (assoc inputs "right.clj" (apply str (repeat 64 "b"))))]
    (is (= #{"left.clj" "right.clj"} (set (keys original))))
    (is (= original (keys-for (assoc forms :derivation.probe/unrelated :boolean) inputs)))
    (is (not= (original "left.clj") (schema-change "left.clj")))
    (is (= (original "right.clj") (schema-change "right.clj")))
    (is (= (original "left.clj") (file-change "left.clj")))
    (is (not= (original "right.clj") (file-change "right.clj")))))

(deftest root-render-properties-do-not-rederive-schema-consumers
  (let [forms (assoc (schema.edn/packaged-forms) :derivation.probe/rendered [:int])
        code (apply str (repeat 64 "a"))
        key-for (fn [form]
                  (#'cluster/derivation-keys
                   code (schema/declaration-projection (assoc forms :derivation.probe/rendered form))
                   {"consumer.clj" code} {"consumer.clj" #{:derivation.probe/rendered}} #{"consumer.clj"}))]
    (is (= (key-for [:int]) (key-for [:int {:seon.render/ai 'example/ai
                                          :seon.render/html 'example/html}])))
    (is (not= (key-for [:int]) (key-for [:int {:min 1}])))
    (is (= [:enum nil {:seon.render/ai 'example/ai}]
           (#'cluster/derivation-form [:enum nil {:seon.render/ai 'example/ai}])))))
