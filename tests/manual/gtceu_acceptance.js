// priority: -10000
// Copy into kubejs/server_scripts in a disposable test instance, then /reload.
ServerEvents.recipes(event => {
    // 1 = original; 2 = changed gold output; 3 = removed. Change this and /reload.
    const stage = 1
    event.remove({ id: 'useless_mod:gt_acceptance' })
    if (stage === 3) return

    event.recipes.gtceu.mixer('useless_mod:gt_acceptance')
        .itemInputs('3x #forge:ingots/iron')
        .notConsumable('minecraft:stick')
        .circuit(24)
        .inputFluids(Fluid.of('minecraft:water', 1000))
        .itemOutputs((stage === 2 ? '3x ' : '2x ') + 'minecraft:gold_ingot')
        .chancedOutput('minecraft:diamond', 2500, 0)
        .outputFluids(Fluid.of('minecraft:lava', 250))
        .duration(200)
        .EUt(32)
        .id('useless_mod:gt_acceptance')
})
