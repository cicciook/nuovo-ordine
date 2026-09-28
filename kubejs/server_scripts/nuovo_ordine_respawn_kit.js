// Nuovo Ordine - Kit essenziale al respawn
// Minecraft 1.20.1 / KubeJS
//
// Starter Kit continua a gestire il kit iniziale una sola volta.
// Questo script ridà SOLO gli oggetti elencati qui sotto dopo una vera morte.

const RESPAWN_ITEMS = [
  '16x minecraft:bread',
  '16x minecraft:torch',
  '1x minecraft:stone_sword',
  '1x minecraft:stone_pickaxe'
]

PlayerEvents.respawned(event => {
  // In KubeJS l'evento respawn viene chiamato anche tornando dall'End.
  // keepData=true in quel caso, quindi non diamo oggetti.
  if (event.keepData) {
    return
  }

  RESPAWN_ITEMS.forEach(item => {
    event.player.give(item)
  })

  event.player.tell('§aKit essenziale ripristinato dopo la morte.')
})
