package de.kalypzo.pantheon.smoke;

import java.util.Locale;
import java.util.Optional;
import org.bukkit.Material;

/** The kits of {@code /pt buy}: a price in coins and what the player gets. */
public enum Kit {
    STARTER(50, Material.BREAD, 16),
    VIP(200, Material.DIAMOND, 4);

    private final long price;
    private final Material item;
    private final int amount;

    Kit(long price, Material item, int amount) {
        this.price = price;
        this.item = item;
        this.amount = amount;
    }

    public long price() {
        return price;
    }

    public Material item() {
        return item;
    }

    public int amount() {
        return amount;
    }

    /** The name players type, in lower case. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Kit> byName(String name) {
        for (Kit k : values()) {
            if (k.id().equalsIgnoreCase(name)) return Optional.of(k);
        }
        return Optional.empty();
    }
}
