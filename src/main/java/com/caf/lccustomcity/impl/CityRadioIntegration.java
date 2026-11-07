package com.caf.lccustomcity.impl;

import com.chaosz.tarkovstamina.radio.api.RadioLocationProvider;
import com.chaosz.tarkovstamina.radio.api.RadioLocations;
import com.chaosz.tarkovstamina.radio.api.RadioSite;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;

/** Supplies only authoritative world locations; reception and stories belong to CAF core. */
public final class CityRadioIntegration {
    private CityRadioIntegration() {}
    public static void register() {
        CityRadioSites.register();
        RadioLocations.registerCityProvider(new RadioLocationProvider() {
            @Override public List<RadioSite> cachedSites(ServerLevel level) {
                return CityRadioSites.cachedSites(level).stream().map(CityRadioIntegration::site).toList();
            }
            @Override public void requestCities(ServerLevel level, Consumer<List<RadioSite>> reply) {
                CityRadioSites.requestCities(level, sites -> reply.accept(sites.stream().map(CityRadioIntegration::site).toList()));
            }
        });
    }
    private static RadioSite site(CityRadioSites.Site site) {
        return new RadioSite(site.id(), site.name(), site.kind(), site.tier(), site.x(), site.z(), site.city());
    }
}
