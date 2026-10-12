@echo off
rem Dump javap bytecode to files. cmd opens the output files directly (no pipes).
set "JDK=%CAF_JDK%"
set "CLIENT=%CAF_NEOFORGE_CLIENT%"
set "UNIV=%CAF_NEOFORGE_UNIVERSAL%"
if "%JDK%"=="" echo Set CAF_JDK, CAF_NEOFORGE_CLIENT and CAF_NEOFORGE_UNIVERSAL first.& exit /b 2
set OUT=%~dp0..\build\disasm
if not exist "%OUT%" mkdir "%OUT%"
"%JDK%\bin\javap.exe" -c -p -cp "%CLIENT%;%UNIV%" net.minecraft.server.packs.repository.ServerPacksSource > "%OUT%\ServerPacksSource.txt" 2>&1
"%JDK%\bin\javap.exe" -p -cp "%CLIENT%;%UNIV%" net.minecraft.server.packs.repository.Pack > "%OUT%\PackSigs.txt" 2>&1
"%JDK%\bin\javap.exe" -p -cp "%CLIENT%;%UNIV%" net.minecraft.server.packs.PackResources > "%OUT%\PackResourcesSigs.txt" 2>&1
"%JDK%\bin\javap.exe" -p -cp "%CLIENT%;%UNIV%" net.minecraft.server.packs.PackType > "%OUT%\PackTypeSigs.txt" 2>&1
echo DISASM2-DONE
