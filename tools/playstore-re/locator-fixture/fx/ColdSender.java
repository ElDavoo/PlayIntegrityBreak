package fx;

public final class ColdSender {
    public void decode(Body body, Ok ok, Err err) {
        ok.onResponse(Endpoints.COLD.toString());
    }
}
