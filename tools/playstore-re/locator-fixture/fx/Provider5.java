package fx;

/** Only the default case makes the factory: there is no id to make it with. */
public final class Provider5 {
    private final Component component;
    private final int id;

    public Provider5(Component component, int id) {
        this.component = component;
        this.id = id;
    }

    public Object get() {
        switch (id) {
            case 40:
                return new Object();
            case 41:
                return "forty-one";
            default:
                return new ColdFactory(component, "default", 1);
        }
    }
}
