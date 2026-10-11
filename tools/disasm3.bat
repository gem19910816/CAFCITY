@echo off
REM Dump real Minecraft 1.21.1 classes from the SRG client jar.
REM The patch jar (neoforge-*-client.jar) does NOT contain these classes,
REM so this uses the full SRG-mapped client jar instead.
REM NOTE: keep this file pure ASCII (BOM-less .bat read as GBK by cmd).
set JAVAP=E:\Minecraft\jave\jdk-21_windows-x64_bin\jdk-21.0.3\bin\javap.exe
set JAR=E:\Minecraft\PCL2\.minecraft\libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar
set OUT=build\disasm-srg
if not exist "%OUT%" mkdir "%OUT%"

%JAVAP% -c -p -cp "%JAR%" net.minecraft.server.packs.repository.FolderRepositorySource > "%OUT%\FolderRepositorySource.txt" 2>&1
%JAVAP% -c -p -cp "%JAR%" net.minecraft.server.packs.repository.PackSelectionConfig > "%OUT%\PackSelectionConfig.txt" 2>&1
%JAVAP% -c -p -cp "%JAR%" "net.minecraft.server.packs.repository.Pack$Position" > "%OUT%\PackPosition.txt" 2>&1
%JAVAP% -c -p -cp "%JAR%" net.minecraft.server.packs.repository.ServerPacksSource > "%OUT%\ServerPacksSource.txt" 2>&1
%JAVAP% -p -cp "%JAR%" net.minecraft.server.packs.repository.Pack > "%OUT%\PackSigs.txt" 2>&1
%JAVAP% -c -p -cp "%JAR%" net.minecraft.server.packs.repository.PackRepository > "%OUT%\PackRepository.txt" 2>&1
echo DONE
