#!/bin/bash
adb connect 127.0.0.1
adb -s 127.0.0.1 install -r ./app/build/outputs/apk/debug/app-debug.apk
adb -s 127.0.0.1 shell monkey -p alin.android.alinos -c android.intent.category.LAUNCHER 1
adb -s 127.0.0.1 logcat -v time --pid=$(adb -s 127.0.0.1 shell pidof -s alin.android.alinos) | tee run.log

