# Nuovo Ordine — mercato, aspetto, Towny e PvP

Minecraft **1.20.1**, Forge **47.4.13+**, Java **17**. Integrazioni esaminate sul pack: MCEF 2.1.6, TACZ 1.1.8-hotfix, Superb Warfare 0.8.9.1-hotfix, Xaero Minimap 26.5.0 e World Map 1.46.0. Towny richiede il server ibrido Mohist già usato dal progetto.

## Installazione

| JAR | Client | Server |
| --- | --- | --- |
| nuovo-ordine-market-1.0.0.jar | Sì, con MCEF | Sì |
| nuovo-ordine-cosmetics-1.0.0.jar | Sì | Sì |
| nuovo-ordine-townnames-1.0.0.jar | Sì | Sì, con Towny |
| nuovo-ordine-pvp-1.0.0.jar | Facoltativo | Sì |

Copia i JAR in `mods/`, non in `plugins/`. Riavvia client e server insieme dopo l'installazione. Il launcher distribuisce i file elencati in `pack.json`; il server va aggiornato separatamente.

## Mercato Nero

Apri **`/mercatonero`**. Tieni in mano l'oggetto per pubblicare l'annuncio: il server registra nome, quantità e ID dell'oggetto, mentre scegli titolo, descrizione e prezzo in denaro o materiali. Massimo 10 annunci per persona, scadenza 7 giorni.

Gli annunci sono pubblici. Le risposte sono conversazioni private fra venditore e singolo interessato; gli altri compratori non possono leggerle. Il venditore può chiudere l'annuncio. Limiti: 30 conversazioni e 100 messaggi per conversazione. Per un annuncio con molte risposte, apri «Cronologia completa» per leggere tutti i messaggi della conversazione.

«Proponi incontro qui» usa la posizione e la dimensione attuali del giocatore sul server. L'altro partecipante deve accettare. Durata selezionabile da 15 a 180 minuti nell'interfaccia. Ciascuno può annullare l'incontro. Chiudere l'annuncio annulla gli incontri associati.

Dopo la conferma, **«Attiva waypoint temporaneo Xaero»** abilita il segnaposto solo per chi preme il pulsante. Richiede **Xaero Minimap**, che fornisce i waypoint visualizzati anche da World Map. Compare quando sei nella dimensione dell'incontro, si rimuove alla scadenza, all'annullamento (entro 5 secondi), alla chiusura dell'annuncio o alla disconnessione. Nessun teletrasporto viene eseguito.

**Gli scambi avvengono di persona.** L'annuncio non trasferisce, prenota o trattiene oggetti e non addebita denaro; il prezzo è una proposta di scambio. Usa i normali strumenti del server per consegnare oggetti e pagare. Non c'è acquisto automatico né garanzia di disponibilità dopo la pubblicazione.

Dati nel salvataggio del mondo, `nuovoordine/market.json`, conservati al riavvio. Scrittura temporanea seguita da sostituzione del file; se il salvataggio fallisce, l'operazione non viene applicata in memoria. Gli annunci scaduti sono esclusi immediatamente e rimossi al successivo salvataggio.

## Skin e laboratorio mantelli — launcher 1.2.0

Il pulsante **Skin e mantelli** importa una skin PNG **64×64**, classica o Alex, e mantelli PNG **64×32** (massimo 32 KB ciascuno). Il laboratorio permette colori, motivi iniziali e disegno pixel per pixel su retro e interno del mantello. Si applicano al successivo ingresso nel server.

Il launcher salva i PNG in `minecraft/config/nuovoordine-cosmetics/`. La mod invia solo il proprio aspetto tramite la connessione Minecraft autenticata: nessun hosting immagini né cambio dell'account Microsoft. Tutti i giocatori con la mod vedono skin e mantelli personalizzati. «Ripristina originale» rimuove la personalizzazione al prossimo ingresso. Il mantello deve essere abilitato anche nelle opzioni di personalizzazione skin di Minecraft.

I file rimangono sul client e vengono reinviati a ogni collegamento. Sul server sono conservati in memoria per la sessione. Skin/mantelli ufficiali rimangono il fallback. Il changelog mostra soltanto l'ultima versione del launcher.

## Nomi Towny

Il server legge appartenenza Towny ogni 5 secondi e aggiunge `[Town • Nazione]` accanto al nome sopra la testa. Non modifica scoreboard, chat o dati Towny. Aggiornamenti all'ingresso, all'uscita e ai cambi di appartenenza. Configurazione `config/notownnames-common.toml`: `display="town"`, `"nation"` oppure `"both"`.

## Bilanciamento PvP

Solo i danni **ai giocatori** sono ridotti; il danno inflitto ai mob resta invariato. Default: moltiplicatore **0,55**, tetto complessivo **60% della salute massima** per attaccante/vittima nella finestra di **2 tick**. Il limite aggrega pellet e danno perforante nello stesso breve intervallo, quindi un colpo al corpo non abbatte un giocatore a salute piena. Un giocatore già ferito può morire; attaccanti diversi hanno budget distinti. Danno ambientale non attribuito a un'arma rimane invariato.

Eccezione: headshot di un cecchino riconosciuto dagli eventi TACZ/Superb mantiene il danno originale. Non si forza un'uccisione se il danno originario non sarebbe sufficiente. TACZ usa il tipo `sniper` del gunpack, inclusi addon; Superb parte dai 9 cecchini classificati `Sniper` nel JAR del pack. Puoi estendere `sniperIds` in `config/nopvp-common.toml`. Se una mod non espone un headshot riconoscibile, si applica il nerf normale. Armi che modificano direttamente la salute senza gli eventi Forge non possono essere garantite da questo filtro.

## Build e test

`./gradlew test build` dalla cartella della suite. JAR finali reobfuscati in `*/build/libs/`. Non distribuire classi compilate con stub né JAR di sviluppo.

- Test unitari Java: privacy/autorizzazione, annullamento e scadenza appuntamenti, persistenza e fallimento di scrittura; limite cumulativo dei danni; formato e limiti PNG.
- Launcher: `python -m pytest -q` dalla radice.
- Interfaccia mercato: `npm install --no-save playwright@1.58.2`, `npx playwright install chromium`, `node tools/test_market_ui.cjs` dalla radice. Il test usa un server simulato, non dimostra l'integrazione MCEF.
- `bash tools/smoke_suite.sh`: carica i JAR su un runtime Forge dedicato fino al controllo EULA, senza accettarlo. Non è una prova multiplayer né una prova di compatibilità Mohist.

Prima dell'uso con i giocatori, verificare sul server di prova: due client reali MCEF, waypoint in Overworld/Nether, restart con annunci salvati, cambi Towny, skin classica/Alex e mantelli, TACZ/Superb con armature e headshot. Queste prove richiedono il server/modpack reale e non sono sostituite dai test automatici.
