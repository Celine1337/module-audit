#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
JB="${JAVA_HOME:+$JAVA_HOME/bin/}"
mkdir -p out/main
"${JB}javac" -d out/main $(find src/main/java -name '*.java')
"${JB}jar" --create --file out/module-audit.jar --main-class io.github.celine1337.moduleaudit.Main -C out/main .
echo "built out/module-audit.jar"