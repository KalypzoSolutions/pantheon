package de.kalypzo.pantheon.core.data;

import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.data.Field;
import java.util.Optional;

/**
 * A held document (a loaded player, a document of a cached store): every read goes to {@link DocHandle#view}, so it
 * follows own writes at once, confirmed conditional requests and other nodes' changes.
 */
public final class LiveDoc extends RemoteDocImpl implements Doc {

    private final DocView view;

    public LiveDoc(String id, DocHandle handle, Target target) {
        super(id, target);
        this.view = new DocView(handle::view);
    }

    @Override
    public <T> T get(Field<T> field) {
        target().check(field);
        return view.get(field);
    }

    @Override
    public <T> Optional<T> find(Field<T> field) {
        target().check(field);
        return view.find(field);
    }

    @Override
    public boolean has(Field<?> field) {
        target().check(field);
        return view.has(field);
    }
}
