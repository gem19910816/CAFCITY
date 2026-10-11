@echo off
REM Check whether the srg client jar is Mojmap-named (method names, not SRG).
REM NeoForge 1.21 uses Mojang mappings at runtime, so this should hold.
REM NOTE: keep this file pure ASCII (BOM-less .bat read as GBK by cmd).
set JAVAP=E:\Minecraft\jave\jdk-21_windows-x64_bin\jdk-21.0.3\bin\javap.exe
set JAR=E:\Minecraft\PCL2\.minecraft\libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar
set OUT=build\disasm-srg
if not exist "%OUT%" mkdir "%OUT%"

%JAVAP% -p -cp "%JAR%" net.minecraft.resources.ResourceLocation > "%OUT%\ResourceLocation.txt" 2>&1
%JAVAP% -p -cp "%JAR%" net.minecraft.nbt.CompoundTag > "%OUT%\CompoundTag.txt" 2>&1
%JAVAP% -p -cp "%JAR%" net.minecraft.nbt.Tag > "%OUT%\Tag.txt" 2>&1
echo DONE
