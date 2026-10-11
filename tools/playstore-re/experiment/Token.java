package pibexp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.security.SecureRandom;
import java.util.ArrayList;

/**
 * The classic integrity token, requested from inside the Play Store process for the Play Store's own package
 * (com.android.vending), through its IntegrityService. Same Play Core protocol that PlayStoreIntegrityCheck uses.
 */
final class Token {
    private static final String TAG = "PIB-EXP";
    private static final String SERVICE = "com.google.android.play.core.integrity.protocol.IIntegrityService";
    private static final String CALLBACK = "com.google.android.play.core.integrity.protocol.IIntegrityServiceCallback";

    private Token() {
    }

    static void run(final Context app, final Object root, final ClassLoader cl) {
        final Binder callback = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code != 2) {
                    return super.onTransact(code, data, reply, flags);
                }
                data.enforceInterface(CALLBACK);
                Bundle bundle = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : new Bundle();
                String token = bundle.getString("token");
                Log.i(TAG, "TOKEN callback keys=" + bundle.keySet() + " error=" + bundle.getInt("error")
                        + " tokenLength=" + (token == null ? -1 : token.length()));
                if (token != null) {
                    Log.i(TAG, "TOKEN head=" + token.substring(0, Math.min(40, token.length())));
                    Decode.run(root, cl, token);
                }
                return true;
            }
        };
        callback.attachInterface(null, CALLBACK);

        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Parcel data = Parcel.obtain();
                try {
                    byte[] nonce = new byte[32];
                    new SecureRandom().nextBytes(nonce);
                    Bundle request = new Bundle();
                    request.putString("package.name", "com.android.vending");
                    request.putByteArray("nonce", nonce);
                    request.putInt("playcore.integrity.version.major", 1);
                    request.putInt("playcore.integrity.version.minor", 4);
                    request.putInt("playcore.integrity.version.patch", 0);
                    Bundle event = new Bundle();
                    event.putInt("event_type", 3);
                    event.putLong("event_timestamp", System.currentTimeMillis());
                    ArrayList<Bundle> events = new ArrayList<>();
                    events.add(event);
                    request.putParcelableArrayList("event_timestamps", events);

                    data.writeInterfaceToken(SERVICE);
                    data.writeInt(1);
                    request.writeToParcel(data, 0);
                    data.writeStrongBinder(callback);
                    service.transact(2, data, null, IBinder.FLAG_ONEWAY);
                    Log.i(TAG, "TOKEN request sent for com.android.vending");
                } catch (Throwable t) {
                    Log.i(TAG, "TOKEN request failed: " + t);
                } finally {
                    data.recycle();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, "TOKEN service disconnected");
            }
        };

        Intent intent = new Intent("com.google.android.play.core.integrityservice.BIND_INTEGRITY_SERVICE")
                .setPackage("com.android.vending");
        Log.i(TAG, "TOKEN bind=" + app.bindService(intent, connection, Context.BIND_AUTO_CREATE));
    }
}
