package com.mmyzd.llor.displaymode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mmyzd.llor.ForgeMod;
import com.mmyzd.llor.displaymode.json.DisplayModeNode;
import com.mmyzd.llor.message.FloatingMessage;
import com.mmyzd.llor.message.MessageEvent;
import com.mmyzd.llor.util.EventBusWeakSubscriber;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.ClientResourcePackInfo;
import net.minecraft.client.resources.I18n;
import net.minecraft.resources.IReloadableResourceManager;
import net.minecraft.resources.IResource;
import net.minecraft.resources.IResourceManager;
import net.minecraft.resources.IResourcePack;
import net.minecraft.resources.ResourcePackType;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.resource.IResourceType;
import net.minecraftforge.resource.ISelectiveResourceReloadListener;

public class DisplayModeManager {

  private static final int MESSAGE_DURATION_TICKS = 500;
  private static final String ACCESSING_ERROR_TRANSLATION_KEY = "llor.message.accessing_error";
  private static final String PARSING_ERROR_TRANSLATION_KEY = "llor.message.parsing_error";
  private static final String BINDING_ERROR_TRANSLATION_KEY = "llor.message.binding_error";
  private static final String MESSAGE_IDENTIFIER_PREFIX = "display_mode_error:";
  private static final String RESOURCE_FOLDER_NAME = "displaymodes";
  private static final String RESOURCE_FILE_SUFFIX = ".json";
  private static final Gson GSON = new GsonBuilder()
          .registerTypeAdapter(DisplayModeNode.class, new DisplayModeNode.Adapter()).create();

  private Map<String, DisplayMode> displayModeByName = new HashMap<>();
  private DisplayMode[] displayModes = new DisplayMode[0];

  public DisplayModeManager() {
    MinecraftForge.EVENT_BUS.register(new EventHandler(this));
  }

  public DisplayMode getDisplayMode(String name) {
    DisplayMode displayMode = displayModeByName.get(name);
    return displayMode != null ? displayMode : new DisplayMode(name);
  }

  public DisplayMode getNextDisplayMode(DisplayMode currentDisplayMode) {
    for (int index = 0; index < displayModes.length; ++index) {
      if (displayModes[index].getName().equals(currentDisplayMode.getName())) {
        return displayModes[(index + 1) % displayModes.length];
      }
    }
    return displayModes.length > 0 ? displayModes[0] : new DisplayMode("standard");
  }

  // Minecraft's own code contains many bugs related to resource packs. The file path depth
  // checker has a reverted logic for zip type resource packs. So the depth limit must be 0 in that
  // case. On the other side, `resourceManager.getAllResourceLocations` will return an invalid path
  // which contains "//" in the path string for folder type resource packs.
  // TODO: Clean up this workaround after those bugs are fixed.
  private ResourceLocation[] getAllCanonicalResourceLocations() {
    HashSet<String> paths = new HashSet<>();
    Collection<ClientResourcePackInfo> packInfos =
            Minecraft.getInstance().getResourcePackList().getEnabledPacks();
    packInfos.forEach(packInfo -> {
      try {
        IResourcePack pack = packInfo.getResourcePack();
        Stream.of(0, 255).forEach(depthLimit -> pack.getAllResourceLocations(ResourcePackType.CLIENT_RESOURCES, RESOURCE_FOLDER_NAME,
                depthLimit, path -> path.toLowerCase().endsWith(RESOURCE_FILE_SUFFIX)).stream()
                .filter(resourceLocation -> ForgeMod.ID.equals(resourceLocation.getNamespace()))
                .forEach(resourceLocation -> paths.add(resourceLocation.getPath())));
      } catch (Exception exception) {
        exception.printStackTrace();
      }
    });
    return paths.stream().map(path -> new ResourceLocation(ForgeMod.ID, path))
            .toArray(ResourceLocation[]::new);
  }

  private void reload() {
    Map<String, DisplayMode> displayModeByName = new HashMap<>();
    for (ResourceLocation resourceLocation : getAllCanonicalResourceLocations()) {
      String path = resourceLocation.getPath();
      String name = path.substring(RESOURCE_FOLDER_NAME.length() + 1,
              path.length() - RESOURCE_FILE_SUFFIX.length());
      DisplayMode displayMode = load(resourceLocation, name);
      if (displayMode != null) {
        displayModeByName.put(name, displayMode);
      }
    }
    this.displayModeByName = displayModeByName;
    displayModes = displayModeByName.values().stream()
            .sorted(Comparator.comparingInt(DisplayMode::getListingPriority))
            .toArray(DisplayMode[]::new);
    MinecraftForge.EVENT_BUS.post(new DisplayModeUpdateEvent(this));
  }

  private DisplayMode load(ResourceLocation resourceLocation, String name) {
    IResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
    IResource resource;
    try {
      resource = resourceManager.getResource(resourceLocation);
    } catch (Exception exception) {
      displayException(exception, resourceLocation, ACCESSING_ERROR_TRANSLATION_KEY);
      return null;
    }

    DisplayModeNode displayModeNode;
    try {
      InputStream inputStream = resource.getInputStream();
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
      displayModeNode = GSON.fromJson(reader, DisplayModeNode.class);
      releaseResource(resource);
    } catch (Exception exception) {
      displayException(exception, resourceLocation, PARSING_ERROR_TRANSLATION_KEY);
      return null;
    }

    DisplayMode displayMode;
    if (displayModeNode.isValid()) {
      try {
        displayMode = new DisplayMode(name, displayModeNode);
        IResource textureResource = resourceManager.getResource(displayMode.getTexture());
        releaseResource(textureResource);
      } catch (Exception exception) {
        displayException(exception, resourceLocation, BINDING_ERROR_TRANSLATION_KEY);
        return null;
      }
    } else {
      displayMode = null;
    }

    return displayMode;
  }

  private void releaseResource(IResource resource) {
    try {
      resource.close();
    } catch (Exception exception) {
      exception.printStackTrace();
    }
  }

  private void displayException(Exception exception, ResourceLocation resourceLocation,
                                String translationKey) {
    String path = resourceLocation.getPath();
    MinecraftForge.EVENT_BUS
            .post(new MessageEvent(new FloatingMessage(I18n.format(translationKey, path),
                    MESSAGE_IDENTIFIER_PREFIX + path, MESSAGE_DURATION_TICKS)));
    exception.printStackTrace();
  }

  private static class EventHandler extends EventBusWeakSubscriber<DisplayModeManager>
          implements ISelectiveResourceReloadListener {

    private EventHandler(DisplayModeManager displayModeManager) {
      super(displayModeManager);
      IResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
      if (resourceManager instanceof IReloadableResourceManager) {
        ((IReloadableResourceManager) resourceManager).addReloadListener(this);
      }
    }

    @Override
    public void onResourceManagerReload(IResourceManager resourceManager,
                                        Predicate<IResourceType> resourcePredicate) {
      with(DisplayModeManager::reload);
    }
  }
}
