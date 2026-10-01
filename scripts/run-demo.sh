#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'Kotlin compiler required for this developer-only demo.' >&2; exit 1; }
mkdir -p build
kotlinc core/src/main/kotlin/dev/readiz/wgtinstaller/core/*.kt simulator/src/main/kotlin/dev/readiz/wgtinstaller/simulator/*.kt -jvm-target 17 -include-runtime -d build/wgt-demo.jar
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp build/wgt-demo.jar dev.readiz.wgtinstaller.simulator.DemoMainKt
