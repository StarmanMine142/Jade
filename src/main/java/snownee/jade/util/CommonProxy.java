package snownee.jade.util;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jetbrains.annotations.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.mojang.authlib.GameProfile;

import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.MatchTool;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.i18n.MavenVersionTranslator;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforgespi.language.ModFileScanData;
import snownee.jade.Jade;
import snownee.jade.addon.harvest.HarvestToolProvider;
import snownee.jade.addon.universal.ItemCollector;
import snownee.jade.addon.universal.ItemIterator;
import snownee.jade.addon.universal.ItemStorageProvider;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.view.EnergyView;
import snownee.jade.api.view.FluidView;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ViewGroup;
import snownee.jade.command.JadeServerCommand;
import snownee.jade.impl.WailaClientRegistration;
import snownee.jade.impl.WailaCommonRegistration;
import snownee.jade.impl.config.ServerPluginConfig;
import snownee.jade.impl.lookup.WrappedHierarchyLookup;
import snownee.jade.mixin.AbstractHorseAccess;
import snownee.jade.network.ClientHandshakePacket;
import snownee.jade.network.ReceiveDataPacket;
import snownee.jade.network.RequestBlockPacket;
import snownee.jade.network.RequestEntityPacket;
import snownee.jade.network.ServerHandshakePacket;
import snownee.jade.network.ShowOverlayPacket;

@Mod(Jade.ID)
public final class CommonProxy {

	@Nullable
	public static String getLastKnownUsername(@Nullable UUID uuid) {
		if (uuid == null) {
			return null;
		}
		Optional<GameProfile> optional = SkullBlockEntity.fetchGameProfile(uuid).getNow(Optional.empty());
		if (optional.isPresent()) {
			return optional.get().getName();
		}
		return UsernameCache.getLastKnownUsername(uuid);
	}

	public static File getConfigDirectory() {
		return FMLPaths.CONFIGDIR.get().toFile();
	}

	public static boolean isCorrectToolForDrops(BlockState state, Player player, Level level, BlockPos pos) {
		return EventHooks.doPlayerHarvestCheck(player, state, level, pos);
	}

	public static String getModIdFromItem(ItemStack stack) {
		if (isPhysicallyClient()) {
			CustomModelData modelData = stack.getOrDefault(DataComponents.CUSTOM_MODEL_DATA, CustomModelData.EMPTY);
			if (!CustomModelData.EMPTY.equals(modelData)) {
				for (String string : modelData.strings()) {
					if (string.startsWith("namespace:")) {
						return string.substring(10);
					}
				}
			}
		}
		if (stack.is(Items.PAINTING)) {
			CustomData customData = stack.getOrDefault(DataComponents.ENTITY_DATA, CustomData.EMPTY);
			if (!customData.isEmpty()) {
				return customData.read(Painting.VARIANT_MAP_CODEC).result()
						.flatMap(Holder::unwrapKey)
						.map(ResourceKey::location)
						.map(ResourceLocation::getNamespace)
						.orElse(ResourceLocation.DEFAULT_NAMESPACE);
			}
		}
		HolderLookup.Provider registries = RegistryAccess.EMPTY;
		if (isPhysicallyClient() && Minecraft.getInstance().level != null) {
			registries = Minecraft.getInstance().level.registryAccess();
		}
		return stack.getItem().getCreatorModId(registries, stack);
	}

	public static boolean isPhysicallyClient() {
		return FMLEnvironment.dist.isClient();
	}

