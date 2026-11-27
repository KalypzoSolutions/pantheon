package de.kalypzo.pantheon.paper.audit;

import de.kalypzo.pantheon.audit.AuditDescriptor;
import de.kalypzo.pantheon.audit.AuditTrace;
import de.kalypzo.pantheon.core.audit.EntryBuilder;
import de.kalypzo.pantheon.core.audit.Position;
import de.kalypzo.pantheon.player.PanPlayer;
import de.kalypzo.pantheon.world.NetworkLocation;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Fills the {@link EntryBuilder} of one entry from Bukkit types (audit §4). Keys and values are checked by the
 * builder (16 keys, {@code [a-z0-9_]+}, 512 characters). Of {@code target(...)} and a player's {@code entity(...)}
 * the last call wins (E32). Not thread-safe: it lives for one call of the plugin's descriptor.
 */
final class PaperAuditDescriptor implements AuditDescriptor {

    private final EntryBuilder entry;

    PaperAuditDescriptor(EntryBuilder entry) {
        this.entry = entry;
    }

    @Override
    public AuditDescriptor location(Location location) {
        Objects.requireNonNull(location, "location");
        World world = location.getWorld();
        if (world == null) throw new IllegalArgumentException("location without a world");
        entry.position(new Position(world.getName(), location.getX(), location.getY(), location.getZ()));
        return this;
    }

    @Override
    public AuditDescriptor location(NetworkLocation location) {
        Objects.requireNonNull(location, "location");
        entry.position(new Position(location.worldName(), location.x(), location.y(), location.z()));
        return this;
    }

    @Override
    public AuditDescriptor target(UUID player) {
        entry.target(Objects.requireNonNull(player, "player"));
        return this;
    }

    @Override
    public AuditDescriptor target(PanPlayer player) {
        return target(Objects.requireNonNull(player, "player").uniqueId());
    }

    @Override
    public AuditDescriptor trace(AuditTrace trace) {
        entry.trace(Objects.requireNonNull(trace, "trace").id());
        return this;
    }

    /**
     * The entity's location, {@code entity_type} ({@code getType().getKey().getKey()}), {@code entity_uuid} and, if
     * present, {@code entity_name}: a player's name, otherwise the custom name as plain text. A player becomes the
     * {@code target}.
     */
    @Override
    @SuppressWarnings("deprecation") // getKey() is what the card names; key() is the same value
    public AuditDescriptor entity(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        location(entity.getLocation());
        String type = entity.getType().getKey().getKey();
        entry.entity(type, entity.getUniqueId(), name(entity));
        if (entity instanceof Player player) entry.target(player.getUniqueId());
        return this;
    }

    private static @Nullable String name(Entity entity) {
        if (entity instanceof Player player) return player.getName();
        Component custom = entity.customName();
        return custom == null ? null : PlainTextComponentSerializer.plainText().serialize(custom);
    }

    @Override
    public AuditDescriptor with(String key, String value) {
        entry.with(key, Objects.requireNonNull(value, "value"));
        return this;
    }

    @Override
    public AuditDescriptor with(String key, long value) {
        entry.with(key, value);
        return this;
    }

    @Override
    public AuditDescriptor with(String key, double value) {
        entry.with(key, value);
        return this;
    }

    @Override
    public AuditDescriptor with(String key, boolean value) {
        entry.with(key, value);
        return this;
    }
}
