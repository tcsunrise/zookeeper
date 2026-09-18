@echo off
REM Start ZooKeeper command-line client.
REM   run-cli.cmd                          interactive, connect 127.0.0.1:2181
REM   run-cli.cmd ls /                      run a single command on default server
REM   run-cli.cmd -server host:2181 ls /    custom server
setlocal
if not defined ZK_JDK8 set "ZK_JDK8=C:\Program Files\Java\jdk1.8.0_503"
set "JAVA_HOME=%ZK_JDK8%"
cd /d "%~dp0"

echo === JAVA_HOME=%JAVA_HOME% ===
if "%~1"=="" (
  call bin\zkCli.cmd -server 127.0.0.1:2181
  goto :done
)
echo %* | findstr /b /c:"-server" >nul
if errorlevel 1 goto :nosrv
call bin\zkCli.cmd %*
goto :done
:nosrv
call bin\zkCli.cmd -server 127.0.0.1:2181 %*
:done
endlocal
