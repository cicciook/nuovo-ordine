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
//
// Compatibilita' Rhino 2001.2.x:
// dentro i blocchi try vengono usati var invece di const/let per evitare
// "TypeError: redeclaration of var ...".

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

  // Chance di drop di OGNI pezzo di armatura equipaggiato.
  const ARMOR_DROP_CHANCE = 0.18

  // Chance di drop dell'arma.
  const WEAPON_DROP_CHANCE = 0.06

  // Usura casuale dell'armatura.
  const ARMOR_MIN_WEAR = 0.25
  const ARMOR_MAX_WEAR = 0.80

  // Usura casuale dell'arma.
  const WEAPON_MIN_WEAR = 0.10
  const WEAPON_MAX_WEAR = 0.65

  // Numero di parti di armatura.
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
    if (
      path.includes('exo') ||
      path.includes('juggernaut') ||
      path.includes('reaper') ||
      path.includes('heavy')
    ) {
      return 1
    }

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
      var modifiers = item
        .getDefaultAttributeModifiers($EquipmentSlot.MAINHAND)
        .get($Attributes.ATTACK_DAMAGE)

      var iterator = modifiers.iterator()
      var damage = 0.0

      while (iterator.hasNext()) {
        damage += iterator.next().getAmount()
      }

      return damage
    } catch (errAttack) {
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
          var slotNameFound = item.getEquipmentSlot().getName()

          if (armor[slotNameFound] !== undefined) {
            addWeighted(
              armor[slotNameFound],
              fullId,
              weightedArmorCopies(path)
            )
          }
        } catch (errArmorScan) {
          console.error('[ZombieGear] Errore leggendo armatura ' + fullId + ': ' + errArmorScan)
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

      if (attackDamage >= 2.0 || hasMeleeKeyword(path)) {
        try {
          var candidateStack = new $ItemStack(item)
          if (candidateStack.getMaxStackSize() === 1) {
            meleeWeapons.push(fullId)
          }
        } catch (errWeaponScan) {
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
      let damageValue = Math.floor(maxDamage * randomBetween(minWear, maxWear))

      if (damageValue >= maxDamage) {
        damageValue = maxDamage - 1
      }

      if (damageValue < 0) {
        damageValue = 0
      }

      stack.setDamageValue(damageValue)
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

    let zombieLike = entity instanceof $Zombie

    if (!zombieLike) {
      try {
        var entityTypeText = entity.type.toString().toLowerCase()
        zombieLike = entityTypeText.includes('zombie')
      } catch (errType) {
        zombieLike = false
      }
    }

    if (!zombieLike) {
      return
    }

    try {
      // In KubeJS 1.20.1 persistentData e' disponibile direttamente sull'entita'.
      var persistentTag = entity.persistentData

      // Evita di riequipaggiare lo stesso zombie quando il chunk viene ricaricato.
      if (persistentTag.getBoolean(MARKER)) {
        return
      }

      equipZombie(entity)
      persistentTag.putBoolean(MARKER, true)
    } catch (errEquip) {
      console.error(
        '[ZombieGear] Errore equipaggiando ' +
        entity.type +
        ': ' +
        errEquip
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
