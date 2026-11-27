package de.kalypzo.pantheon.smoke;

import static de.kalypzo.pantheon.language.Arg.arg;

import de.kalypzo.pantheon.Pantheon;
import de.kalypzo.pantheon.audit.AuditNamespace;
import de.kalypzo.pantheon.audit.AuditTrace;
import de.kalypzo.pantheon.audit.TimelineEntry;
import de.kalypzo.pantheon.audit.TimelinePage;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.language.CommonMessage;
import de.kalypzo.pantheon.player.LocalPanPlayer;
import de.kalypzo.pantheon.player.PanPlayer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * P-28: dev-only plugin for the manual smoke test of {@code dev/docker-compose.yml}. Exercises the Pantheon API with
 * {@code /pt coins [add <n>]}, {@code /pt pay}, {@code /pt msg}, {@code /pt where} and {@code /pt buy}.
 */
public final class SmokePlugin extends JavaPlugin implements TabExecutor {

    /** Pantheon names the language files after the plugin name in lower case. */
    static final String LANGUAGE_NAME = "pantheonsmoke";

    static final DataNamespace SMOKE = DataNamespace.of("smoke");
    static final Field<Long> COINS = SMOKE.ofLong("coins", 0L);
    static final Field<Set<String>> KITS = SMOKE.ofSet("kits", String.class);

    static final AuditNamespace SMOKE_AUDIT = AuditNamespace.of("smoke");

    /** Pages fetched at most, and lines shown at most (newest), so the chat stays readable. */
    static final int MAX_PAGES = 4;
    static final int MAX_LINES = 40;

    private Pantheon pantheon;

    @Override
    public void onEnable() {
        pantheon = Pantheon.get(this);
        // runs the P-19 check: logs the intentionally missing smoke.untranslated of pantheonsmoke_en_US.yml
        pantheon.config().language()
                .prefix("<highlight>Smoke</highlight><dark_gray> »</dark_gray>")
                .register(SmokeMessage.class);
        PluginCommand command = getCommand("pt");
        if (command == null) throw new IllegalStateException("command 'pt' missing in plugin.yml");
        command.setExecutor(this);
        command.setTabCompleter(this);
        getLogger().info("Smoke plugin ready: /pt coins | pay | msg | where | buy | audit | shop");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        PtCommand parsed = PtCommand.parse(args);
        if (parsed instanceof PtCommand.Audit a) { // console-safe: the viewer is the console (nil UUID)
            audit(sender, a);
            return true;
        }
        if (!(sender instanceof Player bukkit)) {
            pantheon.language().send(sender, CommonMessage.PLAYERS_ONLY);
            return true;
        }
        LocalPanPlayer me = pantheon.getPlayer(bukkit);
        switch (parsed) {
            case PtCommand.Coins c -> me.sendMessage(SmokeMessage.COINS, arg("coins", me.data().get(COINS)),
                    arg("kits", String.join(", ", new TreeSet<>(me.data().get(KITS)))));
            case PtCommand.CoinsAdd a -> {
                me.data().inc(COINS, a.delta()); // unconditional: applied locally at once, outbox if Agora is down
                me.sendMessage(SmokeMessage.COINS_ADDED, arg("amount", a.delta()), arg("coins", me.data().get(COINS)));
            }
            case PtCommand.Pay p -> pay(me, p);
            case PtCommand.Msg m -> withTarget(me, m.target(), target -> {
                target.sendMessage(SmokeMessage.MSG_RECEIVED, arg("player", me.name()), arg("body", m.text()));
                me.sendMessage(SmokeMessage.MSG_SENT, arg("player", target.name()),
                        arg("server", target.server().orElse("?")), arg("body", m.text()));
            });
            case PtCommand.Where w -> withTarget(me, w.target(), target -> target.location().whenComplete((loc, e) -> {
                if (e != null) {
                    failed(me, e);
                    return;
                }
                me.sendMessage(SmokeMessage.WHERE, arg("player", target.name()), arg("server", loc.serverName()),
                        arg("world", loc.worldName()), arg("x", fmt(loc.x())), arg("y", fmt(loc.y())),
                        arg("z", fmt(loc.z())));
            }));
            case PtCommand.Buy b -> buy(me, b.kit());
            case PtCommand.Audit a -> { } // handled above
            case PtCommand.Shop s -> shop(me);
            case PtCommand.UnknownKit u -> me.sendMessage(SmokeMessage.UNKNOWN_KIT, arg("kit", u.name()),
                    arg("kits", String.join(", ", List.of(Kit.values()).stream().map(Kit::id).toList())));
            case PtCommand.Usage u -> me.sendMessage(SmokeMessage.USAGE);
        }
        return true;
    }

