package com.nisovin.shopkeepers.shopobjects.living;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;

import com.nisovin.shopkeepers.api.ShopkeepersPlugin;
import com.nisovin.shopkeepers.api.shopkeeper.ShopCreationData;
import com.nisovin.shopkeepers.api.shopobjects.living.LivingShopObject;
import com.nisovin.shopkeepers.api.util.ChunkCoords;
import com.nisovin.shopkeepers.compat.NMSManager;
import com.nisovin.shopkeepers.config.Settings;
import com.nisovin.shopkeepers.debug.DebugOptions;
import com.nisovin.shopkeepers.debug.events.DebugListener;
import com.nisovin.shopkeepers.debug.events.EventDebugListener;
import com.nisovin.shopkeepers.lang.Messages;
import com.nisovin.shopkeepers.shopkeeper.AbstractShopkeeper;
import com.nisovin.shopkeepers.shopobjects.entity.AbstractEntityShopObject;
import com.nisovin.shopkeepers.util.CyclicCounter;
import com.nisovin.shopkeepers.util.EntityUtils;
import com.nisovin.shopkeepers.util.Log;
import com.nisovin.shopkeepers.util.RateLimiter;
import com.nisovin.shopkeepers.util.ShopkeeperUtils;
import com.nisovin.shopkeepers.util.Utils;

public class SKLivingShopObject<E extends LivingEntity> extends AbstractEntityShopObject implements LivingShopObject {

	protected static final double SPAWN_LOCATION_OFFSET = 0.98D;
	protected static final double SPAWN_LOCATION_RANGE = 2.0D;
	protected static final int CHECK_PERIOD_SECONDS = 10;
	private static final CyclicCounter nextCheckingOffset = new CyclicCounter(CHECK_PERIOD_SECONDS);
	// If the entity could not be respawned this amount of times, we throttle its tick rate (i.e. the rate at which we
	// attempt to respawn it):
	protected static final int MAX_RESPAWN_ATTEMPTS = 5;
	protected static final int THROTTLED_CHECK_PERIOD_SECONDS = 60;

	protected final LivingShops livingShops;
	private final SKLivingShopObjectType<?> livingObjectType;
	private E entity;
	private int respawnAttempts = 0;
	private boolean debuggingSpawn = false;
	private static long lastSpawnDebugging = 0; // Shared among all living shopkeepers to prevent spam

	// Initial offset between [0, CHECK_PERIOD_SECONDS) for load balancing:
	private final int checkingOffset = nextCheckingOffset.getAndIncrement();
	private final RateLimiter checkLimiter = RateLimiter.withInitialOffset(CHECK_PERIOD_SECONDS, checkingOffset);

	protected SKLivingShopObject(	LivingShops livingShops, SKLivingShopObjectType<?> livingObjectType,
									AbstractShopkeeper shopkeeper, ShopCreationData creationData) {
		super(shopkeeper, creationData);
		this.livingShops = livingShops;
		this.livingObjectType = livingObjectType;
	}

	@Override
	public SKLivingShopObjectType<?> getType() {
		return livingObjectType;
	}

	@Override
	public EntityType getEntityType() {
		return livingObjectType.getEntityType();
	}

	@Override
	public void load(ConfigurationSection configSection) {
		super.load(configSection);
		// Check for legacy uuid entry:
		if (configSection.contains("uuid")) {
			// Mark dirty to remove this entry with the next save:
			shopkeeper.markDirty();
		}
	}

	@Override
	public void save(ConfigurationSection configSection) {
		super.save(configSection);
	}

	// ACTIVATION

	@Override
	public E getEntity() {
		// Is-active check:
		// Note: Entity#isValid also checks if the entity is dead.
		// Note: The entity's valid flag may get set one tick after the chunk got unloaded. We might also want to check
		// if the entity is still valid when handling chunk unloads, and the entity might be located in a chunk
		// different to the one that is currently being unloaded (and that other chunk might already have been
		// unloaded).
		// In the past we therefore also checked if the chunk in which the entity is currently located is still loaded.
		// However, on later versions of Spigot (late 1.14.1 and above), Entity#isValid also already checks if the chunk
		// is currently loaded, so this is no longer required.
		// TODO: Omit the slightly costly isValid check here? Maybe only do it when checking isActive explicitly.
		if (entity != null && entity.isValid()) {
			return entity;
		}
		return null;
	}

	protected void assignShopkeeperMetadata(E entity) {
		entity.setMetadata(ShopkeeperUtils.SHOPKEEPER_METADATA_KEY, new FixedMetadataValue(ShopkeepersPlugin.getInstance(), true));
	}

	protected void removeShopkeeperMetadata(E entity) {
		entity.removeMetadata(ShopkeeperUtils.SHOPKEEPER_METADATA_KEY, ShopkeepersPlugin.getInstance());
	}

