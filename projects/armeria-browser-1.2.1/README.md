# Armeria Browser 1.2.1

Minecraft 1.20.1 / Forge 47, comando `/armeria` sul client e JAR sul client e sul server.

Il pannello OP offre una ricerca tra 464 definizioni TACZ: ARIPS 1.3.0,
MaxStuff Legacy 1.8.3 hotfix, MS-Mobius 1.5.8 ed Endless Ammo 2.0.
La selezione aggiunge l'oggetto al catalogo del negozio dopo aver impostato
prezzo e quantità; non lo mette in vendita automaticamente. L'importazione
dell'oggetto tenuto in mano continua a funzionare, anche per Superb Warfare
e LesRaisins Tactical.

Le definizioni nel JAR sono ricavate dai quattro archivi indicati nel
manifest del launcher; gli archivi degli addon non sono inclusi nel JAR.
Per rigenerarle: `python3 generate_addons.py ARIPS.zip Maxstuff.jar Mobius.zip EndlessAmmo.jar`.

La pagina standard `server/armeria/shop.html` viene aggiornata automaticamente
solo se è identica alla pagina standard 1.1.0. Una pagina personalizzata
rimane intatta; per vedere il selettore, sostituire manualmente `shop.html`
con `resources/armeria_default/shop.html`, conservando eventuali modifiche.
Il catalogo già configurato (`config/armeria/catalogo.json`) viene mantenuto.

Build: `python3 build.py --deps /path/to/compile-jars --test` (Java 17).
