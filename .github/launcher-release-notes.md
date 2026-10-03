Nuovo Ordine Launcher 1.4.4 — hotfix HTTP 503 del pacchetto extras.

- Il pacchetto extras ora ha un secondo percorso di download tramite GitHub Release Asset API.
- Se github.com risponde HTTP 503, il launcher passa automaticamente al mirror API autenticazione-free per asset pubblici.
- Il mirror API usa correttamente Accept: application/octet-stream, evitando di scaricare per errore i metadati JSON.
- Aggiunto backoff progressivo tra i tentativi invece di ripetere immediatamente la stessa richiesta.
- I mirror espliciti nel manifest vengono validati e possono essere usati anche per futuri pacchetti.
- Restano attivi hash SHA-256, controllo dimensione e rollback completo: nessun file viene applicato finché tutti i download non risultano validi.
