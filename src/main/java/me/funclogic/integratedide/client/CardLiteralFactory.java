package me.funclogic.integratedide.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.extensions.IFluidExtension;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import org.cyclops.commoncapabilities.api.ingredient.IngredientComponent;
import org.cyclops.commoncapabilities.api.ingredient.MixedIngredients;
import org.cyclops.integrateddynamics.core.evaluate.variable.ValueObjectTypeIngredients;

/** Converts Novel literals to the exact values accepted by vanilla programmer elements. */
public final class CardLiteralFactory {
    private CardLiteralFactory() {
    }

    public static ItemStack itemStack(String value) {
        Identifier id = requiredId(value, "item");
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("Unknown item identifier: " + value);
        }
        return new ItemStack(BuiltInRegistries.ITEM.getValue(id));
    }

    public static ItemStack fluidBucket(String value) {
        Identifier id = requiredId(value, "fluid");
        if (!BuiltInRegistries.FLUID.containsKey(id)) {
            throw new IllegalArgumentException("Unknown fluid identifier: " + value);
        }
        Fluid fluid = BuiltInRegistries.FLUID.getValue(id);
        if (!(fluid instanceof IFluidExtension extension)) {
            throw new IllegalArgumentException("Fluid " + value + " does not expose a NeoForge fluid type.");
        }
        ItemStack bucket = extension.getFluidType().getBucket(new FluidStack(fluid, FluidType.BUCKET_VOLUME));
        if (bucket.isEmpty()) {
            throw new IllegalArgumentException("Fluid " + value
                    + " has no bucket item and cannot be entered in the vanilla Logic Programmer.");
        }
        return bucket;
    }

    public static ValueObjectTypeIngredients.ValueIngredients ingredientsTag(String value) {
        Identifier id = requiredId(value, "tag");
        Map<IngredientComponent<?, ?>, List<?>> values = new HashMap<>();
        List<ItemStack> items = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, id))) {
            items.add(new ItemStack(holder.value()));
        }
        if (!items.isEmpty()) {
            values.put(IngredientComponent.ITEMSTACK, items);
        }
        List<FluidStack> fluids = new ArrayList<>();
        for (Holder<Fluid> holder : BuiltInRegistries.FLUID.getTagOrEmpty(TagKey.create(Registries.FLUID, id))) {
            fluids.add(new FluidStack(holder.value(), FluidType.BUCKET_VOLUME));
        }
        if (!fluids.isEmpty()) {
            values.put(IngredientComponent.FLUIDSTACK, fluids);
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("No item or fluid tag named #" + value + " is loaded on this client.");
        }
        return ValueObjectTypeIngredients.ValueIngredients.of(new MixedIngredients(values));
    }

    private static Identifier requiredId(String value, String kind) {
        Identifier id = Identifier.tryParse(value);
        if (id == null) {
            throw new IllegalArgumentException("Invalid " + kind + " identifier: " + value);
        }
        return id;
    }
}
