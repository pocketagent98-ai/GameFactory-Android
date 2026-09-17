@echo off

rem Minimal Gradle wrapper launcher for Windows.

set DIRNAME=%~dp0
if "%JAVA_HOME%"=="" (
    java -classpath "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
) else (
    "%JAVA_HOME%\bin\java" -classpath "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
)
