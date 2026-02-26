@echo off
set JPDA_ADDRESS=8001
set JPDA_TRANSPORT=dt_socket
cd /d "D:\utils\apache\apache-tomcat-8.5.68-windows-x64\apache-tomcat-8.5.68-teamagent\bin"
call catalina.bat jpda run
