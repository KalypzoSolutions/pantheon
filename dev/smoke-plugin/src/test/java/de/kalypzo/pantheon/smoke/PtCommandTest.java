package de.kalypzo.pantheon.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PtCommandTest {

    private static PtCommand parse(String line) {
        return PtCommand.parse(line.isEmpty() ? new String[0] : line.split(" "));
    }

    @Test
    void coinsShowsBalance() {
        assertThat(parse("coins")).isEqualTo(new PtCommand.Coins());
    }

    @Test
    void coinsAddIncrements() {
        assertThat(parse("coins add 100")).isEqualTo(new PtCommand.CoinsAdd(100));
        assertThat(parse("coins add -30")).isEqualTo(new PtCommand.CoinsAdd(-30));
    }

    @Test
    void payNeedsNameAndPositiveAmount() {
        assertThat(parse("pay Notch 25")).isEqualTo(new PtCommand.Pay("Notch", 25));
        assertThat(parse("pay Notch 0")).isInstanceOf(PtCommand.Usage.class);
        assertThat(parse("pay Notch -1")).isInstanceOf(PtCommand.Usage.class);
        assertThat(parse("pay Notch abc")).isInstanceOf(PtCommand.Usage.class);
        assertThat(parse("pay Notch")).isInstanceOf(PtCommand.Usage.class);
    }

    @Test
    void msgJoinsTheRestAsText() {
        assertThat(parse("msg Notch hallo du da")).isEqualTo(new PtCommand.Msg("Notch", "hallo du da"));
        assertThat(parse("msg Notch")).isEqualTo(new PtCommand.Msg("Notch", "Ping!"));
        assertThat(parse("msg")).isInstanceOf(PtCommand.Usage.class);
    }

    @Test
    void whereNeedsName() {
        assertThat(parse("where Notch")).isEqualTo(new PtCommand.Where("Notch"));
        assertThat(parse("where")).isInstanceOf(PtCommand.Usage.class);
    }

    @Test
    void buyKnowsKitsCaseInsensitive() {
        assertThat(parse("buy starter")).isEqualTo(new PtCommand.Buy(Kit.STARTER));
        assertThat(parse("buy VIP")).isEqualTo(new PtCommand.Buy(Kit.VIP));
        assertThat(parse("buy nothing")).isEqualTo(new PtCommand.UnknownKit("nothing"));
        assertThat(parse("buy")).isInstanceOf(PtCommand.Usage.class);
    }

    @Test
    void unknownOrEmptyIsUsage() {
        assertThat(parse("")).isInstanceOf(PtCommand.Usage.class);
        assertThat(parse("fly")).isInstanceOf(PtCommand.Usage.class);
        assertThat(parse("coins steal 5")).isInstanceOf(PtCommand.Usage.class);
    }

    @Test
    void kitPrices() {
        assertThat(Kit.STARTER.price()).isEqualTo(50L);
        assertThat(Kit.VIP.price()).isEqualTo(200L);
        assertThat(Kit.byName("starter")).contains(Kit.STARTER);
        assertThat(Kit.byName("x")).isEmpty();
    }

    @Test
    void auditNeedsNameAndOptionalPositiveHours() {
        assertThat(parse("audit Notch")).isEqualTo(new PtCommand.Audit("Notch", 24));
        assertThat(parse("audit Notch 6")).isEqualTo(new PtCommand.Audit("Notch", 6));
        assertThat(parse("audit Notch 0")).isInstanceOf(PtCommand.Usage.class);
        assertThat(parse("audit Notch -2")).isInstanceOf(PtCommand.Usage.class);
        assertThat(parse("audit Notch abc")).isInstanceOf(PtCommand.Usage.class);
        assertThat(parse("audit Notch 1 2")).isInstanceOf(PtCommand.Usage.class);
        assertThat(parse("audit")).isInstanceOf(PtCommand.Usage.class);
    }

    @Test
    void shopTakesNoArguments() {
        assertThat(parse("shop")).isEqualTo(new PtCommand.Shop());
        assertThat(parse("shop now")).isInstanceOf(PtCommand.Usage.class);
    }

    @Test
    void tpNeedsServerWorldAndThreeNumbers() {
        assertThat(parse("tp lobby-02 world 1.5 64 -3")).isEqualTo(new PtCommand.Tp("lobby-02", "world", 1.5, 64, -3));
        assertThat(parse("tp lobby-02 world x 64 0")).isInstanceOf(PtCommand.Usage.class);
        assertThat(parse("tp lobby-02 world 1 2")).isInstanceOf(PtCommand.Usage.class);
        assertThat(parse("tp lobby-02 world NaN 64 0")).isInstanceOf(PtCommand.Usage.class);
    }

    @Test
    void connectNeedsServer() {
        assertThat(parse("connect lobby-01")).isEqualTo(new PtCommand.Connect("lobby-01"));
        assertThat(parse("connect")).isInstanceOf(PtCommand.Usage.class);
    }
}
