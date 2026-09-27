# Nuovo Ordine

Launcher Minecraft 1.20.1 / **Forge 47.4.10** e distribuzione automatica del modpack.

## Scaricare il modpack

Le mod sono distribuite nella sezione [Releases](../../releases), non nella cronologia Git.
Il launcher usa `pack.json` nella radice di questo repository per scaricarle e aggiornarle.

La prima importazione da Drive è gestita da **Actions → Importa modpack da Drive**.
Controlla lì l'esito dell'operazione. Le release del modpack hanno prefisso `pack-`.

## Pubblicare un nuovo aggiornamento comodamente

1. Carica su Drive uno ZIP con la cartella `minecraft` che contiene il modpack client.
2. Rendi il file scaricabile tramite link.
3. In **Settings → Secrets and variables → Actions**, crea o aggiorna il segreto
   `MODPACK_DRIVE_URL` con il link Drive. Non mettere il link nei file pubblici o nei commenti.
4. Apri **Actions → Importa modpack da Drive → Run workflow** e scrivi una versione nuova
   (esempio `2026.09.28-1`). Per la prima importazione lascia vuoto: userà `2026.09.27-1`.
5. Avvia: GitHub scarica il file, prepara la release e aggiorna il manifest del launcher.

Vengono importati solo JAR principali di `mods` e le cartelle `config`, `defaultconfigs`,
`kubejs`, `resourcepacks`, `shaderpacks`, `scripts`, `tacz` e `customnpcs`.
Sono esclusi log, cache, mappe visitate, server memorizzati, screenshot, profili locali,
file nascosti e due configurazioni contenenti credenziali locali (`resourceful-config-web.json`
e `watermedia.toml`). Le mod rigenerano le configurazioni escluse con i valori predefiniti.
Prima di importare nuovi archivi, non inserirvi altri segreti o configurazioni private.

I JAR sono asset separati: quelli invariati non vengono riscaricati. TaCZ e configurazioni
sono raccolti in `extras.zip`, scaricato una volta quando serve aggiornare uno dei suoi file.
Ogni file e archivio ha un hash SHA-256 verificato dal launcher.

## Launcher

Consulta [LEGGIMI.md](LEGGIMI.md) per avvio e compilazione.
Repository e versione Forge sono configurati. **Mancano ancora indirizzo del server e
Client ID Microsoft abilitato**: inserirli rispettivamente in `pack-settings.json` e
`launcher-config.json` prima della distribuzione ai giocatori.

I file in `images` e il file `veicoli` già presenti nel repository sono mantenuti.
