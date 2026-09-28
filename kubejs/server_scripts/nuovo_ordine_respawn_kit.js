// Nuovo Ordine - Kit di respawn configurabile in game
// Minecraft 1.20.1 / KubeJS
//
// Uso da OP:
//   1) Metti nell'inventario (anche armatura/offhand) SOLO gli oggetti che vuoi nel kit.
//   2) /respawnkit salva
//   3) /respawnkit prova
//   4) /respawnkit info
//   5) /respawnkit cancella
//
// Il kit viene ripristinato ESATTAMENTE negli slot salvati dopo una vera morte.

(() => {
  const ListTag = Java.loadClass('net.minecraft.nbt.ListTag')
  const DATA_KEY = 'nuovo_ordine_respawn_kit'

  function getSavedKit(server) {
    const data = server.persistentData
    if (!data.contains(DATA_KEY)) {
      return null
    }
    return data.getList(DATA_KEY, 10)
  }

  function restoreSavedKit(player) {
    const saved = getSavedKit(player.server)
    if (saved == null || saved.isEmpty()) {
      return false
    }

    // Fa gestire direttamente a Minecraft Slot, Count e NBT.
    // Ripristina hotbar, inventario, armatura e offhand nelle posizioni originali.
    player.inventory.load(saved.copy())

    // Forza l'aggiornamento dell'inventario sul client.
    player.inventoryMenu.broadcastChanges()
    player.containerMenu.broadcastChanges()
    return true
  }

  ServerEvents.commandRegistry(event => {
    const { commands: Commands } = event

    event.register(
      Commands.literal('respawnkit')
        .requires(source => source.hasPermission(2))

        .then(Commands.literal('salva')
          .executes(ctx => {
            const player = ctx.source.player
            const list = new ListTag()

            // Salva inventario, hotbar, armatura e offhand con Slot, quantità e NBT.
            player.inventory.save(list)

            if (list.isEmpty()) {
              player.tell('§cInventario vuoto: il kit non è stato salvato.')
              return 0
            }

            player.server.persistentData.put(DATA_KEY, list.copy())
            player.tell('§aKit di respawn salvato correttamente: §f' + list.size() + ' stack.')
            player.tell('§7Verranno mantenuti anche slot, quantità e NBT degli oggetti.')
            return 1
          })
        )

        .then(Commands.literal('prova')
          .executes(ctx => {
            const player = ctx.source.player

            if (!restoreSavedKit(player)) {
              player.tell('§cNessun kit di respawn salvato.')
              return 0
            }

            player.tell('§aKit ripristinato esattamente come era stato salvato.')
            return 1
          })
        )

        .then(Commands.literal('info')
          .executes(ctx => {
            const player = ctx.source.player
            const saved = getSavedKit(player.server)

            if (saved == null || saved.isEmpty()) {
              player.tell('§eNessun kit di respawn configurato.')
              return 1
            }

            player.tell('§aKit di respawn configurato: §f' + saved.size() + ' stack.')
            return 1
          })
        )

        .then(Commands.literal('cancella')
          .executes(ctx => {
            const player = ctx.source.player
            player.server.persistentData.remove(DATA_KEY)
            player.tell('§eKit di respawn cancellato.')
            return 1
          })
        )
    )
  })

  PlayerEvents.respawned(event => {
    // KubeJS può emettere respawned anche quando si torna dall'End.
    if (event.keepData) {
      return
    }

    if (restoreSavedKit(event.player)) {
      event.player.tell('§aKit di respawn ripristinato dopo la morte.')
    }
  })
})()
