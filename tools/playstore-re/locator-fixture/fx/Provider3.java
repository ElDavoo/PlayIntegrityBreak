package fx;

/** A provider of a merged class: the component is just an Object, and a second int follows the id. */
public final class Provider3 {
    private final Object component;
    private final int id;
    private final int variant;

    public Provider3(Object component, int id, int variant) {
        this.component = component;
        this.id = id;
        this.variant = variant;
    }

    public Object get() {
        switch (id) {
            case 4:
                return new Object();
            case 5:
                return new ColdFactory((Component) component, "merged", variant);
            default:
                throw new AssertionError(id);
        }
    }
}
