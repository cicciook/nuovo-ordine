#define MyAppName "Nuovo Ordine Launcher"
#define MyAppPublisher "Nuovo Ordine"
#define MyAppExeName "NuovoOrdine.exe"
#ifndef AppVersion
  #define AppVersion "1.4.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\dist\NuovoOrdine"
#endif
#ifndef OutputDir
  #define OutputDir "..\dist"
#endif
#ifndef Arch
  #define Arch "amd64"
#endif

[Setup]
AppId={{F321B249-53B0-4E70-B583-9B21D5C46D4A}
AppName={#MyAppName}
AppVersion={#AppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\NuovoOrdine
DefaultGroupName=Nuovo Ordine
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir={#OutputDir}
OutputBaseFilename=NuovoOrdine-Setup-windows-{#Arch}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupLogging=yes
CloseApplications=yes
RestartApplications=no
UninstallDisplayIcon={app}\{#MyAppExeName}

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Nuovo Ordine Launcher"; Filename: "{app}\{#MyAppExeName}"
Name: "{userdesktop}\Nuovo Ordine Launcher"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Crea un collegamento sul desktop"; GroupDescription: "Collegamenti:"; Flags: unchecked

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Avvia Nuovo Ordine Launcher"; Flags: nowait postinstall skipifsilent
