package com.xor.uhdfast.gui;

import net.minecraft.client.gui.screens.Screen;

public class UHDFastConfigFactory {

    public static Screen createConfigScreen(Screen parentScreen) {
        return new UHDFastConfigScreen(parentScreen);
    }
}
