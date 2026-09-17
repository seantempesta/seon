; Read-only JVM probe. Requires are already loaded in the observed process.
(require 'seon.config 'seon.instrument)
(let [original (fn [& arguments]
                 {:probe/body-ran true :probe/arguments (vec arguments)})
      caps (seon.config/result-caps (seon.config/defaults))
      wrapped (seon.instrument/wrap-interpreted
               'probe.slice2/body "[:=> [:cat :int] :int]"
               {} :record caps original)]
  {:probe/identical-original (identical? original wrapped)
   :probe/invalid-input (wrapped "invalid")
   :probe/invalid-arity (wrapped)})
