package fx;

/**
 * The factory is made in code after the switch that two cases share. Case 22 is laid out between them and that code, so
 * "the case that starts last before the call" would be 22, which makes something else.
 */
public final class Provider4 {
    private final Component component;
    private final int id;

    public Provider4(Component component, int id) {
        this.component = component;
        this.id = id;
    }

    public Object get() {
        String name;
        switch (id) {
            case 20:
                name = "first";
                break;
            case 21:
                name = "second";
                break;
            case 22:
                return new Object();
            default:
                throw new AssertionError(id);
        }
        return new ColdFactory(component, name, 1);
    }
}
