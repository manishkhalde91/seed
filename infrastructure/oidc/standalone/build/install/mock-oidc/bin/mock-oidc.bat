@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  mock-oidc startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables, and ensure extensions are enabled
setlocal EnableExtensions

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%..

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and MOCK_OIDC_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

1>&2 echo.
1>&2 echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
1>&2 echo.
1>&2 echo Please set the JAVA_HOME variable in your environment to match the
1>&2 echo location of your Java installation.

"%COMSPEC%" /c exit 1
goto exitWithErrorLevel

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

1>&2 echo.
1>&2 echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
1>&2 echo.
1>&2 echo Please set the JAVA_HOME variable in your environment to match the
1>&2 echo location of your Java installation.

"%COMSPEC%" /c exit 1
goto exitWithErrorLevel

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\lib\mock-oidc.jar;%APP_HOME%\lib\mock-oauth2-server-6.1.5.jar;%APP_HOME%\lib\jackson-databind-3.2.4.jar;%APP_HOME%\lib\jackson-core-3.2.4.jar;%APP_HOME%\lib\jackson-module-kotlin-3.2.4.jar;%APP_HOME%\lib\kotlin-reflect-2.2.21.jar;%APP_HOME%\lib\mockwebserver-5.5.1.jar;%APP_HOME%\lib\mockwebserver3-5.5.1.jar;%APP_HOME%\lib\kotlin-logging-jvm-3.0.5.jar;%APP_HOME%\lib\kotlin-stdlib-jdk8-1.8.0.jar;%APP_HOME%\lib\okhttp-jvm-5.5.1.jar;%APP_HOME%\lib\okio-jvm-3.18.1.jar;%APP_HOME%\lib\kotlin-stdlib-jdk7-1.8.0.jar;%APP_HOME%\lib\kotlin-stdlib-2.2.21.jar;%APP_HOME%\lib\netty-codec-http3-4.2.20.Final.jar;%APP_HOME%\lib\netty-codec-http-4.2.20.Final.jar;%APP_HOME%\lib\netty-codec-dns-4.2.20.Final.jar;%APP_HOME%\lib\freemarker-2.3.35.jar;%APP_HOME%\lib\bcpkix-jdk18on-1.86.jar;%APP_HOME%\lib\oauth2-oidc-sdk-11.38.2.jar;%APP_HOME%\lib\annotations-13.0.jar;%APP_HOME%\lib\jackson-annotations-2.22.jar;%APP_HOME%\lib\netty-codec-compression-4.2.20.Final.jar;%APP_HOME%\lib\netty-codec-native-quic-4.2.20.Final-linux-x86_64.jar;%APP_HOME%\lib\netty-codec-native-quic-4.2.20.Final-linux-aarch_64.jar;%APP_HOME%\lib\netty-codec-native-quic-4.2.20.Final-osx-x86_64.jar;%APP_HOME%\lib\netty-codec-native-quic-4.2.20.Final-osx-aarch_64.jar;%APP_HOME%\lib\netty-codec-native-quic-4.2.20.Final-windows-x86_64.jar;%APP_HOME%\lib\netty-codec-classes-quic-4.2.20.Final.jar;%APP_HOME%\lib\netty-handler-4.2.20.Final.jar;%APP_HOME%\lib\netty-codec-base-4.2.20.Final.jar;%APP_HOME%\lib\netty-transport-native-unix-common-4.2.20.Final.jar;%APP_HOME%\lib\netty-transport-4.2.20.Final.jar;%APP_HOME%\lib\netty-buffer-4.2.20.Final.jar;%APP_HOME%\lib\netty-resolver-4.2.20.Final.jar;%APP_HOME%\lib\netty-common-4.2.20.Final.jar;%APP_HOME%\lib\bcutil-jdk18on-1.86.jar;%APP_HOME%\lib\junit-4.13.2.jar;%APP_HOME%\lib\jcip-annotations-1.0-1.jar;%APP_HOME%\lib\content-type-2.3.jar;%APP_HOME%\lib\json-smart-2.6.0.jar;%APP_HOME%\lib\lang-tag-1.7.jar;%APP_HOME%\lib\nimbus-jose-jwt-10.9.1.jar;%APP_HOME%\lib\slf4j-api-2.0.3.jar;%APP_HOME%\lib\bcprov-jdk18on-1.86.jar;%APP_HOME%\lib\hamcrest-core-1.3.jar;%APP_HOME%\lib\accessors-smart-2.6.0.jar;%APP_HOME%\lib\asm-9.7.1.jar


@rem Execute mock-oidc
@rem endlocal doesn't take effect until after the line is parsed and variables are expanded
@rem which allows us to clear the local environment before executing the java command
endlocal & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %MOCK_OIDC_OPTS%  -classpath "%CLASSPATH%" no.nav.security.mock.oauth2.StandaloneMockOAuth2ServerKt %* & call :exitWithErrorLevel & goto exitWithErrorLevel

@rem This label must not be changed. We rely on old scripts being able to jump to this point.
:exitWithErrorLevel
@rem Use "%COMSPEC%" /c exit to allow operators to work properly in scripts
"%COMSPEC%" /c exit %ERRORLEVEL%
