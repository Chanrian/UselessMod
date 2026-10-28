package com.sorrowmist.useless.content.recipe.adapters.gtceu;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.sorrowmist.useless.api.enums.AlloyFurnaceMode;
import com.sorrowmist.useless.api.recipe.IRecipeAdapter;
import com.sorrowmist.useless.compat.forge1201.fluid.FluidIngredient;
import com.sorrowmist.useless.compat.forge1201.recipe.RecipeHolder;
import com.sorrowmist.useless.content.recipe.*;
import com.sorrowmist.useless.core.config.ConfigManager;
import com.sorrowmist.useless.utils.RateLimitedLogger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

/** Optional bridge for the inspected GTCEu 1.4.4 and 7.2.0 recipe contracts.
 * Reflection is confined to GT-owned, unmapped members; no GT classes are linked when GT is absent.
 */
public final class GtceuRecipeAdapter implements IRecipeAdapter<Recipe<?>> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String GT = "com.gregtechceu.gtceu.";
    private static final String INGREDIENT = GT + "api.recipe.ingredient.";
    private static final Set<String> ENVIRONMENT_CONDITIONS = Set.of(
            "ResearchCondition", "CleanroomCondition", "DimensionCondition", "BiomeCondition",
            "PositionYCondition", "RainingCondition", "ThunderCondition", "DaytimeCondition",
            "EnvironmentalHazardCondition", "EUToStartCondition", "VentCondition", "RPMCondition",
            "GameStageCondition", "FTBQuestCondition", "HeraclesQuestCondition");
    private static final Set<String> PASSIVE_DATA = Set.of(
            "ebf_temp", "eu_to_start", "hide_duration", "disable_distillery", "solder_multiplier", "solderMultiplier");
    private final Class<Recipe<?>> recipeClass;
    private final Object orLogic;
    private final Set<Object> chanceFunctions;
    private final Map<Object, String> capabilities;

    @SuppressWarnings("unchecked")
    public GtceuRecipeAdapter() {
        try {
            recipeClass = (Class<Recipe<?>>) (Class<?>) Class.forName(GT + "api.recipe.GTRecipe");
            orLogic = Class.forName(GT + "api.recipe.chance.logic.ChanceLogic").getField("OR").get(null);
            Class<?> boosts = Class.forName(GT + "api.recipe.chance.boost.ChanceBoostFunction");
            chanceFunctions = Set.of(boosts.getField("NONE").get(null), boosts.getField("OVERCLOCK").get(null));
            capabilities = new IdentityHashMap<>();
            for (String name : List.of("Item", "Fluid", "EU"))
                capabilities.put(Class.forName(GT + "api.capability.recipe." + name + "RecipeCapability")
                        .getField("CAP").get(null), name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported GTCEu recipe API", e);
        }
    }

    // Native-shaped fixtures exercise the same conversion path without starting Forge/GT registries.
    GtceuRecipeAdapter(Class<Recipe<?>> recipeClass, Object orLogic, Map<Object, String> capabilities) {
        this.recipeClass = recipeClass;
        this.orLogic = orLogic;
        this.chanceFunctions = Set.of(orLogic);
        this.capabilities = capabilities;
    }

    @Override public Class<Recipe<?>> getRecipeClass() { return recipeClass; }
    @Override public String sourceId() { return "gtceu"; }
    @Override public ItemStack getMoldItem() { return null; }

    @Override
    public List<AdvancedAlloyFurnaceRecipe> convertAll(RecipeHolder<Recipe<?>> holder, Level level) {
        try {
            return List.of(convert(holder, ConfigManager.getGtceuSpeedup(), fePerEu()));
        } catch (IllegalArgumentException | ArithmeticException e) {
            // One warning per rejection reason/window, with a full per-recipe list at DEBUG.
            RateLimitedLogger.warn(LOGGER, "Skipping GT recipe {}: " + e.getMessage(), holder.id());
            LOGGER.debug("Rejected GT recipe {}: {}", holder.id(), e.getMessage());
            return List.of();
        }
    }

    AdvancedAlloyFurnaceRecipe convert(RecipeHolder<Recipe<?>> holder, int speedup, int fePerEu) {
        Object source = holder.value();
        if (source.getClass() != recipeClass) throw unsupported("GTRecipe subclass", source);
        if (!((List<?>) read(source, "ingredientActions")).isEmpty())
            throw new IllegalArgumentException("KubeJS ingredientActions can change inputs/outputs");
        for (Object condition : (List<?>) read(source, "conditions")) {
            // GTL 1.4.5.4's shipped GravityCondition only checks dimension/height/hatch environment.
            if (condition.getClass().getName().equals("org.gtlcore.gtlcore.common.recipe.condition.GravityCondition")) continue;
            if (!condition.getClass().getPackageName().equals(GT + "common.recipe.condition")
                    || !ENVIRONMENT_CONDITIONS.contains(condition.getClass().getSimpleName()))
                throw unsupported("condition", condition);
        }
        CompoundTag data = (CompoundTag) read(source, "data");
        for (String key : data.getAllKeys()) {
            if (!PASSIVE_DATA.contains(key)) throw new IllegalArgumentException("uninterpreted recipe data: " + key);
        }
        int duration = ((Number) read(source, "duration")).intValue();
        int time = duration(duration, speedup);
        if (!contents(source, "tickOutputs").isEmpty())
            throw new IllegalArgumentException("tick outputs / generators are not processing recipes");
        long eut = 0;
        for (var entry : contents(source, "tickInputs").entrySet()) {
            if (!"EU".equals(capabilities.get(entry.getKey()))) throw unsupported("tick input capability", entry.getKey());
            for (Object content : entry.getValue()) {
                requireCertain(content);
                Object value = read(content, "content");
                long eu;
                if (value instanceof Long number) eu = number; // GTCEu 1.4.4
                else if (value.getClass().getName().equals(INGREDIENT + "EnergyStack"))
                    eu = Math.multiplyExact(positiveOrZero(call(value, "voltage")), positive(call(value, "amperage")));
                else throw unsupported("EU content", value);
                if (eu < 0) throw new IllegalArgumentException("negative EU input");
                eut = Math.addExact(eut, eu);
            }
        }
        long energy = energy(eut, fePerEu, time);
        List<CountedIngredient> items = new ArrayList<>();
        List<LongSizedFluidIngredient> fluids = new ArrayList<>();
        for (var entry : contents(source, "inputs").entrySet()) {
            String capability = capabilities.get(entry.getKey());
            for (Object content : entry.getValue()) {
                int chance = integer(content, "chance"), max = integer(content, "maxChance");
                if (max <= 0 || (chance != 0 && chance != max))
                    throw new IllegalArgumentException("probabilistic input consumption");
                Object value = read(content, "content");
                if ("Item".equals(capability)) {
                    CountedIngredient item = item(value, chance != 0);
                    items.add(item);
                } else if ("Fluid".equals(capability)) {
                    if (chance == 0) throw new IllegalArgumentException("non-consumable fluid input");
                    fluids.add(fluid(value));
                } else throw unsupported("input capability", entry.getKey());
            }
        }
        List<ItemStack> outputItems = new ArrayList<>();
        List<FluidStack> outputFluids = new ArrayList<>();
        List<GenericStack> outputKeys = new ArrayList<>();
        List<RecipeOutputChance> itemChances = new ArrayList<>(), fluidChances = new ArrayList<>(), keyChances = new ArrayList<>();
        Map<?, ?> logics = (Map<?, ?>) read(source, "outputChanceLogics");
        Object chanceFunction = call(read(source, "recipeType"), "getChanceFunction");
        if (!chanceFunctions.contains(chanceFunction)) throw unsupported("chance boost function", chanceFunction);
        for (var entry : contents(source, "outputs").entrySet()) {
            Object logic = logics.get(entry.getKey());
            if (logic != null && logic != orLogic) throw unsupported("output chance logic", logic);
            String capability = capabilities.get(entry.getKey());
            for (Object content : entry.getValue()) {
                int chance = integer(content, "chance"), max = integer(content, "maxChance");
                new RecipeOutputChance(0, chance, max); // validate even zero-chance content
                if (chance == 0) throw new IllegalArgumentException("zero-chance output has retained/catalyst semantics");
                Object value = read(content, "content");
                if ("Item".equals(capability)) {
                    CountedIngredient item = item(value, true);
                    ItemStack[] stacks = item.ingredient().getItems();
                    if (stacks.length != 1 || stacks[0].isEmpty()) throw new IllegalArgumentException("non-single item output");
                    addChance(itemChances, outputItems.size(), chance, max);
                    outputItems.add(stacks[0].copyWithCount(Math.toIntExact(item.count())));
                } else if ("Fluid".equals(capability)) {
                    LongSizedFluidIngredient input = fluid(value);
                    JsonObject json = ((JsonElement) call(value, "toJson")).getAsJsonObject();
                    JsonElement choices = json.get("value");
                    if (choices.isJsonArray()) {
                        if (choices.getAsJsonArray().size() != 1) throw new IllegalArgumentException("non-single fluid output");
                        choices = choices.getAsJsonArray().get(0);
                    }
                    if (!choices.getAsJsonObject().has("fluid")) throw new IllegalArgumentException("tag-valued fluid output");
                    FluidStack[] stacks = input.getRepresentatives();
                    if (stacks.length != 1) throw new IllegalArgumentException("unresolved fluid output");
                    if (input.amount() <= Integer.MAX_VALUE) {
                        addChance(fluidChances, outputFluids.size(), chance, max);
                        outputFluids.add(new FluidStack(stacks[0], (int) input.amount()));
                    } else {
                        addChance(keyChances, outputKeys.size(), chance, max);
                        outputKeys.add(new GenericStack(AEFluidKey.of(stacks[0]), input.amount()));
                    }
                } else throw unsupported("output capability", entry.getKey());
            }
        }
        if (outputItems.isEmpty() && outputFluids.isEmpty() && outputKeys.isEmpty())
            throw new IllegalArgumentException("no material output");
        List<RecipeOutputChance> chances = new ArrayList<>(itemChances);
        offsetChances(chances, fluidChances, outputItems.size());
        offsetChances(chances, keyChances, outputItems.size() + outputFluids.size());
        return new AdvancedAlloyFurnaceRecipe(new ResourceLocation("useless_mod", "gtceu/"
                + holder.id().getNamespace() + "/" + holder.id().getPath()), items, fluids, List.of(),
                outputItems, outputFluids, outputKeys, energy, time, Ingredient.EMPTY, 0, List.of(),
                AlloyFurnaceMode.NORMAL, 0, chances);
    }

    static int duration(int ticks, int speedup) {
        if (ticks <= 0 || speedup <= 0) throw new IllegalArgumentException("nonpositive duration/speedup");
        return Math.toIntExact(Math.max(1L, (ticks + (long) speedup - 1L) / speedup));
    }

    static long energy(long eut, int ratio, int ticks) {
        if (eut < 0 || ratio <= 0 || ticks <= 0) throw new IllegalArgumentException("invalid energy conversion");
        return Math.multiplyExact(Math.multiplyExact(eut, ratio), ticks);
    }

    private static CountedIngredient item(Object value, boolean consumed) {
        long count = 1;
        if (value.getClass().getName().equals(INGREDIENT + "SizedIngredient")) {
            count = positive(call(value, "getAmount"));
            value = call(value, "getInner");
        }
        if (!(value instanceof Ingredient ingredient)) throw unsupported("item ingredient", value);
        String name = value.getClass().getName();
        if (name.startsWith(INGREDIENT) && !name.equals(INGREDIENT + "IntCircuitIngredient"))
            throw unsupported("dynamic or nested GT ingredient", value);
        // IntCircuitIngredient is a machine configuration even if a script sets chance to 100%.
        return new CountedIngredient(ingredient, count, consumed && !name.equals(INGREDIENT + "IntCircuitIngredient"));
    }

    private static LongSizedFluidIngredient fluid(Object value) {
        if (!value.getClass().getName().equals(INGREDIENT + "FluidIngredient")) throw unsupported("fluid ingredient", value);
        return fluidJson(((JsonElement) call(value, "toJson")).getAsJsonObject(),
                positive(call(value, "getAmount")), (CompoundTag) call(value, "getNbt"));
    }

    static LongSizedFluidIngredient fluidJson(JsonObject json, long amount, CompoundTag nbt) {
        // Both inspected Forge versions use mB, including the old LDLib long-fluid API.
        FluidIngredient predicate = FluidIngredient.fromJson(json.get("value"));
        return new LongSizedFluidIngredient(FluidIngredient.withNbt(predicate, nbt), amount);
    }

    private static int fePerEu() {
        int override = ConfigManager.getGtceuFePerEu();
        if (override > 0) return override;
        try {
            return (int) Class.forName(GT + "api.capability.compat.FeCompat")
                    .getMethod("ratio", boolean.class).invoke(null, false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("GT FE/EU ratio unavailable; configure fe_per_eu explicitly", e);
        }
    }

    @Override
    public List<RecipeHolder<Recipe<?>>> findMatchingRecipes(Level level, Map<Ingredient, Long> items,
            Map<FluidStack, Long> fluids, Map<AEKey, Long> keys, ItemStack mold, List<ItemStack> actualInputs) {
        List<RecipeHolder<Recipe<?>>> result = new ArrayList<>();
        for (var entry : AlloyFurnaceRecipeCatalog.entries(level, "gtceu")) {
            var recipe = entry.recipe();
            if (!matchesSupplies(recipe, actualInputs, fluids, keys)) continue;
            String[] id = recipe.id().getPath().substring("gtceu/".length()).split("/", 2);
            level.getRecipeManager().byKey(new ResourceLocation(id[0], id[1])).filter(recipeClass::isInstance)
                    .ifPresent(source -> result.add(RecipeHolder.of(source)));
        }
        return result;
    }

    static boolean matchesSupplies(AdvancedAlloyFurnaceRecipe recipe, List<ItemStack> items,
                                    Map<FluidStack, Long> mergedFluids, Map<AEKey, Long> keys) {
        var supplies = keys.entrySet().stream().map(e -> new GenericStack(e.getKey(), e.getValue())).toList();
        // The manager's fluid map ALREADY includes AEFluidKeys. Do not count them twice or narrow to int.
        return ItemIngredientAllocator.matches(recipe.inputs(), items, supplies, 1)
                && FluidIngredientAllocator.matchesLong(recipe.inputFluids(), mergedFluids, 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, List<Object>> contents(Object recipe, String name) {
        return (Map<Object, List<Object>>) read(recipe, name);
    }
    private static void requireCertain(Object content) {
        if (integer(content, "maxChance") <= 0 || integer(content, "chance") != integer(content, "maxChance"))
            throw new IllegalArgumentException("probabilistic/retained EU input");
    }
    private static int integer(Object target, String name) { return ((Number) read(target, name)).intValue(); }
    private static long positive(Object value) {
        long amount = positiveOrZero(value);
        if (amount == 0) throw new IllegalArgumentException("zero amount");
        return amount;
    }
    private static long positiveOrZero(Object value) {
        long amount = ((Number) value).longValue();
        if (amount < 0) throw new IllegalArgumentException("negative amount");
        return amount;
    }
    private static Object read(Object target, String name) {
        try { return target.getClass().getField(name).get(target); }
        catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Unsupported GT field " + name, e); }
    }
    private static Object call(Object target, String name) {
        try { return target.getClass().getMethod(name).invoke(target); }
        catch (InvocationTargetException e) { throw new IllegalArgumentException("GT method failed: " + name, e.getCause()); }
        catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Unsupported GT method " + name, e); }
    }
    private static IllegalArgumentException unsupported(String kind, Object value) {
        return new IllegalArgumentException("unsupported " + kind + ": " + value.getClass().getName());
    }
    private static void addChance(List<RecipeOutputChance> list, int index, int chance, int max) {
        if (chance != max) list.add(new RecipeOutputChance(index, chance, max));
    }
    private static void offsetChances(List<RecipeOutputChance> to, List<RecipeOutputChance> from, int offset) {
        for (var chance : from) to.add(new RecipeOutputChance(offset + chance.index(), chance.chance(), chance.maxChance()));
    }
}
