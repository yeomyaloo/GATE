@echo off
rem GATE gate-pkg command-line wrapper for Windows:  gate-pkg.cmd check npm lodash@4.17.21
setlocal
if defined GATE_PKG_JAR (set "JAR=%GATE_PKG_JAR%") else (set "JAR=%~dp0..\build\libs\gate-pkg.jar")
if not exist "%JAR%" (
  echo gate-pkg.jar not found at %JAR%. Build it with gradlew :gate-pkg:cliJar 1>&2
  exit /b 3
)
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java.exe") else (set "JAVA=java")
"%JAVA%" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:auto -jar "%JAR%" %*
exit /b %ERRORLEVEL%
