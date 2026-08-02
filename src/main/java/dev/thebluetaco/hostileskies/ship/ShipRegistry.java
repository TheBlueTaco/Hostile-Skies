package dev.thebluetaco.hostileskies.ship;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import dev.thebluetaco.hostileskies.HostileSkies;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.*;

/**
 * Loads ship templates from data/<namespace>/hostile_skies/ships/*.json.
 * Any datapack (or mod) can add ships, and /reload picks up changes.
 * Registered as a reload listener in HostileSkies#onAddReloadListeners.
 */
public class ShipRegistry extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new GsonBuilder().create();
    public static final String DIRECTORY = HostileSkies.MODID + "/ships";

    private static Map<ResourceLocation, ShipTemplate> ships = new LinkedHashMap<>();
    private static Map<Integer, List<ShipTemplate>> byTier = new HashMap<>();

    public ShipRegistry() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files,
                         ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, ShipTemplate> loaded = new LinkedHashMap<>();
        Map<Integer, List<ShipTemplate>> tiers = new HashMap<>();

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

                loaded.put(id, template);
                tiers.computeIfAbsent(template.tier, k -> new ArrayList<>()).add(template);
                HostileSkies.LOGGER.info("Registered ship '{}': {}", id, template);
            } catch (Exception e) {
                HostileSkies.LOGGER.error("Failed to parse ship config '{}'", id, e);
            }
        });

        ships = loaded;
        byTier = tiers;
        HostileSkies.LOGGER.info("Loaded {} ship template(s)", ships.size());
    }

    public static ShipTemplate get(ResourceLocation id) {
        ShipTemplate template = ships.get(id);
        // Bare ids parse into the minecraft namespace, so fall back to Hostile Skies.
        // Also covers save data from before ids were namespaced.
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

    /** All registered ship IDs, for command tab completion. */
    public static Collection<ResourceLocation> getAllIds() {
        return ships.keySet();
    }

    public static boolean isEmpty() {
        return ships.isEmpty();
    }
}
