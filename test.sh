#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
JB="${JAVA_HOME:+$JAVA_HOME/bin/}"
rm -rf out
mkdir -p out/main out/test out/fx
"${JB}javac" -d out/main $(find src/main/java -name '*.java')

F=out/fx
FIX=src/test/fixtures

"${JB}javac" -d "$F/clean" $(find "$FIX/clean/a" -name '*.java')
"${JB}jar" --create --file "$F/clean.jar" -C "$F/clean" .

# cycle: compile each side against a requires-free stub of the other so
# javac accepts it; the descriptors still record the real requires edges
"${JB}javac" -d "$F/cb0" $(find "$FIX/cycle/b0" -name '*.java')
"${JB}javac" -d "$F/ca0" $(find "$FIX/cycle/a0" -name '*.java')
"${JB}javac" --module-path "$F/cb0" -d "$F/ca" $(find "$FIX/cycle/a" -name '*.java')
"${JB}javac" --module-path "$F/ca0" -d "$F/cb" $(find "$FIX/cycle/b" -name '*.java')

# split packages must be compiled separately: javac rejects them together
"${JB}javac" -d "$F/sa" $(find "$FIX/split/a" -name '*.java')
"${JB}javac" -d "$F/sb" $(find "$FIX/split/b" -name '*.java')

"${JB}javac" -d "$F/dup-a" $(find "$FIX/shadow/a" -name '*.java')
"${JB}javac" -d "$F/dup-b" $(find "$FIX/shadow/b" -name '*.java')

# pruned: compile whole, then delete classes to simulate a stale artifact
"${JB}javac" -d "$F/pstub" $(find "$FIX/pruned/stub" -name '*.java')
"${JB}javac" --module-path "$F/pstub" -d "$F/pa" $(find "$FIX/pruned/a" -name '*.java')
rm -rf "$F/pa/com/example/gone"
rm -f "$F/pa/com/example/pa/Impl.class"

"${JB}javac" -cp out/main -d out/test $(find src/test/java -name '*.java')
"${JB}java" -cp out/main:out/test io.github.celine1337.moduleaudit.ModuleAuditTest "$F"

# CLI smoke: exit codes and JSON output
"${JB}java" -cp out/main io.github.celine1337.moduleaudit.Main "$F/clean" > out/cli-clean.txt
if "${JB}java" -cp out/main io.github.celine1337.moduleaudit.Main "$F/ca" "$F/cb" > out/cli-cycle.txt; then
  echo "CLI: expected exit 1 on cycle"
  exit 1
fi
"${JB}java" -cp out/main io.github.celine1337.moduleaudit.Main --json "$F/clean" | grep -q '"errors":0'

# oracle: the JDK itself must accept the clean module and reject the broken ones
"${JB}java" --module-path "$F/clean" -m com.example.clean/com.example.clean.A > out/oracle-clean.txt
grep -q ok out/oracle-clean.txt
if "${JB}java" --module-path "$F/ca:$F/cb" -m com.example.ca/com.example.ca.A > out/oracle-cycle.txt 2>&1; then
  echo "oracle: JDK accepted a requires cycle"
  exit 1
fi
if "${JB}java" --module-path "$F/sa:$F/sb" --add-modules com.example.sa,com.example.sb -m com.example.sa/com.example.shared.S > out/oracle-split.txt 2>&1; then
  echo "oracle: JDK accepted a split package"
  exit 1
fi
if "${JB}java" --module-path "$F/pa" -m com.example.pa/com.example.pa.A > out/oracle-pruned.txt 2>&1; then
  echo "oracle: JDK accepted a missing requires"
  exit 1
fi
echo "ALL CHECKS PASSED (library + CLI + JDK oracle)"