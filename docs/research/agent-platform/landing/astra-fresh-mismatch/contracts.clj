(do
(require 'seon.contracts-compile-test)
(let [started (System/nanoTime)]
  {:findings (seon.contracts-compile-test/check
               {:seon.contracts-compile-test/root "/Users/sean/src/seon/tmp/astra-fresh-mismatch/archive"
                :seon.contracts-compile-test/paths ["/Users/sean/src/seon/tmp/astra-fresh-mismatch/archive/src/seon/sci/arm.clj"]})
   :ms (/ (- (System/nanoTime) started) 1000000.0)})
)
