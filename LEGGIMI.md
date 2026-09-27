# Nuovo Ordine — Launcher 1.0.0

Launcher desktop personalizzato per **Minecraft Java 1.20.1 + Forge**, con interfaccia
italiana, accesso Microsoft nel browser e aggiornamento automatico del modpack da GitHub.

## Cosa contiene questo ZIP

Il **progetto sorgente avviabile**, gli script di pubblicazione delle mod e la procedura
GitHub Actions che compila gli eseguibili. Non contiene ancora un EXE Windows, un'app
macOS o un binario Linux precompilato, né le mod del tuo server.

Gli aggiornamenti automatici riguardano **mod e configurazioni del modpack**:
all'apertura del launcher e prima di ogni avvio del gioco. Il programma del launcher
si aggiorna scaricando una nuova versione dal pulsante **Versioni del launcher**;
questa versione non sostituisce automaticamente il proprio eseguibile.

## Per renderlo utilizzabile dai giocatori

Servono questi dati del proprietario, una sola volta:

1. Un repository GitHub **pubblico**, per esempio `tuonome/nuovo-ordine`.
2. Il Client ID della tua applicazione Microsoft autorizzata alle API Minecraft.
3. Il modpack **client**, la versione esatta Forge e l'indirizzo del server.

I giocatori non devono creare app Microsoft o usare GitHub: riceveranno la build già
configurata e accederanno con il proprio account Microsoft che possiede Minecraft Java.
Non chiedere o inserire password Microsoft nel launcher.

## 1. Provare l'interfaccia subito

