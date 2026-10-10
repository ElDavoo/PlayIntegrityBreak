package fx;

import android.net.Uri;

/** Like the Play Store's class of endpoint URIs: each path is parsed into a static field in the class initializer. */
public final class Endpoints {
    public static final Uri DECODE = Uri.parse("decodeintegritytoken");
    public static final Uri AMBIGUOUS = Uri.parse("fixtureambiguous");
    public static final Uri VIA_INTERFACE = Uri.parse("fixtureviainterface");
    public static final Uri UNUSED = Uri.parse("fixtureunused");
    public static final Uri COLD = Uri.parse("fixturecold");

    private Endpoints() {}
}
