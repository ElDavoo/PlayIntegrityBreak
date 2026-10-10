package fx;

/** Like the Play Store's request sender for one account: a method per endpoint, each reading its endpoint's Uri. */
public final class Sender {
    public void decode(Body body, Ok ok, Err err) {
        send(Endpoints.DECODE.toString(), body, ok, err);
    }

    public void other(OtherBody body, Ok ok, Err err) {
        send(Endpoints.UNUSED.toString(), body, ok, err);
    }

    /** Reads the decode field too, but it has not the shape of a sender (it returns a value). */
    public String describe() {
        return Endpoints.DECODE.toString();
    }

    public void ambiguousOne(Body body, Ok ok, Err err) {
        send(Endpoints.AMBIGUOUS.toString(), body, ok, err);
    }

    public void ambiguousTwo(Body body, Ok ok, Err err) {
        send(Endpoints.AMBIGUOUS.toString(), body, ok, err);
    }

    private void send(String url, Object body, Ok ok, Err err) {
        ok.onResponse(url);
    }
}
