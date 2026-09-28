// Nuovo Ordine - Zombie Gear
// Minecraft 1.20.1 / Forge / KubeJS
//
// Ogni zombie:
// - indossa almeno 1 pezzo di armatura Survival Instinct
// - l'armatura e' usurata e puo' essere droppata
// - impugna sempre un'arma corpo a corpo MODDATA
//
// Mettere in: kubejs/server_scripts/nuovo_ordine_zombie_gear.js
// Richiede riavvio completo del server.

(() => {
  const $ForgeRegistries = Java.loadClass('net.minecraftforge.registries.ForgeRegistries')
  const $ArmorItem = Java.loadClass('net.minecraft.world.item.ArmorItem')
  const $ItemStack = Java.loadClass('net.minecraft.world.item.ItemStack')
  const $EquipmentSlot = Java.loadClass('net.minecraft.world.entity.EquipmentSlot')
  const $Attributes = Java.loadClass('net.minecraft.world.entity.ai.attributes.Attributes')
  const $Zombie = Java.loadClass('net.minecraft.world.entity.monster.Zombie')
  const $ResourceLocation = Java.loadClass('net.minecraft.resources.ResourceLocation')

  // =========================
  // CONFIGURAZIONE
  // =========================

  // Probabilita' che OGNI pezzo di armatura equipaggiato venga droppato.
  // 0.18 = 18%
  const ARMOR_DROP_CHANCE = 0.18

  // Probabilita' che l'arma venga droppata.
  const WEAPON_DROP_CHANCE = 0.06

  // Usura casuale dell'armatura: 0.25 = 25%, 0.80 = 80% della durabilita' consumata.
  const ARMOR_MIN_WEAR = 0.25
  const ARMOR_MAX_WEAR = 0.80

  // Usura casuale dell'arma.
  const WEAPON_MIN_WEAR = 0.10
  const WEAPON_MAX_WEAR = 0.65

  // Quante parti di armatura:
  // 1 pezzo sempre.
  // Poi chance cumulative di aggiungerne altri.
  const SECOND_PIECE_CHANCE = 0.65
  const THIRD_PIECE_CHANCE = 0.28
  const FOURTH_PIECE_CHANCE = 0.08

  const MARKER = 'nuovo_ordine_zombie_gear_v1'

  // =========================

  let built = false

  const armor = {
    head: [],
    chest: [],
    legs: [],
    feet: []
  }

  const meleeWeapons = []

  const meleeKeywords = [
    'sword', 'axe', 'hatchet', 'machete', 'knife', 'dagger',
    'hammer', 'sledge', 'bat', 'baton', 'club', 'crowbar',
    'spear', 'lance', 'katana', 'saber', 'sabre', 'gauntlet',
    'chainsaw', 'circular_saw', 'saw_axe', 'greataxe'
  ]

  const bannedKeywords = [
    'pickaxe', 'shovel', 'hoe',
    'gun', 'rifle', 'pistol', 'shotgun', 'sniper',
    'launcher', 'cannon', 'minigun', 'smg', 'revolver',
    'ammo', 'bullet', 'magazine', 'attachment'
  ]

  function randomBetween(min, max) {
    return min + Math.random() * (max - min)
  }

  function weightedArmorCopies(path) {
    // Armature molto pesanti/endgame piu' rare.
    if (
      path.includes('exo') ||
      path.includes('juggernaut') ||
      path.includes('reaper') ||
      path.includes('heavy')
    ) {
      return 1
    }

    // Kevlar / equipaggiamento comune leggermente piu' frequente.
    if (
      path.includes('kevlar') ||
      path.includes('recruit') ||
      path.includes('rockie') ||
      path.includes('police')
    ) {
      return 5
    }

    if (
      path.includes('military') ||
      path.includes('ghillie') ||
      path.includes('hunter')
    ) {
      return 2
    }

    return 3
  }

  function addWeighted(list, value, weight) {
    for (let i = 0; i < weight; i++) {
      list.push(value)
    }
  }

  function isBannedPath(path) {
    for (let i = 0; i < bannedKeywords.length; i++) {
      if (path.includes(bannedKeywords[i])) {
        return true
      }
    }
    return false
  }

  function hasMeleeKeyword(path) {
    for (let i = 0; i < meleeKeywords.length; i++) {
      if (path.includes(meleeKeywords[i])) {
        return true
      }
    }
    return false
  }

  function getAttackDamageBonus(item) {
    try {
      const modifiers = item
        .getDefaultAttributeModifiers($EquipmentSlot.MAINHAND)
        .get($Attributes.ATTACK_DAMAGE)

      const iterator = modifiers.iterator()
      let damage = 0.0

      while (iterator.hasNext()) {
        damage += iterator.next().getAmount()
      }

      return damage
    } catch (e) {
      return 0.0
    }
  }

  function buildPools() {
    if (built) {
      return
    }

    armor.head.length = 0
    armor.chest.length = 0
    armor.legs.length = 0
    armor.feet.length = 0
    meleeWeapons.length = 0

    const keys = $ForgeRegistries.ITEMS.getKeys().iterator()

    while (keys.hasNext()) {
      const id = keys.next()
      const namespace = id.getNamespace()
      const path = id.getPath()
      const fullId = id.toString()
      const item = $ForgeRegistries.ITEMS.getValue(id)

      if (item == null) {
        continue
      }

      // ARMATURE: solo Survival Instinct.
      if (namespace === 'survival_instinct' && item instanceof $ArmorItem) {
        try {
          const slotName = item.getEquipmentSlot().getName()

          if (armor[slotName] !== undefined) {
            addWeighted(
              armor[slotName],
              fullId,
              weightedArmorCopies(path)
            )
          }
        } catch (e) {
          console.error('[ZombieGear] Errore leggendo armatura ' + fullId + ': ' + e)
        }
      }

      // ARMI: solo item modded, mai vanilla.
      if (namespace === 'minecraft') {
        continue
      }

      if (isBannedPath(path)) {
        continue
      }

      const attackDamage = getAttackDamageBonus(item)

      // Accetta:
      // - armi con vero modificatore di attacco
      // - item con nome chiaramente da arma corpo a corpo
      if (attackDamage >= 2.0 || hasMeleeKeyword(path)) {
        // Evita item stackabili/comuni che contengono accidentalmente una keyword.
        try {
          const stack = new $ItemStack(item)
          if (stack.getMaxStackSize() === 1) {
            meleeWeapons.push(fullId)
          }
        } catch (e) {
          // Ignora item problematici.
        }
      }
    }

    built = true

    console.info(
      '[ZombieGear] Pool caricati | Survival Instinct armor: ' +
      'head=' + armor.head.length +
      ', chest=' + armor.chest.length +
      ', legs=' + armor.legs.length +
      ', feet=' + armor.feet.length +
      ' | armi melee moddate=' + meleeWeapons.length
    )
  }

  function randomFrom(list) {
    if (list.length <= 0) {
      return null
    }
    return list[Math.floor(Math.random() * list.length)]
  }

  function makeWornStack(id, minWear, maxWear) {
    const item = $ForgeRegistries.ITEMS.getValue(
      $ResourceLocation.tryParse(id)
    )

    if (item == null) {
      return null
    }

    const stack = new $ItemStack(item)

    if (stack.isDamageableItem() && stack.getMaxDamage() > 1) {
      const maxDamage = stack.getMaxDamage()
      let damage = Math.floor(maxDamage * randomBetween(minWear, maxWear))

      // Non deve nascere gia' rotto.
      if (damage >= maxDamage) {
        damage = maxDamage - 1
      }

      if (damage < 0) {
        damage = 0
      }

      stack.setDamageValue(damage)
    }

    return stack
  }

  function shuffle(array) {
    for (let i = array.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1))
      const temp = array[i]
      array[i] = array[j]
      array[j] = temp
    }
  }

  function equipZombie(entity) {
    buildPools()

    // Garantisce almeno un pezzo.
    let pieces = 1

    if (Math.random() < SECOND_PIECE_CHANCE) {
      pieces++
    }

    if (pieces >= 2 && Math.random() < THIRD_PIECE_CHANCE) {
      pieces++
    }

    if (pieces >= 3 && Math.random() < FOURTH_PIECE_CHANCE) {
      pieces++
    }

    if (pieces > 4) {
      pieces = 4
    }

    const availableSlots = []

    if (armor.head.length > 0) availableSlots.push('head')
    if (armor.chest.length > 0) availableSlots.push('chest')
    if (armor.legs.length > 0) availableSlots.push('legs')
    if (armor.feet.length > 0) availableSlots.push('feet')

    shuffle(availableSlots)

    if (availableSlots.length <= 0) {
      console.error('[ZombieGear] Nessuna armatura Survival Instinct rilevata.')
    } else {
      if (pieces > availableSlots.length) {
        pieces = availableSlots.length
      }

      for (let i = 0; i < pieces; i++) {
        const slotName = availableSlots[i]
        const armorId = randomFrom(armor[slotName])

        if (armorId == null) {
          continue
        }

        const stack = makeWornStack(
          armorId,
          ARMOR_MIN_WEAR,
          ARMOR_MAX_WEAR
        )

        if (stack == null) {
          continue
        }

        const slot = $EquipmentSlot.byName(slotName)
        entity.setItemSlot(slot, stack)
        entity.setDropChance(slot, ARMOR_DROP_CHANCE)
      }
    }

    // Arma corpo a corpo moddata sempre.
    if (meleeWeapons.length <= 0) {
      console.error('[ZombieGear] Nessuna arma corpo a corpo moddata rilevata.')
      return
    }

    const weaponId = randomFrom(meleeWeapons)
    const weapon = makeWornStack(
      weaponId,
      WEAPON_MIN_WEAR,
      WEAPON_MAX_WEAR
    )

    if (weapon != null) {
      entity.setItemSlot($EquipmentSlot.MAINHAND, weapon)
      entity.setDropChance($EquipmentSlot.MAINHAND, WEAPON_DROP_CHANCE)
    }
  }

  EntityEvents.spawned(event => {
    const entity = event.entity

    if (entity == null) {
      return
    }

    // Zombie vanilla + sottoclassi (Husk, Drowned, molti zombie modded).
    // Fallback anche per entita' moddate che contengono "zombie" nell'ID.
    let zombieLike = entity instanceof $Zombie

    if (!zombieLike) {
      try {
        zombieLike = entity.type.toString().toLowerCase().includes('zombie')
      } catch (e) {
        zombieLike = false
      }
    }

    if (!zombieLike) {
      return
    }

    try {
      const data = entity.getPersistentData()

      // EntityEvents.spawned scatta anche al caricamento dei chunk:
      // questo evita di riequipaggiare lo stesso zombie infinite volte.
      if (data.getBoolean(MARKER)) {
        return
      }

      equipZombie(entity)
      data.putBoolean(MARKER, true)
    } catch (e) {
      console.error(
        '[ZombieGear] Errore equipaggiando ' +
        entity.type +
        ': ' +
        e
      )
    }
  })

  ServerEvents.commandRegistry(event => {
    const { commands: Commands } = event

    event.register(
      Commands.literal('zombiegear')
        .requires(source => source.hasPermission(2))
        .then(
          Commands.literal('scan')
            .executes(ctx => {
              built = false
              buildPools()

              const player = ctx.source.player

              player.tell(
                '§a[ZombieGear] §fArmature SI: ' +
                'testa §e' + armor.head.length +
                '§f, petto §e' + armor.chest.length +
                '§f, gambe §e' + armor.legs.length +
                '§f, piedi §e' + armor.feet.length
              )

              player.tell(
                '§a[ZombieGear] §fArmi corpo a corpo moddate rilevate: §e' +
                meleeWeapons.length
              )

              return 1
            })
        )
    )
  })
})()
