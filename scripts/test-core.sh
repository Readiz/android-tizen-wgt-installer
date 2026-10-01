#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'Development test requires Kotlin compiler (not a phone/runtime requirement).' >&2; exit 1; }
command -v openssl >/dev/null || { echo 'OpenSSL is used only for independent test-fixture verification.' >&2; exit 1; }
mkdir -p core/build
kotlinc core/src/main/kotlin/dev/readiz/wgtinstaller/core/*.kt \
  simulator/src/main/kotlin/dev/readiz/wgtinstaller/simulator/*.kt \
  core/src/test/kotlin/dev/readiz/wgtinstaller/core/*.kt \
  -jvm-target 17 -include-runtime -d core/build/core-tests.jar
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp core/build/core-tests.jar dev.readiz.wgtinstaller.core.CoreTestsKt "$(pwd)/core/build/fixtures"
