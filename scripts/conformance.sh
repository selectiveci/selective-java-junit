#!/usr/bin/env bash
set -euo pipefail
conformance=${1:?usage: scripts/conformance.sh <transport-conformance> <transport> [maven args]}
transport=${2:?usage: scripts/conformance.sh <transport-conformance> <transport> [maven args]}
shift 2
root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
mvn -B -q -f "$root/core/pom.xml" dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$root/core/target/conformance.classpath" "$@"
classpath="$root/core/target/test-classes:$root/core/target/classes:$(cat "$root/core/target/conformance.classpath")"
agent=$(tr ':' '\n' < "$root/core/target/conformance.classpath" | grep -m1 'org.jacoco.agent-.*-runtime.jar$')
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
client="ci.selective.junit.Main --package ci.selective.junit.conformance"
cd "$root"
echo "conformance: select (no JaCoCo agent)"
"$conformance" run --client "$java -cp $classpath $client" --fixture-ids scripts/conformance-ids.json --transport "$transport"
echo "conformance: record (JaCoCo agent attached)"
"$conformance" run --client "$java -javaagent:$agent=output=none -cp $classpath $client" --fixture-ids scripts/conformance-ids.json --transport "$transport"
