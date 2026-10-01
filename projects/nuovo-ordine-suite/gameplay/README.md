# Nuovo Ordine gameplay

Modulo Forge 1.20.1 server-side per i sistemi di gioco approvati.

- Ferite: sanguinamento a livelli, danno periodico, bendaggio configurabile e cure interrompibili se il giocatore si muove o subisce danni.
- Reputazione: valori civile/clandestina persistenti nel player data; comandi staff per integrazione con quest, contratti ed eventi.
- Contatto medico: `/medico`, sblocco tramite reputazione civile, trattamento interrompibile e costo via TNE `money take` al completamento.
- Classifiche: `/classifica giocatori <civil|underground|convoys>` e `/classifica town <...>`; il nome Towny viene letto con adapter opzionale a runtime.
- Convogli: programmazione staff, candidature degli autisti, partenza programmata, consegna alla destinazione e fase PvP di conquista del carico.

Il modulo non guida autonomamente veicoli MTS: il convoglio resta intenzionalmente player-driven. Il collegamento diretto a un'entita MTS e il garage avanzato vengono mantenuti come integrazioni separate.
