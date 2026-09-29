# Armeria Browser 1.2.0 — LesRaisins

Aggiornamento della mod Armeria Browser per Minecraft 1.20.1 / Forge.

## Modifica principale

Il filtro server dell'Armeria accetta ora anche gli item registrati nei namespace `lrtactical:*` e `lesraisins:*`, oltre agli oggetti TACZ e Superb Warfare già supportati.

Compatibilità verificata sul modpack Nuovo Ordine con **LesRaisins Tactical Equipements 0.4.3** (`modId: lrtactical`).

## File modificati

- `src/com/armeria/AmmoSupport.java`: rilevamento degli item LesRaisins tramite Forge registry.
- `resources/META-INF/mods.toml`: versione 1.2.0 e dipendenza opzionale `lrtactical [0.4,0.5)`.
- `build.py`: output aggiornato a `armeria-browser-1.2.0.jar`.

La build runtime prodotta localmente è `armeria-browser-1.2.0.jar`. SHA-256 build completa: `0fbb3c5ab83174927b7114c7e9b0fbcd0137882b3e5510456701b82ae2c6b1db`.

Nota: questa cartella contiene la patch sorgente della 1.2.0. Il manifest del launcher non viene puntato a un binario finché il JAR non è pubblicato come file GitHub/release valido.
