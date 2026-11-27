package de.kalypzo.pantheon.smoke;

import java.util.Arrays;

/** A parsed {@code /pt} command line. Pure, so it can be tested without a server. */
public sealed interface PtCommand {

    record Coins() implements PtCommand {}

    /** {@code /pt coins add <n>}: unconditional {@code inc}, may be negative. */
    record CoinsAdd(long delta) implements PtCommand {}

    record Pay(String target, long amount) implements PtCommand {}

    record Msg(String target, String text) implements PtCommand {}

    record Where(String target) implements PtCommand {}

    record Buy(Kit kit) implements PtCommand {}

    /** {@code /pt audit <name> [hours]}: the timeline of a player as text lines. */
    record Audit(String target, int hours) implements PtCommand {}

    /** {@code /pt shop}: one trace with smoke:open, smoke:buy and smoke:close. */
    record Shop() implements PtCommand {}

    record UnknownKit(String name) implements PtCommand {}

    record Usage() implements PtCommand {}

    int DEFAULT_HOURS = 24;
    /** The audit log keeps 14 days. */
    int MAX_HOURS = 14 * 24;

    String DEFAULT_MSG = "Ping!";

    static PtCommand parse(String[] args) {
        if (args.length == 0) return new Usage();
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        return switch (sub) {
            case "coins" -> {
                if (args.length == 1) yield new Coins();
                if (args.length == 3 && args[1].equalsIgnoreCase("add")) {
                    Long n = number(args[2]);
                    yield n == null ? new Usage() : new CoinsAdd(n);
                }
                yield new Usage();
            }
            case "pay" -> {
                if (args.length != 3) yield new Usage();
                Long n = number(args[2]);
                yield n == null || n <= 0 ? new Usage() : new Pay(args[1], n);
            }
            case "msg" -> {
                if (args.length < 2) yield new Usage();
                String text = args.length == 2 ? DEFAULT_MSG
                        : String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                yield new Msg(args[1], text);
            }
            case "where" -> args.length == 2 ? new Where(args[1]) : new Usage();
            case "audit" -> {
                if (args.length < 2 || args.length > 3) yield new Usage();
                if (args.length == 2) yield new Audit(args[1], DEFAULT_HOURS);
                Long h = number(args[2]);
                yield h == null || h <= 0 || h > MAX_HOURS ? new Usage() : new Audit(args[1], h.intValue());
            }
            case "shop" -> args.length == 1 ? new Shop() : new Usage();
            case "buy" -> {
                if (args.length != 2) yield new Usage();
                yield Kit.byName(args[1]).<PtCommand>map(Buy::new).orElse(new UnknownKit(args[1]));
            }
            default -> new Usage();
        };
    }

    private static Long number(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
