package com.mmyzd.llor;

import java.util.ArrayList;
import com.mmyzd.llor.config.Config;
import com.mmyzd.llor.config.ConfigManager;
import com.mmyzd.llor.message.MessagePresenter;
import com.mmyzd.llor.overlay.OverlayProvider;
import com.mmyzd.llor.overlay.OverlayRenderer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ForgeMod.ID)
public class LightLevelOverlayReloaded implements ForgeMod {

  private final ArrayList<Object> services = new ArrayList<>();

  public LightLevelOverlayReloaded() {
    MessagePresenter messagePresenter = new MessagePresenter();
    services.add(messagePresenter);

    ConfigManager configManager = new ConfigManager();
    services.add(configManager);

    OverlayProvider overlayProvider = new OverlayProvider(configManager);
    OverlayRenderer overlayRenderer = new OverlayRenderer(configManager, overlayProvider);
    services.add(overlayRenderer);
  }
}
