package snownee.jade.util;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.LazyLoadedValue;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.ItemDecoratorHandler;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.client.textures.FluidSpriteCache;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforgespi.language.IModInfo;
import snownee.jade.Jade;
import snownee.jade.JadeClient;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IWailaConfig;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.view.ClientViewGroup;
import snownee.jade.api.view.IClientExtensionProvider;
import snownee.jade.api.view.ViewGroup;
import snownee.jade.command.JadeClientCommand;
import snownee.jade.compat.JEICompat;
import snownee.jade.gui.HomeConfigScreen;
import snownee.jade.gui.PreviewOptionsScreen;
import snownee.jade.impl.BlockAccessorImpl;
import snownee.jade.impl.EntityAccessorImpl;
import snownee.jade.impl.ObjectDataCenter;
import snownee.jade.impl.WailaClientRegistration;
import snownee.jade.impl.theme.ThemeHelper;
import snownee.jade.impl.ui.FluidStackElement;
import snownee.jade.mixin.KeyAccess;
import snownee.jade.network.ClientHandshakePacket;
import snownee.jade.network.RequestBlockPacket;
import snownee.jade.network.RequestEntityPacket;
import snownee.jade.overlay.DatapackBlockManager;
import snownee.jade.overlay.OverlayRenderer;
import snownee.jade.overlay.WailaTickHandler;

public final class ClientProxy {

	private static final List<KeyMapping> keys = Lists.newArrayList();
	private static final List<PreparableReloadListener> listeners = Lists.newArrayList();
	public static boolean hasJEI = CommonProxy.isModLoaded("jei");
	public static boolean hasREI = false; //isModLoaded("roughlyenoughitems");
	public static boolean hasFastScroll = CommonProxy.isModLoaded("fastscroll");
	public static boolean hasAccessibilityMod = CommonProxy.isModLoaded("minecraft_access");
	private static boolean bossbarShown;
	private static int bossbarHeight;

	public static Optional<String> getModName(String namespace) {
		String modMenuKey = "modmenu.nameTranslation.%s".formatted(namespace);
		if (I18n.exists(modMenuKey)) {
			return Optional.of(I18n.get(modMenuKey));
		}
		return ModList.get().getModContainerById(namespace)
				.map(ModContainer::getModInfo)
				.map(IModInfo::getDisplayName)
				.filter(Predicate.not(Strings::isNullOrEmpty));
	}

	public static void registerCommands(RegisterClientCommandsEvent event) {
		event.getDispatcher().register(JadeClientCommand.create(
				Commands::literal,
				Commands::argument,
				(source, component) -> source.sendSuccess(() -> component, false),
				CommandSourceStack::sendFailure));
	}

	private static void onEntityJoin(EntityJoinLevelEvent event) {
		DatapackBlockManager.onEntityJoin(event.getEntity());
	}

	private static void onEntityLeave(EntityLeaveLevelEvent event) {
		DatapackBlockManager.onEntityLeave(event.getEntity());
	}

	private static void onTooltip(ItemTooltipEvent event) {
		JadeClient.appendModName(event.getToolTip(), event.getItemStack(), event.getContext(), event.getFlags());
	}

	public static void onRenderTick(GuiGraphics guiGraphics, float tickDelta) {
		try {
			OverlayRenderer.renderOverlay478757(guiGraphics, tickDelta);
		} catch (Throwable e) {
			WailaExceptionHandler.handleErr(e, null, null);
		} finally {
			bossbarShown = false;
		}
	}

	private static void onClientTick(ClientTickEvent.Post event) {
		try {
			WailaTickHandler.instance().tickClient();
		} catch (Throwable e) {
			WailaExceptionHandler.handleErr(e, null, null);
		}
	}

	private static void onPlayerJoin(ClientPlayerNetworkEvent.LoggingIn event) {
		try {
			event.getPlayer().connection.send(new ClientHandshakePacket(Jade.PROTOCOL_VERSION));
		} catch (Exception ignored) {
		}
	}

	private static void onPlayerLeave(ClientPlayerNetworkEvent.LoggingOut event) {
		ObjectDataCenter.serverConnected = false;
		WailaClientRegistration.instance().setServerConfig(Map.of());
	}

	private static void onKeyPressed(InputEvent.Key event) {
		JadeClient.onKeyPressed(event.getAction());
		if (JadeClient.showUses != null) {
			//REICompat.onKeyPressed(1);
			if (hasJEI) {
				JEICompat.onKeyPressed(1);
			}
		}
	}

