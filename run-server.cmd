@echo off
REM Start ZooKeeper server locally (foreground, standalone, port 2181).
REM Usage: double-click, or run  run-server.cmd  in cmd.
REM To use another JDK8: set ZK_JDK8=your\jdk8\path  before running.
setlocal
if not defined ZK_JDK8 set "ZK_JDK8=C:\Program Files\Java\jdk1.8.0_503"
set "JAVA_HOME=%ZK_JDK8%"
cd /d "%~dp0"

if not exist "%~dp0build\classes" (
  echo [!] build\classes not found. Run build-local.cmd first.
  pause
  exit /b 1
)

echo === JAVA_HOME=%JAVA_HOME% ===
call bin\zkServer.cmd %*
endlocal
