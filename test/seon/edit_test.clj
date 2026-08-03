(ns seon.edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [rewrite-clj.zip :as z]
            [seon.edit :as edit]))

(defn- form-request
  [selector operation source]
  (cond-> {:my.edit/path "sample.clj"
           :my.edit/expected-digest (apply str (repeat 64 "0"))
           :my.edit/form selector
           :my.edit/operation operation}
    source (assoc :my.edit/source source)))

(defn- read-all
  [source]
  (binding [*read-eval* false]
    (with-open [reader (java.io.PushbackReader.
                        (java.io.StringReader. source))]
      (loop [forms []]
        (let [form (read {:eof ::eof :read-cond :allow :features #{:clj}}
                         reader)]
          (if (= ::eof form)
            forms
            (recur (conj forms form))))))))

(deftest form-edit-preserves-every-unselected-byte
  (let [before
        (str ";; before\r\n"
             "#_(defn foo [] :discarded)\r\n"
             "^:private (defn foo  [x]\r\n"
             "  (+ x 1))\r\n"
             ",,;; after\r\n"
             "(def bar 1)\r\n")
        replacement "(defn foo [x] (* x 2))"
        result
        (edit/form
         before
         (form-request {:my.edit.form/head 'defn
                        :my.edit.form/name 'foo}
                       :replace replacement)
         8192)
        after (:seon.edit/source result)
        selected "^:private (defn foo  [x]\r\n  (+ x 1))"]
    (is (= (str (subs before 0 (.indexOf before selected))
                replacement
                (subs before (+ (.indexOf before selected)
                                (count selected))))
           after))
    (is (.contains after "#_(defn foo [] :discarded)\r\n"))
    (is (.contains after ",,;; after\r\n"))
    (is (string? (z/root-string (z/of-string* after))))
    (is (= ['(defn foo [x] (* x 2)) '(def bar 1)]
           (read-all after)))))

(deftest selectors-refuse-ambiguity-and-use-parsed-dispatch
  (let [source (str "(defmethod render :alpha [x] x)\n"
                    "(defmethod render :beta [x] x)\n")
        selector {:my.edit.form/head 'defmethod
                  :my.edit.form/name 'render}
        ambiguous
        (edit/form source (form-request selector :delete nil) 8192)
        selected
        (edit/form source
                   (form-request
                    (assoc selector :my.edit.form/dispatch-source ":beta")
                    :replace "(defmethod render :beta [x] :changed)")
                   8192)]
    (is (= :my.edit/ambiguous-match (:seon.error/kind ambiguous)))
    (is (= [1 2]
           (mapv :my.edit/from-line
                 (get-in ambiguous
                         [:seon.error/data :seon.edit/candidates]))))
    (is (= (str "(defmethod render :alpha [x] x)\n"
                "(defmethod render :beta [x] :changed)\n")
           (:seon.edit/source selected)))))

(deftest form-failures-never-fall-back-to-text
  (testing "malformed source refuses before exact-looking text can be changed"
    (let [source "(defn broken [] OLD"
          result
          (edit/form source
                     (form-request {:my.edit.form/head 'defn
                                    :my.edit.form/name 'broken}
                                   :replace "(defn broken [] :new)")
                     8192)]
      (is (= :my.edit/parse-refused (:seon.error/kind result)))
      (is (nil? (:seon.edit/source result)))))
  (testing "malformed replacement refuses without a candidate"
    (let [result
          (edit/form "(defn stable [] :old)\n"
                     (form-request {:my.edit.form/head 'defn
                                    :my.edit.form/name 'stable}
                                   :replace "(defn stable []")
                     8192)]
      (is (= :my.edit/invalid-replacement (:seon.error/kind result)))
      (is (nil? (:seon.edit/source result)))))
  (testing "not-found returns bounded nearby structural evidence"
    (let [result
          (edit/form "(def present 1)\n"
                     (form-request {:my.edit.form/head 'def
                                    :my.edit.form/name 'absent}
                                   :delete nil)
                     8192)]
      (is (= :my.edit/no-match (:seon.error/kind result)))
      (is (= [{:my.edit.form/head 'def
               :my.edit.form/name 'present
               :my.edit/from-line 1
               :my.edit/to-line 1}]
             (get-in result
                     [:seon.error/data :seon.edit/candidates]))))))

(deftest exact-edit-counts-before-changing
  (let [source "OLD\nkeep\nOLD\n"
        base {:my.edit/path "sample.txt"
              :my.edit/expected-digest (apply str (repeat 64 "0"))
              :my.edit/old-string "OLD"
              :my.edit/new-string "NEW"}
        ambiguous (edit/exact source base 8192)
        all (edit/exact source (assoc base :my.edit/replace-all? true) 8192)
        absent (edit/exact source (assoc base :my.edit/old-string "missing")
                           8192)]
    (is (= :my.edit/ambiguous-match (:seon.error/kind ambiguous)))
    (is (= 2 (get-in ambiguous
                     [:seon.error/data :my.edit/replacements])))
    (is (= "NEW\nkeep\nNEW\n" (:seon.edit/source all)))
    (is (= 2 (:my.edit/replacements all)))
    (is (= :my.edit/no-match (:seon.error/kind absent)))))

(deftest line-window-is-an-exact-second-fence
  (let [source "one\r\ntwo\r\nlast"
        base {:my.edit/path "sample.txt"
              :my.edit/expected-digest (apply str (repeat 64 "0"))
              :my.edit/from-line 2
              :my.edit/to-line 2
              :my.edit/old-window "two\r\n"
              :my.edit/new-window "changed\r\n"}
        changed (edit/lines source base 8192)
        refused (edit/lines source (assoc base :my.edit/old-window "two\n")
                            8192)
        final-line
        (edit/lines source
                    (assoc base
                           :my.edit/from-line 3
                           :my.edit/to-line 3
                           :my.edit/old-window "last"
                           :my.edit/new-window "done")
                    8192)]
    (is (= "one\r\nchanged\r\nlast" (:seon.edit/source changed)))
    (is (= :my.edit/no-match (:seon.error/kind refused)))
    (is (= "two\r\n"
           (get-in refused [:seon.error/data :my.edit/actual-window])))
    (is (= "one\r\ntwo\r\ndone" (:seon.edit/source final-line)))))
