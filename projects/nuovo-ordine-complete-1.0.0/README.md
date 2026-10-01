# Nuovo Ordine Complete 1.0.0

Modulo Forge 1.20.1 principalmente server-side che completa i sistemi gameplay di Nuovo Ordine senza sostituire Mercato Nero, Cosmetics, TownNames o il bilanciamento PvP già esistenti.

## Sistemi inclusi

- punti strategici e campagne Towny/Nation con conquista, contestazione, influenza, eventi caldi e Legacy stagionale;
- money sink tramite Vault/TNE: cure, bendaggi, assicurazioni, recuperi/spawn veicoli, commissioni contratti e tassa usato;
- contratti player-funded: bounty, eliminate, delivery, vehicle, transport, escort e capture;
- reputazione Civile / Criminale / Militare e anti-farming PvP;
- ferite: sanguinamento, gambe/testa, bendaggio e medico interrompibili;
- airdrop configurabili e automatici;
- convogli player-driven con veicoli MTS, consegna e carico conteso;
- metadati garage: proprietà, targa, km, condizione, distruzione, assicurazione e cooldown;
- mercato veicoli usati con trasferimento proprietà e tassa;
- classifiche e statistiche persistenti;
- API live read-only per il launcher su `/api/all`, `/api/status`, `/health` (porta predefinita 8765).

Il vecchio `nuovo-ordine-gameplay-1.0.0.jar` è superseded da questo modulo e non deve essere installato assieme a Complete.

## Installazione server

1. Metti `nuovo-ordine-complete-1.0.0.jar` nella cartella `mods` del server Mohist/Forge 1.20.1.
2. Rimuovi `nuovo-ordine-gameplay-1.0.0.jar` se è presente.
3. Avvia una volta il server. Verranno creati:
   - `config/nuovoordine-complete.properties`
   - `config/nuovoordine-complete-state.dat.gz`
4. Per le funzioni economiche tieni attivo Vault con The New Economy compatibile tramite Vault.
5. Per Town/Nation la mod rileva Towny a runtime; senza Towny usa team/player come fallback.
6. Per mostrare il live Hub ai launcher remoti, consenti TCP 8765 nel firewall/NAT oppure cambia `hub.port` e imposta lo stesso URL nel launcher.

## Setup OP iniziale

Punto strategico nella posizione attuale:

`/no point add raffineria_nord Raffineria Nord`

Punto airdrop:

`/no airdrop add aeroporto Aeroporto`

Route convoglio: esegui il primo comando alla partenza e il secondo all'arrivo:

`/no convoy startpoint porto_citta`

`/no convoy endpoint porto_citta`

Registra un veicolo già acquistato/assegnato al giocatore:

`/no garage grant Player vehicle_key Nome del Veicolo`

Controllo generale:

`/no status`

`/no help`

## Comandi giocatore principali

- `/no status`, `/no stats`, `/no rep`
- `/no ranking influence|kills|contracts|loot|wealth|rep|vehicles`
- `/no contract list|mine|post|accept|deliver|approve|cancel`
- `/no airdrop status|claim`
- `/no convoy status|join|claim`
- `/no garage list|insure|prepare`
- `/no usato list|sell|buy|cancel`
- `/no bendaggio`, `/no medico`

## Nota MTS / concessionaria-garage

Complete gestisce il lato server autorevole di proprietà, costi, assicurazione, cooldown, targa e chilometri. **Non ricrea da zero lo spawn grafico/fisico dei veicoli MTS**: `/no garage prepare <key>` autorizza/prepara il mezzo e l'effettivo spawn continua a essere eseguito dalla mod `concessionaria-garage` già usata dal server. Questo evita due sistemi di spawn concorrenti.

## Configurazione

I valori importanti sono modificabili in `config/nuovoordine-complete.properties`, tra cui durata cattura, frequenza eventi, target stagione, anti-farming, ferite, costi medici, premi airdrop/convogli, assicurazione, tasse usato e porta Hub.

Dopo una modifica usa `/no reload` oppure riavvia il server. Lo stato persistente viene salvato atomicamente; non modificare il `.dat.gz` a server acceso.

## Reset stagionale

Quando una fazione raggiunge `season.targetScore`, riceve una stella Legacy. Con `season.autoReset=true` vengono azzerati solo influenza, proprietari dei punti ed evento di campagna. Soldi, inventari, costruzioni, reputazione, statistiche e veicoli rimangono invariati.
