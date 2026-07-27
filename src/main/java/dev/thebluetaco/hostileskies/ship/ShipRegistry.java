package dev.thebluetaco.hostileskies.ship;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.thebluetaco.hostileskies.HostileSkies;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads ship templates from JSON files in data/hostile_skies/ships/
 * To add a new ship, drop the .json + .nbt in their places and add the register() call below.
 */
public class ShipRegistry {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Map<String, ShipTemplate> ships = new LinkedHashMap<>();
    private static final Map<Integer, List<ShipTemplate>> byTier = new HashMap<>();

    /** Called once at mod init. Register every ship ID here. */
    public static void init() {
        register("karve_t1");
        register("outrider_t1");
        register("lookout_t1");
        register("hirdskip_t2");
    }

    /** Loads a ship template from /data/hostile_skies/ships/{id}.json */
    private static void register(String id) {
        String path = "/data/" + HostileSkies.MODID + "/ships/" + id + ".json";
        try (InputStream stream = ShipRegistry.class.getResourceAsStream(path)) {
            if (stream == null) {
                HostileSkies.LOGGER.error("Ship config not found: {}", path);
                return;
            }
            ShipTemplate template = GSON.fromJson(
                    new InputStreamReader(stream, StandardCharsets.UTF_8),
                    ShipTemplate.class);
            template.id = id;

            ships.put(id, template);
            byTier.computeIfAbsent(template.tier, k -> new ArrayList<>()).add(template);

            HostileSkies.LOGGER.info("Registered ship '{}': {}", id, template);
        } catch (Exception e) {
            HostileSkies.LOGGER.error("Failed to load ship config '{}'", id, e);
        }
    }

    public static ShipTemplate get(String id) {
        return ships.get(id);
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
    public static Collection<String> getAllIds() {
        return ships.keySet();
    }

    public static boolean isEmpty() {
        return ships.isEmpty();
    }
}
