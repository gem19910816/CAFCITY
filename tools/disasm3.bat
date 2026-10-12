@echo off
REM Dump real Minecraft 1.21.1 classes from the SRG client jar.
REM The patch jar (neoforge-*-client.jar) does NOT contain these classes,
REM so this uses the full SRG-mapped client jar instead.
REM NOTE: keep this file pure ASCII (BOM-less .bat read as GBK by cmd).
set "JAVAP=%CAF_JDK%\bin\javap.exe"
set "JAR=%CAF_MINECRAFT_CLIENT%"
if "%CAF_JDK%"=="" echo Set CAF_JDK and CAF_MINECRAFT_CLIENT first.& exit /b 2
set OUT=build\disasm-srg
if not exist "%OUT%" mkdir "%OUT%"

%JAVAP% -c -p -cp "%JAR%" net.minecraft.server.packs.repository.FolderRepositorySource > "%OUT%\FolderRepositorySource.txt" 2>&1
%JAVAP% -c -p -cp "%JAR%" net.minecraft.server.packs.repository.PackSelectionConfig > "%OUT%\PackSelectionConfig.txt" 2>&1
%JAVAP% -c -p -cp "%JAR%" "net.minecraft.server.packs.repository.Pack$Position" > "%OUT%\PackPosition.txt" 2>&1
%JAVAP% -c -p -cp "%JAR%" net.minecraft.server.packs.repository.ServerPacksSource > "%OUT%\ServerPacksSource.txt" 2>&1
%JAVAP% -p -cp "%JAR%" net.minecraft.server.packs.repository.Pack > "%OUT%\PackSigs.txt" 2>&1
%JAVAP% -c -p -cp "%JAR%" net.minecraft.server.packs.repository.PackRepository > "%OUT%\PackRepository.txt" 2>&1
echo DONE
