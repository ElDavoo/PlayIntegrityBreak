package fx;

/** Like a Dagger switching provider: (component, id), and a switch on id. This one has sparse keys. */
public final class Provider1 {
    private final Component component;
    private final int id;

    public Provider1(Component component, int id) {
        this.component = component;
        this.id = id;
    }

    public Object get() {
        switch (id) {
            case 3:
                return new Object();
            case 1148:
                return new ColdFactory(component, "direct", 1);
            case 1149:
                return new ColdFactory(new Object(), new Object());
            default:
                throw new AssertionError(id);
        }
    }
}
