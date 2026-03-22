package com.podzolfarmer;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PodzolFarmerMod implements ModInitializer {
    public static final String MOD_ID = "podzolfarmer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Server-side init — all logic is client-side
    }
}
