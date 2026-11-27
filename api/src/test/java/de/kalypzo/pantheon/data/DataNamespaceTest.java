package de.kalypzo.pantheon.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"unchecked", "rawtypes"})
class DataNamespaceTest {

    record Home(String world, double x) {}

    enum Color { RED, BLUE }

    /** Defines the nested test classes anew, like a reloaded plugin's ClassLoader. */
    static final class ReloadLoader extends ClassLoader {
        ReloadLoader() {
            super(DataNamespaceTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(DataNamespaceTest.class.getName() + "$")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try (var in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        byte[] b = in.readAllBytes();
                        c = defineClass(name, b, 0, b.length);
                    } catch (java.io.IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                return c;
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void redeclarationFromReloadedClassLoaderIsAllowed() throws Exception {
        DataNamespace ns = DataNamespace.of("reload");
        ns.of("home", Home.class);
        ns.ofEnum("color", Color.class, Color.RED);
        ns.ofSet("colors", Color.class);
        ClassLoader second = new ReloadLoader();
        Class<?> home2 = second.loadClass(Home.class.getName());
        Class<?> color2 = second.loadClass(Color.class.getName());
        assertThat(home2).isNotSameAs(Home.class);
        Field<?> h = ns.of("home", (Class) home2);
        Field<?> c = ns.ofEnum("color", (Class) color2, (Enum) color2.getEnumConstants()[0]);
        Field<?> s = ns.ofSet("colors", (Class) color2);
        assertThat(h.valueClass()).isSameAs(home2);
        assertThat(c.valueClass()).isSameAs(color2);
        assertThat(s.elementClass()).isSameAs(color2);
        // a different default still conflicts
        assertThatThrownBy(() -> ns.ofEnum("color", (Class) color2, (Enum) color2.getEnumConstants()[1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUppercase() {
        assertThatThrownBy(() -> DataNamespace.of("Economy")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataNamespace.of("nsupper").ofInt("Level", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isInterned() {
        assertThat(DataNamespace.of("interned")).isSameAs(DataNamespace.of("interned"));
    }

    @Test
    void identicalRedeclarationIsAllowed() {
        DataNamespace ns = DataNamespace.of("redecl");
        Field<Long> a = ns.ofLong("coins", 0L);
        Field<Long> b = ns.ofLong("coins", 0L);
        assertThat(b).isEqualTo(a);
        assertThat(a.type()).isEqualTo(FieldType.LONG);
        assertThat(a.valueClass()).isEqualTo(Long.class);
    }

    @Test
    void conflictingRedeclarationFails() {
        DataNamespace ns = DataNamespace.of("conflict");
        ns.ofLong("coins", 0L);
        assertThatThrownBy(() -> ns.ofInt("coins", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ns.ofLong("coins", 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setRejectsUnsupportedElementType() {
        DataNamespace ns = DataNamespace.of("elements");
        assertThatThrownBy(() -> ns.ofSet("bad", Double.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ns.ofList("bad2", Home.class)).isInstanceOf(IllegalArgumentException.class);
        Field<Set<String>> ok = ns.ofSet("ok", String.class);
        assertThat(ok.defaultValue()).isEmpty();
        assertThat(ok.elementClass()).isEqualTo(String.class);
        Field<List<Integer>> list = ns.ofList("okl", Integer.class);
        assertThatThrownBy(() -> list.defaultValue().add(1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ofRejectsNonRecord() {
        DataNamespace ns = DataNamespace.of("objects");
        Class nonRecord = String.class; // bypass the compile-time bound
        assertThatThrownBy(() -> ns.of("bad", nonRecord)).isInstanceOf(IllegalArgumentException.class);
        Field<Home> home = ns.of("home", Home.class);
        assertThat(home.type()).isEqualTo(FieldType.OBJECT);
        assertThat(home.defaultValue()).isNull();
    }

    @Test
    void readFromKeepsDefaultAndAddsAlias() {
        DataNamespace ns = DataNamespace.of("rename");
        Field<Long> coins = ns.ofLong("coins", 5L).readFrom("money");
        assertThat(coins.defaultValue()).isEqualTo(5L);
        assertThat(coins.aliases()).containsExactly("money");
        assertThat(coins.name()).isEqualTo("coins");
    }
}
