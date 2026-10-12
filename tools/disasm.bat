@echo off
rem Dump javap bytecode to files. cmd opens the output files directly (no pipes).
set "JDK=%CAF_JDK%"
set "UNIV=%CAF_NEOFORGE_UNIVERSAL%"
set "CLIENT=%CAF_NEOFORGE_CLIENT%"
set "FML=%CAF_FML%"
if "%JDK%"=="" echo Set CAF_JDK, CAF_NEOFORGE_UNIVERSAL, CAF_NEOFORGE_CLIENT and CAF_FML first.& exit /b 2
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
"%JDK%\bin\javap.exe" -c -p -cp "%FML%;%UNIV%" net.neoforged.fml.loading.moddiscovery.locators.ModsFolderLocator > "%OUT%\ModsFolderLocator.txt" 2>&1
"%JDK%\bin\javap.exe" -c -p -cp "%FML%;%UNIV%" net.neoforged.fml.loading.moddiscovery.locators.PathBasedLocator > "%OUT%\PathBasedLocator.txt" 2>&1
"%JDK%\bin\javap.exe" -c -p -cp "%FML%;%UNIV%" net.neoforged.fml.loading.ClasspathLocatorUtils > "%OUT%\ClasspathLocatorUtils.txt" 2>&1
echo DISASM-DONE4
"%JDK%\bin\javap.exe" -c -p -cp "%FML%" net.neoforged.fml.lowcodemod.LowCodeModLanguageProvider > "%OUT%\LowCode.txt" 2>&1
"%JDK%\bin\javap.exe" -c -p -cp "%FML%" net.neoforged.fml.lowcodemod.LowCodeModContainer > "%OUT%\LowCodeContainer.txt" 2>&1
echo DISASM-DONE5
