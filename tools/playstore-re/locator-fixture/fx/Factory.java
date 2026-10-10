package fx;

/** Like the Play Store's sender factory: gives the sender for an account name. */
public final class Factory {
    public Sender senderFor(String account) {
        return new Sender();
    }

    /** Takes a String, but returns something else. */
    public String normalize(String account) {
        return account;
    }

    public Api apiFor(String account) {
        return new ApiImpl();
    }
}
