#!/bin/sh
# Scratch copies of the edited printer, loaded beside the installed one in
# default's JVM: renamed namespaces, keywords kept, no registry side effects.
T=/Users/sean/src/seon/tmp/floor_printer
sed -e 's/^(ns seon.print$/(ns tmp.floor-printer.print/' \
    -e 's/::\([a-z]\)/:seon.print\/\1/g' \
    -e '/^(schema\/register-core-predicate!/d' -e '/^#?(:clj (schema.edn\/load! {}))/d' \
    /Users/sean/src/seon/src/seon/print.cljc > $T/print_copy.cljc
sed -e 's/^(ns seon.render.value/(ns tmp.floor-printer.value/' -e 's/^(schema.edn\/load! {})//' \
    -e "s/\[seon.print :as print\]/[tmp.floor-printer.print :as print]/" -e "s/::print\//:seon.print\//g" \
    /Users/sean/src/seon/src/seon/render/value.clj > $T/value_copy.clj
sed -e 's/^(ns seon.print.value/(ns tmp.floor-printer.pvalue/' /Users/sean/src/seon/src/seon/print/value.clj > $T/pvalue_copy.clj
sed -i '' -e 's/\[seon.print.value :as print.value\]/[tmp.floor-printer.pvalue :as print.value]/' $T/value_copy.clj
