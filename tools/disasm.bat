@echo off
rem Dump javap bytecode to files. cmd opens the output files directly (no pipes).
set JDK=E:\Minecraft\jave\jdk-21_windows-x64_bin\jdk-21.0.3
set UNIV=E:\Minecraft\PCL2\.minecraft\libraries\net\neoforged\neoforge\21.1.252\neoforge-21.1.252-universal.jar
set CLIENT=E:\Minecraft\PCL2\.minecraft\libraries\net\neoforged\neoforge\21.1.252\neoforge-21.1.252-client.jar
set OUT=%~dp0..\build\disasm
if not exist "%OUT%" mkdir "%OUT%"
"%JDK%\bin\javap.exe" -c -p -cp "%UNIV%;%CLIENT%" net.neoforged.neoforge.resource.ResourcePackLoader > "%OUT%\ResourcePackLoader.txt" 2>&1
"%JDK%\bin\javap.exe" -c -p -cp "%CLIENT%" net.minecraft.server.packs.repository.PackRepository > "%OUT%\PackRepository.txt" 2>&1
"%JDK%\bin\javap.exe" -c -p -cp "%CLIENT%" net.minecraft.server.packs.repository.BuiltInPackSource > "%OUT%\BuiltInPackSource.txt" 2>&1
echo DISASM-DONE
"%JDK%\bin\javap.exe" -c -p -cp "%CLIENT%" net.minecraft.server.packs.repository.Pack$Position > "%OUT%\PackPosition.txt" 2>&1
"%JDK%\bin\javap.exe" -p -c -cp "%CLIENT%" net.minecraft.server.packs.repository.FolderRepositorySource > "%OUT%\FolderRepositorySource.txt" 2>&1
"%JDK%\bin\javap.exe" -p -cp "%CLIENT%" net.minecraft.server.packs.repository.Pack$SelectionConfig > "%OUT%\PackSelectionConfig.txt" 2>&1
echo DISASM-DONE2
"%JDK%\bin\javap.exe" -c -p -cp "%CLIENT%;%UNIV%" net.minecraft.server.packs.resources.MultiPackResourceManager > "%OUT%\MultiPackResourceManager.txt" 2>&1
"%JDK%\bin\javap.exe" -c -p -cp "%CLIENT%;%UNIV%" net.neoforged.neoforge.resource.ResourcePackLoader > "%OUT%\ResourcePackLoaderC.txt" 2>&1
echo DISASM-DONE3
set FML=E:\Minecraft\PCL2\.minecraft\libraries\net\neoforged\fancymodloader\loader\4.0.44\loader-4.0.44.jar
"%JDK%\bin\javap.exe" -c -p -cp "%FML%;%UNIV%" net.neoforged.fml.loading.moddiscovery.locators.ModsFolderLocator > "%OUT%\ModsFolderLocator.txt" 2>&1
"%JDK%\bin\javap.exe" -c -p -cp "%FML%;%UNIV%" net.neoforged.fml.loading.moddiscovery.locators.PathBasedLocator > "%OUT%\PathBasedLocator.txt" 2>&1
"%JDK%\bin\javap.exe" -c -p -cp "%FML%;%UNIV%" net.neoforged.fml.loading.ClasspathLocatorUtils > "%OUT%\ClasspathLocatorUtils.txt" 2>&1
echo DISASM-DONE4
"%JDK%\bin\javap.exe" -c -p -cp "%FML%" net.neoforged.fml.lowcodemod.LowCodeModLanguageProvider > "%OUT%\LowCode.txt" 2>&1
"%JDK%\bin\javap.exe" -c -p -cp "%FML%" net.neoforged.fml.lowcodemod.LowCodeModContainer > "%OUT%\LowCodeContainer.txt" 2>&1
echo DISASM-DONE5
