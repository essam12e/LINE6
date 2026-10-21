#!/usr/bin/env bash
# Assembles the minimal "SDK lite" the litebuild needs, from public artifacts only, with pinned SHA-256:
#   - android.jar (API 35 classes)  : AOSP-built android.jar from github.com/Reginer/aosp-android-jar
#   - framework resources (API 35)  : resources.arsc + res/ from Robolectric's android-all (Maven Central)
#   - aapt2 2.20 (linux x86_64)      : from the npm package "aaptjs3" (Apache-2.0, bundles Google's aapt2)
# Use this only where dl.google.com / maven.google.com are unreachable. With a normal Android SDK, use the
# regular Gradle build in the project root instead (see ../BUILD.md).
set -euo pipefail

SDK="${NITAAQ_SDK_LITE:-/opt/android-sdk-lite}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch() { # url sha256 out
  curl -fsSL --retry 4 --retry-delay 3 -o "$3" "$1"
  echo "$2  $3" | sha256sum -c -
}

fetch "https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-35/android.jar" \
  60be6880d7b2b526d654460d4ce6fa81bf23efaa28b02d901d0b63a01ccf4671 "$WORK/classes.jar"
fetch "https://repo.maven.apache.org/maven2/org/robolectric/android-all/15-robolectric-12714715/android-all-15-robolectric-12714715.jar" \
  0258fa25f13067df86ebdac0e36d4e7e09007df929f0120b4f3233d9b69b4296 "$WORK/android-all.jar"
fetch "https://registry.npmjs.org/aaptjs3/-/aaptjs3-2.0.2.tgz" \
  26031bd1acb577edce91675ae92daf63cf85795d4e2399333edeb994dd9197e9 "$WORK/aapt.tgz"

mkdir -p "$WORK/aapt" && tar xzf "$WORK/aapt.tgz" -C "$WORK/aapt"
echo "e4dff6060827a3e401bce376e47916f2f93a89602af54973ffd3fdfc2528be3f  $WORK/aapt/package/bin/x64/linux/aapt2" | sha256sum -c -

mkdir -p "$SDK/platforms/android-35" "$SDK/build-tools/35.0.0"
cp "$WORK/classes.jar" "$SDK/platforms/android-35/android.jar"
( cd "$WORK" && mkdir -p res_x && cd res_x && unzip -q ../android-all.jar resources.arsc AndroidManifest.xml 'res/*' \
  && zip -q -r "$SDK/platforms/android-35/android.jar" resources.arsc AndroidManifest.xml res )
install -m 0755 "$WORK/aapt/package/bin/x64/linux/aapt2" "$SDK/aapt2"
cp "$WORK/aapt/package/LICENSE" "$SDK/aapt2.LICENSE"

"$SDK/aapt2" version
echo "SDK lite ready in $SDK"
echo "Build: cd litebuild && NITAAQ_SDK_LITE=$SDK gradle assembleApk test verifyApk"
