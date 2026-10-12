@echo off
set "JDK=%CAF_JDK%"
set "MCJAR=%CAF_MINECRAFT_CLIENT%"
if "%JDK%"=="" echo Set CAF_JDK and CAF_MINECRAFT_CLIENT first.& exit /b 2
"%JDK%\bin\javap.exe" -p -c -cp "%MCJAR%" "net.minecraft.server.packs.repository.Pack$Position" > "%~dp0..\build\disasm\PackPosition.txt" 2>&1
