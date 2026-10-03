# Armeria Browser 1.3.2

Hotfix per Nuovo Ordine / Forge 1.20.1 + Mohist.

- bridge MCEF a iniezione JavaScript/console (non dipende da CefMessageRouter);
- risposta shop inviata direttamente al ServerPlayer con PacketDistributor.PLAYER, evitando SimpleChannel.reply() dopo enqueueWork;
- catalogo automatico ampliato a armi/accessori/munizioni supportati;
- prezzi automatici conservati e resi disponibili nello state JSON.
