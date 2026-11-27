package de.kalypzo.pantheon.audit;

import de.kalypzo.pantheon.player.PanPlayer;
import de.kalypzo.pantheon.world.NetworkLocation;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** Describes an audit entry. */
public interface AuditDescriptor {

    /** The server is this node. */
    AuditDescriptor location(Location location);

    AuditDescriptor location(NetworkLocation location);

    AuditDescriptor target(UUID player);

    AuditDescriptor target(PanPlayer player);

    /** Overrides the active trace. */
    AuditDescriptor trace(AuditTrace trace);

    /**
     * Sets {@code location} to the entity's location and {@code entity_type}, {@code entity_uuid} and, if present,
     * {@code entity_name}. A player becomes the {@code target}.
     */
    AuditDescriptor entity(Entity entity);

    AuditDescriptor with(String key, String value);

    AuditDescriptor with(String key, long value);

    AuditDescriptor with(String key, double value);

    AuditDescriptor with(String key, boolean value);
}
