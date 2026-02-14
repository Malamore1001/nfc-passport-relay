#!/bin/bash
export ANDROID_HOME=~/android-sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin

# Download Gradle
cd ~
curl -sLO https://services.gradle.org/distributions/gradle-8.2-bin.zip
unzip -qo gradle-8.2-bin.zip
export PATH=$PATH:~/gradle-8.2/bin

# Build Reader APK
cd ~/android-reader
echo 'sdk.dir=/home/ec2-user/android-sdk' > local.properties
gradle assembleDebug --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk ~/nfc-reader.apk

# Build Emulator APK
cd ~/android-emulator
echo 'sdk.dir=/home/ec2-user/android-sdk' > local.properties
gradle assembleDebug --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk ~/nfc-emulator.apk

echo 'APKs built!'
ls -la ~/*.apk
