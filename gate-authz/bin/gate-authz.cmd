@echo off
rem GATE gate-authz command-line wrapper for Windows:  gate-authz.cmd scan --config gate-authz.yml
setlocal
if defined GATE_AUTHZ_JAR (set "JAR=%GATE_AUTHZ_JAR%") else (set "JAR=%~dp0..\build\libs\gate-authz.jar")
if not exist "%JAR%" (
  echo gate-authz.jar not found at %JAR%. Build it with gradlew :gate-authz:cliJar 1>&2
  exit /b 3
)
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java.exe") else (set "JAVA=java")
"%JAVA%" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:auto -jar "%JAR%" %*
exit /b %ERRORLEVEL%
