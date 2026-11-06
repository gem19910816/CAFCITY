package com.caf.lccustomcity;

import com.caf.lccustomcity.impl.CitySurveyMapItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Survival entry into the same city plan used by world generation. */
public final class CityNavigationItems {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, LcCustomCityMod.MODID);
    public static final DeferredHolder<Item, CitySurveyMapItem> SURVEY_MAP = ITEMS.register("city_survey_map",
            () -> new CitySurveyMapItem(new Item.Properties().stacksTo(1)));
    private CityNavigationItems() {}
    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        com.caf.lccustomcity.impl.CitySurvivalNavigation.register();
    }
}
