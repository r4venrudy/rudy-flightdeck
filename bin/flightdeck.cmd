@echo off
setlocal
if defined JAVA_HOME goto configured
java -Xmx1g -jar "%~dp0..\lib\rudy-flightdeck-0.1.0-preview.jar" %*
exit /b %ERRORLEVEL%
:configured
"%JAVA_HOME%\bin\java.exe" -Xmx1g -jar "%~dp0..\lib\rudy-flightdeck-0.1.0-preview.jar" %*
exit /b %ERRORLEVEL%