	private static void onGui(ScreenEvent.Init.Pre event) {
		JadeClient.onGui(event.getScreen());
	}

	public static KeyMapping registerKeyBinding(String desc, int defaultKey) {
		KeyMapping key = new KeyMapping(
				"key.jade." + desc,
				KeyConflictContext.IN_GAME,
				KeyModifier.NONE,
				InputConstants.Type.KEYSYM.getOrCreate(defaultKey),
				"modmenu.nameTranslation.jade");
		keys.add(key);
		return key;
	}

	public static boolean shouldRegisterRecipeViewerKeys() {
		return hasJEI || hasREI;
	}

	public static void requestBlockData(BlockAccessor accessor, List<IServerDataProvider<BlockAccessor>> providers) {
		Objects.requireNonNull(Minecraft.getInstance().getConnection())
				.send((new RequestBlockPacket(new BlockAccessorImpl.SyncData(accessor), providers)));
	}

	public static void requestEntityData(EntityAccessor accessor, List<IServerDataProvider<EntityAccessor>> providers) {
		Objects.requireNonNull(Minecraft.getInstance().getConnection()).send((
				new RequestEntityPacket(new EntityAccessorImpl.SyncData(accessor), providers)));
	}

	public static IElement elementFromLiquid(BlockState blockState) {
		FluidState fluidState = blockState.getFluidState();
		return new FluidStackElement(JadeFluidObject.of(fluidState.getType()));//.size(new Size(18, 18));
	}

	public static void registerReloadListener(ResourceManagerReloadListener listener) {
		listeners.add(listener);
	}

	private static void onDrawBossBar(CustomizeGuiOverlayEvent.BossEventProgress event) {
		IWailaConfig.BossBarOverlapMode mode = Jade.config().general().getBossBarOverlapMode();
		if (mode == IWailaConfig.BossBarOverlapMode.NO_OPERATION) {
			return;
		}
		if (mode == IWailaConfig.BossBarOverlapMode.HIDE_BOSS_BAR && OverlayRenderer.shown) {
			event.setCanceled(true);
			return;
		}
		if (mode == IWailaConfig.BossBarOverlapMode.PUSH_DOWN) {
			if (event.isCanceled()) {
				return;
			}
			bossbarHeight = event.getY() + event.getIncrement();
			bossbarShown = true;
		}
	}

	@Nullable
	public static Rect2i getBossBarRect() {
		if (!bossbarShown) {
			return null;
		}
		int i = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int k = i / 2 - 91;
		return new Rect2i(k, 12, 182, bossbarHeight - 12);
	}

	public static boolean isShowDetailsPressed() {
		return JadeClient.showDetails.isDown();
	}

	public static boolean shouldShowWithGui(Minecraft mc, @Nullable Screen screen) {
		return screen == null || shouldShowBeforeGui(mc, screen) || shouldShowAfterGui(mc, screen);
	}

	public static boolean shouldShowAfterGui(Minecraft mc, @NotNull Screen screen) {
		return screen instanceof PreviewOptionsScreen || screen instanceof ChatScreen;
	}

	public static boolean shouldShowBeforeGui(Minecraft mc, @NotNull Screen screen) {
		if (mc.level == null) {
			return false;
		}
		IWailaConfig.General config = IWailaConfig.get().general();
		return !config.shouldHideFromGUIs();
	}

	public static void getFluidSpriteAndColor(JadeFluidObject fluid, BiConsumer<@Nullable TextureAtlasSprite, Integer> consumer) {
		Fluid type = fluid.getType();
		FluidStack fluidStack = CommonProxy.toFluidStack(fluid);
		IClientFluidTypeExtensions handler = IClientFluidTypeExtensions.of(type);
		ResourceLocation fluidStill = handler.getStillTexture(fluidStack);
		TextureAtlasSprite fluidStillSprite = FluidSpriteCache.getSprite(fluidStill);
		int fluidColor = handler.getTintColor(fluidStack);
		if (OverlayRenderer.alpha != 1) {
			fluidColor = IWailaConfig.Overlay.applyAlpha(fluidColor, OverlayRenderer.alpha);
		}
		consumer.accept(fluidStillSprite, fluidColor);
	}

	public static KeyMapping registerDetailsKeyBinding() {
		return registerKeyBinding("show_details", InputConstants.KEY_LSHIFT);
	}

	public static void renderItemDecorationsExtra(GuiGraphics guiGraphics, Font font, ItemStack stack, int x, int y, String text) {
		ItemDecoratorHandler.of(stack).render(guiGraphics, font, stack, x, y);
	}