    private void pay(LocalPanPlayer me, PtCommand.Pay p) {
        withTarget(me, p.target(), target -> pantheon.players()
                .transfer(COINS, me.uniqueId(), target.uniqueId(), p.amount())
                .whenComplete((ok, e) -> {
                    if (e != null) {
                        failed(me, e);
                    } else if (ok) {
                        me.sendMessage(SmokeMessage.PAY_SENT, arg("player", target.name()), arg("amount", p.amount()));
                        target.sendMessage(SmokeMessage.PAY_RECEIVED, arg("player", me.name()),
                                arg("amount", p.amount()));
                    } else {
                        me.sendMessage(SmokeMessage.PAY_FAILED, arg("amount", p.amount()));
                    }
                }));
    }

    /** One trace with smoke:open, smoke:buy and smoke:close (all three carry the same trace id). */
    private void shop(LocalPanPlayer me) {
        AuditTrace trace = me.audit().begin(SMOKE_AUDIT.action("open"), d -> d.with("shop", "smoke"));
        me.audit().action(SMOKE_AUDIT.action("buy"), d -> d.with("item", "diamond_sword").with("price", 50L));
        trace.end(SMOKE_AUDIT.action("close"), d -> d.with("shop", "smoke"));
        me.sendMessage(Component.text("Trace " + trace.id() + ": smoke:open, smoke:buy, smoke:close geschrieben."));
    }

    private void audit(CommandSender sender, PtCommand.Audit a) {
        Optional<PanPlayer> online = pantheon.players().online(a.target());
        UUID target = online.map(PanPlayer::uniqueId)
                .orElseGet(() -> getServer().getOfflinePlayer(a.target()).getUniqueId());
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(a.hours()));
        fetchAll(target, sender, from, to, null, new ArrayList<>(), 0).whenComplete((entries, e) -> {
            if (e != null) {
                getLogger().warning("/pt audit failed: " + e);
                sender.sendMessage(Component.text("Fehlgeschlagen: " + e.getMessage()));
                return;
            }
            // pages are newest first; show the newest MAX_LINES in chronological order
            List<TimelineEntry> shown = new ArrayList<>(entries.subList(0, Math.min(entries.size(), MAX_LINES)));
            Collections.reverse(shown);
            sender.sendMessage(Component.text("Audit " + a.target() + " (" + target + "), letzte " + a.hours()
                    + " h: " + entries.size() + " Eintraege"
                    + (entries.size() > shown.size() ? ", die neuesten " + shown.size() : "")));
            AuditLines.format(shown, ZoneId.systemDefault()).forEach(l -> sender.sendMessage(Component.text(l)));
        });
    }

    /** A full page carries a cursor, but the next page may be empty: stop on no cursor, an empty page or the limit. */
    private CompletableFuture<List<TimelineEntry>> fetchAll(UUID target, CommandSender viewer, Instant from, Instant to,
            UUID cursor, List<TimelineEntry> acc, int page) {
        var query = pantheon.audit().timeline(target).viewer(viewer).between(from, to);
        if (cursor != null) query = query.after(cursor);
        return query.fetch().thenCompose((TimelinePage p) -> {
            acc.addAll(p.entries());
            if (p.cursor().isEmpty() || p.entries().isEmpty() || page + 1 >= MAX_PAGES) {
                return CompletableFuture.completedFuture(acc);
            }
            return fetchAll(target, viewer, from, to, p.cursor().get(), acc, page + 1);
        });
    }

    private void buy(LocalPanPlayer me, Kit kit) {
        // conditional: the database decides; without Agora the future fails and the player gets an error
        me.data().atomic().withdraw(COINS, kit.price()).add(KITS, kit.id()).commit()
                .whenCompleteAsync((ok, e) -> {
                    if (e != null) {
                        failed(me, e);
                    } else if (ok) {
                        me.local().ifPresent(local -> local.bukkit().getInventory()
                                .addItem(new ItemStack(kit.item(), kit.amount())));
                        me.sendMessage(SmokeMessage.BUY_DONE, arg("kit", kit.id()), arg("price", kit.price()),
                                arg("coins", me.data().get(COINS)));
                    } else {
                        me.sendMessage(SmokeMessage.BUY_TOO_POOR, arg("kit", kit.id()), arg("price", kit.price()),
                                arg("coins", me.data().get(COINS)));
                    }
                }, pantheon.mainThread());
    }

    private void withTarget(LocalPanPlayer me, String name, java.util.function.Consumer<PanPlayer> action) {
        Optional<PanPlayer> target = pantheon.players().online(name);
        if (target.isEmpty()) {
            me.sendMessage(CommonMessage.PLAYER_NOT_FOUND, arg("name", name));
            return;
        }
        action.accept(target.get());
    }

    private void failed(PanPlayer me, Throwable e) {
        Throwable cause = e;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        getLogger().warning("/pt failed for " + me.name() + ": " + cause);
        me.sendMessage(SmokeMessage.FAILED, arg("reason", cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage())));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("coins", "pay", "msg", "where", "buy", "audit", "shop"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "coins" -> options.add("add");
                case "buy" -> List.of(Kit.values()).forEach(k -> options.add(k.id()));
                case "pay", "msg", "where", "audit" -> pantheon.players().online().forEach(p -> options.add(p.name()));
                default -> { }
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
