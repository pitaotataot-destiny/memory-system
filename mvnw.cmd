@echo off
REM Maven wrapper — 强制使用 JDK 17
set JAVA_HOME=%JAVA17_HOME%
mvn %*
