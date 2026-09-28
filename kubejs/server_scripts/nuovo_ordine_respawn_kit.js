// Nuovo Ordine - Kit di respawn configurabile in game
// Minecraft 1.20.1 / KubeJS
//
// Uso da OP:
//   1) Metti nell'inventario (anche armatura/offhand) SOLO gli oggetti che vuoi nel kit.
//   2) /respawnkit salva
//   3) /respawnkit prova   -> opzionale, per provarlo subito
//   4) /respawnkit info
//   5) /respawnkit cancella
//
// Il kit viene dato dopo una vera morte. Tornare dall'End non conta come morte.

(() => {
  const ListTag = Java.loadClass('net.minecraft.nbt.ListTag')
  const ItemStack = Java.loadClass('net.minecraft.world.item.ItemStack')

  const DATA_KEY = 'nuovo_ordine_respawn_kit'

  function getSavedKit(server) {
    const data = server.persistentData
    if (!data.contains(DATA_KEY)) {
      return null
    }
    return data.getList(DATA_KEY, 10) // 10 = CompoundTag
  }

  function giveSavedKit(player) {
    const saved = getSavedKit(player.server)
    if (saved == null || saved.isEmpty()) {
      return 0
    }

    let given = 0
    for (let i = 0; i < saved.size(); i++) {
      const stack = ItemStack.of(saved.getCompound(i))
      if (!stack.isEmpty()) {
        player.give(stack.copy())
        given++
      }
    }
    return given
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

            // Salva inventario, hotbar, armatura e mano secondaria con tutto l'NBT/mod data.
            player.inventory.save(list)

            if (list.isEmpty()) {
              player.tell('§cInventario vuoto: il kit non è stato salvato.')
              return 0
            }

            player.server.persistentData.put(DATA_KEY, list.copy())
            player.tell('§aKit di respawn salvato: §f' + list.size() + ' stack.')
            player.tell('§7Da ora verrà dato automaticamente dopo la morte.')
            return 1
          })
        )

        .then(Commands.literal('prova')
          .executes(ctx => {
            const player = ctx.source.player
            const count = giveSavedKit(player)

            if (count <= 0) {
              player.tell('§cNessun kit di respawn salvato.')
              return 0
            }

            player.tell('§aKit di respawn consegnato per prova.')
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
    // L'evento scatta anche quando si torna dall'End.
    if (event.keepData) {
      return
    }

    const count = giveSavedKit(event.player)
    if (count > 0) {
      event.player.tell('§aKit di respawn ripristinato dopo la morte.')
    }
  })
})()
