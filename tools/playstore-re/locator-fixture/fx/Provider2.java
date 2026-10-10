package fx;

/** Another provider: dense keys, and its case calls the static helper. */
public final class Provider2 {
    private final Component component;
    private final int id;

    public Provider2(Component component, int id) {
        this.component = component;
        this.id = id;
    }

    public Object get() {
        switch (id) {
            case 7:
                return new Object();
            case 8:
                return "eight";
            case 9:
                return Helpers.make(component);
            default:
                throw new AssertionError(id);
        }
    }
}