	// Places the entity at the exact location it would fall to, within a range of at most 1 block below the spawn block
	// (because shopkeepers might have been placed 1 block above passable or non-full blocks).
	private Location getSpawnLocation() {
		World world = Bukkit.getWorld(shopkeeper.getWorldName());
		if (world == null) return null; // World not loaded
		Location spawnLocation = new Location(world, shopkeeper.getX() + 0.5D, shopkeeper.getY() + SPAWN_LOCATION_OFFSET, shopkeeper.getZ() + 0.5D);
		double distanceToGround = Utils.getCollisionDistanceToGround(spawnLocation, SPAWN_LOCATION_RANGE);
		if (distanceToGround == SPAWN_LOCATION_RANGE) {
			// No collision within the checked range, remove offset from spawn location:
			distanceToGround = SPAWN_LOCATION_OFFSET;
		}
		// Adjust spawn location:
		spawnLocation.add(0.0D, -distanceToGround, 0.0D);
		return spawnLocation;
	}

	// Any preparation that needs to be done before spawning. Might only allow limited operations.
	protected void prepareEntity(E entity) {
		// Assign metadata for easy identification by other plugins:
		this.assignShopkeeperMetadata(entity);

		// Don't save the entity to the world data:
		entity.setPersistent(false);

		// Apply name (if it has/uses one):
		this.applyName(entity, shopkeeper.getName());

		// Clear equipment:
		// Doing this during entity preparation resolves some issue with the equipment not getting cleared (or at least
		// not getting cleared visually).
		EntityEquipment equipment = entity.getEquipment();
		equipment.clear();

		// We give entities which would usually burn in sunlight an indestructible item as helmet. This results in less
		// EntityCombustEvents that need to be processed.
		if (entity instanceof Zombie || entity instanceof Skeleton) {
			// Note: Phantoms also burn in sunlight, but setting an helmet has no effect for them.
			// Note: Buttons are small enough to not be visible inside the entity's head (even for their baby variants).
			equipment.setHelmet(new ItemStack(Material.STONE_BUTTON));
		}
	}

