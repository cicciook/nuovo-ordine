Nuovo Ordine Launcher 1.4.3 — hotfix download modpack.

- I download del modpack ora usano un User-Agent compatibile con i CDN che rifiutano il client predefinito di Python.
- Aggiunto fallback automatico tra mediafilez.forgecdn.net e edge.forgecdn.net per i file CurseForge ancora presenti nel pack.
- Aumentati i timeout per le mod di grandi dimensioni e mantenuti SHA-256 e dimensione come verifica obbligatoria.
- Se un download fallisce, il launcher mostra finalmente il nome esatto del file, il codice HTTP e la sorgente invece del generico "Controlla Internet".
- Il download di pack.json viene ritentato automaticamente tre volte e segnala separatamente gli errori del manifest.
- Create Contraption Terminals e JRFTL restano distribuiti da Modrinth; Easy Gunpowder è stato sostituito dalla ricetta KubeJS.
- Nessun file dell'installazione attuale viene modificato finché tutti i nuovi download non sono stati verificati.
