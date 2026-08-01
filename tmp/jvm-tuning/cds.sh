#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
result_dir="$repo_root/tmp/jvm-tuning/results/cds"
archive="$result_dir/seon-dependencies.jsa"
staged_dir="$result_dir/staged-classpath"
mkdir -p "$result_dir"

load_form="(do (require 'seon.cluster) (println :loaded))"
classpath="$(clojure -Spath -M:dev)"
mkdir -p "$staged_dir"

package_began="$(python3 -c 'import time; print(time.monotonic_ns())')"
IFS=: read -r -a classpath_entries <<< "$classpath"
staged_entries=()
ordinal=0
for entry in "${classpath_entries[@]}"; do
  if [[ -d "$entry" ]]; then
    staged="$staged_dir/$(printf '%03d' "$ordinal").jar"
    jar --create --file "$staged" -C "$entry" .
    staged_entries+=("$staged")
  else
    staged_entries+=("$entry")
  fi
  ordinal="$((ordinal + 1))"
done
staged_classpath="$(IFS=:; printf '%s' "${staged_entries[*]}")"
package_ended="$(python3 -c 'import time; print(time.monotonic_ns())')"
printf '%s\n' "$(( package_ended - package_began ))" \
  > "$result_dir/package-classpath.ns"

base=(java --add-modules=jdk.incubator.vector
      --enable-native-access=ALL-UNNAMED
      --sun-misc-unsafe-memory-access=allow
      -XX:+UseG1GC -Xmx2g -cp "$staged_classpath" clojure.main)

measure() {
  local label="$1"
  shift
  local began ended
  began="$(python3 -c 'import time; print(time.monotonic_ns())')"
  "$@" > "$result_dir/$label.stdout" 2> "$result_dir/$label.stderr"
  ended="$(python3 -c 'import time; print(time.monotonic_ns())')"
  printf '%s\n' "$(( ended - began ))" > "$result_dir/$label.ns"
}

measure archive-create \
  java --add-modules=jdk.incubator.vector \
  --enable-native-access=ALL-UNNAMED \
  --sun-misc-unsafe-memory-access=allow \
  -XX:+UseG1GC -Xmx2g "-XX:ArchiveClassesAtExit=$archive" \
  -cp "$staged_classpath" clojure.main -e "$load_form"

for ordinal in 1 2 3 4 5; do
  measure "default-$ordinal" "${base[@]}" -e "$load_form"
  measure "custom-$ordinal" \
    java --add-modules=jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED \
    --sun-misc-unsafe-memory-access=allow \
    -XX:+UseG1GC -Xmx2g "-XX:SharedArchiveFile=$archive" \
    -cp "$staged_classpath" clojure.main -e "$load_form"
  measure "off-$ordinal" \
    java --add-modules=jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED \
    --sun-misc-unsafe-memory-access=allow \
    -XX:+UseG1GC -Xmx2g -Xshare:off \
    -cp "$staged_classpath" clojure.main -e "$load_form"
  measure "actual-classpath-$ordinal" \
    java --add-modules=jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED \
    --sun-misc-unsafe-memory-access=allow \
    -XX:+UseG1GC -Xmx2g \
    -cp "$classpath" clojure.main -e "$load_form"
done

wc -c "$archive" > "$result_dir/archive-bytes.txt"
