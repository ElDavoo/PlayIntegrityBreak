package fx;

public final class ApiImpl implements Api {
    @Override
    public void send(Body body, Ok ok, Err err) {
        ok.onResponse(Endpoints.VIA_INTERFACE.toString());
    }
}