Installa [Python 3.12](https://www.python.org/downloads/), estrai tutto lo ZIP e apri
la cartella `nuovo-ordine`.

- **Windows:** doppio clic su `Avvia-Windows.bat`.
- **macOS / Linux:** esegui `bash Avvia-macOS-Linux.sh` dal terminale nella cartella.

Il primo avvio scarica le dipendenze Python. In alternativa:

```sh
python -m venv .venv
# Windows:
.venv\Scripts\python.exe -m pip install -r requirements.txt
.venv\Scripts\python.exe main.py
# macOS / Linux:
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python main.py
```

Senza configurazione puoi vedere l'interfaccia e compilare le impostazioni; il download
del pack e il login richiedono i dati reali. L'istanza Minecraft è separata da `.minecraft`.

## 2. Registrare l'app Microsoft

1. Apri il [portale Microsoft Entra](https://entra.microsoft.com/) e registra
   una nuova applicazione, chiamata **Nuovo Ordine Launcher**. Se il tuo account non
   ha accesso alle registrazioni, occorre predisporre un tenant/account abilitato.
2. Scegli un tipo di account che includa gli **account Microsoft personali**.
3. In **Autenticazione**, aggiungi la piattaforma **Applicazioni mobili e desktop**.
4. Registra esattamente questo URI di reindirizzamento:
   `http://localhost:53682/callback`.
5. Copia l'**ID applicazione (client)** in `launcher-config.json`, campo
   `microsoft_client_id`. Non creare o distribuire un client secret: è un'app pubblica
   che utilizza Authorization Code + PKCE e controllo dello state.
6. Richiedi l'accesso alle API Minecraft tramite il
   [modulo di abilitazione](https://aka.ms/mce-reviewappid), seguendo i requisiti
   correnti Microsoft/Mojang. La registrazione da sola non garantisce l'accesso.
7. Quando l'app è abilitata, prova **Accedi con Microsoft** con un account Java valido.

La pagina Microsoft si apre nel browser predefinito. Il launcher riceve il risultato
su una porta locale, non sul tuo server Minecraft. Il refresh token viene salvato
soltanto nei portachiavi di sistema supportati: Windows Credential Manager, macOS
Keychain, Secret Service/KWallet su Linux. Se non sono disponibili, resta soltanto
in memoria e occorre accedere di nuovo dopo la chiusura. Il launcher non salva password.

Riferimenti:
- [Documentazione del login usata dal progetto](https://minecraft-launcher-lib.readthedocs.io/en/latest/tutorial/microsoft_login.html)
- [Requisiti dei redirect Microsoft](https://learn.microsoft.com/en-us/entra/identity-platform/reply-url)

## 3. Creare il repository GitHub

Installa [Git](https://git-scm.com/) e [GitHub CLI](https://cli.github.com/).
Apri un terminale nella cartella `nuovo-ordine`, dopo avere estratto il pacchetto:

```sh
gh auth login
git init -b main
git add .
git commit -m "Primo launcher Nuovo Ordine"
gh repo create nuovo-ordine --public --source=. --remote=origin --push
```

La GitHub CLI chiederà l'accesso al tuo account. I comandi creano un repository pubblico.
Se ne hai già creato uno, collegalo invece come `origin` e pubblica i file su `main`.
La cartella `.github/workflows` deve essere presente nel repository: contiene le build.

Le mod locali vengono escluse da Git da `.gitignore`: si distribuiscono con le release,
evitando di caricare grossi JAR nella cronologia del repository.

## 4. Configurare il launcher e il pack

Modifica `launcher-config.json` prima di generare gli eseguibili:

```json
{
  "repository": "tuonome/nuovo-ordine",
  "branch": "main",
  "microsoft_client_id": "IL-TUO-CLIENT-ID",
  "server": "play.tuoserver.it:25565",
  "ram_mb": 6144,
  "java_path": ""
}
```

Lascia `java_path` vuoto per il runtime Java automatico; un percorso personalizzato
vale per un singolo PC e non va incorporato in una build distribuita. La RAM è
modificabile dai giocatori: lascia memoria sufficiente al sistema operativo.

In `pack-settings.json`, imposta:

- `forge`: **la versione esatta del server**, nel formato `47.x.x`. `47.4.0` è un
  valore iniziale del progetto, non una verifica della versione del tuo server.
- `server`: IP/dominio, eventualmente con porta. Ha precedenza sul server del launcher.
- `news`: messaggio mostrato nella schermata iniziale.
- `preserve`: percorsi delle configurazioni da creare una volta e poi lasciare al giocatore.

Copia le mod **client** in `modpack/mods` e gli altri file nelle relative cartelle
`config`, `defaultconfigs`, `kubejs`, `resourcepacks`, `shaderpacks` o `scripts`.
Non copiare l'intera cartella del server: niente mondi, plugin, credenziali o mod solo server.

Le mod con librerie native devono supportare il sistema dei giocatori: distribuire
lo stesso pack non rende automaticamente compatibile con macOS/Linux una mod solo Windows.

## 5. Pubblicare le mod e gli aggiornamenti

Usa il Python dell'ambiente virtuale creato sopra. Esempio Windows:

```sh
.venv\Scripts\python.exe tools/publish_pack.py --repo tuonome/nuovo-ordine --version 1.0.0 --publish
```

Su macOS/Linux sostituisci `.venv\Scripts\python.exe` con `.venv/bin/python`.
La GitHub CLI deve essere autenticata con un account che può scrivere nel repository.

Lo script calcola dimensioni e SHA-256, crea la release `pack-1.0.0`, carica i file
e **solo alla fine** aggiorna `pack.json` nel ramo `main`. Non pubblica pack senza mod.
Non conservare PAT o password nei file distribuiti: i giocatori scaricano file pubblici.

Per un aggiornamento modifica i file locali e usa una versione nuova, per esempio:

```sh
.venv\Scripts\python.exe tools/publish_pack.py --repo tuonome/nuovo-ordine --version 1.0.1 --publish
```

Non riutilizzare un numero di release. Il launcher scarica solo i file differenti,
verifica gli hash e rimuove le vecchie mod precedentemente gestite che non sono più nel pack.
Salvataggi, screenshot e mod aggiunte manualmente non vengono rimossi. Le mod manuali
sono segnalate perché possono causare incompatibilità.

Se vuoi preparare i file senza pubblicare, ometti `--publish`: trovi `pack.json` e
gli asset in `release-pack/VERSIONE/`. Per la pubblicazione manuale crea una release
con tag `pack-VERSIONE`, carica tutti gli asset con i loro nomi originali, pubblicala
e soltanto dopo copia `pack.json` nella radice del ramo `main`.

Se una pubblicazione fallisce prima di aggiornare `pack.json`, i giocatori continuano
a usare il pack precedente. Controlla l'eventuale release rimasta in bozza; per riprovare
usa un numero nuovo. Conserva le release precedenti se vuoi poter tornare a quel pack.

### Download da fonti esterne

Per file che non vuoi ospitare su GitHub, puoi inserire in `external_files` elementi così:

```json
{
  "path": "mods/nome-mod.jar",
  "url": "https://dominio-ufficiale.example/percorso-diretto.jar",
  "sha256": "HASH-SHA256-REALE-DI-64-CARATTERI",
  "size": 123456,
  "mode": "replace"
}
```

Usa un URL HTTPS diretto, stabile, senza cookie o credenziali, con dimensione e hash reali.
Non duplicare lo stesso percorso tra cartella locale ed `external_files`. Il launcher
non risolve dipendenze automaticamente da CurseForge/Modrinth: il pack pubblicato deve
essere completo. Verifica di poter distribuire le mod incluse.

## 6. Generare gli eseguibili Windows, macOS e Linux

Dopo avere compilato `launcher-config.json`, pubblica le modifiche:

```sh
git add .
git commit -m "Configura launcher"
git push origin main
```

Nel repository apri **Actions → Compila launcher → Run workflow**.
Quando le tre build finiscono, scarica gli archivi dalla sezione **Artifacts**.
I giocatori non avranno bisogno di Python: devono estrarre l'intero archivio e avviare
`NuovoOrdine.exe` su Windows, `NuovoOrdine.app` su macOS o `NuovoOrdine` su Linux.

Per pubblicare automaticamente le build anche tra le release GitHub:

```sh
git tag launcher-v1.0.0
git push origin launcher-v1.0.0
```

Usa sempre tag `launcher-v...` per il programma e `pack-...` per le mod.
Per compilare localmente sul sistema destinatario:

```sh
python -m pip install -r requirements.txt pyinstaller==6.16.0
python tools/build.py
```

La build Windows è x64, Linux x86-64, macOS Intel x86-64. Su Apple Silicon la build
Intel richiede Rosetta; usa Java x64 coerente con questa build. La compatibilità del
modpack, soprattutto MCEF e altre librerie native, va provata sul sistema reale.
La build macOS usa il runner `macos-15-intel` e va verificata sulle versioni macOS che
vuoi supportare. Gli archivi non sono firmati con un certificato Windows/Apple del
proprietario; la firma e la notarizzazione per distribuzione pubblica non sono incluse.

## Cartelle dei giocatori

Usa **Apri cartella launcher** per trovare il percorso esatto. Di norma:

- Windows: `%LOCALAPPDATA%\NuovoOrdine`
- macOS: `~/Library/Application Support/NuovoOrdine`
- Linux: `~/.local/share/NuovoOrdine` (o il percorso XDG personalizzato)

`minecraft/` contiene l'istanza separata; `settings.json` le preferenze non segrete;
`game-output.log` l'ultimo avvio. Non modificare o cancellare i file di transazione
durante un aggiornamento. Il launcher impedisce due istanze simultanee.

Se non arriva alla schermata di gioco, controlla la versione Java (17), la versione
Forge del server e il modpack client. Il pulsante Gioca aggiorna il pack e verifica la
licenza prima di partire: questa versione richiede Internet e non offre accesso offline.

## Verifiche eseguite e limiti

- 23 test automatici passati: integrità, aggiornamento differenziale, rimozione dei soli
  file gestiti, preservazione configurazioni, percorsi non sicuri, ripristino dopo errori
  e interruzioni, coerenza della pubblicazione, callback Microsoft con PKCE/state.
- Interfaccia avviata e renderizzata su Linux in modalità di test senza schermo.
- Nessun login Microsoft reale, nessun ingresso nel tuo server e nessuna build nativa
  Windows/macOS verificata qui: mancano Client ID abilitato, repository e modpack.
- La connessione al server usa Quick Play di Minecraft 1.20.1; se una mod la modifica,
  il server resta raggiungibile dal menu Multigiocatore.
- Gli hash controllano l'integrità rispetto al manifest ricevuto via HTTPS. Chi controlla
  il repository controlla il codice delle mod eseguite dai giocatori; proteggi l'account
  GitHub e l'accesso in scrittura al repository.

Per rieseguire i test: `python -m pip install pytest==8.3.5` e `python -m pytest -q`.

## Componenti

Interfaccia: PySide6/Qt. Installazione di Minecraft, Forge e Java e protocollo account:
[minecraft-launcher-lib](https://github.com/JakobDev/minecraft-launcher-lib).
I file originali Minecraft/Forge/Java sono scaricati dalle fonti usate dalla libreria,
non sono incorporati in questo ZIP. Le dipendenze mantengono le proprie licenze:
consulta `THIRD_PARTY.md`. Progetto indipendente, non affiliato a Mojang o Microsoft.
