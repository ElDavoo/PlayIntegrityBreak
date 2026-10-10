package fx;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Like a factory class after R8 merged classes into it: it has constructors of different original classes, and only one
 * makes the map that the factory method reads.
 */
public final class ColdFactory {
    private final Object cache;
    private final Object other;

    /** Another class's constructor: it stores no map. */
    public ColdFactory(Object a, Object b) {
        this.other = a;
        this.cache = b;
    }

    /** The real one. */
    public ColdFactory(Component component, String name, int count) {
        this.other = component;
        this.cache = new ConcurrentHashMap<String, Object>();
    }

    public ColdSender senderFor(String account) {
        ConcurrentHashMap<String, Object> map = (ConcurrentHashMap<String, Object>) cache;
        Object known = map.get(String.valueOf(account));
        if (known == null) {
            known = new ColdSender();
            map.put(String.valueOf(account), known);
        }
        return (ColdSender) known;
    }
}
