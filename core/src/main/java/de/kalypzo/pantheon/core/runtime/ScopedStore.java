package de.kalypzo.pantheon.core.runtime;

import de.kalypzo.pantheon.core.store.StoreRegistry;
import de.kalypzo.pantheon.data.CachedStore;
import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.FieldListener;
import de.kalypzo.pantheon.data.Query;
import de.kalypzo.pantheon.data.RemoteDoc;
import de.kalypzo.pantheon.data.Store;
import de.kalypzo.pantheon.data.Subscription;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** A store of the shared {@link StoreRegistry} as one plugin sees it: {@code onChange} and {@code cached()} are scoped. */
class ScopedStore implements Store {

    final PluginScope scope;
    final Store delegate;
    final StoreRegistry registry;

    ScopedStore(PluginScope scope, Store delegate, StoreRegistry registry) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public CompletableFuture<Optional<Doc>> load(String id) {
        return delegate.load(id);
    }

    @Override
    public CompletableFuture<Doc> loadOrCreate(String id) {
        return delegate.loadOrCreate(id);
    }

    @Override
    public CompletableFuture<Boolean> create(String id, Consumer<Doc> init) {
        return delegate.create(id, init);
    }

    @Override
    public RemoteDoc doc(String id) {
        return delegate.doc(id);
    }

    @Override
    public CompletableFuture<Boolean> delete(String id) {
        return delegate.delete(id);
    }

    @Override
    public void expire(String id, Instant at) {
        delegate.expire(id, at);
    }

    @Override
    public Query query() {
        return delegate.query();
    }

    @Override
    public CompletableFuture<Void> index(Field<?>... fields) {
        return delegate.index(fields);
    }

    @Override
    public CompletableFuture<Void> unique(Field<?> field) {
        return delegate.unique(field);
    }

    /** A new share of the cached store; a closed scope gets none (it would never be released). */
    @Override
    public CachedStore cached() {
        if (scope.isClosed()) throw new IllegalStateException("The plugin of this Pantheon instance is disabled");
        CachedStore cached = delegate.cached(); // may block up to 10 s
        scope.trackCached(cached, registry);    // on a scope closed meanwhile: released at once
        if (scope.isClosed()) throw new IllegalStateException("The plugin of this Pantheon instance is disabled");
        return new Cached(scope, cached, registry);
    }

    @Override
    public <T> Subscription onChange(Field<T> field, FieldListener<T> listener) {
        if (scope.isClosed()) return () -> {};
        return scope.track(delegate.onChange(field, scope.guard(listener)));
    }

    @Override
    public CompletableFuture<Boolean> transfer(Field<Long> field, String fromId, String toId, long amount) {
        return delegate.transfer(field, fromId, toId, amount);
    }

    /** The scoped view of a cached store. */
    static final class Cached extends ScopedStore implements CachedStore {

        private final CachedStore cached;

        Cached(PluginScope scope, CachedStore cached, StoreRegistry registry) {
            super(scope, cached, registry);
            this.cached = cached;
        }

        /** Already cached: no new share (the core returns the same cache without counting it). */
        @Override
        public CachedStore cached() {
            return this;
        }

        @Override
        public Optional<Doc> get(String id) {
            return cached.get(id);
        }

        @Override
        public Collection<Doc> all() {
            return cached.all();
        }
    }
}
