@echo off
rem Dump javap bytecode to files. cmd opens the output files directly (no pipes).
set JDK=E:\Minecraft\jave\jdk-21_windows-x64_bin\jdk-21.0.3
set CLIENT=E:\Minecraft\PCL2\.minecraft\libraries\net\neoforged\neoforge\21.1.252\neoforge-21.1.252-client.jar
set UNIV=E:\Minecraft\PCL2\.minecraft\libraries\net\neoforged\neoforge\21.1.252\neoforge-21.1.252-universal.jar
set OUT=%~dp0..\build\disasm
if not exist "%OUT%" mkdir "%OUT%"
"%JDK%\bin\javap.exe" -c -p -cp "%CLIENT%;%UNIV%" net.minecraft.server.packs.repository.ServerPacksSource > "%OUT%\ServerPacksSource.txt" 2>&1
"%JDK%\bin\javap.exe" -p -cp "%CLIENT%;%UNIV%" net.minecraft.server.packs.repository.Pack > "%OUT%\PackSigs.txt" 2>&1
"%JDK%\bin\javap.exe" -p -cp "%CLIENT%;%UNIV%" net.minecraft.server.packs.PackResources > "%OUT%\PackResourcesSigs.txt" 2>&1
"%JDK%\bin\javap.exe" -p -cp "%CLIENT%;%UNIV%" net.minecraft.server.packs.PackType > "%OUT%\PackTypeSigs.txt" 2>&1
echo DISASM2-DONE
