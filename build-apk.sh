#!/usr/bin/env bash
export JAVA_HOME=/nix/store/2ds1jrzlmx4n08sp7flga5sxf000l2sl-zulu-ca-jdk-21.0.4
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_SDK_ROOT=/home/runner/android-sdk
export ANDROID_HOME=$ANDROID_SDK_ROOT
export KEYSTORE_PASSWORD=layamusic2026
export KEY_ALIAS=laya
export KEY_PASSWORD=layamusic2026
export GRADLE_OPTS="-Xmx3g -Dfile.encoding=UTF-8"

echo "[$(date)] BUILD START" >> /tmp/build.log
./gradlew assembleRelease --no-daemon 2>&1 >> /tmp/build.log
echo "BUILD_EXIT:$?" >> /tmp/build.log
echo "[$(date)] BUILD END" >> /tmp/build.log