	public static ItemCollector<?> createItemCollector(Accessor<?> accessor, Cache<Object, ItemCollector<?>> containerCache) {
		if (accessor.getTarget() instanceof AbstractHorseAccess) {
			return new ItemCollector<>(new ItemIterator.ContainerItemIterator(
					o -> {
						if (o instanceof AbstractHorseAccess horse) {
							return horse.getInventory();
						}
						return null;
					}, 2));
		}
		try {
			var storage = findItemHandler(accessor);
			if (storage != null) {
				return containerCache.get(storage, () -> new ItemCollector<>(JadeForgeUtils.fromItemHandler(storage, 0)));
			}
		} catch (Throwable e) {
			WailaExceptionHandler.handleErr(e, null, null);
		}
		final Container container = findContainer(accessor);
		if (container != null) {
			if (container instanceof ChestBlockEntity) {
				return new ItemCollector<>(new ItemIterator.ContainerItemIterator(
						a -> {
							if (a.getTarget() instanceof ChestBlockEntity be) {
								if (be.getBlockState().getBlock() instanceof ChestBlock chestBlock) {
									Container compound = ChestBlock.getContainer(
											chestBlock,
											be.getBlockState(),
											Objects.requireNonNull(be.getLevel()),
											be.getBlockPos(),
											false);
									if (compound != null) {
										return compound;
									}
								}
								return be;
							}
							return null;
						}, 0));
			}
			return new ItemCollector<>(new ItemIterator.ContainerItemIterator(0));
		}
		return ItemCollector.EMPTY;
	}

	@Nullable
	public static List<ViewGroup<ItemStack>> containerGroup(Container container, Accessor<?> accessor) {
		return containerGroup(container, accessor, CommonProxy::findContainer);
	}

	@Nullable
	public static List<ViewGroup<ItemStack>> containerGroup(
			Container container,
			Accessor<?> accessor,
			Function<Accessor<?>, Container> containerFinder) {
		try {
			return ItemStorageProvider.containerCache.get(
							container,
							() -> new ItemCollector<>(new ItemIterator.ContainerItemIterator(containerFinder, 0)))
					.update(accessor);
		} catch (Exception e) {
			return null;
		}
	}

	@Nullable
	public static List<ViewGroup<ItemStack>> storageGroup(Object storage, Accessor<?> accessor) {
		return storageGroup(storage, accessor, CommonProxy::findItemHandler);
	}

	@Nullable
	public static List<ViewGroup<ItemStack>> storageGroup(
			Object storage,
			Accessor<?> accessor,
			Function<Accessor<?>, Object> storageFinder) {
		try {
			//noinspection unchecked
			return ItemStorageProvider.containerCache.get(
					storage,
					() -> new ItemCollector<>(JadeForgeUtils.fromItemHandler(
							(IItemHandler) storage,
							0,
							(Function<Accessor<?>, @Nullable IItemHandler>) (Object) storageFinder))).update(
					accessor
			);
		} catch (Exception e) {
			return null;
		}
	}

	@Nullable
	public static IItemHandler findItemHandler(Accessor<?> accessor) {
		if (accessor instanceof BlockAccessor blockAccessor) {
			return accessor.getLevel().getCapability(
					Capabilities.ItemHandler.BLOCK,
					blockAccessor.getPosition(),
					blockAccessor.getBlockState(),
					blockAccessor.getBlockEntity(),
					null);
		} else if (accessor instanceof EntityAccessor entityAccessor) {
			return entityAccessor.getEntity().getCapability(Capabilities.ItemHandler.ENTITY);
		}
		return null;
	}

	@Nullable
	public static Container findContainer(Accessor<?> accessor) {
		Object target = accessor.getTarget();
		if (target == null && accessor instanceof BlockAccessor blockAccessor &&
				blockAccessor.getBlock() instanceof WorldlyContainerHolder holder) {
			return holder.getContainer(blockAccessor.getBlockState(), accessor.getLevel(), blockAccessor.getPosition());
		} else if (target instanceof Container container) {
			return container;
		}
		return null;
	}

	@Nullable
	public static List<ViewGroup<FluidView.Data>> wrapFluidStorage(Accessor<?> accessor) {
		IFluidHandler fluidHandler = getDefaultStorage(accessor, Capabilities.FluidHandler.BLOCK, Capabilities.FluidHandler.ENTITY);
		if (fluidHandler != null) {
			return JadeForgeUtils.fromFluidHandler(fluidHandler);
		}
		return null;
	}

	@Nullable
	public static List<ViewGroup<EnergyView.Data>> wrapEnergyStorage(Accessor<?> accessor) {
		IEnergyStorage energyStorage = getDefaultStorage(accessor, Capabilities.EnergyStorage.BLOCK, Capabilities.EnergyStorage.ENTITY);
		if (energyStorage != null) {
			var group = new ViewGroup<>(List.of(new EnergyView.Data(energyStorage.getEnergyStored(), energyStorage.getMaxEnergyStored())));
			group.getExtraData().putString("Unit", "FE");
			return List.of(group);
		}
		return null;
	}

