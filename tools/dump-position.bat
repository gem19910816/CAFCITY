@echo off
set JDK=E:\Minecraft\jave\jdk-21_windows-x64_bin\jdk-21.0.3
set MCJAR=E:\Minecraft\PCL2\.minecraft\libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar
"%JDK%\bin\javap.exe" -p -c -cp "%MCJAR%" "net.minecraft.server.packs.repository.Pack$Position" > "%~dp0..\build\disasm\PackPosition.txt" 2>&1
