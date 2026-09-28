// Nuovo Ordine - Zombie Gear
// Minecraft 1.20.1 / Forge / KubeJS
// Versione ES5/Rhino-safe.
//
// Importante: questa versione NON scandisce piu' gli attributi di tutti gli item
// del registro Forge. Il vecchio scan poteva forzare codice di altri mod (Create
// e addon inclusi) mentre il server/client costruivano le creative tab.

(function () {
  var ForgeRegistries = Java.loadClass('net.minecraftforge.registries.ForgeRegistries')
  var ArmorItem = Java.loadClass('net.minecraft.world.item.ArmorItem')
  var ItemStack = Java.loadClass('net.minecraft.world.item.ItemStack')
  var EquipmentSlot = Java.loadClass('net.minecraft.world.entity.EquipmentSlot')
  var Zombie = Java.loadClass('net.minecraft.world.entity.monster.Zombie')
  var ResourceLocation = Java.loadClass('net.minecraft.resources.ResourceLocation')

  // CONFIG
  var ARMOR_DROP_CHANCE = 0.18
  var WEAPON_DROP_CHANCE = 0.06

  var ARMOR_MIN_WEAR = 0.25
  var ARMOR_MAX_WEAR = 0.80

  var WEAPON_MIN_WEAR = 0.10
  var WEAPON_MAX_WEAR = 0.65

  var SECOND_PIECE_CHANCE = 0.65
  var THIRD_PIECE_CHANCE = 0.28
  var FOURTH_PIECE_CHANCE = 0.08

  // v3: nuova scansione registry-safe.
  var MARKER = 'nuovo_ordine_zombie_gear_v3'

  var built = false

  var armor = {
    head: [],
    chest: [],
    legs: [],
    feet: []
  }

  var meleeWeapons = []

  var meleeKeywords = [
    'sword', 'axe', 'hatchet', 'machete', 'knife', 'dagger',
    'hammer', 'sledge', 'bat', 'baton', 'club', 'crowbar',
    'spear', 'lance', 'katana', 'saber', 'sabre', 'gauntlet',
    'chainsaw', 'circular_saw', 'saw_axe', 'greataxe',
    'cleaver', 'mace', 'scythe', 'kukri', 'rapier', 'blade'
  ]

  var bannedKeywords = [
    'pickaxe', 'shovel', 'hoe',
    'gun', 'rifle', 'pistol', 'shotgun', 'sniper',
    'launcher', 'cannon', 'minigun', 'smg', 'revolver',
    'ammo', 'bullet', 'magazine', 'attachment'
  ]

  // Mod Create e addon che non devono mai essere toccati dallo scan armi.
  var bannedNamespaces = [
    'create',
    'railways',
    'copycats',
    'create_connected',
    'createdeco',
    'create_cities_subways',
    'sliceanddice',
    'createbigcannons',
    'interiors',
    'create_power_loader',
    'create_new_age',
    'createdieselgenerators',
    'bellsandwhistles',
    'createbb',
    'createmethhead',
    'createframed',
    'create_dragons_plus'
  ]

  function contains(text, part) {
    return String(text).indexOf(part) >= 0
  }

  function arrayContains(array, value) {
    var i
    for (i = 0; i < array.length; i++) {
      if (String(array[i]) == String(value)) {
        return true
      }
    }
    return false
  }

  function randomBetween(min, max) {
    return min + Math.random() * (max - min)
  }

  function weightedArmorCopies(path) {
    if (
      contains(path, 'exo') ||
      contains(path, 'juggernaut') ||
      contains(path, 'reaper') ||
      contains(path, 'heavy')
    ) {
      return 1
    }

    if (
      contains(path, 'kevlar') ||
      contains(path, 'recruit') ||
      contains(path, 'rockie') ||
      contains(path, 'police')
    ) {
      return 5
    }

    if (
      contains(path, 'military') ||
      contains(path, 'ghillie') ||
      contains(path, 'hunter')
    ) {
      return 2
    }

    return 3
  }

  function addWeighted(list, value, weight) {
    var i
    for (i = 0; i < weight; i++) {
      list.push(value)
    }
  }

  function isBannedPath(path) {
    var i
    for (i = 0; i < bannedKeywords.length; i++) {
      if (contains(path, bannedKeywords[i])) {
        return true
      }
    }
    return false
  }

  function hasMeleeKeyword(path) {
    var i
    for (i = 0; i < meleeKeywords.length; i++) {
      if (contains(path, meleeKeywords[i])) {
        return true
      }
    }
    return false
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

    var keys = ForgeRegistries.ITEMS.getKeys().iterator()

    while (keys.hasNext()) {
      var registryId = keys.next()
      var namespaceName = String(registryId.getNamespace())
      var itemPath = String(registryId.getPath())
      var fullItemId = String(registryId.toString())

      // ARMATURE: tocchiamo l'oggetto Java SOLO per Survival Instinct.
      if (namespaceName == 'survival_instinct') {
        try {
          var survivalItem = ForgeRegistries.ITEMS.getValue(registryId)

          if (survivalItem != null && survivalItem instanceof ArmorItem) {
            var detectedSlot = String(survivalItem.getEquipmentSlot().getName())

            if (armor[detectedSlot] !== undefined) {
              addWeighted(
                armor[detectedSlot],
                fullItemId,
                weightedArmorCopies(itemPath)
              )
            }
          }
        } catch (errArmorScan) {
          console.error('[ZombieGear] Errore leggendo armatura ' + fullItemId + ': ' + errArmorScan)
        }

        // Un item Survival Instinct puo' anche essere un'arma: continua sotto.
      }

      // Mai ispezionare item di Create e addon durante lo scan delle armi.
      if (arrayContains(bannedNamespaces, namespaceName)) {
        continue
      }

      // Mai vanilla e mai categorie vietate.
      if (namespaceName == 'minecraft' || isBannedPath(itemPath)) {
        continue
      }

      // La vecchia versione interrogava gli attributi di OGNI item moddato.
      // Ora leggiamo l'oggetto Java solo dopo un filtro sul solo ID testuale.
      if (!hasMeleeKeyword(itemPath)) {
        continue
      }

      try {
        var candidateItem = ForgeRegistries.ITEMS.getValue(registryId)

        if (candidateItem == null) {
          continue
        }

        var candidateWeaponStack = new ItemStack(candidateItem)

        if (candidateWeaponStack.getMaxStackSize() == 1) {
          meleeWeapons.push(fullItemId)
        }
      } catch (errWeaponScan) {
        // Ignora item incompatibili/problematici.
      }
    }

    built = true

    console.info(
      '[ZombieGear] Pool v3 caricati | Survival Instinct armor: ' +
      'head=' + armor.head.length +
      ', chest=' + armor.chest.length +
      ', legs=' + armor.legs.length +
      ', feet=' + armor.feet.length +
      ' | armi melee moddate=' + meleeWeapons.length
    )
  }

  function randomFrom(list) {
    if (list == null || list.length <= 0) {
      return null
    }

    return list[Math.floor(Math.random() * list.length)]
  }

  function makeWornStack(itemIdText, minWear, maxWear) {
    var resourceId = ResourceLocation.tryParse(String(itemIdText))

    if (resourceId == null) {
      return null
    }

    var foundItem = ForgeRegistries.ITEMS.getValue(resourceId)

    if (foundItem == null) {
      return null
    }

    var resultStack = new ItemStack(foundItem)

    if (resultStack.isDamageableItem() && resultStack.getMaxDamage() > 1) {
      var maxDamageValue = resultStack.getMaxDamage()
      var damageValue = Math.floor(maxDamageValue * randomBetween(minWear, maxWear))

      if (damageValue >= maxDamageValue) {
        damageValue = maxDamageValue - 1
      }

      if (damageValue < 0) {
        damageValue = 0
      }

      resultStack.setDamageValue(damageValue)
    }

    return resultStack
  }

  function shuffle(array) {
    var i
    var j
    var temp

    for (i = array.length - 1; i > 0; i--) {
      j = Math.floor(Math.random() * (i + 1))
      temp = array[i]
      array[i] = array[j]
      array[j] = temp
    }
  }

  function equipZombie(entity) {
    buildPools()

    var pieces = 1

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

    var availableSlots = []

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

      var armorIndex
      for (armorIndex = 0; armorIndex < pieces; armorIndex++) {
        var currentSlotName = availableSlots[armorIndex]
        var selectedArmorId = randomFrom(armor[currentSlotName])

        if (selectedArmorId == null) {
          continue
        }

        var armorStack = makeWornStack(selectedArmorId, ARMOR_MIN_WEAR, ARMOR_MAX_WEAR)

        if (armorStack == null) {
          continue
        }

        var currentEquipmentSlot = EquipmentSlot.byName(currentSlotName)
        entity.setItemSlot(currentEquipmentSlot, armorStack)
        entity.setDropChance(currentEquipmentSlot, ARMOR_DROP_CHANCE)
      }
    }

    if (meleeWeapons.length <= 0) {
      console.error('[ZombieGear] Nessuna arma corpo a corpo moddata rilevata.')
      return
    }

    var selectedWeaponId = randomFrom(meleeWeapons)
    var selectedWeaponStack = makeWornStack(selectedWeaponId, WEAPON_MIN_WEAR, WEAPON_MAX_WEAR)

    if (selectedWeaponStack != null) {
      entity.setItemSlot(EquipmentSlot.MAINHAND, selectedWeaponStack)
      entity.setDropChance(EquipmentSlot.MAINHAND, WEAPON_DROP_CHANCE)
    }
  }

  function isZombieLike(entity) {
    if (entity instanceof Zombie) {
      return true
    }

    try {
      var entityTypeText = String(entity.type).toLowerCase()
      return contains(entityTypeText, 'zombie')
    } catch (errType) {
      return false
    }
  }

  EntityEvents.spawned(function (event) {
    var entity = event.entity

    if (entity == null || !isZombieLike(entity)) {
      return
    }

    try {
      var persistentTag = entity.persistentData

      if (persistentTag != null && persistentTag.getBoolean(MARKER)) {
        return
      }

      equipZombie(entity)

      if (persistentTag != null) {
        persistentTag.putBoolean(MARKER, true)
      }
    } catch (errEquip) {
      console.error('[ZombieGear] Errore equipaggiando ' + String(entity.type) + ': ' + errEquip)
    }
  })

  ServerEvents.commandRegistry(function (event) {
    var Commands = event.commands

    event.register(
      Commands.literal('zombiegear')
        .requires(function (source) {
          return source.hasPermission(2)
        })
        .then(
          Commands.literal('scan')
            .executes(function (ctx) {
              built = false
              buildPools()

              var player = ctx.source.player

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
