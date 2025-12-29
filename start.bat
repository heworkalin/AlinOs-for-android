:: 临时设置 JAVA_HOME（替换为你的 JDK 17 根路径）
set JAVA_HOME=C:\Users\123\AppData\Roaming\.minecraft\runtime\java-runtime-gamma-snapshot

:: 临时将 JDK 的 bin 目录添加到 PATH（优先使用这个 JDK）
set PATH=%JAVA_HOME%\bin;%PATH%

:: 验证临时配置是否生效（必须显示 javac 版本）
java -version
javac -version

echo "D:\Android\app\AlinOs\gradlew.bat"
echo "gradle clean"
echo "gradle assembleDebug"
echo "gradle assembleRelease"