package com.sorrowmist.useless.content.recipe.adapters.gtceu;

import appeng.api.stacks.*;
import com.sorrowmist.useless.content.recipe.AdvancedAlloyFurnaceRecipe;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.ItemStackHandler;
import java.util.*;

/** Output-space limit fed into the furnace's existing parallel calculator. Reserves every possible drop. */
public final class GtceuOutputCapacity {
    private GtceuOutputCapacity() {}

    public static boolean isGtRecipe(AdvancedAlloyFurnaceRecipe recipe) {
        return recipe.id().getNamespace().equals("useless_mod") && recipe.id().getPath().startsWith("gtceu/");
    }

    public static int limit(AdvancedAlloyFurnaceRecipe recipe, int limit, ItemStackHandler items,
                            int start, int count, FluidTank[] tanks, int tankCount) {
        Map<AEKey, Long> outputs = new LinkedHashMap<>();
        try {
            for (var item : recipe.outputs()) outputs.merge(AEItemKey.of(item), (long) item.getCount(), Math::addExact);
            for (var fluid : recipe.outputFluids()) outputs.merge(AEFluidKey.of(fluid), (long) fluid.getAmount(), Math::addExact);
            for (var key : recipe.keyOutputs()) outputs.merge(key.what(), key.amount(), Math::addExact);
        } catch (ArithmeticException e) { return 0; }
        int low = 0, high = limit;
        while (low < high) {
            int mid = low + (int) (((long) high - low + 1) / 2);
            if (fits(outputs, mid, items, start, count, tanks, tankCount)) low = mid;
            else high = mid - 1;
        }
        return low;
    }

    private static boolean fits(Map<AEKey, Long> outputs, int parallel, ItemStackHandler items,
                                 int start, int count, FluidTank[] tanks, int tankCount) {
        AEKey[] itemKeys = new AEKey[count], fluidKeys = new AEKey[tankCount];
        long[] itemSpace = new long[count], fluidSpace = new long[tankCount];
        for (int i = 0; i < count; i++) {
            ItemStack stored = items.getStackInSlot(start + i);
            itemKeys[i] = stored.isEmpty() ? null : AEItemKey.of(stored);
            itemSpace[i] = Math.max(0L, (long) items.getSlotLimit(start + i) - stored.getCount());
        }
        for (int i = 0; i < tankCount; i++) {
            var stored = tanks[i].getFluid();
            fluidKeys[i] = stored.isEmpty() ? null : AEFluidKey.of(stored);
            fluidSpace[i] = Math.max(0L, (long) tanks[i].getCapacity() - stored.getAmount());
        }
        for (var output : outputs.entrySet()) {
            if (output.getValue() <= 0 || output.getValue() > Long.MAX_VALUE / parallel) return false;
            long remaining = output.getValue() * parallel;
            AEKey key = output.getKey();
            AEKey[] keys;
            long[] space;
            if (key instanceof AEItemKey) { keys = itemKeys; space = itemSpace; }
            else if (key instanceof AEFluidKey) { keys = fluidKeys; space = fluidSpace; }
            else return false;
            // Occupied matching slots first, then assign empty slots exclusively to this output.
            for (int pass = 0; pass < 2 && remaining > 0; pass++) {
                for (int i = 0; i < keys.length && remaining > 0; i++) {
                    if (pass == 0 ? !key.equals(keys[i]) : keys[i] != null) continue;
                    long accepted = Math.min(remaining, space[i]);
                    if (accepted > 0) { keys[i] = key; space[i] -= accepted; remaining -= accepted; }
                }
            }
            if (remaining > 0) return false;
        }
        return true;
    }
}
