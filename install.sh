#!/bin/bash
APP=alin.android.alinos
adb connect 127.0.0.1
adb -s 127.0.0.1 install -r ./app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
adb -s 127.0.0.1 shell monkey -p $APP -c android.intent.category.LAUNCHER 1
adb -s 127.0.0.1 logcat -v time --pid=$(adb -s 127.0.0.1 shell pidof -s $APP) | tee run.log

