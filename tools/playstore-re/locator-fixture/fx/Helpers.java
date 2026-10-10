package fx;

/** Like a Dagger module's provides method: a static helper that the provider's case calls. */
public final class Helpers {
    private Helpers() {}

    public static ColdFactory make(Component component) {
        return new ColdFactory(component, "made", 1);
    }
}