	public static InputConstants.Key getBoundKeyOf(KeyMapping keyMapping) {
		return keyMapping.getKey();
	}

	public static GameType getGameMode() {
		MultiPlayerGameMode gameMode = Minecraft.getInstance().gameMode;
		return gameMode == null ? GameType.SURVIVAL : gameMode.getPlayerMode();
	}

	public static boolean hasAccessibilityMod() {
		return hasAccessibilityMod;
	}

	@Nullable
	public static <IN, OUT> List<ClientViewGroup<OUT>> mapToClientGroups(
			Accessor<?> accessor,
			ResourceLocation key,
			StreamCodec<RegistryFriendlyByteBuf, Map.Entry<ResourceLocation, List<ViewGroup<IN>>>> codec,
			Function<ResourceLocation, IClientExtensionProvider<IN, OUT>> mapper,
			ITooltip tooltip) {
		Tag tag = accessor.getServerData().get(key.toString());
		if (tag == null) {
			return null;
		}
		Map.Entry<ResourceLocation, List<ViewGroup<IN>>> entry = accessor.decodeFromNbt(codec, tag).orElse(null);
		if (entry == null) {
			return null;
		}
		IClientExtensionProvider<IN, OUT> provider = mapper.apply(entry.getKey());
		if (provider == null) {
			return null;
		}
		try {
			return provider.getClientGroups(accessor, entry.getValue());
		} catch (Exception e) {
			WailaExceptionHandler.handleErr(e, provider, tooltip::add);
			return null;
		}
	}

	public static float getEnchantPowerBonus(BlockState state, Level world, BlockPos pos) {
		if (WailaClientRegistration.instance().customEnchantPowers.containsKey(state.getBlock())) {
			return WailaClientRegistration.instance().customEnchantPowers.get(state.getBlock()).getEnchantPowerBonus(state, world, pos);
		}
		return state.is(Blocks.BOOKSHELF) ? 1 : 0;
	}

	public static void init(IEventBus modBus) {
		NeoForge.EVENT_BUS.addListener(ClientProxy::onEntityJoin);
		NeoForge.EVENT_BUS.addListener(ClientProxy::onEntityLeave);
		NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ClientProxy::onTooltip);
		NeoForge.EVENT_BUS.addListener(ClientProxy::onClientTick);
		NeoForge.EVENT_BUS.addListener(ClientProxy::onPlayerJoin);
		NeoForge.EVENT_BUS.addListener(ClientProxy::onPlayerLeave);
		NeoForge.EVENT_BUS.addListener(ClientProxy::registerCommands);
		NeoForge.EVENT_BUS.addListener(ClientProxy::onKeyPressed);
		NeoForge.EVENT_BUS.addListener(ClientProxy::onGui);
		NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, ClientProxy::onDrawBossBar);
		NeoForge.EVENT_BUS.addListener(
				RenderGuiEvent.Post.class, event -> {
					if (Minecraft.getInstance().screen == null) {
						onRenderTick(event.getGuiGraphics(), event.getPartialTick().getRealtimeDeltaTicks());
					}
				});
		NeoForge.EVENT_BUS.addListener(
				ScreenEvent.Render.Pre.class, event -> {
					Minecraft mc = Minecraft.getInstance();
					Screen screen = event.getScreen();
					if (shouldShowBeforeGui(mc, screen) && !shouldShowAfterGui(mc, screen)) {
						onRenderTick(event.getGuiGraphics(), event.getPartialTick());
					}
				});
		NeoForge.EVENT_BUS.addListener(
				ScreenEvent.Render.Post.class, event -> {
					if (shouldShowAfterGui(Minecraft.getInstance(), event.getScreen())) {
						onRenderTick(event.getGuiGraphics(), event.getPartialTick());
					}
				});
		modBus.addListener(
				RegisterClientReloadListenersEvent.class, event -> {
					event.registerReloadListener(ThemeHelper.INSTANCE);
					listeners.forEach(event::registerReloadListener);
					listeners.clear();
				});
		modBus.addListener(
				RegisterKeyMappingsEvent.class, event -> {
					keys.forEach(event::register);
					keys.clear();
				});
		ModLoadingContext.get().registerExtensionPoint(
				IConfigScreenFactory.class,
				() -> (modContainer, screen) -> new HomeConfigScreen(screen));

		for (int i = 320; i < 330; i++) {
			InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(i);
			//noinspection deprecation
			((KeyAccess) (Object) key).setDisplayName(new LazyLoadedValue<>(() -> Component.translatable(key.getName())));
		}
		JadeClient.init();
	}
}
