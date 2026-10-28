package com.sorrowmist.useless.content.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.math.BigInteger;
import java.util.random.RandomGenerator;

/** GT OR batch semantics: whole expected successes plus one roll for the fractional remainder. */
public record RecipeOutputChance(int index, int chance, int maxChance) {
    public static final Codec<RecipeOutputChance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("index").forGetter(RecipeOutputChance::index),
            Codec.INT.fieldOf("chance").forGetter(RecipeOutputChance::chance),
            Codec.INT.fieldOf("max_chance").forGetter(RecipeOutputChance::maxChance)
    ).apply(instance, RecipeOutputChance::new));

    public RecipeOutputChance {
        if (index < 0 || maxChance <= 0 || chance < 0 || chance > maxChance)
            throw new IllegalArgumentException("Invalid output probability");
    }

    public BigInteger successes(BigInteger operations, RandomGenerator random) {
        if (operations.signum() < 0) throw new IllegalArgumentException("Negative operation count");
        BigInteger[] parts = operations.multiply(BigInteger.valueOf(chance))
                .divideAndRemainder(BigInteger.valueOf(maxChance));
        return parts[1].signum() != 0 && random.nextInt(maxChance) < parts[1].intValueExact()
                ? parts[0].add(BigInteger.ONE) : parts[0];
    }
}
