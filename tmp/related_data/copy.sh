#!/bin/sh
# Regenerate tmp/related_data/walk.clj from the lane's src/seon/render/walk.clj
# (see proof.clj): a tmp namespace, `::` spelled out, and the checkout's
# packaged projection threaded into `graph`.
cd "$(dirname "$0")/../.." || exit 1
sed -e 's/^(ns seon\.render\.walk$/(ns tmp.related-data.walk/' -e 's/::\([a-z]\)/:seon.render.walk\/\1/g' \
    -e 's/(let \[structure (graph-structure (db\/carried-projection db))/(let [structure (graph-structure (or (:seon.schema\/projection request) (db\/carried-projection db)))/' \
    -e 's/(graph {:seon.db\/db db :seon.render.walk\/lookup lookup :seon.render\/distance 1/(graph {:seon.schema\/projection (:tmp.related-data.walk\/checkout request) :seon.db\/db db :seon.render.walk\/lookup lookup :seon.render\/distance 1/' \
    src/seon/render/walk.clj > tmp/related_data/walk.clj
# The installed seon.render/declared-projection predates this diff's explicit
# producer: call the installed render-ai/render-html with it instead.
python3 - <<'PY'
p='tmp/related_data/walk.clj'
s=open(p).read()
old="(render/declared-projection (assoc unit :seon.render.call/selected-producer producer) value output)"
assert s.count(old)==1
s=s.replace(old,"(let [r ((if (= output :seon.render/html) render/render-html render/render-ai) (assoc unit :seon.render/value value :seon.render.call/selected-producer producer))] (if (:seon.render/refused-member r) {:seon.error/value r} {:seon.print/node {:seon.print/value r}}))")
# The installed test render predates this diff's distance 0: call the diff's
# (tmp.related-data.render-test, loaded from src/seon/render/test.clj).
old="(render/render-ai (assoc unit"
s=s.replace("((if (= output :seon.render/html) render/render-html render/render-ai) (assoc unit :seon.render/value value :seon.render.call/selected-producer producer))",
 "(if (= producer 'seon.render.test/render-ai) ((requiring-resolve 'tmp.related-data.render-test/render-ai) (assoc unit :seon.render/value value)) ((if (= output :seon.render/html) render/render-html render/render-ai) (assoc unit :seon.render/value value :seon.render.call/selected-producer producer)))")
open(p,'w').write(s)
PY
grep -c "tmp.related-data.walk/checkout\|seon.schema/projection request) (db/carried" tmp/related_data/walk.clj