	public static boolean isDevEnv() {
		return !FMLEnvironment.production;
	}

	public static ResourceLocation getId(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block);
	}

	public static ResourceLocation getId(EntityType<?> entityType) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
	}

	public static ResourceLocation getId(BlockEntityType<?> blockEntityType) {
		return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntityType);
	}

	public static String getPlatformIdentifier() {
		return "neoforge";
	}

	public static MutableComponent getProfessionName(VillagerProfession profession) {
		ResourceLocation profName = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession);
		return Component.translatable(EntityType.VILLAGER.getDescriptionId() + '.' +
				(!ResourceLocation.DEFAULT_NAMESPACE.equals(profName.getNamespace()) ? profName.getNamespace() + '.' : "") +
				profName.getPath());
	}

	private static void registerServerCommand(RegisterCommandsEvent event) {
		JadeServerCommand.register(event.getDispatcher());
	}

	public static boolean isBoss(Entity entity) {
		EntityType<?> entityType = entity.getType();
		return entityType.is(Tags.EntityTypes.BOSSES) || entityType == EntityType.ENDER_DRAGON || entityType == EntityType.WITHER;
	}

	public static ItemStack getBlockPickedResult(BlockState state, Player player, BlockHitResult hitResult) {
		return state.getCloneItemStack(player.level(), hitResult.getBlockPos(), true);
	}

	public static ItemStack getEntityPickedResult(Entity entity, Player player, EntityHitResult hitResult) {
		return MoreObjects.firstNonNull(entity.getPickResult(), ItemStack.EMPTY);
	}

	public static void playerHandshake(String clientVersion, ServerPlayer player) {
		if (!Jade.PROTOCOL_VERSION.equals(clientVersion)) {
			String version = ModList.get().getModContainerById(Jade.ID)
					.map($ -> MavenVersionTranslator.artifactVersionToString($.getModInfo().getVersion()))
					.orElse("UNKNOWN");
			player.displayClientMessage(Component.translatable("jade.protocolMismatch", version), false);
			return;
		}
		((JadeServerPlayer) player).jade$setConnected(true);
		Map<ResourceLocation, Object> configs = ServerPluginConfig.instance().values();
		List<Block> shearableBlocks = HarvestToolProvider.INSTANCE.getShearableBlocks();
		if (!configs.isEmpty()) {
			Jade.LOGGER.debug("Syncing config to {} ({})", player.getGameProfile().getName(), player.getGameProfile().getId());
		}
		List<ResourceLocation> blockProviderIds = WailaCommonRegistration.instance().blockDataProviders.mappedIds();
		List<ResourceLocation> entityProviderIds = WailaCommonRegistration.instance().entityDataProviders.mappedIds();
		player.connection.send(new ServerHandshakePacket(configs, shearableBlocks, blockProviderIds, entityProviderIds));
	}

	public static boolean isModLoaded(String modid) {
		try {
			ModList modList = ModList.get();
			if (modList == null) {
				return LoadingModList.get().getModFileById(modid) != null;
			}
			return modList.isLoaded(modid);
		} catch (Throwable e) {
			return false;
		}
	}

	private void loadComplete(FMLLoadCompleteEvent event) {
		/* off */
		List<String> classNames = ModList.get().getAllScanData()
				.stream()
				.flatMap($ -> $.getAnnotations().stream())
				.filter($ -> {
					if ($.annotationType().getClassName().equals(WailaPlugin.class.getName())) {
						String required = (String) $.annotationData().getOrDefault("value", "");
						return required.isEmpty() || ModList.get().isLoaded(required);
					}
					return false;
				})
				.map(ModFileScanData.AnnotationData::memberName)
				.toList();
		/* on */

		for (String className : classNames) {
			Jade.LOGGER.info("Start loading plugin from %s".formatted(className));
			try {
				Class<?> clazz = Class.forName(className);
				if (IWailaPlugin.class.isAssignableFrom(clazz)) {
					IWailaPlugin plugin = (IWailaPlugin) clazz.getDeclaredConstructor().newInstance();
					Stopwatch stopwatch = null;
					if (CommonProxy.isDevEnv()) {
						stopwatch = Stopwatch.createStarted();
					}
					WailaCommonRegistration common = WailaCommonRegistration.instance();
					common.startSession();
					plugin.register(common);
					if (isPhysicallyClient()) {
						WailaClientRegistration client = WailaClientRegistration.instance();
						client.startSession();
						plugin.registerClient(client);
						if (stopwatch != null) {
							Jade.LOGGER.info("Bootstrapped plugin from %s in %s".formatted(className, stopwatch));
						}
						client.endSession();
					}
					common.endSession();
					if (stopwatch != null) {
						Jade.LOGGER.info("Loaded plugin from %s in %s".formatted(className, stopwatch.stop()));
					}
				}
			} catch (Throwable e) {
				Jade.LOGGER.error("Error loading plugin at %s".formatted(className), e);
				Throwables.throwIfInstanceOf(e, IllegalStateException.class);
				if (className.startsWith("snownee.jade.")) {
					ExceptionUtils.wrapAndThrow(e);
				}
			}
		}
		Jade.loadComplete();
	}

	public static Component getFluidName(JadeFluidObject fluid) {
		return toFluidStack(fluid).getHoverName();
	}

	public static FluidStack toFluidStack(JadeFluidObject fluid) {
		int id = BuiltInRegistries.FLUID.getId(fluid.getType());
		Optional<Holder.Reference<Fluid>> holder = BuiltInRegistries.FLUID.get(id);
		return holder.isEmpty() ? FluidStack.EMPTY : new FluidStack(holder.get(), (int) fluid.getAmount(), fluid.getComponents());
	}

	public static int showOrHideFromServer(Collection<ServerPlayer> players, boolean show) {
		ShowOverlayPacket msg = new ShowOverlayPacket(show);
		for (ServerPlayer player : players) {
			player.connection.send(msg);
		}
		return players.size();
	}

	public static boolean isMultipartEntity(Entity target) {
		return target.isMultipartEntity();
	}

	public static Entity wrapPartEntityParent(Entity target) {
		if (target instanceof PartEntity<?> part) {
			return part.getParent();
		}
		return target;
	}

	public static int getPartEntityIndex(Entity entity) {
		if (!(entity instanceof PartEntity<?> part)) {
			return -1;
		}
		Entity parent = wrapPartEntityParent(entity);
		PartEntity<?>[] parts = parent.getParts();
		if (parts == null) {
			return -1;
		}
		return List.of(parts).indexOf(part);
	}

	public static Entity getPartEntity(Entity parent, int index) {
		if (parent == null) {
			return null;
		}
		if (index < 0) {
			return parent;
		}
		PartEntity<?>[] parts = parent.getParts();
		if (parts == null || index >= parts.length) {
			return parent;
		}
		return parts[index];
	}

	public static <T> T getDefaultStorage(
			Accessor<?> accessor,
			BlockCapability<T, ?> blockCapability,
			EntityCapability<T, ?> entityCapability) {
		if (accessor instanceof BlockAccessor blockAccessor) {
			return accessor.getLevel().getCapability(
					blockCapability,
					blockAccessor.getPosition(),
					blockAccessor.getBlockState(),
					blockAccessor.getBlockEntity(),
					null);
		} else if (accessor instanceof EntityAccessor entityAccessor) {
			return entityAccessor.getEntity().getCapability(entityCapability, null);
		}
		return null;
	}

	public static <T> boolean hasDefaultStorage(
			Accessor<?> accessor,
			BlockCapability<T, ?> blockCapability,
			EntityCapability<T, ?> entityCapability) {
		if (accessor instanceof BlockAccessor || accessor instanceof EntityAccessor) {
			return getDefaultStorage(accessor, blockCapability, entityCapability) != null;
		}
		return true;
	}

	public static boolean hasDefaultItemStorage(Accessor<?> accessor) {
		if (accessor.getTarget() == null && accessor instanceof BlockAccessor blockAccessor &&
				blockAccessor.getBlock() instanceof WorldlyContainerHolder) {
			return true;
		}
		return hasDefaultStorage(accessor, Capabilities.ItemHandler.BLOCK, Capabilities.ItemHandler.ENTITY);
	}

	public static boolean hasDefaultFluidStorage(Accessor<?> accessor) {
		return hasDefaultStorage(accessor, Capabilities.FluidHandler.BLOCK, Capabilities.FluidHandler.ENTITY);
	}

	public static boolean hasDefaultEnergyStorage(Accessor<?> accessor) {
		return hasDefaultStorage(accessor, Capabilities.EnergyStorage.BLOCK, Capabilities.EnergyStorage.ENTITY);
	}

	public static long bucketVolume() {
		return FluidType.BUCKET_VOLUME;
	}

	public static long blockVolume() {
		return FluidType.BUCKET_VOLUME;
	}

	public static void registerTagsUpdatedListener(BiConsumer<HolderLookup.Provider, Boolean> listener) {
		NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent event) -> listener.accept(
				event.getLookupProvider(),
				event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED));
	}

	public static boolean isCorrectConditions(List<LootItemCondition> conditions, ItemStack toolItem) {
		if (conditions.size() != 1) {
			return false;
		}
		LootItemCondition condition = conditions.getFirst();
		if (condition instanceof MatchTool(Optional<ItemPredicate> predicate)) {
			ItemPredicate itemPredicate = predicate.orElse(null);
			return itemPredicate != null && itemPredicate.test(toolItem);
		} else if (condition instanceof AnyOfCondition anyOfCondition) {
			for (LootItemCondition child : anyOfCondition.terms) {
				if (isCorrectConditions(List.of(child), toolItem)) {
					return true;
				}
			}
		}
		return false;
	}

	public static <T> Map.Entry<ResourceLocation, List<ViewGroup<T>>> getServerExtensionData(
			Accessor<?> accessor,
			WrappedHierarchyLookup<IServerExtensionProvider<T>> lookup) {
		for (var provider : lookup.wrappedGet(accessor)) {
			List<ViewGroup<T>> groups;
			try {
				groups = provider.getGroups(accessor);
			} catch (Exception e) {
				WailaExceptionHandler.handleErr(e, provider, null);
				continue;
			}
			if (groups != null) {
				return Map.entry(provider.getUid(), groups);
			}
		}
		return null;
	}

	public CommonProxy(IEventBus modBus) {
		modBus.addListener(this::loadComplete);
		modBus.addListener(
				RegisterPayloadHandlersEvent.class, event -> {
					event.registrar(Jade.ID)
							.versioned(Jade.PROTOCOL_VERSION)
							.optional()
							.playToClient(
									ReceiveDataPacket.TYPE,
									ReceiveDataPacket.CODEC,
									(payload, context) -> ReceiveDataPacket.handle(payload, context::enqueueWork))
							.playToServer(
									RequestEntityPacket.TYPE,
									RequestEntityPacket.CODEC,
									(payload, context) -> RequestEntityPacket.handle(payload, () -> (ServerPlayer) context.player()))
							.playToServer(
									RequestBlockPacket.TYPE,
									RequestBlockPacket.CODEC,
									(payload, context) -> RequestBlockPacket.handle(payload, () -> (ServerPlayer) context.player()))
							.playToServer(
									ClientHandshakePacket.TYPE,
									ClientHandshakePacket.CODEC,
									(payload, context) -> ClientHandshakePacket.handle(payload, () -> (ServerPlayer) context.player()))
							.playToClient(
									ServerHandshakePacket.TYPE,
									ServerHandshakePacket.CODEC,
									(payload, context) -> ServerHandshakePacket.handle(payload, context::enqueueWork))
							.playToClient(
									ShowOverlayPacket.TYPE,
									ShowOverlayPacket.CODEC,
									(payload, context) -> ShowOverlayPacket.handle(payload, context::enqueueWork));
				});
		NeoForge.EVENT_BUS.addListener(CommonProxy::registerServerCommand);
		if (isPhysicallyClient()) {
			ClientProxy.init(modBus);
		}
	}
}