	// Any clean up that needs to happen for the entity. The entity might not be fully setup yet.
	protected void cleanUpEntity(E entity) {
		assert entity != null;
		// Disable AI:
		this.cleanupAI();

		// Remove metadata again:
		this.removeShopkeeperMetadata(entity);
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean spawn() {
		// Check if our current old entity is still valid:
		if (this.isActive()) return true;
		if (entity != null) {
			// Perform cleanup before replacing the currently stored entity with a new one:
			this.cleanUpEntity(entity);
			entity = null; // Reset
		}

		// Prepare spawn location:
		Location spawnLocation = this.getSpawnLocation();
		if (spawnLocation == null) {
			return false; // World not loaded
		}
		World world = spawnLocation.getWorld();
		assert world != null;

		// Spawn entity:
		// TODO Check if the block is passable before spawning there?
		EntityType entityType = this.getEntityType();
		// Note: We expect this type of entity to be spawnable, and not result in an IllegalArgumentException.
		entity = (E) world.spawn(spawnLocation, entityType.getEntityClass(), (entity) -> {
			// Note: This callback is run after the entity has been prepared (this includes the creation of random
			// equipment and the random spawning of passengers) and right before the entity gets added to the world
			// (which triggers the corresponding CreatureSpawnEvent).

			// Debugging entity spawning:
			if (entity.isDead()) {
				Log.debug("Spawning shopkeeper entity is dead already!");
			}

			// Prepare entity, before it gets spawned:
			prepareEntity((E) entity);

			// Try to bypass entity-spawn blocking plugins (right before this specific entity is about to get spawned):
			livingShops.forceCreatureSpawn(spawnLocation, entityType);
		});
		assert entity != null;

		boolean success = this.isActive();
		if (success) {
			// Further setup entity after it was successfully spawned:
			// Some entities randomly spawn with passengers:
			for (Entity passenger : entity.getPassengers()) {
				passenger.remove();
			}
			entity.eject(); // Some entities might automatically mount on nearby entities (like baby zombies on chicken)
			entity.setRemoveWhenFarAway(false);
			entity.setCanPickupItems(false);

			// Disable breeding:
			if (entity instanceof Ageable) {
				Ageable ageable = ((Ageable) entity);
				ageable.setAdult();
				ageable.setBreed(false);
				ageable.setAgeLock(true);
			}

			// Set the entity to an adult if we don't support its baby property yet:
			NMSManager.getProvider().setExclusiveAdult(entity);

			// Remove potion effects:
			for (PotionEffect potionEffect : entity.getActivePotionEffects()) {
				entity.removePotionEffect(potionEffect.getType());
			}

			// Overwrite AI:
			this.overwriteAI();
			// Register the entity for our custom AI processing:
			livingShops.getLivingEntityAI().addEntity(entity);

			// Prevent raider shopkeepers from participating in nearby raids:
			if (entity instanceof Raider) {
				NMSManager.getProvider().setCanJoinRaid((Raider) entity, false);
			}

			// Apply sub type:
			this.onSpawn(entity);
		} else {
			// Failure:
			// Reset entity:
			E localEntity = this.entity;
			this.cleanUpEntity(localEntity);
			this.entity = null;

			// TODO Config option to delete the shopkeeper on failed spawn attempt? Check for this during shop creation?

			// Debug, if not already debugging and cooldown is over:
			boolean debug = (Settings.debug && !debuggingSpawn && (System.currentTimeMillis() - lastSpawnDebugging) > (5 * 60 * 1000)
					&& localEntity.isDead() && ChunkCoords.isChunkLoaded(localEntity.getLocation()));

			Log.warning("Failed to spawn shopkeeper entity: Entity dead: " + localEntity.isDead() + ", entity valid: " + localEntity.isValid()
					+ ", chunk loaded: " + ChunkCoords.isChunkLoaded(localEntity.getLocation()) + ", debug -> " + debug);

			// Debug entity spawning:
			if (debug) {
				// Print chunk's entity counts:
				EntityUtils.printEntityCounts(spawnLocation.getChunk());

				// Try again and log event activity:
				debuggingSpawn = true;
				lastSpawnDebugging = System.currentTimeMillis();
				Log.info("Trying again and logging event activity ..");

				// Log all events occurring during spawning, and their registered listeners:
				DebugListener debugListener = DebugListener.register(true, true);

				// Log creature spawn handling:
				EventDebugListener<CreatureSpawnEvent> spawnListener = new EventDebugListener<>(CreatureSpawnEvent.class, (priority, event) -> {
					LivingEntity spawnedEntity = event.getEntity();
					Log.info("  CreatureSpawnEvent (" + priority + "): " + "cancelled: " + event.isCancelled() + ", dead: " + spawnedEntity.isDead()
							+ ", valid: " + spawnedEntity.isValid() + ", chunk loaded: " + ChunkCoords.isChunkLoaded(spawnedEntity.getLocation()));
				});

				// Try to spawn the entity again:
				success = this.spawn();

				// Unregister listeners again:
				debugListener.unregister();
				spawnListener.unregister();
				debuggingSpawn = false;
				Log.info(".. Done. Successful: " + success);
			}
		}

		if (success) {
			// Reset respawn attempts counter and tick rate:
			respawnAttempts = 0;
			this.resetTickRate();
		}
		return success;
	}

	// Gets called after the entity was spawned. Can be used to apply any additionally configured mob-specific setup.
	protected void onSpawn(E entity) {
		// Nothing to do by default.
	}

	protected void overwriteAI() {
		// Setting the entity non-collidable:
		entity.setCollidable(false);
		// TODO Only required to handle the 'look-at-nearby-player' behavior. Maybe replace this with something own?
		NMSManager.getProvider().overwriteLivingEntityAI(entity);

		// Disable AI (also disables gravity) and replace it with our own handling:
		this.setNoAI(entity);

		if (Settings.silenceLivingShopEntities) {
			entity.setSilent(true);
		}
		if (Settings.disableGravity) {
			this.setNoGravity(entity);
			// When gravity is disabled, we may also able to disable collisions / the pushing of mobs via the noclip
			// flag. However, this might not properly work for Vex, since they disable their noclip again after their
			// movement.
			// TODO Still required? Bukkit's setCollidable API might actually work now.
			NMSManager.getProvider().setNoclip(entity);
		}
	}

	protected final void setNoAI(E entity) {
		entity.setAI(false);
		// Note on Bukkit's 'isAware' flag added in MC 1.15: Disables the AI logic similarly to NoAI, but the mob can
		// still move when being pushed around or due to gravity.
		// TODO The collidable API has been reworked to actually work now. Together with the isAware flag this could be
		// an alternative to using NoAI and then having to handle gravity on our own.
		// However, for now we prefer using NoAI. This might be safer in regards to potential future issues and also
		// automatically handles other cases, like players pushing entities around by hitting them.

		// Making sure that Spigot's entity activation range does not keep this entity ticking, because it assumes that
		// it is currently falling:
		// TODO This can be removed once Spigot ignores NoAI entities.
		NMSManager.getProvider().setOnGround(entity, true);
	}

	protected final void setNoGravity(E entity) {
		entity.setGravity(false);

		// Making sure that Spigot's entity activation range does not keep this entity ticking, because it assumes
		// that it is currently falling:
		NMSManager.getProvider().setOnGround(entity, true);
	}

	protected void cleanupAI() {
		// Disable AI:
		livingShops.getLivingEntityAI().removeEntity(entity);
	}

	@Override
	public void despawn() {
		if (entity == null) return;

		// Clean up entity:
		this.cleanUpEntity(entity);

		// Remove entity:
		entity.remove();
		entity = null;
	}

	@Override
	public Location getLocation() {
		if (this.isActive()) {
			return entity.getLocation();
		} else {
			return null;
		}
	}

	// TICKING

	@Override
	public void tick() {
		super.tick();
		if (checkLimiter.request()) {
			this.check();

			// Indicate ticking activity for visualization:
			this.indicateTickActivity();
		}
	}

	private boolean isTickRateThrottled() {
		return (checkLimiter.getThreshold() == THROTTLED_CHECK_PERIOD_SECONDS);
	}

	private void throttleTickRate() {
		if (this.isTickRateThrottled()) return; // Already throttled
		Log.debug("Throttling tick rate");
		checkLimiter.setThreshold(THROTTLED_CHECK_PERIOD_SECONDS);
		checkLimiter.setRemainingThreshold(THROTTLED_CHECK_PERIOD_SECONDS + checkingOffset);
	}

	private void resetTickRate() {
		checkLimiter.setThreshold(CHECK_PERIOD_SECONDS);
		checkLimiter.setRemainingThreshold(CHECK_PERIOD_SECONDS + checkingOffset);
	}

	private void check() {
		if (!this.isActive()) {
			this.respawnInactiveEntity();
		} else {
			this.teleportBackIfMoved();
			this.removePotionEffects();
		}
	}

	// True if the entity was respawned.
	private boolean respawnInactiveEntity() {
		assert !this.isActive();
		Log.debug(() -> "Shopkeeper (" + shopkeeper.getPositionString() + ") is missing, attemtping respawn");
		if (entity != null && ChunkCoords.isSameChunk(shopkeeper.getLocation(), entity.getLocation())) {
			// The chunk was silently unloaded before:
			Log.debug(
					() -> "  Chunk got silently unloaded or mob got removed! (dead: " + entity.isDead() + ", valid: "
							+ entity.isValid() + ", chunk loaded: " + ChunkCoords.isChunkLoaded(entity.getLocation()) + ")"
			);
		}
		boolean spawned = this.spawn(); // This will load the chunk if necessary
		if (!spawned) {
			// TODO Maybe add a setting to remove shopkeeper if it can't be spawned a certain amount of times?
			Log.debug("  Respawn failed");
			respawnAttempts += 1;
			if (respawnAttempts >= MAX_RESPAWN_ATTEMPTS) {
				// Throttle the rate at which we attempt to respawn the entity:
				this.throttleTickRate();
			}
		} // Else: respawnAttempts and tick rate got reset.
		return spawned;
	}

	private void teleportBackIfMoved() {
		assert this.isActive();
		Location entityLoc = entity.getLocation();
		Location spawnLocation = this.getSpawnLocation();
		assert spawnLocation != null; // Since entity is active
		if (!entityLoc.getWorld().equals(spawnLocation.getWorld()) || entityLoc.distanceSquared(spawnLocation) > 0.4D) {
			// Teleport back:
			Log.debug(DebugOptions.regularTickActivities, () -> "Shopkeeper (" + shopkeeper.getPositionString()
					+ ") out of place, teleporting back");
			spawnLocation.setYaw(entityLoc.getYaw());
			spawnLocation.setPitch(entityLoc.getPitch());
			entity.teleport(spawnLocation);
			this.overwriteAI();
		}
	}

	private void removePotionEffects() {
		assert this.isActive();
		for (PotionEffect potionEffect : entity.getActivePotionEffects()) {
			entity.removePotionEffect(potionEffect.getType());
		}
	}

	public void teleportBack() {
		E entity = this.getEntity(); // Null if not active
		if (entity == null) return;

		Location spawnLocation = this.getSpawnLocation();
		assert spawnLocation != null; // Since entity is active
		Location entityLoc = entity.getLocation();
		spawnLocation.setYaw(entityLoc.getYaw());
		spawnLocation.setPitch(entityLoc.getPitch());
		entity.teleport(spawnLocation);
	}

	// NAMING

	@Override
	public void setName(String name) {
		if (!this.isActive()) return;
		this.applyName(entity, name);
	}

	protected void applyName(E entity, String name) {
		if (Settings.showNameplates && name != null && !name.isEmpty()) {
			name = Messages.nameplatePrefix + name;
			name = this.prepareName(name);
			// Set entity name plate:
			entity.setCustomName(name);
			entity.setCustomNameVisible(Settings.alwaysShowNameplates);
		} else {
			// Remove name plate:
			entity.setCustomName(null);
			entity.setCustomNameVisible(false);
		}
	}

	@Override
	public String getName() {
		if (!this.isActive()) return null;
		return entity.getCustomName();
	}

	// EDITOR ACTIONS

	// No default actions.
}
