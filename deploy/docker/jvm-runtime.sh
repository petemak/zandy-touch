#!/usr/bin/env bash
# Build-stage helper shared by Dockerfile.backend and Dockerfile.ssr.
#
#   jvm-runtime.sh <project-dir> <out-dir> <main-namespace>
#
# 1. AOT-compiles <main-namespace> and everything it requires into <out-dir>/classes. Ahead-of-time
#    classes cut the JVM start (no Clojure compile of ~100 namespaces at every boot).
# 2. Resolves the classpath with the Clojure CLI (default aliases only, so no test or dev dependencies),
#    copies every jar to <out-dir>/lib and every source directory to <out-dir>/cp/<n>.
# 3. Writes <out-dir>/classpath, a classpath relative to <out-dir> for `java -cp`.
# The runtime image then needs only a JRE: no Clojure CLI, no Maven cache, no network.
set -euo pipefail

project=$(cd "${1:?project dir}" && pwd)
mkdir -p "${2:?out dir}"
out=$(cd "$2" && pwd)
main_ns=${3:?main namespace}

rm -rf "$out/classes" "$out/lib" "$out/cp" "$out/classpath"
mkdir -p "$out/classes" "$out/lib" "$out/cp"

cd "$project"
sdeps="{:aliases {:aot {:extra-paths [\"$out/classes\"]}}}"

# Direct linking is left off (the default) so the compiled classes behave like the interpreted source.
clojure -Sdeps "$sdeps" -M:aot -e \
  "(binding [*compile-path* \"$out/classes\"] (compile '$main_ns))"

# Absolute classpath, resolved from the same deps.edn.
full_cp=$(clojure -Sdeps "$sdeps" -A:aot -Spath)

rel_cp="classes"
n=0
IFS=':' read -r -a entries <<< "$full_cp"
for entry in "${entries[@]}"; do
  [ -n "$entry" ] || continue
  if [ "$entry" = "$out/classes" ]; then
    continue
  elif [ -f "$entry" ]; then
    # Keep unique file names even if two groups ship the same jar name.
    cp "$entry" "$out/lib/$(printf '%03d' "$n")-$(basename "$entry")"
    n=$((n + 1))
  elif [ -d "$entry" ]; then
    cp -a "$entry" "$out/cp/$n"
    rel_cp="$rel_cp:cp/$n"
    n=$((n + 1))
  fi
done
printf '%s:lib/*\n' "$rel_cp" > "$out/classpath"
echo "jvm-runtime: $main_ns compiled; $(ls "$out/lib" | wc -l) jars; classpath = $(cat "$out/classpath")"
