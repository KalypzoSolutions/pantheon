package de.kalypzo.pantheon.player;

import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.Query;
import de.kalypzo.pantheon.data.RemoteDoc;
import de.kalypzo.pantheon.data.Subscription;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PlayerService extends PanPlayerFactory {

    RemoteDoc data(UUID player);

    CompletableFuture<Optional<Doc>> load(UUID player);

    Query query();

    /** Number of players with a larger value plus one. Equal values share a rank. */
    CompletableFuture<Long> rank(Field<? extends Number> field, UUID player);

    CompletableFuture<Boolean> transfer(Field<Long> field, UUID from, UUID to, long amount);

    CompletableFuture<Void> index(Field<?>... fields);

    /** Listeners run on the main thread. */
    <T> Subscription onChange(Field<T> field, PlayerFieldListener<T> listener);
}
