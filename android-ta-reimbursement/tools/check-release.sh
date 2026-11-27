#!/usr/bin/env bash
# Checks a release APK for code that the shrinker (R8) must not remove: classes and fields that
# libraries look up by name at run time. Usage: tools/check-release.sh path/to/app-release.apk
set -euo pipefail
APK="${1:?APK path}"
BT="${ANDROID_HOME:-/opt/android-sdk}/build-tools"; DEXDUMP="$(ls -d "$BT"/*/ | sort -V | tail -1)dexdump"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$APK" 'classes*.dex' -d "$TMP"
for d in "$TMP"/classes*.dex; do "$DEXDUMP" "$d" 2>/dev/null; done > "$TMP/dump.txt"
fail=0
need_field() { # class descriptor, field
  awk -v c="$1" '/Class descriptor/ {f = (index($0, "'"'"'" c "'"'"'") > 0)} f' "$TMP/dump.txt" > "$TMP/cls.txt"
  if grep -q "name *: '$2'" "$TMP/cls.txt"; then echo "ok   $1 $2"; else echo "MISSING $1 $2"; fail=1; fi
}
need_class() { if grep -q "Class descriptor  : '$1'" "$TMP/dump.txt"; then echo "ok   $1"; else echo "MISSING $1"; fail=1; fi; }
S='Lcom/google/mediapipe/tasks/genai/llminference/jni/proto/LlmOptionsProto$LlmModelSettings;'
for f in modelPath_ cacheDir_ maxNumImages_ supportedLoraRanks_; do need_field "$S" "$f"; done
need_field 'Lcom/google/mediapipe/tasks/genai/llminference/jni/proto/LlmOptionsProto$LlmSessionConfig;' topk_
need_class 'Lcom/google/mediapipe/tasks/genai/llminference/LlmInference;'
need_class 'Lorg/bouncycastle/jcajce/provider/digest/SHA256$Digest;'
need_class 'Lorg/bouncycastle/jcajce/provider/asymmetric/ec/SignatureSpi$ecDSA256;'
unzip -l "$APK" > "$TMP/files.txt"; grep -q 'lib/arm64-v8a/libllm_inference_engine_jni.so' "$TMP/files.txt" && echo "ok   native AI engine (arm64)" || { echo "MISSING native AI engine"; fail=1; }
[ $fail = 0 ] && echo "RELEASE CHECK PASSED" || { echo "RELEASE CHECK FAILED"; exit 1; }
