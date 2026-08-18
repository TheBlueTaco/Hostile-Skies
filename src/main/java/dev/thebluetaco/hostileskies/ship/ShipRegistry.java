package dev.thebluetaco.hostileskies.ship;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import dev.thebluetaco.hostileskies.HostileSkies;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.util.*;

/**
 * Loads ship templates from data/<namespace>/hostile_skies/ships/*.json.
 * Any datapack (or mod) can add ships, and /reload picks up changes.
 * Registered as a reload listener in HostileSkies#onAddReloadListeners.
 *
 * Load pipeline: parse and validate each file, drop ships whose requiredMods
 * are missing, then let surviving ships suppress the ships they replace.
 */
public class ShipRegistry extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new GsonBuilder().create();
    public static final String DIRECTORY = HostileSkies.MODID + "/ships";

    private static Map<ResourceLocation, ShipTemplate> ships = new LinkedHashMap<>();
    private static Map<Integer, List<ShipTemplate>> byTier = new HashMap<>();
    private static Map<ResourceLocation, ResourceLocation> suppressed = new LinkedHashMap<>();

    public ShipRegistry() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files,
                         ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, ShipTemplate> loaded = new LinkedHashMap<>();

        files.forEach((id, json) -> {
            try {
                ShipTemplate template = GSON.fromJson(json, ShipTemplate.class);
                template.id = id.toString();

                if (template.tier < 1 || template.tier > 4) {
                    HostileSkies.LOGGER.error("Ship '{}' has invalid tier {} (must be 1-4), skipping", id, template.tier);
                    return;
                }
                if (template.structure == null || template.structure.isEmpty()) {
                    HostileSkies.LOGGER.error("Ship '{}' has no structure field, skipping", id);
                    return;
                }

                if (template.dimensions == null || template.dimensions.isEmpty()) {
                    HostileSkies.LOGGER.warn("Ship '{}' has an empty dimensions list and will never spawn naturally", id);
                    template.dimensions = List.of();
                }
                for (String d : template.dimensions) {
                    if (!d.equals("*") && ResourceLocation.tryParse(d) == null) {
                        HostileSkies.LOGGER.error("Ship '{}' has invalid dimension id '{}', skipping", id, d);
                        return;
                    }
                }

                if (!validCrew(id, template.crew)) return;

                List<String> missing = missingMods(template);
                if (!missing.isEmpty()) {
                    HostileSkies.LOGGER.info("Ship '{}' requires missing mod(s) {}, skipping", id, missing);
                    return;
                }

                loaded.put(id, template);
            } catch (Exception e) {
                HostileSkies.LOGGER.error("Failed to parse ship config '{}'", id, e);
            }
        });

        // Any loaded ship removes the ship it replaces
        Map<ResourceLocation, ResourceLocation> removed = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, ShipTemplate> entry : loaded.entrySet()) {
            ResourceLocation target = entry.getValue().getReplacesId();
            if (target == null) continue;
            if (target.equals(entry.getKey())) {
                HostileSkies.LOGGER.warn("Ship '{}' replaces itself, ignoring", entry.getKey());
                continue;
            }
            if (!loaded.containsKey(target)) {
                HostileSkies.LOGGER.warn("Ship '{}' replaces '{}', which is not loaded", entry.getKey(), target);
                continue;
            }
            removed.putIfAbsent(target, entry.getKey());
        }
        removed.forEach((target, by) -> {
            loaded.remove(target);
            HostileSkies.LOGGER.info("Ship '{}' suppressed by '{}'", target, by);
        });

        Map<Integer, List<ShipTemplate>> tiers = new HashMap<>();
        loaded.forEach((id, template) -> {
            tiers.computeIfAbsent(template.tier, k -> new ArrayList<>()).add(template);
            HostileSkies.LOGGER.info("Registered ship '{}': {}", id, template);
        });

        ships = loaded;
        byTier = tiers;
        suppressed = removed;
        HostileSkies.LOGGER.info("Loaded {} ship template(s), {} suppressed", ships.size(), suppressed.size());
    }

    private static List<String> missingMods(ShipTemplate template) {
        if (template.requiredMods == null || template.requiredMods.isEmpty()) return List.of();
        List<String> missing = new ArrayList<>();
        for (String modId : template.requiredMods) {
            if (!ModList.get().isLoaded(modId)) missing.add(modId);
        }
        return missing;
    }

    public static ShipTemplate get(ResourceLocation id) {
        ShipTemplate template = ships.get(id);
        // Bare ids parse into the minecraft namespace, so fall back to Hostile Skies.
        if (template == null && id.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)) {
            template = ships.get(ResourceLocation.fromNamespaceAndPath(HostileSkies.MODID, id.getPath()));
        }
        return template;
    }

    public static ShipTemplate get(String id) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        return parsed == null ? null : get(parsed);
    }

    public static List<ShipTemplate> getForTier(int tier) {
        return byTier.getOrDefault(tier, List.of());
    }

    /** Randomized pick from ship list. Returns null if no ships exist for that tier. */
    public static ShipTemplate getRandomForTier(int tier, net.minecraft.util.RandomSource random) {
        List<ShipTemplate> candidates = getForTier(tier);
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    /** Ships for this tier that may spawn naturally in the given dimension. */
    public static List<ShipTemplate> getForTier(int tier, ResourceKey<Level> dimension) {
        List<ShipTemplate> out = new ArrayList<>();
        for (ShipTemplate ship : getForTier(tier)) {
            if (ship.allowsDimension(dimension)) out.add(ship);
        }
        return out;
    }

    public static ShipTemplate getRandomForTier(int tier, ResourceKey<Level> dimension,
                                                net.minecraft.util.RandomSource random) {
        List<ShipTemplate> candidates = getForTier(tier, dimension);
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    /** All registered ship IDs, for command tab completion. */
    public static Collection<ResourceLocation> getAllIds() {
        return ships.keySet();
    }

    /** Ships suppressed via `replaces` this reload, mapped to the ship that replaced them. */
    public static Map<ResourceLocation, ResourceLocation> getSuppressed() {
        return Collections.unmodifiableMap(suppressed);
    }

    public static boolean isEmpty() {
        return ships.isEmpty();
    }

    private static boolean validCrew(ResourceLocation id, ShipTemplate.Crew crew) {
        if (entityType(crew.captainMob) == null) {
            HostileSkies.LOGGER.error("Ship '{}' has unknown captainMob '{}', skipping", id, crew.captainMob);
            return false;
        }
        for (ShipTemplate.CrewEntry entry : crew.mobs) {
            if (entityType(entry.mob) == null) {
                HostileSkies.LOGGER.error("Ship '{}' has unknown crew mob '{}', skipping", id, entry.mob);
                return false;
            }
            if (entry.count < 1) {
                HostileSkies.LOGGER.error("Ship '{}' crew mob '{}' has count {} (must be >= 1), skipping",
                        id, entry.mob, entry.count);
                return false;
            }
        }
        return true;
    }

    private static EntityType<?> entityType(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) return null;
        return BuiltInRegistries.ENTITY_TYPE.get(rl);
    }
}
