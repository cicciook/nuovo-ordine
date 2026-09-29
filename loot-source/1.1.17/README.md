# Lootr More Tactical Loot 1.1.17

Aggiornamento per Minecraft 1.20.1 / Forge. Mantiene le 165 armi e 211
accessori TACZ di ARIPS, MaxStuff Legacy e MS-Mobius presenti nella 1.1.16.
Aggiunge al pool delle munizioni 42 definizioni di Endless Ammo 2.0; altre
6 erano già presenti perché richieste dalle armi. Il totale è di 470 righe
armi/accessori e 93 righe munizioni. Le esclusioni per RPG, lanciatori e
cecchini pesanti delle armi sono conservate.

Il launcher deve distribuire sia il JAR 1.1.17 sia i quattro addon: modificare
soltanto `pack-settings.json` non aggiorna un `pack.json` già pubblicato.
Le nuove offerte riguardano i successivi roll di loot delle casse Lootr;
le casse già generate possono richiedere il reset previsto dal server.

`extend_endless_ammo.py` genera `AMMO.tsv` e il report dall'archivio Endless
Ammo e dal JAR 1.1.16. `Patch.java` mantiene la tabella armi della 1.1.16 e
installa la nuova tabella munizioni, aggiornando `mods.toml` a 1.1.17.
Gli addon di terzi non sono inclusi nel JAR.
