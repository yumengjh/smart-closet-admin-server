@echo off
setlocal
set "BASE_DIR=%~dp0"
for /f "tokens=1,* delims==" %%A in (%BASE_DIR%.mvn\wrapper\maven-wrapper.properties) do if "%%A"=="distributionUrl" set "DIST_URL=%%B"
for /f "tokens=1,* delims==" %%A in (%BASE_DIR%.mvn\wrapper\maven-wrapper.properties) do if "%%A"=="distributionSha512Sum" set "DIST_SHA512=%%B"
for %%A in (%DIST_URL%) do set "DIST_NAME=%%~nxA"
set "MAVEN_DIR=%DIST_NAME:-bin.zip=%"
if defined MAVEN_USER_HOME (set "WRAPPER_ROOT=%MAVEN_USER_HOME%") else (set "WRAPPER_ROOT=%USERPROFILE%\.m2")
set "WRAPPER_HOME=%WRAPPER_ROOT%\wrapper\dists\%MAVEN_DIR%"
if not exist "%WRAPPER_HOME%\bin\mvn.cmd" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $tmp='%WRAPPER_HOME%.tmp'; New-Item -ItemType Directory -Force $tmp | Out-Null; $archive=$tmp+'\%DIST_NAME%'; Invoke-WebRequest '%DIST_URL%' -OutFile $archive; $actual=(Get-FileHash -Algorithm SHA512 $archive).Hash.ToLowerInvariant(); if ($actual -ne '%DIST_SHA512%') { throw 'Maven distribution checksum verification failed.' }; Expand-Archive $archive $tmp -Force; Remove-Item $archive; Move-Item ($tmp+'\%MAVEN_DIR%') '%WRAPPER_HOME%'; Remove-Item $tmp"
  if errorlevel 1 exit /b 1
)
call "%WRAPPER_HOME%\bin\mvn.cmd" -f "%BASE_DIR%pom.xml" %*
