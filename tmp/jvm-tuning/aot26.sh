#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
measurement_tree="${JVM_TUNING_TREE:-$repo_root}"
case_name="${1:-aot26}"
if [[ ! "$case_name" =~ ^[a-zA-Z0-9._-]+$ ]]; then
  echo "case name must be one path component" >&2
  exit 2
fi
result_dir="$repo_root/tmp/jvm-tuning/results/$case_name"
staged_dir="$result_dir/staged-classpath"
archive="$result_dir/seon.aot"
load_form="(do (require 'seon.cluster) (println :loaded))"

if [[ -e "$result_dir/complete" ]]; then
  echo "refusing to overwrite completed JDK 26 AOT measurement" >&2
  exit 2
fi

mkdir -p "$result_dir" "$staged_dir"
raw_classpath="$(cd "$measurement_tree" && clojure -Spath -M:dev)"
IFS=: read -r -a raw_classpath_entries <<< "$raw_classpath"
classpath_entries=()
for entry in "${raw_classpath_entries[@]}"; do
  if [[ "$entry" == /* ]]; then
    classpath_entries+=("$entry")
  else
    classpath_entries+=("$measurement_tree/$entry")
  fi
done
classpath="$(IFS=:; printf '%s' "${classpath_entries[*]}")"
cd "$result_dir"

# The live source-first classpath is the operationally relevant case. Preserve
# its exit status: JDK 26 refuses AOT assembly when a classpath entry is a
# non-empty directory.
source_began="$(python3 -c 'import time; print(time.monotonic_ns())')"
set +e
java --add-modules=jdk.incubator.vector \
  --enable-native-access=ALL-UNNAMED \
  -XX:+UseG1GC -Xmx2g -XX:AOTCacheOutput="$result_dir/source-classpath.aot" \
  -Xlog:aot=info -cp "$classpath" clojure.main -e "$load_form" \
  > "$result_dir/source-classpath.stdout" \
  2> "$result_dir/source-classpath.stderr"
source_status=$?
set -e
source_ended="$(python3 -c 'import time; print(time.monotonic_ns())')"
printf '%s\n' "$source_status" > "$result_dir/source-classpath.exit"
printf '%s\n' "$((source_ended - source_began))" \
  > "$result_dir/source-classpath.ns"

# Stage every directory as a JAR to test the best case, while recording the
# packaging cost that a source-first process would pay after classpath change.
package_began="$(python3 -c 'import time; print(time.monotonic_ns())')"
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
printf '%s\n' "$((package_ended - package_began))" > "$result_dir/package.ns"

measure() {
  local label="$1"
  shift
  local began ended
  began="$(python3 -c 'import time; print(time.monotonic_ns())')"
  "$@" > "$result_dir/$label.stdout" 2> "$result_dir/$label.stderr"
  ended="$(python3 -c 'import time; print(time.monotonic_ns())')"
  printf '%s\n' "$((ended - began))" > "$result_dir/$label.ns"
}

measure archive-create \
  java --add-modules=jdk.incubator.vector \
  --enable-native-access=ALL-UNNAMED \
  -XX:+UseG1GC -Xmx2g -XX:AOTCacheOutput="$archive" \
  -Xlog:aot=info -cp "$staged_classpath" clojure.main -e "$load_form"

# The one-step launcher can return zero even when its assembly child crashes.
# Refuse to benchmark an empty or missing cache as though it were AOT-enabled.
if [[ ! -s "$archive" ]]; then
  echo "JDK 26 produced no usable AOT cache; inspect archive-create output" >&2
  exit 1
fi

for ordinal in 1 2 3 4 5; do
  measure "default-$ordinal" \
    java --add-modules=jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED \
    -XX:+UseG1GC -Xmx2g \
    -cp "$staged_classpath" clojure.main -e "$load_form"
  measure "aot-$ordinal" \
    java --add-modules=jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED \
    -XX:+UseG1GC -Xmx2g -XX:AOTCache="$archive" \
    -cp "$staged_classpath" clojure.main -e "$load_form"
done

wc -c "$archive" > "$result_dir/archive-bytes.txt"
touch "$result_dir/complete"
