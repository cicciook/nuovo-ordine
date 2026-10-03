// Nuovo Ordine: sostituisce Easy Gunpowder Recipe con una ricetta KubeJS.
// Stessa impostazione: quattro combustibili al carbone attorno alla blaze powder -> 4 gunpowder.
ServerEvents.recipes(event => {
  event.shaped(
    Item.of('minecraft:gunpowder', 4),
    [
      ' C ',
      'CBC',
      ' C '
    ],
    {
      C: '#minecraft:coals',
      B: 'minecraft:blaze_powder'
    }
  ).id('kubejs:nuovo_ordine_easy_gunpowder')
})
