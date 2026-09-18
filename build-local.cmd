@echo off
REM Build ZooKeeper locally: force fork to the real JDK8 javac, then lay out
REM build\classes + build\lib so the bin scripts can run.
REM Usage: double-click, or run  build-local.cmd  in cmd.
setlocal
if not defined ZK_JDK8 set "ZK_JDK8=C:\Program Files\Java\jdk1.8.0_503"
set "JAVA_HOME=%ZK_JDK8%"
set "ROOT=%~dp0"
cd /d "%ROOT%"

echo === [1/3] Maven compile+install (fork real JDK8 javac) ===
call mvn -DskipTests -pl zookeeper-jute,zookeeper-server -am clean install -Dmaven.compiler.fork=true -Dmaven.compiler.executable="%ZK_JDK8%\bin\javac"
if errorlevel 1 goto :err

echo === [2/3] Copy runtime deps to build\lib (incl. zookeeper-jute jar) ===
call mvn -pl zookeeper-server dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="%ROOT%build\lib"
if errorlevel 1 goto :err

echo === [3/3] Copy server classes to build\classes ===
if not exist "%ROOT%build\classes" mkdir "%ROOT%build\classes"
xcopy /E /I /Y "%ROOT%zookeeper-server\target\classes\*" "%ROOT%build\classes\" >nul
if errorlevel 1 goto :err

echo.
echo ============================================
echo  Build done.
echo   Start server:  run-server.cmd
echo   Client:        run-cli.cmd ls /
echo ============================================
endlocal
exit /b 0

:err
echo.
echo [X] Build failed. See output above.
endlocal
exit /b 1
