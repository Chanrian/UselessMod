"""Check the reflective bridge against real GTCEu jars; does not launch Minecraft.

Usage: python3 scripts/check_gtceu_api.py /path/to/javap gtceu-1.4.4.jar gtceu-7.2.0.jar
"""
import re
import subprocess
import sys
import zipfile


def inspect(jar, name):
    result = subprocess.run([sys.argv[1], '-classpath', jar, name], check=True, capture_output=True, text=True)
    return result.stdout


for jar in sys.argv[2:]:
    prefix = 'com.gregtechceu.gtceu.'
    recipe = inspect(jar, prefix + 'api.recipe.GTRecipe')
    for field in ['inputs', 'outputs', 'tickInputs', 'tickOutputs', 'outputChanceLogics',
                  'conditions', 'ingredientActions', 'data', 'duration', 'recipeType']:
        assert re.search(r'public (?:final )?.*\b' + field + ';', recipe), field
    content = inspect(jar, prefix + 'api.recipe.content.Content')
    for field in ['chance', 'maxChance', 'tierChanceBoost']:
        assert re.search(r'public (?:final )?int ' + field + ';', content), field
    assert re.search(r'public (?:final )?java.lang.Object content;', content)
    sized = inspect(jar, prefix + 'api.recipe.ingredient.SizedIngredient')
    assert 'int getAmount();' in sized
    assert 'net.minecraft.world.item.crafting.Ingredient getInner();' in sized
    fluid = inspect(jar, prefix + 'api.recipe.ingredient.FluidIngredient')
    assert re.search(r'(int|long) getAmount\(\);', fluid)
    assert 'net.minecraft.nbt.CompoundTag getNbt();' in fluid
    assert 'com.google.gson.JsonElement toJson();' in fluid
    assert 'int ratio(boolean);' in inspect(jar, prefix + 'api.capability.compat.FeCompat')
    assert 'getChanceFunction();' in inspect(jar, prefix + 'api.recipe.GTRecipeType')
    for cap in ['Item', 'Fluid', 'EU']:
        assert ' CAP;' in inspect(jar, prefix + 'api.capability.recipe.' + cap + 'RecipeCapability')
    boost = inspect(jar, prefix + 'api.recipe.chance.boost.ChanceBoostFunction')
    assert ' NONE;' in boost and ' OVERCLOCK;' in boost
    with zipfile.ZipFile(jar) as z:
        manifest = z.read('META-INF/mods.toml').decode()
        version = re.search(r'(?m)^version\s*=\s*"([^"]+)"', manifest)[1]
        if version == '7.2.0':
            energy = inspect(jar, prefix + 'api.recipe.ingredient.EnergyStack')
            assert 'long voltage();' in energy and 'long amperage();' in energy
        elif version != '1.4.4':
            raise AssertionError('Unvalidated version ' + version)
    print(f'PASS: GTCEu {version} reflective recipe, content, ingredient, energy and chance contracts')
