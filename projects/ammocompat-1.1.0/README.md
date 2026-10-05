# TaCZ Superb Warfare Ammo Compat 1.1.1

Minecraft 1.20.1 / Forge 47.x.

Bidirectional ammo bridge between TaCZ and Superb Warfare.

This release adds explicit compatibility for conventional ammunition from:
- ARIPS 1.3.0
- MaxStuff Legacy 1.8.3 hotfix
- MS-Mobius Gunspack 1.5.8
- Endless Ammo 2.0

Heavy anti-materiel, grenade/rocket and special ammo (laser, arrows, nails, etc.) is intentionally excluded from generic Superb Warfare ammo conversion.


## Fix TaCZ / Mohist
- Disabilita lato server `ServerShootNetworkCheck` di TaCZ all'avvio.
- Ritenta al primo tick server del player se TaCZ non era ancora pronto.
- Non disabilita `ServerShootCooldownCheck`, quindi il controllo della cadenza resta attivo.
- Risolve il caso in cui le armi iniziano a sparare solo dopo aver premuto V.

Build modulare 1.1.1 destinata al setup con i JAR Nuovo Ordine separati.

Publish fix definitivo della build 1.1.1.
