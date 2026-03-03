@echo off
chcp 65001 >nul
set JAVA_HOME=C:\Program Files\Java\jdk-11
set PATH=%JAVA_HOME%\bin;%PATH%
set JPDA_ADDRESS=8001
set JPDA_TRANSPORT=dt_socket
cd /d "D:\utils\apache\apache-tomcat-8.5.68-windows-x64\apache-tomcat-8.5.68-teamagent\bin"
@REM call catalina.bat jpda run
call catalina-jrebel.bat jpda run
