@echo off
REM Check whether the srg client jar is Mojmap-named (method names, not SRG).
REM NeoForge 1.21 uses Mojang mappings at runtime, so this should hold.
REM NOTE: keep this file pure ASCII (BOM-less .bat read as GBK by cmd).
set "JAVAP=%CAF_JDK%\bin\javap.exe"
set "JAR=%CAF_MINECRAFT_CLIENT%"
if "%CAF_JDK%"=="" echo Set CAF_JDK and CAF_MINECRAFT_CLIENT first.& exit /b 2
set OUT=build\disasm-srg
if not exist "%OUT%" mkdir "%OUT%"

%JAVAP% -p -cp "%JAR%" net.minecraft.resources.ResourceLocation > "%OUT%\ResourceLocation.txt" 2>&1
%JAVAP% -p -cp "%JAR%" net.minecraft.nbt.CompoundTag > "%OUT%\CompoundTag.txt" 2>&1
%JAVAP% -p -cp "%JAR%" net.minecraft.nbt.Tag > "%OUT%\Tag.txt" 2>&1
echo DONE
