package com.sorrowmist.useless.content.recipe.adapters.gtceu;

import appeng.api.stacks.*;
import com.google.gson.JsonParser;
import com.sorrowmist.useless.InventoryTestBootstrap;
import com.sorrowmist.useless.api.enums.AlloyFurnaceMode;
import com.sorrowmist.useless.compat.forge1201.recipe.RecipeHolder;
import com.sorrowmist.useless.content.recipe.*;
import com.sorrowmist.useless.content.machines.advanced_alloy_furnace.ae.AlloyFurnaceBigIntegerCrafting;
import com.sorrowmist.useless.content.machines.advanced_alloy_furnace.io.FurnaceOutputPort;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GtceuRecipeAdapterTest {
    private static final Object ITEM = new Object(), FLUID = new Object(), EU = new Object(), OR = new Object();
    @BeforeAll static void bootstrap() { InventoryTestBootstrap.initialize(); }

    @SuppressWarnings("unchecked")
    private GtceuRecipeAdapter adapter() {
        return new GtceuRecipeAdapter((Class<Recipe<?>>)(Class<?>) NativeRecipe.class, OR,
                Map.of(ITEM, "Item", FLUID, "Fluid", EU, "EU"));
    }
    private AdvancedAlloyFurnaceRecipe convert(NativeRecipe recipe) {
        return adapter().convert(new RecipeHolder<>(recipe.getId(), recipe), 100, 4);
    }

    @Test void keepsNbtAndNonConsumedItemsAlongsideMultipleOutputs() {
        var source = new NativeRecipe();
        ItemStack circuit = new ItemStack(Items.PAPER);
        circuit.getOrCreateTag().putInt("circuit", 24);
        source.inputs.put(ITEM, List.of(new Content(Ingredient.of(Items.IRON_INGOT), 10000),
                new Content(StrictNBTIngredient.of(circuit), 0)));
        source.outputs.put(ITEM, List.of(new Content(Ingredient.of(Items.GOLD_INGOT), 10000),
                new Content(Ingredient.of(Items.DIAMOND), 2500)));
        source.tickInputs.put(EU, List.of(new Content(32L, 10000)));
        var result = convert(source);
        assertEquals(2, result.processTime());
        assertEquals(256, result.energy());
        assertFalse(result.inputs().get(1).consumed());
        assertTrue(ItemIngredientAllocator.matches(result.inputs(), List.of(new ItemStack(Items.IRON_INGOT, 64), circuit), 64));
        assertFalse(ItemIngredientAllocator.matches(result.inputs(), List.of(new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.PAPER)), 64));
        var allocation = ItemIngredientAllocator.allocate(result.inputs(), List.of(new ItemStack(Items.IRON_INGOT, 64), circuit), 64);
        assertEquals(64, allocation.consumedFromInput(0));
        assertEquals(0, allocation.consumedFromInput(1));
        assertEquals(List.of(new RecipeOutputChance(1, 2500, 10000)), result.outputChances());
        assertTrue(result.molds().isEmpty());
    }

    @Test void invalidConditionsCapabilitiesAndInputChanceFailClosed() {
        var source = new NativeRecipe();
        source.inputs.put(ITEM, List.of(new Content(Ingredient.of(Items.IRON_INGOT), 5000)));
        assertThrows(IllegalArgumentException.class, () -> convert(source));
        source.inputs.clear(); source.conditions = List.of(new Object());
        assertThrows(IllegalArgumentException.class, () -> convert(source));
        source.conditions = List.of(); source.data.putString("dynamic_output", "yes");
        assertThrows(IllegalArgumentException.class, () -> convert(source));
        source.data = new CompoundTag(); source.ingredientActions = List.of(new Object());
        assertThrows(IllegalArgumentException.class, () -> convert(source));
        source.ingredientActions = List.of(); source.tickInputs.put(FLUID, List.of(new Content(new Object(), 10000)));
        assertThrows(IllegalArgumentException.class, () -> convert(source));
        source.tickInputs.clear(); source.outputChanceLogics.put(ITEM, new Object());
        assertThrows(IllegalArgumentException.class, () -> convert(source));
    }

    @Test void speedAndEnergyRespectRoundingAndRejectOverflow() {
        assertEquals(1, GtceuRecipeAdapter.duration(1, 100));
        assertEquals(3, GtceuRecipeAdapter.duration(201, 100));
        assertEquals(21474837, GtceuRecipeAdapter.duration(Integer.MAX_VALUE, 100));
        assertEquals(96000, GtceuRecipeAdapter.energy(8000, 4, 3));
        assertThrows(ArithmeticException.class, () -> GtceuRecipeAdapter.energy(Long.MAX_VALUE, 4, 1));
        assertThrows(IllegalArgumentException.class, () -> GtceuRecipeAdapter.energy(1, 0, 1));
    }

    @Test void fluidTagsNbtAndAmountsAboveIntRemainExact() {
        CompoundTag tag = new CompoundTag(); tag.putInt("grade", 7);
        var fluid = GtceuRecipeAdapter.fluidJson(JsonParser.parseString(
                "{\"value\":[{\"fluid\":\"minecraft:water\"},{\"tag\":\"forge:custom_fluid\"}]}").getAsJsonObject(),
                3_000_000_000L, tag);
        var water = new FluidStack(Fluids.WATER, 1); water.setTag(tag.copy());
        assertTrue(fluid.ingredient().test(water));
        assertFalse(fluid.ingredient().test(new FluidStack(Fluids.WATER, 1)));
        assertEquals(3_000_000_000L, fluid.amount());
        assertTrue(fluid.ingredient().toJson().toString().contains("forge:custom_fluid"));
        var decoded = com.sorrowmist.useless.compat.forge1201.fluid.FluidIngredient.fromJson(fluid.ingredient().toJson());
        assertTrue(decoded.test(water));
        assertTrue(FluidIngredientAllocator.matchesLong(List.of(fluid), List.of(),
                List.of(new GenericStack(AEFluidKey.of(water), 6_000_000_000L)), 2));
        assertFalse(FluidIngredientAllocator.matchesLong(List.of(fluid), List.of(),
                List.of(new GenericStack(AEFluidKey.of(water), 5_999_999_999L)), 2));
    }

    @Test void mergedAeFluidsAreNeitherCountedTwiceNorNarrowedToInt() {
        var water = new FluidStack(Fluids.WATER, 1);
        var recipe = new AdvancedAlloyFurnaceRecipe(new ResourceLocation("useless_mod", "gtceu/test/large_input"),
                List.of(), List.of(new LongSizedFluidIngredient(
                        com.sorrowmist.useless.compat.forge1201.fluid.FluidIngredient.single(water), 3_000_000_000L)),
                List.of(), List.of(new ItemStack(Items.DIAMOND)), List.of(), List.of(),
                128, 1, Ingredient.EMPTY, 0, List.of(), AlloyFurnaceMode.NORMAL, 0);
        Map<AEKey, Long> keys = Map.of(AEFluidKey.of(water), 2_000_000_000L);
        assertFalse(GtceuRecipeAdapter.matchesSupplies(recipe, List.of(), Map.of(water, 2_000_000_000L), keys));
        assertTrue(GtceuRecipeAdapter.matchesSupplies(recipe, List.of(), Map.of(water, 3_000_000_000L), keys));
    }

    @Test void chanceBatchesDoNotMultiplyASingleRollAndSupportBigIntegers() {
        var chance = new RecipeOutputChance(0, 2500, 10000);
        var random = new Random(42);
        assertEquals(BigInteger.valueOf(250000), chance.successes(BigInteger.valueOf(1000000), random));
        BigInteger huge = BigInteger.TEN.pow(30);
        assertEquals(huge.divide(BigInteger.valueOf(4)), chance.successes(huge, random));
        long sum = 0;
        for (int i = 0; i < 20000; i++) {
            long n = chance.successes(BigInteger.valueOf(5), random).longValueExact();
            assertTrue(n == 1 || n == 2); sum += n;
        }
        assertTrue(sum > 24500 && sum < 25500);
        assertThrows(IllegalArgumentException.class, () -> new RecipeOutputChance(0, 10001, 10000));
    }

    @Test void chanceMetadataSurvivesNetworkAndExistingRecipesStayDeterministic() {
        var source = new NativeRecipe();
        source.outputs.put(ITEM, List.of(new Content(Ingredient.of(Items.DIAMOND), 2500)));
        var recipe = convert(source);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AdvancedAlloyFurnaceRecipe.STREAM_CODEC.encode(buf, recipe);
            var decoded = AdvancedAlloyFurnaceRecipe.STREAM_CODEC.decode(buf);
            assertEquals(recipe.outputChances(), decoded.outputChances());
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
        assertEquals(21, convert(new NativeRecipe()).outputOperations(0, 21));
    }

    @Test void changedChanceAndRetainedSemanticsInvalidateRecipeIdentity() {
        var source = new NativeRecipe();
        source.inputs.put(ITEM, List.of(new Content(Ingredient.of(Items.IRON_INGOT), 10000)));
        var original = convert(source);
        String originalId = AlloyFurnaceRecipeFingerprint.create(original, RegistryAccess.EMPTY);
        source.inputs.put(ITEM, List.of(new Content(Ingredient.of(Items.IRON_INGOT), 0)));
        assertNotEquals(originalId, AlloyFurnaceRecipeFingerprint.create(convert(source), RegistryAccess.EMPTY));
        source.inputs.put(ITEM, List.of(new Content(Ingredient.of(Items.IRON_INGOT), 10000)));
        source.outputs.put(ITEM, List.of(new Content(Ingredient.of(Items.GOLD_INGOT), 2500)));
        assertNotEquals(originalId, AlloyFurnaceRecipeFingerprint.create(convert(source), RegistryAccess.EMPTY));
        var encoded = AdvancedAlloyFurnaceRecipe.CODEC.codec().encodeStart(com.mojang.serialization.JsonOps.INSTANCE, original)
                .result().orElseThrow().getAsJsonObject();
        assertFalse(encoded.has("output_chances"));
    }

    @Test void outputSlotsAndTanksCannotBePromisedTwice() {
        var recipe = recipe(List.of(new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.GOLD_INGOT, 64)),
                List.of(new FluidStack(Fluids.WATER, 1000), new FluidStack(Fluids.LAVA, 1000)), List.of(), List.of());
        var slots = new ItemStackHandler(2);
        var tanks = new FluidTank[]{new FluidTank(1000), new FluidTank(1000)};
        assertEquals(1, GtceuOutputCapacity.limit(recipe, 99, slots, 0, 2, tanks, 2));
        assertEquals(0, GtceuOutputCapacity.limit(recipe, 99, slots, 0, 1, tanks, 2));
        assertEquals(0, GtceuOutputCapacity.limit(recipe, 99, slots, 0, 2, tanks, 1));
        slots.setStackInSlot(1, new ItemStack(Items.COBBLESTONE, 64));
        assertEquals(0, GtceuOutputCapacity.limit(recipe, 99, slots, 0, 2, tanks, 2));
        assertEquals(64, slots.getStackInSlot(1).getCount());
        assertTrue(tanks[0].isEmpty());
    }

    @Test void physicalAndBigIntegerOutputsRollTheWholeAdmittedBatch() {
        var recipe = recipe(List.of(new ItemStack(Items.DIAMOND, 3)), List.of(), List.of(),
                List.of(new RecipeOutputChance(0, 2500, 10000)));
        var slots = new ItemStackHandler(1);
        FurnaceOutputPort.AeOutput noAe = new FurnaceOutputPort.AeOutput() {
            public long insertItem(ItemStack stack) { return 0; }
            public long insertFluid(FluidStack stack) { return 0; }
            public long insertKey(AEKey key, long amount) { return 0; }
        };
        FurnaceOutputPort.outputRecipe(recipe, 8, noAe, slots, 0, 1, new FluidTank[0], 0);
        assertEquals(6, slots.getStackInSlot(0).getCount());
        var outputs = AlloyFurnaceBigIntegerCrafting.rolledOutputs(recipe, BigInteger.TEN.pow(25));
        assertEquals(BigInteger.TEN.pow(25).divide(BigInteger.valueOf(4)).multiply(BigInteger.valueOf(3)), outputs.get(0).amount());
    }

    @Test void insufficientFluidDoesNotConsumeItemsAndRetainedOverlapIsNotDoubleSpent() {
        var recipe = new AdvancedAlloyFurnaceRecipe(new ResourceLocation("useless_mod", "gtceu/test/mixed"),
                List.of(new CountedIngredient(Ingredient.of(Items.IRON_INGOT), 2),
                        new CountedIngredient(Ingredient.of(Items.IRON_INGOT), 1, false)),
                List.of(LongSizedFluidIngredient.from(new FluidStack(Fluids.WATER, 1000))),
                List.of(), List.of(new ItemStack(Items.GOLD_INGOT)), List.of(), List.of(),
                128, 1, Ingredient.EMPTY, 0, List.of(), AlloyFurnaceMode.NORMAL, 0);
        var items = new ItemStackHandler(1); items.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 5));
        var tank = new FluidTank(4000); tank.setFluid(new FluidStack(Fluids.WATER, 1999));
        assertFalse(com.sorrowmist.useless.content.machines.advanced_alloy_furnace.io.FurnaceInputPort
                .consumeRecipeInputs(recipe, 2, items, 0, 1, new FluidTank[]{tank}, 1));
        assertEquals(5, items.getStackInSlot(0).getCount());
        assertEquals(1999, tank.getFluidAmount());
        tank.setFluid(new FluidStack(Fluids.WATER, 2000));
        assertTrue(com.sorrowmist.useless.content.machines.advanced_alloy_furnace.io.FurnaceInputPort
                .consumeRecipeInputs(recipe, 2, items, 0, 1, new FluidTank[]{tank}, 1));
        assertEquals(1, items.getStackInSlot(0).getCount());
        assertEquals(0, tank.getFluidAmount());
    }

    @Test void existingEnergySettlementNeverGrantsAnUnpaidOperation() {
        var energy = com.sorrowmist.useless.energy.EnergyManager.builder().capacity(10000).initialEnergy(127).build();
        var effect = new com.sorrowmist.useless.content.machines.advanced_alloy_furnace.catalyst.ResolvedCatalystEffect(
                null, 64, 64, 1, true, 1, false, 0);
        var result = com.sorrowmist.useless.content.machines.advanced_alloy_furnace.execution.AlloyFurnaceRecipeExecutor
                .settleCompletionEnergy(energy, 128, 64, 0, effect);
        assertEquals(0, result.actualParallel());
        assertEquals(127, energy.getEnergyStoredLong());
        energy.modifyEnergy(129);
        result = com.sorrowmist.useless.content.machines.advanced_alloy_furnace.execution.AlloyFurnaceRecipeExecutor
                .settleCompletionEnergy(energy, 128, 64, 0, effect);
        assertEquals(2, result.actualParallel());
        assertEquals(0, energy.getEnergyStoredLong());
        assertEquals(BigInteger.valueOf(2), AlloyFurnaceBigIntegerCrafting.maximumCountForEnergy(128, 256, effect));
    }

    @Test void oversizedFluidOutputUsesLongCountsAndCapacityStillRejectsOverflow() {
        var recipe = recipe(List.of(), List.of(), List.of(new GenericStack(AEFluidKey.of(Fluids.WATER), 3_000_000_000L)), List.of());
        var tanks = new FluidTank[]{new FluidTank(Integer.MAX_VALUE), new FluidTank(Integer.MAX_VALUE)};
        assertEquals(1, GtceuOutputCapacity.limit(recipe, Integer.MAX_VALUE, new ItemStackHandler(0), 0, 0, tanks, 2));
        assertEquals(0, GtceuOutputCapacity.limit(recipe, Integer.MAX_VALUE, new ItemStackHandler(0), 0, 0, tanks, 1));
        var actual = AlloyFurnaceBigIntegerCrafting.rolledOutputs(recipe, BigInteger.valueOf(Integer.MAX_VALUE));
        assertEquals(BigInteger.valueOf(3_000_000_000L).multiply(BigInteger.valueOf(Integer.MAX_VALUE)), actual.get(0).amount());
    }

    private static AdvancedAlloyFurnaceRecipe recipe(List<ItemStack> items, List<FluidStack> fluids,
            List<GenericStack> keys, List<RecipeOutputChance> chances) {
        return new AdvancedAlloyFurnaceRecipe(new ResourceLocation("useless_mod", "gtceu/test/test"),
                List.of(), List.of(), List.of(), items, fluids, keys, 128, 1, Ingredient.EMPTY,
                0, List.of(), AlloyFurnaceMode.NORMAL, 0, chances);
    }

    public static class Content {
        public final Object content;
        public final int chance, maxChance = 10000;
        public Content(Object value, int chance) { this.content = value; this.chance = chance; }
    }
    public static class NativeType {
        public Object getChanceFunction() { return OR; }
    }
    public static class NativeRecipe implements Recipe<Container> {
        public NativeType recipeType = new NativeType();
        public Map<Object,List<Content>> inputs = new LinkedHashMap<>(), outputs = new LinkedHashMap<>(),
                tickInputs = new LinkedHashMap<>(), tickOutputs = new LinkedHashMap<>();
        public Map<Object,Object> outputChanceLogics = new HashMap<>();
        public List<?> conditions = List.of(), ingredientActions = List.of();
        public CompoundTag data = new CompoundTag();
        public int duration = 200;
        public NativeRecipe() { outputs.put(ITEM, List.of(new Content(Ingredient.of(Items.GOLD_INGOT), 10000))); }
        public ResourceLocation getId() { return new ResourceLocation("test", "gt_recipe"); }
        public boolean matches(Container c, Level l) { return false; }
        public ItemStack assemble(Container c, RegistryAccess r) { return ItemStack.EMPTY; }
        public boolean canCraftInDimensions(int w, int h) { return false; }
        public ItemStack getResultItem(RegistryAccess r) { return ItemStack.EMPTY; }
        public RecipeSerializer<?> getSerializer() { return RecipeSerializer.SMELTING_RECIPE; }
        public RecipeType<?> getType() { return RecipeType.SMELTING; }
    }
}
