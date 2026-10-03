# Lootr More Tactical Loot 1.1.18

Aggiornamento per Minecraft 1.20.1 / Forge, costruito sulla 1.1.17.

## Novita
- Aggiunti al loot quattro tier di Sophisticated Backpacks:
  - `sophisticatedbackpacks:backpack` (standard)
  - `sophisticatedbackpacks:copper_backpack`
  - `sophisticatedbackpacks:iron_backpack`
  - `sophisticatedbackpacks:gold_backpack`
- Rarita zaini decrescente: standard > copper > iron > gold.
- Aggiunti gli 8 silenziatori TACZ base direttamente nel pool ACCESSORIES con peso 24 ciascuno.
- Portato a peso 5 il loot dei silenziatori riconoscibili negli addon ARIPS/MaxStuff/MS-Mobius.
- Conservate tutte le 470 righe armi/accessori e le 93 righe munizioni della 1.1.17, incluse le munizioni Endless Ammo.
- Restano escluse le armi già vietate (RPG/lanciatori e cecchini pesanti).
- Nuovo marker 1118: le casse Lootr già viste possono ricevere una singola nuova passata, se hanno slot liberi.

## Build
`build_tables.py` deriva WEAPONS.tsv e AMMO.tsv dalle tabelle 1.1.17 senza rigenerare il catalogo.
`Patch.java` sostituisce le tabelle, aggiunge zaini/silenziatori al pool accessori e aggiorna la versione a 1.1.18.
