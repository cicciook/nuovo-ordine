# Verifica release 1.0.0 / launcher 1.2.0

Sorgenti compilati: commit `6de7e2c759c6f9888342a509bb11898b925b1142`.
Build GitHub Actions: https://github.com/cicciook/nuovo-ordine/actions/runs/36643553184

- **42 test Python superati** sul launcher, inclusi selezione dell'ultima versione, importazioni PNG, errori e conservazione del file precedente.
- **27 test Java superati**: 12 mercato, 8 bilanciamento danni, 7 validazione PNG.
- **Quattro JAR compilati e reobfuscati** con ForgeGradle su Java 17. I JAR distribuiti sono quelli della build CI, non copie ricompilate con stub.
- **Test browser automatizzato superato**: elenco annunci, dettaglio, risposta privata, proposta di appuntamento, creazione annuncio, escaping del testo HTML e assenza di errori JavaScript. Backend simulato per questa prova.
- **Avvio preliminare Forge dedicato completato fino al controllo EULA**, senza errori di risoluzione mod rilevati. Non è stato accettato l'EULA né avviato un mondo: questo controllo non certifica tutte le inizializzazioni o il gameplay.
- **Interfaccia launcher renderizzata** in Qt offscreen; editor mantelli e disegno pixel testati.
- API degli adapter Xaero 26.5.0, MCEF 2.1.6, TACZ 1.1.8 e Superb 0.8.9.1 esaminate nei JAR del pack.

Non eseguiti: accesso di due client al server Mohist reale, rendering skin/mantelli dentro Minecraft, visualizzazione waypoint e cambi Towny durante una partita, prove balistiche con tutte le combinazioni di armi/armature/addon. Per queste verifiche occorre il server di prova del proprietario. Il limite sul danno usa eventi Forge: non copre armi che scrivono la salute direttamente o comportamenti introdotti da altri plugin dopo l'evento.

Gli scambi del mercato sono manuali e di persona; nessun prelievo automatico da The New Economy è implementato o dichiarato.
